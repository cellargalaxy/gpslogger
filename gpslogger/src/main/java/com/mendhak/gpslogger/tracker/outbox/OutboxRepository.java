/*
 * Travel/hiking 改造：Custom URL 待发送队列对外门面。
 *
 * 设计要点：
 * - 所有「记录-入库-取出-发送」状态由本仓库管理；WorkManager 只承担「网络可用时调度 Worker」。
 * - 入队 vs 发送解耦：网络断开时点也能稳妥入库，恢复联网后 Worker 自然消费。
 * - 重试上限独立于 WorkManager 自带的 3 次，可由用户配置（默认 16）。
 * - 容量上限：超过 maxRows 时按 recorded_at 升序裁掉最老的 PENDING 行。
 */
package com.mendhak.gpslogger.tracker.outbox;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.mendhak.gpslogger.common.AppSettings;
import com.mendhak.gpslogger.common.PreferenceHelper;
import com.mendhak.gpslogger.common.slf4j.Logs;
import com.mendhak.gpslogger.tracker.TrackerPreferenceHelper;
import com.mendhak.gpslogger.tracker.db.OutboxDao;
import com.mendhak.gpslogger.tracker.db.OutboxEntry;

import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class OutboxRepository {

    private static final Logger LOG = Logs.of(OutboxRepository.class);
    private static final OutboxDao DAO = new OutboxDao();

    private static final String WORK_NAME = "customurl_outbox_drain";
    /** 退避上限 1 小时，避免长时间堆积只在一个超长退避周期等待。 */
    private static final long MAX_BACKOFF_MS = 60L * 60L * 1000L;

    private static volatile OutboxRepository INSTANCE;

    public static OutboxRepository getInstance() {
        if (INSTANCE == null) {
            synchronized (OutboxRepository.class) {
                if (INSTANCE == null) {
                    INSTANCE = new OutboxRepository();
                }
            }
        }
        return INSTANCE;
    }

    public long enqueue(long recordedAtMs, String url, String method, String body,
                        String headers, String basicAuthUsername, String basicAuthPassword) {
        try {
            OutboxEntry e = new OutboxEntry();
            e.recordedAt = recordedAtMs;
            e.createdAt = System.currentTimeMillis();
            e.url = url;
            e.method = method == null ? "GET" : method;
            e.body = body == null ? "" : body;
            e.headers = headers == null ? "" : headers;
            e.basicAuthUsername = basicAuthUsername;
            e.basicAuthPassword = basicAuthPassword;
            e.status = OutboxEntry.STATUS_PENDING;
            e.attemptCount = 0;
            e.nextAttemptAt = 0;
            long id = DAO.insert(e);
            if (id < 0) {
                LOG.warn("Outbox enqueue failed for recorded_at={}", recordedAtMs);
                return id;
            }
            enforceCapacity();
            scheduleDrain();
            return id;
        } catch (Throwable t) {
            LOG.warn("Outbox enqueue exception", t);
            return -1;
        }
    }

    /** 让 Outbox 按容量上限裁剪最老的 PENDING 行。 */
    public void enforceCapacity() {
        try {
            int max = TrackerPreferenceHelper.getInstance().getCustomUrlOutboxMaxRows();
            int total = DAO.countTotal();
            if (total > max) {
                int over = total - max;
                int dropped = DAO.deleteOldestPending(over);
                if (dropped > 0) {
                    LOG.warn("Outbox over capacity (total={}, max={}), dropped {} oldest PENDING rows",
                            total, max, dropped);
                }
            }
        } catch (Throwable t) {
            LOG.debug("Outbox capacity enforcement failed", t);
        }
    }

    /** 周期性自动清理已经超过保留天数的 FAILED 行。 */
    public void cleanupFailed() {
        try {
            int days = TrackerPreferenceHelper.getInstance().getCustomUrlOutboxKeepFailedDays();
            if (days <= 0) return;
            long threshold = System.currentTimeMillis() - days * 24L * 3600L * 1000L;
            int dropped = DAO.deleteFailedOlderThan(threshold);
            if (dropped > 0) LOG.debug("Outbox dropped {} stale FAILED rows", dropped);
        } catch (Throwable t) {
            LOG.debug("Outbox failed cleanup failed", t);
        }
    }

    public List<OutboxEntry> nextBatch(int limit) {
        return DAO.nextBatch(limit, System.currentTimeMillis());
    }

    /** Worker 启动入口：把上一轮被杀停留在 IN_FLIGHT 的行恢复成 PENDING。 */
    public int recoverInFlight() { return DAO.recoverInFlight(); }

    public void markInFlight(long id) { DAO.markInFlight(id); }

    public void markSucceeded(long id) {
        DAO.deleteById(id);
    }

    public void markFailedRetry(long id, int attemptCount, String error) {
        long delay = computeBackoffMs(attemptCount);
        long now = System.currentTimeMillis();
        DAO.markFailed(id, attemptCount, error, now, now + delay, OutboxEntry.STATUS_PENDING);
    }

    public void markFinalFailure(long id, int attemptCount, String error) {
        long now = System.currentTimeMillis();
        DAO.markFailed(id, attemptCount, error, now, now, OutboxEntry.STATUS_FAILED);
    }

    public int getMaxAttempts() {
        return TrackerPreferenceHelper.getInstance().getCustomUrlOutboxMaxAttempts();
    }

    public int countPending() { return DAO.countByStatus(OutboxEntry.STATUS_PENDING); }
    public int countFailed() { return DAO.countByStatus(OutboxEntry.STATUS_FAILED); }
    public int countTotal() { return DAO.countTotal(); }
    public List<OutboxEntry> sampleLatest(int limit) { return DAO.sample(limit); }
    public int requeueAllFailed() {
        int n = DAO.requeueFailed();
        if (n > 0) scheduleDrain();
        return n;
    }
    public int clearAll() { return DAO.deleteAll(); }

    /** 计算指数退避，30s -> 60s -> 120s ... 上限 1 小时。 */
    public static long computeBackoffMs(int attemptCount) {
        long base = 30L * 1000L;
        long shift = Math.min(attemptCount - 1, 30);
        long delay = base * (1L << Math.max(0, shift));
        if (delay <= 0 || delay > MAX_BACKOFF_MS) delay = MAX_BACKOFF_MS;
        return delay;
    }

    public void scheduleDrain() {
        try {
            boolean wifiOnly = PreferenceHelper.getInstance().shouldAutoSendOnWifiOnly();
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(wifiOnly ? NetworkType.UNMETERED : NetworkType.CONNECTED)
                    .build();
            OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(CustomUrlOutboxWorker.class)
                    .setConstraints(constraints)
                    .setInitialDelay(1, TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .setInputData(new Data.Builder().putString("callbackType", "customurl").build())
                    .build();
            WorkManager.getInstance(AppSettings.getInstance())
                    .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req);
        } catch (Throwable t) {
            LOG.warn("Outbox drain schedule failed", t);
        }
    }
}

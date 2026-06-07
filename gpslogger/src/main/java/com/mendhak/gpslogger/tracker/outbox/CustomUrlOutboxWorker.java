/*
 * Travel/hiking 改造：消费 Custom URL Outbox 的 Worker。
 *
 * 设计：
 * - 网络可用时 WorkManager 调度 doWork(），从 Outbox 按 recorded_at 升序逐条发送。
 * - 单条成功：物理删除该行。
 * - 单条失败：写回 PENDING + 退避；达到 maxAttempts 改 FAILED。
 * - 一轮里只要还有 PENDING 行，下一轮重新调度（APPEND_OR_REPLACE）。
 */
package com.mendhak.gpslogger.tracker.outbox;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.mendhak.gpslogger.common.AppSettings;
import com.mendhak.gpslogger.common.Strings;
import com.mendhak.gpslogger.common.events.UploadEvents;
import com.mendhak.gpslogger.common.network.Networks;
import com.mendhak.gpslogger.common.slf4j.Logs;
import com.mendhak.gpslogger.loggers.customurl.CustomUrlRequest;
import com.mendhak.gpslogger.tracker.db.OutboxEntry;

import de.greenrobot.event.EventBus;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.slf4j.Logger;

import java.util.List;
import java.util.Map;

import javax.net.ssl.X509TrustManager;

public class CustomUrlOutboxWorker extends Worker {

    private static final Logger LOG = Logs.of(CustomUrlOutboxWorker.class);
    /** 一次 doWork() 最多消费多少条；过大会让 Worker 跑很久导致系统强杀。 */
    private static final int BATCH_LIMIT = 100;

    public CustomUrlOutboxWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        OutboxRepository repo = OutboxRepository.getInstance();

        // 入口处顺手做一次失败行清理
        repo.cleanupFailed();

        // 上一轮 Worker 若被系统杀停留在 IN_FLIGHT，需先把它们重置为 PENDING。
        int recovered = repo.recoverInFlight();
        if (recovered > 0) LOG.info("Outbox worker recovered {} stuck IN_FLIGHT rows", recovered);

        int maxAttempts = repo.getMaxAttempts();
        int consumed = 0;
        boolean anyFailureRequestingRetry = false;

        // 复用 GPSLogger 已有 SSL 配置
        OkHttpClient.Builder okBuilder = new OkHttpClient.Builder();
        try {
            okBuilder.sslSocketFactory(
                    Networks.getSocketFactory(AppSettings.getInstance()),
                    (X509TrustManager) Networks.getTrustManager(AppSettings.getInstance()));
        } catch (Throwable t) {
            LOG.warn("Outbox worker: failed to configure SSL, will use default", t);
        }
        OkHttpClient client = okBuilder.build();

        UploadEvents.BaseUploadEvent callbackEvent = new UploadEvents.CustomUrl();

        List<OutboxEntry> batch = repo.nextBatch(BATCH_LIMIT);
        for (OutboxEntry e : batch) {
            try {
                repo.markInFlight(e.id);
                // 用 CustomUrlRequest 解析 headers / basic auth，保持与旧路径一致
                CustomUrlRequest req = new CustomUrlRequest(
                        e.url, e.method, e.body, e.headers,
                        Strings.isNullOrEmpty(e.basicAuthUsername) ? "" : e.basicAuthUsername,
                        Strings.isNullOrEmpty(e.basicAuthPassword) ? "" : e.basicAuthPassword);

                Request.Builder rb = new Request.Builder().url(req.getLogURL());
                for (Map.Entry<String, String> h : req.getHttpHeaders().entrySet()) {
                    rb.addHeader(h.getKey(), h.getValue());
                }
                if (!req.getHttpMethod().equalsIgnoreCase("GET")) {
                    RequestBody body = RequestBody.create(null, req.getHttpBody());
                    rb.method(req.getHttpMethod(), body);
                }

                LOG.info("Outbox -> HTTP {} {}", req.getHttpMethod(), req.getLogURL());

                try (Response resp = client.newCall(rb.build()).execute()) {
                    if (resp.isSuccessful()) {
                        repo.markSucceeded(e.id);
                        consumed++;
                    } else {
                        String errMsg = "Unexpected code " + resp.code();
                        int attempts = e.attemptCount + 1;
                        if (attempts >= maxAttempts) {
                            repo.markFinalFailure(e.id, attempts, errMsg);
                        } else {
                            repo.markFailedRetry(e.id, attempts, errMsg);
                            anyFailureRequestingRetry = true;
                        }
                    }
                }
            } catch (Throwable t) {
                String msg = "Exception " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                int attempts = e.attemptCount + 1;
                if (attempts >= maxAttempts) {
                    repo.markFinalFailure(e.id, attempts, msg);
                } else {
                    repo.markFailedRetry(e.id, attempts, msg);
                    anyFailureRequestingRetry = true;
                }
                LOG.warn("Outbox send failed (id={}): {}", e.id, msg);
            }
        }

        if (consumed > 0) {
            EventBus.getDefault().post(callbackEvent.succeeded());
        }

        // 如果还有 PENDING 行（无论是这轮失败重排还是前面留下的），再排一次
        if (anyFailureRequestingRetry || repo.countPending() > 0) {
            repo.scheduleDrain();
        }

        // doWork 自己消费成功，不依赖 WorkManager 的 retry 机制：永远返回 success，
        // 失败重试由 Outbox 状态机驱动。
        return Result.success();
    }
}

/*
 * Travel/hiking 改造：本地轨迹缓存的对外门面。
 * - 接收过滤后的有效 Location，落入 SQLite
 * - 提供按时间范围查询
 * - 提供按保留时间自动清理
 *
 * 所有调用都已捕获异常；本地缓存失败绝不影响 Custom URL / GPX / CSV 主链路。
 */
package com.mendhak.gpslogger.tracker.cache;

import android.location.Location;
import android.os.Bundle;

import com.mendhak.gpslogger.common.BundleConstants;
import com.mendhak.gpslogger.common.PreferenceHelper;
import com.mendhak.gpslogger.common.Session;
import com.mendhak.gpslogger.common.slf4j.Logs;
import com.mendhak.gpslogger.tracker.TrackerPreferenceHelper;
import com.mendhak.gpslogger.tracker.db.TrackPoint;
import com.mendhak.gpslogger.tracker.db.TrackPointDao;

import org.slf4j.Logger;

import java.util.List;

public class TrackCacheRepository {

    private static final Logger LOG = Logs.of(TrackCacheRepository.class);
    private static final TrackPointDao DAO = new TrackPointDao();

    // 写入后每多少个点触发一次自动清理
    private static final int CLEANUP_INTERVAL_POINTS = 200;

    private static volatile TrackCacheRepository INSTANCE;
    private int sinceLastCleanup = 0;

    public static TrackCacheRepository getInstance() {
        if (INSTANCE == null) {
            synchronized (TrackCacheRepository.class) {
                if (INSTANCE == null) {
                    INSTANCE = new TrackCacheRepository();
                }
            }
        }
        return INSTANCE;
    }

    public void append(Location loc) {
        if (loc == null) return;
        try {
            TrackerPreferenceHelper tprefs = TrackerPreferenceHelper.getInstance();
            if (!tprefs.isLocalTrackCacheEnabled()) return;

            TrackPoint p = fromLocation(loc);
            long id = DAO.insert(p);
            if (id < 0) {
                // 第一次失败 - 尝试清理后再写一次
                cleanupExpired();
                DAO.insert(p);
            }

            sinceLastCleanup++;
            if (sinceLastCleanup >= CLEANUP_INTERVAL_POINTS) {
                sinceLastCleanup = 0;
                cleanupExpired();
            }
        } catch (Throwable t) {
            LOG.debug("TrackCache append failed", t);
        }
    }

    public List<TrackPoint> queryRange(long fromMs, long toMs) {
        return DAO.queryRange(fromMs, toMs);
    }

    public long countAll() {
        return DAO.countAll();
    }

    /** 删除所有早于「now - retention」的点。返回被删除的行数。 */
    public int cleanupExpired() {
        try {
            long retention = TrackerPreferenceHelper.getInstance().getLocalTrackCacheRetentionMillis();
            long threshold = System.currentTimeMillis() - retention;
            int deleted = DAO.deleteOlderThan(threshold);
            if (deleted > 0) LOG.debug("TrackCache cleanup removed {} expired points", deleted);
            return deleted;
        } catch (Throwable t) {
            LOG.debug("TrackCache cleanup failed", t);
            return 0;
        }
    }

    public int clearAll() {
        return DAO.deleteAll();
    }

    private TrackPoint fromLocation(Location loc) {
        TrackPoint p = new TrackPoint();
        p.recordedAt = loc.getTime();
        p.insertedAt = System.currentTimeMillis();
        p.lat = loc.getLatitude();
        p.lon = loc.getLongitude();
        if (loc.hasAltitude()) p.altitude = loc.getAltitude();
        if (loc.hasAccuracy()) p.accuracy = loc.getAccuracy();
        if (loc.hasSpeed()) p.speed = loc.getSpeed();
        if (loc.hasBearing()) p.bearing = loc.getBearing();
        p.provider = loc.getProvider();

        Bundle extras = loc.getExtras();
        if (extras != null) {
            if (extras.containsKey(BundleConstants.SATELLITES_FIX)) {
                p.satellites = extras.getInt(BundleConstants.SATELLITES_FIX);
            }
            p.hdop = parseDoubleOrNull(extras.getString(BundleConstants.HDOP));
            p.vdop = parseDoubleOrNull(extras.getString(BundleConstants.VDOP));
            p.pdop = parseDoubleOrNull(extras.getString(BundleConstants.PDOP));
        }

        // 上下文字段
        try {
            Session s = Session.getInstance();
            if (s != null) {
                p.distanceMeters = s.getTotalTravelled();
                p.fileName = s.getCurrentFormattedFileName();
                if (s.hasDescription()) p.annotation = s.getDescription();
            }
            p.profileName = PreferenceHelper.getInstance().getCurrentProfileName();
        } catch (Throwable ignore) {
            // Session/PreferenceHelper 在测试场景或初始化阶段可能为空，忽略
        }
        return p;
    }

    private Double parseDoubleOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }
}

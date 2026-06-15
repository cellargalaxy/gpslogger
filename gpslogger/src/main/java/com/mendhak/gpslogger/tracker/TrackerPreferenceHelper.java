/*
 * Travel/hiking 改造：所有新增偏好的读写集中在这里，避免污染上游 PreferenceHelper。
 * 读 SharedPreferences 时复用 androidx.preference 的默认实例，与既有项目一致。
 */
package com.mendhak.gpslogger.tracker;

import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import com.mendhak.gpslogger.common.AppSettings;
import com.mendhak.gpslogger.tracker.offline.OpenStreetMapStyle;

public final class TrackerPreferenceHelper {

    private static volatile TrackerPreferenceHelper INSTANCE;

    private final SharedPreferences prefs;

    private TrackerPreferenceHelper() {
        this.prefs = PreferenceManager.getDefaultSharedPreferences(AppSettings.getInstance());
    }

    public static TrackerPreferenceHelper getInstance() {
        if (INSTANCE == null) {
            synchronized (TrackerPreferenceHelper.class) {
                if (INSTANCE == null) {
                    INSTANCE = new TrackerPreferenceHelper();
                }
            }
        }
        return INSTANCE;
    }

    private int parseIntSafely(String value, int fallback) {
        if (value == null) return fallback;
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private int getIntOrString(String key, int fallback) {
        // ListPreference 用 entryValues 存的是字符串，普通 EditTextPreference 也可能是字符串，
        // 直接 getInt 会抛 ClassCastException。统一从字符串解析回退到 int。
        Object raw = prefs.getAll().get(key);
        if (raw instanceof Integer) return (Integer) raw;
        if (raw instanceof String) return parseIntSafely((String) raw, fallback);
        if (raw instanceof Long) return ((Long) raw).intValue();
        return fallback;
    }

    public boolean isLocalTrackCacheEnabled() {
        return prefs.getBoolean(TrackerPreferenceNames.LOCAL_TRACK_CACHE_ENABLED, true);
    }

    public int getLocalTrackCacheRetentionHours() {
        return getIntOrString(TrackerPreferenceNames.LOCAL_TRACK_CACHE_RETENTION_HOURS,
                TrackerPreferenceNames.DEFAULT_LOCAL_TRACK_CACHE_RETENTION_HOURS);
    }

    public long getLocalTrackCacheRetentionMillis() {
        long hours = getLocalTrackCacheRetentionHours();
        if (hours <= 0) hours = TrackerPreferenceNames.DEFAULT_LOCAL_TRACK_CACHE_RETENTION_HOURS;
        return hours * 3600L * 1000L;
    }

    public int getTrackMapSegmentMinutes() {
        int m = getIntOrString(TrackerPreferenceNames.TRACK_MAP_SEGMENT_MINUTES,
                TrackerPreferenceNames.DEFAULT_TRACK_MAP_SEGMENT_MINUTES);
        // 枚举范围 5/10/15/30/60
        if (m != 5 && m != 10 && m != 15 && m != 30 && m != 60) {
            return TrackerPreferenceNames.DEFAULT_TRACK_MAP_SEGMENT_MINUTES;
        }
        return m;
    }

    public int getTrackMapTimeRangeHours() {
        return getIntOrString(TrackerPreferenceNames.TRACK_MAP_TIME_RANGE_HOURS,
                TrackerPreferenceNames.DEFAULT_TRACK_MAP_TIME_RANGE_HOURS);
    }

    public boolean isTrackMapVisibleTileCacheEnabled() {
        return prefs.getBoolean(TrackerPreferenceNames.TRACK_MAP_CACHE_VISIBLE_TILES,
                TrackerPreferenceNames.DEFAULT_TRACK_MAP_CACHE_VISIBLE_TILES);
    }

    public void setTrackMapVisibleTileCacheEnabled(boolean enabled) {
        prefs.edit().putBoolean(TrackerPreferenceNames.TRACK_MAP_CACHE_VISIBLE_TILES, enabled).apply();
    }


    public String getOfflineMapStyleUrl() {
        String s = prefs.getString(TrackerPreferenceNames.OFFLINE_MAP_STYLE_URL,
                TrackerPreferenceNames.DEFAULT_OFFLINE_MAP_STYLE_URL);
        if (s == null || s.trim().isEmpty() || OpenStreetMapStyle.isLegacyDemoStyle(s)) {
            return TrackerPreferenceNames.DEFAULT_OFFLINE_MAP_STYLE_URL;
        }
        s = s.trim();
        if (s.startsWith("builtin:") && !OpenStreetMapStyle.isBuiltInStyle(s)) {
            return TrackerPreferenceNames.DEFAULT_OFFLINE_MAP_STYLE_URL;
        }
        return s;
    }

    public void setOfflineMapStyleUrl(String styleUrl) {
        String s = styleUrl == null ? "" : styleUrl.trim();
        if (s.isEmpty() || OpenStreetMapStyle.isLegacyDemoStyle(s)
                || (s.startsWith("builtin:") && !OpenStreetMapStyle.isBuiltInStyle(s))) {
            s = TrackerPreferenceNames.DEFAULT_OFFLINE_MAP_STYLE_URL;
        }
        prefs.edit().putString(TrackerPreferenceNames.OFFLINE_MAP_STYLE_URL, s).apply();
    }

    public int getOfflineMapMaxCacheMb() {
        int mb = getIntOrString(TrackerPreferenceNames.OFFLINE_MAP_MAX_CACHE_MB,
                TrackerPreferenceNames.DEFAULT_OFFLINE_MAP_MAX_CACHE_MB);
        if (mb <= 0) return TrackerPreferenceNames.DEFAULT_OFFLINE_MAP_MAX_CACHE_MB;
        return mb;
    }

    public long getOfflineMapMaxCacheBytes() {
        return getOfflineMapMaxCacheMb() * 1024L * 1024L;
    }

    public boolean isOfflineMapUsingPublicTileLayer() {
        return OpenStreetMapStyle.usesPublicOrManagedTiles(getOfflineMapStyleUrl());
    }

    public boolean isOfflineMapUsingPublicOpenStreetMapTiles() {
        return isOfflineMapUsingPublicTileLayer();
    }
}

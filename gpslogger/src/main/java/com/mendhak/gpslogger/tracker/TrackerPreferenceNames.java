/*
 * Travel/hiking 改造：所有新增偏好的 key 集中在这里，避免污染上游 PreferenceNames。
 */
package com.mendhak.gpslogger.tracker;

public final class TrackerPreferenceNames {
    private TrackerPreferenceNames() {}

    // 本地轨迹缓存
    public static final String LOCAL_TRACK_CACHE_ENABLED = "local_track_cache_enabled";
    public static final String LOCAL_TRACK_CACHE_RETENTION_HOURS = "local_track_cache_retention_hours";

    // 轨迹地图
    public static final String TRACK_MAP_SEGMENT_MINUTES = "track_map_segment_minutes";
    public static final String TRACK_MAP_TIME_RANGE_HOURS = "track_map_time_range_hours";

    // Custom URL Outbox
    public static final String CUSTOMURL_OUTBOX_ENABLED = "customurl_outbox_enabled";
    public static final String CUSTOMURL_OUTBOX_MAX_ATTEMPTS = "customurl_outbox_max_attempts";
    public static final String CUSTOMURL_OUTBOX_MAX_ROWS = "customurl_outbox_max_rows";
    public static final String CUSTOMURL_OUTBOX_KEEP_FAILED_DAYS = "customurl_outbox_keep_failed_days";

    // 离线地图
    public static final String OFFLINE_MAP_MAX_MB = "offline_map_max_mb";
    public static final String OFFLINE_MAP_STYLE_URL = "offline_map_style_url";

    public static final String BUILTIN_OPENSTREETMAP_STYLE_URL = "builtin:openstreetmap";
    public static final String LEGACY_MAPLIBRE_DEMO_STYLE_URL = "https://demotiles.maplibre.org/style.json";

    // 默认值
    public static final int DEFAULT_LOCAL_TRACK_CACHE_RETENTION_HOURS = 24;
    public static final int DEFAULT_TRACK_MAP_SEGMENT_MINUTES = 15;
    public static final int DEFAULT_TRACK_MAP_TIME_RANGE_HOURS = 24;
    public static final int DEFAULT_CUSTOMURL_OUTBOX_MAX_ATTEMPTS = 16;
    public static final int DEFAULT_CUSTOMURL_OUTBOX_MAX_ROWS = 200000;
    public static final int DEFAULT_CUSTOMURL_OUTBOX_KEEP_FAILED_DAYS = 30;
    public static final int DEFAULT_OFFLINE_MAP_MAX_MB = 1024;
    public static final String DEFAULT_OFFLINE_MAP_STYLE_URL = BUILTIN_OPENSTREETMAP_STYLE_URL;
}

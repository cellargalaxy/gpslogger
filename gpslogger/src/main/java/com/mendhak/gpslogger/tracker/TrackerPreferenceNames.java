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
    public static final String TRACK_MAP_CACHE_VISIBLE_TILES = "track_map_cache_visible_tiles";


    // 离线地图
    public static final String OFFLINE_MAP_STYLE_URL = "offline_map_style_url";

    public static final String BUILTIN_OPENSTREETMAP_STYLE_URL = "builtin:openstreetmap";
    public static final String LEGACY_MAPLIBRE_DEMO_STYLE_URL = "https://demotiles.maplibre.org/style.json";

    // 默认值
    public static final int DEFAULT_LOCAL_TRACK_CACHE_RETENTION_HOURS = 24;
    public static final int DEFAULT_TRACK_MAP_SEGMENT_MINUTES = 15;
    public static final int DEFAULT_TRACK_MAP_TIME_RANGE_HOURS = 24;
    public static final boolean DEFAULT_TRACK_MAP_CACHE_VISIBLE_TILES = false;
    public static final String DEFAULT_OFFLINE_MAP_STYLE_URL = BUILTIN_OPENSTREETMAP_STYLE_URL;
}

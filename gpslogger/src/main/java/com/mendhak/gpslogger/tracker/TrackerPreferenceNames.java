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
    public static final String TRACK_MAP_NOMINATIM_SEARCH_URL = "track_map_nominatim_search_url";
    // 外部导入 KML 后，待「轨迹地图」打开时自动展示的文件名（一次性传递，消费后清空）
    public static final String PENDING_KML_IMPORT_NAME = "pending_kml_import_name";


    // 离线地图
    public static final String OFFLINE_MAP_STYLE_URL = "offline_map_style_url";
    public static final String OFFLINE_MAP_MAX_CACHE_MB = "offline_map_max_cache_mb";

    public static final String BUILTIN_OPENSTREETMAP_STYLE_URL = "builtin:openstreetmap";
    public static final String BUILTIN_CYCLOSM_STYLE_URL = "builtin:cyclosm";
    public static final String BUILTIN_ESRI_WORLD_IMAGERY_STYLE_URL = "builtin:esriWorldImagery";
    public static final String LEGACY_MAPLIBRE_DEMO_STYLE_URL = "https://demotiles.maplibre.org/style.json";

    // 默认值
    public static final int DEFAULT_LOCAL_TRACK_CACHE_RETENTION_HOURS = 24;
    public static final int DEFAULT_TRACK_MAP_SEGMENT_MINUTES = 15;
    public static final int DEFAULT_TRACK_MAP_TIME_RANGE_HOURS = 24;
    public static final boolean DEFAULT_TRACK_MAP_CACHE_VISIBLE_TILES = false;
    public static final String DEFAULT_TRACK_MAP_NOMINATIM_SEARCH_URL = "https://nominatim.openstreetmap.org/search";
    public static final String DEFAULT_OFFLINE_MAP_STYLE_URL = BUILTIN_OPENSTREETMAP_STYLE_URL;
    public static final int DEFAULT_OFFLINE_MAP_MAX_CACHE_MB = 1024;
}

/*
 * Travel/hiking 改造：内置公共/托管在线底图。
 *
 * 说明：
 * - MapLibre 仍然是地图 SDK；这里提供的是 MapLibre style JSON。
 * - 公共/托管瓦片只用于人工交互浏览，不用于离线区域预下载。
 * - 显式设置 User-Agent，避免使用地图 SDK 的通用默认标识。
 */
package com.mendhak.gpslogger.tracker.offline;

import android.content.Context;

import com.mendhak.gpslogger.BuildConfig;
import com.mendhak.gpslogger.tracker.TrackerPreferenceNames;

import com.mendhak.gpslogger.common.slf4j.Logs;

import org.maplibre.android.module.http.HttpRequestUtil;
import org.slf4j.Logger;

import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class OpenStreetMapStyle {

    private static final Logger LOG = Logs.of(OpenStreetMapStyle.class);

    public static final String OPENSTREETMAP_TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
    public static final String CYCLOSM_TILE_URL = "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png";
    public static final String ESRI_WORLD_IMAGERY_TILE_URL = "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}";

    /**
     * 同样内容打包到 APK assets。MapLibre 通过 asset:// 协议读取，比 Style.Builder.fromJson 更稳定，
     * 能绕过部分 MapLibre Native v11 在解析内联 JSON 时偶发的渲染卡顿。
     */
    public static final String BUILTIN_OPENSTREETMAP_ASSET_URI = "asset://styles/openstreetmap.json";
    public static final String BUILTIN_CYCLOSM_ASSET_URI = "asset://styles/cyclosm.json";
    public static final String BUILTIN_ESRI_WORLD_IMAGERY_ASSET_URI = "asset://styles/esri_world_imagery.json";

    /** 保留旧常量，避免漏改调用点时改变默认底图。 */
    public static final String BUILTIN_ASSET_URI = BUILTIN_OPENSTREETMAP_ASSET_URI;

    private static volatile boolean httpClientConfigured;

    private OpenStreetMapStyle() {}

    public static boolean isBuiltInStyle(String styleUrl) {
        return isBuiltInOpenStreetMapStyle(styleUrl)
                || isBuiltInCyclOSMStyle(styleUrl)
                || isBuiltInEsriWorldImageryStyle(styleUrl);
    }

    public static boolean isBuiltInOpenStreetMapStyle(String styleUrl) {
        String s = normalizeStyleUrl(styleUrl);
        return s.isEmpty() || TrackerPreferenceNames.BUILTIN_OPENSTREETMAP_STYLE_URL.equals(s);
    }

    public static boolean isBuiltInCyclOSMStyle(String styleUrl) {
        return TrackerPreferenceNames.BUILTIN_CYCLOSM_STYLE_URL.equals(normalizeStyleUrl(styleUrl));
    }

    public static boolean isBuiltInEsriWorldImageryStyle(String styleUrl) {
        return TrackerPreferenceNames.BUILTIN_ESRI_WORLD_IMAGERY_STYLE_URL.equals(normalizeStyleUrl(styleUrl));
    }

    public static boolean isLegacyDemoStyle(String styleUrl) {
        return styleUrl != null
                && TrackerPreferenceNames.LEGACY_MAPLIBRE_DEMO_STYLE_URL.equals(styleUrl.trim());
    }

    public static String resolveStyleUri(String styleUrl) {
        if (isBuiltInCyclOSMStyle(styleUrl)) return BUILTIN_CYCLOSM_ASSET_URI;
        if (isBuiltInEsriWorldImageryStyle(styleUrl)) return BUILTIN_ESRI_WORLD_IMAGERY_ASSET_URI;
        if (isBuiltInOpenStreetMapStyle(styleUrl)) return BUILTIN_OPENSTREETMAP_ASSET_URI;
        return normalizeStyleUrl(styleUrl);
    }

    public static boolean usesPublicOpenStreetMapTiles(String styleUrl) {
        if (isBuiltInOpenStreetMapStyle(styleUrl) || isLegacyDemoStyle(styleUrl)) return true;
        return styleUrl != null && styleUrl.toLowerCase(Locale.US).contains("tile.openstreetmap.org");
    }

    public static boolean usesPublicOrManagedTiles(String styleUrl) {
        if (isBuiltInStyle(styleUrl) || isLegacyDemoStyle(styleUrl)) return true;
        if (styleUrl == null) return false;
        String s = styleUrl.toLowerCase(Locale.US);
        return s.contains("tile.openstreetmap.org")
                || s.contains("tile-cyclosm.openstreetmap.fr")
                || s.contains("services.arcgisonline.com/arcgis/rest/services/world_imagery/mapserver");
    }

    public static String styleJson() {
        return openStreetMapStyleJson();
    }

    public static String openStreetMapStyleJson() {
        return rasterStyleJson("OpenStreetMap Standard", "osm-standard", OPENSTREETMAP_TILE_URL,
                0, 19, "\u00A9 OpenStreetMap contributors");
    }

    public static String cyclOSMStyleJson() {
        return rasterStyleJson("CyclOSM", "cyclosm", CYCLOSM_TILE_URL,
                -1, -1, "\u00A9 CyclOSM, \u00A9 OpenStreetMap contributors");
    }

    public static String esriWorldImageryStyleJson() {
        return rasterStyleJson("Esri World Imagery", "esri-world-imagery", ESRI_WORLD_IMAGERY_TILE_URL,
                0, 23, "Source: Esri, Vantor, Earthstar Geographics, and the GIS User Community");
    }

    private static String rasterStyleJson(String name, String sourceId, String tileUrl,
                                          int minZoom, int maxZoom, String attribution) {
        // background layer 在 raster 之前，避免瓦片未到位时画布为 MapLibre 默认黑色。
        StringBuilder sb = new StringBuilder();
        sb.append('{')
                .append("\"version\":8,")
                .append("\"name\":\"").append(name).append("\",")
                .append("\"sources\":{")
                .append("\"").append(sourceId).append("\":{")
                .append("\"type\":\"raster\",")
                .append("\"tiles\":[\"").append(tileUrl).append("\"],")
                .append("\"tileSize\":256,");
        if (minZoom >= 0) sb.append("\"minzoom\":").append(minZoom).append(',');
        if (maxZoom >= 0) sb.append("\"maxzoom\":").append(maxZoom).append(',');
        sb.append("\"attribution\":\"").append(attribution).append("\"")
                .append('}')
                .append("},")
                .append("\"layers\":[")
                .append('{')
                .append("\"id\":\"background\",")
                .append("\"type\":\"background\",")
                .append("\"paint\":{\"background-color\":\"#E8EAED\"}")
                .append("},")
                .append('{')
                .append("\"id\":\"").append(sourceId).append("\",")
                .append("\"type\":\"raster\",")
                .append("\"source\":\"").append(sourceId).append("\",")
                .append("\"paint\":{\"raster-opacity\":1.0}")
                .append('}')
                .append(']')
                .append('}');
        return sb.toString();
    }

    public static void configureMapLibreHttpClient(Context context) {
        if (httpClientConfigured) return;
        synchronized (OpenStreetMapStyle.class) {
            if (httpClientConfigured) return;
            try {
                final String userAgent = buildUserAgent(context.getApplicationContext());
                // 启用 MapLibre 内置 HTTP 日志，便于 logcat 观察底层瓦片请求。
                try { HttpRequestUtil.setLogEnabled(true); } catch (Throwable ignore) {}
                OkHttpClient client = new OkHttpClient.Builder()
                        .addInterceptor(chain -> {
                            Request request = chain.request();
                            try {
                                Request modified = request.newBuilder()
                                        .header("User-Agent", userAgent)
                                        .build();
                                long t0 = System.currentTimeMillis();
                                Response response = chain.proceed(modified);
                                long elapsed = System.currentTimeMillis() - t0;
                                LOG.info("MapLibre HTTP {} {} -> {} ({} ms)",
                                        request.method(), request.url(),
                                        response.code(), elapsed);
                                return response;
                            } catch (Throwable t) {
                                LOG.warn("MapLibre HTTP {} {} threw {}",
                                        request.method(), request.url(), t.toString());
                                throw t;
                            }
                        })
                        .build();
                HttpRequestUtil.setOkHttpClient(client);
                httpClientConfigured = true;
                LOG.info("MapLibre OkHttp client installed, UA={}", userAgent);
            } catch (Throwable t) {
                // 地图可用性优先；User-Agent 配置失败不应导致轨迹地图闪退。
                LOG.warn("MapLibre OkHttp client install failed", t);
            }
        }
    }

    private static String normalizeStyleUrl(String styleUrl) {
        return styleUrl == null ? "" : styleUrl.trim();
    }

    public static String buildUserAgent(Context context) {
        return "GPSLogger-Travel/" + BuildConfig.VERSION_NAME
                + " (" + context.getPackageName()
                + "; https://github.com/mendhak/gpslogger)";
    }
}

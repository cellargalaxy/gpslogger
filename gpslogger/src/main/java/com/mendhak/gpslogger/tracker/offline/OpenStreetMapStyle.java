/*
 * Travel/hiking 改造：内置 OpenStreetMap 标准在线底图。
 *
 * 说明：
 * - MapLibre 仍然是地图 SDK；这里提供的是 MapLibre style JSON。
 * - tile.openstreetmap.org 只用于人工交互浏览，不用于离线区域预下载。
 * - 显式设置 User-Agent，避免使用地图 SDK 的通用默认标识。
 */
package com.mendhak.gpslogger.tracker.offline;

import android.content.Context;

import com.mendhak.gpslogger.BuildConfig;
import com.mendhak.gpslogger.tracker.TrackerPreferenceNames;

import org.maplibre.android.module.http.HttpRequestUtil;

import okhttp3.OkHttpClient;
import okhttp3.Request;

public final class OpenStreetMapStyle {

    public static final String TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";

    private static volatile boolean httpClientConfigured;

    private OpenStreetMapStyle() {}

    public static boolean isBuiltInStyle(String styleUrl) {
        return styleUrl == null
                || styleUrl.trim().isEmpty()
                || TrackerPreferenceNames.BUILTIN_OPENSTREETMAP_STYLE_URL.equals(styleUrl.trim());
    }

    public static boolean isLegacyDemoStyle(String styleUrl) {
        return styleUrl != null
                && TrackerPreferenceNames.LEGACY_MAPLIBRE_DEMO_STYLE_URL.equals(styleUrl.trim());
    }

    public static boolean usesPublicOpenStreetMapTiles(String styleUrl) {
        if (isBuiltInStyle(styleUrl) || isLegacyDemoStyle(styleUrl)) return true;
        return styleUrl != null && styleUrl.toLowerCase().contains("tile.openstreetmap.org");
    }

    public static String styleJson() {
        return "{"
                + "\"version\":8,"
                + "\"name\":\"OpenStreetMap Standard\","
                + "\"sources\":{"
                + "\"osm-standard\":{"
                + "\"type\":\"raster\","
                + "\"tiles\":[\"" + TILE_URL + "\"],"
                + "\"tileSize\":256,"
                + "\"minzoom\":0,"
                + "\"maxzoom\":19,"
                + "\"attribution\":\"\\u00A9 OpenStreetMap contributors\""
                + "}"
                + "},"
                + "\"layers\":[{"
                + "\"id\":\"osm-standard\","
                + "\"type\":\"raster\","
                + "\"source\":\"osm-standard\","
                + "\"paint\":{\"raster-opacity\":1.0}"
                + "}]"
                + "}";
    }

    public static void configureMapLibreHttpClient(Context context) {
        if (httpClientConfigured) return;
        synchronized (OpenStreetMapStyle.class) {
            if (httpClientConfigured) return;
            try {
                final String userAgent = buildUserAgent(context.getApplicationContext());
                OkHttpClient client = new OkHttpClient.Builder()
                        .addInterceptor(chain -> {
                            Request request = chain.request().newBuilder()
                                    .header("User-Agent", userAgent)
                                    .build();
                            return chain.proceed(request);
                        })
                        .build();
                HttpRequestUtil.setOkHttpClient(client);
                httpClientConfigured = true;
            } catch (Throwable ignore) {
                // 地图可用性优先；User-Agent 配置失败不应导致轨迹地图闪退。
            }
        }
    }

    private static String buildUserAgent(Context context) {
        return "GPSLogger-Travel/" + BuildConfig.VERSION_NAME
                + " (" + context.getPackageName()
                + "; https://github.com/mendhak/gpslogger)";
    }
}

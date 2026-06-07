/*
 * Travel/hiking 改造：基于 MapLibre Native Android OfflineManager 的离线地图实现。
 *
 * 关键 API：
 * - OfflineManager.getInstance(context)
 * - OfflineManager.createOfflineRegion(OfflineTilePyramidRegionDefinition, metadata, callback)
 * - OfflineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
 *
 * 设计要点：
 * - region metadata 用 JSON 存自定义 name，方便 UI 展示。
 * - 失败和取消都不抛 throws，进 callback。
 * - listRegions 是异步的，本接口做了一次 CountDownLatch 同步包装方便 UI 调用。
 */
package com.mendhak.gpslogger.tracker.offline;

import android.content.Context;

import com.mendhak.gpslogger.common.slf4j.Logs;
import com.mendhak.gpslogger.tracker.TrackerPreferenceHelper;

import org.json.JSONException;
import org.json.JSONObject;
import org.maplibre.android.MapLibre;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.offline.OfflineManager;
import org.maplibre.android.offline.OfflineRegion;
import org.maplibre.android.offline.OfflineRegionError;
import org.maplibre.android.offline.OfflineRegionStatus;
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class MapLibreOfflineMapStore implements OfflineMapStore {

    private static final Logger LOG = Logs.of(MapLibreOfflineMapStore.class);
    private static final String META_NAME_KEY = "name";

    private final Context context;

    public MapLibreOfflineMapStore(Context context) {
        this.context = context.getApplicationContext();
        try {
            OpenStreetMapStyle.configureMapLibreHttpClient(this.context);
            MapLibre.getInstance(this.context);
        } catch (Throwable t) {
            LOG.warn("MapLibre init failed", t);
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            MapLibre.getInstance(context);
            OfflineManager.getInstance(context);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public List<Region> listRegions() {
        final List<Region> result = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);
        OfflineManager.getInstance(context).listOfflineRegions(new OfflineManager.ListOfflineRegionsCallback() {
            @Override
            public void onList(OfflineRegion[] offlineRegions) {
                if (offlineRegions != null) {
                    for (OfflineRegion or : offlineRegions) {
                        Region r = new Region();
                        r.id = or.getId();
                        r.name = parseName(or.getMetadata());
                        if (or.getDefinition() instanceof OfflineTilePyramidRegionDefinition) {
                            OfflineTilePyramidRegionDefinition def = (OfflineTilePyramidRegionDefinition) or.getDefinition();
                            LatLngBounds bounds = def.getBounds();
                            if (bounds != null) {
                                LatLng sw = bounds.getSouthWest();
                                LatLng ne = bounds.getNorthEast();
                                r.minLat = sw.getLatitude();
                                r.minLon = sw.getLongitude();
                                r.maxLat = ne.getLatitude();
                                r.maxLon = ne.getLongitude();
                            }
                            r.minZoom = (int) def.getMinZoom();
                            r.maxZoom = (int) def.getMaxZoom();
                        }
                        result.add(r);
                    }
                }
                latch.countDown();
            }

            @Override
            public void onError(String error) {
                LOG.warn("listOfflineRegions error: {}", error);
                latch.countDown();
            }
        });
        try { latch.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return result;
    }

    @Override
    public long createRegion(String name, double minLat, double minLon, double maxLat, double maxLon,
                             int minZoom, int maxZoom, final ProgressCallback callback) throws Exception {
        String styleUrl = TrackerPreferenceHelper.getInstance().getOfflineMapStyleUrl();
        if (OpenStreetMapStyle.usesPublicOpenStreetMapTiles(styleUrl)) {
            throw new IllegalStateException("OpenStreetMap public tile style cannot be downloaded for offline use.");
        }
        LatLngBounds bounds = new LatLngBounds.Builder()
                .include(new LatLng(minLat, minLon))
                .include(new LatLng(maxLat, maxLon))
                .build();
        OfflineTilePyramidRegionDefinition definition = new OfflineTilePyramidRegionDefinition(
                styleUrl, bounds, minZoom, maxZoom, context.getResources().getDisplayMetrics().density);

        JSONObject metaJson = new JSONObject();
        try { metaJson.put(META_NAME_KEY, name); } catch (JSONException e) { /* 忽略 */ }
        final byte[] metadata = metaJson.toString().getBytes(StandardCharsets.UTF_8);

        final AtomicReference<Long> outId = new AtomicReference<>(-1L);
        final CountDownLatch latch = new CountDownLatch(1);

        OfflineManager.getInstance(context).createOfflineRegion(definition, metadata,
                new OfflineManager.CreateOfflineRegionCallback() {
            @Override
            public void onCreate(OfflineRegion offlineRegion) {
                outId.set(offlineRegion.getId());
                offlineRegion.setObserver(new OfflineRegion.OfflineRegionObserver() {
                    @Override
                    public void onStatusChanged(OfflineRegionStatus status) {
                        if (callback != null) {
                            callback.onProgress(offlineRegion.getId(),
                                    status.getCompletedResourceSize(),
                                    status.getRequiredResourceCount() > 0
                                            ? status.getCompletedResourceCount() : 0,
                                    status.isComplete());
                        }
                    }

                    @Override
                    public void onError(OfflineRegionError error) {
                        if (callback != null) callback.onError(offlineRegion.getId(),
                                error.getReason() + " : " + error.getMessage());
                    }

                    @Override
                    public void mapboxTileCountLimitExceeded(long limit) {
                        if (callback != null) callback.onError(offlineRegion.getId(),
                                "Tile count limit exceeded: " + limit);
                    }
                });
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE);
                latch.countDown();
            }

            @Override
            public void onError(String error) {
                LOG.warn("createOfflineRegion error: {}", error);
                latch.countDown();
            }
        });

        latch.await(5, TimeUnit.SECONDS);
        return outId.get();
    }

    @Override
    public void cancel(final long regionId) {
        forEachMatchingRegion(regionId, region -> region.setDownloadState(OfflineRegion.STATE_INACTIVE));
    }

    @Override
    public void delete(final long regionId) {
        forEachMatchingRegion(regionId, region ->
                region.delete(new OfflineRegion.OfflineRegionDeleteCallback() {
                    @Override public void onDelete() { /* noop */ }
                    @Override public void onError(String error) { LOG.warn("delete region error: {}", error); }
                }));
    }

    @Override
    public void deleteAll() {
        OfflineManager.getInstance(context).listOfflineRegions(new OfflineManager.ListOfflineRegionsCallback() {
            @Override
            public void onList(OfflineRegion[] offlineRegions) {
                if (offlineRegions == null) return;
                for (OfflineRegion or : offlineRegions) {
                    or.delete(new OfflineRegion.OfflineRegionDeleteCallback() {
                        @Override public void onDelete() { /* noop */ }
                        @Override public void onError(String error) { LOG.warn("deleteAll region error: {}", error); }
                    });
                }
            }
            @Override
            public void onError(String error) { LOG.warn("listOfflineRegions error in deleteAll: {}", error); }
        });
    }

    @Override
    public long totalBytes() {
        // MapLibre 暂未直接暴露总大小；这里返回 0 由 UI 提示用户参考。
        return 0;
    }

    interface RegionAction { void run(OfflineRegion region); }

    private void forEachMatchingRegion(long regionId, RegionAction action) {
        OfflineManager.getInstance(context).listOfflineRegions(new OfflineManager.ListOfflineRegionsCallback() {
            @Override
            public void onList(OfflineRegion[] offlineRegions) {
                if (offlineRegions == null) return;
                for (OfflineRegion or : offlineRegions) {
                    if (or.getId() == regionId) action.run(or);
                }
            }
            @Override
            public void onError(String error) { LOG.warn("listOfflineRegions error: {}", error); }
        });
    }

    private String parseName(byte[] metadata) {
        if (metadata == null) return null;
        try {
            JSONObject obj = new JSONObject(new String(metadata, StandardCharsets.UTF_8));
            return obj.optString(META_NAME_KEY, null);
        } catch (JSONException e) {
            return null;
        }
    }
}

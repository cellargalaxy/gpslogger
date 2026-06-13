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
import com.mendhak.gpslogger.tracker.TrackerPreferenceNames;

import org.json.JSONException;
import org.json.JSONObject;
import org.maplibre.android.MapLibre;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.offline.OfflineManager;
import org.maplibre.android.offline.OfflineRegion;
import org.maplibre.android.offline.OfflineRegionError;
import org.maplibre.android.offline.OfflineRegionStatus;
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition;
import org.maplibre.android.storage.FileSource;
import org.slf4j.Logger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class MapLibreOfflineMapStore implements OfflineMapStore {

    private static final Logger LOG = Logs.of(MapLibreOfflineMapStore.class);
    private static final String META_NAME_KEY = "name";
    private static final long FILE_SOURCE_OPERATION_TIMEOUT_SECONDS = 30L;
    private static final String[] MAP_CACHE_NAME_HINTS = new String[]{"maplibre", "mapbox", "mbgl"};

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
        final AtomicBoolean trimmedAfterComplete = new AtomicBoolean(false);
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
                        if (status.isComplete() && trimmedAfterComplete.compareAndSet(false, true)) {
                            try {
                                trimToConfiguredLimit();
                            } catch (Throwable t) {
                                LOG.warn("trim offline map cache after download failed", t);
                            }
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
        deleteAllOfflineRegions();
        clearAmbientCache();
        resetDatabase();
        packDatabase();
    }

    private void deleteAllOfflineRegions() {
        final List<OfflineRegion> regions = new ArrayList<>();
        final CountDownLatch listLatch = new CountDownLatch(1);
        try {
            OfflineManager.getInstance(context).listOfflineRegions(new OfflineManager.ListOfflineRegionsCallback() {
                @Override
                public void onList(OfflineRegion[] offlineRegions) {
                    if (offlineRegions != null) {
                        for (OfflineRegion or : offlineRegions) regions.add(or);
                    }
                    listLatch.countDown();
                }

                @Override
                public void onError(String error) {
                    LOG.warn("listOfflineRegions error in deleteAll: {}", error);
                    listLatch.countDown();
                }
            });
            listLatch.await(5, TimeUnit.SECONDS);

            final CountDownLatch deleteLatch = new CountDownLatch(regions.size());
            for (OfflineRegion or : regions) {
                or.delete(new OfflineRegion.OfflineRegionDeleteCallback() {
                    @Override public void onDelete() { deleteLatch.countDown(); }
                    @Override public void onError(String error) {
                        LOG.warn("deleteAll region error: {}", error);
                        deleteLatch.countDown();
                    }
                });
            }
            deleteLatch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            LOG.warn("deleteAll offline regions failed", t);
        }
    }

    @Override
    public long totalBytes() {
        Set<String> seen = new HashSet<>();
        long total = 0L;
        for (File root : mapCacheRoots()) total += sizeOfMatchingFiles(root, seen);
        return total;
    }

    /** 打开轨迹地图的「缓存视野」时，按用户配置设置 MapLibre ambient cache 上限。失败只记日志，不影响看图。 */
    public void enableAmbientCacheRetention() {
        setAmbientCacheLimit(TrackerPreferenceHelper.getInstance().getOfflineMapMaxCacheBytes());
    }

    public long trimToConfiguredLimit() {
        return trimToLimit(TrackerPreferenceHelper.getInstance().getOfflineMapMaxCacheBytes());
    }

    public long trimToLimit(long maxBytes) {
        if (maxBytes <= 0L) {
            maxBytes = TrackerPreferenceNames.DEFAULT_OFFLINE_MAP_MAX_CACHE_MB * 1024L * 1024L;
        }
        setAmbientCacheLimit(maxBytes);

        long total = totalBytes();
        if (total <= maxBytes) return total;

        deleteOldestOfflineRegionsUntil(maxBytes);
        total = totalBytes();
        if (total <= maxBytes) return total;

        deleteOldestMatchingFilesUntil(maxBytes);
        packDatabase();
        return totalBytes();
    }

    private void setAmbientCacheLimit(long maxBytes) {
        runFileSourceOperation("setMaximumAmbientCacheSize",
                (manager, callback) -> manager.setMaximumAmbientCacheSize(maxBytes, callback),
                false);
    }

    /** 清掉用户浏览时自然产生的瓦片缓存；离线 region 由 deleteAll 负责删除。 */
    public void clearAmbientCache() {
        runFileSourceOperation("clearAmbientCache",
                (manager, callback) -> manager.clearAmbientCache(callback),
                true);
    }

    private void resetDatabase() {
        runFileSourceOperation("resetDatabase",
                (manager, callback) -> manager.resetDatabase(callback),
                true);
    }

    private void packDatabase() {
        runFileSourceOperation("packDatabase",
                (manager, callback) -> manager.packDatabase(callback),
                true);
    }

    private void deleteOldestOfflineRegionsUntil(long maxBytes) {
        List<OfflineRegion> regions = listOfflineRegionsSync();
        Collections.sort(regions, new Comparator<OfflineRegion>() {
            @Override
            public int compare(OfflineRegion a, OfflineRegion b) {
                if (a.getId() == b.getId()) return 0;
                return a.getId() < b.getId() ? -1 : 1;
            }
        });

        for (OfflineRegion region : regions) {
            if (totalBytes() <= maxBytes) return;
            LOG.info("Deleting oldest offline map region {} to enforce cache limit", region.getId());
            deleteOfflineRegionSync(region);
            packDatabase();
        }
    }

    private List<OfflineRegion> listOfflineRegionsSync() {
        final List<OfflineRegion> regions = new ArrayList<>();
        final CountDownLatch listLatch = new CountDownLatch(1);
        try {
            OfflineManager.getInstance(context).listOfflineRegions(new OfflineManager.ListOfflineRegionsCallback() {
                @Override
                public void onList(OfflineRegion[] offlineRegions) {
                    if (offlineRegions != null) {
                        for (OfflineRegion or : offlineRegions) regions.add(or);
                    }
                    listLatch.countDown();
                }

                @Override
                public void onError(String error) {
                    LOG.warn("listOfflineRegions error while trimming cache: {}", error);
                    listLatch.countDown();
                }
            });
            listLatch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            LOG.warn("list offline regions for cache trim failed", t);
        }
        return regions;
    }

    private void deleteOfflineRegionSync(OfflineRegion region) {
        final CountDownLatch deleteLatch = new CountDownLatch(1);
        try {
            region.delete(new OfflineRegion.OfflineRegionDeleteCallback() {
                @Override public void onDelete() { deleteLatch.countDown(); }
                @Override public void onError(String error) {
                    LOG.warn("delete old offline map region error: {}", error);
                    deleteLatch.countDown();
                }
            });
            deleteLatch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            LOG.warn("delete old offline map region failed", t);
        }
    }

    private long deleteOldestMatchingFilesUntil(long maxBytes) {
        Set<String> seen = new HashSet<>();
        List<File> files = new ArrayList<>();
        for (File root : mapCacheRoots()) collectMatchingFiles(root, seen, files);

        long total = 0L;
        for (File file : files) total += file.length();
        if (total <= maxBytes) return total;

        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                long am = a.lastModified();
                long bm = b.lastModified();
                if (am == bm) return a.getAbsolutePath().compareTo(b.getAbsolutePath());
                return am < bm ? -1 : 1;
            }
        });

        for (File file : files) {
            if (total <= maxBytes) break;
            long length = file.length();
            if (file.delete()) {
                total -= length;
                LOG.info("Deleted old map cache file {}", file.getAbsolutePath());
            } else {
                LOG.warn("Failed to delete old map cache file {}", file.getAbsolutePath());
            }
        }
        return total;
    }

    private void collectMatchingFiles(File file, Set<String> seen, List<File> out) {
        if (file == null || !file.exists()) return;
        String key;
        try { key = file.getCanonicalPath(); }
        catch (Throwable t) { key = file.getAbsolutePath(); }
        if (!seen.add(key)) return;

        boolean matched = matchesMapCacheName(file);
        if (file.isFile()) {
            if (matched) out.add(file);
            return;
        }

        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (matched) collectAllFiles(child, seen, out);
            else collectMatchingFiles(child, seen, out);
        }
    }

    private void collectAllFiles(File file, Set<String> seen, List<File> out) {
        if (file == null || !file.exists()) return;
        String key;
        try { key = file.getCanonicalPath(); }
        catch (Throwable t) { key = file.getAbsolutePath(); }
        if (!seen.add(key)) return;
        if (file.isFile()) {
            out.add(file);
            return;
        }

        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) collectAllFiles(child, seen, out);
        }
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

    private void runFileSourceOperation(String operationName, FileSourceOperation operation, boolean waitForCallback) {
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            operation.run(OfflineManager.getInstance(context), new OfflineManager.FileSourceCallback() {
                @Override
                public void onSuccess() {
                    latch.countDown();
                }

                @Override
                public void onError(String message) {
                    LOG.warn("{} failed: {}", operationName, message);
                    latch.countDown();
                }
            });
            if (waitForCallback) latch.await(FILE_SOURCE_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            LOG.warn("MapLibre file source operation {} failed", operationName, t);
        }
    }

    private interface FileSourceOperation {
        void run(OfflineManager manager, OfflineManager.FileSourceCallback callback);
    }

    private List<File> mapCacheRoots() {
        List<File> roots = new ArrayList<>();
        roots.add(context.getCacheDir());
        roots.add(context.getFilesDir());
        try { roots.add(new File(FileSource.getInternalCachePath(context))); } catch (Throwable ignore) {}
        try { roots.add(new File(FileSource.getResourcesCachePath(context))); } catch (Throwable ignore) {}
        try { roots.add(context.getNoBackupFilesDir()); } catch (Throwable ignore) {}
        try { roots.add(context.getExternalFilesDir(null)); } catch (Throwable ignore) {}
        try { roots.add(context.getDatabasePath("gpslogger-map-placeholder.db").getParentFile()); } catch (Throwable ignore) {}
        return roots;
    }

    private long sizeOfMatchingFiles(File file, Set<String> seen) {
        if (file == null || !file.exists()) return 0L;
        String key;
        try { key = file.getCanonicalPath(); }
        catch (Throwable t) { key = file.getAbsolutePath(); }
        if (!seen.add(key)) return 0L;

        boolean matched = matchesMapCacheName(file);
        if (file.isFile()) return matched ? file.length() : 0L;

        long total = 0L;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                total += matched ? sizeOfAllFiles(child, seen) : sizeOfMatchingFiles(child, seen);
            }
        }
        return total;
    }

    private long sizeOfAllFiles(File file, Set<String> seen) {
        if (file == null || !file.exists()) return 0L;
        String key;
        try { key = file.getCanonicalPath(); }
        catch (Throwable t) { key = file.getAbsolutePath(); }
        if (!seen.add(key)) return 0L;
        if (file.isFile()) return file.length();

        long total = 0L;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) total += sizeOfAllFiles(child, seen);
        }
        return total;
    }

    private boolean matchesMapCacheName(File file) {
        String name = file.getName().toLowerCase(Locale.US);
        for (String hint : MAP_CACHE_NAME_HINTS) {
            if (name.contains(hint)) return true;
        }
        return false;
    }
}

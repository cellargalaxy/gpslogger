/*
 * Travel/hiking 改造：离线地图存储抽象。
 *
 * 为何抽象：地图 SDK 选型可能在 MapLibre / VTM / 其他实现之间切换。
 * 把 createRegion / listRegions / delete / progress 抽到一个接口，
 * UI 层只依赖接口，未来换 SDK 不动 UI。
 */
package com.mendhak.gpslogger.tracker.offline;

import java.util.List;

public interface OfflineMapStore {

    class Region {
        public long id;
        public String name;
        public double minLat;
        public double minLon;
        public double maxLat;
        public double maxLon;
        public int minZoom;
        public int maxZoom;
        public long estimatedBytes;
        public long completedBytes;
        public boolean completed;
    }

    interface ProgressCallback {
        void onProgress(long regionId, long completedBytes, long totalEstimatedBytes, boolean done);
        void onError(long regionId, String message);
    }

    boolean isAvailable();

    List<Region> listRegions();

    /** 创建区域并立刻开始下载。failure 时 throws。 */
    long createRegion(String name, double minLat, double minLon, double maxLat, double maxLon,
                      int minZoom, int maxZoom, ProgressCallback callback) throws Exception;

    void cancel(long regionId);

    void delete(long regionId);

    void deleteAll();

    long totalBytes();
}

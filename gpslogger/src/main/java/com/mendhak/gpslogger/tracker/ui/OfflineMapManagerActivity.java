/*
 * Travel/hiking 改造：离线地图区域管理界面。
 *
 * 范围：首版「最小可用」。
 * - 列出已下载的离线区域（名称、边界、缩放级别）
 * - 顶部嵌入 MapView 预览将要下载的 BBox，用红色边框 polygon 可视化范围
 * - 提供「半径(km)」SeekBar 与「最大缩放」Spinner，让用户直观调整下载区域
 * - 同步展示估算瓦片数 / 体积，提示缩放语义
 * - 删除全部 / 长按单条删除
 *
 * 「在地图上手动框选」放到后续版本，避免一次性引入太多 UI 复杂度。
 */
package com.mendhak.gpslogger.tracker.ui;

import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.mendhak.gpslogger.R;
import com.mendhak.gpslogger.common.AppSettings;
import com.mendhak.gpslogger.common.Session;
import com.mendhak.gpslogger.common.slf4j.Logs;
import com.mendhak.gpslogger.tracker.TrackerPreferenceHelper;
import com.mendhak.gpslogger.tracker.offline.MapLibreOfflineMapStore;
import com.mendhak.gpslogger.tracker.offline.OfflineMapStore;
import com.mendhak.gpslogger.tracker.offline.OpenStreetMapStyle;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OfflineMapManagerActivity extends AppCompatActivity {

    private static final Logger LOG = Logs.of(OfflineMapManagerActivity.class);
    private static final int DEFAULT_MIN_ZOOM = 8;
    private static final int[] RADIUS_KM_OPTIONS = new int[]{1, 2, 5, 10, 20};
    private static final int DEFAULT_RADIUS_INDEX = 2; // 5 km
    private static final Integer[] MAX_ZOOM_OPTIONS = new Integer[]{12, 13, 14, 15, 16, 17};
    private static final int DEFAULT_MAX_ZOOM_INDEX = 3; // 15
    private static final String PREVIEW_BBOX_SOURCE_ID = "offline_map_preview_bbox_source";
    private static final String PREVIEW_BBOX_LAYER_ID = "offline_map_preview_bbox_layer";
    private static final int BBOX_OUTLINE_COLOR = 0xFFD32F2F;
    private static final long PREVIEW_TILE_BYTES = 20L * 1024L; // OSM 标准 raster 经验值，仅用于体积估算

    private TextView status;
    private TextView policyHint;
    private TextView downloadPlan;
    private TextView sizeEstimate;
    private TextView zoomExplained;
    private TextView radiusLabel;
    private TextView maxZoomLabel;
    private TextView previewUnavailable;
    private FrameLayout previewContainer;
    private SeekBar radiusSeekBar;
    private Spinner maxZoomSpinner;
    private ListView listView;
    private Button downloadButton;
    private Button deleteAllButton;
    private MapView previewMapView;
    private MapLibreMap previewMapLibreMap;
    private OfflineMapStore store;
    private final ArrayList<String> rows = new ArrayList<>();
    private final ArrayList<Long> ids = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed = false;
    private int radiusIndex = DEFAULT_RADIUS_INDEX;
    private int maxZoom = MAX_ZOOM_OPTIONS[DEFAULT_MAX_ZOOM_INDEX];
    private boolean previewReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_offline_map_manager);
        setTitle(R.string.tracker_offline_map_activity_title);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        status = findViewById(R.id.offline_map_status);
        policyHint = findViewById(R.id.offline_map_policy_hint);
        downloadPlan = findViewById(R.id.offline_map_download_plan);
        sizeEstimate = findViewById(R.id.offline_map_size_estimate);
        zoomExplained = findViewById(R.id.offline_map_zoom_explained);
        radiusLabel = findViewById(R.id.offline_map_radius_label);
        maxZoomLabel = findViewById(R.id.offline_map_max_zoom_label);
        previewContainer = findViewById(R.id.offline_map_preview_container);
        previewUnavailable = findViewById(R.id.offline_map_preview_unavailable);
        radiusSeekBar = findViewById(R.id.offline_map_radius_seekbar);
        maxZoomSpinner = findViewById(R.id.offline_map_max_zoom_spinner);
        listView = findViewById(R.id.offline_map_list);
        downloadButton = findViewById(R.id.offline_map_download);
        deleteAllButton = findViewById(R.id.offline_map_delete_all);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows);
        listView.setAdapter(adapter);

        zoomExplained.setText(R.string.tracker_offline_map_zoom_explained);
        maxZoomLabel.setText(R.string.tracker_offline_map_max_zoom_title);

        setupRadiusSeekBar();
        setupMaxZoomSpinner();
        initializePreviewMap(savedInstanceState);

        store = new MapLibreOfflineMapStore(this);
        if (!store.isAvailable()) {
            status.setText(R.string.tracker_offline_map_no_sdk);
            setControlsEnabled(false);
            return;
        }

        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= ids.size()) return true;
            long regionId = ids.get(position);
            runStoreAction(getString(R.string.tracker_offline_map_deleting), () -> store.delete(regionId));
            return true;
        });

        downloadButton.setOnClickListener(this::onDownloadCurrentArea);
        deleteAllButton.setOnClickListener(v ->
                runStoreAction(getString(R.string.tracker_offline_map_deleting), () -> store.deleteAll()));

        renderDownloadGuidance();
        refreshAsync();
    }

    private void setupRadiusSeekBar() {
        radiusSeekBar.setMax(RADIUS_KM_OPTIONS.length - 1);
        radiusSeekBar.setProgress(DEFAULT_RADIUS_INDEX);
        updateRadiusLabel();
        radiusSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                radiusIndex = Math.max(0, Math.min(progress, RADIUS_KM_OPTIONS.length - 1));
                updateRadiusLabel();
                renderDownloadGuidance();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { /* noop */ }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { /* noop */ }
        });
    }

    private void updateRadiusLabel() {
        radiusLabel.setText(getString(R.string.tracker_offline_map_radius_value_format,
                RADIUS_KM_OPTIONS[radiusIndex]));
    }

    private void setupMaxZoomSpinner() {
        ArrayAdapter<Integer> zoomAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, MAX_ZOOM_OPTIONS);
        zoomAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        maxZoomSpinner.setAdapter(zoomAdapter);
        maxZoomSpinner.setSelection(DEFAULT_MAX_ZOOM_INDEX);
        maxZoom = MAX_ZOOM_OPTIONS[DEFAULT_MAX_ZOOM_INDEX];
        maxZoomSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                maxZoom = MAX_ZOOM_OPTIONS[position];
                renderDownloadGuidance();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { /* noop */ }
        });
    }

    private void initializePreviewMap(Bundle savedInstanceState) {
        try {
            // MapLibre 与 OkHttp UA 注入必须先于 MapView 创建。
            OpenStreetMapStyle.configureMapLibreHttpClient(getApplicationContext());
            MapLibre.getInstance(getApplicationContext());

            previewMapView = new MapView(this);
            previewContainer.addView(previewMapView, 0, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            previewMapView.onCreate(savedInstanceState);
            previewMapView.getMapAsync(map -> {
                previewMapLibreMap = map;
                // 与「轨迹地图」一致，走 asset:// 协议加载预打包样式，避免 fromJson 在某些机型上的渲染卡顿。
                Style.Builder builder = new Style.Builder().fromUri(OpenStreetMapStyle.BUILTIN_ASSET_URI);
                map.setStyle(builder, style -> {
                    previewReady = true;
                    renderDownloadGuidance();
                });
            });
        } catch (Throwable t) {
            LOG.warn("Offline map preview init failed", t);
            previewReady = false;
            showPreviewUnavailable();
        }
    }

    private void showPreviewUnavailable() {
        if (previewUnavailable == null) return;
        previewUnavailable.setText(R.string.tracker_offline_map_preview_unavailable);
        previewUnavailable.setVisibility(View.VISIBLE);
    }

    private void hidePreviewUnavailable() {
        if (previewUnavailable != null) previewUnavailable.setVisibility(View.GONE);
    }

    private void onDownloadCurrentArea(View v) {
        if (TrackerPreferenceHelper.getInstance().isOfflineMapUsingPublicOpenStreetMapTiles()) {
            Toast.makeText(this, R.string.tracker_offline_map_public_osm_blocked, Toast.LENGTH_LONG).show();
            renderDownloadGuidance();
            return;
        }

        Location loc = Session.getInstance().getCurrentLocationInfo();
        if (loc == null) {
            loc = getLastKnownLocation();
        }
        if (loc == null) {
            Toast.makeText(this, R.string.tracker_offline_map_no_location, Toast.LENGTH_LONG).show();
            return;
        }

        RegionPlan plan = buildRegionPlan(loc);
        String name = "Region " + new Date();
        setBusy(getString(R.string.tracker_offline_map_creating));
        ioExecutor.execute(() -> {
            try {
                long id = store.createRegion(name,
                        plan.minLat, plan.minLon,
                        plan.maxLat, plan.maxLon,
                        plan.minZoom, plan.maxZoom,
                        new OfflineMapStore.ProgressCallback() {
                            @Override
                            public void onProgress(long regionId, long completedBytes, long totalEstimatedBytes, boolean done) {
                                runIfAlive(() -> {
                                    status.setText(getString(R.string.tracker_offline_map_download_progress_format,
                                            regionId, completedBytes));
                                    if (done) refreshAsync();
                                });
                            }

                            @Override
                            public void onError(long regionId, String message) {
                                runIfAlive(() -> Toast.makeText(OfflineMapManagerActivity.this,
                                        getString(R.string.tracker_offline_map_region_error_format, regionId, message),
                                        Toast.LENGTH_LONG).show());
                            }
                        });
                runIfAlive(() -> {
                    Toast.makeText(this,
                            getString(R.string.tracker_offline_map_region_created_format, id),
                            Toast.LENGTH_SHORT).show();
                    refreshAsync();
                });
            } catch (Throwable t) {
                LOG.warn("Create region failed", t);
                runIfAlive(() -> {
                    setControlsEnabled(true);
                    Toast.makeText(this,
                            getString(R.string.tracker_offline_map_failed_format, t.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private Location getLastKnownLocation() {
        try {
            LocationManager lm = (LocationManager) AppSettings.getInstance().getSystemService(LOCATION_SERVICE);
            if (lm == null) return null;
            Location l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l == null) l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            return l;
        } catch (SecurityException e) {
            return null;
        }
    }

    private void renderDownloadGuidance() {
        if (policyHint != null) {
            int text = TrackerPreferenceHelper.getInstance().isOfflineMapUsingPublicOpenStreetMapTiles()
                    ? R.string.tracker_offline_map_osm_online_only
                    : R.string.tracker_offline_map_provider_hint;
            policyHint.setText(text);
        }

        Location loc = Session.getInstance().getCurrentLocationInfo();
        if (loc == null) loc = getLastKnownLocation();
        if (loc == null) {
            downloadPlan.setText(R.string.tracker_offline_map_plan_no_location);
            sizeEstimate.setText("");
            clearPreviewBbox();
            showPreviewUnavailable();
            return;
        }
        hidePreviewUnavailable();

        RegionPlan plan = buildRegionPlan(loc);
        downloadPlan.setText(getString(R.string.tracker_offline_map_plan_format,
                plan.minLat, plan.minLon, plan.maxLat, plan.maxLon,
                plan.minZoom, plan.maxZoom, plan.maxZoom));

        long tileCount = estimateTileCount(plan);
        long estimatedMB = Math.max(1L, (tileCount * PREVIEW_TILE_BYTES) / (1024L * 1024L));
        sizeEstimate.setText(getString(R.string.tracker_offline_map_size_estimate_format,
                tileCount, estimatedMB));

        updatePreviewBbox(plan);
    }

    private long estimateTileCount(RegionPlan plan) {
        long total = 0;
        for (int z = plan.minZoom; z <= plan.maxZoom; z++) {
            int n = 1 << z;
            int xMin = lonToTileX(plan.minLon, z, n);
            int xMax = lonToTileX(plan.maxLon, z, n);
            int yMin = latToTileY(plan.maxLat, z, n);
            int yMax = latToTileY(plan.minLat, z, n);
            if (xMax < xMin) { int tmp = xMin; xMin = xMax; xMax = tmp; }
            if (yMax < yMin) { int tmp = yMin; yMin = yMax; yMax = tmp; }
            long w = Math.max(1, (xMax - xMin) + 1L);
            long h = Math.max(1, (yMax - yMin) + 1L);
            total += w * h;
        }
        return total;
    }

    private int lonToTileX(double lon, int z, int n) {
        double v = ((lon + 180.0) / 360.0) * n;
        return clampInt((int) Math.floor(v), 0, n - 1);
    }

    private int latToTileY(double lat, int z, int n) {
        double clampedLat = Math.max(-85.05112878, Math.min(85.05112878, lat));
        double rad = Math.toRadians(clampedLat);
        double v = (1.0 - Math.log(Math.tan(rad) + 1.0 / Math.cos(rad)) / Math.PI) / 2.0 * n;
        return clampInt((int) Math.floor(v), 0, n - 1);
    }

    private int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private void updatePreviewBbox(RegionPlan plan) {
        if (!previewReady || previewMapLibreMap == null) return;
        Style style = previewMapLibreMap.getStyle();
        if (style == null) return;

        String geojson = bboxToGeoJsonLineString(plan);
        try {
            GeoJsonSource existing = (GeoJsonSource) style.getSource(PREVIEW_BBOX_SOURCE_ID);
            if (existing != null) {
                existing.setGeoJson(geojson);
            } else {
                style.addSource(new GeoJsonSource(PREVIEW_BBOX_SOURCE_ID, geojson));
                LineLayer layer = new LineLayer(PREVIEW_BBOX_LAYER_ID, PREVIEW_BBOX_SOURCE_ID);
                layer.setProperties(
                        PropertyFactory.lineColor(BBOX_OUTLINE_COLOR),
                        PropertyFactory.lineWidth(3.0f),
                        PropertyFactory.lineOpacity(0.9f)
                );
                style.addLayer(layer);
            }
        } catch (Throwable t) {
            LOG.warn("Failed to draw offline preview BBox", t);
        }

        try {
            LatLngBounds bounds = new LatLngBounds.Builder()
                    .include(new LatLng(plan.minLat, plan.minLon))
                    .include(new LatLng(plan.maxLat, plan.maxLon))
                    .build();
            previewMapLibreMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 60));
        } catch (Throwable t) {
            // BBox 极小或异常时退化为定位中心
            double centerLat = (plan.minLat + plan.maxLat) / 2.0;
            double centerLon = (plan.minLon + plan.maxLon) / 2.0;
            previewMapLibreMap.moveCamera(CameraUpdateFactory.newCameraPosition(
                    new CameraPosition.Builder()
                            .target(new LatLng(centerLat, centerLon))
                            .zoom(9.0)
                            .build()));
        }
    }

    private void clearPreviewBbox() {
        if (!previewReady || previewMapLibreMap == null) return;
        Style style = previewMapLibreMap.getStyle();
        if (style == null) return;
        try { style.removeLayer(PREVIEW_BBOX_LAYER_ID); } catch (Throwable ignore) {}
        try { style.removeSource(PREVIEW_BBOX_SOURCE_ID); } catch (Throwable ignore) {}
    }

    private String bboxToGeoJsonLineString(RegionPlan plan) {
        // 用闭合 LineString 画矩形边框，避免 Fill 抢占视觉重点
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[");
        sb.append('[').append(plan.minLon).append(',').append(plan.minLat).append(']').append(',');
        sb.append('[').append(plan.maxLon).append(',').append(plan.minLat).append(']').append(',');
        sb.append('[').append(plan.maxLon).append(',').append(plan.maxLat).append(']').append(',');
        sb.append('[').append(plan.minLon).append(',').append(plan.maxLat).append(']').append(',');
        sb.append('[').append(plan.minLon).append(',').append(plan.minLat).append(']');
        sb.append("]},\"properties\":{}}");
        return sb.toString();
    }

    private RegionPlan buildRegionPlan(Location loc) {
        double radiusKm = RADIUS_KM_OPTIONS[radiusIndex];
        double lat = loc.getLatitude();
        double lon = loc.getLongitude();
        double latDelta = radiusKm / 111.32;
        double cos = Math.max(0.2, Math.abs(Math.cos(Math.toRadians(lat))));
        double lonDelta = radiusKm / (111.32 * cos);
        RegionPlan plan = new RegionPlan();
        plan.minLat = clamp(lat - latDelta, -85.0, 85.0);
        plan.maxLat = clamp(lat + latDelta, -85.0, 85.0);
        plan.minLon = clamp(lon - lonDelta, -180.0, 180.0);
        plan.maxLon = clamp(lon + lonDelta, -180.0, 180.0);
        plan.minZoom = DEFAULT_MIN_ZOOM;
        plan.maxZoom = Math.max(DEFAULT_MIN_ZOOM, maxZoom);
        return plan;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private void refreshAsync() {
        setBusy(getString(R.string.tracker_offline_map_loading));
        ioExecutor.execute(() -> {
            try {
                List<OfflineMapStore.Region> list = store.listRegions();
                runIfAlive(() -> renderRegions(list));
            } catch (Throwable t) {
                LOG.warn("List offline regions failed", t);
                runIfAlive(() -> {
                    setControlsEnabled(true);
                    status.setText(getString(R.string.tracker_offline_map_failed_format, t.getMessage()));
                });
            }
        });
    }

    private void renderRegions(List<OfflineMapStore.Region> list) {
        rows.clear();
        ids.clear();
        for (OfflineMapStore.Region r : list) {
            StringBuilder sb = new StringBuilder();
            sb.append('#').append(r.id);
            if (r.name != null) sb.append("  ").append(r.name);
            sb.append('\n').append(String.format(Locale.getDefault(),
                    getString(R.string.tracker_offline_map_region_row_format),
                    r.minLat, r.minLon, r.maxLat, r.maxLon, r.minZoom, r.maxZoom));
            rows.add(sb.toString());
            ids.add(r.id);
        }
        adapter.notifyDataSetChanged();
        renderDownloadGuidance();
        setControlsEnabled(true);
        status.setText(getString(R.string.tracker_offline_map_regions_format, list.size()));
    }

    private void runStoreAction(String busyText, StoreAction action) {
        setBusy(busyText);
        ioExecutor.execute(() -> {
            try {
                action.run();
                // MapLibre delete API 本身是异步回调式，延迟刷新可避免立即读到旧列表。
                mainHandler.postDelayed(() -> {
                    if (!destroyed && !isFinishing()) refreshAsync();
                }, 500);
            } catch (Throwable t) {
                LOG.warn("Offline map action failed", t);
                runIfAlive(() -> {
                    setControlsEnabled(true);
                    Toast.makeText(this,
                            getString(R.string.tracker_offline_map_failed_format, t.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void setBusy(String text) {
        status.setText(text);
        setControlsEnabled(false);
    }

    private void setControlsEnabled(boolean enabled) {
        boolean canDownload = enabled && !TrackerPreferenceHelper.getInstance().isOfflineMapUsingPublicOpenStreetMapTiles();
        downloadButton.setEnabled(canDownload);
        deleteAllButton.setEnabled(enabled);
        listView.setEnabled(enabled);
        radiusSeekBar.setEnabled(enabled);
        maxZoomSpinner.setEnabled(enabled);
    }

    private void runIfAlive(Runnable runnable) {
        runOnUiThread(() -> {
            if (!destroyed && !isFinishing()) runnable.run();
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (previewMapView != null) previewMapView.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (previewMapView != null) previewMapView.onResume();
    }

    @Override
    protected void onPause() {
        if (previewMapView != null) previewMapView.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (previewMapView != null) previewMapView.onStop();
        super.onStop();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (previewMapView != null) previewMapView.onLowMemory();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (previewMapView != null) previewMapView.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ioExecutor.shutdownNow();
        if (previewMapView != null) {
            try { previewMapView.onDestroy(); } catch (Throwable ignore) {}
            previewMapView = null;
        }
        previewMapLibreMap = null;
        super.onDestroy();
    }

    private static class RegionPlan {
        double minLat;
        double minLon;
        double maxLat;
        double maxLon;
        int minZoom;
        int maxZoom;
    }

    private interface StoreAction {
        void run();
    }
}

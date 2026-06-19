/*
 * Travel/hiking 改造：「轨迹地图」视图。
 *
 * 行为：
 * - 默认显示「现在 - 用户配置的时间范围」内的本地缓存轨迹
 * - 按用户配置的切段粒度切分并按段着色
 * - 图例可点击：默认全部高亮，点击单个图例后仅该段保持不透明
 * - 当前定位点用醒目的圆点图标叠加展示
 * - 即使无底图（断网且未预下载离线包）也能在纯色背景上绘出轨迹线
 *
 * 与 GPSLogger 主链路完全解耦：从 TrackCacheRepository 拉数据。
 */
package com.mendhak.gpslogger.tracker.ui;

import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import com.mendhak.gpslogger.R;
import com.mendhak.gpslogger.common.Session;
import com.mendhak.gpslogger.common.slf4j.Logs;
import com.mendhak.gpslogger.tracker.TrackerPreferenceHelper;
import com.mendhak.gpslogger.tracker.cache.TrackCacheRepository;
import com.mendhak.gpslogger.tracker.db.TrackPoint;
import com.mendhak.gpslogger.tracker.offline.MapLibreOfflineMapStore;
import com.mendhak.gpslogger.tracker.offline.OpenStreetMapStyle;
import com.mendhak.gpslogger.ui.fragments.display.GenericViewFragment;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.slf4j.Logger;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TrackMapViewFragment extends GenericViewFragment {

    private static final Logger LOG = Logs.of(TrackMapViewFragment.class);
    private static final String LAYER_ID_PREFIX = "track_segment_layer_";
    private static final String SOURCE_ID_PREFIX = "track_segment_source_";
    private static final String CURRENT_LOCATION_SOURCE_ID = "track_current_location_source";
    private static final String CURRENT_LOCATION_HALO_LAYER_ID = "track_current_location_halo_layer";
    private static final String CURRENT_LOCATION_DOT_LAYER_ID = "track_current_location_dot_layer";
    private static final long ALL_SEGMENTS_SELECTED = Long.MIN_VALUE;
    private static final long AUTO_CACHE_MIN_INTERVAL_MS = 5000L;
    private static final String FALLBACK_STYLE_JSON =
            "{\"version\":8,\"name\":\"GPSLogger Track Fallback\",\"sources\":{},\"layers\":["
                    + "{\"id\":\"background\",\"type\":\"background\","
                    + "\"paint\":{\"background-color\":\"#E8EAED\"}}]}";

    private MapView mapView;
    private MapLibreMap mapLibreMap;
    private LinearLayout legendBar;
    private TextView statusText;
    private SwitchCompat cacheVisibleTilesSwitch;
    private MapLibreOfflineMapStore offlineMapStore;
    private ExecutorService offlineMapExecutor;
    private boolean basemapAvailable = true;
    private boolean fallbackStyleLoaded = false;
    private boolean statusEphemeral = false;
    private long selectedSegmentIndex = ALL_SEGMENTS_SELECTED;
    private boolean autoCachingVisibleRegion = false;
    private String lastCachedVisibleRegionKey = "";
    private long lastCachedVisibleRegionAtMs = 0L;
    private boolean publicOsmCacheHintShown = false;

    private final List<String> currentSourceIds = new ArrayList<>();
    private final List<String> currentLayerIds = new ArrayList<>();
    private final List<RenderedSegment> renderedSegments = new ArrayList<>();

    public static TrackMapViewFragment newInstance() {
        return new TrackMapViewFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_track_map_view, container, false);
        FrameLayout mapContainer = root.findViewById(R.id.track_map_container);
        legendBar = root.findViewById(R.id.track_map_legend);
        statusText = root.findViewById(R.id.track_map_status);
        cacheVisibleTilesSwitch = root.findViewById(R.id.track_map_switch_cache_visible_tiles);
        offlineMapExecutor = Executors.newSingleThreadExecutor();

        ImageButton refresh = root.findViewById(R.id.track_map_btn_refresh);
        ImageButton locate = root.findViewById(R.id.track_map_btn_locate);
        ImageButton fit = root.findViewById(R.id.track_map_btn_fit);
        ImageButton layer = root.findViewById(R.id.track_map_btn_layer);

        refresh.setOnClickListener(v -> refreshTrack(false));
        locate.setOnClickListener(v -> centerOnLatest());
        fit.setOnClickListener(v -> refreshTrack(true));
        layer.setOnClickListener(v -> showMapLayerChooser());
        setupVisibleTileCacheSwitch();

        initializeMapView(mapContainer, savedInstanceState);

        return root;
    }

    private void showMapLayerChooser() {
        if (getContext() == null) return;
        String[] entries = getResources().getStringArray(R.array.tracker_offline_map_layer_entries);
        String[] values = getResources().getStringArray(R.array.tracker_offline_map_layer_values);
        if (entries.length == 0 || entries.length != values.length) return;

        String current = TrackerPreferenceHelper.getInstance().getOfflineMapStyleUrl();
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                checked = i;
                break;
            }
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.tracker_track_map_layer)
                .setSingleChoiceItems(entries, checked, (dialog, which) -> {
                    if (which < 0 || which >= values.length) return;
                    String selected = values[which];
                    if (!selected.equals(TrackerPreferenceHelper.getInstance().getOfflineMapStyleUrl())) {
                        TrackerPreferenceHelper.getInstance().setOfflineMapStyleUrl(selected);
                        publicOsmCacheHintShown = false;
                        lastCachedVisibleRegionKey = "";
                        showStatus(R.string.tracker_track_map_status_loading_style, true);
                        loadConfiguredStyle(false);
                    }
                    dialog.dismiss();
                })
                .show();
    }

    private void setupVisibleTileCacheSwitch() {
        if (cacheVisibleTilesSwitch == null) return;
        boolean cacheEnabled = false;
        try {
            cacheEnabled = TrackerPreferenceHelper.getInstance().isTrackMapVisibleTileCacheEnabled();
        } catch (Throwable t) {
            LOG.warn("Failed to read visible tile cache preference", t);
        }
        cacheVisibleTilesSwitch.setChecked(cacheEnabled);
        cacheVisibleTilesSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            try {
                TrackerPreferenceHelper.getInstance().setTrackMapVisibleTileCacheEnabled(isChecked);
            } catch (Throwable t) {
                LOG.warn("Failed to update visible tile cache preference", t);
            }
            publicOsmCacheHintShown = false;
            if (isChecked) {
                MapLibreOfflineMapStore store = getOfflineMapStoreSafely();
                if (store != null) store.enableAmbientCacheRetention();
                showStatus(R.string.tracker_track_map_cache_visible_tiles_enabled, true);
                cacheVisibleRegionIfEnabled();
            } else {
                showStatus(R.string.tracker_track_map_cache_visible_tiles_disabled, true);
            }
        });
    }

    private MapLibreOfflineMapStore getOfflineMapStoreSafely() {
        if (offlineMapStore != null) return offlineMapStore;
        try {
            offlineMapStore = new MapLibreOfflineMapStore(requireContext());
            return offlineMapStore;
        } catch (Throwable t) {
            LOG.warn("Track map offline cache store init failed", t);
            return null;
        }
    }

    private void initializeMapView(FrameLayout mapContainer, @Nullable Bundle savedInstanceState) {
        if (mapContainer == null) {
            showStatus(R.string.tracker_track_map_no_basemap);
            return;
        }
        try {
            // MapLibre 必须在创建 MapView 之前初始化。HTTP 配置失败不应影响视图打开。
            MapLibre.getInstance(requireContext().getApplicationContext());
            OpenStreetMapStyle.configureMapLibreHttpClient(requireContext().getApplicationContext());

            mapView = new MapView(requireContext());
            mapContainer.addView(mapView, 0, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            mapView.onCreate(savedInstanceState);
            showStatus(R.string.tracker_track_map_status_loading_style, true);
            mapView.addOnDidFailLoadingMapListener(errorMessage -> {
                LOG.warn("Track map style failed to load: {}", errorMessage);
                loadFallbackStyle();
            });
            mapView.addOnDidFinishLoadingStyleListener(() -> {
                LOG.info("Track map style finished loading");
                if (basemapAvailable) {
                    showStatus(R.string.tracker_track_map_status_loading_tiles, true);
                    // 10 秒后如果还没收到 idle 事件，提示用户瓦片可能下载缓慢或网络受限。
                    // 非 ephemeral：避免用户拖动地图触发 idle 后把警告抹掉。
                    mapView.postDelayed(() -> {
                        if (statusEphemeral && basemapAvailable) {
                            showStatus(R.string.tracker_track_map_status_tiles_slow, false);
                        }
                    }, 10000);
                }
            });
            mapView.addOnDidBecomeIdleListener(() -> {
                LOG.debug("Track map became idle");
                if (basemapAvailable && statusEphemeral) hideStatus();
                cacheVisibleRegionIfEnabled();
            });
            // addOnDidBecomeIdleListener 在部分机型上不稳定，再追加一个 RenderingMap 监听做兜底：
            // fully=true 代表所有可见瓦片已完成渲染，等价于「瓦片到位」。
            mapView.addOnDidFinishRenderingMapListener(fully -> {
                LOG.info("Track map finished rendering, fully={}", fully);
                if (fully && basemapAvailable && statusEphemeral) hideStatus();
            });
            mapView.getMapAsync(map -> {
                mapLibreMap = map;
                moveCameraToInitialPosition();
                loadConfiguredStyle(true);
                mapView.postDelayed(() -> {
                    if (mapLibreMap != null && mapLibreMap.getStyle() == null) {
                        LOG.warn("Track map style load timed out");
                        loadFallbackStyle();
                    }
                }, 8000);
            });
        } catch (Throwable t) {
            LOG.warn("Track map MapView init failed", t);
            if (mapView != null) {
                try { mapContainer.removeView(mapView); } catch (Throwable ignore) {}
                mapView = null;
            }
            showStatus(R.string.tracker_track_map_no_basemap);
        }
    }

    private void loadConfiguredStyle(boolean fitBounds) {
        if (mapLibreMap == null) return;
        fallbackStyleLoaded = false;
        basemapAvailable = true;
        String styleUrl = TrackerPreferenceHelper.getInstance().getOfflineMapStyleUrl();
        try {
            // 内置图层走 asset:// 协议加载预打包 style.json；自定义 URL 保持原有直连行为。
            // 实测比 Style.Builder.fromJson 更稳定，可避免 MapLibre v11 在内联 JSON 上偶发的渲染卡顿。
            String resolvedStyleUri = OpenStreetMapStyle.resolveStyleUri(styleUrl);
            Style.Builder builder = new Style.Builder().fromUri(resolvedStyleUri);
            LOG.info("Track map applying style: {}", resolvedStyleUri);
            mapLibreMap.setStyle(builder, style -> {
                LOG.info("Track map setStyle callback fired");
                basemapAvailable = true;
                refreshTrack(fitBounds);
            });
        } catch (Throwable t) {
            LOG.warn("Failed to apply configured map style {}", styleUrl, t);
            loadFallbackStyle();
        }
    }

    private void loadFallbackStyle() {
        if (mapLibreMap == null || fallbackStyleLoaded) return;
        fallbackStyleLoaded = true;
        basemapAvailable = false;
        try {
            mapLibreMap.setStyle(new Style.Builder().fromJson(FALLBACK_STYLE_JSON), style -> refreshTrack(true));
        } catch (Throwable t) {
            LOG.warn("Failed to apply fallback track map style", t);
            showStatus(R.string.tracker_track_map_no_basemap);
        }
    }

    private void centerOnLatest() {
        if (mapLibreMap == null) return;
        List<TrackPoint> points = loadPoints();
        if (points.isEmpty()) {
            showStatus(R.string.tracker_track_map_empty);
            return;
        }
        TrackPoint latest = points.get(points.size() - 1);
        mapLibreMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                new CameraPosition.Builder()
                        .target(new LatLng(latest.lat, latest.lon))
                        .zoom(Math.max(mapLibreMap.getCameraPosition().zoom, 14.0))
                        .build()));
        Style style = mapLibreMap.getStyle();
        if (style != null) addOrUpdateCurrentLocationIcon(style);
    }

    private void moveCameraToInitialPosition() {
        if (mapLibreMap == null) return;
        try {
            List<TrackPoint> points = loadPoints();
            if (!points.isEmpty()) {
                TrackPoint latest = points.get(points.size() - 1);
                mapLibreMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(latest.lat, latest.lon), 14.0));
                return;
            }
            Location loc = getCurrentLocation();
            if (loc != null) {
                mapLibreMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                        new LatLng(loc.getLatitude(), loc.getLongitude()), 14.0));
                return;
            }
            mapLibreMap.moveCamera(CameraUpdateFactory.newCameraPosition(
                    new CameraPosition.Builder().target(new LatLng(0, 0)).zoom(1.0).build()));
        } catch (Throwable t) {
            LOG.warn("Failed to move track map camera to initial position", t);
        }
    }

    private List<TrackPoint> loadPoints() {
        try {
            TrackerPreferenceHelper prefs = TrackerPreferenceHelper.getInstance();
            if (!prefs.isLocalTrackCacheEnabled()) return new ArrayList<>();
            int hours = prefs.getTrackMapTimeRangeHours();
            long now = System.currentTimeMillis();
            long from = now - hours * 3600L * 1000L;
            return TrackCacheRepository.getInstance().queryRange(from, now);
        } catch (Throwable t) {
            LOG.warn("Failed to load track map points", t);
            return new ArrayList<>();
        }
    }

    /**
     * 拉取轨迹、切段、上色、绘制。
     * @param fitBounds 是否在绘制完成后把相机适应到轨迹边界
     */
    private void refreshTrack(boolean fitBounds) {
        if (mapLibreMap == null) return;
        Style style = mapLibreMap.getStyle();
        if (style == null) return;

        // 清掉上一轮的轨迹 layer + source。当前位置图标单独维护，避免刷新时闪烁。
        for (String layerId : currentLayerIds) {
            try { style.removeLayer(layerId); } catch (Throwable ignore) {}
        }
        for (String sourceId : currentSourceIds) {
            try { style.removeSource(sourceId); } catch (Throwable ignore) {}
        }
        currentLayerIds.clear();
        currentSourceIds.clear();
        renderedSegments.clear();
        if (legendBar != null) legendBar.removeAllViews();

        List<TrackPoint> points = loadPoints();
        addOrUpdateCurrentLocationIcon(style);
        if (points.isEmpty()) {
            showStatus(R.string.tracker_track_map_empty);
            return;
        }
        if (basemapAvailable) hideStatus();
        else showStatus(R.string.tracker_track_map_no_basemap);

        int segmentMinutes = TrackerPreferenceHelper.getInstance().getTrackMapSegmentMinutes();
        long segmentMillis = segmentMinutes * 60L * 1000L;

        List<TrackSegmenter.Segment> segments = TrackSegmenter.segment(points, segmentMillis);
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

        for (TrackSegmenter.Segment seg : segments) {
            if (seg.points.size() < 2) continue;

            int renderedIndex = renderedSegments.size();
            int color = TrackSegmenter.colorForIndex(seg.segmentIndex);
            String geojson = toGeoJsonLineString(seg.points);

            String sourceId = SOURCE_ID_PREFIX + renderedIndex;
            String layerId = LAYER_ID_PREFIX + renderedIndex;
            try {
                style.addSource(new GeoJsonSource(sourceId, geojson));
                LineLayer layer = new LineLayer(layerId, sourceId);
                layer.setProperties(
                        PropertyFactory.lineColor(color),
                        PropertyFactory.lineWidth(4.0f),
                        PropertyFactory.lineOpacity(0.9f)
                );
                style.addLayer(layer);
                currentSourceIds.add(sourceId);
                currentLayerIds.add(layerId);

                TextView chip = addLegendChip(seg.segmentIndex, color,
                        fmt.format(new Date(seg.startMs)) + " - " + fmt.format(new Date(seg.endMs)));
                renderedSegments.add(new RenderedSegment(seg.segmentIndex, layerId, color, chip));
            } catch (Throwable t) {
                LOG.warn("Failed to add track segment layer {}", renderedIndex, t);
            }
        }
        addOrUpdateCurrentLocationIcon(style);
        applySegmentSelection();

        if (fitBounds) {
            LatLngBounds.Builder b = new LatLngBounds.Builder();
            for (TrackPoint p : points) b.include(new LatLng(p.lat, p.lon));
            try {
                mapLibreMap.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 80));
            } catch (Throwable t) {
                // 极少数情况下点数过少，bounds 退化为单点，newLatLngBounds 会抛
                TrackPoint p = points.get(points.size() - 1);
                mapLibreMap.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(p.lat, p.lon), 14.0));
            }
        }
    }

    private TextView addLegendChip(long segmentIndex, int color, String text) {
        TextView chip = new TextView(getContext());
        chip.setText("■ " + text);
        chip.setTextColor(color);
        chip.setPadding(12, 4, 12, 4);
        chip.setBackgroundColor(Color.argb(40, 0, 0, 0));
        chip.setOnClickListener(v -> {
            selectedSegmentIndex = selectedSegmentIndex == segmentIndex ? ALL_SEGMENTS_SELECTED : segmentIndex;
            applySegmentSelection();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(8);
        if (legendBar != null) legendBar.addView(chip, lp);
        return chip;
    }

    private void applySegmentSelection() {
        if (mapLibreMap == null) return;
        Style style = mapLibreMap.getStyle();
        if (style == null) return;

        if (selectedSegmentIndex != ALL_SEGMENTS_SELECTED) {
            boolean exists = false;
            for (RenderedSegment segment : renderedSegments) {
                if (segment.segmentIndex == selectedSegmentIndex) {
                    exists = true;
                    break;
                }
            }
            if (!exists) selectedSegmentIndex = ALL_SEGMENTS_SELECTED;
        }

        for (RenderedSegment segment : renderedSegments) {
            boolean active = selectedSegmentIndex == ALL_SEGMENTS_SELECTED
                    || segment.segmentIndex == selectedSegmentIndex;
            try {
                LineLayer layer = (LineLayer) style.getLayer(segment.layerId);
                if (layer != null) {
                    layer.setProperties(PropertyFactory.lineOpacity(active ? 0.9f : 0.22f));
                }
            } catch (Throwable t) {
                LOG.debug("Failed to update track segment opacity", t);
            }
            if (segment.chip != null) {
                segment.chip.setTextColor(withAlpha(segment.color, active ? 255 : 85));
                segment.chip.setBackgroundColor(active && selectedSegmentIndex != ALL_SEGMENTS_SELECTED
                        ? Color.argb(65, 0, 0, 0)
                        : Color.argb(40, 0, 0, 0));
            }
        }
    }

    private int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private void addOrUpdateCurrentLocationIcon(Style style) {
        Location loc = getCurrentLocation();
        if (loc == null) {
            removeCurrentLocationIcon(style);
            return;
        }
        String geojson = toGeoJsonPoint(loc.getLatitude(), loc.getLongitude());
        try {
            GeoJsonSource source = (GeoJsonSource) style.getSource(CURRENT_LOCATION_SOURCE_ID);
            if (source != null) {
                source.setGeoJson(geojson);
            } else {
                style.addSource(new GeoJsonSource(CURRENT_LOCATION_SOURCE_ID, geojson));
            }
            // 每次重画都把当前位置图层放回最上层，避免被新轨迹线盖住。
            try { style.removeLayer(CURRENT_LOCATION_DOT_LAYER_ID); } catch (Throwable ignore) {}
            try { style.removeLayer(CURRENT_LOCATION_HALO_LAYER_ID); } catch (Throwable ignore) {}

            CircleLayer halo = new CircleLayer(CURRENT_LOCATION_HALO_LAYER_ID, CURRENT_LOCATION_SOURCE_ID);
            halo.setProperties(
                    PropertyFactory.circleColor(0xFF1E88E5),
                    PropertyFactory.circleRadius(18.0f),
                    PropertyFactory.circleOpacity(0.22f),
                    PropertyFactory.circleStrokeColor(0xFFFFFFFF),
                    PropertyFactory.circleStrokeWidth(2.0f)
            );
            style.addLayer(halo);

            CircleLayer dot = new CircleLayer(CURRENT_LOCATION_DOT_LAYER_ID, CURRENT_LOCATION_SOURCE_ID);
            dot.setProperties(
                    PropertyFactory.circleColor(0xFF0D47A1),
                    PropertyFactory.circleRadius(7.0f),
                    PropertyFactory.circleOpacity(1.0f),
                    PropertyFactory.circleStrokeColor(0xFFFFFFFF),
                    PropertyFactory.circleStrokeWidth(2.5f)
            );
            style.addLayer(dot);
        } catch (Throwable t) {
            LOG.warn("Failed to draw current location icon", t);
        }
    }

    private void removeCurrentLocationIcon(Style style) {
        try { style.removeLayer(CURRENT_LOCATION_DOT_LAYER_ID); } catch (Throwable ignore) {}
        try { style.removeLayer(CURRENT_LOCATION_HALO_LAYER_ID); } catch (Throwable ignore) {}
        try { style.removeSource(CURRENT_LOCATION_SOURCE_ID); } catch (Throwable ignore) {}
    }

    private Location getCurrentLocation() {
        try {
            return Session.getInstance().getCurrentLocationInfo();
        } catch (Throwable t) {
            LOG.debug("No current location available for track map", t);
            return null;
        }
    }

    private void cacheVisibleRegionIfEnabled() {
        boolean cacheEnabled;
        try {
            cacheEnabled = TrackerPreferenceHelper.getInstance().isTrackMapVisibleTileCacheEnabled();
        } catch (Throwable t) {
            LOG.warn("Failed to read visible tile cache preference", t);
            return;
        }
        if (!cacheEnabled) return;
        if (mapLibreMap == null || offlineMapExecutor == null) return;
        if (mapLibreMap.getStyle() == null) return;

        MapLibreOfflineMapStore store = getOfflineMapStoreSafely();
        if (store == null) return;

        // MapLibre 的 ambient cache 会保存用户实际浏览过的瓦片；开关打开时同步应用用户配置的缓存上限。
        store.enableAmbientCacheRetention();

        if (TrackerPreferenceHelper.getInstance().isOfflineMapUsingPublicTileLayer()) {
            if (!publicOsmCacheHintShown) {
                showStatus(R.string.tracker_track_map_cache_visible_tiles_ambient_only, true);
                publicOsmCacheHintShown = true;
            }
            return;
        }

        if (autoCachingVisibleRegion) return;
        long now = System.currentTimeMillis();
        if (now - lastCachedVisibleRegionAtMs < AUTO_CACHE_MIN_INTERVAL_MS) return;

        LatLngBounds bounds;
        double zoom;
        try {
            bounds = mapLibreMap.getProjection().getVisibleRegion().latLngBounds;
            zoom = mapLibreMap.getCameraPosition().zoom;
        } catch (Throwable t) {
            LOG.debug("Visible region unavailable for offline caching", t);
            return;
        }
        if (bounds == null || bounds.getSouthWest() == null || bounds.getNorthEast() == null) return;

        String key = visibleRegionKey(bounds, zoom);
        if (key.equals(lastCachedVisibleRegionKey)) return;
        lastCachedVisibleRegionKey = key;
        lastCachedVisibleRegionAtMs = now;

        LatLng sw = bounds.getSouthWest();
        LatLng ne = bounds.getNorthEast();
        final double minLat = Math.min(sw.getLatitude(), ne.getLatitude());
        final double maxLat = Math.max(sw.getLatitude(), ne.getLatitude());
        final double minLon = Math.min(sw.getLongitude(), ne.getLongitude());
        final double maxLon = Math.max(sw.getLongitude(), ne.getLongitude());
        final int minZoom = Math.max(0, (int) Math.floor(zoom));
        final int maxZoom = Math.min(19, Math.max(minZoom, (int) Math.ceil(zoom) + 1));
        final String name = "Viewed " + new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());

        autoCachingVisibleRegion = true;
        showStatus(R.string.tracker_track_map_cache_visible_tiles_saving, true);
        try {
            offlineMapExecutor.execute(() -> {
                try {
                    store.createRegion(name, minLat, minLon, maxLat, maxLon, minZoom, maxZoom, null);
                    postToMapView(() -> showStatus(R.string.tracker_track_map_cache_visible_tiles_saved, true));
                } catch (Throwable t) {
                    LOG.warn("Auto-cache visible map region failed", t);
                    postToMapView(() -> showStatus(R.string.tracker_track_map_cache_visible_tiles_failed, false));
                } finally {
                    autoCachingVisibleRegion = false;
                }
            });
        } catch (Throwable t) {
            autoCachingVisibleRegion = false;
            LOG.warn("Failed to schedule visible map region cache", t);
        }
    }

    private String visibleRegionKey(LatLngBounds bounds, double zoom) {
        LatLng sw = bounds.getSouthWest();
        LatLng ne = bounds.getNorthEast();
        return String.format(Locale.US, "%.4f:%.4f:%.4f:%.4f:%d",
                sw.getLatitude(), sw.getLongitude(), ne.getLatitude(), ne.getLongitude(), (int) Math.floor(zoom));
    }

    private void postToMapView(Runnable runnable) {
        if (mapView == null) return;
        mapView.post(() -> {
            if (mapView != null && getContext() != null) runnable.run();
        });
    }

    private void showStatus(int textResId) {
        showStatus(textResId, false);
    }

    /**
     * @param ephemeral 是否是「加载中」类临时态文案。idle 监听只在临时态下自动隐藏，
     *                  防止用户左右拖动时把「无轨迹/无底图」这类持久信息一起抹掉。
     */
    private void showStatus(int textResId, boolean ephemeral) {
        if (statusText == null) return;
        statusText.setText(textResId);
        statusText.setVisibility(View.VISIBLE);
        statusEphemeral = ephemeral;
    }

    private void hideStatus() {
        statusEphemeral = false;
        if (statusText != null) statusText.setVisibility(View.GONE);
    }

    private String toGeoJsonLineString(List<double[]> latLngs) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[");
        for (int i = 0; i < latLngs.size(); i++) {
            double[] ll = latLngs.get(i);
            if (i > 0) sb.append(',');
            sb.append('[').append(ll[1]).append(',').append(ll[0]).append(']');
        }
        sb.append("]},\"properties\":{}}");
        return sb.toString();
    }

    private String toGeoJsonPoint(double lat, double lon) {
        return "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":["
                + lon + ',' + lat + "]},\"properties\":{}}";
    }

    @Override
    public void onStart() {
        super.onStart();
        runMapViewLifecycle("start", view -> view.onStart());
    }

    @Override
    public void onResume() {
        super.onResume();
        runMapViewLifecycle("resume", view -> view.onResume());
        // 进入视图时顺手做一次清理。清理失败只影响缓存维护，不应导致地图页打不开。
        try {
            TrackCacheRepository.getInstance().cleanupExpired();
        } catch (Throwable t) {
            LOG.warn("Track cache cleanup failed", t);
        }
    }

    @Override
    public void onPause() {
        runMapViewLifecycle("pause", view -> view.onPause());
        super.onPause();
    }

    @Override
    public void onStop() {
        runMapViewLifecycle("stop", view -> view.onStop());
        super.onStop();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        runMapViewLifecycle("low memory", view -> view.onLowMemory());
    }

    @Override
    public void onDestroyView() {
        mapLibreMap = null;
        if (offlineMapExecutor != null) {
            offlineMapExecutor.shutdownNow();
            offlineMapExecutor = null;
        }
        offlineMapStore = null;
        runMapViewLifecycle("destroy", view -> view.onDestroy());
        mapView = null;
        super.onDestroyView();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        runMapViewLifecycle("save state", view -> view.onSaveInstanceState(outState));
    }

    private void runMapViewLifecycle(String action, MapViewAction actionRunner) {
        if (mapView == null) return;
        try {
            actionRunner.run(mapView);
        } catch (Throwable t) {
            LOG.warn("Track map MapView {} failed", action, t);
        }
    }

    private interface MapViewAction {
        void run(MapView view);
    }

    private static class RenderedSegment {
        final long segmentIndex;
        final String layerId;
        final int color;
        final TextView chip;

        RenderedSegment(long segmentIndex, String layerId, int color, TextView chip) {
            this.segmentIndex = segmentIndex;
            this.layerId = layerId;
            this.color = color;
            this.chip = chip;
        }
    }
}

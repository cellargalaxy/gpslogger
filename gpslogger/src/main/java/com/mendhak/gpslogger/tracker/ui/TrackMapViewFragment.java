/*
 * Travel/hiking 改造：「轨迹地图」视图。
 *
 * 行为：
 * - 默认显示「现在 - 用户配置的时间范围」内的本地缓存轨迹
 * - 按用户配置的切段粒度切分并按段着色
 * - 即使无底图（断网且未预下载离线包）也能在纯色背景上绘出轨迹线
 *
 * 与 GPSLogger 主链路完全解耦：从 TrackCacheRepository 拉数据。
 */
package com.mendhak.gpslogger.tracker.ui;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.mendhak.gpslogger.R;
import com.mendhak.gpslogger.common.slf4j.Logs;
import com.mendhak.gpslogger.tracker.TrackerPreferenceHelper;
import com.mendhak.gpslogger.tracker.cache.TrackCacheRepository;
import com.mendhak.gpslogger.tracker.db.TrackPoint;
import com.mendhak.gpslogger.ui.fragments.display.GenericViewFragment;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.slf4j.Logger;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class TrackMapViewFragment extends GenericViewFragment {

    private static final Logger LOG = Logs.of(TrackMapViewFragment.class);
    private static final String LAYER_ID_PREFIX = "track_segment_layer_";
    private static final String SOURCE_ID_PREFIX = "track_segment_source_";

    private MapView mapView;
    private MapLibreMap mapLibreMap;
    private LinearLayout legendBar;
    private TextView statusText;

    private final List<String> currentSourceIds = new ArrayList<>();
    private final List<String> currentLayerIds = new ArrayList<>();

    public static TrackMapViewFragment newInstance() {
        return new TrackMapViewFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // MapLibre 必须在 inflate MapView 之前初始化。多次调用安全。
        try {
            MapLibre.getInstance(requireContext().getApplicationContext());
        } catch (Throwable t) {
            LOG.warn("MapLibre init failed", t);
        }
        View root = inflater.inflate(R.layout.fragment_track_map_view, container, false);
        mapView = root.findViewById(R.id.track_map_view);
        legendBar = root.findViewById(R.id.track_map_legend);
        statusText = root.findViewById(R.id.track_map_status);

        Button refresh = root.findViewById(R.id.track_map_btn_refresh);
        Button locate = root.findViewById(R.id.track_map_btn_locate);
        Button fit = root.findViewById(R.id.track_map_btn_fit);

        refresh.setOnClickListener(v -> refreshTrack(false));
        locate.setOnClickListener(v -> centerOnLatest());
        fit.setOnClickListener(v -> refreshTrack(true));

        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(map -> {
            mapLibreMap = map;
            String styleUrl = TrackerPreferenceHelper.getInstance().getOfflineMapStyleUrl();
            map.setStyle(new Style.Builder().fromUri(styleUrl), style -> refreshTrack(true));
        });

        return root;
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
    }

    private List<TrackPoint> loadPoints() {
        int hours = TrackerPreferenceHelper.getInstance().getTrackMapTimeRangeHours();
        long now = System.currentTimeMillis();
        long from = now - hours * 3600L * 1000L;
        return TrackCacheRepository.getInstance().queryRange(from, now);
    }

    /**
     * 拉取轨迹、切段、上色、绘制。
     * @param fitBounds 是否在绘制完成后把相机适应到轨迹边界
     */
    private void refreshTrack(boolean fitBounds) {
        if (mapLibreMap == null) return;
        Style style = mapLibreMap.getStyle();
        if (style == null) return;

        // 清掉上一轮的 layer + source
        for (String layerId : currentLayerIds) {
            try { style.removeLayer(layerId); } catch (Throwable ignore) {}
        }
        for (String sourceId : currentSourceIds) {
            try { style.removeSource(sourceId); } catch (Throwable ignore) {}
        }
        currentLayerIds.clear();
        currentSourceIds.clear();
        legendBar.removeAllViews();

        List<TrackPoint> points = loadPoints();
        if (points.isEmpty()) {
            showStatus(R.string.tracker_track_map_empty);
            return;
        }
        hideStatus();

        int segmentMinutes = TrackerPreferenceHelper.getInstance().getTrackMapSegmentMinutes();
        long segmentMillis = segmentMinutes * 60L * 1000L;

        List<TrackSegmenter.Segment> segments = TrackSegmenter.segment(points, segmentMillis);
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

        for (int i = 0; i < segments.size(); i++) {
            TrackSegmenter.Segment seg = segments.get(i);
            if (seg.points.size() < 2) continue;

            int color = TrackSegmenter.colorForIndex(seg.segmentIndex);
            String geojson = toGeoJsonLineString(seg.points);

            String sourceId = SOURCE_ID_PREFIX + i;
            String layerId = LAYER_ID_PREFIX + i;
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
            } catch (Throwable t) {
                LOG.warn("Failed to add track segment layer {}", i, t);
            }

            addLegendChip(color, fmt.format(new Date(seg.startMs)) + " - " + fmt.format(new Date(seg.endMs)));
        }

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

    private void addLegendChip(int color, String text) {
        if (legendBar == null) return;
        TextView chip = new TextView(getContext());
        chip.setText("■ " + text);
        chip.setTextColor(color);
        chip.setPadding(12, 4, 12, 4);
        chip.setBackgroundColor(Color.argb(40, 0, 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(8);
        legendBar.addView(chip, lp);
    }

    private void showStatus(int textResId) {
        if (statusText == null) return;
        statusText.setText(textResId);
        statusText.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
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

    @Override
    public void onStart() {
        super.onStart();
        if (mapView != null) mapView.onStart();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
        // 进入视图时顺手做一次清理
        TrackCacheRepository.getInstance().cleanupExpired();
    }

    @Override
    public void onPause() {
        if (mapView != null) mapView.onPause();
        super.onPause();
    }

    @Override
    public void onStop() {
        if (mapView != null) mapView.onStop();
        super.onStop();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (mapView != null) mapView.onLowMemory();
    }

    @Override
    public void onDestroyView() {
        if (mapView != null) mapView.onDestroy();
        super.onDestroyView();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapView != null) mapView.onSaveInstanceState(outState);
    }
}

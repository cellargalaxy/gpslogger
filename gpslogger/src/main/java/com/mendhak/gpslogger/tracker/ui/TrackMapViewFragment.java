/*
 * Travel/hiking 改造：「轨迹地图」视图。
 *
 * 行为：
 * - 默认显示「现在 - 用户配置的时间范围」内的本地缓存轨迹
 * - 按用户配置的切段粒度切分并按段着色
 * - 顶部「轨迹」下拉列出各分段（色块 + 时间范围）：默认「全部分段」全部高亮，选择单个分段后仅该段保持不透明
 * - 当前定位点用醒目的圆点图标叠加展示
 * - 即使无底图（断网且未预下载离线包）也能在纯色背景上绘出轨迹线
 *
 * 与 GPSLogger 主链路完全解耦：从 TrackCacheRepository 拉数据。
 */
package com.mendhak.gpslogger.tracker.ui;

import android.content.Context;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import com.mendhak.gpslogger.R;
import com.mendhak.gpslogger.common.PreferenceHelper;
import com.mendhak.gpslogger.common.Session;
import com.mendhak.gpslogger.common.slf4j.Logs;
import com.mendhak.gpslogger.tracker.TrackerPreferenceHelper;
import com.mendhak.gpslogger.tracker.cache.TrackCacheRepository;
import com.mendhak.gpslogger.tracker.db.TrackPoint;
import com.mendhak.gpslogger.loggers.Files;
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
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class TrackMapViewFragment extends GenericViewFragment {

    private static final Logger LOG = Logs.of(TrackMapViewFragment.class);
    private static final String LAYER_ID_PREFIX = "track_segment_layer_";
    private static final String SOURCE_ID_PREFIX = "track_segment_source_";
    private static final String CURRENT_LOCATION_SOURCE_ID = "track_current_location_source";
    private static final String CURRENT_LOCATION_HALO_LAYER_ID = "track_current_location_halo_layer";
    private static final String CURRENT_LOCATION_DOT_LAYER_ID = "track_current_location_dot_layer";
    private static final String SEARCH_RESULT_SOURCE_ID = "track_search_result_source";
    private static final String SEARCH_RESULT_HALO_LAYER_ID = "track_search_result_halo_layer";
    private static final String SEARCH_RESULT_DOT_LAYER_ID = "track_search_result_dot_layer";
    private static final String KML_LAYER_ID_PREFIX = "kml_track_layer_";
    private static final String KML_SOURCE_ID_PREFIX = "kml_track_source_";
    // 约定的 KML 存放目录：<gpslogger_folder>/kml/（应用外部专属目录，读取无需运行时权限）。
    private static final String KML_SUBFOLDER = "kml";
    // KML 轨迹配色：与历史分段调色板区分开，用于区分不同 KML 文件；再叠加虚线与历史实线区分。
    private static final int[] KML_PALETTE = new int[]{
            0xFF00BFA5, // teal
            0xFFFF6D00, // deep orange
            0xFFAA00FF, // purple
            0xFF2962FF, // blue
            0xFFC51162, // pink
            0xFF64DD17  // light green
    };
    private static final long ALL_SEGMENTS_SELECTED = Long.MIN_VALUE;
    private static final long AUTO_CACHE_MIN_INTERVAL_MS = 5000L;
    private static final long NOMINATIM_MIN_INTERVAL_MS = 1000L;
    private static final OkHttpClient NOMINATIM_CLIENT = new OkHttpClient();
    private static final int TOOLBAR_MODE_CONTROLS = 0;
    private static final int TOOLBAR_MODE_CONFIG = 1;
    private static final int TOOLBAR_MODE_SEARCH = 2;
    private static final int TOOLBAR_MODE_TRACK = 3;
    private static final String FALLBACK_STYLE_JSON =
            "{\"version\":8,\"name\":\"GPSLogger Track Fallback\",\"sources\":{},\"layers\":["
                    + "{\"id\":\"background\",\"type\":\"background\","
                    + "\"paint\":{\"background-color\":\"#E8EAED\"}}]}";

    private MapView mapView;
    private MapLibreMap mapLibreMap;
    private Spinner trackSelector;
    private TrackOptionAdapter trackAdapter;
    private boolean suppressTrackSelectionCallback = false;
    private TextView statusText;
    private SwitchCompat cacheVisibleTilesSwitch;
    private MapLibreOfflineMapStore offlineMapStore;
    private ExecutorService offlineMapExecutor;
    private Call activeSearchCall;
    private boolean basemapAvailable = true;
    private boolean fallbackStyleLoaded = false;
    private boolean statusEphemeral = false;
    private long selectedSegmentIndex = ALL_SEGMENTS_SELECTED;
    private boolean autoCachingVisibleRegion = false;
    private String lastCachedVisibleRegionKey = "";
    private long lastCachedVisibleRegionAtMs = 0L;
    private long lastNominatimSearchAtMs = 0L;
    private boolean publicOsmCacheHintShown = false;
    // 外部导入 KML 后待自动展示的文件名；地图就绪后消费一次即清空。
    private String pendingKmlFileName = null;

    private final List<String> currentSourceIds = new ArrayList<>();
    private final List<String> currentLayerIds = new ArrayList<>();
    private final List<RenderedSegment> renderedSegments = new ArrayList<>();
    private final List<TrackOption> trackOptions = new ArrayList<>();
    // 已叠加的 KML 图层/源 id，以及当前展示的 KML 文件名（用于对话框回显勾选）。
    private final List<String> kmlLayerIds = new ArrayList<>();
    private final List<String> kmlSourceIds = new ArrayList<>();
    private final List<String> selectedKmlFileNames = new ArrayList<>();

    public static TrackMapViewFragment newInstance() {
        return new TrackMapViewFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_track_map_view, container, false);
        FrameLayout mapContainer = root.findViewById(R.id.track_map_container);
        statusText = root.findViewById(R.id.track_map_status);
        cacheVisibleTilesSwitch = root.findViewById(R.id.track_map_switch_cache_visible_tiles);
        offlineMapExecutor = Executors.newSingleThreadExecutor();

        // 若由「外部打开 KML」跳转而来，取出待展示的文件名（一次性，取后即清）。
        try {
            String pending = TrackerPreferenceHelper.getInstance().getPendingKmlImportName();
            if (pending != null && !pending.isEmpty()) {
                pendingKmlFileName = pending;
                TrackerPreferenceHelper.getInstance().clearPendingKmlImportName();
            }
        } catch (Throwable t) {
            LOG.warn("Failed to read pending KML import name", t);
        }

        Spinner toolbarMode = root.findViewById(R.id.track_map_toolbar_mode);
        LinearLayout toolbarControls = root.findViewById(R.id.track_map_toolbar_controls);
        LinearLayout toolbarConfig = root.findViewById(R.id.track_map_toolbar_config);
        LinearLayout toolbarSearch = root.findViewById(R.id.track_map_toolbar_search);
        LinearLayout toolbarTrack = root.findViewById(R.id.track_map_toolbar_track);
        trackSelector = root.findViewById(R.id.track_map_track_selector);
        EditText searchText = root.findViewById(R.id.track_map_search_text);
        ImageButton search = root.findViewById(R.id.track_map_btn_search);
        ImageButton refresh = root.findViewById(R.id.track_map_btn_refresh);
        ImageButton locate = root.findViewById(R.id.track_map_btn_locate);
        ImageButton fit = root.findViewById(R.id.track_map_btn_fit);
        ImageButton layer = root.findViewById(R.id.track_map_btn_layer);
        ImageButton kml = root.findViewById(R.id.track_map_btn_kml);

        search.setOnClickListener(v -> searchPlace(searchText));
        searchText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchPlace(searchText);
                return true;
            }
            return false;
        });
        refresh.setOnClickListener(v -> refreshTrack(false));
        locate.setOnClickListener(v -> centerOnLatest());
        fit.setOnClickListener(v -> refreshTrack(true));
        layer.setOnClickListener(v -> showMapLayerChooser());
        kml.setOnClickListener(v -> showKmlChooser());
        setupToolbarModeSelector(toolbarMode, toolbarControls, toolbarConfig, toolbarSearch, toolbarTrack);
        setupTrackSelector();
        setupVisibleTileCacheSwitch();

        initializeMapView(mapContainer, savedInstanceState);

        return root;
    }

    private void setupToolbarModeSelector(Spinner toolbarMode, LinearLayout controls,
                                          LinearLayout config, LinearLayout search, LinearLayout track) {
        if (toolbarMode == null) return;
        try {
            ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(requireContext(),
                    R.array.tracker_track_map_toolbar_mode_entries,
                    android.R.layout.simple_spinner_item);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            toolbarMode.setAdapter(adapter);
        } catch (Throwable t) {
            LOG.warn("Track map toolbar mode adapter init failed", t);
        }
        toolbarMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                applyToolbarMode(position, controls, config, search, track);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                applyToolbarMode(TOOLBAR_MODE_CONTROLS, controls, config, search, track);
            }
        });
        toolbarMode.setSelection(TOOLBAR_MODE_CONTROLS);
        applyToolbarMode(TOOLBAR_MODE_CONTROLS, controls, config, search, track);
    }

    private void applyToolbarMode(int mode, LinearLayout controls, LinearLayout config,
                                  LinearLayout search, LinearLayout track) {
        if (controls != null) controls.setVisibility(mode == TOOLBAR_MODE_CONTROLS ? View.VISIBLE : View.GONE);
        if (config != null) config.setVisibility(mode == TOOLBAR_MODE_CONFIG ? View.VISIBLE : View.GONE);
        if (search != null) search.setVisibility(mode == TOOLBAR_MODE_SEARCH ? View.VISIBLE : View.GONE);
        if (track != null) track.setVisibility(mode == TOOLBAR_MODE_TRACK ? View.VISIBLE : View.GONE);
    }

    /** 初始化顶部「轨迹」下拉：默认仅含「全部分段」，随刷新动态填充各分段。 */
    private void setupTrackSelector() {
        if (trackSelector == null) return;
        trackAdapter = new TrackOptionAdapter(requireContext(), trackOptions);
        trackSelector.setAdapter(trackAdapter);
        trackSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // 重建下拉时会以编程方式设置选中项，需抑制自触发，避免误改选中态。
                if (suppressTrackSelectionCallback) return;
                if (position < 0 || position >= trackOptions.size()) return;
                selectedSegmentIndex = trackOptions.get(position).segmentIndex;
                applySegmentSelection();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        rebuildTrackSelector();
    }

    /**
     * 依据当前已渲染分段重建下拉项，并把选中项对齐到 selectedSegmentIndex。
     * 需在 applySegmentSelection() 归一化选中态之后调用。
     */
    private void rebuildTrackSelector() {
        if (trackSelector == null || trackAdapter == null) return;
        trackOptions.clear();
        trackOptions.add(new TrackOption(ALL_SEGMENTS_SELECTED, 0,
                getString(R.string.tracker_track_map_track_all)));
        for (RenderedSegment seg : renderedSegments) {
            trackOptions.add(new TrackOption(seg.segmentIndex, seg.color, seg.label));
        }

        int position = 0;
        for (int i = 0; i < trackOptions.size(); i++) {
            if (trackOptions.get(i).segmentIndex == selectedSegmentIndex) {
                position = i;
                break;
            }
        }
        // 选中的分段可能已不存在（如刷新后段数变化），回落到实际选项，避免选中态与下拉不一致。
        selectedSegmentIndex = trackOptions.get(position).segmentIndex;

        suppressTrackSelectionCallback = true;
        trackAdapter.notifyDataSetChanged();
        trackSelector.setSelection(position);
        // setSelection 会把 onItemSelected 投递到消息队列，post 到队尾再解除抑制确保覆盖该回调。
        trackSelector.post(() -> suppressTrackSelectionCallback = false);
    }

    /** dp 转 px，用于下拉行内边距。 */
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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

    /**
     * 弹出 KML 多选对话框：条目为约定目录下预扫描到的 *.kml 文件，
     * 已展示的文件回显为勾选态，每条前置该文件在地图上的色块，便于对照。
     */
    private void showKmlChooser() {
        if (getContext() == null) return;
        File dir = getKmlFolder();
        final File[] files = listKmlFiles(dir);
        if (files.length == 0) {
            String path = dir == null ? "" : dir.getAbsolutePath();
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.tracker_track_map_kml_overlay)
                    .setMessage(getString(R.string.tracker_track_map_kml_empty, path))
                    .setNeutralButton(R.string.tracker_track_map_kml_open_folder,
                            (dialog, w) -> openKmlFolderInFileManager())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        final CharSequence[] labels = new CharSequence[files.length];
        final boolean[] checked = new boolean[files.length];
        for (int i = 0; i < files.length; i++) {
            String name = files[i].getName();
            SpannableStringBuilder sb = new SpannableStringBuilder("■ ");
            sb.setSpan(new ForegroundColorSpan(kmlColorForIndex(i)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(name);
            labels[i] = sb;
            checked[i] = selectedKmlFileNames.contains(name);
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.tracker_track_map_kml_overlay)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, (dialog, w) -> {
                    List<KmlPick> picks = new ArrayList<>();
                    for (int i = 0; i < files.length; i++) {
                        if (checked[i]) picks.add(new KmlPick(files[i], kmlColorForIndex(i)));
                    }
                    applyKmlSelection(picks);
                })
                .setNeutralButton(R.string.tracker_track_map_kml_open_folder,
                        (dialog, w) -> openKmlFolderInFileManager())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 在系统文件管理器中打开 KML 约定目录，便于用户直接管理文件。 */
    private void openKmlFolderInFileManager() {
        File dir = getKmlFolder();
        if (dir == null || getContext() == null) return;
        boolean opened = false;
        // Files.openFolderInFileManager 基于 DocumentsContract，要求 Android 11(R) 及以上。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            opened = Files.openFolderInFileManager(requireContext(), dir);
        }
        if (!opened) {
            // 低版本或无可用文件管理器时，退回把路径提示给用户。
            Toast.makeText(requireContext(),
                    getString(R.string.tracker_track_map_kml_folder_path, dir.getAbsolutePath()),
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 外部导入 KML 后由 refreshTrack() 触发一次：在约定目录里找到该文件并自动叠加展示。
     * 消费一次即清空 pendingKmlFileName，避免后续刷新重复触发。
     */
    private void autoDisplayPendingKml() {
        if (pendingKmlFileName == null) return;
        String name = pendingKmlFileName;
        pendingKmlFileName = null;
        File[] files = listKmlFiles(getKmlFolder());
        for (int i = 0; i < files.length; i++) {
            if (files[i].getName().equals(name)) {
                List<KmlPick> picks = new ArrayList<>();
                picks.add(new KmlPick(files[i], kmlColorForIndex(i)));
                applyKmlSelection(picks);
                return;
            }
        }
        LOG.warn("Pending KML file not found in folder: {}", name);
    }

    /** 记录选择、后台解析 KML，解析完回到地图线程重绘叠加层。空选择直接清空。 */
    private void applyKmlSelection(List<KmlPick> picks) {
        if (mapLibreMap == null || mapLibreMap.getStyle() == null) return;
        selectedKmlFileNames.clear();
        for (KmlPick p : picks) selectedKmlFileNames.add(p.file.getName());

        if (picks.isEmpty()) {
            removeKmlLayers(mapLibreMap.getStyle());
            showStatus(R.string.tracker_track_map_kml_cleared, true);
            return;
        }
        if (offlineMapExecutor == null) return;
        showStatus(R.string.tracker_track_map_kml_loading, true);

        final List<KmlPick> finalPicks = new ArrayList<>(picks);
        offlineMapExecutor.execute(() -> {
            List<KmlRender> renders = new ArrayList<>();
            int failed = 0;
            for (KmlPick pick : finalPicks) {
                try {
                    List<List<double[]>> tracks = KmlTrackReader.getTracks(pick.file);
                    KmlRender render = new KmlRender(pick.color);
                    for (List<double[]> line : tracks) {
                        if (line.size() < 2) continue;
                        render.geojsonLines.add(toGeoJsonLineString(line));
                        render.points.addAll(line);
                    }
                    if (!render.geojsonLines.isEmpty()) renders.add(render);
                } catch (Throwable t) {
                    LOG.warn("Failed to read KML file {}", pick.file.getName(), t);
                    failed++;
                }
            }
            final int finalFailed = failed;
            postToMapView(() -> renderKmlOverlays(renders, finalFailed));
        });
    }

    /** 地图线程：清掉旧 KML 图层后逐文件重绘（虚线 + 各文件独立色），并把相机适应到 KML 边界。 */
    private void renderKmlOverlays(List<KmlRender> renders, int failed) {
        if (mapLibreMap == null) return;
        Style style = mapLibreMap.getStyle();
        if (style == null) return;
        removeKmlLayers(style);

        int drawn = 0;
        LatLngBounds.Builder bounds = new LatLngBounds.Builder();
        boolean hasBounds = false;
        for (int f = 0; f < renders.size(); f++) {
            KmlRender render = renders.get(f);
            for (int p = 0; p < render.geojsonLines.size(); p++) {
                String sourceId = KML_SOURCE_ID_PREFIX + f + "_" + p;
                String layerId = KML_LAYER_ID_PREFIX + f + "_" + p;
                try {
                    style.addSource(new GeoJsonSource(sourceId, render.geojsonLines.get(p)));
                    LineLayer layer = new LineLayer(layerId, sourceId);
                    layer.setProperties(
                            PropertyFactory.lineColor(render.color),
                            PropertyFactory.lineWidth(5.0f),
                            PropertyFactory.lineOpacity(0.95f),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                            PropertyFactory.lineDasharray(new Float[]{2.0f, 2.0f})
                    );
                    style.addLayer(layer);
                    kmlSourceIds.add(sourceId);
                    kmlLayerIds.add(layerId);
                    drawn++;
                } catch (Throwable t) {
                    LOG.warn("Failed to add KML layer {}", layerId, t);
                }
            }
            for (double[] ll : render.points) {
                bounds.include(new LatLng(ll[0], ll[1]));
                hasBounds = true;
            }
        }
        // 当前定位点重新置顶，避免被 KML 线盖住。
        addOrUpdateCurrentLocationIcon(style);

        if (drawn == 0) {
            showStatus(R.string.tracker_track_map_kml_none_valid, false);
            return;
        }
        if (hasBounds) {
            try {
                mapLibreMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 80));
            } catch (Throwable ignore) {
                // bounds 退化等极端情况忽略，图层已绘制即可。
            }
        }
        if (failed > 0) {
            showStatus(R.string.tracker_track_map_kml_failed, false);
        } else {
            showStatus(getString(R.string.tracker_track_map_kml_loaded, renders.size()), true);
        }
    }

    private void removeKmlLayers(Style style) {
        if (style == null) return;
        for (String layerId : kmlLayerIds) {
            try { style.removeLayer(layerId); } catch (Throwable ignore) {}
        }
        for (String sourceId : kmlSourceIds) {
            try { style.removeSource(sourceId); } catch (Throwable ignore) {}
        }
        kmlLayerIds.clear();
        kmlSourceIds.clear();
    }

    /** 约定的 KML 目录 <gpslogger_folder>/kml/，不存在则创建；失败返回 null。 */
    private File getKmlFolder() {
        try {
            String base = PreferenceHelper.getInstance().getGpsLoggerFolder();
            if (base == null || base.isEmpty()) return null;
            File dir = new File(base, KML_SUBFOLDER);
            if (!dir.exists()) dir.mkdirs();
            return dir;
        } catch (Throwable t) {
            LOG.warn("Failed to resolve KML folder", t);
            return null;
        }
    }

    /** 列出目录下的 *.kml 文件，按文件名不区分大小写升序，保证颜色映射稳定。 */
    private File[] listKmlFiles(File dir) {
        if (dir == null || !dir.isDirectory()) return new File[]{};
        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.US).endsWith(".kml"));
        if (files == null) return new File[]{};
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return files;
    }

    private static int kmlColorForIndex(int index) {
        return KML_PALETTE[Math.floorMod(index, KML_PALETTE.length)];
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

    private void searchPlace(EditText searchText) {
        if (searchText == null) return;
        String query = searchText.getText() == null ? "" : searchText.getText().toString().trim();
        if (query.length() == 0) {
            showStatus(R.string.tracker_track_map_search_empty);
            return;
        }
        if (mapLibreMap == null) {
            showStatus(R.string.tracker_track_map_search_map_not_ready);
            return;
        }
        if (activeSearchCall != null) {
            showStatus(R.string.tracker_track_map_search_in_progress, true);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastNominatimSearchAtMs < NOMINATIM_MIN_INTERVAL_MS) {
            showStatus(R.string.tracker_track_map_search_wait, true);
            return;
        }

        hideKeyboard(searchText);
        lastNominatimSearchAtMs = now;
        showStatus(R.string.tracker_track_map_search_in_progress, true);

        String url;
        try {
            url = buildNominatimSearchUrl(query);
        } catch (Throwable t) {
            showStatus(R.string.tracker_track_map_search_failed);
            return;
        }

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", OpenStreetMapStyle.buildUserAgent(requireContext().getApplicationContext()))
                .header("Accept", "application/json")
                .header("Accept-Language", Locale.getDefault().toLanguageTag())
                .build();

        activeSearchCall = NOMINATIM_CLIENT.newCall(request);
        activeSearchCall.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                postToMapView(() -> {
                    if (call == activeSearchCall) activeSearchCall = null;
                    if (call.isCanceled()) return;
                    LOG.warn("Nominatim place search failed", e);
                    showStatus(R.string.tracker_track_map_search_failed);
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                List<SearchResult> results = new ArrayList<>();
                String errorMessage = null;
                try (ResponseBody body = response.body()) {
                    if (!response.isSuccessful() || body == null) {
                        errorMessage = "HTTP " + response.code();
                    } else {
                        results = parseNominatimResults(body.string());
                    }
                } catch (Throwable t) {
                    LOG.warn("Nominatim place search response parse failed", t);
                    errorMessage = t.toString();
                }

                final List<SearchResult> finalResults = results;
                final String finalErrorMessage = errorMessage;
                postToMapView(() -> {
                    if (call == activeSearchCall) activeSearchCall = null;
                    if (call.isCanceled()) return;
                    if (finalErrorMessage != null) {
                        LOG.warn("Nominatim place search returned {}", finalErrorMessage);
                        showStatus(R.string.tracker_track_map_search_failed);
                        return;
                    }
                    showSearchResults(finalResults);
                });
            }
        });
    }

    private String buildNominatimSearchUrl(String query) throws Exception {
        String baseUrl = TrackerPreferenceHelper.getInstance().getTrackMapNominatimSearchUrl();
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + separator + "format=jsonv2&limit=5&q=" + URLEncoder.encode(query, "UTF-8");
    }

    private List<SearchResult> parseNominatimResults(String responseBody) throws Exception {
        List<SearchResult> results = new ArrayList<>();
        JSONArray arr = new JSONArray(responseBody);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.getJSONObject(i);
            double lat = Double.parseDouble(obj.getString("lat"));
            double lon = Double.parseDouble(obj.getString("lon"));
            String displayName = obj.optString("display_name",
                    String.format(Locale.US, "%.6f, %.6f", lat, lon));
            results.add(new SearchResult(displayName, lat, lon));
        }
        return results;
    }

    private void showSearchResults(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            showStatus(R.string.tracker_track_map_search_no_results);
            return;
        }
        if (results.size() == 1) {
            moveToSearchResult(results.get(0));
            return;
        }
        String[] labels = new String[results.size()];
        for (int i = 0; i < results.size(); i++) labels[i] = results.get(i).displayName;
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.tracker_track_map_search_results)
                .setItems(labels, (dialog, which) -> {
                    if (which >= 0 && which < results.size()) moveToSearchResult(results.get(which));
                })
                .show();
    }

    private void moveToSearchResult(SearchResult result) {
        if (mapLibreMap == null || result == null) return;
        LatLng target = new LatLng(result.lat, result.lon);
        mapLibreMap.animateCamera(CameraUpdateFactory.newLatLngZoom(target,
                Math.max(mapLibreMap.getCameraPosition().zoom, 14.0)));
        Style style = mapLibreMap.getStyle();
        if (style != null) addOrUpdateSearchResultMarker(style, result.lat, result.lon);
        showStatus(getString(R.string.tracker_track_map_search_result_format, result.displayName), true);
    }

    private void hideKeyboard(View view) {
        try {
            InputMethodManager imm = (InputMethodManager) requireContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        } catch (Throwable ignore) {}
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

        List<TrackPoint> points = loadPoints();
        addOrUpdateCurrentLocationIcon(style);
        // KML 叠加与历史点无关：即便无历史轨迹，也要把外部导入的 KML 展示出来。
        autoDisplayPendingKml();
        if (points.isEmpty()) {
            rebuildTrackSelector();
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

                String label = fmt.format(new Date(seg.startMs)) + " - " + fmt.format(new Date(seg.endMs));
                renderedSegments.add(new RenderedSegment(seg.segmentIndex, layerId, color, label));
            } catch (Throwable t) {
                LOG.warn("Failed to add track segment layer {}", renderedIndex, t);
            }
        }
        addOrUpdateCurrentLocationIcon(style);
        // 先归一化选中态（可能因分段变化回落到「全部分段」），再据此重建下拉。
        applySegmentSelection();
        rebuildTrackSelector();

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
        }
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

    private void addOrUpdateSearchResultMarker(Style style, double lat, double lon) {
        String geojson = toGeoJsonPoint(lat, lon);
        try {
            GeoJsonSource source = (GeoJsonSource) style.getSource(SEARCH_RESULT_SOURCE_ID);
            if (source != null) {
                source.setGeoJson(geojson);
            } else {
                style.addSource(new GeoJsonSource(SEARCH_RESULT_SOURCE_ID, geojson));
            }
            try { style.removeLayer(SEARCH_RESULT_DOT_LAYER_ID); } catch (Throwable ignore) {}
            try { style.removeLayer(SEARCH_RESULT_HALO_LAYER_ID); } catch (Throwable ignore) {}

            CircleLayer halo = new CircleLayer(SEARCH_RESULT_HALO_LAYER_ID, SEARCH_RESULT_SOURCE_ID);
            halo.setProperties(
                    PropertyFactory.circleColor(0xFFE53935),
                    PropertyFactory.circleRadius(20.0f),
                    PropertyFactory.circleOpacity(0.18f),
                    PropertyFactory.circleStrokeColor(0xFFFFFFFF),
                    PropertyFactory.circleStrokeWidth(2.0f)
            );
            style.addLayer(halo);

            CircleLayer dot = new CircleLayer(SEARCH_RESULT_DOT_LAYER_ID, SEARCH_RESULT_SOURCE_ID);
            dot.setProperties(
                    PropertyFactory.circleColor(0xFFB71C1C),
                    PropertyFactory.circleRadius(7.0f),
                    PropertyFactory.circleOpacity(1.0f),
                    PropertyFactory.circleStrokeColor(0xFFFFFFFF),
                    PropertyFactory.circleStrokeWidth(2.5f)
            );
            style.addLayer(dot);
        } catch (Throwable t) {
            LOG.warn("Failed to draw search result marker", t);
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

    private void showStatus(String text, boolean ephemeral) {
        if (statusText == null) return;
        statusText.setText(text);
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
        if (activeSearchCall != null) {
            activeSearchCall.cancel();
            activeSearchCall = null;
        }
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

    private static class SearchResult {
        final String displayName;
        final double lat;
        final double lon;

        SearchResult(String displayName, double lat, double lon) {
            this.displayName = displayName;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private static class RenderedSegment {
        final long segmentIndex;
        final String layerId;
        final int color;
        final String label;

        RenderedSegment(long segmentIndex, String layerId, int color, String label) {
            this.segmentIndex = segmentIndex;
            this.layerId = layerId;
            this.color = color;
            this.label = label;
        }
    }

    /** 用户在 KML 对话框中勾选的一个文件及其分配到的颜色。 */
    private static class KmlPick {
        final File file;
        final int color;

        KmlPick(File file, int color) {
            this.file = file;
            this.color = color;
        }
    }

    /** 单个 KML 文件解析后的可绘制数据：颜色、各折线的 GeoJSON、用于适应边界的全部点。 */
    private static class KmlRender {
        final int color;
        final List<String> geojsonLines = new ArrayList<>();
        final List<double[]> points = new ArrayList<>();

        KmlRender(int color) {
            this.color = color;
        }
    }

    /** 顶部「轨迹」下拉的一项：「全部分段」或某个具体分段。 */
    private static class TrackOption {
        final long segmentIndex;
        final int color;
        final String label;

        TrackOption(long segmentIndex, int color, String label) {
            this.segmentIndex = segmentIndex;
            this.color = color;
            this.label = label;
        }
    }

    /**
     * 「轨迹」下拉的自定义适配器：分段项前置对应段色的方块，时间文字沿用主题前景色以保证对比度；
     * 「全部分段」项不显示色块。下拉展开态放大内边距/字号，便于点击与阅读。
     */
    private class TrackOptionAdapter extends ArrayAdapter<TrackOption> {
        TrackOptionAdapter(Context context, List<TrackOption> data) {
            super(context, 0, data);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return buildRow(position, convertView, false);
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return buildRow(position, convertView, true);
        }

        private View buildRow(int position, View convertView, boolean dropDown) {
            TextView tv = (convertView instanceof TextView) ? (TextView) convertView : new TextView(getContext());
            tv.setSingleLine(true);
            tv.setGravity(Gravity.CENTER_VERTICAL);
            int padH = dp(dropDown ? 16 : 8);
            int padV = dp(dropDown ? 14 : 6);
            tv.setPadding(padH, padV, padH, padV);
            tv.setTextSize(dropDown ? 16f : 14f);

            TrackOption opt = getItem(position);
            if (opt == null) {
                tv.setText("");
                return tv;
            }
            if (opt.segmentIndex == ALL_SEGMENTS_SELECTED) {
                tv.setText(opt.label);
            } else {
                SpannableStringBuilder sb = new SpannableStringBuilder("■ ");
                sb.setSpan(new ForegroundColorSpan(opt.color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append(opt.label);
                tv.setText(sb);
            }
            return tv;
        }
    }
}

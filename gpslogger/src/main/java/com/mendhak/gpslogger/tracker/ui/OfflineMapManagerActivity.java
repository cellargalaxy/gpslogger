/*
 * Travel/hiking 改造：离线地图区域管理界面。
 *
 * 范围：首版「最小可用」。
 * - 列出已下载的离线区域（名称、边界、缩放级别）
 * - 触发「下载当前位置周边一小块」作为入门用法
 * - 删除全部 / 单条删除
 *
 * 「按当前视野下载」「手动框选」放到后续版本，避免一次性引入太多 UI 复杂度。
 */
package com.mendhak.gpslogger.tracker.ui;

import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.mendhak.gpslogger.R;
import com.mendhak.gpslogger.common.AppSettings;
import com.mendhak.gpslogger.common.Session;
import com.mendhak.gpslogger.common.slf4j.Logs;
import com.mendhak.gpslogger.tracker.offline.MapLibreOfflineMapStore;
import com.mendhak.gpslogger.tracker.offline.OfflineMapStore;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OfflineMapManagerActivity extends AppCompatActivity {

    private static final Logger LOG = Logs.of(OfflineMapManagerActivity.class);
    private static final double DEFAULT_RADIUS_DEG = 0.05; // 约 5km

    private TextView status;
    private ListView listView;
    private Button downloadButton;
    private Button deleteAllButton;
    private OfflineMapStore store;
    private final ArrayList<String> rows = new ArrayList<>();
    private final ArrayList<Long> ids = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_offline_map_manager);
        setTitle(R.string.tracker_offline_map_activity_title);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        status = findViewById(R.id.offline_map_status);
        listView = findViewById(R.id.offline_map_list);
        downloadButton = findViewById(R.id.offline_map_download);
        deleteAllButton = findViewById(R.id.offline_map_delete_all);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows);
        listView.setAdapter(adapter);

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

        refreshAsync();
    }

    private void onDownloadCurrentArea(View v) {
        Location loc = Session.getInstance().getCurrentLocationInfo();
        if (loc == null) {
            loc = getLastKnownLocation();
        }
        if (loc == null) {
            Toast.makeText(this, R.string.tracker_offline_map_no_location, Toast.LENGTH_LONG).show();
            return;
        }

        double lat = loc.getLatitude();
        double lon = loc.getLongitude();
        String name = "Region " + new Date();
        setBusy(getString(R.string.tracker_offline_map_creating));
        ioExecutor.execute(() -> {
            try {
                long id = store.createRegion(name,
                        lat - DEFAULT_RADIUS_DEG, lon - DEFAULT_RADIUS_DEG,
                        lat + DEFAULT_RADIUS_DEG, lon + DEFAULT_RADIUS_DEG,
                        8, 15,
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
        downloadButton.setEnabled(enabled);
        deleteAllButton.setEnabled(enabled);
        listView.setEnabled(enabled);
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
    protected void onDestroy() {
        destroyed = true;
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    private interface StoreAction {
        void run();
    }
}

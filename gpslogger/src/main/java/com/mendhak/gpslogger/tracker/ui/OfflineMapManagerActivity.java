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

public class OfflineMapManagerActivity extends AppCompatActivity {

    private static final Logger LOG = Logs.of(OfflineMapManagerActivity.class);
    private static final double DEFAULT_RADIUS_DEG = 0.05; // 约 5km

    private TextView status;
    private ListView listView;
    private OfflineMapStore store;
    private final ArrayList<String> rows = new ArrayList<>();
    private final ArrayList<Long> ids = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_offline_map_manager);
        setTitle(R.string.tracker_offline_map_activity_title);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        status = findViewById(R.id.offline_map_status);
        listView = findViewById(R.id.offline_map_list);
        Button download = findViewById(R.id.offline_map_download);
        Button deleteAll = findViewById(R.id.offline_map_delete_all);

        store = new MapLibreOfflineMapStore(this);
        if (!store.isAvailable()) {
            status.setText(R.string.tracker_offline_map_no_sdk);
            download.setEnabled(false);
            deleteAll.setEnabled(false);
            return;
        }

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows);
        listView.setAdapter(adapter);
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            long regionId = ids.get(position);
            store.delete(regionId);
            refresh();
            return true;
        });

        download.setOnClickListener(this::onDownloadCurrentArea);
        deleteAll.setOnClickListener(v -> {
            store.deleteAll();
            refresh();
        });

        refresh();
    }

    private void onDownloadCurrentArea(View v) {
        try {
            Location loc = Session.getInstance().getCurrentLocationInfo();
            if (loc == null) {
                loc = getLastKnownLocation();
            }
            if (loc == null) {
                Toast.makeText(this, "No current location available", Toast.LENGTH_LONG).show();
                return;
            }
            double lat = loc.getLatitude();
            double lon = loc.getLongitude();
            String name = "Region " + new Date();
            // ProgressCallback 有 onProgress / onError 两个抽象方法，不是 SAM 接口，
            // 不能用 lambda；这里用匿名类。
            long id = store.createRegion(name,
                    lat - DEFAULT_RADIUS_DEG, lon - DEFAULT_RADIUS_DEG,
                    lat + DEFAULT_RADIUS_DEG, lon + DEFAULT_RADIUS_DEG,
                    8, 15,
                    new OfflineMapStore.ProgressCallback() {
                        @Override
                        public void onProgress(long regionId, long completedBytes, long totalEstimatedBytes, boolean done) {
                            runOnUiThread(() -> {
                                status.setText("Downloading region " + regionId + ": " + completedBytes + " bytes");
                                if (done) refresh();
                            });
                        }

                        @Override
                        public void onError(long regionId, String message) {
                            runOnUiThread(() ->
                                    Toast.makeText(OfflineMapManagerActivity.this,
                                            "Region " + regionId + " error: " + message,
                                            Toast.LENGTH_LONG).show());
                        }
                    });
            Toast.makeText(this, "Region created: " + id, Toast.LENGTH_SHORT).show();
            refresh();
        } catch (Throwable t) {
            LOG.warn("Create region failed", t);
            Toast.makeText(this, "Failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
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

    private void refresh() {
        List<OfflineMapStore.Region> list = store.listRegions();
        rows.clear();
        ids.clear();
        for (OfflineMapStore.Region r : list) {
            StringBuilder sb = new StringBuilder();
            sb.append("#").append(r.id);
            if (r.name != null) sb.append("  ").append(r.name);
            sb.append('\n').append(String.format(
                    "BBox %.4f,%.4f - %.4f,%.4f  zoom %d-%d",
                    r.minLat, r.minLon, r.maxLat, r.maxLon, r.minZoom, r.maxZoom));
            rows.add(sb.toString());
            ids.add(r.id);
        }
        adapter.notifyDataSetChanged();
        status.setText("Regions: " + list.size() + "  (long-press to delete one)");
    }
}

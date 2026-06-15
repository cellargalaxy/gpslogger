/*
 * Travel/hiking 改造：轨迹与地图设置入口的 PreferenceFragment。
 * - 本地轨迹缓存：开关 + 保留时间 + 手动清空（二次确认）
 * - 轨迹地图：切段粒度 + 默认时间范围
 * - 离线地图：缓存大小展示 + 最大地图缓存大小 + 清空全部缓存（二次确认）+ 样式 URL
 */
package com.mendhak.gpslogger.tracker.ui;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.mendhak.gpslogger.R;
import com.mendhak.gpslogger.tracker.TrackerPreferenceHelper;
import com.mendhak.gpslogger.tracker.TrackerPreferenceNames;
import com.mendhak.gpslogger.tracker.cache.TrackCacheRepository;
import com.mendhak.gpslogger.tracker.offline.MapLibreOfflineMapStore;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TrackerSettingsFragment extends PreferenceFragmentCompat {

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Preference offlineMapCacheSize;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.pref_tracker, rootKey);

        // 用当前值更新各 ListPreference 的 summary，否则用户看不到选中项
        bindSummary(TrackerPreferenceNames.LOCAL_TRACK_CACHE_RETENTION_HOURS);
        bindSummary(TrackerPreferenceNames.TRACK_MAP_SEGMENT_MINUTES);
        bindSummary(TrackerPreferenceNames.TRACK_MAP_TIME_RANGE_HOURS);
        bindSummary(TrackerPreferenceNames.OFFLINE_MAP_MAX_CACHE_MB);
        bindSummary(TrackerPreferenceNames.OFFLINE_MAP_STYLE_URL);

        Preference clear = findPreference("tracker_clear_local_track_cache");
        if (clear != null) clear.setOnPreferenceClickListener(p -> {
            confirmThen(R.string.tracker_local_cache_clear_confirm_title,
                    R.string.tracker_local_cache_clear_confirm_message,
                    () -> {
                        int n = TrackCacheRepository.getInstance().clearAll();
                        Toast.makeText(getContext(),
                                getString(R.string.tracker_local_cache_cleared) + " (" + n + ")",
                                Toast.LENGTH_SHORT).show();
                    });
            return true;
        });


        offlineMapCacheSize = findPreference("tracker_offline_map_cache_size");
        Preference clearOfflineMap = findPreference("tracker_clear_offline_map_cache");
        if (clearOfflineMap != null) clearOfflineMap.setOnPreferenceClickListener(p -> {
            confirmThen(R.string.tracker_offline_map_clear_confirm_title,
                    R.string.tracker_offline_map_clear_confirm_message,
                    this::clearOfflineMapCacheAsync);
            return true;
        });
        refreshOfflineMapCacheSize();

        // 用户改保留时间时立刻触发一次清理
        Preference retention = findPreference(TrackerPreferenceNames.LOCAL_TRACK_CACHE_RETENTION_HOURS);
        if (retention != null) retention.setOnPreferenceChangeListener((p, v) -> {
            // ListPreference 在 onPreferenceChange 时还没写入，summary 用 v 即可；
            // 旧 helper 下次读时会自然取到新值
            bindListSummary((ListPreference) p, String.valueOf(v));
            TrackCacheRepository.getInstance().cleanupExpired();
            return true;
        });
        Preference segment = findPreference(TrackerPreferenceNames.TRACK_MAP_SEGMENT_MINUTES);
        if (segment != null) segment.setOnPreferenceChangeListener((p, v) -> {
            bindListSummary((ListPreference) p, String.valueOf(v));
            return true;
        });
        Preference timeRange = findPreference(TrackerPreferenceNames.TRACK_MAP_TIME_RANGE_HOURS);
        if (timeRange != null) timeRange.setOnPreferenceChangeListener((p, v) -> {
            bindListSummary((ListPreference) p, String.valueOf(v));
            return true;
        });
        Preference offlineMapMaxCache = findPreference(TrackerPreferenceNames.OFFLINE_MAP_MAX_CACHE_MB);
        if (offlineMapMaxCache != null) offlineMapMaxCache.setOnPreferenceChangeListener((p, v) -> {
            bindListSummary((ListPreference) p, String.valueOf(v));
            trimOfflineMapCacheAsync(maxCacheBytesFromValue(v));
            return true;
        });
        Preference offlineMapStyle = findPreference(TrackerPreferenceNames.OFFLINE_MAP_STYLE_URL);
        if (offlineMapStyle != null) offlineMapStyle.setOnPreferenceChangeListener((p, v) -> {
            bindListSummary((ListPreference) p, String.valueOf(v));
            return true;
        });

        // 显示一下 TrackerPreferenceHelper 已经规整后的值，确认配置生效
        TrackerPreferenceHelper.getInstance();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshOfflineMapCacheSize();
    }

    @Override
    public void onDestroy() {
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    private void bindSummary(String key) {
        Preference p = findPreference(key);
        if (p instanceof ListPreference) {
            bindListSummary((ListPreference) p, ((ListPreference) p).getValue());
        }
    }

    private void bindListSummary(ListPreference lp, String value) {
        if (lp == null) return;
        CharSequence[] entries = lp.getEntries();
        CharSequence[] values = lp.getEntryValues();
        if (entries == null || values == null) return;
        for (int i = 0; i < values.length; i++) {
            if (values[i].toString().equals(value)) {
                lp.setSummary(entries[i]);
                return;
            }
        }
        if (value != null && !value.trim().isEmpty()) lp.setSummary(value.trim());
    }

    private void confirmThen(int titleRes, int messageRes, Runnable confirmedAction) {
        Context context = getContext();
        if (context == null) return;
        new AlertDialog.Builder(context)
                .setTitle(titleRes)
                .setMessage(messageRes)
                .setPositiveButton(R.string.ok, (dialog, which) -> confirmedAction.run())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void refreshOfflineMapCacheSize() {
        if (offlineMapCacheSize == null || getContext() == null) return;
        offlineMapCacheSize.setSummary(R.string.tracker_offline_map_cache_size_calculating);
        Context appContext = requireContext().getApplicationContext();
        ioExecutor.execute(() -> {
            long bytes = new MapLibreOfflineMapStore(appContext).totalBytes();
            mainHandler.post(() -> {
                if (!isAdded() || offlineMapCacheSize == null) return;
                offlineMapCacheSize.setSummary(getString(R.string.tracker_offline_map_cache_size_summary_format,
                        formatBytes(bytes)));
            });
        });
    }

    private void trimOfflineMapCacheAsync(long maxBytes) {
        if (getContext() == null) return;
        Context appContext = requireContext().getApplicationContext();
        ioExecutor.execute(() -> {
            MapLibreOfflineMapStore store = new MapLibreOfflineMapStore(appContext);
            long bytes = store.trimToLimit(maxBytes);
            mainHandler.post(() -> {
                if (!isAdded() || offlineMapCacheSize == null) return;
                offlineMapCacheSize.setSummary(getString(R.string.tracker_offline_map_cache_size_summary_format,
                        formatBytes(bytes)));
            });
        });
    }

    private long maxCacheBytesFromValue(Object value) {
        int mb = TrackerPreferenceNames.DEFAULT_OFFLINE_MAP_MAX_CACHE_MB;
        if (value != null) {
            try {
                mb = Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignore) {
                mb = TrackerPreferenceNames.DEFAULT_OFFLINE_MAP_MAX_CACHE_MB;
            }
        }
        if (mb <= 0) mb = TrackerPreferenceNames.DEFAULT_OFFLINE_MAP_MAX_CACHE_MB;
        return mb * 1024L * 1024L;
    }

    private void clearOfflineMapCacheAsync() {
        if (getContext() == null) return;
        Context appContext = requireContext().getApplicationContext();
        Toast.makeText(getContext(), R.string.tracker_offline_map_deleting, Toast.LENGTH_SHORT).show();
        ioExecutor.execute(() -> {
            MapLibreOfflineMapStore store = new MapLibreOfflineMapStore(appContext);
            store.deleteAll();
            long bytes = store.totalBytes();
            mainHandler.post(() -> {
                if (!isAdded()) return;
                Toast.makeText(getContext(), R.string.tracker_offline_map_cache_cleared, Toast.LENGTH_SHORT).show();
                if (offlineMapCacheSize != null) {
                    offlineMapCacheSize.setSummary(getString(R.string.tracker_offline_map_cache_size_summary_format,
                            formatBytes(bytes)));
                }
            });
        });
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(Locale.getDefault(), "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format(Locale.getDefault(), "%.1f MB", mb);
        return String.format(Locale.getDefault(), "%.1f GB", mb / 1024.0);
    }
}

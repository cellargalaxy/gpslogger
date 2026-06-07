/*
 * Travel/hiking 改造：旅行模式设置入口的 PreferenceFragment。
 * - 本地轨迹缓存：开关 + 保留时间 + 手动清空
 * - 轨迹地图：切段粒度 + 默认时间范围
 * - Custom URL Outbox：开关 + 重试上限 + 容量 + 失败保留天数 + 队列入口
 * - 离线地图：上限 + 样式 URL + 管理入口
 */
package com.mendhak.gpslogger.tracker.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.mendhak.gpslogger.R;
import com.mendhak.gpslogger.tracker.TrackerPreferenceHelper;
import com.mendhak.gpslogger.tracker.TrackerPreferenceNames;
import com.mendhak.gpslogger.tracker.cache.TrackCacheRepository;

public class TrackerSettingsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.pref_tracker, rootKey);

        // 用当前值更新各 ListPreference 的 summary，否则用户看不到选中项
        bindSummary(TrackerPreferenceNames.LOCAL_TRACK_CACHE_RETENTION_HOURS);
        bindSummary(TrackerPreferenceNames.TRACK_MAP_SEGMENT_MINUTES);
        bindSummary(TrackerPreferenceNames.TRACK_MAP_TIME_RANGE_HOURS);

        Preference clear = findPreference("tracker_clear_local_track_cache");
        if (clear != null) clear.setOnPreferenceClickListener(p -> {
            int n = TrackCacheRepository.getInstance().clearAll();
            Toast.makeText(getContext(),
                    getString(R.string.tracker_local_cache_cleared) + " (" + n + ")",
                    Toast.LENGTH_SHORT).show();
            return true;
        });

        Preference openOutbox = findPreference("tracker_open_outbox_queue");
        if (openOutbox != null) openOutbox.setOnPreferenceClickListener(p -> {
            startActivity(new Intent(getContext(), OutboxQueueActivity.class));
            return true;
        });

        Preference openOfflineMap = findPreference("tracker_open_offline_map_manager");
        if (openOfflineMap != null) openOfflineMap.setOnPreferenceClickListener(p -> {
            startActivity(new Intent(getContext(), OfflineMapManagerActivity.class));
            return true;
        });

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

        // 显示一下 TrackerPreferenceHelper 已经规整后的值，确认配置生效
        TrackerPreferenceHelper.getInstance();
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
    }
}

/*
 * Travel/hiking 改造：接收「用其他应用打开 KML」的入口。
 *
 * 在文件管理器里打开 .kml 文件、选择 GPSLogger 时进入本页：
 * - 把传入的 KML（content:// 或 file://）复制到约定目录 <gpslogger_folder>/kml/；
 * - 记下文件名，跳转到主界面的「轨迹地图」视图并在地图上自动展示该轨迹。
 *
 * 采用独立的半透明中转 Activity（与 ProfileLinkReceiverActivity 相同范式），
 * 避免污染 launchMode=singleTask 的主界面。
 */
package com.mendhak.gpslogger.tracker.ui;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.mendhak.gpslogger.GpsMainActivity;
import com.mendhak.gpslogger.R;
import com.mendhak.gpslogger.common.IntentConstants;
import com.mendhak.gpslogger.common.PreferenceHelper;
import com.mendhak.gpslogger.common.slf4j.Logs;
import com.mendhak.gpslogger.loggers.Streams;
import com.mendhak.gpslogger.tracker.TrackerPreferenceHelper;

import org.slf4j.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

public class KmlImportActivity extends AppCompatActivity {

    private static final Logger LOG = Logs.of(KmlImportActivity.class);
    private static final String KML_SUBFOLDER = "kml";

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Uri uri = resolveIncomingUri(getIntent());
        if (uri == null) {
            LOG.warn("KML import: no data URI in intent");
            Toast.makeText(this, R.string.tracker_track_map_kml_import_failed, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        LOG.info("Received a KML file to import: {}", uri);
        // 复制可能耗时（大文件），放后台线程；期间本 Activity 存活，content URI 读权限有效。
        final Uri finalUri = uri;
        new Thread(() -> importKml(finalUri)).start();
    }

    /** 支持 VIEW（getData）与 SEND（EXTRA_STREAM）两种入口。 */
    @Nullable
    private Uri resolveIncomingUri(@Nullable Intent intent) {
        if (intent == null) return null;
        Uri data = intent.getData();
        if (data != null) return data;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            return intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        return null;
    }

    private void importKml(Uri uri) {
        try {
            File dir = new File(PreferenceHelper.getInstance().getGpsLoggerFolder(), KML_SUBFOLDER);
            if (!dir.exists() && !dir.mkdirs()) {
                LOG.warn("KML import: failed to create folder {}", dir.getAbsolutePath());
            }

            String fileName = resolveKmlFileName(uri);
            // 同名视为同一文件，直接覆盖，避免重复导入产生副本堆积。
            File dest = new File(dir, fileName);

            long copied = 0;
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) throw new IOException("openInputStream returned null");
            copied = Streams.copyIntoStream(in, new FileOutputStream(dest));

            if (copied <= 0 || !dest.exists() || dest.length() <= 0) {
                throw new IOException("copied " + copied + " bytes");
            }

            LOG.info("KML imported to {} ({} bytes)", dest.getAbsolutePath(), dest.length());
            final String savedName = dest.getName();
            handler.post(() -> onImportSucceeded(savedName));
        } catch (Throwable t) {
            LOG.error("Could not import KML file", t);
            handler.post(this::onImportFailed);
        }
    }

    private void onImportSucceeded(String savedName) {
        // 用一次性偏好把文件名交给「轨迹地图」，由其在地图就绪后自动展示并清空。
        TrackerPreferenceHelper.getInstance().setPendingKmlImportName(savedName);
        Toast.makeText(this, getString(R.string.tracker_track_map_kml_imported, savedName),
                Toast.LENGTH_LONG).show();

        Intent intent = new Intent(getApplicationContext(), GpsMainActivity.class);
        intent.putExtra(IntentConstants.SHOW_TRACK_MAP, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    private void onImportFailed() {
        Toast.makeText(this, R.string.tracker_track_map_kml_import_failed, Toast.LENGTH_LONG).show();
        finish();
    }

    /** 解析出安全的 .kml 文件名：优先取 content 的显示名，回退到路径末段。 */
    private String resolveKmlFileName(Uri uri) {
        String name = null;
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) name = cursor.getString(idx);
                }
            } catch (Throwable t) {
                LOG.debug("KML import: display name query failed", t);
            }
        }
        if (name == null || name.isEmpty()) name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) name = "track.kml";

        // 只保留文件名部分并过滤掉文件系统不安全字符。
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (name.isEmpty()) name = "track.kml";
        if (!name.toLowerCase(Locale.US).endsWith(".kml")) name = name + ".kml";
        return name;
    }
}

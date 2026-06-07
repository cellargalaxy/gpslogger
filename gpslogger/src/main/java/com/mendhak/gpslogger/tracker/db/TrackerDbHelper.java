/*
 * Travel/hiking 改造：tracker 模块独立的 SQLite 数据库。
 * 用 SQLiteOpenHelper 保持与现有 Java 项目风格一致，避免引入 Room。
 * 与 GPSLogger 主链路完全解耦，删表/破坏时不影响 GPX/CSV/CustomURL 既有行为。
 */
package com.mendhak.gpslogger.tracker.db;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.mendhak.gpslogger.common.AppSettings;
import com.mendhak.gpslogger.common.slf4j.Logs;

import org.slf4j.Logger;

public class TrackerDbHelper extends SQLiteOpenHelper {

    private static final Logger LOG = Logs.of(TrackerDbHelper.class);
    private static final String DB_NAME = "gpslogger_tracker.db";
    private static final int DB_VERSION = 1;

    static final String TABLE_TRACK_POINTS = "local_track_points";
    static final String TABLE_CUSTOMURL_OUTBOX = "customurl_outbox";

    private static volatile TrackerDbHelper INSTANCE;

    public static TrackerDbHelper getInstance() {
        if (INSTANCE == null) {
            synchronized (TrackerDbHelper.class) {
                if (INSTANCE == null) {
                    INSTANCE = new TrackerDbHelper(AppSettings.getInstance());
                }
            }
        }
        return INSTANCE;
    }

    private TrackerDbHelper(Context ctx) {
        super(ctx.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        LOG.info("Creating tracker tables");
        db.execSQL("CREATE TABLE " + TABLE_TRACK_POINTS + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "recorded_at INTEGER NOT NULL, " +
                "inserted_at INTEGER NOT NULL, " +
                "lat REAL NOT NULL, " +
                "lon REAL NOT NULL, " +
                "altitude REAL, " +
                "accuracy REAL, " +
                "speed REAL, " +
                "bearing REAL, " +
                "provider TEXT, " +
                "satellites INTEGER, " +
                "hdop REAL, " +
                "vdop REAL, " +
                "pdop REAL, " +
                "annotation TEXT, " +
                "profile_name TEXT, " +
                "file_name TEXT, " +
                "distance_meters REAL" +
                ")");
        db.execSQL("CREATE INDEX idx_local_track_points_recorded_at " +
                "ON " + TABLE_TRACK_POINTS + " (recorded_at)");

        db.execSQL("CREATE TABLE " + TABLE_CUSTOMURL_OUTBOX + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "recorded_at INTEGER NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "url TEXT NOT NULL, " +
                "method TEXT NOT NULL, " +
                "headers TEXT NOT NULL, " +
                "body TEXT NOT NULL, " +
                "basic_auth_username TEXT, " +
                "basic_auth_password TEXT, " +
                "status TEXT NOT NULL, " +
                "attempt_count INTEGER NOT NULL DEFAULT 0, " +
                "last_error TEXT, " +
                "last_attempt_at INTEGER, " +
                "next_attempt_at INTEGER NOT NULL DEFAULT 0" +
                ")");
        db.execSQL("CREATE INDEX idx_customurl_outbox_status_next " +
                "ON " + TABLE_CUSTOMURL_OUTBOX + " (status, next_attempt_at)");
        db.execSQL("CREATE INDEX idx_customurl_outbox_recorded_at " +
                "ON " + TABLE_CUSTOMURL_OUTBOX + " (recorded_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 后续版本变更在这里加迁移；首版无需处理。
    }
}

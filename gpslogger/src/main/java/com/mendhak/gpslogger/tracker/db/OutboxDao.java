/*
 * Custom URL Outbox 的存取。
 */
package com.mendhak.gpslogger.tracker.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.mendhak.gpslogger.common.slf4j.Logs;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

public class OutboxDao {

    private static final Logger LOG = Logs.of(OutboxDao.class);

    public long insert(OutboxEntry e) {
        try {
            SQLiteDatabase db = TrackerDbHelper.getInstance().getWritableDatabase();
            ContentValues cv = toContentValues(e);
            return db.insertOrThrow(TrackerDbHelper.TABLE_CUSTOMURL_OUTBOX, null, cv);
        } catch (Exception ex) {
            LOG.warn("Outbox insert failed", ex);
            return -1;
        }
    }

    public List<OutboxEntry> nextBatch(int limit, long nowMs) {
        List<OutboxEntry> list = new ArrayList<>();
        try {
            SQLiteDatabase db = TrackerDbHelper.getInstance().getReadableDatabase();
            try (Cursor c = db.query(
                    TrackerDbHelper.TABLE_CUSTOMURL_OUTBOX, null,
                    "status = ? AND next_attempt_at <= ?",
                    new String[]{OutboxEntry.STATUS_PENDING, String.valueOf(nowMs)},
                    null, null, "recorded_at ASC, id ASC", String.valueOf(limit))) {
                while (c.moveToNext()) list.add(fromCursor(c));
            }
        } catch (Exception ex) {
            LOG.warn("Outbox query failed", ex);
        }
        return list;
    }

    public int markInFlight(long id) {
        SQLiteDatabase db = TrackerDbHelper.getInstance().getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("status", OutboxEntry.STATUS_IN_FLIGHT);
        return db.update(TrackerDbHelper.TABLE_CUSTOMURL_OUTBOX, cv,
                "id = ?", new String[]{String.valueOf(id)});
    }

    public int deleteById(long id) {
        SQLiteDatabase db = TrackerDbHelper.getInstance().getWritableDatabase();
        return db.delete(TrackerDbHelper.TABLE_CUSTOMURL_OUTBOX,
                "id = ?", new String[]{String.valueOf(id)});
    }

    public int markFailed(long id, int attemptCount, String error,
                          long lastAttemptAt, long nextAttemptAt, String status) {
        SQLiteDatabase db = TrackerDbHelper.getInstance().getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("attempt_count", attemptCount);
        cv.put("last_error", error);
        cv.put("last_attempt_at", lastAttemptAt);
        cv.put("next_attempt_at", nextAttemptAt);
        cv.put("status", status);
        return db.update(TrackerDbHelper.TABLE_CUSTOMURL_OUTBOX, cv,
                "id = ?", new String[]{String.valueOf(id)});
    }

    public int countByStatus(String status) {
        try {
            SQLiteDatabase db = TrackerDbHelper.getInstance().getReadableDatabase();
            try (Cursor c = db.rawQuery(
                    "SELECT COUNT(*) FROM " + TrackerDbHelper.TABLE_CUSTOMURL_OUTBOX + " WHERE status = ?",
                    new String[]{status})) {
                if (c.moveToFirst()) return c.getInt(0);
            }
        } catch (Exception e) {
            LOG.warn("Outbox count failed", e);
        }
        return 0;
    }

    public int countTotal() {
        try {
            SQLiteDatabase db = TrackerDbHelper.getInstance().getReadableDatabase();
            try (Cursor c = db.rawQuery(
                    "SELECT COUNT(*) FROM " + TrackerDbHelper.TABLE_CUSTOMURL_OUTBOX, null)) {
                if (c.moveToFirst()) return c.getInt(0);
            }
        } catch (Exception e) {
            LOG.warn("Outbox total count failed", e);
        }
        return 0;
    }

    public int deleteOldestPending(int howMany) {
        try {
            SQLiteDatabase db = TrackerDbHelper.getInstance().getWritableDatabase();
            // SQLite 的 DELETE 不直接支持 ORDER BY + LIMIT，需用子查询。
            return db.delete(TrackerDbHelper.TABLE_CUSTOMURL_OUTBOX,
                    "id IN (SELECT id FROM " + TrackerDbHelper.TABLE_CUSTOMURL_OUTBOX +
                            " WHERE status = ? ORDER BY recorded_at ASC LIMIT " + howMany + ")",
                    new String[]{OutboxEntry.STATUS_PENDING});
        } catch (Exception e) {
            LOG.warn("Outbox cap delete failed", e);
            return 0;
        }
    }

    public int deleteFailedOlderThan(long thresholdMs) {
        try {
            SQLiteDatabase db = TrackerDbHelper.getInstance().getWritableDatabase();
            return db.delete(TrackerDbHelper.TABLE_CUSTOMURL_OUTBOX,
                    "status = ? AND created_at < ?",
                    new String[]{OutboxEntry.STATUS_FAILED, String.valueOf(thresholdMs)});
        } catch (Exception e) {
            LOG.warn("Outbox failed cleanup failed", e);
            return 0;
        }
    }

    public int deleteAll() {
        try {
            SQLiteDatabase db = TrackerDbHelper.getInstance().getWritableDatabase();
            return db.delete(TrackerDbHelper.TABLE_CUSTOMURL_OUTBOX, null, null);
        } catch (Exception e) {
            LOG.warn("Outbox delete all failed", e);
            return 0;
        }
    }

    public int requeueFailed() {
        try {
            SQLiteDatabase db = TrackerDbHelper.getInstance().getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("status", OutboxEntry.STATUS_PENDING);
            cv.put("attempt_count", 0);
            cv.put("next_attempt_at", 0L);
            return db.update(TrackerDbHelper.TABLE_CUSTOMURL_OUTBOX, cv,
                    "status = ?", new String[]{OutboxEntry.STATUS_FAILED});
        } catch (Exception e) {
            LOG.warn("Outbox requeue failed", e);
            return 0;
        }
    }

    /** Worker 启动时把所有 IN_FLIGHT 行重置为 PENDING，防止 Worker 被杀后行被永久卡住。 */
    public int recoverInFlight() {
        try {
            SQLiteDatabase db = TrackerDbHelper.getInstance().getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("status", OutboxEntry.STATUS_PENDING);
            return db.update(TrackerDbHelper.TABLE_CUSTOMURL_OUTBOX, cv,
                    "status = ?", new String[]{OutboxEntry.STATUS_IN_FLIGHT});
        } catch (Exception e) {
            LOG.warn("Outbox in-flight recovery failed", e);
            return 0;
        }
    }

    public List<OutboxEntry> sample(int limit) {
        List<OutboxEntry> list = new ArrayList<>();
        try {
            SQLiteDatabase db = TrackerDbHelper.getInstance().getReadableDatabase();
            try (Cursor c = db.query(TrackerDbHelper.TABLE_CUSTOMURL_OUTBOX, null,
                    null, null, null, null, "id DESC", String.valueOf(limit))) {
                while (c.moveToNext()) list.add(fromCursor(c));
            }
        } catch (Exception e) {
            LOG.warn("Outbox sample failed", e);
        }
        return list;
    }

    private ContentValues toContentValues(OutboxEntry e) {
        ContentValues cv = new ContentValues();
        cv.put("recorded_at", e.recordedAt);
        cv.put("created_at", e.createdAt);
        cv.put("url", e.url);
        cv.put("method", e.method);
        cv.put("headers", e.headers == null ? "" : e.headers);
        cv.put("body", e.body == null ? "" : e.body);
        cv.put("basic_auth_username", e.basicAuthUsername);
        cv.put("basic_auth_password", e.basicAuthPassword);
        cv.put("status", e.status == null ? OutboxEntry.STATUS_PENDING : e.status);
        cv.put("attempt_count", e.attemptCount);
        cv.put("last_error", e.lastError);
        if (e.lastAttemptAt != null) cv.put("last_attempt_at", e.lastAttemptAt);
        cv.put("next_attempt_at", e.nextAttemptAt);
        return cv;
    }

    private OutboxEntry fromCursor(Cursor c) {
        OutboxEntry e = new OutboxEntry();
        e.id = c.getLong(c.getColumnIndexOrThrow("id"));
        e.recordedAt = c.getLong(c.getColumnIndexOrThrow("recorded_at"));
        e.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        e.url = c.getString(c.getColumnIndexOrThrow("url"));
        e.method = c.getString(c.getColumnIndexOrThrow("method"));
        e.headers = c.getString(c.getColumnIndexOrThrow("headers"));
        e.body = c.getString(c.getColumnIndexOrThrow("body"));
        e.basicAuthUsername = c.getString(c.getColumnIndexOrThrow("basic_auth_username"));
        e.basicAuthPassword = c.getString(c.getColumnIndexOrThrow("basic_auth_password"));
        e.status = c.getString(c.getColumnIndexOrThrow("status"));
        e.attemptCount = c.getInt(c.getColumnIndexOrThrow("attempt_count"));
        e.lastError = c.getString(c.getColumnIndexOrThrow("last_error"));
        int laIdx = c.getColumnIndexOrThrow("last_attempt_at");
        e.lastAttemptAt = c.isNull(laIdx) ? null : c.getLong(laIdx);
        e.nextAttemptAt = c.getLong(c.getColumnIndexOrThrow("next_attempt_at"));
        return e;
    }
}

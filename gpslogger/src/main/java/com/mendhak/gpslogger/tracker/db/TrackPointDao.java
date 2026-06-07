/*
 * 本地轨迹点的存取。线程安全：每个方法自取自闭一次 db 连接。
 */
package com.mendhak.gpslogger.tracker.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.mendhak.gpslogger.common.slf4j.Logs;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

public class TrackPointDao {

    private static final Logger LOG = Logs.of(TrackPointDao.class);

    public long insert(TrackPoint p) {
        try {
            SQLiteDatabase db = TrackerDbHelper.getInstance().getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("recorded_at", p.recordedAt);
            cv.put("inserted_at", p.insertedAt);
            cv.put("lat", p.lat);
            cv.put("lon", p.lon);
            if (p.altitude != null) cv.put("altitude", p.altitude);
            if (p.accuracy != null) cv.put("accuracy", p.accuracy);
            if (p.speed != null) cv.put("speed", p.speed);
            if (p.bearing != null) cv.put("bearing", p.bearing);
            if (p.provider != null) cv.put("provider", p.provider);
            if (p.satellites != null) cv.put("satellites", p.satellites);
            if (p.hdop != null) cv.put("hdop", p.hdop);
            if (p.vdop != null) cv.put("vdop", p.vdop);
            if (p.pdop != null) cv.put("pdop", p.pdop);
            if (p.annotation != null) cv.put("annotation", p.annotation);
            if (p.profileName != null) cv.put("profile_name", p.profileName);
            if (p.fileName != null) cv.put("file_name", p.fileName);
            if (p.distanceMeters != null) cv.put("distance_meters", p.distanceMeters);
            return db.insertOrThrow(TrackerDbHelper.TABLE_TRACK_POINTS, null, cv);
        } catch (Exception e) {
            LOG.debug("Local track insert failed", e);
            return -1;
        }
    }

    public List<TrackPoint> queryRange(long fromMs, long toMs) {
        List<TrackPoint> list = new ArrayList<>();
        try {
            SQLiteDatabase db = TrackerDbHelper.getInstance().getReadableDatabase();
            try (Cursor c = db.query(
                    TrackerDbHelper.TABLE_TRACK_POINTS, null,
                    "recorded_at >= ? AND recorded_at <= ?",
                    new String[]{String.valueOf(fromMs), String.valueOf(toMs)},
                    null, null, "recorded_at ASC")) {
                while (c.moveToNext()) list.add(fromCursor(c));
            }
        } catch (Exception e) {
            LOG.warn("Local track query failed", e);
        }
        return list;
    }

    public long countAll() {
        try {
            SQLiteDatabase db = TrackerDbHelper.getInstance().getReadableDatabase();
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TrackerDbHelper.TABLE_TRACK_POINTS, null)) {
                if (c.moveToFirst()) return c.getLong(0);
            }
        } catch (Exception e) {
            LOG.warn("Local track count failed", e);
        }
        return 0;
    }

    public int deleteOlderThan(long thresholdMs) {
        try {
            SQLiteDatabase db = TrackerDbHelper.getInstance().getWritableDatabase();
            return db.delete(TrackerDbHelper.TABLE_TRACK_POINTS,
                    "recorded_at < ?",
                    new String[]{String.valueOf(thresholdMs)});
        } catch (Exception e) {
            LOG.warn("Local track delete-older failed", e);
            return 0;
        }
    }

    public int deleteAll() {
        try {
            SQLiteDatabase db = TrackerDbHelper.getInstance().getWritableDatabase();
            return db.delete(TrackerDbHelper.TABLE_TRACK_POINTS, null, null);
        } catch (Exception e) {
            LOG.warn("Local track delete-all failed", e);
            return 0;
        }
    }

    private TrackPoint fromCursor(Cursor c) {
        TrackPoint p = new TrackPoint();
        p.id = c.getLong(c.getColumnIndexOrThrow("id"));
        p.recordedAt = c.getLong(c.getColumnIndexOrThrow("recorded_at"));
        p.insertedAt = c.getLong(c.getColumnIndexOrThrow("inserted_at"));
        p.lat = c.getDouble(c.getColumnIndexOrThrow("lat"));
        p.lon = c.getDouble(c.getColumnIndexOrThrow("lon"));
        p.altitude = c.isNull(c.getColumnIndexOrThrow("altitude")) ? null : c.getDouble(c.getColumnIndexOrThrow("altitude"));
        p.accuracy = c.isNull(c.getColumnIndexOrThrow("accuracy")) ? null : c.getFloat(c.getColumnIndexOrThrow("accuracy"));
        p.speed = c.isNull(c.getColumnIndexOrThrow("speed")) ? null : c.getFloat(c.getColumnIndexOrThrow("speed"));
        p.bearing = c.isNull(c.getColumnIndexOrThrow("bearing")) ? null : c.getFloat(c.getColumnIndexOrThrow("bearing"));
        p.provider = c.isNull(c.getColumnIndexOrThrow("provider")) ? null : c.getString(c.getColumnIndexOrThrow("provider"));
        p.satellites = c.isNull(c.getColumnIndexOrThrow("satellites")) ? null : c.getInt(c.getColumnIndexOrThrow("satellites"));
        p.hdop = c.isNull(c.getColumnIndexOrThrow("hdop")) ? null : c.getDouble(c.getColumnIndexOrThrow("hdop"));
        p.vdop = c.isNull(c.getColumnIndexOrThrow("vdop")) ? null : c.getDouble(c.getColumnIndexOrThrow("vdop"));
        p.pdop = c.isNull(c.getColumnIndexOrThrow("pdop")) ? null : c.getDouble(c.getColumnIndexOrThrow("pdop"));
        p.annotation = c.isNull(c.getColumnIndexOrThrow("annotation")) ? null : c.getString(c.getColumnIndexOrThrow("annotation"));
        p.profileName = c.isNull(c.getColumnIndexOrThrow("profile_name")) ? null : c.getString(c.getColumnIndexOrThrow("profile_name"));
        p.fileName = c.isNull(c.getColumnIndexOrThrow("file_name")) ? null : c.getString(c.getColumnIndexOrThrow("file_name"));
        p.distanceMeters = c.isNull(c.getColumnIndexOrThrow("distance_meters")) ? null : c.getDouble(c.getColumnIndexOrThrow("distance_meters"));
        return p;
    }
}

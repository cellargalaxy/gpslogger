/*
 * 本地轨迹缓存中的单个点。字段语义与 SerializableLocation 对齐，但只保留地图展示需要的内容。
 */
package com.mendhak.gpslogger.tracker.db;

public class TrackPoint {
    public long id;
    public long recordedAt;
    public long insertedAt;
    public double lat;
    public double lon;
    public Double altitude;
    public Float accuracy;
    public Float speed;
    public Float bearing;
    public String provider;
    public Integer satellites;
    public Double hdop;
    public Double vdop;
    public Double pdop;
    public String annotation;
    public String profileName;
    public String fileName;
    public Double distanceMeters;
}

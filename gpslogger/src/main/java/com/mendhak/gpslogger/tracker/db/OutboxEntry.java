/*
 * Custom URL 待发送队列的一条记录。
 * 与 CustomUrlRequest 一对一对应，但状态机和重试由 Outbox 自身管理。
 */
package com.mendhak.gpslogger.tracker.db;

public class OutboxEntry {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_IN_FLIGHT = "IN_FLIGHT";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";

    public long id;
    public long recordedAt;
    public long createdAt;
    public String url;
    public String method;
    public String headers; // 原始多行字符串
    public String body;
    public String basicAuthUsername;
    public String basicAuthPassword;
    public String status = STATUS_PENDING;
    public int attemptCount;
    public String lastError;
    public Long lastAttemptAt;
    public long nextAttemptAt;
}

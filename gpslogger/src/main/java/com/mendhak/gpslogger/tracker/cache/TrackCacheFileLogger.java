/*
 * Travel/hiking 改造：把本地轨迹缓存适配为 FileLogger 输出端。
 * 这里不直接代表用户可导出的文件格式，只是复用现有写入管线，避免在 FileLoggerFactory.write()
 * 中散落单独的缓存写入语句。
 */
package com.mendhak.gpslogger.tracker.cache;

import android.location.Location;

import com.mendhak.gpslogger.loggers.FileLogger;

public class TrackCacheFileLogger implements FileLogger {

    @Override
    public void write(Location loc) {
        TrackCacheRepository.getInstance().append(loc);
    }

    @Override
    public void annotate(String description, Location loc) {
        // 轨迹缓存写点时会从 Session 读取当前 annotation；这里不再插入额外点。
    }

    @Override
    public String getName() {
        return "TrackCache";
    }
}

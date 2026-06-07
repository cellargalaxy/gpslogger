/*
 * Travel/hiking 改造：将轨迹点按自然钟点边界切段。
 *
 * 例：粒度 15 分钟、轨迹 13:12 ~ 13:33 ->
 *   段 0 [13:12, 13:15)
 *   段 1 [13:15, 13:30)
 *   段 2 [13:30, 13:33]
 *
 * 算法：segmentIndex = floor(recorded_at / segmentMillis)。相邻两点跨越边界时，
 * 在边界处线性插值出一个边界点用于绘制颜色切换。插值点不会写回缓存。
 */
package com.mendhak.gpslogger.tracker.ui;

import com.mendhak.gpslogger.tracker.db.TrackPoint;

import java.util.ArrayList;
import java.util.List;

public final class TrackSegmenter {

    private TrackSegmenter() {}

    public static class Segment {
        /** 全局段索引（绝对值，影响颜色环映射）。 */
        public long segmentIndex;
        public long startMs;
        public long endMs;
        public final List<double[]> points = new ArrayList<>();
    }

    /** 输入按 recorded_at 升序的轨迹点，返回切好的段。 */
    public static List<Segment> segment(List<TrackPoint> points, long segmentMillis) {
        List<Segment> result = new ArrayList<>();
        if (points == null || points.isEmpty() || segmentMillis <= 0) return result;

        Segment current = null;
        TrackPoint prev = null;
        long prevIdx = Long.MIN_VALUE;

        for (TrackPoint p : points) {
            long idx = Math.floorDiv(p.recordedAt, segmentMillis);
            if (current == null) {
                current = newSegment(idx, segmentMillis);
                current.points.add(new double[]{p.lat, p.lon});
                prevIdx = idx;
                prev = p;
                result.add(current);
                continue;
            }
            if (idx != prevIdx) {
                // 跨越了至少一条边界。沿前一段插值到该段结束的边界处，
                // 再让新段从同一个边界点继续，颜色切换在边界处发生。
                long boundaryMs = idx * segmentMillis; // 与 prevIdx + 1 段的起点重合（idx 跳跃 ≥1）
                double[] boundaryLatLon = interpolate(prev, p, boundaryMs);
                current.points.add(boundaryLatLon);
                current = newSegment(idx, segmentMillis);
                current.points.add(boundaryLatLon);
                current.points.add(new double[]{p.lat, p.lon});
                result.add(current);
                prevIdx = idx;
            } else {
                current.points.add(new double[]{p.lat, p.lon});
            }
            prev = p;
        }

        if (current != null && !points.isEmpty()) {
            current.endMs = points.get(points.size() - 1).recordedAt;
        }
        // 修正第一段 startMs 为首点真实时刻，最后一段 endMs 为末点真实时刻。
        if (!result.isEmpty()) {
            result.get(0).startMs = points.get(0).recordedAt;
        }

        return result;
    }

    private static Segment newSegment(long idx, long segmentMillis) {
        Segment s = new Segment();
        s.segmentIndex = idx;
        s.startMs = idx * segmentMillis;
        s.endMs = (idx + 1) * segmentMillis;
        return s;
    }

    /**
     * 在 (a, b) 两点之间按时间线性插值出 t 时刻的经纬度。
     * 若 t 超出 [a, b]，clamp 到端点。
     */
    static double[] interpolate(TrackPoint a, TrackPoint b, long t) {
        if (b.recordedAt == a.recordedAt) return new double[]{b.lat, b.lon};
        double r = (double) (t - a.recordedAt) / (double) (b.recordedAt - a.recordedAt);
        if (r < 0) r = 0;
        if (r > 1) r = 1;
        double lat = a.lat + (b.lat - a.lat) * r;
        double lon = a.lon + (b.lon - a.lon) * r;
        return new double[]{lat, lon};
    }

    /** 8 色调色板，按 segmentIndex 取模映射，避免相邻段颜色雷同。 */
    public static int colorForIndex(long segmentIndex) {
        int[] palette = new int[]{
                0xFFE6194B, // red
                0xFF3CB44B, // green
                0xFF4363D8, // blue
                0xFFF58231, // orange
                0xFF911EB4, // purple
                0xFF42D4F4, // cyan
                0xFFF032E6, // magenta
                0xFFBFEF45  // lime
        };
        int idx = (int) Math.floorMod(segmentIndex, palette.length);
        return palette[idx];
    }
}

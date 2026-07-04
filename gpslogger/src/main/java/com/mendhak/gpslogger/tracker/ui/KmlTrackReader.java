/*
 * Travel/hiking 改造：读取 KML 文件中的轨迹路径（折线）。
 *
 * 支持两类线要素：
 * - <gx:Track>：内含若干 <gx:coord>，格式 "lon lat [alt]"（空格分隔）。
 * - <LineString>：内含 <coordinates>，格式为空白分隔的 "lon,lat[,alt]" 元组。
 * 每个线要素返回为一条折线（点序列 {lat, lon}）。<Point> 等标注点不在此处理。
 *
 * 解析方式沿用项目既有 GpxReader 的 DOM（DocumentBuilderFactory，默认非命名空间感知）风格，
 * 因此可直接用带前缀的标签名 "gx:Track"/"gx:coord" 检索。
 */
package com.mendhak.gpslogger.tracker.ui;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

public final class KmlTrackReader {

    private KmlTrackReader() {}

    /**
     * 解析 KML，返回其中所有线要素。每条折线是一个 {lat, lon} 点序列，点数不足 2 的会被丢弃。
     */
    public static List<List<double[]>> getTracks(File kmlFile) throws Exception {
        List<List<double[]>> tracks = new ArrayList<>();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();

        FileInputStream fis = new FileInputStream(kmlFile);
        try {
            Document dom = builder.parse(fis);
            Element root = dom.getDocumentElement();
            if (root == null) return tracks;

            // gx:Track —— 每个 <gx:coord> 一个点，"lon lat [alt]"
            NodeList gxTracks = root.getElementsByTagName("gx:Track");
            for (int i = 0; i < gxTracks.getLength(); i++) {
                List<double[]> line = new ArrayList<>();
                NodeList coords = ((Element) gxTracks.item(i)).getElementsByTagName("gx:coord");
                for (int j = 0; j < coords.getLength(); j++) {
                    double[] latLon = parseSpaceCoord(textOf(coords.item(j)));
                    if (latLon != null) line.add(latLon);
                }
                if (line.size() >= 2) tracks.add(line);
            }

            // LineString —— <coordinates> 内空白分隔的 "lon,lat[,alt]" 元组
            NodeList lineStrings = root.getElementsByTagName("LineString");
            for (int i = 0; i < lineStrings.getLength(); i++) {
                NodeList coordsNodes = ((Element) lineStrings.item(i)).getElementsByTagName("coordinates");
                for (int k = 0; k < coordsNodes.getLength(); k++) {
                    List<double[]> line = parseCoordinatesBlock(textOf(coordsNodes.item(k)));
                    if (line.size() >= 2) tracks.add(line);
                }
            }
        } finally {
            fis.close();
        }
        return tracks;
    }

    /** "lon lat [alt]" -> {lat, lon}；解析失败返回 null。 */
    private static double[] parseSpaceCoord(String text) {
        if (text == null) return null;
        String[] parts = text.trim().split("\\s+");
        if (parts.length < 2) return null;
        try {
            double lon = Double.parseDouble(parts[0]);
            double lat = Double.parseDouble(parts[1]);
            return new double[]{lat, lon};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** <coordinates> 文本 -> 折线；每个元组 "lon,lat[,alt]"，无法解析的元组跳过。 */
    private static List<double[]> parseCoordinatesBlock(String text) {
        List<double[]> line = new ArrayList<>();
        if (text == null) return line;
        String[] tuples = text.trim().split("\\s+");
        for (String tuple : tuples) {
            if (tuple.isEmpty()) continue;
            String[] parts = tuple.split(",");
            if (parts.length < 2) continue;
            try {
                double lon = Double.parseDouble(parts[0]);
                double lat = Double.parseDouble(parts[1]);
                line.add(new double[]{lat, lon});
            } catch (NumberFormatException e) {
                // 跳过无法解析的元组
            }
        }
        return line;
    }

    private static String textOf(Node node) {
        return node == null ? null : node.getTextContent();
    }
}

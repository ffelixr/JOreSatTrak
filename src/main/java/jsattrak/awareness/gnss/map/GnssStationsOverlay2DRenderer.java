package jsattrak.awareness.gnss.map;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.geometry.GnssGeometryService;
import jsattrak.awareness.gnss.stations.GnssReferenceStation;
import jsattrak.awareness.gnss.stations.GnssStationListener;
import jsattrak.awareness.gnss.stations.GnssStationNetwork;
import jsattrak.awareness.gnss.stations.GnssStationService;
import jsattrak.awareness.gnss.stations.GnssStationStatus;
import jsattrak.awareness.gnss.stations.analysis.GnssStationAnalysis;
import jsattrak.awareness.gnss.stations.analysis.RegionalScanReport;
import jsattrak.awareness.gnss.stations.analysis.RegionalScanReport.RegionalEventSeverity;
import jsattrak.awareness.gnss.stations.analysis.RegionalScanReport.StationAnomalyLevel;
import jsattrak.awareness.gnss.stations.analysis.RegionalScanReport.StationAnomalySummary;

/**
 * Shared Java2D renderer, screen-space clusterer, hit-tester, and tooltip formatter for 2D
 * Equirectangular and 2D Polar GNSS Reference Station overlays.
 */
public class GnssStationsOverlay2DRenderer {

    public static final Color COLOR_NOMINAL = new Color(0x3F, 0xBF, 0x6A);
    public static final Color COLOR_DELAYED = new Color(0xFA, 0x8C, 0x16);
    public static final Color COLOR_ALERT_OR_OFFLINE = new Color(0xE5, 0x48, 0x4D);
    public static final Color COLOR_UNKNOWN = new Color(0x8C, 0x95, 0xA6);

    private static final Color OUTLINE_DARK = new Color(18, 22, 28, 235);
    private static final Color SELECTED_HALO = new Color(0x00, 0xE5, 0xFF, 130);
    private static final Color SELECTED_RING = new Color(0x00, 0xE5, 0xFF, 235);
    private static final Color ZONE_INTERFERENCE_FILL = new Color(0xE5, 0x48, 0x4D, 28);
    private static final Color ZONE_DEGRADATION_FILL = new Color(0xFA, 0x8C, 0x16, 24);
    private static final Color LABEL_BG = new Color(18, 22, 28, 205);

    private static final double DEFAULT_CLUSTER_RADIUS_PX = 18.0;
    private static final double EARTH_RADIUS_KM = 6371.0088;
    private static final int RING_SEGMENTS = 72;

    public record RenderSummary(int renderedClusters, int markerCount, boolean interferenceZoneDrawn) {
    }

    private record RenderedMarker(
            GnssReferenceStation leadStation,
            int count,
            int px,
            int py,
            int discSign,
            Color color,
            boolean containsSelected) {
    }

    private static final class ClusterBucket {
        final List<GnssReferenceStation> members = new ArrayList<>();
        double sumX;
        double sumY;
        final int discSign;
        Color color;
        int severityRank;
        boolean containsSelected;

        ClusterBucket(GnssReferenceStation first, int px, int py, int discSign, Color color, int rank, boolean selected) {
            this.members.add(first);
            this.sumX = px;
            this.sumY = py;
            this.discSign = discSign;
            this.color = color;
            this.severityRank = rank;
            this.containsSelected = selected;
        }

        double centerX() {
            return sumX / members.size();
        }

        double centerY() {
            return sumY / members.size();
        }

        void add(GnssReferenceStation st, int px, int py, Color c, int rank, boolean selected) {
            members.add(st);
            sumX += px;
            sumY += py;
            if (rank > severityRank) {
                severityRank = rank;
                color = c;
            }
            if (selected) {
                containsSelected = true;
            }
        }
    }

    private volatile List<RenderedMarker> lastEquirectangularMarkers = List.of();
    private volatile List<RenderedMarker> lastPolarMarkers = List.of();

    public static Color colorForStation(GnssReferenceStation station, RegionalScanReport report) {
        if (report != null && report.stations() != null && station != null) {
            for (StationAnomalySummary summary : report.stations()) {
                if (summary != null && summary.station() != null
                        && Objects.equals(summary.station().stationId(), station.stationId())) {
                    if (summary.level() == StationAnomalyLevel.ALERT) {
                        return COLOR_ALERT_OR_OFFLINE;
                    }
                    if (summary.level() == StationAnomalyLevel.WARNING) {
                        return COLOR_DELAYED;
                    }
                }
            }
        }
        if (station == null || station.status() == null) {
            return COLOR_UNKNOWN;
        }
        return switch (station.status()) {
            case ONLINE -> COLOR_NOMINAL;
            case DELAYED -> COLOR_DELAYED;
            case STALE, OFFLINE -> COLOR_ALERT_OR_OFFLINE;
            case UNKNOWN -> COLOR_UNKNOWN;
        };
    }

    private static int severityRank(Color color) {
        if (COLOR_ALERT_OR_OFFLINE.equals(color)) {
            return 4;
        }
        if (COLOR_DELAYED.equals(color)) {
            return 3;
        }
        if (COLOR_NOMINAL.equals(color)) {
            return 2;
        }
        return 1;
    }

    public static GnssStationListener createRepaintListener(JComponent target) {
        return new GnssStationListener() {
            private void trigger() {
                if (target == null) {
                    return;
                }
                if (SwingUtilities.isEventDispatchThread()) {
                    target.repaint();
                } else {
                    SwingUtilities.invokeLater(target::repaint);
                }
            }

            @Override
            public void onStationCatalogChanged() {
                trigger();
            }

            @Override
            public void onSelectedStationChanged(String selectedStationId) {
                trigger();
            }

            @Override
            public void onInspectionUpdated(GnssStationAnalysis analysis) {
                trigger();
            }

            @Override
            public void onRegionalScanUpdated(RegionalScanReport report) {
                trigger();
            }

            @Override
            public void onStationStatusMessage(String message, boolean isWarning) {
            }

            @Override
            public void onMapOverlayStateChanged(boolean enabled) {
                trigger();
            }
        };
    }

    public void clear() {
        this.lastEquirectangularMarkers = List.of();
        this.lastPolarMarkers = List.of();
    }

    public void clearEquirectangular() {
        this.lastEquirectangularMarkers = List.of();
    }

    public void clearPolar() {
        this.lastPolarMarkers = List.of();
    }

    public int paintServiceOverlay(
            Graphics2D g2,
            GnssStationService service,
            boolean perWindowVisible,
            Rectangle bounds,
            BiFunction<Double, Double, int[]> projector,
            boolean polar) {
        if (g2 == null || projector == null || service == null
                || !perWindowVisible || !service.isMapOverlayEnabled()) {
            if (polar) {
                clearPolar();
            } else {
                clearEquirectangular();
            }
            return 0;
        }
        List<GnssReferenceStation> stations = service.getFilteredStations("", null);
        RenderSummary summary = polar
                ? renderPolar(g2, stations, service.getSelectedStationId(), service.getLatestRegionalReport(), bounds, projector)
                : renderEquirectangular(g2, stations, service.getSelectedStationId(), service.getLatestRegionalReport(), bounds, projector);
        return summary.markerCount();
    }

    public RenderSummary renderEquirectangular(
            Graphics2D g2,
            List<GnssReferenceStation> stations,
            String selectedStationId,
            RegionalScanReport report,
            Rectangle bounds,
            BiFunction<Double, Double, int[]> projector) {
        return renderInternal(g2, stations, selectedStationId, report, bounds, projector, false);
    }

    public RenderSummary renderPolar(
            Graphics2D g2,
            List<GnssReferenceStation> stations,
            String selectedStationId,
            RegionalScanReport report,
            Rectangle bounds,
            BiFunction<Double, Double, int[]> projector) {
        return renderInternal(g2, stations, selectedStationId, report, bounds, projector, true);
    }

    private RenderSummary renderInternal(
            Graphics2D g2,
            List<GnssReferenceStation> stations,
            String selectedStationId,
            RegionalScanReport report,
            Rectangle bounds,
            BiFunction<Double, Double, int[]> projector,
            boolean polar) {
        if (g2 == null || projector == null) {
            if (polar) {
                lastPolarMarkers = List.of();
            } else {
                lastEquirectangularMarkers = List.of();
            }
            return new RenderSummary(0, 0, false);
        }

        Object prevAA = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Path2D.Double ringPath = buildInterferenceZonePath(report, bounds, projector, polar);
        if (ringPath != null) {
            paintInterferenceZoneFill(g2, report, ringPath, bounds, projector, polar);
        }

        List<ClusterBucket> clusters = new ArrayList<>();
        if (stations != null) {
            for (GnssReferenceStation station : stations) {
                if (station == null || !Double.isFinite(station.latDeg()) || !Double.isFinite(station.lonDeg())) {
                    continue;
                }
                int[] xy = projector.apply(station.latDeg(), station.lonDeg());
                if (xy == null || xy.length < 2) {
                    continue;
                }
                if (bounds != null && !bounds.contains(xy[0], xy[1])) {
                    continue;
                }
                int discSign = polar && xy.length >= 3 ? xy[2] : 1;
                Color color = colorForStation(station, report);
                int rank = severityRank(color);
                boolean isSelected = selectedStationId != null && selectedStationId.equalsIgnoreCase(station.stationId());

                ClusterBucket matched = null;
                for (ClusterBucket bucket : clusters) {
                    if (bucket.discSign != discSign) {
                        continue;
                    }
                    double dist = Math.hypot(xy[0] - bucket.centerX(), xy[1] - bucket.centerY());
                    if (dist <= DEFAULT_CLUSTER_RADIUS_PX) {
                        matched = bucket;
                        break;
                    }
                }
                if (matched == null) {
                    clusters.add(new ClusterBucket(station, xy[0], xy[1], discSign, color, rank, isSelected));
                } else {
                    matched.add(station, xy[0], xy[1], color, rank, isSelected);
                }
            }
        }

        List<RenderedMarker> rendered = new ArrayList<>(clusters.size());
        int totalMarkers = 0;
        Font badgeFont = new Font("SansSerif", Font.BOLD, 10);
        Font labelFont = new Font("SansSerif", Font.BOLD, 10);

        for (ClusterBucket bucket : clusters) {
            int cx = (int) Math.round(bucket.centerX());
            int cy = (int) Math.round(bucket.centerY());
            int count = bucket.members.size();
            totalMarkers += count;
            GnssReferenceStation lead = pickLeadStation(bucket.members, selectedStationId);

            if (count == 1) {
                drawBeaconIcon(g2, cx, cy, bucket.color, bucket.containsSelected);
                if (bucket.containsSelected) {
                    drawStationTag(g2, cx, cy - 11, lead.shortCode(), labelFont);
                }
            } else {
                drawClusterBadge(g2, cx, cy, count, bucket.color, bucket.containsSelected, badgeFont);
            }
            rendered.add(new RenderedMarker(lead, count, cx, cy, bucket.discSign, bucket.color, bucket.containsSelected));
        }

        boolean zoneDrawn = ringPath != null && paintInterferenceZoneStroke(g2, report, ringPath);

        if (polar) {
            lastPolarMarkers = List.copyOf(rendered);
        } else {
            lastEquirectangularMarkers = List.copyOf(rendered);
        }

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, prevAA != null ? prevAA : RenderingHints.VALUE_ANTIALIAS_DEFAULT);
        return new RenderSummary(clusters.size(), totalMarkers, zoneDrawn);
    }

    private static GnssReferenceStation pickLeadStation(List<GnssReferenceStation> members, String selectedStationId) {
        if (selectedStationId != null) {
            for (GnssReferenceStation st : members) {
                if (selectedStationId.equalsIgnoreCase(st.stationId())) {
                    return st;
                }
            }
        }
        return members.get(0);
    }

    private static Path2D.Double buildInterferenceZonePath(
            RegionalScanReport report,
            Rectangle bounds,
            BiFunction<Double, Double, int[]> projector,
            boolean polar) {
        if (report == null || report.severity() == RegionalEventSeverity.NOMINAL) {
            return null;
        }
        double latDeg = report.centroidLatDeg();
        double lonDeg = report.centroidLonDeg();
        double radiusKm = report.radiusKm();
        if (!Double.isFinite(latDeg) || !Double.isFinite(lonDeg) || !Double.isFinite(radiusKm) || radiusKm <= 0.0) {
            return null;
        }

        double angularRad = radiusKm / EARTH_RADIUS_KM;
        double lat0 = Math.toRadians(latDeg);
        double lon0 = Math.toRadians(lonDeg);
        int maxJumpPx = bounds != null ? Math.max(80, Math.min(bounds.width, bounds.height) / 3) : 120;

        Path2D.Double strokePath = new Path2D.Double();
        boolean penDown = false;
        int prevX = 0;
        int prevY = 0;
        int prevDisc = Integer.MIN_VALUE;
        int drawnSegments = 0;

        for (int i = 0; i <= RING_SEGMENTS; i++) {
            double bearing = (2.0 * Math.PI * i) / RING_SEGMENTS;
            double sinLat = Math.sin(lat0) * Math.cos(angularRad)
                    + Math.cos(lat0) * Math.sin(angularRad) * Math.cos(bearing);
            double ringLat = Math.asin(Math.max(-1.0, Math.min(1.0, sinLat)));
            double ringLon = lon0 + Math.atan2(
                    Math.sin(bearing) * Math.sin(angularRad) * Math.cos(lat0),
                    Math.cos(angularRad) - Math.sin(lat0) * Math.sin(ringLat));
            double degLat = Math.toDegrees(ringLat);
            double degLon = Math.toDegrees(ringLon);
            while (degLon > 180.0) {
                degLon -= 360.0;
            }
            while (degLon < -180.0) {
                degLon += 360.0;
            }

            int[] xy = projector.apply(degLat, degLon);
            if (xy == null || xy.length < 2) {
                penDown = false;
                continue;
            }
            int disc = polar && xy.length >= 3 ? xy[2] : 1;
            if (!penDown || (polar && disc != prevDisc) || Math.hypot(xy[0] - prevX, xy[1] - prevY) > maxJumpPx) {
                strokePath.moveTo(xy[0], xy[1]);
                penDown = true;
            } else {
                strokePath.lineTo(xy[0], xy[1]);
                drawnSegments++;
            }
            prevX = xy[0];
            prevY = xy[1];
            prevDisc = disc;
        }
        return drawnSegments > 0 ? strokePath : null;
    }

    private static void paintInterferenceZoneFill(
            Graphics2D g2,
            RegionalScanReport report,
            Path2D.Double ringPath,
            Rectangle bounds,
            BiFunction<Double, Double, int[]> projector,
            boolean polar) {
        if (polar || ringPath == null || report == null) {
            return;
        }
        // Only fill when path contains a single unbroken loop (one SEG_MOVETO)
        int moveCount = 0;
        var iter = ringPath.getPathIterator(null);
        double[] coords = new double[6];
        while (!iter.isDone()) {
            if (iter.currentSegment(coords) == java.awt.geom.PathIterator.SEG_MOVETO) {
                moveCount++;
            }
            iter.next();
        }
        if (moveCount == 1) {
            Path2D.Double fillPoly = new Path2D.Double(ringPath);
            fillPoly.closePath();
            g2.setColor(report.severity() == RegionalEventSeverity.POTENTIAL_INTERFERENCE
                    ? ZONE_INTERFERENCE_FILL
                    : ZONE_DEGRADATION_FILL);
            g2.fill(fillPoly);
        }
    }

    private static boolean paintInterferenceZoneStroke(
            Graphics2D g2,
            RegionalScanReport report,
            Path2D.Double strokePath) {
        if (strokePath == null || report == null) {
            return false;
        }
        Color strokeColor = report.severity() == RegionalEventSeverity.POTENTIAL_INTERFERENCE
                ? COLOR_ALERT_OR_OFFLINE
                : COLOR_DELAYED;
        Stroke prevStroke = g2.getStroke();
        g2.setColor(strokeColor);
        g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10.0f, new float[]{7.0f, 5.0f}, 0.0f));
        g2.draw(strokePath);
        g2.setStroke(prevStroke);
        return true;
    }

    private static void drawBeaconIcon(Graphics2D g2, int cx, int cy, Color fill, boolean selected) {
        if (selected) {
            g2.setColor(SELECTED_HALO);
            g2.fill(new Ellipse2D.Double(cx - 10.5, cy - 10.5, 21.0, 21.0));
            g2.setColor(SELECTED_RING);
            Stroke prev = g2.getStroke();
            g2.setStroke(new BasicStroke(1.6f));
            g2.draw(new Ellipse2D.Double(cx - 9.0, cy - 9.0, 18.0, 18.0));
            g2.setStroke(prev);
        }

        double r = 6.2;
        Path2D.Double tri = new Path2D.Double();
        tri.moveTo(cx, cy - r);
        tri.lineTo(cx + r * 0.88, cy + r * 0.72);
        tri.lineTo(cx - r * 0.88, cy + r * 0.72);
        tri.closePath();

        g2.setColor(fill);
        g2.fill(tri);

        Stroke prev = g2.getStroke();
        g2.setStroke(new BasicStroke(1.2f));
        g2.setColor(OUTLINE_DARK);
        g2.draw(tri);
        g2.setStroke(prev);
    }

    private static void drawClusterBadge(
            Graphics2D g2, int cx, int cy, int count, Color fill, boolean containsSelected, Font font) {
        double radius = count >= 100 ? 12.0 : (count >= 10 ? 10.5 : 9.0);
        if (containsSelected) {
            g2.setColor(SELECTED_HALO);
            g2.fill(new Ellipse2D.Double(cx - radius - 3.0, cy - radius - 3.0, (radius + 3.0) * 2.0, (radius + 3.0) * 2.0));
        }

        Ellipse2D.Double circle = new Ellipse2D.Double(cx - radius, cy - radius, radius * 2.0, radius * 2.0);
        g2.setColor(fill);
        g2.fill(circle);

        Stroke prev = g2.getStroke();
        g2.setStroke(new BasicStroke(containsSelected ? 2.0f : 1.3f));
        g2.setColor(containsSelected ? SELECTED_RING : OUTLINE_DARK);
        g2.draw(circle);
        g2.setStroke(prev);

        String label = Integer.toString(count);
        Font prevFont = g2.getFont();
        g2.setFont(font);
        FontMetrics fm = g2.getFontMetrics();
        int tx = cx - fm.stringWidth(label) / 2;
        int ty = cy + (fm.getAscent() - fm.getDescent()) / 2;
        g2.setColor(Color.WHITE);
        g2.drawString(label, tx, ty);
        g2.setFont(prevFont);
    }

    private static void drawStationTag(Graphics2D g2, int cx, int topY, String code, Font font) {
        if (code == null || code.isBlank()) {
            return;
        }
        Font prevFont = g2.getFont();
        g2.setFont(font);
        FontMetrics fm = g2.getFontMetrics();
        int tw = fm.stringWidth(code);
        int th = fm.getHeight();
        int bx = cx - tw / 2 - 4;
        int by = topY - th;
        g2.setColor(LABEL_BG);
        g2.fillRoundRect(bx, by, tw + 8, th + 2, 6, 6);
        g2.setColor(Color.WHITE);
        g2.drawString(code, cx - tw / 2, topY - fm.getDescent());
        g2.setFont(prevFont);
    }

    public String hitTestEquirectangular(int x, int y, double hitRadiusPx) {
        RenderedMarker marker = findNearestMarker(lastEquirectangularMarkers, x, y, hitRadiusPx);
        return marker != null ? marker.leadStation().stationId() : null;
    }

    public String hitTestPolar(int x, int y, double hitRadiusPx) {
        RenderedMarker marker = findNearestMarker(lastPolarMarkers, x, y, hitRadiusPx);
        return marker != null ? marker.leadStation().stationId() : null;
    }

    private static RenderedMarker findNearestMarker(
            List<RenderedMarker> markers, int x, int y, double hitRadiusPx) {
        if (markers == null || markers.isEmpty()) {
            return null;
        }
        RenderedMarker best = null;
        double bestDist = Double.MAX_VALUE;
        for (RenderedMarker marker : markers) {
            double effectiveRadius = marker.count() > 1 ? Math.max(hitRadiusPx, 13.0) : hitRadiusPx;
            double dist = Math.hypot(x - marker.px(), y - marker.py());
            if (dist <= effectiveRadius && dist < bestDist) {
                bestDist = dist;
                best = marker;
            }
        }
        return best;
    }

    public boolean handleLeftClick(
            int x,
            int y,
            double hitRadiusPx,
            GnssStationService service,
            GnssGeometryService geometryService,
            boolean perWindowVisible,
            boolean polar) {
        if (!perWindowVisible || service == null || !service.isMapOverlayEnabled()) {
            return false;
        }
        if (geometryService != null && geometryService.isPickOnMapMode()) {
            return false;
        }
        String hitId = polar
                ? hitTestPolar(x, y, hitRadiusPx)
                : hitTestEquirectangular(x, y, hitRadiusPx);
        if (hitId == null) {
            return false;
        }
        service.selectStation(hitId);
        return true;
    }

    public boolean updateHoverTooltip(
            JComponent target,
            int x,
            int y,
            double hitRadiusPx,
            GnssStationService service,
            boolean perWindowVisible,
            boolean polar) {
        if (target == null || service == null || !perWindowVisible || !service.isMapOverlayEnabled()) {
            return false;
        }
        List<RenderedMarker> markers = polar ? lastPolarMarkers : lastEquirectangularMarkers;
        RenderedMarker hit = findNearestMarker(markers, x, y, hitRadiusPx);
        if (hit == null) {
            return false;
        }
        if (hit.count() > 1) {
            target.setToolTipText("<html><b>" + hit.count() + " GNSS Reference Stations</b>"
                    + "<br/>Lead: " + escapeHtml(hit.leadStation().stationId()) + "</html>");
            return true;
        }
        target.setToolTipText(formatTooltipHtml(hit.leadStation(), service.getLatestRegionalReport()));
        return true;
    }

    public String formatTooltipHtml(GnssReferenceStation station, RegionalScanReport report) {
        if (station == null) {
            return "";
        }
        String nets = station.networks().stream()
                .map(GnssStationNetwork::getDisplayName)
                .collect(Collectors.joining(", "));
        String consts = station.supportedConstellations().isEmpty()
                ? "—"
                : station.supportedConstellations().stream()
                        .map(GnssConstellation::getDisplayName)
                        .collect(Collectors.joining(", "));
        String formats = station.availableFormats().isEmpty()
                ? "RINEX"
                : String.join(", ", station.availableFormats());
        String latencyStr = station.latencyMinutes() >= 0
                ? station.latencyMinutes() + " min"
                : "N/A";
        String availStr = Double.isFinite(station.availabilityPct())
                ? String.format(Locale.US, "%.1f%%", station.availabilityPct())
                : "—";

        StringBuilder sb = new StringBuilder();
        sb.append("<html><b>").append(escapeHtml(station.stationId())).append("</b>");
        if (!station.siteName().isBlank()) {
            sb.append(" &middot; ").append(escapeHtml(station.siteName()));
        }
        if (!station.countryCode().isBlank()) {
            sb.append(" (").append(escapeHtml(station.countryCode())).append(")");
        }
        sb.append("<br/>Networks: ").append(escapeHtml(nets))
                .append(" &middot; Status: ").append(escapeHtml(station.status().getDisplayLabel()));
        if (!station.receiverModel().isBlank()) {
            sb.append("<br/>Receiver: ").append(escapeHtml(station.receiverModel()));
        }
        if (!station.antennaModel().isBlank()) {
            sb.append(" | Antenna: ").append(escapeHtml(station.antennaModel()));
        }
        sb.append("<br/>Constellations: ").append(escapeHtml(consts))
                .append(" &middot; Formats: ").append(escapeHtml(formats))
                .append("<br/>Latency: ").append(escapeHtml(latencyStr))
                .append(" &middot; Availability: ").append(escapeHtml(availStr));

        if (report != null && report.stations() != null) {
            for (StationAnomalySummary s : report.stations()) {
                if (s != null && s.station() != null
                        && Objects.equals(s.station().stationId(), station.stationId())) {
                    sb.append("<br/>Regional scan: <b>").append(escapeHtml(s.level().getDisplayLabel())).append("</b>");
                    if (Double.isFinite(s.medianDeltaCn0DbHz())) {
                        sb.append(String.format(Locale.US, " (&Delta;C/N0 %+.1f dB-Hz)", s.medianDeltaCn0DbHz()));
                    }
                    break;
                }
            }
        }
        sb.append("</html>");
        return sb.toString();
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}

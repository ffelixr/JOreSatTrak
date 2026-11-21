package jsattrak.awareness.gnss.map;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import javax.swing.JComponent;
import jsattrak.awareness.gnss.GnssAwarenessService;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.GnssDisplayStatus;
import jsattrak.awareness.gnss.GnssOrbitPropagator;
import jsattrak.awareness.gnss.GnssSatelliteState;
import jsattrak.awareness.gnss.GnssStateListener;
import jsattrak.utilities.TLE;

/**
 * Shared Java2D renderer, hit-tester, tooltip formatter, and shape/palette provider for 2D and
 * 2D Polar GNSS constellation overlays.
 */
public class GnssOverlay2DRenderer {

    private static final Color COLOR_OPERATIONAL = new Color(0x3F, 0xBF, 0x6A);
    private static final Color COLOR_TESTING = new Color(0xF0, 0xA8, 0x30);
    private static final Color COLOR_UNAVAILABLE = new Color(0xE5, 0x48, 0x4D);
    private static final Color COLOR_NO_DATA = new Color(0x90, 0xA4, 0xAE);

    private static final Color OUTLINE_DARK = new Color(18, 22, 28, 230);
    private static final Color HALO_SELECTED = new Color(255, 255, 255, 95);
    private static final Color TRACK_COLOR = new Color(255, 255, 255, 195);
    private static final Color LABEL_BG = new Color(18, 22, 28, 205);
    private static final Color LEGEND_BG = new Color(18, 22, 28, 200);
    private static final Color LEGEND_BORDER = new Color(255, 255, 255, 70);

    private static final int GROUND_TRACK_SAMPLES = 96;

    private volatile List<RenderedMarker> lastRenderedMarkers = List.of();
    private volatile String cachedTrackKey = "";
    private volatile List<List<double[]>> cachedTrackSegments = List.of();

    public static Color colorForStatus(GnssDisplayStatus status) {
        if (status == null) {
            return COLOR_NO_DATA;
        }
        return switch (status) {
            case OPERATIONAL -> COLOR_OPERATIONAL;
            case TESTING -> COLOR_TESTING;
            case UNAVAILABLE -> COLOR_UNAVAILABLE;
            case NO_STATUS_DATA -> COLOR_NO_DATA;
        };
    }

    /**
     * Draws a constellation-specific marker shape centered at {@code (cx, cy)}.
     */
    public static void drawConstellationShape(
            Graphics2D g2,
            GnssConstellation constellation,
            double cx,
            double cy,
            double radius,
            Color fill,
            Color outline) {
        GnssConstellation.MarkerShape shape = constellation != null
                ? constellation.getMarkerShape()
                : GnssConstellation.MarkerShape.CIRCLE;
        Shape geom = buildShapeGeometry(shape, cx, cy, radius);
        if (shape == GnssConstellation.MarkerShape.OPEN_DIAMOND) {
            Stroke prev = g2.getStroke();
            g2.setStroke(new BasicStroke(1.5f));
            g2.setColor(fill);
            g2.draw(geom);
            g2.setStroke(prev);
            return;
        }
        g2.setColor(fill);
        g2.fill(geom);
        if (outline != null) {
            Stroke prev = g2.getStroke();
            g2.setStroke(new BasicStroke(1.1f));
            g2.setColor(outline);
            g2.draw(geom);
            g2.setStroke(prev);
        }
    }

    /**
     * Splits a sequence of {@code [latDeg, lonDeg]} points into contiguous segments whenever
     * consecutive longitude steps cross the &plusmn;180&deg; antimeridian.
     */
    public static List<List<double[]>> splitGroundTrackSegments(List<double[]> latLonPoints) {
        if (latLonPoints == null || latLonPoints.isEmpty()) {
            return List.of();
        }
        List<List<double[]>> segments = new ArrayList<>();
        List<double[]> current = new ArrayList<>();
        double prevLon = Double.NaN;
        for (double[] pt : latLonPoints) {
            if (pt == null || pt.length < 2 || !Double.isFinite(pt[0]) || !Double.isFinite(pt[1])) {
                continue;
            }
            if (!Double.isNaN(prevLon) && Math.abs(pt[1] - prevLon) > 180.0 && !current.isEmpty()) {
                segments.add(List.copyOf(current));
                current = new ArrayList<>();
            }
            current.add(pt);
            prevLon = pt[1];
        }
        if (!current.isEmpty()) {
            segments.add(List.copyOf(current));
        }
        return List.copyOf(segments);
    }

    public void clear() {
        this.lastRenderedMarkers = List.of();
    }

    public int paintServiceOverlay(
            Graphics2D g2,
            GnssAwarenessService service,
            boolean perWindowVisible,
            long simEpochMillis,
            Rectangle bounds,
            BiFunction<Double, Double, int[]> latLonProjector) {
        if (g2 == null || latLonProjector == null || service == null
                || !perWindowVisible || !service.isMapOverlayEnabled() || !service.isFeedEnabled()) {
            clear();
            return 0;
        }
        List<GnssSatelliteState> visible = service.getVisibleSatelliteStates(simEpochMillis);
        if (visible.isEmpty()) {
            clear();
            return 0;
        }

        Object prevAA = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        Stroke prevStroke = g2.getStroke();
        Font prevFont = g2.getFont();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        String selectedPrn = service.getSelectedPrnId();
        GnssSatelliteState selectedState = null;
        if (selectedPrn != null) {
            for (GnssSatelliteState s : visible) {
                if (selectedPrn.equals(s.catalog().prnId())) {
                    selectedState = s;
                    break;
                }
            }
        }

        // Draw 1-period ground track only for the selected GNSS satellite
        if (selectedState != null && selectedState.tle() != null) {
            paintSelectedGroundTrack(g2, selectedState, simEpochMillis, latLonProjector);
        }

        List<RenderedMarker> rendered = new ArrayList<>(visible.size());
        RenderedMarker selectedMarker = null;
        for (GnssSatelliteState s : visible) {
            GnssOrbitPropagator.GeodeticPosition pos = s.position();
            if (pos == null) {
                continue;
            }
            int[] xy = latLonProjector.apply(pos.latDeg(), pos.lonDeg());
            if (xy == null || xy.length < 2) {
                continue;
            }
            RenderedMarker marker = new RenderedMarker(xy[0], xy[1], s);
            rendered.add(marker);
            boolean isSelected = s.catalog().prnId().equals(selectedPrn);
            if (isSelected) {
                selectedMarker = marker;
            } else {
                drawConstellationShape(
                        g2,
                        s.catalog().constellation(),
                        xy[0],
                        xy[1],
                        4.2,
                        colorForStatus(s.statusResult().status()),
                        OUTLINE_DARK);
            }
        }

        if (selectedMarker != null) {
            paintSelectedMarker(g2, selectedMarker);
        }

        if (bounds != null && bounds.width >= 260 && bounds.height >= 140) {
            paintCompactLegend(g2, bounds);
        }

        this.lastRenderedMarkers = List.copyOf(rendered);
        g2.setFont(prevFont);
        g2.setStroke(prevStroke);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, prevAA);
        return rendered.size();
    }

    public boolean handleLeftClick(
            int x,
            int y,
            double hitRadiusPx,
            GnssAwarenessService service,
            boolean perWindowVisible) {
        if (service == null || !perWindowVisible || !service.isMapOverlayEnabled() || !service.isFeedEnabled()) {
            return false;
        }
        RenderedMarker hit = findNearestMarker(x, y, hitRadiusPx);
        if (hit == null) {
            return false;
        }
        service.selectSatelliteAndRequestOpenUi(hit.state().catalog().prnId());
        return true;
    }

    public boolean updateHoverTooltip(
            JComponent target,
            int x,
            int y,
            double hitRadiusPx,
            GnssAwarenessService service,
            boolean perWindowVisible) {
        if (target == null || service == null || !perWindowVisible
                || !service.isMapOverlayEnabled() || !service.isFeedEnabled()) {
            return false;
        }
        RenderedMarker hit = findNearestMarker(x, y, hitRadiusPx);
        if (hit == null) {
            return false;
        }
        GnssSatelliteState s = hit.state();
        String tooltip = "<html><b>" + escapeHtml(s.catalog().prnId()) + "</b> &middot; "
                + escapeHtml(s.catalog().constellation().getDisplayName()) + " &mdash; "
                + escapeHtml(s.statusResult().status().getDisplayLabel()) + "</html>";
        target.setToolTipText(tooltip);
        return true;
    }

    public static GnssStateListener createRepaintListener(Component repaintTarget) {
        return new GnssStateListener() {
            @Override
            public void onGnssDataChanged() {
                if (repaintTarget != null) {
                    repaintTarget.repaint();
                }
            }

            @Override
            public void onGnssSelectionChanged(String selectedPrnId) {
                if (repaintTarget != null) {
                    repaintTarget.repaint();
                }
            }

            @Override
            public void onGnssStatusMessage(String message, boolean isWarning) {
            }
        };
    }

    private void paintSelectedGroundTrack(
            Graphics2D g2,
            GnssSatelliteState selectedState,
            long simEpochMillis,
            BiFunction<Double, Double, int[]> projector) {
        TLE tle = selectedState.tle();
        long epochMinute = simEpochMillis / 60_000L;
        String key = selectedState.catalog().prnId() + ":" + epochMinute + ":" + tle.getLine1();
        List<List<double[]>> segments;
        if (Objects.equals(key, this.cachedTrackKey)) {
            segments = this.cachedTrackSegments;
        } else {
            List<double[]> raw = GnssOrbitPropagator.computeGroundTrack(
                    tle, simEpochMillis, GROUND_TRACK_SAMPLES);
            segments = splitGroundTrackSegments(raw);
            this.cachedTrackSegments = segments;
            this.cachedTrackKey = key;
        }
        if (segments.isEmpty()) {
            return;
        }
        g2.setColor(TRACK_COLOR);
        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (List<double[]> seg : segments) {
            Path2D.Double path = new Path2D.Double();
            boolean penDown = false;
            int prevX = 0;
            int prevY = 0;
            int prevDisc = Integer.MIN_VALUE;
            for (double[] ll : seg) {
                int[] xy = projector.apply(ll[0], ll[1]);
                if (xy == null) {
                    penDown = false;
                    continue;
                }
                int disc = xy.length >= 3 ? xy[2] : 0;
                if (!penDown
                        || (prevDisc != Integer.MIN_VALUE && disc != prevDisc)
                        || Math.abs(xy[0] - prevX) > 220
                        || Math.abs(xy[1] - prevY) > 220) {
                    path.moveTo(xy[0], xy[1]);
                    penDown = true;
                } else {
                    path.lineTo(xy[0], xy[1]);
                }
                prevX = xy[0];
                prevY = xy[1];
                prevDisc = disc;
            }
            g2.draw(path);
        }
    }

    private static void paintSelectedMarker(Graphics2D g2, RenderedMarker marker) {
        int x = marker.x();
        int y = marker.y();
        GnssSatelliteState s = marker.state();
        g2.setColor(HALO_SELECTED);
        g2.fill(new Ellipse2D.Double(x - 10, y - 10, 20, 20));
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(1.8f));
        g2.draw(new Ellipse2D.Double(x - 8, y - 8, 16, 16));

        drawConstellationShape(
                g2,
                s.catalog().constellation(),
                x,
                y,
                5.2,
                colorForStatus(s.statusResult().status()),
                OUTLINE_DARK);

        Font labelFont = g2.getFont().deriveFont(Font.BOLD, 11f);
        g2.setFont(labelFont);
        FontMetrics fm = g2.getFontMetrics();
        String text = s.catalog().prnId();
        int tw = fm.stringWidth(text);
        int lx = x + 10;
        int ly = y - 8;
        g2.setColor(LABEL_BG);
        g2.fill(new RoundRectangle2D.Double(lx - 3, ly - fm.getAscent(), tw + 6, fm.getHeight() + 1, 5, 5));
        g2.setColor(Color.WHITE);
        g2.drawString(text, lx, ly);
    }

    private static void paintCompactLegend(Graphics2D g2, Rectangle bounds) {
        Font legendFont = new Font("SansSerif", Font.PLAIN, 10);
        g2.setFont(legendFont);
        FontMetrics fm = g2.getFontMetrics();
        int pad = 6;
        int rowH = 14;
        String line1 = "OP   TEST   UNAV   NO DATA";
        String line2 = "GPS  GAL  GLO  BDS";
        int boxW = Math.max(fm.stringWidth(line1) + 56, fm.stringWidth(line2) + 58);
        int boxH = rowH * 2 + pad;
        int bx = bounds.x + 8;
        int by = bounds.y + bounds.height - boxH - 26;
        if (by < bounds.y + 4) {
            return;
        }
        g2.setColor(LEGEND_BG);
        g2.fill(new RoundRectangle2D.Double(bx, by, boxW, boxH, 6, 6));
        g2.setColor(LEGEND_BORDER);
        g2.setStroke(new BasicStroke(1f));
        g2.draw(new RoundRectangle2D.Double(bx, by, boxW, boxH, 6, 6));

        int y1 = by + pad + 4;
        int cx = bx + 10;
        GnssDisplayStatus[] statuses = {
                GnssDisplayStatus.OPERATIONAL,
                GnssDisplayStatus.TESTING,
                GnssDisplayStatus.UNAVAILABLE,
                GnssDisplayStatus.NO_STATUS_DATA
        };
        String[] sLabels = {"Op", "Test", "Unav", "No data"};
        for (int i = 0; i < statuses.length; i++) {
            g2.setColor(colorForStatus(statuses[i]));
            g2.fill(new Ellipse2D.Double(cx - 3.5, y1 - 3.5, 7, 7));
            g2.setColor(Color.WHITE);
            g2.drawString(sLabels[i], cx + 6, y1 + 3);
            cx += fm.stringWidth(sLabels[i]) + 15;
        }

        int y2 = y1 + rowH;
        cx = bx + 10;
        for (GnssConstellation c : GnssConstellation.layer1ActiveValues()) {
            drawConstellationShape(g2, c, cx, y2, 3.3, Color.LIGHT_GRAY, OUTLINE_DARK);
            g2.setColor(Color.LIGHT_GRAY);
            String shortName = switch (c) {
                case GPS -> "GPS";
                case GALILEO -> "GAL";
                case GLONASS -> "GLO";
                case BEIDOU -> "BDS";
                case SBAS -> "SBAS";
            };
            g2.drawString(shortName, cx + 6, y2 + 3);
            cx += fm.stringWidth(shortName) + 16;
        }
    }

    private RenderedMarker findNearestMarker(int x, int y, double hitRadiusPx) {
        RenderedMarker best = null;
        double bestDist = hitRadiusPx;
        for (RenderedMarker m : lastRenderedMarkers) {
            double d = Math.hypot(m.x() - x, m.y() - y);
            if (d <= bestDist) {
                bestDist = d;
                best = m;
            }
        }
        return best;
    }

    private static Shape buildShapeGeometry(
            GnssConstellation.MarkerShape shape,
            double cx,
            double cy,
            double r) {
        return switch (shape) {
            case CIRCLE -> new Ellipse2D.Double(cx - r, cy - r, 2.0 * r, 2.0 * r);
            case SQUARE -> new Rectangle2D.Double(cx - r * 0.85, cy - r * 0.85, r * 1.7, r * 1.7);
            case DIAMOND, OPEN_DIAMOND -> {
                Path2D.Double p = new Path2D.Double();
                double d = r * 1.18;
                p.moveTo(cx, cy - d);
                p.lineTo(cx + d, cy);
                p.lineTo(cx, cy + d);
                p.lineTo(cx - d, cy);
                p.closePath();
                yield p;
            }
            case TRIANGLE -> {
                Path2D.Double p = new Path2D.Double();
                p.moveTo(cx, cy - r * 1.25);
                p.lineTo(cx + r * 1.12, cy + r * 0.85);
                p.lineTo(cx - r * 1.12, cy + r * 0.85);
                p.closePath();
                yield p;
            }
        };
    }

    private static String escapeHtml(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private record RenderedMarker(int x, int y, GnssSatelliteState state) {
    }
}

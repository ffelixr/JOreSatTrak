package jsattrak.awareness.gnss.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JPanel;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import jsattrak.awareness.gnss.map.GnssOverlay2DRenderer;
import jsattrak.awareness.gnss.stations.analysis.GnssStationAnalysis;
import jsattrak.awareness.gnss.stations.analysis.GnssStationAnalysis.SatelliteMeasurementSummary;
import jsattrak.awareness.gnss.stations.analysis.GnssStationAnalysis.TrackingState;

/**
 * Observed vs. predicted polar skyplot coloring tracked SVs by median C/N0, flagging cycle slips,
 * and drawing dashed hollow crimson rings for missing operational SVs.
 */
public class GnssObservedSkyplotPanel extends JPanel {

    private static final Color COLOR_LOW_CN0 = new Color(0xE5, 0x48, 0x4D);
    private static final Color COLOR_MID_CN0 = new Color(0xFA, 0x8C, 0x16);
    private static final Color COLOR_GOOD_CN0 = new Color(0x3F, 0xBF, 0x6A);
    private static final Color COLOR_UNKNOWN = new Color(0x8C, 0x95, 0xA6);
    private static final Color SLIP_HALO = new Color(0xFA, 0x8C, 0x16, 220);

    private record PlottedSv(SatelliteMeasurementSummary sv, int px, int py) {
    }

    private GnssStationAnalysis analysis;
    private volatile List<PlottedSv> plottedPoints = List.of();

    public GnssObservedSkyplotPanel() {
        setPreferredSize(new Dimension(280, 280));
        setMinimumSize(new Dimension(210, 210));
        setOpaque(true);
        ToolTipManager.sharedInstance().registerComponent(this);
        addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                updateHoverTooltip(e.getX(), e.getY());
            }
        });
    }

    public void setAnalysis(GnssStationAnalysis analysis) {
        this.analysis = analysis;
        repaint();
    }

    public static Color colorForCn0(double cn0DbHz) {
        if (!Double.isFinite(cn0DbHz) || cn0DbHz <= 0.0) {
            return COLOR_UNKNOWN;
        }
        if (cn0DbHz < 30.0) {
            return COLOR_LOW_CN0;
        }
        if (cn0DbHz < 38.0) {
            double t = (cn0DbHz - 30.0) / 8.0;
            return interpolateColor(COLOR_LOW_CN0, COLOR_MID_CN0, t);
        }
        if (cn0DbHz < 44.0) {
            double t = (cn0DbHz - 38.0) / 6.0;
            return interpolateColor(COLOR_MID_CN0, COLOR_GOOD_CN0, t);
        }
        return COLOR_GOOD_CN0;
    }

    private static Color interpolateColor(Color a, Color b, double t) {
        double c = Math.max(0.0, Math.min(1.0, t));
        return new Color(
                (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * c),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * c),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * c));
    }

    private void updateHoverTooltip(int x, int y) {
        PlottedSv best = null;
        double bestDist = 14.0;
        for (PlottedSv p : plottedPoints) {
            double d = Math.hypot(x - p.px(), y - p.py());
            if (d <= bestDist) {
                bestDist = d;
                best = p;
            }
        }
        if (best == null) {
            setToolTipText(null);
            return;
        }
        SatelliteMeasurementSummary sv = best.sv();
        String cn0Str = Double.isFinite(sv.medianCn0DbHz())
                ? String.format(Locale.US, "%.1f dB-Hz", sv.medianCn0DbHz())
                : "N/A";
        String deltaStr = Double.isFinite(sv.medianDeltaCn0DbHz())
                ? String.format(Locale.US, "%+.1f dB", sv.medianDeltaCn0DbHz())
                : "—";
        setToolTipText(String.format(Locale.US,
                "<html><b>%s</b> (%s) &middot; %s<br/>Az %.1f&deg;, El %.1f&deg;<br/>Med C/N0: %s (&Delta; %s)<br/>Slips: %d &middot; Obs: %d</html>",
                sv.prnId(),
                sv.constellation().getDisplayName(),
                sv.trackingState().getDisplayLabel(),
                sv.azimuthDeg(),
                sv.elevationDeg(),
                cn0Str,
                deltaStr,
                sv.slipCount(),
                sv.obsCount()));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            Color panelBg = uiColor("Panel.background", new Color(36, 40, 46));
            Color circleBg = darkerOrDark(panelBg);
            Color gridColor = uiColor("Component.borderColor", new Color(110, 120, 135, 110));
            Color textColor = uiColor("Label.foreground", new Color(225, 230, 238));
            Color mutedColor = uiColor("Label.disabledForeground", new Color(145, 152, 165));

            g2.setColor(panelBg);
            g2.fillRect(0, 0, w, h);

            int pad = 26;
            int diam = Math.max(40, Math.min(w, h) - pad * 2);
            int radius = diam / 2;
            int cx = w / 2;
            int cy = h / 2;

            Ellipse2D.Double outerDisc = new Ellipse2D.Double(cx - radius, cy - radius, 2.0 * radius, 2.0 * radius);
            g2.setColor(circleBg);
            g2.fill(outerDisc);

            double maskDeg = analysis != null ? Math.max(0.0, Math.min(45.0, analysis.elevationMaskDeg())) : 10.0;
            if (maskDeg > 0.1) {
                double innerR = radius * ((90.0 - maskDeg) / 90.0);
                Ellipse2D.Double innerDisc = new Ellipse2D.Double(cx - innerR, cy - innerR, 2.0 * innerR, 2.0 * innerR);
                Area maskRing = new Area(outerDisc);
                maskRing.subtract(new Area(innerDisc));
                g2.setColor(new Color(229, 72, 77, 36));
                g2.fill(maskRing);
                g2.setColor(new Color(229, 72, 77, 130));
                g2.draw(innerDisc);
            }

            Stroke solid = new BasicStroke(1.2f);
            Stroke dashed = new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f);
            g2.setColor(gridColor);
            g2.setStroke(solid);
            g2.draw(outerDisc);
            g2.setStroke(dashed);
            for (int el : new int[]{30, 60}) {
                double rEl = radius * ((90.0 - el) / 90.0);
                g2.draw(new Ellipse2D.Double(cx - rEl, cy - rEl, 2.0 * rEl, 2.0 * rEl));
            }
            g2.drawLine(cx - radius, cy, cx + radius, cy);
            g2.drawLine(cx, cy - radius, cx, cy + radius);

            Font smallFont = getFont().deriveFont(Font.PLAIN, 10.0f);
            g2.setFont(smallFont);
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(mutedColor);
            g2.drawString("30\u00B0", cx + 3, (int) Math.round(cy - radius * (60.0 / 90.0)) + fm.getAscent() / 2);
            g2.drawString("60\u00B0", cx + 3, (int) Math.round(cy - radius * (30.0 / 90.0)) + fm.getAscent() / 2);

            Font compassFont = getFont().deriveFont(Font.BOLD, 11.0f);
            g2.setFont(compassFont);
            FontMetrics cfm = g2.getFontMetrics();
            g2.setColor(textColor);
            g2.drawString("N", cx - cfm.stringWidth("N") / 2, cy - radius - 5);
            g2.drawString("S", cx - cfm.stringWidth("S") / 2, cy + radius + cfm.getAscent() + 2);
            g2.drawString("E", cx + radius + 4, cy + cfm.getAscent() / 2 - 1);
            g2.drawString("W", cx - radius - cfm.stringWidth("W") - 4, cy + cfm.getAscent() / 2 - 1);

            List<PlottedSv> nextPlotted = new ArrayList<>();
            if (analysis != null && analysis.satellites() != null && !analysis.satellites().isEmpty()) {
                g2.setFont(getFont().deriveFont(Font.BOLD, 10.0f));
                for (SatelliteMeasurementSummary sv : analysis.satellites()) {
                    if (!Double.isFinite(sv.azimuthDeg()) || !Double.isFinite(sv.elevationDeg()) || sv.elevationDeg() < 0.0) {
                        continue;
                    }
                    double rNorm = Math.max(0.0, Math.min(1.0, (90.0 - sv.elevationDeg()) / 90.0));
                    double azRad = Math.toRadians(sv.azimuthDeg());
                    int px = (int) Math.round(cx + radius * rNorm * Math.sin(azRad));
                    int py = (int) Math.round(cy - radius * rNorm * Math.cos(azRad));

                    if (sv.trackingState() == TrackingState.MISSING) {
                        Stroke prev = g2.getStroke();
                        g2.setColor(COLOR_LOW_CN0);
                        g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{3f, 2f}, 0f));
                        g2.draw(new Ellipse2D.Double(px - 6.0, py - 6.0, 12.0, 12.0));
                        g2.setStroke(prev);
                        g2.drawString(sv.prnId(), px + 7, py + 4);
                    } else {
                        if (sv.slipCount() > 0) {
                            Stroke prev = g2.getStroke();
                            g2.setColor(SLIP_HALO);
                            g2.setStroke(new BasicStroke(1.7f));
                            g2.draw(new Ellipse2D.Double(px - 8.5, py - 8.5, 17.0, 17.0));
                            g2.setStroke(prev);
                        }
                        Color fill = colorForCn0(sv.medianCn0DbHz());
                        Color outline = new Color(16, 20, 26, 235);
                        GnssOverlay2DRenderer.drawConstellationShape(
                                g2, sv.constellation(), px, py, 5.5, fill, outline);
                        g2.setColor(textColor);
                        g2.drawString(sv.prnId(), px + 7, py + 4);
                    }
                    nextPlotted.add(new PlottedSv(sv, px, py));
                }
            } else {
                g2.setFont(smallFont);
                g2.setColor(mutedColor);
                String msg = analysis == null ? "Select a station & Fetch Obs" : "No sky geometry available";
                g2.drawString(msg, cx - fm.stringWidth(msg) / 2, cy);
            }
            this.plottedPoints = List.copyOf(nextPlotted);
        } finally {
            g2.dispose();
        }
    }

    private static Color uiColor(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }

    private static Color darkerOrDark(Color bg) {
        int luma = (bg.getRed() * 299 + bg.getGreen() * 587 + bg.getBlue() * 114) / 1000;
        if (luma < 128) {
            return new Color(Math.max(0, bg.getRed() - 10), Math.max(0, bg.getGreen() - 10), Math.max(0, bg.getBlue() - 8));
        }
        return new Color(242, 245, 249);
    }
}

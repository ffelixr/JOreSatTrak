package jsattrak.awareness.gnss.gui;

import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.geometry.GnssDopSolution;
import jsattrak.awareness.gnss.geometry.SatelliteSkyPoint;
import jsattrak.awareness.gnss.map.GnssOverlay2DRenderer;

import javax.swing.JPanel;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
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

/**
 * Polar azimuth/elevation skyplot for instantaneous GNSS satellite visibility above an observer.
 */
public class GnssSkyplotPanel extends JPanel {

    private record PlottedPoint(SatelliteSkyPoint point, int px, int py) {
    }

    private GnssDopSolution solution;
    private List<PlottedPoint> plottedPoints = List.of();

    public GnssSkyplotPanel() {
        setPreferredSize(new Dimension(280, 260));
        setMinimumSize(new Dimension(200, 200));
        setOpaque(true);
        ToolTipManager.sharedInstance().registerComponent(this);
        addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                updateTooltip(e.getX(), e.getY());
            }
        });
    }

    public void setSolution(GnssDopSolution solution) {
        this.solution = solution;
        repaint();
    }

    public GnssDopSolution getSolution() {
        return solution;
    }

    /**
     * Hit-tests rendered skyplot markers within 12 px and returns the nearest satellite, if any.
     */
    public SatelliteSkyPoint findSatelliteAt(int x, int y) {
        SatelliteSkyPoint best = null;
        double bestDistSq = 12.0 * 12.0;
        for (PlottedPoint p : plottedPoints) {
            double dx = p.px() - x;
            double dy = p.py() - y;
            double dSq = dx * dx + dy * dy;
            if (dSq <= bestDistSq) {
                bestDistSq = dSq;
                best = p.point();
            }
        }
        return best;
    }

    private void updateTooltip(int x, int y) {
        SatelliteSkyPoint hit = findSatelliteAt(x, y);
        if (hit == null) {
            setToolTipText(null);
            return;
        }
        GnssConstellation c = hit.constellation();
        String constName = c != null ? c.getDisplayName() : "";
        String statusStr = hit.status() != null ? hit.status().getDisplayLabel() : "";
        String srcStr = hit.orbitSource() != null ? hit.orbitSource().getDisplayLabel() : "";
        setToolTipText(String.format(Locale.US,
                "<html><b>%s</b> (%s)<br>Az: %.1f&deg; &nbsp; El: %.1f&deg;<br>Status: %s%s<br>Orbit: %s</html>",
                hit.prnId(),
                constName,
                hit.azimuthDeg(),
                hit.elevationDeg(),
                statusStr,
                hit.usedInDop() ? " (Used in DOP)" : (hit.aboveMask() ? " (Excluded)" : " (Below Mask)"),
                srcStr));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            Color panelBg = uiColor("Panel.background", new Color(36, 39, 46));
            Color textColor = uiColor("Label.foreground", new Color(220, 224, 232));
            Color mutedColor = uiColor("Label.disabledForeground", new Color(145, 152, 165));
            Color circleBg = darkerOrDark(panelBg);
            Color gridColor = new Color(mutedColor.getRed(), mutedColor.getGreen(), mutedColor.getBlue(), 110);

            g2.setColor(panelBg);
            g2.fillRect(0, 0, w, h);

            int pad = 26;
            int radius = Math.max(24, Math.min(w, h) / 2 - pad);
            int cx = w / 2;
            int cy = h / 2;

            // Sky disc background
            Ellipse2D.Double outerDisc = new Ellipse2D.Double(cx - radius, cy - radius, 2.0 * radius, 2.0 * radius);
            g2.setColor(circleBg);
            g2.fill(outerDisc);

            // Shaded elevation mask annulus [0° .. mask]
            double maskDeg = solution != null ? Math.max(0.0, Math.min(45.0, solution.elevationMaskDeg())) : 10.0;
            if (maskDeg > 0.1) {
                double innerR = radius * ((90.0 - maskDeg) / 90.0);
                Ellipse2D.Double innerDisc = new Ellipse2D.Double(cx - innerR, cy - innerR, 2.0 * innerR, 2.0 * innerR);
                Area maskRing = new Area(outerDisc);
                maskRing.subtract(new Area(innerDisc));
                g2.setColor(new Color(229, 72, 77, 38));
                g2.fill(maskRing);
                g2.setColor(new Color(229, 72, 77, 130));
                g2.setStroke(new BasicStroke(1.0f));
                g2.draw(innerDisc);
            }

            // Elevation rings (0°, 30°, 60°) & crosshairs
            Stroke solid = new BasicStroke(1.2f);
            float[] dash = {4f, 4f};
            Stroke dashed = new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, dash, 0f);
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

            // Ring degree labels & N/E/S/W compass ticks
            Font smallFont = getFont().deriveFont(Font.PLAIN, 10.5f);
            g2.setFont(smallFont);
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(mutedColor);
            g2.drawString("30\u00B0", cx + 3, (int) Math.round(cy - radius * (60.0 / 90.0)) + fm.getAscent() / 2);
            g2.drawString("60\u00B0", cx + 3, (int) Math.round(cy - radius * (30.0 / 90.0)) + fm.getAscent() / 2);

            Font compassFont = getFont().deriveFont(Font.BOLD, 11.5f);
            g2.setFont(compassFont);
            FontMetrics cfm = g2.getFontMetrics();
            g2.setColor(textColor);
            g2.drawString("N", cx - cfm.stringWidth("N") / 2, cy - radius - 6);
            g2.drawString("S", cx - cfm.stringWidth("S") / 2, cy + radius + cfm.getAscent() + 3);
            g2.drawString("E", cx + radius + 5, cy + cfm.getAscent() / 2 - 1);
            g2.drawString("W", cx - radius - cfm.stringWidth("W") - 5, cy + cfm.getAscent() / 2 - 1);

            // Plot satellites (Azimuth 0=N clockwise: E=90, S=180, W=270)
            List<PlottedPoint> newPlotted = new ArrayList<>();
            if (solution != null && solution.skyPoints() != null) {
                g2.setFont(getFont().deriveFont(Font.BOLD, 10.5f));
                for (SatelliteSkyPoint pt : solution.skyPoints()) {
                    if (pt.elevationDeg() < 0.0) {
                        continue; // below horizon
                    }
                    double rNorm = Math.max(0.0, Math.min(1.0, (90.0 - pt.elevationDeg()) / 90.0));
                    double azRad = Math.toRadians(pt.azimuthDeg());
                    int px = (int) Math.round(cx + radius * rNorm * Math.sin(azRad));
                    int py = (int) Math.round(cy - radius * rNorm * Math.cos(azRad));

                    Color base = GnssOverlay2DRenderer.colorForStatus(pt.status());
                    boolean muted = !pt.aboveMask() || !pt.usedInDop();
                    Color fill = muted
                            ? new Color(base.getRed(), base.getGreen(), base.getBlue(), 110)
                            : base;
                    Color outline = muted ? new Color(30, 34, 40, 130) : new Color(16, 20, 26, 230);

                    GnssOverlay2DRenderer.drawConstellationShape(
                            g2, pt.constellation(), px, py, pt.usedInDop() ? 6.0 : 5.0, fill, outline);

                    g2.setColor(muted
                            ? new Color(textColor.getRed(), textColor.getGreen(), textColor.getBlue(), 125)
                            : textColor);
                    g2.drawString(pt.prnId(), px + 7, py + 4);
                    newPlotted.add(new PlottedPoint(pt, px, py));
                }
            } else {
                g2.setColor(mutedColor);
                String empty = "No ephemeris data";
                g2.drawString(empty, cx - fm.stringWidth(empty) / 2, cy);
            }
            this.plottedPoints = List.copyOf(newPlotted);
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

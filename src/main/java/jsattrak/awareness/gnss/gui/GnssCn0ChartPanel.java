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
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import javax.swing.JPanel;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import jsattrak.awareness.gnss.stations.analysis.GnssStationAnalysis;
import jsattrak.awareness.gnss.stations.analysis.GnssStationAnalysis.Cn0ElevationPoint;
import jsattrak.awareness.gnss.stations.analysis.GnssStationAnalysis.Cn0TimeSample;
import jsattrak.awareness.gnss.stations.analysis.GnssStationAnalysis.SatelliteMeasurementSummary;
import jsattrak.awareness.gnss.stations.analysis.GnssStationAnalysis.TrackingState;
import jsattrak.awareness.gnss.stations.analysis.RinexObservationAnalyzer;

/**
 * Multi-mode C/N0 measurement chart: C/N0 vs Elevation scatter + reference curve,
 * per-SV distribution whiskers, and epoch time series.
 */
public class GnssCn0ChartPanel extends JPanel {

    public enum ChartMode {
        CN0_VS_ELEVATION("C/N0 vs Elevation"),
        PER_SV_DISTRIBUTION("Per-SV Distribution"),
        CN0_TIME_SERIES("C/N0 Time Series");

        private final String label;

        ChartMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final Color CURVE_COLOR = new Color(0x00, 0xD8, 0xF6, 220);
    private static final Color COLOR_NOMINAL = new Color(0x3F, 0xBF, 0x6A);
    private static final Color COLOR_WARN = new Color(0xFA, 0x8C, 0x16);
    private static final Color COLOR_ALERT = new Color(0xE5, 0x48, 0x4D);
    private static final Color[] SERIES_PALETTE = {
            new Color(0x00, 0xD8, 0xF6),
            new Color(0x3F, 0xBF, 0x6A),
            new Color(0xFA, 0x8C, 0x16),
            new Color(0xA3, 0x7A, 0xFF),
            new Color(0xFF, 0x6B, 0x8A),
            new Color(0x6C, 0xA8, 0xFF)
    };

    private static final int PAD_LEFT = 42;
    private static final int PAD_RIGHT = 18;
    private static final int PAD_TOP = 24;
    private static final int PAD_BOTTOM = 28;
    private static final double MIN_CN0 = 15.0;
    private static final double MAX_CN0 = 55.0;

    private record HitTip(int x, int y, String html) {
    }

    private GnssStationAnalysis analysis;
    private TimeZone displayTz = TimeZone.getTimeZone("UTC");
    private ChartMode chartMode = ChartMode.CN0_VS_ELEVATION;
    private volatile List<HitTip> hoverTargets = List.of();

    public GnssCn0ChartPanel() {
        setPreferredSize(new Dimension(400, 260));
        setMinimumSize(new Dimension(240, 180));
        setOpaque(true);
        ToolTipManager.sharedInstance().registerComponent(this);
        addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                HitTip best = null;
                double bestDist = 14.0;
                for (HitTip tip : hoverTargets) {
                    double d = Math.hypot(e.getX() - tip.x(), e.getY() - tip.y());
                    if (d <= bestDist) {
                        bestDist = d;
                        best = tip;
                    }
                }
                setToolTipText(best != null ? best.html() : null);
            }
        });
    }

    public void setAnalysis(GnssStationAnalysis analysis, TimeZone displayTz) {
        this.analysis = analysis;
        if (displayTz != null) {
            this.displayTz = displayTz;
        }
        repaint();
    }

    public ChartMode getChartMode() {
        return chartMode;
    }

    public void setChartMode(ChartMode chartMode) {
        if (chartMode != null && this.chartMode != chartMode) {
            this.chartMode = chartMode;
            repaint();
        }
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
            Color gridColor = uiColor("Component.borderColor", new Color(100, 110, 125, 90));
            Color textColor = uiColor("Label.foreground", new Color(225, 230, 238));
            Color mutedColor = uiColor("Label.disabledForeground", new Color(145, 152, 165));

            g2.setColor(panelBg);
            g2.fillRect(0, 0, w, h);

            int plotW = Math.max(40, w - PAD_LEFT - PAD_RIGHT);
            int plotH = Math.max(40, h - PAD_TOP - PAD_BOTTOM);
            int plotX = PAD_LEFT;
            int plotY = PAD_TOP;

            drawYAxisGrid(g2, plotX, plotY, plotW, plotH, gridColor, mutedColor);

            if (analysis == null) {
                g2.setColor(mutedColor);
                g2.setFont(getFont().deriveFont(Font.PLAIN, 11.5f));
                String empty = "Fetch or load RINEX observations to inspect C/N0 metrics";
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(empty, plotX + (plotW - fm.stringWidth(empty)) / 2, plotY + plotH / 2);
                hoverTargets = List.of();
                return;
            }

            switch (chartMode) {
                case CN0_VS_ELEVATION -> paintVsElevation(g2, plotX, plotY, plotW, plotH, textColor, mutedColor);
                case PER_SV_DISTRIBUTION -> paintPerSvDistribution(g2, plotX, plotY, plotW, plotH, textColor, mutedColor);
                case CN0_TIME_SERIES -> paintTimeSeries(g2, plotX, plotY, plotW, plotH, textColor, mutedColor);
            }
        } finally {
            g2.dispose();
        }
    }

    private void drawYAxisGrid(
            Graphics2D g2, int px, int py, int pw, int ph, Color gridColor, Color mutedColor) {
        g2.setFont(getFont().deriveFont(Font.PLAIN, 10.0f));
        FontMetrics fm = g2.getFontMetrics();
        Stroke dashed = new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3f, 3f}, 0f);
        for (int db : new int[]{20, 30, 40, 50}) {
            int y = yForCn0(db, py, ph);
            g2.setColor(gridColor);
            g2.setStroke(dashed);
            g2.drawLine(px, y, px + pw, y);
            g2.setColor(mutedColor);
            String label = db + "";
            g2.drawString(label, px - fm.stringWidth(label) - 5, y + fm.getAscent() / 2 - 1);
        }
        g2.setStroke(new BasicStroke(1.1f));
        g2.setColor(gridColor);
        g2.drawRect(px, py, pw, ph);
    }

    private void paintVsElevation(
            Graphics2D g2, int px, int py, int pw, int ph, Color textColor, Color mutedColor) {
        // Empirical antenna C/N0(el) curve
        Path2D.Double curve = new Path2D.Double();
        for (int el = 0; el <= 90; el += 3) {
            double x = px + (el / 90.0) * pw;
            double y = yForCn0(RinexObservationAnalyzer.expectedCn0DbHz(el), py, ph);
            if (el == 0) {
                curve.moveTo(x, y);
            } else {
                curve.lineTo(x, y);
            }
        }
        g2.setColor(CURVE_COLOR);
        g2.setStroke(new BasicStroke(1.8f));
        g2.draw(curve);

        g2.setFont(getFont().deriveFont(Font.PLAIN, 10.0f));
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(mutedColor);
        for (int el : new int[]{0, 30, 60, 90}) {
            int x = (int) Math.round(px + (el / 90.0) * pw);
            String lbl = el + "\u00B0";
            g2.drawString(lbl, Math.min(px + pw - fm.stringWidth(lbl), x - fm.stringWidth(lbl) / 2), py + ph + fm.getAscent() + 3);
        }

        List<HitTip> tips = new ArrayList<>();
        for (Cn0ElevationPoint pt : analysis.elevationScatter()) {
            if (!Double.isFinite(pt.elevationDeg()) || !Double.isFinite(pt.cn0DbHz())) {
                continue;
            }
            double el = pt.elevationDeg();
            int cx = (int) Math.round(px + (Math.max(0.0, Math.min(90.0, el)) / 90.0) * pw);
            int cy = yForCn0(pt.cn0DbHz(), py, ph);
            Color dotColor = colorForDelta(pt.deltaCn0DbHz());
            if (pt.hasSlip()) {
                g2.setColor(COLOR_WARN);
                g2.draw(new Ellipse2D.Double(cx - 5.5, cy - 5.5, 11.0, 11.0));
            }
            g2.setColor(dotColor);
            g2.fill(new Ellipse2D.Double(cx - 3.5, cy - 3.5, 7.0, 7.0));
            tips.add(new HitTip(cx, cy, String.format(Locale.US,
                    "<html><b>%s</b> &middot; El %.1f&deg;<br/>C/N0 %.1f dB-Hz (&Delta; %+.1f dB)</html>",
                    pt.prnId(), el, pt.cn0DbHz(), pt.deltaCn0DbHz())));
        }
        if (tips.isEmpty() && !analysis.elevationScatter().isEmpty()) {
            g2.setColor(mutedColor);
            g2.setFont(getFont().deriveFont(Font.PLAIN, 11.0f));
            String noEph = "Elevation ephemeris unavailable (load TLE or Nav/SP3)";
            g2.drawString(noEph, px + Math.max(8, (pw - fm.stringWidth(noEph)) / 2), py + ph / 2);
        }
        this.hoverTargets = List.copyOf(tips);

        g2.setColor(textColor);
        g2.setFont(getFont().deriveFont(Font.BOLD, 11.0f));
        g2.drawString("C/N0 vs. Elevation (cyan = nominal curve)", px + 4, py - 6);
    }

    private void paintPerSvDistribution(
            Graphics2D g2, int px, int py, int pw, int ph, Color textColor, Color mutedColor) {
        List<SatelliteMeasurementSummary> tracked = analysis.satellites().stream()
                .filter(s -> s.trackingState() != TrackingState.MISSING && Double.isFinite(s.medianCn0DbHz()))
                .limit(28)
                .toList();
        if (tracked.isEmpty()) {
            g2.setColor(mutedColor);
            g2.drawString("No tracked PRNs to plot", px + 10, py + ph / 2);
            hoverTargets = List.of();
            return;
        }
        double slotW = (double) pw / tracked.size();
        int barW = Math.max(4, Math.min(14, (int) Math.round(slotW * 0.55)));
        List<HitTip> tips = new ArrayList<>();
        FontMetrics fm = g2.getFontMetrics(getFont().deriveFont(Font.PLAIN, 9.0f));

        for (int i = 0; i < tracked.size(); i++) {
            SatelliteMeasurementSummary sv = tracked.get(i);
            int cx = (int) Math.round(px + (i + 0.5) * slotW);
            int yMin = yForCn0(Double.isFinite(sv.minCn0DbHz()) ? sv.minCn0DbHz() : sv.medianCn0DbHz(), py, ph);
            int yMax = yForCn0(Double.isFinite(sv.maxCn0DbHz()) ? sv.maxCn0DbHz() : sv.medianCn0DbHz(), py, ph);
            int yP10 = yForCn0(Double.isFinite(sv.p10Cn0DbHz()) ? sv.p10Cn0DbHz() : sv.medianCn0DbHz(), py, ph);
            int yP90 = yForCn0(Double.isFinite(sv.p90Cn0DbHz()) ? sv.p90Cn0DbHz() : sv.medianCn0DbHz(), py, ph);
            int yMed = yForCn0(sv.medianCn0DbHz(), py, ph);

            Color c = colorForDelta(sv.medianDeltaCn0DbHz());
            g2.setColor(mutedColor);
            g2.setStroke(new BasicStroke(1.2f));
            g2.drawLine(cx, Math.min(yMin, yMax), cx, Math.max(yMin, yMax));

            int boxTop = Math.min(yP10, yP90);
            int boxH = Math.max(3, Math.abs(yP10 - yP90));
            g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 165));
            g2.fillRect(cx - barW / 2, boxTop, barW, boxH);
            g2.setColor(Color.WHITE);
            g2.drawLine(cx - barW / 2 - 1, yMed, cx + barW / 2 + 1, yMed);

            if (slotW >= 18) {
                g2.setFont(getFont().deriveFont(Font.PLAIN, 9.0f));
                g2.setColor(mutedColor);
                String lbl = sv.prnId();
                g2.drawString(lbl, cx - fm.stringWidth(lbl) / 2, py + ph + fm.getAscent() + 2);
            }
            tips.add(new HitTip(cx, yMed, String.format(Locale.US,
                    "<html><b>%s</b><br/>Med %.1f dB-Hz (p10 %.1f / p90 %.1f)<br/>&Delta;C/N0 %+.1f dB</html>",
                    sv.prnId(), sv.medianCn0DbHz(), sv.p10Cn0DbHz(), sv.p90Cn0DbHz(), sv.medianDeltaCn0DbHz())));
        }
        hoverTargets = List.copyOf(tips);
        g2.setColor(textColor);
        g2.setFont(getFont().deriveFont(Font.BOLD, 11.0f));
        g2.drawString("Per-SV C/N0 Distribution (p10..p90 & median)", px + 4, py - 6);
    }

    private void paintTimeSeries(
            Graphics2D g2, int px, int py, int pw, int ph, Color textColor, Color mutedColor) {
        List<Cn0TimeSample> series = analysis.timeSeries();
        if (series == null || series.isEmpty()) {
            g2.setColor(mutedColor);
            g2.drawString("No C/N0 time-series samples", px + 10, py + ph / 2);
            hoverTargets = List.of();
            return;
        }
        long minT = Long.MAX_VALUE;
        long maxT = Long.MIN_VALUE;
        Map<String, List<Cn0TimeSample>> byPrn = new LinkedHashMap<>();
        for (Cn0TimeSample s : series) {
            minT = Math.min(minT, s.epochMillis());
            maxT = Math.max(maxT, s.epochMillis());
            byPrn.computeIfAbsent(s.prnId(), k -> new ArrayList<>()).add(s);
        }
        long span = Math.max(1000L, maxT - minT);

        List<HitTip> tips = new ArrayList<>();
        int colorIdx = 0;
        for (Map.Entry<String, List<Cn0TimeSample>> entry : byPrn.entrySet()) {
            Color lineColor = SERIES_PALETTE[colorIdx++ % SERIES_PALETTE.length];
            Path2D.Double path = new Path2D.Double();
            boolean first = true;
            for (Cn0TimeSample s : entry.getValue()) {
                int x = (int) Math.round(px + ((s.epochMillis() - minT) / (double) span) * pw);
                int y = yForCn0(s.cn0DbHz(), py, ph);
                if (first) {
                    path.moveTo(x, y);
                    first = false;
                } else {
                    path.lineTo(x, y);
                }
                if (entry.getValue().size() <= 40) {
                    g2.setColor(lineColor);
                    g2.fillOval(x - 2, y - 2, 4, 4);
                }
                tips.add(new HitTip(x, y, String.format(Locale.US,
                        "<html><b>%s</b><br/>C/N0 %.1f dB-Hz (&Delta; %+.1f dB)</html>",
                        s.prnId(), s.cn0DbHz(), s.deltaCn0DbHz())));
            }
            g2.setColor(lineColor);
            g2.setStroke(new BasicStroke(1.5f));
            g2.draw(path);
            if (colorIdx >= 12) {
                break;
            }
        }
        hoverTargets = List.copyOf(tips);

        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.US);
        fmt.setTimeZone(displayTz);
        g2.setFont(getFont().deriveFont(Font.PLAIN, 10.0f));
        g2.setColor(mutedColor);
        String tStart = fmt.format(new Date(minT));
        String tEnd = fmt.format(new Date(maxT));
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(tStart, px, py + ph + fm.getAscent() + 3);
        g2.drawString(tEnd, px + pw - fm.stringWidth(tEnd), py + ph + fm.getAscent() + 3);

        g2.setColor(textColor);
        g2.setFont(getFont().deriveFont(Font.BOLD, 11.0f));
        g2.drawString("C/N0 Time Series (" + byPrn.size() + " PRNs)", px + 4, py - 6);
    }

    private static int yForCn0(double cn0, int py, int ph) {
        double norm = (Math.max(MIN_CN0, Math.min(MAX_CN0, cn0)) - MIN_CN0) / (MAX_CN0 - MIN_CN0);
        return (int) Math.round(py + ph - norm * ph);
    }

    private static Color colorForDelta(double delta) {
        if (!Double.isFinite(delta)) {
            return new Color(0x8C, 0x95, 0xA6);
        }
        if (delta <= -7.0) {
            return COLOR_ALERT;
        }
        if (delta <= -4.0) {
            return COLOR_WARN;
        }
        return COLOR_NOMINAL;
    }

    private static Color uiColor(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }
}

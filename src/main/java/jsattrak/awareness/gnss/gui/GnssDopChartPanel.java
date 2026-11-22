package jsattrak.awareness.gnss.gui;

import jsattrak.awareness.gnss.geometry.GnssDopSample;
import jsattrak.awareness.gui.LaunchDetailsInspectorPanel;

import javax.swing.JPanel;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.function.Consumer;

/**
 * 24-hour GNSS DOP ({@code PDOP, HDOP, VDOP, GDOP}) and visible satellite count chart with
 * No-Fix outage bands, simulation-clock cursor, and click-to-jump-epoch navigation.
 */
public class GnssDopChartPanel extends JPanel {

    private static final Color PDOP_COLOR = new Color(0x00, 0xD8, 0xF6);
    private static final Color HDOP_COLOR = new Color(0x52, 0xC4, 0x1A);
    private static final Color VDOP_COLOR = new Color(0xFA, 0x8C, 0x16);
    private static final Color GDOP_COLOR = new Color(0xC0, 0xC6, 0xD0);
    private static final Color SV_FILL = new Color(110, 155, 220, 48);
    private static final Color SV_LINE = new Color(120, 165, 230, 130);
    private static final Color NO_FIX_BAND = new Color(229, 72, 77, 55);
    private static final Color CURSOR_COLOR = new Color(255, 230, 110, 220);

    private static final double MAX_DOP_AXIS = 10.0;
    private static final int PAD_LEFT = 40;
    private static final int PAD_RIGHT = 38;
    private static final int PAD_TOP = 26;
    private static final int PAD_BOTTOM = 28;

    private final Consumer<Long> onJumpToEpoch;
    private List<GnssDopSample> series = List.of();
    private long simEpochMillis;
    private TimeZone displayTz = TimeZone.getTimeZone("UTC");

    public GnssDopChartPanel(Consumer<Long> onJumpToEpoch) {
        this.onJumpToEpoch = onJumpToEpoch;
        setPreferredSize(new Dimension(420, 260));
        setMinimumSize(new Dimension(260, 180));
        setOpaque(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        ToolTipManager.sharedInstance().registerComponent(this);

        MouseAdapter mouseAdapter = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1 && onJumpToEpoch != null && !series.isEmpty()) {
                    long targetEpoch = epochAtX(e.getX());
                    if (targetEpoch > 0L) {
                        onJumpToEpoch.accept(targetEpoch);
                    }
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                updateHoverTooltip(e.getX());
            }
        };
        addMouseListener(mouseAdapter);
        addMouseMotionListener(mouseAdapter);
    }

    public void setSeries(List<GnssDopSample> samples, long simEpochMillis, TimeZone displayTz) {
        this.series = samples != null ? List.copyOf(samples) : List.of();
        this.simEpochMillis = simEpochMillis;
        this.displayTz = displayTz != null ? displayTz : TimeZone.getTimeZone("UTC");
        repaint();
    }

    public void setSimEpochMillis(long simEpochMillis, TimeZone displayTz) {
        this.simEpochMillis = simEpochMillis;
        if (displayTz != null) {
            this.displayTz = displayTz;
        }
        repaint();
    }

    /**
     * Converts pixel {@code x} inside the plot bounds into an epoch timestamp in milliseconds.
     */
    public long epochAtX(int x) {
        if (series == null || series.isEmpty()) {
            return 0L;
        }
        long tStart = series.get(0).epochMillis();
        long tEnd = series.get(series.size() - 1).epochMillis();
        if (tEnd <= tStart) {
            return tStart;
        }
        int plotW = Math.max(1, getWidth() - PAD_LEFT - PAD_RIGHT);
        double frac = Math.max(0.0, Math.min(1.0, (x - PAD_LEFT) / (double) plotW));
        return tStart + Math.round(frac * (tEnd - tStart));
    }

    private void updateHoverTooltip(int x) {
        if (series == null || series.size() < 2) {
            setToolTipText(null);
            return;
        }
        long ep = epochAtX(x);
        GnssDopSample nearest = series.get(0);
        long bestDiff = Math.abs(nearest.epochMillis() - ep);
        for (GnssDopSample s : series) {
            long d = Math.abs(s.epochMillis() - ep);
            if (d < bestDiff) {
                bestDiff = d;
                nearest = s;
            }
        }
        String timeStr = LaunchDetailsInspectorPanel.formatDateTime(nearest.epochMillis(), displayTz);
        String pdopStr = nearest.fixAvailable() ? String.format(Locale.US, "%.2f", nearest.pdop()) : "No Fix";
        String hdopStr = nearest.fixAvailable() ? String.format(Locale.US, "%.2f", nearest.hdop()) : "--";
        String vdopStr = nearest.fixAvailable() ? String.format(Locale.US, "%.2f", nearest.vdop()) : "--";
        String gdopStr = nearest.fixAvailable() ? String.format(Locale.US, "%.2f", nearest.gdop()) : "--";
        setToolTipText(String.format(Locale.US,
                "<html><b>%s</b><br>PDOP: %s &nbsp; GDOP: %s<br>HDOP: %s &nbsp; VDOP: %s<br>Visible SVs: %d (click to jump Sim Clock)</html>",
                timeStr, pdopStr, gdopStr, hdopStr, vdopStr, nearest.visibleCount()));
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
            Color plotBg = darkerOrLight(panelBg);
            Color fg = uiColor("Label.foreground", new Color(220, 224, 232));
            Color muted = uiColor("Label.disabledForeground", new Color(145, 152, 165));

            g2.setColor(panelBg);
            g2.fillRect(0, 0, w, h);

            int plotW = Math.max(10, w - PAD_LEFT - PAD_RIGHT);
            int plotH = Math.max(10, h - PAD_TOP - PAD_BOTTOM);
            int plotX = PAD_LEFT;
            int plotY = PAD_TOP;

            g2.setColor(plotBg);
            g2.fillRect(plotX, plotY, plotW, plotH);

            // Grid lines & Y-axis ticks (0..10 DOP)
            Font labelFont = getFont().deriveFont(Font.PLAIN, 10f);
            g2.setFont(labelFont);
            FontMetrics fm = g2.getFontMetrics();
            Color gridLine = new Color(muted.getRed(), muted.getGreen(), muted.getBlue(), 65);
            for (int dopTick : new int[]{0, 2, 4, 6, 8, 10}) {
                int y = plotY + (int) Math.round((1.0 - dopTick / MAX_DOP_AXIS) * plotH);
                g2.setColor(gridLine);
                g2.drawLine(plotX, y, plotX + plotW, y);
                g2.setColor(muted);
                String txt = String.valueOf(dopTick);
                g2.drawString(txt, plotX - fm.stringWidth(txt) - 5, y + fm.getAscent() / 2 - 1);
            }

            // Legend top-left
            drawLegend(g2, plotX, 6, fg);

            if (series == null || series.size() < 2) {
                g2.setColor(muted);
                String msg = "Computing 24h DOP profile...";
                g2.drawString(msg, plotX + (plotW - fm.stringWidth(msg)) / 2, plotY + plotH / 2);
                return;
            }

            long tStart = series.get(0).epochMillis();
            long tEnd = series.get(series.size() - 1).epochMillis();
            long span = Math.max(1L, tEnd - tStart);

            int maxSv = 12;
            for (GnssDopSample s : series) {
                if (s.visibleCount() > maxSv) {
                    maxSv = s.visibleCount();
                }
            }
            maxSv = ((maxSv + 3) / 4) * 4;

            // Right Y-axis ticks (Visible SVs)
            g2.setColor(muted);
            for (int svTick : new int[]{0, maxSv / 2, maxSv}) {
                int y = plotY + (int) Math.round((1.0 - svTick / (double) maxSv) * plotH);
                g2.drawString(String.valueOf(svTick), plotX + plotW + 5, y + fm.getAscent() / 2 - 1);
            }

            // 1. Shaded Visible SVs step fill
            Path2D.Double svArea = new Path2D.Double();
            Path2D.Double svLine = new Path2D.Double();
            svArea.moveTo(plotX, plotY + plotH);
            for (int i = 0; i < series.size(); i++) {
                GnssDopSample s = series.get(i);
                double x = plotX + ((s.epochMillis() - tStart) / (double) span) * plotW;
                double y = plotY + (1.0 - s.visibleCount() / (double) maxSv) * plotH;
                svArea.lineTo(x, y);
                if (i == 0) {
                    svLine.moveTo(x, y);
                } else {
                    svLine.lineTo(x, y);
                }
            }
            svArea.lineTo(plotX + plotW, plotY + plotH);
            svArea.closePath();
            g2.setColor(SV_FILL);
            g2.fill(svArea);
            g2.setColor(SV_LINE);
            g2.setStroke(new BasicStroke(1.0f));
            g2.draw(svLine);

            // 2. Red shaded bands for No-Fix intervals
            double stepW = plotW / (double) Math.max(1, series.size() - 1);
            g2.setColor(NO_FIX_BAND);
            for (GnssDopSample s : series) {
                if (!s.fixAvailable()) {
                    int x = plotX + (int) Math.round(((s.epochMillis() - tStart) / (double) span) * plotW - stepW * 0.5);
                    g2.fillRect(Math.max(plotX, x), plotY, (int) Math.ceil(stepW) + 1, plotH);
                }
            }

            // 3. DOP line traces (clipped to plot rect)
            java.awt.Shape oldClip = g2.getClip();
            g2.clipRect(plotX, plotY, plotW, plotH);
            drawMetricTrace(g2, series, tStart, span, plotX, plotY, plotW, plotH, GDOP_COLOR, 1.3f, GnssDopSample::gdop);
            drawMetricTrace(g2, series, tStart, span, plotX, plotY, plotW, plotH, VDOP_COLOR, 1.4f, GnssDopSample::vdop);
            drawMetricTrace(g2, series, tStart, span, plotX, plotY, plotW, plotH, HDOP_COLOR, 1.4f, GnssDopSample::hdop);
            drawMetricTrace(g2, series, tStart, span, plotX, plotY, plotW, plotH, PDOP_COLOR, 2.1f, GnssDopSample::pdop);

            // 4. Vertical Sim Clock cursor
            if (simEpochMillis >= tStart && simEpochMillis <= tEnd) {
                int cursorX = plotX + (int) Math.round(((simEpochMillis - tStart) / (double) span) * plotW);
                g2.setColor(CURSOR_COLOR);
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawLine(cursorX, plotY, cursorX, plotY + plotH);
                g2.drawString("Sim", Math.min(plotX + plotW - 22, cursorX + 3), plotY + 11);
            }
            g2.setClip(oldClip);

            // Plot border & X-axis labels
            g2.setColor(new Color(muted.getRed(), muted.getGreen(), muted.getBlue(), 130));
            g2.setStroke(new BasicStroke(1.0f));
            g2.drawRect(plotX, plotY, plotW, plotH);

            g2.setColor(muted);
            String startLbl = formatShortTime(tStart);
            String midLbl = formatShortTime(tStart + span / 2);
            String endLbl = formatShortTime(tEnd);
            int yLbl = plotY + plotH + fm.getAscent() + 4;
            g2.drawString(startLbl, plotX, yLbl);
            g2.drawString(midLbl, plotX + (plotW - fm.stringWidth(midLbl)) / 2, yLbl);
            g2.drawString(endLbl, plotX + plotW - fm.stringWidth(endLbl), yLbl);
        } finally {
            g2.dispose();
        }
    }

    private void drawMetricTrace(
            Graphics2D g2,
            List<GnssDopSample> samples,
            long tStart,
            long span,
            int plotX,
            int plotY,
            int plotW,
            int plotH,
            Color color,
            float width,
            java.util.function.ToDoubleFunction<GnssDopSample> extractor) {
        g2.setColor(color);
        Stroke s = new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        g2.setStroke(s);
        Path2D.Double path = new Path2D.Double();
        boolean penDown = false;
        for (GnssDopSample sample : samples) {
            double val = extractor.applyAsDouble(sample);
            if (!sample.fixAvailable() || !Double.isFinite(val)) {
                penDown = false;
                continue;
            }
            double clamped = Math.max(0.0, Math.min(MAX_DOP_AXIS, val));
            double x = plotX + ((sample.epochMillis() - tStart) / (double) span) * plotW;
            double y = plotY + (1.0 - clamped / MAX_DOP_AXIS) * plotH;
            if (!penDown) {
                path.moveTo(x, y);
                penDown = true;
            } else {
                path.lineTo(x, y);
            }
        }
        g2.draw(path);
    }

    private void drawLegend(Graphics2D g2, int x, int y, Color fg) {
        g2.setFont(getFont().deriveFont(Font.BOLD, 10f));
        FontMetrics fm = g2.getFontMetrics();
        int curX = x;
        String[] names = {"PDOP", "HDOP", "VDOP", "GDOP", "Vis SVs"};
        Color[] cols = {PDOP_COLOR, HDOP_COLOR, VDOP_COLOR, GDOP_COLOR, SV_LINE};
        for (int i = 0; i < names.length; i++) {
            g2.setColor(cols[i]);
            g2.fillRect(curX, y + 3, 10, 8);
            curX += 13;
            g2.setColor(fg);
            g2.drawString(names[i], curX, y + fm.getAscent());
            curX += fm.stringWidth(names[i]) + 10;
        }
    }

    private String formatShortTime(long epochMillis) {
        String full = LaunchDetailsInspectorPanel.formatDateTime(epochMillis, displayTz);
        int sp = full.indexOf(' ');
        if (sp >= 0 && full.length() >= sp + 6) {
            return full.substring(sp + 1, sp + 6); // HH:mm
        }
        return full;
    }

    private static Color uiColor(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }

    private static Color darkerOrLight(Color bg) {
        int luma = (bg.getRed() * 299 + bg.getGreen() * 587 + bg.getBlue() * 114) / 1000;
        if (luma < 128) {
            return new Color(Math.max(0, bg.getRed() - 10), Math.max(0, bg.getGreen() - 10), Math.max(0, bg.getBlue() - 8));
        }
        return new Color(248, 250, 252);
    }
}

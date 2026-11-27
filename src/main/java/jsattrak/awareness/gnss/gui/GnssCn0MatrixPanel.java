package jsattrak.awareness.gnss.gui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.JPanel;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import jsattrak.awareness.gnss.stations.analysis.RegionalScanReport;
import jsattrak.awareness.gnss.stations.analysis.RegionalScanReport.StationAnomalySummary;

/**
 * Station x PRN {@code ΔC/N0} anomaly matrix heatmap clicking rows to select stations in the hub.
 */
public class GnssCn0MatrixPanel extends JPanel {

    private static final int ROW_LABEL_W = 86;
    private static final int HEADER_H = 22;
    private static final int PAD = 8;

    private record CellRect(Rectangle rect, String stationId, String prn, double delta) {
    }

    private final Consumer<String> onStationClick;
    private RegionalScanReport report;
    private String selectedStationId;
    private volatile List<CellRect> cellRects = List.of();
    private volatile List<Rectangle> rowRects = List.of();
    private volatile List<String> rowStationIds = List.of();

    public GnssCn0MatrixPanel(Consumer<String> onStationClick) {
        this.onStationClick = onStationClick;
        setPreferredSize(new Dimension(420, 200));
        setMinimumSize(new Dimension(240, 140));
        setOpaque(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        ToolTipManager.sharedInstance().registerComponent(this);
        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1 || onStationClick == null) {
                    return;
                }
                for (int i = 0; i < rowRects.size(); i++) {
                    if (rowRects.get(i).contains(e.getPoint())) {
                        onStationClick.accept(rowStationIds.get(i));
                        return;
                    }
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                for (CellRect c : cellRects) {
                    if (c.rect().contains(e.getPoint())) {
                        String val = Double.isFinite(c.delta())
                                ? String.format(Locale.US, "%+.1f dB-Hz", c.delta())
                                : "Not tracked";
                        setToolTipText("<html><b>" + c.stationId() + "</b> &times; <b>" + c.prn()
                                + "</b><br/>&Delta;C/N0: " + val + "</html>");
                        return;
                    }
                }
                setToolTipText(null);
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
    }

    public void setReport(RegionalScanReport report) {
        this.report = report;
        repaint();
    }

    public void setSelectedStationId(String stationId) {
        this.selectedStationId = stationId;
        repaint();
    }

    public static Color colorForDeltaCell(double delta) {
        if (!Double.isFinite(delta)) {
            return new Color(75, 82, 92, 90);
        }
        if (delta <= -7.0) {
            return new Color(0xE5, 0x48, 0x4D, 225);
        }
        if (delta <= -4.0) {
            return new Color(0xFA, 0x8C, 0x16, 210);
        }
        if (delta < -1.5) {
            return new Color(0xF0, 0xC4, 0x40, 150);
        }
        return new Color(0x3F, 0xBF, 0x6A, 155);
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
            Color borderColor = uiColor("Component.borderColor", new Color(95, 105, 120, 90));
            Color textColor = uiColor("Label.foreground", new Color(225, 230, 238));
            Color mutedColor = uiColor("Label.disabledForeground", new Color(145, 152, 165));

            g2.setColor(panelBg);
            g2.fillRect(0, 0, w, h);

            if (report == null || report.stations().isEmpty() || report.prnColumns().isEmpty()) {
                g2.setColor(mutedColor);
                g2.setFont(getFont().deriveFont(Font.PLAIN, 11.5f));
                String empty = "Click \"Scan Region\" to compare C/N0 across nearby stations";
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(empty, Math.max(8, (w - fm.stringWidth(empty)) / 2), h / 2);
                cellRects = List.of();
                rowRects = List.of();
                rowStationIds = List.of();
                return;
            }

            List<StationAnomalySummary> rows = report.stations();
            List<String> cols = report.prnColumns().subList(0, Math.min(24, report.prnColumns().size()));
            int gridX = PAD + ROW_LABEL_W;
            int gridY = PAD + HEADER_H;
            int gridW = Math.max(40, w - gridX - PAD);
            int gridH = Math.max(40, h - gridY - PAD);
            double cellW = (double) gridW / cols.size();
            double cellH = Math.max(14.0, (double) gridH / rows.size());

            Font headerFont = getFont().deriveFont(Font.BOLD, 9.5f);
            Font rowFont = getFont().deriveFont(Font.PLAIN, 10.5f);
            FontMetrics hfm = g2.getFontMetrics(headerFont);
            g2.setFont(headerFont);
            g2.setColor(textColor);
            for (int c = 0; c < cols.size(); c++) {
                int cx = (int) Math.round(gridX + c * cellW);
                if (cellW >= 18 || c % 2 == 0) {
                    String lbl = cols.get(c);
                    g2.drawString(lbl, cx + Math.max(1, ((int) cellW - hfm.stringWidth(lbl)) / 2), PAD + HEADER_H - 6);
                }
            }

            List<CellRect> nextCells = new ArrayList<>();
            List<Rectangle> nextRows = new ArrayList<>();
            List<String> nextIds = new ArrayList<>();

            for (int r = 0; r < rows.size(); r++) {
                StationAnomalySummary stSum = rows.get(r);
                String stationId = stSum.station().stationId();
                int ry = (int) Math.round(gridY + r * cellH);
                int rh = Math.max(12, (int) Math.round((r + 1) * cellH) - (int) Math.round(r * cellH));
                boolean isSelected = selectedStationId != null && selectedStationId.equalsIgnoreCase(stationId);

                Rectangle rowBox = new Rectangle(PAD, ry, w - PAD * 2, rh);
                nextRows.add(rowBox);
                nextIds.add(stationId);

                if (isSelected) {
                    g2.setColor(new Color(0x00, 0xE5, 0xFF, 45));
                    g2.fillRect(PAD, ry, ROW_LABEL_W, rh);
                }
                g2.setFont(rowFont);
                g2.setColor(isSelected ? new Color(0x00, 0xE5, 0xFF) : textColor);
                String shortLbl = stSum.station().shortCode();
                g2.drawString(shortLbl + String.format(Locale.US, " (%.0fkm)", stSum.distanceKm()),
                        PAD + 4, ry + Math.min(rh - 3, 13));

                Map<String, Double> rowMap = report.deltaCn0Matrix().getOrDefault(stationId, Map.of());
                for (int c = 0; c < cols.size(); c++) {
                    String prn = cols.get(c);
                    int cx = (int) Math.round(gridX + c * cellW);
                    int cw = Math.max(4, (int) Math.round((c + 1) * cellW) - (int) Math.round(c * cellW));
                    Double dVal = rowMap.get(prn);
                    double delta = dVal != null ? dVal : Double.NaN;
                    g2.setColor(colorForDeltaCell(delta));
                    g2.fillRect(cx + 1, ry + 1, Math.max(2, cw - 2), Math.max(2, rh - 2));
                    g2.setColor(borderColor);
                    g2.drawRect(cx, ry, cw, rh);
                    nextCells.add(new CellRect(new Rectangle(cx, ry, cw, rh), stationId, prn, delta));
                }
            }
            this.cellRects = List.copyOf(nextCells);
            this.rowRects = List.copyOf(nextRows);
            this.rowStationIds = List.copyOf(nextIds);
        } finally {
            g2.dispose();
        }
    }

    private static Color uiColor(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return Objects.requireNonNullElse(c, fallback);
    }
}

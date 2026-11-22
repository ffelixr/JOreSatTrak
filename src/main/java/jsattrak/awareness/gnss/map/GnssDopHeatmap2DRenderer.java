package jsattrak.awareness.gnss.map;

import jsattrak.awareness.gnss.geometry.GnssDopGrid;
import jsattrak.awareness.gnss.geometry.GnssDopMetric;
import jsattrak.awareness.gnss.geometry.GnssGeometryListener;
import jsattrak.awareness.gnss.geometry.GnssGeometryService;
import jsattrak.awareness.gnss.geometry.GnssObserverPosition;
import jsattrak.gui.J2dPolarEarthLabel;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.function.BiFunction;

/**
 * Shared Java2D renderer for 2D Equirectangular and 2D Polar GNSS DOP spatial heatmaps,
 * color-scale legend, and observer crosshair pin.
 */
public class GnssDopHeatmap2DRenderer {

    private static final Color LEGEND_BG = new Color(18, 22, 28, 205);
    private static final Color LEGEND_BORDER = new Color(255, 255, 255, 75);
    private static final Color PIN_COLOR = new Color(0x00, 0xE5, 0xFF);
    private static final Color PIN_OUTLINE = new Color(10, 14, 20, 235);

    private record PolarDiscCache(
            GnssDopGrid grid,
            int alpha,
            int poleSign,
            int radius,
            int offsetX,
            int offsetY,
            double maxAng,
            BufferedImage image,
            int paintedPixels) {
        boolean matches(GnssDopGrid g, int a, int p, int r, int ox, int oy, double ma) {
            return grid == g && alpha == a && poleSign == p && radius == r
                    && offsetX == ox && offsetY == oy && Double.compare(maxAng, ma) == 0;
        }
    }

    private volatile GnssDopGrid cachedGrid;
    private volatile double cachedOpacity = -1.0;
    private volatile BufferedImage cachedRgbaImage;
    private volatile PolarDiscCache northPolarCache;
    private volatile PolarDiscCache southPolarCache;
    private volatile int polarDiscCacheHits;

    public static GnssGeometryListener createRepaintListener(JComponent target) {
        return new GnssGeometryListener() {
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
            public void onGeometryDataChanged() {
                trigger();
            }

            @Override
            public void onHeatmapUpdated(GnssDopGrid grid) {
                trigger();
            }

            @Override
            public void onObserverChanged(GnssObserverPosition observer) {
                trigger();
            }

            @Override
            public void onPickOnMapModeChanged(boolean pickMode) {
                trigger();
            }
        };
    }

    /**
     * Paints the equirectangular global or zoomed DOP heatmap underneath satellite markers.
     */
    public int paintEquirectangularHeatmap(
            Graphics2D g2,
            GnssGeometryService service,
            boolean perWindowVisible,
            long simEpochMillis,
            Rectangle mapRect,
            double minLon,
            double maxLon,
            double minLat,
            double maxLat) {
        if (g2 == null || service == null || !perWindowVisible || !service.isMapOverlayEnabled()
                || mapRect == null || mapRect.width <= 0 || mapRect.height <= 0) {
            return 0;
        }
        service.requestHeatmapAsync(simEpochMillis);
        GnssDopGrid grid = service.getLatestHeatmapGrid();
        if (grid == null) {
            return 0;
        }

        BufferedImage rgba = getOrRenderRgba(grid, service.getHeatmapOpacity());
        if (rgba == null) {
            return 0;
        }

        Shape oldClip = g2.getClip();
        Object oldInterp = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g2.clip(mapRect);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        double lonSpan = Math.max(1e-6, maxLon - minLon);
        double latSpan = Math.max(1e-6, maxLat - minLat);

        // World rectangle in pixel coords for shifts (-360, 0, +360) so zoomed antimeridian views paint seamlessly
        int dy1 = (int) Math.floor(mapRect.y + (maxLat - 90.0) / latSpan * mapRect.height);
        int dy2 = (int) Math.ceil(mapRect.y + (maxLat - (-90.0)) / latSpan * mapRect.height);

        for (int shift = -360; shift <= 360; shift += 360) {
            double worldMinLon = -180.0 + shift;
            double worldMaxLon = 180.0 + shift;
            if (worldMaxLon < minLon || worldMinLon > maxLon) {
                continue;
            }
            int dx1 = (int) Math.floor(mapRect.x + (worldMinLon - minLon) / lonSpan * mapRect.width);
            int dx2 = (int) Math.ceil(mapRect.x + (worldMaxLon - minLon) / lonSpan * mapRect.width);
            g2.drawImage(rgba, dx1, dy1, dx2, dy2, 0, 0, rgba.getWidth(), rgba.getHeight(), null);
        }

        if (oldInterp != null) {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterp);
        }
        g2.setClip(oldClip);
        return 1;
    }

    /**
     * Paints the DOP heatmap inside a single polar disc (must be called while the disc's circular clip is active).
     */
    public int paintPolarDiscHeatmap(
            Graphics2D g2,
            GnssGeometryService service,
            boolean perWindowVisible,
            long simEpochMillis,
            int poleSign,
            int discCx,
            int discCy,
            int radius,
            int projCx,
            int projCy,
            double maxAng) {
        if (g2 == null || service == null || !perWindowVisible || !service.isMapOverlayEnabled() || radius <= 2) {
            return 0;
        }
        service.requestHeatmapAsync(simEpochMillis);
        GnssDopGrid grid = service.getLatestHeatmapGrid();
        if (grid == null) {
            return 0;
        }

        int alpha = Math.max(0, Math.min(255, (int) Math.round(service.getHeatmapOpacity() * 255.0)));
        int originX = discCx - radius;
        int originY = discCy - radius;
        int relProjX = projCx - discCx;
        int relProjY = projCy - discCy;

        PolarDiscCache existing = poleSign >= 0 ? northPolarCache : southPolarCache;
        if (existing != null && existing.matches(grid, alpha, poleSign, radius, relProjX, relProjY, maxAng)) {
            polarDiscCacheHits++;
            g2.drawImage(existing.image(), originX, originY, null);
            return existing.paintedPixels();
        }

        int size = radius * 2 + 1;
        BufferedImage discImg = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        boolean isCount = grid.getMetric() == GnssDopMetric.VISIBLE_SVS;
        int rSq = radius * radius;
        int paintedPixels = 0;

        for (int py = 0; py < size; py++) {
            int dyDisc = py - radius;
            int dySq = dyDisc * dyDisc;
            int screenY = originY + py;
            for (int px = 0; px < size; px++) {
                int dxDisc = px - radius;
                if (dxDisc * dxDisc + dySq > rSq) {
                    continue;
                }
                int screenX = originX + px;
                double[] ll = J2dPolarEarthLabel.polarXYToLatLon(
                        screenX, screenY, poleSign, projCx, projCy, radius, maxAng);
                if (ll == null) {
                    continue;
                }
                double val = grid.getValueAt(ll[0], ll[1]);
                int argb = isCount
                        ? GnssDopGrid.argbForVisibleCount((int) Math.round(val), alpha)
                        : GnssDopGrid.argbForDop(val, alpha);
                discImg.setRGB(px, py, argb);
                paintedPixels++;
            }
        }
        PolarDiscCache updated = new PolarDiscCache(
                grid, alpha, poleSign, radius, relProjX, relProjY, maxAng, discImg, paintedPixels);
        if (poleSign >= 0) {
            northPolarCache = updated;
        } else {
            southPolarCache = updated;
        }
        g2.drawImage(discImg, originX, originY, null);
        return paintedPixels;
    }

    public int getPolarDiscCacheHitCountForTest() {
        return polarDiscCacheHits;
    }

    /**
     * Paints the observer crosshair pin when {@link GnssGeometryService#isObserverPinVisible()} is true.
     */
    public void paintObserverPin(
            Graphics2D g2,
            GnssGeometryService service,
            BiFunction<Double, Double, int[]> projector) {
        if (g2 == null || service == null || projector == null || !service.isObserverPinVisible()) {
            return;
        }
        GnssObserverPosition obs = service.getObserver();
        if (obs == null) {
            return;
        }
        int[] pt = projector.apply(obs.latDeg(), obs.lonDeg());
        if (pt == null || pt.length < 2) {
            return;
        }
        int cx = pt[0];
        int cy = pt[1];

        Object oldAA = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        Stroke oldStroke = g2.getStroke();
        Font oldFont = g2.getFont();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g2.setStroke(new BasicStroke(3.0f));
        g2.setColor(PIN_OUTLINE);
        g2.drawOval(cx - 7, cy - 7, 14, 14);
        g2.drawLine(cx - 11, cy, cx + 11, cy);
        g2.drawLine(cx, cy - 11, cx, cy + 11);

        g2.setStroke(new BasicStroke(1.6f));
        g2.setColor(PIN_COLOR);
        g2.drawOval(cx - 7, cy - 7, 14, 14);
        g2.drawLine(cx - 11, cy, cx + 11, cy);
        g2.drawLine(cx, cy - 11, cx, cy + 11);

        Font labelFont = oldFont.deriveFont(Font.BOLD, 10.5f);
        g2.setFont(labelFont);
        FontMetrics fm = g2.getFontMetrics();
        String label = "GNSS OBS (" + String.format(Locale.US, "%.1f, %.1f", obs.latDeg(), obs.lonDeg()) + ")";
        int lw = fm.stringWidth(label) + 8;
        int lh = fm.getHeight() + 2;
        int lx = cx + 12;
        int ly = cy - lh / 2;
        g2.setColor(LEGEND_BG);
        g2.fill(new RoundRectangle2D.Double(lx, ly, lw, lh, 6, 6));
        g2.setColor(PIN_COLOR);
        g2.drawString(label, lx + 4, ly + fm.getAscent() + 1);

        g2.setFont(oldFont);
        g2.setStroke(oldStroke);
        if (oldAA != null) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
        }
    }

    /**
     * Paints a compact bottom-right color-scale legend when both master gate and per-window toggle are active.
     */
    public void paintColorScaleLegend(
            Graphics2D g2,
            GnssGeometryService service,
            boolean perWindowVisible,
            Rectangle bounds) {
        if (g2 == null || service == null || !perWindowVisible || !service.isMapOverlayEnabled()
                || bounds == null || bounds.width < 180 || bounds.height < 80) {
            return;
        }
        GnssDopMetric metric = service.getHeatmapMetric();
        String title = metric != null ? metric.getDisplayName() : "PDOP";
        String[] labels = (metric == GnssDopMetric.VISIBLE_SVS)
                ? new String[]{"<4", "4-7", "8-15", ">=16"}
                : new String[]{"<=1.5", "<=2.5", "<=4", "<=6", ">6", "NoFix"};
        Color[] colors = (metric == GnssDopMetric.VISIBLE_SVS)
                ? new Color[]{
                        GnssDopGrid.colorForVisibleCount(2, 230),
                        GnssDopGrid.colorForVisibleCount(5, 230),
                        GnssDopGrid.colorForVisibleCount(10, 230),
                        GnssDopGrid.colorForVisibleCount(18, 230)}
                : new Color[]{
                        GnssDopGrid.colorForDop(1.2, 230),
                        GnssDopGrid.colorForDop(2.0, 230),
                        GnssDopGrid.colorForDop(3.2, 230),
                        GnssDopGrid.colorForDop(5.0, 230),
                        GnssDopGrid.colorForDop(8.0, 230),
                        GnssDopGrid.colorForDop(Double.NaN, 230)};

        Font oldFont = g2.getFont();
        g2.setFont(oldFont.deriveFont(Font.PLAIN, 10f));
        FontMetrics fm = g2.getFontMetrics();
        int sw = 10;
        int gap = 5;
        int contentW = fm.stringWidth(title + ": ");
        for (String lbl : labels) {
            contentW += sw + 3 + fm.stringWidth(lbl) + gap;
        }
        int boxW = contentW + 10;
        int boxH = fm.getHeight() + 8;
        int x = bounds.x + bounds.width - boxW - 10;
        int y = bounds.y + bounds.height - boxH - 22;

        g2.setColor(LEGEND_BG);
        g2.fill(new RoundRectangle2D.Double(x, y, boxW, boxH, 8, 8));
        g2.setColor(LEGEND_BORDER);
        g2.draw(new RoundRectangle2D.Double(x, y, boxW, boxH, 8, 8));

        int curX = x + 6;
        int textY = y + fm.getAscent() + 4;
        g2.setColor(Color.WHITE);
        g2.drawString(title + ":", curX, textY);
        curX += fm.stringWidth(title + ": ");
        for (int i = 0; i < labels.length; i++) {
            g2.setColor(colors[i]);
            g2.fillRect(curX, y + (boxH - sw) / 2, sw, sw);
            curX += sw + 3;
            g2.setColor(Color.WHITE);
            g2.drawString(labels[i], curX, textY);
            curX += fm.stringWidth(labels[i]) + gap;
        }
        g2.setFont(oldFont);
    }

    private BufferedImage getOrRenderRgba(GnssDopGrid grid, double opacity) {
        if (this.cachedGrid == grid
                && Math.abs(this.cachedOpacity - opacity) < 1e-4
                && this.cachedRgbaImage != null) {
            return this.cachedRgbaImage;
        }
        BufferedImage rendered = grid.renderRgbaImage(opacity);
        this.cachedGrid = grid;
        this.cachedOpacity = opacity;
        this.cachedRgbaImage = rendered;
        return rendered;
    }
}

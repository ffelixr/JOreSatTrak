package jsattrak.awareness.map;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import javax.swing.JComponent;
import jsattrak.awareness.AwarenessStateListener;
import jsattrak.awareness.LaunchRecord;
import jsattrak.awareness.LaunchTimeEvaluator;
import jsattrak.awareness.LaunchUrgency;
import jsattrak.awareness.LiveAwarenessService;

/**
 * Shared Java2D renderer, hit-tester, and tooltip formatter for 2D and 2D Polar launch overlays.
 */
public class LaunchOverlay2DRenderer {

    private static final Color COLOR_IMMINENT = new Color(0xE5, 0x39, 0x35);
    private static final Color COLOR_SOON = new Color(0xFB, 0x8C, 0x00);
    private static final Color COLOR_UPCOMING = new Color(0x1E, 0x88, 0xE5);
    private static final Color COLOR_EXPIRED = new Color(0x78, 0x90, 0x9C);

    private static final Color BORDER_DARK = new Color(18, 22, 28, 220);
    private static final Color LABEL_BG = new Color(18, 22, 28, 185);
    private static final Color BADGE_BG = new Color(20, 26, 34, 235);
    private static final Color SELECTED_HALO = new Color(255, 255, 255, 85);

    private final double clusterRadiusPx;
    private volatile List<LaunchMapCluster> lastPaintedClusters = List.of();

    public LaunchOverlay2DRenderer() {
        this(LaunchClusterer.DEFAULT_CLUSTER_RADIUS_PX);
    }

    public LaunchOverlay2DRenderer(double clusterRadiusPx) {
        this.clusterRadiusPx = (Double.isFinite(clusterRadiusPx) && clusterRadiusPx >= 0.0)
                ? clusterRadiusPx
                : LaunchClusterer.DEFAULT_CLUSTER_RADIUS_PX;
    }

    public static Color colorForUrgency(LaunchUrgency urgency) {
        if (urgency == null) {
            return COLOR_EXPIRED;
        }
        return switch (urgency) {
            case IMMINENT_OR_IN_WINDOW -> COLOR_IMMINENT;
            case SOON_1_TO_3D -> COLOR_SOON;
            case UPCOMING_3_TO_7D -> COLOR_UPCOMING;
            case EXPIRED -> COLOR_EXPIRED;
        };
    }

    public void clear() {
        this.lastPaintedClusters = List.of();
    }

    public List<LaunchMapCluster> getLastPaintedClusters() {
        return lastPaintedClusters;
    }

    public List<LaunchMapCluster> paintServiceOverlay(
            Graphics2D g2,
            LiveAwarenessService service,
            boolean perWindowVisible,
            long simEpochMillis,
            Function<LaunchRecord, int[]> projector) {
        if (service == null || !perWindowVisible || !service.isGlobalMapOverlayEnabled()) {
            clear();
            return List.of();
        }
        List<LaunchRecord> upcoming = service.getVisibleUpcomingLaunches(simEpochMillis);
        LaunchRecord selected = service.getSelectedLaunch();
        LaunchRecord extraSelectedRecent = (selected != null
                && !containsLaunch(upcoming, selected)
                && selected.getNetEpochMillis() <= simEpochMillis)
                ? selected
                : null;
        return paint(g2, upcoming, extraSelectedRecent, simEpochMillis, service.getSelectedLaunchId(), projector);
    }

    public boolean handleLeftClick(
            int x,
            int y,
            double hitRadiusPx,
            LiveAwarenessService service,
            boolean perWindowVisible) {
        if (service == null || !perWindowVisible || !service.isGlobalMapOverlayEnabled()) {
            return false;
        }
        LaunchMapCluster hit = findClusterAt(x, y, hitRadiusPx);
        if (hit == null || hit.getSoonest() == null) {
            return false;
        }
        service.selectLaunchAndRequestOpenUi(hit.getSoonest().getId());
        return true;
    }

    public void updateHoverTooltip(
            JComponent target,
            int x,
            int y,
            double hitRadiusPx,
            LiveAwarenessService service,
            boolean perWindowVisible,
            long simEpochMillis) {
        if (target == null) {
            return;
        }
        if (service == null || !perWindowVisible || !service.isGlobalMapOverlayEnabled()) {
            target.setToolTipText(null);
            return;
        }
        LaunchMapCluster hit = findClusterAt(x, y, hitRadiusPx);
        target.setToolTipText(hit != null ? buildTooltipHtml(hit, simEpochMillis) : null);
    }

    public static AwarenessStateListener createRepaintListener(Component repaintTarget) {
        return new AwarenessStateListener() {
            @Override
            public void onAwarenessDataChanged() {
                if (repaintTarget != null) {
                    repaintTarget.repaint();
                }
            }

            @Override
            public void onSelectionChanged(String selectedLaunchId) {
                if (repaintTarget != null) {
                    repaintTarget.repaint();
                }
            }

            @Override
            public void onStatusMessage(String message, boolean isWarning) {
            }
        };
    }

    public List<LaunchMapCluster> paint(
            Graphics2D g2,
            List<LaunchRecord> launches,
            LaunchRecord extraSelectedRecent,
            long simEpochMillis,
            String selectedLaunchId,
            Function<LaunchRecord, int[]> projector) {
        if (g2 == null || projector == null) {
            this.lastPaintedClusters = List.of();
            return List.of();
        }

        List<LaunchMapCluster> baseClusters = LaunchClusterer.clusterByScreenDistance(
                launches, simEpochMillis, selectedLaunchId, projector, clusterRadiusPx);
        List<LaunchMapCluster> clusters = mergeExtraSelectedRecent(
                baseClusters, launches, extraSelectedRecent, simEpochMillis, selectedLaunchId, projector);

        this.lastPaintedClusters = List.copyOf(clusters);
        if (clusters.isEmpty()) {
            return this.lastPaintedClusters;
        }

        Graphics2D g = (Graphics2D) g2.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            List<Rectangle> drawnLabelBounds = new ArrayList<>();

            // Paint unselected first, then selected on top so selection ring & label remain unobscured
            for (LaunchMapCluster cluster : clusters) {
                if (!cluster.isSelected()) {
                    drawCluster(g, cluster, simEpochMillis, drawnLabelBounds);
                }
            }
            for (LaunchMapCluster cluster : clusters) {
                if (cluster.isSelected()) {
                    drawCluster(g, cluster, simEpochMillis, drawnLabelBounds);
                }
            }
        } finally {
            g.dispose();
        }

        return this.lastPaintedClusters;
    }

    public LaunchMapCluster findClusterAt(int mouseX, int mouseY, double hitRadiusPx) {
        if (!Double.isFinite(hitRadiusPx) || hitRadiusPx < 0.0) {
            return null;
        }
        List<LaunchMapCluster> snapshot = this.lastPaintedClusters;
        if (snapshot.isEmpty()) {
            return null;
        }
        LaunchMapCluster best = null;
        double bestDist = Double.MAX_VALUE;
        for (LaunchMapCluster cluster : snapshot) {
            double dist = Math.hypot(mouseX - cluster.getScreenX(), mouseY - cluster.getScreenY());
            if (dist <= hitRadiusPx) {
                boolean closer = dist < bestDist - 1e-6;
                boolean tiePreferSelected = Math.abs(dist - bestDist) <= 1e-6
                        && cluster.isSelected()
                        && (best == null || !best.isSelected());
                if (closer || tiePreferSelected) {
                    bestDist = dist;
                    best = cluster;
                }
            }
        }
        return best;
    }

    public static String buildTooltipHtml(LaunchMapCluster cluster, long simEpochMillis) {
        if (cluster == null || cluster.getLaunches().isEmpty()) {
            return null;
        }
        List<LaunchRecord> items = cluster.getLaunches();
        LaunchRecord soonest = items.get(0);

        StringBuilder sb = new StringBuilder(256);
        sb.append("<html><body style='margin:2px 4px;'>");
        String siteHeader = formatSiteLabel(soonest, 36);
        if (items.size() > 1) {
            siteHeader = nonBlankOr(soonest.getSpaceportName(), siteHeader);
        }
        if (!siteHeader.isEmpty()) {
            sb.append("<b>").append(escapeHtml(siteHeader)).append("</b>");
            if (items.size() > 1) {
                sb.append(" <span style='color:#90CAF9;'>(\u00D7").append(items.size()).append(" launches)</span>");
            }
            sb.append("<br/>");
        }

        int maxShown = Math.min(items.size(), 4);
        for (int i = 0; i < maxShown; i++) {
            LaunchRecord r = items.get(i);
            String countdown = LaunchTimeEvaluator.formatCountdown(r, simEpochMillis);
            String mission = nonBlankOr(r.getMissionName(), "Scheduled Launch");
            String vehicle = nonBlankOr(r.getVehicle(), "");
            String status = nonBlankOr(r.getStatus(), "");

            if (i > 0) {
                sb.append("<br/>");
            }
            sb.append(i == 0 ? "<b>" : "<span style='color:#CFD8DC;'>");
            sb.append(escapeHtml(countdown)).append(" &mdash; ").append(escapeHtml(mission));
            if (!vehicle.isEmpty()) {
                sb.append(" (").append(escapeHtml(vehicle)).append(")");
            }
            if (!status.isEmpty()) {
                sb.append(" [").append(escapeHtml(status)).append("]");
            }
            sb.append(i == 0 ? "</b>" : "</span>");
        }
        if (items.size() > maxShown) {
            sb.append("<br/><i>+").append(items.size() - maxShown).append(" more</i>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    private List<LaunchMapCluster> mergeExtraSelectedRecent(
            List<LaunchMapCluster> baseClusters,
            List<LaunchRecord> launches,
            LaunchRecord extraSelectedRecent,
            long simEpochMillis,
            String selectedLaunchId,
            Function<LaunchRecord, int[]> projector) {
        if (extraSelectedRecent == null
                || !Double.isFinite(extraSelectedRecent.getLatDeg())
                || !Double.isFinite(extraSelectedRecent.getLonDeg())
                || containsLaunch(launches, extraSelectedRecent)) {
            return baseClusters;
        }
        int[] xy = projector.apply(extraSelectedRecent);
        if (xy == null || xy.length < 2) {
            return baseClusters;
        }

        boolean extraIsSelected = selectedLaunchId != null
                && !selectedLaunchId.isBlank()
                && Objects.equals(selectedLaunchId, extraSelectedRecent.getId());

        List<LaunchMapCluster> merged = new ArrayList<>(baseClusters.size() + 1);
        merged.addAll(baseClusters);

        int nearestIdx = -1;
        double nearestDist = Double.MAX_VALUE;
        for (int i = 0; i < merged.size(); i++) {
            LaunchMapCluster existing = merged.get(i);
            double dist = Math.hypot(xy[0] - existing.getScreenX(), xy[1] - existing.getScreenY());
            if (dist <= clusterRadiusPx && dist < nearestDist) {
                nearestDist = dist;
                nearestIdx = i;
            }
        }

        if (nearestIdx >= 0) {
            if (extraIsSelected) {
                merged.set(nearestIdx, merged.get(nearestIdx).withSelected(true));
            }
        } else {
            LaunchUrgency urgency = LaunchTimeEvaluator.evaluateUrgency(extraSelectedRecent, simEpochMillis);
            merged.add(new LaunchMapCluster(
                    extraSelectedRecent.getLatDeg(),
                    extraSelectedRecent.getLonDeg(),
                    xy[0],
                    xy[1],
                    urgency,
                    List.of(extraSelectedRecent),
                    extraIsSelected));
        }
        return merged;
    }

    private static void drawCluster(
            Graphics2D g,
            LaunchMapCluster cluster,
            long simEpochMillis,
            List<Rectangle> drawnLabelBounds) {
        int x = cluster.getScreenX();
        int y = cluster.getScreenY();
        boolean selected = cluster.isSelected();
        int r = selected ? 7 : 6;
        Color fill = colorForUrgency(cluster.getSoonestUrgency());

        if (selected) {
            g.setColor(SELECTED_HALO);
            g.fill(new Ellipse2D.Double(x - (r + 5.5), y - (r + 5.5), 2 * (r + 5.5), 2 * (r + 5.5)));

            Stroke prev = g.getStroke();
            g.setStroke(new BasicStroke(2.2f));
            g.setColor(Color.WHITE);
            g.draw(new Ellipse2D.Double(x - (r + 3.5), y - (r + 3.5), 2 * (r + 3.5), 2 * (r + 3.5)));
            g.setStroke(prev);
        }

        // Subtle dark outer rim for contrast over bright maps
        g.setColor(BORDER_DARK);
        g.fill(new Ellipse2D.Double(x - (r + 1.2), y - (r + 1.2), 2 * (r + 1.2), 2 * (r + 1.2)));

        // Urgency fill
        g.setColor(fill);
        g.fill(new Ellipse2D.Double(x - r, y - r, 2.0 * r, 2.0 * r));

        // Crisp white inner ring
        Stroke prevStroke = g.getStroke();
        g.setStroke(new BasicStroke(selected ? 1.8f : 1.4f));
        g.setColor(Color.WHITE);
        g.draw(new Ellipse2D.Double(x - r, y - r, 2.0 * r, 2.0 * r));
        g.setStroke(prevStroke);

        int count = cluster.getLaunches().size();
        int badgeEndX = x + r;
        if (count > 1) {
            badgeEndX = drawCountBadge(g, x + r - 1, y - r + 2, count);
        }

        drawCompactLabel(g, cluster, simEpochMillis, Math.max(x + r + 5, badgeEndX + 4), y, drawnLabelBounds);
    }

    private static int drawCountBadge(Graphics2D g, int anchorX, int anchorY, int count) {
        Font origFont = g.getFont();
        Font badgeFont = origFont.deriveFont(Font.BOLD, 10.0f);
        g.setFont(badgeFont);
        FontMetrics fm = g.getFontMetrics();

        String badgeText = "\u00D7" + count;
        int tw = fm.stringWidth(badgeText);
        int th = fm.getAscent();
        int padX = 4;
        int boxW = tw + padX * 2;
        int boxH = th + 4;
        int bx = anchorX;
        int by = anchorY - boxH + 2;

        RoundRectangle2D pill = new RoundRectangle2D.Double(bx, by, boxW, boxH, 8, 8);
        g.setColor(BADGE_BG);
        g.fill(pill);
        g.setColor(Color.WHITE);
        Stroke prev = g.getStroke();
        g.setStroke(new BasicStroke(1.0f));
        g.draw(pill);
        g.setStroke(prev);
        g.drawString(badgeText, bx + padX, by + th + 1);

        g.setFont(origFont);
        return bx + boxW;
    }

    private static void drawCompactLabel(
            Graphics2D g,
            LaunchMapCluster cluster,
            long simEpochMillis,
            int labelX,
            int centerY,
            List<Rectangle> drawnLabelBounds) {
        LaunchRecord soonest = cluster.getSoonest();
        String site = formatSiteLabel(soonest, 16);
        String countdown = LaunchTimeEvaluator.formatCountdown(soonest, simEpochMillis);
        String label = site.isEmpty() ? countdown : (site + " \u00B7 " + countdown);

        Font origFont = g.getFont();
        Font labelFont = origFont.deriveFont(cluster.isSelected() ? Font.BOLD : Font.PLAIN, 10.5f);
        g.setFont(labelFont);
        FontMetrics fm = g.getFontMetrics();

        int tw = fm.stringWidth(label);
        int th = fm.getAscent();
        int padX = 4;
        int padY = 2;
        int boxW = tw + padX * 2;
        int boxH = th + padY * 2;
        int boxY = centerY - boxH / 2;

        Rectangle bounds = new Rectangle(labelX, boxY, boxW, boxH);
        if (!cluster.isSelected() && overlapsAny(bounds, drawnLabelBounds)) {
            g.setFont(origFont);
            return;
        }
        drawnLabelBounds.add(bounds);

        RoundRectangle2D bg = new RoundRectangle2D.Double(bounds.x, bounds.y, bounds.width, bounds.height, 6, 6);
        g.setColor(LABEL_BG);
        g.fill(bg);
        if (cluster.isSelected()) {
            Stroke prev = g.getStroke();
            g.setStroke(new BasicStroke(1.0f));
            g.setColor(new Color(255, 255, 255, 190));
            g.draw(bg);
            g.setStroke(prev);
        }
        g.setColor(Color.WHITE);
        g.drawString(label, bounds.x + padX, bounds.y + padY + th - 1);

        g.setFont(origFont);
    }

    private static boolean overlapsAny(Rectangle candidate, List<Rectangle> existing) {
        for (Rectangle rect : existing) {
            if (rect.intersects(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsLaunch(List<LaunchRecord> launches, LaunchRecord target) {
        if (launches == null || launches.isEmpty() || target == null) {
            return false;
        }
        String targetId = target.getId();
        for (LaunchRecord item : launches) {
            if (item == target) {
                return true;
            }
            if (targetId != null && !targetId.isBlank() && Objects.equals(targetId, item.getId())) {
                return true;
            }
        }
        return false;
    }

    private static String formatSiteLabel(LaunchRecord r, int maxLen) {
        String candidate = nonBlankOr(r.getSpaceportName(), nonBlankOr(r.getPadName(), ""));
        if (candidate.isEmpty()) {
            return "";
        }
        int commaIdx = candidate.indexOf(',');
        if (commaIdx > 2) {
            candidate = candidate.substring(0, commaIdx).trim();
        }
        if (candidate.length() > maxLen && maxLen > 3) {
            return candidate.substring(0, maxLen - 1).trim() + "\u2026";
        }
        return candidate;
    }

    private static String nonBlankOr(String candidate, String fallback) {
        return (candidate != null && !candidate.isBlank()) ? candidate.trim() : fallback;
    }

    private static String escapeHtml(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> out.append(ch);
            }
        }
        return out.toString();
    }
}

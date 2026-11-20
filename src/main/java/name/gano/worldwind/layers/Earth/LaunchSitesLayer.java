package name.gano.worldwind.layers.Earth;

import gov.nasa.worldwind.View;
import gov.nasa.worldwind.WorldWind;
import gov.nasa.worldwind.WorldWindow;
import gov.nasa.worldwind.avlist.AVKey;
import gov.nasa.worldwind.avlist.AVList;
import gov.nasa.worldwind.event.SelectEvent;
import gov.nasa.worldwind.event.SelectListener;
import gov.nasa.worldwind.geom.Angle;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.layers.RenderableLayer;
import gov.nasa.worldwind.pick.PickedObject;
import gov.nasa.worldwind.render.AnnotationAttributes;
import gov.nasa.worldwind.render.DrawContext;
import gov.nasa.worldwind.render.GlobeAnnotation;
import gov.nasa.worldwind.render.Highlightable;
import gov.nasa.worldwind.render.Offset;
import gov.nasa.worldwind.render.PointPlacemark;
import gov.nasa.worldwind.render.PointPlacemarkAttributes;
import gov.nasa.worldwind.view.orbit.BasicOrbitView;
import gov.nasa.worldwindx.examples.util.ToolTipAnnotation;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import jsattrak.awareness.AwarenessStateListener;
import jsattrak.awareness.LaunchRecord;
import jsattrak.awareness.LaunchTimeEvaluator;
import jsattrak.awareness.LaunchUrgency;
import jsattrak.awareness.LiveAwarenessService;
import jsattrak.awareness.map.LaunchOverlay2DRenderer;
import name.gano.worldwind.view.BasicModelView3;
import name.gano.worldwind.view.GroundStationToSatView;

/**
 * WorldWind {@link RenderableLayer} rendering clustered upcoming launch sites and selection state
 * on 3D globe windows without requiring an active OpenGL context during marker construction.
 */
public class LaunchSitesLayer extends RenderableLayer {

    public static final String LAYER_NAME = "Upcoming Launches";
    public static final String LAUNCH_ID_KEY = "LAUNCH_ID";
    public static final String LAUNCH_URGENCY_COLOR_KEY = "LAUNCH_URGENCY_COLOR";
    public static final double DEFAULT_EYE_ALTITUDE_METERS = 1.5e7;

    private static final double MIN_CLUSTER_RADIUS_DEG = 0.02;
    private static final double REFERENCE_ALTITUDE_METERS = 1.5e7;
    private static final double REFERENCE_CLUSTER_RADIUS_DEG = 2.5;
    private static final double DEFAULT_PAN_ZOOM_METERS = 2_500_000.0;
    private static final long PAN_DURATION_MILLIS = 900L;
    private static final double MJD_EPOCH_1970 = 40587.0;
    private static final double MILLIS_PER_DAY = 86_400_000.0;

    private static final Comparator<LaunchRecord> SOONEST_FIRST =
            Comparator.comparingLong(LaunchRecord::getNetEpochMillis)
                    .thenComparing(LaunchRecord::getId, Comparator.nullsLast(String::compareTo));

    private static final Map<LaunchUrgency, PointPlacemarkAttributes> NORMAL_ATTRS = new EnumMap<>(LaunchUrgency.class);
    private static final Map<LaunchUrgency, PointPlacemarkAttributes> SELECTED_ATTRS = new EnumMap<>(LaunchUrgency.class);
    private static final Map<LaunchUrgency, PointPlacemarkAttributes> HIGHLIGHT_ATTRS = new EnumMap<>(LaunchUrgency.class);
    private static final Font LABEL_FONT_PLAIN = new Font("SansSerif", Font.PLAIN, 11);
    private static final Font LABEL_FONT_BOLD = new Font("SansSerif", Font.BOLD, 11);
    private static final Color LABEL_BG = new Color(18, 22, 28, 200);
    private static final Color LABEL_BORDER = new Color(255, 255, 255, 75);
    private static final Color TOOLTIP_BG = new Color(18, 22, 28, 232);
    private static final Color TOOLTIP_BORDER = new Color(144, 202, 249, 190);

    private volatile LiveAwarenessService awarenessService;
    private volatile long lastSimEpochMillis;
    private volatile double lastEyeAltitudeMeters = DEFAULT_EYE_ALTITUDE_METERS;
    private volatile boolean hasRebuildEpoch;
    private volatile ToolTipAnnotation hoverTooltip;
    private volatile String activeHoverTooltipText;

    public LaunchSitesLayer(LiveAwarenessService awarenessService) {
        super();
        setName(LAYER_NAME);
        setEnabled(true);
        setPickEnabled(true);
        this.awarenessService = awarenessService;
    }

    public LiveAwarenessService getAwarenessService() {
        return awarenessService;
    }

    public void setAwarenessService(LiveAwarenessService awarenessService) {
        this.awarenessService = awarenessService;
        if (awarenessService == null) {
            clearHoverTooltip();
            removeAllRenderables();
        } else if (hasRebuildEpoch) {
            rebuildMarkers(lastSimEpochMillis, lastEyeAltitudeMeters);
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) {
            clearHoverTooltip();
            removeAllRenderables();
        } else if (hasRebuildEpoch) {
            rebuildMarkers(lastSimEpochMillis, lastEyeAltitudeMeters);
        }
    }

    public String getActiveHoverTooltipText() {
        return activeHoverTooltipText;
    }

    public void showHoverTooltip(String text, Point pickPoint) {
        if (text == null || text.isBlank() || pickPoint == null) {
            clearHoverTooltip();
            return;
        }
        ToolTipAnnotation tip = this.hoverTooltip;
        if (tip == null) {
            tip = createTooltipAnnotation(text);
            this.hoverTooltip = tip;
        } else {
            tip.setText(text);
        }
        tip.setScreenPoint(new Point(pickPoint));
        this.activeHoverTooltipText = text;
    }

    public void clearHoverTooltip() {
        this.hoverTooltip = null;
        this.activeHoverTooltipText = null;
    }

    @Override
    protected void doRender(DrawContext dc) {
        super.doRender(dc);
        LiveAwarenessService svc = this.awarenessService;
        ToolTipAnnotation tip = this.hoverTooltip;
        if (tip != null && isEnabled() && svc != null && svc.isGlobalMapOverlayEnabled()) {
            tip.render(dc);
        }
    }

    /**
     * Rebuilds 3D {@link PointPlacemark} and {@link GlobeAnnotation} renderables from the current
     * {@link LiveAwarenessService} state without requiring an active OpenGL context.
     */
    public void rebuildMarkers(long simEpochMillis, double eyeAltitudeMeters) {
        double safeEyeAlt = Double.isFinite(eyeAltitudeMeters)
                ? Math.max(0.0, eyeAltitudeMeters)
                : DEFAULT_EYE_ALTITUDE_METERS;
        this.lastSimEpochMillis = simEpochMillis;
        this.lastEyeAltitudeMeters = safeEyeAlt;
        this.hasRebuildEpoch = true;

        LiveAwarenessService svc = this.awarenessService;
        if (!isEnabled() || svc == null || !svc.isGlobalMapOverlayEnabled()) {
            clearHoverTooltip();
            removeAllRenderables();
            return;
        }

        List<LaunchCluster> clusters = computeClusters(svc, simEpochMillis, safeEyeAlt);
        removeAllRenderables();
        if (clusters.isEmpty()) {
            clearHoverTooltip();
            return;
        }

        for (LaunchCluster cluster : clusters) {
            LaunchRecord soonest = cluster.soonest();
            Position pos = Position.fromDegrees(cluster.centroidLatDeg, cluster.centroidLonDeg, 0.0);
            Color urgencyColor = LaunchOverlay2DRenderer.colorForUrgency(cluster.urgency);
            String hoverText = buildHoverText(cluster, simEpochMillis);
            String compactLabel = buildCompactLabel(cluster, simEpochMillis);

            PointPlacemark placemark = new PointPlacemark(pos);
            placemark.setAltitudeMode(WorldWind.CLAMP_TO_GROUND);
            placemark.setDragEnabled(false);
            placemark.setAlwaysOnTop(cluster.selected);
            placemark.setAttributes(getOrCreatePlacemarkAttributes(cluster.urgency, cluster.selected));
            placemark.setHighlightAttributes(getOrCreateHighlightAttributes(cluster.urgency));
            applyLaunchMetadata(placemark, soonest.getId(), urgencyColor, hoverText);
            addRenderable(placemark);

            GlobeAnnotation annotation = new GlobeAnnotation(
                    compactLabel,
                    pos,
                    createLabelAttributes(cluster.selected));
            annotation.setAltitudeMode(WorldWind.CLAMP_TO_GROUND);
            annotation.setDragEnabled(false);
            annotation.setAlwaysOnTop(cluster.selected);
            applyLaunchMetadata(annotation, soonest.getId(), urgencyColor, hoverText);
            addRenderable(annotation);
        }
    }

    /**
     * Rebuilds markers only when eye altitude has changed sufficiently to affect angular clustering.
     */
    public boolean rebuildIfAltitudeChanged(long simEpochMillis, double eyeAltitudeMeters) {
        double safeEyeAlt = Double.isFinite(eyeAltitudeMeters)
                ? Math.max(0.0, eyeAltitudeMeters)
                : DEFAULT_EYE_ALTITUDE_METERS;
        if (!hasRebuildEpoch) {
            rebuildMarkers(simEpochMillis, safeEyeAlt);
            return true;
        }
        double oldRadius = computeClusterRadiusDeg(this.lastEyeAltitudeMeters);
        double newRadius = computeClusterRadiusDeg(safeEyeAlt);
        double denom = Math.max(oldRadius, 1e-6);
        if (Math.abs(newRadius - oldRadius) / denom >= 0.12) {
            rebuildMarkers(simEpochMillis, safeEyeAlt);
            return true;
        }
        return false;
    }

    public static double computeClusterRadiusDeg(double eyeAltitudeMeters) {
        double safeAlt = Double.isFinite(eyeAltitudeMeters)
                ? Math.max(0.0, eyeAltitudeMeters)
                : DEFAULT_EYE_ALTITUDE_METERS;
        return Math.max(MIN_CLUSTER_RADIUS_DEG, (safeAlt / REFERENCE_ALTITUDE_METERS) * REFERENCE_CLUSTER_RADIUS_DEG);
    }

    public static long mjdToEpochMillis(double mjd) {
        if (!Double.isFinite(mjd)) {
            return System.currentTimeMillis();
        }
        return Math.round((mjd - MJD_EPOCH_1970) * MILLIS_PER_DAY);
    }

    public static double getEyeAltitudeMeters(WorldWindow wwd) {
        if (wwd == null || wwd.getView() == null) {
            return DEFAULT_EYE_ALTITUDE_METERS;
        }
        View view = wwd.getView();
        try {
            Position eyePos = view.getCurrentEyePosition();
            if (eyePos == null) {
                eyePos = view.getEyePosition();
            }
            if (eyePos != null && Double.isFinite(eyePos.getElevation()) && eyePos.getElevation() > 0.0) {
                return eyePos.getElevation();
            }
        } catch (RuntimeException ignored) {
            // View may not be initialized before first GL frame or in headless stubs
        }
        if (view instanceof BasicOrbitView orbitView) {
            double zoom = orbitView.getZoom();
            if (Double.isFinite(zoom) && zoom > 0.0) {
                return zoom;
            }
        }
        return DEFAULT_EYE_ALTITUDE_METERS;
    }

    /**
     * Smoothly pans a 3D {@link BasicOrbitView} to the given coordinates when in Earth OrbitView mode.
     */
    public static void centerOrbitViewOnLatLon(WorldWindow wwd, double latDeg, double lonDeg) {
        if (wwd == null || !Double.isFinite(latDeg) || !Double.isFinite(lonDeg)) {
            return;
        }
        View view = wwd.getView();
        if (!(view instanceof BasicOrbitView orbitView)
                || view instanceof GroundStationToSatView
                || view instanceof BasicModelView3) {
            return;
        }
        double clampedLat = Math.max(-90.0, Math.min(90.0, latDeg));
        double normLon = normalizeLonDeg(lonDeg);
        Position targetPos = Position.fromDegrees(clampedLat, normLon, 0.0);
        Angle heading = orbitView.getHeading() != null ? orbitView.getHeading() : Angle.ZERO;
        Angle pitch = orbitView.getPitch() != null ? orbitView.getPitch() : Angle.ZERO;
        double currentZoom = orbitView.getZoom();
        double targetZoom = (Double.isFinite(currentZoom) && currentZoom > 0.0)
                ? Math.min(currentZoom, DEFAULT_PAN_ZOOM_METERS)
                : DEFAULT_PAN_ZOOM_METERS;
        try {
            orbitView.stopAnimations();
            orbitView.addPanToAnimator(targetPos, heading, pitch, targetZoom, PAN_DURATION_MILLIS, true);
        } catch (RuntimeException e) {
            orbitView.setCenterPosition(targetPos);
            orbitView.setZoom(targetZoom);
        }
        wwd.redraw();
    }

    /**
     * Creates a WorldWind {@link SelectListener} handling {@link SelectEvent#LEFT_CLICK} on launch
     * renderables carrying {@code "LAUNCH_ID"} and updating hover tooltips on {@code HOVER}/{@code ROLLOVER},
     * while never intercepting right-click or drag events.
     */
    public static SelectListener createSelectListener(LiveAwarenessService service, Runnable onMarkerClicked) {
        return new SelectListener() {
            private WeakReference<LaunchSitesLayer> lastTooltipLayer = new WeakReference<>(null);
            private WeakReference<Highlightable> lastHighlighted = new WeakReference<>(null);

            @Override
            public void selected(SelectEvent event) {
                if (event == null) {
                    return;
                }
                String action = event.getEventAction();
                if (SelectEvent.LEFT_CLICK.equals(action)) {
                    String launchId = extractLaunchId(event.getTopObject());
                    if (launchId != null) {
                        event.consume();
                        if (service != null) {
                            service.selectLaunchAndRequestOpenUi(launchId);
                        }
                        if (onMarkerClicked != null) {
                            onMarkerClicked.run();
                        }
                    }
                    return;
                }
                if (SelectEvent.HOVER.equals(action) || SelectEvent.ROLLOVER.equals(action)) {
                    handleHoverOrRollover(event);
                }
            }

            private void handleHoverOrRollover(SelectEvent event) {
                Object topObject = event.getTopObject();
                String launchId = extractLaunchId(topObject);
                boolean needsRedraw = false;

                if (SelectEvent.ROLLOVER.equals(event.getEventAction())) {
                    Highlightable prevHighlight = lastHighlighted.get();
                    Highlightable nextHighlight = (launchId != null && topObject instanceof Highlightable h) ? h : null;
                    if (prevHighlight != null && prevHighlight != nextHighlight) {
                        prevHighlight.setHighlighted(false);
                        needsRedraw = true;
                    }
                    if (nextHighlight != null && !nextHighlight.isHighlighted()) {
                        nextHighlight.setHighlighted(true);
                        needsRedraw = true;
                    }
                    lastHighlighted = new WeakReference<>(nextHighlight);
                }

                LaunchSitesLayer targetLayer = resolveLaunchLayer(event);
                LaunchSitesLayer prevLayer = lastTooltipLayer.get();
                if (launchId != null && targetLayer != null && topObject instanceof AVList avList) {
                    String hoverText = avList.getStringValue(AVKey.HOVER_TEXT);
                    if (hoverText == null || hoverText.isBlank()) {
                        hoverText = avList.getStringValue(AVKey.ROLLOVER_TEXT);
                    }
                    if (prevLayer != null && prevLayer != targetLayer) {
                        prevLayer.clearHoverTooltip();
                    }
                    if (hoverText != null && !hoverText.isBlank()) {
                        targetLayer.showHoverTooltip(hoverText, event.getPickPoint());
                        lastTooltipLayer = new WeakReference<>(targetLayer);
                        needsRedraw = true;
                    }
                } else if (prevLayer != null) {
                    if (prevLayer.getActiveHoverTooltipText() != null) {
                        prevLayer.clearHoverTooltip();
                        needsRedraw = true;
                    }
                    lastTooltipLayer = new WeakReference<>(null);
                }

                if (needsRedraw && event.getSource() instanceof WorldWindow wwd) {
                    wwd.redraw();
                }
            }
        };
    }

    /**
     * Shared coordinator binding a 3D panel's {@link WorldWindow} and {@link LaunchSitesLayer}
     * to {@link LiveAwarenessService} without duplicating listener/view-switch logic across
     * {@code J3DEarthPanel} and {@code J3DEarthInternalPanel}.
     */
    public static final class ViewCoordinator {
        private final WorldWindow wwd;
        private final LaunchSitesLayer layer;
        private LiveAwarenessService awarenessService;
        private AwarenessStateListener awarenessListener;
        private SelectListener selectListener;
        private volatile long lastSimEpochMillis;
        private List<ClusterSignature> lastRenderedSignature = List.of();
        private double lastRenderedThresholdDeg = Double.NaN;
        private boolean lastRenderedVisible = false;

        public ViewCoordinator(WorldWindow wwd, LaunchSitesLayer layer, long initialSimEpochMillis) {
            this.wwd = Objects.requireNonNull(wwd, "wwd");
            this.layer = Objects.requireNonNull(layer, "layer");
            this.lastSimEpochMillis = initialSimEpochMillis;
        }

        public LiveAwarenessService getAwarenessService() {
            return awarenessService;
        }

        public void setAwarenessService(LiveAwarenessService service) {
            if (this.awarenessService == service) {
                return;
            }
            if (this.awarenessService != null && this.awarenessListener != null) {
                this.awarenessService.removeListener(this.awarenessListener);
            }
            if (this.selectListener != null) {
                wwd.removeSelectListener(this.selectListener);
                this.selectListener = null;
            }
            this.awarenessService = service;
            this.layer.setAwarenessService(service);
            if (service != null) {
                this.awarenessListener = new AwarenessStateListener() {
                    @Override
                    public void onAwarenessDataChanged() {
                        refreshLaunchOverlay(lastSimEpochMillis);
                    }

                    @Override
                    public void onSelectionChanged(String selectedLaunchId) {
                        refreshLaunchOverlay(lastSimEpochMillis);
                    }

                    @Override
                    public void onStatusMessage(String message, boolean isWarning) {
                    }
                };
                service.addListener(this.awarenessListener);
                this.selectListener = createSelectListener(service, null);
                wwd.addSelectListener(this.selectListener);
            } else {
                this.awarenessListener = null;
            }
            refreshLaunchOverlay(lastSimEpochMillis);
        }

        /**
         * Re-attaches the launch {@link SelectListener} after {@code setupView()} replaces the
         * {@link WorldWindow}'s {@code InputHandler}.
         */
        public void reattachSelectListener() {
            if (this.selectListener != null) {
                wwd.removeSelectListener(this.selectListener);
                wwd.addSelectListener(this.selectListener);
            }
        }

        public boolean isShowUpcomingLaunches() {
            return layer.isEnabled();
        }

        public void setShowUpcomingLaunches(boolean enabled) {
            layer.setEnabled(enabled);
            refreshLaunchOverlay(lastSimEpochMillis);
        }

        public void refreshLaunchOverlay(long simEpochMillis) {
            this.lastSimEpochMillis = simEpochMillis;
            double eyeAlt = getEyeAltitudeMeters(wwd);
            layer.rebuildMarkers(simEpochMillis, eyeAlt);
            recordRenderedState(simEpochMillis, eyeAlt);
            wwd.redraw();
        }

        public void refreshForMjd(double mjd) {
            long simEpochMillis = mjdToEpochMillis(mjd);
            this.lastSimEpochMillis = simEpochMillis;
            LiveAwarenessService svc = this.awarenessService;
            boolean shouldBeVisible = svc != null && layer.isEnabled() && svc.isGlobalMapOverlayEnabled();
            if (!shouldBeVisible) {
                if (this.lastRenderedVisible || layer.getNumRenderables() > 0) {
                    layer.rebuildMarkers(simEpochMillis, getEyeAltitudeMeters(wwd));
                    this.lastRenderedSignature = List.of();
                    this.lastRenderedVisible = false;
                }
                return;
            }
            double eyeAlt = getEyeAltitudeMeters(wwd);
            double thresholdDeg = computeClusterRadiusDeg(eyeAlt);
            List<LaunchCluster> clusters = computeClusters(svc, simEpochMillis, eyeAlt);
            List<ClusterSignature> nextSignature = toSignature(clusters, simEpochMillis);
            if (!this.lastRenderedVisible
                    || Double.compare(this.lastRenderedThresholdDeg, thresholdDeg) != 0
                    || !Objects.equals(this.lastRenderedSignature, nextSignature)) {
                layer.rebuildMarkers(simEpochMillis, eyeAlt);
                this.lastRenderedSignature = nextSignature;
                this.lastRenderedThresholdDeg = thresholdDeg;
                this.lastRenderedVisible = true;
            }
        }

        public void onViewMoved() {
            double eyeAlt = getEyeAltitudeMeters(wwd);
            if (layer.rebuildIfAltitudeChanged(lastSimEpochMillis, eyeAlt)) {
                recordRenderedState(lastSimEpochMillis, eyeAlt);
                wwd.redraw();
            }
        }

        private void recordRenderedState(long simEpochMillis, double eyeAlt) {
            LiveAwarenessService svc = this.awarenessService;
            boolean visible = svc != null && layer.isEnabled() && svc.isGlobalMapOverlayEnabled();
            this.lastRenderedVisible = visible;
            this.lastRenderedThresholdDeg = computeClusterRadiusDeg(eyeAlt);
            this.lastRenderedSignature = visible
                    ? toSignature(computeClusters(svc, simEpochMillis, eyeAlt), simEpochMillis)
                    : List.of();
        }
    }

    private static List<ClusterSignature> toSignature(List<LaunchCluster> clusters, long simEpochMillis) {
        if (clusters.isEmpty()) {
            return List.of();
        }
        List<ClusterSignature> sig = new ArrayList<>(clusters.size());
        for (LaunchCluster c : clusters) {
            sig.add(new ClusterSignature(
                    c.soonest().getId(),
                    c.launches.size(),
                    c.urgency,
                    c.selected,
                    buildCompactLabel(c, simEpochMillis)));
        }
        return sig;
    }

    private static String extractLaunchId(Object topObject) {
        if (topObject instanceof AVList avList) {
            Object raw = avList.getValue(LAUNCH_ID_KEY);
            if (raw instanceof String id && !id.isBlank()) {
                return id;
            }
        }
        return null;
    }

    private static LaunchSitesLayer resolveLaunchLayer(SelectEvent event) {
        PickedObject picked = event.getTopPickedObject();
        if (picked != null && picked.getParentLayer() instanceof LaunchSitesLayer layer) {
            return layer;
        }
        if (event.getSource() instanceof LaunchSitesLayer layer) {
            return layer;
        }
        return null;
    }

    private static List<LaunchCluster> computeClusters(
            LiveAwarenessService service,
            long simEpochMillis,
            double eyeAltitudeMeters) {
        List<LaunchRecord> rawUpcoming = service.getVisibleUpcomingLaunches(simEpochMillis);
        List<LaunchRecord> upcoming = new ArrayList<>();
        if (rawUpcoming != null) {
            for (LaunchRecord r : rawUpcoming) {
                if (isValidLatLon(r)) {
                    upcoming.add(r);
                }
            }
        }
        upcoming.sort(SOONEST_FIRST);

        String selectedId = normalizeId(service.getSelectedLaunchId());
        double thresholdDeg = computeClusterRadiusDeg(eyeAltitudeMeters);

        List<ClusterBuilder> builders = new ArrayList<>();
        for (LaunchRecord record : upcoming) {
            boolean itemSelected = selectedId != null && Objects.equals(selectedId, record.getId());
            ClusterBuilder closest = findClosestCluster(builders, record.getLatDeg(), record.getLonDeg(), thresholdDeg);
            if (closest != null) {
                closest.add(record, itemSelected);
            } else {
                builders.add(new ClusterBuilder(record, itemSelected));
            }
        }

        LaunchRecord selected = service.getSelectedLaunch();
        if (selected != null
                && isValidLatLon(selected)
                && !containsLaunch(upcoming, selected)
                && selected.getNetEpochMillis() <= simEpochMillis) {
            boolean extraSelected = selectedId != null && Objects.equals(selectedId, selected.getId());
            ClusterBuilder nearest = findClosestCluster(builders, selected.getLatDeg(), selected.getLonDeg(), thresholdDeg);
            if (nearest != null) {
                if (extraSelected) {
                    nearest.selected = true;
                }
            } else {
                builders.add(new ClusterBuilder(selected, extraSelected));
            }
        }

        List<LaunchCluster> clusters = new ArrayList<>(builders.size());
        for (ClusterBuilder builder : builders) {
            clusters.add(builder.build(simEpochMillis));
        }
        return clusters;
    }

    private static ClusterBuilder findClosestCluster(
            List<ClusterBuilder> builders,
            double latDeg,
            double lonDeg,
            double thresholdDeg) {
        ClusterBuilder best = null;
        double bestDist = Double.MAX_VALUE;
        for (ClusterBuilder candidate : builders) {
            double dist = angularDistanceDeg(latDeg, lonDeg, candidate.centroidLatDeg(), candidate.centroidLonDeg());
            if (dist <= thresholdDeg && dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Computes great-circle angular separation in degrees using the haversine formula.
     */
    static double angularDistanceDeg(double lat1Deg, double lon1Deg, double lat2Deg, double lon2Deg) {
        double lat1 = Math.toRadians(lat1Deg);
        double lat2 = Math.toRadians(lat2Deg);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(normalizeDeltaLonDeg(lon2Deg - lon1Deg));
        double sinLat = Math.sin(dLat * 0.5);
        double sinLon = Math.sin(dLon * 0.5);
        double a = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
        double clampedA = Math.max(0.0, Math.min(1.0, a));
        return Math.toDegrees(2.0 * Math.asin(Math.sqrt(clampedA)));
    }

    private static double normalizeDeltaLonDeg(double deltaLon) {
        double d = deltaLon;
        while (d > 180.0) {
            d -= 360.0;
        }
        while (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    private static double normalizeLonDeg(double lonDeg) {
        double normalized = ((lonDeg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        return (normalized == -180.0 && lonDeg > 0.0) ? 180.0 : normalized;
    }

    private static boolean isValidLatLon(LaunchRecord record) {
        return record != null
                && Double.isFinite(record.getLatDeg())
                && Double.isFinite(record.getLonDeg())
                && record.getLatDeg() >= -90.0
                && record.getLatDeg() <= 90.0;
    }

    private static String normalizeId(String id) {
        return (id == null || id.isBlank()) ? null : id;
    }

    private static boolean containsLaunch(List<LaunchRecord> launches, LaunchRecord target) {
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

    private static void applyLaunchMetadata(
            AVList renderable,
            String launchId,
            Color urgencyColor,
            String hoverText) {
        renderable.setValue(LAUNCH_ID_KEY, launchId);
        renderable.setValue(LAUNCH_URGENCY_COLOR_KEY, urgencyColor);
        renderable.setValue(AVKey.HOVER_TEXT, hoverText);
        renderable.setValue(AVKey.ROLLOVER_TEXT, hoverText);
    }

    private static String buildCompactLabel(LaunchCluster cluster, long simEpochMillis) {
        LaunchRecord soonest = cluster.soonest();
        String site = formatSiteLabel(soonest, 16);
        String countdown = LaunchTimeEvaluator.formatCountdown(soonest, simEpochMillis);
        StringBuilder sb = new StringBuilder();
        if (cluster.launches.size() > 1) {
            sb.append('\u00D7').append(cluster.launches.size()).append(' ');
        }
        if (!site.isEmpty()) {
            sb.append(site).append(" \u00B7 ");
        }
        sb.append(countdown);
        return sb.toString();
    }

    private static String buildHoverText(LaunchCluster cluster, long simEpochMillis) {
        List<LaunchRecord> items = cluster.launches;
        LaunchRecord soonest = items.get(0);
        StringBuilder sb = new StringBuilder(192);
        String siteHeader = items.size() > 1
                ? nonBlankOr(soonest.getSpaceportName(), formatSiteLabel(soonest, 36))
                : formatSiteLabel(soonest, 36);
        if (!siteHeader.isEmpty()) {
            sb.append(siteHeader);
            if (items.size() > 1) {
                sb.append(" (\u00D7").append(items.size()).append(" launches)");
            }
        }
        int maxShown = Math.min(items.size(), 4);
        for (int i = 0; i < maxShown; i++) {
            LaunchRecord r = items.get(i);
            if (sb.length() > 0) {
                sb.append('\n');
            }
            String countdown = LaunchTimeEvaluator.formatCountdown(r, simEpochMillis);
            String mission = nonBlankOr(r.getMissionName(), "Scheduled Launch");
            String vehicle = nonBlankOr(r.getVehicle(), "");
            String status = nonBlankOr(r.getStatus(), "");
            sb.append(countdown).append(" \u2014 ").append(mission);
            if (!vehicle.isEmpty()) {
                sb.append(" (").append(vehicle).append(')');
            }
            if (!status.isEmpty()) {
                sb.append(" [").append(status).append(']');
            }
        }
        if (items.size() > maxShown) {
            sb.append("\n+").append(items.size() - maxShown).append(" more");
        }
        return sb.toString();
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

    private static synchronized PointPlacemarkAttributes getOrCreatePlacemarkAttributes(
            LaunchUrgency urgency,
            boolean selected) {
        LaunchUrgency key = urgency != null ? urgency : LaunchUrgency.EXPIRED;
        Map<LaunchUrgency, PointPlacemarkAttributes> cache = selected ? SELECTED_ATTRS : NORMAL_ATTRS;
        return cache.computeIfAbsent(key, u -> createPlacemarkAttributes(u, selected, false));
    }

    private static synchronized PointPlacemarkAttributes getOrCreateHighlightAttributes(LaunchUrgency urgency) {
        LaunchUrgency key = urgency != null ? urgency : LaunchUrgency.EXPIRED;
        return HIGHLIGHT_ATTRS.computeIfAbsent(key, u -> createPlacemarkAttributes(u, true, true));
    }

    private static PointPlacemarkAttributes createPlacemarkAttributes(
            LaunchUrgency urgency,
            boolean selected,
            boolean highlighted) {
        BufferedImage icon = createMarkerIcon(LaunchOverlay2DRenderer.colorForUrgency(urgency), selected);
        PointPlacemarkAttributes attrs = new PointPlacemarkAttributes();
        attrs.setImage(icon);
        attrs.setImageAddress("launch-marker-" + urgency.name() + (selected ? "-sel" : ""));
        attrs.setImageOffset(Offset.CENTER);
        attrs.setScale(highlighted ? 1.18 : 1.0);
        return attrs;
    }

    private static BufferedImage createMarkerIcon(Color fillColor, boolean selected) {
        int size = selected ? 30 : 22;
        double center = size * 0.5;
        double r = selected ? 7.0 : 6.0;

        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (selected) {
                g.setColor(new Color(255, 255, 255, 90));
                g.fill(new Ellipse2D.Double(center - (r + 5.5), center - (r + 5.5), 2 * (r + 5.5), 2 * (r + 5.5)));

                Stroke prev = g.getStroke();
                g.setStroke(new BasicStroke(2.0f));
                g.setColor(Color.WHITE);
                g.draw(new Ellipse2D.Double(center - (r + 3.2), center - (r + 3.2), 2 * (r + 3.2), 2 * (r + 3.2)));
                g.setStroke(prev);
            }

            g.setColor(new Color(18, 22, 28, 225));
            g.fill(new Ellipse2D.Double(center - (r + 1.2), center - (r + 1.2), 2 * (r + 1.2), 2 * (r + 1.2)));

            g.setColor(fillColor);
            g.fill(new Ellipse2D.Double(center - r, center - r, 2.0 * r, 2.0 * r));

            g.setStroke(new BasicStroke(selected ? 1.7f : 1.4f));
            g.setColor(Color.WHITE);
            g.draw(new Ellipse2D.Double(center - r, center - r, 2.0 * r, 2.0 * r));
        } finally {
            g.dispose();
        }
        return img;
    }

    private static AnnotationAttributes createLabelAttributes(boolean selected) {
        AnnotationAttributes attr = new AnnotationAttributes();
        attr.setFrameShape(AVKey.SHAPE_RECTANGLE);
        attr.setCornerRadius(6);
        attr.setLeader(AVKey.SHAPE_NONE);
        attr.setAdjustWidthToText(AVKey.SIZE_FIT_TEXT);
        attr.setSize(new Dimension(300, 0));
        attr.setInsets(new Insets(2, 6, 2, 6));
        attr.setFont(selected ? LABEL_FONT_BOLD : LABEL_FONT_PLAIN);
        attr.setTextColor(Color.WHITE);
        attr.setTextAlign(AVKey.CENTER);
        attr.setBackgroundColor(LABEL_BG);
        attr.setBorderColor(selected ? Color.WHITE : LABEL_BORDER);
        attr.setBorderWidth(selected ? 1.4 : 0.8);
        attr.setDrawOffset(new Point(0, 14));
        attr.setScale(1.0);
        attr.setDistanceMinScale(1.0);
        attr.setDistanceMaxScale(1.0);
        attr.setDistanceMinOpacity(1.0);
        return attr;
    }

    private static ToolTipAnnotation createTooltipAnnotation(String text) {
        ToolTipAnnotation tip = new ToolTipAnnotation(text);
        tip.setAlwaysOnTop(true);
        tip.setPickEnabled(false);
        AnnotationAttributes attr = tip.getAttributes();
        attr.setFrameShape(AVKey.SHAPE_RECTANGLE);
        attr.setCornerRadius(6);
        attr.setLeader(AVKey.SHAPE_NONE);
        attr.setAdjustWidthToText(AVKey.SIZE_FIT_TEXT);
        attr.setSize(new Dimension(380, 0));
        attr.setInsets(new Insets(5, 8, 5, 8));
        attr.setFont(LABEL_FONT_PLAIN);
        attr.setTextColor(Color.WHITE);
        attr.setTextAlign(AVKey.LEFT);
        attr.setBackgroundColor(TOOLTIP_BG);
        attr.setBorderColor(TOOLTIP_BORDER);
        attr.setBorderWidth(1.0);
        attr.setScale(1.0);
        attr.setDistanceMinScale(1.0);
        attr.setDistanceMaxScale(1.0);
        attr.setDistanceMinOpacity(1.0);
        return tip;
    }

    private record ClusterSignature(
            String soonestId,
            int count,
            LaunchUrgency urgency,
            boolean selected,
            String label) {
    }

    private static final class ClusterBuilder {
        private final List<LaunchRecord> members = new ArrayList<>();
        private final double baseLonDeg;
        private double sumLatDeg;
        private double sumDeltaLonDeg;
        private boolean selected;

        ClusterBuilder(LaunchRecord seed, boolean selected) {
            this.baseLonDeg = seed.getLonDeg();
            add(seed, selected);
        }

        void add(LaunchRecord record, boolean itemSelected) {
            members.add(record);
            sumLatDeg += record.getLatDeg();
            sumDeltaLonDeg += normalizeDeltaLonDeg(record.getLonDeg() - baseLonDeg);
            if (itemSelected) {
                selected = true;
            }
        }

        double centroidLatDeg() {
            return sumLatDeg / members.size();
        }

        double centroidLonDeg() {
            return normalizeLonDeg(baseLonDeg + (sumDeltaLonDeg / members.size()));
        }

        LaunchCluster build(long simEpochMillis) {
            LaunchUrgency urgency = LaunchTimeEvaluator.evaluateUrgency(members.get(0), simEpochMillis);
            return new LaunchCluster(
                    centroidLatDeg(),
                    centroidLonDeg(),
                    urgency,
                    List.copyOf(members),
                    selected);
        }
    }

    private record LaunchCluster(
            double centroidLatDeg,
            double centroidLonDeg,
            LaunchUrgency urgency,
            List<LaunchRecord> launches,
            boolean selected) {
        LaunchRecord soonest() {
            return launches.get(0);
        }
    }
}

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
import gov.nasa.worldwind.render.Renderable;
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
    private static final double ALTITUDE_THRESHOLD_HYSTERESIS_RATIO = 0.12;
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

    private final Object renderLock = new Object();
    private volatile LiveAwarenessService awarenessService;
    private long lastSimEpochMillis;
    private double lastEyeAltitudeMeters = DEFAULT_EYE_ALTITUDE_METERS;
    private boolean hasRebuildEpoch;
    private ToolTipAnnotation hoverTooltip;
    private String activeHoverTooltipText;

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
        long simEpoch;
        double eyeAlt;
        boolean shouldRebuild;
        synchronized (renderLock) {
            if (awarenessService == null) {
                clearHoverTooltipLocked();
                removeAllRenderables();
                return;
            }
            shouldRebuild = this.hasRebuildEpoch;
            simEpoch = this.lastSimEpochMillis;
            eyeAlt = this.lastEyeAltitudeMeters;
        }
        if (shouldRebuild) {
            rebuildMarkers(simEpoch, eyeAlt);
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        long simEpoch;
        double eyeAlt;
        boolean shouldRebuild;
        synchronized (renderLock) {
            if (!enabled) {
                clearHoverTooltipLocked();
                removeAllRenderables();
                return;
            }
            shouldRebuild = this.hasRebuildEpoch;
            simEpoch = this.lastSimEpochMillis;
            eyeAlt = this.lastEyeAltitudeMeters;
        }
        if (shouldRebuild) {
            rebuildMarkers(simEpoch, eyeAlt);
        }
    }

    public String getActiveHoverTooltipText() {
        synchronized (renderLock) {
            return activeHoverTooltipText;
        }
    }

    public void showHoverTooltip(String text, Point pickPoint) {
        synchronized (renderLock) {
            if (text == null || text.isBlank() || pickPoint == null) {
                clearHoverTooltipLocked();
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
    }

    public void clearHoverTooltip() {
        synchronized (renderLock) {
            clearHoverTooltipLocked();
        }
    }

    private void clearHoverTooltipLocked() {
        this.hoverTooltip = null;
        this.activeHoverTooltipText = null;
    }

    @Override
    public int getNumRenderables() {
        synchronized (renderLock) {
            return super.getNumRenderables();
        }
    }

    @Override
    public Iterable<Renderable> getRenderables() {
        synchronized (renderLock) {
            List<Renderable> snapshot = new ArrayList<>();
            for (Renderable renderable : super.getRenderables()) {
                snapshot.add(renderable);
            }
            return List.copyOf(snapshot);
        }
    }

    @Override
    protected void doPreRender(DrawContext dc) {
        synchronized (renderLock) {
            super.doPreRender(dc);
        }
    }

    @Override
    protected void doPick(DrawContext dc, Point pickPoint) {
        synchronized (renderLock) {
            super.doPick(dc, pickPoint);
        }
    }

    @Override
    protected void doRender(DrawContext dc) {
        synchronized (renderLock) {
            super.doRender(dc);
            LiveAwarenessService svc = this.awarenessService;
            ToolTipAnnotation tip = this.hoverTooltip;
            if (tip != null && isEnabled() && svc != null && svc.isGlobalMapOverlayEnabled()) {
                tip.render(dc);
            }
        }
    }

    /**
     * Rebuilds 3D {@link PointPlacemark} and {@link GlobeAnnotation} renderables from the current
     * {@link LiveAwarenessService} state without requiring an active OpenGL context.
     */
    public void rebuildMarkers(long simEpochMillis, double eyeAltitudeMeters) {
        double safeEyeAlt = sanitizeEyeAltitude(eyeAltitudeMeters);
        LiveAwarenessService svc = this.awarenessService;
        if (!isEnabled() || svc == null || !svc.isGlobalMapOverlayEnabled()) {
            applyPreparedClusters(List.of(), simEpochMillis, safeEyeAlt);
            return;
        }
        List<PreparedCluster> prepared = prepareClusters(svc, simEpochMillis, safeEyeAlt);
        applyPreparedClusters(prepared, simEpochMillis, safeEyeAlt);
    }

    /**
     * Builds the renderable list outside {@code renderLock}, then atomically swaps the layer contents.
     */
    void applyPreparedClusters(List<PreparedCluster> prepared, long simEpochMillis, double safeEyeAlt) {
        List<Renderable> nextRenderables = buildRenderables(prepared);
        synchronized (renderLock) {
            this.lastSimEpochMillis = simEpochMillis;
            this.lastEyeAltitudeMeters = safeEyeAlt;
            this.hasRebuildEpoch = true;

            LiveAwarenessService svc = this.awarenessService;
            if (!isEnabled() || svc == null || !svc.isGlobalMapOverlayEnabled() || nextRenderables.isEmpty()) {
                clearHoverTooltipLocked();
                removeAllRenderables();
                return;
            }
            removeAllRenderables();
            addRenderables(nextRenderables);
        }
    }

    /**
     * Rebuilds markers only when eye altitude has changed sufficiently to affect angular clustering.
     */
    public boolean rebuildIfAltitudeChanged(long simEpochMillis, double eyeAltitudeMeters) {
        double safeEyeAlt = sanitizeEyeAltitude(eyeAltitudeMeters);
        synchronized (renderLock) {
            if (this.hasRebuildEpoch) {
                double oldRadius = computeClusterRadiusDeg(this.lastEyeAltitudeMeters);
                double newRadius = computeClusterRadiusDeg(safeEyeAlt);
                if (!isThresholdChangeSignificant(oldRadius, newRadius)) {
                    return false;
                }
            }
        }
        rebuildMarkers(simEpochMillis, safeEyeAlt);
        return true;
    }

    static boolean isThresholdChangeSignificant(double previousThresholdDeg, double nextThresholdDeg) {
        if (!Double.isFinite(previousThresholdDeg)) {
            return true;
        }
        double denom = Math.max(previousThresholdDeg, 1e-6);
        return Math.abs(nextThresholdDeg - previousThresholdDeg) / denom >= ALTITUDE_THRESHOLD_HYSTERESIS_RATIO;
    }

    static double sanitizeEyeAltitude(double eyeAltitudeMeters) {
        return Double.isFinite(eyeAltitudeMeters)
                ? Math.max(0.0, eyeAltitudeMeters)
                : DEFAULT_EYE_ALTITUDE_METERS;
    }

    public static double computeClusterRadiusDeg(double eyeAltitudeMeters) {
        double safeAlt = sanitizeEyeAltitude(eyeAltitudeMeters);
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
        return createSelectListener(service, onMarkerClicked, null);
    }

    public static SelectListener createSelectListener(
            LiveAwarenessService service,
            Runnable onMarkerClicked,
            java.util.function.BooleanSupplier suppressSelection) {
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
                    if (suppressSelection != null && suppressSelection.getAsBoolean()) {
                        return;
                    }
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
     * Weak listener wrapper so {@link LiveAwarenessService} never pins a closed 3D window's
     * {@link ViewCoordinator} in memory; automatically unregisters itself once GC clears the target.
     */
    private static final class WeakAwarenessListener implements AwarenessStateListener {
        private final WeakReference<ViewCoordinator> coordinatorRef;
        private final WeakReference<LiveAwarenessService> serviceRef;

        WeakAwarenessListener(ViewCoordinator coordinator, LiveAwarenessService service) {
            this.coordinatorRef = new WeakReference<>(coordinator);
            this.serviceRef = new WeakReference<>(service);
        }

        private ViewCoordinator resolveOrUnregister() {
            ViewCoordinator coordinator = coordinatorRef.get();
            if (coordinator == null) {
                LiveAwarenessService svc = serviceRef.get();
                if (svc != null) {
                    svc.removeListener(this);
                }
                return null;
            }
            return coordinator;
        }

        @Override
        public void onAwarenessDataChanged() {
            ViewCoordinator coordinator = resolveOrUnregister();
            if (coordinator != null) {
                coordinator.onServiceStateChanged();
            }
        }

        @Override
        public void onSelectionChanged(String selectedLaunchId) {
            ViewCoordinator coordinator = resolveOrUnregister();
            if (coordinator != null) {
                coordinator.onServiceStateChanged();
            }
        }

        @Override
        public void onStatusMessage(String message, boolean isWarning) {
            resolveOrUnregister();
        }
    }

    /**
     * Shared coordinator binding a 3D panel's {@link WorldWindow} and {@link LaunchSitesLayer}
     * to {@link LiveAwarenessService} without duplicating listener/view-switch logic across
     * {@code J3DEarthPanel} and {@code J3DEarthInternalPanel}.
     */
    public static final class ViewCoordinator {
        private final Object stateLock = new Object();
        private final WorldWindow wwd;
        private final LaunchSitesLayer layer;
        private LiveAwarenessService awarenessService;
        private AwarenessStateListener awarenessListener;
        private SelectListener selectListener;
        private java.util.function.BooleanSupplier pickSuppressionCheck;
        private long lastSimEpochMillis;
        private List<ClusterSignature> lastRenderedSignature = List.of();
        private double lastRenderedThresholdDeg = Double.NaN;
        private double lastRenderedEyeAltMeters = DEFAULT_EYE_ALTITUDE_METERS;
        private boolean lastRenderedVisible = false;

        public ViewCoordinator(WorldWindow wwd, LaunchSitesLayer layer, long initialSimEpochMillis) {
            this.wwd = Objects.requireNonNull(wwd, "wwd");
            this.layer = Objects.requireNonNull(layer, "layer");
            this.lastSimEpochMillis = initialSimEpochMillis;
        }

        public LiveAwarenessService getAwarenessService() {
            synchronized (stateLock) {
                return awarenessService;
            }
        }

        public void setAwarenessService(LiveAwarenessService service) {
            synchronized (stateLock) {
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
                    this.awarenessListener = new WeakAwarenessListener(this, service);
                    service.addListener(this.awarenessListener);
                    WeakReference<ViewCoordinator> selfRef = new WeakReference<>(this);
                    this.selectListener = createSelectListener(
                            service,
                            null,
                            () -> {
                                ViewCoordinator c = selfRef.get();
                                return c != null && c.isPickSuppressed();
                            });
                    wwd.addSelectListener(this.selectListener);
                } else {
                    this.awarenessListener = null;
                }
                this.lastRenderedSignature = List.of();
                this.lastRenderedThresholdDeg = Double.NaN;
                this.lastRenderedEyeAltMeters = DEFAULT_EYE_ALTITUDE_METERS;
                this.lastRenderedVisible = false;
                double eyeAlt = sanitizeEyeAltitude(getEyeAltitudeMeters(wwd));
                applyCurrentStateLocked(this.lastSimEpochMillis, eyeAlt, true);
            }
            wwd.redraw();
        }

        public void setPickSuppressionCheck(java.util.function.BooleanSupplier check) {
            synchronized (stateLock) {
                this.pickSuppressionCheck = check;
            }
        }

        private boolean isPickSuppressed() {
            java.util.function.BooleanSupplier check;
            synchronized (stateLock) {
                check = this.pickSuppressionCheck;
            }
            return check != null && check.getAsBoolean();
        }

        /**
         * Detaches listeners and clears layer renderables when the owning 3D window closes.
         */
        public void dispose() {
            setAwarenessService(null);
        }

        /**
         * Re-attaches the launch {@link SelectListener} after {@code setupView()} replaces the
         * {@link WorldWindow}'s {@code InputHandler}.
         */
        public void reattachSelectListener() {
            synchronized (stateLock) {
                if (this.selectListener != null) {
                    wwd.removeSelectListener(this.selectListener);
                    wwd.addSelectListener(this.selectListener);
                }
            }
        }

        public boolean isShowUpcomingLaunches() {
            return layer.isEnabled();
        }

        public void setShowUpcomingLaunches(boolean enabled) {
            layer.setEnabled(enabled);
            synchronized (stateLock) {
                double eyeAlt = sanitizeEyeAltitude(getEyeAltitudeMeters(wwd));
                applyCurrentStateLocked(this.lastSimEpochMillis, eyeAlt, true);
            }
            wwd.redraw();
        }

        public void refreshLaunchOverlay(long simEpochMillis) {
            synchronized (stateLock) {
                this.lastSimEpochMillis = simEpochMillis;
                double eyeAlt = sanitizeEyeAltitude(getEyeAltitudeMeters(wwd));
                applyCurrentStateLocked(simEpochMillis, eyeAlt, true);
            }
            wwd.redraw();
        }

        void onServiceStateChanged() {
            synchronized (stateLock) {
                if (this.awarenessService == null) {
                    return;
                }
                double eyeAlt = sanitizeEyeAltitude(getEyeAltitudeMeters(wwd));
                applyCurrentStateLocked(this.lastSimEpochMillis, eyeAlt, true);
            }
            wwd.redraw();
        }

        public void refreshForMjd(double mjd) {
            long simEpochMillis = mjdToEpochMillis(mjd);
            synchronized (stateLock) {
                this.lastSimEpochMillis = simEpochMillis;
                double eyeAlt = sanitizeEyeAltitude(getEyeAltitudeMeters(wwd));
                applyCurrentStateLocked(simEpochMillis, eyeAlt, false);
            }
        }

        public void onViewMoved() {
            boolean updated;
            synchronized (stateLock) {
                double eyeAlt = sanitizeEyeAltitude(getEyeAltitudeMeters(wwd));
                double nextThresholdDeg = computeClusterRadiusDeg(eyeAlt);
                if (this.lastRenderedVisible
                        && !isThresholdChangeSignificant(this.lastRenderedThresholdDeg, nextThresholdDeg)) {
                    return;
                }
                updated = applyCurrentStateLocked(this.lastSimEpochMillis, eyeAlt, false);
            }
            if (updated) {
                wwd.redraw();
            }
        }

        private boolean applyCurrentStateLocked(long simEpochMillis, double eyeAlt, boolean forceRebuild) {
            LiveAwarenessService svc = this.awarenessService;
            boolean shouldBeVisible = svc != null && layer.isEnabled() && svc.isGlobalMapOverlayEnabled();
            if (!shouldBeVisible) {
                boolean needsClear = forceRebuild || this.lastRenderedVisible || layer.getNumRenderables() > 0;
                if (needsClear) {
                    layer.applyPreparedClusters(List.of(), simEpochMillis, eyeAlt);
                }
                this.lastRenderedSignature = List.of();
                this.lastRenderedThresholdDeg = Double.NaN;
                this.lastRenderedEyeAltMeters = eyeAlt;
                this.lastRenderedVisible = false;
                return needsClear;
            }

            double candidateThresholdDeg = computeClusterRadiusDeg(eyeAlt);
            boolean thresholdChanged = !this.lastRenderedVisible
                    || isThresholdChangeSignificant(this.lastRenderedThresholdDeg, candidateThresholdDeg);
            double effectiveEyeAlt = (forceRebuild || thresholdChanged) ? eyeAlt : this.lastRenderedEyeAltMeters;
            double effectiveThresholdDeg = computeClusterRadiusDeg(effectiveEyeAlt);

            List<PreparedCluster> prepared = prepareClusters(svc, simEpochMillis, effectiveEyeAlt);
            List<ClusterSignature> nextSignature = extractSignatures(prepared);
            if (!forceRebuild
                    && this.lastRenderedVisible
                    && !thresholdChanged
                    && Objects.equals(this.lastRenderedSignature, nextSignature)) {
                return false;
            }

            layer.applyPreparedClusters(prepared, simEpochMillis, effectiveEyeAlt);
            this.lastRenderedSignature = nextSignature;
            this.lastRenderedThresholdDeg = effectiveThresholdDeg;
            this.lastRenderedEyeAltMeters = effectiveEyeAlt;
            this.lastRenderedVisible = true;
            return true;
        }
    }

    private static List<PreparedCluster> prepareClusters(
            LiveAwarenessService service,
            long simEpochMillis,
            double eyeAltitudeMeters) {
        List<LaunchCluster> clusters = computeClusters(service, simEpochMillis, eyeAltitudeMeters);
        if (clusters.isEmpty()) {
            return List.of();
        }
        List<PreparedCluster> prepared = new ArrayList<>(clusters.size());
        for (LaunchCluster cluster : clusters) {
            String compactLabel = buildCompactLabel(cluster, simEpochMillis);
            String hoverText = buildHoverText(cluster, simEpochMillis);
            List<String> memberIds = new ArrayList<>(cluster.launches.size());
            for (LaunchRecord r : cluster.launches) {
                memberIds.add(r.getId());
            }
            ClusterSignature signature = new ClusterSignature(
                    List.copyOf(memberIds),
                    Math.round(cluster.centroidLatDeg * 1_000_000.0),
                    Math.round(cluster.centroidLonDeg * 1_000_000.0),
                    cluster.urgency,
                    cluster.selected,
                    compactLabel,
                    hoverText);
            prepared.add(new PreparedCluster(cluster, compactLabel, hoverText, signature));
        }
        return prepared;
    }

    private static List<ClusterSignature> extractSignatures(List<PreparedCluster> prepared) {
        if (prepared.isEmpty()) {
            return List.of();
        }
        List<ClusterSignature> sigs = new ArrayList<>(prepared.size());
        for (PreparedCluster item : prepared) {
            sigs.add(item.signature);
        }
        return sigs;
    }

    private static List<Renderable> buildRenderables(List<PreparedCluster> prepared) {
        if (prepared == null || prepared.isEmpty()) {
            return List.of();
        }
        List<Renderable> result = new ArrayList<>(prepared.size() * 2);
        for (PreparedCluster item : prepared) {
            LaunchCluster cluster = item.cluster;
            LaunchRecord soonest = cluster.soonest();
            Position pos = Position.fromDegrees(cluster.centroidLatDeg, cluster.centroidLonDeg, 0.0);
            Color urgencyColor = LaunchOverlay2DRenderer.colorForUrgency(cluster.urgency);

            PointPlacemark placemark = new PointPlacemark(pos);
            placemark.setAltitudeMode(WorldWind.CLAMP_TO_GROUND);
            placemark.setDragEnabled(false);
            placemark.setAlwaysOnTop(cluster.selected);
            placemark.setAttributes(getOrCreatePlacemarkAttributes(cluster.urgency, cluster.selected));
            placemark.setHighlightAttributes(getOrCreateHighlightAttributes(cluster.urgency));
            applyLaunchMetadata(placemark, soonest.getId(), urgencyColor, item.hoverText);
            result.add(placemark);

            GlobeAnnotation annotation = new GlobeAnnotation(
                    item.compactLabel,
                    pos,
                    createLabelAttributes(cluster.selected));
            annotation.setAltitudeMode(WorldWind.CLAMP_TO_GROUND);
            annotation.setDragEnabled(false);
            annotation.setAlwaysOnTop(cluster.selected);
            applyLaunchMetadata(annotation, soonest.getId(), urgencyColor, item.hoverText);
            result.add(annotation);
        }
        return result;
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

    static final class PreparedCluster {
        final LaunchCluster cluster;
        final String compactLabel;
        final String hoverText;
        final ClusterSignature signature;

        PreparedCluster(
                LaunchCluster cluster,
                String compactLabel,
                String hoverText,
                ClusterSignature signature) {
            this.cluster = cluster;
            this.compactLabel = compactLabel;
            this.hoverText = hoverText;
            this.signature = signature;
        }
    }

    private record ClusterSignature(
            List<String> memberIds,
            long latMicroDeg,
            long lonMicroDeg,
            LaunchUrgency urgency,
            boolean selected,
            String compactLabel,
            String hoverText) {
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

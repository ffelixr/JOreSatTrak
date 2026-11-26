package name.gano.worldwind.layers.Earth;

import gov.nasa.worldwind.WorldWind;
import gov.nasa.worldwind.WorldWindow;
import gov.nasa.worldwind.avlist.AVKey;
import gov.nasa.worldwind.avlist.AVList;
import gov.nasa.worldwind.event.SelectEvent;
import gov.nasa.worldwind.event.SelectListener;
import gov.nasa.worldwind.geom.LatLon;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.layers.RenderableLayer;
import gov.nasa.worldwind.render.AnnotationAttributes;
import gov.nasa.worldwind.render.BasicShapeAttributes;
import gov.nasa.worldwind.render.DrawContext;
import gov.nasa.worldwind.render.Highlightable;
import gov.nasa.worldwind.render.Material;
import gov.nasa.worldwind.render.Offset;
import gov.nasa.worldwind.render.PointPlacemark;
import gov.nasa.worldwind.render.PointPlacemarkAttributes;
import gov.nasa.worldwind.render.Renderable;
import gov.nasa.worldwind.render.SurfaceCircle;
import gov.nasa.worldwindx.examples.util.ToolTipAnnotation;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.map.GnssStationsOverlay2DRenderer;
import jsattrak.awareness.gnss.stations.GnssReferenceStation;
import jsattrak.awareness.gnss.stations.GnssStationListener;
import jsattrak.awareness.gnss.stations.GnssStationNetwork;
import jsattrak.awareness.gnss.stations.GnssStationService;
import jsattrak.awareness.gnss.stations.analysis.GnssStationAnalysis;
import jsattrak.awareness.gnss.stations.analysis.RegionalScanReport;
import jsattrak.awareness.gnss.stations.analysis.RegionalScanReport.RegionalEventSeverity;
import jsattrak.awareness.gnss.stations.analysis.RegionalScanReport.StationAnomalyLevel;
import jsattrak.awareness.gnss.stations.analysis.RegionalScanReport.StationAnomalySummary;

/**
 * WorldWind 3D overlay layer rendering permanent GNSS reference stations (clustered at global
 * zoom, expanding to single beacon pins on zoom-in) plus active regional interference zones.
 */
public class GnssStationsLayer extends RenderableLayer {

    public static final String LAYER_NAME = "GNSS Reference Stations";
    public static final String STATION_ID_KEY = "jsattrak.gnss.station.id";

    private static final double DEFAULT_EYE_ALTITUDE_METERS = 1.5e7;
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private static final Color SELECTED_RING = new Color(0x00, 0xE5, 0xFF);
    private static final Color OUTLINE_DARK = new Color(18, 22, 28, 235);
    private static final Color TOOLTIP_BG = new Color(18, 24, 34, 232);
    private static final Color TOOLTIP_BORDER = new Color(115, 145, 185, 190);
    private static final Font LABEL_FONT_PLAIN = new Font("SansSerif", Font.PLAIN, 12);
    private static final Font BADGE_FONT = new Font("SansSerif", Font.BOLD, 10);

    private static final Map<String, PointPlacemarkAttributes> ATTR_CACHE = new ConcurrentHashMap<>();

    private final Object renderLock = new Object();
    private volatile GnssStationService stationService;
    private ToolTipAnnotation hoverTooltip;
    private String activeHoverTooltipText;
    private double lastEyeAltitudeMeters = DEFAULT_EYE_ALTITUDE_METERS;

    public GnssStationsLayer(GnssStationService stationService) {
        this.stationService = stationService;
        setName(LAYER_NAME);
        setPickEnabled(true);
        setEnabled(true);
    }

    public GnssStationService getStationService() {
        return stationService;
    }

    public void setStationService(GnssStationService stationService) {
        synchronized (renderLock) {
            this.stationService = stationService;
        }
    }

    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) {
            synchronized (renderLock) {
                clearHoverTooltipLocked();
                removeAllRenderables();
            }
        } else {
            rebuildMarkers(this.lastEyeAltitudeMeters);
        }
    }

    public String getActiveHoverTooltipText() {
        synchronized (renderLock) {
            return activeHoverTooltipText;
        }
    }

    public void showHoverTooltip(String text, Point pickPoint) {
        if (text == null || text.isBlank() || pickPoint == null) {
            clearHoverTooltip();
            return;
        }
        synchronized (renderLock) {
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
            GnssStationService svc = this.stationService;
            ToolTipAnnotation tip = this.hoverTooltip;
            if (tip != null && isEnabled() && svc != null && svc.isMapOverlayEnabled()) {
                tip.render(dc);
            }
        }
    }

    public void rebuildMarkers(double eyeAltitudeMeters) {
        double safeEyeAlt = sanitizeEyeAltitude(eyeAltitudeMeters);
        GnssStationService svc = this.stationService;
        if (!isEnabled() || svc == null || !svc.isMapOverlayEnabled()) {
            applyPreparedClusters(List.of(), null, safeEyeAlt);
            return;
        }
        List<GnssReferenceStation> stations = svc.getFilteredStations("", null);
        String selectedId = svc.getSelectedStationId();
        RegionalScanReport report = svc.getLatestRegionalReport();
        List<StationCluster> clusters = computeClusters(stations, selectedId, report, safeEyeAlt);
        applyPreparedClusters(clusters, report, safeEyeAlt);
    }

    void applyPreparedClusters(
            List<StationCluster> clusters,
            RegionalScanReport report,
            double safeEyeAlt) {
        List<Renderable> nextRenderables = buildRenderables(clusters, report);
        synchronized (renderLock) {
            this.lastEyeAltitudeMeters = safeEyeAlt;
            GnssStationService svc = this.stationService;
            if (!isEnabled() || svc == null || !svc.isMapOverlayEnabled() || nextRenderables.isEmpty()) {
                clearHoverTooltipLocked();
                removeAllRenderables();
                return;
            }
            removeAllRenderables();
            addRenderables(nextRenderables);
        }
    }

    private static List<Renderable> buildRenderables(
            List<StationCluster> clusters,
            RegionalScanReport report) {
        List<Renderable> out = new ArrayList<>();
        if (report != null && report.severity() != RegionalEventSeverity.NOMINAL
                && Double.isFinite(report.centroidLatDeg())
                && Double.isFinite(report.centroidLonDeg())
                && Double.isFinite(report.radiusKm())
                && report.radiusKm() > 0.0) {
            double radiusMeters = Math.max(10_000.0, report.radiusKm() * 1000.0);
            SurfaceCircle ring = new SurfaceCircle(
                    LatLon.fromDegrees(report.centroidLatDeg(), report.centroidLonDeg()),
                    radiusMeters);
            BasicShapeAttributes attrs = new BasicShapeAttributes();
            Color zoneColor = report.severity() == RegionalEventSeverity.POTENTIAL_INTERFERENCE
                    ? GnssStationsOverlay2DRenderer.COLOR_ALERT_OR_OFFLINE
                    : GnssStationsOverlay2DRenderer.COLOR_DELAYED;
            attrs.setOutlineMaterial(new Material(zoneColor));
            attrs.setInteriorMaterial(new Material(zoneColor));
            attrs.setInteriorOpacity(0.12);
            attrs.setOutlineOpacity(0.95);
            attrs.setOutlineWidth(2.2);
            attrs.setOutlineStipplePattern((short) 0xF0F0);
            attrs.setOutlineStippleFactor(2);
            attrs.setDrawInterior(true);
            attrs.setDrawOutline(true);
            ring.setAttributes(attrs);
            out.add(ring);
        }

        for (StationCluster cluster : clusters) {
            PointPlacemark pm = new PointPlacemark(Position.fromDegrees(
                    cluster.centroidLatDeg(), cluster.centroidLonDeg(), 0.0));
            pm.setAltitudeMode(WorldWind.CLAMP_TO_GROUND);
            pm.setAttributes(getOrCreatePlacemarkAttributes(
                    cluster.color(), cluster.containsSelected(), cluster.members().size(), false));
            pm.setHighlightAttributes(getOrCreatePlacemarkAttributes(
                    cluster.color(), cluster.containsSelected(), cluster.members().size(), true));
            pm.setValue(STATION_ID_KEY, cluster.leadStation().stationId());
            pm.setValue(AVKey.HOVER_TEXT, cluster.hoverText());
            pm.setValue(AVKey.ROLLOVER_TEXT, cluster.hoverText());
            out.add(pm);
        }
        return out;
    }

    static double computeClusterRadiusDeg(double eyeAltitudeMeters) {
        double alt = sanitizeEyeAltitude(eyeAltitudeMeters);
        if (alt <= 900_000.0) {
            return 0.25;
        }
        if (alt >= 1.2e7) {
            return 4.5;
        }
        return Math.max(0.25, Math.min(4.5, (alt / 1.2e7) * 4.2));
    }

    static boolean isThresholdChangeSignificant(double prevDeg, double nextDeg) {
        if (Double.isNaN(prevDeg)) {
            return true;
        }
        return Math.abs(nextDeg - prevDeg) >= Math.max(0.15, 0.25 * prevDeg);
    }

    static double sanitizeEyeAltitude(double altMeters) {
        if (!Double.isFinite(altMeters) || altMeters <= 0.0) {
            return DEFAULT_EYE_ALTITUDE_METERS;
        }
        return altMeters;
    }

    static double getEyeAltitudeMeters(WorldWindow wwd) {
        if (wwd == null || wwd.getView() == null || wwd.getView().getEyePosition() == null) {
            return DEFAULT_EYE_ALTITUDE_METERS;
        }
        double alt = wwd.getView().getEyePosition().getElevation();
        return sanitizeEyeAltitude(alt);
    }

    private static List<StationCluster> computeClusters(
            List<GnssReferenceStation> stations,
            String selectedStationId,
            RegionalScanReport report,
            double eyeAltitudeMeters) {
        if (stations == null || stations.isEmpty()) {
            return List.of();
        }
        double thresholdDeg = computeClusterRadiusDeg(eyeAltitudeMeters);
        List<ClusterBuilder> builders = new ArrayList<>();
        for (GnssReferenceStation st : stations) {
            if (st == null || !Double.isFinite(st.latDeg()) || !Double.isFinite(st.lonDeg())) {
                continue;
            }
            Color color = GnssStationsOverlay2DRenderer.colorForStation(st, report);
            boolean sel = selectedStationId != null && selectedStationId.equalsIgnoreCase(st.stationId());
            ClusterBuilder matched = findClosestCluster(builders, st.latDeg(), st.lonDeg(), thresholdDeg);
            if (matched != null) {
                matched.add(st, color, sel);
            } else {
                builders.add(new ClusterBuilder(st, color, sel));
            }
        }
        List<StationCluster> out = new ArrayList<>(builders.size());
        for (ClusterBuilder b : builders) {
            out.add(b.build(selectedStationId, report));
        }
        return out;
    }

    private static ClusterBuilder findClosestCluster(
            List<ClusterBuilder> builders,
            double latDeg,
            double lonDeg,
            double thresholdDeg) {
        ClusterBuilder best = null;
        double bestDist = Double.MAX_VALUE;
        for (ClusterBuilder c : builders) {
            double dist = angularDistanceDeg(latDeg, lonDeg, c.centroidLatDeg(), c.centroidLonDeg());
            if (dist <= thresholdDeg && dist < bestDist) {
                bestDist = dist;
                best = c;
            }
        }
        return best;
    }

    private static double angularDistanceDeg(double lat1Deg, double lon1Deg, double lat2Deg, double lon2Deg) {
        double lat1 = Math.toRadians(lat1Deg);
        double lat2 = Math.toRadians(lat2Deg);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(normalizeDeltaLonDeg(lon2Deg - lon1Deg));
        double sinLat = Math.sin(dLat * 0.5);
        double sinLon = Math.sin(dLon * 0.5);
        double a = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
        return Math.toDegrees(2.0 * Math.asin(Math.sqrt(Math.max(0.0, Math.min(1.0, a)))));
    }

    private static double normalizeDeltaLonDeg(double d) {
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

    public static String extractStationId(Object topObject) {
        if (topObject instanceof AVList avList) {
            String val = avList.getStringValue(STATION_ID_KEY);
            if (val != null && !val.isBlank()) {
                return val;
            }
        }
        return null;
    }

    private static GnssStationsLayer resolveStationsLayer(SelectEvent event) {
        if (event == null) {
            return null;
        }
        if (event.getTopPickedObject() != null
                && event.getTopPickedObject().getParentLayer() instanceof GnssStationsLayer layer) {
            return layer;
        }
        if (event.getSource() instanceof WorldWindow wwd
                && wwd.getModel() != null
                && wwd.getModel().getLayers() != null) {
            for (var layer : wwd.getModel().getLayers()) {
                if (layer instanceof GnssStationsLayer stLayer) {
                    return stLayer;
                }
            }
        }
        return null;
    }

    public static SelectListener createSelectListener(
            GnssStationService service,
            BooleanSupplier pickSuppressionCheck) {
        return new SelectListener() {
            private WeakReference<Highlightable> lastHighlighted = new WeakReference<>(null);
            private WeakReference<GnssStationsLayer> lastTooltipLayer = new WeakReference<>(null);

            @Override
            public void selected(SelectEvent event) {
                if (event == null || service == null) {
                    return;
                }
                String action = event.getEventAction();
                if (SelectEvent.LEFT_CLICK.equals(action)) {
                    if (pickSuppressionCheck != null && pickSuppressionCheck.getAsBoolean()) {
                        return;
                    }
                    String stationId = extractStationId(event.getTopObject());
                    if (stationId != null) {
                        service.selectStation(stationId);
                    }
                    return;
                }
                if (SelectEvent.HOVER.equals(action) || SelectEvent.ROLLOVER.equals(action)) {
                    handleHoverOrRollover(event);
                }
            }

            private void handleHoverOrRollover(SelectEvent event) {
                Object topObject = event.getTopObject();
                String stationId = extractStationId(topObject);
                boolean needsRedraw = false;

                if (SelectEvent.ROLLOVER.equals(event.getEventAction())) {
                    Highlightable prev = lastHighlighted.get();
                    Highlightable next = (stationId != null && topObject instanceof Highlightable h) ? h : null;
                    if (prev != null && prev != next) {
                        prev.setHighlighted(false);
                        needsRedraw = true;
                    }
                    if (next != null && !next.isHighlighted()) {
                        next.setHighlighted(true);
                        needsRedraw = true;
                    }
                    lastHighlighted = new WeakReference<>(next);
                }

                GnssStationsLayer targetLayer = resolveStationsLayer(event);
                GnssStationsLayer prevLayer = lastTooltipLayer.get();
                if (stationId != null && targetLayer != null && topObject instanceof AVList avList) {
                    String hoverText = avList.getStringValue(AVKey.HOVER_TEXT);
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
     * Weak listener wrapper so {@link GnssStationService} never pins a closed 3D window's
     * {@link ViewCoordinator} in memory; unregisters automatically once GC collects the target.
     */
    private static final class WeakStationListener implements GnssStationListener {
        private final WeakReference<ViewCoordinator> coordinatorRef;
        private final WeakReference<GnssStationService> serviceRef;

        WeakStationListener(ViewCoordinator coordinator, GnssStationService service) {
            this.coordinatorRef = new WeakReference<>(coordinator);
            this.serviceRef = new WeakReference<>(service);
        }

        private ViewCoordinator resolveOrUnregister() {
            ViewCoordinator c = coordinatorRef.get();
            if (c == null) {
                GnssStationService svc = serviceRef.get();
                if (svc != null) {
                    svc.removeListener(this);
                }
                return null;
            }
            return c;
        }

        @Override
        public void onStationCatalogChanged() {
            ViewCoordinator c = resolveOrUnregister();
            if (c != null) {
                c.onServiceStateChanged();
            }
        }

        @Override
        public void onSelectedStationChanged(String selectedStationId) {
            ViewCoordinator c = resolveOrUnregister();
            if (c != null) {
                c.onServiceStateChanged();
            }
        }

        @Override
        public void onInspectionUpdated(GnssStationAnalysis analysis) {
            resolveOrUnregister();
        }

        @Override
        public void onRegionalScanUpdated(RegionalScanReport report) {
            ViewCoordinator c = resolveOrUnregister();
            if (c != null) {
                c.onServiceStateChanged();
            }
        }

        @Override
        public void onStationStatusMessage(String message, boolean isWarning) {
            resolveOrUnregister();
        }

        @Override
        public void onMapOverlayStateChanged(boolean enabled) {
            ViewCoordinator c = resolveOrUnregister();
            if (c != null) {
                c.onServiceStateChanged();
            }
        }
    }

    /**
     * Shared coordinator binding a 3D window's {@link WorldWindow} and {@link GnssStationsLayer}
     * to {@link GnssStationService} cleanly without leaking GC references.
     */
    public static final class ViewCoordinator {
        private final Object stateLock = new Object();
        private final WorldWindow wwd;
        private final GnssStationsLayer layer;
        private GnssStationService stationService;
        private GnssStationListener stationListener;
        private SelectListener selectListener;
        private BooleanSupplier pickSuppressionCheck;
        private List<ClusterSignature> lastRenderedSignature = List.of();
        private double lastRenderedThresholdDeg = Double.NaN;
        private double lastRenderedEyeAltMeters = DEFAULT_EYE_ALTITUDE_METERS;
        private boolean lastRenderedVisible = false;

        public ViewCoordinator(WorldWindow wwd, GnssStationsLayer layer) {
            this.wwd = Objects.requireNonNull(wwd, "wwd");
            this.layer = Objects.requireNonNull(layer, "layer");
        }

        public GnssStationService getStationService() {
            synchronized (stateLock) {
                return stationService;
            }
        }

        public void setStationService(GnssStationService service) {
            synchronized (stateLock) {
                if (this.stationService == service) {
                    return;
                }
                if (this.stationService != null && this.stationListener != null) {
                    this.stationService.removeListener(this.stationListener);
                }
                if (this.selectListener != null) {
                    wwd.removeSelectListener(this.selectListener);
                    this.selectListener = null;
                }
                this.stationService = service;
                this.layer.setStationService(service);
                if (service != null) {
                    this.stationListener = new WeakStationListener(this, service);
                    service.addListener(this.stationListener);
                    WeakReference<ViewCoordinator> selfRef = new WeakReference<>(this);
                    this.selectListener = createSelectListener(
                            service,
                            () -> {
                                ViewCoordinator c = selfRef.get();
                                return c != null && c.isPickSuppressed();
                            });
                    wwd.addSelectListener(this.selectListener);
                } else {
                    this.stationListener = null;
                }
                this.lastRenderedSignature = List.of();
                this.lastRenderedThresholdDeg = Double.NaN;
                this.lastRenderedEyeAltMeters = DEFAULT_EYE_ALTITUDE_METERS;
                this.lastRenderedVisible = false;
                double eyeAlt = getEyeAltitudeMeters(wwd);
                applyCurrentStateLocked(eyeAlt, true);
            }
            wwd.redraw();
        }

        public void setPickSuppressionCheck(BooleanSupplier check) {
            synchronized (stateLock) {
                this.pickSuppressionCheck = check;
            }
        }

        private boolean isPickSuppressed() {
            BooleanSupplier check;
            synchronized (stateLock) {
                check = this.pickSuppressionCheck;
            }
            return check != null && check.getAsBoolean();
        }

        public void dispose() {
            setStationService(null);
        }

        public void reattachSelectListener() {
            synchronized (stateLock) {
                if (this.selectListener != null) {
                    wwd.removeSelectListener(this.selectListener);
                    wwd.addSelectListener(this.selectListener);
                }
            }
        }

        public boolean isShowGnssStations() {
            return layer.isEnabled();
        }

        public void setShowGnssStations(boolean enabled) {
            layer.setEnabled(enabled);
            synchronized (stateLock) {
                double eyeAlt = getEyeAltitudeMeters(wwd);
                applyCurrentStateLocked(eyeAlt, true);
            }
            wwd.redraw();
        }

        public void refreshGnssStationsOverlay() {
            synchronized (stateLock) {
                double eyeAlt = getEyeAltitudeMeters(wwd);
                applyCurrentStateLocked(eyeAlt, true);
            }
            wwd.redraw();
        }

        void onServiceStateChanged() {
            synchronized (stateLock) {
                if (this.stationService == null) {
                    return;
                }
                double eyeAlt = getEyeAltitudeMeters(wwd);
                applyCurrentStateLocked(eyeAlt, true);
            }
            wwd.redraw();
        }

        public void onViewMoved() {
            boolean updated;
            synchronized (stateLock) {
                double eyeAlt = getEyeAltitudeMeters(wwd);
                double nextThresholdDeg = computeClusterRadiusDeg(eyeAlt);
                if (this.lastRenderedVisible
                        && !isThresholdChangeSignificant(this.lastRenderedThresholdDeg, nextThresholdDeg)) {
                    return;
                }
                updated = applyCurrentStateLocked(eyeAlt, false);
            }
            if (updated) {
                wwd.redraw();
            }
        }

        private boolean applyCurrentStateLocked(double eyeAlt, boolean forceRebuild) {
            GnssStationService svc = this.stationService;
            boolean shouldBeVisible = svc != null && layer.isEnabled() && svc.isMapOverlayEnabled();
            if (!shouldBeVisible) {
                boolean needsClear = forceRebuild || this.lastRenderedVisible || layer.getNumRenderables() > 0;
                if (needsClear) {
                    layer.applyPreparedClusters(List.of(), null, eyeAlt);
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

            List<GnssReferenceStation> stations = svc.getFilteredStations("", null);
            String selectedId = svc.getSelectedStationId();
            RegionalScanReport report = svc.getLatestRegionalReport();
            List<StationCluster> clusters = computeClusters(stations, selectedId, report, effectiveEyeAlt);
            List<ClusterSignature> nextSignature = extractSignatures(clusters, report);

            if (!forceRebuild
                    && this.lastRenderedVisible
                    && !thresholdChanged
                    && Objects.equals(this.lastRenderedSignature, nextSignature)) {
                return false;
            }

            layer.applyPreparedClusters(clusters, report, effectiveEyeAlt);
            this.lastRenderedSignature = nextSignature;
            this.lastRenderedThresholdDeg = effectiveThresholdDeg;
            this.lastRenderedEyeAltMeters = effectiveEyeAlt;
            this.lastRenderedVisible = true;
            return true;
        }
    }

    private static List<ClusterSignature> extractSignatures(
            List<StationCluster> clusters,
            RegionalScanReport report) {
        RegionalEventSeverity sev = report != null ? report.severity() : RegionalEventSeverity.NOMINAL;
        long rLat = report != null ? Math.round(report.centroidLatDeg() * 1000.0) : 0L;
        long rLon = report != null ? Math.round(report.centroidLonDeg() * 1000.0) : 0L;
        long rKm = report != null ? Math.round(report.radiusKm()) : 0L;
        List<ClusterSignature> out = new ArrayList<>(clusters.size());
        for (StationCluster c : clusters) {
            List<String> ids = new ArrayList<>(c.members().size());
            for (GnssReferenceStation s : c.members()) {
                ids.add(s.stationId());
            }
            out.add(new ClusterSignature(
                    ids,
                    Math.round(c.centroidLatDeg() * 1_000_000.0),
                    Math.round(c.centroidLonDeg() * 1_000_000.0),
                    c.color().getRGB(),
                    c.containsSelected(),
                    sev,
                    rLat,
                    rLon,
                    rKm));
        }
        return out;
    }

    private static PointPlacemarkAttributes getOrCreatePlacemarkAttributes(
            Color color, boolean selected, int count, boolean highlighted) {
        int badgeBucket = count == 1 ? 1 : (count < 10 ? count : 10 + Math.min(99, count));
        String key = color.getRGB() + ":" + selected + ":" + badgeBucket + ":" + highlighted;
        return ATTR_CACHE.computeIfAbsent(key, k -> {
            BufferedImage icon = createMarkerIcon(color, selected, count);
            PointPlacemarkAttributes attrs = new PointPlacemarkAttributes();
            attrs.setImage(icon);
            attrs.setImageAddress("gnss-station-" + key);
            attrs.setImageOffset(Offset.CENTER);
            attrs.setScale(highlighted ? 1.18 : 1.0);
            return attrs;
        });
    }

    private static BufferedImage createMarkerIcon(Color fillColor, boolean selected, int count) {
        int size = selected ? 30 : 24;
        double center = size * 0.5;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (selected) {
                g.setColor(new Color(0x00, 0xE5, 0xFF, 110));
                g.fill(new Ellipse2D.Double(center - 12.0, center - 12.0, 24.0, 24.0));
                g.setColor(SELECTED_RING);
                g.setStroke(new BasicStroke(1.8f));
                g.draw(new Ellipse2D.Double(center - 10.5, center - 10.5, 21.0, 21.0));
            }
            if (count == 1) {
                double r = 6.8;
                Path2D.Double tri = new Path2D.Double();
                tri.moveTo(center, center - r);
                tri.lineTo(center + r * 0.88, center + r * 0.72);
                tri.lineTo(center - r * 0.88, center + r * 0.72);
                tri.closePath();
                g.setColor(fillColor);
                g.fill(tri);
                g.setStroke(new BasicStroke(1.3f));
                g.setColor(OUTLINE_DARK);
                g.draw(tri);
            } else {
                double r = count >= 100 ? 10.5 : 9.5;
                Ellipse2D.Double circle = new Ellipse2D.Double(center - r, center - r, r * 2.0, r * 2.0);
                g.setColor(fillColor);
                g.fill(circle);
                g.setStroke(new BasicStroke(1.4f));
                g.setColor(OUTLINE_DARK);
                g.draw(circle);

                g.setFont(BADGE_FONT);
                FontMetrics fm = g.getFontMetrics();
                String text = Integer.toString(count);
                int tx = (int) Math.round(center - fm.stringWidth(text) * 0.5);
                int ty = (int) Math.round(center + (fm.getAscent() - fm.getDescent()) * 0.5);
                g.setColor(Color.WHITE);
                g.drawString(text, tx, ty);
            }
        } finally {
            g.dispose();
        }
        return img;
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
            List<String> memberIds,
            long latMicroDeg,
            long lonMicroDeg,
            int rgb,
            boolean selected,
            RegionalEventSeverity severity,
            long reportLat,
            long reportLon,
            long reportKm) {
    }

    record StationCluster(
            double centroidLatDeg,
            double centroidLonDeg,
            Color color,
            List<GnssReferenceStation> members,
            GnssReferenceStation leadStation,
            boolean containsSelected,
            String hoverText) {
    }

    private static final class ClusterBuilder {
        private final List<GnssReferenceStation> members = new ArrayList<>();
        private final double baseLonDeg;
        private double sumLatDeg;
        private double sumDeltaLonDeg;
        private Color color;
        private int rank;
        private boolean selected;

        ClusterBuilder(GnssReferenceStation seed, Color color, boolean selected) {
            this.baseLonDeg = seed.lonDeg();
            this.color = color;
            this.rank = rank(color);
            add(seed, color, selected);
        }

        private static int rank(Color c) {
            if (GnssStationsOverlay2DRenderer.COLOR_ALERT_OR_OFFLINE.equals(c)) {
                return 4;
            }
            if (GnssStationsOverlay2DRenderer.COLOR_DELAYED.equals(c)) {
                return 3;
            }
            if (GnssStationsOverlay2DRenderer.COLOR_NOMINAL.equals(c)) {
                return 2;
            }
            return 1;
        }

        void add(GnssReferenceStation st, Color c, boolean sel) {
            members.add(st);
            sumLatDeg += st.latDeg();
            sumDeltaLonDeg += normalizeDeltaLonDeg(st.lonDeg() - baseLonDeg);
            int r = rank(c);
            if (r > rank) {
                rank = r;
                color = c;
            }
            if (sel) {
                selected = true;
            }
        }

        double centroidLatDeg() {
            return sumLatDeg / members.size();
        }

        double centroidLonDeg() {
            return normalizeLonDeg(baseLonDeg + (sumDeltaLonDeg / members.size()));
        }

        StationCluster build(String selectedStationId, RegionalScanReport report) {
            GnssReferenceStation lead = members.get(0);
            if (selectedStationId != null) {
                for (GnssReferenceStation st : members) {
                    if (selectedStationId.equalsIgnoreCase(st.stationId())) {
                        lead = st;
                        break;
                    }
                }
            }
            String hover;
            if (members.size() == 1) {
                String nets = lead.networks().stream()
                        .map(GnssStationNetwork::getDisplayName)
                        .collect(Collectors.joining(", "));
                String latency = lead.latencyMinutes() >= 0 ? lead.latencyMinutes() + " min" : "N/A";
                hover = lead.stationId() + " — " + lead.siteName()
                        + "\n" + nets + " · " + lead.status().getDisplayLabel() + " · Latency: " + latency
                        + (lead.receiverModel().isBlank() ? "" : "\n" + lead.receiverModel());
            } else {
                hover = members.size() + " GNSS Reference Stations (Lead: " + lead.stationId() + ")";
            }
            return new StationCluster(
                    centroidLatDeg(),
                    centroidLonDeg(),
                    color,
                    List.copyOf(members),
                    lead,
                    selected,
                    hover);
        }
    }
}

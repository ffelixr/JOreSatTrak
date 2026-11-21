package name.gano.worldwind.layers.Earth;

import gov.nasa.worldwind.WorldWind;
import gov.nasa.worldwind.WorldWindow;
import gov.nasa.worldwind.avlist.AVKey;
import gov.nasa.worldwind.avlist.AVList;
import gov.nasa.worldwind.event.SelectEvent;
import gov.nasa.worldwind.event.SelectListener;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.layers.RenderableLayer;
import gov.nasa.worldwind.pick.PickedObject;
import gov.nasa.worldwind.render.AnnotationAttributes;
import gov.nasa.worldwind.render.BasicShapeAttributes;
import gov.nasa.worldwind.render.DrawContext;
import gov.nasa.worldwind.render.GlobeAnnotation;
import gov.nasa.worldwind.render.Highlightable;
import gov.nasa.worldwind.render.Material;
import gov.nasa.worldwind.render.Offset;
import gov.nasa.worldwind.render.Path;
import gov.nasa.worldwind.render.PointPlacemark;
import gov.nasa.worldwind.render.PointPlacemarkAttributes;
import gov.nasa.worldwind.render.Renderable;
import gov.nasa.worldwind.render.ScreenAnnotation;
import gov.nasa.worldwind.render.ShapeAttributes;
import gov.nasa.worldwind.util.WWMath;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import jsattrak.awareness.gnss.GnssAwarenessService;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.GnssDisplayStatus;
import jsattrak.awareness.gnss.GnssOrbitClass;
import jsattrak.awareness.gnss.GnssOrbitPropagator;
import jsattrak.awareness.gnss.GnssSatelliteState;
import jsattrak.awareness.gnss.GnssStateListener;
import jsattrak.awareness.gnss.map.GnssOverlay2DRenderer;
import jsattrak.utilities.TLE;

/**
 * WorldWind {@link RenderableLayer} rendering GNSS constellation satellites at orbital altitude
 * ({@link WorldWind#ABSOLUTE}), optional faint orbital-plane rings, selection highlight/label,
 * compact legend, and click/hover selection without requiring an active GL context during build.
 */
public class GnssConstellationLayer extends RenderableLayer {

    public static final String LAYER_NAME = "GNSS Constellations";
    public static final String GNSS_PRN_KEY = "GNSS_PRN_ID";

    private static final int ORBIT_RING_SAMPLES = 72;
    private static final long RING_CACHE_BUCKET_MILLIS = 15L * 60L * 1000L;
    private static final Font LABEL_FONT_BOLD = new Font("SansSerif", Font.BOLD, 11);
    private static final Font LABEL_FONT_PLAIN = new Font("SansSerif", Font.PLAIN, 11);
    private static final Color LABEL_BG = new Color(18, 22, 28, 205);
    private static final Color TOOLTIP_BG = new Color(18, 22, 28, 232);
    private static final Color TOOLTIP_BORDER = new Color(144, 202, 249, 190);
    private static final Map<String, PointPlacemarkAttributes> ATTRS_CACHE = new ConcurrentHashMap<>();

    private final Object renderLock = new Object();
    private final Map<String, CachedRing> orbitRingCache = new LinkedHashMap<>();

    private volatile GnssAwarenessService awarenessService;
    private long lastSimEpochMillis;
    private boolean hasRebuildEpoch;
    private ToolTipAnnotation hoverTooltip;
    private String activeHoverTooltipText;
    private ScreenAnnotation legendAnnotation;

    public GnssConstellationLayer(GnssAwarenessService service) {
        super();
        setName(LAYER_NAME);
        setEnabled(true);
        setPickEnabled(true);
        this.awarenessService = service;
    }

    public GnssAwarenessService getAwarenessService() {
        return awarenessService;
    }

    public void setAwarenessService(GnssAwarenessService service) {
        this.awarenessService = service;
        long epoch;
        boolean shouldRebuild;
        synchronized (renderLock) {
            if (service == null) {
                clearHoverTooltipLocked();
                legendAnnotation = null;
                removeAllRenderables();
                return;
            }
            shouldRebuild = this.hasRebuildEpoch;
            epoch = this.lastSimEpochMillis;
        }
        if (shouldRebuild) {
            rebuildRenderables(epoch);
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        long epoch;
        boolean shouldRebuild;
        synchronized (renderLock) {
            if (!enabled) {
                clearHoverTooltipLocked();
                legendAnnotation = null;
                removeAllRenderables();
                return;
            }
            shouldRebuild = this.hasRebuildEpoch;
            epoch = this.lastSimEpochMillis;
        }
        if (shouldRebuild) {
            rebuildRenderables(epoch);
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

    public String getActiveHoverTooltipText() {
        synchronized (renderLock) {
            return activeHoverTooltipText;
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
            for (Renderable r : super.getRenderables()) {
                snapshot.add(r);
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
            GnssAwarenessService svc = this.awarenessService;
            if (!isEnabled() || svc == null || !svc.isMapOverlayEnabled() || !svc.isFeedEnabled()) {
                return;
            }
            if (legendAnnotation != null && dc != null && dc.getDrawableHeight() > 80) {
                legendAnnotation.setScreenPoint(new Point(12, 34));
                legendAnnotation.render(dc);
            }
            if (hoverTooltip != null) {
                hoverTooltip.render(dc);
            }
        }
    }

    /**
     * Rebuilds 3D GNSS markers, optional orbital-plane rings, and selection label for {@code simEpochMillis}.
     */
    public void rebuildRenderables(long simEpochMillis) {
        GnssAwarenessService svc = this.awarenessService;
        if (!isEnabled() || svc == null || !svc.isMapOverlayEnabled() || !svc.isFeedEnabled()) {
            synchronized (renderLock) {
                this.lastSimEpochMillis = simEpochMillis;
                this.hasRebuildEpoch = true;
                clearHoverTooltipLocked();
                legendAnnotation = null;
                removeAllRenderables();
            }
            return;
        }

        List<GnssSatelliteState> visible = svc.getVisibleSatelliteStates(simEpochMillis);
        String selectedPrn = svc.getSelectedPrnId();
        boolean showRings = svc.isShowOrbitRings3d();

        List<Renderable> nextRenderables = new ArrayList<>(visible.size() + 24);
        Map<String, GnssSatelliteState> planeReps = new LinkedHashMap<>();

        for (GnssSatelliteState state : visible) {
            GnssOrbitPropagator.GeodeticPosition pos = state.position();
            if (pos == null) {
                continue;
            }
            String prn = state.catalog().prnId();
            boolean selected = prn.equals(selectedPrn);
            Position wwPos = Position.fromDegrees(pos.latDeg(), pos.lonDeg(), pos.altMeters());

            PointPlacemark pm = new PointPlacemark(wwPos);
            pm.setAltitudeMode(WorldWind.ABSOLUTE);
            pm.setValue(GNSS_PRN_KEY, prn);
            pm.setValue(AVKey.HOVER_TEXT,
                    prn + " · " + state.catalog().constellation().getDisplayName()
                            + " — " + state.statusResult().status().getDisplayLabel());
            pm.setAttributes(getOrCreateAttributes(
                    state.catalog().constellation(), state.statusResult().status(), selected, false));
            pm.setHighlightAttributes(getOrCreateAttributes(
                    state.catalog().constellation(), state.statusResult().status(), selected, true));
            nextRenderables.add(pm);

            if (selected) {
                GlobeAnnotation label = new GlobeAnnotation(prn, wwPos, createLabelAttributes());
                label.setAltitudeMode(WorldWind.ABSOLUTE);
                label.setPickEnabled(false);
                nextRenderables.add(label);
            }

            if (showRings && state.tle() != null
                    && state.catalog().orbitClass() != GnssOrbitClass.GEO
                    && state.catalog().plane() != null && !state.catalog().plane().isBlank()) {
                String planeKey = state.catalog().constellation().name() + ":" + state.catalog().plane();
                planeReps.putIfAbsent(planeKey, state);
            }
        }

        if (showRings && !planeReps.isEmpty()) {
            long ringBucket = simEpochMillis / RING_CACHE_BUCKET_MILLIS;
            ShapeAttributes ringAttrs = createRingAttributes();
            for (Map.Entry<String, GnssSatelliteState> entry : planeReps.entrySet()) {
                List<Position> ringPositions = getOrComputeRingPositions(
                        entry.getKey(), entry.getValue().tle(), simEpochMillis, ringBucket);
                if (ringPositions.size() >= 3) {
                    Path ringPath = new Path(ringPositions);
                    ringPath.setAltitudeMode(WorldWind.ABSOLUTE);
                    ringPath.setFollowTerrain(false);
                    ringPath.setPathType(AVKey.LINEAR);
                    ringPath.setAttributes(ringAttrs);
                    nextRenderables.add(ringPath);
                }
            }
        }

        ScreenAnnotation nextLegend = nextRenderables.isEmpty() ? null : createLegendAnnotation();

        synchronized (renderLock) {
            this.lastSimEpochMillis = simEpochMillis;
            this.hasRebuildEpoch = true;
            if (!isEnabled() || this.awarenessService == null
                    || !this.awarenessService.isMapOverlayEnabled()
                    || !this.awarenessService.isFeedEnabled()) {
                clearHoverTooltipLocked();
                legendAnnotation = null;
                removeAllRenderables();
                return;
            }
            legendAnnotation = nextLegend;
            removeAllRenderables();
            addRenderables(nextRenderables);
        }
    }

    private List<Position> getOrComputeRingPositions(
            String planeKey,
            TLE tle,
            long simEpochMillis,
            long ringBucket) {
        String signature = ringBucket + ":" + tle.getLine1();
        synchronized (orbitRingCache) {
            CachedRing cached = orbitRingCache.get(planeKey);
            if (cached != null && Objects.equals(cached.signature(), signature)) {
                return cached.positions();
            }
        }
        List<GnssOrbitPropagator.GeodeticPosition> ring = GnssOrbitPropagator.computeOrbitRing(
                tle, simEpochMillis, ORBIT_RING_SAMPLES);
        List<Position> positions = new ArrayList<>(ring.size());
        for (GnssOrbitPropagator.GeodeticPosition gp : ring) {
            positions.add(Position.fromDegrees(gp.latDeg(), gp.lonDeg(), gp.altMeters()));
        }
        List<Position> immutable = List.copyOf(positions);
        synchronized (orbitRingCache) {
            orbitRingCache.put(planeKey, new CachedRing(signature, immutable));
        }
        return immutable;
    }

    private static PointPlacemarkAttributes getOrCreateAttributes(
            GnssConstellation constellation,
            GnssDisplayStatus status,
            boolean selected,
            boolean highlighted) {
        String key = constellation.name() + "|" + status.name() + "|" + selected + "|" + highlighted;
        return ATTRS_CACHE.computeIfAbsent(key, k -> {
            BufferedImage icon = createMarkerIcon(constellation, status, selected);
            PointPlacemarkAttributes attrs = new PointPlacemarkAttributes();
            attrs.setImage(icon);
            attrs.setImageAddress("gnss-marker-" + key);
            attrs.setImageOffset(Offset.CENTER);
            attrs.setScale(highlighted ? 1.2 : 1.0);
            return attrs;
        });
    }

    private static BufferedImage createMarkerIcon(
            GnssConstellation constellation,
            GnssDisplayStatus status,
            boolean selected) {
        int size = selected ? 28 : 20;
        double center = size * 0.5;
        double r = selected ? 6.2 : 5.0;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (selected) {
                g.setColor(new Color(255, 255, 255, 90));
                g.fill(new Ellipse2D.Double(center - (r + 5), center - (r + 5), 2 * (r + 5), 2 * (r + 5)));
                Stroke prev = g.getStroke();
                g.setStroke(new BasicStroke(1.8f));
                g.setColor(Color.WHITE);
                g.draw(new Ellipse2D.Double(center - (r + 3), center - (r + 3), 2 * (r + 3), 2 * (r + 3)));
                g.setStroke(prev);
            }
            GnssOverlay2DRenderer.drawConstellationShape(
                    g,
                    constellation,
                    center,
                    center,
                    r,
                    GnssOverlay2DRenderer.colorForStatus(status),
                    new Color(18, 22, 28, 230));
        } finally {
            g.dispose();
        }
        return img;
    }

    private static ShapeAttributes createRingAttributes() {
        BasicShapeAttributes attrs = new BasicShapeAttributes();
        attrs.setDrawInterior(false);
        attrs.setDrawOutline(true);
        attrs.setOutlineMaterial(new Material(new Color(140, 175, 220)));
        attrs.setOutlineOpacity(0.22);
        attrs.setOutlineWidth(1.0);
        return attrs;
    }

    private static AnnotationAttributes createLabelAttributes() {
        AnnotationAttributes attr = new AnnotationAttributes();
        attr.setFrameShape(AVKey.SHAPE_RECTANGLE);
        attr.setCornerRadius(5);
        attr.setLeader(AVKey.SHAPE_NONE);
        attr.setAdjustWidthToText(AVKey.SIZE_FIT_TEXT);
        attr.setInsets(new Insets(2, 6, 2, 6));
        attr.setFont(LABEL_FONT_BOLD);
        attr.setTextColor(Color.WHITE);
        attr.setBackgroundColor(LABEL_BG);
        attr.setBorderColor(Color.WHITE);
        attr.setBorderWidth(1.2);
        attr.setDrawOffset(new Point(0, 14));
        attr.setScale(1.0);
        attr.setDistanceMinScale(1.0);
        attr.setDistanceMaxScale(1.0);
        attr.setDistanceMinOpacity(1.0);
        return attr;
    }

    private static ScreenAnnotation createLegendAnnotation() {
        String legendText = "GNSS: ● GPS  ◆ GAL  ▲ GLO  ■ BDS\n"
                + "Status: Green=Op  Amber=Test  Red=Unav  Grey=No data";
        AnnotationAttributes attr = new AnnotationAttributes();
        attr.setFrameShape(AVKey.SHAPE_RECTANGLE);
        attr.setCornerRadius(6);
        attr.setLeader(AVKey.SHAPE_NONE);
        attr.setAdjustWidthToText(AVKey.SIZE_FIT_TEXT);
        attr.setInsets(new Insets(4, 8, 4, 8));
        attr.setFont(new Font("SansSerif", Font.PLAIN, 10));
        attr.setTextColor(Color.WHITE);
        attr.setTextAlign(AVKey.LEFT);
        attr.setBackgroundColor(new Color(18, 22, 28, 195));
        attr.setBorderColor(new Color(255, 255, 255, 70));
        attr.setBorderWidth(1.0);
        ScreenAnnotation ann = new ScreenAnnotation(legendText, new Point(12, 34), attr);
        ann.setAlwaysOnTop(true);
        ann.setPickEnabled(false);
        return ann;
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
        attr.setSize(new Dimension(340, 0));
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

    public static SelectListener createSelectListener(GnssAwarenessService service) {
        return new SelectListener() {
            private WeakReference<GnssConstellationLayer> lastTooltipLayer = new WeakReference<>(null);
            private WeakReference<Highlightable> lastHighlighted = new WeakReference<>(null);

            @Override
            public void selected(SelectEvent event) {
                if (event == null) {
                    return;
                }
                String action = event.getEventAction();
                if (SelectEvent.LEFT_CLICK.equals(action)) {
                    String prn = extractPrnId(event.getTopObject());
                    if (prn != null) {
                        event.consume();
                        if (service != null) {
                            service.selectSatelliteAndRequestOpenUi(prn);
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
                String prn = extractPrnId(topObject);
                boolean needsRedraw = false;

                if (SelectEvent.ROLLOVER.equals(event.getEventAction())) {
                    Highlightable prev = lastHighlighted.get();
                    Highlightable next = (prn != null && topObject instanceof Highlightable h) ? h : null;
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

                GnssConstellationLayer targetLayer = resolveLayer(event);
                GnssConstellationLayer prevLayer = lastTooltipLayer.get();
                if (prn != null && targetLayer != null && topObject instanceof AVList avList) {
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

    private static String extractPrnId(Object topObject) {
        if (topObject instanceof AVList av) {
            Object val = av.getValue(GNSS_PRN_KEY);
            if (val != null) {
                String text = val.toString().trim();
                return text.isEmpty() ? null : text;
            }
        }
        return null;
    }

    private static GnssConstellationLayer resolveLayer(SelectEvent event) {
        if (event == null || event.getObjects() == null) {
            return null;
        }
        PickedObject po = event.getObjects().getTopPickedObject();
        if (po != null && po.getParentLayer() instanceof GnssConstellationLayer layer) {
            return layer;
        }
        return null;
    }

    private static final class WeakGnssListener implements GnssStateListener {
        private final WeakReference<ViewCoordinator> coordinatorRef;
        private final WeakReference<GnssAwarenessService> serviceRef;

        WeakGnssListener(ViewCoordinator coordinator, GnssAwarenessService service) {
            this.coordinatorRef = new WeakReference<>(coordinator);
            this.serviceRef = new WeakReference<>(service);
        }

        private ViewCoordinator resolveOrUnregister() {
            ViewCoordinator coordinator = coordinatorRef.get();
            if (coordinator == null) {
                GnssAwarenessService svc = serviceRef.get();
                if (svc != null) {
                    svc.removeListener(this);
                }
                return null;
            }
            return coordinator;
        }

        @Override
        public void onGnssDataChanged() {
            ViewCoordinator c = resolveOrUnregister();
            if (c != null) {
                c.onServiceStateChanged();
            }
        }

        @Override
        public void onGnssSelectionChanged(String selectedPrnId) {
            ViewCoordinator c = resolveOrUnregister();
            if (c != null) {
                c.onServiceStateChanged();
            }
        }

        @Override
        public void onGnssStatusMessage(String message, boolean isWarning) {
            resolveOrUnregister();
        }
    }

    /**
     * Shared coordinator binding a 3D panel's {@link WorldWindow} and {@link GnssConstellationLayer}
     * to {@link GnssAwarenessService}.
     */
    public static final class ViewCoordinator {
        private final Object stateLock = new Object();
        private final WorldWindow wwd;
        private final GnssConstellationLayer layer;
        private GnssAwarenessService service;
        private GnssStateListener listener;
        private SelectListener selectListener;
        private long lastSimEpochMillis;

        public ViewCoordinator(WorldWindow wwd, GnssConstellationLayer layer, long initialSimEpochMillis) {
            this.wwd = Objects.requireNonNull(wwd, "wwd");
            this.layer = Objects.requireNonNull(layer, "layer");
            this.lastSimEpochMillis = initialSimEpochMillis;
        }

        public GnssAwarenessService getGnssAwarenessService() {
            synchronized (stateLock) {
                return service;
            }
        }

        public void setGnssAwarenessService(GnssAwarenessService service) {
            synchronized (stateLock) {
                if (this.service == service) {
                    return;
                }
                if (this.service != null && this.listener != null) {
                    this.service.removeListener(this.listener);
                }
                if (this.selectListener != null) {
                    wwd.removeSelectListener(this.selectListener);
                    this.selectListener = null;
                }
                this.service = service;
                this.layer.setAwarenessService(service);
                if (service != null) {
                    this.listener = new WeakGnssListener(this, service);
                    service.addListener(this.listener);
                    this.selectListener = createSelectListener(service);
                    wwd.addSelectListener(this.selectListener);
                    layer.rebuildRenderables(this.lastSimEpochMillis);
                } else {
                    this.listener = null;
                }
            }
            wwd.redraw();
        }

        public void dispose() {
            setGnssAwarenessService(null);
        }

        public void reattachSelectListener() {
            synchronized (stateLock) {
                if (this.selectListener != null) {
                    wwd.removeSelectListener(this.selectListener);
                    wwd.addSelectListener(this.selectListener);
                }
            }
        }

        public boolean isShowGnssConstellations() {
            return layer.isEnabled();
        }

        public void setShowGnssConstellations(boolean enabled) {
            layer.setEnabled(enabled);
            synchronized (stateLock) {
                layer.rebuildRenderables(this.lastSimEpochMillis);
            }
            wwd.redraw();
        }

        public void refreshGnssOverlay(long simEpochMillis) {
            synchronized (stateLock) {
                this.lastSimEpochMillis = simEpochMillis;
                layer.rebuildRenderables(simEpochMillis);
            }
            wwd.redraw();
        }

        public void refreshForMjd(double mjd) {
            long simEpochMillis = LaunchSitesLayer.mjdToEpochMillis(mjd);
            synchronized (stateLock) {
                this.lastSimEpochMillis = simEpochMillis;
                if (this.service == null || !layer.isEnabled()) {
                    return;
                }
                layer.rebuildRenderables(simEpochMillis);
            }
        }

        void onServiceStateChanged() {
            synchronized (stateLock) {
                if (this.service == null) {
                    return;
                }
                layer.rebuildRenderables(this.lastSimEpochMillis);
            }
            wwd.redraw();
        }
    }

    private record CachedRing(String signature, List<Position> positions) {
    }
}

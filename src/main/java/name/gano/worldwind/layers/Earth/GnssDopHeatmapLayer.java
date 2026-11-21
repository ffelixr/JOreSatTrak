package name.gano.worldwind.layers.Earth;

import gov.nasa.worldwind.WorldWind;
import gov.nasa.worldwind.WorldWindow;
import gov.nasa.worldwind.avlist.AVKey;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.geom.Sector;
import gov.nasa.worldwind.layers.RenderableLayer;
import gov.nasa.worldwind.render.DrawContext;
import gov.nasa.worldwind.render.Offset;
import gov.nasa.worldwind.render.PointPlacemark;
import gov.nasa.worldwind.render.PointPlacemarkAttributes;
import gov.nasa.worldwind.render.SurfaceImage;
import jsattrak.awareness.gnss.geometry.GnssDopGrid;
import jsattrak.awareness.gnss.geometry.GnssGeometryListener;
import jsattrak.awareness.gnss.geometry.GnssGeometryService;
import jsattrak.awareness.gnss.geometry.GnssObserverPosition;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.ref.WeakReference;
import java.util.Objects;

/**
 * WorldWind {@link RenderableLayer} rendering the global GNSS DOP heatmap ({@link SurfaceImage})
 * and an independent 3D observer {@link PointPlacemark}, plus Pick-on-Map click handling via
 * {@link ViewCoordinator}.
 */
public class GnssDopHeatmapLayer extends RenderableLayer {

    public static final String LAYER_NAME = "GNSS DOP Heatmap";

    private static final Color PIN_COLOR = new Color(0x00, 0xE5, 0xFF);
    private static final Color PIN_OUTLINE = new Color(10, 14, 20, 235);
    private static final BufferedImage OBSERVER_ICON = createObserverIcon();

    private final Object renderLock = new Object();
    private volatile GnssGeometryService geometryService;
    private long lastSimEpochMillis;
    private boolean hasRebuildEpoch;
    private PointPlacemark observerPin;

    public GnssDopHeatmapLayer(GnssGeometryService service) {
        super();
        setName(LAYER_NAME);
        setEnabled(true); // per-window toggle defaults ON; master gate controls visibility
        setPickEnabled(false);
        this.geometryService = service;
    }

    public GnssGeometryService getGeometryService() {
        return geometryService;
    }

    public void setGeometryService(GnssGeometryService service) {
        this.geometryService = service;
        long epoch;
        boolean shouldRebuild;
        synchronized (renderLock) {
            if (service == null) {
                observerPin = null;
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
            shouldRebuild = this.hasRebuildEpoch;
            epoch = this.lastSimEpochMillis;
        }
        if (shouldRebuild) {
            rebuildRenderables(epoch);
        } else if (!enabled) {
            synchronized (renderLock) {
                removeAllRenderables();
                if (observerPin != null) {
                    addRenderable(observerPin);
                }
            }
        }
    }

    /**
     * Rebuilds the heatmap {@link SurfaceImage} (only when both per-window layer and master gate are enabled)
     * and observer {@link PointPlacemark} (independently when {@link GnssGeometryService#isObserverPinVisible()}).
     */
    public void rebuildRenderables(long simEpochMillis) {
        GnssGeometryService svc = this.geometryService;
        SurfaceImage surfaceImage = null;
        PointPlacemark pin = null;

        if (svc != null) {
            if (isEnabled() && svc.isMapOverlayEnabled()) {
                svc.requestHeatmapAsync(simEpochMillis);
                GnssDopGrid grid = svc.getLatestHeatmapGrid();
                if (grid != null) {
                    BufferedImage rgba = grid.renderRgbaImage(svc.getHeatmapOpacity());
                    surfaceImage = new SurfaceImage(rgba, Sector.FULL_SPHERE);
                }
            }
            if (svc.isObserverPinVisible() && svc.getObserver() != null) {
                GnssObserverPosition obs = svc.getObserver();
                pin = new PointPlacemark(Position.fromDegrees(obs.latDeg(), obs.lonDeg(), 0.0));
                pin.setAltitudeMode(WorldWind.CLAMP_TO_GROUND);
                pin.setLabelText("GNSS Obs (" + obs.label() + ")");
                PointPlacemarkAttributes attrs = new PointPlacemarkAttributes();
                attrs.setImage(OBSERVER_ICON);
                attrs.setImageOffset(Offset.CENTER);
                attrs.setScale(1.0);
                attrs.setLabelColor("ff00e5ff");
                pin.setAttributes(attrs);
                pin.setValue(AVKey.DISPLAY_NAME, "GNSS Observer: " + obs.label());
            }
        }

        synchronized (renderLock) {
            this.lastSimEpochMillis = simEpochMillis;
            this.hasRebuildEpoch = true;
            this.observerPin = pin;
            removeAllRenderables();
            if (surfaceImage != null) {
                addRenderable(surfaceImage);
            }
            if (pin != null) {
                addRenderable(pin);
            }
        }
    }

    @Override
    public void render(DrawContext dc) {
        if (isEnabled()) {
            super.render(dc);
            return;
        }
        // Keep the observer pin visible even if this 3D window's heatmap layer is muted
        PointPlacemark pin;
        synchronized (renderLock) {
            pin = this.observerPin;
        }
        if (pin != null && dc != null) {
            pin.render(dc);
        }
    }

    private static BufferedImage createObserverIcon() {
        int s = 26;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int c = s / 2;
        g2.setStroke(new BasicStroke(3.2f));
        g2.setColor(PIN_OUTLINE);
        g2.drawOval(c - 7, c - 7, 14, 14);
        g2.drawLine(2, c, s - 3, c);
        g2.drawLine(c, 2, c, s - 3);
        g2.setStroke(new BasicStroke(1.6f));
        g2.setColor(PIN_COLOR);
        g2.drawOval(c - 7, c - 7, 14, 14);
        g2.drawLine(2, c, s - 3, c);
        g2.drawLine(c, 2, c, s - 3);
        g2.dispose();
        return img;
    }

    private static final class WeakGeometryListener implements GnssGeometryListener {
        private final WeakReference<ViewCoordinator> coordinatorRef;
        private final WeakReference<GnssGeometryService> serviceRef;

        WeakGeometryListener(ViewCoordinator coordinator, GnssGeometryService service) {
            this.coordinatorRef = new WeakReference<>(coordinator);
            this.serviceRef = new WeakReference<>(service);
        }

        private ViewCoordinator resolveOrUnregister() {
            ViewCoordinator coordinator = coordinatorRef.get();
            if (coordinator == null) {
                GnssGeometryService svc = serviceRef.get();
                if (svc != null) {
                    svc.removeListener(this);
                }
                return null;
            }
            return coordinator;
        }

        @Override
        public void onGeometryDataChanged() {
            ViewCoordinator c = resolveOrUnregister();
            if (c != null) {
                c.onServiceStateChanged();
            }
        }

        @Override
        public void onHeatmapUpdated(GnssDopGrid grid) {
            ViewCoordinator c = resolveOrUnregister();
            if (c != null) {
                c.onServiceStateChanged();
            }
        }

        @Override
        public void onObserverChanged(GnssObserverPosition observer) {
            ViewCoordinator c = resolveOrUnregister();
            if (c != null) {
                c.onServiceStateChanged();
            }
        }

        @Override
        public void onPickOnMapModeChanged(boolean pickMode) {
            ViewCoordinator c = resolveOrUnregister();
            if (c != null) {
                c.onServiceStateChanged();
            }
        }
    }

    /**
     * Shared coordinator binding a 3D panel's {@link WorldWindow} and {@link GnssDopHeatmapLayer}
     * to {@link GnssGeometryService}, including Pick-on-Map click handling.
     */
    public static final class ViewCoordinator {
        private final Object stateLock = new Object();
        private final WorldWindow wwd;
        private final GnssDopHeatmapLayer layer;
        private GnssGeometryService service;
        private GnssGeometryListener listener;
        private MouseAdapter pickMouseListener;
        private long lastSimEpochMillis;

        public ViewCoordinator(WorldWindow wwd, GnssDopHeatmapLayer layer, long initialSimEpochMillis) {
            this.wwd = Objects.requireNonNull(wwd, "wwd");
            this.layer = Objects.requireNonNull(layer, "layer");
            this.lastSimEpochMillis = initialSimEpochMillis;
        }

        public GnssGeometryService getGnssGeometryService() {
            synchronized (stateLock) {
                return service;
            }
        }

        public void setGnssGeometryService(GnssGeometryService service) {
            synchronized (stateLock) {
                if (this.service == service) {
                    return;
                }
                if (this.service != null && this.listener != null) {
                    this.service.removeListener(this.listener);
                }
                if (this.pickMouseListener != null && wwd instanceof Component comp) {
                    comp.removeMouseListener(this.pickMouseListener);
                    this.pickMouseListener = null;
                }
                this.service = service;
                this.layer.setGeometryService(service);
                if (service != null) {
                    this.listener = new WeakGeometryListener(this, service);
                    service.addListener(this.listener);
                    if (wwd instanceof Component comp) {
                        this.pickMouseListener = new MouseAdapter() {
                            @Override
                            public void mouseClicked(MouseEvent e) {
                                if (e.getButton() != MouseEvent.BUTTON1 || e.getClickCount() != 1) {
                                    return;
                                }
                                GnssGeometryService current = getGnssGeometryService();
                                if (current == null || !current.isPickOnMapMode()) {
                                    return;
                                }
                                Position pos = wwd.getCurrentPosition();
                                if (pos == null && wwd.getView() != null) {
                                    pos = wwd.getView().computePositionFromScreenPoint(e.getX(), e.getY());
                                }
                                if (pos != null) {
                                    current.completeMapPick(
                                            pos.getLatitude().degrees,
                                            pos.getLongitude().degrees);
                                }
                            }
                        };
                        comp.addMouseListener(this.pickMouseListener);
                    }
                    layer.rebuildRenderables(this.lastSimEpochMillis);
                } else {
                    this.listener = null;
                }
            }
            wwd.redraw();
        }

        public boolean isShowGnssDopHeatmap() {
            return layer.isEnabled();
        }

        public void setShowGnssDopHeatmap(boolean enabled) {
            layer.setEnabled(enabled);
            synchronized (stateLock) {
                layer.rebuildRenderables(this.lastSimEpochMillis);
            }
            wwd.redraw();
        }

        public void refreshGnssDopHeatmap(long simEpochMillis) {
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
                if (this.service != null && this.service.isMapOverlayEnabled() && layer.isEnabled()) {
                    this.service.requestHeatmapAsync(simEpochMillis);
                }
            }
        }

        public void reattachPickListener() {
            synchronized (stateLock) {
                if (this.pickMouseListener != null && wwd instanceof Component comp) {
                    comp.removeMouseListener(this.pickMouseListener);
                    comp.addMouseListener(this.pickMouseListener);
                }
            }
        }

        public void dispose() {
            setGnssGeometryService(null);
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
}

package name.gano.worldwind.layers.Earth;

import gov.nasa.worldwind.Model;
import gov.nasa.worldwind.WorldWind;
import gov.nasa.worldwind.WorldWindow;
import gov.nasa.worldwind.avlist.AVKey;
import gov.nasa.worldwind.avlist.AVList;
import gov.nasa.worldwind.event.SelectEvent;
import gov.nasa.worldwind.event.SelectListener;
import gov.nasa.worldwind.geom.Angle;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.globes.Earth;
import gov.nasa.worldwind.layers.Layer;
import gov.nasa.worldwind.layers.LayerList;
import gov.nasa.worldwind.pick.PickedObject;
import gov.nasa.worldwind.pick.PickedObjectList;
import gov.nasa.worldwind.render.GlobeAnnotation;
import gov.nasa.worldwind.render.PointPlacemark;
import gov.nasa.worldwind.render.Renderable;
import gov.nasa.worldwind.view.orbit.BasicOrbitView;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.SwingUtilities;
import jsattrak.awareness.AwarenessStateListener;
import jsattrak.awareness.LaunchRecord;
import jsattrak.awareness.LaunchUrgency;
import jsattrak.awareness.LiveAwarenessService;
import jsattrak.awareness.map.LaunchOverlay2DRenderer;
import jsattrak.gui.JSatTrak;
import jsattrak.gui.JThreeDViewPropPanel;
import jsattrak.utilities.J3DEarthComponent;
import name.gano.worldwind.geom.ECIRadialGrid;
import name.gano.worldwind.layers.Earth.EcefTimeDepRenderableLayer;
import name.gano.worldwind.view.AutoClipBasicOrbitView;
import name.gano.worldwind.view.GroundStationToSatView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LaunchSitesLayerTest {
    static {
        System.setProperty("java.awt.headless", "true");
        WorldWind.setOfflineMode(true);
    }

    @Test
    public void testLayerNameAndGlobalMutePreservesPerWindowEnabledState(@TempDir Path tmp) {
        long now = 1_795_132_800_000L;
        LiveAwarenessService svc = LiveAwarenessService.createForTestWithLaunches(tmp,
                List.of(sampleLaunch("l1", "Alpha", "SLC-40", "CCSFS", 28.56, -80.58, now + 6L * 3600_000L)));
        try {
            LaunchSitesLayer layer = new LaunchSitesLayer(svc);
            assertEq("Upcoming Launches", layer.getName());
            assertEq(true, layer.isEnabled());

            layer.rebuildMarkers(now, 1.5e7);
            if (layer.getNumRenderables() == 0) {
                throw new AssertionError("Expected launch renderables when enabled and unmuted");
            }

            // Global mute hides markers without flipping per-window layer.isEnabled()
            svc.setGlobalMapOverlayEnabled(false);
            layer.rebuildMarkers(now, 1.5e7);
            assertEq(true, layer.isEnabled());
            assertEq(0, layer.getNumRenderables());

            // Unmuting restores markers on rebuild
            svc.setGlobalMapOverlayEnabled(true);
            layer.rebuildMarkers(now, 1.5e7);
            if (layer.getNumRenderables() == 0) {
                throw new AssertionError("Expected launch renderables after unmuting");
            }

            // Per-window disable hides markers, re-enabling restores them
            layer.setEnabled(false);
            assertEq(false, layer.isEnabled());
            assertEq(0, layer.getNumRenderables());

            layer.setEnabled(true);
            assertEq(true, layer.isEnabled());
            if (layer.getNumRenderables() == 0) {
                throw new AssertionError("Expected launch renderables after re-enabling layer");
            }
        } finally {
            svc.shutdown();
        }
    }

    @Test
    public void testAngularClusteringMergesCapeCanaveralAndKscGloballyAndSplitsZoomedIn(@TempDir Path tmp) {
        long now = 1_795_132_800_000L;
        LaunchRecord slc40 = sampleLaunch(
                "slc40", "Starlink 10-1", "SLC-40", "Cape Canaveral SFS", 28.5619, -80.5774, now + 8L * 3600_000L);
        LaunchRecord lc39a = sampleLaunch(
                "lc39a", "Crew-12", "LC-39A", "Kennedy Space Center", 28.6084, -80.6043, now + 48L * 3600_000L);

        LiveAwarenessService svc = LiveAwarenessService.createForTestWithLaunches(tmp, List.of(lc39a, slc40));
        try {
            LaunchSitesLayer layer = new LaunchSitesLayer(svc);

            // Global altitude (~15,000 km): CCSFS and KSC merge into 1 cluster colored by soonest (slc40)
            layer.rebuildMarkers(now, 1.5e7);
            List<PointPlacemark> globalPlacemarks = collectRenderables(layer, PointPlacemark.class);
            List<GlobeAnnotation> globalAnnotations = collectRenderables(layer, GlobeAnnotation.class);
            assertEq(1, globalPlacemarks.size());
            assertEq(1, globalAnnotations.size());
            assertEq("slc40", globalPlacemarks.get(0).getStringValue(LaunchSitesLayer.LAUNCH_ID_KEY));
            assertEq("slc40", globalAnnotations.get(0).getStringValue(LaunchSitesLayer.LAUNCH_ID_KEY));
            assertTrue("Cluster label should indicate count x2",
                    globalAnnotations.get(0).getText().contains("\u00D72"));

            // Zoomed-in altitude (~100 km): CCSFS and KSC split into 2 independent markers
            layer.rebuildMarkers(now, 1.0e5);
            List<PointPlacemark> zoomedPlacemarks = collectRenderables(layer, PointPlacemark.class);
            List<GlobeAnnotation> zoomedAnnotations = collectRenderables(layer, GlobeAnnotation.class);
            assertEq(2, zoomedPlacemarks.size());
            assertEq(2, zoomedAnnotations.size());
            assertEq("slc40", zoomedPlacemarks.get(0).getStringValue(LaunchSitesLayer.LAUNCH_ID_KEY));
            assertEq("lc39a", zoomedPlacemarks.get(1).getStringValue(LaunchSitesLayer.LAUNCH_ID_KEY));
        } finally {
            svc.shutdown();
        }
    }

    @Test
    public void testUrgencyColorsSelectedStateAndMjdConversion(@TempDir Path tmp) {
        long now = 1_795_132_800_000L;
        LaunchRecord imminent = sampleLaunch("imm", "Imminent", "Pad 1", "Site A", 28.5, -80.5, now + 6L * 3600_000L);
        LaunchRecord soon = sampleLaunch("soon", "Soon", "Pad 2", "Site B", 34.6, -120.6, now + 36L * 3600_000L);
        LaunchRecord upcoming = sampleLaunch("up", "Upcoming", "Pad 3", "Site C", 5.2, -52.7, now + 5L * 86_400_000L);

        LiveAwarenessService svc = LiveAwarenessService.createForTestWithLaunches(
                tmp, List.of(imminent, soon, upcoming));
        try {
            svc.selectLaunch("soon");
            LaunchSitesLayer layer = new LaunchSitesLayer(svc);
            layer.rebuildMarkers(now, 1.5e7);

            List<PointPlacemark> placemarks = collectRenderables(layer, PointPlacemark.class);
            assertEq(3, placemarks.size());

            // Selected marker stays always on top and carries urgency color via LaunchOverlay2DRenderer
            assertEq(LaunchOverlay2DRenderer.colorForUrgency(LaunchUrgency.IMMINENT_OR_IN_WINDOW),
                    placemarks.get(0).getValue("LAUNCH_URGENCY_COLOR"));
            assertEq(LaunchOverlay2DRenderer.colorForUrgency(LaunchUrgency.SOON_1_TO_3D),
                    placemarks.get(1).getValue("LAUNCH_URGENCY_COLOR"));
            assertEq(LaunchOverlay2DRenderer.colorForUrgency(LaunchUrgency.UPCOMING_3_TO_7D),
                    placemarks.get(2).getValue("LAUNCH_URGENCY_COLOR"));

            assertEq(false, placemarks.get(0).isAlwaysOnTop());
            assertEq(true, placemarks.get(1).isAlwaysOnTop());
            assertEq(false, placemarks.get(2).isAlwaysOnTop());

            assertEq(0L, LaunchSitesLayer.mjdToEpochMillis(40587.0));
            assertEq(86_400_000L, LaunchSitesLayer.mjdToEpochMillis(40588.0));
        } finally {
            svc.shutdown();
        }
    }

    @Test
    public void testSelectListenerLeftClickOnlyHoverTooltipAndIgnoresRightClickAndDrag(@TempDir Path tmp) throws Exception {
        long now = 1_795_132_800_000L;
        LaunchRecord l1 = sampleLaunch("l1", "Starlink 10-1", "SLC-40", "Cape Canaveral", 28.56, -80.58, now + 6L * 3600_000L);
        LiveAwarenessService svc = LiveAwarenessService.createForTestWithLaunches(tmp, List.of(l1));
        try {
            AtomicBoolean openUiFired = new AtomicBoolean(false);
            svc.addListener(new AwarenessStateListener() {
                @Override public void onAwarenessDataChanged() {}
                @Override public void onSelectionChanged(String selectedLaunchId) {}
                @Override public void onStatusMessage(String message, boolean isWarning) {}
                @Override public void onOpenUiRequested() { openUiFired.set(true); }
            });

            LaunchSitesLayer layer = new LaunchSitesLayer(svc);
            layer.rebuildMarkers(now, 1.5e7);
            PointPlacemark pm = collectRenderables(layer, PointPlacemark.class).get(0);

            PickedObject launchPick = new PickedObject(1, pm);
            launchPick.setParentLayer(layer);
            PickedObjectList launchPickList = new PickedObjectList();
            launchPickList.add(launchPick);

            AtomicInteger clickedCount = new AtomicInteger();
            SelectListener listener = LaunchSitesLayer.createSelectListener(svc, clickedCount::incrementAndGet);

            // Right-click and drag must never select or open UI or consume
            SelectEvent rightClick = new SelectEvent(layer, SelectEvent.RIGHT_CLICK, new Point(10, 10), launchPickList);
            listener.selected(rightClick);
            assertEq(null, svc.getSelectedLaunchId());
            assertEq(false, openUiFired.get());
            assertEq(0, clickedCount.get());

            SelectEvent drag = new SelectEvent(layer, SelectEvent.DRAG, new Point(10, 10), launchPickList);
            listener.selected(drag);
            assertEq(null, svc.getSelectedLaunchId());
            assertEq(false, openUiFired.get());
            assertEq(0, clickedCount.get());

            // Left click on a non-launch renderable (no LAUNCH_ID) must be ignored
            PointPlacemark unrelated = new PointPlacemark(Position.fromDegrees(0, 0, 0));
            PickedObject unrelatedPick = new PickedObject(2, unrelated);
            PickedObjectList unrelatedList = new PickedObjectList();
            unrelatedList.add(unrelatedPick);
            listener.selected(new SelectEvent(layer, SelectEvent.LEFT_CLICK, new Point(10, 10), unrelatedList));
            assertEq(null, svc.getSelectedLaunchId());
            assertEq(false, openUiFired.get());
            assertEq(0, clickedCount.get());

            // Hover / rollover updates tooltip on LaunchSitesLayer and clears when moving off
            listener.selected(new SelectEvent(layer, SelectEvent.HOVER, new Point(15, 20), launchPickList));
            String tip = layer.getActiveHoverTooltipText();
            assertTrue("Expected hover tooltip to mention mission name", tip != null && tip.contains("Starlink 10-1"));
            assertTrue("Hover text should be stored in AVKey.HOVER_TEXT",
                    pm.getStringValue(AVKey.HOVER_TEXT) != null);

            listener.selected(new SelectEvent(layer, SelectEvent.ROLLOVER, new Point(15, 20), unrelatedList));
            assertEq(null, layer.getActiveHoverTooltipText());

            // Left click on launch renderable selects launch, requests UI open, and runs callback
            listener.selected(new SelectEvent(layer, SelectEvent.LEFT_CLICK, new Point(10, 10), launchPickList));
            SwingUtilities.invokeAndWait(() -> { });
            assertEq("l1", svc.getSelectedLaunchId());
            assertEq(true, openUiFired.get());
            assertEq(1, clickedCount.get());
        } finally {
            svc.shutdown();
        }
    }

    @Test
    public void testThreeDViewPropPanelCheckboxAndCoordinatorCentering(@TempDir Path tmp) {
        long now = 1_795_132_800_000L;
        LiveAwarenessService svc = LiveAwarenessService.createForTestWithLaunches(tmp,
                List.of(sampleLaunch("l1", "Vandenberg Mission", "SLC-4E", "Vandenberg SFB", 34.63, -120.61, now + 4L * 3600_000L)));
        try {
            LayerList layers = new LayerList();
            LaunchSitesLayer launchLayer = new LaunchSitesLayer(null);
            layers.add(launchLayer);

            AutoClipBasicOrbitView orbitView = new AutoClipBasicOrbitView();
            orbitView.setGlobe(new Earth());
            Model model = (Model) Proxy.newProxyInstance(
                    Model.class.getClassLoader(),
                    new Class<?>[] { Model.class },
                    (proxy, method, args) -> {
                        if ("getLayers".equals(method.getName())) {
                            return layers;
                        }
                        if ("getGlobe".equals(method.getName())) {
                            return orbitView.getGlobe();
                        }
                        return defaultReturn(method.getReturnType());
                    });

            List<SelectListener> attachedListeners = new ArrayList<>();
            WorldWindow stubWwd = (WorldWindow) Proxy.newProxyInstance(
                    WorldWindow.class.getClassLoader(),
                    new Class<?>[] { WorldWindow.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getView" -> orbitView;
                        case "getModel" -> model;
                        case "addSelectListener" -> {
                            attachedListeners.add((SelectListener) args[0]);
                            yield null;
                        }
                        case "removeSelectListener" -> {
                            attachedListeners.remove((SelectListener) args[0]);
                            yield null;
                        }
                        default -> defaultReturn(method.getReturnType());
                    });

            LaunchSitesLayer.ViewCoordinator coordinator =
                    new LaunchSitesLayer.ViewCoordinator(stubWwd, launchLayer, now);

            StubEarthComponent stubComp = new StubEarthComponent(stubWwd, layers, coordinator);
            stubComp.setAwarenessService(svc);
            assertEq(1, attachedListeners.size());
            assertTrue("Coordinator should populate markers after attaching service",
                    launchLayer.getNumRenderables() > 0);

            // Reattach helper (used by setupView when InputHandler changes) keeps exactly 1 listener
            coordinator.reattachSelectListener();
            assertEq(1, attachedListeners.size());

            // Centering in OrbitView mode invokes pan without throwing; GS-to-Sat mode is ignored cleanly
            stubComp.centerOnLatLon(34.63, -120.61);

            // JThreeDViewPropPanel binds "Show Upcoming Launches" checkbox to J3DEarthComponent
            JThreeDViewPropPanel propPanel = new JThreeDViewPropPanel(null, stubComp, stubWwd);
            JCheckBox launchCheck = findCheckBox(propPanel, "Show Upcoming Launches");
            assertTrue("Expected 'Show Upcoming Launches' checkbox on JThreeDViewPropPanel", launchCheck != null);
            assertEq(true, launchCheck.isSelected());

            launchCheck.setSelected(false);
            AbstractButton applyBtn = findButton(propPanel, "Apply");
            assertTrue("Expected Apply button on JThreeDViewPropPanel", applyBtn != null);
            applyBtn.doClick();
            assertEq(false, stubComp.isShowUpcomingLaunches());
            assertEq(false, launchLayer.isEnabled());

            // Verify legacy J3DEarthComponent methods behave properly when toggled back on
            stubComp.setShowUpcomingLaunches(true);
            assertEq(true, stubComp.isShowUpcomingLaunches());
            assertEq(true, launchLayer.isEnabled());
            assertTrue("Expected markers restored when Show Upcoming Launches re-enabled",
                    launchLayer.getNumRenderables() > 0);

            // GS-to-Sat view mode should safely no-op centerOnLatLon
            stubComp.setGsToSatViewMode(true);
            stubComp.centerOnLatLon(28.5, -80.5);
        } finally {
            svc.shutdown();
        }
    }

    @Test
    public void testAltitudeReclusterMjdRefreshAndAntimeridianClustering(@TempDir Path tmp) {
        long now = LaunchSitesLayer.mjdToEpochMillis(61000.0);
        // Two launches straddling the antimeridian (+179.9 and -179.9 -> 0.2*cos(10) deg apart)
        LaunchRecord east = sampleLaunch("east", "East Antimeridian", "Pad E", "Site E", 10.0, 179.9, now + 4L * 3600_000L);
        LaunchRecord west = sampleLaunch("west", "West Antimeridian", "Pad W", "Site W", 10.0, -179.9, now + 8L * 3600_000L);
        // Launch at CCSFS & KSC for altitude re-cluster test
        LaunchRecord slc40 = sampleLaunch("slc40", "SLC-40 Shot", "SLC-40", "CCSFS", 28.5619, -80.5774, now + 6L * 3600_000L);
        LaunchRecord lc39a = sampleLaunch("lc39a", "LC-39A Shot", "LC-39A", "KSC", 28.6084, -80.6043, now + 10L * 3600_000L);

        LiveAwarenessService svc = LiveAwarenessService.createForTestWithLaunches(
                tmp, List.of(east, west, slc40, lc39a));
        try {
            LaunchSitesLayer layer = new LaunchSitesLayer(svc);
            layer.rebuildMarkers(now, 1.5e7);
            List<PointPlacemark> globalPlacemarks = collectRenderables(layer, PointPlacemark.class);
            // Antimeridian pair clusters together (~0.2 deg <= 2.5 deg), CCSFS+KSC cluster together -> 2 clusters total
            assertEq(2, globalPlacemarks.size());
            double lonAntimeridian = globalPlacemarks.get(0).getPosition().getLongitude().getDegrees();
            assertTrue("Antimeridian centroid should remain near +-180, got " + lonAntimeridian,
                    Math.abs(lonAntimeridian) > 179.0);

            // Small altitude change at constant scale should NOT trigger unnecessary rebuild
            assertEq(false, layer.rebuildIfAltitudeChanged(now, 1.48e7));
            // Zooming down to 100 km splits both pairs into 4 markers
            assertEq(true, layer.rebuildIfAltitudeChanged(now, 1.0e5));
            assertEq(4, collectRenderables(layer, PointPlacemark.class).size());

            // Coordinator refreshForMjd updates countdowns and expires launches past grace buffer
            LaunchSitesLayer.ViewCoordinator coord = new LaunchSitesLayer.ViewCoordinator(
                    (WorldWindow) Proxy.newProxyInstance(
                            WorldWindow.class.getClassLoader(),
                            new Class<?>[] { WorldWindow.class },
                            (proxy, method, args) -> defaultReturn(method.getReturnType())),
                    layer,
                    now);
            coord.setAwarenessService(svc);
            coord.refreshForMjd(61000.0);
            int beforeCount = collectRenderables(layer, PointPlacemark.class).size();
            assertTrue("Expected markers at initial MJD", beforeCount > 0);

            // Advance MJD by 10 days -> all launches expired -> markers clear
            coord.refreshForMjd(61010.0);
            assertEq(0, layer.getNumRenderables());
        } finally {
            svc.shutdown();
        }
    }

    @Test
    public void testViewCoordinatorDisposeAndWeakListenerGcCleanup(@TempDir Path tmp) throws Exception {
        long now = 1_795_132_800_000L;
        LaunchRecord launch = sampleLaunch("l1", "Alpha", "SLC-40", "CCSFS", 28.56, -80.58, now + 6L * 3600_000L);
        LiveAwarenessService svc = LiveAwarenessService.createForTestWithLaunches(tmp, List.of(launch));
        try {
            List<SelectListener> attached = new ArrayList<>();
            WorldWindow stubWwd = (WorldWindow) Proxy.newProxyInstance(
                    WorldWindow.class.getClassLoader(),
                    new Class<?>[] { WorldWindow.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "addSelectListener" -> {
                            attached.add((SelectListener) args[0]);
                            yield null;
                        }
                        case "removeSelectListener" -> {
                            attached.remove((SelectListener) args[0]);
                            yield null;
                        }
                        default -> defaultReturn(method.getReturnType());
                    });

            // 1. Explicit dispose() detaches listener & clears renderables immediately
            CountingLayer layer1 = new CountingLayer(null);
            LaunchSitesLayer.ViewCoordinator coord1 = new LaunchSitesLayer.ViewCoordinator(stubWwd, layer1, now);
            coord1.setAwarenessService(svc);
            assertEq(1, attached.size());
            int countBeforeDispose = layer1.applyCount.get();

            coord1.dispose();
            assertEq(0, attached.size());
            assertEq(null, coord1.getAwarenessService());
            assertEq(0, layer1.getNumRenderables());
            int countAfterDispose = layer1.applyCount.get();
            svc.setGlobalMapOverlayEnabled(false);
            SwingUtilities.invokeAndWait(() -> { });
            assertEq(countAfterDispose, layer1.applyCount.get());
            assertTrue("Expected dispose/initial attaches to build cleanly", countBeforeDispose >= 1);

            // 2. WeakReference auto-cleanup when ViewCoordinator is GC'd without explicit dispose()
            svc.setGlobalMapOverlayEnabled(true);
            CountingLayer layer2 = new CountingLayer(null);
            LaunchSitesLayer.ViewCoordinator coord2 = new LaunchSitesLayer.ViewCoordinator(stubWwd, layer2, now);
            coord2.setAwarenessService(svc);
            int afterAttach = layer2.applyCount.get();

            svc.selectLaunch("l1");
            SwingUtilities.invokeAndWait(() -> { });
            assertTrue("Active coordinator should react to service callbacks", layer2.applyCount.get() > afterAttach);

            WeakReference<LaunchSitesLayer.ViewCoordinator> coordRef = new WeakReference<>(coord2);
            coord2 = null;
            for (int i = 0; i < 60 && coordRef.get() != null; i++) {
                System.gc();
                Thread.sleep(20);
            }
            assertEq(null, coordRef.get());

            int countAtGc = layer2.applyCount.get();
            svc.setGlobalMapOverlayEnabled(false);
            svc.selectLaunch(null);
            SwingUtilities.invokeAndWait(() -> { });
            assertEq(countAtGc, layer2.applyCount.get());
        } finally {
            svc.shutdown();
        }
    }

    @Test
    public void testRefreshForMjdRespectsAltitudeHysteresisAndSecondaryHoverCountdown(@TempDir Path tmp) {
        long t0 = LaunchSitesLayer.mjdToEpochMillis(61000.0);
        // Primary launch stays "In Window" across t0 and t0 + 60s so compactLabel is unchanged
        LaunchRecord primary = LaunchRecord.builder()
                .id("p1")
                .missionName("Primary Window")
                .provider("SpaceX")
                .vehicle("Falcon 9")
                .netEpochMillis(t0 - 60_000L)
                .windowStartMillis(t0 - 60_000L)
                .windowEndMillis(t0 + 3600_000L)
                .status("Go")
                .orbital(true)
                .padName("SLC-40")
                .spaceportName("Cape Canaveral")
                .country("USA")
                .latDeg(28.5619)
                .lonDeg(-80.5774)
                .fixedLandPad(true)
                .build();
        // Secondary launch at KSC clusters globally at 1.5e7m and changes countdown T-06h 00m -> T-05h 59m
        LaunchRecord secondary = sampleLaunch(
                "s2", "Secondary Shot", "LC-39A", "Cape Canaveral", 28.6084, -80.6043, t0 + 6L * 3600_000L);

        LiveAwarenessService svc = LiveAwarenessService.createForTestWithLaunches(tmp, List.of(primary, secondary));
        try {
            AutoClipBasicOrbitView orbitView = new AutoClipBasicOrbitView();
            orbitView.setZoom(1.5e7);
            WorldWindow stubWwd = (WorldWindow) Proxy.newProxyInstance(
                    WorldWindow.class.getClassLoader(),
                    new Class<?>[] { WorldWindow.class },
                    (proxy, method, args) -> "getView".equals(method.getName()) ? orbitView : defaultReturn(method.getReturnType()));

            CountingLayer layer = new CountingLayer(null);
            LaunchSitesLayer.ViewCoordinator coord = new LaunchSitesLayer.ViewCoordinator(stubWwd, layer, t0);
            coord.setAwarenessService(svc);
            assertEq(1, layer.applyCount.get());
            assertEq(1, collectRenderables(layer, PointPlacemark.class).size());

            // Sub-12% eye-altitude jitter must NOT force marker rebuilds during refreshForMjd
            orbitView.setZoom(1.45e7);
            coord.refreshForMjd(61000.0);
            orbitView.setZoom(1.48e7);
            coord.refreshForMjd(61000.0);
            assertEq(1, layer.applyCount.get());

            // Advancing time by 60s leaves primary compactLabel ("In Window") identical,
            // but secondary hover countdown shifts T-06h 00m -> T-05h 59m, so ClusterSignature must trigger rebuild
            coord.refreshForMjd(61000.0 + (60_000.0 / 86_400_000.0));
            assertEq(2, layer.applyCount.get());
            String updatedHover = collectRenderables(layer, PointPlacemark.class).get(0).getStringValue(AVKey.HOVER_TEXT);
            assertTrue("Expected updated secondary countdown T-05h 59m in hover text, got: " + updatedHover,
                    updatedHover != null && updatedHover.contains("T-05h 59m"));

            // Zooming in >= 12% (1.5e7 -> 1.0e5) triggers rebuild and splits the two pads
            orbitView.setZoom(1.0e5);
            coord.refreshForMjd(61000.0 + (60_000.0 / 86_400_000.0));
            assertEq(3, layer.applyCount.get());
            assertEq(2, collectRenderables(layer, PointPlacemark.class).size());
        } finally {
            svc.shutdown();
        }
    }

    private static final class CountingLayer extends LaunchSitesLayer {
        final AtomicInteger applyCount = new AtomicInteger();

        CountingLayer(LiveAwarenessService svc) {
            super(svc);
        }

        @Override
        void applyPreparedClusters(List<PreparedCluster> clusters, long simEpochMillis, double safeEyeAlt) {
            applyCount.incrementAndGet();
            super.applyPreparedClusters(clusters, simEpochMillis, safeEyeAlt);
        }
    }

    private static final class StubEarthComponent implements J3DEarthComponent {
        private final WorldWindow wwd;
        private final LayerList layers;
        private final LaunchSitesLayer.ViewCoordinator coordinator;
        private boolean viewModeEci = true;
        private boolean modelViewMode;
        private boolean gsToSatViewMode;
        private final ECIRadialGrid radialGrid = new ECIRadialGrid();

        StubEarthComponent(WorldWindow wwd, LayerList layers, LaunchSitesLayer.ViewCoordinator coordinator) {
            this.wwd = wwd;
            this.layers = layers;
            this.coordinator = coordinator;
        }

        @Override public void setAwarenessService(LiveAwarenessService service) { coordinator.setAwarenessService(service); }
        @Override public void centerOnLatLon(double latDeg, double lonDeg) {
            if (!modelViewMode && !gsToSatViewMode) {
                LaunchSitesLayer.centerOrbitViewOnLatLon(wwd, latDeg, lonDeg);
            }
        }
        @Override public boolean isShowUpcomingLaunches() { return coordinator.isShowUpcomingLaunches(); }
        @Override public void setShowUpcomingLaunches(boolean enabled) { coordinator.setShowUpcomingLaunches(enabled); }
        @Override public void refreshLaunchOverlay(long simEpochMillis) { coordinator.refreshLaunchOverlay(simEpochMillis); }
        @Override public void dispose() { coordinator.dispose(); }

        @Override public boolean getTerrainProfileEnabled() { return false; }
        @Override public String getTerrainProfileSat() { return ""; }
        @Override public double getTerrainProfileLongSpan() { return 10.0; }
        @Override public void setTerrainProfileSat(String terrainProfileSat) {}
        @Override public void setTerrainProfileLongSpan(double terrainProfileLongSpan) {}
        @Override public void setTerrainProfileEnabled(boolean enabled) {}
        @Override public boolean isViewModeECI() { return viewModeEci; }
        @Override public void setViewModeECI(boolean viewModeECI) { this.viewModeEci = viewModeECI; }
        @Override public JSatTrak getApp() { return null; }
        @Override public WorldWindow getWwd() { return wwd; }
        @Override public String getDialogTitle() { return "3D Test"; }
        @Override public int getWwdWidth() { return 600; }
        @Override public int getWwdHeight() { return 400; }
        @Override public Point getWwdLocationOnScreen() { return new Point(0, 0); }
        @Override public LayerList getLayerList() { return layers; }
        @Override public void setOrbitFarClipDistance(double clipDist) {}
        @Override public void setOrbitNearClipDistance(double clipDist) {}
        @Override public double getOrbitFarClipDistance() { return -1; }
        @Override public double getOrbitNearClipDistance() { return -1; }
        @Override public boolean isModelViewMode() { return modelViewMode; }
        @Override public void setModelViewMode(boolean modelViewMode) { this.modelViewMode = modelViewMode; }
        @Override public String getModelViewString() { return ""; }
        @Override public void setModelViewString(String modelViewString) {}
        @Override public double getModelViewNearClip() { return 10000; }
        @Override public void setModelViewNearClip(double modelViewNearClip) {}
        @Override public double getModelViewFarClip() { return 5e7; }
        @Override public void setModelViewFarClip(double modelViewFarClip) {}
        @Override public void resetWWJdisplay() {}
        @Override public boolean isSmoothViewChanges() { return true; }
        @Override public void setSmoothViewChanges(boolean smoothViewChanges) {}
        @Override public void setSunShadingOn(boolean useSunShading) {}
        @Override public boolean isSunShadingOn() { return false; }
        @Override public void setAmbientLightLevel(int level) {}
        @Override public int getAmbientLightLevel() { return 50; }
        @Override public boolean isLensFlareEnabled() { return false; }
        @Override public void setLensFlare(boolean enabled) {}
        @Override public EcefTimeDepRenderableLayer getEcefTimeDepRenderableLayer() { return null; }
        @Override public ECIRadialGrid getEciRadialGrid() { return radialGrid; }
        @Override public boolean isGsToSatViewMode() { return gsToSatViewMode; }
        @Override public void setGsToSatViewMode(boolean gsToSatViewMode) { this.gsToSatViewMode = gsToSatViewMode; }
        @Override public String getGsToSatGroundStation() { return ""; }
        @Override public void setGsToSatGroundStation(String gsName) {}
        @Override public String getGsToSatSatellite() { return ""; }
        @Override public void setGsToSatSatellite(String satName) {}
        @Override public boolean isGsToSatClampToHorizon() { return true; }
        @Override public void setGsToSatClampToHorizon(boolean clampToHorizon) {}
        @Override public boolean isGsToSatShowHud() { return true; }
        @Override public void setGsToSatShowHud(boolean showHud) {}
    }

    private static <T extends Renderable> List<T> collectRenderables(LaunchSitesLayer layer, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Renderable r : layer.getRenderables()) {
            if (type.isInstance(r)) {
                result.add(type.cast(r));
            }
        }
        return result;
    }

    private static JCheckBox findCheckBox(Container root, String text) {
        for (Component comp : root.getComponents()) {
            if (comp instanceof JCheckBox cb && text.equals(cb.getText())) {
                return cb;
            }
            if (comp instanceof Container child) {
                JCheckBox found = findCheckBox(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static AbstractButton findButton(Container root, String text) {
        for (Component comp : root.getComponents()) {
            if (comp instanceof AbstractButton btn && text.equals(btn.getText())) {
                return btn;
            }
            if (comp instanceof Container child) {
                AbstractButton found = findButton(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Object defaultReturn(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        return null;
    }

    private static LaunchRecord sampleLaunch(
            String id,
            String missionName,
            String padName,
            String spaceportName,
            double latDeg,
            double lonDeg,
            long netEpochMillis) {
        return LaunchRecord.builder()
                .id(id)
                .missionName(missionName)
                .provider("SpaceX")
                .vehicle("Falcon 9")
                .targetOrbit("LEO")
                .netEpochMillis(netEpochMillis)
                .windowStartMillis(netEpochMillis)
                .windowEndMillis(netEpochMillis + 3600_000L)
                .status("Go")
                .orbital(true)
                .padName(padName)
                .spaceportName(spaceportName)
                .country("USA")
                .latDeg(latDeg)
                .lonDeg(lonDeg)
                .fixedLandPad(true)
                .build();
    }

    private static void assertEq(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected [" + expected + "] but got [" + actual + "]");
        }
    }

    private static void assertTrue(String message, boolean condition) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

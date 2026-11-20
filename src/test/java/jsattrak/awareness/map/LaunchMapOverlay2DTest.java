package jsattrak.awareness.map;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JInternalFrame;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import jsattrak.awareness.AwarenessStateListener;
import jsattrak.awareness.LaunchRecord;
import jsattrak.awareness.LaunchUrgency;
import jsattrak.awareness.LiveAwarenessService;
import jsattrak.gui.J2DEarthPanel;
import jsattrak.gui.J2DPolarEarthPanel;
import jsattrak.gui.J2dPolarEarthLabel;
import jsattrak.gui.TwoDPolarViewPropertiesPanel;
import jsattrak.gui.TwoDViewPropertiesPanel;
import jsattrak.utilities.J2DEarthPanelSave;
import jsattrak.utilities.J2DPolarEarthPanelSave;
import name.gano.astro.bodies.Sun;
import name.gano.astro.time.Time;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LaunchMapOverlay2DTest {
    static {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    public void testCapeCanaveralAndKscClusterAtGlobalZoomAndSplitZoomedIn() {
        long now = 1_795_132_800_000L;
        LaunchRecord slc40 = launch("l1", "Starlink 10-1", "SLC-40", 28.5619, -80.5774, now + 8L * 3600_000L);
        LaunchRecord lc39a = launch("l2", "Crew-12", "LC-39A", 28.6084, -80.6043, now + 48L * 3600_000L);

        // Global scale: 1 deg = 2 px -> distance < 1 px -> merges into 1 cluster colored by soonest (l1)
        List<LaunchMapCluster> global = LaunchClusterer.clusterByScreenDistance(
                List.of(lc39a, slc40), now, null,
                r -> new int[] { (int) ((r.getLonDeg() + 180) * 2), (int) ((90 - r.getLatDeg()) * 2) }, 22.0);
        assertEq(1, global.size());
        assertEq(2, global.get(0).getLaunches().size());
        assertEq("l1", global.get(0).getSoonest().getId());
        assertEq(LaunchUrgency.IMMINENT_OR_IN_WINDOW, global.get(0).getSoonestUrgency());

        // High zoom: 1 deg = 1000 px -> ~54 px apart -> splits into 2 markers
        List<LaunchMapCluster> zoomed = LaunchClusterer.clusterByScreenDistance(
                List.of(lc39a, slc40), now, "l2",
                r -> new int[] { (int) ((r.getLonDeg() + 180) * 1000), (int) ((90 - r.getLatDeg()) * 1000) }, 22.0);
        assertEq(2, zoomed.size());
        assertEq(false, zoomed.get(0).isSelected());
        assertEq(true, zoomed.get(1).isSelected());
    }

    @Test
    public void testOverlayRendererColorsHitTestingSelectionAndTooltipHtml() {
        assertEq(new Color(0xE5, 0x39, 0x35), LaunchOverlay2DRenderer.colorForUrgency(LaunchUrgency.IMMINENT_OR_IN_WINDOW));
        assertEq(new Color(0xFB, 0x8C, 0x00), LaunchOverlay2DRenderer.colorForUrgency(LaunchUrgency.SOON_1_TO_3D));
        assertEq(new Color(0x1E, 0x88, 0xE5), LaunchOverlay2DRenderer.colorForUrgency(LaunchUrgency.UPCOMING_3_TO_7D));

        long now = 1_795_132_800_000L;
        LaunchRecord l1 = launch("u1", "Mission <Alpha> & Beta", "SLC-40", 28.56, -80.58, now + 6L * 3600_000L);
        LaunchRecord l2 = launch("u2", "Mission Two", "LC-39A", 28.61, -80.60, now + 50L * 3600_000L);
        LaunchRecord recentAtOtherPad = launch("r1", "Past Launch", "Vandenberg SLC-4E", 34.63, -120.61, now - 6L * 3600_000L);

        LaunchOverlay2DRenderer renderer = new LaunchOverlay2DRenderer();
        BufferedImage img = new BufferedImage(400, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            List<LaunchMapCluster> clusters = renderer.paint(
                    g2,
                    List.of(l1, l2),
                    recentAtOtherPad,
                    now,
                    "r1",
                    r -> new int[] { r.getId().startsWith("u") ? 200 : 80, 100 });
            assertEq(2, clusters.size());
        } finally {
            g2.dispose();
        }

        LaunchMapCluster capeHit = renderer.findClusterAt(205, 104, 14.0);
        assertTrue("Expected hit near Cape cluster", capeHit != null);
        assertEq(2, capeHit.getLaunches().size());
        assertEq("u1", capeHit.getSoonest().getId());

        LaunchMapCluster recentHit = renderer.findClusterAt(80, 100, 14.0);
        assertTrue("Expected hit near selected recent launch", recentHit != null);
        assertEq(true, recentHit.isSelected());
        assertEq(LaunchUrgency.EXPIRED, recentHit.getSoonestUrgency());

        assertEq(null, renderer.findClusterAt(350, 180, 14.0));

        String html = LaunchOverlay2DRenderer.buildTooltipHtml(capeHit, now);
        assertTrue("Tooltip should be HTML", html != null && html.startsWith("<html>") && html.endsWith("</html>"));
        assertTrue("Tooltip should escape < and &", html.contains("&lt;Alpha&gt;") && html.contains("&amp;"));
        assertTrue("Tooltip should include cluster count", html.contains("2"));

        renderer.clear();
        assertEq(null, renderer.findClusterAt(200, 100, 14.0));

        // Selected recent launch coinciding with an upcoming cluster highlights it without inflating count
        LaunchRecord recentSamePad = launch("r2", "Past Cape", "SLC-40", 28.56, -80.58, now - 4L * 3600_000L);
        try (var gWrap = new AutoCloseableGraphics(img.createGraphics())) {
            List<LaunchMapCluster> merged = renderer.paint(
                    gWrap.g2,
                    List.of(l1, l2),
                    recentSamePad,
                    now,
                    "r2",
                    r -> new int[] { 200, 100 });
            assertEq(1, merged.size());
            assertEq(2, merged.get(0).getLaunches().size());
            assertEq(LaunchUrgency.IMMINENT_OR_IN_WINDOW, merged.get(0).getSoonestUrgency());
            assertEq(true, merged.get(0).isSelected());
        }
    }

    private static final class AutoCloseableGraphics implements AutoCloseable {
        final Graphics2D g2;
        AutoCloseableGraphics(Graphics2D g2) { this.g2 = g2; }
        @Override public void close() { g2.dispose(); }
    }

    @Test
    public void testLegacyJstNullBooleanDefaultsToShowUpcomingLaunchesTrue() {
        J2DEarthPanel p2d = new J2DEarthPanel();
        assertEq(true, p2d.isShowUpcomingLaunches());
        J2DEarthPanelSave save2d = new J2DEarthPanelSave(p2d, 0, 0, new Dimension(600, 300));
        // Simulate legacy XML where showUpcomingLaunches was absent (null)
        save2d.setShowUpcomingLaunches(null);
        J2DEarthPanel restored2d = new J2DEarthPanel();
        restored2d.setShowUpcomingLaunches(false);
        save2d.copySettings2PanelAndFrame(restored2d, new JInternalFrame());
        assertEq(true, restored2d.isShowUpcomingLaunches());

        // Explicit false must persist and restore as false
        p2d.setShowUpcomingLaunches(false);
        J2DEarthPanelSave save2dFalse = new J2DEarthPanelSave(p2d, 0, 0, new Dimension(600, 300));
        assertEq(Boolean.FALSE, save2dFalse.getShowUpcomingLaunches());
        J2DEarthPanel restored2dFalse = new J2DEarthPanel();
        save2dFalse.copySettings2PanelAndFrame(restored2dFalse, new JInternalFrame());
        assertEq(false, restored2dFalse.isShowUpcomingLaunches());

        J2DPolarEarthPanel pPolar = new J2DPolarEarthPanel();
        assertEq(true, pPolar.isShowUpcomingLaunches());
        J2DPolarEarthPanelSave savePolar = new J2DPolarEarthPanelSave(pPolar, 0, 0, new Dimension(600, 400));
        savePolar.setShowUpcomingLaunches(null);
        J2DPolarEarthPanel restoredPolar = new J2DPolarEarthPanel();
        restoredPolar.setShowUpcomingLaunches(false);
        savePolar.copySettings2PanelAndFrame(restoredPolar, new JInternalFrame());
        assertEq(true, restoredPolar.isShowUpcomingLaunches());

        pPolar.setShowUpcomingLaunches(false);
        J2DPolarEarthPanelSave savePolarFalse = new J2DPolarEarthPanelSave(pPolar, 0, 0, new Dimension(600, 400));
        assertEq(Boolean.FALSE, savePolarFalse.getShowUpcomingLaunches());
        J2DPolarEarthPanel restoredPolarFalse = new J2DPolarEarthPanel();
        savePolarFalse.copySettings2PanelAndFrame(restoredPolarFalse, new JInternalFrame());
        assertEq(false, restoredPolarFalse.isShowUpcomingLaunches());
    }

    @Test
    public void testOffScreenRenderWithOverlayOn2DAndPolarAndDualPoleAndGlobalMute(@TempDir Path tmp) {
        long now = 1_795_132_800_000L;
        Time time = new Time();
        time.setTimeInMillis(now);
        Sun sun = new Sun(time.getMJD());

        LaunchRecord northLaunch = launch("n1", "North Mission", "Kiruna", 67.89, 21.10, now + 8L * 3600_000L);
        LaunchRecord southLaunch = launch("s1", "South Mission", "Mahia", -39.26, 177.86, now + 36L * 3600_000L);
        LiveAwarenessService svc = LiveAwarenessService.createForTestWithLaunches(tmp, List.of(northLaunch, southLaunch));
        try {
            // 2D Equirectangular panel offscreen render + overlay hit
            J2DEarthPanel panel2d = new J2DEarthPanel(
                    new java.util.Hashtable<>(), new java.util.Hashtable<>(),
                    null, null, null, time, sun, null);
            panel2d.setAwarenessService(svc);
            panel2d.setSize(600, 300);
            panel2d.getImageMap().setSize(600, 300);
            panel2d.rescaleAndSetBackgroundImage();
            BufferedImage img2d = panel2d.offScreenRender();
            assertTrue("2D offscreen render failed", img2d != null && img2d.getWidth() == 600 && img2d.getHeight() == 300);

            int[] north2dXY = panel2d.getImageMap().findXYfromLL(
                    northLaunch.getLatDeg(), northLaunch.getLonDeg(),
                    600, 300,
                    panel2d.getImageMap().getImageWidth(), panel2d.getImageMap().getImageHeight());
            LaunchMapCluster hit2d = panel2d.getOverlayRenderer().findClusterAt(north2dXY[0], north2dXY[1], 14.0);
            assertTrue("2D overlay should render and hit North launch", hit2d != null && "n1".equals(hit2d.getSoonest().getId()));

            // Global mute hides markers without flipping per-window flag
            svc.setGlobalMapOverlayEnabled(false);
            panel2d.offScreenRender();
            assertEq(true, panel2d.isShowUpcomingLaunches());
            assertEq(null, panel2d.getOverlayRenderer().findClusterAt(north2dXY[0], north2dXY[1], 14.0));

            // Re-enable global mute, test per-window toggle OFF
            svc.setGlobalMapOverlayEnabled(true);
            panel2d.setShowUpcomingLaunches(false);
            panel2d.offScreenRender();
            assertEq(null, panel2d.getOverlayRenderer().findClusterAt(north2dXY[0], north2dXY[1], 14.0));

            // Polar panel: North mode filters out Southern Hemisphere launch, Dual mode hits both
            J2DPolarEarthPanel polar = new J2DPolarEarthPanel(
                    new java.util.Hashtable<>(), new java.util.Hashtable<>(),
                    null, null, null, time, sun, null);
            polar.setAwarenessService(svc);
            polar.setSize(600, 400);
            polar.getPolarLabel().setSize(600, 400);

            polar.setPolarMode(J2dPolarEarthLabel.MODE_NORTH);
            BufferedImage imgNorth = polar.offScreenRender();
            assertTrue("Polar North offscreen render failed", imgNorth != null && imgNorth.getWidth() == 600);
            assertEq(1, polar.getOverlayRenderer().getLastPaintedClusters().size());
            int[][] northDisc = polar.getPolarLabel().computeDiscGeometries(600, 400);
            int[] northPolarXY = J2dPolarEarthLabel.latLonToPolarXY(
                    northLaunch.getLatDeg(), northLaunch.getLonDeg(),
                    +1, northDisc[0][1], northDisc[0][2], northDisc[0][3],
                    polar.getPolarLabel().getMaxAngularRadiusDeg());
            assertTrue("North mode should hit northern launch",
                    polar.getOverlayRenderer().findClusterAt(northPolarXY[0], northPolarXY[1], 14.0) != null);

            polar.setPolarMode(J2dPolarEarthLabel.MODE_DUAL);
            BufferedImage imgDual = polar.offScreenRender();
            assertTrue("Polar Dual offscreen render failed", imgDual != null && imgDual.getWidth() == 600);
            assertEq(2, polar.getOverlayRenderer().getLastPaintedClusters().size());
            int[][] dualDiscs = polar.getPolarLabel().computeDiscGeometries(600, 400);
            int[] dualNorthXY = J2dPolarEarthLabel.latLonToPolarXY(
                    northLaunch.getLatDeg(), northLaunch.getLonDeg(),
                    +1, dualDiscs[0][1], dualDiscs[0][2], dualDiscs[0][3],
                    polar.getPolarLabel().getMaxAngularRadiusDeg());
            int[] dualSouthXY = J2dPolarEarthLabel.latLonToPolarXY(
                    southLaunch.getLatDeg(), southLaunch.getLonDeg(),
                    -1, dualDiscs[1][1], dualDiscs[1][2], dualDiscs[1][3],
                    polar.getPolarLabel().getMaxAngularRadiusDeg());
            assertTrue("Dual mode should hit North disc launch",
                    polar.getOverlayRenderer().findClusterAt(dualNorthXY[0], dualNorthXY[1], 14.0) != null);
            assertTrue("Dual mode should hit South disc launch",
                    polar.getOverlayRenderer().findClusterAt(dualSouthXY[0], dualSouthXY[1], 14.0) != null);
        } finally {
            svc.shutdown();
        }
    }

    @Test
    public void testCenterOnLatLonClickSelectionAndPopupAndPropertiesSync(@TempDir Path tmp) throws Exception {
        long now = 1_795_132_800_000L;
        Time time = new Time();
        time.setTimeInMillis(now);
        Sun sun = new Sun(time.getMJD());

        LaunchRecord cape = launch("c1", "Cape Flight", "SLC-40", 28.5619, -80.5774, now + 4L * 3600_000L);
        LaunchRecord mahia = launch("m1", "NZ Flight", "Mahia", -39.26, 177.86, now + 12L * 3600_000L);
        LiveAwarenessService svc = LiveAwarenessService.createForTestWithLaunches(tmp, List.of(cape, mahia));

        AtomicReference<String> selectedIdRef = new AtomicReference<>();
        AtomicBoolean openUiRequested = new AtomicBoolean(false);
        svc.addListener(new AwarenessStateListener() {
            @Override
            public void onAwarenessDataChanged() {
            }

            @Override
            public void onSelectionChanged(String selectedLaunchId) {
                selectedIdRef.set(selectedLaunchId);
            }

            @Override
            public void onStatusMessage(String message, boolean isWarning) {
            }

            @Override
            public void onOpenUiRequested() {
                openUiRequested.set(true);
            }
        });

        try {
            // 1. 2D Panel centering & click-to-select + popup Overlays menu + Properties panel
            J2DEarthPanel panel2d = new J2DEarthPanel(
                    new java.util.Hashtable<>(), new java.util.Hashtable<>(),
                    null, null, null, time, sun, null);
            panel2d.setAwarenessService(svc);
            panel2d.setSize(600, 300);
            panel2d.getImageMap().setSize(600, 300);
            panel2d.rescaleAndSetBackgroundImage();

            panel2d.centerOnLatLon(28.5619, -80.5774);
            assertTrue("2D centerOnLatLon should ensure zoom >= 2.0", panel2d.getZoomFactor() >= 2.0);
            assertTrue("2D centerLat should track target", Math.abs(panel2d.getCenterLat() - 28.5619) < 0.05);
            assertTrue("2D centerLong should track target", Math.abs(panel2d.getCenterLong() - (-80.5774)) < 0.05);

            panel2d.offScreenRender();
            int[] cape2dXY = panel2d.getImageMap().findXYfromLL(
                    cape.getLatDeg(), cape.getLonDeg(),
                    600, 300,
                    panel2d.getImageMap().getImageWidth(), panel2d.getImageMap().getImageHeight());
            MouseEvent hover2d = new MouseEvent(
                    panel2d, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(),
                    0, cape2dXY[0], cape2dXY[1], 0, false);
            for (var mml : panel2d.getMouseMotionListeners()) {
                mml.mouseMoved(hover2d);
            }
            assertTrue("2D hover tooltip should show launch details",
                    panel2d.getToolTipText() != null && panel2d.getToolTipText().contains("Cape Flight"));
            MouseEvent miss2d = new MouseEvent(
                    panel2d, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(),
                    0, 5, 5, 0, false);
            for (var mml : panel2d.getMouseMotionListeners()) {
                mml.mouseMoved(miss2d);
            }
            assertEq(null, panel2d.getToolTipText());

            MouseEvent click2d = new MouseEvent(
                    panel2d, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                    0, cape2dXY[0], cape2dXY[1], 1, false, MouseEvent.BUTTON1);
            for (var ml : panel2d.getMouseListeners()) {
                ml.mouseClicked(click2d);
            }
            SwingUtilities.invokeAndWait(() -> { });
            assertEq("c1", selectedIdRef.get());
            assertEq(true, openUiRequested.get());

            JCheckBoxMenuItem item2d = findOverlaysMenuItem(panel2d.popup);
            assertTrue("2D popup should have Overlays -> Upcoming Launches item", item2d != null);
            assertEq(true, item2d.isSelected());
            item2d.doClick();
            assertEq(false, panel2d.isShowUpcomingLaunches());

            TwoDViewPropertiesPanel props2d = new TwoDViewPropertiesPanel(panel2d, null);
            JCheckBox cb2d = findCheckBox(props2d, "Show Upcoming Launches");
            assertTrue("2D properties panel should expose Show Upcoming Launches checkbox", cb2d != null);
            assertEq(false, cb2d.isSelected());
            cb2d.setSelected(true);
            props2d.saveSettings();
            assertEq(true, panel2d.isShowUpcomingLaunches());
            assertEq(true, item2d.isSelected());

            // 2. Polar Panel centering (switches hemisphere if needed), click-to-select, popup & properties
            J2DPolarEarthPanel polar = new J2DPolarEarthPanel(
                    new java.util.Hashtable<>(), new java.util.Hashtable<>(),
                    null, null, null, time, sun, null);
            polar.setAwarenessService(svc);
            polar.setSize(600, 400);
            polar.getPolarLabel().setSize(600, 400);
            polar.setPolarMode(J2dPolarEarthLabel.MODE_NORTH);

            polar.centerOnLatLon(mahia.getLatDeg(), mahia.getLonDeg());
            assertEq(J2dPolarEarthLabel.MODE_SOUTH, polar.getPolarMode());

            polar.offScreenRender();
            int[][] southDisc = polar.getPolarLabel().computeDiscGeometries(600, 400);
            int panCx = southDisc[0][1] + (int) Math.round(polar.getPanXNorm() * southDisc[0][3]);
            int panCy = southDisc[0][2] + (int) Math.round(polar.getPanYNorm() * southDisc[0][3]);
            int[] mahiaXY = J2dPolarEarthLabel.latLonToPolarXY(
                    mahia.getLatDeg(), mahia.getLonDeg(),
                    -1, panCx, panCy, southDisc[0][3],
                    polar.getPolarLabel().getMaxAngularRadiusDeg());

            MouseEvent hoverPolar = new MouseEvent(
                    polar, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(),
                    0, mahiaXY[0], mahiaXY[1], 0, false);
            for (var mml : polar.getMouseMotionListeners()) {
                mml.mouseMoved(hoverPolar);
            }
            assertTrue("Polar hover tooltip should show launch details",
                    polar.getToolTipText() != null && polar.getToolTipText().contains("NZ Flight"));

            openUiRequested.set(false);
            MouseEvent clickPolar = new MouseEvent(
                    polar, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                    0, mahiaXY[0], mahiaXY[1], 1, false, MouseEvent.BUTTON1);
            for (var ml : polar.getMouseListeners()) {
                ml.mouseClicked(clickPolar);
            }
            SwingUtilities.invokeAndWait(() -> { });
            assertEq("m1", selectedIdRef.get());
            assertEq(true, openUiRequested.get());

            JCheckBoxMenuItem itemPolar = findOverlaysMenuItem(polar.popup);
            assertTrue("Polar popup should have Overlays -> Upcoming Launches item", itemPolar != null);
            assertEq(true, itemPolar.isSelected());

            TwoDPolarViewPropertiesPanel propsPolar = new TwoDPolarViewPropertiesPanel(polar, null);
            JCheckBox cbPolar = findCheckBox(propsPolar, "Show Upcoming Launches");
            assertTrue("Polar properties panel should expose Show Upcoming Launches checkbox", cbPolar != null);
            cbPolar.setSelected(false);
            propsPolar.saveSettings();
            assertEq(false, polar.isShowUpcomingLaunches());
            assertEq(false, itemPolar.isSelected());
        } finally {
            svc.shutdown();
        }
    }

    private static JCheckBoxMenuItem findOverlaysMenuItem(javax.swing.JPopupMenu popup) {
        if (popup == null) {
            return null;
        }
        for (int i = 0; i < popup.getComponentCount(); i++) {
            Component c = popup.getComponent(i);
            if (c instanceof JMenu menu && "Overlays".equals(menu.getText())) {
                for (int j = 0; j < menu.getItemCount(); j++) {
                    JMenuItem item = menu.getItem(j);
                    if (item instanceof JCheckBoxMenuItem cb && "Upcoming Launches".equals(cb.getText())) {
                        return cb;
                    }
                }
            }
        }
        return null;
    }

    private static JCheckBox findCheckBox(java.awt.Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c instanceof JCheckBox cb && text.equals(cb.getText())) {
                return cb;
            }
            if (c instanceof java.awt.Container nested) {
                JCheckBox found = findCheckBox(nested, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static LaunchRecord launch(String id, String mission, String pad, double lat, double lon, long netMillis) {
        return LaunchRecord.builder()
                .id(id)
                .missionName(mission)
                .provider("SpaceX")
                .vehicle("Falcon 9")
                .targetOrbit("LEO")
                .netEpochMillis(netMillis)
                .windowStartMillis(netMillis)
                .windowEndMillis(netMillis + 3600_000L)
                .status("Go")
                .orbital(true)
                .padName(pad)
                .spaceportName("Test Spaceport")
                .country("USA")
                .latDeg(lat)
                .lonDeg(lon)
                .fixedLandPad(true)
                .build();
    }

    private static void assertEq(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("Expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertTrue(String message, boolean condition) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

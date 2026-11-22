package jsattrak.awareness.gnss.map;

import jsattrak.awareness.config.AwarenessSettingsStore;
import jsattrak.awareness.gnss.BroadcastHealthRecord;
import jsattrak.awareness.gnss.GnssAwarenessService;
import jsattrak.awareness.gnss.GnssCatalogEntry;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.GnssOrbitClass;
import jsattrak.awareness.gnss.geometry.GnssDopGrid;
import jsattrak.awareness.gnss.geometry.GnssGeometryService;
import jsattrak.awareness.gnss.geometry.GnssObserverPosition;
import jsattrak.gui.J2DEarthPanel;
import jsattrak.gui.J2DPolarEarthPanel;
import jsattrak.gui.J2dPolarEarthLabel;
import jsattrak.gui.JSatTrak;
import jsattrak.utilities.J2DEarthPanelSave;
import jsattrak.utilities.J2DPolarEarthPanelSave;
import jsattrak.utilities.TLE;
import name.gano.astro.time.Time;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GnssDopHeatmap2DTest {

    private static final long T0 = 1_795_132_800_000L; // 2026-11-21T00:00:00Z

    @Test
    void paintsEquirectangularAndPolarDiscHeatmapWithoutGapBleedAndHandlesPickMode(@TempDir Path tmpDir) {
        JSatTrak.initOrekitData();
        AwarenessSettingsStore store = new AwarenessSettingsStore(tmpDir.resolve("awareness-settings.json"));
        GnssAwarenessService awareness = buildTestAwareness(tmpDir);
        GnssGeometryService geometry = new GnssGeometryService(awareness, store);

        try {
            // 1. Master gate OFF -> equirectangular & polar paint 0 heatmap pixels
            GnssDopHeatmap2DRenderer renderer = new GnssDopHeatmap2DRenderer();
            BufferedImage offImg = new BufferedImage(360, 180, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gOff = offImg.createGraphics();
            int offDrawn = renderer.paintEquirectangularHeatmap(
                    gOff, geometry, true, T0, new Rectangle(0, 0, 360, 180), -180.0, 180.0, -90.0, 90.0);
            gOff.dispose();
            assertEquals(0, offDrawn);

            // 2. Enable master gate + compute grid -> equirectangular paints non-transparent pixels
            geometry.setMapOverlayEnabled(true);
            GnssDopGrid grid = geometry.computeHeatmapBlockingForTest(T0);
            assertNotNull(grid);

            BufferedImage onImg = new BufferedImage(360, 180, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gOn = onImg.createGraphics();
            int onDrawn = renderer.paintEquirectangularHeatmap(
                    gOn, geometry, true, T0, new Rectangle(0, 0, 360, 180), -180.0, 180.0, -90.0, 90.0);
            gOn.dispose();
            assertTrue(onDrawn > 0);
            assertTrue((onImg.getRGB(180, 90) >>> 24) > 0);

            // 3. Render J2DPolarEarthPanel in MODE_DUAL (450x470) with baseline diff ->
            //    heatmap stays strictly inside discs (0 pixels in inter-disc gap y in [212..258])
            Time t = new Time();
            t.setTimeInMillis(T0);
            J2DPolarEarthPanel polarPanel = new J2DPolarEarthPanel(
                    new Hashtable<>(), new Hashtable<>(), null, null, null, t, null, null);
            polarPanel.setGnssGeometryService(geometry);
            assertTrue(polarPanel.isShowGnssDopHeatmap()); // per-window defaults ON
            polarPanel.getPolarLabel().setPolarMode(J2dPolarEarthLabel.MODE_DUAL);
            polarPanel.getPolarLabel().setSize(450, 470);

            polarPanel.setShowGnssDopHeatmap(false);
            BufferedImage baseline = new BufferedImage(450, 470, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gBase = baseline.createGraphics();
            polarPanel.getPolarLabel().paint(gBase);
            gBase.dispose();

            polarPanel.setShowGnssDopHeatmap(true);
            BufferedImage polarOn = new BufferedImage(450, 470, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gPolar = polarOn.createGraphics();
            polarPanel.getPolarLabel().paint(gPolar);
            gPolar.dispose();

            boolean sawDiscPixelDiff = false;
            for (int y = 0; y < 470; y++) {
                for (int x = 0; x < 450; x++) {
                    if (polarOn.getRGB(x, y) != baseline.getRGB(x, y)) {
                        if (y >= 212 && y <= 258) {
                            fail("Heatmap pixel leaked into inter-disc gap at (" + x + "," + y + ")");
                        }
                        sawDiscPixelDiff = true;
                    }
                }
            }
            assertTrue(sawDiscPixelDiff, "Expected polar heatmap pixels inside discs");

            // 4. Pick on Map via J2DEarthPanel and J2DPolarEarthPanel
            J2DEarthPanel earthPanel = new J2DEarthPanel(
                    new Hashtable<>(), new Hashtable<>(), null, null, null, t, null, null);
            earthPanel.setGnssGeometryService(geometry);
            assertTrue(earthPanel.isShowGnssDopHeatmap());
            earthPanel.setSize(400, 200);
            earthPanel.getImageMap().setSize(400, 200);
            earthPanel.doLayout();

            geometry.setObserver(new GnssObserverPosition("Initial", 0.0, 0.0, 0.0));
            geometry.setPickOnMapMode(true);
            assertTrue(geometry.isPickOnMapMode());

            MouseEvent clickEq = new MouseEvent(
                    earthPanel, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                    0, 250, 75, 1, false, MouseEvent.BUTTON1);
            for (var ml : earthPanel.getMouseListeners()) {
                ml.mouseClicked(clickEq);
            }
            assertFalse(geometry.isPickOnMapMode());
            assertNotEquals(0.0, geometry.getObserver().latDeg(), 1e-3);

            // Also test Pick on Map on Polar panel (North disc center at 225, 108 in 450x470 MODE_DUAL)
            polarPanel.setSize(450, 470);
            polarPanel.doLayout();
            geometry.setPickOnMapMode(true);
            MouseEvent clickPolar = new MouseEvent(
                    polarPanel, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                    0, 225, 108, 1, false, MouseEvent.BUTTON1);
            for (var ml : polarPanel.getMouseListeners()) {
                ml.mouseClicked(clickPolar);
            }
            assertFalse(geometry.isPickOnMapMode());
            assertTrue(geometry.getObserver().latDeg() > 70.0, "Expected near-North-Pole pick");

            // 5. Legacy .jst save defaults
            J2DEarthPanelSave save2d = new J2DEarthPanelSave();
            save2d.setShowGnssDopHeatmap(null);
            assertTrue(J2DEarthPanelSave.resolveShowGnssDopHeatmapDefault(save2d.getShowGnssDopHeatmap()));

            J2DPolarEarthPanelSave savePolar = new J2DPolarEarthPanelSave();
            savePolar.setShowGnssDopHeatmap(null);
            assertTrue(J2DPolarEarthPanelSave.resolveShowGnssDopHeatmapDefault(savePolar.getShowGnssDopHeatmap()));

            // 6. Polar heatmap cache reuses rendered disc image on steady-state repaints
            assertEquals(GnssDopGrid.colorForDop(1.8, 180).getRGB(), GnssDopGrid.argbForDop(1.8, 180));
            BufferedImage directPolar = new BufferedImage(450, 470, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gDir = directPolar.createGraphics();
            renderer.paintPolarDiscHeatmap(gDir, geometry, true, T0, 1, 225, 108, 100, 225, 108, 90.0);
            assertEquals(0, renderer.getPolarDiscCacheHitCountForTest());
            renderer.paintPolarDiscHeatmap(gDir, geometry, true, T0, 1, 225, 108, 100, 225, 108, 90.0);
            assertEquals(1, renderer.getPolarDiscCacheHitCountForTest());
            gDir.dispose();
        } finally {
            geometry.shutdown();
            awareness.shutdown();
        }
    }

    private static GnssAwarenessService buildTestAwareness(Path tmpDir) {
        List<GnssCatalogEntry> cat = List.of(
                new GnssCatalogEntry("G01", GnssConstellation.GPS, 1, "G063", 37753, "2011-036A", "GPS-IIF", GnssOrbitClass.MEO, "D", "2", ""),
                new GnssCatalogEntry("G02", GnssConstellation.GPS, 2, "G064", 37754, "2011-036B", "GPS-IIF", GnssOrbitClass.MEO, "D", "2", ""),
                new GnssCatalogEntry("E11", GnssConstellation.GALILEO, 11, "E101", 37846, "2011-060A", "GALILEO-1", GnssOrbitClass.MEO, "B", "5", ""),
                new GnssCatalogEntry("R05", GnssConstellation.GLONASS, 5, "R730", 36111, "2009-070A", "GLONASS-M", GnssOrbitClass.MEO, "1", "5", ""),
                new GnssCatalogEntry("C19", GnssConstellation.BEIDOU, 19, "C201", 43107, "2018-003A", "BEIDOU-3M", GnssOrbitClass.MEO, "A", "1", ""));
        List<TLE> tles = List.of(
                new TLE("G01", "1 37753U 11036A   26325.00000000  .00000000  00000+0  00000+0 0  9991", "2 37753  55.0000  40.0000 0010000  90.0000  10.0000  2.00560000100001"),
                new TLE("G02", "1 37754U 11036B   26325.00000000  .00000000  00000+0  00000+0 0  9992", "2 37754  55.0000 100.0000 0010000  90.0000  90.0000  2.00560000100002"),
                new TLE("E11", "1 37846U 11060A   26325.00000000  .00000000  00000+0  00000+0 0  9993", "2 37846  56.0000 200.0000 0002000  90.0000 180.0000  1.70475000100003"),
                new TLE("R05", "1 36111U 09070A   26325.00000000  .00000000  00000+0  00000+0 0  9995", "2 36111  65.1033  66.0683 0001945 301.0107 247.3624  2.13103295131494"),
                new TLE("C19", "1 43107U 18003A   26325.00000000  .00000000  00000+0  00000+0 0  9994", "2 43107  55.0000 300.0000 0005000  90.0000 270.0000  1.86230000 50001"));
        Map<String, BroadcastHealthRecord> health = Map.of(
                "G01", new BroadcastHealthRecord("G01", GnssConstellation.GPS, "LNAV", T0, 0, true, "OK"),
                "G02", new BroadcastHealthRecord("G02", GnssConstellation.GPS, "LNAV", T0, 0, true, "OK"),
                "E11", new BroadcastHealthRecord("E11", GnssConstellation.GALILEO, "INAV", T0, 0, true, "OK"),
                "R05", new BroadcastHealthRecord("R05", GnssConstellation.GLONASS, "FDMA", T0, 0, true, "OK"),
                "C19", new BroadcastHealthRecord("C19", GnssConstellation.BEIDOU, "D1", T0, 0, true, "OK"));
        return GnssAwarenessService.createForTest(tmpDir, cat, tles, health, Map.of(), List.of());
    }
}

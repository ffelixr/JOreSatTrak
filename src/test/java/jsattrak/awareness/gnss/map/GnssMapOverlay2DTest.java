package jsattrak.awareness.gnss.map;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import jsattrak.awareness.gnss.BroadcastHealthRecord;
import jsattrak.awareness.gnss.GnssAwarenessService;
import jsattrak.awareness.gnss.GnssCatalogEntry;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.GnssOrbitClass;
import jsattrak.gui.JSatTrak;
import jsattrak.utilities.J2DEarthPanelSave;
import jsattrak.utilities.J2DPolarEarthPanelSave;
import jsattrak.utilities.TLE;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class GnssMapOverlay2DTest {

    private static final long T0 = 1_795_132_800_000L; // 2026-11-21T00:00:00Z

    @Test
    void paintsAllFourConstellationsSelectedTrackSplitsAntimeridianAndHandlesClickHover(@TempDir Path tmpDir) {
        JSatTrak.initOrekitData();
        GnssAwarenessService svc = buildTestServiceWith4Constellations(tmpDir);
        svc.selectSatellite("G01");

        GnssOverlay2DRenderer renderer = new GnssOverlay2DRenderer();
        BufferedImage img = new BufferedImage(800, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        BiFunction<Double, Double, int[]> eqProjector = (lat, lon) -> new int[]{
                (int) Math.round((lon + 180.0) / 360.0 * 800.0),
                (int) Math.round((90.0 - lat) / 180.0 * 400.0)
        };
        int painted = renderer.paintServiceOverlay(g2, svc, true, T0, new Rectangle(0, 0, 800, 400), eqProjector);
        g2.dispose();
        assertEquals(5, painted);

        List<List<double[]>> segments = GnssOverlay2DRenderer.splitGroundTrackSegments(
                List.of(new double[]{10, 175}, new double[]{11, -178}, new double[]{12, -170}));
        assertEquals(2, segments.size());

        var g07Pos = svc.getSatelliteState("G07", T0).orElseThrow().position();
        int[] pt = eqProjector.apply(g07Pos.latDeg(), g07Pos.lonDeg());
        assertTrue(renderer.handleLeftClick(pt[0], pt[1], 12.0, svc, true));
        assertEquals("G07", svc.getSelectedPrnId());

        assertFalse(renderer.handleLeftClick(pt[0], pt[1], 12.0, svc, false));
    }

    @Test
    void legacyJstSaveWithNullShowGnssDefaultsToTrue() {
        J2DEarthPanelSave save2d = new J2DEarthPanelSave();
        save2d.setShowGnssConstellations(null);
        assertTrue(J2DEarthPanelSave.resolveShowGnssDefault(save2d.getShowGnssConstellations()));

        J2DPolarEarthPanelSave savePolar = new J2DPolarEarthPanelSave();
        savePolar.setShowGnssConstellations(null);
        assertTrue(J2DPolarEarthPanelSave.resolveShowGnssDefault(savePolar.getShowGnssConstellations()));
    }

    private static GnssAwarenessService buildTestServiceWith4Constellations(Path tmpDir) {
        List<GnssCatalogEntry> cat = List.of(
                new GnssCatalogEntry("G01", GnssConstellation.GPS, 1, "G063", 37753, "2011-036A", "GPS-IIF", GnssOrbitClass.MEO, "D", "2", "2011-07-16"),
                new GnssCatalogEntry("G07", GnssConstellation.GPS, 7, "G056", 32711, "2008-012A", "GPS-IIRM", GnssOrbitClass.MEO, "A", "4", "2008-03-15"),
                new GnssCatalogEntry("E11", GnssConstellation.GALILEO, 11, "E101", 37846, "2011-060A", "GALILEO-1", GnssOrbitClass.MEO, "B", "5", "2011-10-21"),
                new GnssCatalogEntry("R05", GnssConstellation.GLONASS, 5, "R730", 36111, "2009-070A", "GLONASS-M", GnssOrbitClass.MEO, "1", "5", "2009-12-14"),
                new GnssCatalogEntry("C19", GnssConstellation.BEIDOU, 19, "C201", 43107, "2018-003A", "BEIDOU-3M", GnssOrbitClass.MEO, "A", "1", "2018-01-11"));
        List<TLE> tles = List.of(
                new TLE("G01", "1 37753U 11036A   26312.00000000  .00000000  00000+0  00000+0 0  9991", "2 37753  55.0000 100.0000 0010000  90.0000 270.0000  2.00560000100001"),
                new TLE("G07", "1 32711U 08012A   26312.00000000  .00000000  00000+0  00000+0 0  9991", "2 32711  55.0000 150.0000 0010000  90.0000 270.0000  2.00560000100001"),
                new TLE("E11", "1 37846U 11060A   26312.00000000  .00000000  00000+0  00000+0 0  9991", "2 37846  56.0000 200.0000 0002000  90.0000 270.0000  1.70475000100001"),
                new TLE("R05", "1 36111U 09070A   26312.00000000  .00000000  00000+0  00000+0 0  9995", "2 36111  65.1033  66.0683 0001945 301.0107 247.3624  2.13103295131494"),
                new TLE("C19", "1 43107U 18003A   26312.00000000  .00000000  00000+0  00000+0 0  9994", "2 43107  55.0000 300.0000 0005000  90.0000 270.0000  1.86230000 50001"));
        Map<String, BroadcastHealthRecord> health = Map.of(
                "G01", new BroadcastHealthRecord("G01", GnssConstellation.GPS, "LNAV", T0, 0, true, "OK"),
                "G07", new BroadcastHealthRecord("G07", GnssConstellation.GPS, "LNAV", T0, 63, false, "bad"),
                "E11", new BroadcastHealthRecord("E11", GnssConstellation.GALILEO, "INAV", T0, 0, true, "OK"),
                "R05", new BroadcastHealthRecord("R05", GnssConstellation.GLONASS, "FDMA", T0, 0, true, "OK"),
                "C19", new BroadcastHealthRecord("C19", GnssConstellation.BEIDOU, "D1/D2", T0, 0, true, "OK"));
        return GnssAwarenessService.createForTest(tmpDir, cat, tles, health, Map.of(), List.of());
    }
}

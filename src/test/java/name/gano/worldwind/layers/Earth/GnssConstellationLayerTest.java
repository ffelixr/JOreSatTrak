package name.gano.worldwind.layers.Earth;

import static org.junit.jupiter.api.Assertions.*;

import gov.nasa.worldwind.WorldWind;
import gov.nasa.worldwind.render.Path;
import gov.nasa.worldwind.render.PointPlacemark;
import gov.nasa.worldwind.render.Renderable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jsattrak.awareness.gnss.BroadcastHealthRecord;
import jsattrak.awareness.gnss.GnssAwarenessService;
import jsattrak.awareness.gnss.GnssCatalogEntry;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.GnssOrbitClass;
import jsattrak.gui.JSatTrak;
import jsattrak.utilities.TLE;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class GnssConstellationLayerTest {

    static {
        System.setProperty("java.awt.headless", "true");
        WorldWind.setOfflineMode(true);
    }

    private static final long T0 = 1_795_132_800_000L; // 2026-11-21T00:00:00Z

    @Test
    void buildsOrbitalAltitudePlacemarksPlaneRingsSelectionLabelAndRespectsToggles(@TempDir java.nio.file.Path tmpDir) {
        JSatTrak.initOrekitData();
        GnssAwarenessService svc = buildTestServiceWith4Constellations(tmpDir);
        svc.selectSatellite("G01");

        GnssConstellationLayer layer = new GnssConstellationLayer(svc);
        assertEquals(GnssConstellationLayer.LAYER_NAME, layer.getName());
        layer.rebuildRenderables(T0);

        List<PointPlacemark> placemarks = collectRenderables(layer, PointPlacemark.class);
        assertEquals(4, placemarks.size());
        assertTrue(placemarks.stream().allMatch(p -> p.getAltitudeMode() == WorldWind.ABSOLUTE));
        assertTrue(placemarks.stream().allMatch(p -> p.getPosition().getElevation() > 18_000_000.0));
        assertTrue(placemarks.stream().anyMatch(p -> "G01".equals(p.getValue(GnssConstellationLayer.GNSS_PRN_KEY))));

        assertTrue(collectRenderables(layer, Path.class).size() >= 1);
        svc.setShowOrbitRings3d(false);
        layer.rebuildRenderables(T0);
        assertEquals(0, collectRenderables(layer, Path.class).size());

        layer.setEnabled(false);
        assertEquals(0, collectRenderables(layer, PointPlacemark.class).size());
    }

    private static <T extends Renderable> List<T> collectRenderables(GnssConstellationLayer layer, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Renderable r : layer.getRenderables()) {
            if (type.isInstance(r)) {
                out.add(type.cast(r));
            }
        }
        return out;
    }

    private static GnssAwarenessService buildTestServiceWith4Constellations(java.nio.file.Path tmpDir) {
        List<GnssCatalogEntry> cat = List.of(
                new GnssCatalogEntry("G01", GnssConstellation.GPS, 1, "G063", 37753, "2011-036A", "GPS-IIF", GnssOrbitClass.MEO, "D", "2", "2011-07-16"),
                new GnssCatalogEntry("E11", GnssConstellation.GALILEO, 11, "E101", 37846, "2011-060A", "GALILEO-1", GnssOrbitClass.MEO, "B", "5", "2011-10-21"),
                new GnssCatalogEntry("R05", GnssConstellation.GLONASS, 5, "R730", 36111, "2009-070A", "GLONASS-M", GnssOrbitClass.MEO, "1", "5", "2009-12-14"),
                new GnssCatalogEntry("C19", GnssConstellation.BEIDOU, 19, "C201", 43107, "2018-003A", "BEIDOU-3M", GnssOrbitClass.MEO, "A", "1", "2018-01-11"));
        List<TLE> tles = List.of(
                new TLE("G01", "1 37753U 11036A   26312.00000000  .00000000  00000+0  00000+0 0  9991", "2 37753  55.0000 100.0000 0010000  90.0000 270.0000  2.00560000100001"),
                new TLE("E11", "1 37846U 11060A   26312.00000000  .00000000  00000+0  00000+0 0  9991", "2 37846  56.0000 200.0000 0002000  90.0000 270.0000  1.70475000100001"),
                new TLE("R05", "1 36111U 09070A   26312.00000000  .00000000  00000+0  00000+0 0  9995", "2 36111  65.1033  66.0683 0001945 301.0107 247.3624  2.13103295131494"),
                new TLE("C19", "1 43107U 18003A   26312.00000000  .00000000  00000+0  00000+0 0  9994", "2 43107  55.0000 300.0000 0005000  90.0000 270.0000  1.86230000 50001"));
        Map<String, BroadcastHealthRecord> health = Map.of(
                "G01", new BroadcastHealthRecord("G01", GnssConstellation.GPS, "LNAV", T0, 0, true, "OK"),
                "E11", new BroadcastHealthRecord("E11", GnssConstellation.GALILEO, "INAV", T0, 0, true, "OK"),
                "R05", new BroadcastHealthRecord("R05", GnssConstellation.GLONASS, "FDMA", T0, 0, true, "OK"),
                "C19", new BroadcastHealthRecord("C19", GnssConstellation.BEIDOU, "D1/D2", T0, 0, true, "OK"));
        return GnssAwarenessService.createForTest(tmpDir, cat, tles, health, Map.of(), List.of());
    }
}

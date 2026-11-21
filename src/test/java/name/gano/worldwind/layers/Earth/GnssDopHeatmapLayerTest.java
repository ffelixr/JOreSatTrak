package name.gano.worldwind.layers.Earth;

import gov.nasa.worldwind.WorldWind;
import gov.nasa.worldwind.geom.Sector;
import gov.nasa.worldwind.render.PointPlacemark;
import gov.nasa.worldwind.render.Renderable;
import gov.nasa.worldwind.render.SurfaceImage;
import jsattrak.awareness.config.AwarenessSettingsStore;
import jsattrak.awareness.gnss.BroadcastHealthRecord;
import jsattrak.awareness.gnss.GnssAwarenessService;
import jsattrak.awareness.gnss.GnssCatalogEntry;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.GnssOrbitClass;
import jsattrak.awareness.gnss.geometry.GnssGeometryService;
import jsattrak.awareness.gnss.geometry.GnssObserverPosition;
import jsattrak.gui.JSatTrak;
import jsattrak.utilities.TLE;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GnssDopHeatmapLayerTest {

    static {
        System.setProperty("java.awt.headless", "true");
        WorldWind.setOfflineMode(true);
    }

    private static final long T0 = 1_795_132_800_000L; // 2026-11-21T00:00:00Z

    @Test
    void rendersSurfaceImageOnlyWhenMasterGateEnabledAndObserverPinIndependently(@TempDir Path tmpDir) {
        JSatTrak.initOrekitData();
        AwarenessSettingsStore store = new AwarenessSettingsStore(tmpDir.resolve("awareness-settings.json"));
        GnssAwarenessService awareness = buildTestService(tmpDir);
        GnssGeometryService geometry = new GnssGeometryService(awareness, store);
        try {
            geometry.setObserver(new GnssObserverPosition("Madrid", 40.4168, -3.7038, 650.0));
            GnssDopHeatmapLayer layer = new GnssDopHeatmapLayer(geometry);
            assertEquals(GnssDopHeatmapLayer.LAYER_NAME, layer.getName());
            assertTrue(layer.isEnabled());

            // 1. Master gate OFF, tab hidden -> 0 SurfaceImage, 0 PointPlacemark
            layer.rebuildRenderables(T0);
            assertEquals(0, collectRenderables(layer, SurfaceImage.class).size());
            assertEquals(0, collectRenderables(layer, PointPlacemark.class).size());

            // 2. Tab visible, master gate still OFF -> 0 SurfaceImage, 1 PointPlacemark
            geometry.setGeometryTabVisible(true);
            layer.rebuildRenderables(T0);
            assertEquals(0, collectRenderables(layer, SurfaceImage.class).size());
            List<PointPlacemark> pins = collectRenderables(layer, PointPlacemark.class);
            assertEquals(1, pins.size());
            assertEquals(40.4168, pins.get(0).getPosition().getLatitude().degrees, 1e-4);
            assertEquals(-3.7038, pins.get(0).getPosition().getLongitude().degrees, 1e-4);

            // 3. Master gate ON + grid computed -> 1 SurfaceImage (FULL_SPHERE) + 1 PointPlacemark
            geometry.setMapOverlayEnabled(true);
            assertNotNull(geometry.computeHeatmapBlockingForTest(T0));
            layer.rebuildRenderables(T0);
            List<SurfaceImage> images = collectRenderables(layer, SurfaceImage.class);
            assertEquals(1, images.size());
            assertEquals(Sector.FULL_SPHERE, images.get(0).getSector());
            assertEquals(1, collectRenderables(layer, PointPlacemark.class).size());

            // 4. Per-window disable -> 0 SurfaceImage
            layer.setEnabled(false);
            assertEquals(0, collectRenderables(layer, SurfaceImage.class).size());
        } finally {
            geometry.shutdown();
            awareness.shutdown();
        }
    }

    private static <T extends Renderable> List<T> collectRenderables(GnssDopHeatmapLayer layer, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Renderable r : layer.getRenderables()) {
            if (type.isInstance(r)) {
                out.add(type.cast(r));
            }
        }
        return out;
    }

    private static GnssAwarenessService buildTestService(Path tmpDir) {
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

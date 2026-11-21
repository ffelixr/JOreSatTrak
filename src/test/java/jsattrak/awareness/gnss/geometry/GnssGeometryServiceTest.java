package jsattrak.awareness.gnss.geometry;

import jsattrak.awareness.config.AwarenessSettings;
import jsattrak.awareness.config.AwarenessSettingsStore;
import jsattrak.awareness.gnss.BroadcastHealthRecord;
import jsattrak.awareness.gnss.GnssAwarenessService;
import jsattrak.awareness.gnss.GnssCatalogEntry;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.GnssOrbitClass;
import jsattrak.awareness.gnss.GnssOutageNotice;
import jsattrak.gui.JSatTrak;
import jsattrak.utilities.TLE;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GnssGeometryServiceTest {

    // 8 GNSS satellites staged across MEO planes so >=5 are visible over (0°, 0°) both at T0 and T0+3h
    private static List<GnssCatalogEntry> distributedCatalog() {
        return List.of(
                cat("G01", GnssConstellation.GPS, 1, 37753),
                cat("G02", GnssConstellation.GPS, 2, 37754),
                cat("G05", GnssConstellation.GPS, 5, 37755),
                cat("G12", GnssConstellation.GPS, 12, 37756),
                cat("G18", GnssConstellation.GPS, 18, 37757),
                cat("E11", GnssConstellation.GALILEO, 11, 37846),
                cat("R05", GnssConstellation.GLONASS, 5, 36111),
                cat("C19", GnssConstellation.BEIDOU, 19, 43107)
        );
    }

    private static GnssCatalogEntry cat(String prn, GnssConstellation c, int num, int norad) {
        return new GnssCatalogEntry(prn, c, num, prn, norad, "2020-001A", prn, GnssOrbitClass.MEO, "", "", "");
    }

    private static List<TLE> distributedTles() {
        // Epoch 2026 day 325.0 (2026-11-21T00:00:00Z). Earth GMST ~ 60 deg.
        // Group A (good over 0°,0° at T0): RAAN ~ 60 deg, varying inclination/M0
        // Group B including G18 (directly over 0°,0° at T0+3h): RAAN ~ 105 deg, M0 ~ 270 deg (90 deg behind)
        return List.of(
                tle("GPS PRN 01", 37753, 55.0, 40.0, 20.0, 2.0056),
                tle("GPS PRN 02", 37754, 55.0, 80.0, 340.0, 2.0056),
                tle("GPS PRN 05", 37755, 55.0, 120.0, 250.0, 2.0056),
                tle("GPS PRN 12", 37756, 20.0, 60.0, 10.0, 2.0056),
                tle("GPS PRN 18", 37757, 55.0, 105.0, 270.0, 2.0056),
                tle("GALILEO E11", 37846, 56.0, 60.0, 330.0, 1.7048),
                tle("GLONASS R05", 36111, 64.8, 105.0, 260.0, 2.1310),
                tle("BEIDOU C19", 43107, 55.0, 105.0, 285.0, 1.8623)
        );
    }

    private static TLE tle(String name, int norad, double incDeg, double raanDeg, double m0Deg, double revPerDay) {
        String l1 = String.format(java.util.Locale.US,
                "1 %05dU 20001A   26325.00000000  .00000000  00000-0  00000-0 0  9990", norad);
        String l2 = String.format(java.util.Locale.US,
                "2 %05d %8.4f %8.4f 0001000   0.0000 %8.4f %11.8f000010",
                norad, incDeg, raanDeg, m0Deg, revPerDay);
        return new TLE(name, l1, l2);
    }

    @Test
    void computesPointDop24hOutageSpikeGlobalHeatmapAndPreservesSettings(@TempDir Path tmpDir) throws Exception {
        JSatTrak.initOrekitData();
        long t0 = Instant.parse("2026-11-21T00:00:00Z").toEpochMilli();

        Map<String, BroadcastHealthRecord> health = Map.of(
                "G01", new BroadcastHealthRecord("G01", GnssConstellation.GPS, "LNAV", t0, 0, true, "OK"),
                "G02", new BroadcastHealthRecord("G02", GnssConstellation.GPS, "LNAV", t0, 0, true, "OK"),
                "G05", new BroadcastHealthRecord("G05", GnssConstellation.GPS, "LNAV", t0, 0, true, "OK"),
                "G12", new BroadcastHealthRecord("G12", GnssConstellation.GPS, "LNAV", t0, 0, true, "OK"),
                "G18", new BroadcastHealthRecord("G18", GnssConstellation.GPS, "LNAV", t0, 0, true, "OK"),
                "E11", new BroadcastHealthRecord("E11", GnssConstellation.GALILEO, "INAV", t0, 0, true, "OK"),
                "R05", new BroadcastHealthRecord("R05", GnssConstellation.GLONASS, "FDMA", t0, 0, true, "OK"),
                "C19", new BroadcastHealthRecord("C19", GnssConstellation.BEIDOU, "D1", t0, 0, true, "OK")
        );
        GnssOutageNotice plannedOutage = new GnssOutageNotice(
                "NANU-2026-099", GnssConstellation.GPS, "G18", "SVN63", "FCSTDV",
                true, false, t0 + 2 * 3600_000L, t0 + 6 * 3600_000L, "Planned outage", "");

        AwarenessSettingsStore store = new AwarenessSettingsStore(tmpDir.resolve("awareness-settings.json"));
        GnssAwarenessService awareness = GnssAwarenessService.createForTest(
                tmpDir, distributedCatalog(), distributedTles(), health, Map.of(), List.of(plannedOutage));

        GnssGeometryService service = new GnssGeometryService(awareness, store);
        try {
            assertEquals("gnss-geometry", service.getFeedId());
            assertFalse(service.isMapOverlayEnabled());
            assertFalse(service.isObserverPinVisible());

            service.setObserver(new GnssObserverPosition("Equator", 0.0, 0.0, 0.0));
            service.setElevationMaskDeg(5.0);

            GnssDopSolution solT0 = service.computePointSolution(t0);
            assertTrue(solT0.fixAvailable(), "Expected valid fix at T0");
            assertTrue(solT0.pdop() > 0.0 && Double.isFinite(solT0.pdop()));

            List<GnssDopSample> series = service.compute24hSeriesBlockingForTest(t0);
            assertEquals(145, series.size());
            assertEquals(t0 - 6 * 3600_000L, series.get(0).epochMillis());
            assertEquals(t0 + 18 * 3600_000L, series.get(144).epochMillis());

            // At T0+3h, G18 is in its planned NANU outage -> excluded from used SVs when includeNonOp=false
            long t3 = t0 + 3 * 3600_000L;
            GnssDopSolution solDuringOutage = service.computePointSolution(t3);
            service.setIncludeNonOperational(true);
            GnssDopSolution solWhatIf = service.computePointSolution(t3);
            service.setIncludeNonOperational(false);
            assertTrue(solWhatIf.usedCount() > solDuringOutage.usedCount(),
                    "Expected G18 outage exclusion during T0+3h");

            // Global 5-deg heatmap -> 36 x 72 cells
            GnssDopGrid grid = service.computeHeatmapBlockingForTest(t0);
            assertNotNull(grid);
            assertEquals(36, grid.getLatRows());
            assertEquals(72, grid.getLonCols());
            BufferedImage img = grid.renderRgbaImage(0.45);
            assertNotNull(img);
            assertEquals(72, img.getWidth());
            assertEquals(36, img.getHeight());

            // Pick on Map updates observer and disarms pick mode
            service.setPickOnMapMode(true);
            assertTrue(service.isPickOnMapMode());
            assertTrue(service.isObserverPinVisible());
            service.completeMapPick(51.5, -0.1);
            assertFalse(service.isPickOnMapMode());
            assertEquals(51.5, service.getObserver().latDeg(), 1e-6);
            assertEquals(-0.1, service.getObserver().lonDeg(), 1e-6);

            // Settings persisted & reloaded cleanly
            service.setElevationMaskDeg(15.0);
            service.setHeatmapMetric(GnssDopMetric.HDOP);
            service.setHeatmapResolutionDeg(2.5);
            service.setHeatmapOpacity(0.65);

            AwarenessSettings reloaded = store.load();
            assertEquals(15.0, reloaded.getGnssElevationMaskDeg(), 1e-6);
            assertEquals("HDOP", reloaded.getGnssDopHeatmapMetric());
            assertEquals(2.5, reloaded.getGnssDopHeatmapResolutionDeg(), 1e-6);
            assertEquals(0.65, reloaded.getGnssDopHeatmapOpacity(), 1e-6);
            assertEquals(51.5, reloaded.getGnssObserverLatDeg(), 1e-6);
            assertEquals(-0.1, reloaded.getGnssObserverLonDeg(), 1e-6);
        } finally {
            service.shutdown();
            awareness.shutdown();
        }
    }
}

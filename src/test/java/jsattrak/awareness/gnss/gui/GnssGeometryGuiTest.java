package jsattrak.awareness.gnss.gui;

import jsattrak.awareness.LiveAwarenessService;
import jsattrak.awareness.config.AwarenessSettingsStore;
import jsattrak.awareness.config.DataSourceConfig;
import jsattrak.awareness.gnss.BroadcastHealthRecord;
import jsattrak.awareness.gnss.GnssAwarenessService;
import jsattrak.awareness.gnss.GnssCatalogEntry;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.GnssOrbitClass;
import jsattrak.awareness.gnss.geometry.GnssGeometryService;
import jsattrak.awareness.gnss.geometry.GnssObserverPosition;
import jsattrak.awareness.gui.LiveAwarenessInternalFrame;
import jsattrak.awareness.net.LaunchApiClient;
import jsattrak.awareness.tle.TleFeedClient;
import jsattrak.gui.JSatTrak;
import jsattrak.utilities.GroundStationCatalogRepository;
import jsattrak.utilities.TLE;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;

class GnssGeometryGuiTest {

    @BeforeAll
    static void setupHeadless() {
        System.setProperty("java.awt.headless", "true");
        JSatTrak.initOrekitData();
    }

    @Test
    void geometryTabUpdatesKpisOnMaskAndObserverChangeTogglesPickModeAndIntegratesInHub(@TempDir Path tmpDir) throws Exception {
        long t0 = Instant.parse("2026-11-21T00:00:00Z").toEpochMilli();
        AwarenessSettingsStore store = new AwarenessSettingsStore(tmpDir.resolve("awareness-settings.json"));
        LaunchApiClient mockLaunch = new LaunchApiClient() {
            @Override
            public LaunchFetchResult fetchUpcomingAndRecent(List<DataSourceConfig> prioritizedSources, long realNowMillis) {
                return new LaunchFetchResult(List.of(), List.of(), "mock");
            }
        };
        TleFeedClient emptyTle = new TleFeedClient() {
            @Override
            public List<TLE> fetchTleCatalog(DataSourceConfig source) {
                return List.of();
            }
        };
        GroundStationCatalogRepository repo = new GroundStationCatalogRepository(
                tmpDir.resolve("base.csv").toFile(),
                tmpDir.resolve("custom.csv").toFile(),
                tmpDir.resolve("launches.csv").toFile());
        LiveAwarenessService launches = new LiveAwarenessService(store, tmpDir.resolve("launch-cache.json"), mockLaunch, emptyTle, repo);
        GnssAwarenessService awareness = GnssAwarenessService.createForTest(
                tmpDir, sampleCatalog(), sampleTles(), sampleHealth(t0), Map.of(), List.of());
        GnssGeometryService geometry = new GnssGeometryService(awareness, store);
        geometry.setObserver(new GnssObserverPosition("Equator", 0.0, 0.0, 0.0));
        geometry.setElevationMaskDeg(5.0);

        try {
            SwingUtilities.invokeAndWait(() -> {
                LiveAwarenessInternalFrame frame = new LiveAwarenessInternalFrame(
                        launches,
                        awareness,
                        geometry,
                        null,
                        t -> {},
                        (lat, lon) -> {},
                        r -> {},
                        null,
                        () -> {},
                        s -> {},
                        s -> false);
                try {
                    assertEquals(3, frame.getFeedTabbedPane().getTabCount());
                    assertFalse(geometry.isObserverPinVisible());

                    // Select GNSS Geometry tab -> observer pin becomes visible
                    frame.selectFeedTab(GnssGeometryService.FEED_ID);
                    assertTrue(geometry.isObserverPinVisible());

                    // Switch back to Launches -> observer pin hides again
                    frame.selectFeedTab(LiveAwarenessService.FEED_ID);
                    assertFalse(geometry.isObserverPinVisible());

                    // Switch back to GNSS Geometry and refresh display
                    frame.selectFeedTab(GnssGeometryService.FEED_ID);
                    frame.refreshDisplay(t0, false, TimeZone.getTimeZone("UTC"));
                    GnssGeometryTab tab = frame.getGnssGeometryTab();
                    assertNotNull(tab);

                    String pdopText = tab.getPdopLabel().getText();
                    String gdopText = tab.getGdopLabel().getText();
                    assertNotEquals("--", pdopText);
                    assertNotEquals("No Fix", pdopText);
                    assertDoesNotThrow(() -> Double.parseDouble(pdopText));
                    assertDoesNotThrow(() -> Double.parseDouble(gdopText));

                    int visBefore = Integer.parseInt(tab.getVisibleCountLabel().getText());
                    assertTrue(visBefore >= 4);

                    // Raise elevation mask spinner to 45 deg -> visible count decreases & service updated
                    tab.getElevationMaskSpinner().setValue(45.0);
                    assertEquals(45.0, geometry.getElevationMaskDeg(), 1e-6);
                    int visAfter = Integer.parseInt(tab.getVisibleCountLabel().getText());
                    assertTrue(visAfter <= visBefore);

                    // Toggle Pick on Map button -> completeMapPick disarms toggle
                    tab.getPickOnMapButton().doClick();
                    assertTrue(geometry.isPickOnMapMode());
                    assertTrue(tab.getPickOnMapButton().isSelected());

                    geometry.completeMapPick(48.85, 2.35);
                    assertFalse(geometry.isPickOnMapMode());
                    assertFalse(tab.getPickOnMapButton().isSelected());
                } finally {
                    frame.dispose();
                }
            });
        } finally {
            geometry.shutdown();
            awareness.shutdown();
            launches.shutdown();
        }
    }

    private static List<GnssCatalogEntry> sampleCatalog() {
        return List.of(
                cat("G01", GnssConstellation.GPS, 1, 37753),
                cat("G02", GnssConstellation.GPS, 2, 37754),
                cat("G05", GnssConstellation.GPS, 5, 37755),
                cat("G12", GnssConstellation.GPS, 12, 37756),
                cat("E11", GnssConstellation.GALILEO, 11, 37846),
                cat("C19", GnssConstellation.BEIDOU, 19, 43107)
        );
    }

    private static GnssCatalogEntry cat(String prn, GnssConstellation c, int num, int norad) {
        return new GnssCatalogEntry(prn, c, num, prn, norad, "2020-001A", prn, GnssOrbitClass.MEO, "", "", "");
    }

    private static List<TLE> sampleTles() {
        return List.of(
                tle("GPS PRN 01", 37753, 55.0, 40.0, 20.0, 2.0056),
                tle("GPS PRN 02", 37754, 55.0, 80.0, 340.0, 2.0056),
                tle("GPS PRN 05", 37755, 55.0, 60.0, 355.0, 2.0056),
                tle("GPS PRN 12", 37756, 20.0, 60.0, 10.0, 2.0056),
                tle("GALILEO E11", 37846, 56.0, 60.0, 330.0, 1.7048),
                tle("BEIDOU C19", 43107, 55.0, 60.0, 30.0, 1.8623)
        );
    }

    private static TLE tle(String name, int norad, double incDeg, double raanDeg, double m0Deg, double revPerDay) {
        String l1 = String.format(Locale.US,
                "1 %05dU 20001A   26325.00000000  .00000000  00000-0  00000-0 0  9990", norad);
        String l2 = String.format(Locale.US,
                "2 %05d %8.4f %8.4f 0001000   0.0000 %8.4f %11.8f000010",
                norad, incDeg, raanDeg, m0Deg, revPerDay);
        return new TLE(name, l1, l2);
    }

    private static Map<String, BroadcastHealthRecord> sampleHealth(long t0) {
        return Map.of(
                "G01", new BroadcastHealthRecord("G01", GnssConstellation.GPS, "LNAV", t0, 0, true, "OK"),
                "G02", new BroadcastHealthRecord("G02", GnssConstellation.GPS, "LNAV", t0, 0, true, "OK"),
                "G05", new BroadcastHealthRecord("G05", GnssConstellation.GPS, "LNAV", t0, 0, true, "OK"),
                "G12", new BroadcastHealthRecord("G12", GnssConstellation.GPS, "LNAV", t0, 0, true, "OK"),
                "E11", new BroadcastHealthRecord("E11", GnssConstellation.GALILEO, "INAV", t0, 0, true, "OK"),
                "C19", new BroadcastHealthRecord("C19", GnssConstellation.BEIDOU, "D1", t0, 0, true, "OK")
        );
    }
}

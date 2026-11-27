package jsattrak.awareness.gnss.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import javax.swing.JCheckBox;
import javax.swing.SwingUtilities;
import jsattrak.awareness.LiveAwarenessService;
import jsattrak.awareness.config.AwarenessSettings;
import jsattrak.awareness.config.AwarenessSettingsStore;
import jsattrak.awareness.gnss.GnssAwarenessService;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.geometry.GnssGeometryService;
import jsattrak.awareness.gnss.net.GnssStationCatalogClient;
import jsattrak.awareness.gnss.stations.GnssReferenceStation;
import jsattrak.awareness.gnss.stations.GnssStationNetwork;
import jsattrak.awareness.gnss.stations.GnssStationService;
import jsattrak.awareness.gnss.stations.GnssStationStatus;
import jsattrak.awareness.gnss.stations.analysis.GnssSppSolver;
import jsattrak.awareness.gnss.stations.analysis.GnssStationAnalysis;
import jsattrak.awareness.gnss.stations.analysis.GnssStationAnalysis.Cn0ElevationPoint;
import jsattrak.awareness.gnss.stations.analysis.GnssStationAnalysis.Cn0TimeSample;
import jsattrak.awareness.gnss.stations.analysis.GnssStationAnalysis.SatelliteMeasurementSummary;
import jsattrak.awareness.gnss.stations.analysis.GnssStationAnalysis.TrackingState;
import jsattrak.awareness.gnss.stations.analysis.RegionalScanReport;
import jsattrak.awareness.gnss.stations.analysis.RegionalScanReport.RegionalEventSeverity;
import jsattrak.awareness.gnss.stations.analysis.RegionalScanReport.StationAnomalyLevel;
import jsattrak.awareness.gnss.stations.analysis.RegionalScanReport.StationAnomalySummary;
import jsattrak.awareness.gui.LiveAwarenessInternalFrame;
import jsattrak.gui.JSatTrak;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GnssStationsGuiTest {

    private static final Instant T0 = Instant.parse("2026-11-25T19:00:00Z");

    @Test
    void buildsFourTabHubPaintsMeasurementWidgetsAndHandlesMatchSimEpochAndAnalyzeInDopHandoff(
            @TempDir Path tmpDir) throws Exception {
        JSatTrak.initOrekitData();
        AwarenessSettingsStore store = new AwarenessSettingsStore(tmpDir.resolve("awareness.json"));
        AwarenessSettings settings = AwarenessSettings.createDefaults();
        settings.setAutoFetchOnStartup(false);
        store.save(settings);

        LiveAwarenessService launchSvc = LiveAwarenessService.createForTestWithLaunches(tmpDir, List.of());
        GnssAwarenessService gnssSvc = GnssAwarenessService.createForTest(
                tmpDir, List.of(), List.of(), Map.of(), Map.of(), List.of());
        gnssSvc.syncSettingsSilently(settings);
        GnssGeometryService geometrySvc = new GnssGeometryService(gnssSvc, store);

        GnssReferenceStation kiru = new GnssReferenceStation(
                "KIRU00SWE", "KIRU", "Kiruna", "SWE",
                GnssStationNetwork.IGS, Set.of(GnssStationNetwork.IGS, GnssStationNetwork.EUREF),
                67.857, 20.968, 391.0, null,
                "SEPTENTRIO POLARX5", "5.5.0", "SEPCHOKE_B3E6", "NONE",
                EnumSet.of(GnssConstellation.GPS, GnssConstellation.GALILEO),
                List.of("RINEX3"), T0.toEpochMilli(), 15, 99.8, GnssStationStatus.ONLINE, null);

        GnssStationCatalogClient mockCat = new GnssStationCatalogClient() {
            @Override
            public CatalogSyncResult fetchMergedCatalog(
                    List<jsattrak.awareness.config.DataSourceConfig> cat,
                    List<jsattrak.awareness.config.DataSourceConfig> arc,
                    long nowMillis) {
                return new CatalogSyncResult(List.of(kiru), "1 station");
            }
        };

        GnssStationService stationSvc = new GnssStationService(
                gnssSvc, geometrySvc, store, tmpDir.resolve("stations-cache.json"),
                mockCat, List.of(), Clock.fixed(T0, ZoneOffset.UTC));

        try {
            java.util.concurrent.CountDownLatch syncLatch = new java.util.concurrent.CountDownLatch(1);
            stationSvc.addListener(new jsattrak.awareness.gnss.stations.GnssStationListener() {
                @Override
                public void onStationCatalogChanged() {
                    syncLatch.countDown();
                }
            });
            stationSvc.refreshNowAsync();
            assertTrue(syncLatch.await(5, java.util.concurrent.TimeUnit.SECONDS), "Timed out waiting for catalog sync");

            GnssStationAnalysis analysis = buildSampleAnalysis(kiru);
            RegionalScanReport report = buildSampleReport(kiru);

            SwingUtilities.invokeAndWait(() -> {
                LiveAwarenessInternalFrame frame = new LiveAwarenessInternalFrame(
                        launchSvc, gnssSvc, geometrySvc, stationSvc, null,
                        epoch -> {}, (lat, lon) -> {}, r -> {}, () -> {}, () -> {},
                        s -> {}, s -> false);
                try {
                    assertEquals(4, frame.getFeedTabbedPane().getTabCount());
                    assertTrue(frame.getFeedTabbedPane().getTitleAt(3).startsWith("GNSS Stations"),
                            "Expected 4th tab 'GNSS Stations', got " + frame.getFeedTabbedPane().getTitleAt(3));
                    assertNotNull(frame.getGnssStationsTab());

                    // 2. Paint all 3 custom Java2D panels onto BufferedImage(480, 320)
                    GnssObservedSkyplotPanel skyplot = new GnssObservedSkyplotPanel();
                    skyplot.setAnalysis(analysis);
                    assertPaintedNonEmpty(skyplot);

                    GnssCn0ChartPanel chart = new GnssCn0ChartPanel();
                    chart.setAnalysis(analysis, TimeZone.getTimeZone("UTC"));
                    for (GnssCn0ChartPanel.ChartMode mode : GnssCn0ChartPanel.ChartMode.values()) {
                        chart.setChartMode(mode);
                        assertPaintedNonEmpty(chart);
                    }

                    GnssCn0MatrixPanel matrix = new GnssCn0MatrixPanel(stationId -> {});
                    matrix.setReport(report);
                    assertPaintedNonEmpty(matrix);

                    // 3. Toggle Match Sim Epoch checkbox -> Fetch button switches text
                    GnssStationsTab tab = frame.getGnssStationsTab();
                    assertEquals("Fetch Latest Obs", tab.getFetchButton().getText());
                    JCheckBox matchBox = tab.getMatchSimEpochCheckBox();
                    assertFalse(matchBox.isSelected());
                    matchBox.doClick();
                    assertTrue(matchBox.isSelected());
                    assertEquals("Fetch Sim Obs", tab.getFetchButton().getText());

                    // 4. Select KIRU00SWE & trigger Analyze in GNSS Geometry handoff
                    stationSvc.selectStation("KIRU00SWE");
                    tab.getAnalyzeInDopButton().doClick();
                    assertSame(frame.getGnssGeometryTab(), frame.getFeedTabbedPane().getSelectedComponent());
                    assertEquals(kiru.latDeg(), geometrySvc.getObserver().latDeg(), 1e-3);
                    assertEquals(kiru.lonDeg(), geometrySvc.getObserver().lonDeg(), 1e-3);
                } finally {
                    frame.dispose();
                }
            });
        } finally {
            stationSvc.shutdown();
            geometrySvc.shutdown();
            gnssSvc.shutdown();
            launchSvc.shutdown();
        }
    }

    private static void assertPaintedNonEmpty(javax.swing.JComponent comp) {
        comp.setSize(480, 320);
        BufferedImage img = new BufferedImage(480, 320, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        comp.paint(g2);
        g2.dispose();
        int first = img.getRGB(0, 0);
        boolean differs = false;
        for (int y = 0; y < img.getHeight() && !differs; y += 2) {
            for (int x = 0; x < img.getWidth(); x += 2) {
                if (img.getRGB(x, y) != first) {
                    differs = true;
                    break;
                }
            }
        }
        assertTrue(differs, "Expected non-empty paint output for " + comp.getClass().getSimpleName());
    }

    private static GnssStationAnalysis buildSampleAnalysis(GnssReferenceStation st) {
        long t = T0.toEpochMilli();
        List<SatelliteMeasurementSummary> sats = List.of(
                new SatelliteMeasurementSummary("G01", GnssConstellation.GPS, TrackingState.TRACKED,
                        45.0, 62.0, 47.0, 45.0, 45.5, 48.0, 48.5, -0.4, 0, 0.22, 10),
                new SatelliteMeasurementSummary("E11", GnssConstellation.GALILEO, TrackingState.TRACKED,
                        180.0, 38.0, 34.0, 30.0, 31.0, 36.0, 37.0, -9.8, 2, 0.45, 10),
                new SatelliteMeasurementSummary("G07", GnssConstellation.GPS, TrackingState.MISSING,
                        270.0, 52.0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, Double.NaN, 0));
        List<Cn0ElevationPoint> scatter = List.of(
                new Cn0ElevationPoint("G01", GnssConstellation.GPS, 62.0, 47.0, -0.4, false),
                new Cn0ElevationPoint("E11", GnssConstellation.GALILEO, 38.0, 34.0, -9.8, true));
        List<Cn0TimeSample> series = List.of(
                new Cn0TimeSample(t - 60_000L, "G01", GnssConstellation.GPS, 62.0, 47.5, 0.1),
                new Cn0TimeSample(t, "G01", GnssConstellation.GPS, 62.0, 47.0, -0.4),
                new Cn0TimeSample(t - 60_000L, "E11", GnssConstellation.GALILEO, 38.0, 44.0, 0.2),
                new Cn0TimeSample(t, "E11", GnssConstellation.GALILEO, 38.0, 34.0, -9.8));
        GnssSppSolver.SppEpochResult sppEpoch = new GnssSppSolver.SppEpochResult(
                t, true, new double[]{2244956.0, 860516.0, 5888128.0}, 1.2, 2.1, 2.4, 0.8, 1.8, 12, "BRDC");
        GnssSppSolver.SppSessionSummary spp = new GnssSppSolver.SppSessionSummary(
                sppEpoch, 1.1, 1.8, 1.9, 3.0, 2);
        return new GnssStationAnalysis(
                st, t, false, 10.0, 3, 2, 1, 66.7,
                40.5, -5.1, 2, 1, 1, 10.0, 0.31, 0.35, spp, sats, scatter, series);
    }

    private static RegionalScanReport buildSampleReport(GnssReferenceStation st) {
        return new RegionalScanReport(
                RegionalEventSeverity.LOCAL_DEGRADATION,
                "Degradation on Galileo at KIRU",
                EnumSet.of(GnssConstellation.GALILEO),
                st.latDeg(),
                st.lonDeg(),
                25.0,
                List.of(new StationAnomalySummary(
                        st, 0.0, StationAnomalyLevel.ALERT, -9.8, 0.33, 10.0, 1.2,
                        EnumSet.of(GnssConstellation.GALILEO),
                        T0.toEpochMilli() - 60_000L, T0.toEpochMilli(), "Low C/N0")),
                List.of("G01", "E11"),
                Map.of(st.stationId(), Map.of("G01", -0.4, "E11", -9.8)));
    }
}

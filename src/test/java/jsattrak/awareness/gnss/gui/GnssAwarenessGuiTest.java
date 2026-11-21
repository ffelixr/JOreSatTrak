package jsattrak.awareness.gnss.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JCheckBox;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import jsattrak.awareness.LiveAwarenessService;
import jsattrak.awareness.config.AwarenessSettings;
import jsattrak.awareness.config.AwarenessSettingsStore;
import jsattrak.awareness.config.DataSourceConfig;
import jsattrak.awareness.gnss.BroadcastHealthRecord;
import jsattrak.awareness.gnss.GnssAwarenessService;
import jsattrak.awareness.gnss.GnssCatalogEntry;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.GnssDisplayStatus;
import jsattrak.awareness.gnss.GnssManualStatusList;
import jsattrak.awareness.gnss.GnssManualStatusStore;
import jsattrak.awareness.gnss.GnssOrbitClass;
import jsattrak.awareness.gnss.GnssOutageNotice;
import jsattrak.awareness.gnss.GnssSatelliteState;
import jsattrak.awareness.gnss.OperatorStatusRecord;
import jsattrak.awareness.gnss.net.BroadcastNavClient;
import jsattrak.awareness.gnss.net.GnssCatalogClient;
import jsattrak.awareness.gnss.net.OperatorStatusClient;
import jsattrak.utilities.GroundStationCatalogRepository;
import jsattrak.awareness.gui.LiveDataSourcesDialog;
import jsattrak.awareness.net.LaunchApiClient;
import jsattrak.awareness.tle.TleFeedClient;
import jsattrak.gui.JSatTrak;
import jsattrak.utilities.TLE;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class GnssAwarenessGuiTest {

    @BeforeAll
    static void setupHeadlessAndOrekit() {
        System.setProperty("java.awt.headless", "true");
        JSatTrak.initOrekitData();
    }

    @Test
    void gnssTabFiltersSortsAndUpdatesInspectorEvidenceAndActions(@TempDir Path tmpDir) throws Exception {
        long now = 1_795_132_800_000L; // 2026-11-20T00:00:00Z
        long plannedStart = now + 3_600_000L;
        long plannedEnd = now + 7_200_000L;

        List<GnssCatalogEntry> catalog = List.of(
                new GnssCatalogEntry("G01", GnssConstellation.GPS, 1, "G063", 37753, "2011-036A", "GPS-IIF", GnssOrbitClass.MEO, "D", "2", "2011-07-16"),
                new GnssCatalogEntry("G07", GnssConstellation.GPS, 7, "G055", 32711, "2008-012A", "GPS-IIR-M", GnssOrbitClass.MEO, "A", "4", "2008-03-15"),
                new GnssCatalogEntry("G18", GnssConstellation.GPS, 18, "G075", 48859, "2021-054A", "GPS-III", GnssOrbitClass.MEO, "E", "1", "2021-06-17"),
                new GnssCatalogEntry("E26", GnssConstellation.GALILEO, 26, "E203", 40128, "2014-050A", "GAL-FOC", GnssOrbitClass.MEO, "B", "1", "2014-08-22"),
                new GnssCatalogEntry("R05", GnssConstellation.GLONASS, 5, "R757", 43508, "2018-053A", "GLONASS-M", GnssOrbitClass.MEO, "1", "5", "2018-06-16")
        );

        TLE g01Tle = new TLE("GPS BIIF-2 (PRN 01)",
                "1 37753U 11036A   26312.06320080  .00000016  00000+0  00000+0 0  9991",
                "2 37753  55.1200 120.4500 0050000 305.0010 182.1874  2.00558916192834");
        TLE g18Tle = new TLE("GPS III SV05 (PRN 18)",
                "1 48859U 21054A   26312.06320080  .00000016  00000+0  00000+0 0  9992",
                "2 48859  54.9000 240.4500 0030000 110.0010 120.1874  2.00558916192835");

        Map<String, BroadcastHealthRecord> health = Map.of();
        Map<String, OperatorStatusRecord> opStatus = Map.of(
                "G01", new OperatorStatusRecord("G01", GnssConstellation.GPS, "NAVCEN", OperatorStatusRecord.OperatorHealth.USABLE, "USABLE", now, "Operational"),
                "G07", new OperatorStatusRecord("G07", GnssConstellation.GPS, "NAVCEN", OperatorStatusRecord.OperatorHealth.NOT_AVAILABLE, "NOT AVAILABLE", now, "Unusable"),
                "G18", new OperatorStatusRecord("G18", GnssConstellation.GPS, "NAVCEN", OperatorStatusRecord.OperatorHealth.USABLE, "USABLE", now, "Operational"),
                "E26", new OperatorStatusRecord("E26", GnssConstellation.GALILEO, "GSC", OperatorStatusRecord.OperatorHealth.TESTING, "TESTING", now, "Under commissioning")
        );
        List<GnssOutageNotice> notices = List.of(
                new GnssOutageNotice("NANU-2026001", GnssConstellation.GPS, "G18", "G075", "FCSTDV",
                        true, false, plannedStart, plannedEnd, "Planned delta-V maintenance", "NAVCEN")
        );

        GnssAwarenessService service = GnssAwarenessService.createForTest(
                tmpDir, catalog, List.of(g01Tle, g18Tle), health, opStatus, notices);

        AtomicReference<Long> jumpedEpoch = new AtomicReference<>();
        AtomicReference<double[]> centeredCoords = new AtomicReference<>();
        List<GnssSatelliteState> addedSats = new ArrayList<>();

        runOnEdt(() -> {
            GnssAwarenessTab tab = new GnssAwarenessTab(
                    service,
                    jumpedEpoch::set,
                    (lat, lon) -> centeredCoords.set(new double[] {lat, lon}),
                    addedSats::add,
                    state -> state != null && "G01".equals(state.catalog().prnId()));
            try {
                tab.refreshDisplay(now, false, TimeZone.getTimeZone("UTC"));

                JTable table = tab.getSatelliteTable();
                assertEquals(5, table.getRowCount());
                // Order: UNAVAILABLE -> TESTING -> NO_STATUS_DATA -> OPERATIONAL
                assertEquals("G07", table.getValueAt(0, 0));
                assertEquals("E26", table.getValueAt(1, 0));
                assertEquals("R05", table.getValueAt(2, 0));
                assertEquals("G01", table.getValueAt(3, 0));
                assertEquals("G18", table.getValueAt(4, 0));

                // Toggle GPS constellation off -> GPS rows disappear, then re-enable
                JCheckBox gpsBox = tab.getConstellationCheckBox(GnssConstellation.GPS);
                assertNotNull(gpsBox);
                gpsBox.doClick();
                assertEquals(2, table.getRowCount());
                assertEquals("E26", table.getValueAt(0, 0));
                assertEquals("R05", table.getValueAt(1, 0));
                gpsBox.doClick();
                assertEquals(5, table.getRowCount());

                // Select G18 -> inspector shows planned notice & Jump Sim button invokes outage start
                table.setRowSelectionInterval(4, 4);
                GnssDetailsInspectorPanel inspector = tab.getInspectorPanel();
                assertTrue(inspector.getNoticeBannerText().contains("Planned delta-V maintenance")
                        || inspector.getOutageInfoText().contains("NANU-2026001"));
                assertTrue(inspector.getJumpToOutageButton().isEnabled());
                inspector.getJumpToOutageButton().doClick();
                assertEquals(plannedStart, jumpedEpoch.get());

                // Select G01 -> marked in Object List -> Add button disabled and says "In Object List"
                table.setRowSelectionInterval(3, 3);
                assertFalse(inspector.getAddToObjectListButton().isEnabled());
                assertEquals("In Object List", inspector.getAddToObjectListButton().getText());

                // Refresh with simEpoch = lastSync + 48h in Non-Real-Time mode -> sim-clock pill & >24h note visible
                long futureSim = service.getLastSuccessfulSyncMillis() + 48L * 3_600_000L;
                tab.refreshDisplay(futureSim, false, TimeZone.getTimeZone("UTC"));
                assertTrue(tab.getSimClockPill().isVisible());
                assertTrue(tab.getSyncDivergenceLabel().isVisible());

                // Verify disabled SBAS placeholder row exists in constellation strip
                assertNotNull(tab.getSbasPlaceholderComponent());
                assertFalse(tab.getSbasPlaceholderComponent().isEnabled());
            } finally {
                tab.dispose();
            }
        });
    }

    @Test
    void manualStatusDialogSavesOverrideAndClearsStaleWarning(@TempDir Path tmpDir) throws Exception {
        AwarenessSettingsStore settingsStore = new AwarenessSettingsStore(tmpDir.resolve("awareness-settings.json"));
        GnssManualStatusStore manualStore = new GnssManualStatusStore(tmpDir.resolve("gnss-manual-status.json"));
        manualStore.save(new GnssManualStatusList("2025-01-01", List.of()));

        List<GnssCatalogEntry> catalog = List.of(
                new GnssCatalogEntry("R24", GnssConstellation.GLONASS, 24, "R801", 40001, "2014-010A", "GLONASS-M", GnssOrbitClass.MEO, "3", "24", "2014-03-23")
        );
        GnssAwarenessService service = new GnssAwarenessService(
                settingsStore,
                tmpDir.resolve("gnss-cache.json"),
                manualStore,
                null,
                new GnssCatalogClient() {
                    @Override
                    public CatalogFetchResult fetchCatalog(List<DataSourceConfig> sources, long refEpoch) {
                        return new CatalogFetchResult(catalog, "Test");
                    }
                },
                new TleFeedClient(),
                new BroadcastNavClient(),
                new OperatorStatusClient(),
                Clock.systemUTC());
        service.refreshBlockingForTest();
        assertTrue(service.isManualListStale());

        runOnEdt(() -> {
            GnssManualStatusDialog.ManualStatusEditorPanel editor =
                    new GnssManualStatusDialog.ManualStatusEditorPanel(service, null);
            editor.addEntry("R24", GnssDisplayStatus.TESTING, "flight test");
            editor.markReviewedToday();
            editor.save();
            assertTrue(editor.isSaved());
        });

        assertFalse(service.isManualListStale());
        var state = service.getSatelliteState("R24", System.currentTimeMillis()).orElseThrow();
        assertEquals(GnssDisplayStatus.TESTING, state.statusResult().status());
        assertFalse(state.statusResult().manualOperationalOverride());
    }

    @Test
    void liveDataSourcesDialogPreservesLaunchesWhenEditingGnssSources(@TempDir Path tmpDir) throws Exception {
        AwarenessSettingsStore store = new AwarenessSettingsStore(tmpDir.resolve("awareness-settings.json"));
        AwarenessSettings initial = AwarenessSettings.createDefaults();
        store.save(initial);

        LiveAwarenessService launchService = new LiveAwarenessService(
                store,
                tmpDir.resolve("launch-cache.json"),
                new LaunchApiClient(),
                new TleFeedClient(),
                new GroundStationCatalogRepository(
                        tmpDir.resolve("base.csv").toFile(),
                        tmpDir.resolve("custom.csv").toFile(),
                        tmpDir.resolve("launches.csv").toFile()));

        GnssAwarenessService gnssService = new GnssAwarenessService(
                store,
                tmpDir.resolve("gnss-cache.json"),
                new GnssManualStatusStore(tmpDir.resolve("gnss-manual.json")),
                null,
                new GnssCatalogClient(),
                new TleFeedClient(),
                new BroadcastNavClient(),
                new OperatorStatusClient(),
                Clock.systemUTC());

        int originalLaunchSourceCount = launchService.getSettings().getEnabledSourcesByCategory(
                DataSourceConfig.FeedCategory.LAUNCH).size();
        String originalLaunchUrl = launchService.getSettings()
                .getEnabledSourcesByCategory(DataSourceConfig.FeedCategory.LAUNCH).get(0).getEndpointUrl();

        runOnEdt(() -> {
            LiveDataSourcesDialog.DataSourcesEditorPanel panel =
                    new LiveDataSourcesDialog.DataSourcesEditorPanel(
                            launchService,
                            gnssService,
                            new LaunchApiClient(),
                            new TleFeedClient(),
                            new GnssCatalogClient(),
                            new BroadcastNavClient(),
                            new OperatorStatusClient(),
                            null);
            panel.setGnssFeedEnabledForTest(false);
            panel.updateSourceUrlById("bkg-brdc-nav", "https://igs.bkg.bund.de/custom/BRDC/");
            panel.save();
            assertTrue(panel.isSaved());
        });

        assertFalse(gnssService.isFeedEnabled());
        String updatedBkgUrl = gnssService.getSettings().getSources().stream()
                .filter(s -> "bkg-brdc-nav".equals(s.getId()))
                .map(DataSourceConfig::getEndpointUrl)
                .findFirst().orElse("");
        assertEquals("https://igs.bkg.bund.de/custom/BRDC/", updatedBkgUrl);

        assertEquals(originalLaunchSourceCount, launchService.getSettings().getEnabledSourcesByCategory(
                DataSourceConfig.FeedCategory.LAUNCH).size());
        assertEquals(originalLaunchUrl, launchService.getSettings()
                .getEnabledSourcesByCategory(DataSourceConfig.FeedCategory.LAUNCH).get(0).getEndpointUrl());
    }

    @Test
    void sourcesDialogPreservesRuntimeGnssPrefsSupportsProviderScopeTabClockReuseAndHelpContent(@TempDir Path tmpDir)
            throws Exception {
        AwarenessSettingsStore store = new AwarenessSettingsStore(tmpDir.resolve("awareness-settings.json"));
        store.save(AwarenessSettings.createDefaults());

        LiveAwarenessService launchService = new LiveAwarenessService(
                store, tmpDir.resolve("launch-cache.json"), new LaunchApiClient(), new TleFeedClient(),
                new GroundStationCatalogRepository(
                        tmpDir.resolve("base.csv").toFile(),
                        tmpDir.resolve("custom.csv").toFile(),
                        tmpDir.resolve("launches.csv").toFile()));
        GnssAwarenessService gnssService = GnssAwarenessService.createForTest(
                tmpDir, List.of(
                        new GnssCatalogEntry("G01", GnssConstellation.GPS, 1, "G063", 37753, "2011-036A", "GPS-IIF", GnssOrbitClass.MEO, "D", "2", "2011-07-16"),
                        new GnssCatalogEntry("E11", GnssConstellation.GALILEO, 11, "E101", 37846, "2011-060A", "GAL-FOC", GnssOrbitClass.MEO, "B", "1", "2011-10-21")),
                List.of(),
                Map.of(),
                Map.of("E11", new OperatorStatusRecord("E11", GnssConstellation.GALILEO, "GSC", OperatorStatusRecord.OperatorHealth.USABLE, "USABLE", 1_795_132_800_000L, "Nominal")),
                List.of());

        // Toggle GNSS UI preferences during session without writing via LiveAwarenessService
        gnssService.setConstellationEnabled(GnssConstellation.GPS, false);
        gnssService.setShowOrbitRings3d(false);

        runOnEdt(() -> {
            LiveDataSourcesDialog.DataSourcesEditorPanel editor = new LiveDataSourcesDialog.DataSourcesEditorPanel(
                    launchService, gnssService, new LaunchApiClient(), new TleFeedClient(),
                    new GnssCatalogClient(), new BroadcastNavClient(), new OperatorStatusClient(), null);
            assertNotNull(editor.getProviderScopeCombo());
            editor.getCategoryTabs().setSelectedIndex(DataSourceConfig.FeedCategory.GNSS_OPERATOR_STATUS.ordinal());
            assertTrue(editor.getProviderScopeCombo().isVisible());
            editor.save();
            assertTrue(editor.isSaved());

            assertFalse(gnssService.isConstellationEnabled(GnssConstellation.GPS));
            assertFalse(gnssService.isShowOrbitRings3d());

            // Clock-tick refresh must preserve inspector evidence components rather than tearing down panel
            GnssAwarenessTab tab = new GnssAwarenessTab(gnssService, t -> {}, (la, lo) -> {}, s -> {}, s -> false);
            try {
                tab.refreshDisplay(1_795_132_800_000L, false, TimeZone.getTimeZone("UTC"));
                tab.getSatelliteTable().setRowSelectionInterval(0, 0);
                var firstEvidenceBefore = tab.getInspectorPanel().getEvidencePanel().getComponent(0);
                tab.refreshDisplay(1_795_132_805_000L, false, TimeZone.getTimeZone("UTC"));
                assertSame(firstEvidenceBefore, tab.getInspectorPanel().getEvidencePanel().getComponent(0));
            } finally {
                tab.dispose();
            }
        });

        String helpHtml = JSatTrak.buildGnssHelpHtml();
        assertTrue(helpHtml.contains("IGS"));
        assertTrue(helpHtml.contains("BKG"));
        assertTrue(helpHtml.contains("NAVCEN"));
        assertTrue(helpHtml.contains("GSC"));
        assertTrue(helpHtml.contains("Operational"));
        assertTrue(helpHtml.contains("Unavailable"));
        assertTrue(helpHtml.contains("Testing"));
        assertTrue(helpHtml.contains("Add to Object List"));
    }

    private static void runOnEdt(ThrowingRunnable action) throws Exception {
        AtomicReference<Throwable> err = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                err.set(t);
            }
        });
        if (err.get() != null) {
            Throwable t = err.get();
            if (t instanceof Exception ex) {
                throw ex;
            }
            throw new AssertionError(t);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}

package jsattrak.awareness.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.TableModelEvent;
import jsattrak.awareness.AwarenessStateListener;
import jsattrak.awareness.LaunchRecord;
import jsattrak.awareness.LaunchUrgency;
import jsattrak.awareness.LiveAwarenessService;
import jsattrak.awareness.config.AuthMode;
import jsattrak.awareness.config.AwarenessSettings;
import jsattrak.awareness.config.AwarenessSettingsStore;
import jsattrak.awareness.config.DataSourceConfig;
import jsattrak.awareness.net.ConnectionTestResult;
import jsattrak.awareness.net.LaunchApiClient;
import jsattrak.awareness.tle.LaunchTleStatus;
import jsattrak.awareness.tle.PayloadTleMatch;
import jsattrak.awareness.tle.TleFeedClient;
import jsattrak.utilities.GroundStationCatalogRepository;
import jsattrak.utilities.TLE;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LiveAwarenessGuiTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    public void testPayloadTableModelSmartDefaultsAndDuplicateFlag() {
        TLE t1 = new TLE("SAT-A",
                "1 61001U 26142A   26324.50000000  .00001000  00000-0  50000-4 0  9991",
                "2 61001  53.0000 100.0000 0001500  90.0000 270.0000 15.10000000    15");
        TLE t2 = new TLE("FALCON 9 R/B",
                "1 61002U 26142B   26324.50000000  .00001000  00000-0  50000-4 0  9992",
                "2 61002  53.0000 100.0000 0001500  90.0000 270.0000 15.10000000    16");
        TLE t3 = new TLE("SAT-EXISTING",
                "1 61003U 26142C   26324.50000000  .00001000  00000-0  50000-4 0  9993",
                "2 61003  53.0000 100.0000 0001500  90.0000 270.0000 15.10000000    17");
        TLE t4 = new TLE("FALCON 9 DEB",
                "1 61004U 26142D   26324.50000000  .00001000  00000-0  50000-4 0  9994",
                "2 61004  53.0000 100.0000 0001500  90.0000 270.0000 15.10000000    18");

        List<PayloadTleMatch> items = List.of(
                new PayloadTleMatch("SAT-A", "2026-142A", 61001, PayloadTleMatch.ObjectType.PAYLOAD, "Official", false, false, t1),
                new PayloadTleMatch("FALCON 9 R/B", "2026-142B", 61002, PayloadTleMatch.ObjectType.ROCKET_BODY, "Official", false, false, t2),
                new PayloadTleMatch("SAT-EXISTING", "2026-142C", 61003, PayloadTleMatch.ObjectType.PAYLOAD, "Official", false, true, t3),
                new PayloadTleMatch("FALCON 9 DEB", "2026-142D", 61004, PayloadTleMatch.ObjectType.DEBRIS, "Official", false, false, t4)
        );
        PayloadTleImportDialog.PayloadTleTableModel model = new PayloadTleImportDialog.PayloadTleTableModel(items);

        assertEq(true, model.isSelected(0));   // new payload pre-checked
        assertEq(false, model.isSelected(1));  // rocket body unchecked
        assertEq(false, model.isSelected(2));  // already-tracked unchecked by default
        assertEq(false, model.isSelected(3));  // debris unchecked
        assertEq(1, model.getSelectedMatches().size());
        assertEq("SAT-A", model.getSelectedMatches().get(0).objectName());

        model.selectAllPayloads();
        assertEq(true, model.isSelected(0));
        assertEq(false, model.isSelected(1));
        assertEq(false, model.isSelected(2));  // still respects alreadyTracked unless user checks it
        assertEq(false, model.isSelected(3));

        model.setSelected(2, true);
        assertEq(true, model.isSelected(2));
        assertEq(2, model.getSelectedMatches().size());

        model.selectAll();
        assertEq(4, model.getSelectedMatches().size());

        model.clearSelection();
        assertEq(0, model.getSelectedMatches().size());

        // Column 5 formats source and parsed TLE epoch (26324.5 -> 2026-11-20 12:00 UTC)
        String sourceEpoch = model.getValueAt(0, 5).toString();
        assertEq(true, sourceEpoch.contains("Official") && sourceEpoch.contains("2026-11-20 12:00 UTC"));
    }

    @Test
    public void testPayloadPickerPanelControlsAndUpdateFlag() throws Exception {
        TLE t1 = new TLE("SAT-A", "1 61001U ...", "2 61001 ...");
        TLE t2 = new TLE("FALCON 9 R/B", "1 61002U ...", "2 61002 ...");
        LaunchRecord launch = sample("1", "Starlink 12-4", "SpaceX", "Falcon 9", "Cape Canaveral",
                1_795_132_800_000L, 1_795_136_400_000L);
        List<PayloadTleMatch> items = List.of(
                new PayloadTleMatch("SAT-A", "2026-142A", 61001, PayloadTleMatch.ObjectType.PAYLOAD, "SupGP", true, false, t1),
                new PayloadTleMatch("FALCON 9 R/B", "2026-142B", 61002, PayloadTleMatch.ObjectType.ROCKET_BODY, "SupGP", true, false, t2)
        );

        runOnEdt(() -> {
            AtomicBoolean confirmed = new AtomicBoolean(false);
            PayloadTleImportDialog.PayloadTlePickerPanel panel =
                    new PayloadTleImportDialog.PayloadTlePickerPanel(launch, items, () -> confirmed.set(true), () -> {});
            assertEq(1, panel.getSelectedMatches().size());
            assertEq(true, panel.getAddSelectedButton().isEnabled());
            assertEq(true, panel.isUpdateExistingEnabled());

            panel.getClearButton().doClick();
            assertEq(0, panel.getSelectedMatches().size());
            assertEq(false, panel.getAddSelectedButton().isEnabled());

            panel.getSelectAllPayloadsButton().doClick();
            assertEq(1, panel.getSelectedMatches().size());
            assertEq(true, panel.getAddSelectedButton().isEnabled());

            panel.getUpdateExistingCheckBox().doClick();
            assertEq(false, panel.isUpdateExistingEnabled());

            panel.getAddSelectedButton().doClick();
            assertEq(true, confirmed.get());
        });
    }

    @Test
    public void testQuickFilterAndTimezoneWindowFormatting() {
        long net = 1_795_132_800_000L; // 2026-11-20 00:00 UTC
        LaunchRecord r1 = sample("1", "Sentinel-1D", "Arianespace", "Vega-C", "Kourou", net, net + 3600_000L);
        LaunchRecord r2 = sample("2", "Starlink 12-4", "SpaceX", "Falcon 9", "Cape Canaveral", net + 7200_000L, net + 10800_000L);

        List<LaunchRecord> filtered = LaunchesAwarenessTab.applySearchFilter(List.of(r1, r2), "kourou");
        assertEq(1, filtered.size());
        assertEq("1", filtered.get(0).getId());

        assertEq(2, LaunchesAwarenessTab.applySearchFilter(List.of(r1, r2), "   ").size());
        assertEq(1, LaunchesAwarenessTab.applySearchFilter(List.of(r1, r2), "SPACEX").size());

        String utcStr = LaunchDetailsInspectorPanel.formatLaunchWindow(r1, TimeZone.getTimeZone("UTC"));
        String plus2Str = LaunchDetailsInspectorPanel.formatLaunchWindow(r1, TimeZone.getTimeZone("GMT+2"));
        if (!utcStr.contains("00:00") || !plus2Str.contains("02:00")) {
            throw new AssertionError("Timezone formatting mismatch: utc=" + utcStr + ", gmt+2=" + plus2Str);
        }
    }

    @Test
    public void testDetailsInspectorTleStatusButtonsAndCallbacks() throws Exception {
        long net = 1_795_132_800_000L;
        LaunchRecord orbital = sample("orb-1", "Sentinel-1D", "Arianespace", "Vega-C", "Kourou", net, net + 3600_000L);
        LaunchRecord suborbital = LaunchRecord.builder()
                .id("sub-1")
                .missionName("Suborbital Test")
                .orbital(false)
                .netEpochMillis(net)
                .windowStartMillis(net)
                .windowEndMillis(net)
                .latDeg(32.9)
                .lonDeg(-106.9)
                .webcastUrl("javascript:alert(1)")
                .build();

        runOnEdt(() -> {
            AtomicReference<Long> jumpedEpoch = new AtomicReference<>();
            AtomicReference<double[]> centeredCoords = new AtomicReference<>();
            AtomicReference<LaunchRecord> importedLaunch = new AtomicReference<>();
            AtomicBoolean checkedNow = new AtomicBoolean(false);
            AtomicBoolean openedSatBrowser = new AtomicBoolean(false);

            LaunchDetailsInspectorPanel inspector = new LaunchDetailsInspectorPanel(
                    jumpedEpoch::set,
                    (lat, lon) -> centeredCoords.set(new double[]{lat, lon}),
                    importedLaunch::set,
                    () -> checkedNow.set(true),
                    () -> openedSatBrowser.set(true)
            );

            // NO_ORBITAL_PAYLOAD disables import and hides AWAITING_TLE fallbacks
            inspector.setLaunch(suborbital, LaunchTleStatus.NO_ORBITAL_PAYLOAD, 0, TimeZone.getTimeZone("UTC"));
            assertEq(false, inspector.getImportTlesButton().isEnabled());
            assertEq(false, inspector.getCheckNowButton().isVisible());
            assertEq(false, inspector.getOpenSatBrowserButton().isVisible());
            assertEq(false, inspector.getWebcastButton().isEnabled());

            // AWAITING_TLE disables import and shows Check Now + Open Satellite Browser
            inspector.setLaunch(orbital, LaunchTleStatus.AWAITING_TLE, 0, TimeZone.getTimeZone("GMT+2"));
            assertEq(false, inspector.getImportTlesButton().isEnabled());
            assertEq(true, inspector.getCheckNowButton().isVisible());
            assertEq(true, inspector.getOpenSatBrowserButton().isVisible());
            assertEq(true, inspector.getWebcastButton().isEnabled());
            assertEq(true, inspector.getDisplayedWindowText().contains("02:00"));

            inspector.getCheckNowButton().doClick();
            inspector.getOpenSatBrowserButton().doClick();
            assertEq(true, checkedNow.get());
            assertEq(true, openedSatBrowser.get());

            // TLES_READY enables import and hides fallbacks
            inspector.setLaunch(orbital, LaunchTleStatus.TLES_READY, 3, TimeZone.getTimeZone("UTC"));
            assertEq(true, inspector.getImportTlesButton().isEnabled());
            assertEq(false, inspector.getCheckNowButton().isVisible());
            assertEq(false, inspector.getOpenSatBrowserButton().isVisible());

            inspector.getJumpToT0Button().doClick();
            inspector.getCenterMapsButton().doClick();
            inspector.getImportTlesButton().doClick();
            assertEq(net, jumpedEpoch.get());
            assertEq(5.236, centeredCoords.get()[0]);
            assertEq(-52.768, centeredCoords.get()[1]);
            assertEq("orb-1", importedLaunch.get().getId());
        });

        assertEq(true, LaunchDetailsInspectorPanel.isSafeWebcastUrl("https://www.youtube.com/watch?v=123"));
        assertEq(true, LaunchDetailsInspectorPanel.isSafeWebcastUrl("http://example.org/stream"));
        assertEq(false, LaunchDetailsInspectorPanel.isSafeWebcastUrl("file:///etc/passwd"));
        assertEq(false, LaunchDetailsInspectorPanel.isSafeWebcastUrl("javascript:alert(1)"));
    }

    @Test
    public void testLaunchesTabModeHorizonSearchSimClockPillAndSelectionSync(@TempDir Path tmp) throws Exception {
        long now = 1_795_132_800_000L;
        LaunchRecord l1 = sample("l1", "Imminent Alpha", "SpaceX", "Falcon 9", "Cape Canaveral",
                now + 6L * 3600_000L, now + 7L * 3600_000L);
        LaunchRecord l2 = sample("l2", "Soon Beta", "Rocket Lab", "Electron", "Mahia",
                now + 48L * 3600_000L, now + 49L * 3600_000L);
        LaunchRecord l3 = sample("l3", "Week Gamma", "Arianespace", "Ariane 6", "Kourou",
                now + 5L * 86400_000L, now + 5L * 86400_000L + 3600_000L);

        LiveAwarenessService service = LiveAwarenessService.createForTestWithLaunches(tmp, List.of(l3, l2, l1));
        try {
            runOnEdt(() -> {
                LaunchesAwarenessTab tab = new LaunchesAwarenessTab(service, t -> {}, (lat, lon) -> {}, r -> {}, () -> {});
                tab.refreshDisplay(now, false, TimeZone.getTimeZone("UTC"));

                // Non-Real-Time mode displays Relative to Sim Clock pill
                assertEq(true, tab.getSimClockPill().isVisible());
                assertEq(3, tab.getLaunchesTable().getRowCount());
                // Sorted soonest-first
                assertEq(true, tab.getLaunchesTable().getValueAt(0, 2).toString().contains("Imminent Alpha"));

                // Real-Time mode hides pill
                tab.refreshDisplay(now, true, TimeZone.getTimeZone("UTC"));
                assertEq(false, tab.getSimClockPill().isVisible());

                // Horizon buttons filter 1d / 3d / 7d via service
                tab.getHorizon1dButton().doClick();
                assertEq(1, service.getSelectedHorizonDays());
                assertEq(1, tab.getLaunchesTable().getRowCount());

                tab.getHorizon3dButton().doClick();
                assertEq(3, service.getSelectedHorizonDays());
                assertEq(2, tab.getLaunchesTable().getRowCount());

                tab.getHorizon7dButton().doClick();
                assertEq(7, service.getSelectedHorizonDays());
                assertEq(3, tab.getLaunchesTable().getRowCount());

                // Quick search narrows table
                tab.getSearchField().setText("Kourou");
                assertEq(1, tab.getLaunchesTable().getRowCount());
                tab.getSearchField().setText("");
                assertEq(3, tab.getLaunchesTable().getRowCount());

                // Two-way selection sync: service -> table and table -> service
                service.selectLaunch("l2");
                assertEq(1, tab.getLaunchesTable().getSelectedRow());
                tab.getLaunchesTable().setRowSelectionInterval(2, 2);
                assertEq("l3", service.getSelectedLaunchId());

                // Master Show on maps toggle
                assertEq(true, tab.getShowOnMapsCheckBox().isSelected());
                tab.getShowOnMapsCheckBox().doClick();
                assertEq(false, service.isGlobalMapOverlayEnabled());

                // Collapsible inspector toggle
                assertEq(true, tab.isInspectorExpanded());
                tab.getInspectorToggleButton().doClick();
                assertEq(false, tab.isInspectorExpanded());

                tab.dispose();
            });
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void testLiveAwarenessInternalFrameRefreshAndCallbacks(@TempDir Path tmp) throws Exception {
        long now = 1_795_132_800_000L;
        LaunchRecord l1 = sample("l1", "Orbital One", "SpaceX", "Falcon 9", "Cape Canaveral",
                now + 3600_000L, now + 7200_000L);
        LiveAwarenessService service = LiveAwarenessService.createForTestWithLaunches(tmp, List.of(l1));
        try {
            runOnEdt(() -> {
                AtomicBoolean sourcesOpened = new AtomicBoolean(false);
                LiveAwarenessInternalFrame frame = new LiveAwarenessInternalFrame(
                        service,
                        t -> {},
                        (lat, lon) -> {},
                        r -> {},
                        () -> {},
                        () -> sourcesOpened.set(true));
                frame.refreshDisplay(now, true, TimeZone.getTimeZone("UTC"));
                assertEq("Launches (1)", frame.getFeedTabbedPane().getTitleAt(0));
                assertEq(true, frame.getSyncStatusLabel().getText().contains("Synced"));

                frame.getSourcesButton().doClick();
                assertEq(true, sourcesOpened.get());
                frame.dispose();
            });
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void testDataSourcesEditorReorderAuthToggleAndNonBlockingTestConnection(@TempDir Path tmp) throws Exception {
        LiveAwarenessService service = LiveAwarenessService.createForTestWithLaunches(tmp, List.of());
        CountDownLatch launchTestStarted = new CountDownLatch(1);
        CountDownLatch tleTestFinished = new CountDownLatch(1);

        LaunchApiClient stubLaunchClient = new LaunchApiClient() {
            @Override
            public ConnectionTestResult testConnection(DataSourceConfig source) {
                launchTestStarted.countDown();
                return new ConnectionTestResult(true, 200, 10, "OK (10 launches parsed)");
            }
        };
        TleFeedClient stubTleClient = new TleFeedClient() {
            @Override
            public ConnectionTestResult testConnection(DataSourceConfig source) {
                return new ConnectionTestResult(false, 401, 0, "HTTP 401 Unauthorized");
            }
        };

        AtomicReference<LiveDataSourcesDialog.DataSourcesEditorPanel> panelRef = new AtomicReference<>();
        try {
            runOnEdt(() -> {
                LiveDataSourcesDialog.DataSourcesEditorPanel panel = new LiveDataSourcesDialog.DataSourcesEditorPanel(
                        service, stubLaunchClient, stubTleClient, () -> {});
                panelRef.set(panel);

                // Toggle autoFetchOnStartup
                assertEq(false, panel.getAutoFetchCheckBox().isSelected());
                panel.getAutoFetchCheckBox().doClick();

                // Add second launch source and reorder priority Up
                assertEq(1, panel.getLaunchSourcesList().getModel().getSize());
                panel.getAddSourceButton().doClick();
                assertEq(2, panel.getLaunchSourcesList().getModel().getSize());
                assertEq(1, panel.getLaunchSourcesList().getSelectedIndex());

                panel.getDisplayNameField().setText("Mirror LL2");
                panel.getEndpointUrlField().setText("https://mirror.example.org/ll2/");
                panel.getAuthModeCombo().setSelectedItem(AuthMode.API_KEY_HEADER);
                panel.getAuthKeyNameField().setText("Authorization");
                panel.getAuthSecretField().setText("Token xyz");
                panel.getCompanionGsSyncCheckBox().setSelected(true);
                panel.selectRefreshIntervalMinutes(30);

                // Show/hide toggle reveals/masks password field and resets when switching sources
                assertEq(false, panel.getAuthSecretField().getEchoChar() == (char) 0);
                panel.getShowSecretCheckBox().doClick();
                assertEq(true, panel.getAuthSecretField().getEchoChar() == (char) 0);
                panel.getLaunchSourcesList().setSelectedIndex(0);
                assertEq(false, panel.getShowSecretCheckBox().isSelected());
                assertEq(false, panel.getAuthSecretField().getEchoChar() == (char) 0);
                panel.getLaunchSourcesList().setSelectedIndex(1);
                assertEq(false, panel.getShowSecretCheckBox().isSelected());
                assertEq(false, panel.getAuthSecretField().getEchoChar() == (char) 0);

                // Move second source up to priority 1
                panel.getMoveUpButton().doClick();
                assertEq(0, panel.getLaunchSourcesList().getSelectedIndex());

                // Verify all 5 AuthModes adjust key/username/secret field enablement cleanly
                panel.getAuthModeCombo().setSelectedItem(AuthMode.NONE);
                assertEq(false, panel.getAuthSecretField().isEnabled());
                panel.getAuthModeCombo().setSelectedItem(AuthMode.BASIC_AUTH);
                assertEq(true, panel.getAuthUsernameField().isEnabled());
                assertEq(true, panel.getAuthSecretField().isEnabled());
                panel.getAuthModeCombo().setSelectedItem(AuthMode.BEARER_TOKEN);
                assertEq(false, panel.getAuthKeyNameField().isEnabled());
                assertEq(true, panel.getAuthSecretField().isEnabled());
                panel.getAuthModeCombo().setSelectedItem(AuthMode.QUERY_PARAM);
                assertEq(true, panel.getAuthKeyNameField().isEnabled());
                assertEq(true, panel.getAuthSecretField().isEnabled());
                panel.getAuthModeCombo().setSelectedItem(AuthMode.API_KEY_HEADER);

                // Launch non-blocking Test Connection for Launch source
                panel.getTestConnectionButton().doClick();
            });

            assertEq(true, launchTestStarted.await(5, TimeUnit.SECONDS));
            waitForEdtCondition(() -> !panelRef.get().isTestInProgress()
                    && panelRef.get().getTestStatusLabel().getText().contains("10 launches parsed"), 5000);

            // Switch to TLE Sources tab and run Test Connection on TLE source
            runOnEdt(() -> {
                LiveDataSourcesDialog.DataSourcesEditorPanel panel = panelRef.get();
                panel.getCategoryTabs().setSelectedIndex(1);
                panel.getTestConnectionButton().doClick();
                tleTestFinished.countDown();
            });

            assertEq(true, tleTestFinished.await(5, TimeUnit.SECONDS));
            waitForEdtCondition(() -> !panelRef.get().isTestInProgress()
                    && panelRef.get().getTestStatusLabel().getText().contains("401"), 5000);

            // Save and verify persisted into LiveAwarenessService
            runOnEdt(() -> panelRef.get().getSaveButton().doClick());

            AwarenessSettings saved = service.getSettings();
            assertEq(true, saved.isAutoFetchOnStartup());
            List<DataSourceConfig> launchSources = saved.getEnabledSourcesByCategory(DataSourceConfig.FeedCategory.LAUNCH);
            assertEq(2, launchSources.size());
            assertEq("Mirror LL2", launchSources.get(0).getDisplayName());
            assertEq(1, launchSources.get(0).getPriority());
            assertEq(30, launchSources.get(0).getRefreshIntervalMinutes());
            assertEq(true, launchSources.get(0).isSyncFixedPadsToGroundStations());
            assertEq(AuthMode.API_KEY_HEADER, launchSources.get(0).getAuthMode());
            assertEq("Token xyz", launchSources.get(0).getAuthSecret());
            assertEq(2, launchSources.get(1).getPriority());
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void testRecentModeTleBadgesStaleStatusAndFlatLafThemes(@TempDir Path tmp) throws Exception {
        long realNow = System.currentTimeMillis();
        LaunchRecord recentOrbital = LaunchRecord.builder()
                .id("rec-orb")
                .missionName("Recent Orbital")
                .provider("SpaceX")
                .vehicle("Falcon 9")
                .spaceportName("Vandenberg")
                .cosparId("2026-999")
                .netEpochMillis(realNow - 4L * 3600_000L)
                .windowStartMillis(realNow - 4L * 3600_000L)
                .windowEndMillis(realNow - 3L * 3600_000L)
                .status("Success")
                .outcome("Success")
                .orbital(true)
                .latDeg(34.63)
                .lonDeg(-120.61)
                .build();
        LaunchRecord recentFailed = LaunchRecord.builder()
                .id("rec-fail")
                .missionName("Failed Attempt")
                .provider("TestOrg")
                .vehicle("SmallSat")
                .spaceportName("Kourou")
                .netEpochMillis(realNow - 8L * 3600_000L)
                .windowStartMillis(realNow - 8L * 3600_000L)
                .windowEndMillis(realNow - 8L * 3600_000L)
                .status("Failure")
                .outcome("Failure")
                .orbital(true)
                .latDeg(5.23)
                .lonDeg(-52.76)
                .build();

        AwarenessSettingsStore store = new AwarenessSettingsStore(tmp.resolve("settings.json"));
        LaunchApiClient mockLaunch = new LaunchApiClient() {
            private boolean failNext;

            @Override
            public LaunchFetchResult fetchUpcomingAndRecent(List<DataSourceConfig> sources, long nowMillis)
                    throws IOException {
                if (failNext) {
                    throw new IOException("Rate limited (429)");
                }
                failNext = true;
                return new LaunchFetchResult(List.of(), List.of(recentOrbital, recentFailed), "MockSource");
            }
        };
        TleFeedClient emptyTle = new TleFeedClient() {
            @Override
            public List<TLE> fetchTleCatalog(DataSourceConfig source) {
                return List.of();
            }
        };
        GroundStationCatalogRepository repo = new GroundStationCatalogRepository(
                tmp.resolve("base.csv").toFile(),
                tmp.resolve("custom.csv").toFile(),
                tmp.resolve("launches.csv").toFile());
        LiveAwarenessService service = new LiveAwarenessService(
                store, tmp.resolve("cache.json"), mockLaunch, emptyTle, repo);

        LookAndFeel previousLaf = UIManager.getLookAndFeel();
        try {
            service.refreshBlockingForTest();
            service.refreshBlockingForTest(); // triggers stale state while retaining cached recent launches
            assertEq(true, service.isDataStale());

            for (LookAndFeel laf : List.of(new FlatLightLaf(), new FlatDarkLaf())) {
                runOnEdt(() -> {
                    try {
                        UIManager.setLookAndFeel(laf);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                    LiveAwarenessInternalFrame frame = new LiveAwarenessInternalFrame(
                            service, t -> {}, (lat, lon) -> {}, r -> {}, () -> {}, () -> {});
                    frame.refreshDisplay(realNow, true, TimeZone.getTimeZone("UTC"));
                    assertEq(true, frame.getSyncStatusLabel().getText().startsWith("Stale"));

                    LaunchesAwarenessTab tab = frame.getLaunchesTab();
                    tab.getRecentButton().doClick();
                    assertEq(false, tab.isUpcomingMode());
                    assertEq(2, tab.getLaunchesTable().getRowCount());
                    assertEq("Awaiting TLE", tab.getLaunchesTable().getValueAt(0, 5));
                    assertEq("No Orbital Payload", tab.getLaunchesTable().getValueAt(1, 5));

                    // Exercise painting under both Light and Dark FlatLaf themes
                    frame.setSize(780, 560);
                    frame.doLayout();
                    BufferedImage img = new BufferedImage(780, 560, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g2 = img.createGraphics();
                    try {
                        frame.paint(g2);
                    } finally {
                        g2.dispose();
                    }
                    frame.dispose();
                });
            }
        } finally {
            service.shutdown();
            if (previousLaf != null) {
                runOnEdt(() -> {
                    try {
                        UIManager.setLookAndFeel(previousLaf);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Test
    public void testDataSourcesMoveDownRemoveManualIntervalCancelAndReset(@TempDir Path tmp) throws Exception {
        LiveAwarenessService service = LiveAwarenessService.createForTestWithLaunches(tmp, List.of());
        try {
            runOnEdt(() -> {
                AtomicBoolean closed = new AtomicBoolean(false);
                LiveDataSourcesDialog.DataSourcesEditorPanel panel = new LiveDataSourcesDialog.DataSourcesEditorPanel(
                        service, new LaunchApiClient(), new TleFeedClient(), () -> closed.set(true));

                panel.getAddSourceButton().doClick();
                assertEq(2, panel.getLaunchSourcesList().getModel().getSize());
                panel.getLaunchSourcesList().setSelectedIndex(0);
                panel.getMoveDownButton().doClick();
                assertEq(1, panel.getLaunchSourcesList().getSelectedIndex());

                panel.selectRefreshIntervalMinutes(0); // Manual only
                AwarenessSettings snap = panel.getWorkingSettingsSnapshot();
                assertEq(0, snap.getEnabledSourcesByCategory(DataSourceConfig.FeedCategory.LAUNCH).get(1)
                        .getRefreshIntervalMinutes());

                // Cancel discards edits
                panel.getAutoFetchCheckBox().setSelected(true);
                panel.getCancelButton().doClick();
                assertEq(true, closed.get());
                assertEq(false, panel.isSaved());
                assertEq(false, service.getSettings().isAutoFetchOnStartup());

                // Remove and Reset Defaults
                panel.getRemoveSourceButton().doClick();
                assertEq(1, panel.getLaunchSourcesList().getModel().getSize());
                panel.getResetDefaultsButton().doClick();
                assertEq(1, panel.getLaunchSourcesList().getModel().getSize());
                assertEq(2, panel.getTleSourcesList().getModel().getSize());
            });
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void testOffEdtAwarenessStateListenerMarshalsToEdt(@TempDir Path tmp) throws Exception {
        long now = 1_795_132_800_000L;
        LaunchRecord l1 = sample("l1", "Launch One", "SpaceX", "Falcon 9", "Cape Canaveral",
                now + 4L * 3600_000L, now + 5L * 3600_000L);
        LaunchRecord l2 = sample("l2", "Launch Two", "Rocket Lab", "Electron", "Mahia",
                now + 12L * 3600_000L, now + 13L * 3600_000L);

        AwarenessSettingsStore store = new AwarenessSettingsStore(tmp.resolve("settings.json"));
        LaunchApiClient mockLaunch = new LaunchApiClient() {
            @Override
            public LaunchFetchResult fetchUpcomingAndRecent(List<DataSourceConfig> sources, long nowMillis) {
                return new LaunchFetchResult(List.of(l1, l2), List.of(), "OffEdtMock");
            }
        };
        TleFeedClient emptyTle = new TleFeedClient() {
            @Override
            public List<TLE> fetchTleCatalog(DataSourceConfig source) {
                return List.of();
            }
        };
        GroundStationCatalogRepository repo = new GroundStationCatalogRepository(
                tmp.resolve("base.csv").toFile(),
                tmp.resolve("custom.csv").toFile(),
                tmp.resolve("launches.csv").toFile());

        List<AwarenessStateListener> capturedListeners = new ArrayList<>();
        LiveAwarenessService service = new LiveAwarenessService(
                store, tmp.resolve("cache.json"), mockLaunch, emptyTle, repo) {
            @Override
            public void addListener(AwarenessStateListener listener) {
                super.addListener(listener);
                if (listener != null) {
                    capturedListeners.add(listener);
                }
            }
        };

        try {
            AtomicReference<LiveAwarenessInternalFrame> frameRef = new AtomicReference<>();
            AtomicBoolean offEdtSwingMutation = new AtomicBoolean(false);
            AtomicInteger edtEventsObserved = new AtomicInteger(0);

            runOnEdt(() -> {
                LiveAwarenessInternalFrame frame = new LiveAwarenessInternalFrame(
                        service, t -> {}, (lat, lon) -> {}, r -> {}, () -> {}, () -> {});
                frame.refreshDisplay(now, false, TimeZone.getTimeZone("UTC"));
                frame.getSyncStatusLabel().addPropertyChangeListener("text", evt -> {
                    if (!SwingUtilities.isEventDispatchThread()) {
                        offEdtSwingMutation.set(true);
                    } else {
                        edtEventsObserved.incrementAndGet();
                    }
                });
                frame.getLaunchesTab().getLaunchesTable().getModel().addTableModelListener(evt -> {
                    if (!SwingUtilities.isEventDispatchThread()) {
                        offEdtSwingMutation.set(true);
                    } else {
                        edtEventsObserved.incrementAndGet();
                    }
                });
                frame.getLaunchesTab().getLaunchesTable().getSelectionModel().addListSelectionListener(evt -> {
                    if (!SwingUtilities.isEventDispatchThread()) {
                        offEdtSwingMutation.set(true);
                    } else {
                        edtEventsObserved.incrementAndGet();
                    }
                });
                frameRef.set(frame);
            });

            AtomicReference<Throwable> workerError = new AtomicReference<>();
            Thread bg = new Thread(() -> {
                try {
                    service.refreshBlockingForTest();
                    for (AwarenessStateListener listener : List.copyOf(capturedListeners)) {
                        listener.onSelectionChanged("l2");
                        listener.onStatusMessage("Background status update", false);
                    }
                } catch (Throwable t) {
                    workerError.set(t);
                }
            }, "test-off-edt-notifier");
            bg.start();
            bg.join(5000);
            if (workerError.get() != null) {
                throw new AssertionError("Background notification threw exception", workerError.get());
            }

            runOnEdt(() -> {});
            assertEq(false, offEdtSwingMutation.get());
            assertEq(true, edtEventsObserved.get() > 0);

            runOnEdt(() -> {
                LiveAwarenessInternalFrame frame = frameRef.get();
                assertEq(2, frame.getLaunchesTab().getLaunchesTable().getRowCount());
                assertEq(1, frame.getLaunchesTab().getLaunchesTable().getSelectedRow());
                assertEq("Background status update", frame.getSyncStatusLabel().getToolTipText());
                frame.dispose();
            });
        } finally {
            service.shutdown();
        }
    }

    @Test
    public void testRefreshDisplayPreservesTableAndInspectorScrollState(@TempDir Path tmp) throws Exception {
        long baseNow = 1_795_132_800_000L;
        LaunchRecord l1 = LaunchRecord.builder()
                .id("l1")
                .missionName("Long Desc Mission")
                .provider("SpaceX")
                .vehicle("Falcon 9")
                .spaceportName("Cape Canaveral")
                .padName("SLC-40")
                .country("US")
                .targetOrbit("LEO")
                .cosparId("2026-142")
                .netEpochMillis(baseNow + 25L * 3600_000L)
                .windowStartMillis(baseNow + 25L * 3600_000L)
                .windowEndMillis(baseNow + 26L * 3600_000L)
                .status("Go")
                .orbital(true)
                .latDeg(28.56)
                .lonDeg(-80.58)
                .description("First line of mission profile describing ascent trajectory and payload deployment.")
                .fixedLandPad(true)
                .build();
        LaunchRecord l2 = LaunchRecord.builder()
                .id("l2")
                .missionName("Second Mission")
                .provider("Arianespace")
                .vehicle("Ariane 6")
                .spaceportName("Kourou")
                .padName("ELA-4")
                .country("FR")
                .targetOrbit("GTO")
                .netEpochMillis(baseNow + 48L * 3600_000L)
                .windowStartMillis(baseNow + 48L * 3600_000L)
                .windowEndMillis(baseNow + 49L * 3600_000L)
                .status("TBC")
                .orbital(true)
                .latDeg(5.23)
                .lonDeg(-52.76)
                .description("Different launch description for second mission.")
                .fixedLandPad(true)
                .build();

        List<LaunchRecord> manyLaunches = new ArrayList<>();
        manyLaunches.add(l1);
        manyLaunches.add(l2);
        for (int i = 3; i <= 15; i++) {
            manyLaunches.add(sample("l" + i, "Mission " + i, "Provider", "Vehicle", "Site",
                    baseNow + (48L + i) * 3600_000L, baseNow + (49L + i) * 3600_000L));
        }

        AwarenessSettingsStore store = new AwarenessSettingsStore(tmp.resolve("settings.json"));
        LaunchApiClient mockLaunch = new LaunchApiClient() {
            @Override
            public LaunchFetchResult fetchUpcomingAndRecent(List<DataSourceConfig> sources, long nowMillis) {
                return new LaunchFetchResult(manyLaunches, List.of(), "MockTick");
            }
        };
        TleFeedClient emptyTle = new TleFeedClient() {
            @Override
            public List<TLE> fetchTleCatalog(DataSourceConfig source) {
                return List.of();
            }
        };
        GroundStationCatalogRepository repo = new GroundStationCatalogRepository(
                tmp.resolve("base.csv").toFile(),
                tmp.resolve("custom.csv").toFile(),
                tmp.resolve("launches.csv").toFile());

        AtomicInteger matchedTleCalls = new AtomicInteger(0);
        LiveAwarenessService service = new LiveAwarenessService(
                store, tmp.resolve("cache.json"), mockLaunch, emptyTle, repo) {
            @Override
            public List<PayloadTleMatch> getMatchedTles(
                    LaunchRecord launch, Set<String> existingSatNames, Set<Integer> existingNoradIds) {
                matchedTleCalls.incrementAndGet();
                return super.getMatchedTles(launch, existingSatNames, existingNoradIds);
            }
        };

        try {
            service.refreshBlockingForTest();
            runOnEdt(() -> {
                LiveAwarenessInternalFrame frame = new LiveAwarenessInternalFrame(
                        service, t -> {}, (lat, lon) -> {}, r -> {}, () -> {}, () -> {});
                frame.setSize(780, 440);
                frame.doLayout();
                LaunchesAwarenessTab tab = frame.getLaunchesTab();
                tab.doLayout();
                JTextArea descArea = tab.getInspectorPanel().getDescriptionArea();
                JScrollPane tableScroll = findFirstComponent(tab, JScrollPane.class);
                tableScroll.setSize(760, 140);
                tableScroll.doLayout();

                frame.refreshDisplay(baseNow, false, TimeZone.getTimeZone("UTC"));
                tab.getLaunchesTable().setRowSelectionInterval(0, 0);
                assertEq(LaunchUrgency.SOON_1_TO_3D, tab.getLaunchesTable().getValueAt(0, 0));
                assertEq("T-1d 01h", tab.getLaunchesTable().getValueAt(0, 1));

                // Scroll table viewport down away from row 0 and select text in inspector description
                tab.getLaunchesTable().scrollRectToVisible(tab.getLaunchesTable().getCellRect(14, 0, true));
                Point scrolledViewPos = tableScroll.getViewport().getViewPosition();
                assertEq(true, scrolledViewPos.y > 0);
                descArea.select(12, 28);
                assertEq(28, descArea.getCaretPosition());

                AtomicInteger structuralResetEvents = new AtomicInteger(0);
                AtomicInteger totalTableEvents = new AtomicInteger(0);
                tab.getLaunchesTable().getModel().addTableModelListener(evt -> {
                    totalTableEvents.incrementAndGet();
                    if (evt.getLastRow() == Integer.MAX_VALUE || evt.getFirstRow() == TableModelEvent.HEADER_ROW) {
                        structuralResetEvents.incrementAndGet();
                    }
                });
                matchedTleCalls.set(0);

                // Tick +2 hours: same launch list, urgency crosses 24h threshold and countdown changes
                frame.refreshDisplay(baseNow + 2L * 3600_000L, false, TimeZone.getTimeZone("UTC"));
                assertEq(0, tab.getLaunchesTable().getSelectedRow());
                assertEq(scrolledViewPos, tableScroll.getViewport().getViewPosition());
                assertEq(LaunchUrgency.IMMINENT_OR_IN_WINDOW, tab.getLaunchesTable().getValueAt(0, 0));
                assertEq("T-23h 00m", tab.getLaunchesTable().getValueAt(0, 1));
                assertEq(0, structuralResetEvents.get());
                assertEq(0, matchedTleCalls.get());
                assertEq(12, descArea.getSelectionStart());
                assertEq(28, descArea.getSelectionEnd());
                assertEq(28, descArea.getCaretPosition());

                // Identical tick fires zero table model events and preserves caret/selection & scroll
                totalTableEvents.set(0);
                frame.refreshDisplay(baseNow + 2L * 3600_000L, false, TimeZone.getTimeZone("UTC"));
                assertEq(0, totalTableEvents.get());
                assertEq(scrolledViewPos, tableScroll.getViewport().getViewPosition());
                assertEq(28, descArea.getCaretPosition());

                // Switching selection to a different launch resets description caret to 0
                tab.getLaunchesTable().setRowSelectionInterval(1, 1);
                assertEq(0, descArea.getCaretPosition());

                // External selection (e.g. map click) DOES scroll table back to the selected row
                service.selectLaunch("l1");
                assertEq(0, tab.getLaunchesTable().getSelectedRow());
                assertEq(0, tableScroll.getViewport().getViewPosition().y);

                frame.dispose();
            });
        } finally {
            service.shutdown();
        }
    }

    private static <T extends Component> T findFirstComponent(Container root, Class<T> type) {
        T found = findFirstComponentOrNull(root, type);
        if (found == null) {
            throw new AssertionError("Component not found: " + type.getName());
        }
        return found;
    }

    private static <T extends Component> T findFirstComponentOrNull(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof Container nested) {
                T found = findFirstComponentOrNull(nested, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static LaunchRecord sample(
            String id,
            String mission,
            String provider,
            String vehicle,
            String spaceport,
            long net,
            long windowEnd) {
        return LaunchRecord.builder()
                .id(id)
                .missionName(mission)
                .provider(provider)
                .vehicle(vehicle)
                .spaceportName(spaceport)
                .padName("Pad-1")
                .country("FR")
                .targetOrbit("SSO")
                .cosparId("2026-142")
                .netEpochMillis(net)
                .windowStartMillis(net)
                .windowEndMillis(windowEnd)
                .status("Go")
                .orbital(true)
                .latDeg(5.236)
                .lonDeg(-52.768)
                .webcastUrl("https://example.org/live/" + id)
                .fixedLandPad(true)
                .build();
    }

    private static void runOnEdt(Runnable task) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeAndWait(task);
        }
    }

    private static void waitForEdtCondition(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            AtomicBoolean met = new AtomicBoolean(false);
            runOnEdt(() -> met.set(condition.getAsBoolean()));
            if (met.get()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Condition not met within " + timeoutMs + " ms");
    }

    private static void assertEq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("Expected <" + expected + "> but was <" + actual + ">");
        }
    }
}

package jsattrak.awareness;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import jsattrak.awareness.config.AuthMode;
import jsattrak.awareness.config.AwarenessSettings;
import jsattrak.awareness.config.AwarenessSettingsStore;
import jsattrak.awareness.config.DataSourceConfig;
import jsattrak.awareness.net.LaunchApiClient;
import jsattrak.awareness.tle.LaunchTleStatus;
import jsattrak.awareness.tle.PayloadTleMatch;
import jsattrak.awareness.tle.TleFeedClient;
import jsattrak.utilities.GroundStationCatalogRepository;
import jsattrak.utilities.TLE;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LiveAwarenessServiceTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private static final long BASE_NOW = 1_795_132_800_000L; // 2026-11-20T00:00:00Z

    @Test
    public void testQuietStartupMakesZeroNetworkCalls(@TempDir Path tmp) throws Exception {
        AtomicInteger launchCalls = new AtomicInteger();
        AtomicInteger tleCalls = new AtomicInteger();
        LiveAwarenessService svc = createServiceWithStubClient(tmp, launchCalls, tleCalls, false, false);

        try {
            svc.initializeAtStartup();
            Thread.sleep(50);
            assertEq(0, launchCalls.get());
            assertEq(0, tleCalls.get());
        } finally {
            svc.shutdown();
        }
    }

    @Test
    public void testAutoFetchOnStartupTriggersBackgroundFetchAndEdtDispatch(@TempDir Path tmp) throws Exception {
        AtomicInteger launchCalls = new AtomicInteger();
        AtomicInteger tleCalls = new AtomicInteger();
        LiveAwarenessService svc = createServiceWithStubClient(tmp, launchCalls, tleCalls, true, false);

        CountDownLatch dataLatch = new CountDownLatch(1);
        CountDownLatch statusLatch = new CountDownLatch(1);
        AtomicBoolean callbackWasOnEdt = new AtomicBoolean(false);

        svc.addListener(new AwarenessStateListener() {
            @Override
            public void onAwarenessDataChanged() {
                callbackWasOnEdt.set(SwingUtilities.isEventDispatchThread());
                dataLatch.countDown();
            }

            @Override
            public void onSelectionChanged(String selectedLaunchId) {
            }

            @Override
            public void onStatusMessage(String message, boolean isWarning) {
                if (!isWarning) {
                    statusLatch.countDown();
                }
            }
        });

        try {
            svc.initializeAtStartup();
            assertEq(true, dataLatch.await(5, TimeUnit.SECONDS));
            assertEq(true, statusLatch.await(5, TimeUnit.SECONDS));
            assertEq(true, callbackWasOnEdt.get());
            assertEq(1, launchCalls.get());
            assertEq(false, svc.isDataStale());
        } finally {
            svc.shutdown();
        }
    }

    @Test
    public void testEnsureActivatedTriggersInitialFetchOnceAndRespectsShutdown(@TempDir Path tmp) throws Exception {
        AtomicInteger launchCalls = new AtomicInteger();
        AtomicInteger tleCalls = new AtomicInteger();
        LiveAwarenessService svc = createServiceWithStubClient(tmp, launchCalls, tleCalls, false, false);

        CountDownLatch firstFetchDone = new CountDownLatch(1);
        svc.addListener(new AwarenessStateListener() {
            @Override
            public void onAwarenessDataChanged() {
                firstFetchDone.countDown();
            }

            @Override
            public void onSelectionChanged(String selectedLaunchId) {
            }

            @Override
            public void onStatusMessage(String message, boolean isWarning) {
            }
        });

        try {
            svc.ensureActivated();
            assertEq(true, firstFetchDone.await(5, TimeUnit.SECONDS));
            assertEq(1, launchCalls.get());

            svc.ensureActivated();
            Thread.sleep(50);
            assertEq(1, launchCalls.get());
        } finally {
            svc.shutdown();
        }

        svc.refreshNowAsync();
        Thread.sleep(50);
        assertEq(1, launchCalls.get());
    }

    @Test
    public void testFailedRefreshPreservesCachedDataAndMarksStale(@TempDir Path tmp) throws Exception {
        AtomicInteger fetchCalls = new AtomicInteger();
        LiveAwarenessService svc = createServiceFailOnSecondCall(tmp, fetchCalls);

        try {
            svc.refreshBlockingForTest();
            assertEq(false, svc.isDataStale());
            assertEq("", svc.getLastErrorMessage());
            long firstSyncMillis = svc.getLastSuccessfulSyncMillis();
            assertEq(true, firstSyncMillis > 0L);
            assertEq(1, svc.getVisibleUpcomingLaunches(BASE_NOW).size());
            assertEq(1, svc.getVisibleRecentLaunches(BASE_NOW).size());

            svc.refreshBlockingForTest();
            assertEq(true, svc.isDataStale());
            assertEq(true, !svc.getLastErrorMessage().isBlank());
            assertEq(firstSyncMillis, svc.getLastSuccessfulSyncMillis());
            assertEq(1, svc.getVisibleUpcomingLaunches(BASE_NOW).size());
            assertEq(1, svc.getVisibleRecentLaunches(BASE_NOW).size());
        } finally {
            svc.shutdown();
        }

        // Restarting from persisted cache reloads last-good launches and retains them on offline failure
        AwarenessSettingsStore store = new AwarenessSettingsStore(tmp.resolve("awareness-settings.json"));
        Path cachePath = tmp.resolve("awareness-cache.json");
        GroundStationCatalogRepository gsRepo = testGsRepo(tmp);
        LaunchApiClient alwaysFailLaunch = new LaunchApiClient() {
            @Override
            public LaunchFetchResult fetchUpcomingAndRecent(List<DataSourceConfig> sources, long realNowMillis)
                    throws IOException {
                throw new IOException("Offline");
            }
        };
        TleFeedClient noOpTle = new TleFeedClient() {
            @Override
            public List<TLE> fetchTleCatalog(DataSourceConfig source) {
                return List.of();
            }
        };

        LiveAwarenessService restarted = new LiveAwarenessService(store, cachePath, alwaysFailLaunch, noOpTle, gsRepo);
        try {
            assertEq(false, restarted.isDataStale());
            assertEq(true, restarted.getLastSuccessfulSyncMillis() > 0L);
            assertEq(1, restarted.getVisibleUpcomingLaunches(BASE_NOW).size());
            assertEq(1, restarted.getVisibleRecentLaunches(BASE_NOW).size());

            restarted.refreshBlockingForTest();
            assertEq(true, restarted.isDataStale());
            assertEq(1, restarted.getVisibleUpcomingLaunches(BASE_NOW).size());
        } finally {
            restarted.shutdown();
        }
    }

    @Test
    public void testCorruptCacheJsonRecoversGracefullyAndRecoversOnSync(@TempDir Path tmp) throws Exception {
        AwarenessSettingsStore store = new AwarenessSettingsStore(tmp.resolve("awareness-settings.json"));
        AwarenessSettings settings = AwarenessSettings.createDefaults();
        store.save(settings);

        Path cachePath = tmp.resolve("awareness-cache.json");
        Files.writeString(cachePath, "{ truncated corrupt json !!!");

        LaunchRecord recoveredLaunch = sampleLaunch("recov-1", "Recovered Mission", BASE_NOW + 3600_000L, true);
        LaunchApiClient okClient = new LaunchApiClient() {
            @Override
            public LaunchFetchResult fetchUpcomingAndRecent(List<DataSourceConfig> sources, long realNowMillis) {
                return new LaunchFetchResult(List.of(recoveredLaunch), List.of(), "Stub");
            }
        };
        TleFeedClient emptyTle = new TleFeedClient() {
            @Override
            public List<TLE> fetchTleCatalog(DataSourceConfig source) {
                return List.of();
            }
        };

        LiveAwarenessService svc = new LiveAwarenessService(store, cachePath, okClient, emptyTle, testGsRepo(tmp));
        try {
            assertEq(false, svc.isDataStale());
            assertEq(0L, svc.getLastSuccessfulSyncMillis());
            assertEq(0, svc.getVisibleUpcomingLaunches(BASE_NOW).size());
            assertEq(0, svc.getVisibleRecentLaunches(BASE_NOW).size());

            svc.refreshBlockingForTest();
            assertEq(1, svc.getVisibleUpcomingLaunches(BASE_NOW).size());
        } finally {
            svc.shutdown();
        }

        // Also verify structurally invalid/null-element JSON recovers without throwing
        Files.writeString(cachePath, "{\"lastSuccessfulSyncMillis\": -5, \"upcomingLaunches\": [null], \"recentLaunches\": null}");
        LiveAwarenessService partialJsonSvc = new LiveAwarenessService(store, cachePath, okClient, emptyTle, testGsRepo(tmp));
        try {
            assertEq(0L, partialJsonSvc.getLastSuccessfulSyncMillis());
            assertEq(0, partialJsonSvc.getVisibleUpcomingLaunches(BASE_NOW).size());
        } finally {
            partialJsonSvc.shutdown();
        }
    }

    @Test
    public void testCompanionGroundStationPadSyncRespectsSourceFlag(@TempDir Path tmp) throws Exception {
        Path subA = Files.createDirectory(tmp.resolve("sync-on"));
        AtomicInteger callsA = new AtomicInteger();
        GroundStationCatalogRepository repoA = testGsRepo(subA);
        File baseDbA = subA.resolve("base.csv").toFile();
        String originalBase = "Launch,Legacy Pad,28.5,-81.0,0\n";
        Files.writeString(baseDbA.toPath(), originalBase);

        LiveAwarenessService svcSyncOn = createServiceWithStubClient(subA, callsA, new AtomicInteger(), false, true, repoA);
        try {
            svcSyncOn.refreshBlockingForTest();
            List<GroundStationCatalogRepository.CatalogEntry> synced =
                    repoA.loadGroupedCatalog().get(GroundStationCatalogRepository.LAUNCH_API_NETWORK);
            assertEq(1, synced.size()); // fixed pad synced, mobile sea pad skipped
            assertEq(originalBase, Files.readString(baseDbA.toPath()));
        } finally {
            svcSyncOn.shutdown();
        }

        Path subB = Files.createDirectory(tmp.resolve("sync-off"));
        AtomicInteger callsB = new AtomicInteger();
        GroundStationCatalogRepository repoB = testGsRepo(subB);
        LiveAwarenessService svcSyncOff = createServiceWithStubClient(subB, callsB, new AtomicInteger(), false, false, repoB);
        try {
            svcSyncOff.refreshBlockingForTest();
            assertEq(null, repoB.loadGroupedCatalog().get(GroundStationCatalogRepository.LAUNCH_API_NETWORK));
        } finally {
            svcSyncOff.shutdown();
        }
    }

    @Test
    public void testTleCatalogSyncClassificationAndMatching(@TempDir Path tmp) throws Exception {
        AwarenessSettingsStore store = new AwarenessSettingsStore(tmp.resolve("awareness-settings.json"));
        AwarenessSettings settings = new AwarenessSettings();
        settings.setAutoFetchOnStartup(false);
        settings.getSources().add(new DataSourceConfig(
                "ll2", DataSourceConfig.FeedCategory.LAUNCH, "MockLL2",
                "https://ll2.example/launch/", true, 1, AuthMode.NONE, "", "", "", 15, false));
        settings.getSources().add(new DataSourceConfig(
                "celestrak-tle-new", DataSourceConfig.FeedCategory.TLE, "CelesTrak Last 30 Days",
                "https://tle.example/last-30-days", true, 1, AuthMode.NONE, "", "", "", 15, false));
        settings.getSources().add(new DataSourceConfig(
                "celestrak-supgp", DataSourceConfig.FeedCategory.TLE, "CelesTrak SupGP Index",
                "https://tle.example/supplemental/", true, 2, AuthMode.NONE, "", "", "", 15, false));
        settings.getSources().add(new DataSourceConfig(
                "disabled-tle", DataSourceConfig.FeedCategory.TLE, "Disabled TLE",
                "https://tle.example/disabled", false, 3, AuthMode.NONE, "", "", "", 15, false));
        store.save(settings);

        LaunchRecord officialLaunch = LaunchRecord.builder()
                .id("off-1")
                .missionName("Starlink Group 12-4")
                .cosparId("2026-142")
                .netEpochMillis(BASE_NOW - 2L * 3600_000L)
                .windowStartMillis(BASE_NOW - 2L * 3600_000L)
                .windowEndMillis(BASE_NOW - 2L * 3600_000L)
                .orbital(true)
                .status("Success")
                .outcome("Success")
                .build();
        LaunchRecord supLaunch = LaunchRecord.builder()
                .id("sup-1")
                .missionName("Starlink Group 12-9")
                .netEpochMillis(BASE_NOW + 6L * 3600_000L)
                .windowStartMillis(BASE_NOW + 6L * 3600_000L)
                .windowEndMillis(BASE_NOW + 6L * 3600_000L)
                .orbital(true)
                .status("Go")
                .build();
        LaunchRecord awaitingLaunch = LaunchRecord.builder()
                .id("wait-1")
                .missionName("Unmatched Orbital")
                .netEpochMillis(BASE_NOW - 3L * 3600_000L)
                .windowStartMillis(BASE_NOW - 3L * 3600_000L)
                .windowEndMillis(BASE_NOW - 3L * 3600_000L)
                .orbital(true)
                .status("Success")
                .outcome("Success")
                .build();
        LaunchRecord suborbitalLaunch = LaunchRecord.builder()
                .id("sub-1")
                .missionName("NS-99")
                .netEpochMillis(BASE_NOW - 4L * 3600_000L)
                .windowStartMillis(BASE_NOW - 4L * 3600_000L)
                .windowEndMillis(BASE_NOW - 4L * 3600_000L)
                .orbital(false)
                .status("Success")
                .outcome("Success")
                .build();

        LaunchApiClient launchClient = new LaunchApiClient() {
            @Override
            public LaunchFetchResult fetchUpcomingAndRecent(List<DataSourceConfig> sources, long realNowMillis) {
                return new LaunchFetchResult(
                        List.of(supLaunch),
                        List.of(officialLaunch, awaitingLaunch, suborbitalLaunch),
                        "MockLL2");
            }
        };

        List<String> fetchedTleIds = new ArrayList<>();
        TleFeedClient tleClient = new TleFeedClient() {
            @Override
            public List<TLE> fetchTleCatalog(DataSourceConfig source) throws IOException {
                fetchedTleIds.add(source.getId());
                if ("celestrak-tle-new".equals(source.getId())) {
                    return List.of(new TLE(
                            "STARLINK-9001",
                            "1 61001U 26142A   26324.50000000  .00001000  00000-0  50000-4 0  9991",
                            "2 61001  53.1600 100.0000 0001200  90.0000 270.0000 15.05000000    12"));
                }
                if ("celestrak-supgp".equals(source.getId())) {
                    return List.of(new TLE(
                            "STARLINK-GROUP-12-9-1",
                            "1 99001U 26999A   26325.10000000  .00001000  00000-0  50000-4 0  9991",
                            "2 99001  53.1600 100.0000 0001200  90.0000 270.0000 15.05000000    12"));
                }
                throw new IOException("Unexpected source: " + source.getId());
            }
        };

        LiveAwarenessService svc = new LiveAwarenessService(
                store, tmp.resolve("awareness-cache.json"), launchClient, tleClient, testGsRepo(tmp));
        try {
            svc.refreshBlockingForTest();
            assertEq(List.of("celestrak-tle-new", "celestrak-supgp"), fetchedTleIds);
            assertEq(LaunchTleStatus.TLES_READY, svc.getTleStatus(officialLaunch));
            assertEq(LaunchTleStatus.SUPPLEMENTAL_READY, svc.getTleStatus(supLaunch));
            assertEq(LaunchTleStatus.AWAITING_TLE, svc.getTleStatus(awaitingLaunch));
            assertEq(LaunchTleStatus.NO_ORBITAL_PAYLOAD, svc.getTleStatus(suborbitalLaunch));

            List<PayloadTleMatch> matches = svc.getMatchedTles(officialLaunch, Set.of(), Set.of(61001));
            assertEq(1, matches.size());
            assertEq(true, matches.get(0).alreadyTracked());
        } finally {
            svc.shutdown();
        }
    }

    @Test
    public void testSelectionOpenUiHorizonOverlayAndFeedInterface(@TempDir Path tmp) throws Exception {
        LaunchRecord l12h = sampleLaunch("u1", "Imminent 12h", BASE_NOW + 12L * 3600_000L, true);
        LaunchRecord l48h = sampleLaunch("u2", "Soon 48h", BASE_NOW + 48L * 3600_000L, true);
        LaunchRecord l5d = sampleLaunch("u3", "Upcoming 5d", BASE_NOW + 120L * 3600_000L, true);

        LiveAwarenessService svc = LiveAwarenessService.createForTestWithLaunches(tmp, List.of(l5d, l48h, l12h));
        List<String> selectedIds = new CopyOnWriteArrayList<>();
        AtomicInteger dataChanges = new AtomicInteger();
        AtomicInteger openUiCount = new AtomicInteger();

        svc.addListener(new AwarenessStateListener() {
            @Override
            public void onAwarenessDataChanged() {
                dataChanges.incrementAndGet();
            }

            @Override
            public void onSelectionChanged(String selectedLaunchId) {
                selectedIds.add(selectedLaunchId);
            }

            @Override
            public void onStatusMessage(String message, boolean isWarning) {
            }

            @Override
            public void onOpenUiRequested() {
                openUiCount.incrementAndGet();
            }
        });

        try {
            assertEq("launches", svc.getFeedId());
            assertEq("Launches", svc.getDisplayName());
            assertEq(true, svc.isGlobalMapOverlayEnabled());
            assertEq(true, svc.isMapOverlayEnabled());

            assertEq(7, svc.getSelectedHorizonDays());
            assertEq(3, svc.getVisibleUpcomingLaunches(BASE_NOW).size());

            svc.setSelectedHorizonDays(3);
            assertEq(2, svc.getVisibleUpcomingLaunches(BASE_NOW).size());

            svc.setSelectedHorizonDays(1);
            assertEq(1, svc.getVisibleUpcomingLaunches(BASE_NOW).size());

            svc.setMapOverlayEnabled(false);
            assertEq(false, svc.isGlobalMapOverlayEnabled());
            assertEq(false, svc.isMapOverlayEnabled());

            svc.selectLaunch("u2");
            svc.selectLaunchAndRequestOpenUi("u2"); // Already selected, must still fire onOpenUiRequested!
            SwingUtilities.invokeAndWait(() -> { });

            assertEq("u2", svc.getSelectedLaunchId());
            assertEq(l48h, svc.getSelectedLaunch());
            assertEq(List.of("u2"), selectedIds);
            assertEq(1, openUiCount.get());
            assertEq(true, dataChanges.get() >= 3);

            AwarenessSettings updated = svc.getSettings();
            updated.setAutoFetchOnStartup(true);
            svc.updateSettings(updated);
            assertEq(true, svc.getSettings().isAutoFetchOnStartup());
        } finally {
            svc.shutdown();
        }
    }

    @Test
    public void testFailoverSyncFlagPartialTleFailureRecentSelectionAndManualInterval(@TempDir Path tmp)
            throws Exception {
        AwarenessSettingsStore store = new AwarenessSettingsStore(tmp.resolve("awareness-settings.json"));
        AwarenessSettings settings = new AwarenessSettings();
        settings.setAutoFetchOnStartup(false);
        // Primary has sync=true, Backup has sync=false, interval=0 (Manual only)
        settings.getSources().add(new DataSourceConfig(
                "primary", DataSourceConfig.FeedCategory.LAUNCH, "Primary",
                "https://primary.example/launch/", true, 1, AuthMode.NONE, "", "", "", 0, true));
        settings.getSources().add(new DataSourceConfig(
                "backup", DataSourceConfig.FeedCategory.LAUNCH, "Backup",
                "https://backup.example/launch/", true, 2, AuthMode.NONE, "", "", "", 0, false));
        settings.getSources().add(new DataSourceConfig(
                "official-tle", DataSourceConfig.FeedCategory.TLE, "Official",
                "https://tle.example/official", true, 1, AuthMode.NONE, "", "", "", 0, false));
        settings.getSources().add(new DataSourceConfig(
                "supgp-tle", DataSourceConfig.FeedCategory.TLE, "SupGP",
                "https://tle.example/supplemental/", true, 2, AuthMode.NONE, "", "", "", 0, false));
        store.save(settings);

        LaunchRecord up1 = sampleLaunch("up-a", "Upcoming Alpha", BASE_NOW + 3600_000L, true);
        LaunchRecord rec1 = sampleLaunch("rec-a", "Recent Alpha", BASE_NOW - 2L * 3600_000L, true);
        AtomicInteger refreshRound = new AtomicInteger();

        LaunchApiClient failoverClient = new LaunchApiClient() {
            @Override
            public LaunchFetchResult fetchUpcomingAndRecent(List<DataSourceConfig> sources, long realNowMillis) {
                int round = refreshRound.incrementAndGet();
                if (round == 1) {
                    // Failover selected Backup (syncFixedPads == false)
                    return new LaunchFetchResult(List.of(up1), List.of(rec1), "Backup");
                }
                // Round 2 drops rec1
                return new LaunchFetchResult(List.of(up1), List.of(), "Primary");
            }
        };
        TleFeedClient partialFailTle = new TleFeedClient() {
            @Override
            public List<TLE> fetchTleCatalog(DataSourceConfig source) throws IOException {
                if ("supgp-tle".equals(source.getId())) {
                    throw new IOException("SupGP HTML index error");
                }
                return List.of(new TLE(
                        "ALPHA-1",
                        "1 62001U 26150A   26324.50000000  .00001000  00000-0  50000-4 0  9991",
                        "2 62001  53.1600 100.0000 0001200  90.0000 270.0000 15.05000000    12"));
            }
        };

        Clock fixedClock = Clock.fixed(Instant.ofEpochMilli(BASE_NOW), ZoneOffset.UTC);
        GroundStationCatalogRepository gsRepo = testGsRepo(tmp);
        LiveAwarenessService svc = new LiveAwarenessService(
                store, tmp.resolve("awareness-cache.json"), failoverClient, partialFailTle, gsRepo, fixedClock);
        try {
            svc.refreshBlockingForTest();
            // Manual-only interval (0) must activate cleanly without throwing when scheduling timer
            svc.ensureActivated();

            // Failover source "Backup" had sync=false -> no pads written
            assertEq(null, gsRepo.loadGroupedCatalog().get(GroundStationCatalogRepository.LAUNCH_API_NETWORK));
            // Partial TLE failure keeps launches non-stale
            assertEq(false, svc.isDataStale());
            assertEq(1, svc.getActiveItemCount());

            // Selecting a recent launch works via getSelectedLaunch()
            svc.selectLaunch("rec-a");
            SwingUtilities.invokeAndWait(() -> { });
            assertEq(rec1, svc.getSelectedLaunch());

            // Round 2 drops rec-a and uses Primary (sync=true) -> pads synced and stale selection cleared
            svc.refreshBlockingForTest();
            assertEq(1, gsRepo.loadGroupedCatalog().get(GroundStationCatalogRepository.LAUNCH_API_NETWORK).size());
            assertEq(null, svc.getSelectedLaunchId());
            assertEq(null, svc.getSelectedLaunch());

            boolean threwInvalidHorizon = false;
            try {
                svc.setSelectedHorizonDays(5);
            } catch (IllegalArgumentException expected) {
                threwInvalidHorizon = true;
            }
            assertEq(true, threwInvalidHorizon);
        } finally {
            svc.shutdown();
        }
    }

    @Test
    public void testDefensiveCopyIsolationOnSettings(@TempDir Path tmp) throws Exception {
        AtomicInteger launchCalls = new AtomicInteger();
        AtomicInteger tleCalls = new AtomicInteger();
        LiveAwarenessService svc = createServiceWithStubClient(tmp, launchCalls, tleCalls, false, false);
        try {
            AwarenessSettings leakedCopy = svc.getSettings();
            leakedCopy.setAutoFetchOnStartup(true);
            leakedCopy.getSources().get(0).setEndpointUrl("https://mutated-getter.example/");
            leakedCopy.getSources().clear();

            AwarenessSettings current = svc.getSettings();
            assertEq(false, current.isAutoFetchOnStartup());
            assertEq(2, current.getSources().size());
            assertEq("https://stub.example/launch/", current.getSources().get(0).getEndpointUrl());

            AwarenessSettings updated = new AwarenessSettings();
            updated.setAutoFetchOnStartup(true);
            updated.setDefaultHorizonDays(3);
            updated.getSources().add(new DataSourceConfig(
                    "ll2-new", DataSourceConfig.FeedCategory.LAUNCH, "NewLL2",
                    "https://updated.example/launch/", true, 1, AuthMode.NONE, "", "", "", 15, true));
            svc.updateSettings(updated);

            // Mutating caller's object after updateSettings() must not affect service state
            updated.setAutoFetchOnStartup(false);
            updated.getSources().get(0).setEndpointUrl("https://post-update-mutated.example/");
            updated.getSources().clear();

            AwarenessSettings afterUpdate = svc.getSettings();
            assertEq(true, afterUpdate.isAutoFetchOnStartup());
            assertEq(1, afterUpdate.getSources().size());
            assertEq("https://updated.example/launch/", afterUpdate.getSources().get(0).getEndpointUrl());
        } finally {
            svc.shutdown();
        }
    }

    @Test
    public void testScheduledRefreshSurvivesRuntimeExceptionAndRecovers(@TempDir Path tmp) throws Exception {
        AwarenessSettingsStore store = new AwarenessSettingsStore(tmp.resolve("awareness-settings.json"));
        AwarenessSettings settings = new AwarenessSettings();
        settings.setAutoFetchOnStartup(false);
        settings.getSources().add(new DataSourceConfig(
                "ll2-stub", DataSourceConfig.FeedCategory.LAUNCH, "StubLL2",
                "https://stub.example/launch/", true, 1, AuthMode.NONE, "", "", "", 15, false));
        store.save(settings);

        AtomicInteger calls = new AtomicInteger();
        LaunchRecord up1 = sampleLaunch("sched-1", "Scheduled Launch", BASE_NOW + 3600_000L, true);
        LaunchApiClient flakyRuntimeClient = new LaunchApiClient() {
            @Override
            public LaunchFetchResult fetchUpcomingAndRecent(List<DataSourceConfig> sources, long realNowMillis) {
                int c = calls.incrementAndGet();
                if (c == 1) {
                    throw new IllegalStateException("Simulated unexpected parser failure");
                }
                return new LaunchFetchResult(List.of(up1), List.of(), "StubLL2");
            }
        };
        TleFeedClient noOpTle = new TleFeedClient() {
            @Override
            public List<TLE> fetchTleCatalog(DataSourceConfig source) {
                return List.of();
            }
        };

        LiveAwarenessService svc = new LiveAwarenessService(
                store, tmp.resolve("awareness-cache.json"), flakyRuntimeClient, noOpTle, testGsRepo(tmp));
        CountDownLatch warningSeen = new CountDownLatch(1);
        CountDownLatch recoveredSeen = new CountDownLatch(1);
        List<String> warningMessages = new CopyOnWriteArrayList<>();

        svc.addListener(new AwarenessStateListener() {
            @Override
            public void onAwarenessDataChanged() {
            }

            @Override
            public void onSelectionChanged(String selectedLaunchId) {
            }

            @Override
            public void onStatusMessage(String message, boolean isWarning) {
                if (isWarning) {
                    warningMessages.add(message);
                    warningSeen.countDown();
                } else if (warningSeen.getCount() == 0) {
                    recoveredSeen.countDown();
                }
            }
        });

        try {
            svc.schedulePeriodicRefreshForTest(25L);
            assertEq(true, warningSeen.await(5, TimeUnit.SECONDS));
            assertEq(true, recoveredSeen.await(5, TimeUnit.SECONDS));
            assertEq(true, calls.get() >= 2);
            assertEq(false, svc.isDataStale());
            assertEq("", svc.getLastErrorMessage());
            assertEq(1, svc.getVisibleUpcomingLaunches(BASE_NOW).size());
            assertEq(true, warningMessages.get(0).contains("Simulated unexpected parser failure"));
        } finally {
            svc.shutdown();
        }
    }

    @Test
    public void testShutdownMidFetchSuppressesStaleFlipAndCallbacks(@TempDir Path tmp) throws Exception {
        AwarenessSettingsStore store = new AwarenessSettingsStore(tmp.resolve("awareness-settings.json"));
        AwarenessSettings settings = new AwarenessSettings();
        settings.setAutoFetchOnStartup(false);
        settings.getSources().add(new DataSourceConfig(
                "ll2-stub", DataSourceConfig.FeedCategory.LAUNCH, "StubLL2",
                "https://stub.example/launch/", true, 1, AuthMode.NONE, "", "", "", 15, false));
        store.save(settings);

        CountDownLatch enteredFetch = new CountDownLatch(1);
        CountDownLatch allowThrow = new CountDownLatch(1);
        AtomicInteger callbackCount = new AtomicInteger();

        LaunchApiClient blockingClient = new LaunchApiClient() {
            @Override
            public LaunchFetchResult fetchUpcomingAndRecent(List<DataSourceConfig> sources, long realNowMillis)
                    throws IOException {
                enteredFetch.countDown();
                try {
                    allowThrow.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    throw new IOException("Launch API request interrupted", interrupted);
                }
                throw new IOException("Mid-flight connection closed");
            }
        };
        TleFeedClient noOpTle = new TleFeedClient() {
            @Override
            public List<TLE> fetchTleCatalog(DataSourceConfig source) {
                return List.of();
            }
        };

        LiveAwarenessService svc = new LiveAwarenessService(
                store, tmp.resolve("awareness-cache.json"), blockingClient, noOpTle, testGsRepo(tmp));
        svc.addListener(new AwarenessStateListener() {
            @Override
            public void onAwarenessDataChanged() {
                callbackCount.incrementAndGet();
            }

            @Override
            public void onSelectionChanged(String selectedLaunchId) {
                callbackCount.incrementAndGet();
            }

            @Override
            public void onStatusMessage(String message, boolean isWarning) {
                callbackCount.incrementAndGet();
            }

            @Override
            public void onOpenUiRequested() {
                callbackCount.incrementAndGet();
            }
        });

        try {
            svc.refreshNowAsync();
            assertEq(true, enteredFetch.await(5, TimeUnit.SECONDS));

            svc.shutdown();
            allowThrow.countDown();

            long deadline = System.currentTimeMillis() + 5000L;
            while (svc.isRefreshing() && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
            SwingUtilities.invokeAndWait(() -> { });

            assertEq(false, svc.isRefreshing());
            assertEq(false, svc.isDataStale());
            assertEq("", svc.getLastErrorMessage());
            assertEq(0, callbackCount.get());
        } finally {
            svc.shutdown();
        }
    }

    private static LiveAwarenessService createServiceWithStubClient(
            Path tmp,
            AtomicInteger launchCalls,
            AtomicInteger tleCalls,
            boolean autoFetchOnStartup,
            boolean syncFixedPads) throws Exception {
        return createServiceWithStubClient(tmp, launchCalls, tleCalls, autoFetchOnStartup, syncFixedPads, testGsRepo(tmp));
    }

    private static LiveAwarenessService createServiceWithStubClient(
            Path tmp,
            AtomicInteger launchCalls,
            AtomicInteger tleCalls,
            boolean autoFetchOnStartup,
            boolean syncFixedPads,
            GroundStationCatalogRepository gsRepo) throws Exception {
        AwarenessSettingsStore store = new AwarenessSettingsStore(tmp.resolve("awareness-settings.json"));
        AwarenessSettings settings = new AwarenessSettings();
        settings.setAutoFetchOnStartup(autoFetchOnStartup);
        settings.setDefaultHorizonDays(7);
        settings.getSources().add(new DataSourceConfig(
                "ll2-stub", DataSourceConfig.FeedCategory.LAUNCH, "StubLL2",
                "https://stub.example/launch/", true, 1, AuthMode.NONE, "", "", "", 15, syncFixedPads));
        settings.getSources().add(new DataSourceConfig(
                "tle-stub", DataSourceConfig.FeedCategory.TLE, "StubTLE",
                "https://stub.example/tle.txt", true, 1, AuthMode.NONE, "", "", "", 15, false));
        store.save(settings);

        LaunchRecord fixedPad = sampleLaunch("l1", "Fixed Launch", BASE_NOW + 6L * 3600_000L, true);
        LaunchRecord seaPad = sampleLaunch("l2", "Sea Launch", BASE_NOW + 12L * 3600_000L, false);

        LaunchApiClient launchClient = new LaunchApiClient() {
            @Override
            public LaunchFetchResult fetchUpcomingAndRecent(List<DataSourceConfig> sources, long realNowMillis) {
                launchCalls.incrementAndGet();
                return new LaunchFetchResult(List.of(fixedPad, seaPad), List.of(), "StubLL2");
            }
        };
        TleFeedClient tleClient = new TleFeedClient() {
            @Override
            public List<TLE> fetchTleCatalog(DataSourceConfig source) {
                tleCalls.incrementAndGet();
                return List.of();
            }
        };
        return new LiveAwarenessService(store, tmp.resolve("awareness-cache.json"), launchClient, tleClient, gsRepo);
    }

    private static LiveAwarenessService createServiceFailOnSecondCall(Path tmp, AtomicInteger fetchCalls)
            throws Exception {
        AwarenessSettingsStore store = new AwarenessSettingsStore(tmp.resolve("awareness-settings.json"));
        AwarenessSettings settings = new AwarenessSettings();
        settings.setAutoFetchOnStartup(false);
        settings.getSources().add(new DataSourceConfig(
                "ll2-stub", DataSourceConfig.FeedCategory.LAUNCH, "StubLL2",
                "https://stub.example/launch/", true, 1, AuthMode.NONE, "", "", "", 15, false));
        store.save(settings);

        LaunchRecord up1 = sampleLaunch("up-1", "Upcoming One", BASE_NOW + 6L * 3600_000L, true);
        LaunchRecord rec1 = sampleLaunch("rec-1", "Recent One", BASE_NOW - 6L * 3600_000L, true);

        LaunchApiClient launchClient = new LaunchApiClient() {
            @Override
            public LaunchFetchResult fetchUpcomingAndRecent(List<DataSourceConfig> sources, long realNowMillis)
                    throws IOException {
                int call = fetchCalls.incrementAndGet();
                if (call == 1) {
                    return new LaunchFetchResult(List.of(up1), List.of(rec1), "StubLL2");
                }
                throw new IOException("Simulated rate limit HTTP 429");
            }
        };
        TleFeedClient tleClient = new TleFeedClient() {
            @Override
            public List<TLE> fetchTleCatalog(DataSourceConfig source) {
                return List.of();
            }
        };
        return new LiveAwarenessService(
                store, tmp.resolve("awareness-cache.json"), launchClient, tleClient, testGsRepo(tmp));
    }

    private static GroundStationCatalogRepository testGsRepo(Path dir) {
        return new GroundStationCatalogRepository(
                dir.resolve("base.csv").toFile(),
                dir.resolve("custom.csv").toFile(),
                dir.resolve("launches.csv").toFile());
    }

    private static LaunchRecord sampleLaunch(String id, String name, long netMillis, boolean fixedLandPad) {
        return LaunchRecord.builder()
                .id(id)
                .missionName(name)
                .provider("TestProvider")
                .vehicle("TestRocket")
                .targetOrbit("LEO")
                .netEpochMillis(netMillis)
                .windowStartMillis(netMillis)
                .windowEndMillis(netMillis + 3600_000L)
                .status("Go")
                .orbital(true)
                .padName(fixedLandPad ? "SLC-40" : "Sea Platform")
                .spaceportName(fixedLandPad ? "Cape Canaveral SFS" : "Pacific Ocean")
                .country("USA")
                .latDeg(fixedLandPad ? 28.5619 : 0.5)
                .lonDeg(fixedLandPad ? -80.5774 : -154.0)
                .fixedLandPad(fixedLandPad)
                .build();
    }

    private static void assertEq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("Expected <" + expected + "> but got <" + actual + ">");
        }
    }
}

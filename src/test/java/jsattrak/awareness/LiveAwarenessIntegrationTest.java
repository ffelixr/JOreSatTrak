package jsattrak.awareness;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import jsattrak.awareness.config.AuthMode;
import jsattrak.awareness.config.AwarenessSettings;
import jsattrak.awareness.config.AwarenessSettingsStore;
import jsattrak.awareness.config.DataSourceConfig;
import jsattrak.awareness.gui.LiveAwarenessInternalFrame;
import jsattrak.awareness.gui.PayloadTleImportDialog;
import jsattrak.awareness.net.LaunchApiClient;
import jsattrak.awareness.net.LaunchLibrary2Parser;
import jsattrak.awareness.tle.LaunchTleStatus;
import jsattrak.awareness.tle.PayloadTleMatch;
import jsattrak.awareness.tle.PayloadTleMatcher;
import jsattrak.awareness.tle.TleFeedClient;
import jsattrak.gui.J2DEarthPanel;
import jsattrak.gui.J2DPolarEarthPanel;
import jsattrak.gui.J2dPolarEarthLabel;
import jsattrak.gui.J3DEarthInternalPanel;
import jsattrak.gui.J3DEarthPanel;
import jsattrak.gui.JSatTrak;
import jsattrak.objects.AbstractSatellite;
import jsattrak.objects.GroundStation;
import jsattrak.objects.SatelliteTleSGP4;
import jsattrak.utilities.GroundStationCatalogRepository;
import jsattrak.utilities.TLE;
import name.gano.astro.bodies.Sun;
import name.gano.astro.time.Time;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LiveAwarenessIntegrationTest {

    static {
        System.setProperty("java.awt.headless", "true");
        JSatTrak.initOrekitData();
    }

    private static final long BASE_NOW = 1_795_132_800_000L; // 2026-11-20T00:00:00Z
    private static final String STARLINK_LINE1_OLD =
            "1 61001U 26142A   26323.50000000  .00001000  00000-0  50000-4 0  9990";
    private static final String STARLINK_LINE2_OLD =
            "2 61001  53.0000 100.0000 0001500  90.0000 270.0000 15.10000000    14";
    private static final String STARLINK_LINE1_NEW =
            "1 61001U 26142A   26324.50000000  .00001000  00000-0  50000-4 0  9991";
    private static final String STARLINK_LINE2_NEW =
            "2 61001  53.0000 100.0000 0001500  90.0000 270.0000 15.10000000    15";
    private static final String FALCON_RB_LINE1 =
            "1 61002U 26142B   26324.50000000  .00001000  00000-0  50000-4 0  9992";
    private static final String FALCON_RB_LINE2 =
            "2 61002  53.0000 100.0000 0001500  90.0000 270.0000 15.10000000    16";

    @Test
    public void testEndToEndLaunchFetchCompanionSyncAndTleToSatelliteCreation(@TempDir Path tmp)
            throws Exception {
        AtomicInteger launchHits = new AtomicInteger();
        AtomicInteger tleHits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        registerMockEndpoints(server, launchHits, tleHits);
        server.start();
        try {
            int port = server.getAddress().getPort();
            AwarenessSettingsStore store = new AwarenessSettingsStore(tmp.resolve("settings.json"));
            AwarenessSettings settings = new AwarenessSettings();
            settings.setAutoFetchOnStartup(false);
            settings.getSources().add(new DataSourceConfig(
                    "ll2", DataSourceConfig.FeedCategory.LAUNCH, "Mock LL2",
                    "http://127.0.0.1:" + port + "/ll2/", true, 1, AuthMode.NONE, "", "", "", 15, true));
            settings.getSources().add(new DataSourceConfig(
                    "tle", DataSourceConfig.FeedCategory.TLE, "Mock TLE",
                    "http://127.0.0.1:" + port + "/tle-new.txt", true, 1, AuthMode.NONE, "", "", "", 15, false));
            store.save(settings);

            File baseDb = tmp.resolve("base.csv").toFile();
            Files.writeString(baseDb.toPath(), "Launch,Legacy Pad,28.5,-81.0,0\n");
            GroundStationCatalogRepository gsRepo = new GroundStationCatalogRepository(
                    baseDb, tmp.resolve("custom.csv").toFile(), tmp.resolve("launches.csv").toFile());

            LiveAwarenessService svc = new LiveAwarenessService(
                    store, tmp.resolve("cache.json"), new LaunchApiClient(), new TleFeedClient(), gsRepo);
            try {
                // Quiet startup makes zero network calls
                svc.initializeAtStartup();
                Thread.sleep(60);
                assertEq(0, launchHits.get());
                assertEq(0, tleHits.get());

                // Snapshot companion Launch (API) pads before launch sync
                List<GroundStationCatalogRepository.CatalogEntry> initialPads =
                        JSatTrak.loadLaunchApiPads(gsRepo);
                assertEq(0, initialPads.size());

                svc.refreshBlockingForTest();
                assertEq(true, launchHits.get() >= 2);
                assertEq(1, tleHits.get());

                // 1. Companion GS file received the fixed launch pads and base DB stayed untouched
                Map<String, List<GroundStationCatalogRepository.CatalogEntry>> grouped = gsRepo.loadGroupedCatalog();
                assertEq(1, grouped.get("Launch").size());
                assertEq(2, grouped.get(GroundStationCatalogRepository.LAUNCH_API_NETWORK).size());

                // Verify JSatTrak's companion-pad diff check triggers GS browser reload once and stays quiet on no-op
                AtomicInteger reloadCalls = new AtomicInteger();
                List<GroundStationCatalogRepository.CatalogEntry> updatedPads =
                        JSatTrak.syncLaunchApiPadSnapshot(gsRepo, initialPads, reloadCalls::incrementAndGet);
                assertEq(2, updatedPads.size());
                assertEq(1, reloadCalls.get());
                JSatTrak.syncLaunchApiPadSnapshot(gsRepo, updatedPads, reloadCalls::incrementAndGet);
                assertEq(1, reloadCalls.get());

                // 2. Upcoming and recent launches parsed accurately
                List<LaunchRecord> upcoming = svc.getVisibleUpcomingLaunches(BASE_NOW);
                assertEq(1, upcoming.size());
                assertEq("Artemis-X", upcoming.get(0).getMissionName());

                List<LaunchRecord> recent = svc.getVisibleRecentLaunches(BASE_NOW);
                assertEq(1, recent.size());
                assertEq(LaunchTleStatus.TLES_READY, svc.getTleStatus(recent.get(0)));

                // 3. Matched TLE builds a valid Orekit/SGP4 SatelliteTleSGP4 via JSatTrak coordinator helper
                List<PayloadTleMatch> matches = svc.getMatchedTles(recent.get(0), Set.of(), Set.of());
                assertEq(2, matches.size());

                Hashtable<String, AbstractSatellite> satHash = new Hashtable<>();
                Time simTime = new Time();
                simTime.setTimeInMillis(BASE_NOW);
                AtomicInteger uiUpdates = new AtomicInteger();

                boolean added = JSatTrak.addOrUpdateSatelliteFromTle(
                        satHash,
                        matches.get(0).tle(),
                        true,
                        simTime.getJulianDate(),
                        sat -> satHash.put(sat.getName(), sat),
                        uiUpdates::incrementAndGet);

                assertEq(true, added);
                assertEq(1, uiUpdates.get());
                SatelliteTleSGP4 sat = (SatelliteTleSGP4) satHash.get("STARLINK-9001");
                assertEq("STARLINK-9001", sat.getName());
                assertEq(61001, PayloadTleMatcher.extractNoradId(sat.getTle()));
                assertEq(true, Double.isFinite(sat.getLatitude()));
            } finally {
                svc.shutdown();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testJsattrakCoordinatorDuplicateDetectionAndInPlaceTleUpdate(@TempDir Path tmp)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        registerMockEndpoints(server, new AtomicInteger(), new AtomicInteger());
        server.start();
        try {
            int port = server.getAddress().getPort();
            AwarenessSettingsStore store = new AwarenessSettingsStore(tmp.resolve("settings.json"));
            AwarenessSettings settings = new AwarenessSettings();
            settings.setAutoFetchOnStartup(false);
            settings.getSources().add(new DataSourceConfig(
                    "ll2", DataSourceConfig.FeedCategory.LAUNCH, "Mock LL2",
                    "http://127.0.0.1:" + port + "/ll2/", true, 1, AuthMode.NONE, "", "", "", 15, true));
            settings.getSources().add(new DataSourceConfig(
                    "tle", DataSourceConfig.FeedCategory.TLE, "Mock TLE",
                    "http://127.0.0.1:" + port + "/tle-new.txt", true, 1, AuthMode.NONE, "", "", "", 15, false));
            store.save(settings);

            GroundStationCatalogRepository gsRepo = new GroundStationCatalogRepository(
                    tmp.resolve("base.csv").toFile(),
                    tmp.resolve("custom.csv").toFile(),
                    tmp.resolve("launches.csv").toFile());
            LiveAwarenessService svc = new LiveAwarenessService(
                    store, tmp.resolve("cache.json"), new LaunchApiClient(), new TleFeedClient(), gsRepo);
            try {
                svc.refreshBlockingForTest();
                LaunchRecord recentLaunch = svc.getVisibleRecentLaunches(BASE_NOW).get(0);

                // Pre-existing satellite with different alias name but same NORAD ID 61001 and older TLE epoch
                SatelliteTleSGP4 existing = new SatelliteTleSGP4(
                        "MY-CUSTOM-ALIAS", STARLINK_LINE1_OLD, STARLINK_LINE2_OLD);
                double oldEpochJd = existing.getTleEpochJD();
                Hashtable<String, AbstractSatellite> satHash = new Hashtable<>();
                satHash.put(existing.getName(), existing);

                Set<String> existingNames = new HashSet<>();
                Set<Integer> existingNoradIds = new HashSet<>();
                JSatTrak.collectExistingSatelliteIdentifiers(satHash, existingNames, existingNoradIds);

                assertEq(true, existingNames.contains("MY-CUSTOM-ALIAS"));
                assertEq(true, existingNoradIds.contains(61001));

                List<PayloadTleMatch> matches = svc.getMatchedTles(recentLaunch, existingNames, existingNoradIds);
                assertEq(true, matches.get(0).alreadyTracked());
                assertEq(false, matches.get(1).alreadyTracked());

                Time simTime = new Time();
                simTime.setTimeInMillis(BASE_NOW);
                AtomicInteger uiUpdates = new AtomicInteger();

                // When updateIfPresent == false, existing satellite is not modified
                boolean skipped = JSatTrak.addOrUpdateSatelliteFromTle(
                        satHash,
                        matches.get(0).tle(),
                        false,
                        simTime.getJulianDate(),
                        sat -> satHash.put(sat.getName(), sat),
                        uiUpdates::incrementAndGet);
                assertEq(false, skipped);
                assertEq(0, uiUpdates.get());
                assertEq(1, satHash.size());
                assertEq(oldEpochJd, existing.getTleEpochJD());

                // When updateIfPresent == true, existing instance is updated in place without renaming key
                boolean updated = JSatTrak.addOrUpdateSatelliteFromTle(
                        satHash,
                        matches.get(0).tle(),
                        true,
                        simTime.getJulianDate(),
                        sat -> satHash.put(sat.getName(), sat),
                        uiUpdates::incrementAndGet);
                assertEq(true, updated);
                assertEq(1, uiUpdates.get());
                assertEq(1, satHash.size());
                assertEq(true, satHash.get("MY-CUSTOM-ALIAS") == existing);
                assertEq("MY-CUSTOM-ALIAS", existing.getName());
                assertEq(true, existing.getTleEpochJD() > oldEpochJd);
            } finally {
                svc.shutdown();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testMapCenteringTimeJumpHubRefreshPickerAndPublicApiSignatures(@TempDir Path tmp)
            throws Exception {
        // Verify all required Task 10 public methods exist on JSatTrak with exact signatures
        Method mGetService = JSatTrak.class.getMethod("getLiveAwarenessService");
        assertEq(LiveAwarenessService.class, mGetService.getReturnType());
        JSatTrak.class.getMethod("openOrFocusLiveAwareness");
        JSatTrak.class.getMethod("openLiveDataSourcesDialog");
        JSatTrak.class.getMethod("jumpToEpochMillis", long.class);
        JSatTrak.class.getMethod("centerAllMapsOn", double.class, double.class);
        Method mAddOrUpdate = JSatTrak.class.getMethod("addOrUpdateSatelliteFromTle", TLE.class, boolean.class);
        assertEq(boolean.class, mAddOrUpdate.getReturnType());

        long t0 = BASE_NOW + 6L * 3600_000L;
        LaunchRecord launch = LaunchRecord.builder()
                .id("l-ksc")
                .missionName("Artemis-X")
                .provider("NASA")
                .vehicle("SLS")
                .cosparId("2026-142")
                .netEpochMillis(t0)
                .windowStartMillis(t0)
                .windowEndMillis(t0 + 1800_000L)
                .status("Go")
                .padName("LC-39A")
                .spaceportName("Kennedy Space Center")
                .latDeg(28.6084)
                .lonDeg(-80.6043)
                .fixedLandPad(true)
                .build();

        LiveAwarenessService svc = LiveAwarenessService.createForTestWithLaunches(tmp, List.of(launch));
        try {
            Time simTime = new Time();
            simTime.setTimeInMillis(BASE_NOW);
            Sun sun = new Sun(simTime.getMJD());
            Hashtable<String, AbstractSatellite> satHash = new Hashtable<>();
            Hashtable<String, GroundStation> gsHash = new Hashtable<>();

            AtomicReference<J2DEarthPanel> twoDRef = new AtomicReference<>();
            AtomicReference<J2DPolarEarthPanel> polarRef = new AtomicReference<>();
            AtomicReference<LiveAwarenessInternalFrame> hubRef = new AtomicReference<>();

            runOnEdt(() -> {
                J2DEarthPanel twoD = new J2DEarthPanel(
                        satHash, gsHash, new JToggleButton(), new JToggleButton(), new JToggleButton(),
                        simTime, sun, null);
                twoD.setSize(600, 300);
                twoD.setAwarenessService(svc);

                J2DPolarEarthPanel polar = new J2DPolarEarthPanel(
                        satHash, gsHash, new JToggleButton(), new JToggleButton(), new JToggleButton(),
                        simTime, sun, null);
                polar.setSize(400, 400);
                polar.setAwarenessService(svc);

                LiveAwarenessInternalFrame hub = new LiveAwarenessInternalFrame(
                        svc,
                        epoch -> {},
                        (lat, lon) -> JSatTrak.centerMapsOn(
                                List.of(twoD),
                                List.of(polar),
                                List.<J3DEarthPanel>of(),
                                List.<J3DEarthInternalPanel>of(),
                                lat,
                                lon),
                        rec -> {},
                        () -> {},
                        () -> {});

                twoDRef.set(twoD);
                polarRef.set(polar);
                hubRef.set(hub);
            });

            // Map click-to-surface listener wiring
            AtomicBoolean openUiCalled = new AtomicBoolean(false);
            svc.addListener(new AwarenessStateListener() {
                @Override
                public void onAwarenessDataChanged() {}

                @Override
                public void onSelectionChanged(String selectedLaunchId) {}

                @Override
                public void onStatusMessage(String message, boolean isWarning) {}

                @Override
                public void onOpenUiRequested() {
                    openUiCalled.set(true);
                }
            });
            svc.selectLaunchAndRequestOpenUi("l-ksc");
            waitForEdt();
            assertEq(true, openUiCalled.get());

            // Center all maps helper (Northern Hemisphere -> North pole mode + pan coordinates)
            runOnEdt(() -> JSatTrak.centerMapsOn(
                    List.of(twoDRef.get()),
                    List.of(polarRef.get()),
                    List.<J3DEarthPanel>of(),
                    List.<J3DEarthInternalPanel>of(),
                    28.6084,
                    -80.6043));
            assertNear(28.6084, twoDRef.get().getCenterLat(), 1e-3);
            assertNear(-80.6043, twoDRef.get().getCenterLong(), 1e-3);
            assertEq(J2dPolarEarthLabel.MODE_NORTH, polarRef.get().getPolarMode());
            assertNear(1.0, polarRef.get().getZoomFactor(), 1e-6);
            double maxAng = polarRef.get().getPolarLabel().getMaxAngularRadiusDeg();
            double normDistNorth = Math.min(1.0, Math.max(0.0, (90.0 - 28.6084) / maxAng));
            assertNear(-normDistNorth * Math.sin(Math.toRadians(-80.6043)), polarRef.get().getPanXNorm(), 1e-4);
            assertNear(-normDistNorth * Math.cos(Math.toRadians(-80.6043)), polarRef.get().getPanYNorm(), 1e-4);

            // Centering on Southern Hemisphere switches polar panel to South pole mode
            runOnEdt(() -> JSatTrak.centerMapsOn(
                    List.of(twoDRef.get()),
                    List.of(polarRef.get()),
                    List.<J3DEarthPanel>of(),
                    List.<J3DEarthInternalPanel>of(),
                    -39.2615,
                    177.8648));
            assertNear(-39.2615, twoDRef.get().getCenterLat(), 1e-3);
            assertNear(177.8648, twoDRef.get().getCenterLong(), 1e-3);
            assertEq(J2dPolarEarthLabel.MODE_SOUTH, polarRef.get().getPolarMode());
            double normDistSouth = Math.min(1.0, Math.max(0.0, (90.0 - 39.2615) / maxAng));
            assertNear(-normDistSouth * Math.sin(Math.toRadians(177.8648)), polarRef.get().getPanXNorm(), 1e-4);
            assertNear(normDistSouth * Math.cos(Math.toRadians(177.8648)), polarRef.get().getPanYNorm(), 1e-4);

            // Jump to T-0 helper exits real-time mode, updates Time, resets ground tracks, refreshes hub
            AtomicBoolean realTimeDisabled = new AtomicBoolean(false);
            AtomicBoolean playCleared = new AtomicBoolean(false);
            AtomicBoolean timeUpdated = new AtomicBoolean(false);
            AtomicReference<Double> jumpDiffDays = new AtomicReference<>(0.0);

            JSatTrak.applyJumpToEpochMillis(
                    simTime,
                    t0,
                    true,
                    () -> realTimeDisabled.set(true),
                    jumpDiffDays::set,
                    () -> playCleared.set(true),
                    () -> {
                        timeUpdated.set(true);
                        hubRef.get().refreshDisplay(
                                simTime.getTimeInMillis(),
                                false,
                                JSatTrak.resolveDisplayTimeZone(false, TimeZone.getTimeZone("America/New_York")));
                    });

            assertEq(true, realTimeDisabled.get());
            assertEq(true, playCleared.get());
            assertEq(true, timeUpdated.get());
            assertEq(t0, simTime.getTimeInMillis());
            assertNear(0.25, jumpDiffDays.get(), 1e-4);

            // Verify PayloadTlePickerPanel smart selection -> TLE import
            List<TLE> parsed = PayloadTleMatcher.parseThreeLineTles(
                    "STARLINK-9001\n" + STARLINK_LINE1_NEW + "\n" + STARLINK_LINE2_NEW + "\n"
                            + "FALCON 9 R/B\n" + FALCON_RB_LINE1 + "\n" + FALCON_RB_LINE2 + "\n");
            List<PayloadTleMatch> matches = PayloadTleMatcher.matchLaunch(launch, parsed, List.of(), Set.of(), Set.of());
            AtomicBoolean confirmed = new AtomicBoolean(false);
            runOnEdt(() -> {
                PayloadTleImportDialog.PayloadTlePickerPanel picker =
                        new PayloadTleImportDialog.PayloadTlePickerPanel(
                                launch, matches, () -> confirmed.set(true), () -> {});
                picker.getAddSelectedButton().doClick();
                assertEq(true, confirmed.get());
                assertEq(1, picker.getSelectedMatches().size());
                assertEq("STARLINK-9001", picker.getSelectedMatches().get(0).objectName());
            });

            // Unhook maps and dispose hub cleanly
            runOnEdt(() -> {
                twoDRef.get().setAwarenessService(null);
                polarRef.get().setAwarenessService(null);
                hubRef.get().dispose();
            });
            assertEq(null, twoDRef.get().getAwarenessService());
            assertEq(null, polarRef.get().getAwarenessService());
        } finally {
            svc.shutdown();
        }
    }

    @Test
    public void testOfflineEndToEndLaunchSyncPadCatalogAndTleImportPipeline(@TempDir Path tmp)
            throws Exception {
        AwarenessSettingsStore store = new AwarenessSettingsStore(tmp.resolve("settings.json"));
        AwarenessSettings settings = new AwarenessSettings();
        settings.setAutoFetchOnStartup(false);
        settings.getSources().add(new DataSourceConfig(
                "ll2", DataSourceConfig.FeedCategory.LAUNCH, "Offline LL2",
                "https://ll.thespacedevs.com/2.2.0/launch/", true, 1, AuthMode.NONE, "", "", "", 15, true));
        settings.getSources().add(new DataSourceConfig(
                "tle", DataSourceConfig.FeedCategory.TLE, "Offline TLE",
                "https://celestrak.org/NORAD/elements/gp.php?GROUP=tle-new&FORMAT=3le",
                true, 1, AuthMode.NONE, "", "", "", 15, false));
        store.save(settings);

        File baseDb = tmp.resolve("base.csv").toFile();
        Files.writeString(baseDb.toPath(), "Launch,Legacy Pad,28.5,-81.0,0\n");
        GroundStationCatalogRepository gsRepo = new GroundStationCatalogRepository(
                baseDb, tmp.resolve("custom.csv").toFile(), tmp.resolve("launches.csv").toFile());

        String upcomingJson = buildMockUpcomingJson();
        String recentJson = buildMockRecentJson();
        String tleText = buildMockTleCatalog();

        AtomicInteger launchFetches = new AtomicInteger();
        AtomicInteger tleFetches = new AtomicInteger();
        LaunchApiClient stubLaunchClient = new LaunchApiClient() {
            @Override
            public LaunchFetchResult fetchUpcomingAndRecent(List<DataSourceConfig> sources, long realNowMillis) {
                launchFetches.incrementAndGet();
                return new LaunchFetchResult(
                        LaunchLibrary2Parser.parseResults(upcomingJson),
                        LaunchLibrary2Parser.parseResults(recentJson),
                        "OfflineMockLL2");
            }
        };
        TleFeedClient stubTleClient = new TleFeedClient() {
            @Override
            public List<TLE> fetchTleCatalog(DataSourceConfig source) {
                tleFetches.incrementAndGet();
                return PayloadTleMatcher.parseThreeLineTles(tleText);
            }
        };

        LiveAwarenessService svc = new LiveAwarenessService(
                store, tmp.resolve("cache.json"), stubLaunchClient, stubTleClient, gsRepo);
        try {
            svc.initializeAtStartup();
            Thread.sleep(50);
            assertEq(0, launchFetches.get());
            assertEq(0, tleFetches.get());

            List<GroundStationCatalogRepository.CatalogEntry> initialPads = JSatTrak.loadLaunchApiPads(gsRepo);
            assertEq(0, initialPads.size());

            svc.refreshBlockingForTest();
            assertEq(1, launchFetches.get());
            assertEq(1, tleFetches.get());

            AtomicInteger gsReloads = new AtomicInteger();
            List<GroundStationCatalogRepository.CatalogEntry> updatedPads =
                    JSatTrak.syncLaunchApiPadSnapshot(gsRepo, initialPads, gsReloads::incrementAndGet);
            assertEq(2, updatedPads.size());
            assertEq(1, gsReloads.get());

            List<LaunchRecord> recent = svc.getVisibleRecentLaunches(BASE_NOW);
            assertEq(1, recent.size());
            assertEq(LaunchTleStatus.TLES_READY, svc.getTleStatus(recent.get(0)));

            Hashtable<String, AbstractSatellite> satHash = new Hashtable<>();
            SatelliteTleSGP4 existing = new SatelliteTleSGP4(
                    "MY-CUSTOM-ALIAS", STARLINK_LINE1_OLD, STARLINK_LINE2_OLD);
            double oldEpochJd = existing.getTleEpochJD();
            satHash.put(existing.getName(), existing);

            Set<String> names = new HashSet<>();
            Set<Integer> noradIds = new HashSet<>();
            JSatTrak.collectExistingSatelliteIdentifiers(satHash, names, noradIds);
            assertEq(true, noradIds.contains(61001));

            List<PayloadTleMatch> matches = svc.getMatchedTles(recent.get(0), names, noradIds);
            assertEq(2, matches.size());
            assertEq(true, matches.get(0).alreadyTracked());
            assertEq(false, matches.get(1).alreadyTracked());

            Time simTime = new Time();
            simTime.setTimeInMillis(BASE_NOW);
            AtomicInteger uiUpdates = new AtomicInteger();

            assertEq(false, JSatTrak.addOrUpdateSatelliteFromTle(
                    satHash, matches.get(0).tle(), false, simTime.getJulianDate(),
                    sat -> satHash.put(sat.getName(), sat), uiUpdates::incrementAndGet));
            assertEq(true, JSatTrak.addOrUpdateSatelliteFromTle(
                    satHash, matches.get(0).tle(), true, simTime.getJulianDate(),
                    sat -> satHash.put(sat.getName(), sat), uiUpdates::incrementAndGet));
            assertEq(true, existing.getTleEpochJD() > oldEpochJd);
            assertEq(1, uiUpdates.get());

            assertEq(true, JSatTrak.addOrUpdateSatelliteFromTle(
                    satHash, matches.get(1).tle(), false, simTime.getJulianDate(),
                    sat -> satHash.put(sat.getName(), sat), uiUpdates::incrementAndGet));
            assertEq(2, satHash.size());
            assertEq(2, uiUpdates.get());
        } finally {
            svc.shutdown();
        }
    }

    private static String buildMockUpcomingJson() {
        String upcomingIso = Instant.ofEpochMilli(BASE_NOW + 6L * 3600_000L).toString();
        return """
                {
                  "results": [
                    {
                      "id": "u-1",
                      "name": "SLS | Artemis-X",
                      "net": "%s",
                      "window_start": "%s",
                      "window_end": "%s",
                      "status": {"name": "Go for Launch", "abbrev": "Go"},
                      "launch_service_provider": {"name": "NASA"},
                      "rocket": {"configuration": {"full_name": "SLS Block 1"}},
                      "mission": {"name": "Artemis-X", "orbit": {"abbrev": "TLI"}},
                      "pad": {
                        "name": "Launch Complex 39A",
                        "latitude": 28.6084,
                        "longitude": -80.6043,
                        "location": {"name": "Kennedy Space Center", "country_code": "USA"}
                      }
                    }
                  ]
                }
                """.formatted(upcomingIso, upcomingIso, upcomingIso);
    }

    private static String buildMockRecentJson() {
        String recentIso = Instant.ofEpochMilli(BASE_NOW - 12L * 3600_000L).toString();
        return """
                {
                  "results": [
                    {
                      "id": "r-1",
                      "name": "Falcon 9 | Starlink 12-4",
                      "net": "%s",
                      "window_start": "%s",
                      "window_end": "%s",
                      "status": {"name": "Launch Successful", "abbrev": "Success"},
                      "launch_service_provider": {"name": "SpaceX"},
                      "rocket": {"configuration": {"full_name": "Falcon 9 Block 5"}},
                      "mission": {"name": "Starlink 12-4", "orbit": {"abbrev": "LEO"}},
                      "pad": {
                        "name": "Space Launch Complex 40",
                        "latitude": 28.5619,
                        "longitude": -80.5774,
                        "location": {"name": "Cape Canaveral SFS", "country_code": "USA"}
                      },
                      "launch_designator": "2026-142"
                    }
                  ]
                }
                """.formatted(recentIso, recentIso, recentIso);
    }

    private static String buildMockTleCatalog() {
        return "STARLINK-9001\n"
                + STARLINK_LINE1_NEW + "\n"
                + STARLINK_LINE2_NEW + "\n"
                + "FALCON 9 R/B\n"
                + FALCON_RB_LINE1 + "\n"
                + FALCON_RB_LINE2 + "\n";
    }

    private static void registerMockEndpoints(
            HttpServer server,
            AtomicInteger launchHits,
            AtomicInteger tleHits) {
        String upcomingJson = buildMockUpcomingJson();
        String recentJson = buildMockRecentJson();
        String tleCatalog = buildMockTleCatalog();

        server.createContext("/ll2/upcoming/", exchange -> {
            launchHits.incrementAndGet();
            sendResponse(exchange, 200, "application/json", upcomingJson);
        });
        server.createContext("/ll2/previous/", exchange -> {
            launchHits.incrementAndGet();
            sendResponse(exchange, 200, "application/json", recentJson);
        });
        server.createContext("/tle-new.txt", exchange -> {
            tleHits.incrementAndGet();
            sendResponse(exchange, 200, "text/plain", tleCatalog);
        });
    }

    private static void sendResponse(
            com.sun.net.httpserver.HttpExchange exchange,
            int status,
            String contentType,
            String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void runOnEdt(Runnable task) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeAndWait(task);
        }
    }

    private static void waitForEdt() throws Exception {
        runOnEdt(() -> {});
    }

    private static void assertEq(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertNear(double expected, double actual, double eps) {
        if (Math.abs(expected - actual) > eps) {
            throw new AssertionError("Expected ~" + expected + " (±" + eps + ") but was " + actual);
        }
    }
}

package jsattrak.awareness.gnss.stations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jsattrak.awareness.config.AwarenessSettings;
import jsattrak.awareness.config.AwarenessSettingsStore;
import jsattrak.awareness.gnss.GnssAwarenessService;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.geometry.GnssGeometryService;
import jsattrak.awareness.gnss.net.GnssStationCatalogClient;
import jsattrak.awareness.gnss.stations.obs.GnssObservationSource;
import jsattrak.awareness.gnss.stations.obs.RinexObservationData;
import jsattrak.gui.JSatTrak;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class GnssStationServiceTest {

    @Test
    void obeysStartupSilenceLoadsSeedThenAtomicCacheAndDropsStaleAsyncSelections(@TempDir Path tmpDir)
            throws Exception {
        JSatTrak.initOrekitData();
        AwarenessSettingsStore store = new AwarenessSettingsStore(tmpDir.resolve("awareness-settings.json"));
        AwarenessSettings s = AwarenessSettings.createDefaults();
        s.setAutoFetchOnStartup(false);
        s.setGnssFeedEnabled(false);
        store.save(s);

        GnssAwarenessService awareness = GnssAwarenessService.createForTest(
                tmpDir, List.of(), List.of(), Map.of(), Map.of(), List.of());
        awareness.syncSettingsSilently(s);
        GnssGeometryService geom = new GnssGeometryService(awareness, store);

        AtomicInteger catCalls = new AtomicInteger(0);
        GnssReferenceStation stA = station("ST0A00ESP", 40.4, -3.7);
        GnssReferenceStation stB = station("ST0B00BEL", 50.8, 4.3);

        GnssStationCatalogClient mockCat = new GnssStationCatalogClient() {
            @Override
            public CatalogSyncResult fetchMergedCatalog(
                    List<jsattrak.awareness.config.DataSourceConfig> catSources,
                    List<jsattrak.awareness.config.DataSourceConfig> arcSources,
                    long nowMillis) {
                catCalls.incrementAndGet();
                return new CatalogSyncResult(List.of(stA, stB), "Synced 2");
            }
        };

        CountDownLatch stAEntered = new CountDownLatch(1);
        CountDownLatch releaseStA = new CountDownLatch(1);
        GnssObservationSource mockObsSource = new GnssObservationSource() {
            @Override
            public String getSourceId() {
                return "mock";
            }

            @Override
            public String getDisplayName() {
                return "Mock Obs";
            }

            @Override
            public boolean supportsNetwork(GnssStationNetwork network) {
                return network == GnssStationNetwork.IGS;
            }

            @Override
            public boolean isRealTimeStream() {
                return false;
            }

            @Override
            public ObservationFetchResult fetchObservations(GnssReferenceStation st, ObservationQuery q)
                    throws IOException {
                if ("ST0A00ESP".equals(st.stationId())) {
                    stAEntered.countDown();
                    try {
                        releaseStA.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                RinexObservationData data = RinexObservationLoaderHelper.parseMinimal(st.stationId(), st.monumentEcef());
                return new ObservationFetchResult(st, data, "Mock", "mock://" + st.stationId(), q.targetEpochMillis(), "RNX");
            }
        };

        Path cachePath = tmpDir.resolve("gnss-stations-cache.json");
        GnssStationService svc = new GnssStationService(
                awareness, geom, store, cachePath, mockCat, List.of(mockObsSource), null);

        // 1. Startup silence + seed load (not stale offline before first live sync)
        svc.initializeAtStartup();
        assertEquals(0, catCalls.get());
        assertFalse(svc.isDataStale(), "Initial offline seed state must not report Stale before any sync failure");
        assertTrue(svc.getStations().size() >= 30, "Seed must populate startup stations");
        assertFalse(svc.isMapOverlayEnabled());

        // 2. gnssFeedEnabled = false keeps ensureActivated silent
        svc.ensureActivated();
        Thread.sleep(100);
        assertEquals(0, catCalls.get());

        // 3. Enable & refresh syncs atomic cache; second instance reloads cached 2 stations offline
        s.setGnssFeedEnabled(true);
        awareness.syncSettingsSilently(s);
        CountDownLatch syncedLatch = new CountDownLatch(1);
        svc.addListener(new GnssStationListener() {
            @Override
            public void onStationCatalogChanged() {
                syncedLatch.countDown();
            }
        });
        svc.refreshNowAsync();
        assertTrue(syncedLatch.await(5, TimeUnit.SECONDS));
        assertTrue(Files.isRegularFile(cachePath));

        GnssStationService offlineReload = new GnssStationService(
                awareness, geom, store, cachePath, mockCat, List.of(), null);
        assertEquals(2, offlineReload.getStations().size());
        offlineReload.shutdown();

        // 4. Generation counter drops stale Station A callback after Station B fetch completes
        CountDownLatch bUpdated = new CountDownLatch(1);
        svc.addListener(new GnssStationListener() {
            @Override
            public void onInspectionUpdated(jsattrak.awareness.gnss.stations.analysis.GnssStationAnalysis a) {
                if (a != null && "ST0B00BEL".equals(a.station().stationId())) {
                    bUpdated.countDown();
                }
            }
        });
        svc.selectStation("ST0A00ESP");
        svc.fetchSelectedStationObservationsAsync();
        assertTrue(stAEntered.await(5, TimeUnit.SECONDS));

        svc.selectStation("ST0B00BEL");
        svc.fetchSelectedStationObservationsAsync();
        assertTrue(bUpdated.await(5, TimeUnit.SECONDS));

        releaseStA.countDown();
        Thread.sleep(150);
        assertNotNull(svc.getLatestAnalysis());
        assertEquals("ST0B00BEL", svc.getLatestAnalysis().station().stationId());

        svc.shutdown();
        geom.shutdown();
    }

    @Test
    void matchSimEpochNeverAutoDownloadsOnClockTickAndRoutesHistoricalOnlyOnButtonClick(@TempDir Path tmpDir)
            throws Exception {
        JSatTrak.initOrekitData();
        AwarenessSettingsStore store = new AwarenessSettingsStore(tmpDir.resolve("awareness-settings.json"));
        GnssAwarenessService awareness = GnssAwarenessService.createForTest(
                tmpDir, List.of(), List.of(), Map.of(), Map.of(), List.of());
        GnssGeometryService geom = new GnssGeometryService(awareness, store);

        GnssReferenceStation madr = station("MADR00ESP", 40.4292, -4.2497);
        List<GnssObservationSource.ObservationQuery> recordedQueries = new CopyOnWriteArrayList<>();
        GnssObservationSource mockObs = new GnssObservationSource() {
            @Override
            public String getSourceId() {
                return "mock";
            }

            @Override
            public String getDisplayName() {
                return "Mock";
            }

            @Override
            public boolean supportsNetwork(GnssStationNetwork network) {
                return true;
            }

            @Override
            public boolean isRealTimeStream() {
                return false;
            }

            @Override
            public ObservationFetchResult fetchObservations(GnssReferenceStation st, ObservationQuery q)
                    throws IOException {
                recordedQueries.add(q);
                RinexObservationData data = RinexObservationLoaderHelper.parseMinimal(st.stationId(), st.monumentEcef());
                return new ObservationFetchResult(st, data, "Mock", "mock://hist", q.targetEpochMillis(), "RNX");
            }
        };
        GnssStationCatalogClient mockCat = new GnssStationCatalogClient() {
            @Override
            public CatalogSyncResult fetchMergedCatalog(
                    List<jsattrak.awareness.config.DataSourceConfig> cat,
                    List<jsattrak.awareness.config.DataSourceConfig> arc,
                    long nowMillis) {
                return new CatalogSyncResult(List.of(madr), "OK");
            }
        };

        GnssStationService svc = new GnssStationService(
                awareness, geom, store, tmpDir.resolve("cache.json"), mockCat, List.of(mockObs), null);
        CountDownLatch syncLatch = new CountDownLatch(1);
        svc.addListener(new GnssStationListener() {
            @Override
            public void onStationCatalogChanged() {
                syncLatch.countDown();
            }
        });
        svc.refreshNowAsync();
        assertTrue(syncLatch.await(5, TimeUnit.SECONDS));

        svc.selectStation("MADR00ESP");
        svc.setMatchSimEpoch(true);
        long histEpoch = Instant.parse("2024-06-01T00:00:00Z").toEpochMilli();
        for (int i = 0; i < 5; i++) {
            svc.updateSimEpochMillis(histEpoch + i * 60_000L, false);
        }
        Thread.sleep(100);
        assertTrue(recordedQueries.isEmpty(), "Sim clock scrubbing must never trigger network downloads");

        CountDownLatch fetchLatch = new CountDownLatch(1);
        svc.addListener(new GnssStationListener() {
            @Override
            public void onInspectionUpdated(jsattrak.awareness.gnss.stations.analysis.GnssStationAnalysis a) {
                if (a != null) {
                    fetchLatch.countDown();
                }
            }
        });
        svc.fetchSelectedStationObservationsAsync();
        assertTrue(fetchLatch.await(5, TimeUnit.SECONDS));
        assertEquals(1, recordedQueries.size());
        assertFalse(recordedQueries.get(0).preferLatestAvailable());
        assertEquals(histEpoch + 4 * 60_000L, recordedQueries.get(0).targetEpochMillis());

        // Local multi-file import populates inspector & RegionalScanReport
        Path f1 = tmpDir.resolve("LOC100ESP.rnx");
        Path f2 = tmpDir.resolve("LOC200ESP.rnx");
        Files.writeString(f1, buildSampleRnx("LOC100ESP", 4849202.3, -360328.9, 4114913.2), StandardCharsets.UTF_8);
        Files.writeString(f2, buildSampleRnx("LOC200ESP", 4840000.0, -350000.0, 4120000.0), StandardCharsets.UTF_8);

        CountDownLatch batchLatch = new CountDownLatch(1);
        svc.addListener(new GnssStationListener() {
            @Override
            public void onRegionalScanUpdated(jsattrak.awareness.gnss.stations.analysis.RegionalScanReport report) {
                if (report != null && report.stations().size() == 2) {
                    batchLatch.countDown();
                }
            }
        });
        svc.loadLocalObservationFilesAsync(List.of(f1, f2));
        assertTrue(batchLatch.await(5, TimeUnit.SECONDS));
        assertNotNull(svc.getLatestAnalysis());
        assertEquals(2, svc.getLatestRegionalReport().stations().size());

        // Offset-clamped sim-epoch ticks must not cancel an in-flight explicit Fetch Latest Obs
        long nowOutsideObs = histEpoch + 12L * 3600_000L;
        svc.updateSimEpochMillis(nowOutsideObs, true);
        svc.updateSimEpochMillis(nowOutsideObs + 60_000L, true);

        CountDownLatch slowBarrierStart = new CountDownLatch(1);
        CountDownLatch slowBarrierRelease = new CountDownLatch(1);
        AtomicInteger callSeq = new AtomicInteger(0);
        GnssObservationSource barrierSource = new GnssObservationSource() {
            @Override public String getSourceId() { return "barrier"; }
            @Override public String getDisplayName() { return "Barrier"; }
            @Override public boolean supportsNetwork(GnssStationNetwork n) { return true; }
            @Override public boolean isRealTimeStream() { return false; }
            @Override public ObservationFetchResult fetchObservations(GnssReferenceStation s, ObservationQuery q) throws IOException {
                int c = callSeq.incrementAndGet();
                if (c == 2) {
                    slowBarrierStart.countDown();
                    try { slowBarrierRelease.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                }
                RinexObservationData data = RinexObservationLoaderHelper.parseMinimal(s.stationId(), s.monumentEcef());
                return new ObservationFetchResult(s, data, "Barrier", "mock://b" + c, q.targetEpochMillis(), "RNX");
            }
        };
        GnssStationService tickIsolationSvc = new GnssStationService(
                awareness, geom, store, tmpDir.resolve("tick-cache-2.json"),
                mockCat, List.of(barrierSource), null);
        CountDownLatch catLatch3 = new CountDownLatch(1);
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        tickIsolationSvc.addListener(new GnssStationListener() {
            @Override public void onStationCatalogChanged() { catLatch3.countDown(); }
            @Override public void onInspectionUpdated(jsattrak.awareness.gnss.stations.analysis.GnssStationAnalysis a) {
                if (callSeq.get() == 1) firstDone.countDown();
                else if (callSeq.get() >= 2) secondDone.countDown();
            }
        });
        tickIsolationSvc.refreshNowAsync();
        assertTrue(catLatch3.await(5, TimeUnit.SECONDS));
        tickIsolationSvc.selectStation("MADR00ESP");
        tickIsolationSvc.updateSimEpochMillis(histEpoch + 10L * 3600_000L, true);
        tickIsolationSvc.fetchSelectedStationObservationsAsync();
        assertTrue(firstDone.await(5, TimeUnit.SECONDS));

        // Now start a second fetch and fire simulation ticks mid-flight; ticks must NOT drop the fetch!
        tickIsolationSvc.fetchSelectedStationObservationsAsync();
        assertTrue(slowBarrierStart.await(5, TimeUnit.SECONDS));
        tickIsolationSvc.updateSimEpochMillis(histEpoch + 11L * 3600_000L, true);
        tickIsolationSvc.updateSimEpochMillis(histEpoch + 12L * 3600_000L, true);
        slowBarrierRelease.countDown();
        assertTrue(secondDone.await(5, TimeUnit.SECONDS),
                "Background sim-epoch ticks must not cancel in-flight explicit observation fetch");

        // Importing a local file matching an existing catalog station preserves its existing network membership
        Path matchingLocal = tmpDir.resolve("MADR00ESP.rnx");
        Files.writeString(matchingLocal, buildSampleRnx("MADR00ESP", 4849202.3, -360328.9, 4114913.2), StandardCharsets.UTF_8);
        CountDownLatch localMergeLatch = new CountDownLatch(1);
        tickIsolationSvc.addListener(new GnssStationListener() {
            @Override public void onStationCatalogChanged() { localMergeLatch.countDown(); }
        });
        tickIsolationSvc.loadLocalObservationFilesAsync(List.of(matchingLocal));
        assertTrue(localMergeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(tickIsolationSvc.findStationById("MADR00ESP").networks().contains(GnssStationNetwork.IGS),
                "Local RINEX import must preserve existing IGS network membership");

        tickIsolationSvc.shutdown();
        svc.shutdown();
        geom.shutdown();
    }

    private static GnssReferenceStation station(String id, double lat, double lon) {
        return new GnssReferenceStation(
                id, id.substring(0, 4), id, "ESP", GnssStationNetwork.IGS,
                EnumSet.of(GnssStationNetwork.IGS), lat, lon, 600.0, null,
                "SEPT POLARX5", "5.5", "AOAD/M_T", "NONE",
                EnumSet.of(GnssConstellation.GPS), List.of(), 0L, 15, Double.NaN, GnssStationStatus.ONLINE, "");
    }

    private static String hdr(String c, String label) {
        return String.format(Locale.US, "%-60s%s\n", c, label);
    }

    private static String buildSampleRnx(String marker, double x, double y, double z) {
        return hdr("     3.04           OBSERVATION DATA    M", "RINEX VERSION / TYPE")
                + hdr("TEST                TEST                20261125 190000 UTC", "PGM / RUN BY / DATE")
                + hdr(marker, "MARKER NAME")
                + hdr("001", "MARKER NUMBER")
                + hdr("GEODETIC", "MARKER TYPE")
                + hdr("TESTOBS             TESTAGENCY", "OBSERVER / AGENCY")
                + hdr("001                 SEPT POLARX5        5.5.0", "REC # / TYPE / VERS")
                + hdr("002                 AOAD/M_T            NONE", "ANT # / TYPE")
                + hdr(String.format(Locale.US, "%14.4f%14.4f%14.4f", x, y, z), "APPROX POSITION XYZ")
                + hdr(String.format(Locale.US, "%14.4f%14.4f%14.4f", 0.0, 0.0, 0.0), "ANTENNA: DELTA H/E/N")
                + hdr("G    3 C1C L1C S1C", "SYS / # / OBS TYPES")
                + hdr("    30.000", "INTERVAL")
                + hdr("  2026    11    25    19    00    0.0000000     GPS", "TIME OF FIRST OBS")
                + hdr("", "END OF HEADER")
                + "> 2026 11 25 19 00  0.0000000  0  1\n"
                + String.format(Locale.US, "G01%14.3f %c%14.3f %c%14.3f  %n", 21234567.89, '8', 111600000.0, '8', 46.5);
    }

    private static final class RinexObservationLoaderHelper {
        static RinexObservationData parseMinimal(String marker, double[] ecef) throws IOException {
            String txt = buildSampleRnx(marker, ecef[0], ecef[1], ecef[2]);
            return jsattrak.awareness.gnss.stations.obs.RinexObservationLoader.parse(
                    txt.getBytes(StandardCharsets.UTF_8), marker + ".rnx");
        }
    }
}

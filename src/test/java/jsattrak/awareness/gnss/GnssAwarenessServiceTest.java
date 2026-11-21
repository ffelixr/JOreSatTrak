package jsattrak.awareness.gnss;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import jsattrak.awareness.config.AwarenessSettings;
import jsattrak.awareness.config.AwarenessSettingsStore;
import jsattrak.awareness.config.DataSourceConfig;
import jsattrak.awareness.gnss.net.BroadcastNavClient;
import jsattrak.awareness.gnss.net.GnssCatalogClient;
import jsattrak.awareness.gnss.net.OperatorStatusClient;
import jsattrak.awareness.gnss.net.RinexNavHealthParser;
import jsattrak.awareness.tle.TleFeedClient;
import jsattrak.gui.JSatTrak;
import jsattrak.utilities.TLE;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class GnssAwarenessServiceTest {

    private static final long T0 = 1_795_132_800_000L; // 2026-11-21T00:00:00Z

    @Test
    void quietStartupMakesZeroCallsUntilActivatedOrAutoFetchEnabled(@TempDir Path tmpDir) throws Exception {
        AtomicInteger catCalls = new AtomicInteger();
        AtomicInteger tleCalls = new AtomicInteger();
        AtomicInteger navCalls = new AtomicInteger();
        AtomicInteger opCalls = new AtomicInteger();

        GnssAwarenessService svc = buildMockService(
                tmpDir, new MutableClock(T0), catCalls, tleCalls, navCalls, opCalls, false);
        try {
            svc.initializeAtStartup();
            assertEquals(0, catCalls.get());
            assertEquals(0, tleCalls.get());
            assertEquals(0, navCalls.get());
            assertEquals(0, opCalls.get());

            svc.refreshBlockingForTest();
            assertEquals(1, catCalls.get());
            assertEquals(1, tleCalls.get());
            assertEquals(1, navCalls.get());
            assertEquals(1, opCalls.get());
        } finally {
            svc.shutdown();
        }
    }

    @Test
    void tieredRefreshSkipsDailyCatalogAndTleOnSubsequent15MinTickUnlessForced(@TempDir Path tmpDir) throws Exception {
        AtomicInteger catCalls = new AtomicInteger();
        AtomicInteger tleCalls = new AtomicInteger();
        AtomicInteger navCalls = new AtomicInteger();
        AtomicInteger opCalls = new AtomicInteger();
        MutableClock clock = new MutableClock(T0);

        GnssAwarenessService svc = buildMockService(
                tmpDir, clock, catCalls, tleCalls, navCalls, opCalls, false);
        try {
            svc.refreshScheduledTickBlockingForTest();
            assertEquals(1, catCalls.get());
            assertEquals(1, tleCalls.get());
            assertEquals(1, navCalls.get());
            assertEquals(1, opCalls.get());

            // Advance 15 minutes: daily sources skipped, 15m sources refreshed
            clock.advanceMillis(15L * 60_000L);
            svc.refreshScheduledTickBlockingForTest();
            assertEquals(1, catCalls.get());
            assertEquals(1, tleCalls.get());
            assertEquals(2, navCalls.get());
            assertEquals(2, opCalls.get());

            // Advance 24h 1m: daily sources refresh again
            clock.advanceMillis(24L * 3600_000L + 60_000L);
            svc.refreshScheduledTickBlockingForTest();
            assertEquals(2, catCalls.get());
            assertEquals(2, tleCalls.get());
            assertEquals(3, navCalls.get());
            assertEquals(3, opCalls.get());
        } finally {
            svc.shutdown();
        }
    }

    @Test
    void partialFailureRetainsCacheAndExposesOperationalOnlyForLayer2(@TempDir Path tmpDir) throws Exception {
        JSatTrak.initOrekitData();
        AtomicInteger catCalls = new AtomicInteger();
        AtomicInteger tleCalls = new AtomicInteger();
        AtomicInteger navCalls = new AtomicInteger();
        AtomicInteger opCalls = new AtomicInteger();
        MutableClock clock = new MutableClock(T0);

        GnssAwarenessService svc = buildMockService(
                tmpDir, clock, catCalls, tleCalls, navCalls, opCalls, true);
        try {
            svc.refreshBlockingForTest();
            List<GnssSatelliteState> opOnly = svc.getOperationalSatellites(T0);
            assertEquals(2, opOnly.size());
            assertTrue(opOnly.stream().allMatch(s -> s.isOperational() && s.position() != null));

            // Second refresh fails on broadcast nav -> cached G01/G07/E11/E26 retained, BKG stale
            svc.refreshBlockingForTest();
            assertEquals(4, svc.getAllSatelliteStates(T0).size());
            assertEquals(2, svc.getOperationalSatellites(T0).size());
            assertTrue(svc.getSourceHealthSummary().stream()
                    .anyMatch(h -> h.key().equals("BROADCAST_NAV") && h.stale()));
        } finally {
            svc.shutdown();
        }

        // Corrupt cache file -> new service recovers cleanly without throwing
        Path cacheFile = tmpDir.resolve("gnss-cache.json");
        Files.writeString(cacheFile, "{corrupt json");
        GnssAwarenessService recovered = buildMockService(
                tmpDir, clock, catCalls, tleCalls, navCalls, opCalls, false);
        try {
            assertNotNull(recovered.getAllSatelliteStates(T0));
        } finally {
            recovered.shutdown();
        }
    }

    @Test
    void plannedOutageBoundaryTransitionDetectedWhenSimClockMoves(@TempDir Path tmpDir) {
        JSatTrak.initOrekitData();
        long start = T0 + 2L * 3600_000L;
        long end = T0 + 8L * 3600_000L;
        GnssCatalogEntry g18 = new GnssCatalogEntry(
                "G18", GnssConstellation.GPS, 18, "G078", 55268, "2023-009A",
                "GPS-III", GnssOrbitClass.MEO, "A", "2", "2023-01-18");
        TLE g18Tle = new TLE("GPS III SV06",
                "1 55268U 23009A   26312.06320080  .00000016  00000+0  00000+0 0  9991",
                "2 55268  54.8263 209.4537 0115270 305.0010 182.1874  2.00558916192834");
        BroadcastHealthRecord healthy = new BroadcastHealthRecord(
                "G18", GnssConstellation.GPS, "LNAV", T0, 0, true, "OK");
        GnssOutageNotice planned = new GnssOutageNotice(
                "NANU 2026074", GnssConstellation.GPS, "G18", "G078", "FCSTDV",
                true, false, start, end, "Planned DV", "NAVCEN");

        GnssAwarenessService svc = GnssAwarenessService.createForTest(
                tmpDir,
                List.of(g18),
                List.of(g18Tle),
                Map.of("G18", healthy),
                Map.of(),
                List.of(planned));
        try {
            assertFalse(svc.hasCrossedNoticeBoundary(T0, T0 + 3600_000L));
            assertTrue(svc.hasCrossedNoticeBoundary(T0 + 3600_000L, T0 + 3L * 3600_000L));
            assertEquals(GnssDisplayStatus.OPERATIONAL,
                    svc.getSatelliteState("G18", T0).orElseThrow().statusResult().status());
            assertEquals(GnssDisplayStatus.UNAVAILABLE,
                    svc.getSatelliteState("G18", T0 + 3L * 3600_000L).orElseThrow().statusResult().status());
        } finally {
            svc.shutdown();
        }
    }

    private static GnssAwarenessService buildMockService(
            Path tmpDir,
            MutableClock clock,
            AtomicInteger catCalls,
            AtomicInteger tleCalls,
            AtomicInteger navCalls,
            AtomicInteger opCalls,
            boolean failNavOnSecondCall) throws IOException {
        AwarenessSettingsStore store = new AwarenessSettingsStore(tmpDir.resolve("awareness-settings.json"));
        store.save(AwarenessSettings.createDefaults());

        List<GnssCatalogEntry> cat = List.of(
                new GnssCatalogEntry("G01", GnssConstellation.GPS, 1, "G063", 37753, "2011-036A", "GPS-IIF", GnssOrbitClass.MEO, "D", "2", "2011-07-16"),
                new GnssCatalogEntry("G07", GnssConstellation.GPS, 7, "G056", 32711, "2008-012A", "GPS-IIRM", GnssOrbitClass.MEO, "A", "4", "2008-03-15"),
                new GnssCatalogEntry("E11", GnssConstellation.GALILEO, 11, "E101", 37846, "2011-060A", "GALILEO-1", GnssOrbitClass.MEO, "B", "5", "2011-10-21"),
                new GnssCatalogEntry("E26", GnssConstellation.GALILEO, 26, "E226", 43564, "2018-060A", "GALILEO-2", GnssOrbitClass.MEO, "A", "1", "2018-07-25"));
        List<TLE> tles = List.of(
                new TLE("G01", "1 37753U 11036A   26312.00000000  .00000000  00000+0  00000+0 0  9991", "2 37753  55.0000 100.0000 0010000  90.0000 270.0000  2.00560000100001"),
                new TLE("G07", "1 32711U 08012A   26312.00000000  .00000000  00000+0  00000+0 0  9991", "2 32711  55.0000 150.0000 0010000  90.0000 270.0000  2.00560000100001"),
                new TLE("E11", "1 37846U 11060A   26312.00000000  .00000000  00000+0  00000+0 0  9991", "2 37846  56.0000 200.0000 0002000  90.0000 270.0000  1.70475000100001"),
                new TLE("E26", "1 43564U 18060A   26312.00000000  .00000000  00000+0  00000+0 0  9991", "2 43564  56.0000 250.0000 0002000  90.0000 270.0000  1.70475000100001"));

        GnssCatalogClient catClient = new GnssCatalogClient() {
            @Override
            public CatalogFetchResult fetchCatalog(List<DataSourceConfig> sources, long refEpoch) {
                catCalls.incrementAndGet();
                return new CatalogFetchResult(cat, "MockIGS");
            }
        };
        TleFeedClient tleClient = new TleFeedClient() {
            @Override
            public List<TLE> fetchTleCatalog(DataSourceConfig source) {
                tleCalls.incrementAndGet();
                return tles;
            }
        };
        BroadcastNavClient navClient = new BroadcastNavClient() {
            @Override
            public BroadcastFetchResult fetchLatest(List<DataSourceConfig> sources) throws IOException {
                int c = navCalls.incrementAndGet();
                if (failNavOnSecondCall && c >= 2) {
                    throw new IOException("Simulated BKG outage");
                }
                long ep = clock.millis();
                Map<String, BroadcastHealthRecord> map = Map.of(
                        "G01", new BroadcastHealthRecord("G01", GnssConstellation.GPS, "LNAV", ep, 0, true, "OK"),
                        "G07", new BroadcastHealthRecord("G07", GnssConstellation.GPS, "LNAV", ep, 63, false, "unhealthy"),
                        "E11", new BroadcastHealthRecord("E11", GnssConstellation.GALILEO, "INAV", ep, 0, true, "OK"),
                        "E26", new BroadcastHealthRecord("E26", GnssConstellation.GALILEO, "INAV", ep, 511, false, "unhealthy"));
                return new BroadcastFetchResult(
                        new RinexNavHealthParser.ParseResult(map, ep, 4, "3.04"),
                        "MockBKG", "https://mock/brdc");
            }
        };
        OperatorStatusClient opClient = new OperatorStatusClient() {
            @Override
            public OperatorFetchResult fetchAll(Map<String, List<DataSourceConfig>> scopedSources, long nowMillis) {
                opCalls.incrementAndGet();
                return new OperatorFetchResult(
                        Map.of("E26", new OperatorStatusRecord("E26", GnssConstellation.GALILEO, "GSC",
                                OperatorStatusRecord.OperatorHealth.TESTING, "UNDER COMMISSIONING", nowMillis, "commissioning")),
                        List.of(), true, true, Map.of(), Map.of());
            }
        };

        return new GnssAwarenessService(
                store,
                tmpDir.resolve("gnss-cache.json"),
                new GnssManualStatusStore(tmpDir.resolve("gnss-manual-status.json")),
                null,
                catClient,
                tleClient,
                navClient,
                opClient,
                clock);
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong millis;

        MutableClock(long initialMillis) {
            this.millis = new AtomicLong(initialMillis);
        }

        void advanceMillis(long delta) {
            millis.addAndGet(delta);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }
}

package jsattrak.awareness.gnss;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class GnssStatusEvaluatorTest {
    private static final long T0 = 1_795_132_800_000L; // 2026-11-21T00:00:00Z

    @Test
    void activeOutageNoticeBeatsManualOperationalOverrideAndBroadcastHealthy() {
        GnssCatalogEntry g18 = entry("G18", GnssConstellation.GPS);
        BroadcastHealthRecord healthy = new BroadcastHealthRecord(
                "G18", GnssConstellation.GPS, "LNAV", T0 - 600_000L, 0, true, "SV health 0");
        GnssManualOverride manualOp = new GnssManualOverride("G18", GnssDisplayStatus.OPERATIONAL, "force green");
        GnssOutageNotice active = new GnssOutageNotice(
                "NANU 2026-074", GnssConstellation.GPS, "G18", "G054", "FCSTDV",
                true, false, T0 - 3600_000L, T0 + 3600_000L, "Planned maintenance", "NAVCEN");

        GnssStatusResult res = GnssStatusEvaluator.evaluate(
                g18, T0, healthy, T0, T0, null, List.of(active), manualOp, true);
        assertEquals(GnssDisplayStatus.UNAVAILABLE, res.status());
        assertEquals(active, res.activeNotice());
        assertFalse(res.manualOperationalOverride());
        assertTrue(res.evidence().stream().anyMatch(e -> e.decisive() && e.source().contains("NANU 2026-074")));
    }

    @Test
    void manualOperationalOverrideFlagsVisiblyOverOperatorNotAvailable() {
        GnssCatalogEntry e14 = entry("E14", GnssConstellation.GALILEO);
        OperatorStatusRecord notAvail = new OperatorStatusRecord(
                "E14", GnssConstellation.GALILEO, "GSC",
                OperatorStatusRecord.OperatorHealth.NOT_AVAILABLE, "NOT AVAILABLE", T0, "eccentric");
        GnssManualOverride manualOp = new GnssManualOverride("E14", GnssDisplayStatus.OPERATIONAL, "test receiver use");

        GnssStatusResult res = GnssStatusEvaluator.evaluate(
                e14, T0, null, T0, T0, notAvail, List.of(), manualOp, true);
        assertEquals(GnssDisplayStatus.OPERATIONAL, res.status());
        assertTrue(res.manualOperationalOverride());
    }

    @Test
    void operatorTestingBeatsBroadcastUnhealthy() {
        GnssCatalogEntry e26 = entry("E26", GnssConstellation.GALILEO);
        BroadcastHealthRecord unhealthy = new BroadcastHealthRecord(
                "E26", GnssConstellation.GALILEO, "INAV", T0 - 300_000L, 511, false, "SHS/DVS unhealthy");
        OperatorStatusRecord commissioning = new OperatorStatusRecord(
                "E26", GnssConstellation.GALILEO, "GSC",
                OperatorStatusRecord.OperatorHealth.TESTING, "UNDER COMMISSIONING", T0, "commissioning");

        GnssStatusResult res = GnssStatusEvaluator.evaluate(
                e26, T0, unhealthy, T0, T0, commissioning, List.of(), null, true);
        assertEquals(GnssDisplayStatus.TESTING, res.status());
        assertEquals("GSC", res.primarySource());
    }

    @Test
    void absentOver4HoursInFreshBroadcastMarksUnavailableButNoEvidenceMarksNoStatusData() {
        GnssCatalogEntry r05 = entry("R05", GnssConstellation.GLONASS);
        BroadcastHealthRecord staleSat = new BroadcastHealthRecord(
                "R05", GnssConstellation.GLONASS, "FDMA", T0 - 5L * 3600_000L, 0, true, "OK");
        GnssStatusResult absent = GnssStatusEvaluator.evaluate(
                r05, T0, staleSat, T0, T0, null, List.of(), null, false);
        assertEquals(GnssDisplayStatus.UNAVAILABLE, absent.status());

        GnssStatusResult unknown = GnssStatusEvaluator.evaluate(
                r05, T0, null, 0L, 0L, null, List.of(), null, false);
        assertEquals(GnssDisplayStatus.NO_STATUS_DATA, unknown.status());
    }

    @Test
    void plannedOutageTransitionAndNextBoundaryCalculation() {
        GnssCatalogEntry g18 = entry("G18", GnssConstellation.GPS);
        BroadcastHealthRecord healthy = new BroadcastHealthRecord(
                "G18", GnssConstellation.GPS, "LNAV", T0, 0, true, "OK");
        long start = T0 + 2L * 3600_000L;
        long end = T0 + 8L * 3600_000L;
        GnssOutageNotice planned = new GnssOutageNotice(
                "NANU 2026-074", GnssConstellation.GPS, "G18", "G054", "FCSTDV",
                true, false, start, end, "DV", "NAVCEN");

        GnssStatusResult before = GnssStatusEvaluator.evaluate(
                g18, T0, healthy, T0, T0, null, List.of(planned), null, true);
        assertEquals(GnssDisplayStatus.OPERATIONAL, before.status());
        assertEquals(planned, before.nextPlannedNotice());
        assertEquals(start, GnssStatusEvaluator.nextNoticeBoundaryAfter(List.of(planned), T0));

        GnssStatusResult during = GnssStatusEvaluator.evaluate(
                g18, start + 60_000L, healthy, T0, T0, null, List.of(planned), null, true);
        assertEquals(GnssDisplayStatus.UNAVAILABLE, during.status());
        assertEquals(end + 1L, GnssStatusEvaluator.nextNoticeBoundaryAfter(List.of(planned), start + 60_000L));
    }

    @Test
    void manualStatusStoreSeedRoundTripStalenessAndCorruptRecovery(@TempDir Path tmpDir) throws Exception {
        Path file = tmpDir.resolve("gnss-manual-status.json");
        GnssManualStatusStore store = new GnssManualStatusStore(file);
        GnssManualStatusList seeded = store.load();
        assertNotNull(seeded);
        assertNotNull(seeded.getLastReviewedIso());

        GnssManualStatusList custom = new GnssManualStatusList(
                "2026-09-01",
                List.of(new GnssManualOverride("R24", GnssDisplayStatus.TESTING, "Flight tests")));
        store.save(custom);
        GnssManualStatusList reloaded = store.load();
        assertEquals(GnssDisplayStatus.TESTING, reloaded.findByPrn("R24").orElseThrow().status());
        assertTrue(reloaded.isStale(T0, 30));

        Files.writeString(file, "{broken json");
        GnssManualStatusList recovered = store.load();
        assertNotNull(recovered);
    }

    private static GnssCatalogEntry entry(String prn, GnssConstellation c) {
        return new GnssCatalogEntry(
                prn, c, Integer.parseInt(prn.substring(1)), "SV01",
                40000, "2020-001A", "BLOCK", GnssOrbitClass.MEO, "A", "1", "2020-01-01");
    }
}

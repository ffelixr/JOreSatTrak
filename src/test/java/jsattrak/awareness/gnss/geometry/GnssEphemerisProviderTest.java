package jsattrak.awareness.gnss.geometry;

import jsattrak.awareness.gnss.BroadcastHealthRecord;
import jsattrak.awareness.gnss.GnssAwarenessService;
import jsattrak.awareness.gnss.GnssCatalogEntry;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.GnssOrbitClass;
import jsattrak.gui.JSatTrak;
import jsattrak.utilities.TLE;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class GnssEphemerisProviderTest {

    // Epoch: 2026-11-21T00:00:00Z = Saturday, GPS week 2445, ToW = 518400s; BDT week 1089, ToW = 518400s
    private static final String MIXED_RINEX3_FIXTURE = ""
            + "     3.04           N: GNSS NAV DATA    M: Mixed            RINEX VERSION / TYPE\n"
            + "BKG                                     20261121 001500 UTC PGM / RUN BY / DATE\n"
            + "                                                            END OF HEADER\n"
            + "G01 2026 11 21 00 00 00 1.000000000000D-04 0.000000000000D+00 0.000000000000D+00\n"
            + "     1.000000000000D+01 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
            + "     0.000000000000D+00 0.000000000000D+00 0.000000000000D+00 5.153650000000D+03\n"
            + "     5.184000000000D+05 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
            + "     9.600000000000D-01 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
            + "     0.000000000000D+00 0.000000000000D+00 2.445000000000D+03 0.000000000000D+00\n"
            + "     2.000000000000D+00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
            + "     5.184000000000D+05 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
            + "E11 2026 11 21 00 00 00 1.000000000000D-04 0.000000000000D+00 0.000000000000D+00\n"
            + "     1.000000000000D+01 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
            + "     0.000000000000D+00 0.000000000000D+00 0.000000000000D+00 5.440610000000D+03\n"
            + "     5.184000000000D+05 0.000000000000D+00 1.000000000000D+00 0.000000000000D+00\n"
            + "     9.770000000000D-01 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
            + "     0.000000000000D+00 5.170000000000D+02 2.445000000000D+03 0.000000000000D+00\n"
            + "     3.120000000000D+00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
            + "     5.184000000000D+05 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
            + "C19 2026 11 21 00 00 00 1.000000000000D-04 0.000000000000D+00 0.000000000000D+00\n"
            + "     1.000000000000D+01 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
            + "     0.000000000000D+00 0.000000000000D+00 0.000000000000D+00 5.282610000000D+03\n"
            + "     5.184000000000D+05 0.000000000000D+00 2.000000000000D+00 0.000000000000D+00\n"
            + "     9.600000000000D-01 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
            + "     0.000000000000D+00 0.000000000000D+00 1.089000000000D+03 0.000000000000D+00\n"
            + "     2.000000000000D+00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
            + "     5.184000000000D+05 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
            + "R05 2026 11 21 00 00 00 1.000000000000D-04 0.000000000000D+00 0.000000000000D+00\n"
            + "     2.550000000000D+04 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
            + "     0.000000000000D+00 3.000000000000D+00 0.000000000000D+00 1.000000000000D+00\n"
            + "     0.000000000000D+00 2.500000000000D+00 0.000000000000D+00 0.000000000000D+00\n";

    private static List<GnssCatalogEntry> sampleCatalog() {
        return List.of(
                new GnssCatalogEntry("G01", GnssConstellation.GPS, 1, "G063", 37753, "2011-036A", "GPS IIF-2", GnssOrbitClass.MEO, "", "", ""),
                new GnssCatalogEntry("E11", GnssConstellation.GALILEO, 11, "E101", 37846, "2011-060A", "GALILEO-1", GnssOrbitClass.MEO, "", "", ""),
                new GnssCatalogEntry("C19", GnssConstellation.BEIDOU, 19, "C201", 43107, "2018-003A", "BEIDOU-3M1", GnssOrbitClass.MEO, "", "", ""),
                new GnssCatalogEntry("R05", GnssConstellation.GLONASS, 5, "R734", 36111, "2009-070A", "COSMOS 2456", GnssOrbitClass.MEO, "", "", "")
        );
    }

    private static List<TLE> sampleTles() {
        return List.of(
                new TLE("GPS BIIR-2  (PRN 01)", "1 37753U 11036A   26325.00000000  .00000010  00000-0  00000-0 0  9991", "2 37753  55.0000  10.0000 0010000  10.0000  10.0000  2.00560000000011"),
                new TLE("GSAT0101 (GALILEO 1)", "1 37846U 11060A   26325.00000000  .00000010  00000-0  00000-0 0  9992", "2 37846  56.0000 130.0000 0010000  20.0000  20.0000  1.70475000000022"),
                new TLE("BEIDOU-3 M1 (C19)", "1 43107U 18003A   26325.00000000  .00000010  00000-0  00000-0 0  9993", "2 43107  55.0000 250.0000 0010000  30.0000  30.0000  1.86230000000033"),
                new TLE("COSMOS 2456 (734)", "1 36111U 09070A   26325.00000000  .00000010  00000-0  00000-0 0  9994", "2 36111  64.8000  70.0000 0010000  40.0000  40.0000  2.13100000000044")
        );
    }

    @Test
    void propagatesRinex3BrdcAcrossConstellationsAndFallsBackToTleOutsideValidityWindow(@TempDir Path tmpDir) throws Exception {
        JSatTrak.initOrekitData();
        long t0 = Instant.parse("2026-11-21T00:00:00Z").toEpochMilli();
        GnssAwarenessService awareness = GnssAwarenessService.createForTest(
                tmpDir, sampleCatalog(), sampleTles(),
                Map.of(
                        "G01", new BroadcastHealthRecord("G01", GnssConstellation.GPS, "LNAV", t0, 0, true, "OK"),
                        "E11", new BroadcastHealthRecord("E11", GnssConstellation.GALILEO, "INAV", t0, 0, true, "OK"),
                        "C19", new BroadcastHealthRecord("C19", GnssConstellation.BEIDOU, "D1", t0, 0, true, "OK"),
                        "R05", new BroadcastHealthRecord("R05", GnssConstellation.GLONASS, "FDMA", t0, 0, true, "OK")),
                Map.of(), List.of());

        GnssEphemerisProvider provider = new GnssEphemerisProvider();
        int loaded = provider.loadBrdcBytes(gzip(MIXED_RINEX3_FIXTURE), "TestBKG");
        assertEquals(4, loaded);

        // 1. Resolve at T0 + 10 min -> all 4 SVs propagated via BRDC at MEO radii
        GnssEphemerisProvider.ResolvedEpochSnapshot near = provider.resolveSatellitesAtEpoch(t0 + 10 * 60_000L, awareness);
        assertEquals(4, near.candidates().size());
        assertEquals(GnssOrbitSource.BRDC, near.orbitSource().primary());
        for (GnssDopCalculator.CandidateSatellite sat : near.candidates()) {
            double rKm = Math.sqrt(sat.satX() * sat.satX() + sat.satY() * sat.satY() + sat.satZ() * sat.satZ()) / 1000.0;
            assertTrue(rKm >= 24_000.0 && rKm <= 31_000.0, sat.prnId() + " radius km=" + rKm);
            assertEquals(GnssOrbitSource.BRDC, sat.orbitSource());
        }

        // 2. Resolve at T0 + 2 hours -> G01/E11/C19 still BRDC, R05 (>30 min) falls back to TLE -> MIXED
        GnssEphemerisProvider.ResolvedEpochSnapshot twoHours = provider.resolveSatellitesAtEpoch(t0 + 2 * 3600_000L, awareness);
        assertEquals(GnssOrbitSource.MIXED, twoHours.orbitSource().primary());
        assertEquals(3, twoHours.orbitSource().brdcCount());
        assertEquals(1, twoHours.orbitSource().tleCount());

        // 3. Resolve at T0 + 12 hours (outside ±4h validity window) -> all fall back to TLE
        GnssEphemerisProvider.ResolvedEpochSnapshot twelveHours = provider.resolveSatellitesAtEpoch(t0 + 12 * 3600_000L, awareness);
        assertEquals(GnssOrbitSource.TLE_FALLBACK, twelveHours.orbitSource().primary());
        assertEquals(0, twelveHours.orbitSource().brdcCount());
        assertEquals(4, twelveHours.orbitSource().tleCount());
    }

    @Test
    void localSp3AndLocalRinexOverrideLiveBrdcAndHandleOfflineHistoricalEpochs(@TempDir Path tmpDir) throws Exception {
        JSatTrak.initOrekitData();
        Path sp3Path = tmpDir.resolve("historical_2024.sp3");
        Files.writeString(sp3Path, buildSampleSp3(), StandardCharsets.UTF_8);

        GnssAwarenessService offline = GnssAwarenessService.createForTest(
                tmpDir, List.of(), List.of(), Map.of(), Map.of(), List.of());

        GnssEphemerisProvider provider = new GnssEphemerisProvider();
        GnssEphemerisProvider.LocalLoadSummary summary = provider.loadLocalFile(sp3Path);
        assertEquals(4, summary.satelliteCount());
        assertFalse(provider.getLoadedLocalFileName().isBlank());

        long histEpoch = Instant.parse("2024-06-01T00:30:00Z").toEpochMilli();
        GnssEphemerisProvider.ResolvedEpochSnapshot snap = provider.resolveSatellitesAtEpoch(histEpoch, offline);
        assertEquals(4, snap.candidates().size());
        assertEquals(GnssOrbitSource.SP3, snap.orbitSource().primary());
        assertTrue(snap.orbitSource().noHealthFlags());
        assertTrue(snap.candidates().stream().allMatch(s -> s.orbitSource() == GnssOrbitSource.SP3));

        provider.clearLocalFile();
        assertEquals("", provider.getLoadedLocalFileName());

        // Also test live SP3 without health flags (NO_STATUS_DATA within 24h) + historical SP3 excluding live TLEs
        GnssAwarenessService partialLive = GnssAwarenessService.createForTest(
                tmpDir.resolve("live"), sampleCatalog(), sampleTles(), Map.of(), Map.of(), List.of(), histEpoch);
        provider.loadLocalFile(sp3Path);
        GnssEphemerisProvider.ResolvedEpochSnapshot liveNoHealth =
                provider.resolveSatellitesAtEpoch(histEpoch, partialLive);
        assertEquals(GnssOrbitSource.MIXED, liveNoHealth.orbitSource().primary());
        assertEquals(4, liveNoHealth.orbitSource().sp3Count());
        assertEquals(1, liveNoHealth.orbitSource().tleCount());
        assertTrue(liveNoHealth.orbitSource().noHealthFlags());

        // Historical SP3 (>24h from live sync) must not mix out-of-era 2026 C19 TLE
        GnssAwarenessService liveIn2026 = GnssAwarenessService.createForTest(
                tmpDir.resolve("live2026"), sampleCatalog(), sampleTles(), Map.of(), Map.of(), List.of(),
                Instant.parse("2026-11-21T00:00:00Z").toEpochMilli());
        GnssEphemerisProvider.ResolvedEpochSnapshot histNoLiveLeak =
                provider.resolveSatellitesAtEpoch(histEpoch, liveIn2026);
        assertEquals(GnssOrbitSource.SP3, histNoLiveLeak.orbitSource().primary());
        assertTrue(histNoLiveLeak.candidates().stream().allMatch(s -> s.orbitSource() == GnssOrbitSource.SP3));

        // Load local .rnx.gz where G01 is unhealthy at T0: historical epoch uses svHealth, T0+12h live TLE ignores stale local health
        String localRnxUnhealthyG01 = MIXED_RINEX3_FIXTURE.replace(
                "     2.000000000000D+00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                        + "     5.184000000000D+05 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                        + "E11",
                "     2.000000000000D+00 1.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                        + "     5.184000000000D+05 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                        + "E11");
        Path localRnxPath = tmpDir.resolve("local_nav.rnx.gz");
        Files.write(localRnxPath, gzip(localRnxUnhealthyG01));
        GnssEphemerisProvider.LocalLoadSummary rnxSummary = provider.loadLocalFile(localRnxPath);
        assertEquals(GnssOrbitSource.BRDC, rnxSummary.sourceType());
        assertEquals(4, rnxSummary.satelliteCount());

        long liveT0 = Instant.parse("2026-11-21T00:00:00Z").toEpochMilli();
        GnssAwarenessService healthyLive = GnssAwarenessService.createForTest(
                tmpDir.resolve("healthy"), sampleCatalog(), sampleTles(),
                Map.of(
                        "G01", new BroadcastHealthRecord("G01", GnssConstellation.GPS, "LNAV", liveT0, 0, true, "OK"),
                        "E11", new BroadcastHealthRecord("E11", GnssConstellation.GALILEO, "INAV", liveT0, 0, true, "OK"),
                        "C19", new BroadcastHealthRecord("C19", GnssConstellation.BEIDOU, "D1", liveT0, 0, true, "OK"),
                        "R05", new BroadcastHealthRecord("R05", GnssConstellation.GLONASS, "FDMA", liveT0, 0, true, "OK")),
                Map.of(), List.of(), liveT0);

        GnssEphemerisProvider.ResolvedEpochSnapshot atLocalWindow =
                provider.resolveSatellitesAtEpoch(liveT0 + 600_000L, healthyLive);
        var g01Local = atLocalWindow.candidates().stream().filter(s -> s.prnId().equals("G01")).findFirst().orElseThrow();
        assertEquals(GnssOrbitSource.BRDC, g01Local.orbitSource());
        assertEquals(jsattrak.awareness.gnss.GnssDisplayStatus.UNAVAILABLE, g01Local.status());

        // Outside +-4h local RINEX window -> falls back to TLE_FALLBACK and uses live health (OPERATIONAL), not stale local G01=1
        GnssEphemerisProvider.ResolvedEpochSnapshot outsideLocalWindow =
                provider.resolveSatellitesAtEpoch(liveT0 + 12 * 3600_000L, healthyLive);
        var g01Tle = outsideLocalWindow.candidates().stream().filter(s -> s.prnId().equals("G01")).findFirst().orElseThrow();
        assertEquals(GnssOrbitSource.TLE_FALLBACK, g01Tle.orbitSource());
        assertEquals(jsattrak.awareness.gnss.GnssDisplayStatus.OPERATIONAL, g01Tle.status());
    }

    private static String buildSampleSp3() {
        StringBuilder sb = new StringBuilder();
        sb.append("#cP2024  6  1  0  0  0.00000000       9 ORBIT IGS20 HLM  IGS\n");
        sb.append("## 2316 518400.00000000   900.00000000 60462 0.0000000000000\n");
        sb.append("+    4   G01G02E11R05  0  0  0  0  0  0  0  0  0  0  0  0  0\n");
        for (int i = 0; i < 4; i++) {
            sb.append("+          0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0\n");
        }
        for (int i = 0; i < 5; i++) {
            sb.append("++         0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0  0\n");
        }
        sb.append("%c G  cc GPS ccc cccc cccc cccc cccc ccccc ccccc ccccc ccccc\n");
        sb.append("%c cc cc ccc ccc cccc cccc cccc cccc ccccc ccccc ccccc ccccc\n");
        sb.append("%f  1.2500000  1.025000000  0.00000000000  0.000000000000000\n");
        sb.append("%f  0.0000000  0.000000000  0.00000000000  0.000000000000000\n");
        sb.append("%i    0    0    0    0      0      0      0      0         0\n");
        sb.append("%i    0    0    0    0      0      0      0      0         0\n");
        sb.append("/* Synthetic test SP3c fixture\n");
        sb.append("/* Comment line 2\n");
        sb.append("/* Comment line 3\n");
        sb.append("/* Comment line 4\n");
        for (int step = 0; step < 9; step++) {
            int totalMin = step * 15;
            int hr = totalMin / 60;
            int min = totalMin % 60;
            sb.append(String.format(Locale.US, "*  2024  6  1 %2d %2d  0.00000000%n", hr, min));
            double ang = step * 0.12;
            sb.append(String.format(Locale.US, "PG01%14.6f%14.6f%14.6f      0.000000%n",
                    26560.0 * Math.cos(ang), 26560.0 * Math.sin(ang), 5000.0));
            sb.append(String.format(Locale.US, "PG02%14.6f%14.6f%14.6f      0.000000%n",
                    -26560.0 * Math.sin(ang), 26560.0 * Math.cos(ang), -5000.0));
            sb.append(String.format(Locale.US, "PE11%14.6f%14.6f%14.6f      0.000000%n",
                    29600.0 * Math.cos(ang + 0.5), 10000.0, 29600.0 * Math.sin(ang + 0.5)));
            sb.append(String.format(Locale.US, "PR05%14.6f%14.6f%14.6f      0.000000%n",
                    10000.0, 25500.0 * Math.cos(ang + 1.0), 25500.0 * Math.sin(ang + 1.0)));
        }
        sb.append("EOF\n");
        return sb.toString();
    }

    private static byte[] gzip(String text) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return baos.toByteArray();
    }
}

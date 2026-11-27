package jsattrak.awareness.gnss.stations.analysis;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import jsattrak.awareness.gnss.BroadcastHealthRecord;
import jsattrak.awareness.gnss.GnssAwarenessService;
import jsattrak.awareness.gnss.GnssCatalogEntry;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.GnssOrbitClass;
import jsattrak.awareness.gnss.geometry.GnssDopCalculator;
import jsattrak.awareness.gnss.geometry.GnssEphemerisProvider;
import jsattrak.awareness.gnss.stations.obs.RinexObservationData;
import jsattrak.gui.JSatTrak;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.orekit.gnss.ObservationType;
import org.orekit.gnss.PredefinedObservationType;

import static org.junit.jupiter.api.Assertions.*;

class GnssSppSolverTest {

    private static final double SPEED_OF_LIGHT = 299_792_458.0;
    private static final double OMEGA_EARTH = 7.2921151467e-5;

    private static String buildConstellationNavFixture() {
        StringBuilder sb = new StringBuilder();
        sb.append("     3.04           N: GNSS NAV DATA    M: Mixed            RINEX VERSION / TYPE\n");
        sb.append("BKG                                     20261121 001500 UTC PGM / RUN BY / DATE\n");
        sb.append("                                                            END OF HEADER\n");
        // 6 GPS + 2 Galileo orbits rotated in RAAN / M0 to guarantee >=6 SVs well above Madrid at T0
        String[] prns = {"G01", "G02", "G03", "G04", "G05", "G06", "E11", "E12"};
        double[] omega0 = {0.10, 0.90, 1.80, 2.70, -0.60, -1.50, 0.40, 2.20};
        double[] m0 = {0.40, 0.90, 1.40, 0.20, 0.70, 1.10, 0.55, 1.25};
        double[] af0 = {1.2e-4, -0.8e-4, 2.1e-4, 0.5e-4, -1.4e-4, 0.9e-4, 1.1e-4, -0.6e-4};
        for (int i = 0; i < prns.length; i++) {
            String prn = prns[i];
            boolean gal = prn.startsWith("E");
            String sqrtA = gal ? "5.440610000000D+03" : "5.153650000000D+03";
            String src = gal ? "5.170000000000D+02" : "0.000000000000D+00";
            String sysLine = gal ? "1.000000000000D+00" : "0.000000000000D+00";
            String inc = gal ? "9.770000000000D-01" : "9.600000000000D-01";
            String af0Str = String.format(Locale.US, "%19.12E", af0[i]).replace('E', 'D');
            String omStr = String.format(Locale.US, "%19.12E", omega0[i]).replace('E', 'D');
            String m0Str = String.format(Locale.US, "%19.12E", m0[i]).replace('E', 'D');
            sb.append(String.format(Locale.US,
                    "%s 2026 11 21 00 00 00%s 0.000000000000D+00 0.000000000000D+00%n", prn, af0Str));
            sb.append("     1.000000000000D+01 0.000000000000D+00 0.000000000000D+00" + m0Str + "\n");
            sb.append("     0.000000000000D+00 0.000000000000D+00 0.000000000000D+00 " + sqrtA + "\n");
            sb.append("     5.184000000000D+05 0.000000000000D+00" + omStr + " 0.000000000000D+00\n");
            sb.append("     " + inc + " 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n");
            sb.append("     0.000000000000D+00 " + src + " 2.445000000000D+03 " + sysLine + "\n");
            sb.append("     2.000000000000D+00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n");
            sb.append("     5.184000000000D+05 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n");
        }
        return sb.toString();
    }

    @Test
    void recoversMonumentCoordinatesWithinHalfMeterAndRejectsSinglePseudorangeBlunder(@TempDir Path tmpDir)
            throws Exception {
        JSatTrak.initOrekitData();
        long t0 = Instant.parse("2026-11-21T00:10:00Z").toEpochMilli();

        List<GnssCatalogEntry> catalog = List.of(
                cat("G01", GnssConstellation.GPS, 1), cat("G02", GnssConstellation.GPS, 2),
                cat("G03", GnssConstellation.GPS, 3), cat("G04", GnssConstellation.GPS, 4),
                cat("G05", GnssConstellation.GPS, 5), cat("G06", GnssConstellation.GPS, 6),
                cat("E11", GnssConstellation.GALILEO, 11), cat("E12", GnssConstellation.GALILEO, 12));
        Map<String, BroadcastHealthRecord> health = new LinkedHashMap<>();
        for (GnssCatalogEntry e : catalog) {
            health.put(e.prnId(), new BroadcastHealthRecord(e.prnId(), e.constellation(), "NAV", t0, 0, true, "OK"));
        }
        GnssAwarenessService awareness = GnssAwarenessService.createForTest(
                tmpDir, catalog, List.of(), health, Map.of(), List.of(), t0);

        GnssEphemerisProvider provider = new GnssEphemerisProvider();
        int loaded = provider.loadBrdcBytes(gzip(buildConstellationNavFixture()), "TestBRDC");
        assertEquals(8, loaded);

        // Place observer under the cluster centroid (~0, 0, 100m or choose one visible lat/lon)
        GnssEphemerisProvider.ResolvedEpochSnapshot preSnap = provider.resolveSatellitesAtEpoch(t0, awareness);
        // Find a surface point beneath the first SV to guarantee >= 6 visible SVs above horizon
        double[] sv0 = {preSnap.candidates().get(0).satX(), preSnap.candidates().get(0).satY(), preSnap.candidates().get(0).satZ()};
        double latDeg = Math.toDegrees(Math.atan2(sv0[2], Math.hypot(sv0[0], sv0[1])));
        double lonDeg = Math.toDegrees(Math.atan2(sv0[1], sv0[0]));
        double altM = 650.0;
        double[] monEcef = GnssDopCalculator.geodeticToEcef(latDeg, lonDeg, altM);

        double trueRxClkSec = 25.0e-6; // +25.0 microseconds
        Map<String, RinexObservationData.SatelliteObsSample> satObs = new LinkedHashMap<>();
        String blunderedPrn = null;
        int visibleClockSats = 0;

        GnssEphemerisProvider.ResolvedClockEpochSnapshot clkSnap =
                provider.resolveSatellitesWithClockAtEpoch(t0, awareness);
        for (GnssEphemerisProvider.CandidateSatelliteWithClock sat : clkSnap.candidates()) {
            double[] azEl = GnssDopCalculator.computeAzEl(latDeg, lonDeg, altM, sat.satX(), sat.satY(), sat.satZ());
            if (azEl[1] < 5.0) {
                continue;
            }
            visibleClockSats++;
            double dx = sat.satX() - monEcef[0];
            double dy = sat.satY() - monEcef[1];
            double dz = sat.satZ() - monEcef[2];
            double rho0 = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double tau = rho0 / SPEED_OF_LIGHT;
            // Sagnac + emission-time motion
            double sx = sat.satX() - sat.velX() * tau + OMEGA_EARTH * sat.satY() * tau;
            double sy = sat.satY() - sat.velY() * tau - OMEGA_EARTH * sat.satX() * tau;
            double sz = sat.satZ() - sat.velZ() * tau;
            double geom = Math.sqrt(
                    Math.pow(sx - monEcef[0], 2) + Math.pow(sy - monEcef[1], 2) + Math.pow(sz - monEcef[2], 2));
            double tropo = GnssSppSolver.saastamoinenTropoMeters(azEl[1], altM);
            double pr = geom + SPEED_OF_LIGHT * (trueRxClkSec - sat.clockBiasSec() + sat.tgdSec()) + tropo;
            if (blunderedPrn == null && visibleClockSats >= 6) {
                blunderedPrn = sat.prnId();
                pr += 150.0;
            }
            ObservationType codeType = sat.constellation() == GnssConstellation.GALILEO
                    ? PredefinedObservationType.C1X : PredefinedObservationType.C1C;
            satObs.put(sat.prnId(), new RinexObservationData.SatelliteObsSample(
                    sat.prnId(), sat.constellation(), Map.of(codeType, pr), Map.of(), Map.of(), false, null));
        }
        assertTrue(satObs.size() >= 5, "Expected >=5 visible clock SVs, got " + satObs.size());

        RinexObservationData.HeaderSummary hdr = new RinexObservationData.HeaderSummary(
                "SYNTH", "RX", "1.0", "ANT", monEcef, 30.0, t0, t0, "DBHZ",
                new EnumMap<>(GnssConstellation.class), List.of());
        RinexObservationData obs = new RinexObservationData(
                hdr, List.of(new RinexObservationData.EpochRecord(t0, 0.0, satObs)));

        GnssSppSolver.SppSessionSummary spp = GnssSppSolver.solveSession(
                obs, monEcef, 5.0, t0, provider, awareness);

        assertTrue(spp.inspectionEpoch().sppAvailable(), spp.inspectionEpoch().statusMessage());
        assertTrue(spp.inspectionEpoch().error3dM() < 0.5,
                "Expected 3D error < 0.5 m, got " + spp.inspectionEpoch().error3dM());
        assertEquals(25.0, spp.inspectionEpoch().rxClockOffsetMicros(), 0.05);
    }

    @Test
    void returnsSppUnavailableOnTleFallbackOrSp3MissingClockSentinel(@TempDir Path tmpDir) throws Exception {
        JSatTrak.initOrekitData();
        long histEpoch = Instant.parse("2024-06-01T00:30:00Z").toEpochMilli();
        GnssAwarenessService offline = GnssAwarenessService.createForTest(
                tmpDir, List.of(), List.of(), Map.of(), Map.of(), List.of());

        double[] monEcef = GnssDopCalculator.geodeticToEcef(40.4292, -4.2497, 829.5);
        Map<String, RinexObservationData.SatelliteObsSample> obsMap = Map.of(
                "G01", new RinexObservationData.SatelliteObsSample(
                        "G01", GnssConstellation.GPS, Map.of(PredefinedObservationType.C1C, 2.2e7),
                        Map.of(), Map.of(), false, null),
                "G02", new RinexObservationData.SatelliteObsSample(
                        "G02", GnssConstellation.GPS, Map.of(PredefinedObservationType.C1C, 2.3e7),
                        Map.of(), Map.of(), false, null),
                "E11", new RinexObservationData.SatelliteObsSample(
                        "E11", GnssConstellation.GALILEO, Map.of(PredefinedObservationType.C1X, 2.4e7),
                        Map.of(), Map.of(), false, null),
                "R05", new RinexObservationData.SatelliteObsSample(
                        "R05", GnssConstellation.GLONASS, Map.of(PredefinedObservationType.C1C, 2.1e7),
                        Map.of(), Map.of(), false, null)
        );
        RinexObservationData.HeaderSummary hdr = new RinexObservationData.HeaderSummary(
                "MADR", "RX", "1.0", "ANT", monEcef, 30.0, histEpoch, histEpoch, "DBHZ",
                new EnumMap<>(GnssConstellation.class), List.of());
        RinexObservationData obs = new RinexObservationData(
                hdr, List.of(new RinexObservationData.EpochRecord(histEpoch, 0.0, obsMap)));

        GnssEphemerisProvider provider = new GnssEphemerisProvider();
        GnssSppSolver.SppSessionSummary noEph = GnssSppSolver.solveSession(
                obs, monEcef, 5.0, histEpoch, provider, offline);
        assertFalse(noEph.inspectionEpoch().sppAvailable());

        // SP3 containing missing-clock sentinel 999999.999999 us -> clockValid == false -> sppAvailable == false
        Path badSp3 = tmpDir.resolve("missing_clk.sp3");
        Files.writeString(badSp3, buildSp3WithSentinelClock(), StandardCharsets.UTF_8);
        provider.loadLocalFile(badSp3);
        GnssEphemerisProvider.ResolvedClockEpochSnapshot sentinelSnap =
                provider.resolveSatellitesWithClockAtEpoch(histEpoch, offline);
        assertEquals(4, sentinelSnap.candidates().size());
        assertTrue(sentinelSnap.candidates().stream().noneMatch(GnssEphemerisProvider.CandidateSatelliteWithClock::clockValid));

        GnssSppSolver.SppSessionSummary sentinelSpp = GnssSppSolver.solveSession(
                obs, monEcef, 5.0, histEpoch, provider, offline);
        assertFalse(sentinelSpp.inspectionEpoch().sppAvailable());

        // 3. Load matching local SP3 with valid clocks into shared GnssGeometryService provider -> sppAvailable == true
        jsattrak.awareness.gnss.geometry.GnssGeometryService geomSvc =
                new jsattrak.awareness.gnss.geometry.GnssGeometryService(offline, null);
        Path validSp3 = tmpDir.resolve("valid_clk.sp3");
        String sp3Text = buildSp3WithValidClocks(monEcef);
        Files.writeString(validSp3, sp3Text, StandardCharsets.UTF_8);
        geomSvc.loadLocalEphemerisFile(validSp3);
        GnssEphemerisProvider.ResolvedClockEpochSnapshot sp3Snap =
                geomSvc.getEphemerisProvider().resolveSatellitesWithClockAtEpoch(histEpoch, offline);
        assertEquals(4, sp3Snap.candidates().size());
        assertTrue(sp3Snap.candidates().stream().allMatch(GnssEphemerisProvider.CandidateSatelliteWithClock::clockValid));

        // Build exact pseudoranges matching SP3 SVs so SPP resolves cleanly
        double[] lla = {40.4292, -4.2497, 829.5};
        Map<String, RinexObservationData.SatelliteObsSample> sp3ObsMap = new LinkedHashMap<>();
        for (GnssEphemerisProvider.CandidateSatelliteWithClock sat : sp3Snap.candidates()) {
            double[] azEl = GnssDopCalculator.computeAzEl(lla[0], lla[1], lla[2], sat.satX(), sat.satY(), sat.satZ());
            if (azEl[1] < 0.0) {
                continue;
            }
            double rho = Math.sqrt(Math.pow(sat.satX() - monEcef[0], 2)
                    + Math.pow(sat.satY() - monEcef[1], 2) + Math.pow(sat.satZ() - monEcef[2], 2));
            double tau = rho / SPEED_OF_LIGHT;
            double sx = sat.satX() - sat.velX() * tau + OMEGA_EARTH * sat.satY() * tau;
            double sy = sat.satY() - sat.velY() * tau - OMEGA_EARTH * sat.satX() * tau;
            double sz = sat.satZ() - sat.velZ() * tau;
            double geom = Math.sqrt(Math.pow(sx - monEcef[0], 2)
                    + Math.pow(sy - monEcef[1], 2) + Math.pow(sz - monEcef[2], 2));
            double pr = geom + SPEED_OF_LIGHT * (12.0e-6 - sat.clockBiasSec())
                    + GnssSppSolver.saastamoinenTropoMeters(azEl[1], lla[2]);
            ObservationType ct = sat.constellation() == GnssConstellation.GALILEO
                    ? PredefinedObservationType.C1X : PredefinedObservationType.C1C;
            sp3ObsMap.put(sat.prnId(), new RinexObservationData.SatelliteObsSample(
                    sat.prnId(), sat.constellation(), Map.of(ct, pr), Map.of(), Map.of(), false, null));
        }
        RinexObservationData sp3Obs = new RinexObservationData(
                hdr, List.of(new RinexObservationData.EpochRecord(histEpoch, 0.0, sp3ObsMap)));
        GnssSppSolver.SppSessionSummary sp3Spp = GnssSppSolver.solveSession(
                sp3Obs, monEcef, 0.0, histEpoch, geomSvc.getEphemerisProvider(), offline);
        assertTrue(sp3Spp.inspectionEpoch().sppAvailable(), sp3Spp.inspectionEpoch().statusMessage());
        assertTrue(sp3Spp.inspectionEpoch().error3dM() < 1.0);
        geomSvc.shutdown();
    }

    private static String buildSp3WithValidClocks(double[] monEcef) {
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
        for (int i = 0; i < 4; i++) {
            sb.append("/* SP3 test\n");
        }
        for (int step = 0; step < 9; step++) {
            int min = step * 15;
            sb.append(String.format(Locale.US, "*  2024  6  1 %2d %2d  0.00000000%n", min / 60, min % 60));
            double s = step * 10.0;
            sb.append(String.format(Locale.US, "PG01%14.6f%14.6f%14.6f%14.6f%n", 21000.0 + s, -2000.0, 16000.0, 120.0));
            sb.append(String.format(Locale.US, "PG02%14.6f%14.6f%14.6f%14.6f%n", 18000.0, 10000.0 + s, 16000.0, -45.0));
            sb.append(String.format(Locale.US, "PE11%14.6f%14.6f%14.6f%14.6f%n", 18000.0, -12000.0, 16000.0 + s, 85.0));
            sb.append(String.format(Locale.US, "PR05%14.6f%14.6f%14.6f%14.6f%n", 12000.0, 0.0, 23000.0 + s, 15.0));
        }
        sb.append("EOF\n");
        return sb.toString();
    }

    private static String buildSp3WithSentinelClock() {
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
        for (int i = 0; i < 4; i++) {
            sb.append("/* SP3 test\n");
        }
        for (int step = 0; step < 9; step++) {
            int min = step * 15;
            sb.append(String.format(Locale.US, "*  2024  6  1 %2d %2d  0.00000000%n", min / 60, min % 60));
            double ang = step * 0.12;
            sb.append(String.format(Locale.US, "PG01%14.6f%14.6f%14.6f 999999.999999%n",
                    26560.0 * Math.cos(ang), 26560.0 * Math.sin(ang), 5000.0));
            sb.append(String.format(Locale.US, "PG02%14.6f%14.6f%14.6f 999999.999999%n",
                    -26560.0 * Math.sin(ang), 26560.0 * Math.cos(ang), -5000.0));
            sb.append(String.format(Locale.US, "PE11%14.6f%14.6f%14.6f 999999.999999%n",
                    29600.0 * Math.cos(ang + 0.5), 10000.0, 29600.0 * Math.sin(ang + 0.5)));
            sb.append(String.format(Locale.US, "PR05%14.6f%14.6f%14.6f 999999.999999%n",
                    10000.0, 25500.0 * Math.cos(ang + 1.0), 25500.0 * Math.sin(ang + 1.0)));
        }
        sb.append("EOF\n");
        return sb.toString();
    }

    private static GnssCatalogEntry cat(String prn, GnssConstellation c, int n) {
        return new GnssCatalogEntry(prn, c, n, prn, 40000 + n, "2020-001A", prn, GnssOrbitClass.MEO, "", "", "");
    }

    private static byte[] gzip(String text) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return baos.toByteArray();
    }
}

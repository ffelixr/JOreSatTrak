package jsattrak.awareness.gnss.stations.analysis;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import jsattrak.awareness.gnss.GnssAwarenessService;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.geometry.GnssDopCalculator;
import jsattrak.awareness.gnss.geometry.GnssEphemerisProvider;
import jsattrak.awareness.gnss.stations.GnssReferenceStation;
import jsattrak.awareness.gnss.stations.GnssStationNetwork;
import jsattrak.awareness.gnss.stations.GnssStationStatus;
import jsattrak.awareness.gnss.stations.obs.RinexObservationData;
import jsattrak.gui.JSatTrak;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.orekit.gnss.ObservationType;
import org.orekit.gnss.PredefinedObservationType;

import static org.junit.jupiter.api.Assertions.*;

class GnssStationAnalyticsTest {

    private static final double SPEED_OF_LIGHT = 299_792_458.0;

    @Test
    void detectsMissingPredictedSatellitesElevationNormalizedCn0LliGfSlipsAndMp1Mp2Rms(@TempDir Path tmpDir)
            throws Exception {
        JSatTrak.initOrekitData();
        long t0 = Instant.parse("2024-06-01T00:15:00Z").toEpochMilli();
        double latDeg = 40.4292;
        double lonDeg = -4.2497;
        double altM = 829.5;
        double[] monEcef = GnssDopCalculator.geodeticToEcef(latDeg, lonDeg, altM);

        GnssReferenceStation madr = new GnssReferenceStation(
                "MADR00ESP", "MADR", "Madrid", "ESP", GnssStationNetwork.IGS,
                EnumSet.of(GnssStationNetwork.IGS), latDeg, lonDeg, altM, monEcef,
                "SEPT POLARX5", "5.5.0", "AOAD/M_T", "NONE",
                EnumSet.of(GnssConstellation.GPS, GnssConstellation.GALILEO),
                List.of(), t0, 10, Double.NaN, GnssStationStatus.ONLINE, "");

        // SVs in SP3 at fixed Az/El over MADR:
        // G01: el=60° (C/N0 39 -> delta ~ -8.4), G02: el=35° (+0.8m multipath), G03: el=12° (C/N0 36.5 -> delta ~ 0),
        // E11: el=45° (pure GF slip), E12: el=50° (absent from RINEX -> MISSING)
        Path sp3File = tmpDir.resolve("madr_analytics.sp3");
        Files.writeString(sp3File, buildSp3FixedGeometry(latDeg, lonDeg, altM), StandardCharsets.UTF_8);

        GnssAwarenessService offline = GnssAwarenessService.createForTest(
                tmpDir, List.of(), List.of(), Map.of(), Map.of(), List.of());
        GnssEphemerisProvider provider = new GnssEphemerisProvider();
        provider.loadLocalFile(sp3File);

        double f1Gps = 1575.42e6;
        double f2Gps = 1227.60e6;
        double lam1Gps = SPEED_OF_LIGHT / f1Gps;
        double lam2Gps = SPEED_OF_LIGHT / f2Gps;

        double f1Gal = 1575.42e6;
        double f5Gal = 1176.45e6;
        double lam1Gal = SPEED_OF_LIGHT / f1Gal;
        double lam5Gal = SPEED_OF_LIGHT / f5Gal;

        List<RinexObservationData.EpochRecord> epochs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long ep = t0 + i * 30_000L;
            Map<String, RinexObservationData.SatelliteObsSample> map = new LinkedHashMap<>();

            // G01: clean codes/phases, C/N0=39.0 at 60 deg, LLI=1 on epoch 5
            double r1 = 21_500_000.0 + i * 50.0;
            Map<ObservationType, Integer> lliG01 = (i == 5)
                    ? Map.of(PredefinedObservationType.L1C, 1)
                    : Map.of();
            map.put("G01", new RinexObservationData.SatelliteObsSample(
                    "G01", GnssConstellation.GPS,
                    Map.of(
                            PredefinedObservationType.C1C, r1,
                            PredefinedObservationType.L1C, r1 / lam1Gps,
                            PredefinedObservationType.C2W, r1,
                            PredefinedObservationType.L2W, r1 / lam2Gps,
                            PredefinedObservationType.S1C, 39.0,
                            PredefinedObservationType.S2W, 36.0),
                    lliG01, Map.of(), false, null));

            // G02: +-0.8 m sinusoidal multipath on C1C & C2W; clean phase
            double r2 = 22_000_000.0 + i * 30.0;
            double mp = 0.8 * Math.sin(i * 0.7);
            map.put("G02", new RinexObservationData.SatelliteObsSample(
                    "G02", GnssConstellation.GPS,
                    Map.of(
                            PredefinedObservationType.C1C, r2 + mp,
                            PredefinedObservationType.L1C, r2 / lam1Gps,
                            PredefinedObservationType.C2W, r2 - mp,
                            PredefinedObservationType.L2W, r2 / lam2Gps,
                            PredefinedObservationType.S1C, 46.0,
                            PredefinedObservationType.S2W, 43.0),
                    Map.of(), Map.of(), false, null));

            // G03: low elevation 12 deg, C/N0 = 38.5 dB-Hz (clean against baseline ~38.76 dB-Hz)
            double r3 = 23_800_000.0 + i * 20.0;
            map.put("G03", new RinexObservationData.SatelliteObsSample(
                    "G03", GnssConstellation.GPS,
                    Map.of(
                            PredefinedObservationType.C1C, r3,
                            PredefinedObservationType.L1C, r3 / lam1Gps,
                            PredefinedObservationType.S1C, 38.5),
                    Map.of(), Map.of(), false, null));

            // E11: pure geometry-free jump (+0.5 cycles on L1X starting at epoch 10, no LLI)
            double r4 = 24_200_000.0 + i * 25.0;
            double slipCycles = (i >= 10) ? 0.5 : 0.0;
            map.put("E11", new RinexObservationData.SatelliteObsSample(
                    "E11", GnssConstellation.GALILEO,
                    Map.of(
                            PredefinedObservationType.C1X, r4,
                            PredefinedObservationType.L1X, r4 / lam1Gal + slipCycles,
                            PredefinedObservationType.C5X, r4,
                            PredefinedObservationType.L5X, r4 / lam5Gal,
                            PredefinedObservationType.S1X, 46.5,
                            PredefinedObservationType.S5X, 44.0),
                    Map.of(), Map.of(), false, null));

            // E12 intentionally omitted -> TrackingState.MISSING!
            epochs.add(new RinexObservationData.EpochRecord(ep, 0.0, map));
        }

        RinexObservationData.HeaderSummary hdr = new RinexObservationData.HeaderSummary(
                "MADR00ESP", "SEPT POLARX5", "5.5.0", "AOAD/M_T NONE", monEcef, 30.0,
                t0, t0 + 19 * 30_000L, "DBHZ", new EnumMap<>(GnssConstellation.class), List.of());
        RinexObservationData data = new RinexObservationData(hdr, epochs);

        // Run analyzer at t0 -> inside window
        GnssStationAnalysis analysis = RinexObservationAnalyzer.analyze(
                madr, data, 10.0, t0 + 150_000L, provider, offline);

        assertFalse(analysis.epochOffsetFlagged());
        assertEquals(5, analysis.predictedCount());
        assertEquals(4, analysis.trackedCount());
        assertEquals(1, analysis.missingCount());
        assertEquals(1, analysis.lliSlipCount());
        assertTrue(analysis.gfSlipCount() >= 1, "Expected >= 1 pure GF slip");
        assertTrue(analysis.totalCycleSlips() >= 2);
        assertEquals(80.0, analysis.trackingCompletenessPct(), 0.5);

        GnssStationAnalysis.SatelliteMeasurementSummary g01 = findSat(analysis, "G01");
        assertEquals(GnssStationAnalysis.TrackingState.TRACKED, g01.trackingState());
        assertEquals(39.0, g01.medianCn0DbHz(), 0.01);
        assertEquals(39.0 - RinexObservationAnalyzer.expectedCn0DbHz(60.0), g01.medianDeltaCn0DbHz(), 0.25);
        assertTrue(g01.mp1RmsMeters() < 0.05);

        GnssStationAnalysis.SatelliteMeasurementSummary g02 = findSat(analysis, "G02");
        assertTrue(g02.mp1RmsMeters() >= 0.40 && g02.mp1RmsMeters() <= 0.85,
                "Expected multipath MP1 ~0.55 m, got " + g02.mp1RmsMeters());

        GnssStationAnalysis.SatelliteMeasurementSummary g03 = findSat(analysis, "G03");
        assertTrue(Math.abs(g03.medianDeltaCn0DbHz()) < 2.0,
                "Low-el clean SV delta C/N0 should stay near 0, got " + g03.medianDeltaCn0DbHz());

        GnssStationAnalysis.SatelliteMeasurementSummary e12 = findSat(analysis, "E12");
        assertEquals(GnssStationAnalysis.TrackingState.MISSING, e12.trackingState());

        // Sim epoch > 2h outside file span triggers epochOffsetFlagged == true
        GnssStationAnalysis offset = RinexObservationAnalyzer.analyze(
                madr, data, 10.0, t0 + 6L * 3600_000L, provider, offline);
        assertTrue(offset.epochOffsetFlagged());
        assertEquals(t0 + 19 * 30_000L, offset.inspectionEpochMillis());
    }

    private static GnssStationAnalysis.SatelliteMeasurementSummary findSat(GnssStationAnalysis a, String prn) {
        return a.satellites().stream().filter(s -> prn.equals(s.prnId())).findFirst().orElseThrow();
    }

    private static String buildSp3FixedGeometry(double latDeg, double lonDeg, double altM) {
        StringBuilder sb = new StringBuilder();
        sb.append("#cP2024  6  1  0  0  0.00000000       9 ORBIT IGS20 HLM  IGS\n");
        sb.append("## 2316 518400.00000000   900.00000000 60462 0.0000000000000\n");
        sb.append("+    5   G01G02G03E11E12  0  0  0  0  0  0  0  0  0  0  0  0\n");
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
            sb.append("/* Analytics test\n");
        }
        double[][] defs = {
                {45.0, 60.0},   // G01
                {150.0, 35.0},  // G02
                {270.0, 12.0},  // G03
                {310.0, 45.0},  // E11
                {90.0, 50.0}    // E12
        };
        String[] names = {"PG01", "PG02", "PG03", "PE11", "PE12"};
        for (int step = 0; step < 9; step++) {
            int min = step * 15;
            sb.append(String.format(Locale.US, "*  2024  6  1 %2d %2d  0.00000000%n", min / 60, min % 60));
            for (int k = 0; k < names.length; k++) {
                double[] p = enuPointToEcefKm(latDeg, lonDeg, altM, defs[k][0], defs[k][1], 21_500_000.0);
                sb.append(String.format(Locale.US, "%s%14.6f%14.6f%14.6f%14.6f%n", names[k], p[0], p[1], p[2], 10.0));
            }
        }
        sb.append("EOF\n");
        return sb.toString();
    }

    private static double[] enuPointToEcefKm(
            double latDeg, double lonDeg, double altM,
            double azDeg, double elDeg, double rangeM) {
        double[] obs = GnssDopCalculator.geodeticToEcef(latDeg, lonDeg, altM);
        double az = Math.toRadians(azDeg);
        double el = Math.toRadians(elDeg);
        double e = rangeM * Math.cos(el) * Math.sin(az);
        double n = rangeM * Math.cos(el) * Math.cos(az);
        double u = rangeM * Math.sin(el);
        double lat = Math.toRadians(latDeg);
        double lon = Math.toRadians(lonDeg);
        double sinLat = Math.sin(lat), cosLat = Math.cos(lat);
        double sinLon = Math.sin(lon), cosLon = Math.cos(lon);
        double dx = -sinLon * e - sinLat * cosLon * n + cosLat * cosLon * u;
        double dy = cosLon * e - sinLat * sinLon * n + cosLat * sinLon * u;
        double dz = cosLat * n + sinLat * u;
        return new double[]{(obs[0] + dx) / 1000.0, (obs[1] + dy) / 1000.0, (obs[2] + dz) / 1000.0};
    }
}

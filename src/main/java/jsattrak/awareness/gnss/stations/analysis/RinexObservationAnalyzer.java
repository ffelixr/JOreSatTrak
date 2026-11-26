package jsattrak.awareness.gnss.stations.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jsattrak.awareness.gnss.GnssAwarenessService;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.GnssDisplayStatus;
import jsattrak.awareness.gnss.geometry.GnssDopCalculator;
import jsattrak.awareness.gnss.geometry.GnssDopSolution;
import jsattrak.awareness.gnss.geometry.GnssEphemerisProvider;
import jsattrak.awareness.gnss.geometry.GnssObserverPosition;
import jsattrak.awareness.gnss.geometry.SatelliteSkyPoint;
import jsattrak.awareness.gnss.stations.GnssReferenceStation;
import jsattrak.awareness.gnss.stations.obs.RinexObservationData;

/**
 * Computes predicted-vs-observed tracking completeness, elevation-normalized C/N0 residuals,
 * LLI + geometry-free cycle slips, dual-frequency MP1/MP2 multipath RMS, and local SPP quality
 * from parsed {@link RinexObservationData}.
 */
public final class RinexObservationAnalyzer {

    private static final double SPEED_OF_LIGHT = 299_792_458.0;
    private static final long TWO_HOURS_MILLIS = 2L * 3600L * 1000L;
    private static final long GF_SLIP_MAX_GAP_MILLIS = 30_000L;
    private static final double GF_SLIP_THRESHOLD_METERS = 0.05;
    private static final long MP_ARC_MAX_GAP_MILLIS = 60_000L;
    private static final int MP_MIN_ARC_SAMPLES = 10;

    private record SvArcSample(
            long epochMillis,
            double gfMeters,
            double mp1Raw,
            double mp2Raw,
            boolean hasLli) {
    }

    private RinexObservationAnalyzer() {
    }

    /**
     * Smooth empirical geodetic antenna reference curve (dB-Hz) as a function of elevation (deg).
     */
    public static double expectedCn0DbHz(double elevationDeg) {
        double clamped = Math.max(5.0, Math.min(90.0, elevationDeg));
        return 33.0 + 16.0 * Math.pow(Math.sin(Math.toRadians(clamped)), 0.65);
    }

    public static GnssStationAnalysis analyze(
            GnssReferenceStation station,
            RinexObservationData obs,
            double elevationMaskDeg,
            long simEpochMillis,
            GnssEphemerisProvider ephemerisProvider,
            GnssAwarenessService awarenessService) {
        List<RinexObservationData.EpochRecord> epochs = obs.epochs();
        long firstEpoch = epochs.get(0).epochMillis();
        long lastEpoch = epochs.get(epochs.size() - 1).epochMillis();

        boolean epochOffsetFlagged = simEpochMillis < firstEpoch - TWO_HOURS_MILLIS
                || simEpochMillis > lastEpoch + TWO_HOURS_MILLIS;
        long inspectionEpoch = epochOffsetFlagged
                ? lastEpoch
                : epochs.get(findClosestEpochIndex(epochs, simEpochMillis)).epochMillis();

        double[] obsPos = {station.latDeg(), station.lonDeg(), station.altMeters()};
        double[] monEcef = station.monumentEcef();
        if (monEcef != null && monEcef.length == 3
                && (monEcef[0] != 0.0 || monEcef[1] != 0.0 || monEcef[2] != 0.0)) {
            obsPos = jsattrak.awareness.gnss.stations.obs.LocalRinexFileSource.ecefToGeodeticDeg(
                    monEcef[0], monEcef[1], monEcef[2]);
        }

        // 1. Predicted visibility at inspection epoch
        GnssObserverPosition observer = new GnssObserverPosition(
                station.stationId(), obsPos[0], obsPos[1], obsPos[2]);
        GnssEphemerisProvider.ResolvedEpochSnapshot predSnap = (ephemerisProvider != null)
                ? ephemerisProvider.resolveSatellitesAtEpoch(inspectionEpoch, awarenessService)
                : new GnssEphemerisProvider.ResolvedEpochSnapshot(List.of(),
                        jsattrak.awareness.gnss.geometry.GnssOrbitSource.Summary.fromCounts(0, 0, 0, false, ""));
        GnssDopSolution dopSol = GnssDopCalculator.computePointDop(
                inspectionEpoch, observer, elevationMaskDeg, false, predSnap.candidates());

        Map<String, SatelliteSkyPoint> skyByPrn = new LinkedHashMap<>();
        Set<String> predictedOperationalAboveMask = new LinkedHashSet<>();
        for (SatelliteSkyPoint sp : dopSol.skyPoints()) {
            skyByPrn.put(sp.prnId(), sp);
            if (sp.aboveMask() && sp.status() == GnssDisplayStatus.OPERATIONAL) {
                predictedOperationalAboveMask.add(sp.prnId());
            }
        }

        // 2. Gather per-PRN session stats across all epochs
        RinexObservationData.EpochRecord inspRecord = epochs.get(findClosestEpochIndex(epochs, inspectionEpoch));
        Set<String> observedAtInsp = inspRecord.satellites().keySet();

        int predictedCount = predictedOperationalAboveMask.size();
        int trackedCount = 0;
        int missingCount = 0;
        for (String predPrn : predictedOperationalAboveMask) {
            if (observedAtInsp.contains(predPrn)) {
                trackedCount++;
            } else {
                missingCount++;
            }
        }
        double completenessPct = predictedCount > 0 ? (100.0 * trackedCount / predictedCount) : 100.0;

        Map<String, List<Double>> cn0ByPrn = new LinkedHashMap<>();
        Map<String, List<Double>> deltaByPrn = new LinkedHashMap<>();
        Map<String, List<SvArcSample>> arcSamplesByPrn = new LinkedHashMap<>();
        Map<String, GnssConstellation> constByPrn = new LinkedHashMap<>();
        List<Double> allCn0 = new ArrayList<>();
        List<Double> allDeltas = new ArrayList<>();
        List<GnssStationAnalysis.Cn0TimeSample> timeSeries = new ArrayList<>();
        List<GnssStationAnalysis.Cn0ElevationPoint> scatter = new ArrayList<>();

        int totalObsSamples = 0;
        int lliSlipCount = 0;
        int gfSlipCount = 0;
        int totalSlips = 0;
        Map<String, Integer> slipsByPrn = new LinkedHashMap<>();

        // Resolve orbit positions once at inspection epoch for Az/El fallback across nearby hourly span
        for (RinexObservationData.EpochRecord rec : epochs) {
            for (Map.Entry<String, RinexObservationData.SatelliteObsSample> e : rec.satellites().entrySet()) {
                String prn = e.getKey();
                RinexObservationData.SatelliteObsSample sample = e.getValue();
                constByPrn.putIfAbsent(prn, sample.constellation());
                totalObsSamples++;

                SatelliteSkyPoint sky = skyByPrn.get(prn);
                double elDeg = sky != null ? sky.elevationDeg() : 30.0;
                double cn0 = sample.primaryCn0DbHz(sample.constellation());
                boolean hasLli = sample.hasLossOfLock();
                if (hasLli) {
                    lliSlipCount++;
                    totalSlips++;
                    slipsByPrn.merge(prn, 1, Integer::sum);
                }

                double delta = Double.isFinite(cn0) ? (cn0 - expectedCn0DbHz(elDeg)) : Double.NaN;
                if (Double.isFinite(cn0)) {
                    cn0ByPrn.computeIfAbsent(prn, k -> new ArrayList<>()).add(cn0);
                    deltaByPrn.computeIfAbsent(prn, k -> new ArrayList<>()).add(delta);
                    allCn0.add(cn0);
                    allDeltas.add(delta);
                    timeSeries.add(new GnssStationAnalysis.Cn0TimeSample(
                            rec.epochMillis(), prn, sample.constellation(), elDeg, cn0, delta));
                    if (rec.epochMillis() == inspRecord.epochMillis()) {
                        scatter.add(new GnssStationAnalysis.Cn0ElevationPoint(
                                prn, sample.constellation(), elDeg, cn0, delta, hasLli));
                    }
                }

                RinexObservationData.DualFreqObs df = sample.selectDualFreqPair();
                double gfMeters = Double.NaN;
                double mp1Raw = Double.NaN;
                double mp2Raw = Double.NaN;
                if (df != null) {
                    double lam1 = SPEED_OF_LIGHT / df.f1Hz();
                    double lam2 = SPEED_OF_LIGHT / df.f2Hz();
                    double phi1 = lam1 * df.l1Cycles();
                    double phi2 = lam2 * df.l2Cycles();
                    gfMeters = phi1 - phi2;
                    double alpha = Math.pow(df.f1Hz() / df.f2Hz(), 2);
                    mp1Raw = df.c1Meters()
                            - ((alpha + 1.0) / (alpha - 1.0)) * phi1
                            + (2.0 / (alpha - 1.0)) * phi2;
                    mp2Raw = df.c2Meters()
                            - ((2.0 * alpha) / (alpha - 1.0)) * phi1
                            + ((alpha + 1.0) / (alpha - 1.0)) * phi2;
                }
                arcSamplesByPrn.computeIfAbsent(prn, k -> new ArrayList<>())
                        .add(new SvArcSample(rec.epochMillis(), gfMeters, mp1Raw, mp2Raw, hasLli));
            }
        }

        // Compute pure geometry-free slips and arc-demeaned MP1 / MP2 RMS
        List<Double> allMp1Sq = new ArrayList<>();
        List<Double> allMp2Sq = new ArrayList<>();
        Map<String, Double> mp1RmsByPrn = new LinkedHashMap<>();

        for (Map.Entry<String, List<SvArcSample>> e : arcSamplesByPrn.entrySet()) {
            String prn = e.getKey();
            List<SvArcSample> seq = e.getValue();
            List<Double> arcMp1 = new ArrayList<>();
            List<Double> arcMp2 = new ArrayList<>();
            List<Double> svMp1Sq = new ArrayList<>();

            SvArcSample prev = null;
            for (SvArcSample cur : seq) {
                boolean gapBreak = prev != null && (cur.epochMillis() - prev.epochMillis()) > MP_ARC_MAX_GAP_MILLIS;
                boolean slipBreak = false;
                if (prev != null && !gapBreak && Double.isFinite(cur.gfMeters()) && Double.isFinite(prev.gfMeters())) {
                    long dt = cur.epochMillis() - prev.epochMillis();
                    if (dt > 0 && dt <= GF_SLIP_MAX_GAP_MILLIS
                            && Math.abs(cur.gfMeters() - prev.gfMeters()) > GF_SLIP_THRESHOLD_METERS) {
                        slipBreak = true;
                        gfSlipCount++;
                        if (!cur.hasLli()) {
                            totalSlips++;
                            slipsByPrn.merge(prn, 1, Integer::sum);
                        }
                    }
                }
                if (gapBreak || slipBreak || cur.hasLli()) {
                    flushMpArc(arcMp1, arcMp2, svMp1Sq, allMp1Sq, allMp2Sq);
                }
                if (Double.isFinite(cur.mp1Raw()) && Double.isFinite(cur.mp2Raw())) {
                    arcMp1.add(cur.mp1Raw());
                    arcMp2.add(cur.mp2Raw());
                }
                prev = cur;
            }
            flushMpArc(arcMp1, arcMp2, svMp1Sq, allMp1Sq, allMp2Sq);
            mp1RmsByPrn.put(prn, rmsFromSquares(svMp1Sq));
        }

        // Assemble per-SV table entries covering tracked & missing predicted SVs
        Set<String> allTablePrns = new LinkedHashSet<>(constByPrn.keySet());
        allTablePrns.addAll(predictedOperationalAboveMask);
        List<GnssStationAnalysis.SatelliteMeasurementSummary> satSummaries = new ArrayList<>(allTablePrns.size());
        for (String prn : allTablePrns) {
            SatelliteSkyPoint sky = skyByPrn.get(prn);
            GnssConstellation c = constByPrn.getOrDefault(prn,
                    sky != null ? sky.constellation() : GnssConstellation.fromPrefix(prn.charAt(0)));
            double az = sky != null ? sky.azimuthDeg() : Double.NaN;
            double el = sky != null ? sky.elevationDeg() : Double.NaN;
            boolean inObserved = observedAtInsp.contains(prn);
            GnssStationAnalysis.TrackingState state;
            if (predictedOperationalAboveMask.contains(prn) && !inObserved) {
                state = GnssStationAnalysis.TrackingState.MISSING;
            } else if (sky != null && !sky.aboveMask()) {
                state = GnssStationAnalysis.TrackingState.BELOW_MASK_TRACKED;
            } else {
                state = GnssStationAnalysis.TrackingState.TRACKED;
            }

            List<Double> vals = cn0ByPrn.getOrDefault(prn, List.of());
            List<Double> dVals = deltaByPrn.getOrDefault(prn, List.of());
            satSummaries.add(new GnssStationAnalysis.SatelliteMeasurementSummary(
                    prn,
                    c,
                    state,
                    az,
                    el,
                    percentile(vals, 50.0),
                    min(vals),
                    percentile(vals, 10.0),
                    percentile(vals, 90.0),
                    max(vals),
                    percentile(dVals, 50.0),
                    slipsByPrn.getOrDefault(prn, 0),
                    mp1RmsByPrn.getOrDefault(prn, Double.NaN),
                    vals.size()));
        }

        double slipsPer100 = totalObsSamples > 0 ? (100.0 * totalSlips / totalObsSamples) : 0.0;
        GnssSppSolver.SppSessionSummary spp = GnssSppSolver.solveSession(
                obs, monEcef, elevationMaskDeg, inspectionEpoch, ephemerisProvider, awarenessService);

        return new GnssStationAnalysis(
                station,
                inspectionEpoch,
                epochOffsetFlagged,
                elevationMaskDeg,
                predictedCount,
                trackedCount,
                missingCount,
                completenessPct,
                percentile(allCn0, 50.0),
                percentile(allDeltas, 50.0),
                totalSlips,
                lliSlipCount,
                gfSlipCount,
                slipsPer100,
                rmsFromSquares(allMp1Sq),
                rmsFromSquares(allMp2Sq),
                spp,
                satSummaries,
                scatter,
                timeSeries);
    }

    private static void flushMpArc(
            List<Double> arcMp1,
            List<Double> arcMp2,
            List<Double> svMp1Sq,
            List<Double> allMp1Sq,
            List<Double> allMp2Sq) {
        if (arcMp1.size() >= MP_MIN_ARC_SAMPLES) {
            double mean1 = mean(arcMp1);
            double mean2 = mean(arcMp2);
            for (int i = 0; i < arcMp1.size(); i++) {
                double d1 = arcMp1.get(i) - mean1;
                double d2 = arcMp2.get(i) - mean2;
                double sq1 = d1 * d1;
                svMp1Sq.add(sq1);
                allMp1Sq.add(sq1);
                allMp2Sq.add(d2 * d2);
            }
        }
        arcMp1.clear();
        arcMp2.clear();
    }

    private static int findClosestEpochIndex(List<RinexObservationData.EpochRecord> epochs, long targetMillis) {
        int bestIdx = epochs.size() - 1;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < epochs.size(); i++) {
            long d = Math.abs(epochs.get(i).epochMillis() - targetMillis);
            if (d < bestDiff) {
                bestDiff = d;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private static double rmsFromSquares(List<Double> sqList) {
        if (sqList == null || sqList.isEmpty()) {
            return Double.NaN;
        }
        double sum = 0.0;
        for (double sq : sqList) {
            sum += sq;
        }
        return Math.sqrt(sum / sqList.size());
    }

    private static double mean(List<Double> values) {
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    private static double min(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return Double.NaN;
        }
        double m = Double.POSITIVE_INFINITY;
        for (double v : values) {
            m = Math.min(m, v);
        }
        return m;
    }

    private static double max(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return Double.NaN;
        }
        double m = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            m = Math.max(m, v);
        }
        return m;
    }

    private static double percentile(List<Double> values, double pct) {
        if (values == null || values.isEmpty()) {
            return Double.NaN;
        }
        double[] arr = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            arr[i] = values.get(i);
        }
        Arrays.sort(arr);
        double rank = (pct / 100.0) * (arr.length - 1);
        int lo = (int) Math.floor(rank);
        int hi = Math.min(arr.length - 1, lo + 1);
        double frac = rank - lo;
        return arr[lo] * (1.0 - frac) + arr[hi] * frac;
    }
}

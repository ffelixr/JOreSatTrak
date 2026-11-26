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
import jsattrak.awareness.gnss.geometry.GnssDopCalculator;
import jsattrak.awareness.gnss.geometry.GnssEphemerisProvider;
import jsattrak.awareness.gnss.geometry.GnssOrbitSource;
import jsattrak.awareness.gnss.stations.obs.RinexObservationData;
import org.hipparchus.linear.Array2DRowRealMatrix;
import org.hipparchus.linear.ArrayRealVector;
import org.hipparchus.linear.DecompositionSolver;
import org.hipparchus.linear.MatrixUtils;
import org.hipparchus.linear.QRDecomposition;
import org.hipparchus.linear.RealMatrix;
import org.hipparchus.linear.RealVector;
import org.hipparchus.linear.SingularValueDecomposition;

/**
 * Local elevation-weighted Gauss-Newton Single-Point Positioning (SPP) verification solver
 * comparing RINEX pseudorange fixes against surveyed station monument coordinates.
 */
public final class GnssSppSolver {

    public static final double SPEED_OF_LIGHT = 299_792_458.0;
    public static final double OMEGA_EARTH = 7.2921151467e-5;
    private static final double OUTLIER_THRESHOLD_METERS = 30.0;
    private static final int MAX_SAMPLED_EPOCHS = 120;
    private static final int MAX_ITERATIONS = 10;

    public record SppEpochResult(
            long epochMillis,
            boolean sppAvailable,
            double[] solvedEcef,
            double horizontalErrorM,
            double verticalErrorM,
            double error3dM,
            double pseudorangeRmsM,
            double rxClockOffsetMicros,
            int usedSvCount,
            String statusMessage) {

        public static SppEpochResult unavailable(long epochMillis, String reason) {
            return new SppEpochResult(
                    epochMillis, false, new double[0], Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, 0, reason != null ? reason : "N/A");
        }
    }

    public record SppSessionSummary(
            SppEpochResult inspectionEpoch,
            double medianHorizErrM,
            double p95HorizErrM,
            double medianVertErrM,
            double p95VertErrM,
            int solvedEpochCount) {
    }

    private record MeasObs(
            GnssEphemerisProvider.CandidateSatelliteWithClock sat,
            double pseudorangeM,
            double weight) {
    }

    private GnssSppSolver() {
    }

    /**
     * Standard Saastamoinen hydrostatic tropospheric slant delay estimate (meters).
     */
    public static double saastamoinenTropoMeters(double elevationDeg, double altMeters) {
        double clampedAlt = Math.max(-500.0, Math.min(20_000.0, altMeters));
        double p = 1013.25 * Math.pow(1.0 - 2.2557e-5 * clampedAlt, 5.2568);
        double zenHydro = 0.002277 * p;
        double sinEl = Math.sin(Math.toRadians(Math.max(5.0, elevationDeg)));
        return zenHydro / sinEl;
    }

    /**
     * Solves SPP across subsampled session epochs (up to 120) and at {@code inspectionEpochMillis},
     * comparing each solution against {@code monumentEcef}.
     */
    public static SppSessionSummary solveSession(
            RinexObservationData obs,
            double[] monumentEcef,
            double elevationMaskDeg,
            long inspectionEpochMillis,
            GnssEphemerisProvider ephemerisProvider,
            GnssAwarenessService awarenessService) {
        if (obs == null || obs.epochs().isEmpty() || ephemerisProvider == null
                || monumentEcef == null || monumentEcef.length != 3
                || (monumentEcef[0] == 0.0 && monumentEcef[1] == 0.0 && monumentEcef[2] == 0.0)) {
            return new SppSessionSummary(
                    SppEpochResult.unavailable(inspectionEpochMillis, "N/A (No monument or ephemeris)"),
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0);
        }

        List<RinexObservationData.EpochRecord> epochs = obs.epochs();
        int inspIdx = findClosestEpochIndex(epochs, inspectionEpochMillis);
        SppEpochResult inspectionRes = solveSingleEpoch(
                epochs.get(inspIdx), monumentEcef, elevationMaskDeg, ephemerisProvider, awarenessService);

        int stride = Math.max(1, (int) Math.ceil(epochs.size() / (double) MAX_SAMPLED_EPOCHS));
        List<Double> hErrs = new ArrayList<>();
        List<Double> vErrs = new ArrayList<>();

        for (int i = 0; i < epochs.size(); i += stride) {
            SppEpochResult r = (i == inspIdx)
                    ? inspectionRes
                    : solveSingleEpoch(epochs.get(i), monumentEcef, elevationMaskDeg, ephemerisProvider, awarenessService);
            if (r.sppAvailable() && Double.isFinite(r.horizontalErrorM()) && Double.isFinite(r.verticalErrorM())) {
                hErrs.add(r.horizontalErrorM());
                vErrs.add(r.verticalErrorM());
            }
        }
        if (hErrs.isEmpty() && inspectionRes.sppAvailable()) {
            hErrs.add(inspectionRes.horizontalErrorM());
            vErrs.add(inspectionRes.verticalErrorM());
        }

        return new SppSessionSummary(
                inspectionRes,
                percentile(hErrs, 50.0),
                percentile(hErrs, 95.0),
                percentile(vErrs, 50.0),
                percentile(vErrs, 95.0),
                hErrs.size());
    }

    public static SppEpochResult solveSingleEpoch(
            RinexObservationData.EpochRecord epoch,
            double[] monumentEcef,
            double elevationMaskDeg,
            GnssEphemerisProvider ephemerisProvider,
            GnssAwarenessService awarenessService) {
        if (epoch == null || epoch.satellites().isEmpty()) {
            return SppEpochResult.unavailable(0L, "No observation epoch");
        }
        GnssEphemerisProvider.ResolvedClockEpochSnapshot snap =
                ephemerisProvider.resolveSatellitesWithClockAtEpoch(epoch.epochMillis(), awarenessService);
        if (snap == null || snap.orbitSource().primary() == GnssOrbitSource.TLE_FALLBACK
                || snap.orbitSource().primary() == GnssOrbitSource.NONE) {
            return SppEpochResult.unavailable(epoch.epochMillis(), "N/A (BRDC or SP3 clocks required)");
        }

        Map<String, GnssEphemerisProvider.CandidateSatelliteWithClock> satByPrn = new LinkedHashMap<>();
        for (GnssEphemerisProvider.CandidateSatelliteWithClock sat : snap.candidates()) {
            if (sat != null && sat.clockValid()) {
                satByPrn.put(sat.prnId(), sat);
            }
        }
        if (satByPrn.size() < 4) {
            return SppEpochResult.unavailable(epoch.epochMillis(), "N/A (Load matching RINEX Nav/SP3)");
        }

        double[] lla = ecefToGeodeticDeg(monumentEcef[0], monumentEcef[1], monumentEcef[2]);
        List<MeasObs> measList = new ArrayList<>();
        for (Map.Entry<String, RinexObservationData.SatelliteObsSample> e : epoch.satellites().entrySet()) {
            GnssEphemerisProvider.CandidateSatelliteWithClock sat = satByPrn.get(e.getKey());
            if (sat == null) {
                continue;
            }
            double[] azEl = GnssDopCalculator.computeAzEl(
                    lla[0], lla[1], lla[2], sat.satX(), sat.satY(), sat.satZ());
            if (azEl[1] < elevationMaskDeg) {
                continue;
            }
            RinexObservationData.SatelliteObsSample sample = e.getValue();
            double tropo = saastamoinenTropoMeters(azEl[1], lla[2]);
            double corrPr = buildCorrectedPseudorange(sample, sat, tropo);
            if (!Double.isFinite(corrPr)) {
                continue;
            }
            double sinEl = Math.sin(Math.toRadians(Math.max(5.0, azEl[1])));
            measList.add(new MeasObs(sat, corrPr, sinEl * sinEl));
        }

        if (measList.size() < 4) {
            return SppEpochResult.unavailable(epoch.epochMillis(), "N/A (<4 clock-valid SVs above mask)");
        }

        SppSolveAttempt attempt = runGaussNewtonWithIsb(measList, monumentEcef);
        if (attempt == null) {
            return SppEpochResult.unavailable(epoch.epochMillis(), "N/A (SPP matrix singular)");
        }

        // One robust >30 m outlier rejection pass if enough SVs exist
        List<MeasObs> inliers = new ArrayList<>(measList.size());
        for (int i = 0; i < measList.size(); i++) {
            if (Math.abs(attempt.residuals()[i]) <= OUTLIER_THRESHOLD_METERS) {
                inliers.add(measList.get(i));
            }
        }
        if (inliers.size() < measList.size() && inliers.size() >= 4) {
            SppSolveAttempt cleanAttempt = runGaussNewtonWithIsb(inliers, monumentEcef);
            if (cleanAttempt != null) {
                attempt = cleanAttempt;
                measList = inliers;
            }
        }

        double[] solved = attempt.xyz();
        double[] enuErr = ecefDeltaToEnu(
                solved[0] - monumentEcef[0],
                solved[1] - monumentEcef[1],
                solved[2] - monumentEcef[2],
                lla[0], lla[1]);
        double hErr = Math.hypot(enuErr[0], enuErr[1]);
        double vErr = Math.abs(enuErr[2]);
        double e3d = Math.sqrt(enuErr[0] * enuErr[0] + enuErr[1] * enuErr[1] + enuErr[2] * enuErr[2]);

        double sumSq = 0.0;
        for (double r : attempt.residuals()) {
            sumSq += r * r;
        }
        double rms = Math.sqrt(sumSq / attempt.residuals().length);
        double clkMicros = (attempt.cDtMeters() / SPEED_OF_LIGHT) * 1.0e6;

        return new SppEpochResult(
                epoch.epochMillis(), true, solved, hErr, vErr, e3d, rms, clkMicros,
                measList.size(), "OK");
    }

    private record SppSolveAttempt(double[] xyz, double cDtMeters, double[] residuals) {
    }

    private static SppSolveAttempt runGaussNewtonWithIsb(List<MeasObs> meas, double[] initEcef) {
        // Count per-constellation SVs; include ISB states for extra constellations with >=2 SVs when N >= 4 + extraCount
        Map<GnssConstellation, Integer> counts = new LinkedHashMap<>();
        for (MeasObs m : meas) {
            counts.merge(m.sat().constellation(), 1, Integer::sum);
        }
        GnssConstellation refConst = counts.containsKey(GnssConstellation.GPS)
                ? GnssConstellation.GPS
                : meas.get(0).sat().constellation();
        List<GnssConstellation> extraIsbConsts = new ArrayList<>();
        for (Map.Entry<GnssConstellation, Integer> e : counts.entrySet()) {
            if (e.getKey() != refConst && e.getValue() >= 2) {
                extraIsbConsts.add(e.getKey());
            }
        }
        if (meas.size() < 4 + extraIsbConsts.size() + 1) {
            // Too few measurements for extra ISBs; filter to dominant constellation if >=4, else 4-param
            if (counts.getOrDefault(refConst, 0) >= 4) {
                List<MeasObs> single = new ArrayList<>();
                for (MeasObs m : meas) {
                    if (m.sat().constellation() == refConst) {
                        single.add(m);
                    }
                }
                return solveWls(single, List.of(), refConst, initEcef);
            }
            extraIsbConsts = List.of();
        }
        return solveWls(meas, extraIsbConsts, refConst, initEcef);
    }

    private static SppSolveAttempt solveWls(
            List<MeasObs> meas,
            List<GnssConstellation> extraConsts,
            GnssConstellation refConst,
            double[] initEcef) {
        int n = meas.size();
        int numParams = 4 + extraConsts.size();
        if (n < numParams) {
            return null;
        }
        double[] state = new double[numParams];
        state[0] = initEcef[0];
        state[1] = initEcef[1];
        state[2] = initEcef[2];

        double[] lastRes = new double[n];
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            RealMatrix h = new Array2DRowRealMatrix(n, numParams);
            RealVector v = new ArrayRealVector(n);
            RealMatrix w = MatrixUtils.createRealDiagonalMatrix(new double[n]);

            for (int i = 0; i < n; i++) {
                MeasObs obs = meas.get(i);
                GnssEphemerisProvider.CandidateSatelliteWithClock sat = obs.sat();
                double dx0 = sat.satX() - state[0];
                double dy0 = sat.satY() - state[1];
                double dz0 = sat.satZ() - state[2];
                double rho0 = Math.sqrt(dx0 * dx0 + dy0 * dy0 + dz0 * dz0);
                double tau = rho0 / SPEED_OF_LIGHT;
                double sx = sat.satX() - sat.velX() * tau + OMEGA_EARTH * sat.satY() * tau;
                double sy = sat.satY() - sat.velY() * tau - OMEGA_EARTH * sat.satX() * tau;
                double sz = sat.satZ() - sat.velZ() * tau;

                double dx = sx - state[0];
                double dy = sy - state[1];
                double dz = sz - state[2];
                double geom = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (geom < 1.0) {
                    return null;
                }

                double isb = 0.0;
                int isbIdx = extraConsts.indexOf(sat.constellation());
                if (isbIdx >= 0) {
                    isb = state[4 + isbIdx];
                }

                double predicted = geom + state[3] + isb;
                double res = obs.pseudorangeM() - predicted;
                lastRes[i] = res;
                v.setEntry(i, res);
                w.setEntry(i, i, obs.weight());

                h.setEntry(i, 0, -dx / geom);
                h.setEntry(i, 1, -dy / geom);
                h.setEntry(i, 2, -dz / geom);
                h.setEntry(i, 3, 1.0);
                if (isbIdx >= 0) {
                    h.setEntry(i, 4 + isbIdx, 1.0);
                }
            }

            RealMatrix htW = h.transpose().multiply(w);
            RealMatrix normal = htW.multiply(h);
            RealVector rhs = htW.operate(v);

            RealVector step;
            try {
                SingularValueDecomposition svd = new SingularValueDecomposition(normal);
                double[] sv = svd.getSingularValues();
                if (sv.length == 0 || sv[sv.length - 1] <= 1e-12) {
                    return null;
                }
                DecompositionSolver solver = new QRDecomposition(normal).getSolver();
                step = solver.solve(rhs);
            } catch (RuntimeException e) {
                return null;
            }

            for (int k = 0; k < numParams; k++) {
                state[k] += step.getEntry(k);
            }
            if (Math.hypot(Math.hypot(step.getEntry(0), step.getEntry(1)), step.getEntry(2)) < 1e-4) {
                break;
            }
        }
        return new SppSolveAttempt(new double[]{state[0], state[1], state[2]}, state[3], lastRes);
    }

    private static double buildCorrectedPseudorange(
            RinexObservationData.SatelliteObsSample sample,
            GnssEphemerisProvider.CandidateSatelliteWithClock sat,
            double tropoMeters) {
        RinexObservationData.DualFreqObs df = sample.selectDualFreqPair();
        if (df != null) {
            double alpha = Math.pow(df.f1Hz() / df.f2Hz(), 2);
            double pIf = (alpha * df.c1Meters() - df.c2Meters()) / (alpha - 1.0);
            return pIf + SPEED_OF_LIGHT * sat.clockBiasSec() - tropoMeters;
        }
        RinexObservationData.PrimaryCodeObs single = sample.selectPrimaryCode(sat.constellation());
        if (single == null) {
            return Double.NaN;
        }
        return single.codeMeters() + SPEED_OF_LIGHT * (sat.clockBiasSec() - sat.tgdSec()) - tropoMeters;
    }

    private static int findClosestEpochIndex(List<RinexObservationData.EpochRecord> epochs, long targetMillis) {
        int bestIdx = epochs.size() - 1;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < epochs.size(); i++) {
            long diff = Math.abs(epochs.get(i).epochMillis() - targetMillis);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private static double[] ecefDeltaToEnu(double dx, double dy, double dz, double latDeg, double lonDeg) {
        double lat = Math.toRadians(latDeg);
        double lon = Math.toRadians(lonDeg);
        double sinLat = Math.sin(lat);
        double cosLat = Math.cos(lat);
        double sinLon = Math.sin(lon);
        double cosLon = Math.cos(lon);
        double e = -sinLon * dx + cosLon * dy;
        double n = -sinLat * cosLon * dx - sinLat * sinLon * dy + cosLat * dz;
        double u = cosLat * cosLon * dx + cosLat * sinLon * dy + sinLat * dz;
        return new double[]{e, n, u};
    }

    private static double[] ecefToGeodeticDeg(double x, double y, double z) {
        double a = GnssDopCalculator.WGS84_A;
        double e2 = GnssDopCalculator.WGS84_E2;
        double lon = Math.atan2(y, x);
        double p = Math.hypot(x, y);
        double lat = Math.atan2(z, p * (1.0 - e2));
        for (int i = 0; i < 6; i++) {
            double sinLat = Math.sin(lat);
            double n = a / Math.sqrt(1.0 - e2 * sinLat * sinLat);
            lat = Math.atan2(z + e2 * n * sinLat, p);
        }
        double sinLat = Math.sin(lat);
        double n = a / Math.sqrt(1.0 - e2 * sinLat * sinLat);
        double alt = p / Math.max(1e-12, Math.cos(lat)) - n;
        return new double[]{Math.toDegrees(lat), Math.toDegrees(lon), alt};
    }

    private static double percentile(List<Double> values, double pct) {
        if (values == null || values.isEmpty()) {
            return Double.NaN;
        }
        double[] sorted = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            sorted[i] = values.get(i);
        }
        Arrays.sort(sorted);
        double rank = (pct / 100.0) * (sorted.length - 1);
        int lo = (int) Math.floor(rank);
        int hi = Math.min(sorted.length - 1, lo + 1);
        double frac = rank - lo;
        return sorted[lo] * (1.0 - frac) + sorted[hi] * frac;
    }
}

package jsattrak.awareness.gnss.stations.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.stations.GnssReferenceStation;

/**
 * Evaluates multi-station elevation-normalized C/N0 residuals, missing satellites, and slip rates
 * (for satellites at {@code el >= 15°}) to separate local hardware/masking faults from synchronized
 * regional C/N0 drops indicative of RF interference.
 */
public final class RegionalCn0AnomalyDetector {

    private static final double MIN_ANOMALY_ELEVATION_DEG = 15.0;
    private static final double WARNING_DELTA_CN0_DB = -4.0;
    private static final double ALERT_DELTA_CN0_DB = -7.0;
    private static final double SYNC_DROP_THRESHOLD_DB = 6.0;
    private static final long SYNC_WINDOW_MILLIS = 5L * 60_000L;
    private static final long REGIONAL_OVERLAP_TOLERANCE_MILLIS = 15L * 60_000L;

    public record StationObservationInput(
            GnssReferenceStation station,
            GnssStationAnalysis analysis,
            double distanceKm) {
    }

    private RegionalCn0AnomalyDetector() {
    }

    public static RegionalScanReport evaluate(List<StationObservationInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return RegionalScanReport.empty();
        }

        List<RegionalScanReport.StationAnomalySummary> summaries = new ArrayList<>(inputs.size());
        Set<String> prnSet = new LinkedHashSet<>();
        Map<String, Map<String, Double>> matrix = new LinkedHashMap<>();

        for (StationObservationInput in : inputs) {
            if (in == null || in.station() == null || in.analysis() == null) {
                continue;
            }
            GnssReferenceStation st = in.station();
            GnssStationAnalysis a = in.analysis();

            List<Double> svMedDeltasHighEl = new ArrayList<>();
            Map<GnssConstellation, List<Double>> constDeltas = new EnumMap<>(GnssConstellation.class);
            Map<String, Double> stationRow = new LinkedHashMap<>();

            for (GnssStationAnalysis.SatelliteMeasurementSummary sat : a.satellites()) {
                if (!Double.isFinite(sat.medianDeltaCn0DbHz())) {
                    continue;
                }
                prnSet.add(sat.prnId());
                stationRow.put(sat.prnId(), sat.medianDeltaCn0DbHz());
                if (!Double.isFinite(sat.elevationDeg()) || sat.elevationDeg() >= MIN_ANOMALY_ELEVATION_DEG) {
                    svMedDeltasHighEl.add(sat.medianDeltaCn0DbHz());
                    if (sat.constellation() != null) {
                        constDeltas.computeIfAbsent(sat.constellation(), k -> new ArrayList<>())
                                .add(sat.medianDeltaCn0DbHz());
                    }
                }
            }
            matrix.put(st.stationId(), stationRow);

            // Detect 5-min synchronized drop >= 6 dB across >=3 SVs vs station p90 baseline
            boolean syncDropAlert = detectSynchronizedFiveMinuteDrop(a.timeSeries());

            double medianDelta = percentile(svMedDeltasHighEl, 50.0);
            int svHighCount = svMedDeltasHighEl.size();
            double missingFrac = a.predictedCount() > 0 ? ((double) a.missingCount() / a.predictedCount()) : 0.0;
            double slipRate = a.slipsPer100Obs();

            EnumSet<GnssConstellation> degradedConsts = EnumSet.noneOf(GnssConstellation.class);
            for (Map.Entry<GnssConstellation, List<Double>> ce : constDeltas.entrySet()) {
                if (ce.getValue().size() >= 2 && percentile(ce.getValue(), 50.0) <= WARNING_DELTA_CN0_DB) {
                    degradedConsts.add(ce.getKey());
                }
            }

            RegionalScanReport.StationAnomalyLevel level = RegionalScanReport.StationAnomalyLevel.NOMINAL;
            List<String> reasons = new ArrayList<>();

            if (svHighCount >= 3 && Double.isFinite(medianDelta) && medianDelta <= ALERT_DELTA_CN0_DB) {
                level = RegionalScanReport.StationAnomalyLevel.ALERT;
                reasons.add(String.format(Locale.US, "Med ΔC/N0 %.1f dB", medianDelta));
            } else if (a.missingCount() >= 3 && missingFrac >= 0.50) {
                level = RegionalScanReport.StationAnomalyLevel.ALERT;
                reasons.add(String.format(Locale.US, "%d/%d predicted SVs missing", a.missingCount(), a.predictedCount()));
            } else if (syncDropAlert) {
                level = RegionalScanReport.StationAnomalyLevel.ALERT;
                reasons.add("Synchronized 5m C/N0 drop >= 6 dB");
            } else if (svHighCount >= 3 && Double.isFinite(medianDelta) && medianDelta <= WARNING_DELTA_CN0_DB) {
                level = RegionalScanReport.StationAnomalyLevel.WARNING;
                reasons.add(String.format(Locale.US, "Med ΔC/N0 %.1f dB", medianDelta));
            } else if (a.missingCount() >= 2 && missingFrac >= 0.25) {
                level = RegionalScanReport.StationAnomalyLevel.WARNING;
                reasons.add(String.format(Locale.US, "%d/%d SVs missing", a.missingCount(), a.predictedCount()));
            } else if (slipRate >= 5.0) {
                level = RegionalScanReport.StationAnomalyLevel.WARNING;
                reasons.add(String.format(Locale.US, "High slip rate %.1f%%", slipRate));
            }

            // Ensure degradedConstellations is populated if station triggered non-C/N0 anomaly
            if (level != RegionalScanReport.StationAnomalyLevel.NOMINAL && degradedConsts.isEmpty()) {
                for (GnssStationAnalysis.SatelliteMeasurementSummary sat : a.satellites()) {
                    if (sat.trackingState() == GnssStationAnalysis.TrackingState.MISSING && sat.constellation() != null) {
                        degradedConsts.add(sat.constellation());
                    }
                }
                if (degradedConsts.isEmpty()) {
                    degradedConsts.addAll(st.supportedConstellations());
                }
            }

            long winStart = a.inspectionEpochMillis();
            long winEnd = a.inspectionEpochMillis();
            if (!a.timeSeries().isEmpty()) {
                winStart = a.timeSeries().stream().mapToLong(GnssStationAnalysis.Cn0TimeSample::epochMillis).min().orElse(winStart);
                winEnd = a.timeSeries().stream().mapToLong(GnssStationAnalysis.Cn0TimeSample::epochMillis).max().orElse(winEnd);
            }
            double sppH = (a.spp() != null && a.spp().inspectionEpoch().sppAvailable())
                    ? a.spp().inspectionEpoch().horizontalErrorM() : Double.NaN;

            summaries.add(new RegionalScanReport.StationAnomalySummary(
                    st,
                    in.distanceKm(),
                    level,
                    medianDelta,
                    missingFrac,
                    slipRate,
                    sppH,
                    degradedConsts,
                    winStart,
                    winEnd,
                    reasons.isEmpty() ? "Nominal" : String.join("; ", reasons)));
        }

        // Check regional escalation: >=2 non-nominal stations sharing a degraded constellation in overlapping 15-min windows
        List<RegionalScanReport.StationAnomalySummary> flagged = summaries.stream()
                .filter(s -> s.level() != RegionalScanReport.StationAnomalyLevel.NOMINAL)
                .toList();

        RegionalScanReport.RegionalEventSeverity severity = RegionalScanReport.RegionalEventSeverity.NOMINAL;
        EnumSet<GnssConstellation> sharedConsts = EnumSet.noneOf(GnssConstellation.class);
        List<RegionalScanReport.StationAnomalySummary> regionalCluster = new ArrayList<>();

        for (int i = 0; i < flagged.size(); i++) {
            RegionalScanReport.StationAnomalySummary a = flagged.get(i);
            for (int j = i + 1; j < flagged.size(); j++) {
                RegionalScanReport.StationAnomalySummary b = flagged.get(j);
                boolean overlaps = (a.windowStartMillis() - REGIONAL_OVERLAP_TOLERANCE_MILLIS) <= b.windowEndMillis()
                        && (b.windowStartMillis() - REGIONAL_OVERLAP_TOLERANCE_MILLIS) <= a.windowEndMillis();
                if (!overlaps) {
                    continue;
                }
                EnumSet<GnssConstellation> common = EnumSet.noneOf(GnssConstellation.class);
                for (GnssConstellation c : a.degradedConstellations()) {
                    if (b.degradedConstellations().contains(c)) {
                        common.add(c);
                    }
                }
                if (!common.isEmpty()) {
                    sharedConsts.addAll(common);
                    if (!regionalCluster.contains(a)) {
                        regionalCluster.add(a);
                    }
                    if (!regionalCluster.contains(b)) {
                        regionalCluster.add(b);
                    }
                }
            }
        }

        double centroidLat = Double.NaN;
        double centroidLon = Double.NaN;
        double radiusKm = 0.0;
        String diagnosis;

        if (regionalCluster.size() >= 2) {
            severity = RegionalScanReport.RegionalEventSeverity.POTENTIAL_INTERFERENCE;
            double sumLat = 0.0;
            double sumLon = 0.0;
            for (RegionalScanReport.StationAnomalySummary s : regionalCluster) {
                sumLat += s.station().latDeg();
                sumLon += s.station().lonDeg();
            }
            centroidLat = sumLat / regionalCluster.size();
            centroidLon = sumLon / regionalCluster.size();
            double maxDist = 0.0;
            for (RegionalScanReport.StationAnomalySummary s : regionalCluster) {
                maxDist = Math.max(maxDist, s.station().distanceKmTo(centroidLat, centroidLon));
            }
            radiusKm = Math.max(25.0, maxDist * 1.15);
            diagnosis = String.format(Locale.US,
                    "Potential RF interference: %d stations degraded on %s across %.0f km",
                    regionalCluster.size(), sharedConsts, radiusKm * 2.0);
        } else if (flagged.size() == 1 || (!flagged.isEmpty() && regionalCluster.isEmpty())) {
            severity = RegionalScanReport.RegionalEventSeverity.LOCAL_DEGRADATION;
            RegionalScanReport.StationAnomalySummary f = flagged.get(0);
            centroidLat = f.station().latDeg();
            centroidLon = f.station().lonDeg();
            radiusKm = 25.0;
            diagnosis = "Isolated local degradation at " + f.station().stationId() + " (" + f.reason()
                    + "); nearby stations nominal";
        } else {
            diagnosis = "All " + summaries.size() + " scanned stations nominal";
        }

        List<String> sortedPrns = new ArrayList<>(prnSet);
        Collections.sort(sortedPrns);
        return new RegionalScanReport(
                severity, diagnosis, sharedConsts, centroidLat, centroidLon, radiusKm,
                summaries, sortedPrns, matrix);
    }

    private static boolean detectSynchronizedFiveMinuteDrop(List<GnssStationAnalysis.Cn0TimeSample> samples) {
        if (samples == null || samples.size() < 6) {
            return false;
        }
        Map<String, List<GnssStationAnalysis.Cn0TimeSample>> highByPrn = new LinkedHashMap<>();
        List<Long> epochs = new ArrayList<>();
        Set<Long> seenEpochs = new LinkedHashSet<>();
        for (GnssStationAnalysis.Cn0TimeSample s : samples) {
            if (s.elevationDeg() >= MIN_ANOMALY_ELEVATION_DEG && Double.isFinite(s.deltaCn0DbHz())) {
                highByPrn.computeIfAbsent(s.prnId(), k -> new ArrayList<>()).add(s);
                if (seenEpochs.add(s.epochMillis())) {
                    epochs.add(s.epochMillis());
                }
            }
        }
        if (highByPrn.size() < 3 || epochs.size() < 2) {
            return false;
        }
        Map<String, Double> baselineByPrn = new LinkedHashMap<>();
        for (Map.Entry<String, List<GnssStationAnalysis.Cn0TimeSample>> e : highByPrn.entrySet()) {
            List<Double> deltas = e.getValue().stream().map(GnssStationAnalysis.Cn0TimeSample::deltaCn0DbHz).toList();
            baselineByPrn.put(e.getKey(), percentile(deltas, 90.0));
        }

        Collections.sort(epochs);
        for (long winStart : epochs) {
            long winEnd = winStart + SYNC_WINDOW_MILLIS;
            int droppedSvs = 0;
            for (Map.Entry<String, List<GnssStationAnalysis.Cn0TimeSample>> e : highByPrn.entrySet()) {
                double base = baselineByPrn.get(e.getKey());
                List<Double> inWin = new ArrayList<>();
                for (GnssStationAnalysis.Cn0TimeSample s : e.getValue()) {
                    if (s.epochMillis() >= winStart && s.epochMillis() <= winEnd) {
                        inWin.add(s.deltaCn0DbHz());
                    }
                }
                if (!inWin.isEmpty() && (base - percentile(inWin, 50.0)) >= SYNC_DROP_THRESHOLD_DB) {
                    droppedSvs++;
                }
            }
            if (droppedSvs >= 3) {
                return true;
            }
        }
        return false;
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

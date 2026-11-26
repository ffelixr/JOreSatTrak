package jsattrak.awareness.gnss.stations.analysis;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.stations.GnssReferenceStation;

/**
 * Single-station measurement quality, tracking completeness, C/N0 normalization, multipath,
 * and SPP verification snapshot.
 */
public record GnssStationAnalysis(
        GnssReferenceStation station,
        long inspectionEpochMillis,
        boolean epochOffsetFlagged,
        double elevationMaskDeg,
        int predictedCount,
        int trackedCount,
        int missingCount,
        double trackingCompletenessPct,
        double medianCn0DbHz,
        double medianDeltaCn0DbHz,
        int totalCycleSlips,
        int lliSlipCount,
        int gfSlipCount,
        double slipsPer100Obs,
        double mp1RmsMeters,
        double mp2RmsMeters,
        GnssSppSolver.SppSessionSummary spp,
        List<SatelliteMeasurementSummary> satellites,
        List<Cn0ElevationPoint> elevationScatter,
        List<Cn0TimeSample> timeSeries) {

    public enum TrackingState {
        TRACKED("Tracked"),
        MISSING("Missing"),
        BELOW_MASK_TRACKED("Below Mask / Unlisted");

        private final String displayLabel;

        TrackingState(String displayLabel) {
            this.displayLabel = displayLabel;
        }

        public String getDisplayLabel() {
            return displayLabel;
        }
    }

    public record SatelliteMeasurementSummary(
            String prnId,
            GnssConstellation constellation,
            TrackingState trackingState,
            double azimuthDeg,
            double elevationDeg,
            double medianCn0DbHz,
            double minCn0DbHz,
            double p10Cn0DbHz,
            double p90Cn0DbHz,
            double maxCn0DbHz,
            double medianDeltaCn0DbHz,
            int slipCount,
            double mp1RmsMeters,
            int obsCount) {

        public SatelliteMeasurementSummary(
                String prnId,
                GnssConstellation constellation,
                TrackingState trackingState,
                double azimuthDeg,
                double elevationDeg,
                double medianCn0DbHz,
                double minCn0DbHz,
                double medianDeltaCn0DbHz,
                int slipCount,
                double mp1RmsMeters,
                int obsCount) {
            this(prnId, constellation, trackingState, azimuthDeg, elevationDeg,
                    medianCn0DbHz, minCn0DbHz, minCn0DbHz, medianCn0DbHz, medianCn0DbHz,
                    medianDeltaCn0DbHz, slipCount, mp1RmsMeters, obsCount);
        }
    }

    public record Cn0ElevationPoint(
            String prnId,
            GnssConstellation constellation,
            double elevationDeg,
            double cn0DbHz,
            double deltaCn0DbHz,
            boolean hasSlip) {
    }

    public record Cn0TimeSample(
            long epochMillis,
            String prnId,
            GnssConstellation constellation,
            double elevationDeg,
            double cn0DbHz,
            double deltaCn0DbHz) {
    }

    public GnssStationAnalysis {
        satellites = satellites != null ? List.copyOf(satellites) : List.of();
        elevationScatter = elevationScatter != null ? List.copyOf(elevationScatter) : List.of();
        timeSeries = timeSeries != null ? List.copyOf(timeSeries) : List.of();
    }

    public Map<String, SatelliteMeasurementSummary> satelliteMap() {
        Map<String, SatelliteMeasurementSummary> m = new LinkedHashMap<>();
        for (SatelliteMeasurementSummary s : satellites) {
            m.put(s.prnId(), s);
        }
        return Collections.unmodifiableMap(m);
    }
}

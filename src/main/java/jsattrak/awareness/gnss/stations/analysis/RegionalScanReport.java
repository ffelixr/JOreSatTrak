package jsattrak.awareness.gnss.stations.analysis;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.stations.GnssReferenceStation;

/**
 * Multi-station regional C/N0 anomaly and potential RF interference assessment report.
 */
public record RegionalScanReport(
        RegionalEventSeverity severity,
        String diagnosis,
        Set<GnssConstellation> affectedConstellations,
        double centroidLatDeg,
        double centroidLonDeg,
        double radiusKm,
        List<StationAnomalySummary> stations,
        List<String> prnColumns,
        Map<String, Map<String, Double>> deltaCn0Matrix) {

    public enum RegionalEventSeverity {
        NOMINAL("Nominal"),
        LOCAL_DEGRADATION("Local Degradation"),
        POTENTIAL_INTERFERENCE("Potential Regional Interference");

        private final String displayLabel;

        RegionalEventSeverity(String displayLabel) {
            this.displayLabel = displayLabel;
        }

        public String getDisplayLabel() {
            return displayLabel;
        }
    }

    public enum StationAnomalyLevel {
        NOMINAL("Nominal"),
        WARNING("Warning"),
        ALERT("Alert");

        private final String displayLabel;

        StationAnomalyLevel(String displayLabel) {
            this.displayLabel = displayLabel;
        }

        public String getDisplayLabel() {
            return displayLabel;
        }
    }

    public record StationAnomalySummary(
            GnssReferenceStation station,
            double distanceKm,
            StationAnomalyLevel level,
            double medianDeltaCn0DbHz,
            double missingFraction,
            double slipsPer100Obs,
            double sppHorizErrorM,
            Set<GnssConstellation> degradedConstellations,
            long windowStartMillis,
            long windowEndMillis,
            String reason) {
    }

    public RegionalScanReport {
        severity = severity != null ? severity : RegionalEventSeverity.NOMINAL;
        diagnosis = diagnosis != null ? diagnosis : "";
        affectedConstellations = affectedConstellations != null ? Set.copyOf(affectedConstellations) : Set.of();
        stations = stations != null ? List.copyOf(stations) : List.of();
        prnColumns = prnColumns != null ? List.copyOf(prnColumns) : List.of();
        if (deltaCn0Matrix != null) {
            Map<String, Map<String, Double>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Double>> e : deltaCn0Matrix.entrySet()) {
                copy.put(e.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(e.getValue())));
            }
            deltaCn0Matrix = Collections.unmodifiableMap(copy);
        } else {
            deltaCn0Matrix = Map.of();
        }
    }

    public static RegionalScanReport empty() {
        return new RegionalScanReport(
                RegionalEventSeverity.NOMINAL,
                "No regional scan run yet",
                Set.of(), Double.NaN, Double.NaN, 0.0,
                List.of(), List.of(), Map.of());
    }
}

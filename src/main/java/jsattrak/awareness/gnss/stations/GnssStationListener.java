package jsattrak.awareness.gnss.stations;

import jsattrak.awareness.gnss.stations.analysis.GnssStationAnalysis;
import jsattrak.awareness.gnss.stations.analysis.RegionalScanReport;

/**
 * Listener interface for Layer-3 GNSS reference station catalog, inspection, regional anomaly,
 * and map visibility updates.
 */
public interface GnssStationListener {

    default void onStationCatalogChanged() {
    }

    default void onSelectedStationChanged(String stationId) {
    }

    default void onInspectionUpdated(GnssStationAnalysis analysis) {
    }

    default void onRegionalScanUpdated(RegionalScanReport report) {
    }

    default void onStationStatusMessage(String message, boolean isWarning) {
    }

    default void onMapOverlayStateChanged(boolean enabled) {
    }
}

package jsattrak.awareness.gnss.geometry;

/**
 * Listener notified when GNSS geometry parameters, observer location, pick mode, or heatmaps change.
 */
public interface GnssGeometryListener {

    default void onGeometryDataChanged() {
    }

    default void onHeatmapUpdated(GnssDopGrid grid) {
    }

    default void onObserverChanged(GnssObserverPosition observer) {
    }

    default void onPickOnMapModeChanged(boolean pickMode) {
    }

    default void onGeometryStatusMessage(String message, boolean warning) {
    }
}

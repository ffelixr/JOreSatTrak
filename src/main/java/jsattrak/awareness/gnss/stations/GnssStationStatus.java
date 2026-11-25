package jsattrak.awareness.gnss.stations;

/**
 * Operational and data-latency status for a GNSS reference station.
 */
public enum GnssStationStatus {
    ONLINE("Online"),
    DELAYED("Delayed"),
    STALE("Stale"),
    OFFLINE("Offline"),
    UNKNOWN("Unknown");

    private final String displayLabel;

    GnssStationStatus(String displayLabel) {
        this.displayLabel = displayLabel;
    }

    public String getDisplayLabel() {
        return displayLabel;
    }

    /**
     * Classifies station status from archive latency in minutes and upstream operational flag.
     *
     * @param latencyMinutes archive latency in minutes ({@code < 0} when unknown)
     * @param active whether upstream catalog marks the station active
     * @return classified {@link GnssStationStatus}
     */
    public static GnssStationStatus classifyFromLatencyMinutes(long latencyMinutes, boolean active) {
        if (!active) {
            return OFFLINE;
        }
        if (latencyMinutes < 0L) {
            return UNKNOWN;
        }
        if (latencyMinutes < 180L) {
            return ONLINE;
        }
        if (latencyMinutes <= 1440L) {
            return DELAYED;
        }
        return STALE;
    }
}

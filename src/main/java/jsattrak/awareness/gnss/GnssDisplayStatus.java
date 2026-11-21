package jsattrak.awareness.gnss;

/**
 * Derived Layer-1 display status for a GNSS satellite.
 */
public enum GnssDisplayStatus {
    UNAVAILABLE(0, "Unavailable"),
    TESTING(1, "Testing"),
    NO_STATUS_DATA(2, "No status data"),
    OPERATIONAL(3, "Operational");

    private final int sortRank;
    private final String displayLabel;

    GnssDisplayStatus(int sortRank, String displayLabel) {
        this.sortRank = sortRank;
        this.displayLabel = displayLabel;
    }

    public int getSortRank() {
        return sortRank;
    }

    public String getDisplayLabel() {
        return displayLabel;
    }
}

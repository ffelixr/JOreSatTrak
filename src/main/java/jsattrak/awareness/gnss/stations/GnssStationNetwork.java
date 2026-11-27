package jsattrak.awareness.gnss.stations;

import java.util.Locale;

/**
 * GNSS reference station networks supported by Layer 3.
 */
public enum GnssStationNetwork {
    IGS("IGS", "IGS"),
    EUREF("EUREF", "EUREF EPN"),
    NOAA_CORS("NOAA", "NOAA CORS"),
    LOCAL("LOC", "Local RINEX");

    private final String shortLabel;
    private final String displayName;

    GnssStationNetwork(String shortLabel, String displayName) {
        this.shortLabel = shortLabel;
        this.displayName = displayName;
    }

    public String getShortLabel() {
        return shortLabel;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static GnssStationNetwork fromScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return IGS;
        }
        String norm = scope.trim().toUpperCase(Locale.US);
        if (norm.contains("EUREF") || norm.contains("EPN")) {
            return EUREF;
        }
        if (norm.contains("NOAA") || norm.contains("CORS")) {
            return NOAA_CORS;
        }
        if (norm.contains("LOCAL")) {
            return LOCAL;
        }
        return IGS;
    }
}

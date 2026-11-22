package jsattrak.awareness.gnss.geometry;

import jsattrak.awareness.gnss.GnssConstellation;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Lightweight time-series sample for the 24-hour DOP and satellite visibility chart.
 */
public record GnssDopSample(
        long epochMillis,
        boolean fixAvailable,
        double gdop,
        double pdop,
        double hdop,
        double vdop,
        double tdop,
        int visibleCount,
        Map<GnssConstellation, Integer> visibleByConstellation) {

    public GnssDopSample {
        EnumMap<GnssConstellation, Integer> copy = new EnumMap<>(GnssConstellation.class);
        if (visibleByConstellation != null) {
            copy.putAll(visibleByConstellation);
        }
        visibleByConstellation = Collections.unmodifiableMap(copy);
    }
}

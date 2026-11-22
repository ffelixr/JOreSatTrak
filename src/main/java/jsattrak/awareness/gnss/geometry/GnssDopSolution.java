package jsattrak.awareness.gnss.geometry;

import jsattrak.awareness.gnss.GnssConstellation;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Instantaneous point-observer satellite visibility and DOP solution.
 */
public record GnssDopSolution(
        long epochMillis,
        GnssObserverPosition observer,
        double elevationMaskDeg,
        boolean fixAvailable,
        double gdop,
        double pdop,
        double hdop,
        double vdop,
        double tdop,
        int visibleCount,
        int usedCount,
        Map<GnssConstellation, Integer> visibleByConstellation,
        List<SatelliteSkyPoint> skyPoints,
        GnssOrbitSource.Summary orbitSource) {

    public GnssDopSolution {
        EnumMap<GnssConstellation, Integer> copy = new EnumMap<>(GnssConstellation.class);
        if (visibleByConstellation != null) {
            copy.putAll(visibleByConstellation);
        }
        visibleByConstellation = Collections.unmodifiableMap(copy);
        skyPoints = skyPoints == null ? List.of() : List.copyOf(skyPoints);
    }

    public static GnssDopSolution empty(
            long epochMillis,
            GnssObserverPosition observer,
            double elevationMaskDeg) {
        return new GnssDopSolution(
                epochMillis,
                observer,
                elevationMaskDeg,
                false,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                0,
                0,
                Map.of(),
                List.of(),
                new GnssOrbitSource.Summary(GnssOrbitSource.NONE, 0, 0, 0, false, ""));
    }
}

package jsattrak.awareness.gnss;

import java.util.Arrays;
import java.util.List;

/**
 * Supported GNSS constellations and planned regional augmentation extension points.
 */
public enum GnssConstellation {
    GPS('G', "GPS", "US", MarkerShape.CIRCLE, true),
    GALILEO('E', "Galileo", "EU", MarkerShape.DIAMOND, true),
    GLONASS('R', "GLONASS", "RU", MarkerShape.TRIANGLE, true),
    BEIDOU('C', "BeiDou", "CN", MarkerShape.SQUARE, true),
    SBAS('S', "SBAS", "Regional", MarkerShape.OPEN_DIAMOND, false);

    public enum MarkerShape {
        CIRCLE,
        DIAMOND,
        TRIANGLE,
        SQUARE,
        OPEN_DIAMOND
    }

    private final char prefix;
    private final String displayName;
    private final String regionCode;
    private final MarkerShape markerShape;
    private final boolean layer1Active;

    GnssConstellation(
            char prefix,
            String displayName,
            String regionCode,
            MarkerShape markerShape,
            boolean layer1Active) {
        this.prefix = prefix;
        this.displayName = displayName;
        this.regionCode = regionCode;
        this.markerShape = markerShape;
        this.layer1Active = layer1Active;
    }

    public char getPrefix() {
        return prefix;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getRegionCode() {
        return regionCode;
    }

    public MarkerShape getMarkerShape() {
        return markerShape;
    }

    public boolean isLayer1Active() {
        return layer1Active;
    }

    /**
     * Resolves a RINEX satellite system prefix character ({@code G, E, R, C, S}), or {@code null}
     * if not supported by this enum.
     */
    public static GnssConstellation fromPrefix(char prefix) {
        char upper = Character.toUpperCase(prefix);
        for (GnssConstellation c : values()) {
            if (c.prefix == upper) {
                return c;
            }
        }
        return null;
    }

    /**
     * Returns the constellations actively tracked in Layer 1 (excluding future SBAS).
     */
    public static List<GnssConstellation> layer1ActiveValues() {
        return Arrays.stream(values())
                .filter(GnssConstellation::isLayer1Active)
                .toList();
    }
}

package jsattrak.awareness.gnss.geometry;

/**
 * Immutable geodetic observer position used for local DOP and skyplot computation.
 */
public record GnssObserverPosition(
        String label,
        double latDeg,
        double lonDeg,
        double altMeters) {

    public GnssObserverPosition {
        if (!Double.isFinite(latDeg) || !Double.isFinite(lonDeg) || !Double.isFinite(altMeters)) {
            throw new IllegalArgumentException("Observer coordinates must be finite numbers");
        }
        label = (label == null || label.isBlank()) ? "Custom Coordinates" : label.trim();
        latDeg = Math.max(-90.0, Math.min(90.0, latDeg));
        lonDeg = Math.max(-180.0, Math.min(180.0, lonDeg));
    }
}

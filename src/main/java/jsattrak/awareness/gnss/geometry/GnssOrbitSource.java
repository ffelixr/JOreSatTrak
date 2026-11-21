package jsattrak.awareness.gnss.geometry;

/**
 * Provenance classification of ephemeris/orbit data used in GNSS geometry calculations.
 */
public enum GnssOrbitSource {
    SP3("SP3"),
    BRDC("BRDC"),
    MIXED("Mixed"),
    TLE_FALLBACK("TLE fallback"),
    NONE("No orbits");

    private final String displayLabel;

    GnssOrbitSource(String displayLabel) {
        this.displayLabel = displayLabel;
    }

    public String getDisplayLabel() {
        return displayLabel;
    }

    /**
     * Summary of orbit provenance across all resolved satellites for an epoch or grid.
     */
    public record Summary(
            GnssOrbitSource primary,
            int sp3Count,
            int brdcCount,
            int tleCount,
            boolean noHealthFlags,
            String fileLabel) {

        public static Summary fromCounts(
                int sp3Count,
                int brdcCount,
                int tleCount,
                boolean noHealthFlags,
                String fileLabel) {
            int nonZeroKinds = (sp3Count > 0 ? 1 : 0) + (brdcCount > 0 ? 1 : 0) + (tleCount > 0 ? 1 : 0);
            GnssOrbitSource primary;
            if (nonZeroKinds == 0) {
                primary = GnssOrbitSource.NONE;
            } else if (nonZeroKinds > 1) {
                primary = GnssOrbitSource.MIXED;
            } else if (sp3Count > 0) {
                primary = GnssOrbitSource.SP3;
            } else if (brdcCount > 0) {
                primary = GnssOrbitSource.BRDC;
            } else {
                primary = GnssOrbitSource.TLE_FALLBACK;
            }
            return new Summary(primary, sp3Count, brdcCount, tleCount, noHealthFlags, fileLabel);
        }

        public String getBadgeText() {
            String base = primary.getDisplayLabel();
            if (primary == GnssOrbitSource.SP3 && noHealthFlags) {
                return "SP3 (no health flags)";
            }
            if (primary == GnssOrbitSource.MIXED && sp3Count == 0 && brdcCount > 0 && tleCount > 0) {
                return "BRDC + TLE fallback";
            }
            return base;
        }

        public String getTooltipText() {
            StringBuilder sb = new StringBuilder();
            sb.append("Orbit source: ").append(getBadgeText());
            sb.append(" (SP3=").append(sp3Count)
                    .append(", BRDC=").append(brdcCount)
                    .append(", TLE=").append(tleCount).append(")");
            if (fileLabel != null && !fileLabel.isBlank()) {
                sb.append(" | ").append(fileLabel);
            }
            if (noHealthFlags) {
                sb.append(" | Note: SP3 file contains no satellite health flags");
            }
            return sb.toString();
        }
    }
}

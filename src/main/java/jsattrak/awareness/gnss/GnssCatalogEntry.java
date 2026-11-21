package jsattrak.awareness.gnss;

/**
 * Immutable GNSS satellite catalogue identity record.
 */
public record GnssCatalogEntry(
        String prnId,
        GnssConstellation constellation,
        int prnNumber,
        String svn,
        int noradId,
        String cosparId,
        String blockType,
        GnssOrbitClass orbitClass,
        String plane,
        String slot,
        String launchDateIso) {
}

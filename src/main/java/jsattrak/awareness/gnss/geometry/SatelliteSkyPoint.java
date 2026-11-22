package jsattrak.awareness.gnss.geometry;

import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.GnssDisplayStatus;

/**
 * Single satellite azimuth/elevation state relative to an observer for skyplot and table rendering.
 */
public record SatelliteSkyPoint(
        String prnId,
        GnssConstellation constellation,
        GnssDisplayStatus status,
        double azimuthDeg,
        double elevationDeg,
        boolean aboveMask,
        boolean usedInDop,
        GnssOrbitSource orbitSource) {
}

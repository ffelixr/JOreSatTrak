package jsattrak.awareness.gnss;

import jsattrak.utilities.TLE;

/**
 * Public Layer-2-ready snapshot combining a satellite's catalog identity, derived Layer-1 status,
 * matched TLE, and propagated geodetic position at the queried simulation epoch.
 */
public record GnssSatelliteState(
        GnssCatalogEntry catalog,
        GnssStatusResult statusResult,
        TLE tle,
        GnssOrbitPropagator.GeodeticPosition position) {

    public boolean hasOrbitData() {
        return tle != null && position != null;
    }

    public boolean isOperational() {
        return statusResult != null && statusResult.status() == GnssDisplayStatus.OPERATIONAL;
    }
}

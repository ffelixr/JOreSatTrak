package jsattrak.awareness.gnss.geometry;

import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.GnssDisplayStatus;
import org.hipparchus.linear.MatrixUtils;
import org.hipparchus.linear.RealMatrix;
import org.hipparchus.linear.SingularValueDecomposition;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * Pure WGS-84 ECEF/ENU satellite visibility and 4x4 normal-matrix DOP solver.
 */
public final class GnssDopCalculator {

    public static final double WGS84_A = 6378137.0;
    public static final double WGS84_F = 1.0 / 298.257223563;
    public static final double WGS84_E2 = WGS84_F * (2.0 - WGS84_F);

    private static final double MIN_SINGULAR_VALUE = 1e-6;
    private static final double MAX_CONDITION_NUMBER = 1e8;

    private GnssDopCalculator() {
    }

    /**
     * Pre-resolved ITRF/ECEF candidate satellite position and operational health status.
     */
    public record CandidateSatellite(
            String prnId,
            GnssConstellation constellation,
            GnssDisplayStatus status,
            GnssOrbitSource orbitSource,
            double satX,
            double satY,
            double satZ) {
    }

    /**
     * Converts geodetic coordinates (deg, deg, meters above WGS-84 ellipsoid) to ECEF XYZ (meters).
     */
    public static double[] geodeticToEcef(double latDeg, double lonDeg, double altMeters) {
        double lat = Math.toRadians(latDeg);
        double lon = Math.toRadians(lonDeg);
        double sinLat = Math.sin(lat);
        double cosLat = Math.cos(lat);
        double sinLon = Math.sin(lon);
        double cosLon = Math.cos(lon);
        double n = WGS84_A / Math.sqrt(1.0 - WGS84_E2 * sinLat * sinLat);
        double x = (n + altMeters) * cosLat * cosLon;
        double y = (n + altMeters) * cosLat * sinLon;
        double z = (n * (1.0 - WGS84_E2) + altMeters) * sinLat;
        return new double[]{x, y, z};
    }

    /**
     * Computes topocentric azimuth, elevation, and local ENU line-of-sight unit vector
     * {@code [azDeg, elDeg, uE, uN, uU]} from observer geodetic coordinates to ECEF satellite coordinates.
     */
    public static double[] computeAzEl(
            double obsLatDeg,
            double obsLonDeg,
            double obsAltMeters,
            double satX,
            double satY,
            double satZ) {
        double[] obsEcef = geodeticToEcef(obsLatDeg, obsLonDeg, obsAltMeters);
        double lat = Math.toRadians(obsLatDeg);
        double lon = Math.toRadians(obsLonDeg);
        double sinLat = Math.sin(lat);
        double cosLat = Math.cos(lat);
        double sinLon = Math.sin(lon);
        double cosLon = Math.cos(lon);

        double dx = satX - obsEcef[0];
        double dy = satY - obsEcef[1];
        double dz = satZ - obsEcef[2];
        double range = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (range < 1.0) {
            return new double[]{0.0, 90.0, 0.0, 0.0, 1.0};
        }

        double e = -sinLon * dx + cosLon * dy;
        double n = -sinLat * cosLon * dx - sinLat * sinLon * dy + cosLat * dz;
        double u = cosLat * cosLon * dx + cosLat * sinLon * dy + sinLat * dz;

        double horiz = Math.hypot(e, n);
        double elDeg = Math.toDegrees(Math.atan2(u, horiz));
        double azDeg = Math.toDegrees(Math.atan2(e, n));
        if (azDeg < 0.0) {
            azDeg += 360.0;
        }
        return new double[]{azDeg, elDeg, e / range, n / range, u / range};
    }

    /**
     * Evaluates instantaneous visibility, per-constellation distribution, skyplot points, and DOP values
     * for a single observer and pre-resolved candidate satellites.
     */
    public static GnssDopSolution computePointDop(
            long epochMillis,
            GnssObserverPosition observer,
            double elevationMaskDeg,
            boolean includeNonOperational,
            List<CandidateSatellite> satellites) {
        if (observer == null || satellites == null || satellites.isEmpty()) {
            return GnssDopSolution.empty(epochMillis, observer, elevationMaskDeg);
        }

        double[] obsEcef = geodeticToEcef(observer.latDeg(), observer.lonDeg(), observer.altMeters());
        double lat = Math.toRadians(observer.latDeg());
        double lon = Math.toRadians(observer.lonDeg());
        double sinLat = Math.sin(lat);
        double cosLat = Math.cos(lat);
        double sinLon = Math.sin(lon);
        double cosLon = Math.cos(lon);

        int maxSats = satellites.size();
        double[] uE = new double[maxSats];
        double[] uN = new double[maxSats];
        double[] uU = new double[maxSats];

        EnumMap<GnssConstellation, Integer> visibleByConst = new EnumMap<>(GnssConstellation.class);
        for (GnssConstellation c : GnssConstellation.layer1ActiveValues()) {
            visibleByConst.put(c, 0);
        }

        List<SatelliteSkyPoint> skyPoints = new ArrayList<>(maxSats);
        int visibleCount = 0;
        int usedCount = 0;
        int sp3Used = 0;
        int brdcUsed = 0;
        int tleUsed = 0;

        for (CandidateSatellite sat : satellites) {
            if (!Double.isFinite(sat.satX()) || !Double.isFinite(sat.satY()) || !Double.isFinite(sat.satZ())) {
                continue;
            }
            double dx = sat.satX() - obsEcef[0];
            double dy = sat.satY() - obsEcef[1];
            double dz = sat.satZ() - obsEcef[2];
            double range = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (range < 1.0) {
                continue;
            }

            double e = -sinLon * dx + cosLon * dy;
            double n = -sinLat * cosLon * dx - sinLat * sinLon * dy + cosLat * dz;
            double u = cosLat * cosLon * dx + cosLat * sinLon * dy + sinLat * dz;

            double elDeg = Math.toDegrees(Math.atan2(u, Math.hypot(e, n)));
            double azDeg = Math.toDegrees(Math.atan2(e, n));
            if (azDeg < 0.0) {
                azDeg += 360.0;
            }

            boolean aboveMask = elDeg >= elevationMaskDeg;
            boolean operationalPass = includeNonOperational || sat.status() == GnssDisplayStatus.OPERATIONAL;
            boolean usedInDop = aboveMask && operationalPass;

            if (aboveMask) {
                visibleCount++;
                if (sat.constellation() != null) {
                    visibleByConst.merge(sat.constellation(), 1, Integer::sum);
                }
            }
            if (usedInDop) {
                uE[usedCount] = e / range;
                uN[usedCount] = n / range;
                uU[usedCount] = u / range;
                usedCount++;
                if (sat.orbitSource() == GnssOrbitSource.SP3) {
                    sp3Used++;
                } else if (sat.orbitSource() == GnssOrbitSource.BRDC) {
                    brdcUsed++;
                } else if (sat.orbitSource() == GnssOrbitSource.TLE_FALLBACK) {
                    tleUsed++;
                }
            }

            skyPoints.add(new SatelliteSkyPoint(
                    sat.prnId(),
                    sat.constellation(),
                    sat.status(),
                    azDeg,
                    elDeg,
                    aboveMask,
                    usedInDop,
                    sat.orbitSource()));
        }

        double[] dops = computeRawDopFromUnitVectors(uE, uN, uU, usedCount);
        boolean fixAvailable = dops != null;
        double gdop = fixAvailable ? dops[0] : Double.NaN;
        double pdop = fixAvailable ? dops[1] : Double.NaN;
        double hdop = fixAvailable ? dops[2] : Double.NaN;
        double vdop = fixAvailable ? dops[3] : Double.NaN;
        double tdop = fixAvailable ? dops[4] : Double.NaN;

        GnssOrbitSource.Summary orbitSummary = GnssOrbitSource.Summary.fromCounts(
                sp3Used, brdcUsed, tleUsed, false, "");

        return new GnssDopSolution(
                epochMillis,
                observer,
                elevationMaskDeg,
                fixAvailable,
                gdop,
                pdop,
                hdop,
                vdop,
                tdop,
                visibleCount,
                usedCount,
                visibleByConst,
                skyPoints,
                orbitSummary);
    }

    /**
     * Solves {@code M = H^T H} and {@code Q = M^{-1}} from ENU line-of-sight unit vectors.
     *
     * @return {@code [gdop, pdop, hdop, vdop, tdop]}, or {@code null} when fewer than 4 satellites
     *         are provided or geometry is singular / ill-conditioned.
     */
    public static double[] computeRawDopFromUnitVectors(
            double[] uE,
            double[] uN,
            double[] uU,
            int count) {
        if (count < 4 || uE == null || uN == null || uU == null
                || uE.length < count || uN.length < count || uU.length < count) {
            return null;
        }

        double[][] m = new double[4][4];
        for (int i = 0; i < count; i++) {
            double e = uE[i];
            double n = uN[i];
            double u = uU[i];
            m[0][0] += e * e;
            m[0][1] += e * n;
            m[0][2] += e * u;
            m[0][3] += e;

            m[1][1] += n * n;
            m[1][2] += n * u;
            m[1][3] += n;

            m[2][2] += u * u;
            m[2][3] += u;
        }
        m[3][3] = count;
        m[1][0] = m[0][1];
        m[2][0] = m[0][2];
        m[2][1] = m[1][2];
        m[3][0] = m[0][3];
        m[3][1] = m[1][3];
        m[3][2] = m[2][3];

        RealMatrix normalMatrix = MatrixUtils.createRealMatrix(m);
        SingularValueDecomposition svd = new SingularValueDecomposition(normalMatrix);
        double[] sv = svd.getSingularValues();
        double maxSv = sv[0];
        double minSv = sv[3];
        if (!Double.isFinite(minSv) || minSv < MIN_SINGULAR_VALUE) {
            return null;
        }
        if (maxSv / minSv > MAX_CONDITION_NUMBER) {
            return null;
        }

        RealMatrix q = svd.getSolver().getInverse();
        double q00 = q.getEntry(0, 0);
        double q11 = q.getEntry(1, 1);
        double q22 = q.getEntry(2, 2);
        double q33 = q.getEntry(3, 3);
        if (q00 <= 0.0 || q11 <= 0.0 || q22 <= 0.0 || q33 <= 0.0) {
            return null;
        }

        double hdop = Math.sqrt(q00 + q11);
        double vdop = Math.sqrt(q22);
        double pdop = Math.sqrt(q00 + q11 + q22);
        double tdop = Math.sqrt(q33);
        double gdop = Math.sqrt(q00 + q11 + q22 + q33);
        if (!Double.isFinite(gdop) || !Double.isFinite(pdop)) {
            return null;
        }
        return new double[]{gdop, pdop, hdop, vdop, tdop};
    }
}

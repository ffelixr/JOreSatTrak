package jsattrak.awareness.gnss.geometry;

import jsattrak.awareness.gnss.GnssDisplayStatus;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Vectorized global lat/lon GNSS positioning-quality grid ({@code 5°} or {@code 2.5°})
 * rasterizable to an RGBA {@link BufferedImage} for 2D, 2D Polar, and 3D map overlays.
 */
public final class GnssDopGrid {

    private final long epochMillis;
    private final double resolutionDeg;
    private final double elevationMaskDeg;
    private final GnssDopMetric metric;
    private final GnssOrbitSource.Summary orbitSource;
    private final int latRows;
    private final int lonCols;
    private final double[] values; // row-major [r * lonCols + c], lat descending (+90 -> -90), lon ascending (-180 -> +180)

    private GnssDopGrid(
            long epochMillis,
            double resolutionDeg,
            double elevationMaskDeg,
            GnssDopMetric metric,
            GnssOrbitSource.Summary orbitSource,
            int latRows,
            int lonCols,
            double[] values) {
        this.epochMillis = epochMillis;
        this.resolutionDeg = resolutionDeg;
        this.elevationMaskDeg = elevationMaskDeg;
        this.metric = metric;
        this.orbitSource = orbitSource;
        this.latRows = latRows;
        this.lonCols = lonCols;
        this.values = values;
    }

    /**
     * Evaluates the global positioning quality grid at {@code alt = 0 m} with zero per-cell
     * satellite propagation overhead.
     */
    public static GnssDopGrid compute(
            long epochMillis,
            double resolutionDeg,
            double elevMaskDeg,
            boolean includeNonOp,
            GnssDopMetric metric,
            List<GnssDopCalculator.CandidateSatellite> sats,
            GnssOrbitSource.Summary orbitSource) {
        double res = resolutionDeg > 0.0 ? resolutionDeg : 5.0;
        int latRows = Math.max(1, (int) Math.round(180.0 / res));
        int lonCols = Math.max(1, (int) Math.round(360.0 / res));
        GnssDopMetric effectiveMetric = metric != null ? metric : GnssDopMetric.PDOP;
        double[] values = new double[latRows * lonCols];

        // Pre-extract satellite ECEF positions once into primitive arrays; track operational flag
        // separately so VISIBLE_SVS counts all above-mask SVs (matching GnssDopCalculator.visibleCount)
        // while DOP computations filter non-operational SVs when includeNonOp == false.
        int satCapacity = sats != null ? sats.size() : 0;
        double[] satX = new double[satCapacity];
        double[] satY = new double[satCapacity];
        double[] satZ = new double[satCapacity];
        boolean[] satOp = new boolean[satCapacity];
        int satCount = 0;
        if (sats != null) {
            for (GnssDopCalculator.CandidateSatellite sat : sats) {
                if (sat == null) {
                    continue;
                }
                if (!Double.isFinite(sat.satX()) || !Double.isFinite(sat.satY()) || !Double.isFinite(sat.satZ())) {
                    continue;
                }
                satX[satCount] = sat.satX();
                satY[satCount] = sat.satY();
                satZ[satCount] = sat.satZ();
                satOp[satCount] = includeNonOp || sat.status() == GnssDisplayStatus.OPERATIONAL;
                satCount++;
            }
        }

        double sinMask = Math.sin(Math.toRadians(elevMaskDeg));
        double[] uE = new double[satCount];
        double[] uN = new double[satCount];
        double[] uU = new double[satCount];

        // Precompute trig over longitude columns once
        double[] cosLon = new double[lonCols];
        double[] sinLon = new double[lonCols];
        for (int c = 0; c < lonCols; c++) {
            double lonRad = Math.toRadians(-180.0 + (c + 0.5) * res);
            cosLon[c] = Math.cos(lonRad);
            sinLon[c] = Math.sin(lonRad);
        }

        for (int r = 0; r < latRows; r++) {
            double latDeg = 90.0 - (r + 0.5) * res;
            double latRad = Math.toRadians(latDeg);
            double sinLat = Math.sin(latRad);
            double cosLat = Math.cos(latRad);
            double primeVert = GnssDopCalculator.WGS84_A
                    / Math.sqrt(1.0 - GnssDopCalculator.WGS84_E2 * sinLat * sinLat);
            double obsZ = primeVert * (1.0 - GnssDopCalculator.WGS84_E2) * sinLat;

            for (int c = 0; c < lonCols; c++) {
                double obsX = primeVert * cosLat * cosLon[c];
                double obsY = primeVert * cosLat * sinLon[c];
                double sLon = sinLon[c];
                double cLon = cosLon[c];

                int visAll = 0;
                int used = 0;
                for (int i = 0; i < satCount; i++) {
                    double dx = satX[i] - obsX;
                    double dy = satY[i] - obsY;
                    double dz = satZ[i] - obsZ;
                    double rangeSq = dx * dx + dy * dy + dz * dz;
                    if (rangeSq < 1.0) {
                        continue;
                    }
                    double range = Math.sqrt(rangeSq);
                    double u = cosLat * cLon * dx + cosLat * sLon * dy + sinLat * dz;
                    double sinEl = u / range;
                    if (sinEl < sinMask) {
                        continue;
                    }
                    visAll++;
                    if (satOp[i]) {
                        double e = -sLon * dx + cLon * dy;
                        double n = -sinLat * cLon * dx - sinLat * sLon * dy + cosLat * dz;
                        uE[used] = e / range;
                        uN[used] = n / range;
                        uU[used] = sinEl;
                        used++;
                    }
                }

                int idx = r * lonCols + c;
                if (effectiveMetric == GnssDopMetric.VISIBLE_SVS) {
                    values[idx] = visAll;
                } else {
                    double[] dops = GnssDopCalculator.computeRawDopFromUnitVectors(uE, uN, uU, used);
                    if (dops == null) {
                        values[idx] = Double.NaN;
                    } else {
                        values[idx] = switch (effectiveMetric) {
                            case GDOP -> dops[0];
                            case PDOP -> dops[1];
                            case HDOP -> dops[2];
                            case VDOP -> dops[3];
                            case TDOP -> dops[4];
                            case VISIBLE_SVS -> visAll;
                        };
                    }
                }
            }
        }

        return new GnssDopGrid(epochMillis, res, elevMaskDeg, effectiveMetric, orbitSource, latRows, lonCols, values);
    }

    /**
     * Rasterizes this grid into an ARGB {@link BufferedImage} ({@code lonCols x latRows}) with
     * row 0 at North (+90°) and col 0 at -180°.
     */
    public BufferedImage renderRgbaImage(double opacity) {
        int alpha = clampAlpha((int) Math.round(Math.max(0.0, Math.min(1.0, opacity)) * 255.0));
        BufferedImage img = new BufferedImage(lonCols, latRows, BufferedImage.TYPE_INT_ARGB);
        int[] argbRow = new int[lonCols];
        boolean isCount = metric == GnssDopMetric.VISIBLE_SVS;
        for (int r = 0; r < latRows; r++) {
            int offset = r * lonCols;
            for (int c = 0; c < lonCols; c++) {
                double val = values[offset + c];
                argbRow[c] = isCount
                        ? argbForVisibleCount((int) Math.round(val), alpha)
                        : argbForDop(val, alpha);
            }
            img.setRGB(0, r, lonCols, 1, argbRow, 0, lonCols);
        }
        return img;
    }

    /**
     * Looks up the grid value at geodetic {@code (latDeg, lonDeg)}.
     */
    public double getValueAt(double latDeg, double lonDeg) {
        double wrappedLon = ((lonDeg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        int r = Math.max(0, Math.min(latRows - 1, (int) Math.floor((90.0 - latDeg) / resolutionDeg)));
        int c = Math.max(0, Math.min(lonCols - 1, (int) Math.floor((wrappedLon + 180.0) / resolutionDeg)));
        return values[r * lonCols + c];
    }

    /**
     * Packed ARGB integer for a DOP value (zero per-pixel {@link Color} allocations).
     */
    public static int argbForDop(double dop, int alpha) {
        int a = clampAlpha(alpha) << 24;
        if (!Double.isFinite(dop) || dop <= 0.0) {
            return a | 0x6B2D5C; // No Fix
        }
        if (dop <= 1.5) {
            return a | 0x1B8A5A; // Excellent
        }
        if (dop <= 2.5) {
            return a | 0x52C41A; // Good
        }
        if (dop <= 4.0) {
            return a | 0xFADB14; // Moderate
        }
        if (dop <= 6.0) {
            return a | 0xFA8C16; // Fair
        }
        return a | 0xE5484D; // Poor (>6.0)
    }

    /**
     * Packed ARGB integer for a visible satellite count (zero per-pixel {@link Color} allocations).
     */
    public static int argbForVisibleCount(int count, int alpha) {
        int a = clampAlpha(alpha) << 24;
        if (count < 4) {
            return a | 0xE5484D; // No Fix (<4)
        }
        if (count <= 7) {
            return a | 0xFA8C16; // Minimal (4-7)
        }
        if (count <= 15) {
            return a | 0x3FBF6A; // Good (8-15)
        }
        return a | 0x00B5D8; // Strong (>=16)
    }

    /**
     * Maps a DOP value to its semantic quality color (with magenta {@code #6B2D5C} for No Fix).
     */
    public static Color colorForDop(double dop, int alpha) {
        return new Color(argbForDop(dop, alpha), true);
    }

    /**
     * Maps a visible satellite count to its semantic availability color.
     */
    public static Color colorForVisibleCount(int count, int alpha) {
        return new Color(argbForVisibleCount(count, alpha), true);
    }

    private static int clampAlpha(int alpha) {
        return Math.max(0, Math.min(255, alpha));
    }

    public long getEpochMillis() {
        return epochMillis;
    }

    public double getResolutionDeg() {
        return resolutionDeg;
    }

    public double getElevationMaskDeg() {
        return elevationMaskDeg;
    }

    public GnssDopMetric getMetric() {
        return metric;
    }

    public GnssOrbitSource.Summary getOrbitSource() {
        return orbitSource;
    }

    public int getLatRows() {
        return latRows;
    }

    public int getLonCols() {
        return lonCols;
    }
}

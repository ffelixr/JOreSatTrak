package jsattrak.awareness.gnss;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import jsattrak.awareness.tle.PayloadTleMatcher;
import jsattrak.utilities.TLE;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.bodies.GeodeticPoint;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.Constants;
import org.orekit.utils.IERSConventions;

/**
 * Thread-safe Orekit SGP4 orbit propagator for GNSS display positions, 1-period ground tracks,
 * 3D orbital plane rings, and read-only bundled TLE fallback loading.
 */
public final class GnssOrbitPropagator {

    private static final List<String> BUNDLED_TLE_FILES = List.of(
            "gps-ops.txt", "galileo.txt", "glo-ops.txt", "beidou.txt");
    private static final int MAX_CACHED_PROPAGATORS = 512;
    private static final Map<String, TLEPropagator> PROPAGATOR_CACHE = new ConcurrentHashMap<>();

    private static volatile Frame itrf;
    private static volatile OneAxisEllipsoid earthEllipsoid;

    private GnssOrbitPropagator() {
    }

    public record GeodeticPosition(double latDeg, double lonDeg, double altMeters) {
    }

    private static OneAxisEllipsoid getEarth() {
        OneAxisEllipsoid existing = earthEllipsoid;
        if (existing == null) {
            synchronized (GnssOrbitPropagator.class) {
                if (earthEllipsoid == null) {
                    itrf = FramesFactory.getITRF(IERSConventions.IERS_2010, true);
                    earthEllipsoid = new OneAxisEllipsoid(
                            Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
                            Constants.WGS84_EARTH_FLATTENING,
                            itrf);
                }
                existing = earthEllipsoid;
            }
        }
        return existing;
    }

    private static TLEPropagator getOrCreatePropagator(TLE tle) {
        if (tle == null || tle.getLine1() == null || tle.getLine2() == null) {
            return null;
        }
        String key = tle.getLine1() + "\n" + tle.getLine2();
        TLEPropagator cached = PROPAGATOR_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        if (PROPAGATOR_CACHE.size() >= MAX_CACHED_PROPAGATORS) {
            PROPAGATOR_CACHE.clear();
        }
        org.orekit.propagation.analytical.tle.TLE orekitTle =
                new org.orekit.propagation.analytical.tle.TLE(tle.getLine1(), tle.getLine2());
        TLEPropagator created = TLEPropagator.selectExtrapolator(orekitTle);
        TLEPropagator prev = PROPAGATOR_CACHE.putIfAbsent(key, created);
        return prev != null ? prev : created;
    }

    /**
     * Propagates {@code tle} to {@code epochMillis} in WGS84 geodetic coordinates.
     */
    public static Optional<GeodeticPosition> propagateGeodetic(TLE tle, long epochMillis) {
        if (tle == null || tle.getLine1() == null || tle.getLine1().isBlank()
                || tle.getLine2() == null || tle.getLine2().isBlank()) {
            return Optional.empty();
        }
        try {
            TLEPropagator prop = getOrCreatePropagator(tle);
            if (prop == null) {
                return Optional.empty();
            }
            OneAxisEllipsoid earth = getEarth();
            AbsoluteDate targetDate = new AbsoluteDate(new Date(epochMillis), TimeScalesFactory.getUTC());
            Vector3D posItrf;
            synchronized (prop) {
                posItrf = prop.getPosition(targetDate, itrf);
            }
            GeodeticPoint gp = earth.transform(posItrf, itrf, targetDate);
            return Optional.of(new GeodeticPosition(
                    Math.toDegrees(gp.getLatitude()),
                    Math.toDegrees(gp.getLongitude()),
                    gp.getAltitude()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Computes {@code samples} geodetic {@code [latDeg, lonDeg]} points spanning one orbital period
     * centered on {@code centerEpochMillis}.
     */
    public static List<double[]> computeGroundTrack(TLE tle, long centerEpochMillis, int samples) {
        if (tle == null || samples < 2) {
            return List.of();
        }
        long periodMillis = estimateOrbitalPeriodMillis(tle);
        long startMillis = centerEpochMillis - (periodMillis / 2L);
        List<double[]> points = new ArrayList<>(samples);
        for (int i = 0; i < samples; i++) {
            long t = startMillis + Math.round((double) i * periodMillis / (samples - 1));
            Optional<GeodeticPosition> pos = propagateGeodetic(tle, t);
            if (pos.isPresent()) {
                points.add(new double[]{pos.get().latDeg(), pos.get().lonDeg()});
            }
        }
        return List.copyOf(points);
    }

    /**
     * Computes {@code samples} 3D geodetic points covering one full orbit around {@code epochMillis}.
     */
    public static List<GeodeticPosition> computeOrbitRing(TLE tle, long epochMillis, int samples) {
        if (tle == null || samples < 3) {
            return List.of();
        }
        long periodMillis = estimateOrbitalPeriodMillis(tle);
        List<GeodeticPosition> ring = new ArrayList<>(samples);
        for (int i = 0; i < samples; i++) {
            long t = epochMillis + Math.round((double) i * periodMillis / (samples - 1));
            propagateGeodetic(tle, t).ifPresent(ring::add);
        }
        return List.copyOf(ring);
    }

    /**
     * Loads read-only bundled GNSS TLEs from {@code tleDir} ({@code data/tle/}) as a fallback when
     * no remote GNSS TLE catalog has been fetched yet.
     */
    public static List<TLE> loadBundledFallbackTles(Path tleDir) {
        if (tleDir == null || !Files.isDirectory(tleDir)) {
            return List.of();
        }
        List<TLE> all = new ArrayList<>();
        for (String filename : BUNDLED_TLE_FILES) {
            Path file = tleDir.resolve(filename);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                all.addAll(PayloadTleMatcher.parseThreeLineTles(content));
            } catch (IOException ignored) {
            }
        }
        return List.copyOf(all);
    }

    public static long estimateOrbitalPeriodMillis(TLE tle) {
        if (tle != null && tle.getLine2() != null && tle.getLine2().length() >= 63) {
            try {
                double revPerDay = Double.parseDouble(tle.getLine2().substring(52, 63).trim());
                if (revPerDay > 0.05 && revPerDay < 25.0) {
                    return Math.round(86_400_000.0 / revPerDay);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return 12L * 3600L * 1000L;
    }
}

package jsattrak.awareness.gnss.stations.obs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.geometry.GnssDopCalculator;
import jsattrak.awareness.gnss.stations.GnssReferenceStation;
import jsattrak.awareness.gnss.stations.GnssStationNetwork;
import jsattrak.awareness.gnss.stations.GnssStationStatus;

/**
 * {@link GnssObservationSource} importing local RINEX 2/3/4 observation files and constructing
 * or enriching station metadata directly from the RINEX header.
 */
public class LocalRinexFileSource implements GnssObservationSource {

    @Override
    public String getSourceId() {
        return "local-rinex-file";
    }

    @Override
    public String getDisplayName() {
        return "Local RINEX Observation File";
    }

    @Override
    public boolean supportsNetwork(GnssStationNetwork network) {
        return network == GnssStationNetwork.LOCAL;
    }

    @Override
    public boolean isRealTimeStream() {
        return false;
    }

    @Override
    public ObservationFetchResult fetchObservations(GnssReferenceStation station, ObservationQuery query)
            throws IOException {
        if (station == null || station.observationHintUrl() == null || station.observationHintUrl().isBlank()) {
            throw new IOException("No local RINEX path attached to station");
        }
        return fetchFromFile(Path.of(station.observationHintUrl()), station);
    }

    public ObservationFetchResult fetchFromFile(Path file, GnssReferenceStation existingStation) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("Local RINEX file does not exist: " + file);
        }
        byte[] bytes = Files.readAllBytes(file);
        String fileName = file.getFileName().toString();
        RinexObservationData obs = RinexObservationLoader.parse(bytes, fileName);
        RinexObservationData.HeaderSummary hdr = obs.header();

        GnssReferenceStation resolved = synthesizeOrEnrichStation(existingStation, obs, file);
        return new ObservationFetchResult(
                resolved,
                obs,
                getDisplayName(),
                file.toAbsolutePath().toString(),
                System.currentTimeMillis(),
                "Local " + fileName);
    }

    private static GnssReferenceStation synthesizeOrEnrichStation(
            GnssReferenceStation existing,
            RinexObservationData obs,
            Path file) {
        RinexObservationData.HeaderSummary hdr = obs.header();
        String marker = !hdr.markerName().isBlank()
                ? hdr.markerName().toUpperCase(Locale.US)
                : file.getFileName().toString().replaceAll("\\..*$", "").toUpperCase(Locale.US);
        String shortCode = marker.length() >= 4 ? marker.substring(0, 4) : marker;

        double[] ecef = hdr.approxEcef();
        boolean hasEcef = ecef != null && ecef.length == 3
                && (ecef[0] != 0.0 || ecef[1] != 0.0 || ecef[2] != 0.0);
        double[] lla = hasEcef ? ecefToGeodeticDeg(ecef[0], ecef[1], ecef[2]) : new double[]{0.0, 0.0, 0.0};

        EnumSet<GnssConstellation> consts = EnumSet.noneOf(GnssConstellation.class);
        consts.addAll(hdr.typeObs().keySet());
        if (consts.isEmpty() && !obs.epochs().isEmpty()) {
            for (RinexObservationData.SatelliteObsSample s : obs.epochs().get(0).satellites().values()) {
                if (s.constellation() != null) {
                    consts.add(s.constellation());
                }
            }
        }

        if (existing != null) {
            return existing.withHardware(
                    hdr.receiverType(), hdr.receiverVersion(), hdr.antennaType(), "", consts, hasEcef ? ecef : null);
        }

        return new GnssReferenceStation(
                marker,
                shortCode,
                marker + " (Local RINEX)",
                marker.length() == 9 ? marker.substring(6, 9) : "",
                GnssStationNetwork.LOCAL,
                EnumSet.of(GnssStationNetwork.LOCAL),
                lla[0],
                lla[1],
                lla[2],
                hasEcef ? ecef : null,
                hdr.receiverType(),
                hdr.receiverVersion(),
                hdr.antennaType(),
                "",
                consts,
                List.of("Local RINEX"),
                hdr.lastObsMillis(),
                -1,
                Double.NaN,
                GnssStationStatus.ONLINE,
                file.toAbsolutePath().toString());
    }

    /**
     * Bowring iterative WGS-84 ECEF (meters) -> geodetic (latDeg, lonDeg, altMeters).
     */
    public static double[] ecefToGeodeticDeg(double x, double y, double z) {
        double a = GnssDopCalculator.WGS84_A;
        double e2 = GnssDopCalculator.WGS84_E2;
        double lon = Math.atan2(y, x);
        double p = Math.hypot(x, y);
        double lat = Math.atan2(z, p * (1.0 - e2));
        for (int i = 0; i < 6; i++) {
            double sinLat = Math.sin(lat);
            double n = a / Math.sqrt(1.0 - e2 * sinLat * sinLat);
            lat = Math.atan2(z + e2 * n * sinLat, p);
        }
        double sinLat = Math.sin(lat);
        double n = a / Math.sqrt(1.0 - e2 * sinLat * sinLat);
        double alt = p / Math.max(1e-12, Math.cos(lat)) - n;
        return new double[]{Math.toDegrees(lat), Math.toDegrees(lon), alt};
    }
}

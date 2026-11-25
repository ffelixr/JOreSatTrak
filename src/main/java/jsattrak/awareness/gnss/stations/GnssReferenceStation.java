package jsattrak.awareness.gnss.stations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.geometry.GnssDopCalculator;

/**
 * Immutable metadata and archive-availability snapshot for a permanent GNSS reference station.
 */
public record GnssReferenceStation(
        String stationId,
        String shortCode,
        String siteName,
        String countryCode,
        GnssStationNetwork primaryNetwork,
        Set<GnssStationNetwork> networks,
        double latDeg,
        double lonDeg,
        double altMeters,
        double[] monumentEcef,
        String receiverModel,
        String receiverFirmware,
        String antennaModel,
        String radomeCode,
        Set<GnssConstellation> supportedConstellations,
        List<String> availableFormats,
        long lastObsEpochMillis,
        int latencyMinutes,
        double availabilityPct,
        GnssStationStatus status,
        String observationHintUrl) {

    private static final double EARTH_RADIUS_KM = 6371.0088;

    public GnssReferenceStation {
        stationId = stationId != null ? stationId.trim().toUpperCase(Locale.US) : "";
        String derivedShort = shortCode != null && !shortCode.isBlank()
                ? shortCode.trim().toUpperCase(Locale.US)
                : (stationId.length() >= 4 ? stationId.substring(0, 4) : stationId);
        shortCode = derivedShort;
        siteName = siteName != null ? siteName.trim() : "";
        countryCode = countryCode != null ? countryCode.trim().toUpperCase(Locale.US) : "";
        primaryNetwork = primaryNetwork != null ? primaryNetwork : GnssStationNetwork.IGS;
        EnumSet<GnssStationNetwork> netSet = EnumSet.noneOf(GnssStationNetwork.class);
        if (networks != null) {
            netSet.addAll(networks);
        }
        netSet.add(primaryNetwork);
        networks = Collections.unmodifiableSet(netSet);

        if (monumentEcef != null && monumentEcef.length == 3
                && isFiniteVector(monumentEcef)
                && (monumentEcef[0] != 0.0 || monumentEcef[1] != 0.0 || monumentEcef[2] != 0.0)) {
            monumentEcef = Arrays.copyOf(monumentEcef, 3);
        } else if (Double.isFinite(latDeg) && Double.isFinite(lonDeg)) {
            monumentEcef = GnssDopCalculator.geodeticToEcef(
                    latDeg, lonDeg, Double.isFinite(altMeters) ? altMeters : 0.0);
        } else {
            monumentEcef = new double[]{0.0, 0.0, 0.0};
        }

        receiverModel = receiverModel != null ? receiverModel.trim() : "";
        receiverFirmware = receiverFirmware != null ? receiverFirmware.trim() : "";
        antennaModel = antennaModel != null ? antennaModel.trim() : "";
        radomeCode = radomeCode != null ? radomeCode.trim().toUpperCase(Locale.US) : "";

        EnumSet<GnssConstellation> constSet = EnumSet.noneOf(GnssConstellation.class);
        if (supportedConstellations != null) {
            constSet.addAll(supportedConstellations);
        }
        supportedConstellations = Collections.unmodifiableSet(constSet);

        availableFormats = availableFormats != null
                ? List.copyOf(new ArrayList<>(availableFormats))
                : List.of();
        status = status != null ? status : GnssStationStatus.UNKNOWN;
        observationHintUrl = observationHintUrl != null ? observationHintUrl.trim() : "";
    }

    public GnssReferenceStation withLatency(
            long lastObsEpochMillis,
            int latencyMinutes,
            List<String> availableFormats,
            GnssStationStatus status,
            String observationHintUrl) {
        return new GnssReferenceStation(
                stationId, shortCode, siteName, countryCode, primaryNetwork, networks,
                latDeg, lonDeg, altMeters, monumentEcef,
                receiverModel, receiverFirmware, antennaModel, radomeCode,
                supportedConstellations,
                (availableFormats != null && !availableFormats.isEmpty()) ? availableFormats : this.availableFormats,
                lastObsEpochMillis, latencyMinutes, availabilityPct, status,
                (observationHintUrl != null && !observationHintUrl.isBlank()) ? observationHintUrl : this.observationHintUrl);
    }

    public GnssReferenceStation withHardware(
            String receiverModel,
            String receiverFirmware,
            String antennaModel,
            String radomeCode,
            Set<GnssConstellation> constellations,
            double[] ecef) {
        return new GnssReferenceStation(
                stationId, shortCode, siteName, countryCode, primaryNetwork, networks,
                latDeg, lonDeg, altMeters,
                (ecef != null && ecef.length == 3) ? ecef : this.monumentEcef,
                preferNonBlank(receiverModel, this.receiverModel),
                preferNonBlank(receiverFirmware, this.receiverFirmware),
                preferNonBlank(antennaModel, this.antennaModel),
                preferNonBlank(radomeCode, this.radomeCode),
                (constellations != null && !constellations.isEmpty()) ? constellations : this.supportedConstellations,
                availableFormats, lastObsEpochMillis, latencyMinutes, availabilityPct, status, observationHintUrl);
    }

    /**
     * Merges {@code other} (same physical monument from another network) keeping richest metadata.
     */
    public GnssReferenceStation mergeWith(GnssReferenceStation other) {
        if (other == null) {
            return this;
        }
        EnumSet<GnssStationNetwork> mergedNets = EnumSet.copyOf(this.networks);
        mergedNets.addAll(other.networks);
        EnumSet<GnssConstellation> mergedConsts = EnumSet.copyOf(this.supportedConstellations);
        mergedConsts.addAll(other.supportedConstellations);

        List<String> mergedFormats = new ArrayList<>(this.availableFormats);
        for (String fmt : other.availableFormats) {
            if (!mergedFormats.contains(fmt)) {
                mergedFormats.add(fmt);
            }
        }

        String bestId = this.stationId.length() >= other.stationId.length() ? this.stationId : other.stationId;
        double[] bestEcef = hasNonZeroEcef(other.monumentEcef) && !hasNonZeroEcef(this.monumentEcef)
                ? other.monumentEcef : this.monumentEcef;
        long bestObs = Math.max(this.lastObsEpochMillis, other.lastObsEpochMillis);
        int bestLatency = (this.latencyMinutes >= 0 && other.latencyMinutes >= 0)
                ? Math.min(this.latencyMinutes, other.latencyMinutes)
                : Math.max(this.latencyMinutes, other.latencyMinutes);
        GnssStationStatus bestStatus = statusPriority(this.status) >= statusPriority(other.status)
                ? this.status : other.status;

        return new GnssReferenceStation(
                bestId,
                preferNonBlank(this.shortCode, other.shortCode),
                preferNonBlank(this.siteName, other.siteName),
                preferNonBlank(this.countryCode, other.countryCode),
                this.primaryNetwork,
                mergedNets,
                Double.isFinite(this.latDeg) ? this.latDeg : other.latDeg,
                Double.isFinite(this.lonDeg) ? this.lonDeg : other.lonDeg,
                Double.isFinite(this.altMeters) ? this.altMeters : other.altMeters,
                bestEcef,
                preferNonBlank(this.receiverModel, other.receiverModel),
                preferNonBlank(this.receiverFirmware, other.receiverFirmware),
                preferNonBlank(this.antennaModel, other.antennaModel),
                preferNonBlank(this.radomeCode, other.radomeCode),
                mergedConsts,
                mergedFormats,
                bestObs,
                bestLatency,
                Double.isFinite(this.availabilityPct) ? this.availabilityPct : other.availabilityPct,
                bestStatus,
                preferNonBlank(this.observationHintUrl, other.observationHintUrl));
    }

    /**
     * Great-circle surface distance (km) between this station and another geodetic coordinate.
     */
    public double distanceKmTo(double otherLatDeg, double otherLonDeg) {
        double lat1 = Math.toRadians(this.latDeg);
        double lat2 = Math.toRadians(otherLatDeg);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(otherLonDeg - this.lonDeg);
        double a = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2.0) * Math.sin(dLon / 2.0);
        return 2.0 * EARTH_RADIUS_KM * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0.0, 1.0 - a)));
    }

    public String constellationCodes() {
        StringBuilder sb = new StringBuilder(4);
        for (GnssConstellation c : GnssConstellation.layer1ActiveValues()) {
            if (supportedConstellations.contains(c)) {
                sb.append(c.getPrefix());
            }
        }
        return sb.toString();
    }

    private static String preferNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return Objects.requireNonNullElse(second, "");
    }

    private static boolean isFiniteVector(double[] v) {
        return Double.isFinite(v[0]) && Double.isFinite(v[1]) && Double.isFinite(v[2]);
    }

    private static boolean hasNonZeroEcef(double[] v) {
        return v != null && v.length == 3 && (v[0] != 0.0 || v[1] != 0.0 || v[2] != 0.0);
    }

    private static int statusPriority(GnssStationStatus s) {
        return switch (s) {
            case ONLINE -> 4;
            case DELAYED -> 3;
            case STALE -> 2;
            case OFFLINE -> 1;
            case UNKNOWN -> 0;
        };
    }
}

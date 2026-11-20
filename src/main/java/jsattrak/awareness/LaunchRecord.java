package jsattrak.awareness;

import java.util.Objects;

/**
 * Immutable domain model describing an upcoming or recent orbital/suborbital launch.
 */
public final class LaunchRecord {

    private final String id;
    private final String missionName;
    private final String provider;
    private final String vehicle;
    private final String targetOrbit;
    private final String cosparId;
    private final long netEpochMillis;
    private final long windowStartMillis;
    private final long windowEndMillis;
    private final String status;
    private final String outcome;
    private final boolean orbital;
    private final String description;
    private final String webcastUrl;
    private final String padName;
    private final String spaceportName;
    private final String country;
    private final double latDeg;
    private final double lonDeg;
    private final boolean fixedLandPad;

    public LaunchRecord(
            String id,
            String missionName,
            String provider,
            String vehicle,
            String targetOrbit,
            String cosparId,
            long netEpochMillis,
            long windowStartMillis,
            long windowEndMillis,
            String status,
            String outcome,
            boolean orbital,
            String description,
            String webcastUrl,
            String padName,
            String spaceportName,
            String country,
            double latDeg,
            double lonDeg,
            boolean fixedLandPad) {
        this.id = id;
        this.missionName = missionName;
        this.provider = provider;
        this.vehicle = vehicle;
        this.targetOrbit = targetOrbit;
        this.cosparId = cosparId;
        this.netEpochMillis = netEpochMillis;
        this.windowStartMillis = windowStartMillis;
        this.windowEndMillis = windowEndMillis;
        this.status = status;
        this.outcome = outcome;
        this.orbital = orbital;
        this.description = description;
        this.webcastUrl = webcastUrl;
        this.padName = padName;
        this.spaceportName = spaceportName;
        this.country = country;
        this.latDeg = latDeg;
        this.lonDeg = lonDeg;
        this.fixedLandPad = fixedLandPad;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getId() {
        return id;
    }

    public String getMissionName() {
        return missionName;
    }

    public String getProvider() {
        return provider;
    }

    public String getVehicle() {
        return vehicle;
    }

    public String getTargetOrbit() {
        return targetOrbit;
    }

    public String getCosparId() {
        return cosparId;
    }

    public long getNetEpochMillis() {
        return netEpochMillis;
    }

    public long getWindowStartMillis() {
        return windowStartMillis;
    }

    public long getWindowEndMillis() {
        return windowEndMillis;
    }

    /**
     * Returns the effective window end epoch, guarding against missing or malformed
     * payloads where {@code windowEndMillis < netEpochMillis}.
     */
    public long effectiveWindowEndMillis() {
        return Math.max(netEpochMillis, windowEndMillis);
    }

    public String getStatus() {
        return status;
    }

    public String getOutcome() {
        return outcome;
    }

    public boolean isOrbital() {
        return orbital;
    }

    public String getDescription() {
        return description;
    }

    public String getWebcastUrl() {
        return webcastUrl;
    }

    public String getPadName() {
        return padName;
    }

    public String getSpaceportName() {
        return spaceportName;
    }

    public String getCountry() {
        return country;
    }

    public double getLatDeg() {
        return latDeg;
    }

    public double getLonDeg() {
        return lonDeg;
    }

    public boolean isFixedLandPad() {
        return fixedLandPad;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LaunchRecord that)) {
            return false;
        }
        return netEpochMillis == that.netEpochMillis
                && windowStartMillis == that.windowStartMillis
                && windowEndMillis == that.windowEndMillis
                && orbital == that.orbital
                && Double.compare(that.latDeg, latDeg) == 0
                && Double.compare(that.lonDeg, lonDeg) == 0
                && fixedLandPad == that.fixedLandPad
                && Objects.equals(id, that.id)
                && Objects.equals(missionName, that.missionName)
                && Objects.equals(provider, that.provider)
                && Objects.equals(vehicle, that.vehicle)
                && Objects.equals(targetOrbit, that.targetOrbit)
                && Objects.equals(cosparId, that.cosparId)
                && Objects.equals(status, that.status)
                && Objects.equals(outcome, that.outcome)
                && Objects.equals(description, that.description)
                && Objects.equals(webcastUrl, that.webcastUrl)
                && Objects.equals(padName, that.padName)
                && Objects.equals(spaceportName, that.spaceportName)
                && Objects.equals(country, that.country);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                id, missionName, provider, vehicle, targetOrbit, cosparId,
                netEpochMillis, windowStartMillis, windowEndMillis,
                status, outcome, orbital, description, webcastUrl,
                padName, spaceportName, country, latDeg, lonDeg, fixedLandPad);
    }

    @Override
    public String toString() {
        return "LaunchRecord{id='" + id + "', missionName='" + missionName + "', net=" + netEpochMillis + "}";
    }

    /**
     * Fluent builder for {@link LaunchRecord}.
     */
    public static final class Builder {
        private String id = "";
        private String missionName = "";
        private String provider = "";
        private String vehicle = "";
        private String targetOrbit = "";
        private String cosparId = "";
        private long netEpochMillis;
        private long windowStartMillis;
        private long windowEndMillis;
        private String status = "";
        private String outcome = "";
        private boolean orbital = true;
        private String description = "";
        private String webcastUrl = "";
        private String padName = "";
        private String spaceportName = "";
        private String country = "";
        private double latDeg;
        private double lonDeg;
        private boolean fixedLandPad;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder missionName(String missionName) {
            this.missionName = missionName;
            return this;
        }

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder vehicle(String vehicle) {
            this.vehicle = vehicle;
            return this;
        }

        public Builder targetOrbit(String targetOrbit) {
            this.targetOrbit = targetOrbit;
            return this;
        }

        public Builder cosparId(String cosparId) {
            this.cosparId = cosparId;
            return this;
        }

        public Builder netEpochMillis(long netEpochMillis) {
            this.netEpochMillis = netEpochMillis;
            return this;
        }

        public Builder windowStartMillis(long windowStartMillis) {
            this.windowStartMillis = windowStartMillis;
            return this;
        }

        public Builder windowEndMillis(long windowEndMillis) {
            this.windowEndMillis = windowEndMillis;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder outcome(String outcome) {
            this.outcome = outcome;
            return this;
        }

        public Builder orbital(boolean orbital) {
            this.orbital = orbital;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder webcastUrl(String webcastUrl) {
            this.webcastUrl = webcastUrl;
            return this;
        }

        public Builder padName(String padName) {
            this.padName = padName;
            return this;
        }

        public Builder spaceportName(String spaceportName) {
            this.spaceportName = spaceportName;
            return this;
        }

        public Builder country(String country) {
            this.country = country;
            return this;
        }

        public Builder latDeg(double latDeg) {
            this.latDeg = latDeg;
            return this;
        }

        public Builder lonDeg(double lonDeg) {
            this.lonDeg = lonDeg;
            return this;
        }

        public Builder fixedLandPad(boolean fixedLandPad) {
            this.fixedLandPad = fixedLandPad;
            return this;
        }

        public LaunchRecord build() {
            return new LaunchRecord(
                    id, missionName, provider, vehicle, targetOrbit, cosparId,
                    netEpochMillis, windowStartMillis, windowEndMillis,
                    status, outcome, orbital, description, webcastUrl,
                    padName, spaceportName, country, latDeg, lonDeg, fixedLandPad);
        }
    }
}

package jsattrak.awareness.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.GnssDisplayStatus;

/**
 * Top-level configuration model for launch, TLE, and GNSS awareness feeds.
 */
public class AwarenessSettings {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    private int schemaVersion;
    private boolean autoFetchOnStartup;
    private int defaultHorizonDays = 7;
    private Boolean gnssFeedEnabled = Boolean.TRUE;
    private Boolean gnssShowOrbitRings3d = Boolean.TRUE;
    private Map<String, Boolean> gnssConstellationEnabled = defaultConstellationMap();
    private Map<String, Boolean> gnssStatusFilter = defaultStatusFilterMap();
    private Double gnssElevationMaskDeg = 10.0;
    private Boolean gnssIncludeNonOperationalInDop = Boolean.FALSE;
    private String gnssDopHeatmapMetric = "PDOP";
    private Double gnssDopHeatmapResolutionDeg = 5.0;
    private Double gnssDopHeatmapOpacity = 0.45;
    private Double gnssObserverLatDeg = 40.4168;
    private Double gnssObserverLonDeg = -3.7038;
    private Double gnssObserverAltMeters = 650.0;
    private List<DataSourceConfig> sources = new ArrayList<>();

    public AwarenessSettings() {
    }

    /**
     * Builds the default offline-first settings with public LL2, CelesTrak, and GNSS endpoints.
     */
    public static AwarenessSettings createDefaults() {
        AwarenessSettings settings = new AwarenessSettings();
        settings.setSchemaVersion(CURRENT_SCHEMA_VERSION);
        settings.setAutoFetchOnStartup(false);
        settings.setDefaultHorizonDays(7);
        settings.setGnssFeedEnabled(true);
        settings.setGnssShowOrbitRings3d(true);
        settings.setGnssConstellationEnabled(defaultConstellationMap());
        settings.setGnssStatusFilter(defaultStatusFilterMap());

        List<DataSourceConfig> defaults = new ArrayList<>();
        defaults.add(new DataSourceConfig(
                "ll2-public",
                DataSourceConfig.FeedCategory.LAUNCH,
                "Launch Library 2 (Public)",
                "https://ll.thespacedevs.com/2.2.0/launch/",
                true,
                1,
                AuthMode.NONE,
                "",
                "",
                "",
                15,
                true
        ));
        defaults.add(new DataSourceConfig(
                "celestrak-tle-new",
                DataSourceConfig.FeedCategory.TLE,
                "CelesTrak Last 30 Days",
                "https://celestrak.org/NORAD/elements/gp.php?GROUP=last-30-days&FORMAT=tle",
                true,
                1,
                AuthMode.NONE,
                "",
                "",
                "",
                15,
                false
        ));
        defaults.add(new DataSourceConfig(
                "celestrak-supgp",
                DataSourceConfig.FeedCategory.TLE,
                "CelesTrak SupGP (SpaceX-E)",
                "https://celestrak.org/NORAD/elements/supplemental/sup-gp.php?SOURCE=SpaceX-E&FORMAT=tle",
                true,
                2,
                AuthMode.NONE,
                "",
                "",
                "",
                15,
                false
        ));
        defaults.addAll(createDefaultGnssSources());
        settings.setSources(defaults);
        return settings;
    }

    /**
     * Builds the default public, no-auth GNSS data sources for Layer 1.
     */
    public static List<DataSourceConfig> createDefaultGnssSources() {
        List<DataSourceConfig> gnss = new ArrayList<>();
        gnss.add(new DataSourceConfig(
                "igs-sat-metadata",
                DataSourceConfig.FeedCategory.GNSS_CATALOG,
                "",
                "IGS Satellite Metadata (SINEX)",
                "https://files.igs.org/pub/station/general/igs_satellite_metadata.snx",
                true,
                1,
                AuthMode.NONE,
                "",
                "",
                "",
                1440,
                false
        ));
        gnss.add(new DataSourceConfig(
                "celestrak-gnss-tle",
                DataSourceConfig.FeedCategory.GNSS_ORBITS_TLE,
                "",
                "CelesTrak GNSS Combined",
                "https://celestrak.org/NORAD/elements/gp.php?GROUP=gnss&FORMAT=tle",
                true,
                1,
                AuthMode.NONE,
                "",
                "",
                "",
                1440,
                false
        ));
        gnss.add(new DataSourceConfig(
                "bkg-brdc-nav",
                DataSourceConfig.FeedCategory.GNSS_BROADCAST_NAV,
                "",
                "BKG Multi-GNSS Broadcast Nav (BRDC)",
                "https://igs.bkg.bund.de/root_ftp/NTRIP/BRDC/",
                true,
                1,
                AuthMode.NONE,
                "",
                "",
                "",
                15,
                false
        ));
        gnss.add(new DataSourceConfig(
                "navcen-gps-status",
                DataSourceConfig.FeedCategory.GNSS_OPERATOR_STATUS,
                "GPS_STATUS",
                "NAVCEN GPS Constellation Status",
                "https://www.navcen.uscg.gov/gps-constellation",
                true,
                1,
                AuthMode.NONE,
                "",
                "",
                "",
                15,
                false
        ));
        gnss.add(new DataSourceConfig(
                "navcen-gps-nanu",
                DataSourceConfig.FeedCategory.GNSS_OPERATOR_STATUS,
                "GPS_NANU",
                "NAVCEN GPS NANU Archive",
                "https://www.navcen.uscg.gov/sites/default/files/gps/nanu/",
                true,
                1,
                AuthMode.NONE,
                "",
                "",
                "",
                15,
                false
        ));
        gnss.add(new DataSourceConfig(
                "gsc-galileo-status",
                DataSourceConfig.FeedCategory.GNSS_OPERATOR_STATUS,
                "GALILEO_STATUS",
                "GSC Galileo Constellation Status",
                "https://www.gsc-europa.eu/system-service-status/constellation-information",
                true,
                1,
                AuthMode.NONE,
                "",
                "",
                "",
                15,
                false
        ));
        gnss.add(new DataSourceConfig(
                "gsc-galileo-nagu",
                DataSourceConfig.FeedCategory.GNSS_OPERATOR_STATUS,
                "GALILEO_NAGU",
                "GSC Galileo NAGU Feed",
                "https://www.gsc-europa.eu/system-service-status/nagu-information",
                true,
                1,
                AuthMode.NONE,
                "",
                "",
                "",
                15,
                false
        ));
        return gnss;
    }

    public static Map<String, Boolean> defaultConstellationMap() {
        Map<String, Boolean> map = new LinkedHashMap<>();
        for (GnssConstellation c : GnssConstellation.layer1ActiveValues()) {
            map.put(c.name(), Boolean.TRUE);
        }
        return map;
    }

    public static Map<String, Boolean> defaultStatusFilterMap() {
        Map<String, Boolean> map = new LinkedHashMap<>();
        for (GnssDisplayStatus s : GnssDisplayStatus.values()) {
            map.put(s.name(), Boolean.TRUE);
        }
        return map;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public boolean isGnssFeedEnabled() {
        return gnssFeedEnabled == null || gnssFeedEnabled.booleanValue();
    }

    public void setGnssFeedEnabled(boolean gnssFeedEnabled) {
        this.gnssFeedEnabled = gnssFeedEnabled;
    }

    public boolean isGnssShowOrbitRings3d() {
        return gnssShowOrbitRings3d == null || gnssShowOrbitRings3d.booleanValue();
    }

    public void setGnssShowOrbitRings3d(boolean gnssShowOrbitRings3d) {
        this.gnssShowOrbitRings3d = gnssShowOrbitRings3d;
    }

    public Map<String, Boolean> getGnssConstellationEnabled() {
        if (gnssConstellationEnabled == null) {
            gnssConstellationEnabled = defaultConstellationMap();
        }
        return gnssConstellationEnabled;
    }

    public void setGnssConstellationEnabled(Map<String, Boolean> gnssConstellationEnabled) {
        this.gnssConstellationEnabled = gnssConstellationEnabled != null
                ? new LinkedHashMap<>(gnssConstellationEnabled)
                : defaultConstellationMap();
    }

    public Map<String, Boolean> getGnssStatusFilter() {
        if (gnssStatusFilter == null) {
            gnssStatusFilter = defaultStatusFilterMap();
        }
        return gnssStatusFilter;
    }

    public void setGnssStatusFilter(Map<String, Boolean> gnssStatusFilter) {
        this.gnssStatusFilter = gnssStatusFilter != null
                ? new LinkedHashMap<>(gnssStatusFilter)
                : defaultStatusFilterMap();
    }

    public boolean isAutoFetchOnStartup() {
        return autoFetchOnStartup;
    }

    public void setAutoFetchOnStartup(boolean autoFetchOnStartup) {
        this.autoFetchOnStartup = autoFetchOnStartup;
    }

    public int getDefaultHorizonDays() {
        return defaultHorizonDays;
    }

    public void setDefaultHorizonDays(int defaultHorizonDays) {
        this.defaultHorizonDays = defaultHorizonDays;
    }

    public List<DataSourceConfig> getSources() {
        return sources;
    }

    public void setSources(List<DataSourceConfig> sources) {
        this.sources = sources;
    }

    public double getGnssElevationMaskDeg() {
        return gnssElevationMaskDeg != null ? gnssElevationMaskDeg : 10.0;
    }

    public void setGnssElevationMaskDeg(double gnssElevationMaskDeg) {
        this.gnssElevationMaskDeg = gnssElevationMaskDeg;
    }

    public boolean isGnssIncludeNonOperationalInDop() {
        return gnssIncludeNonOperationalInDop != null && gnssIncludeNonOperationalInDop.booleanValue();
    }

    public void setGnssIncludeNonOperationalInDop(boolean gnssIncludeNonOperationalInDop) {
        this.gnssIncludeNonOperationalInDop = gnssIncludeNonOperationalInDop;
    }

    public String getGnssDopHeatmapMetric() {
        return (gnssDopHeatmapMetric != null && !gnssDopHeatmapMetric.isBlank()) ? gnssDopHeatmapMetric : "PDOP";
    }

    public void setGnssDopHeatmapMetric(String gnssDopHeatmapMetric) {
        this.gnssDopHeatmapMetric = (gnssDopHeatmapMetric != null && !gnssDopHeatmapMetric.isBlank())
                ? gnssDopHeatmapMetric : "PDOP";
    }

    public double getGnssDopHeatmapResolutionDeg() {
        return (gnssDopHeatmapResolutionDeg != null && gnssDopHeatmapResolutionDeg > 0.0)
                ? gnssDopHeatmapResolutionDeg : 5.0;
    }

    public void setGnssDopHeatmapResolutionDeg(double gnssDopHeatmapResolutionDeg) {
        this.gnssDopHeatmapResolutionDeg = gnssDopHeatmapResolutionDeg;
    }

    public double getGnssDopHeatmapOpacity() {
        return gnssDopHeatmapOpacity != null ? gnssDopHeatmapOpacity : 0.45;
    }

    public void setGnssDopHeatmapOpacity(double gnssDopHeatmapOpacity) {
        this.gnssDopHeatmapOpacity = gnssDopHeatmapOpacity;
    }

    public double getGnssObserverLatDeg() {
        return gnssObserverLatDeg != null ? gnssObserverLatDeg : 40.4168;
    }

    public void setGnssObserverLatDeg(double gnssObserverLatDeg) {
        this.gnssObserverLatDeg = gnssObserverLatDeg;
    }

    public double getGnssObserverLonDeg() {
        return gnssObserverLonDeg != null ? gnssObserverLonDeg : -3.7038;
    }

    public void setGnssObserverLonDeg(double gnssObserverLonDeg) {
        this.gnssObserverLonDeg = gnssObserverLonDeg;
    }

    public double getGnssObserverAltMeters() {
        return gnssObserverAltMeters != null ? gnssObserverAltMeters : 650.0;
    }

    public void setGnssObserverAltMeters(double gnssObserverAltMeters) {
        this.gnssObserverAltMeters = gnssObserverAltMeters;
    }

    /**
     * Returns enabled sources for the given category sorted ascending by priority.
     */
    public List<DataSourceConfig> getEnabledSourcesByCategory(DataSourceConfig.FeedCategory category) {
        if (category == null || sources == null) {
            return List.of();
        }
        return sources.stream()
                .filter(Objects::nonNull)
                .filter(DataSourceConfig::isEnabled)
                .filter(source -> source.getFeedCategory() == category)
                .sorted(Comparator.comparingInt(DataSourceConfig::getPriority))
                .toList();
    }

    /**
     * Returns enabled sources for {@code category} grouped by {@code providerScope}, preserving
     * first-seen scope order and sorting each scope ascending by priority.
     */
    public Map<String, List<DataSourceConfig>> getEnabledSourcesGroupedByScope(
            DataSourceConfig.FeedCategory category) {
        Map<String, List<DataSourceConfig>> grouped = new LinkedHashMap<>();
        for (DataSourceConfig source : getEnabledSourcesByCategory(category)) {
            String scope = source.getProviderScope();
            grouped.computeIfAbsent(scope, k -> new ArrayList<>()).add(source);
        }
        return grouped;
    }
}

package jsattrak.awareness.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Top-level configuration model for launch and TLE awareness feeds.
 */
public class AwarenessSettings {

    private boolean autoFetchOnStartup;
    private int defaultHorizonDays = 7;
    private List<DataSourceConfig> sources = new ArrayList<>();

    public AwarenessSettings() {
    }

    /**
     * Builds the default offline-first settings with public LL2 and CelesTrak endpoints.
     */
    public static AwarenessSettings createDefaults() {
        AwarenessSettings settings = new AwarenessSettings();
        settings.setAutoFetchOnStartup(false);
        settings.setDefaultHorizonDays(7);

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
                false
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
                "CelesTrak SupGP Index",
                "https://celestrak.org/NORAD/elements/supplemental/",
                true,
                2,
                AuthMode.NONE,
                "",
                "",
                "",
                15,
                false
        ));
        settings.setSources(defaults);
        return settings;
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
}

package jsattrak.awareness.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class AwarenessSettingsStoreTest {

    @Test
    public void testDefaultsAndRoundTripAllAuthModes() throws Exception {
        Path tmp = Files.createTempDirectory("awareness-cfg").resolve("settings.json");
        AwarenessSettingsStore store = new AwarenessSettingsStore(tmp);

        AwarenessSettings defaults = store.load();
        assertEq(false, defaults.isAutoFetchOnStartup());
        assertEq(7, defaults.getDefaultHorizonDays());
        List<DataSourceConfig> defaultLaunchSources = defaults.getEnabledSourcesByCategory(DataSourceConfig.FeedCategory.LAUNCH);
        assertEq(1, defaultLaunchSources.size());
        assertEq("ll2-public", defaultLaunchSources.get(0).getId());
        assertEq(true, defaultLaunchSources.get(0).isSyncFixedPadsToGroundStations());

        List<DataSourceConfig> defaultTleSources = defaults.getEnabledSourcesByCategory(DataSourceConfig.FeedCategory.TLE);
        assertEq(2, defaultTleSources.size());
        assertEq("celestrak-supgp", defaultTleSources.get(1).getId());
        assertEq("CelesTrak SupGP (SpaceX-E)", defaultTleSources.get(1).getDisplayName());
        assertEq("https://celestrak.org/NORAD/elements/supplemental/sup-gp.php?SOURCE=SpaceX-E&FORMAT=tle",
                defaultTleSources.get(1).getEndpointUrl());
        assertEq(true, defaultTleSources.get(1).isEnabled());
        assertEq(15, new DataSourceConfig().getRefreshIntervalMinutes());
        for (DataSourceConfig defaultSource : defaults.getSources()) {
            assertEq(15, defaultSource.getRefreshIntervalMinutes());
        }

        defaults.setAutoFetchOnStartup(true);
        defaults.setDefaultHorizonDays(14);
        DataSourceConfig secondary = new DataSourceConfig("ll2-mirror", DataSourceConfig.FeedCategory.LAUNCH,
                "Mirror", "https://mirror.example/ll2/", true, 2, AuthMode.API_KEY_HEADER,
                "Authorization", "Token secret123", "", 15, true);
        DataSourceConfig bearerSource = new DataSourceConfig("bearer-tle", DataSourceConfig.FeedCategory.TLE,
                "Bearer Feed", "https://tle.example/bearer", true, 0, AuthMode.BEARER_TOKEN,
                "", "bearer-tok-xyz", "", 30, false);
        DataSourceConfig querySource = new DataSourceConfig("query-launch", DataSourceConfig.FeedCategory.LAUNCH,
                "Query Feed", "https://launch.example/feed", true, 0, AuthMode.QUERY_PARAM,
                "api_key", "qsecret", "", 45, false);
        DataSourceConfig basicDisabled = new DataSourceConfig("basic-disabled", DataSourceConfig.FeedCategory.TLE,
                "Basic Feed", "https://tle.example/basic", false, 3, AuthMode.BASIC_AUTH,
                "", "pw123", "user1", 60, false);

        defaults.getSources().add(secondary);
        defaults.getSources().add(bearerSource);
        defaults.getSources().add(querySource);
        defaults.getSources().add(basicDisabled);
        store.save(defaults);

        AwarenessSettings reloaded = store.load();
        assertEq(true, reloaded.isAutoFetchOnStartup());
        assertEq(14, reloaded.getDefaultHorizonDays());

        List<DataSourceConfig> launchSources = reloaded.getEnabledSourcesByCategory(DataSourceConfig.FeedCategory.LAUNCH);
        assertEq(3, launchSources.size());
        assertEq("query-launch", launchSources.get(0).getId());
        assertEq(AuthMode.QUERY_PARAM, launchSources.get(0).getAuthMode());
        assertEq("api_key", launchSources.get(0).getAuthKeyName());
        assertEq("qsecret", launchSources.get(0).getAuthSecret());
        assertEq("ll2-public", launchSources.get(1).getId());
        assertEq(AuthMode.NONE, launchSources.get(1).getAuthMode());
        assertEq(true, launchSources.get(1).isSyncFixedPadsToGroundStations());
        assertEq("ll2-mirror", launchSources.get(2).getId());
        assertEq("Token secret123", launchSources.get(2).getAuthSecret());
        assertEq(true, launchSources.get(2).isSyncFixedPadsToGroundStations());

        List<DataSourceConfig> tleSources = reloaded.getEnabledSourcesByCategory(DataSourceConfig.FeedCategory.TLE);
        assertEq(3, tleSources.size());
        assertEq("bearer-tle", tleSources.get(0).getId());
        assertEq(AuthMode.BEARER_TOKEN, tleSources.get(0).getAuthMode());
        assertEq("bearer-tok-xyz", tleSources.get(0).getAuthSecret());

        // Disabled source must still be persisted even though excluded from enabled query
        DataSourceConfig reloadedBasic = reloaded.getSources().stream()
                .filter(s -> "basic-disabled".equals(s.getId()))
                .findFirst()
                .orElseThrow();
        assertEq(AuthMode.BASIC_AUTH, reloadedBasic.getAuthMode());
        assertEq("user1", reloadedBasic.getAuthUsername());
        assertEq("pw123", reloadedBasic.getAuthSecret());
        assertEq(false, reloadedBasic.isEnabled());

        if (Files.getFileAttributeView(tmp, PosixFileAttributeView.class) != null) {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(tmp);
            assertEq(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms);
        }
    }

    @Test
    public void testCorruptAndNullFieldJsonRecoversToDefaults() throws Exception {
        Path tmp = Files.createTempDirectory("awareness-cfg-bad").resolve("settings.json");
        Files.writeString(tmp, "{ truncated json !!!");
        AwarenessSettingsStore store = new AwarenessSettingsStore(tmp);
        AwarenessSettings recovered = store.load();
        assertEq(false, recovered.isAutoFetchOnStartup());
        assertEq(7, recovered.getDefaultHorizonDays());
        assertEq(1, recovered.getEnabledSourcesByCategory(DataSourceConfig.FeedCategory.LAUNCH).size());
        assertEq(2, recovered.getEnabledSourcesByCategory(DataSourceConfig.FeedCategory.TLE).size());

        Files.writeString(tmp, "{\"autoFetchOnStartup\": true, \"sources\": null}");
        AwarenessSettings nullSourcesRecovered = store.load();
        assertEq(false, nullSourcesRecovered.isAutoFetchOnStartup());
        assertEq(7, nullSourcesRecovered.getDefaultHorizonDays());
        assertEq(2, nullSourcesRecovered.getEnabledSourcesByCategory(DataSourceConfig.FeedCategory.TLE).size());
    }

    @Test
    public void testCreateDefaultUsesUserHomeDirectory() {
        AwarenessSettingsStore store = AwarenessSettingsStore.createDefault();
        Path expected = Path.of(System.getProperty("user.home"), ".joresattrak", "awareness-settings.json");
        assertEq(expected, store.getSettingsPath());
    }

    private static void assertEq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("Expected <" + expected + "> but was <" + actual + ">");
        }
    }
}

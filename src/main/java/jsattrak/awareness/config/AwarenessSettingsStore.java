package jsattrak.awareness.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.GnssDisplayStatus;

/**
 * Persists {@link AwarenessSettings} as pretty-printed JSON with owner-only POSIX permissions.
 */
public class AwarenessSettingsStore {

    private static final Set<PosixFilePermission> OWNER_READ_WRITE =
            PosixFilePermissions.fromString("rw-------");

    private final Path settingsPath;
    private final Gson gson;

    public AwarenessSettingsStore(Path settingsPath) {
        this.settingsPath = Objects.requireNonNull(settingsPath, "settingsPath");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    public static AwarenessSettingsStore createDefault() {
        Path path = Path.of(System.getProperty("user.home"), ".joresattrak", "awareness-settings.json");
        return new AwarenessSettingsStore(path);
    }

    public Path getSettingsPath() {
        return settingsPath;
    }

    /**
     * Loads persisted settings, falling back to {@link AwarenessSettings#createDefaults()}
     * when the file is missing, unreadable, malformed JSON, or contains null required fields.
     */
    public AwarenessSettings load() {
        if (!Files.exists(settingsPath)) {
            return AwarenessSettings.createDefaults();
        }
        try {
            String raw = Files.readString(settingsPath, StandardCharsets.UTF_8);
            if (raw == null || raw.isBlank()) {
                return AwarenessSettings.createDefaults();
            }
            AwarenessSettings parsed = gson.fromJson(raw, AwarenessSettings.class);
            if (!isValid(parsed)) {
                return AwarenessSettings.createDefaults();
            }
            return migrateAndNormalize(parsed);
        } catch (IOException | JsonParseException ex) {
            return AwarenessSettings.createDefaults();
        }
    }

    private static AwarenessSettings migrateAndNormalize(AwarenessSettings settings) {
        List<DataSourceConfig> sources = new ArrayList<>(settings.getSources());
        boolean isPreGnssSchema = settings.getSchemaVersion() < AwarenessSettings.CURRENT_SCHEMA_VERSION;
        boolean hasAnyGnssSource = sources.stream().anyMatch(s -> s.getFeedCategory() != null
                && s.getFeedCategory().name().startsWith("GNSS_"));
        if (isPreGnssSchema && !hasAnyGnssSource) {
            sources.addAll(AwarenessSettings.createDefaultGnssSources());
        }
        settings.setSchemaVersion(AwarenessSettings.CURRENT_SCHEMA_VERSION);
        for (DataSourceConfig source : sources) {
            if (source.getProviderScope() == null) {
                source.setProviderScope("");
            }
        }
        settings.setSources(sources);

        Map<String, Boolean> constellations = new LinkedHashMap<>(AwarenessSettings.defaultConstellationMap());
        constellations.putAll(settings.getGnssConstellationEnabled());
        for (GnssConstellation c : GnssConstellation.layer1ActiveValues()) {
            constellations.putIfAbsent(c.name(), Boolean.TRUE);
        }
        settings.setGnssConstellationEnabled(constellations);

        Map<String, Boolean> statuses = new LinkedHashMap<>(AwarenessSettings.defaultStatusFilterMap());
        statuses.putAll(settings.getGnssStatusFilter());
        for (GnssDisplayStatus s : GnssDisplayStatus.values()) {
            statuses.putIfAbsent(s.name(), Boolean.TRUE);
        }
        settings.setGnssStatusFilter(statuses);
        return settings;
    }

    /**
     * Persists settings atomically with owner-only ({@code rw-------}) POSIX permissions where supported.
     */
    public void save(AwarenessSettings settings) throws IOException {
        Objects.requireNonNull(settings, "settings");
        Path absoluteTarget = settingsPath.toAbsolutePath();
        Path parent = absoluteTarget.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        if (settings.getSchemaVersion() < AwarenessSettings.CURRENT_SCHEMA_VERSION) {
            settings.setSchemaVersion(AwarenessSettings.CURRENT_SCHEMA_VERSION);
        }
        String json = gson.toJson(settings);
        Path tempFile = createSecureTempFile(parent);
        try {
            Files.writeString(tempFile, json, StandardCharsets.UTF_8);
            applyOwnerOnlyPermissions(tempFile);
            Files.move(tempFile, absoluteTarget, StandardCopyOption.REPLACE_EXISTING);
            applyOwnerOnlyPermissions(absoluteTarget);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private static boolean isValid(AwarenessSettings settings) {
        if (settings == null || settings.getSources() == null || settings.getDefaultHorizonDays() <= 0) {
            return false;
        }
        for (DataSourceConfig source : settings.getSources()) {
            if (source == null
                    || source.getId() == null
                    || source.getFeedCategory() == null
                    || source.getEndpointUrl() == null
                    || source.getAuthMode() == null) {
                return false;
            }
        }
        return true;
    }

    private static Path createSecureTempFile(Path parent) throws IOException {
        if (supportsPosix(parent)) {
            try {
                return Files.createTempFile(
                        parent,
                        "awareness-settings-",
                        ".tmp",
                        PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE)
                );
            } catch (UnsupportedOperationException ignored) {
                // Fall through to standard temp file creation on non-POSIX filesystems
            }
        }
        return Files.createTempFile(parent, "awareness-settings-", ".tmp");
    }

    private static void applyOwnerOnlyPermissions(Path path) throws IOException {
        if (!supportsPosix(path)) {
            return;
        }
        try {
            Files.setPosixFilePermissions(path, OWNER_READ_WRITE);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem (e.g., Windows)
        }
    }

    private static boolean supportsPosix(Path path) {
        return path != null && Files.getFileAttributeView(path, PosixFileAttributeView.class) != null;
    }
}

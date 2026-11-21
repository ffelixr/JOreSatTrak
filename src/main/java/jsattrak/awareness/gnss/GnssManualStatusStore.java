package jsattrak.awareness.gnss;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Persists {@link GnssManualStatusList} atomically under {@code ~/.joresattrak/gnss-manual-status.json},
 * seeding from the bundled classpath resource on first use or corrupt JSON recovery.
 */
public class GnssManualStatusStore {

    private static final String DEFAULT_RESOURCE = "/jsattrak/awareness/gnss/default-manual-status.json";
    private static final Set<PosixFilePermission> OWNER_READ_WRITE =
            PosixFilePermissions.fromString("rw-------");

    private final Path filePath;
    private final Gson gson;

    public GnssManualStatusStore(Path filePath) {
        this.filePath = Objects.requireNonNull(filePath, "filePath");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    public static GnssManualStatusStore createDefault() {
        Path path = Path.of(System.getProperty("user.home"), ".joresattrak", "gnss-manual-status.json");
        return new GnssManualStatusStore(path);
    }

    public Path getFilePath() {
        return filePath;
    }

    public GnssManualStatusList load() {
        if (!Files.exists(filePath)) {
            GnssManualStatusList seeded = loadBundledDefault();
            try {
                save(seeded);
            } catch (IOException ignored) {
                // Non-fatal if user config directory is read-only
            }
            return seeded;
        }
        try {
            String raw = Files.readString(filePath, StandardCharsets.UTF_8);
            if (raw == null || raw.isBlank()) {
                return loadBundledDefault();
            }
            GnssManualStatusList parsed = gson.fromJson(raw, GnssManualStatusList.class);
            if (parsed == null || parsed.getLastReviewedIso() == null) {
                return loadBundledDefault();
            }
            return sanitize(parsed);
        } catch (IOException | JsonParseException e) {
            return loadBundledDefault();
        }
    }

    public void save(GnssManualStatusList list) throws IOException {
        Objects.requireNonNull(list, "list");
        GnssManualStatusList sanitized = sanitize(list);
        Path absolute = filePath.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String json = gson.toJson(sanitized);
        Path tempFile = createSecureTempFile(parent);
        try {
            Files.writeString(tempFile, json, StandardCharsets.UTF_8);
            applyOwnerOnlyPermissions(tempFile);
            Files.move(tempFile, absolute, StandardCopyOption.REPLACE_EXISTING);
            applyOwnerOnlyPermissions(absolute);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private GnssManualStatusList loadBundledDefault() {
        try (InputStream in = GnssManualStatusStore.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in != null) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                GnssManualStatusList parsed = gson.fromJson(json, GnssManualStatusList.class);
                if (parsed != null) {
                    return sanitize(parsed);
                }
            }
        } catch (IOException | JsonParseException ignored) {
        }
        return new GnssManualStatusList(LocalDate.now(ZoneOffset.UTC).toString(), List.of());
    }

    private static GnssManualStatusList sanitize(GnssManualStatusList list) {
        String reviewed = (list.getLastReviewedIso() != null && !list.getLastReviewedIso().isBlank())
                ? list.getLastReviewedIso().trim()
                : LocalDate.now(ZoneOffset.UTC).toString();
        List<GnssManualOverride> cleanEntries = new ArrayList<>();
        if (list.getEntries() != null) {
            for (GnssManualOverride entry : list.getEntries()) {
                if (entry != null
                        && entry.prnId() != null
                        && !entry.prnId().isBlank()
                        && entry.status() != null
                        && entry.status() != GnssDisplayStatus.NO_STATUS_DATA) {
                    cleanEntries.add(new GnssManualOverride(
                            entry.prnId().trim().toUpperCase(),
                            entry.status(),
                            entry.note() != null ? entry.note().trim() : ""));
                }
            }
        }
        return new GnssManualStatusList(reviewed, cleanEntries);
    }

    private static Path createSecureTempFile(Path parent) throws IOException {
        if (supportsPosix(parent)) {
            try {
                return Files.createTempFile(
                        parent,
                        "gnss-manual-",
                        ".tmp",
                        PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE));
            } catch (UnsupportedOperationException ignored) {
            }
        }
        return Files.createTempFile(parent, "gnss-manual-", ".tmp");
    }

    private static void applyOwnerOnlyPermissions(Path path) throws IOException {
        if (!supportsPosix(path)) {
            return;
        }
        try {
            Files.setPosixFilePermissions(path, OWNER_READ_WRITE);
        } catch (UnsupportedOperationException ignored) {
        }
    }

    private static boolean supportsPosix(Path path) {
        return path != null && Files.getFileAttributeView(path, PosixFileAttributeView.class) != null;
    }
}

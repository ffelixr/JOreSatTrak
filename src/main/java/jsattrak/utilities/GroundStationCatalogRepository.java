package jsattrak.utilities;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import jsattrak.awareness.LaunchRecord;

/**
 * Repository for reading and updating JSatTrak's ground station catalogs
 * (base database, custom user stations, and companion Launch (API) pads).
 */
public class GroundStationCatalogRepository {

    public static final String LAUNCH_API_NETWORK = "Launch (API)";
    public static final String CUSTOM_NETWORK = "Custom";
    public static final String DEFAULT_BASE_DB = "data/groundstations/groundstations_db.csv";
    public static final String DEFAULT_CUSTOM_DB = "data/groundstations/groundstations_custom.csv";
    public static final String DEFAULT_LAUNCH_DB = "data/groundstations/groundstations_launches.csv";

    private static final double DEDUP_PROXIMITY_DEG = 0.005;
    private static final double ZERO_COORD_EPSILON = 1e-6;

    /**
     * Immutable representation of a single ground station catalog entry.
     */
    public record CatalogEntry(String network, String name, double latDeg, double lonDeg, double altMeters) {
    }

    private final File baseDbFile;
    private final File customDbFile;
    private final File launchApiDbFile;

    public GroundStationCatalogRepository(File baseDbFile, File customDbFile, File launchApiDbFile) {
        this.baseDbFile = baseDbFile;
        this.customDbFile = customDbFile;
        this.launchApiDbFile = launchApiDbFile;
    }

    public static GroundStationCatalogRepository createDefault() {
        return new GroundStationCatalogRepository(
                new File(DEFAULT_BASE_DB),
                new File(DEFAULT_CUSTOM_DB),
                new File(DEFAULT_LAUNCH_DB));
    }

    /**
     * Loads all ground station catalogs grouped by network in display order:
     * base DB networks, {@code Custom}, and {@code Launch (API)} (if non-empty).
     */
    public synchronized Map<String, List<CatalogEntry>> loadGroupedCatalog() throws IOException {
        Map<String, List<CatalogEntry>> grouped = new LinkedHashMap<>();

        for (CatalogEntry entry : readEntries(baseDbFile, null)) {
            grouped.computeIfAbsent(entry.network(), k -> new ArrayList<>()).add(entry);
        }

        List<CatalogEntry> customEntries = grouped.computeIfAbsent(CUSTOM_NETWORK, k -> new ArrayList<>());
        customEntries.addAll(readEntries(customDbFile, CUSTOM_NETWORK));

        List<CatalogEntry> launchEntries = readEntries(launchApiDbFile, LAUNCH_API_NETWORK);
        if (!launchEntries.isEmpty()) {
            grouped.put(LAUNCH_API_NETWORK, launchEntries);
        }

        return grouped;
    }

    /**
     * Appends a user-defined ground station to {@code customDbFile}.
     */
    public synchronized void appendCustomStation(String name, double latDeg, double lonDeg, double altMeters)
            throws IOException {
        if (customDbFile == null) {
            throw new IOException("Custom ground station database file is not configured.");
        }
        String sanitizedName = sanitizeCsvField(name);
        if (sanitizedName.isEmpty()) {
            throw new IllegalArgumentException("Ground station name must not be blank.");
        }

        ensureParentDirectory(customDbFile);
        boolean needsLeadingNewline = customDbFile.exists()
                && customDbFile.length() > 0
                && !endsWithNewline(customDbFile);

        StringBuilder sb = new StringBuilder();
        if (needsLeadingNewline) {
            sb.append('\n');
        }
        sb.append(formatCsvRow(CUSTOM_NETWORK, sanitizedName, latDeg, lonDeg, altMeters));

        Files.writeString(
                customDbFile.toPath(),
                sb.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    /**
     * Syncs fixed land-based launch pads into the companion {@code launchApiDbFile}
     * without ever modifying {@code baseDbFile}.
     *
     * @return number of newly added fixed pads
     */
    public synchronized int syncLaunchPads(List<LaunchRecord> launches) throws IOException {
        if (launches == null || launches.isEmpty() || launchApiDbFile == null) {
            return 0;
        }

        List<CatalogEntry> allEntries = new ArrayList<>(readEntries(launchApiDbFile, LAUNCH_API_NETWORK));
        int addedCount = 0;

        for (LaunchRecord launch : launches) {
            if (launch == null || !launch.isFixedLandPad()) {
                continue;
            }
            double lat = launch.getLatDeg();
            double lon = launch.getLonDeg();
            if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
                continue;
            }
            if (Math.abs(lat) < ZERO_COORD_EPSILON && Math.abs(lon) < ZERO_COORD_EPSILON) {
                continue;
            }

            String stationName = formatPadStationName(launch.getSpaceportName(), launch.getPadName());
            if (stationName.isEmpty() || isDuplicatePad(allEntries, stationName, lat, lon)) {
                continue;
            }

            allEntries.add(new CatalogEntry(LAUNCH_API_NETWORK, stationName, lat, lon, 0.0));
            addedCount++;
        }

        if (addedCount == 0) {
            return 0;
        }

        writeEntriesAtomically(launchApiDbFile, allEntries);
        return addedCount;
    }

    public static String sanitizeCsvField(String raw) {
        if (raw == null) {
            return "";
        }
        return raw
                .replace("\"", "")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace(',', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String formatPadStationName(String spaceportName, String padName) {
        String spaceport = sanitizeCsvField(spaceportName);
        String pad = sanitizeCsvField(padName);
        if (!spaceport.isEmpty() && !pad.isEmpty()) {
            return spaceport + " - " + pad;
        }
        return !spaceport.isEmpty() ? spaceport : pad;
    }

    private static boolean isDuplicatePad(
            List<CatalogEntry> existing,
            String candidateName,
            double candidateLat,
            double candidateLon) {
        String normalizedCandidate = normalizePadName(candidateName);
        for (CatalogEntry entry : existing) {
            if (normalizePadName(entry.name()).equals(normalizedCandidate)
                    && Math.abs(entry.latDeg() - candidateLat) <= DEDUP_PROXIMITY_DEG
                    && Math.abs(entry.lonDeg() - candidateLon) <= DEDUP_PROXIMITY_DEG) {
                return true;
            }
        }
        return false;
    }

    private static String normalizePadName(String name) {
        return name == null
                ? ""
                : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static List<CatalogEntry> readEntries(File file, String overrideNetwork) throws IOException {
        if (file == null || !file.exists() || !file.isFile()) {
            return List.of();
        }
        List<CatalogEntry> entries = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] elements = line.split(",", -1);
                if (elements.length != 5) {
                    continue;
                }
                String network = overrideNetwork != null ? overrideNetwork : elements[0].trim();
                String stationName = elements[1].trim();
                try {
                    double lat = Double.parseDouble(elements[2].trim());
                    double lon = Double.parseDouble(elements[3].trim());
                    double alt = Double.parseDouble(elements[4].trim());
                    entries.add(new CatalogEntry(network, stationName, lat, lon, alt));
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid numeric coordinate in " + file + ": " + line, e);
                }
            }
        }
        return entries;
    }

    private static void writeEntriesAtomically(File targetFile, List<CatalogEntry> entries) throws IOException {
        ensureParentDirectory(targetFile);
        Path targetPath = targetFile.toPath().toAbsolutePath();
        Path parentDir = targetPath.getParent();

        StringBuilder content = new StringBuilder();
        for (CatalogEntry entry : entries) {
            content.append(formatCsvRow(
                    entry.network(),
                    entry.name(),
                    entry.latDeg(),
                    entry.lonDeg(),
                    entry.altMeters()));
        }

        Path tempFile = Files.createTempFile(parentDir, "groundstations_", ".tmp");
        try {
            Files.writeString(tempFile, content.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private static String formatCsvRow(
            String network,
            String name,
            double latDeg,
            double lonDeg,
            double altMeters) {
        return sanitizeCsvField(network) + ","
                + sanitizeCsvField(name) + ","
                + latDeg + ","
                + lonDeg + ","
                + altMeters + "\n";
    }

    private static void ensureParentDirectory(File file) throws IOException {
        Path parent = file.toPath().toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static boolean endsWithNewline(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            if (length == 0) {
                return true;
            }
            raf.seek(length - 1);
            int lastByte = raf.read();
            return lastByte == '\n' || lastByte == '\r';
        }
    }
}

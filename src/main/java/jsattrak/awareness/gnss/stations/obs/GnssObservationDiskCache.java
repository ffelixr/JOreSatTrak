package jsattrak.awareness.gnss.stations.obs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Bounded LRU disk cache for downloaded RINEX observation payloads under
 * {@code ~/.joresattrak/gnss-obs-cache/} (default 50 MB max size, 24 h TTL).
 */
public class GnssObservationDiskCache {

    public static final long DEFAULT_MAX_BYTES = 50L * 1024L * 1024L;
    public static final long DEFAULT_TTL_MILLIS = 24L * 3600L * 1000L;

    private final Path cacheDir;
    private final long maxBytes;
    private final long ttlMillis;

    public GnssObservationDiskCache() {
        this(Path.of(System.getProperty("user.home"), ".joresattrak", "gnss-obs-cache"),
                DEFAULT_MAX_BYTES, DEFAULT_TTL_MILLIS);
    }

    public GnssObservationDiskCache(Path cacheDir) {
        this(cacheDir, DEFAULT_MAX_BYTES, DEFAULT_TTL_MILLIS);
    }

    public GnssObservationDiskCache(Path cacheDir, long maxBytes, long ttlMillis) {
        this.cacheDir = Objects.requireNonNull(cacheDir, "cacheDir");
        this.maxBytes = Math.max(1024L, maxBytes);
        this.ttlMillis = Math.max(1000L, ttlMillis);
    }

    public synchronized byte[] get(String uriKey, long nowMillis) {
        if (uriKey == null || uriKey.isBlank()) {
            return null;
        }
        Path entry = cacheDir.resolve(sha256Hex(uriKey) + ".bin");
        if (!Files.isRegularFile(entry)) {
            return null;
        }
        try {
            long mtime = Files.getLastModifiedTime(entry).toMillis();
            if (nowMillis - mtime > ttlMillis) {
                Files.deleteIfExists(entry);
                return null;
            }
            Files.setLastModifiedTime(entry, java.nio.file.attribute.FileTime.fromMillis(nowMillis));
            return Files.readAllBytes(entry);
        } catch (IOException e) {
            return null;
        }
    }

    public synchronized void put(String uriKey, byte[] payload, long nowMillis) {
        if (uriKey == null || uriKey.isBlank() || payload == null || payload.length == 0) {
            return;
        }
        try {
            Files.createDirectories(cacheDir);
            Path target = cacheDir.resolve(sha256Hex(uriKey) + ".bin");
            Path tmp = cacheDir.resolve(sha256Hex(uriKey) + ".tmp");
            Files.write(tmp, payload);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(nowMillis));
            evictIfNeeded(nowMillis);
        } catch (IOException ignored) {
        }
    }

    private void evictIfNeeded(long nowMillis) throws IOException {
        if (!Files.isDirectory(cacheDir)) {
            return;
        }
        List<Path> entries = new ArrayList<>();
        long totalSize = 0L;
        try (Stream<Path> walk = Files.list(cacheDir)) {
            for (Path p : walk.toList()) {
                if (!Files.isRegularFile(p) || !p.getFileName().toString().endsWith(".bin")) {
                    continue;
                }
                long mtime = Files.getLastModifiedTime(p).toMillis();
                if (nowMillis - mtime > ttlMillis) {
                    Files.deleteIfExists(p);
                    continue;
                }
                totalSize += Files.size(p);
                entries.add(p);
            }
        }
        if (totalSize <= maxBytes) {
            return;
        }
        entries.sort(Comparator.comparingLong(p -> {
            try {
                return Files.getLastModifiedTime(p).toMillis();
            } catch (IOException e) {
                return 0L;
            }
        }));
        for (Path p : entries) {
            if (totalSize <= maxBytes) {
                break;
            }
            long sz = Files.size(p);
            if (Files.deleteIfExists(p)) {
                totalSize -= sz;
            }
        }
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}

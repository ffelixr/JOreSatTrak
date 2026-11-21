package jsattrak.awareness.gnss;

/**
 * Sync health summary for a single GNSS data source category or operator scope.
 */
public record GnssSourceHealth(
        String key,
        String displayName,
        boolean ok,
        boolean stale,
        long lastSyncMillis,
        String message) {
}

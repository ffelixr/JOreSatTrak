package jsattrak.awareness.gnss;

/**
 * Broadcast navigation health message snapshot extracted from RINEX navigation data.
 */
public record BroadcastHealthRecord(
        String prnId,
        GnssConstellation constellation,
        String messageType,
        long epochMillis,
        int rawHealthCode,
        boolean healthy,
        String detail) {
}

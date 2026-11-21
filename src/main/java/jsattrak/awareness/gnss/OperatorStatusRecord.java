package jsattrak.awareness.gnss;

/**
 * Operator-published satellite operational status (e.g. NAVCEN or GSC).
 */
public record OperatorStatusRecord(
        String prnId,
        GnssConstellation constellation,
        String sourceName,
        OperatorHealth status,
        String rawLabel,
        long fetchedAtMillis,
        String detail) {

    public enum OperatorHealth {
        USABLE,
        TESTING,
        NOT_AVAILABLE
    }
}

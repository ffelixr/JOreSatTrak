package jsattrak.awareness.gnss;

/**
 * Single evidence item contributing to a satellite's derived status, displayed in the inspector.
 */
public record GnssEvidenceItem(
        String source,
        long timestampMillis,
        String rawValue,
        String reason,
        boolean decisive,
        Severity severity) {

    public enum Severity {
        OK,
        WARN,
        ERROR,
        MUTED
    }
}

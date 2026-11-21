package jsattrak.awareness.gnss;

/**
 * User-editable manual status override entry (e.g. for GLONASS/BeiDou flight testing).
 */
public record GnssManualOverride(
        String prnId,
        GnssDisplayStatus status,
        String note) {
}

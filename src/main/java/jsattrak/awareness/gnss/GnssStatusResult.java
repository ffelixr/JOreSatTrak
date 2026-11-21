package jsattrak.awareness.gnss;

import java.util.List;

/**
 * Derived operational status, decisive rule/source metadata, and ordered reasoning trail.
 */
public record GnssStatusResult(
        GnssDisplayStatus status,
        String primarySource,
        String decidedByRule,
        List<GnssEvidenceItem> evidence,
        GnssOutageNotice activeNotice,
        GnssOutageNotice nextPlannedNotice,
        boolean manualOperationalOverride) {
}

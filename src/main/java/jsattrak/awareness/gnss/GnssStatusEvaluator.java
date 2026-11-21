package jsattrak.awareness.gnss;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Pure 8-rule GNSS satellite status derivation engine combining outage notices, manual overrides,
 * operator constellation status, and broadcast navigation health flags (§5.4 & §11).
 */
public final class GnssStatusEvaluator {

    public static final long DEFAULT_ABSENCE_THRESHOLD_MILLIS = 4L * 3600L * 1000L;
    public static final int DEFAULT_MANUAL_STALE_DAYS = 30;

    private static final String RULE_OUTAGE_NOTICE = "ACTIVE_OUTAGE_NOTICE";
    private static final String RULE_MANUAL_OVERRIDE = "MANUAL_OVERRIDE";
    private static final String RULE_OPERATOR_TESTING = "OPERATOR_TESTING";
    private static final String RULE_OPERATOR_NOT_AVAILABLE = "OPERATOR_NOT_AVAILABLE";
    private static final String RULE_BROADCAST_UNHEALTHY = "BROADCAST_UNHEALTHY";
    private static final String RULE_BROADCAST_ABSENT = "BROADCAST_ABSENT";
    private static final String RULE_BROADCAST_HEALTHY = "BROADCAST_HEALTHY";
    private static final String RULE_NO_EVIDENCE = "NO_EVIDENCE";

    private GnssStatusEvaluator() {
    }

    /**
     * Evaluates a satellite's display status at {@code simEpochMillis} according to the strict
     * 8-rule precedence hierarchy.
     */
    public static GnssStatusResult evaluate(
            GnssCatalogEntry entry,
            long simEpochMillis,
            BroadcastHealthRecord broadcast,
            long broadcastFeedLatestEpochMillis,
            long broadcastFeedSyncedMillis,
            OperatorStatusRecord operatorStatus,
            List<GnssOutageNotice> notices,
            GnssManualOverride manualOverride,
            boolean noticesFeedAvailable) {
        Objects.requireNonNull(entry, "entry");

        List<GnssOutageNotice> activeNotices = new ArrayList<>();
        List<GnssOutageNotice> futurePlanned = new ArrayList<>();
        if (notices != null) {
            for (GnssOutageNotice notice : notices) {
                if (notice == null || notice.cancelled()) {
                    continue;
                }
                if (notice.isActiveAt(simEpochMillis)) {
                    activeNotices.add(notice);
                } else if (notice.planned() && notice.startEpochMillis() > simEpochMillis) {
                    futurePlanned.add(notice);
                }
            }
        }
        activeNotices.sort(Comparator.comparingLong(GnssOutageNotice::startEpochMillis));
        futurePlanned.sort(Comparator.comparingLong(GnssOutageNotice::startEpochMillis));

        GnssOutageNotice activeNotice = activeNotices.isEmpty() ? null : activeNotices.get(0);
        GnssOutageNotice nextPlannedNotice = futurePlanned.isEmpty() ? null : futurePlanned.get(0);

        boolean broadcastFeedPresent = broadcastFeedLatestEpochMillis > 0L;
        boolean broadcastAbsent = broadcastFeedPresent
                && (broadcast == null
                || (broadcastFeedLatestEpochMillis - broadcast.epochMillis()) > DEFAULT_ABSENCE_THRESHOLD_MILLIS);

        // Determine winning rule strictly in precedence order 1..8
        GnssDisplayStatus status;
        String primarySource;
        String decidedRule;

        if (activeNotice != null) {
            status = GnssDisplayStatus.UNAVAILABLE;
            primarySource = nonBlankOr(activeNotice.noticeId(), nonBlankOr(activeNotice.sourceName(), "Outage Notice"));
            decidedRule = RULE_OUTAGE_NOTICE;
        } else if (manualOverride != null && manualOverride.status() != null
                && manualOverride.status() != GnssDisplayStatus.NO_STATUS_DATA) {
            status = manualOverride.status();
            primarySource = "Manual list";
            decidedRule = RULE_MANUAL_OVERRIDE;
        } else if (operatorStatus != null
                && operatorStatus.status() == OperatorStatusRecord.OperatorHealth.TESTING) {
            status = GnssDisplayStatus.TESTING;
            primarySource = nonBlankOr(operatorStatus.sourceName(), "Operator status");
            decidedRule = RULE_OPERATOR_TESTING;
        } else if (operatorStatus != null
                && operatorStatus.status() == OperatorStatusRecord.OperatorHealth.NOT_AVAILABLE) {
            status = GnssDisplayStatus.UNAVAILABLE;
            primarySource = nonBlankOr(operatorStatus.sourceName(), "Operator status");
            decidedRule = RULE_OPERATOR_NOT_AVAILABLE;
        } else if (broadcast != null && !broadcast.healthy()) {
            status = GnssDisplayStatus.UNAVAILABLE;
            primarySource = "Broadcast nav";
            decidedRule = RULE_BROADCAST_UNHEALTHY;
        } else if (broadcastAbsent) {
            status = GnssDisplayStatus.UNAVAILABLE;
            primarySource = "Broadcast nav (absent)";
            decidedRule = RULE_BROADCAST_ABSENT;
        } else if (broadcast != null && broadcast.healthy()) {
            status = GnssDisplayStatus.OPERATIONAL;
            primarySource = "Broadcast nav";
            decidedRule = RULE_BROADCAST_HEALTHY;
        } else if (operatorStatus != null
                && operatorStatus.status() == OperatorStatusRecord.OperatorHealth.USABLE) {
            // Operator says usable when broadcast nav has not yet loaded
            status = GnssDisplayStatus.OPERATIONAL;
            primarySource = nonBlankOr(operatorStatus.sourceName(), "Operator status");
            decidedRule = RULE_BROADCAST_HEALTHY;
        } else {
            status = GnssDisplayStatus.NO_STATUS_DATA;
            primarySource = "No data";
            decidedRule = RULE_NO_EVIDENCE;
        }

        // Build ordered evidence trail for the inspector
        List<GnssEvidenceItem> evidence = new ArrayList<>();
        if (activeNotice != null) {
            String src = nonBlankOr(activeNotice.noticeId(), nonBlankOr(activeNotice.sourceName(), "Outage Notice"));
            evidence.add(new GnssEvidenceItem(
                    src,
                    activeNotice.startEpochMillis(),
                    nonBlankOr(activeNotice.noticeType(), "OUTAGE"),
                    nonBlankOr(activeNotice.summary(), "Active outage notice covering simulation clock"),
                    RULE_OUTAGE_NOTICE.equals(decidedRule),
                    GnssEvidenceItem.Severity.ERROR));
        }
        if (nextPlannedNotice != null) {
            String src = nonBlankOr(nextPlannedNotice.noticeId(), nonBlankOr(nextPlannedNotice.sourceName(), "Planned Notice"));
            evidence.add(new GnssEvidenceItem(
                    src,
                    nextPlannedNotice.startEpochMillis(),
                    nonBlankOr(nextPlannedNotice.noticeType(), "PLANNED"),
                    nonBlankOr(nextPlannedNotice.summary(), "Upcoming planned outage"),
                    false,
                    GnssEvidenceItem.Severity.WARN));
        }
        if (manualOverride != null && manualOverride.status() != null) {
            boolean decisive = RULE_MANUAL_OVERRIDE.equals(decidedRule);
            GnssEvidenceItem.Severity sev = switch (manualOverride.status()) {
                case OPERATIONAL -> GnssEvidenceItem.Severity.WARN;
                case TESTING -> GnssEvidenceItem.Severity.WARN;
                case UNAVAILABLE, NO_STATUS_DATA -> GnssEvidenceItem.Severity.ERROR;
            };
            String reason = "Manual status override: " + manualOverride.status().getDisplayLabel()
                    + (manualOverride.note() != null && !manualOverride.note().isBlank()
                    ? " — " + manualOverride.note()
                    : "");
            evidence.add(new GnssEvidenceItem(
                    "Manual list",
                    0L,
                    manualOverride.status().getDisplayLabel(),
                    reason,
                    decisive,
                    sev));
        }
        if (operatorStatus != null) {
            boolean decisive = RULE_OPERATOR_TESTING.equals(decidedRule)
                    || RULE_OPERATOR_NOT_AVAILABLE.equals(decidedRule)
                    || (RULE_BROADCAST_HEALTHY.equals(decidedRule) && broadcast == null);
            GnssEvidenceItem.Severity sev = switch (operatorStatus.status()) {
                case USABLE -> GnssEvidenceItem.Severity.OK;
                case TESTING -> GnssEvidenceItem.Severity.WARN;
                case NOT_AVAILABLE -> GnssEvidenceItem.Severity.ERROR;
            };
            String raw = nonBlankOr(operatorStatus.rawLabel(), operatorStatus.status().name());
            String reason = nonBlankOr(operatorStatus.detail(), "Operator constellation status: " + raw);
            evidence.add(new GnssEvidenceItem(
                    nonBlankOr(operatorStatus.sourceName(), "Operator status"),
                    operatorStatus.fetchedAtMillis(),
                    raw,
                    reason,
                    decisive,
                    sev));
        }
        if (broadcast != null && !broadcastAbsent) {
            boolean decisive = RULE_BROADCAST_UNHEALTHY.equals(decidedRule)
                    || RULE_BROADCAST_HEALTHY.equals(decidedRule);
            String raw = broadcast.healthy()
                    ? "healthy (" + broadcast.rawHealthCode() + ")"
                    : "unhealthy (" + broadcast.rawHealthCode() + ")";
            String reason = nonBlankOr(broadcast.detail(),
                    "Broadcast nav (" + nonBlankOr(broadcast.messageType(), "NAV") + "): " + raw);
            evidence.add(new GnssEvidenceItem(
                    "Broadcast nav",
                    broadcast.epochMillis(),
                    raw,
                    reason,
                    decisive,
                    broadcast.healthy() ? GnssEvidenceItem.Severity.OK : GnssEvidenceItem.Severity.ERROR));
        } else if (broadcastAbsent) {
            long ageHours = broadcast != null
                    ? Math.max(1L, (broadcastFeedLatestEpochMillis - broadcast.epochMillis()) / 3600_000L)
                    : 0L;
            String reason = broadcast != null
                    ? "Not broadcasting in recent navigation data (last seen " + ageHours + " h before latest nav epoch)"
                    : "Missing from fresh broadcast navigation data";
            evidence.add(new GnssEvidenceItem(
                    "Broadcast nav",
                    broadcastFeedSyncedMillis > 0L ? broadcastFeedSyncedMillis : broadcastFeedLatestEpochMillis,
                    "ABSENT",
                    reason,
                    RULE_BROADCAST_ABSENT.equals(decidedRule),
                    GnssEvidenceItem.Severity.ERROR));
        }
        if (!noticesFeedAvailable) {
            evidence.add(new GnssEvidenceItem(
                    "Outage notices",
                    0L,
                    "UNAVAILABLE",
                    "Notices not available for this constellation",
                    false,
                    GnssEvidenceItem.Severity.MUTED));
        }
        if (RULE_NO_EVIDENCE.equals(decidedRule)) {
            evidence.add(new GnssEvidenceItem(
                    "Status",
                    0L,
                    "NO_DATA",
                    "No broadcast navigation or operator status data received yet",
                    true,
                    GnssEvidenceItem.Severity.MUTED));
        }

        boolean manualOperationalOverride =
                (status == GnssDisplayStatus.OPERATIONAL && RULE_MANUAL_OVERRIDE.equals(decidedRule));

        return new GnssStatusResult(
                status,
                primarySource,
                decidedRule,
                List.copyOf(evidence),
                activeNotice,
                nextPlannedNotice,
                manualOperationalOverride);
    }

    /**
     * Returns the earliest epoch strictly after {@code epochMillis} where a notice becomes active or
     * expires, or {@code Long.MAX_VALUE} if no boundary exists.
     */
    public static long nextNoticeBoundaryAfter(List<GnssOutageNotice> notices, long epochMillis) {
        if (notices == null || notices.isEmpty()) {
            return Long.MAX_VALUE;
        }
        long next = Long.MAX_VALUE;
        for (GnssOutageNotice n : notices) {
            if (n == null || n.cancelled()) {
                continue;
            }
            if (n.startEpochMillis() > epochMillis && n.startEpochMillis() < next) {
                next = n.startEpochMillis();
            }
            if (n.endEpochMillis() > 0L && n.endEpochMillis() >= epochMillis) {
                long expiry = n.endEpochMillis() + 1L;
                if (expiry > epochMillis && expiry < next) {
                    next = expiry;
                }
            }
        }
        return next;
    }

    private static String nonBlankOr(String candidate, String fallback) {
        return (candidate != null && !candidate.isBlank()) ? candidate.trim() : fallback;
    }
}

package jsattrak.awareness.gnss.net;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.GnssOutageNotice;
import jsattrak.awareness.gnss.OperatorStatusRecord;

/**
 * Parser for USCG NAVCEN GPS constellation status reports and Notice Advisories to Navstar Users (NANUs).
 */
public final class NavcenParser {

    private static final Pattern PRN_PATTERN = Pattern.compile("\\b(?:PRN\\s*-?\\s*|G)(\\d{1,2})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NANU_NUM_PATTERN = Pattern.compile("(?:NANU\\s+NUMBER:|NANU\\)\\s*)(\\d{6,8})", Pattern.CASE_INSENSITIVE);
    private static final Pattern NANU_TYPE_PATTERN = Pattern.compile("NANU\\s+TYPE:\\s*([A-Z0-9_-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REF_NANU_PATTERN = Pattern.compile("REFERENCE\\s+NANU:\\s*(\\d{6,8})", Pattern.CASE_INSENSITIVE);
    private static final Pattern SVN_PATTERN = Pattern.compile("\\bSVN\\s*-?\\s*(\\d{2,3})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern START_TIME_PATTERN = Pattern.compile("START\\s+TIME:\\s*(\\d{4})\\s+(\\d{3})\\s+(\\d{4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern STOP_TIME_PATTERN = Pattern.compile("STOP\\s+TIME:\\s*(\\d{4})\\s+(\\d{3})\\s+(\\d{4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUBJ_PATTERN = Pattern.compile("SUBJ:\\s*(.+)");

    private NavcenParser() {
    }

    /**
     * Parses GPS constellation status rows from CSV, plain text, or stripped HTML table rows.
     */
    public static List<OperatorStatusRecord> parseConstellationStatus(String content, long fetchedAtMillis) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String normalized = content.replaceAll("(?i)</tr>", "\n").replaceAll("<[^>]+>", " ");
        List<OperatorStatusRecord> results = new ArrayList<>();
        Set<String> seenPrns = new HashSet<>();

        for (String rawLine : normalized.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.toUpperCase(Locale.ROOT).startsWith("PRN,")) {
                continue;
            }
            Matcher prnMatcher = PRN_PATTERN.matcher(line);
            if (!prnMatcher.find()) {
                continue;
            }
            int prnNum;
            try {
                prnNum = Integer.parseInt(prnMatcher.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (prnNum < 1 || prnNum > 32) {
                continue;
            }
            String prnId = String.format("G%02d", prnNum);
            OperatorStatusRecord.OperatorHealth health = classifyStatusText(line);
            if (health == null) {
                continue;
            }
            if (!seenPrns.add(prnId)) {
                continue;
            }
            String rawLabel = extractRawStatusLabel(line, health);
            results.add(new OperatorStatusRecord(
                    prnId,
                    GnssConstellation.GPS,
                    "NAVCEN",
                    health,
                    rawLabel,
                    fetchedAtMillis,
                    "NAVCEN GPS status: " + rawLabel));
        }
        return List.copyOf(results);
    }

    /**
     * Parses a single plain-text NANU notice.
     */
    public static Optional<GnssOutageNotice> parseSingleNanu(String nanuText) {
        if (nanuText == null || nanuText.isBlank()) {
            return Optional.empty();
        }
        Matcher numMatcher = NANU_NUM_PATTERN.matcher(nanuText);
        if (!numMatcher.find()) {
            return Optional.empty();
        }
        String nanuNum = numMatcher.group(1);
        String noticeId = "NANU " + nanuNum;

        Matcher typeMatcher = NANU_TYPE_PATTERN.matcher(nanuText);
        String noticeType = typeMatcher.find() ? typeMatcher.group(1).toUpperCase(Locale.ROOT) : "NANU";

        Matcher prnMatcher = PRN_PATTERN.matcher(nanuText);
        String prnId = "";
        if (prnMatcher.find()) {
            int prnNum = Integer.parseInt(prnMatcher.group(1));
            if (prnNum >= 1 && prnNum <= 32) {
                prnId = String.format("G%02d", prnNum);
            }
        }
        if (prnId.isEmpty()) {
            return Optional.empty();
        }

        Matcher svnMatcher = SVN_PATTERN.matcher(nanuText);
        String svn = svnMatcher.find() ? "G0" + svnMatcher.group(1) : "";

        Matcher startMatcher = START_TIME_PATTERN.matcher(nanuText);
        long startMillis = startMatcher.find() ? parseNanuTimestamp(startMatcher) : 0L;

        Matcher stopMatcher = STOP_TIME_PATTERN.matcher(nanuText);
        long endMillis = stopMatcher.find() ? parseNanuTimestamp(stopMatcher) : 0L;

        boolean isCancel = noticeType.contains("CANC");
        boolean planned = noticeType.startsWith("FCST") && !isCancel;

        Matcher subjMatcher = SUBJ_PATTERN.matcher(nanuText);
        String summary = subjMatcher.find() ? subjMatcher.group(1).trim() : noticeType;

        Matcher refMatcher = REF_NANU_PATTERN.matcher(nanuText);
        if (isCancel && refMatcher.find()) {
            summary = "CANCELS NANU " + refMatcher.group(1) + " — " + summary;
        }

        return Optional.of(new GnssOutageNotice(
                noticeId,
                GnssConstellation.GPS,
                prnId,
                svn,
                noticeType,
                planned,
                isCancel,
                startMillis,
                endMillis,
                summary,
                "NAVCEN"));
    }

    /**
     * Marks any target NANU referenced by a cancellation NANU as {@code cancelled = true} and
     * drops standalone cancellation meta-notices from active outage lists.
     */
    public static List<GnssOutageNotice> applyCancellations(List<GnssOutageNotice> rawNotices) {
        if (rawNotices == null || rawNotices.isEmpty()) {
            return List.of();
        }
        Set<String> cancelledIds = new HashSet<>();
        Pattern refExtract = Pattern.compile("CANCELS\\s+NANU\\s+(\\d{6,8})", Pattern.CASE_INSENSITIVE);
        for (GnssOutageNotice n : rawNotices) {
            if (n != null && n.cancelled() && n.summary() != null) {
                Matcher m = refExtract.matcher(n.summary());
                if (m.find()) {
                    cancelledIds.add("NANU " + m.group(1));
                }
            }
        }
        List<GnssOutageNotice> resolved = new ArrayList<>();
        for (GnssOutageNotice n : rawNotices) {
            if (n == null) {
                continue;
            }
            if (n.noticeType() != null && n.noticeType().contains("CANC")) {
                continue;
            }
            boolean isCancelled = n.cancelled() || cancelledIds.contains(n.noticeId());
            resolved.add(isCancelled == n.cancelled()
                    ? n
                    : new GnssOutageNotice(
                    n.noticeId(), n.constellation(), n.prnId(), n.svn(), n.noticeType(),
                    n.planned(), true, n.startEpochMillis(), n.endEpochMillis(), n.summary(), n.sourceName()));
        }
        return List.copyOf(resolved);
    }

    private static long parseNanuTimestamp(Matcher m) {
        int year = Integer.parseInt(m.group(1));
        int doy = Math.max(1, Math.min(366, Integer.parseInt(m.group(2))));
        String hhmm = m.group(3);
        int hour = Integer.parseInt(hhmm.substring(0, 2));
        int minute = Integer.parseInt(hhmm.substring(2, 4));
        return LocalDate.ofYearDay(year, doy)
                .atTime(hour % 24, minute % 60)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli() + (hour >= 24 ? 86_400_000L : 0L);
    }

    private static OperatorStatusRecord.OperatorHealth classifyStatusText(String line) {
        String upper = line.toUpperCase(Locale.ROOT);
        if (upper.contains("UNUSABLE") || upper.contains("UNHEALTHY")
                || upper.contains("DECOMMISSIONED") || upper.contains("NON-OPERATIONAL")) {
            return OperatorStatusRecord.OperatorHealth.NOT_AVAILABLE;
        }
        if (upper.contains("COMMISSIONING") || upper.contains("TESTING")
                || upper.contains("USABINIT PENDING") || upper.contains("LAUNCHED")) {
            return OperatorStatusRecord.OperatorHealth.TESTING;
        }
        if (upper.contains("OPERATIONAL") || upper.contains("HEALTHY") || upper.contains("USABLE")) {
            return OperatorStatusRecord.OperatorHealth.USABLE;
        }
        return null;
    }

    private static String extractRawStatusLabel(String line, OperatorStatusRecord.OperatorHealth health) {
        String[] csvParts = line.split(",");
        if (csvParts.length >= 3) {
            return csvParts[2].trim();
        }
        return switch (health) {
            case USABLE -> "OPERATIONAL";
            case TESTING -> "COMMISSIONING";
            case NOT_AVAILABLE -> "UNUSABLE";
        };
    }
}

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
    private static final Pattern TR_BLOCK_PATTERN = Pattern.compile("<tr\\b[^>]*>(.*?)</tr>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern BARE_FIRST_INT_PATTERN = Pattern.compile("^(\\d{1,2})\\b");
    private static final Pattern NANU_NUM_PATTERN = Pattern.compile("(?:NANU\\s+NUMBER:|NANU\\)\\s*)(\\d{6,8})", Pattern.CASE_INSENSITIVE);
    private static final Pattern NANU_TYPE_PATTERN = Pattern.compile("NANU\\s+TYPE:\\s*([A-Z0-9_-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REF_NANU_PATTERN = Pattern.compile("REFERENCE\\s+NANU:\\s*(\\d{6,8})", Pattern.CASE_INSENSITIVE);
    private static final Pattern SVN_PATTERN = Pattern.compile("\\bSVN\\s*-?\\s*(\\d{2,3})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern START_TIME_PATTERN = Pattern.compile("START\\s+TIME:\\s*(\\d{4})\\s+(\\d{3})\\s+(\\d{4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern STOP_TIME_PATTERN = Pattern.compile("STOP\\s+TIME:\\s*(\\d{4})\\s+(\\d{3})\\s+(\\d{4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUBJ_PATTERN = Pattern.compile("SUBJ:\\s*(.+)");

    private static final Pattern JDAY_RANGE_PATTERN = Pattern.compile(
            "JDAY\\s*(\\d{1,3})/(\\d{4})(?:\\s*-\\s*JDAY\\s*(\\d{1,3})/(\\d{4}))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NNU_LINK_PATTERN = Pattern.compile("(\\d{6,8})\\.nnu", Pattern.CASE_INSENSITIVE);

    private NavcenParser() {
    }

    /**
     * Parses GPS constellation status rows from CSV, plain text, or HTML table rows.
     */
    public static List<OperatorStatusRecord> parseConstellationStatus(String content, long fetchedAtMillis) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> candidateLines = extractStatusRows(content);
        List<OperatorStatusRecord> results = new ArrayList<>();
        Set<String> seenPrns = new HashSet<>();
        boolean tableHasPrnColumn = false;

        for (String rawLine : candidateLines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String upperLine = line.toUpperCase(Locale.ROOT);
            if (upperLine.startsWith("PRN,")
                    || (!PRN_PATTERN.matcher(line).find()
                            && !SVN_PATTERN.matcher(line).find()
                            && upperLine.contains("PRN")
                            && (upperLine.contains("SVN") || upperLine.contains("STATUS")))) {
                tableHasPrnColumn = true;
                continue;
            }
            Matcher prnMatcher = PRN_PATTERN.matcher(line);
            String prnDigits = null;
            if (prnMatcher.find()) {
                prnDigits = prnMatcher.group(1);
            } else if (tableHasPrnColumn) {
                Matcher bare = BARE_FIRST_INT_PATTERN.matcher(line);
                if (bare.find()) {
                    prnDigits = bare.group(1);
                }
            }
            if (prnDigits == null) {
                continue;
            }
            int prnNum;
            try {
                prnNum = Integer.parseInt(prnDigits);
            } catch (NumberFormatException e) {
                continue;
            }
            if (prnNum < 1 || prnNum > 32) {
                continue;
            }
            String prnId = String.format("G%02d", prnNum);
            OperatorStatusRecord.OperatorHealth health = classifyStatusText(line);
            if (health == null && tableHasPrnColumn && SVN_PATTERN.matcher(line).find()) {
                // Active NAVCEN constellation rows often leave Remarks blank when nominal
                health = OperatorStatusRecord.OperatorHealth.USABLE;
            }
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

    private static List<String> extractStatusRows(String content) {
        Matcher trMatcher = TR_BLOCK_PATTERN.matcher(content);
        List<String> trRows = new ArrayList<>();
        while (trMatcher.find()) {
            String rawRow = trMatcher.group(1);
            if (rawRow.contains("<td") && rawRow.contains("views-field-field-gps-prn")) {
                String prnText = extractCellByClass(rawRow, "views-field-field-gps-prn");
                String svnText = extractCellByClass(rawRow, "views-field-field-gps-svn");
                String nanuType = extractCellByClass(rawRow, "views-field-field-nanu-type");
                String nanuSubj = extractCellByClass(rawRow, "views-field-field-nanu-subject");
                String activeFlag = extractCellByClass(rawRow, "nanu-active-check");
                if (!prnText.isEmpty()) {
                    if ("0".equals(activeFlag)) {
                        trRows.add("PRN" + prnText + " SVN" + svnText + " OPERATIONAL");
                    } else {
                        trRows.add("PRN" + prnText + " SVN" + svnText + " " + nanuType + " " + nanuSubj);
                    }
                    continue;
                }
            }
            String inner = rawRow
                    .replaceAll("(?i)<t[dh][^>]*>", " ")
                    .replaceAll("<[^>]+>", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (!inner.isEmpty()) {
                trRows.add(inner);
            }
        }
        if (!trRows.isEmpty()) {
            return trRows;
        }
        String stripped = content.replaceAll("(?i)</tr>", "\n").replaceAll("<[^>]+>", " ");
        List<String> lines = new ArrayList<>();
        for (String line : stripped.split("\\R")) {
            lines.add(line);
        }
        return lines;
    }

    /**
     * Parses NANU summary entries embedded in NAVCEN's Drupal {@code /gps-constellation} HTML table.
     */
    public static List<GnssOutageNotice> parseHtmlTableNanus(String html) {
        if (html == null || !html.contains("views-field-field-nanu-subject")) {
            return List.of();
        }
        List<GnssOutageNotice> raw = new ArrayList<>();
        Matcher trMatcher = TR_BLOCK_PATTERN.matcher(html);
        while (trMatcher.find()) {
            String rawRow = trMatcher.group(1);
            if (!rawRow.contains("<td") || !rawRow.contains("views-field-field-nanu-subject")) {
                continue;
            }
            Matcher nnuMatcher = NNU_LINK_PATTERN.matcher(rawRow);
            if (!nnuMatcher.find()) {
                continue;
            }
            String nanuNum = nnuMatcher.group(1);
            String prnText = extractCellByClass(rawRow, "views-field-field-gps-prn");
            String svnText = extractCellByClass(rawRow, "views-field-field-gps-svn");
            String nanuType = extractCellByClass(rawRow, "views-field-field-nanu-type").toUpperCase(Locale.ROOT);
            String nanuSubj = extractCellByClass(rawRow, "views-field-field-nanu-subject");
            String activeFlag = extractCellByClass(rawRow, "nanu-active-check");

            String prnId = normalizeGpsPrn(prnText);
            if (prnId.isEmpty()) {
                Matcher pm = PRN_PATTERN.matcher(nanuSubj);
                if (pm.find()) {
                    prnId = normalizeGpsPrn(pm.group(1));
                }
            }
            if (prnId.isEmpty()) {
                continue;
            }

            String svn = normalizeSvn(svnText);
            String noticeType = nanuType.isEmpty() ? "NANU" : nanuType;
            int year = parseYearFromNanuNum(nanuNum);
            long startMillis = 0L;
            long endMillis = 0L;
            Matcher jday = JDAY_RANGE_PATTERN.matcher(nanuSubj);
            if (jday.find() && year > 0) {
                int startDoy = Integer.parseInt(jday.group(1));
                startMillis = parseDoyHhmm(year, startDoy, jday.group(2));
                if (jday.group(3) != null && jday.group(4) != null) {
                    int stopDoy = Integer.parseInt(jday.group(3));
                    int stopYear = stopDoy < startDoy ? year + 1 : year;
                    endMillis = parseDoyHhmm(stopYear, stopDoy, jday.group(4));
                }
            }
            if ("0".equals(activeFlag) && endMillis <= 0L) {
                continue;
            }
            boolean isCancel = noticeType.contains("CANC");
            boolean planned = noticeType.startsWith("FCST") && !isCancel;
            raw.add(new GnssOutageNotice(
                    "NANU " + nanuNum,
                    GnssConstellation.GPS,
                    prnId,
                    svn,
                    noticeType,
                    planned,
                    isCancel,
                    startMillis,
                    endMillis,
                    nanuSubj.isEmpty() ? noticeType : nanuSubj,
                    "NAVCEN"));
        }
        return applyCancellations(raw);
    }

    private static String extractCellByClass(String rowHtml, String cssClass) {
        Pattern p = Pattern.compile(
                "<td\\b[^>]*class\\s*=\\s*[\"'][^\"']*\\b" + Pattern.quote(cssClass)
                        + "\\b[^\"']*[\"'][^>]*>(.*?)</td>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(rowHtml);
        if (!m.find()) {
            return "";
        }
        return m.group(1).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String normalizeGpsPrn(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            int prnNum = Integer.parseInt(raw.trim());
            return (prnNum >= 1 && prnNum <= 32) ? String.format("G%02d", prnNum) : "";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static String normalizeSvn(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return "";
        }
        return "G" + (digits.length() == 2 ? "0" + digits : digits);
    }

    private static int parseYearFromNanuNum(String nanuNum) {
        if (nanuNum != null && nanuNum.length() >= 4) {
            try {
                return Integer.parseInt(nanuNum.substring(0, 4));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private static long parseDoyHhmm(int year, int rawDoy, String hhmm) {
        try {
            int maxDoy = java.time.Year.isLeap(year) ? 366 : 365;
            int doy = Math.max(1, Math.min(maxDoy, rawDoy));
            int hour = Integer.parseInt(hhmm.substring(0, 2));
            int minute = Integer.parseInt(hhmm.substring(2, 4));
            return LocalDate.ofYearDay(year, doy)
                    .atTime(hour % 24, minute % 60)
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli() + (hour >= 24 ? 86_400_000L : 0L);
        } catch (RuntimeException e) {
            return 0L;
        }
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
        String svn = "";
        if (svnMatcher.find()) {
            String digits = svnMatcher.group(1);
            svn = "G" + (digits.length() == 2 ? "0" + digits : digits);
        }

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
        try {
            int year = Integer.parseInt(m.group(1));
            int maxDoy = java.time.Year.isLeap(year) ? 366 : 365;
            int doy = Math.max(1, Math.min(maxDoy, Integer.parseInt(m.group(2))));
            String hhmm = m.group(3);
            int hour = Integer.parseInt(hhmm.substring(0, 2));
            int minute = Integer.parseInt(hhmm.substring(2, 4));
            return LocalDate.ofYearDay(year, doy)
                    .atTime(hour % 24, minute % 60)
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli() + (hour >= 24 ? 86_400_000L : 0L);
        } catch (RuntimeException e) {
            return 0L;
        }
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

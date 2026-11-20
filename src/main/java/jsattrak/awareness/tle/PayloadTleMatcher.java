package jsattrak.awareness.tle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jsattrak.awareness.LaunchRecord;
import jsattrak.utilities.TLE;

/**
 * Parses 3-line TLE catalogs, matches launch records to official COSPAR or supplemental SupGP
 * feeds, classifies spacecraft/debris types, and flags objects already present in the scenario.
 */
public final class PayloadTleMatcher {

    private static final int COSPAR_PIVOT_YEAR = 57;
    private static final Pattern ROCKET_BODY_PATTERN = Pattern.compile("(?:^|[^A-Z0-9])R/B(?:[^A-Z0-9]|$)");
    private static final Pattern DEBRIS_PATTERN = Pattern.compile("(?:^|[^A-Z0-9])DEB(?:[^A-Z0-9]|$)");
    private static final Pattern FULL_COSPAR_PATTERN = Pattern.compile("^(\\d{4})-(\\d{1,3})");
    private static final Pattern COMPACT_COSPAR_PATTERN = Pattern.compile("^(\\d{2})(\\d{3})");
    private static final Set<String> GENERIC_MISSION_TOKENS = Set.of(
            "test", "sat", "satellite", "launch", "flight", "mission", "group", "demo", "space");

    private PayloadTleMatcher() {
    }

    /**
     * Parses a 3-line (or 2-line fallback) TLE catalog text into {@link TLE} instances.
     */
    public static List<TLE> parseThreeLineTles(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] lines = text.split("\\R");
        List<TLE> results = new ArrayList<>();
        String pendingName = null;
        String pendingLine1 = null;

        for (String rawLine : lines) {
            String line = rawLine.stripTrailing();
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (pendingLine1 != null) {
                if (trimmed.startsWith("2 ") && trimmed.length() >= 7) {
                    String name = (pendingName != null && !pendingName.isBlank())
                            ? pendingName
                            : ("NORAD " + extractNoradId(new TLE("", pendingLine1, line)));
                    results.add(new TLE(name, pendingLine1, line));
                    pendingName = null;
                    pendingLine1 = null;
                    continue;
                }
                pendingLine1 = null;
                pendingName = null;
            }
            if (trimmed.startsWith("1 ") && trimmed.length() >= 18) {
                pendingLine1 = line;
            } else {
                pendingName = trimmed.startsWith("0 ") && trimmed.length() > 2
                        ? trimmed.substring(2).trim()
                        : trimmed;
            }
        }
        return List.copyOf(results);
    }

    /**
     * Extracts the normalized {@code YYYY-NNN} launch designator prefix from TLE Line 1 columns 9–17.
     */
    public static String extractDesignatorPrefix(TLE tle) {
        DesignatorParts parts = parseDesignatorParts(tle);
        return parts == null ? "" : parts.prefix();
    }

    /**
     * Classifies a catalog object name as rocket body, debris, or payload.
     */
    public static PayloadTleMatch.ObjectType classifyObject(String objectName) {
        if (objectName == null || objectName.isBlank()) {
            return PayloadTleMatch.ObjectType.PAYLOAD;
        }
        String upper = objectName.toUpperCase(Locale.ROOT).trim();
        if (ROCKET_BODY_PATTERN.matcher(upper).find()) {
            return PayloadTleMatch.ObjectType.ROCKET_BODY;
        }
        if (DEBRIS_PATTERN.matcher(upper).find()) {
            return PayloadTleMatch.ObjectType.DEBRIS;
        }
        return PayloadTleMatch.ObjectType.PAYLOAD;
    }

    /**
     * Matches a launch against the official catalog by COSPAR prefix, falling back to the
     * supplemental catalog by COSPAR prefix or normalized mission name tokens.
     */
    public static List<PayloadTleMatch> matchLaunch(
            LaunchRecord launch,
            List<TLE> officialCatalog,
            List<TLE> supplementalCatalog,
            Set<String> existingSatNames,
            Set<Integer> existingNoradIds) {
        if (launch == null || !launch.isOrbital() || isNoOrbitalOutcome(launch)) {
            return List.of();
        }

        Set<String> normalizedNames = normalizeExistingNames(existingSatNames);
        Set<Integer> noradIds = existingNoradIds != null ? existingNoradIds : Set.of();
        String launchCosparPrefix = normalizeLaunchCosparPrefix(launch.getCosparId());

        List<PayloadTleMatch> officialMatches = new ArrayList<>();
        if (!launchCosparPrefix.isEmpty() && officialCatalog != null && !officialCatalog.isEmpty()) {
            for (TLE tle : officialCatalog) {
                if (tle == null) {
                    continue;
                }
                if (launchCosparPrefix.equals(extractDesignatorPrefix(tle))) {
                    officialMatches.add(toMatch(tle, "Official", false, normalizedNames, noradIds));
                }
            }
        }
        if (!officialMatches.isEmpty()) {
            return List.copyOf(officialMatches);
        }

        List<PayloadTleMatch> supplementalMatches = new ArrayList<>();
        if (supplementalCatalog != null && !supplementalCatalog.isEmpty()) {
            List<String> missionTokens = extractMissionTokens(launch.getMissionName());
            for (TLE tle : supplementalCatalog) {
                if (tle == null) {
                    continue;
                }
                boolean cosparMatch = !launchCosparPrefix.isEmpty()
                        && launchCosparPrefix.equals(extractDesignatorPrefix(tle));
                boolean missionMatch = matchesMissionTokens(missionTokens, tle.getSatName());
                if (cosparMatch || missionMatch) {
                    supplementalMatches.add(toMatch(tle, "SupGP", true, normalizedNames, noradIds));
                }
            }
        }
        return List.copyOf(supplementalMatches);
    }

    /**
     * Evaluates the overall TLE readiness status for a launch given its candidate matches.
     */
    public static LaunchTleStatus evaluateStatus(LaunchRecord launch, List<PayloadTleMatch> matches) {
        if (launch == null || !launch.isOrbital() || isNoOrbitalOutcome(launch)) {
            return LaunchTleStatus.NO_ORBITAL_PAYLOAD;
        }
        if (matches == null || matches.isEmpty()) {
            return LaunchTleStatus.AWAITING_TLE;
        }
        for (PayloadTleMatch match : matches) {
            if (match != null && !match.supplemental()) {
                return LaunchTleStatus.TLES_READY;
            }
        }
        return LaunchTleStatus.SUPPLEMENTAL_READY;
    }

    private static PayloadTleMatch toMatch(
            TLE tle,
            String sourceLabel,
            boolean supplemental,
            Set<String> normalizedNames,
            Set<Integer> existingNoradIds) {
        String rawName = tle.getSatName();
        int noradId = extractNoradId(tle);
        String name = (rawName != null && !rawName.isBlank()) ? rawName.trim() : ("NORAD " + noradId);
        DesignatorParts designator = parseDesignatorParts(tle);
        String fullDesignator = designator != null ? designator.fullDesignator() : "";
        PayloadTleMatch.ObjectType type = classifyObject(name);
        boolean tracked = (noradId > 0 && existingNoradIds.contains(noradId))
                || normalizedNames.contains(name.toLowerCase(Locale.ROOT));
        return new PayloadTleMatch(name, fullDesignator, noradId, type, sourceLabel, supplemental, tracked, tle);
    }

    private static DesignatorParts parseDesignatorParts(TLE tle) {
        if (tle == null || tle.getLine1() == null) {
            return null;
        }
        String line1 = tle.getLine1();
        if (line1.length() < 14) {
            return null;
        }
        int end = Math.min(17, line1.length());
        String field = line1.substring(9, end).trim();
        if (field.length() < 5) {
            return null;
        }
        for (int i = 0; i < 5; i++) {
            if (!Character.isDigit(field.charAt(i))) {
                return null;
            }
        }
        int yy = Integer.parseInt(field.substring(0, 2));
        int fullYear = (yy >= COSPAR_PIVOT_YEAR) ? (1900 + yy) : (2000 + yy);
        String launchNum = field.substring(2, 5);
        String piece = field.substring(5).trim().toUpperCase(Locale.ROOT);
        String prefix = fullYear + "-" + launchNum;
        return new DesignatorParts(prefix, prefix + piece);
    }

    static int extractNoradId(TLE tle) {
        if (tle == null || tle.getLine1() == null) {
            return 0;
        }
        String line1 = tle.getLine1();
        if (line1.length() < 7) {
            return 0;
        }
        String field = line1.substring(2, 7).trim().toUpperCase(Locale.ROOT);
        if (field.isEmpty()) {
            return 0;
        }
        if (Character.isDigit(field.charAt(0))) {
            try {
                return Integer.parseInt(field);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        // Alpha-5 encoding for catalog numbers >= 100000 (A..Z skipping I and O)
        if (field.length() == 5 && Character.isLetter(field.charAt(0))) {
            int prefixVal = alpha5PrefixValue(field.charAt(0));
            if (prefixVal >= 10) {
                try {
                    return prefixVal * 10_000 + Integer.parseInt(field.substring(1));
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static int alpha5PrefixValue(char c) {
        if (c >= 'A' && c <= 'H') {
            return 10 + (c - 'A');
        }
        if (c >= 'J' && c <= 'N') {
            return 18 + (c - 'J');
        }
        if (c >= 'P' && c <= 'Z') {
            return 23 + (c - 'P');
        }
        return -1;
    }

    private static String normalizeLaunchCosparPrefix(String cosparId) {
        if (cosparId == null || cosparId.isBlank()) {
            return "";
        }
        String trimmed = cosparId.trim().toUpperCase(Locale.ROOT);
        Matcher full = FULL_COSPAR_PATTERN.matcher(trimmed);
        if (full.find()) {
            int num = Integer.parseInt(full.group(2));
            return full.group(1) + "-" + String.format(Locale.ROOT, "%03d", num);
        }
        Matcher compact = COMPACT_COSPAR_PATTERN.matcher(trimmed);
        if (compact.find()) {
            int yy = Integer.parseInt(compact.group(1));
            int fullYear = (yy >= COSPAR_PIVOT_YEAR) ? (1900 + yy) : (2000 + yy);
            return fullYear + "-" + compact.group(2);
        }
        return "";
    }

    private static boolean isNoOrbitalOutcome(LaunchRecord launch) {
        return isFailureText(launch.getOutcome()) || isFailureText(launch.getStatus());
    }

    private static boolean isFailureText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("partial")) {
            return false;
        }
        return lower.equals("failure")
                || lower.equals("failed")
                || lower.contains("failure")
                || lower.equals("cancelled")
                || lower.equals("canceled");
    }

    private static Set<String> normalizeExistingNames(Set<String> existingSatNames) {
        if (existingSatNames == null || existingSatNames.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new HashSet<>(existingSatNames.size());
        for (String name : existingSatNames) {
            if (name != null && !name.isBlank()) {
                normalized.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        return normalized;
    }

    private static List<String> extractMissionTokens(String missionName) {
        if (missionName == null || missionName.isBlank()) {
            return List.of();
        }
        String effective = missionName;
        int pipeIdx = missionName.lastIndexOf('|');
        if (pipeIdx >= 0 && pipeIdx + 1 < missionName.length()) {
            String afterPipe = missionName.substring(pipeIdx + 1).trim();
            if (!afterPipe.isEmpty()) {
                effective = afterPipe;
            }
        }
        return tokenize(effective);
    }

    private static boolean matchesMissionTokens(List<String> missionTokens, String satName) {
        if (missionTokens.isEmpty() || satName == null || satName.isBlank()) {
            return false;
        }
        List<String> satTokens = tokenize(satName);
        if (satTokens.isEmpty()) {
            return false;
        }
        if (missionTokens.size() == 1) {
            String only = missionTokens.get(0);
            if (only.length() < 4 || GENERIC_MISSION_TOKENS.contains(only)) {
                return false;
            }
            return satTokens.contains(only);
        }
        return isOrderedTokenSubsequence(missionTokens, satTokens);
    }

    private static boolean isOrderedTokenSubsequence(List<String> needle, List<String> haystack) {
        int i = 0;
        for (String token : haystack) {
            if (token.equals(needle.get(i))) {
                i++;
                if (i == needle.size()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> tokenize(String text) {
        String[] parts = text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        List<String> tokens = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (!part.isEmpty()) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private record DesignatorParts(String prefix, String fullDesignator) {
    }
}

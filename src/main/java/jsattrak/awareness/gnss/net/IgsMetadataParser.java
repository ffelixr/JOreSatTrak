package jsattrak.awareness.gnss.net;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jsattrak.awareness.gnss.GnssCatalogEntry;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.GnssOrbitClass;
import jsattrak.awareness.tle.PayloadTleMatcher;
import jsattrak.utilities.TLE;

/**
 * Epoch-aware parser for IGS satellite metadata SINEX ({@code igs_satellite_metadata.snx})
 * and NORAD/COSPAR TLE catalog joiner.
 */
public final class IgsMetadataParser {

    private static final Pattern SINEX_EPOCH_PATTERN = Pattern.compile("^(\\d{4}):(\\d{3}):(\\d{5})$");
    private static final Pattern FULL_COSPAR_PATTERN = Pattern.compile("^(\\d{4})-(\\d{3}[A-Z0-9]+)$");
    private static final Pattern TLE_COSPAR_PATTERN = Pattern.compile("^(\\d{2})(\\d{3}[A-Z0-9]+)$");
    private static final Pattern INTEGER_PATTERN = Pattern.compile("^\\d{4,6}$");

    private IgsMetadataParser() {
    }

    /**
     * Parses IGS SINEX metadata and resolves the satellite catalog active at
     * {@code referenceEpochMillis} across Layer-1/SBAS constellations.
     */
    public static List<GnssCatalogEntry> parseSinex(String sinexText, long referenceEpochMillis) {
        if (sinexText == null || sinexText.isBlank()) {
            return List.of();
        }
        Map<String, IdentifierInfo> identifiersBySvn = new LinkedHashMap<>();
        Map<String, PrnAssignment> activePrnByPrnId = new LinkedHashMap<>();
        Map<String, PlaneSlot> activePlaneBySvn = new HashMap<>();

        String section = "";
        for (String rawLine : sinexText.split("\\R")) {
            if (rawLine.isEmpty()) {
                continue;
            }
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("*") || trimmed.startsWith("%")) {
                continue;
            }
            if (trimmed.startsWith("+")) {
                section = trimmed.substring(1).trim().toUpperCase(Locale.ROOT);
                continue;
            }
            if (trimmed.startsWith("-")) {
                section = "";
                continue;
            }

            switch (section) {
                case "SATELLITE/IDENTIFIER" -> parseIdentifierLine(trimmed, identifiersBySvn);
                case "SATELLITE/PRN" -> parsePrnLine(trimmed, referenceEpochMillis, activePrnByPrnId);
                case "SATELLITE/PLANE" -> parsePlaneLine(trimmed, referenceEpochMillis, activePlaneBySvn);
                default -> {
                }
            }
        }

        List<GnssCatalogEntry> entries = new ArrayList<>();
        for (PrnAssignment prn : activePrnByPrnId.values()) {
            GnssConstellation constellation = prn.constellation();
            if (constellation == null) {
                continue;
            }
            IdentifierInfo idInfo = identifiersBySvn.get(prn.svn());
            PlaneSlot planeSlot = activePlaneBySvn.get(prn.svn());
            String cospar = idInfo != null ? idInfo.cosparId() : "";
            int noradId = idInfo != null ? idInfo.noradId() : 0;
            String block = idInfo != null ? idInfo.blockType() : "";
            String launchIso = idInfo != null ? idInfo.launchDateIso() : "";
            GnssOrbitClass orbitClass = inferOrbitClass(constellation, block, prn.prnNumber());
            String plane = planeSlot != null ? planeSlot.plane() : "";
            String slot = planeSlot != null ? planeSlot.slot() : "";

            entries.add(new GnssCatalogEntry(
                    prn.prnId(),
                    constellation,
                    prn.prnNumber(),
                    prn.svn(),
                    noradId,
                    cospar,
                    block,
                    orbitClass,
                    plane,
                    slot,
                    launchIso));
        }

        entries.sort(Comparator.comparing(GnssCatalogEntry::prnId));
        return List.copyOf(entries);
    }

    /**
     * Joins catalog entries to TLEs by NORAD catalog number first, then by normalized COSPAR
     * international designator ({@code YYYY-NNNA} &harr; TLE {@code YYNNNA}).
     */
    public static Map<String, TLE> matchTlesToCatalog(List<GnssCatalogEntry> catalog, List<TLE> tles) {
        if (catalog == null || catalog.isEmpty() || tles == null || tles.isEmpty()) {
            return Map.of();
        }
        Map<Integer, TLE> byNorad = new HashMap<>();
        Map<String, TLE> byCospar = new HashMap<>();
        for (TLE tle : tles) {
            if (tle == null) {
                continue;
            }
            int norad = PayloadTleMatcher.extractNoradId(tle);
            if (norad > 0) {
                byNorad.putIfAbsent(norad, tle);
            }
            String cospar = extractNormalizedCosparFromTle(tle);
            if (!cospar.isEmpty()) {
                byCospar.putIfAbsent(cospar, tle);
            }
        }

        Map<String, TLE> matched = new LinkedHashMap<>();
        for (GnssCatalogEntry entry : catalog) {
            if (entry == null || entry.prnId() == null) {
                continue;
            }
            TLE found = null;
            if (entry.noradId() > 0) {
                found = byNorad.get(entry.noradId());
            }
            if (found == null && entry.cosparId() != null && !entry.cosparId().isBlank()) {
                found = byCospar.get(normalizeFullCospar(entry.cosparId()));
            }
            if (found != null) {
                matched.put(entry.prnId(), found);
            }
        }
        return Map.copyOf(matched);
    }

    private static void parseIdentifierLine(String line, Map<String, IdentifierInfo> out) {
        String[] tokens = line.split("\\s+");
        if (tokens.length < 2) {
            return;
        }
        String svn = tokens[0].toUpperCase(Locale.ROOT);
        GnssConstellation constellation = GnssConstellation.fromPrefix(svn.charAt(0));
        if (constellation == null) {
            return;
        }
        String cospar = "";
        int noradId = 0;
        StringBuilder blockBuilder = new StringBuilder();
        String launchIso = "";

        for (int i = 1; i < tokens.length; i++) {
            String tok = tokens[i];
            if (cospar.isEmpty() && FULL_COSPAR_PATTERN.matcher(tok.toUpperCase(Locale.ROOT)).matches()) {
                cospar = tok.toUpperCase(Locale.ROOT);
            } else if (noradId == 0 && tok.matches("\\d{5,6}")) {
                try {
                    noradId = Integer.parseInt(tok);
                } catch (NumberFormatException ignored) {
                }
            } else if (SINEX_EPOCH_PATTERN.matcher(tok).matches()) {
                if (launchIso.isEmpty()) {
                    launchIso = sinexEpochToIsoDate(tok);
                }
            } else if (!tok.isBlank()) {
                if (blockBuilder.length() > 0) {
                    blockBuilder.append(' ');
                }
                blockBuilder.append(tok);
            }
        }
        out.put(svn, new IdentifierInfo(svn, cospar, noradId, blockBuilder.toString(), launchIso));
    }

    private static void parsePrnLine(
            String line,
            long referenceEpochMillis,
            Map<String, PrnAssignment> out) {
        String[] tokens = line.split("\\s+");
        if (tokens.length < 4) {
            return;
        }
        String svn = tokens[0].toUpperCase(Locale.ROOT);
        long validFrom = parseSinexEpochMillis(tokens[1], true);
        long validTo = parseSinexEpochMillis(tokens[2], false);
        if (referenceEpochMillis < validFrom || referenceEpochMillis > validTo) {
            return;
        }
        String prnId = tokens[3].toUpperCase(Locale.ROOT);
        if (prnId.length() < 2) {
            return;
        }
        GnssConstellation constellation = GnssConstellation.fromPrefix(prnId.charAt(0));
        if (constellation == null) {
            return;
        }
        int prnNumber;
        try {
            prnNumber = Integer.parseInt(prnId.substring(1));
        } catch (NumberFormatException e) {
            return;
        }
        out.put(prnId, new PrnAssignment(prnId, constellation, prnNumber, svn, validFrom));
    }

    private static void parsePlaneLine(
            String line,
            long referenceEpochMillis,
            Map<String, PlaneSlot> out) {
        String[] tokens = line.split("\\s+");
        if (tokens.length < 4) {
            return;
        }
        String svn = tokens[0].toUpperCase(Locale.ROOT);
        long validFrom = parseSinexEpochMillis(tokens[1], true);
        long validTo = parseSinexEpochMillis(tokens[2], false);
        if (referenceEpochMillis < validFrom || referenceEpochMillis > validTo) {
            return;
        }
        String plane = tokens[3];
        String slot = tokens.length >= 5 ? tokens[4] : "";
        out.put(svn, new PlaneSlot(plane, slot));
    }

    static long parseSinexEpochMillis(String token, boolean isStartBound) {
        if (token == null || token.isBlank()
                || "0000:000:00000".equals(token.trim())
                || "9999:999:99999".equals(token.trim())) {
            return isStartBound ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        Matcher m = SINEX_EPOCH_PATTERN.matcher(token.trim());
        if (!m.matches()) {
            return isStartBound ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        try {
            int year = Integer.parseInt(m.group(1));
            if (year <= 0 || year >= 9999) {
                return isStartBound ? Long.MIN_VALUE : Long.MAX_VALUE;
            }
            int maxDoy = java.time.Year.isLeap(year) ? 366 : 365;
            int doy = Math.max(1, Math.min(maxDoy, Integer.parseInt(m.group(2))));
            int secOfDay = Math.max(0, Integer.parseInt(m.group(3)));
            Instant inst = LocalDate.ofYearDay(year, doy).atStartOfDay(ZoneOffset.UTC).toInstant()
                    .plusSeconds(secOfDay);
            return inst.toEpochMilli();
        } catch (RuntimeException e) {
            return isStartBound ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    private static String sinexEpochToIsoDate(String token) {
        Matcher m = SINEX_EPOCH_PATTERN.matcher(token.trim());
        if (!m.matches()) {
            return "";
        }
        try {
            int year = Integer.parseInt(m.group(1));
            int rawDoy = Integer.parseInt(m.group(2));
            if (year <= 0 || year >= 9999 || rawDoy <= 0) {
                return "";
            }
            int maxDoy = java.time.Year.isLeap(year) ? 366 : 365;
            return LocalDate.ofYearDay(year, Math.min(maxDoy, rawDoy)).toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    static GnssOrbitClass inferOrbitClass(GnssConstellation constellation, String blockType, int prnNumber) {
        if (constellation == GnssConstellation.SBAS) {
            return GnssOrbitClass.GEO;
        }
        String upper = blockType != null ? blockType.toUpperCase(Locale.ROOT) : "";
        if (upper.contains("IGSO") || upper.contains("-I")) {
            return GnssOrbitClass.IGSO;
        }
        if (upper.contains("GEO") || upper.contains("-G")) {
            return GnssOrbitClass.GEO;
        }
        if (constellation == GnssConstellation.BEIDOU) {
            if (prnNumber >= 1 && prnNumber <= 5 || prnNumber >= 59) {
                return GnssOrbitClass.GEO;
            }
            if ((prnNumber >= 6 && prnNumber <= 10) || prnNumber == 13 || prnNumber == 16
                    || (prnNumber >= 38 && prnNumber <= 40)) {
                return GnssOrbitClass.IGSO;
            }
        }
        return GnssOrbitClass.MEO;
    }

    private static String extractNormalizedCosparFromTle(TLE tle) {
        String line1 = tle.getLine1();
        if (line1 == null || line1.length() < 17) {
            return "";
        }
        String raw = line1.substring(9, 17).trim().toUpperCase(Locale.ROOT);
        Matcher m = TLE_COSPAR_PATTERN.matcher(raw);
        if (!m.matches()) {
            return "";
        }
        int yy = Integer.parseInt(m.group(1));
        int yyyy = (yy >= 57 ? 1900 : 2000) + yy;
        return yyyy + "-" + m.group(2);
    }

    private static String normalizeFullCospar(String cospar) {
        return cospar.trim().toUpperCase(Locale.ROOT);
    }

    private record IdentifierInfo(String svn, String cosparId, int noradId, String blockType, String launchDateIso) {
    }

    private record PrnAssignment(
            String prnId,
            GnssConstellation constellation,
            int prnNumber,
            String svn,
            long validFromMillis) {
    }

    private record PlaneSlot(String plane, String slot) {
    }
}

package jsattrak.awareness.gnss.net;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import jsattrak.awareness.gnss.BroadcastHealthRecord;
import jsattrak.awareness.gnss.GnssConstellation;

/**
 * Streaming RINEX 3.x and 4.x multi-GNSS broadcast navigation health parser (with automatic GZIP
 * detection, BeiDou {@code SatH1} / {@code CNV1-3} health decoding, Galileo per-signal SHS/DVS
 * bitmask decoding, and truncated-tail salvage).
 */
public final class RinexNavHealthParser {

    private static final long GPST_UTC_LEAP_MILLIS = 18_000L;
    private static final long BDT_UTC_LEAP_MILLIS = 4_000L;

    private RinexNavHealthParser() {
    }

    public record ParseResult(
            Map<String, BroadcastHealthRecord> latestByPrn,
            long latestEpochMillis,
            int totalMessagesParsed,
            String rinexVersion) {
    }

    /**
     * Parses RINEX 3/4 navigation records from {@code stream} (raw or GZIP-compressed).
     */
    public static ParseResult parse(InputStream stream) throws IOException {
        Objects.requireNonNull(stream, "stream");
        InputStream decoded = unwrapGzipIfNeeded(stream);
        BufferedReader reader = new BufferedReader(new InputStreamReader(decoded, StandardCharsets.UTF_8));

        String rinexVersion = "";
        boolean inHeader = true;
        boolean skipRinex4Block = false;
        String pendingMsgType = "";

        String currentPrn = null;
        GnssConstellation currentConstellation = null;
        long currentEpochMillis = 0L;
        String currentMsgType = "";
        List<String> orbitLines = new ArrayList<>(8);

        Map<String, BroadcastHealthRecord> latestByPrn = new LinkedHashMap<>();
        long latestEpochMillis = 0L;
        int totalMessagesParsed = 0;

        while (true) {
            String line;
            try {
                line = reader.readLine();
            } catch (EOFException eof) {
                // Salvage all complete messages parsed before a truncated gzip tail
                break;
            }
            if (line == null) {
                break;
            }

            if (inHeader) {
                if (line.length() >= 9 && line.contains("RINEX VERSION")) {
                    rinexVersion = line.substring(0, Math.min(9, line.length())).trim();
                }
                if (line.contains("END OF HEADER")) {
                    inHeader = false;
                }
                continue;
            }

            if (line.isBlank()) {
                continue;
            }

            // RINEX 4 record marker
            if (line.startsWith(">")) {
                BroadcastHealthRecord rec = finalizeRecord(
                        currentPrn, currentConstellation, currentEpochMillis, currentMsgType, orbitLines);
                if (rec != null) {
                    totalMessagesParsed++;
                    if (rec.epochMillis() > latestEpochMillis) {
                        latestEpochMillis = rec.epochMillis();
                    }
                    mergeLatest(latestByPrn, rec);
                }
                currentPrn = null;
                currentConstellation = null;
                orbitLines.clear();

                String[] parts = line.substring(1).trim().split("\\s+");
                if (parts.length >= 1 && "EPH".equalsIgnoreCase(parts[0])) {
                    skipRinex4Block = false;
                    pendingMsgType = parts.length >= 3 ? parts[2].toUpperCase(Locale.ROOT) : "";
                } else {
                    skipRinex4Block = true;
                    pendingMsgType = "";
                }
                continue;
            }

            if (skipRinex4Block) {
                continue;
            }

            if (!Character.isWhitespace(line.charAt(0))) {
                // Flush previous complete record
                BroadcastHealthRecord rec = finalizeRecord(
                        currentPrn, currentConstellation, currentEpochMillis, currentMsgType, orbitLines);
                if (rec != null) {
                    totalMessagesParsed++;
                    if (rec.epochMillis() > latestEpochMillis) {
                        latestEpochMillis = rec.epochMillis();
                    }
                    mergeLatest(latestByPrn, rec);
                }
                currentPrn = null;
                currentConstellation = null;
                orbitLines.clear();

                if (line.length() >= 23) {
                    char sys = Character.toUpperCase(line.charAt(0));
                    GnssConstellation constellation = GnssConstellation.fromPrefix(sys);
                    if (constellation != null && constellation.isLayer1Active()) {
                        String prnNumStr = line.substring(1, 3).trim().replace(' ', '0');
                        try {
                            int prnNum = Integer.parseInt(prnNumStr);
                            long epoch = parseEpochMillis(line.substring(4, 23), constellation);
                            if (epoch > 0L) {
                                currentPrn = constellation.getPrefix() + String.format("%02d", prnNum);
                                currentConstellation = constellation;
                                currentEpochMillis = epoch;
                                currentMsgType = !pendingMsgType.isEmpty()
                                        ? pendingMsgType
                                        : defaultMsgType(constellation);
                            }
                        } catch (java.time.DateTimeException | IllegalArgumentException ignored) {
                        }
                    }
                }
                pendingMsgType = "";
            } else if (currentPrn != null) {
                orbitLines.add(line);
            }
        }

        // Flush final record at EOF
        BroadcastHealthRecord tail = finalizeRecord(
                currentPrn, currentConstellation, currentEpochMillis, currentMsgType, orbitLines);
        if (tail != null) {
            totalMessagesParsed++;
            if (tail.epochMillis() > latestEpochMillis) {
                latestEpochMillis = tail.epochMillis();
            }
            mergeLatest(latestByPrn, tail);
        }

        return new ParseResult(Map.copyOf(latestByPrn), latestEpochMillis, totalMessagesParsed, rinexVersion);
    }

    /**
     * Decodes Galileo 9-bit SV health (E1-B bits 0–2, E5a bits 3–5, E5b bits 6–8) against the
     * active {@code dataSourceBits} flags.
     */
    public static boolean isGalileoHealthy(int svHealthBits, int dataSourceBits) {
        int activeSignals = dataSourceBits & 0b111;
        if (activeSignals == 0) {
            return svHealthBits == 0;
        }
        if ((activeSignals & 0b001) != 0 && (svHealthBits & 0b111) != 0) {
            return false;
        }
        if ((activeSignals & 0b010) != 0 && ((svHealthBits >> 3) & 0b111) != 0) {
            return false;
        }
        if ((activeSignals & 0b100) != 0 && ((svHealthBits >> 6) & 0b111) != 0) {
            return false;
        }
        return true;
    }

    private static BroadcastHealthRecord finalizeRecord(
            String prnId,
            GnssConstellation constellation,
            long epochMillis,
            String msgType,
            List<String> orbitLines) {
        if (prnId == null || constellation == null || epochMillis <= 0L) {
            return null;
        }
        return switch (constellation) {
            case GLONASS -> {
                if (orbitLines.size() < 3) {
                    yield null;
                }
                int bn = parseOrbitInt(orbitLines.get(0), 3);
                boolean ok = (bn == 0);
                yield new BroadcastHealthRecord(
                        prnId, constellation, msgType, epochMillis, bn, ok,
                        "GLONASS health flag Bn=" + bn + (ok ? " (healthy)" : " (unhealthy)"));
            }
            case GPS -> {
                if (orbitLines.size() < 6) {
                    yield null;
                }
                int svHealth = parseOrbitInt(orbitLines.get(5), 1);
                boolean ok = (svHealth == 0);
                yield new BroadcastHealthRecord(
                        prnId, constellation, msgType, epochMillis, svHealth, ok,
                        "GPS " + msgType + " SV health=" + svHealth + (ok ? " (healthy)" : " (unhealthy)"));
            }
            case GALILEO -> {
                if (orbitLines.size() < 6) {
                    yield null;
                }
                int dataSources = parseOrbitInt(orbitLines.get(4), 1);
                int svHealth = parseOrbitInt(orbitLines.get(5), 1);
                boolean ok = isGalileoHealthy(svHealth, dataSources);
                yield new BroadcastHealthRecord(
                        prnId, constellation, msgType, epochMillis, svHealth, ok,
                        "Galileo " + msgType + " SV health=" + svHealth
                                + " (dataSource=" + dataSources + ", " + (ok ? "healthy" : "unhealthy") + ")");
            }
            case BEIDOU -> {
                if ("CNV1".equals(msgType) || "CNV2".equals(msgType)) {
                    if (orbitLines.size() < 8) {
                        yield null;
                    }
                    int hs = parseOrbitInt(orbitLines.get(7), 1);
                    boolean ok = (hs == 0);
                    yield new BroadcastHealthRecord(
                            prnId, constellation, msgType, epochMillis, hs, ok,
                            "BeiDou " + msgType + " HS=" + hs + (ok ? " (healthy)" : " (unhealthy)"));
                } else if ("CNV3".equals(msgType)) {
                    if (orbitLines.size() < 7) {
                        yield null;
                    }
                    int hs = parseOrbitInt(orbitLines.get(6), 1);
                    boolean ok = (hs == 0);
                    yield new BroadcastHealthRecord(
                            prnId, constellation, msgType, epochMillis, hs, ok,
                            "BeiDou CNV3 HS=" + hs + (ok ? " (healthy)" : " (unhealthy)"));
                } else {
                    if (orbitLines.size() < 6) {
                        yield null;
                    }
                    int satH1 = parseOrbitInt(orbitLines.get(5), 1);
                    boolean ok = (satH1 == 0);
                    yield new BroadcastHealthRecord(
                            prnId, constellation, msgType, epochMillis, satH1, ok,
                            "BeiDou " + msgType + " SatH1=" + satH1 + (ok ? " (healthy)" : " (unhealthy)"));
                }
            }
            case SBAS -> null;
        };
    }

    private static void mergeLatest(Map<String, BroadcastHealthRecord> latestByPrn, BroadcastHealthRecord rec) {
        BroadcastHealthRecord existing = latestByPrn.get(rec.prnId());
        if (existing == null || rec.epochMillis() >= existing.epochMillis()) {
            latestByPrn.put(rec.prnId(), rec);
        }
    }

    private static int parseOrbitInt(String line, int fieldIndex) {
        int start = 4 + fieldIndex * 19;
        int end = start + 19;
        if (line == null || line.length() <= start) {
            return 0;
        }
        String raw = line.substring(start, Math.min(end, line.length())).trim();
        if (raw.isEmpty()) {
            return 0;
        }
        try {
            double val = Double.parseDouble(raw.replace('D', 'E').replace('d', 'e'));
            return (int) Math.round(val);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseEpochMillis(String epochSlice, GnssConstellation constellation) {
        String[] parts = epochSlice.trim().split("\\s+");
        if (parts.length < 6) {
            return 0L;
        }
        int year = Integer.parseInt(parts[0]);
        int month = Integer.parseInt(parts[1]);
        int day = Integer.parseInt(parts[2]);
        int hour = Integer.parseInt(parts[3]);
        int min = Integer.parseInt(parts[4]);
        int sec = (int) Math.floor(Double.parseDouble(parts[5].replace('D', 'E')));
        int extraSec = 0;
        if (sec >= 60) {
            extraSec = sec - 59;
            sec = 59;
        } else if (sec < 0) {
            sec = 0;
        }
        long rawMillis = LocalDateTime.of(year, month, day, hour, min, sec)
                .plusSeconds(extraSec)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli();
        return switch (constellation) {
            case GPS, GALILEO -> rawMillis - GPST_UTC_LEAP_MILLIS;
            case BEIDOU -> rawMillis - BDT_UTC_LEAP_MILLIS;
            case GLONASS, SBAS -> rawMillis;
        };
    }

    private static String defaultMsgType(GnssConstellation c) {
        return switch (c) {
            case GPS -> "LNAV";
            case GALILEO -> "INAV";
            case GLONASS -> "FDMA";
            case BEIDOU -> "D1/D2";
            case SBAS -> "SBAS";
        };
    }

    private static InputStream unwrapGzipIfNeeded(InputStream stream) throws IOException {
        PushbackInputStream pb = new PushbackInputStream(stream, 2);
        byte[] sig = new byte[2];
        int len = pb.read(sig);
        if (len > 0) {
            pb.unread(sig, 0, len);
        }
        if (len == 2 && (sig[0] & 0xFF) == 0x1F && (sig[1] & 0xFF) == 0x8B) {
            return new GZIPInputStream(pb);
        }
        return pb;
    }
}

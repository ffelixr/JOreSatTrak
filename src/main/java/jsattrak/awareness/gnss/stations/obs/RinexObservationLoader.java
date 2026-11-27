package jsattrak.awareness.gnss.stations.obs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import jsattrak.awareness.gnss.GnssConstellation;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.data.DataFilter;
import org.orekit.data.DataSource;
import org.orekit.data.UnixCompressFilter;
import org.orekit.files.rinex.HatanakaCompressFilter;
import org.orekit.files.rinex.observation.GlonassSatelliteChannel;
import org.orekit.files.rinex.observation.ObservationData;
import org.orekit.files.rinex.observation.ObservationDataSet;
import org.orekit.files.rinex.observation.RinexObservation;
import org.orekit.files.rinex.observation.RinexObservationHeader;
import org.orekit.files.rinex.observation.RinexObservationParser;
import org.orekit.gnss.MeasurementType;
import org.orekit.gnss.ObservationType;
import org.orekit.gnss.SatInSystem;
import org.orekit.gnss.SatelliteSystem;
import org.orekit.time.TimeScale;
import org.orekit.time.TimeScalesFactory;

/**
 * Orekit-backed RINEX 2/3/4 observation loader with automatic GZIP, Unix-compress (.Z),
 * and Hatanaka (.crx / .YYd) decoding.
 */
public final class RinexObservationLoader {

    private RinexObservationLoader() {
    }

    public static RinexObservationData parse(byte[] rawBytes, String fileName) throws IOException {
        if (rawBytes == null || rawBytes.length == 0) {
            throw new IOException("RINEX observation payload is empty: " + fileName);
        }
        String safeName = (fileName != null && !fileName.isBlank()) ? fileName : "observation.rnx";
        byte[] normalizedBytes = unwrapStreamCompression(rawBytes, safeName);
        if (normalizedBytes.length == 0) {
            throw new IOException("Decompressed RINEX observation payload is empty: " + safeName);
        }

        String headerProbe = new String(
                normalizedBytes, 0, Math.min(normalizedBytes.length, 256), StandardCharsets.US_ASCII);
        boolean isCompactHatanaka = looksLikeHatanaka(safeName, headerProbe);

        DataSource ds = new DataSource(stripCompressionSuffix(safeName),
                () -> new ByteArrayInputStream(normalizedBytes));
        if (isCompactHatanaka) {
            DataFilter crxFilter = new HatanakaCompressFilter();
            ds = crxFilter.filter(ds);
        }

        RinexObservation parsed;
        try {
            parsed = new RinexObservationParser().parse(ds);
        } catch (RuntimeException e) {
            throw new IOException("Unable to parse RINEX observation file '" + safeName + "': " + e.getMessage(), e);
        }

        if (parsed == null || parsed.getHeader() == null || parsed.getObservationDataSets().isEmpty()) {
            throw new IOException("No observation epochs found in RINEX file: " + safeName);
        }

        RinexObservationHeader h = parsed.getHeader();
        Map<String, Integer> glonassChannels = new LinkedHashMap<>();
        List<RinexObservationData.GlonassChannelEntry> channelList = new ArrayList<>();
        if (h.getGlonassChannels() != null) {
            for (GlonassSatelliteChannel ch : h.getGlonassChannels()) {
                if (ch != null && ch.getSatellite() != null) {
                    String prn = formatPrn(ch.getSatellite());
                    if (prn != null) {
                        glonassChannels.put(prn, ch.getK());
                        channelList.add(new RinexObservationData.GlonassChannelEntry(prn, ch.getK()));
                    }
                }
            }
        }

        Map<GnssConstellation, List<ObservationType>> typeObs = new EnumMap<>(GnssConstellation.class);
        if (h.getTypeObs() != null) {
            for (Map.Entry<SatelliteSystem, List<ObservationType>> e : h.getTypeObs().entrySet()) {
                GnssConstellation c = fromSatelliteSystem(e.getKey());
                if (c != null && e.getValue() != null) {
                    typeObs.put(c, List.copyOf(e.getValue()));
                }
            }
        }

        TimeScale utc = TimeScalesFactory.getUTC();
        List<RinexObservationData.EpochRecord> epochRecords = new ArrayList<>();
        long minEpochMillis = Long.MAX_VALUE;
        long maxEpochMillis = Long.MIN_VALUE;

        for (List<ObservationDataSet> bundle : parsed.bundleByDates()) {
            if (bundle == null || bundle.isEmpty()) {
                continue;
            }
            long epochMillis = bundle.get(0).getDate().toDate(utc).getTime();
            minEpochMillis = Math.min(minEpochMillis, epochMillis);
            maxEpochMillis = Math.max(maxEpochMillis, epochMillis);
            double rcvrClk = bundle.get(0).getRcvrClkOffset();

            Map<String, RinexObservationData.SatelliteObsSample> satMap = new LinkedHashMap<>();
            for (ObservationDataSet set : bundle) {
                if (set == null || set.getSatellite() == null) {
                    continue;
                }
                GnssConstellation c = fromSatelliteSystem(set.getSatellite().getSystem());
                if (c == null || !c.isLayer1Active()) {
                    continue;
                }
                String prnId = formatPrn(set.getSatellite());
                if (prnId == null) {
                    continue;
                }
                Map<ObservationType, Double> values = new LinkedHashMap<>();
                Map<ObservationType, Integer> lliMap = new LinkedHashMap<>();
                Map<ObservationType, Integer> ssiMap = new LinkedHashMap<>();
                boolean hasExplicitCn0 = false;

                if (set.getObservationData() != null) {
                    for (ObservationData od : set.getObservationData()) {
                        if (od == null || od.getObservationType() == null) {
                            continue;
                        }
                        ObservationType type = od.getObservationType();
                        double val = od.getValue();
                        if (Double.isFinite(val)) {
                            values.put(type, val);
                            if (type.getMeasurementType() == MeasurementType.SIGNAL_STRENGTH && val > 0.0) {
                                hasExplicitCn0 = true;
                            }
                        }
                        if (od.getLossOfLockIndicator() > 0) {
                            lliMap.put(type, od.getLossOfLockIndicator());
                        }
                        if (od.getSignalStrength() > 0) {
                            ssiMap.put(type, od.getSignalStrength());
                        }
                    }
                }
                boolean ssiFallback = !hasExplicitCn0 && !ssiMap.isEmpty();
                satMap.put(prnId, new RinexObservationData.SatelliteObsSample(
                        prnId, c, values, lliMap, ssiMap, ssiFallback, glonassChannels.get(prnId)));
            }
            if (!satMap.isEmpty()) {
                epochRecords.add(new RinexObservationData.EpochRecord(epochMillis, rcvrClk, satMap));
            }
        }

        if (epochRecords.isEmpty()) {
            throw new IOException("No supported GPS/Galileo/GLONASS/BeiDou observations in RINEX file: " + safeName);
        }

        Vector3D approx = h.getApproxPos();
        double[] approxEcef = (approx != null)
                ? new double[]{approx.getX(), approx.getY(), approx.getZ()}
                : new double[]{0.0, 0.0, 0.0};
        long firstObs = h.getTFirstObs() != null ? h.getTFirstObs().toDate(utc).getTime() : minEpochMillis;
        long lastObs = h.getTLastObs() != null ? h.getTLastObs().toDate(utc).getTime() : maxEpochMillis;

        String antenna = nonBlank(h.getAntennaType());
        RinexObservationData.HeaderSummary summary = new RinexObservationData.HeaderSummary(
                h.getMarkerName(),
                h.getReceiverType(),
                h.getReceiverVersion(),
                antenna,
                approxEcef,
                h.getInterval(),
                firstObs,
                lastObs,
                h.getSignalStrengthUnit(),
                typeObs,
                channelList);
        return new RinexObservationData(summary, epochRecords);
    }

    private static byte[] unwrapStreamCompression(byte[] rawBytes, String fileName) throws IOException {
        if (isGzip(rawBytes)) {
            try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(rawBytes));
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                gis.transferTo(out);
                return out.toByteArray();
            }
        }
        if (isUnixCompress(rawBytes) || fileName.toLowerCase(Locale.US).endsWith(".z")) {
            DataSource compressed = new DataSource(fileName, () -> new ByteArrayInputStream(rawBytes));
            DataSource filtered = new UnixCompressFilter().filter(compressed);
            try (var reader = filtered.getOpener().openStreamOnce();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                reader.transferTo(out);
                return out.toByteArray();
            } catch (RuntimeException e) {
                throw new IOException("Failed to decompress Unix .Z RINEX stream: " + e.getMessage(), e);
            }
        }
        return rawBytes;
    }

    private static boolean isGzip(byte[] bytes) {
        return bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
    }

    private static boolean isUnixCompress(byte[] bytes) {
        return bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x9D;
    }

    private static boolean looksLikeHatanaka(String fileName, String headerPrefix) {
        if (headerPrefix.contains("COMPACT RINEX FORMAT")) {
            return true;
        }
        String lower = stripCompressionSuffix(fileName).toLowerCase(Locale.US);
        return lower.endsWith(".crx") || lower.matches(".*\\.\\d{2}d$");
    }

    private static String stripCompressionSuffix(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".gz")) {
            return name.substring(0, name.length() - 3);
        }
        if (lower.endsWith(".z")) {
            return name.substring(0, name.length() - 2);
        }
        return name;
    }

    private static GnssConstellation fromSatelliteSystem(SatelliteSystem sys) {
        if (sys == null) {
            return null;
        }
        return switch (sys) {
            case GPS -> GnssConstellation.GPS;
            case GALILEO -> GnssConstellation.GALILEO;
            case GLONASS -> GnssConstellation.GLONASS;
            case BEIDOU -> GnssConstellation.BEIDOU;
            default -> null;
        };
    }

    private static String formatPrn(SatInSystem sat) {
        GnssConstellation c = fromSatelliteSystem(sat.getSystem());
        if (c == null || sat.getPRN() <= 0 || sat.getPRN() > 99) {
            return null;
        }
        return String.format(Locale.US, "%c%02d", c.getPrefix(), sat.getPRN());
    }

    private static String nonBlank(String s) {
        return s != null ? s.trim() : "";
    }
}

package jsattrak.awareness.gnss.stations.obs;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import jsattrak.awareness.gnss.GnssConstellation;
import org.orekit.gnss.GnssSignal;
import org.orekit.gnss.MeasurementType;
import org.orekit.gnss.ObservationType;
import org.orekit.gnss.SatelliteSystem;

/**
 * Normalized multi-GNSS RINEX observation container independent of Orekit's parser classes.
 */
public final class RinexObservationData {

    private static final double SPEED_OF_LIGHT_MPS = 299_792_458.0;

    public record GlonassChannelEntry(String prnId, int k) {
    }

    public record HeaderSummary(
            String markerName,
            String receiverType,
            String receiverVersion,
            String antennaType,
            double[] approxEcef,
            double intervalSeconds,
            long firstObsMillis,
            long lastObsMillis,
            String signalStrengthUnit,
            Map<GnssConstellation, List<ObservationType>> typeObs,
            List<GlonassChannelEntry> glonassChannels) {

        public HeaderSummary {
            markerName = markerName != null ? markerName.trim() : "";
            receiverType = receiverType != null ? receiverType.trim() : "";
            receiverVersion = receiverVersion != null ? receiverVersion.trim() : "";
            antennaType = antennaType != null ? antennaType.trim() : "";
            approxEcef = (approxEcef != null && approxEcef.length == 3) ? approxEcef.clone() : new double[3];
            signalStrengthUnit = signalStrengthUnit != null ? signalStrengthUnit.trim() : "";
            typeObs = (typeObs != null && !typeObs.isEmpty())
                    ? Collections.unmodifiableMap(new EnumMap<>(typeObs))
                    : Map.of();
            glonassChannels = glonassChannels != null ? List.copyOf(glonassChannels) : List.of();
        }
    }

    public record DualFreqObs(
            ObservationType c1Type,
            double c1Meters,
            ObservationType c2Type,
            double c2Meters,
            ObservationType l1Type,
            double l1Cycles,
            ObservationType l2Type,
            double l2Cycles,
            double f1Hz,
            double f2Hz,
            ObservationType s1Type,
            double s1DbHz,
            ObservationType s2Type,
            double s2DbHz) {
    }

    public record PrimaryCodeObs(
            ObservationType codeType,
            double codeMeters,
            double freqHz,
            double cn0DbHz,
            boolean lli) {
    }

    public record SatelliteObsSample(
            String prnId,
            GnssConstellation constellation,
            Map<ObservationType, Double> values,
            Map<ObservationType, Integer> lliByType,
            Map<ObservationType, Integer> ssiByType,
            boolean cn0FromSsiFallback,
            Integer glonassK) {

        public SatelliteObsSample {
            values = values != null ? Collections.unmodifiableMap(new LinkedHashMap<>(values)) : Map.of();
            lliByType = lliByType != null ? Collections.unmodifiableMap(new LinkedHashMap<>(lliByType)) : Map.of();
            ssiByType = ssiByType != null ? Collections.unmodifiableMap(new LinkedHashMap<>(ssiByType)) : Map.of();
        }

        public double codeMeters(ObservationType type) {
            if (type == null || type.getMeasurementType() != MeasurementType.PSEUDO_RANGE) {
                return Double.NaN;
            }
            Double v = values.get(type);
            return v != null ? v : Double.NaN;
        }

        public double phaseCycles(ObservationType type) {
            if (type == null || type.getMeasurementType() != MeasurementType.CARRIER_PHASE) {
                return Double.NaN;
            }
            Double v = values.get(type);
            return v != null ? v : Double.NaN;
        }

        public int lliFlags(ObservationType type) {
            if (type == null) {
                return 0;
            }
            Integer v = lliByType.get(type);
            return v != null ? v : 0;
        }

        public boolean hasLossOfLock() {
            for (Map.Entry<ObservationType, Integer> e : lliByType.entrySet()) {
                if (e.getKey() != null && e.getKey().getMeasurementType() == MeasurementType.CARRIER_PHASE
                        && e.getValue() != null && (e.getValue() & 1) != 0) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Returns the preferred L1/E1/B1/G1 C/N0 in dB-Hz (falling back to {@code ssi * 6.0}
         * only when no explicit {@code S*} signal-strength observation is present).
         */
        public double primaryCn0DbHz(GnssConstellation c) {
            List<String> prefs = primaryBandPrefixes(c != null ? c : constellation);
            for (String codeName : prefs) {
                ObservationType match = findByExactName(codeName);
                if (match != null) {
                    Double v = values.get(match);
                    if (v != null && Double.isFinite(v) && v > 0.0) {
                        return v;
                    }
                }
            }
            // Any SIGNAL_STRENGTH value
            for (Map.Entry<ObservationType, Double> e : values.entrySet()) {
                if (e.getKey() != null && e.getKey().getMeasurementType() == MeasurementType.SIGNAL_STRENGTH
                        && e.getValue() != null && Double.isFinite(e.getValue()) && e.getValue() > 0.0) {
                    return e.getValue();
                }
            }
            // Fallback to RINEX SSI (1..9 -> 6..54 dB-Hz)
            int maxSsi = 0;
            for (Map.Entry<ObservationType, Integer> e : ssiByType.entrySet()) {
                if (e.getValue() != null && e.getValue() > maxSsi) {
                    maxSsi = e.getValue();
                }
            }
            return maxSsi > 0 ? maxSsi * 6.0 : Double.NaN;
        }

        public PrimaryCodeObs selectPrimaryCode(GnssConstellation c) {
            GnssConstellation sys = c != null ? c : constellation;
            ObservationType code = findFirstPresent(MeasurementType.PSEUDO_RANGE, primaryBandSuffixes(sys));
            if (code == null) {
                code = findAnyPresent(MeasurementType.PSEUDO_RANGE);
            }
            if (code == null) {
                return null;
            }
            Double meters = values.get(code);
            if (meters == null || !Double.isFinite(meters) || meters <= 1.0e5) {
                return null;
            }
            double freq = frequencyHz(sys, code, glonassK);
            double cn0 = primaryCn0DbHz(sys);
            ObservationType phaseMatch = findMatchingPhase(code);
            boolean lli = phaseMatch != null && (lliFlags(phaseMatch) & 1) != 0;
            return new PrimaryCodeObs(code, meters, freq, cn0, lli);
        }

        public DualFreqObs selectDualFreqPair() {
            GnssConstellation sys = constellation;
            ObservationType c1 = findFirstPresent(MeasurementType.PSEUDO_RANGE, band1Prefixes(sys));
            ObservationType l1 = findFirstPresent(MeasurementType.CARRIER_PHASE, band1Prefixes(sys));
            ObservationType c2 = findFirstPresent(MeasurementType.PSEUDO_RANGE, band2Prefixes(sys));
            ObservationType l2 = findFirstPresent(MeasurementType.CARRIER_PHASE, band2Prefixes(sys));
            if (c1 == null || l1 == null || c2 == null || l2 == null) {
                return null;
            }
            Double c1v = values.get(c1);
            Double c2v = values.get(c2);
            Double l1v = values.get(l1);
            Double l2v = values.get(l2);
            if (!isFinitePositive(c1v) || !isFinitePositive(c2v)
                    || l1v == null || !Double.isFinite(l1v)
                    || l2v == null || !Double.isFinite(l2v)) {
                return null;
            }
            double f1 = frequencyHz(sys, c1, glonassK);
            double f2 = frequencyHz(sys, c2, glonassK);
            if (!(f1 > 0.0) || !(f2 > 0.0) || Math.abs(f1 - f2) < 1.0e6) {
                return null;
            }
            ObservationType s1 = findFirstPresent(MeasurementType.SIGNAL_STRENGTH, band1Prefixes(sys));
            ObservationType s2 = findFirstPresent(MeasurementType.SIGNAL_STRENGTH, band2Prefixes(sys));
            double s1Val = s1 != null && values.get(s1) != null ? values.get(s1) : primaryCn0DbHz(sys);
            double s2Val = s2 != null && values.get(s2) != null ? values.get(s2) : Double.NaN;
            return new DualFreqObs(c1, c1v, c2, c2v, l1, l1v, l2, l2v, f1, f2, s1, s1Val, s2, s2Val);
        }

        public static double frequencyHz(GnssConstellation constellation, ObservationType type, Integer glonassK) {
            if (constellation == GnssConstellation.GLONASS && type != null) {
                String n = type.getName();
                int k = glonassK != null ? glonassK : 0;
                if (n.length() >= 2 && n.charAt(1) == '1') {
                    return 1602.0e6 + k * 562_500.0;
                }
                if (n.length() >= 2 && n.charAt(1) == '2') {
                    return 1246.0e6 + k * 437_500.0;
                }
            }
            SatelliteSystem satSys = toSatelliteSystem(constellation);
            if (type != null && satSys != null) {
                try {
                    GnssSignal sig = type.getSignal(satSys);
                    if (sig != null && sig.getFrequency() > 0.0) {
                        return sig.getFrequency();
                    }
                } catch (RuntimeException ignored) {
                }
            }
            return 1575.42e6;
        }

        public static double wavelengthMeters(GnssConstellation c, ObservationType type, Integer glonassK) {
            return SPEED_OF_LIGHT_MPS / frequencyHz(c, type, glonassK);
        }

        private ObservationType findByExactName(String name) {
            for (ObservationType t : values.keySet()) {
                if (t != null && name.equals(t.getName())) {
                    return t;
                }
            }
            return null;
        }

        private ObservationType findFirstPresent(MeasurementType mType, List<String> bandPrefixes) {
            for (String prefix : bandPrefixes) {
                for (ObservationType t : values.keySet()) {
                    if (t != null && t.getMeasurementType() == mType && t.getName().length() >= 2
                            && t.getName().substring(1).startsWith(prefix)) {
                        Double v = values.get(t);
                        if (v != null && Double.isFinite(v) && v != 0.0) {
                            return t;
                        }
                    }
                }
            }
            return null;
        }

        private ObservationType findAnyPresent(MeasurementType mType) {
            for (ObservationType t : values.keySet()) {
                if (t != null && t.getMeasurementType() == mType) {
                    Double v = values.get(t);
                    if (v != null && Double.isFinite(v) && v != 0.0) {
                        return t;
                    }
                }
            }
            return null;
        }

        private ObservationType findMatchingPhase(ObservationType codeType) {
            if (codeType == null) {
                return null;
            }
            String suffix = codeType.getName().substring(1);
            for (ObservationType t : values.keySet()) {
                if (t != null && t.getMeasurementType() == MeasurementType.CARRIER_PHASE
                        && t.getName().substring(1).equals(suffix)) {
                    return t;
                }
            }
            return findAnyPresent(MeasurementType.CARRIER_PHASE);
        }

        private static boolean isFinitePositive(Double v) {
            return v != null && Double.isFinite(v) && v > 0.0;
        }

        private static List<String> primaryBandPrefixes(GnssConstellation c) {
            if (c == null) {
                return List.of("S1C", "S1W", "S1X", "S1", "S2I");
            }
            return switch (c) {
                case GPS -> List.of("S1C", "S1W", "S1L", "S1X", "S1S", "S1");
                case GALILEO -> List.of("S1X", "S1C", "S1B", "S1");
                case GLONASS -> List.of("S1C", "S1P", "S1");
                case BEIDOU -> List.of("S2I", "S1X", "S1P", "S1D", "S2X", "S1");
                default -> List.of("S1C", "S1X", "S1");
            };
        }

        private static List<String> primaryBandSuffixes(GnssConstellation c) {
            if (c == null) {
                return List.of("1C", "1W", "1X", "1", "2I");
            }
            return switch (c) {
                case GPS -> List.of("1C", "1W", "1L", "1X", "1");
                case GALILEO -> List.of("1X", "1C", "1B", "1");
                case GLONASS -> List.of("1C", "1P", "1");
                case BEIDOU -> List.of("2I", "1X", "1P", "2");
                default -> List.of("1C", "1X", "1");
            };
        }

        private static List<String> band1Prefixes(GnssConstellation c) {
            if (c == null) {
                return List.of("1C", "1X", "1");
            }
            return switch (c) {
                case BEIDOU -> List.of("2I", "2X", "1X", "1P", "1D", "2", "1");
                case GALILEO -> List.of("1X", "1C", "1B", "1");
                case GLONASS -> List.of("1C", "1P", "1");
                default -> List.of("1C", "1W", "1X", "1L", "1");
            };
        }

        private static List<String> band2Prefixes(GnssConstellation c) {
            if (c == null) {
                return List.of("2W", "2L", "5Q", "5X", "7Q", "2");
            }
            return switch (c) {
                case GPS -> List.of("2W", "2L", "2S", "2X", "2P", "2", "5Q", "5X");
                case GALILEO -> List.of("5X", "5Q", "7X", "7Q", "8X");
                case GLONASS -> List.of("2C", "2P", "2");
                case BEIDOU -> List.of("6I", "7I", "5X", "7X", "6", "7");
                default -> List.of("2W", "5X", "2");
            };
        }

        private static SatelliteSystem toSatelliteSystem(GnssConstellation c) {
            if (c == null) {
                return null;
            }
            return switch (c) {
                case GPS -> SatelliteSystem.GPS;
                case GALILEO -> SatelliteSystem.GALILEO;
                case GLONASS -> SatelliteSystem.GLONASS;
                case BEIDOU -> SatelliteSystem.BEIDOU;
                case SBAS -> SatelliteSystem.SBAS;
            };
        }
    }

    public record EpochRecord(
            long epochMillis,
            double rcvrClkOffsetSec,
            Map<String, SatelliteObsSample> satellites) {
        public EpochRecord {
            satellites = satellites != null ? Collections.unmodifiableMap(new LinkedHashMap<>(satellites)) : Map.of();
        }
    }

    private final HeaderSummary header;
    private final List<EpochRecord> epochs;

    public RinexObservationData(HeaderSummary header, List<EpochRecord> epochs) {
        this.header = Objects.requireNonNull(header, "header");
        this.epochs = epochs != null ? List.copyOf(epochs) : List.of();
    }

    public HeaderSummary header() {
        return header;
    }

    public List<EpochRecord> epochs() {
        return epochs;
    }
}

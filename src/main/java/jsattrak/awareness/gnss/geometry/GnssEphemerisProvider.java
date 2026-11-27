package jsattrak.awareness.gnss.geometry;

import jsattrak.awareness.gnss.BroadcastHealthRecord;
import jsattrak.awareness.gnss.GnssAwarenessService;
import jsattrak.awareness.gnss.GnssCatalogEntry;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.GnssDisplayStatus;
import jsattrak.awareness.gnss.GnssOrbitClass;
import jsattrak.awareness.gnss.GnssOrbitPropagator;
import jsattrak.awareness.gnss.GnssStatusResult;
import jsattrak.awareness.gnss.net.RinexNavHealthParser;
import jsattrak.utilities.TLE;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.data.DataSource;
import org.orekit.files.rinex.navigation.RinexNavigation;
import org.orekit.files.rinex.navigation.RinexNavigationParser;
import org.orekit.files.sp3.SP3;
import org.orekit.files.sp3.SP3Coordinate;
import org.orekit.files.sp3.SP3Ephemeris;
import org.orekit.files.sp3.SP3Parser;
import org.orekit.files.sp3.SP3Segment;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.propagation.BoundedPropagator;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.analytical.gnss.GLONASSAnalyticalPropagatorBuilder;
import org.orekit.propagation.analytical.gnss.GNSSPropagatorBuilder;
import org.orekit.propagation.analytical.gnss.data.GLONASSNavigationMessage;
import org.orekit.propagation.analytical.gnss.data.GLONASSOrbitalElements;
import org.orekit.propagation.analytical.gnss.data.GNSSClockElements;
import org.orekit.propagation.analytical.gnss.data.GNSSOrbitalElements;
import org.orekit.utils.TimeStampedPVCoordinates;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.IERSConventions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * Multi-tier GNSS ephemeris provider resolving ITRF/ECEF satellite positions in strict priority:
 * <ol>
 *     <li>Local imported SP3 precise orbit file (within ephemeris span)</li>
 *     <li>Local imported RINEX Navigation file (within ephemeris validity window)</li>
 *     <li>Live / cached BKG BRDC mixed RINEX Navigation stream (within validity window)</li>
 *     <li>Layer-1 TLE / SGP4 fallback</li>
 * </ol>
 */
public class GnssEphemerisProvider {

    public static final long BRDC_VALIDITY_KEPLER_MILLIS = 4L * 3600L * 1000L;
    public static final long BRDC_VALIDITY_GLONASS_MILLIS = 30L * 60L * 1000L;
    public static final long SP3_LIVE_HEALTH_WINDOW_MILLIS = 24L * 3600L * 1000L;

    public record LocalLoadSummary(
            String fileName,
            GnssOrbitSource sourceType,
            int satelliteCount,
            long startEpochMillis,
            long stopEpochMillis) {
    }

    public record ResolvedEpochSnapshot(
            List<GnssDopCalculator.CandidateSatellite> candidates,
            GnssOrbitSource.Summary orbitSource) {
    }

    public record CandidateSatelliteWithClock(
            String prnId,
            GnssConstellation constellation,
            GnssDisplayStatus status,
            GnssOrbitSource orbitSource,
            double satX,
            double satY,
            double satZ,
            double velX,
            double velY,
            double velZ,
            double clockBiasSec,
            double tgdSec,
            boolean clockValid) {
    }

    public record ResolvedClockEpochSnapshot(
            List<CandidateSatelliteWithClock> candidates,
            GnssOrbitSource.Summary orbitSource) {
    }

    private record PropagatedState(
            double satX,
            double satY,
            double satZ,
            double velX,
            double velY,
            double velZ,
            double clockBiasSec,
            double tgdSec,
            boolean clockValid) {
    }

    private record RinexMessageEntry(
            String prnId,
            GnssConstellation constellation,
            long toeMillis,
            GNSSOrbitalElements<?> keplerElements,
            GLONASSOrbitalElements glonassElements) {
    }

    private static final class ParsedRinexNavStore {
        final String sourceName;
        final Map<String, List<RinexMessageEntry>> messagesByPrn;
        final Map<String, BroadcastHealthRecord> healthByPrn;
        final Map<String, Propagator> propagatorCache = new ConcurrentHashMap<>();

        ParsedRinexNavStore(
                String sourceName,
                Map<String, List<RinexMessageEntry>> messagesByPrn,
                Map<String, BroadcastHealthRecord> healthByPrn) {
            this.sourceName = sourceName != null ? sourceName : "";
            this.messagesByPrn = messagesByPrn;
            this.healthByPrn = healthByPrn;
        }
    }

    private static final class ParsedSp3Store {
        final String fileName;
        final Map<String, SP3Ephemeris> ephemerisByPrn;
        final Map<String, BoundedPropagator> propagatorCache = new ConcurrentHashMap<>();

        ParsedSp3Store(String fileName, Map<String, SP3Ephemeris> ephemerisByPrn) {
            this.fileName = fileName;
            this.ephemerisByPrn = ephemerisByPrn;
        }
    }

    private volatile ParsedRinexNavStore brdcStore;
    private volatile ParsedRinexNavStore localRinexStore;
    private volatile ParsedSp3Store localSp3Store;
    private volatile String loadedLocalFileName = "";
    private volatile byte[] lastBrdcRawBytesRef;
    private volatile int lastBrdcByteHash;

    /**
     * Parses raw RINEX Navigation bytes (plain or GZIP) into the live/cached BRDC ephemeris store.
     *
     * @return number of distinct active-constellation PRNs parsed
     */
    public int loadBrdcBytes(byte[] rawBytes, String sourceName) {
        if (rawBytes == null || rawBytes.length == 0) {
            this.brdcStore = null;
            this.lastBrdcRawBytesRef = null;
            this.lastBrdcByteHash = 0;
            return 0;
        }
        if (rawBytes == this.lastBrdcRawBytesRef && this.brdcStore != null) {
            return this.brdcStore.messagesByPrn.size();
        }
        int hash = java.util.Arrays.hashCode(rawBytes);
        if (hash == this.lastBrdcByteHash && this.brdcStore != null) {
            this.lastBrdcRawBytesRef = rawBytes;
            return this.brdcStore.messagesByPrn.size();
        }
        try {
            byte[] plain = unwrapGzipIfNeeded(rawBytes);
            ParsedRinexNavStore store = parseRinexNav(plain, sourceName != null ? sourceName : "BKG BRDC", false);
            this.brdcStore = store;
            this.lastBrdcRawBytesRef = rawBytes;
            this.lastBrdcByteHash = hash;
            return store.messagesByPrn.size();
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /**
     * Syncs the BRDC store lazily from {@link GnssAwarenessService#getLatestBrdcRawBytes()} when bytes changed.
     */
    public void syncBrdcFromAwareness(GnssAwarenessService awarenessService) {
        if (awarenessService == null) {
            return;
        }
        byte[] bytes = awarenessService.getLatestBrdcRawBytes();
        if (bytes != null && bytes.length > 0) {
            loadBrdcBytes(bytes, awarenessService.getLatestBrdcSourceName());
        }
    }

    /**
     * Imports a local RINEX Navigation or SP3 precise orbit file (plain or GZIP).
     */
    public LocalLoadSummary loadLocalFile(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("File does not exist: " + file);
        }
        String fileName = file.getFileName().toString();
        byte[] raw = Files.readAllBytes(file);
        if (raw.length == 0) {
            throw new IOException("Selected file is empty: " + fileName);
        }
        byte[] plain = unwrapGzipIfNeeded(raw);
        if (looksLikeSp3(fileName, plain)) {
            ParsedSp3Store sp3 = parseSp3(plain, fileName);
            this.localSp3Store = sp3;
            this.localRinexStore = null;
            this.loadedLocalFileName = fileName;
            long minStart = Long.MAX_VALUE;
            long maxStop = Long.MIN_VALUE;
            TimeScale utc = TimeScalesFactory.getUTC();
            for (SP3Ephemeris ep : sp3.ephemerisByPrn.values()) {
                if (ep.getStart() != null) {
                    minStart = Math.min(minStart, ep.getStart().toDate(utc).getTime());
                }
                if (ep.getStop() != null) {
                    maxStop = Math.max(maxStop, ep.getStop().toDate(utc).getTime());
                }
            }
            if (minStart == Long.MAX_VALUE) {
                minStart = 0L;
                maxStop = 0L;
            }
            return new LocalLoadSummary(fileName, GnssOrbitSource.SP3, sp3.ephemerisByPrn.size(), minStart, maxStop);
        } else {
            ParsedRinexNavStore rnx = parseRinexNav(plain, fileName, true);
            if (rnx.messagesByPrn.isEmpty()) {
                throw new IOException("No supported GPS/Galileo/GLONASS/BeiDou ephemeris records found in " + fileName);
            }
            this.localRinexStore = rnx;
            this.localSp3Store = null;
            this.loadedLocalFileName = fileName;
            long minToe = Long.MAX_VALUE;
            long maxToe = Long.MIN_VALUE;
            for (List<RinexMessageEntry> list : rnx.messagesByPrn.values()) {
                for (RinexMessageEntry m : list) {
                    minToe = Math.min(minToe, m.toeMillis());
                    maxToe = Math.max(maxToe, m.toeMillis());
                }
            }
            if (minToe == Long.MAX_VALUE) {
                minToe = 0L;
                maxToe = 0L;
            }
            return new LocalLoadSummary(fileName, GnssOrbitSource.BRDC, rnx.messagesByPrn.size(), minToe, maxToe);
        }
    }

    public void clearLocalFile() {
        this.localSp3Store = null;
        this.localRinexStore = null;
        this.loadedLocalFileName = "";
    }

    public String getLoadedLocalFileName() {
        return loadedLocalFileName;
    }

    /**
     * Resolves ITRF/ECEF satellite vectors and operational statuses for {@code simEpochMillis}.
     */
    public ResolvedEpochSnapshot resolveSatellitesAtEpoch(
            long simEpochMillis,
            GnssAwarenessService awarenessService) {
        ResolvedClockEpochSnapshot clkSnap = resolveSatellitesWithClockAtEpoch(simEpochMillis, awarenessService);
        List<GnssDopCalculator.CandidateSatellite> out = new ArrayList<>(clkSnap.candidates().size());
        for (CandidateSatelliteWithClock s : clkSnap.candidates()) {
            out.add(new GnssDopCalculator.CandidateSatellite(
                    s.prnId(), s.constellation(), s.status(), s.orbitSource(), s.satX(), s.satY(), s.satZ()));
        }
        return new ResolvedEpochSnapshot(Collections.unmodifiableList(out), clkSnap.orbitSource());
    }

    /**
     * Resolves ITRF/ECEF satellite positions, velocities, clock corrections, and operational statuses
     * for {@code simEpochMillis} (used by Layer 3 SPP and measurement analytics).
     */
    public ResolvedClockEpochSnapshot resolveSatellitesWithClockAtEpoch(
            long simEpochMillis,
            GnssAwarenessService awarenessService) {
        syncBrdcFromAwareness(awarenessService);

        ParsedSp3Store sp3 = this.localSp3Store;
        ParsedRinexNavStore localRnx = this.localRinexStore;
        ParsedRinexNavStore brdc = this.brdcStore;

        Map<String, GnssCatalogEntry> catalogMap = new LinkedHashMap<>();
        if (awarenessService != null) {
            for (GnssCatalogEntry entry : awarenessService.getCatalogEntries()) {
                if (entry != null && entry.prnId() != null && entry.constellation() != null
                        && entry.constellation().isLayer1Active()) {
                    catalogMap.putIfAbsent(entry.prnId(), entry);
                }
            }
        }

        long lastLiveSync = awarenessService != null ? awarenessService.getLastSuccessfulSyncMillis() : 0L;
        boolean outsideLiveWindow = lastLiveSync <= 0L
                || Math.abs(simEpochMillis - lastLiveSync) > SP3_LIVE_HEALTH_WINDOW_MILLIS;

        boolean historicalLocalOnly = outsideLiveWindow && (sp3 != null || localRnx != null);
        Set<String> allPrns = new LinkedHashSet<>();
        if (!historicalLocalOnly) {
            allPrns.addAll(catalogMap.keySet());
        }
        if (sp3 != null) {
            allPrns.addAll(sp3.ephemerisByPrn.keySet());
        }
        if (localRnx != null) {
            allPrns.addAll(localRnx.messagesByPrn.keySet());
        }
        if (brdc != null && allPrns.isEmpty()) {
            allPrns.addAll(brdc.messagesByPrn.keySet());
        }

        Map<String, TLE> matchedTles = (!historicalLocalOnly && awarenessService != null)
                ? awarenessService.getMatchedTlesByPrn()
                : Map.of();

        Frame itrf = FramesFactory.getITRF(IERSConventions.IERS_2010, true);
        AbsoluteDate targetDate = new AbsoluteDate(new Date(simEpochMillis), TimeScalesFactory.getUTC());

        List<CandidateSatelliteWithClock> candidates = new ArrayList<>(allPrns.size());
        int sp3Count = 0;
        int brdcCount = 0;
        int tleCount = 0;
        boolean sp3UsedWithoutHealthFlags = false;
        String fileLabel = "";

        if (sp3 != null) {
            fileLabel = sp3.fileName;
        } else if (localRnx != null) {
            fileLabel = localRnx.sourceName;
        } else if (brdc != null) {
            fileLabel = brdc.sourceName;
        }

        for (String prnId : allPrns) {
            GnssCatalogEntry catEntry = catalogMap.get(prnId);
            GnssConstellation constellation = catEntry != null
                    ? catEntry.constellation()
                    : GnssConstellation.fromPrefix(prnId.charAt(0));
            if (constellation == null || !constellation.isLayer1Active()) {
                continue;
            }
            if (awarenessService != null && !awarenessService.isConstellationEnabled(constellation)) {
                continue;
            }

            PropagatedState state = null;
            GnssOrbitSource orbitSource = GnssOrbitSource.NONE;
            boolean usedLocalRinexOrbit = false;

            if (sp3 != null) {
                state = tryPropagateSp3WithClock(sp3, prnId, targetDate, itrf);
                if (state != null) {
                    orbitSource = GnssOrbitSource.SP3;
                }
            }
            if (state == null && localRnx != null) {
                state = tryPropagateRinexWithClock(localRnx, prnId, constellation, simEpochMillis, targetDate, itrf);
                if (state != null) {
                    orbitSource = GnssOrbitSource.BRDC;
                    usedLocalRinexOrbit = true;
                }
            }
            if (state == null && brdc != null) {
                state = tryPropagateRinexWithClock(brdc, prnId, constellation, simEpochMillis, targetDate, itrf);
                if (state != null) {
                    orbitSource = GnssOrbitSource.BRDC;
                }
            }
            if (state == null && !historicalLocalOnly) {
                TLE tle = matchedTles.get(prnId);
                if (tle != null) {
                    GnssOrbitPropagator.GeodeticPosition gp = GnssOrbitPropagator
                            .propagateGeodetic(tle, simEpochMillis)
                            .orElse(null);
                    if (gp != null) {
                        double[] ecef = GnssDopCalculator.geodeticToEcef(gp.latDeg(), gp.lonDeg(), gp.altMeters());
                        state = new PropagatedState(ecef[0], ecef[1], ecef[2], 0.0, 0.0, 0.0, 0.0, 0.0, false);
                        orbitSource = GnssOrbitSource.TLE_FALLBACK;
                    }
                }
            }

            if (state == null) {
                continue;
            }

            HealthResolution healthRes = resolveHealthStatus(
                    prnId, constellation, catEntry, orbitSource,
                    simEpochMillis, awarenessService,
                    usedLocalRinexOrbit ? localRnx : null, outsideLiveWindow);

            if (healthRes.sp3WithoutHealthFlags()) {
                sp3UsedWithoutHealthFlags = true;
            }

            if (orbitSource == GnssOrbitSource.SP3) {
                sp3Count++;
            } else if (orbitSource == GnssOrbitSource.BRDC) {
                brdcCount++;
            } else if (orbitSource == GnssOrbitSource.TLE_FALLBACK) {
                tleCount++;
            }

            candidates.add(new CandidateSatelliteWithClock(
                    prnId, constellation, healthRes.status(), orbitSource,
                    state.satX(), state.satY(), state.satZ(),
                    state.velX(), state.velY(), state.velZ(),
                    state.clockBiasSec(), state.tgdSec(), state.clockValid()));
        }

        GnssOrbitSource.Summary orbitSummary = GnssOrbitSource.Summary.fromCounts(
                sp3Count, brdcCount, tleCount, sp3UsedWithoutHealthFlags, fileLabel);
        return new ResolvedClockEpochSnapshot(Collections.unmodifiableList(candidates), orbitSummary);
    }

    private record HealthResolution(GnssDisplayStatus status, boolean sp3WithoutHealthFlags) {
    }

    private static HealthResolution resolveHealthStatus(
            String prnId,
            GnssConstellation constellation,
            GnssCatalogEntry catEntry,
            GnssOrbitSource orbitSource,
            long simEpochMillis,
            GnssAwarenessService awarenessService,
            ParsedRinexNavStore usedLocalRnx,
            boolean outsideLiveWindow) {
        // Only use embedded svHealth when the local RINEX actually supplied this satellite's orbit
        if (usedLocalRnx != null && usedLocalRnx.healthByPrn.containsKey(prnId)) {
            BroadcastHealthRecord rec = usedLocalRnx.healthByPrn.get(prnId);
            GnssDisplayStatus st = (rec != null && rec.healthy())
                    ? GnssDisplayStatus.OPERATIONAL
                    : GnssDisplayStatus.UNAVAILABLE;
            return new HealthResolution(st, false);
        }
        GnssCatalogEntry effectiveEntry = catEntry != null
                ? catEntry
                : new GnssCatalogEntry(prnId, constellation, parsePrnNum(prnId), "", 0, "", prnId, GnssOrbitClass.MEO, "", "", "");
        GnssStatusResult liveRes = awarenessService != null
                ? awarenessService.evaluateStatusForEntry(effectiveEntry, simEpochMillis)
                : null;
        GnssDisplayStatus liveStatus = liveRes != null ? liveRes.status() : GnssDisplayStatus.NO_STATUS_DATA;

        // For SP3 without live health flags (>24h from live sync, offline, or NO_STATUS_DATA):
        // treat SP3 SVs as usable and flag missing health provenance accurately.
        if (orbitSource == GnssOrbitSource.SP3 && (outsideLiveWindow || liveStatus == GnssDisplayStatus.NO_STATUS_DATA)) {
            return new HealthResolution(GnssDisplayStatus.OPERATIONAL, true);
        }
        return new HealthResolution(liveStatus, false);
    }

    private static PropagatedState tryPropagateSp3WithClock(
            ParsedSp3Store sp3,
            String prnId,
            AbsoluteDate targetDate,
            Frame itrf) {
        SP3Ephemeris ephem = sp3.ephemerisByPrn.get(prnId);
        if (ephem == null || ephem.getStart() == null || ephem.getStop() == null) {
            return null;
        }
        if (targetDate.isBefore(ephem.getStart()) || targetDate.isAfter(ephem.getStop())) {
            return null;
        }
        try {
            BoundedPropagator prop = sp3.propagatorCache.computeIfAbsent(prnId, k -> ephem.getPropagator());
            TimeStampedPVCoordinates pv;
            synchronized (prop) {
                pv = prop.getPVCoordinates(targetDate, itrf);
            }
            Vector3D pos = pv.getPosition();
            Vector3D vel = pv.getVelocity();
            double clkSec = interpolateSp3ClockSec(ephem, targetDate);
            boolean clkValid = Double.isFinite(clkSec) && Math.abs(clkSec) < 0.99;
            return new PropagatedState(
                    pos.getX(), pos.getY(), pos.getZ(),
                    vel.getX(), vel.getY(), vel.getZ(),
                    clkValid ? clkSec : 0.0, 0.0, clkValid);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static double interpolateSp3ClockSec(SP3Ephemeris ephem, AbsoluteDate targetDate) {
        List<SP3Segment> segments = ephem.getSegments();
        if (segments == null || segments.isEmpty()) {
            return Double.NaN;
        }
        List<SP3Coordinate> coords = new ArrayList<>();
        for (SP3Segment seg : segments) {
            if (seg != null && seg.getCoordinates() != null) {
                coords.addAll(seg.getCoordinates());
            }
        }
        if (coords.isEmpty()) {
            return Double.NaN;
        }
        SP3Coordinate prev = null;
        SP3Coordinate next = null;
        for (SP3Coordinate c : coords) {
            if (c == null || c.getDate() == null) {
                continue;
            }
            double dt = c.getDate().durationFrom(targetDate);
            if (Math.abs(dt) < 1e-6) {
                return c.getClockCorrection();
            }
            if (dt < 0.0) {
                prev = c;
            } else {
                next = c;
                break;
            }
        }
        if (prev == null || next == null) {
            SP3Coordinate single = prev != null ? prev : next;
            return single != null ? single.getClockCorrection() : Double.NaN;
        }
        double c1 = prev.getClockCorrection();
        double c2 = next.getClockCorrection();
        if (!Double.isFinite(c1) || !Double.isFinite(c2) || Math.abs(c1) >= 0.99 || Math.abs(c2) >= 0.99) {
            return Double.NaN;
        }
        double span = next.getDate().durationFrom(prev.getDate());
        if (span <= 0.0) {
            return c1;
        }
        double frac = targetDate.durationFrom(prev.getDate()) / span;
        return c1 + frac * (c2 - c1);
    }

    private static PropagatedState tryPropagateRinexWithClock(
            ParsedRinexNavStore store,
            String prnId,
            GnssConstellation constellation,
            long simEpochMillis,
            AbsoluteDate targetDate,
            Frame itrf) {
        List<RinexMessageEntry> list = store.messagesByPrn.get(prnId);
        if (list == null || list.isEmpty()) {
            return null;
        }
        long maxAge = (constellation == GnssConstellation.GLONASS)
                ? BRDC_VALIDITY_GLONASS_MILLIS
                : BRDC_VALIDITY_KEPLER_MILLIS;

        RinexMessageEntry best = null;
        long bestDiff = Long.MAX_VALUE;
        for (RinexMessageEntry entry : list) {
            long diff = Math.abs(simEpochMillis - entry.toeMillis());
            if (diff <= maxAge && diff < bestDiff) {
                bestDiff = diff;
                best = entry;
            }
        }
        if (best == null) {
            return null;
        }
        try {
            String key = prnId + "@" + best.toeMillis();
            RinexMessageEntry chosen = best;
            Propagator prop = store.propagatorCache.computeIfAbsent(key, k -> buildPropagator(chosen, itrf));
            if (prop == null) {
                return null;
            }
            TimeStampedPVCoordinates pv;
            synchronized (prop) {
                pv = prop.getPVCoordinates(targetDate, itrf);
            }
            Vector3D pos = pv.getPosition();
            Vector3D vel = pv.getVelocity();
            double clkBiasSec = 0.0;
            double tgdSec = 0.0;
            boolean clkValid = false;
            double c = 299_792_458.0;
            if (chosen.keplerElements() instanceof GNSSClockElements clk) {
                AbsoluteDate tocDate = (chosen.keplerElements() instanceof org.orekit.propagation.analytical.gnss.data.AbstractNavigationMessage<?> navMsg
                        && navMsg.getEpochToc() != null)
                        ? navMsg.getEpochToc()
                        : chosen.keplerElements().getDate();
                double dt = targetDate.durationFrom(tocDate);
                double rel = -2.0 * pos.dotProduct(vel) / (c * c);
                clkBiasSec = clk.getAf0() + clk.getAf1() * dt + clk.getAf2() * dt * dt + rel;
                double rawTgd = clk.getTGD();
                if (clk instanceof org.orekit.propagation.analytical.gnss.data.GalileoNavigationMessage gal) {
                    double bgd = gal.getBGDE1E5a();
                    if (Double.isFinite(bgd)) {
                        rawTgd = bgd;
                    }
                } else if (clk instanceof org.orekit.propagation.analytical.gnss.data.BeidouLegacyNavigationMessage bds) {
                    double tgd1 = bds.getTGD1();
                    if (Double.isFinite(tgd1)) {
                        rawTgd = tgd1;
                    }
                } else if (clk instanceof org.orekit.propagation.analytical.gnss.data.BeidouCivilianNavigationMessage bdsCiv) {
                    double tgdB1 = bdsCiv.getTgdB1Cp();
                    if (Double.isFinite(tgdB1)) {
                        rawTgd = tgdB1;
                    }
                }
                tgdSec = Double.isFinite(rawTgd) ? rawTgd : 0.0;
                clkValid = Double.isFinite(clkBiasSec);
            } else if (chosen.glonassElements() instanceof GLONASSNavigationMessage glo) {
                double dt = targetDate.durationFrom(glo.getDate());
                clkBiasSec = -glo.getTN() + glo.getGammaN() * dt;
                clkValid = Double.isFinite(clkBiasSec);
            }
            return new PropagatedState(
                    pos.getX(), pos.getY(), pos.getZ(),
                    vel.getX(), vel.getY(), vel.getZ(),
                    clkBiasSec, tgdSec, clkValid);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Propagator buildPropagator(RinexMessageEntry entry, Frame itrf) {
        if (entry.keplerElements() != null) {
            return new GNSSPropagatorBuilder(entry.keplerElements()).ecef(itrf).build();
        }
        if (entry.glonassElements() != null) {
            return new GLONASSAnalyticalPropagatorBuilder(entry.glonassElements()).ecef(itrf).build();
        }
        return null;
    }

    private static ParsedRinexNavStore parseRinexNav(byte[] plainBytes, String sourceName, boolean extractHealth)
            throws IOException {
        RinexNavigation nav;
        try {
            DataSource ds = new DataSource(sourceName, () -> new ByteArrayInputStream(plainBytes));
            nav = new RinexNavigationParser().parse(ds);
        } catch (RuntimeException e) {
            throw new IOException("Unable to parse RINEX navigation file " + sourceName + ": " + e.getMessage(), e);
        }

        TimeScale utc = TimeScalesFactory.getUTC();
        Map<String, List<RinexMessageEntry>> byPrn = new LinkedHashMap<>();

        collectKeplerMessages(nav.getGPSLegacyNavigationMessages(), GnssConstellation.GPS, utc, byPrn);
        collectKeplerMessages(nav.getGPSCivilianNavigationMessages(), GnssConstellation.GPS, utc, byPrn);
        collectKeplerMessages(nav.getGalileoNavigationMessages(), GnssConstellation.GALILEO, utc, byPrn);
        collectKeplerMessages(nav.getBeidouLegacyNavigationMessages(), GnssConstellation.BEIDOU, utc, byPrn);
        collectKeplerMessages(nav.getBeidouCivilianNavigationMessages(), GnssConstellation.BEIDOU, utc, byPrn);
        collectGlonassMessages(nav.getGlonassNavigationMessages(), utc, byPrn);

        Map<String, BroadcastHealthRecord> healthMap = Map.of();
        if (extractHealth) {
            try {
                healthMap = RinexNavHealthParser.parse(new ByteArrayInputStream(plainBytes)).latestByPrn();
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return new ParsedRinexNavStore(sourceName, byPrn, healthMap);
    }

    private static <T extends GNSSOrbitalElements<?>> void collectKeplerMessages(
            Map<String, List<T>> rawMap,
            GnssConstellation constellation,
            TimeScale utc,
            Map<String, List<RinexMessageEntry>> out) {
        if (rawMap == null || rawMap.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<T>> entry : rawMap.entrySet()) {
            String prnId = normalizePrnId(entry.getKey(), constellation);
            if (prnId == null || entry.getValue() == null) {
                continue;
            }
            List<RinexMessageEntry> target = out.computeIfAbsent(prnId, k -> new ArrayList<>());
            for (T msg : entry.getValue()) {
                if (msg == null || msg.getDate() == null) {
                    continue;
                }
                long toeMillis = msg.getDate().toDate(utc).getTime();
                target.add(new RinexMessageEntry(prnId, constellation, toeMillis, msg, null));
            }
        }
    }

    private static void collectGlonassMessages(
            Map<String, List<GLONASSNavigationMessage>> rawMap,
            TimeScale utc,
            Map<String, List<RinexMessageEntry>> out) {
        if (rawMap == null || rawMap.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<GLONASSNavigationMessage>> entry : rawMap.entrySet()) {
            String prnId = normalizePrnId(entry.getKey(), GnssConstellation.GLONASS);
            if (prnId == null || entry.getValue() == null) {
                continue;
            }
            List<RinexMessageEntry> target = out.computeIfAbsent(prnId, k -> new ArrayList<>());
            for (GLONASSNavigationMessage msg : entry.getValue()) {
                if (msg == null || msg.getDate() == null) {
                    continue;
                }
                long toeMillis = msg.getDate().toDate(utc).getTime();
                target.add(new RinexMessageEntry(prnId, GnssConstellation.GLONASS, toeMillis, null, msg));
            }
        }
    }

    private static ParsedSp3Store parseSp3(byte[] plainBytes, String fileName) throws IOException {
        SP3 sp3;
        try {
            DataSource ds = new DataSource(fileName, () -> new ByteArrayInputStream(plainBytes));
            sp3 = new SP3Parser().parse(ds);
        } catch (RuntimeException e) {
            throw new IOException("Unable to parse SP3 orbit file " + fileName + ": " + e.getMessage(), e);
        }
        if (sp3 == null || sp3.getSatellites().isEmpty()) {
            throw new IOException("SP3 file contains no satellite ephemerides: " + fileName);
        }
        Map<String, SP3Ephemeris> byPrn = new LinkedHashMap<>();
        for (Map.Entry<String, SP3Ephemeris> entry : sp3.getSatellites().entrySet()) {
            String prnId = normalizePrnId(entry.getKey(), null);
            if (prnId == null || entry.getValue() == null) {
                continue;
            }
            GnssConstellation c = GnssConstellation.fromPrefix(prnId.charAt(0));
            if (c != null && c.isLayer1Active()) {
                byPrn.put(prnId, entry.getValue());
            }
        }
        if (byPrn.isEmpty()) {
            throw new IOException("No supported G/E/R/C satellites found in SP3 file: " + fileName);
        }
        return new ParsedSp3Store(fileName, byPrn);
    }

    private static boolean looksLikeSp3(String fileName, byte[] plainBytes) {
        String lower = fileName != null ? fileName.toLowerCase(Locale.ROOT) : "";
        if (lower.endsWith(".sp3") || lower.endsWith(".sp3.gz") || lower.endsWith(".eph") || lower.endsWith(".eph.gz")) {
            return true;
        }
        if (plainBytes != null && plainBytes.length >= 2) {
            return plainBytes[0] == '#' && (plainBytes[1] == 'a' || plainBytes[1] == 'c' || plainBytes[1] == 'd');
        }
        return false;
    }

    private static String normalizePrnId(String raw, GnssConstellation fallbackConstellation) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim().toUpperCase(Locale.ROOT);
        if (trimmed.length() >= 2 && Character.isLetter(trimmed.charAt(0))) {
            char prefix = trimmed.charAt(0);
            String digits = trimmed.substring(1).trim();
            try {
                int num = Integer.parseInt(digits);
                return prefix + String.format(Locale.US, "%02d", num);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (fallbackConstellation != null) {
            try {
                int num = Integer.parseInt(trimmed);
                return fallbackConstellation.getPrefix() + String.format(Locale.US, "%02d", num);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static int parsePrnNum(String prnId) {
        if (prnId == null || prnId.length() < 2) {
            return 0;
        }
        try {
            return Integer.parseInt(prnId.substring(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static byte[] unwrapGzipIfNeeded(byte[] raw) throws IOException {
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0x1F) {
            int b1 = raw[1] & 0xFF;
            if (b1 == 0x8B || b1 == 0x9D) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length * 4);
                byte[] buf = new byte[8192];
                try (java.io.InputStream compressed = (b1 == 0x8B)
                        ? new GZIPInputStream(new ByteArrayInputStream(raw))
                        : new org.orekit.data.UnixCompressFilter()
                                .filter(new DataSource("nav.Z", () -> new ByteArrayInputStream(raw)))
                                .getOpener()
                                .openStreamOnce()) {
                    int n;
                    while ((n = compressed.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                } catch (EOFException eof) {
                    // Salvage decompressed content before a truncated compressed tail
                }
                return out.toByteArray();
            }
        }
        return raw;
    }
}

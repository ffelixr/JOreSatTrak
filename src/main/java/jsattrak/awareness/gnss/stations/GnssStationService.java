package jsattrak.awareness.gnss.stations;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import jsattrak.awareness.AwarenessFeed;
import jsattrak.awareness.config.AwarenessSettings;
import jsattrak.awareness.config.AwarenessSettingsStore;
import jsattrak.awareness.config.DataSourceConfig;
import jsattrak.awareness.gnss.GnssAwarenessService;
import jsattrak.awareness.gnss.GnssStateListener;
import jsattrak.awareness.gnss.geometry.GnssEphemerisProvider;
import jsattrak.awareness.gnss.geometry.GnssGeometryListener;
import jsattrak.awareness.gnss.geometry.GnssGeometryService;
import jsattrak.awareness.gnss.net.GnssStationCatalogClient;
import jsattrak.awareness.gnss.stations.analysis.GnssStationAnalysis;
import jsattrak.awareness.gnss.stations.analysis.RegionalCn0AnomalyDetector;
import jsattrak.awareness.gnss.stations.analysis.RegionalScanReport;
import jsattrak.awareness.gnss.stations.analysis.RinexObservationAnalyzer;
import jsattrak.awareness.gnss.stations.obs.BkgHourlyRinexSource;
import jsattrak.awareness.gnss.stations.obs.GnssObservationDiskCache;
import jsattrak.awareness.gnss.stations.obs.GnssObservationSource;
import jsattrak.awareness.gnss.stations.obs.LocalRinexFileSource;
import jsattrak.awareness.gnss.stations.obs.NoaaCorsS3RinexSource;
import jsattrak.awareness.gnss.stations.obs.RinexObservationData;

/**
 * Central Layer-3 coordinator ({@link AwarenessFeed}) managing GNSS reference station catalogs,
 * NRT archive latency metadata, on-demand single/regional RINEX observation acquisition,
 * quality & SPP analysis, and regional C/N0 interference scanning.
 */
public class GnssStationService implements AwarenessFeed {

    public static final String FEED_ID = "gnss-stations";
    public static final String DISPLAY_NAME = "GNSS Stations";
    public static final String DEFAULT_CACHE_FILE = "gnss-stations-cache.json";
    private static final String NOAA_STATION_LOG_BASE = "https://noaa-cors-pds.s3.amazonaws.com/station_log/";
    private static final long PERIODIC_SYNC_MINUTES = 60L;
    private static final long STALE_AFTER_MILLIS = 12L * 3600L * 1000L;

    private final GnssAwarenessService awarenessService;
    private final GnssGeometryService geometryService;
    private final AwarenessSettingsStore settingsStore;
    private final Path cachePath;
    private final GnssStationCatalogClient catalogClient;
    private final List<GnssObservationSource> observationSources;
    private final LocalRinexFileSource localRinexSource;
    private final Clock clock;
    private final Gson gson = new GsonBuilder()
            .serializeSpecialFloatingPointValues()
            .setPrettyPrinting()
            .create();

    private final CopyOnWriteArrayList<GnssStationListener> listeners = new CopyOnWriteArrayList<>();
    private final Object lifecycleLock = new Object();
    private final AtomicLong inspectionGeneration = new AtomicLong(0L);
    private final AtomicLong scanGeneration = new AtomicLong(0L);
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    private ScheduledExecutorService catalogScheduler;
    private ScheduledFuture<?> periodicFuture;
    private ExecutorService workerPool;
    private ExecutorService regionalFetchPool;
    private boolean activated;
    private boolean hasSyncedInSession;
    private boolean shutdown;

    private final GnssStateListener awarenessListener;
    private final GnssGeometryListener geometryListener;

    private volatile List<GnssReferenceStation> stations = List.of();
    private volatile String selectedStationId;
    private volatile boolean mapOverlayEnabled = false; // master gate OFF on startup
    private volatile boolean matchSimEpoch = false;
    private volatile long lastSimEpochMillis = System.currentTimeMillis();
    private volatile boolean lastRealTimeMode = true;

    private volatile Map<GnssStationNetwork, Boolean> networksEnabled = defaultNetworkMap();
    private volatile double scanRadiusKm = 250.0;
    private volatile int scanMaxStations = 6;
    private volatile double stationMaskDeg = 10.0;

    private volatile long lastSuccessfulSyncMillis = 0L;
    private volatile String lastStatusMessage = "Ready (seed / offline)";
    private volatile boolean lastStatusWarning = false;

    private volatile GnssReferenceStation loadedObsStation;
    private volatile RinexObservationData loadedObsData;
    private volatile String loadedObsFormat = "";
    private volatile String loadedObsSourceName = "";
    private volatile GnssStationAnalysis latestAnalysis;
    private volatile RegionalScanReport latestRegionalReport = RegionalScanReport.empty();

    public GnssStationService(
            GnssAwarenessService awarenessService,
            GnssGeometryService geometryService,
            AwarenessSettingsStore settingsStore) {
        this(awarenessService, geometryService, settingsStore,
                Path.of(System.getProperty("user.home"), ".joresattrak", DEFAULT_CACHE_FILE),
                new GnssStationCatalogClient(),
                null,
                Clock.systemUTC());
    }

    public GnssStationService(
            GnssAwarenessService awarenessService,
            GnssGeometryService geometryService,
            AwarenessSettingsStore settingsStore,
            Path cachePath,
            GnssStationCatalogClient catalogClient,
            List<GnssObservationSource> customSources,
            Clock clock) {
        this.awarenessService = Objects.requireNonNull(awarenessService, "awarenessService");
        this.geometryService = Objects.requireNonNull(geometryService, "geometryService");
        this.settingsStore = settingsStore;
        this.cachePath = cachePath != null
                ? cachePath
                : Path.of(System.getProperty("user.home"), ".joresattrak", DEFAULT_CACHE_FILE);
        this.catalogClient = catalogClient != null ? catalogClient : new GnssStationCatalogClient();
        this.localRinexSource = new LocalRinexFileSource();
        this.clock = clock != null ? clock : Clock.systemUTC();

        if (customSources != null && !customSources.isEmpty()) {
            this.observationSources = List.copyOf(customSources);
        } else {
            GnssObservationDiskCache diskCache = new GnssObservationDiskCache();
            this.observationSources = List.of(
                    new BkgHourlyRinexSource(this::getArchiveSources, diskCache),
                    new NoaaCorsS3RinexSource(this::getArchiveSources, diskCache),
                    this.localRinexSource);
        }

        loadSettingsFromAwareness();
        loadInitialStations();

        this.awarenessListener = new GnssStateListener() {
            @Override
            public void onGnssDataChanged() {
                recomputeLoadedAnalysisInBackground();
            }

            @Override
            public void onGnssSelectionChanged(String selectedPrnId) {
            }

            @Override
            public void onGnssStatusMessage(String message, boolean isWarning) {
            }
        };
        this.awarenessService.addListener(this.awarenessListener);

        this.geometryListener = new GnssGeometryListener() {
            @Override
            public void onGeometryDataChanged() {
                recomputeLoadedAnalysisInBackground();
            }
        };
        this.geometryService.addListener(this.geometryListener);
    }

    // ---- AwarenessFeed ----

    @Override
    public String getFeedId() {
        return FEED_ID;
    }

    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    public int getActiveItemCount() {
        return getFilteredStations("", null).size();
    }

    @Override
    public boolean isMapOverlayEnabled() {
        return mapOverlayEnabled;
    }

    @Override
    public void setMapOverlayEnabled(boolean enabled) {
        if (this.mapOverlayEnabled != enabled) {
            this.mapOverlayEnabled = enabled;
            dispatchCallbacks(l -> l.onMapOverlayStateChanged(enabled));
            dispatchCallbacks(GnssStationListener::onStationCatalogChanged);
        }
    }

    // ---- Lifecycle ----

    public void initializeAtStartup() {
        AwarenessSettings s = awarenessService.getSettings();
        if (s == null || !s.isAutoFetchOnStartup() || !s.isGnssFeedEnabled()) {
            return;
        }
        ensureActivated();
    }

    public void ensureActivated() {
        AwarenessSettings s = awarenessService.getSettings();
        if (s == null || !s.isGnssFeedEnabled()) {
            return;
        }
        boolean shouldRefresh = false;
        synchronized (lifecycleLock) {
            if (shutdown) {
                return;
            }
            activated = true;
            if (periodicFuture == null || periodicFuture.isCancelled()) {
                ScheduledExecutorService sched = ensureCatalogSchedulerLocked();
                periodicFuture = sched.scheduleAtFixedRate(
                        this::refreshNowAsync, PERIODIC_SYNC_MINUTES, PERIODIC_SYNC_MINUTES, TimeUnit.MINUTES);
            }
            if (!hasSyncedInSession && !refreshing.get()) {
                shouldRefresh = true;
            }
        }
        if (shouldRefresh) {
            refreshNowAsync();
        }
    }

    public void shutdown() {
        synchronized (lifecycleLock) {
            shutdown = true;
            if (periodicFuture != null) {
                periodicFuture.cancel(true);
            }
            if (catalogScheduler != null) {
                catalogScheduler.shutdownNow();
            }
            if (workerPool != null) {
                workerPool.shutdownNow();
            }
            if (regionalFetchPool != null) {
                regionalFetchPool.shutdownNow();
            }
        }
        awarenessService.removeListener(awarenessListener);
        geometryService.removeListener(geometryListener);
    }

    // ---- Listeners ----

    public void addListener(GnssStationListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(GnssStationListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    // ---- Catalog Sync ----

    public boolean isRefreshing() {
        return refreshing.get();
    }

    public long getLastSuccessfulSyncMillis() {
        return lastSuccessfulSyncMillis;
    }

    public boolean isDataStale() {
        if (lastSuccessfulSyncMillis <= 0L) {
            return true;
        }
        return (clock.millis() - lastSuccessfulSyncMillis) > STALE_AFTER_MILLIS;
    }

    public String getLastStatusMessage() {
        return lastStatusMessage;
    }

    public boolean isLastStatusWarning() {
        return lastStatusWarning;
    }

    public void refreshNowAsync() {
        AwarenessSettings s = awarenessService.getSettings();
        if (s != null && !s.isGnssFeedEnabled()) {
            updateStatus("GNSS feed disabled in Live Data Sources", true);
            return;
        }
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        synchronized (lifecycleLock) {
            if (shutdown) {
                refreshing.set(false);
                return;
            }
            ensureCatalogSchedulerLocked().execute(() -> {
                try {
                    long now = clock.millis();
                    GnssStationCatalogClient.CatalogSyncResult res = catalogClient.fetchMergedCatalog(
                            getCatalogSources(), getArchiveSources(), now);
                    if (res != null && !res.stations().isEmpty()) {
                        this.stations = res.stations();
                        this.lastSuccessfulSyncMillis = now;
                        synchronized (lifecycleLock) {
                            this.hasSyncedInSession = true;
                        }
                        saveAtomicCache(res.stations());
                        updateStatus(res.summaryMessage(), false);
                        dispatchCallbacks(GnssStationListener::onStationCatalogChanged);
                    }
                } catch (IOException | RuntimeException e) {
                    String msg = "Station catalog sync failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                    updateStatus(msg, true);
                } finally {
                    refreshing.set(false);
                }
            });
        }
    }

    // ---- Selection, Filters & Controls ----

    public List<GnssReferenceStation> getStations() {
        return stations;
    }

    public List<GnssReferenceStation> getFilteredStations(String query, GnssStationStatus statusFilter) {
        String q = query != null ? query.trim().toUpperCase(Locale.US) : "";
        List<GnssReferenceStation> out = new ArrayList<>();
        for (GnssReferenceStation st : stations) {
            if (st == null) {
                continue;
            }
            boolean netMatch = false;
            for (GnssStationNetwork n : st.networks()) {
                if (n == GnssStationNetwork.LOCAL || networksEnabled.getOrDefault(n.name(), Boolean.TRUE)) {
                    netMatch = true;
                    break;
                }
            }
            if (!netMatch) {
                continue;
            }
            if (statusFilter != null && st.status() != statusFilter) {
                continue;
            }
            if (!q.isEmpty()) {
                boolean textMatch = st.stationId().contains(q)
                        || st.shortCode().contains(q)
                        || st.siteName().toUpperCase(Locale.US).contains(q)
                        || st.countryCode().toUpperCase(Locale.US).contains(q)
                        || st.receiverModel().toUpperCase(Locale.US).contains(q);
                if (!textMatch) {
                    continue;
                }
            }
            out.add(st);
        }
        return out;
    }

    public GnssReferenceStation findStationById(String stationId) {
        if (stationId == null || stationId.isBlank()) {
            return null;
        }
        for (GnssReferenceStation st : stations) {
            if (stationId.equalsIgnoreCase(st.stationId()) || stationId.equalsIgnoreCase(st.shortCode())) {
                return st;
            }
        }
        return null;
    }

    public void selectStation(String stationId) {
        if (Objects.equals(this.selectedStationId, stationId)) {
            return;
        }
        this.selectedStationId = stationId;
        dispatchCallbacks(l -> l.onSelectedStationChanged(stationId));

        GnssReferenceStation st = findStationById(stationId);
        if (st != null && st.networks().contains(GnssStationNetwork.NOAA_CORS) && st.receiverModel().isBlank()) {
            ensureWorkerPool().execute(() -> enrichStationQuietly(st));
        }
    }

    public String getSelectedStationId() {
        return selectedStationId;
    }

    public GnssReferenceStation getSelectedStation() {
        return findStationById(selectedStationId);
    }

    public boolean isNetworkEnabled(GnssStationNetwork net) {
        return net != null && networksEnabled.getOrDefault(net.name(), Boolean.TRUE);
    }

    public void setNetworkEnabled(GnssStationNetwork net, boolean enabled) {
        if (net == null) {
            return;
        }
        EnumMap<GnssStationNetwork, Boolean> copy = new EnumMap<>(networksEnabled);
        if (Objects.equals(copy.get(net), enabled)) {
            return;
        }
        copy.put(net, enabled);
        this.networksEnabled = Map.copyOf(copy);
        persistLayer3Settings();
        dispatchCallbacks(GnssStationListener::onStationCatalogChanged);
    }

    public double getElevationMaskDeg() {
        return stationMaskDeg;
    }

    public void setElevationMaskDeg(double maskDeg) {
        double clamped = Math.max(0.0, Math.min(45.0, maskDeg));
        if (Double.compare(this.stationMaskDeg, clamped) != 0) {
            this.stationMaskDeg = clamped;
            persistLayer3Settings();
            recomputeLoadedAnalysisInBackground();
        }
    }

    public boolean isMatchSimEpoch() {
        return matchSimEpoch;
    }

    public void setMatchSimEpoch(boolean matchSimEpoch) {
        this.matchSimEpoch = matchSimEpoch;
    }

    public double getScanRadiusKm() {
        return scanRadiusKm;
    }

    public void setScanRadiusKm(double radiusKm) {
        double clamped = Math.max(50.0, Math.min(1000.0, radiusKm));
        if (Double.compare(this.scanRadiusKm, clamped) != 0) {
            this.scanRadiusKm = clamped;
            persistLayer3Settings();
        }
    }

    public int getScanMaxStations() {
        return scanMaxStations;
    }

    public void setScanMaxStations(int maxStations) {
        int clamped = Math.max(3, Math.min(12, maxStations));
        if (this.scanMaxStations != clamped) {
            this.scanMaxStations = clamped;
            persistLayer3Settings();
        }
    }

    public long getLastSimEpochMillis() {
        return lastSimEpochMillis;
    }

    public boolean isLastRealTimeMode() {
        return lastRealTimeMode;
    }

    /**
     * Updates the current simulation epoch for in-memory inspection alignment. Never triggers
     * network downloads even when {@code matchSimEpoch == true}.
     */
    public void updateSimEpochMillis(long epochMillis, boolean realTimeMode) {
        this.lastSimEpochMillis = epochMillis;
        this.lastRealTimeMode = realTimeMode;
        if (loadedObsData != null && loadedObsStation != null) {
            GnssStationAnalysis current = this.latestAnalysis;
            if (current == null || Math.abs(epochMillis - current.inspectionEpochMillis()) >= 30_000L) {
                recomputeLoadedAnalysisInBackground();
            }
        }
    }

    // ---- Observation Fetch, Local Import & Regional Scan ----

    public GnssStationAnalysis getLatestAnalysis() {
        return latestAnalysis;
    }

    public String getLoadedObsFormat() {
        return loadedObsFormat;
    }

    public String getLoadedObsSourceName() {
        return loadedObsSourceName;
    }

    public RegionalScanReport getLatestRegionalReport() {
        return latestRegionalReport;
    }

    public GnssEphemerisProvider getSharedEphemerisProvider() {
        return geometryService.getEphemerisProvider();
    }

    public GnssEphemerisProvider.LocalLoadSummary importLocalNavOrSp3File(Path file) throws IOException {
        GnssEphemerisProvider.LocalLoadSummary summary = geometryService.loadLocalEphemerisFile(file);
        recomputeLoadedAnalysisInBackground();
        return summary;
    }

    public void clearLocalNavOrSp3File() {
        geometryService.clearLocalEphemerisFile();
        recomputeLoadedAnalysisInBackground();
    }

    public void fetchSelectedStationObservationsAsync() {
        GnssReferenceStation station = getSelectedStation();
        if (station == null) {
            updateStatus("Select a GNSS station first", true);
            return;
        }
        final long gen = inspectionGeneration.incrementAndGet();
        final boolean preferLatest = !matchSimEpoch || lastRealTimeMode;
        final long targetEpoch = preferLatest ? clock.millis() : lastSimEpochMillis;

        updateStatus("Fetching RINEX observations for " + station.stationId() + "...", false);
        ensureWorkerPool().execute(() -> {
            try {
                GnssReferenceStation resolvedStation = maybeEnrichNoaa(station);
                GnssObservationSource.ObservationFetchResult fetched = fetchUsingSources(
                        resolvedStation,
                        new GnssObservationSource.ObservationQuery(targetEpoch, 60, preferLatest));
                if (gen != inspectionGeneration.get() || shutdown) {
                    return;
                }
                GnssReferenceStation st = fetched.resolvedStation();
                upsertStation(st);
                this.loadedObsStation = st;
                this.loadedObsData = fetched.observationData();
                this.loadedObsFormat = fetched.formatLabel();
                this.loadedObsSourceName = fetched.sourceName();

                GnssStationAnalysis analysis = RinexObservationAnalyzer.analyze(
                        st,
                        fetched.observationData(),
                        stationMaskDeg,
                        targetEpoch,
                        geometryService.getEphemerisProvider(),
                        awarenessService);
                if (gen != inspectionGeneration.get() || shutdown) {
                    return;
                }
                this.latestAnalysis = analysis;
                updateStatus("Loaded " + st.stationId() + " (" + fetched.observationData().epochs().size()
                        + " epochs, " + fetched.formatLabel() + ")", false);
                dispatchCallbacks(l -> l.onInspectionUpdated(analysis));
            } catch (IOException | RuntimeException e) {
                if (gen == inspectionGeneration.get()) {
                    String msg = "Observation fetch failed for " + station.stationId() + ": " + e.getMessage();
                    updateStatus(msg, true);
                }
            }
        });
    }

    public void loadLocalObservationFilesAsync(List<Path> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        final long inspGen = inspectionGeneration.incrementAndGet();
        final long scanGen = scanGeneration.incrementAndGet();
        final long targetEpoch = lastSimEpochMillis;

        updateStatus("Loading " + files.size() + " local RINEX observation file(s)...", false);
        ensureWorkerPool().execute(() -> {
            try {
                List<RegionalCn0AnomalyDetector.StationObservationInput> inputs = new ArrayList<>();
                GnssStationAnalysis firstAnalysis = null;
                GnssReferenceStation firstStation = null;
                RinexObservationData firstObs = null;
                String firstFormat = "";
                String firstSrc = "";

                for (Path file : files) {
                    if (file == null || !Files.isRegularFile(file)) {
                        continue;
                    }
                    GnssObservationSource.ObservationFetchResult res = localRinexSource.fetchFromFile(file, null);
                    GnssReferenceStation st = res.resolvedStation();
                    upsertStation(st);
                    GnssStationAnalysis analysis = RinexObservationAnalyzer.analyze(
                            st, res.observationData(), stationMaskDeg, targetEpoch,
                            geometryService.getEphemerisProvider(), awarenessService);
                    inputs.add(new RegionalCn0AnomalyDetector.StationObservationInput(st, analysis, 0.0));
                    if (firstAnalysis == null) {
                        firstAnalysis = analysis;
                        firstStation = st;
                        firstObs = res.observationData();
                        firstFormat = res.formatLabel();
                        firstSrc = res.sourceName();
                    }
                }
                if (firstAnalysis == null || inspGen != inspectionGeneration.get() || shutdown) {
                    return;
                }
                this.selectedStationId = firstStation.stationId();
                this.loadedObsStation = firstStation;
                this.loadedObsData = firstObs;
                this.loadedObsFormat = firstFormat;
                this.loadedObsSourceName = firstSrc;
                this.latestAnalysis = firstAnalysis;

                final String selId = firstStation.stationId();
                final GnssStationAnalysis finalAnalysis = firstAnalysis;
                dispatchCallbacks(GnssStationListener::onStationCatalogChanged);
                dispatchCallbacks(l -> l.onSelectedStationChanged(selId));
                dispatchCallbacks(l -> l.onInspectionUpdated(finalAnalysis));

                if (inputs.size() >= 2 && scanGen == scanGeneration.get()) {
                    RegionalScanReport report = RegionalCn0AnomalyDetector.evaluate(inputs);
                    this.latestRegionalReport = report;
                    dispatchCallbacks(l -> l.onRegionalScanUpdated(report));
                    updateStatus(report.diagnosis(),
                            report.severity() != RegionalScanReport.RegionalEventSeverity.NOMINAL);
                } else {
                    updateStatus("Loaded local RINEX: " + firstStation.stationId(), false);
                }
            } catch (IOException | RuntimeException e) {
                updateStatus("Local RINEX import failed: " + e.getMessage(), true);
            }
        });
    }

    public void scanRegionalInterferenceAsync(String centerStationId) {
        GnssReferenceStation center = findStationById(centerStationId != null ? centerStationId : selectedStationId);
        if (center == null) {
            updateStatus("Select a center station before running Regional Scan", true);
            return;
        }
        final long gen = scanGeneration.incrementAndGet();
        final boolean preferLatest = !matchSimEpoch || lastRealTimeMode;
        final long targetEpoch = preferLatest ? clock.millis() : lastSimEpochMillis;
        final double maxRadius = this.scanRadiusKm;
        final int maxCount = this.scanMaxStations;

        List<GnssReferenceStation> candidates = stations.stream()
                .filter(s -> s != null && s.status() != GnssStationStatus.OFFLINE)
                .filter(s -> s.distanceKmTo(center.latDeg(), center.lonDeg()) <= maxRadius)
                .sorted(Comparator.comparingDouble(s -> s.distanceKmTo(center.latDeg(), center.lonDeg())))
                .limit(maxCount)
                .toList();

        if (candidates.isEmpty()) {
            candidates = List.of(center);
        }

        final List<GnssReferenceStation> scanTargets = candidates;
        updateStatus("Scanning " + scanTargets.size() + " stations within " + Math.round(maxRadius) + " km...", false);

        ensureWorkerPool().execute(() -> {
            ExecutorService pool = ensureRegionalFetchPool();
            List<Future<RegionalCn0AnomalyDetector.StationObservationInput>> futures = new ArrayList<>();
            for (GnssReferenceStation st : scanTargets) {
                if (shutdown) {
                    return;
                }
                try {
                    futures.add(pool.submit(() -> {
                        GnssReferenceStation enriched = maybeEnrichNoaa(st);
                        GnssObservationSource.ObservationFetchResult fetched = fetchUsingSources(
                                enriched,
                                new GnssObservationSource.ObservationQuery(targetEpoch, 60, preferLatest));
                        GnssStationAnalysis analysis = RinexObservationAnalyzer.analyze(
                                fetched.resolvedStation(),
                                fetched.observationData(),
                                stationMaskDeg,
                                targetEpoch,
                                geometryService.getEphemerisProvider(),
                                awarenessService);
                        double dist = center.distanceKmTo(enriched.latDeg(), enriched.lonDeg());
                        return new RegionalCn0AnomalyDetector.StationObservationInput(
                                fetched.resolvedStation(), analysis, dist);
                    }));
                } catch (java.util.concurrent.RejectedExecutionException ignored) {
                    return;
                }
            }

            List<RegionalCn0AnomalyDetector.StationObservationInput> completed = new ArrayList<>();
            for (Future<RegionalCn0AnomalyDetector.StationObservationInput> f : futures) {
                try {
                    RegionalCn0AnomalyDetector.StationObservationInput res = f.get();
                    if (res != null) {
                        completed.add(res);
                    }
                } catch (Exception ignored) {
                }
            }
            if (gen != scanGeneration.get() || shutdown) {
                return;
            }
            RegionalScanReport report = RegionalCn0AnomalyDetector.evaluate(completed);
            this.latestRegionalReport = report;
            updateStatus(report.diagnosis(),
                    report.severity() == RegionalScanReport.RegionalEventSeverity.POTENTIAL_INTERFERENCE);
            dispatchCallbacks(l -> l.onRegionalScanUpdated(report));
        });
    }

    // ---- Helpers ----

    private void recomputeLoadedAnalysisInBackground() {
        GnssReferenceStation st = this.loadedObsStation;
        RinexObservationData data = this.loadedObsData;
        if (st == null || data == null || shutdown) {
            return;
        }
        final long gen = inspectionGeneration.incrementAndGet();
        final long epoch = this.lastSimEpochMillis;
        ensureWorkerPool().execute(() -> {
            if (gen != inspectionGeneration.get() || shutdown) {
                return;
            }
            GnssStationAnalysis updated = RinexObservationAnalyzer.analyze(
                    st, data, stationMaskDeg, epoch,
                    geometryService.getEphemerisProvider(), awarenessService);
            if (gen == inspectionGeneration.get() && !shutdown) {
                this.latestAnalysis = updated;
                dispatchCallbacks(l -> l.onInspectionUpdated(updated));
            }
        });
    }

    private GnssObservationSource.ObservationFetchResult fetchUsingSources(
            GnssReferenceStation station,
            GnssObservationSource.ObservationQuery query) throws IOException {
        IOException lastEx = null;
        for (GnssObservationSource src : observationSources) {
            if (src == null) {
                continue;
            }
            boolean supports = station.networks().stream().anyMatch(src::supportsNetwork);
            if (!supports) {
                continue;
            }
            try {
                return src.fetchObservations(station, query);
            } catch (IOException | RuntimeException e) {
                lastEx = (e instanceof IOException io) ? io : new IOException(e.getMessage(), e);
            }
        }
        throw lastEx != null ? lastEx : new IOException("No observation source supports " + station.stationId());
    }

    private void enrichStationQuietly(GnssReferenceStation st) {
        try {
            GnssReferenceStation enriched = maybeEnrichNoaa(st);
            if (enriched != st) {
                upsertStation(enriched);
                dispatchCallbacks(GnssStationListener::onStationCatalogChanged);
            }
        } catch (Exception ignored) {
        }
    }

    private GnssReferenceStation maybeEnrichNoaa(GnssReferenceStation st) {
        if (st != null && st.networks().contains(GnssStationNetwork.NOAA_CORS)
                && st.receiverModel().isBlank()) {
            try {
                return catalogClient.enrichNoaaStationHardware(st, NOAA_STATION_LOG_BASE);
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return st;
    }

    private void upsertStation(GnssReferenceStation updated) {
        if (updated == null) {
            return;
        }
        List<GnssReferenceStation> current = new ArrayList<>(this.stations);
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i).stationId().equalsIgnoreCase(updated.stationId())) {
                current.set(i, updated);
                this.stations = List.copyOf(current);
                return;
            }
        }
        current.add(updated);
        this.stations = List.copyOf(current);
    }

    private List<DataSourceConfig> getCatalogSources() {
        AwarenessSettings s = awarenessService.getSettings();
        return s != null ? s.getEnabledSourcesByCategory(DataSourceConfig.FeedCategory.GNSS_STATION_CATALOG) : List.of();
    }

    private List<DataSourceConfig> getArchiveSources() {
        AwarenessSettings s = awarenessService.getSettings();
        return s != null ? s.getEnabledSourcesByCategory(DataSourceConfig.FeedCategory.GNSS_OBSERVATION_ARCHIVE) : List.of();
    }

    private void loadSettingsFromAwareness() {
        AwarenessSettings s = awarenessService.getSettings();
        if (s == null) {
            return;
        }
        EnumMap<GnssStationNetwork, Boolean> map = new EnumMap<>(defaultNetworkMap());
        for (Map.Entry<String, Boolean> e : s.getGnssStationNetworksEnabled().entrySet()) {
            try {
                GnssStationNetwork net = GnssStationNetwork.valueOf(e.getKey());
                map.put(net, Boolean.TRUE.equals(e.getValue()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        this.networksEnabled = Map.copyOf(map);
        this.scanRadiusKm = s.getGnssRegionalScanRadiusKm();
        this.scanMaxStations = s.getGnssRegionalScanMaxStations();
        this.stationMaskDeg = s.getGnssStationMaskDeg();
    }

    private void persistLayer3Settings() {
        AwarenessSettings current = awarenessService.getSettings();
        if (current == null) {
            current = AwarenessSettings.createDefaults();
        }
        Map<String, Boolean> serialized = new java.util.LinkedHashMap<>();
        for (Map.Entry<GnssStationNetwork, Boolean> e : networksEnabled.entrySet()) {
            serialized.put(e.getKey().name(), e.getValue());
        }
        current.setGnssStationNetworksEnabled(serialized);
        current.setGnssRegionalScanRadiusKm(scanRadiusKm);
        current.setGnssRegionalScanMaxStations(scanMaxStations);
        current.setGnssStationMaskDeg(stationMaskDeg);
        try {
            if (settingsStore != null) {
                settingsStore.save(current);
            }
            awarenessService.syncSettingsSilently(current);
        } catch (IOException ignored) {
        }
    }

    private void loadInitialStations() {
        if (Files.isRegularFile(cachePath)) {
            try {
                String json = Files.readString(cachePath, StandardCharsets.UTF_8);
                Type listType = new TypeToken<List<GnssReferenceStation>>() {}.getType();
                List<GnssReferenceStation> cached = gson.fromJson(json, listType);
                if (cached != null && !cached.isEmpty()) {
                    this.stations = List.copyOf(cached);
                    this.lastStatusMessage = "Loaded " + cached.size() + " cached stations";
                    return;
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        this.stations = GnssStationCatalogClient.loadBundledSeed();
        this.lastStatusMessage = "Loaded " + this.stations.size() + " bundled seed stations";
    }

    private void saveAtomicCache(List<GnssReferenceStation> list) {
        try {
            Path abs = cachePath.toAbsolutePath();
            if (abs.getParent() != null) {
                Files.createDirectories(abs.getParent());
            }
            Path tmp = abs.resolveSibling(abs.getFileName() + ".tmp");
            Files.writeString(tmp, gson.toJson(list), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
        }
    }

    private void updateStatus(String msg, boolean warning) {
        this.lastStatusMessage = msg != null ? msg : "";
        this.lastStatusWarning = warning;
        dispatchCallbacks(l -> l.onStationStatusMessage(this.lastStatusMessage, warning));
    }

    private void dispatchCallbacks(Consumer<GnssStationListener> call) {
        Runnable run = () -> {
            for (GnssStationListener l : listeners) {
                try {
                    call.accept(l);
                } catch (RuntimeException ignored) {
                }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            run.run();
        } else {
            SwingUtilities.invokeLater(run);
        }
    }

    private ScheduledExecutorService ensureCatalogSchedulerLocked() {
        if (catalogScheduler == null || catalogScheduler.isShutdown()) {
            catalogScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "gnss-stations-catalog");
                t.setDaemon(true);
                return t;
            });
        }
        return catalogScheduler;
    }

    private ExecutorService ensureWorkerPool() {
        synchronized (lifecycleLock) {
            if (workerPool == null || workerPool.isShutdown()) {
                workerPool = Executors.newFixedThreadPool(2, r -> {
                    Thread t = new Thread(r, "gnss-stations-worker");
                    t.setDaemon(true);
                    return t;
                });
            }
            return workerPool;
        }
    }

    private ExecutorService ensureRegionalFetchPool() {
        synchronized (lifecycleLock) {
            if (regionalFetchPool == null || regionalFetchPool.isShutdown()) {
                regionalFetchPool = Executors.newFixedThreadPool(4, r -> {
                    Thread t = new Thread(r, "gnss-regional-fetch");
                    t.setDaemon(true);
                    return t;
                });
            }
            return regionalFetchPool;
        }
    }

    private static Map<GnssStationNetwork, Boolean> defaultNetworkMap() {
        EnumMap<GnssStationNetwork, Boolean> map = new EnumMap<>(GnssStationNetwork.class);
        map.put(GnssStationNetwork.IGS, Boolean.TRUE);
        map.put(GnssStationNetwork.EUREF, Boolean.TRUE);
        map.put(GnssStationNetwork.NOAA_CORS, Boolean.TRUE);
        return Map.copyOf(map);
    }
}

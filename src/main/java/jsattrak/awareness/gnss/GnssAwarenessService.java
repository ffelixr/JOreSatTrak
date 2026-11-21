package jsattrak.awareness.gnss;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.zip.GZIPOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;
import jsattrak.awareness.AwarenessFeed;
import jsattrak.awareness.config.AwarenessSettings;
import jsattrak.awareness.config.AwarenessSettingsStore;
import jsattrak.awareness.config.DataSourceConfig;
import jsattrak.awareness.gnss.net.BroadcastNavClient;
import jsattrak.awareness.gnss.net.GnssCatalogClient;
import jsattrak.awareness.gnss.net.IgsMetadataParser;
import jsattrak.awareness.gnss.net.OperatorStatusClient;
import jsattrak.awareness.tle.TleFeedClient;
import jsattrak.utilities.TLE;

/**
 * Central coordinator for the GNSS Constellation Status &amp; Satellite Availability feed (Layer 1),
 * providing quiet startup, tiered 15-min/24-hour refresh, local cache retention, manual testing
 * overrides, and a Layer-2-ready satellite query API.
 */
public class GnssAwarenessService implements AwarenessFeed {

    public static final String FEED_ID = "gnss";
    public static final String DISPLAY_NAME = "GNSS";
    public static final long SIM_SYNC_DIVERGENCE_WARN_MILLIS = 24L * 3600L * 1000L;

    private static final int DEFAULT_FAST_INTERVAL_MINUTES = 15;
    private static final long DEFAULT_DAILY_INTERVAL_MILLIS = 24L * 3600L * 1000L;
    private static final String DEFAULT_CACHE_DIR = ".joresattrak";
    private static final String DEFAULT_CACHE_FILE = "gnss-cache.json";
    private static final String DEFAULT_BRDC_CACHE_FILE = "gnss-brdc-latest.rnx.gz";
    private static final Pattern PRN_HINT_PATTERN = Pattern.compile("\\((?:PRN\\s*|)?([GERC]\\d{2}|PRN\\s*\\d{1,2})\\)", Pattern.CASE_INSENSITIVE);

    private final AwarenessSettingsStore settingsStore;
    private final Path cachePath;
    private final GnssManualStatusStore manualStatusStore;
    private final Path bundledTleDir;
    private final GnssCatalogClient catalogClient;
    private final TleFeedClient tleFeedClient;
    private final BroadcastNavClient broadcastNavClient;
    private final OperatorStatusClient operatorStatusClient;
    private final Clock clock;
    private final Gson gson;
    private final CopyOnWriteArrayList<GnssStateListener> listeners = new CopyOnWriteArrayList<>();

    private final Object refreshLock = new Object();
    private final Object lifecycleLock = new Object();

    private volatile AwarenessSettings settings;
    private volatile List<GnssCatalogEntry> catalogEntries = List.of();
    private volatile List<TLE> tleCatalog = List.of();
    private volatile Map<String, TLE> matchedTlesByPrn = Map.of();
    private volatile Map<String, BroadcastHealthRecord> broadcastByPrn = Map.of();
    private volatile long broadcastLatestEpochMillis;
    private volatile long broadcastSyncedMillis;
    private volatile byte[] latestBrdcRawBytes;
    private volatile String latestBrdcSourceName = "";
    private volatile Map<String, OperatorStatusRecord> operatorByPrn = Map.of();
    private volatile List<GnssOutageNotice> outageNotices = List.of();
    private volatile boolean gpsNoticesAvailable = true;
    private volatile boolean galileoNoticesAvailable = true;
    private volatile GnssManualStatusList manualStatusList;

    private volatile boolean globalMapOverlayEnabled = true;
    private volatile String selectedPrnId;
    private volatile boolean dataStale;
    private volatile long lastSuccessfulSyncMillis;
    private volatile long lastCatalogSyncMillis;
    private volatile long lastTleSyncMillis;
    private volatile String lastErrorMessage = "";
    private volatile Map<String, GnssSourceHealth> sourceHealthByKey = new LinkedHashMap<>();

    private boolean activated;
    private boolean activationRequested;
    private boolean hasSyncedInSession;
    private volatile boolean refreshInFlight;
    private volatile boolean shutdown;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> periodicFuture;

    public GnssAwarenessService(
            AwarenessSettingsStore settingsStore,
            Path cachePath,
            GnssManualStatusStore manualStatusStore,
            Path bundledTleDir,
            GnssCatalogClient catalogClient,
            TleFeedClient tleFeedClient,
            BroadcastNavClient broadcastNavClient,
            OperatorStatusClient operatorStatusClient,
            Clock clock) {
        this.settingsStore = Objects.requireNonNull(settingsStore, "settingsStore");
        this.cachePath = cachePath;
        this.manualStatusStore = Objects.requireNonNull(manualStatusStore, "manualStatusStore");
        this.bundledTleDir = bundledTleDir;
        this.catalogClient = Objects.requireNonNull(catalogClient, "catalogClient");
        this.tleFeedClient = Objects.requireNonNull(tleFeedClient, "tleFeedClient");
        this.broadcastNavClient = Objects.requireNonNull(broadcastNavClient, "broadcastNavClient");
        this.operatorStatusClient = Objects.requireNonNull(operatorStatusClient, "operatorStatusClient");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.gson = new GsonBuilder().setPrettyPrinting().create();

        AwarenessSettings loaded = settingsStore.load();
        this.settings = loaded != null ? loaded : AwarenessSettings.createDefaults();
        this.manualStatusList = manualStatusStore.load();
        initDefaultHealthSummary();
        loadCacheQuietly();
        loadBrdcCacheQuietly();
        applyBundledFallbackIfEmpty();
        rebuildTleMatches();
    }

    public static GnssAwarenessService createDefault(AwarenessSettingsStore settingsStore) {
        AwarenessSettingsStore effectiveStore = settingsStore != null
                ? settingsStore
                : AwarenessSettingsStore.createDefault();
        Path defaultCache = Path.of(System.getProperty("user.home"), DEFAULT_CACHE_DIR, DEFAULT_CACHE_FILE);
        return new GnssAwarenessService(
                effectiveStore,
                defaultCache,
                GnssManualStatusStore.createDefault(),
                Path.of("data", "tle"),
                new GnssCatalogClient(),
                new TleFeedClient(),
                new BroadcastNavClient(),
                new OperatorStatusClient(),
                Clock.systemUTC());
    }

    /**
     * Creates an offline test service preloaded with deterministic fixtures.
     */
    public static GnssAwarenessService createForTest(
            Path tmpDir,
            List<GnssCatalogEntry> catalog,
            List<TLE> tles,
            Map<String, BroadcastHealthRecord> health,
            Map<String, OperatorStatusRecord> opStatus,
            List<GnssOutageNotice> notices) {
        Path baseDir;
        try {
            baseDir = tmpDir != null ? tmpDir : Files.createTempDirectory("gnss-test");
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create temp directory for GNSS test service", e);
        }
        AwarenessSettingsStore store = new AwarenessSettingsStore(baseDir.resolve("awareness-settings.json"));
        long now = Clock.systemUTC().millis();
        List<GnssCatalogEntry> cleanCat = catalog != null ? List.copyOf(catalog) : List.of();
        List<TLE> cleanTles = tles != null ? List.copyOf(tles) : List.of();
        Map<String, BroadcastHealthRecord> cleanHealth = health != null ? Map.copyOf(health) : Map.of();
        Map<String, OperatorStatusRecord> cleanOp = opStatus != null ? Map.copyOf(opStatus) : Map.of();
        List<GnssOutageNotice> cleanNotices = notices != null ? List.copyOf(notices) : List.of();

        GnssCatalogClient catClient = new GnssCatalogClient() {
            @Override
            public CatalogFetchResult fetchCatalog(List<DataSourceConfig> sources, long refEpoch) {
                return new CatalogFetchResult(cleanCat, "OfflineTestIGS");
            }
        };
        TleFeedClient tleClient = new TleFeedClient() {
            @Override
            public List<TLE> fetchTleCatalog(DataSourceConfig source) {
                return cleanTles;
            }
        };
        BroadcastNavClient navClient = new BroadcastNavClient() {
            @Override
            public BroadcastFetchResult fetchLatest(List<DataSourceConfig> sources) {
                long latestEp = cleanHealth.values().stream()
                        .mapToLong(BroadcastHealthRecord::epochMillis).max().orElse(now);
                return new BroadcastFetchResult(
                        new jsattrak.awareness.gnss.net.RinexNavHealthParser.ParseResult(
                                cleanHealth, latestEp, cleanHealth.size(), "3.04"),
                        "OfflineTestBKG", "mock://brdc");
            }
        };
        OperatorStatusClient opClient = new OperatorStatusClient() {
            @Override
            public OperatorFetchResult fetchAll(Map<String, List<DataSourceConfig>> scopedSources, long nowMillis) {
                return new OperatorFetchResult(cleanOp, cleanNotices, true, true, Map.of(), Map.of());
            }
        };

        GnssAwarenessService svc = new GnssAwarenessService(
                store,
                baseDir.resolve(DEFAULT_CACHE_FILE),
                new GnssManualStatusStore(baseDir.resolve("gnss-manual-status.json")),
                null,
                catClient,
                tleClient,
                navClient,
                opClient,
                Clock.systemUTC());
        svc.preloadForTest(cleanCat, cleanTles, cleanHealth, cleanOp, cleanNotices, now);
        return svc;
    }

    private void preloadForTest(
            List<GnssCatalogEntry> catalog,
            List<TLE> tles,
            Map<String, BroadcastHealthRecord> health,
            Map<String, OperatorStatusRecord> opStatus,
            List<GnssOutageNotice> notices,
            long nowMillis) {
        this.catalogEntries = catalog;
        this.tleCatalog = tles;
        this.broadcastByPrn = health;
        this.broadcastLatestEpochMillis = health.values().stream()
                .mapToLong(BroadcastHealthRecord::epochMillis).max().orElse(health.isEmpty() ? 0L : nowMillis);
        this.broadcastSyncedMillis = health.isEmpty() ? 0L : nowMillis;
        this.operatorByPrn = opStatus;
        this.outageNotices = notices;
        this.lastSuccessfulSyncMillis = nowMillis;
        this.lastCatalogSyncMillis = nowMillis;
        this.lastTleSyncMillis = nowMillis;
        this.dataStale = false;
        rebuildTleMatches();
        synchronized (lifecycleLock) {
            this.hasSyncedInSession = true;
        }
    }

    public void addListener(GnssStateListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(GnssStateListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public void initializeAtStartup() {
        AwarenessSettings current = this.settings;
        if (current == null || !current.isAutoFetchOnStartup() || !current.isGnssFeedEnabled()) {
            return;
        }
        ensureActivated();
    }

    public void ensureActivated() {
        synchronized (lifecycleLock) {
            if (shutdown) {
                return;
            }
            activationRequested = true;
        }
        AwarenessSettings current = this.settings;
        if (current == null || !current.isGnssFeedEnabled()) {
            return;
        }
        boolean shouldFetchNow = false;
        synchronized (lifecycleLock) {
            if (shutdown) {
                return;
            }
            activated = true;
            if (periodicFuture == null || periodicFuture.isCancelled()) {
                schedulePeriodicRefreshLocked();
            }
            if (!hasSyncedInSession && !refreshInFlight) {
                shouldFetchNow = true;
            }
        }
        if (shouldFetchNow) {
            refreshNowAsync();
        }
    }

    public void refreshNowAsync() {
        triggerAsyncRefresh(true);
    }

    private void triggerScheduledRefreshAsync() {
        triggerAsyncRefresh(false);
    }

    private void triggerAsyncRefresh(boolean forceDaily) {
        AwarenessSettings current = this.settings;
        if (current == null || !current.isGnssFeedEnabled()) {
            return;
        }
        ScheduledExecutorService exec;
        synchronized (lifecycleLock) {
            if (shutdown || refreshInFlight) {
                return;
            }
            refreshInFlight = true;
            exec = ensureExecutorLocked();
        }
        try {
            exec.execute(() -> executeRefreshTask(forceDaily));
        } catch (RejectedExecutionException ignored) {
            synchronized (lifecycleLock) {
                refreshInFlight = false;
            }
        }
    }

    public void refreshBlockingForTest() {
        performRefresh(true, true);
    }

    public void refreshScheduledTickBlockingForTest() {
        performRefresh(true, false);
    }

    public void shutdown() {
        ScheduledExecutorService execToStop;
        synchronized (lifecycleLock) {
            shutdown = true;
            if (periodicFuture != null) {
                periodicFuture.cancel(false);
                periodicFuture = null;
            }
            execToStop = executor;
            executor = null;
        }
        if (execToStop != null) {
            execToStop.shutdownNow();
        }
    }

    public AwarenessSettings getSettings() {
        return settings;
    }

    public void updateSettings(AwarenessSettings updated) throws IOException {
        Objects.requireNonNull(updated, "updated");
        settingsStore.save(updated);
        applySettingsSnapshotWithoutSaving(updated);
    }

    public void applySettingsSnapshotWithoutSaving(AwarenessSettings updated) {
        Objects.requireNonNull(updated, "updated");
        this.settings = updated;
        boolean triggerImmediateFetch = false;
        synchronized (lifecycleLock) {
            if (periodicFuture != null) {
                periodicFuture.cancel(false);
                periodicFuture = null;
            }
            if ((activated || activationRequested) && !shutdown && updated.isGnssFeedEnabled()) {
                activated = true;
                schedulePeriodicRefreshLocked();
                if (!hasSyncedInSession && !refreshInFlight) {
                    triggerImmediateFetch = true;
                }
            }
        }
        if (triggerImmediateFetch) {
            refreshNowAsync();
        }
        dispatchCallbacks(false, this::notifyDataChanged);
    }

    // ---- Layer 1 & Layer 2 Query API ----

    public List<GnssSatelliteState> getAllSatelliteStates(long simEpochMillis) {
        List<GnssCatalogEntry> entries = this.catalogEntries;
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        Map<String, TLE> tleMap = this.matchedTlesByPrn;
        Map<String, BroadcastHealthRecord> navMap = this.broadcastByPrn;
        long navLatestEpoch = this.broadcastLatestEpochMillis;
        long navSynced = this.broadcastSyncedMillis;
        Map<String, OperatorStatusRecord> opMap = this.operatorByPrn;
        List<GnssOutageNotice> notices = this.outageNotices;
        GnssManualStatusList manual = this.manualStatusList;
        boolean gpsNoticesOk = this.gpsNoticesAvailable;
        boolean galNoticesOk = this.galileoNoticesAvailable;

        Map<String, List<GnssOutageNotice>> noticesByPrn = indexNoticesByPrn(notices);
        List<GnssSatelliteState> result = new ArrayList<>(entries.size());

        for (GnssCatalogEntry entry : entries) {
            if (entry == null || entry.constellation() == null || !entry.constellation().isLayer1Active()) {
                continue;
            }
            String prn = entry.prnId();
            BroadcastHealthRecord broadcast = navMap.get(prn);
            OperatorStatusRecord opStatus = opMap.get(prn);
            List<GnssOutageNotice> satNotices = noticesByPrn.getOrDefault(prn, List.of());
            GnssManualOverride manualOverride = manual != null ? manual.findByPrn(prn).orElse(null) : null;

            boolean noticesAvailForConstellation = switch (entry.constellation()) {
                case GPS -> gpsNoticesOk;
                case GALILEO -> galNoticesOk;
                default -> true; // GLONASS & BeiDou don't expect notice feeds in Layer 1
            };

            GnssStatusResult statusResult = GnssStatusEvaluator.evaluate(
                    entry,
                    simEpochMillis,
                    broadcast,
                    navLatestEpoch,
                    navSynced,
                    opStatus,
                    satNotices,
                    manualOverride,
                    noticesAvailForConstellation);

            TLE tle = tleMap.get(prn);
            GnssOrbitPropagator.GeodeticPosition pos = tle != null
                    ? GnssOrbitPropagator.propagateGeodetic(tle, simEpochMillis).orElse(null)
                    : null;

            result.add(new GnssSatelliteState(entry, statusResult, tle, pos));
        }
        return List.copyOf(result);
    }

    public List<GnssSatelliteState> getVisibleSatelliteStates(long simEpochMillis) {
        List<GnssSatelliteState> all = getAllSatelliteStates(simEpochMillis);
        List<GnssSatelliteState> visible = new ArrayList<>();
        for (GnssSatelliteState state : all) {
            if (isConstellationEnabled(state.catalog().constellation())
                    && isStatusFilterEnabled(state.statusResult().status())) {
                visible.add(state);
            }
        }
        visible.sort(Comparator.comparingInt((GnssSatelliteState s) -> s.statusResult().status().getSortRank())
                .thenComparing(s -> s.catalog().prnId()));
        return List.copyOf(visible);
    }

    /**
     * Layer-2 entry point: returns only {@code OPERATIONAL} satellites with valid propagated
     * positions at {@code simEpochMillis}.
     */
    public List<GnssSatelliteState> getOperationalSatellites(long simEpochMillis) {
        List<GnssSatelliteState> all = getAllSatelliteStates(simEpochMillis);
        List<GnssSatelliteState> operational = new ArrayList<>();
        for (GnssSatelliteState state : all) {
            if (state.isOperational() && state.position() != null) {
                operational.add(state);
            }
        }
        return List.copyOf(operational);
    }

    public Optional<GnssSatelliteState> getSatelliteState(String prnId, long simEpochMillis) {
        if (prnId == null || prnId.isBlank()) {
            return Optional.empty();
        }
        String normalized = prnId.trim().toUpperCase(Locale.ROOT);
        for (GnssSatelliteState state : getAllSatelliteStates(simEpochMillis)) {
            if (normalized.equals(state.catalog().prnId())) {
                return Optional.of(state);
            }
        }
        return Optional.empty();
    }

    /**
     * Layer-2 entry point: returns lightweight satellite status + matched TLE metadata at {@code simEpochMillis}
     * without running SGP4 propagation on every entry.
     */
    public GnssStatusResult evaluateStatusForEntry(GnssCatalogEntry entry, long simEpochMillis) {
        if (entry == null) {
            return null;
        }
        String prn = entry.prnId();
        BroadcastHealthRecord broadcast = this.broadcastByPrn.get(prn);
        OperatorStatusRecord opStatus = this.operatorByPrn.get(prn);
        List<GnssOutageNotice> satNotices = indexNoticesByPrn(this.outageNotices).getOrDefault(prn, List.of());
        GnssManualStatusList manual = this.manualStatusList;
        GnssManualOverride manualOverride = manual != null ? manual.findByPrn(prn).orElse(null) : null;
        boolean noticesAvail = switch (entry.constellation()) {
            case GPS -> this.gpsNoticesAvailable;
            case GALILEO -> this.galileoNoticesAvailable;
            default -> true;
        };
        return GnssStatusEvaluator.evaluate(
                entry,
                simEpochMillis,
                broadcast,
                this.broadcastLatestEpochMillis,
                this.broadcastSyncedMillis,
                opStatus,
                satNotices,
                manualOverride,
                noticesAvail);
    }

    public List<GnssCatalogEntry> getCatalogEntries() {
        return catalogEntries;
    }

    public Map<String, TLE> getMatchedTlesByPrn() {
        return matchedTlesByPrn;
    }

    public byte[] getLatestBrdcRawBytes() {
        return latestBrdcRawBytes;
    }

    public String getLatestBrdcSourceName() {
        return latestBrdcSourceName;
    }

    /**
     * Returns {@code true} if any active/planned outage notice starts or expires between
     * {@code prevEpochMillis} and {@code newEpochMillis}.
     */
    public boolean hasCrossedNoticeBoundary(long prevEpochMillis, long newEpochMillis) {
        if (prevEpochMillis == newEpochMillis) {
            return false;
        }
        long lo = Math.min(prevEpochMillis, newEpochMillis);
        long hi = Math.max(prevEpochMillis, newEpochMillis);
        long next = GnssStatusEvaluator.nextNoticeBoundaryAfter(this.outageNotices, lo);
        return next <= hi;
    }

    // ---- UI Filters, Selection & Manual List ----

    public boolean isConstellationEnabled(GnssConstellation c) {
        if (c == null || !c.isLayer1Active()) {
            return false;
        }
        Boolean val = settings.getGnssConstellationEnabled().get(c.name());
        return val == null || val.booleanValue();
    }

    public void setConstellationEnabled(GnssConstellation c, boolean enabled) {
        if (c == null || !c.isLayer1Active()) {
            return;
        }
        settings.getGnssConstellationEnabled().put(c.name(), enabled);
        saveSettingsQuietly();
        dispatchCallbacks(false, this::notifyDataChanged);
    }

    public boolean isStatusFilterEnabled(GnssDisplayStatus s) {
        if (s == null) {
            return false;
        }
        Boolean val = settings.getGnssStatusFilter().get(s.name());
        return val == null || val.booleanValue();
    }

    public void setStatusFilterEnabled(GnssDisplayStatus s, boolean enabled) {
        if (s == null) {
            return;
        }
        settings.getGnssStatusFilter().put(s.name(), enabled);
        saveSettingsQuietly();
        dispatchCallbacks(false, this::notifyDataChanged);
    }

    public boolean isShowOrbitRings3d() {
        return settings.isGnssShowOrbitRings3d();
    }

    public void setShowOrbitRings3d(boolean show) {
        if (settings.isGnssShowOrbitRings3d() != show) {
            settings.setGnssShowOrbitRings3d(show);
            saveSettingsQuietly();
            dispatchCallbacks(false, this::notifyDataChanged);
        }
    }

    public String getSelectedPrnId() {
        return selectedPrnId;
    }

    public void selectSatellite(String prnId) {
        String normalized = (prnId == null || prnId.isBlank()) ? null : prnId.trim().toUpperCase(Locale.ROOT);
        if (!Objects.equals(this.selectedPrnId, normalized)) {
            this.selectedPrnId = normalized;
            dispatchCallbacks(false, () -> notifySelectionChanged(normalized));
        }
    }

    public void selectSatelliteAndRequestOpenUi(String prnId) {
        selectSatellite(prnId);
        dispatchCallbacks(false, this::notifyOpenUiRequested);
    }

    public GnssManualStatusList getManualStatusList() {
        return manualStatusList;
    }

    public void saveManualStatusList(GnssManualStatusList list) throws IOException {
        Objects.requireNonNull(list, "list");
        manualStatusStore.save(list);
        this.manualStatusList = manualStatusStore.load();
        dispatchCallbacks(false, this::notifyDataChanged);
    }

    public boolean isManualListStale() {
        GnssManualStatusList list = this.manualStatusList;
        return list != null && list.isStale(clock.millis(), GnssStatusEvaluator.DEFAULT_MANUAL_STALE_DAYS);
    }

    public List<GnssSourceHealth> getSourceHealthSummary() {
        return List.copyOf(sourceHealthByKey.values());
    }

    public boolean isDataStale() {
        return dataStale;
    }

    public long getLastSuccessfulSyncMillis() {
        return lastSuccessfulSyncMillis;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public boolean isRefreshing() {
        return refreshInFlight;
    }

    public boolean isFeedEnabled() {
        return settings != null && settings.isGnssFeedEnabled();
    }

    public boolean isSimClockFarFromSync(long simEpochMillis) {
        long sync = this.lastSuccessfulSyncMillis;
        return sync > 0L && Math.abs(simEpochMillis - sync) > SIM_SYNC_DIVERGENCE_WARN_MILLIS;
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
        return getVisibleSatelliteStates(clock.millis()).size();
    }

    @Override
    public boolean isMapOverlayEnabled() {
        return globalMapOverlayEnabled;
    }

    @Override
    public void setMapOverlayEnabled(boolean enabled) {
        if (this.globalMapOverlayEnabled != enabled) {
            this.globalMapOverlayEnabled = enabled;
            dispatchCallbacks(false, this::notifyDataChanged);
        }
    }

    // ---- Refresh Pipeline ----

    private void executeRefreshTask(boolean forceDaily) {
        try {
            performRefresh(false, forceDaily);
        } catch (Throwable t) {
            dispatchCallbacks(false, () -> notifyStatusMessage(
                    "GNSS refresh failed: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()),
                    true));
        } finally {
            synchronized (lifecycleLock) {
                refreshInFlight = false;
            }
            dispatchCallbacks(false, this::notifyDataChanged);
        }
    }

    private void performRefresh(boolean synchronousCallbacks, boolean forceDaily) {
        synchronized (refreshLock) {
            if (shutdown) {
                return;
            }
            AwarenessSettings current = this.settings;
            if (current == null || !current.isGnssFeedEnabled()) {
                return;
            }
            long nowMillis = clock.millis();
            Map<String, GnssSourceHealth> updatedHealth = new LinkedHashMap<>(this.sourceHealthByKey);
            boolean anySucceeded = false;
            List<String> failures = new ArrayList<>();

            // 1. Daily tier: Satellite Catalog (IGS SINEX)
            List<DataSourceConfig> catSources = current.getEnabledSourcesByCategory(
                    DataSourceConfig.FeedCategory.GNSS_CATALOG);
            long catIntervalMillis = resolveCategoryIntervalMillis(catSources, DEFAULT_DAILY_INTERVAL_MILLIS);
            boolean catalogDue = forceDaily
                    || this.lastCatalogSyncMillis <= 0L
                    || (nowMillis - this.lastCatalogSyncMillis) >= catIntervalMillis;
            if (!catSources.isEmpty() && catalogDue) {
                try {
                    GnssCatalogClient.CatalogFetchResult res = catalogClient.fetchCatalog(catSources, nowMillis);
                    if (res != null && res.entries() != null && !res.entries().isEmpty()) {
                        this.catalogEntries = List.copyOf(res.entries());
                        this.lastCatalogSyncMillis = nowMillis;
                        anySucceeded = true;
                        updatedHealth.put("CATALOG", new GnssSourceHealth(
                                "CATALOG", "IGS SINEX", true, false, nowMillis,
                                res.entries().size() + " satellites"));
                    }
                } catch (IOException | RuntimeException e) {
                    String msg = nonBlankOr(e.getMessage(), "Catalog fetch failed");
                    failures.add("IGS SINEX: " + msg);
                    updatedHealth.put("CATALOG", new GnssSourceHealth(
                            "CATALOG", "IGS SINEX", false, true, this.lastCatalogSyncMillis, msg));
                }
            }

            // 2. Daily tier: GNSS TLE Orbits
            List<DataSourceConfig> tleSources = current.getEnabledSourcesByCategory(
                    DataSourceConfig.FeedCategory.GNSS_ORBITS_TLE);
            long tleIntervalMillis = resolveCategoryIntervalMillis(tleSources, DEFAULT_DAILY_INTERVAL_MILLIS);
            boolean tleDue = forceDaily
                    || this.lastTleSyncMillis <= 0L
                    || (nowMillis - this.lastTleSyncMillis) >= tleIntervalMillis;
            if (!tleSources.isEmpty() && tleDue) {
                try {
                    List<TLE> fetchedTles = fetchTlesWithFailover(tleSources);
                    if (!fetchedTles.isEmpty()) {
                        this.tleCatalog = List.copyOf(fetchedTles);
                        this.lastTleSyncMillis = nowMillis;
                        anySucceeded = true;
                        updatedHealth.put("TLE", new GnssSourceHealth(
                                "TLE", "GNSS TLEs", true, false, nowMillis,
                                fetchedTles.size() + " TLEs"));
                    }
                } catch (IOException | RuntimeException e) {
                    String msg = nonBlankOr(e.getMessage(), "TLE fetch failed");
                    failures.add("GNSS TLEs: " + msg);
                    updatedHealth.put("TLE", new GnssSourceHealth(
                            "TLE", "GNSS TLEs", false, true, this.lastTleSyncMillis, msg));
                }
            }

            if (this.catalogEntries.isEmpty() && !this.tleCatalog.isEmpty()) {
                this.catalogEntries = synthesizeFallbackCatalog(this.tleCatalog);
            }
            rebuildTleMatches();

            // 3. Fast tier (15 min): Broadcast Navigation Health (BKG BRDC)
            List<DataSourceConfig> navSources = current.getEnabledSourcesByCategory(
                    DataSourceConfig.FeedCategory.GNSS_BROADCAST_NAV);
            if (!navSources.isEmpty()) {
                try {
                    BroadcastNavClient.BroadcastFetchResult navRes = broadcastNavClient.fetchLatest(navSources);
                    if (navRes != null && navRes.parsed() != null) {
                        this.broadcastByPrn = navRes.parsed().latestByPrn();
                        this.broadcastLatestEpochMillis = navRes.parsed().latestEpochMillis();
                        this.broadcastSyncedMillis = nowMillis;
                        if (navRes.rawBytes() != null && navRes.rawBytes().length > 0) {
                            this.latestBrdcRawBytes = navRes.rawBytes();
                            this.latestBrdcSourceName = nonNull(navRes.sourceName());
                            saveBrdcCacheQuietly(navRes.rawBytes());
                        }
                        anySucceeded = true;
                        updatedHealth.put("BROADCAST_NAV", new GnssSourceHealth(
                                "BROADCAST_NAV", "BKG BRDC", true, false, nowMillis,
                                navRes.parsed().latestByPrn().size() + " SVs"));
                    }
                } catch (IOException | RuntimeException e) {
                    String msg = nonBlankOr(e.getMessage(), "Broadcast nav failed");
                    failures.add("BKG BRDC: " + msg);
                    updatedHealth.put("BROADCAST_NAV", new GnssSourceHealth(
                            "BROADCAST_NAV", "BKG BRDC", false, true, this.broadcastSyncedMillis, msg));
                }
            }

            // 4. Fast tier (15 min): Operator Status & Notices (NAVCEN & GSC)
            Map<String, List<DataSourceConfig>> scopedOp = current.getEnabledSourcesGroupedByScope(
                    DataSourceConfig.FeedCategory.GNSS_OPERATOR_STATUS);
            if (!scopedOp.isEmpty()) {
                OperatorStatusClient.OperatorFetchResult opRes = operatorStatusClient.fetchAll(scopedOp, nowMillis);
                if (!opRes.statusByPrn().isEmpty()) {
                    Map<String, OperatorStatusRecord> merged = new LinkedHashMap<>(this.operatorByPrn);
                    merged.putAll(opRes.statusByPrn());
                    this.operatorByPrn = Map.copyOf(merged);
                    anySucceeded = true;
                }
                boolean gpsNoticeScopeSucceeded = scopedOp.containsKey(OperatorStatusClient.SCOPE_GPS_NANU)
                        && opRes.gpsNoticesAvailable();
                boolean galNoticeScopeSucceeded = scopedOp.containsKey(OperatorStatusClient.SCOPE_GALILEO_NAGU)
                        && opRes.galileoNoticesAvailable();
                if (gpsNoticeScopeSucceeded || galNoticeScopeSucceeded) {
                    this.outageNotices = mergeOutageNoticesByConstellation(
                            this.outageNotices,
                            opRes.notices(),
                            gpsNoticeScopeSucceeded,
                            galNoticeScopeSucceeded);
                    anySucceeded = true;
                }
                if (scopedOp.containsKey(OperatorStatusClient.SCOPE_GPS_NANU)) {
                    this.gpsNoticesAvailable = opRes.gpsNoticesAvailable();
                }
                if (scopedOp.containsKey(OperatorStatusClient.SCOPE_GALILEO_NAGU)) {
                    this.galileoNoticesAvailable = opRes.galileoNoticesAvailable();
                }
                updateOperatorScopeHealth(updatedHealth, scopedOp, opRes, nowMillis, failures);
            }

            this.sourceHealthByKey = updatedHealth;
            synchronized (lifecycleLock) {
                this.hasSyncedInSession = true;
            }
            if (anySucceeded) {
                this.lastSuccessfulSyncMillis = nowMillis;
                saveCacheQuietly();
            }
            this.dataStale = !failures.isEmpty();
            this.lastErrorMessage = String.join("; ", failures);

            String statusMsg = failures.isEmpty()
                    ? "GNSS synced (" + this.catalogEntries.size() + " satellites)"
                    : "GNSS partial sync warning: " + this.lastErrorMessage;
            boolean warning = !failures.isEmpty();
            dispatchCallbacks(synchronousCallbacks, () -> {
                notifyDataChanged();
                notifyStatusMessage(statusMsg, warning);
            });
        }
    }

    private void updateOperatorScopeHealth(
            Map<String, GnssSourceHealth> updatedHealth,
            Map<String, List<DataSourceConfig>> scopedOp,
            OperatorStatusClient.OperatorFetchResult opRes,
            long nowMillis,
            List<String> failures) {
        updateSingleScopeHealth(updatedHealth, scopedOp, opRes, OperatorStatusClient.SCOPE_GPS_STATUS, "NAVCEN Status", nowMillis, failures);
        updateSingleScopeHealth(updatedHealth, scopedOp, opRes, OperatorStatusClient.SCOPE_GPS_NANU, "NAVCEN NANU", nowMillis, failures);
        updateSingleScopeHealth(updatedHealth, scopedOp, opRes, OperatorStatusClient.SCOPE_GALILEO_STATUS, "GSC Status", nowMillis, failures);
        updateSingleScopeHealth(updatedHealth, scopedOp, opRes, OperatorStatusClient.SCOPE_GALILEO_NAGU, "GSC NAGU", nowMillis, failures);
    }

    private void updateSingleScopeHealth(
            Map<String, GnssSourceHealth> updatedHealth,
            Map<String, List<DataSourceConfig>> scopedOp,
            OperatorStatusClient.OperatorFetchResult opRes,
            String scopeKey,
            String label,
            long nowMillis,
            List<String> failures) {
        if (!scopedOp.containsKey(scopeKey)) {
            return;
        }
        String err = opRes.scopeErrors().get(scopeKey);
        GnssSourceHealth prev = updatedHealth.get(scopeKey);
        long prevSync = prev != null ? prev.lastSyncMillis() : 0L;
        if (err == null) {
            updatedHealth.put(scopeKey, new GnssSourceHealth(scopeKey, label, true, false, nowMillis, "OK"));
        } else {
            failures.add(label + ": " + err);
            updatedHealth.put(scopeKey, new GnssSourceHealth(scopeKey, label, false, true, prevSync, err));
        }
    }

    private List<TLE> fetchTlesWithFailover(List<DataSourceConfig> tleSources) throws IOException {
        IOException lastError = null;
        for (DataSourceConfig source : tleSources) {
            if (source == null || !source.isEnabled()) {
                continue;
            }
            try {
                List<TLE> list = tleFeedClient.fetchTleCatalog(source);
                if (list != null && !list.isEmpty()) {
                    return list;
                }
            } catch (IOException | RuntimeException e) {
                lastError = (e instanceof IOException io) ? io : new IOException(e.getMessage(), e);
            }
        }
        throw lastError != null ? lastError : new IOException("All GNSS TLE sources failed");
    }

    private void rebuildTleMatches() {
        this.matchedTlesByPrn = IgsMetadataParser.matchTlesToCatalog(this.catalogEntries, this.tleCatalog);
    }

    private void applyBundledFallbackIfEmpty() {
        if (this.tleCatalog.isEmpty() && this.bundledTleDir != null) {
            List<TLE> bundled = GnssOrbitPropagator.loadBundledFallbackTles(this.bundledTleDir);
            if (!bundled.isEmpty()) {
                this.tleCatalog = bundled;
            }
        }
        if (this.catalogEntries.isEmpty() && !this.tleCatalog.isEmpty()) {
            this.catalogEntries = synthesizeFallbackCatalog(this.tleCatalog);
        }
    }

    private static List<GnssOutageNotice> mergeOutageNoticesByConstellation(
            List<GnssOutageNotice> existing,
            List<GnssOutageNotice> fetched,
            boolean replaceGps,
            boolean replaceGalileo) {
        List<GnssOutageNotice> merged = new ArrayList<>();
        if (existing != null) {
            for (GnssOutageNotice n : existing) {
                if (n == null || n.constellation() == null) {
                    continue;
                }
                if (n.constellation() == GnssConstellation.GPS && replaceGps) {
                    continue;
                }
                if (n.constellation() == GnssConstellation.GALILEO && replaceGalileo) {
                    continue;
                }
                merged.add(n);
            }
        }
        if (fetched != null) {
            for (GnssOutageNotice n : fetched) {
                if (n == null || n.constellation() == null) {
                    continue;
                }
                if (n.constellation() == GnssConstellation.GPS && replaceGps) {
                    merged.add(n);
                } else if (n.constellation() == GnssConstellation.GALILEO && replaceGalileo) {
                    merged.add(n);
                } else if (n.constellation() != GnssConstellation.GPS
                        && n.constellation() != GnssConstellation.GALILEO) {
                    merged.add(n);
                }
            }
        }
        return List.copyOf(merged);
    }

    private static List<GnssCatalogEntry> synthesizeFallbackCatalog(List<TLE> tles) {
        Map<String, GnssCatalogEntry> byPrn = new LinkedHashMap<>();
        int nextGalileoFallback = 1;
        int nextGlonassFallback = 1;
        Pattern galileoNumPattern = Pattern.compile("\\bGALILEO[-\\s]+(\\d{1,2})\\b");
        Pattern cosmosNumPattern = Pattern.compile("\\((\\d{3}[A-Z]?)\\)");

        for (TLE tle : tles) {
            if (tle == null || tle.getSatName() == null) {
                continue;
            }
            String name = tle.getSatName().trim().toUpperCase(Locale.ROOT);
            Matcher m = PRN_HINT_PATTERN.matcher(name);
            String prnId = null;
            String svn = "";
            if (m.find()) {
                String tok = m.group(1).replaceAll("\\s+", "");
                if (tok.startsWith("PRN")) {
                    int n = Integer.parseInt(tok.substring(3));
                    GnssConstellation c = name.contains("GALILEO") || name.contains("GSAT")
                            ? GnssConstellation.GALILEO : GnssConstellation.GPS;
                    prnId = c.getPrefix() + String.format("%02d", n);
                } else {
                    prnId = tok;
                }
            } else if (name.contains("GALILEO") || name.contains("GSAT")) {
                Matcher gm = galileoNumPattern.matcher(name);
                if (gm.find()) {
                    int n = Integer.parseInt(gm.group(1));
                    if (n >= 1 && n <= 36 && !byPrn.containsKey("E" + String.format("%02d", n))) {
                        prnId = "E" + String.format("%02d", n);
                    }
                }
                while (prnId == null && nextGalileoFallback <= 36) {
                    String candidate = "E" + String.format("%02d", nextGalileoFallback++);
                    if (!byPrn.containsKey(candidate)) {
                        prnId = candidate;
                    }
                }
                if (name.startsWith("GSAT")) {
                    svn = name.split("\\s+")[0];
                }
            } else if (name.contains("COSMOS") || name.contains("GLONASS")) {
                Matcher cm = cosmosNumPattern.matcher(name);
                if (cm.find()) {
                    svn = "R" + cm.group(1);
                }
                while (nextGlonassFallback <= 26) {
                    String candidate = "R" + String.format("%02d", nextGlonassFallback++);
                    if (!byPrn.containsKey(candidate)) {
                        prnId = candidate;
                        break;
                    }
                }
            }
            if (prnId != null && prnId.length() >= 2) {
                GnssConstellation c = GnssConstellation.fromPrefix(prnId.charAt(0));
                if (c != null && c.isLayer1Active()) {
                    int num = Integer.parseInt(prnId.substring(1));
                    int norad = jsattrak.awareness.tle.PayloadTleMatcher.extractNoradId(tle);
                    byPrn.putIfAbsent(prnId, new GnssCatalogEntry(
                            prnId, c, num, svn, norad, "", name,
                            GnssOrbitClass.MEO, "", "", ""));
                }
            }
        }
        return List.copyOf(byPrn.values());
    }

    private static Map<String, List<GnssOutageNotice>> indexNoticesByPrn(List<GnssOutageNotice> notices) {
        Map<String, List<GnssOutageNotice>> map = new HashMap<>();
        if (notices == null) {
            return map;
        }
        for (GnssOutageNotice n : notices) {
            if (n != null && n.prnId() != null && !n.prnId().isBlank()) {
                map.computeIfAbsent(n.prnId().trim().toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(n);
            }
        }
        return map;
    }

    private static long resolveCategoryIntervalMillis(List<DataSourceConfig> sources, long defaultMillis) {
        for (DataSourceConfig s : sources) {
            if (s != null && s.isEnabled() && s.getRefreshIntervalMinutes() > 0) {
                return s.getRefreshIntervalMinutes() * 60_000L;
            }
        }
        return defaultMillis;
    }

    private void initDefaultHealthSummary() {
        Map<String, GnssSourceHealth> map = new LinkedHashMap<>();
        map.put("CATALOG", new GnssSourceHealth("CATALOG", "IGS SINEX", true, false, 0L, "Pending"));
        map.put("TLE", new GnssSourceHealth("TLE", "GNSS TLEs", true, false, 0L, "Pending"));
        map.put("BROADCAST_NAV", new GnssSourceHealth("BROADCAST_NAV", "BKG BRDC", true, false, 0L, "Pending"));
        map.put("GPS_STATUS", new GnssSourceHealth("GPS_STATUS", "NAVCEN Status", true, false, 0L, "Pending"));
        map.put("GPS_NANU", new GnssSourceHealth("GPS_NANU", "NAVCEN NANU", true, false, 0L, "Pending"));
        map.put("GALILEO_STATUS", new GnssSourceHealth("GALILEO_STATUS", "GSC Status", true, false, 0L, "Pending"));
        map.put("GALILEO_NAGU", new GnssSourceHealth("GALILEO_NAGU", "GSC NAGU", true, false, 0L, "Pending"));
        this.sourceHealthByKey = map;
    }

    private void schedulePeriodicRefreshLocked() {
        int intervalMinutes = DEFAULT_FAST_INTERVAL_MINUTES;
        AwarenessSettings current = this.settings;
        if (current != null) {
            List<DataSourceConfig> fastSources = current.getEnabledSourcesByCategory(
                    DataSourceConfig.FeedCategory.GNSS_BROADCAST_NAV);
            if (!fastSources.isEmpty()) {
                intervalMinutes = fastSources.get(0).getRefreshIntervalMinutes();
            }
        }
        if (intervalMinutes <= 0) {
            return;
        }
        ScheduledExecutorService exec = ensureExecutorLocked();
        long periodMillis = Math.max(1L, intervalMinutes) * 60_000L;
        periodicFuture = exec.scheduleAtFixedRate(
                this::triggerScheduledRefreshAsync,
                periodMillis,
                periodMillis,
                TimeUnit.MILLISECONDS);
    }

    private ScheduledExecutorService ensureExecutorLocked() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "gnss-awareness-refresh");
                t.setDaemon(true);
                return t;
            });
        }
        return executor;
    }

    private void saveSettingsQuietly() {
        try {
            settingsStore.save(this.settings);
        } catch (IOException ignored) {
        }
    }

    private void dispatchCallbacks(boolean synchronous, Runnable action) {
        if (shutdown || action == null) {
            return;
        }
        if (synchronous || SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(() -> {
                if (!shutdown) {
                    action.run();
                }
            });
        }
    }

    private void notifyDataChanged() {
        for (GnssStateListener l : listeners) {
            l.onGnssDataChanged();
        }
    }

    private void notifySelectionChanged(String prnId) {
        for (GnssStateListener l : listeners) {
            l.onGnssSelectionChanged(prnId);
        }
    }

    private void notifyStatusMessage(String msg, boolean warning) {
        for (GnssStateListener l : listeners) {
            l.onGnssStatusMessage(msg, warning);
        }
    }

    private void notifyOpenUiRequested() {
        for (GnssStateListener l : listeners) {
            l.onOpenGnssUiRequested();
        }
    }

    private void loadCacheQuietly() {
        if (cachePath == null || !Files.exists(cachePath)) {
            return;
        }
        try {
            String raw = Files.readString(cachePath, StandardCharsets.UTF_8);
            if (raw == null || raw.isBlank()) {
                return;
            }
            GnssCachePayload payload = gson.fromJson(raw, GnssCachePayload.class);
            if (payload == null) {
                return;
            }
            this.lastSuccessfulSyncMillis = payload.lastSuccessfulSyncMillis;
            this.lastCatalogSyncMillis = payload.lastCatalogSyncMillis;
            this.lastTleSyncMillis = payload.lastTleSyncMillis;
            this.broadcastLatestEpochMillis = payload.broadcastLatestEpochMillis;
            this.broadcastSyncedMillis = payload.broadcastSyncedMillis;
            this.gpsNoticesAvailable = payload.gpsNoticesAvailable;
            this.galileoNoticesAvailable = payload.galileoNoticesAvailable;
            if (payload.catalogEntries != null) {
                this.catalogEntries = List.copyOf(payload.catalogEntries);
            }
            if (payload.tleCatalog != null) {
                List<TLE> tles = new ArrayList<>();
                for (CachedTle ct : payload.tleCatalog) {
                    if (ct != null && ct.line1 != null && ct.line2 != null) {
                        tles.add(new TLE(nonNull(ct.line0), ct.line1, ct.line2));
                    }
                }
                this.tleCatalog = List.copyOf(tles);
            }
            if (payload.broadcastByPrn != null) {
                this.broadcastByPrn = Map.copyOf(payload.broadcastByPrn);
            }
            if (payload.operatorByPrn != null) {
                this.operatorByPrn = Map.copyOf(payload.operatorByPrn);
            }
            if (payload.outageNotices != null) {
                this.outageNotices = List.copyOf(payload.outageNotices);
            }
        } catch (IOException | RuntimeException ignored) {
            // Corrupt cache must never fail startup
        }
    }

    private void saveCacheQuietly() {
        if (cachePath == null) {
            return;
        }
        try {
            Path target = cachePath.toAbsolutePath();
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            GnssCachePayload payload = new GnssCachePayload();
            payload.lastSuccessfulSyncMillis = this.lastSuccessfulSyncMillis;
            payload.lastCatalogSyncMillis = this.lastCatalogSyncMillis;
            payload.lastTleSyncMillis = this.lastTleSyncMillis;
            payload.broadcastLatestEpochMillis = this.broadcastLatestEpochMillis;
            payload.broadcastSyncedMillis = this.broadcastSyncedMillis;
            payload.gpsNoticesAvailable = this.gpsNoticesAvailable;
            payload.galileoNoticesAvailable = this.galileoNoticesAvailable;
            payload.catalogEntries = new ArrayList<>(this.catalogEntries);
            List<CachedTle> cachedTles = new ArrayList<>();
            for (TLE tle : this.tleCatalog) {
                if (tle != null && tle.getLine1() != null && tle.getLine2() != null) {
                    CachedTle ct = new CachedTle();
                    ct.line0 = nonNull(tle.getSatName());
                    ct.line1 = tle.getLine1();
                    ct.line2 = tle.getLine2();
                    cachedTles.add(ct);
                }
            }
            payload.tleCatalog = cachedTles;
            payload.broadcastByPrn = new LinkedHashMap<>(this.broadcastByPrn);
            payload.operatorByPrn = new LinkedHashMap<>(this.operatorByPrn);
            payload.outageNotices = new ArrayList<>(this.outageNotices);

            String json = gson.toJson(payload);
            Path temp = Files.createTempFile(parent, "gnss-cache-", ".tmp");
            try {
                Files.writeString(temp, json, StandardCharsets.UTF_8);
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException ignored) {
        }
    }

    private Path resolveBrdcCachePath() {
        if (cachePath == null) {
            return null;
        }
        Path parent = cachePath.toAbsolutePath().getParent();
        return parent != null ? parent.resolve(DEFAULT_BRDC_CACHE_FILE) : null;
    }

    private void loadBrdcCacheQuietly() {
        Path brdcPath = resolveBrdcCachePath();
        if (brdcPath == null || !Files.isRegularFile(brdcPath)) {
            return;
        }
        try {
            byte[] raw = Files.readAllBytes(brdcPath);
            if (raw.length > 0) {
                this.latestBrdcRawBytes = raw;
                this.latestBrdcSourceName = "Cached BKG BRDC";
            }
        } catch (IOException ignored) {
        }
    }

    private void saveBrdcCacheQuietly(byte[] rawBytes) {
        Path brdcPath = resolveBrdcCachePath();
        if (brdcPath == null || rawBytes == null || rawBytes.length == 0) {
            return;
        }
        try {
            Path parent = brdcPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            byte[] gzipped = isGzip(rawBytes) ? rawBytes : compressGzip(rawBytes);
            Path temp = Files.createTempFile(parent, "gnss-brdc-", ".tmp");
            try {
                Files.write(temp, gzipped);
                try {
                    Files.move(temp, brdcPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, brdcPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException ignored) {
        }
    }

    private static boolean isGzip(byte[] bytes) {
        return bytes != null && bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
    }

    private static byte[] compressGzip(byte[] raw) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(raw);
        }
        return baos.toByteArray();
    }

    private static String nonBlankOr(String s, String fallback) {
        return (s != null && !s.isBlank()) ? s : fallback;
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    private static final class GnssCachePayload {
        long lastSuccessfulSyncMillis;
        long lastCatalogSyncMillis;
        long lastTleSyncMillis;
        long broadcastLatestEpochMillis;
        long broadcastSyncedMillis;
        boolean gpsNoticesAvailable = true;
        boolean galileoNoticesAvailable = true;
        List<GnssCatalogEntry> catalogEntries;
        List<CachedTle> tleCatalog;
        Map<String, BroadcastHealthRecord> broadcastByPrn;
        Map<String, OperatorStatusRecord> operatorByPrn;
        List<GnssOutageNotice> outageNotices;
    }

    private static final class CachedTle {
        String line0;
        String line1;
        String line2;
    }
}

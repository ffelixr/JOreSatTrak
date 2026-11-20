package jsattrak.awareness;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import jsattrak.awareness.config.AwarenessSettings;
import jsattrak.awareness.config.AwarenessSettingsStore;
import jsattrak.awareness.config.DataSourceConfig;
import jsattrak.awareness.net.LaunchApiClient;
import jsattrak.awareness.tle.LaunchTleStatus;
import jsattrak.awareness.tle.PayloadTleMatch;
import jsattrak.awareness.tle.PayloadTleMatcher;
import jsattrak.awareness.tle.TleFeedClient;
import jsattrak.utilities.GroundStationCatalogRepository;
import jsattrak.utilities.TLE;

/**
 * Central coordinator for Live Awareness launch schedules, TLE matching catalogs,
 * local cache retention, companion ground station pad syncing, and quiet-startup lifecycle.
 */
public class LiveAwarenessService implements AwarenessFeed {

    public static final String FEED_ID = "launches";
    public static final String DISPLAY_NAME = "Launches";

    private static final int DEFAULT_HORIZON_DAYS = 7;
    private static final int DEFAULT_REFRESH_INTERVAL_MINUTES = 15;
    private static final String DEFAULT_CACHE_DIR = ".joresattrak";
    private static final String DEFAULT_CACHE_FILE = "awareness-cache.json";

    private final AwarenessSettingsStore settingsStore;
    private final Path cachePath;
    private final LaunchApiClient launchApiClient;
    private final TleFeedClient tleFeedClient;
    private final GroundStationCatalogRepository gsRepo;
    private final Clock clock;
    private final Gson gson;
    private final CopyOnWriteArrayList<AwarenessStateListener> listeners = new CopyOnWriteArrayList<>();

    private final Object refreshLock = new Object();
    private final Object lifecycleLock = new Object();

    private volatile AwarenessSettings settings;
    private volatile List<LaunchRecord> upcomingLaunches = List.of();
    private volatile List<LaunchRecord> recentLaunches = List.of();
    private volatile List<TLE> officialTleCatalog = List.of();
    private volatile List<TLE> supplementalTleCatalog = List.of();
    private volatile int selectedHorizonDays;
    private volatile boolean globalMapOverlayEnabled = true;
    private volatile String selectedLaunchId;
    private volatile boolean dataStale;
    private volatile long lastSuccessfulSyncMillis;
    private volatile String lastErrorMessage = "";

    private boolean activated;
    private boolean hasSyncedInSession;
    private boolean refreshInFlight;
    private boolean shutdown;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> periodicFuture;

    public LiveAwarenessService(
            AwarenessSettingsStore settingsStore,
            Path cachePath,
            LaunchApiClient launchApiClient,
            TleFeedClient tleFeedClient,
            GroundStationCatalogRepository gsRepo) {
        this(settingsStore, cachePath, launchApiClient, tleFeedClient, gsRepo, Clock.systemUTC());
    }

    LiveAwarenessService(
            AwarenessSettingsStore settingsStore,
            Path cachePath,
            LaunchApiClient launchApiClient,
            TleFeedClient tleFeedClient,
            GroundStationCatalogRepository gsRepo,
            Clock clock) {
        this.settingsStore = Objects.requireNonNull(settingsStore, "settingsStore");
        this.cachePath = cachePath;
        this.launchApiClient = Objects.requireNonNull(launchApiClient, "launchApiClient");
        this.tleFeedClient = Objects.requireNonNull(tleFeedClient, "tleFeedClient");
        this.gsRepo = gsRepo;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.gson = new GsonBuilder().setPrettyPrinting().create();

        AwarenessSettings loadedSettings = settingsStore.load();
        this.settings = loadedSettings != null ? loadedSettings : AwarenessSettings.createDefaults();
        this.selectedHorizonDays = normalizeHorizonDays(this.settings.getDefaultHorizonDays());
        loadCacheQuietly();
    }

    /**
     * Creates a default production {@link LiveAwarenessService} storing settings and cache under
     * {@code ~/.joresattrak/}.
     */
    public static LiveAwarenessService createDefault(GroundStationCatalogRepository gsRepo) {
        Path defaultCachePath = Path.of(
                System.getProperty("user.home"),
                DEFAULT_CACHE_DIR,
                DEFAULT_CACHE_FILE);
        GroundStationCatalogRepository effectiveRepo =
                gsRepo != null ? gsRepo : GroundStationCatalogRepository.createDefault();
        return new LiveAwarenessService(
                AwarenessSettingsStore.createDefault(),
                defaultCachePath,
                new LaunchApiClient(),
                new TleFeedClient(),
                effectiveRepo);
    }

    /**
     * Creates an offline test {@link LiveAwarenessService} preloaded with the given launches and
     * backed by stub network clients inside {@code tmpDir}.
     */
    public static LiveAwarenessService createForTestWithLaunches(Path tmpDir, List<LaunchRecord> upcomingLaunches) {
        Path baseDir;
        try {
            baseDir = tmpDir != null ? tmpDir : Files.createTempDirectory("awareness-test");
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create temporary directory for test service", e);
        }
        List<LaunchRecord> preloaded = sanitizeLaunchList(upcomingLaunches);
        AwarenessSettingsStore store = new AwarenessSettingsStore(baseDir.resolve("awareness-settings.json"));
        LaunchApiClient offlineLaunchClient = new LaunchApiClient() {
            @Override
            public LaunchFetchResult fetchUpcomingAndRecent(List<DataSourceConfig> sources, long realNowMillis) {
                return new LaunchFetchResult(preloaded, List.of(), "OfflineTest");
            }
        };
        TleFeedClient offlineTleClient = new TleFeedClient() {
            @Override
            public List<TLE> fetchTleCatalog(DataSourceConfig source) {
                return List.of();
            }
        };
        GroundStationCatalogRepository testRepo = new GroundStationCatalogRepository(
                baseDir.resolve("base.csv").toFile(),
                baseDir.resolve("custom.csv").toFile(),
                baseDir.resolve("launches.csv").toFile());
        LiveAwarenessService service = new LiveAwarenessService(
                store,
                baseDir.resolve(DEFAULT_CACHE_FILE),
                offlineLaunchClient,
                offlineTleClient,
                testRepo);
        service.preloadForTest(preloaded);
        return service;
    }

    private void preloadForTest(List<LaunchRecord> preloaded) {
        this.upcomingLaunches = preloaded;
        this.recentLaunches = List.of();
        this.dataStale = false;
        this.lastErrorMessage = "";
        this.lastSuccessfulSyncMillis = clock.millis();
        synchronized (lifecycleLock) {
            this.hasSyncedInSession = true;
        }
    }

    public void addListener(AwarenessStateListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(AwarenessStateListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    /**
     * Enforces quiet startup when {@code autoFetchOnStartup} is disabled; otherwise starts background syncing.
     */
    public void initializeAtStartup() {
        AwarenessSettings current = this.settings;
        if (current == null || !current.isAutoFetchOnStartup()) {
            return;
        }
        ensureActivated();
    }

    /**
     * Activates periodic background refresh and triggers an initial fetch if not yet synced this session.
     */
    public void ensureActivated() {
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

    /**
     * Triggers an asynchronous launch and TLE refresh off the Swing EDT.
     */
    public void refreshNowAsync() {
        ScheduledExecutorService exec;
        synchronized (lifecycleLock) {
            if (shutdown || refreshInFlight) {
                return;
            }
            refreshInFlight = true;
            exec = ensureExecutorLocked();
        }
        try {
            exec.execute(this::executeRefreshTask);
        } catch (RejectedExecutionException ignored) {
            synchronized (lifecycleLock) {
                refreshInFlight = false;
            }
        }
    }

    /**
     * Executes a refresh synchronously on the calling thread and dispatches listener callbacks directly
     * for deterministic unit and integration testing.
     */
    public void refreshBlockingForTest() {
        performRefresh(true);
    }

    /**
     * Stops periodic background refresh tasks and shuts down the daemon worker executor.
     */
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
        this.settings = updated;
        synchronized (lifecycleLock) {
            if (activated && !shutdown) {
                if (periodicFuture != null) {
                    periodicFuture.cancel(false);
                    periodicFuture = null;
                }
                schedulePeriodicRefreshLocked();
            }
        }
        dispatchCallbacks(false, this::notifyDataChanged);
    }

    public List<LaunchRecord> getVisibleUpcomingLaunches(long simEpochMillis) {
        return LaunchTimeEvaluator.filterUpcoming(upcomingLaunches, simEpochMillis, selectedHorizonDays);
    }

    public List<LaunchRecord> getVisibleRecentLaunches(long realNowMillis) {
        return LaunchTimeEvaluator.filterRecent(recentLaunches, realNowMillis);
    }

    public List<PayloadTleMatch> getMatchedTles(
            LaunchRecord launch,
            Set<String> existingSatNames,
            Set<Integer> existingNoradIds) {
        return PayloadTleMatcher.matchLaunch(
                launch,
                officialTleCatalog,
                supplementalTleCatalog,
                existingSatNames,
                existingNoradIds);
    }

    public LaunchTleStatus getTleStatus(LaunchRecord launch) {
        List<PayloadTleMatch> matches = getMatchedTles(launch, Set.of(), Set.of());
        return PayloadTleMatcher.evaluateStatus(launch, matches);
    }

    public int getSelectedHorizonDays() {
        return selectedHorizonDays;
    }

    public void setSelectedHorizonDays(int days) {
        if (days != 1 && days != 3 && days != 7) {
            throw new IllegalArgumentException("Horizon days must be 1, 3, or 7 (got " + days + ")");
        }
        if (this.selectedHorizonDays != days) {
            this.selectedHorizonDays = days;
            dispatchCallbacks(false, this::notifyDataChanged);
        }
    }

    public boolean isGlobalMapOverlayEnabled() {
        return globalMapOverlayEnabled;
    }

    public void setGlobalMapOverlayEnabled(boolean enabled) {
        if (this.globalMapOverlayEnabled != enabled) {
            this.globalMapOverlayEnabled = enabled;
            dispatchCallbacks(false, this::notifyDataChanged);
        }
    }

    public String getSelectedLaunchId() {
        return selectedLaunchId;
    }

    public void selectLaunch(String launchId) {
        String normalized = (launchId == null || launchId.isBlank()) ? null : launchId;
        if (!Objects.equals(this.selectedLaunchId, normalized)) {
            this.selectedLaunchId = normalized;
            dispatchCallbacks(false, () -> notifySelectionChanged(normalized));
        }
    }

    /**
     * Selects the given launch and requests listeners (such as {@code JSatTrak}) to open or focus
     * the Live Awareness window.
     */
    public void selectLaunchAndRequestOpenUi(String launchId) {
        selectLaunch(launchId);
        dispatchCallbacks(false, this::notifyOpenUiRequested);
    }

    public LaunchRecord getSelectedLaunch() {
        return findLaunchById(this.selectedLaunchId, upcomingLaunches, recentLaunches);
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
        return getVisibleUpcomingLaunches(clock.millis()).size();
    }

    @Override
    public boolean isMapOverlayEnabled() {
        return isGlobalMapOverlayEnabled();
    }

    @Override
    public void setMapOverlayEnabled(boolean enabled) {
        setGlobalMapOverlayEnabled(enabled);
    }

    private void performRefresh(boolean synchronousCallbacks) {
        synchronized (refreshLock) {
            if (shutdown) {
                return;
            }
            AwarenessSettings currentSettings = this.settings;
            List<DataSourceConfig> launchSources = currentSettings.getEnabledSourcesByCategory(
                    DataSourceConfig.FeedCategory.LAUNCH);
            long nowMillis = clock.millis();

            LaunchApiClient.LaunchFetchResult launchResult;
            try {
                launchResult = launchApiClient.fetchUpcomingAndRecent(launchSources, nowMillis);
                if (launchResult == null) {
                    throw new IOException("Launch API client returned null result");
                }
            } catch (IOException e) {
                this.dataStale = true;
                String error = nonBlankOr(e.getMessage(), e.getClass().getSimpleName());
                this.lastErrorMessage = error;
                String statusMsg = "Launch refresh failed (showing cached data): " + error;
                dispatchCallbacks(synchronousCallbacks, () -> {
                    notifyStatusMessage(statusMsg, true);
                    notifyDataChanged();
                });
                return;
            }

            List<LaunchRecord> newUpcoming = sanitizeLaunchList(launchResult.upcoming());
            List<LaunchRecord> newRecent = sanitizeLaunchList(launchResult.recent());
            this.upcomingLaunches = newUpcoming;
            this.recentLaunches = newRecent;
            this.lastSuccessfulSyncMillis = nowMillis;
            this.dataStale = false;
            this.lastErrorMessage = "";
            synchronized (lifecycleLock) {
                this.hasSyncedInSession = true;
            }

            boolean selectionCleared = clearSelectionIfMissing(newUpcoming, newRecent);
            List<String> warnings = new ArrayList<>();

            DataSourceConfig activeSource = findActiveLaunchSource(launchSources, launchResult.sourceUsed());
            if (activeSource != null && activeSource.isSyncFixedPadsToGroundStations() && gsRepo != null) {
                try {
                    List<LaunchRecord> allLaunches = new ArrayList<>(newUpcoming.size() + newRecent.size());
                    allLaunches.addAll(newUpcoming);
                    allLaunches.addAll(newRecent);
                    gsRepo.syncLaunchPads(allLaunches);
                } catch (IOException e) {
                    warnings.add("GS pad sync warning: " + nonBlankOr(e.getMessage(), e.getClass().getSimpleName()));
                }
            }

            syncTleCatalogs(currentSettings, warnings);

            try {
                saveCacheAtomically();
            } catch (IOException e) {
                warnings.add("Cache save warning: " + nonBlankOr(e.getMessage(), e.getClass().getSimpleName()));
            }

            String sourceLabel = nonBlankOr(launchResult.sourceUsed(), "Launch Feed");
            String baseStatus = "Synced " + newUpcoming.size() + " upcoming, "
                    + newRecent.size() + " recent launches (" + sourceLabel + ")";
            boolean hasWarning = !warnings.isEmpty();
            String fullStatus = hasWarning ? baseStatus + " — " + String.join("; ", warnings) : baseStatus;

            dispatchCallbacks(synchronousCallbacks, () -> {
                notifyDataChanged();
                if (selectionCleared) {
                    notifySelectionChanged(null);
                }
                notifyStatusMessage(fullStatus, hasWarning);
            });
        }
    }

    private void syncTleCatalogs(AwarenessSettings currentSettings, List<String> warnings) {
        List<DataSourceConfig> tleSources = currentSettings.getEnabledSourcesByCategory(
                DataSourceConfig.FeedCategory.TLE);
        List<DataSourceConfig> officialSources = new ArrayList<>();
        List<DataSourceConfig> supplementalSources = new ArrayList<>();
        for (DataSourceConfig source : tleSources) {
            if (isSupplementalTleSource(source)) {
                supplementalSources.add(source);
            } else {
                officialSources.add(source);
            }
        }

        TleBatchResult officialBatch = fetchTleGroup(officialSources);
        if (officialSources.isEmpty() || officialBatch.anySucceeded) {
            this.officialTleCatalog = officialBatch.tles;
        }
        warnings.addAll(officialBatch.warnings);

        TleBatchResult supplementalBatch = fetchTleGroup(supplementalSources);
        if (supplementalSources.isEmpty() || supplementalBatch.anySucceeded) {
            this.supplementalTleCatalog = supplementalBatch.tles;
        }
        warnings.addAll(supplementalBatch.warnings);
    }

    private TleBatchResult fetchTleGroup(List<DataSourceConfig> sources) {
        if (sources.isEmpty()) {
            return new TleBatchResult(List.of(), false, List.of());
        }
        List<TLE> collected = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean anySucceeded = false;
        for (DataSourceConfig source : sources) {
            try {
                List<TLE> fetched = tleFeedClient.fetchTleCatalog(source);
                if (fetched != null) {
                    for (TLE tle : fetched) {
                        if (tle != null) {
                            collected.add(tle);
                        }
                    }
                }
                anySucceeded = true;
            } catch (IOException e) {
                warnings.add("TLE feed '" + sourceLabel(source) + "' unavailable: "
                        + nonBlankOr(e.getMessage(), e.getClass().getSimpleName()));
            }
        }
        return new TleBatchResult(List.copyOf(collected), anySucceeded, warnings);
    }

    private boolean clearSelectionIfMissing(List<LaunchRecord> upcoming, List<LaunchRecord> recent) {
        String id = this.selectedLaunchId;
        if (id == null || findLaunchById(id, upcoming, recent) != null) {
            return false;
        }
        this.selectedLaunchId = null;
        return true;
    }

    private static LaunchRecord findLaunchById(
            String id,
            List<LaunchRecord> upcoming,
            List<LaunchRecord> recent) {
        if (id == null) {
            return null;
        }
        for (LaunchRecord record : upcoming) {
            if (id.equals(record.getId())) {
                return record;
            }
        }
        for (LaunchRecord record : recent) {
            if (id.equals(record.getId())) {
                return record;
            }
        }
        return null;
    }

    private void executeRefreshTask() {
        try {
            performRefresh(false);
        } finally {
            synchronized (lifecycleLock) {
                refreshInFlight = false;
            }
        }
    }

    private void runScheduledRefresh() {
        synchronized (lifecycleLock) {
            if (shutdown || refreshInFlight) {
                return;
            }
            refreshInFlight = true;
        }
        executeRefreshTask();
    }

    private ScheduledExecutorService ensureExecutorLocked() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread t = new Thread(runnable, "LiveAwarenessService-worker");
                t.setDaemon(true);
                return t;
            });
        }
        return executor;
    }

    private void schedulePeriodicRefreshLocked() {
        int intervalMinutes = resolveRefreshIntervalMinutes(this.settings);
        if (intervalMinutes <= 0 || shutdown) {
            return;
        }
        ScheduledExecutorService exec = ensureExecutorLocked();
        periodicFuture = exec.scheduleWithFixedDelay(
                this::runScheduledRefresh,
                intervalMinutes,
                intervalMinutes,
                TimeUnit.MINUTES);
    }

    private void dispatchCallbacks(boolean synchronous, Runnable task) {
        if (synchronous || SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    private void forEachListener(Consumer<AwarenessStateListener> action) {
        for (AwarenessStateListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (RuntimeException ignored) {
                // Keep notifying remaining listeners
            }
        }
    }

    private void notifyDataChanged() {
        forEachListener(AwarenessStateListener::onAwarenessDataChanged);
    }

    private void notifySelectionChanged(String launchId) {
        forEachListener(listener -> listener.onSelectionChanged(launchId));
    }

    private void notifyStatusMessage(String message, boolean isWarning) {
        forEachListener(listener -> listener.onStatusMessage(message, isWarning));
    }

    private void notifyOpenUiRequested() {
        forEachListener(AwarenessStateListener::onOpenUiRequested);
    }

    private void loadCacheQuietly() {
        if (cachePath == null || !Files.exists(cachePath) || !Files.isRegularFile(cachePath)) {
            return;
        }
        try {
            String raw = Files.readString(cachePath, StandardCharsets.UTF_8);
            if (raw == null || raw.isBlank()) {
                return;
            }
            CachePayload payload = gson.fromJson(raw, CachePayload.class);
            if (payload == null) {
                return;
            }
            this.lastSuccessfulSyncMillis = Math.max(0L, payload.lastSuccessfulSyncMillis);
            this.upcomingLaunches = fromCachedLaunches(payload.upcomingLaunches);
            this.recentLaunches = fromCachedLaunches(payload.recentLaunches);
            this.officialTleCatalog = fromCachedTles(payload.officialTleCatalog);
            this.supplementalTleCatalog = fromCachedTles(payload.supplementalTleCatalog);
        } catch (Exception ignored) {
            // Corrupt/truncated cache must never prevent startup
            this.lastSuccessfulSyncMillis = 0L;
            this.upcomingLaunches = List.of();
            this.recentLaunches = List.of();
            this.officialTleCatalog = List.of();
            this.supplementalTleCatalog = List.of();
        }
    }

    private void saveCacheAtomically() throws IOException {
        if (cachePath == null) {
            return;
        }
        Path target = cachePath.toAbsolutePath();
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        CachePayload payload = new CachePayload();
        payload.lastSuccessfulSyncMillis = this.lastSuccessfulSyncMillis;
        payload.upcomingLaunches = toCachedLaunches(this.upcomingLaunches);
        payload.recentLaunches = toCachedLaunches(this.recentLaunches);
        payload.officialTleCatalog = toCachedTles(this.officialTleCatalog);
        payload.supplementalTleCatalog = toCachedTles(this.supplementalTleCatalog);

        String json = gson.toJson(payload);
        Path tempFile = Files.createTempFile(parent, "awareness-cache-", ".tmp");
        try {
            Files.writeString(tempFile, json, StandardCharsets.UTF_8);
            try {
                Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private static int normalizeHorizonDays(int days) {
        return (days == 1 || days == 3 || days == 7) ? days : DEFAULT_HORIZON_DAYS;
    }

    private static int resolveRefreshIntervalMinutes(AwarenessSettings currentSettings) {
        if (currentSettings == null) {
            return DEFAULT_REFRESH_INTERVAL_MINUTES;
        }
        List<DataSourceConfig> launchSources =
                currentSettings.getEnabledSourcesByCategory(DataSourceConfig.FeedCategory.LAUNCH);
        if (!launchSources.isEmpty()) {
            return launchSources.get(0).getRefreshIntervalMinutes();
        }
        List<DataSourceConfig> tleSources =
                currentSettings.getEnabledSourcesByCategory(DataSourceConfig.FeedCategory.TLE);
        if (!tleSources.isEmpty()) {
            return tleSources.get(0).getRefreshIntervalMinutes();
        }
        return DEFAULT_REFRESH_INTERVAL_MINUTES;
    }

    private static DataSourceConfig findActiveLaunchSource(
            List<DataSourceConfig> launchSources,
            String sourceUsed) {
        if (launchSources == null || launchSources.isEmpty()) {
            return null;
        }
        if (sourceUsed != null && !sourceUsed.isBlank()) {
            for (DataSourceConfig source : launchSources) {
                if (sourceUsed.equals(sourceLabel(source))
                        || sourceUsed.equals(source.getDisplayName())
                        || sourceUsed.equals(source.getId())
                        || sourceUsed.equals(source.getEndpointUrl())) {
                    return source;
                }
            }
        }
        return launchSources.get(0);
    }

    private static boolean isSupplementalTleSource(DataSourceConfig source) {
        if (source == null) {
            return false;
        }
        return containsSupGpHint(source.getId())
                || containsSupGpHint(source.getDisplayName())
                || containsSupGpHint(source.getEndpointUrl());
    }

    private static boolean containsSupGpHint(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("supgp") || lower.contains("supplemental") || lower.contains("sup-gp");
    }

    private static String sourceLabel(DataSourceConfig source) {
        String name = source.getDisplayName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        String id = source.getId();
        return (id != null && !id.isBlank()) ? id : nonBlankOr(source.getEndpointUrl(), "");
    }

    private static List<LaunchRecord> sanitizeLaunchList(List<LaunchRecord> launches) {
        if (launches == null || launches.isEmpty()) {
            return List.of();
        }
        List<LaunchRecord> clean = new ArrayList<>(launches.size());
        for (LaunchRecord r : launches) {
            if (r != null) {
                clean.add(r);
            }
        }
        return List.copyOf(clean);
    }

    private static String nonBlankOr(String candidate, String fallback) {
        return (candidate != null && !candidate.isBlank()) ? candidate : fallback;
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    private static List<LaunchRecord> fromCachedLaunches(List<CachedLaunch> cached) {
        if (cached == null || cached.isEmpty()) {
            return List.of();
        }
        List<LaunchRecord> records = new ArrayList<>(cached.size());
        for (CachedLaunch item : cached) {
            if (item == null) {
                continue;
            }
            if (nonNull(item.id).isBlank() && nonNull(item.missionName).isBlank()) {
                continue;
            }
            double lat = Double.isFinite(item.latDeg) ? item.latDeg : 0.0;
            double lon = Double.isFinite(item.lonDeg) ? item.lonDeg : 0.0;
            records.add(LaunchRecord.builder()
                    .id(nonNull(item.id))
                    .missionName(nonNull(item.missionName))
                    .provider(nonNull(item.provider))
                    .vehicle(nonNull(item.vehicle))
                    .targetOrbit(nonNull(item.targetOrbit))
                    .cosparId(nonNull(item.cosparId))
                    .netEpochMillis(item.netEpochMillis)
                    .windowStartMillis(item.windowStartMillis)
                    .windowEndMillis(item.windowEndMillis)
                    .status(nonNull(item.status))
                    .outcome(nonNull(item.outcome))
                    .orbital(item.orbital)
                    .description(nonNull(item.description))
                    .webcastUrl(nonNull(item.webcastUrl))
                    .padName(nonNull(item.padName))
                    .spaceportName(nonNull(item.spaceportName))
                    .country(nonNull(item.country))
                    .latDeg(lat)
                    .lonDeg(lon)
                    .fixedLandPad(item.fixedLandPad)
                    .build());
        }
        return List.copyOf(records);
    }

    private static List<CachedLaunch> toCachedLaunches(List<LaunchRecord> launches) {
        if (launches == null || launches.isEmpty()) {
            return List.of();
        }
        List<CachedLaunch> cached = new ArrayList<>(launches.size());
        for (LaunchRecord r : launches) {
            if (r == null) {
                continue;
            }
            CachedLaunch item = new CachedLaunch();
            item.id = nonNull(r.getId());
            item.missionName = nonNull(r.getMissionName());
            item.provider = nonNull(r.getProvider());
            item.vehicle = nonNull(r.getVehicle());
            item.targetOrbit = nonNull(r.getTargetOrbit());
            item.cosparId = nonNull(r.getCosparId());
            item.netEpochMillis = r.getNetEpochMillis();
            item.windowStartMillis = r.getWindowStartMillis();
            item.windowEndMillis = r.getWindowEndMillis();
            item.status = nonNull(r.getStatus());
            item.outcome = nonNull(r.getOutcome());
            item.orbital = r.isOrbital();
            item.description = nonNull(r.getDescription());
            item.webcastUrl = nonNull(r.getWebcastUrl());
            item.padName = nonNull(r.getPadName());
            item.spaceportName = nonNull(r.getSpaceportName());
            item.country = nonNull(r.getCountry());
            item.latDeg = r.getLatDeg();
            item.lonDeg = r.getLonDeg();
            item.fixedLandPad = r.isFixedLandPad();
            cached.add(item);
        }
        return cached;
    }

    private static List<TLE> fromCachedTles(List<CachedTle> cached) {
        if (cached == null || cached.isEmpty()) {
            return List.of();
        }
        List<TLE> tles = new ArrayList<>(cached.size());
        for (CachedTle item : cached) {
            if (item == null || nonNull(item.line1).isBlank() || nonNull(item.line2).isBlank()) {
                continue;
            }
            tles.add(new TLE(nonNull(item.line0), item.line1, item.line2));
        }
        return List.copyOf(tles);
    }

    private static List<CachedTle> toCachedTles(List<TLE> tles) {
        if (tles == null || tles.isEmpty()) {
            return List.of();
        }
        List<CachedTle> cached = new ArrayList<>(tles.size());
        for (TLE tle : tles) {
            if (tle == null || nonNull(tle.getLine1()).isBlank() || nonNull(tle.getLine2()).isBlank()) {
                continue;
            }
            CachedTle item = new CachedTle();
            item.line0 = nonNull(tle.getSatName());
            item.line1 = tle.getLine1();
            item.line2 = tle.getLine2();
            cached.add(item);
        }
        return cached;
    }

    private record TleBatchResult(List<TLE> tles, boolean anySucceeded, List<String> warnings) {
    }

    private static final class CachePayload {
        long lastSuccessfulSyncMillis;
        List<CachedLaunch> upcomingLaunches;
        List<CachedLaunch> recentLaunches;
        List<CachedTle> officialTleCatalog;
        List<CachedTle> supplementalTleCatalog;
    }

    private static final class CachedLaunch {
        String id;
        String missionName;
        String provider;
        String vehicle;
        String targetOrbit;
        String cosparId;
        long netEpochMillis;
        long windowStartMillis;
        long windowEndMillis;
        String status;
        String outcome;
        boolean orbital = true;
        String description;
        String webcastUrl;
        String padName;
        String spaceportName;
        String country;
        double latDeg;
        double lonDeg;
        boolean fixedLandPad;
    }

    private static final class CachedTle {
        String line0;
        String line1;
        String line2;
    }
}

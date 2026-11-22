package jsattrak.awareness.gnss.geometry;

import jsattrak.awareness.AwarenessFeed;
import jsattrak.awareness.config.AwarenessSettings;
import jsattrak.awareness.config.AwarenessSettingsStore;
import jsattrak.awareness.gnss.GnssAwarenessService;
import jsattrak.awareness.gnss.GnssStateListener;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central Layer-2 coordinator ({@link AwarenessFeed}) for local GNSS DOP analysis, 24-hour
 * outage-aware time-series, and asynchronous global DOP heatmaps.
 */
public class GnssGeometryService implements AwarenessFeed {

    public static final String FEED_ID = "gnss-geometry";
    public static final String DISPLAY_NAME = "GNSS Geometry";

    public static final long SERIES_STEP_MILLIS = 10L * 60_000L; // 10 minutes
    public static final long SERIES_LOOKBACK_MILLIS = 6L * 3600_000L;
    public static final long SERIES_LOOKAHEAD_MILLIS = 18L * 3600_000L;
    public static final int SERIES_SAMPLE_COUNT = 145;
    private static final long SERIES_REANCHOR_THRESHOLD_MILLIS = 3600_000L; // 1 hour
    private static final long HEATMAP_RECOMPUTE_THRESHOLD_MILLIS = 60_000L; // 1 minute
    private static final long ASYNC_DEBOUNCE_MILLIS = 120L;

    private final GnssAwarenessService awarenessService;
    private final AwarenessSettingsStore settingsStore;
    private final GnssEphemerisProvider ephemerisProvider;
    private final CopyOnWriteArrayList<GnssGeometryListener> listeners = new CopyOnWriteArrayList<>();
    private final GnssStateListener awarenessListener;

    private final Object workerLock = new Object();
    private ScheduledExecutorService workerExecutor;
    private ScheduledFuture<?> pendingSeriesFuture;
    private ScheduledFuture<?> pendingHeatmapFuture;
    private final AtomicLong dataVersion = new AtomicLong(1L);
    private final AtomicLong seriesGeneration = new AtomicLong(0L);
    private final AtomicLong heatmapGeneration = new AtomicLong(0L);

    private volatile boolean mapOverlayEnabled = false; // master gate OFF on startup
    private volatile boolean geometryTabVisible = false;
    private volatile boolean pickOnMapMode = false;
    private volatile boolean shutdown = false;

    private volatile GnssObserverPosition observer;
    private volatile double elevationMaskDeg;
    private volatile boolean includeNonOperational;
    private volatile GnssDopMetric heatmapMetric;
    private volatile double heatmapResolutionDeg;
    private volatile double heatmapOpacity;

    private volatile GnssDopSolution latestPointSolution;
    private volatile List<GnssDopSample> latest24hSeries = List.of();
    private volatile long seriesAnchorEpochMillis = Long.MIN_VALUE;
    private volatile long seriesDataVersion = -1L;

    private volatile GnssDopGrid latestHeatmapGrid;
    private volatile long heatmapDataVersion = -1L;

    public GnssGeometryService(GnssAwarenessService awarenessService, AwarenessSettingsStore settingsStore) {
        this.awarenessService = Objects.requireNonNull(awarenessService, "awarenessService");
        this.settingsStore = settingsStore;
        this.ephemerisProvider = new GnssEphemerisProvider();

        AwarenessSettings settings = awarenessService.getSettings();
        if (settings == null) {
            settings = AwarenessSettings.createDefaults();
        }
        this.observer = new GnssObserverPosition(
                "Saved Observer",
                settings.getGnssObserverLatDeg(),
                settings.getGnssObserverLonDeg(),
                settings.getGnssObserverAltMeters());
        this.elevationMaskDeg = clampElevMask(settings.getGnssElevationMaskDeg());
        this.includeNonOperational = settings.isGnssIncludeNonOperationalInDop();
        this.heatmapMetric = GnssDopMetric.fromNameOrDefault(settings.getGnssDopHeatmapMetric(), GnssDopMetric.PDOP);
        this.heatmapResolutionDeg = normalizeResolution(settings.getGnssDopHeatmapResolutionDeg());
        this.heatmapOpacity = clampOpacity(settings.getGnssDopHeatmapOpacity());

        this.ephemerisProvider.syncBrdcFromAwareness(awarenessService);
        this.awarenessListener = new GnssStateListener() {
            @Override
            public void onGnssDataChanged() {
                ephemerisProvider.syncBrdcFromAwareness(awarenessService);
                invalidateDerivedCaches();
                dispatchCallbacks(false, l -> l.onGeometryDataChanged());
            }

            @Override
            public void onGnssSelectionChanged(String selectedPrnId) {
            }

            @Override
            public void onGnssStatusMessage(String message, boolean isWarning) {
            }
        };
        this.awarenessService.addListener(this.awarenessListener);
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
        GnssDopSolution sol = this.latestPointSolution;
        return sol != null ? sol.visibleCount() : 0;
    }

    @Override
    public boolean isMapOverlayEnabled() {
        return mapOverlayEnabled;
    }

    @Override
    public void setMapOverlayEnabled(boolean enabled) {
        if (this.mapOverlayEnabled != enabled) {
            this.mapOverlayEnabled = enabled;
            dispatchCallbacks(false, l -> l.onGeometryDataChanged());
            dispatchCallbacks(false, l -> l.onHeatmapUpdated(enabled ? this.latestHeatmapGrid : null));
        }
    }

    // ---- Listeners ----

    public void addListener(GnssGeometryListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(GnssGeometryListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    // ---- Configuration Getters & Setters ----

    public GnssObserverPosition getObserver() {
        return observer;
    }

    public void setObserver(GnssObserverPosition newObserver) {
        if (newObserver == null) {
            return;
        }
        if (!Objects.equals(this.observer, newObserver)) {
            this.observer = newObserver;
            invalidateSeriesCache();
            persistLayer2Settings();
            dispatchCallbacks(false, l -> l.onObserverChanged(newObserver));
            dispatchCallbacks(false, l -> l.onGeometryDataChanged());
        }
    }

    public double getElevationMaskDeg() {
        return elevationMaskDeg;
    }

    public void setElevationMaskDeg(double elevationMaskDeg) {
        double clamped = clampElevMask(elevationMaskDeg);
        if (Double.compare(this.elevationMaskDeg, clamped) != 0) {
            this.elevationMaskDeg = clamped;
            invalidateDerivedCaches();
            persistLayer2Settings();
            dispatchCallbacks(false, l -> l.onGeometryDataChanged());
        }
    }

    public boolean isIncludeNonOperational() {
        return includeNonOperational;
    }

    public void setIncludeNonOperational(boolean includeNonOperational) {
        if (this.includeNonOperational != includeNonOperational) {
            this.includeNonOperational = includeNonOperational;
            invalidateDerivedCaches();
            persistLayer2Settings();
            dispatchCallbacks(false, l -> l.onGeometryDataChanged());
        }
    }

    public GnssDopMetric getHeatmapMetric() {
        return heatmapMetric;
    }

    public void setHeatmapMetric(GnssDopMetric metric) {
        if (metric != null && this.heatmapMetric != metric) {
            this.heatmapMetric = metric;
            invalidateHeatmapCache();
            persistLayer2Settings();
            dispatchCallbacks(false, l -> l.onGeometryDataChanged());
        }
    }

    public double getHeatmapResolutionDeg() {
        return heatmapResolutionDeg;
    }

    public void setHeatmapResolutionDeg(double resolutionDeg) {
        double normalized = normalizeResolution(resolutionDeg);
        if (Double.compare(this.heatmapResolutionDeg, normalized) != 0) {
            this.heatmapResolutionDeg = normalized;
            invalidateHeatmapCache();
            persistLayer2Settings();
            dispatchCallbacks(false, l -> l.onGeometryDataChanged());
        }
    }

    public double getHeatmapOpacity() {
        return heatmapOpacity;
    }

    public void setHeatmapOpacity(double opacity) {
        double clamped = clampOpacity(opacity);
        if (Double.compare(this.heatmapOpacity, clamped) != 0) {
            this.heatmapOpacity = clamped;
            persistLayer2Settings();
            if (this.mapOverlayEnabled) {
                dispatchCallbacks(false, l -> l.onHeatmapUpdated(this.latestHeatmapGrid));
            }
        }
    }

    public boolean isPickOnMapMode() {
        return pickOnMapMode;
    }

    public void setPickOnMapMode(boolean pickMode) {
        if (this.pickOnMapMode != pickMode) {
            this.pickOnMapMode = pickMode;
            dispatchCallbacks(false, l -> l.onPickOnMapModeChanged(pickMode));
            dispatchCallbacks(false, l -> l.onGeometryDataChanged());
        }
    }

    public void completeMapPick(double latDeg, double lonDeg) {
        double alt = this.observer != null ? this.observer.altMeters() : 0.0;
        this.pickOnMapMode = false;
        GnssObserverPosition picked = new GnssObserverPosition("Map Pick", latDeg, lonDeg, alt);
        this.observer = picked;
        invalidateSeriesCache();
        persistLayer2Settings();
        dispatchCallbacks(false, l -> l.onPickOnMapModeChanged(false));
        dispatchCallbacks(false, l -> l.onObserverChanged(picked));
        dispatchCallbacks(false, l -> l.onGeometryDataChanged());
    }

    public boolean isGeometryTabVisible() {
        return geometryTabVisible;
    }

    public void setGeometryTabVisible(boolean visible) {
        if (this.geometryTabVisible != visible) {
            this.geometryTabVisible = visible;
            dispatchCallbacks(false, l -> l.onGeometryDataChanged());
        }
    }

    /**
     * Observer crosshair pin is displayed only while the GNSS Geometry tab is visible or Pick on Map is armed.
     */
    public boolean isObserverPinVisible() {
        return geometryTabVisible || pickOnMapMode;
    }

    // ---- Ephemeris Import ----

    public GnssEphemerisProvider.LocalLoadSummary loadLocalEphemerisFile(Path file) throws IOException {
        GnssEphemerisProvider.LocalLoadSummary summary = ephemerisProvider.loadLocalFile(file);
        invalidateDerivedCaches();
        dispatchCallbacks(false, l -> l.onGeometryDataChanged());
        dispatchCallbacks(false, l -> l.onGeometryStatusMessage(
                "Loaded " + summary.fileName() + " (" + summary.satelliteCount() + " SVs, "
                        + summary.sourceType().getDisplayLabel() + ")",
                false));
        return summary;
    }

    public void clearLocalEphemerisFile() {
        ephemerisProvider.clearLocalFile();
        invalidateDerivedCaches();
        dispatchCallbacks(false, l -> l.onGeometryDataChanged());
        dispatchCallbacks(false, l -> l.onGeometryStatusMessage("Cleared local ephemeris file", false));
    }

    public String getLoadedLocalFileName() {
        return ephemerisProvider.getLoadedLocalFileName();
    }

    public GnssAwarenessService getAwarenessService() {
        return awarenessService;
    }

    // ---- Point, 24h Series & Spatial Grid Computations ----

    public GnssDopSolution computePointSolution(long simEpochMillis) {
        GnssEphemerisProvider.ResolvedEpochSnapshot snap =
                ephemerisProvider.resolveSatellitesAtEpoch(simEpochMillis, awarenessService);
        GnssDopSolution raw = GnssDopCalculator.computePointDop(
                simEpochMillis,
                this.observer,
                this.elevationMaskDeg,
                this.includeNonOperational,
                snap.candidates());
        // Preserve epoch-level provenance (including SP3 noHealthFlags & fileLabel); when all SVs
        // are masked out or excluded (usedCount == 0), still report the resolved epoch provenance.
        GnssOrbitSource.Summary effectiveSummary;
        if (raw.usedCount() == 0 && snap.orbitSource() != null && snap.orbitSource().primary() != GnssOrbitSource.NONE) {
            effectiveSummary = snap.orbitSource();
        } else if (snap.orbitSource() != null) {
            effectiveSummary = new GnssOrbitSource.Summary(
                    raw.orbitSource().primary(),
                    raw.orbitSource().sp3Count(),
                    raw.orbitSource().brdcCount(),
                    raw.orbitSource().tleCount(),
                    snap.orbitSource().noHealthFlags(),
                    snap.orbitSource().fileLabel());
        } else {
            effectiveSummary = raw.orbitSource();
        }
        GnssDopSolution result = new GnssDopSolution(
                raw.epochMillis(),
                raw.observer(),
                raw.elevationMaskDeg(),
                raw.fixAvailable(),
                raw.gdop(),
                raw.pdop(),
                raw.hdop(),
                raw.vdop(),
                raw.tdop(),
                raw.visibleCount(),
                raw.usedCount(),
                raw.visibleByConstellation(),
                raw.skyPoints(),
                effectiveSummary);
        this.latestPointSolution = result;
        return result;
    }

    public GnssDopSolution getLatestPointSolution() {
        return latestPointSolution;
    }

    public List<GnssDopSample> getLatest24hSeries() {
        return latest24hSeries;
    }

    public void request24hSeriesAsync(long simEpochMillis) {
        long ver = this.dataVersion.get();
        if (this.seriesDataVersion == ver
                && !this.latest24hSeries.isEmpty()
                && this.seriesAnchorEpochMillis != Long.MIN_VALUE
                && Math.abs(simEpochMillis - this.seriesAnchorEpochMillis) <= SERIES_REANCHOR_THRESHOLD_MILLIS) {
            return;
        }
        long gen = this.seriesGeneration.incrementAndGet();
        synchronized (workerLock) {
            if (shutdown) {
                return;
            }
            if (pendingSeriesFuture != null) {
                pendingSeriesFuture.cancel(false);
            }
            ScheduledExecutorService exec = ensureWorkerLocked();
            pendingSeriesFuture = exec.schedule(() -> {
                if (shutdown || this.seriesGeneration.get() != gen) {
                    return;
                }
                List<GnssDopSample> computed = compute24hSeriesInternal(simEpochMillis);
                if (!shutdown && this.seriesGeneration.get() == gen) {
                    this.latest24hSeries = computed;
                    this.seriesAnchorEpochMillis = simEpochMillis;
                    this.seriesDataVersion = ver;
                    dispatchCallbacks(false, l -> l.onGeometryDataChanged());
                }
            }, ASYNC_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    public List<GnssDopSample> compute24hSeriesBlockingForTest(long simEpochMillis) {
        long ver = this.dataVersion.get();
        List<GnssDopSample> computed = compute24hSeriesInternal(simEpochMillis);
        this.latest24hSeries = computed;
        this.seriesAnchorEpochMillis = simEpochMillis;
        this.seriesDataVersion = ver;
        return computed;
    }

    public GnssDopGrid getLatestHeatmapGrid() {
        return latestHeatmapGrid;
    }

    public void requestHeatmapAsync(long simEpochMillis) {
        if (!this.mapOverlayEnabled || shutdown) {
            return;
        }
        long ver = this.dataVersion.get();
        GnssDopGrid existing = this.latestHeatmapGrid;
        if (existing != null && this.heatmapDataVersion == ver) {
            long age = Math.abs(simEpochMillis - existing.getEpochMillis());
            if (age < HEATMAP_RECOMPUTE_THRESHOLD_MILLIS
                    && !awarenessService.hasCrossedNoticeBoundary(existing.getEpochMillis(), simEpochMillis)) {
                return;
            }
        }
        long gen = this.heatmapGeneration.incrementAndGet();
        synchronized (workerLock) {
            if (shutdown) {
                return;
            }
            if (pendingHeatmapFuture != null) {
                pendingHeatmapFuture.cancel(false);
            }
            ScheduledExecutorService exec = ensureWorkerLocked();
            pendingHeatmapFuture = exec.schedule(() -> {
                if (shutdown || !this.mapOverlayEnabled || this.heatmapGeneration.get() != gen) {
                    return;
                }
                GnssDopGrid grid = computeHeatmapInternal(simEpochMillis);
                if (!shutdown && this.mapOverlayEnabled && this.heatmapGeneration.get() == gen) {
                    this.latestHeatmapGrid = grid;
                    this.heatmapDataVersion = ver;
                    dispatchCallbacks(false, l -> l.onHeatmapUpdated(grid));
                }
            }, ASYNC_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    public GnssDopGrid computeHeatmapBlockingForTest(long simEpochMillis) {
        long ver = this.dataVersion.get();
        GnssDopGrid grid = computeHeatmapInternal(simEpochMillis);
        this.latestHeatmapGrid = grid;
        this.heatmapDataVersion = ver;
        return grid;
    }

    public void shutdown() {
        this.awarenessService.removeListener(this.awarenessListener);
        ScheduledExecutorService execToStop;
        synchronized (workerLock) {
            this.shutdown = true;
            if (pendingSeriesFuture != null) {
                pendingSeriesFuture.cancel(false);
                pendingSeriesFuture = null;
            }
            if (pendingHeatmapFuture != null) {
                pendingHeatmapFuture.cancel(false);
                pendingHeatmapFuture = null;
            }
            execToStop = workerExecutor;
            workerExecutor = null;
        }
        if (execToStop != null) {
            execToStop.shutdownNow();
        }
    }

    private List<GnssDopSample> compute24hSeriesInternal(long centerEpochMillis) {
        long startMillis = centerEpochMillis - SERIES_LOOKBACK_MILLIS;
        GnssObserverPosition obs = this.observer;
        double mask = this.elevationMaskDeg;
        boolean includeNonOp = this.includeNonOperational;
        List<GnssDopSample> samples = new ArrayList<>(SERIES_SAMPLE_COUNT);
        for (int i = 0; i < SERIES_SAMPLE_COUNT; i++) {
            if (Thread.currentThread().isInterrupted() || shutdown) {
                break;
            }
            long epoch = startMillis + i * SERIES_STEP_MILLIS;
            GnssEphemerisProvider.ResolvedEpochSnapshot snap =
                    ephemerisProvider.resolveSatellitesAtEpoch(epoch, awarenessService);
            GnssDopSolution sol = GnssDopCalculator.computePointDop(
                    epoch, obs, mask, includeNonOp, snap.candidates());
            samples.add(new GnssDopSample(
                    epoch,
                    sol.fixAvailable(),
                    sol.gdop(),
                    sol.pdop(),
                    sol.hdop(),
                    sol.vdop(),
                    sol.tdop(),
                    sol.visibleCount(),
                    sol.visibleByConstellation()));
        }
        return List.copyOf(samples);
    }

    private GnssDopGrid computeHeatmapInternal(long epochMillis) {
        GnssEphemerisProvider.ResolvedEpochSnapshot snap =
                ephemerisProvider.resolveSatellitesAtEpoch(epochMillis, awarenessService);
        return GnssDopGrid.compute(
                epochMillis,
                this.heatmapResolutionDeg,
                this.elevationMaskDeg,
                this.includeNonOperational,
                this.heatmapMetric,
                snap.candidates(),
                snap.orbitSource());
    }

    private void invalidateDerivedCaches() {
        this.dataVersion.incrementAndGet();
        this.seriesGeneration.incrementAndGet();
        this.heatmapGeneration.incrementAndGet();
        this.seriesDataVersion = -1L;
        this.heatmapDataVersion = -1L;
    }

    private void invalidateSeriesCache() {
        this.seriesGeneration.incrementAndGet();
        this.seriesAnchorEpochMillis = Long.MIN_VALUE;
        this.seriesDataVersion = -1L;
    }

    private void invalidateHeatmapCache() {
        this.heatmapGeneration.incrementAndGet();
        this.heatmapDataVersion = -1L;
    }

    private void persistLayer2Settings() {
        AwarenessSettings current = awarenessService.getSettings();
        if (current == null) {
            current = AwarenessSettings.createDefaults();
        }
        current.setGnssElevationMaskDeg(this.elevationMaskDeg);
        current.setGnssIncludeNonOperationalInDop(this.includeNonOperational);
        current.setGnssDopHeatmapMetric(this.heatmapMetric.name());
        current.setGnssDopHeatmapResolutionDeg(this.heatmapResolutionDeg);
        current.setGnssDopHeatmapOpacity(this.heatmapOpacity);
        if (this.observer != null) {
            current.setGnssObserverLatDeg(this.observer.latDeg());
            current.setGnssObserverLonDeg(this.observer.lonDeg());
            current.setGnssObserverAltMeters(this.observer.altMeters());
        }
        try {
            if (settingsStore != null) {
                settingsStore.save(current);
            }
            awarenessService.syncSettingsSilently(current);
        } catch (IOException ignored) {
        }
    }

    private ScheduledExecutorService ensureWorkerLocked() {
        if (workerExecutor == null || workerExecutor.isShutdown()) {
            workerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "gnss-geometry-worker");
                t.setDaemon(true);
                return t;
            });
        }
        return workerExecutor;
    }

    private void dispatchCallbacks(boolean synchronous, java.util.function.Consumer<GnssGeometryListener> action) {
        if (shutdown || action == null) {
            return;
        }
        Runnable fire = () -> {
            if (shutdown) {
                return;
            }
            for (GnssGeometryListener l : listeners) {
                action.accept(l);
            }
        };
        if (synchronous || SwingUtilities.isEventDispatchThread()) {
            fire.run();
        } else {
            SwingUtilities.invokeLater(fire);
        }
    }

    private static double clampElevMask(double deg) {
        return Double.isFinite(deg) ? Math.max(0.0, Math.min(45.0, deg)) : 10.0;
    }

    private static double normalizeResolution(double deg) {
        return (Double.isFinite(deg) && deg <= 3.0) ? 2.5 : 5.0;
    }

    private static double clampOpacity(double opacity) {
        return Double.isFinite(opacity) ? Math.max(0.0, Math.min(1.0, opacity)) : 0.45;
    }
}

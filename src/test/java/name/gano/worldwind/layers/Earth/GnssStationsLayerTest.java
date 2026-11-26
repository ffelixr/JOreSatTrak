package name.gano.worldwind.layers.Earth;

import static org.junit.jupiter.api.Assertions.*;

import gov.nasa.worldwind.Model;
import gov.nasa.worldwind.WorldWind;
import gov.nasa.worldwind.WorldWindow;
import gov.nasa.worldwind.event.SelectEvent;
import gov.nasa.worldwind.event.SelectListener;
import gov.nasa.worldwind.globes.Earth;
import gov.nasa.worldwind.layers.LayerList;
import gov.nasa.worldwind.pick.PickedObject;
import gov.nasa.worldwind.pick.PickedObjectList;
import gov.nasa.worldwind.render.PointPlacemark;
import gov.nasa.worldwind.render.Renderable;
import gov.nasa.worldwind.render.SurfaceCircle;
import java.awt.Point;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import jsattrak.awareness.config.AwarenessSettings;
import jsattrak.awareness.config.AwarenessSettingsStore;
import jsattrak.awareness.gnss.GnssAwarenessService;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.geometry.GnssGeometryService;
import jsattrak.awareness.gnss.net.GnssStationCatalogClient;
import jsattrak.awareness.gnss.stations.GnssReferenceStation;
import jsattrak.awareness.gnss.stations.GnssStationListener;
import jsattrak.awareness.gnss.stations.GnssStationNetwork;
import jsattrak.awareness.gnss.stations.GnssStationService;
import jsattrak.awareness.gnss.stations.GnssStationStatus;
import jsattrak.awareness.gnss.stations.analysis.GnssStationAnalysis;
import jsattrak.awareness.gnss.stations.analysis.RegionalScanReport;
import jsattrak.awareness.gnss.stations.analysis.RegionalScanReport.RegionalEventSeverity;
import jsattrak.awareness.gnss.stations.obs.GnssObservationSource;
import jsattrak.awareness.gnss.stations.obs.RinexObservationData;
import jsattrak.awareness.gnss.stations.obs.RinexObservationData.EpochRecord;
import jsattrak.awareness.gnss.stations.obs.RinexObservationData.HeaderSummary;
import jsattrak.awareness.gnss.stations.obs.RinexObservationData.SatelliteObsSample;
import jsattrak.gui.JSatTrak;
import name.gano.worldwind.view.AutoClipBasicOrbitView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.orekit.gnss.PredefinedObservationType;

class GnssStationsLayerTest {

    static {
        System.setProperty("java.awt.headless", "true");
        WorldWind.setOfflineMode(true);
    }

    private static final Instant T0 = Instant.parse("2026-11-25T19:00:00Z");

    @Test
    void clustersStationsRendersInterferenceRingSuppressesDuringPickModeAndGcCleansViewCoordinator(
            @TempDir Path tmpDir) throws Exception {
        JSatTrak.initOrekitData();
        AwarenessSettingsStore store = new AwarenessSettingsStore(tmpDir.resolve("awareness.json"));
        AwarenessSettings settings = AwarenessSettings.createDefaults();
        settings.setAutoFetchOnStartup(false);
        settings.setGnssFeedEnabled(true);
        store.save(settings);

        GnssAwarenessService awareness = GnssAwarenessService.createForTest(
                tmpDir, List.of(), List.of(), Map.of(), Map.of(), List.of());
        awareness.syncSettingsSilently(settings);
        GnssGeometryService geometry = new GnssGeometryService(awareness, store);

        List<GnssReferenceStation> stations = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            stations.add(makeStation(
                    String.format(Locale.US, "MAD%d00ESP", i),
                    40.4 + i * 0.08,
                    -3.7 + i * 0.08));
        }

        GnssStationCatalogClient mockCat = new GnssStationCatalogClient() {
            @Override
            public CatalogSyncResult fetchMergedCatalog(
                    List<jsattrak.awareness.config.DataSourceConfig> catSources,
                    List<jsattrak.awareness.config.DataSourceConfig> arcSources,
                    long nowMillis) {
                return new CatalogSyncResult(stations, "Synced 6");
            }
        };

        GnssObservationSource degradedSource = new GnssObservationSource() {
            @Override
            public String getSourceId() {
                return "mock";
            }

            @Override
            public String getDisplayName() {
                return "Mock Degraded";
            }

            @Override
            public boolean supportsNetwork(GnssStationNetwork network) {
                return true;
            }

            @Override
            public boolean isRealTimeStream() {
                return false;
            }

            @Override
            public ObservationFetchResult fetchObservations(GnssReferenceStation st, ObservationQuery q) {
                SatelliteObsSample g01Pre = new SatelliteObsSample(
                        "G01", GnssConstellation.GPS,
                        Map.of(PredefinedObservationType.S1C, 46.0, PredefinedObservationType.C1C, 2.0e7),
                        Map.of(), Map.of(), false, null);
                SatelliteObsSample e11Pre = new SatelliteObsSample(
                        "E11", GnssConstellation.GALILEO,
                        Map.of(PredefinedObservationType.S1X, 46.0, PredefinedObservationType.C1X, 2.3e7),
                        Map.of(), Map.of(), false, null);
                SatelliteObsSample g02Pre = new SatelliteObsSample(
                        "G02", GnssConstellation.GPS,
                        Map.of(PredefinedObservationType.S1C, 46.0, PredefinedObservationType.C1C, 2.1e7),
                        Map.of(), Map.of(), false, null);
                SatelliteObsSample g01Drop = new SatelliteObsSample(
                        "G01", GnssConstellation.GPS,
                        Map.of(PredefinedObservationType.S1C, 24.0, PredefinedObservationType.C1C, 2.0e7),
                        Map.of(), Map.of(), false, null);
                SatelliteObsSample e11Drop = new SatelliteObsSample(
                        "E11", GnssConstellation.GALILEO,
                        Map.of(PredefinedObservationType.S1X, 23.0, PredefinedObservationType.C1X, 2.3e7),
                        Map.of(), Map.of(), false, null);
                SatelliteObsSample g02Drop = new SatelliteObsSample(
                        "G02", GnssConstellation.GPS,
                        Map.of(PredefinedObservationType.S1C, 22.5, PredefinedObservationType.C1C, 2.1e7),
                        Map.of(), Map.of(), false, null);
                long tPre = T0.toEpochMilli() - 60_000L;
                long tDrop = T0.toEpochMilli();
                HeaderSummary header = new HeaderSummary(
                        st.stationId(), st.receiverModel(), st.receiverFirmware(), st.antennaModel(),
                        st.monumentEcef(), 60.0, tPre, tDrop, "DBHZ",
                        Map.of(), List.of());
                RinexObservationData obs = new RinexObservationData(
                        header,
                        List.of(
                                new EpochRecord(tPre, 0.0, Map.of("G01", g01Pre, "E11", e11Pre, "G02", g02Pre)),
                                new EpochRecord(tDrop, 0.0, Map.of("G01", g01Drop, "E11", e11Drop, "G02", g02Drop))));
                return new ObservationFetchResult(st, obs, "Mock Degraded", "mock://obs", tDrop, "RINEX 3.04");
            }
        };

        GnssStationService service = new GnssStationService(
                awareness,
                geometry,
                store,
                tmpDir.resolve("stations-cache.json"),
                mockCat,
                List.of(degradedSource),
                Clock.fixed(T0, ZoneOffset.UTC));

        try {
            service.ensureActivated();
            // Wait briefly for catalog sync from mockCat
            for (int i = 0; i < 40 && service.getStations().size() != 6; i++) {
                Thread.sleep(25);
            }
            assertEquals(6, service.getStations().size());

            // 1. Trigger regional interference report via scanRegionalInterferenceAsync
            java.util.concurrent.CountDownLatch scanLatch = new java.util.concurrent.CountDownLatch(1);
            GnssStationListener scanListener = new GnssStationListener() {
                @Override
                public void onStationCatalogChanged() {}
                @Override
                public void onSelectedStationChanged(String selectedStationId) {}
                @Override
                public void onInspectionUpdated(GnssStationAnalysis analysis) {}
                @Override
                public void onRegionalScanUpdated(RegionalScanReport report) {
                    scanLatch.countDown();
                }
                @Override
                public void onStationStatusMessage(String message, boolean isWarning) {}
                @Override
                public void onMapOverlayStateChanged(boolean enabled) {}
            };
            service.addListener(scanListener);
            service.scanRegionalInterferenceAsync("MAD000ESP");
            assertTrue(scanLatch.await(10, TimeUnit.SECONDS), "Timed out waiting for regional scan report");
            service.removeListener(scanListener);
            RegionalScanReport report = service.getLatestRegionalReport();
            assertNotNull(report, "Expected regional scan report");
            assertEquals(RegionalEventSeverity.POTENTIAL_INTERFERENCE, report.severity(),
                    () -> "diag=" + report.diagnosis() + ", stations=" + report.stations().size()
                            + ", status=" + service.getLastStatusMessage());

            // Stub WorldWindow
            LayerList layers = new LayerList();
            GnssStationsLayer layer = new GnssStationsLayer(null);
            layers.add(layer);
            assertEquals(GnssStationsLayer.LAYER_NAME, layer.getName());
            assertTrue(layer.isEnabled(), "Layer should default enabled for legacy .jst tolerance");

            AutoClipBasicOrbitView orbitView = new AutoClipBasicOrbitView();
            orbitView.setGlobe(new Earth());
            Model model = (Model) Proxy.newProxyInstance(
                    Model.class.getClassLoader(),
                    new Class<?>[]{Model.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "getLayers" -> layers;
                        case "getGlobe" -> orbitView.getGlobe();
                        default -> null;
                    });
            List<SelectListener> attached = new ArrayList<>();
            WorldWindow stubWwd = (WorldWindow) Proxy.newProxyInstance(
                    WorldWindow.class.getClassLoader(),
                    new Class<?>[]{WorldWindow.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "getView" -> orbitView;
                        case "getModel" -> model;
                        case "addSelectListener" -> {
                            attached.add((SelectListener) a[0]);
                            yield null;
                        }
                        case "removeSelectListener" -> {
                            attached.remove((SelectListener) a[0]);
                            yield null;
                        }
                        default -> null;
                    });

            GnssStationsLayer.ViewCoordinator coordinator =
                    new GnssStationsLayer.ViewCoordinator(stubWwd, layer);
            AtomicBoolean pickSuppressed = new AtomicBoolean(true);
            coordinator.setPickSuppressionCheck(pickSuppressed::get);
            coordinator.setStationService(service);

            // Master gate defaults false -> renderables must be empty!
            assertFalse(service.isMapOverlayEnabled());
            assertEquals(0, layer.getNumRenderables());

            // Enable master gate -> PointPlacemark cluster(s) + SurfaceCircle created!
            service.setMapOverlayEnabled(true);
            javax.swing.SwingUtilities.invokeAndWait(() -> { });
            assertTrue(layer.getNumRenderables() > 0, "Expected renderables once master gate enabled");
            PointPlacemark placemark = null;
            SurfaceCircle ring = null;
            for (Renderable r : layer.getRenderables()) {
                if (r instanceof PointPlacemark pm && placemark == null) {
                    placemark = pm;
                } else if (r instanceof SurfaceCircle sc) {
                    ring = sc;
                }
            }
            assertNotNull(placemark, "Expected at least one station/cluster PointPlacemark");
            assertNotNull(ring, "Expected interference zone SurfaceCircle");

            // 2. Pick suppression check: LEFT_CLICK when suppressed must NOT select station
            assertNull(service.getSelectedStationId());
            PickedObjectList pickList = new PickedObjectList();
            pickList.add(new PickedObject(1, placemark));
            SelectListener sl = attached.get(0);
            sl.selected(new SelectEvent(stubWwd, SelectEvent.LEFT_CLICK, new Point(50, 50), pickList));
            assertNull(service.getSelectedStationId(), "Click must be suppressed during Pick-on-Map mode");

            pickSuppressed.set(false);
            sl.selected(new SelectEvent(stubWwd, SelectEvent.LEFT_CLICK, new Point(50, 50), pickList));
            assertNotNull(service.getSelectedStationId(), "Click must select station when not suppressed");

            // 3. GC hygiene: clear strong references to ViewCoordinator without dispose -> GC cleans up
            service.selectStation(null);
            GnssStationsLayer layer2 = new GnssStationsLayer(null);
            GnssStationsLayer.ViewCoordinator coord2 = new GnssStationsLayer.ViewCoordinator(stubWwd, layer2);
            coord2.setStationService(service);
            WeakReference<GnssStationsLayer.ViewCoordinator> ref = new WeakReference<>(coord2);
            coord2 = null;
            for (int i = 0; i < 60 && ref.get() != null; i++) {
                System.gc();
                Thread.sleep(20);
            }
            assertNull(ref.get(), "ViewCoordinator must be GC-eligible when window drops reference");
        } finally {
            service.shutdown();
            awareness.shutdown();
            geometry.shutdown();
        }
    }

    private static GnssReferenceStation makeStation(String id, double lat, double lon) {
        return new GnssReferenceStation(
                id,
                id.substring(0, 4),
                id + " Site",
                "ESP",
                GnssStationNetwork.IGS,
                Set.of(GnssStationNetwork.IGS),
                lat,
                lon,
                650.0,
                null,
                "SEPTENTRIO POLARX5",
                "5.5.0",
                "SEPCHOKE_B3E6",
                "NONE",
                EnumSet.of(GnssConstellation.GPS, GnssConstellation.GALILEO),
                List.of("RINEX3"),
                T0.toEpochMilli(),
                15,
                99.5,
                GnssStationStatus.ONLINE,
                null);
    }
}

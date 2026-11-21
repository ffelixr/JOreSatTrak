package jsattrak.awareness.gnss;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import jsattrak.awareness.config.AuthMode;
import jsattrak.awareness.config.AwarenessSettings;
import jsattrak.awareness.config.AwarenessSettingsStore;
import jsattrak.awareness.config.DataSourceConfig;
import jsattrak.awareness.gnss.map.GnssOverlay2DRenderer;
import jsattrak.awareness.gnss.net.BroadcastNavClient;
import jsattrak.awareness.gnss.net.GnssCatalogClient;
import jsattrak.awareness.gnss.net.OperatorStatusClient;
import jsattrak.awareness.tle.TleFeedClient;
import jsattrak.gui.JSatTrak;
import jsattrak.objects.AbstractSatellite;
import jsattrak.objects.SatelliteTleSGP4;
import name.gano.worldwind.layers.Earth.GnssConstellationLayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class GnssAwarenessIntegrationTest {

    @Test
    void fullPipelineFromMockHttpFeedsThroughStatusClockScrubAndObjectListPromotion(@TempDir Path tmpDir)
            throws Exception {
        JSatTrak.initOrekitData();

        long t0 = Instant.parse("2026-11-21T00:00:00Z").toEpochMilli();
        Clock fixedClock = Clock.fixed(Instant.ofEpochMilli(t0), ZoneOffset.UTC);

        String sinex = """
                %=SNX 2.02 IGS
                +SATELLITE/IDENTIFIER
                 G055 2008-012A GPS-IIR-M        32711 2008:075:00000
                 G075 2021-054A GPS-III          48859 2021:168:00000
                 E226 2018-060A GALILEO-2        43567 2018:206:00000
                 R757 2018-053A GLONASS-M        43508 2018:167:00000
                 C201 2018-003A BEIDOU-3M-CAST   43107 2018:011:00000
                 C215 2018-062A BEIDOU-3M-SECM   43581 2018:210:00000
                -SATELLITE/IDENTIFIER
                +SATELLITE/PRN
                 G055 2008:075:00000 0000:000:00000 G07
                 G075 2021:168:00000 0000:000:00000 G18
                 E226 2018:206:00000 0000:000:00000 E26
                 R757 2018:167:00000 0000:000:00000 R05
                 C201 2018:011:00000 0000:000:00000 C19
                 C215 2018:210:00000 0000:000:00000 C33
                -SATELLITE/PRN
                %ENDSNX
                """;

        String tleFeed = """
                GPS BIIRM-5 (PRN 07)
                1 32711U 08012A   26324.50000000  .00000010  00000+0  00000+0 0  9991
                2 32711  55.1000 100.0000 0050000  45.0000 120.0000  2.00560000100001
                GPS III SV05 (PRN 18)
                1 48859U 21054A   26324.50000000  .00000010  00000+0  00000+0 0  9992
                2 48859  54.9000 160.0000 0030000  90.0000 180.0000  2.00560000100002
                GSAT0226 (GALILEO 26)
                1 43567U 18060A   26324.50000000 -.00000020  00000+0  00000+0 0  9993
                2 43567  56.0000 220.0000 0004000  60.0000 200.0000  1.70470000100003
                COSMOS 2527 (GLONASS 757)
                1 43508U 18053A   26324.50000000 -.00000010  00000+0  00000+0 0  9994
                2 43508  64.8000 280.0000 0008000 110.0000 240.0000  2.13100000100004
                BEIDOU-3 M1 (C19)
                1 43107U 18003A   26324.50000000  .00000000  00000+0  00000+0 0  9995
                2 43107  55.0000  40.0000 0005000 150.0000 300.0000  1.86230000100005
                BEIDOU-3 M10 (C33)
                1 43581U 18062A   26324.50000000  .00000000  00000+0  00000+0 0  9996
                2 43581  55.0000 340.0000 0005000 170.0000 320.0000  1.86230000100006
                """;

        String rinex3 = ""
                + "     3.04           N: GNSS NAV DATA    M: Mixed            RINEX VERSION / TYPE\n"
                + "BKG                                     20261121 001500 UTC PGM / RUN BY / DATE\n"
                + "                                                            END OF HEADER\n"
                + "G07 2026 11 21 00 00 00 1.000000000000D-04 0.000000000000D+00 0.000000000000D+00\n"
                + "     1.000000000000D+01 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                + "     0.000000000000D+00 0.000000000000D+00 5.153650000000D+03 0.000000000000D+00\n"
                + "     0.000000000000D+00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                + "     0.000000000000D+00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                + "     0.000000000000D+00 0.000000000000D+00 2.445000000000D+03 0.000000000000D+00\n"
                + "     2.000000000000D+00 6.300000000000D+01 0.000000000000D+00 0.000000000000D+00\n"
                + "     0.000000000000D+00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                + "G18 2026 11 21 00 00 00 1.000000000000D-04 0.000000000000D+00 0.000000000000D+00\n"
                + "     1.000000000000D+01 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                + "     0.000000000000D+00 0.000000000000D+00 5.153650000000D+03 0.000000000000D+00\n"
                + "     0.000000000000D+00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                + "     0.000000000000D+00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                + "     0.000000000000D+00 0.000000000000D+00 2.445000000000D+03 0.000000000000D+00\n"
                + "     2.000000000000D+00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                + "     0.000000000000D+00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                + "E26 2026 11 21 00 10 00 1.000000000000D-04 0.000000000000D+00 0.000000000000D+00\n"
                + "     1.000000000000D+01 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                + "     0.000000000000D+00 0.000000000000D+00 5.440610000000D+03 0.000000000000D+00\n"
                + "     0.000000000000D+00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                + "     0.000000000000D+00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                + "     0.000000000000D+00 5.170000000000D+02 2.445000000000D+03 0.000000000000D+00\n"
                + "     3.120000000000D+00 6.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                + "     0.000000000000D+00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                + "R05 2026 11 21 00 15 00 1.000000000000D-04 0.000000000000D+00 0.000000000000D+00\n"
                + "     1.234500000000D+04 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                + "     1.234500000000D+04 0.000000000000D+00 0.000000000000D+00 1.000000000000D+00\n"
                + "     1.234500000000D+04 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                + "C19 2026 11 21 00 00 00 1.000000000000D-04 0.000000000000D+00 0.000000000000D+00\n"
                + "     1.000000000000D+01 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                + "     0.000000000000D+00 0.000000000000D+00 5.282610000000D+03 0.000000000000D+00\n"
                + "     0.000000000000D+00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                + "     0.000000000000D+00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                + "     0.000000000000D+00 0.000000000000D+00 1.090000000000D+03 0.000000000000D+00\n"
                + "     2.000000000000D+00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                + "     0.000000000000D+00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                + "C33 2026 11 21 00 00 00 1.000000000000D-04 0.000000000000D+00 0.000000000000D+00\n"
                + "     1.000000000000D+01 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                + "     0.000000000000D+00 0.000000000000D+00 5.282610000000D+03 0.000000000000D+00\n"
                + "     0.000000000000D+00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                + "     0.000000000000D+00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                + "     0.000000000000D+00 0.000000000000D+00 1.090000000000D+03 0.000000000000D+00\n"
                + "     2.000000000000D+00 1.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n"
                + "     0.000000000000D+00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n";

        String navcenStatus = """
                PRN,SVN,Status,Remarks
                G07,G055,OPERATIONAL,Reported nominal
                G18,G075,OPERATIONAL,Nominal
                """;
        // 2026-11-21 is DOY 325: [0200..0800] UTC -> [T0+2h .. T0+8h]
        String nanu2026074 = """
                NOTICE ADVISORY TO NAVSTAR USERS (NANU) 2026074
                SUBJ: SVN75 (PRN18) FORECAST OUTAGE DV
                1. NANU TYPE: FCSTDV
                   NANU NUMBER: 2026074
                   START TIME: 2026 325 0200
                   STOP TIME:  2026 325 0800
                """;
        String gscStatusXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <constellation>
                  <satellite prn="E26" svn="GSAT0226" status="UNDER COMMISSIONING" remarks="In-orbit testing"/>
                </constellation>
                """;
        String gscNaguHtml = "<!DOCTYPE html><html><head><title>GSC Portal</title></head><body>Portal login</body></html>";

        byte[] gzBrdc = gzip(rinex3);
        AtomicInteger totalHits = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/igs.snx", ex -> {
                totalHits.incrementAndGet();
                respond(ex, 200, "text/plain", sinex.getBytes(StandardCharsets.UTF_8));
            });
            server.createContext("/gnss.tle", ex -> {
                totalHits.incrementAndGet();
                respond(ex, 200, "text/plain", tleFeed.getBytes(StandardCharsets.UTF_8));
            });
            server.createContext("/brdc/", ex -> {
                totalHits.incrementAndGet();
                String path = ex.getRequestURI().getPath();
                if (path.endsWith("/brdc/")) {
                    String html = "<html><body><a href=\"BRDC00WRD_R_20263250000_01D_MN.rnx.gz\">2026325</a></body></html>";
                    respond(ex, 200, "text/html", html.getBytes(StandardCharsets.UTF_8));
                } else if (path.endsWith("BRDC00WRD_R_20263250000_01D_MN.rnx.gz")) {
                    respond(ex, 200, "application/gzip", gzBrdc);
                } else {
                    ex.sendResponseHeaders(404, -1);
                    ex.close();
                }
            });
            server.createContext("/navcen/status", ex -> {
                totalHits.incrementAndGet();
                respond(ex, 200, "text/plain", navcenStatus.getBytes(StandardCharsets.UTF_8));
            });
            server.createContext("/navcen/nanu/", ex -> {
                totalHits.incrementAndGet();
                String path = ex.getRequestURI().getPath();
                if (path.endsWith("/navcen/nanu/")) {
                    respond(ex, 200, "text/html", "<html><body><a href=\"2026074.nnu\">2026074.nnu</a></body></html>"
                            .getBytes(StandardCharsets.UTF_8));
                } else if (path.endsWith("2026074.nnu")) {
                    respond(ex, 200, "text/plain", nanu2026074.getBytes(StandardCharsets.UTF_8));
                } else {
                    ex.sendResponseHeaders(404, -1);
                    ex.close();
                }
            });
            server.createContext("/gsc/status", ex -> {
                totalHits.incrementAndGet();
                respond(ex, 200, "application/xml", gscStatusXml.getBytes(StandardCharsets.UTF_8));
            });
            server.createContext("/gsc/nagu", ex -> {
                totalHits.incrementAndGet();
                respond(ex, 200, "text/html", gscNaguHtml.getBytes(StandardCharsets.UTF_8));
            });
            server.start();

            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            AwarenessSettingsStore store = new AwarenessSettingsStore(tmpDir.resolve("awareness-settings.json"));
            AwarenessSettings settings = new AwarenessSettings();
            settings.setAutoFetchOnStartup(false);
            settings.setGnssFeedEnabled(true);
            settings.setSources(List.of(
                    src("igs", DataSourceConfig.FeedCategory.GNSS_CATALOG, "", "IGS SINEX", base + "/igs.snx", 1440),
                    src("tle", DataSourceConfig.FeedCategory.GNSS_ORBITS_TLE, "", "GNSS TLE", base + "/gnss.tle", 1440),
                    src("brdc", DataSourceConfig.FeedCategory.GNSS_BROADCAST_NAV, "", "BKG BRDC", base + "/brdc/", 15),
                    src("gps-st", DataSourceConfig.FeedCategory.GNSS_OPERATOR_STATUS, "GPS_STATUS", "NAVCEN Status", base + "/navcen/status", 15),
                    src("gps-nu", DataSourceConfig.FeedCategory.GNSS_OPERATOR_STATUS, "GPS_NANU", "NAVCEN NANU", base + "/navcen/nanu/", 15),
                    src("gal-st", DataSourceConfig.FeedCategory.GNSS_OPERATOR_STATUS, "GALILEO_STATUS", "GSC Status", base + "/gsc/status", 15),
                    src("gal-nu", DataSourceConfig.FeedCategory.GNSS_OPERATOR_STATUS, "GALILEO_NAGU", "GSC NAGU", base + "/gsc/nagu", 15)
            ));
            store.save(settings);

            GnssManualStatusStore manualStore = new GnssManualStatusStore(tmpDir.resolve("gnss-manual.json"));
            manualStore.save(new GnssManualStatusList("2026-11-21", List.of()));

            GnssAwarenessService service = new GnssAwarenessService(
                    store,
                    tmpDir.resolve("gnss-cache.json"),
                    manualStore,
                    null,
                    new GnssCatalogClient(),
                    new TleFeedClient(),
                    new BroadcastNavClient(),
                    new OperatorStatusClient(),
                    fixedClock);
            try {
                // Step 2: quiet startup -> 0 HTTP hits before explicit refresh
                service.initializeAtStartup();
                assertEquals(0, totalHits.get());

                // Step 3: sync & verify T0 statuses + Galileo notice fallback note
                service.refreshBlockingForTest();
                assertTrue(totalHits.get() > 0);

                assertEquals(GnssDisplayStatus.OPERATIONAL, stateStatus(service, "G18", t0));
                assertNotNull(service.getSatelliteState("G18", t0).orElseThrow().statusResult().nextPlannedNotice());
                assertEquals(GnssDisplayStatus.UNAVAILABLE, stateStatus(service, "G07", t0));
                assertEquals(GnssDisplayStatus.TESTING, stateStatus(service, "E26", t0));
                assertEquals(GnssDisplayStatus.OPERATIONAL, stateStatus(service, "R05", t0));
                assertEquals(GnssDisplayStatus.OPERATIONAL, stateStatus(service, "C19", t0));
                assertEquals(GnssDisplayStatus.UNAVAILABLE, stateStatus(service, "C33", t0));

                var e26Evidence = service.getSatelliteState("E26", t0).orElseThrow().statusResult().evidence();
                assertTrue(e26Evidence.stream().anyMatch(e ->
                        e.reason().toLowerCase().contains("unavailable")
                                || e.rawValue().toLowerCase().contains("unavailable")));

                // Step 4: scrub sim time to T0 + 3h (inside G18 planned outage [T0+2h .. T0+8h])
                long tOutage = t0 + 3L * 3_600_000L;
                assertEquals(GnssDisplayStatus.UNAVAILABLE, stateStatus(service, "G18", tOutage));
                List<GnssSatelliteState> opAtOutage = service.getOperationalSatellites(tOutage);
                assertTrue(opAtOutage.stream().noneMatch(s -> "G18".equals(s.catalog().prnId())));
                assertTrue(opAtOutage.stream().anyMatch(s -> "C19".equals(s.catalog().prnId())));

                // Step 5: promote C19 to scenario Object List helper
                GnssSatelliteState c19 = service.getSatelliteState("C19", tOutage).orElseThrow();
                GnssSatelliteState r05 = service.getSatelliteState("R05", tOutage).orElseThrow();
                Map<String, AbstractSatellite> satHash = new HashMap<>();
                assertFalse(JSatTrak.isGnssSatelliteInObjectList(satHash, c19));

                boolean added = JSatTrak.addOrUpdateSatelliteFromTle(
                        satHash, c19.tle(), false, 2461365.5, null, null);
                assertTrue(added);
                assertTrue(satHash.values().stream().anyMatch(SatelliteTleSGP4.class::isInstance));
                assertTrue(JSatTrak.isGnssSatelliteInObjectList(satHash, c19));
                assertFalse(JSatTrak.isGnssSatelliteInObjectList(satHash, r05));

                // Step 6: 2D overlay & 3D GnssConstellationLayer render the updated snapshot cleanly
                BufferedImage img = new BufferedImage(800, 400, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2 = img.createGraphics();
                try {
                    GnssOverlay2DRenderer renderer = new GnssOverlay2DRenderer();
                    int painted = renderer.paintServiceOverlay(
                            g2,
                            service,
                            true,
                            tOutage,
                            new Rectangle(0, 0, 800, 400),
                            (lat, lon) -> new int[] {
                                    (int) Math.round((lon + 180.0) / 360.0 * 800.0),
                                    (int) Math.round((90.0 - lat) / 180.0 * 400.0)
                            });
                    assertEquals(6, painted);
                } finally {
                    g2.dispose();
                }

                GnssConstellationLayer layer3d = new GnssConstellationLayer(service);
                layer3d.rebuildRenderables(tOutage);
                assertTrue(layer3d.getNumRenderables() >= 6);
            } finally {
                service.shutdown();
            }
        } finally {
            server.stop(0);
        }
    }

    private static GnssDisplayStatus stateStatus(GnssAwarenessService service, String prn, long epochMillis) {
        return service.getSatelliteState(prn, epochMillis).orElseThrow().statusResult().status();
    }

    private static DataSourceConfig src(
            String id, DataSourceConfig.FeedCategory category, String scope, String name, String url, int interval) {
        return new DataSourceConfig(
                id, category, scope, name, url, true, 1, AuthMode.NONE, "", "", "", interval, false);
    }

    private static void respond(HttpExchange ex, int status, String contentType, byte[] bytes) throws IOException {
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
        ex.close();
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return baos.toByteArray();
    }
}

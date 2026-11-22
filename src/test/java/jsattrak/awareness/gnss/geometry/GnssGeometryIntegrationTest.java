package jsattrak.awareness.gnss.geometry;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jsattrak.awareness.config.AuthMode;
import jsattrak.awareness.config.AwarenessSettings;
import jsattrak.awareness.config.AwarenessSettingsStore;
import jsattrak.awareness.config.DataSourceConfig;
import jsattrak.awareness.gnss.GnssAwarenessService;
import jsattrak.awareness.gnss.GnssManualStatusList;
import jsattrak.awareness.gnss.GnssManualStatusStore;
import jsattrak.awareness.gnss.map.GnssDopHeatmap2DRenderer;
import jsattrak.awareness.gnss.net.BroadcastNavClient;
import jsattrak.awareness.gnss.net.GnssCatalogClient;
import jsattrak.awareness.gnss.net.OperatorStatusClient;
import jsattrak.awareness.tle.TleFeedClient;
import jsattrak.gui.JSatTrak;
import name.gano.worldwind.layers.Earth.GnssDopHeatmapLayer;
import gov.nasa.worldwind.WorldWind;
import gov.nasa.worldwind.render.SurfaceImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class GnssGeometryIntegrationTest {

    static {
        System.setProperty("java.awt.headless", "true");
        WorldWind.setOfflineMode(true);
    }

    @Test
    void fullLayer2PipelineFromMockBrdcAndNanuThroughOutageDopSpikeAndHeatmaps(@TempDir Path tmpDir) throws Exception {
        JSatTrak.initOrekitData();

        long t0 = Instant.parse("2026-11-21T00:00:00Z").toEpochMilli();
        Clock fixedClock = Clock.fixed(Instant.ofEpochMilli(t0), ZoneOffset.UTC);

        String sinex = """
                %=SNX 2.02 IGS
                +SATELLITE/IDENTIFIER
                 G051 2010-001A GPS-IIF          37751 2010:001:00000
                 G052 2011-001A GPS-IIF          37752 2011:001:00000
                 G053 2012-001A GPS-IIF          37753 2012:001:00000
                 G054 2013-001A GPS-IIF          37754 2013:001:00000
                 G075 2021-054A GPS-III          48859 2021:168:00000
                 E226 2018-060A GALILEO-2        43567 2018:206:00000
                -SATELLITE/IDENTIFIER
                +SATELLITE/PRN
                 G051 2010:001:00000 0000:000:00000 G01
                 G052 2011:001:00000 0000:000:00000 G02
                 G053 2012:001:00000 0000:000:00000 G05
                 G054 2013:001:00000 0000:000:00000 G12
                 G075 2021:054A 0000:000:00000 G18
                 E226 2018:206:00000 0000:000:00000 E26
                -SATELLITE/PRN
                %ENDSNX
                """;
        final String sinexData = sinex.replace("G075 2021:054A 0000:000:00000 G18", "G075 2021:168:00000 0000:000:00000 G18");

        StringBuilder rinex = new StringBuilder();
        rinex.append("     3.04           N: GNSS NAV DATA    M: Mixed            RINEX VERSION / TYPE\n");
        rinex.append("BKG                                     20261121 001500 UTC PGM / RUN BY / DATE\n");
        rinex.append("                                                            END OF HEADER\n");
        // Distributed Keplerian SVs visible over (0°, 0°) both near T0 and at T0+3h (with G18 overhead around T0+3h)
        appendKeplerRecord(rinex, "G01", 5153.65, -0.4, 0.96, -0.2, 2445);
        appendKeplerRecord(rinex, "G02", 5153.65, 0.3, 0.96, 0.5, 2445);
        appendKeplerRecord(rinex, "G05", 5153.65, -1.2, 0.96, 0.9, 2445);
        appendKeplerRecord(rinex, "G12", 5153.65, -1.5, 0.20, 0.2, 2445);
        appendKeplerRecord(rinex, "G18", 5153.65, -1.55, 0.96, 0.85, 2445);
        appendKeplerRecord(rinex, "E26", 5440.61, -1.4, 0.977, 1.0, 2445);

        byte[] gzBrdc = gzip(rinex.toString());

        String navcenStatus = """
                PRN,SVN,Status,Remarks
                G01,G051,OPERATIONAL,Nominal
                G02,G052,OPERATIONAL,Nominal
                G05,G053,OPERATIONAL,Nominal
                G12,G054,OPERATIONAL,Nominal
                G18,G075,OPERATIONAL,Nominal
                """;
        // Planned NANU outage for G18 [T0+2h .. T0+6h] on DOY 325 (2026-11-21)
        String nanuOutage = """
                NOTICE ADVISORY TO NAVSTAR USERS (NANU) 2026088
                SUBJ: SVN75 (PRN18) FORECAST OUTAGE DV
                1. NANU TYPE: FCSTDV
                   NANU NUMBER: 2026088
                   START TIME: 2026 325 0200
                   STOP TIME:  2026 325 0600
                """;
        String gscStatusXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <constellation>
                  <satellite prn="E26" svn="GSAT0226" status="USABLE" remarks="Nominal"/>
                </constellation>
                """;

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/igs.snx", ex -> respond(ex, 200, "text/plain", sinexData.getBytes(StandardCharsets.UTF_8)));
            server.createContext("/gnss.tle", ex -> respond(ex, 200, "text/plain", "".getBytes(StandardCharsets.UTF_8)));
            server.createContext("/brdc/", ex -> {
                String path = ex.getRequestURI().getPath();
                if (path.endsWith("/brdc/")) {
                    respond(ex, 200, "text/html",
                            "<html><body><a href=\"BRDC00WRD_R_20263250000_01D_MN.rnx.gz\">latest</a></body></html>"
                                    .getBytes(StandardCharsets.UTF_8));
                } else if (path.endsWith("BRDC00WRD_R_20263250000_01D_MN.rnx.gz")) {
                    respond(ex, 200, "application/gzip", gzBrdc);
                } else {
                    ex.sendResponseHeaders(404, -1);
                    ex.close();
                }
            });
            server.createContext("/navcen/status", ex -> respond(ex, 200, "text/plain", navcenStatus.getBytes(StandardCharsets.UTF_8)));
            server.createContext("/navcen/nanu/", ex -> {
                String path = ex.getRequestURI().getPath();
                if (path.endsWith("/navcen/nanu/")) {
                    respond(ex, 200, "text/html",
                            "<html><body><a href=\"2026088.nnu\">2026088.nnu</a></body></html>".getBytes(StandardCharsets.UTF_8));
                } else if (path.endsWith("2026088.nnu")) {
                    respond(ex, 200, "text/plain", nanuOutage.getBytes(StandardCharsets.UTF_8));
                } else {
                    ex.sendResponseHeaders(404, -1);
                    ex.close();
                }
            });
            server.createContext("/gsc/status", ex -> respond(ex, 200, "application/xml", gscStatusXml.getBytes(StandardCharsets.UTF_8)));
            server.createContext("/gsc/nagu", ex -> respond(ex, 200, "text/html", "<html><body>OK</body></html>".getBytes(StandardCharsets.UTF_8)));
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

            GnssAwarenessService awareness = new GnssAwarenessService(
                    store,
                    tmpDir.resolve("gnss-cache.json"),
                    manualStore,
                    null,
                    new GnssCatalogClient(),
                    new TleFeedClient(),
                    new BroadcastNavClient(),
                    new OperatorStatusClient(),
                    fixedClock);
            GnssGeometryService geometry = new GnssGeometryService(awareness, store);

            try {
                geometry.setObserver(new GnssObserverPosition("Equator", 0.0, 0.0, 0.0));
                geometry.setElevationMaskDeg(5.0);

                // 2. Sync GnssAwarenessService -> GnssGeometryService ingests BRDC automatically
                awareness.refreshBlockingForTest();
                assertNotNull(awareness.getLatestBrdcRawBytes());

                // 3. Point solution at T0 uses pure BRDC & obtains a valid fix
                GnssDopSolution solT0 = geometry.computePointSolution(t0);
                assertEquals(GnssOrbitSource.BRDC, solT0.orbitSource().primary());
                assertTrue(solT0.fixAvailable());
                assertTrue(solT0.visibleCount() >= 4);

                // 4. Scrub to T0+3h (inside G18 NANU outage): G18 excluded from DOP
                long t3 = t0 + 3 * 3600_000L;
                GnssDopSolution solT3 = geometry.computePointSolution(t3);
                geometry.setIncludeNonOperational(true);
                GnssDopSolution solT3WhatIf = geometry.computePointSolution(t3);
                geometry.setIncludeNonOperational(false);
                assertTrue(solT3WhatIf.usedCount() > solT3.usedCount());

                // 5. 24h time-series captures the T0+3h outage sample
                List<GnssDopSample> series = geometry.compute24hSeriesBlockingForTest(t0);
                assertEquals(145, series.size());
                GnssDopSample sampleT3 = series.stream()
                        .filter(s -> s.epochMillis() == t3)
                        .findFirst()
                        .orElseThrow();
                assertEquals(solT3.visibleCount(), sampleT3.visibleCount());
                assertTrue(Double.isNaN(solT3.pdop()) || solT3.pdop() >= solT3WhatIf.pdop(),
                        "Outage exclusion should degrade or lose DOP fix relative to what-if inclusion");

                // 6. Enable heatmap master gate -> 2D & 3D heatmaps render cleanly
                geometry.setMapOverlayEnabled(true);
                GnssDopGrid grid = geometry.computeHeatmapBlockingForTest(t0);
                assertNotNull(grid);

                GnssDopHeatmap2DRenderer r2d = new GnssDopHeatmap2DRenderer();
                BufferedImage img2d = new BufferedImage(360, 180, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2 = img2d.createGraphics();
                assertEquals(1, r2d.paintEquirectangularHeatmap(
                        g2, geometry, true, t0, new Rectangle(0, 0, 360, 180), -180, 180, -90, 90));
                g2.dispose();

                GnssDopHeatmapLayer layer3d = new GnssDopHeatmapLayer(geometry);
                layer3d.rebuildRenderables(t0);
                int surfaceImages = 0;
                for (var r : layer3d.getRenderables()) {
                    if (r instanceof SurfaceImage) {
                        surfaceImages++;
                    }
                }
                assertEquals(1, surfaceImages);

                // 7. Verify Layer-2 documentation in Help HTML + JSatTrak accessor existence
                String helpHtml = JSatTrak.buildGnssHelpHtml();
                assertTrue(helpHtml.contains("GDOP"));
                assertTrue(helpHtml.contains("PDOP"));
                assertTrue(helpHtml.contains("HDOP"));
                assertTrue(helpHtml.contains("VDOP"));
                assertTrue(helpHtml.contains("TDOP"));
                assertTrue(helpHtml.contains("SP3"));
                assertTrue(helpHtml.contains("2.5\u00b0") || helpHtml.contains("2.5"));
                assertTrue(helpHtml.toLowerCase(Locale.ROOT).contains("elevation mask"));
                // Compile-time check for JSatTrak.getGnssGeometryService()
                assertNotNull(JSatTrak.class.getMethod("getGnssGeometryService"));
            } finally {
                geometry.shutdown();
                awareness.shutdown();
            }
        } finally {
            server.stop(0);
        }
    }

    private static void appendKeplerRecord(
            StringBuilder sb,
            String prn,
            double sqrtA,
            double m0Rad,
            double i0Rad,
            double omega0Rad,
            int week) {
        sb.append(prn).append(" 2026 11 21 00 00 00")
                .append(String.format(Locale.US, "%19.12E%19.12E%19.12E%n", 1e-4, 0.0, 0.0).replace('E', 'D'));
        sb.append(String.format(Locale.US,
                "    %19.12E%19.12E%19.12E%19.12E%n", 10.0, 0.0, 0.0, m0Rad).replace('E', 'D'));
        sb.append(String.format(Locale.US,
                "    %19.12E%19.12E%19.12E%19.12E%n", 0.0, 0.0, 0.0, sqrtA).replace('E', 'D'));
        sb.append(String.format(Locale.US,
                "    %19.12E%19.12E%19.12E%19.12E%n", 518400.0, 0.0, omega0Rad, 0.0).replace('E', 'D'));
        sb.append(String.format(Locale.US,
                "    %19.12E%19.12E%19.12E%19.12E%n", i0Rad, 0.0, 0.0, 0.0).replace('E', 'D'));
        double dataSrc = prn.startsWith("E") ? 517.0 : 0.0;
        sb.append(String.format(Locale.US,
                "    %19.12E%19.12E%19.12E%19.12E%n", 0.0, dataSrc, (double) week, 0.0).replace('E', 'D'));
        sb.append(String.format(Locale.US,
                "    %19.12E%19.12E%19.12E%19.12E%n", 2.0, 0.0, 0.0, 0.0).replace('E', 'D'));
        sb.append(String.format(Locale.US,
                "    %19.12E%19.12E%19.12E%19.12E%n", 518400.0, 0.0, 0.0, 0.0).replace('E', 'D'));
    }

    private static DataSourceConfig src(
            String id, DataSourceConfig.FeedCategory cat, String scope, String name, String url, int intervalMin) {
        return new DataSourceConfig(id, cat, scope, name, url, true, 1, AuthMode.NONE, "", "", "", intervalMin, false);
    }

    private static void respond(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
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

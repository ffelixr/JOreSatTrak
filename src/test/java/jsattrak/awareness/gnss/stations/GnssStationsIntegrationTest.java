package jsattrak.awareness.gnss.stations;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import jsattrak.awareness.config.AuthMode;
import jsattrak.awareness.config.AwarenessSettings;
import jsattrak.awareness.config.AwarenessSettingsStore;
import jsattrak.awareness.config.DataSourceConfig;
import jsattrak.awareness.gnss.BroadcastHealthRecord;
import jsattrak.awareness.gnss.GnssAwarenessService;
import jsattrak.awareness.gnss.GnssCatalogEntry;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.GnssOrbitClass;
import jsattrak.awareness.gnss.geometry.GnssDopCalculator;
import jsattrak.awareness.gnss.geometry.GnssEphemerisProvider;
import jsattrak.awareness.gnss.geometry.GnssGeometryService;
import jsattrak.awareness.gnss.net.GnssStationCatalogClient;
import jsattrak.awareness.gnss.stations.analysis.GnssSppSolver;
import jsattrak.awareness.gnss.stations.analysis.GnssStationAnalysis;
import jsattrak.awareness.gnss.stations.analysis.RegionalScanReport;
import jsattrak.awareness.gnss.stations.analysis.RegionalScanReport.RegionalEventSeverity;
import jsattrak.awareness.gnss.stations.obs.BkgHourlyRinexSource;
import jsattrak.awareness.gnss.stations.obs.GnssObservationDiskCache;
import jsattrak.gui.JSatTrak;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GnssStationsIntegrationTest {

    private static final double SPEED_OF_LIGHT = 299_792_458.0;
    private static final double OMEGA_EARTH = 7.2921151467e-5;
    private static final Instant T0 = Instant.parse("2026-11-21T00:10:00Z");

    @Test
    void endToEndCatalogSyncObsFetchSppVerificationAndRegionalInterferenceScan(@TempDir Path tmpDir)
            throws Exception {
        JSatTrak.initOrekitData();
        long t0 = T0.toEpochMilli();

        // 1. Setup Layer 1 awareness with 6 GPS + 2 Galileo SVs and BRDC orbits/clocks
        List<GnssCatalogEntry> catalog = List.of(
                cat("G01", GnssConstellation.GPS, 1), cat("G02", GnssConstellation.GPS, 2),
                cat("G03", GnssConstellation.GPS, 3), cat("G04", GnssConstellation.GPS, 4),
                cat("G05", GnssConstellation.GPS, 5), cat("G06", GnssConstellation.GPS, 6),
                cat("E11", GnssConstellation.GALILEO, 11), cat("E12", GnssConstellation.GALILEO, 12));
        Map<String, BroadcastHealthRecord> health = new LinkedHashMap<>();
        for (GnssCatalogEntry e : catalog) {
            health.put(e.prnId(), new BroadcastHealthRecord(e.prnId(), e.constellation(), "NAV", t0, 0, true, "OK"));
        }
        GnssAwarenessService awareness = GnssAwarenessService.createForTest(
                tmpDir, catalog, List.of(), health, Map.of(), List.of(), t0);

        Path brdcFile = tmpDir.resolve("brdc3250.rnx");
        Files.writeString(brdcFile, buildBrdcNavFixture(), StandardCharsets.UTF_8);

        GnssEphemerisProvider probeProvider = new GnssEphemerisProvider();
        assertEquals(8, probeProvider.loadLocalFile(brdcFile).satelliteCount());
        GnssEphemerisProvider.ResolvedClockEpochSnapshot clkSnap =
                probeProvider.resolveSatellitesWithClockAtEpoch(t0, awareness);
        double[] sv0 = {clkSnap.candidates().get(0).satX(), clkSnap.candidates().get(0).satY(), clkSnap.candidates().get(0).satZ()};
        double baseLat = Math.toDegrees(Math.atan2(sv0[2], Math.hypot(sv0[0], sv0[1])));
        double baseLon = Math.toDegrees(Math.atan2(sv0[1], sv0[0]));

        double[] staLatLon = {baseLat, baseLon, 650.0};
        double[] stbLatLon = {baseLat + 0.12, baseLon + 0.12, 620.0};
        double[] stcLatLon = {baseLat - 0.10, baseLon + 0.10, 610.0};

        byte[] staGz = gzip(buildRinex3Obs("STA000ESP", staLatLon, t0, clkSnap.candidates(), 47.5));
        byte[] stbGz = gzip(buildRinex3Obs("STB000ESP", stbLatLon, t0, clkSnap.candidates(), 31.0));
        byte[] stcGz = gzip(buildRinex3Obs("STC000ESP", stcLatLon, t0, clkSnap.candidates(), 30.5));

        String epnJson = String.format(Locale.US, """
                [
                  {"id":"STA000ESP","name":"Station Alpha","country":"ESP","lat":%.6f,"lon":%.6f,"height":%.1f,"receiver_type":"SEPT POLARX5","receiver_firmware":"5.5.0","antenna_type":"AOAD/M_T","radome_type":"NONE","constellations":["G","E"]},
                  {"id":"STB000ESP","name":"Station Bravo","country":"ESP","lat":%.6f,"lon":%.6f,"height":%.1f,"receiver_type":"SEPT POLARX5","antenna_type":"AOAD/M_T","radome_type":"NONE","constellations":["G","E"]},
                  {"id":"STC000ESP","name":"Station Charlie","country":"ESP","lat":%.6f,"lon":%.6f,"height":%.1f,"receiver_type":"TRIMBLE ALLOY","antenna_type":"TRM59800.00","radome_type":"NONE","constellations":["G","E"]}
                ]
                """, staLatLon[0], staLatLon[1], staLatLon[2],
                stbLatLon[0], stbLatLon[1], stbLatLon[2],
                stcLatLon[0], stcLatLon[1], stcLatLon[2]);
        String igsJson = String.format(Locale.US, """
                {
                  "stations": [
                    {"id":"STA000ESP","name":"Station Alpha","country":"ESP","lat":%.6f,"lon":%.6f,"height":%.1f,"receiver_type":"SEPT POLARX5","antenna_type":"AOAD/M_T","radome_type":"NONE","constellations":["G","E"]}
                  ]
                }
                """, staLatLon[0], staLatLon[1], staLatLon[2]);

        String bkgDirHtml = """
                <html><body><pre>
                <a href="STA000ESP_R_20263242300_01H_30S_MO.rnx.gz">STA000ESP_R_20263242300_01H_30S_MO.rnx.gz</a>  21-Nov-2026 00:01  10K
                <a href="STB000ESP_R_20263242300_01H_30S_MO.rnx.gz">STB000ESP_R_20263242300_01H_30S_MO.rnx.gz</a>  21-Nov-2026 00:01  10K
                <a href="STC000ESP_R_20263242300_01H_30S_MO.rnx.gz">STC000ESP_R_20263242300_01H_30S_MO.rnx.gz</a>  21-Nov-2026 00:01  10K
                </pre></body></html>
                """;

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/m3g/epn", ex -> respond(ex, 200, "application/json", epnJson.getBytes(StandardCharsets.UTF_8)));
        server.createContext("/m3g/igs", ex -> respond(ex, 200, "application/json", igsJson.getBytes(StandardCharsets.UTF_8)));
        server.createContext("/bkg/igs/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.contains("STA000ESP")) {
                respond(ex, 200, "application/gzip", staGz);
            } else if (path.contains("STB000ESP")) {
                respond(ex, 200, "application/gzip", stbGz);
            } else if (path.contains("STC000ESP")) {
                respond(ex, 200, "application/gzip", stcGz);
            } else {
                respond(ex, 200, "text/html", bkgDirHtml.getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();

        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        AwarenessSettingsStore store = new AwarenessSettingsStore(tmpDir.resolve("awareness.json"));
        AwarenessSettings settings = AwarenessSettings.createDefaults();
        settings.setAutoFetchOnStartup(false);
        settings.setGnssFeedEnabled(true);
        settings.setSources(List.of(
                new DataSourceConfig("epn", DataSourceConfig.FeedCategory.GNSS_STATION_CATALOG, "EUREF",
                        "EPN", base + "/m3g/epn", true, 1, AuthMode.NONE, "", "", "", 1440, false),
                new DataSourceConfig("igs", DataSourceConfig.FeedCategory.GNSS_STATION_CATALOG, "IGS",
                        "IGS", base + "/m3g/igs", true, 2, AuthMode.NONE, "", "", "", 1440, false),
                new DataSourceConfig("bkg-igs", DataSourceConfig.FeedCategory.GNSS_OBSERVATION_ARCHIVE, "IGS",
                        "BKG IGS", base + "/bkg/igs/nrt/", true, 1, AuthMode.NONE, "", "", "", 15, false),
                new DataSourceConfig("bkg-euref", DataSourceConfig.FeedCategory.GNSS_OBSERVATION_ARCHIVE, "EUREF",
                        "BKG EUREF", base + "/bkg/igs/nrt/", true, 2, AuthMode.NONE, "", "", "", 15, false)));
        store.save(settings);
        awareness.syncSettingsSilently(settings);

        GnssGeometryService geometry = new GnssGeometryService(awareness, store);
        GnssObservationDiskCache obsCache = new GnssObservationDiskCache(tmpDir.resolve("obs-cache"), 10 * 1024 * 1024L, 3600_000L);
        BkgHourlyRinexSource bkgSource = new BkgHourlyRinexSource(
                () -> settings.getEnabledSourcesByCategory(DataSourceConfig.FeedCategory.GNSS_OBSERVATION_ARCHIVE),
                obsCache);

        GnssStationService stationSvc = new GnssStationService(
                awareness, geometry, store, tmpDir.resolve("stations-cache.json"),
                new GnssStationCatalogClient(), List.of(bkgSource),
                Clock.fixed(T0, ZoneOffset.UTC));

        try {
            // 1. Sync catalog -> assert deduplication & NRT latency extraction
            CountDownLatch catLatch = new CountDownLatch(1);
            CountDownLatch inspLatch = new CountDownLatch(1);
            CountDownLatch scanLatch = new CountDownLatch(1);
            stationSvc.addListener(new GnssStationListener() {
                @Override
                public void onStationCatalogChanged() { catLatch.countDown(); }
                @Override
                public void onInspectionUpdated(GnssStationAnalysis analysis) {
                    if (analysis != null) { inspLatch.countDown(); }
                }
                @Override
                public void onRegionalScanUpdated(RegionalScanReport report) {
                    if (report != null) { scanLatch.countDown(); }
                }
            });

            stationSvc.refreshNowAsync();
            assertTrue(catLatch.await(10, TimeUnit.SECONDS), "Timed out waiting for catalog sync");
            assertEquals(3, stationSvc.getStations().size());
            GnssReferenceStation sta = stationSvc.findStationById("STA000ESP");
            assertNotNull(sta);
            assertEquals(Set.of(GnssStationNetwork.EUREF, GnssStationNetwork.IGS), sta.networks());
            assertEquals(10, sta.latencyMinutes());

            // 2. Load shared BRDC nav & fetch Station A observations -> assert valid SPP (< 5 m error)
            assertEquals(8, stationSvc.importLocalNavOrSp3File(brdcFile).satelliteCount());
            stationSvc.selectStation("STA000ESP");
            stationSvc.fetchSelectedStationObservationsAsync();
            assertTrue(inspLatch.await(10, TimeUnit.SECONDS),
                    () -> "Timed out waiting for STA000ESP analysis; status=" + stationSvc.getLastStatusMessage());

            GnssStationAnalysis staAnalysis = stationSvc.getLatestAnalysis();
            assertNotNull(staAnalysis);
            assertTrue(staAnalysis.spp().inspectionEpoch().sppAvailable(),
                    "Expected SPP available: " + staAnalysis.spp().inspectionEpoch().statusMessage());
            assertTrue(staAnalysis.spp().inspectionEpoch().horizontalErrorM() < 5.0,
                    "Expected < 5 m horizontal SPP error, got " + staAnalysis.spp().inspectionEpoch().horizontalErrorM());
            assertTrue(staAnalysis.medianCn0DbHz() >= 44.0);

            // 3. Regional scan centered on STB000ESP -> POTENTIAL_INTERFERENCE flagging STB & STC
            stationSvc.scanRegionalInterferenceAsync("STB000ESP");
            assertTrue(scanLatch.await(10, TimeUnit.SECONDS), "Timed out waiting for regional scan");
            RegionalScanReport report = stationSvc.getLatestRegionalReport();
            assertNotNull(report);
            assertEquals(RegionalEventSeverity.POTENTIAL_INTERFERENCE, report.severity(), report.diagnosis());
            assertTrue(report.deltaCn0Matrix().containsKey("STB000ESP"));
            assertTrue(report.deltaCn0Matrix().containsKey("STC000ESP"));

            // 4. Verify JSatTrak API & Layer 3 help documentation
            assertNotNull(JSatTrak.class.getMethod("getGnssStationService"));
            String helpHtml = JSatTrak.buildGnssHelpHtml();
            assertTrue(helpHtml.contains("Ground Reference Stations"), "Expected Layer 3 section in help HTML");
            assertTrue(helpHtml.contains("EUREF") && helpHtml.contains("NOAA") && helpHtml.contains("C/N0"));
        } finally {
            stationSvc.shutdown();
            geometry.shutdown();
            awareness.shutdown();
            server.stop(0);
        }
    }

    private static byte[] buildRinex3Obs(
            String stationId,
            double[] latLonAlt,
            long epochMillis,
            List<GnssEphemerisProvider.CandidateSatelliteWithClock> candidates,
            double cn0DbHz) {
        double[] monEcef = GnssDopCalculator.geodeticToEcef(latLonAlt[0], latLonAlt[1], latLonAlt[2]);
        StringBuilder sb = new StringBuilder();
        sb.append(hdr("     3.04           OBSERVATION DATA    M", "RINEX VERSION / TYPE"));
        sb.append(hdr("JOreSatTrak Test                        20261121 001000 UTC", "PGM / RUN BY / DATE"));
        sb.append(hdr(stationId, "MARKER NAME"));
        sb.append(hdr("", "MARKER NUMBER"));
        sb.append(hdr("GEODETIC", "MARKER TYPE"));
        sb.append(hdr("TESTOBS             TESTAGENCY", "OBSERVER / AGENCY"));
        sb.append(hdr("001                 SEPT POLARX5        5.5.0", "REC # / TYPE / VERS"));
        sb.append(hdr("002                 AOAD/M_T            NONE", "ANT # / TYPE"));
        sb.append(hdr(String.format(Locale.US, "%14.4f%14.4f%14.4f", monEcef[0], monEcef[1], monEcef[2]),
                "APPROX POSITION XYZ"));
        sb.append(hdr(String.format(Locale.US, "%14.4f%14.4f%14.4f", 0.0, 0.0, 0.0), "ANTENNA: DELTA H/E/N"));
        sb.append(hdr("G    2 C1C S1C", "SYS / # / OBS TYPES"));
        sb.append(hdr("E    2 C1X S1X", "SYS / # / OBS TYPES"));
        sb.append(hdr("    30.000", "INTERVAL"));
        sb.append(hdr("  2026    11    21    00    10   18.0000000     GPS", "TIME OF FIRST OBS"));
        sb.append(hdr("", "END OF HEADER"));

        StringBuilder body = new StringBuilder();
        int svCount = 0;
        for (GnssEphemerisProvider.CandidateSatelliteWithClock sat : candidates) {
            double[] azEl = GnssDopCalculator.computeAzEl(
                    latLonAlt[0], latLonAlt[1], latLonAlt[2], sat.satX(), sat.satY(), sat.satZ());
            if (azEl[1] < 20.0) {
                continue;
            }
            double dx = sat.satX() - monEcef[0];
            double dy = sat.satY() - monEcef[1];
            double dz = sat.satZ() - monEcef[2];
            double rho0 = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double tau = rho0 / SPEED_OF_LIGHT;
            double sx = sat.satX() - sat.velX() * tau + OMEGA_EARTH * sat.satY() * tau;
            double sy = sat.satY() - sat.velY() * tau - OMEGA_EARTH * sat.satX() * tau;
            double sz = sat.satZ() - sat.velZ() * tau;
            double geom = Math.sqrt(
                    Math.pow(sx - monEcef[0], 2) + Math.pow(sy - monEcef[1], 2) + Math.pow(sz - monEcef[2], 2));
            double tropo = GnssSppSolver.saastamoinenTropoMeters(azEl[1], latLonAlt[2]);
            double pr = geom + SPEED_OF_LIGHT * (12.0e-6 - sat.clockBiasSec() + sat.tgdSec()) + tropo;
            body.append(String.format(Locale.US, "%s%14.3f  %14.3f  %n", sat.prnId(), pr, cn0DbHz));
            svCount++;
        }
        sb.append(String.format(Locale.US, "> 2026 11 21 00 10 18.0000000  0 %2d%n", svCount));
        sb.append(body);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String hdr(String left, String label) {
        return String.format(Locale.US, "%-60s%s%n", left, label);
    }

    private static GnssCatalogEntry cat(String prn, GnssConstellation c, int n) {
        return new GnssCatalogEntry(prn, c, n, prn, 40000 + n, "2026-001A", prn, GnssOrbitClass.MEO, "", "", "");
    }

    private static String buildBrdcNavFixture() {
        StringBuilder sb = new StringBuilder();
        sb.append("     3.04           N: GNSS NAV DATA    M: Mixed            RINEX VERSION / TYPE\n");
        sb.append("BKG                                     20261121 001500 UTC PGM / RUN BY / DATE\n");
        sb.append("                                                            END OF HEADER\n");
        String[] prns = {"G01", "G02", "G03", "G04", "G05", "G06", "E11", "E12"};
        double[] omega0 = {0.10, 0.90, 1.80, 2.70, -0.60, -1.50, 0.40, 2.20};
        double[] m0 = {0.40, 0.90, 1.40, 0.20, 0.70, 1.10, 0.55, 1.25};
        double[] af0 = {1.2e-4, -0.8e-4, 2.1e-4, 0.5e-4, -1.4e-4, 0.9e-4, 1.1e-4, -0.6e-4};
        for (int i = 0; i < prns.length; i++) {
            String prn = prns[i];
            boolean gal = prn.startsWith("E");
            String sqrtA = gal ? "5.440610000000D+03" : "5.153650000000D+03";
            String src = gal ? "5.170000000000D+02" : "0.000000000000D+00";
            String sysLine = gal ? "1.000000000000D+00" : "0.000000000000D+00";
            String inc = gal ? "9.770000000000D-01" : "9.600000000000D-01";
            String af0Str = String.format(Locale.US, "%19.12E", af0[i]).replace('E', 'D');
            String omStr = String.format(Locale.US, "%19.12E", omega0[i]).replace('E', 'D');
            String m0Str = String.format(Locale.US, "%19.12E", m0[i]).replace('E', 'D');
            sb.append(String.format(Locale.US,
                    "%s 2026 11 21 00 00 00%s 0.000000000000D+00 0.000000000000D+00%n", prn, af0Str));
            sb.append("     1.000000000000D+01 0.000000000000D+00 0.000000000000D+00" + m0Str + "\n");
            sb.append("     0.000000000000D+00 0.000000000000D+00 0.000000000000D+00 " + sqrtA + "\n");
            sb.append("     5.184000000000D+05 0.000000000000D+00" + omStr + " 0.000000000000D+00\n");
            sb.append("     " + inc + " 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n");
            sb.append("     0.000000000000D+00 " + src + " 2.445000000000D+03 " + sysLine + "\n");
            sb.append("     2.000000000000D+00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n");
            sb.append("     5.184000000000D+05 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n");
        }
        return sb.toString();
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(raw);
        }
        return bos.toByteArray();
    }

    private static void respond(HttpExchange ex, int status, String contentType, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}

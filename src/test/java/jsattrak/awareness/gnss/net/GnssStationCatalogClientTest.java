package jsattrak.awareness.gnss.net;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jsattrak.awareness.config.AuthMode;
import jsattrak.awareness.config.AwarenessSettings;
import jsattrak.awareness.config.AwarenessSettingsStore;
import jsattrak.awareness.config.DataSourceConfig;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.stations.GnssReferenceStation;
import jsattrak.awareness.gnss.stations.GnssStationNetwork;
import jsattrak.awareness.gnss.stations.GnssStationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class GnssStationCatalogClientTest {

    @Test
    void fetchesAndDeduplicatesM3gIgsEurefNoaaCatalogAndProbesBkgNrtLatency(@TempDir Path tmpDir) throws Exception {
        String epnJson = """
                [
                  {
                    "id": "MADR00ESP",
                    "name": "Madrid Deep Space Station",
                    "country": "ESP",
                    "lat": 40.4292,
                    "lon": -4.2497,
                    "height": 829.5,
                    "receiver_type": "SEPT POLARX5",
                    "receiver_firmware": "5.5.0",
                    "antenna_type": "AOAD/M_T",
                    "radome_type": "",
                    "constellations": ["GPS", "GALILEO", "GLONASS", "BEIDOU"]
                  },
                  {
                    "id": "BRUX00BEL",
                    "name": "Brussels ROB",
                    "country": "BEL",
                    "lat": 50.7981,
                    "lon": 4.3586,
                    "height": 158.3,
                    "receiver_type": "SEPT POLARX5TR",
                    "antenna_type": "JAVRINGANT_DM",
                    "radome_type": "SCIS",
                    "constellations": ["G", "E", "R", "C"]
                  }
                ]
                """;
        String igsJson = """
                {
                  "stations": [
                    {
                      "id": "MADR00ESP",
                      "name": "Madrid Deep Space Station",
                      "country": "ESP",
                      "x": 4849202.3,
                      "y": -360328.9,
                      "z": 4114913.2,
                      "lat": 40.4292,
                      "lon": -4.2497,
                      "height": 829.5,
                      "receiver_type": "SEPT POLARX5",
                      "antenna_type": "AOAD/M_T",
                      "radome_type": "NONE",
                      "constellations": ["G", "E", "R", "C"]
                    },
                    {
                      "id": "ALGO00CAN",
                      "name": "Algonquin Park",
                      "country": "CAN",
                      "lat": 45.9558,
                      "lon": -78.0714,
                      "height": 200.9,
                      "receiver_type": "TPS NET-G5",
                      "antenna_type": "AOAD/M_T",
                      "radome_type": "NONE",
                      "constellations": ["G", "R"]
                    }
                  ]
                }
                """;
        String noaaTable = """
                # Site  Name                      City/State     Status           Lat        Lon        EllHt
                AB02    Nikolski_AK_2005          Nikolski, AK   Operational      52.9718   -168.8542   73.4
                XX99    Legacy_Site_Decomm        Retired, TX    Decommissioned   30.2500   -97.7500    150.0
                """;
        String noaaSiteLog = """
                2.   Site Location Information
                     X coordinate (m)       : -3803528.100
                     Y coordinate (m)       : -749190.200
                     Z coordinate (m)       : 5068180.500
                     Latitude (N is +)      : +525818.48
                     Longitude (E is +)     : -1685115.12
                     Elevation (m,ellips.)  : 73.4

                3.   GNSS Receiver Information
                3.1  Receiver Type            : TRIMBLE NETR9
                     Satellite System         : GPS+GLO
                     Serial Number            : 5032K69001
                     Firmware Version         : 5.45

                4.   GNSS Antenna Information
                4.1  Antenna Type             : TRM59800.00     SCIS
                     Radome Type              : SCIS
                """;
        String bkgDirHtml = """
                <html><body><pre>
                <a href="MADR00ESP_R_20263291900_01H_30S_MO.crx.gz">MADR00ESP_R_20263291900_01H_30S_MO.crx.gz</a>  25-Nov-2026 20:01  420K
                </pre></body></html>
                """;

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/m3g/epn", ex -> respondText(ex, 200, "application/json", epnJson));
        server.createContext("/m3g/igs", ex -> respondText(ex, 200, "application/json", igsJson));
        server.createContext("/noaa/All_CORS.txt", ex -> respondText(ex, 200, "text/plain", noaaTable));
        server.createContext("/noaa/station_log/ab02.log.txt", ex -> respondText(ex, 200, "text/plain", noaaSiteLog));
        server.createContext("/bkg/igs/nrt/329/19/", ex -> respondText(ex, 200, "text/html", bkgDirHtml));
        server.createContext("/bkg/igs/nrt/329/20/", ex -> respondText(ex, 200, "text/html", "<html></html>"));
        server.start();

        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            List<DataSourceConfig> catSources = List.of(
                    new DataSourceConfig("epn", DataSourceConfig.FeedCategory.GNSS_STATION_CATALOG, "EUREF",
                            "EPN", base + "/m3g/epn", true, 1, AuthMode.NONE, "", "", "", 1440, false),
                    new DataSourceConfig("igs", DataSourceConfig.FeedCategory.GNSS_STATION_CATALOG, "IGS",
                            "IGS", base + "/m3g/igs", true, 2, AuthMode.NONE, "", "", "", 1440, false),
                    new DataSourceConfig("noaa", DataSourceConfig.FeedCategory.GNSS_STATION_CATALOG, "NOAA_CORS",
                            "NOAA", base + "/noaa/All_CORS.txt", true, 3, AuthMode.NONE, "", "", "", 1440, false)
            );
            List<DataSourceConfig> arcSources = List.of(
                    new DataSourceConfig("bkg-igs", DataSourceConfig.FeedCategory.GNSS_OBSERVATION_ARCHIVE, "IGS",
                            "BKG IGS", base + "/bkg/igs/nrt/", true, 1, AuthMode.NONE, "", "", "", 15, false)
            );

            long nowMillis = Instant.parse("2026-11-25T20:20:00Z").toEpochMilli();
            GnssStationCatalogClient client = new GnssStationCatalogClient();
            GnssStationCatalogClient.CatalogSyncResult result =
                    client.fetchMergedCatalog(catSources, arcSources, nowMillis);

            // 5 distinct stations: MADR00ESP (merged IGS+EUREF), BRUX00BEL, ALGO00CAN, AB02, XX99
            assertEquals(5, result.stations().size());
            GnssReferenceStation madr = result.stations().stream()
                    .filter(s -> "MADR00ESP".equals(s.stationId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(Set.of(GnssStationNetwork.EUREF, GnssStationNetwork.IGS), madr.networks());
            assertEquals("NONE", madr.radomeCode());
            assertEquals(GnssStationStatus.ONLINE, madr.status());
            assertEquals(20, madr.latencyMinutes());
            assertEquals(Instant.parse("2026-11-25T20:00:00Z").toEpochMilli(), madr.lastObsEpochMillis());

            GnssReferenceStation xx99 = result.stations().stream()
                    .filter(s -> "XX99".equals(s.shortCode()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(GnssStationStatus.OFFLINE, xx99.status());

            GnssReferenceStation ab02 = result.stations().stream()
                    .filter(s -> "AB02".equals(s.shortCode()))
                    .findFirst()
                    .orElseThrow();
            GnssReferenceStation enrichedAb02 = client.enrichNoaaStationHardware(ab02, base + "/noaa/station_log/");
            assertEquals("TRIMBLE NETR9", enrichedAb02.receiverModel());
            assertTrue(enrichedAb02.antennaModel().contains("TRM59800.00"));
            assertTrue(enrichedAb02.supportedConstellations().contains(GnssConstellation.GPS));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void loadsBundledSeedAndMergesLayer3DefaultSourcesIntoLegacySettings(@TempDir Path tmpDir) throws Exception {
        List<GnssReferenceStation> seed = GnssStationCatalogClient.loadBundledSeed();
        assertTrue(seed.size() >= 30, "Seed must contain >= 30 global stations");
        assertTrue(seed.stream().allMatch(s -> s.status() == GnssStationStatus.UNKNOWN));

        Path settingsFile = tmpDir.resolve("awareness-settings.json");
        String legacyJson = """
                {
                  "schemaVersion": 2,
                  "autoFetchOnStartup": false,
                  "defaultHorizonDays": 7,
                  "gnssFeedEnabled": true,
                  "sources": [
                    {
                      "id": "custom-launch",
                      "feedCategory": "LAUNCH",
                      "providerScope": "",
                      "displayName": "Custom Launch",
                      "endpointUrl": "https://example.invalid/launch",
                      "enabled": true,
                      "priority": 1,
                      "authMode": "NONE",
                      "refreshIntervalMinutes": 15,
                      "syncFixedPadsToGroundStations": false
                    },
                    {
                      "id": "igs-sat-metadata",
                      "feedCategory": "GNSS_CATALOG",
                      "providerScope": "",
                      "displayName": "IGS Satellite Metadata (SINEX)",
                      "endpointUrl": "https://files.igs.org/pub/station/general/igs_satellite_metadata.snx",
                      "enabled": true,
                      "priority": 1,
                      "authMode": "NONE",
                      "refreshIntervalMinutes": 1440,
                      "syncFixedPadsToGroundStations": false
                    }
                  ]
                }
                """;
        Files.writeString(settingsFile, legacyJson, StandardCharsets.UTF_8);

        AwarenessSettings loaded = new AwarenessSettingsStore(settingsFile).load();
        assertTrue(loaded.getSources().stream().anyMatch(s -> "custom-launch".equals(s.getId())));
        assertTrue(loaded.getSources().stream()
                .anyMatch(s -> s.getFeedCategory() == DataSourceConfig.FeedCategory.GNSS_STATION_CATALOG));
        assertTrue(loaded.getSources().stream()
                .anyMatch(s -> s.getFeedCategory() == DataSourceConfig.FeedCategory.GNSS_OBSERVATION_ARCHIVE));
        assertEquals(250.0, loaded.getGnssRegionalScanRadiusKm(), 1e-6);
        assertEquals(6, loaded.getGnssRegionalScanMaxStations());
        assertEquals(10.0, loaded.getGnssStationMaskDeg(), 1e-6);
        Map<String, Boolean> nets = loaded.getGnssStationNetworksEnabled();
        assertTrue(nets.getOrDefault("IGS", false));
        assertTrue(nets.getOrDefault("EUREF", false));
        assertTrue(nets.getOrDefault("NOAA_CORS", false));
    }

    private static void respondText(com.sun.net.httpserver.HttpExchange ex, int code, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}

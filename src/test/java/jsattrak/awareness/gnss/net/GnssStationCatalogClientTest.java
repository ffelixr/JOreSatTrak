package jsattrak.awareness.gnss.net;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
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

    @Test
    void deduplicationPreservesSurveyedEcefAndParsesEcefOnlyJsonStations(@TempDir Path tmpDir) throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        String ecefOnlyJson = """
                [
                  {
                    "id": "BRUX00BEL",
                    "name": "Brussels ECEF Only",
                    "country": "BEL",
                    "x": 4027881.123,
                    "y": 307045.456,
                    "z": 4919498.789,
                    "receiver_type": "SEPT POLARX5",
                    "satelliteSystem": "G,E"
                  }
                ]
                """;
        server.createContext("/ecef.json", ex -> respondText(ex, 200, "application/json", ecefOnlyJson));
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            DataSourceConfig cat = new DataSourceConfig(
                    "ecef-cat", DataSourceConfig.FeedCategory.GNSS_STATION_CATALOG, "IGS", "ECEF JSON",
                    base + "/ecef.json", true, 1, AuthMode.NONE, "", "", "", 1440, false);
            GnssStationCatalogClient.CatalogSyncResult res = new GnssStationCatalogClient().fetchMergedCatalog(
                    List.of(cat), List.of(), Instant.parse("2026-11-25T19:00:00Z").toEpochMilli());
            GnssReferenceStation brux = res.stations().stream()
                    .filter(s -> "BRUX00BEL".equals(s.stationId())).findFirst().orElseThrow();
            assertTrue(brux.latDeg() > 50.0 && brux.latDeg() < 51.5);
            assertTrue(brux.lonDeg() > 4.0 && brux.lonDeg() < 5.0);
        } finally {
            server.stop(0);
        }

        // 4-char derived geodetic merges with 9-char surveyed XYZ + later 4-char alias -> surveyed XYZ wins cleanly
        GnssReferenceStation derivedFirst = new GnssReferenceStation(
                "AB02", "AB02", "Derived", "USA", GnssStationNetwork.NOAA_CORS,
                EnumSet.of(GnssStationNetwork.NOAA_CORS), 40.4292, -4.2497, 829.5, null,
                "", "", "", "", EnumSet.of(GnssConstellation.GPS), List.of(), 0L, -1, Double.NaN, GnssStationStatus.UNKNOWN, "");
        double[] surveyedXyz = {4849202.333, -360328.999, 4114913.222};
        GnssReferenceStation surveyedSecond = new GnssReferenceStation(
                "AB0200USA", "AB02", "Surveyed", "USA", GnssStationNetwork.IGS,
                EnumSet.of(GnssStationNetwork.IGS), 40.4292, -4.2497, 829.5, surveyedXyz,
                "TRIMBLE NETR9", "", "", "", EnumSet.of(GnssConstellation.GPS, GnssConstellation.GALILEO),
                List.of(), 0L, 10, Double.NaN, GnssStationStatus.ONLINE, "");
        GnssReferenceStation aliasThird = new GnssReferenceStation(
                "AB02", "AB02", "Alias", "USA", GnssStationNetwork.EUREF,
                EnumSet.of(GnssStationNetwork.EUREF), 40.4292, -4.2497, 829.5, null,
                "", "", "", "", EnumSet.of(GnssConstellation.GLONASS), List.of(), 0L, 5, Double.NaN, GnssStationStatus.ONLINE, "");

        List<GnssReferenceStation> merged = GnssStationCatalogClient.deduplicateStations(
                List.of(derivedFirst, surveyedSecond, aliasThird));
        assertEquals(1, merged.size());
        GnssReferenceStation result = merged.get(0);
        assertEquals("AB0200USA", result.stationId());
        assertEquals(surveyedXyz[0], result.monumentEcef()[0], 1e-3);
        assertEquals(3, result.networks().size());
    }

    @Test
    void parsesLiveNoaaUfcorsS3CoordsM3gGeoJsonAndMigratesRetiredAllCorsUrl(@TempDir Path tmpDir)
            throws Exception {
        // 1. Default settings must use UFCORS + S3 coords rather than retired All_CORS.txt
        AwarenessSettings defaults = AwarenessSettings.createDefaults();
        assertTrue(defaults.getSources().stream().noneMatch(
                s -> s.getEndpointUrl() != null && s.getEndpointUrl().contains("All_CORS.txt")));
        assertTrue(defaults.getSources().stream().anyMatch(
                s -> s.getFeedCategory() == DataSourceConfig.FeedCategory.GNSS_STATION_CATALOG
                        && s.getEndpointUrl().contains("UFCORS_site_list.txt")));
        assertTrue(defaults.getSources().stream().anyMatch(
                s -> s.getFeedCategory() == DataSourceConfig.FeedCategory.GNSS_STATION_CATALOG
                        && s.getEndpointUrl().contains("itrf2020_geo.comp.txt")));

        // 2. UFCORS pipe-delimited + AWS S3 itrf2020_geo.comp.txt parsing and height merge
        String ufcors = """
                1LSU|GPS:GAL:GLO|2003-04-23 00:00:00.0|null|Louisiana State University|30|60|Operational|N302426.72859|W0911048.94791|LASU
                AB02|GPS:GAL|2011-04-05 00:00:00.0|null|UNAVCO-PBO|15|1440|Operational|N525814.17078|W1685116.81658|UNAVPB
                AB12|GPS|2010-03-17 00:00:00.0|null|UNAVCO-PBO|15|1440|Non-Operational|N585702.85433|W1614446.54662|UNAVPB
                """;
        List<GnssReferenceStation> ufcorsStations = GnssStationCatalogClient.parseCatalogPayload(
                ufcors, GnssStationNetwork.NOAA_CORS);
        assertEquals(3, ufcorsStations.size());
        GnssReferenceStation lsu = ufcorsStations.get(0);
        assertEquals("1LSU", lsu.stationId());
        assertEquals(30.4074246, lsu.latDeg(), 1e-5);
        assertEquals(-91.1802633, lsu.lonDeg(), 1e-5);
        assertEquals(EnumSet.of(GnssConstellation.GPS, GnssConstellation.GALILEO, GnssConstellation.GLONASS),
                lsu.supportedConstellations());
        assertEquals(GnssStationStatus.UNKNOWN, lsu.status());
        assertEquals(GnssStationStatus.OFFLINE, ufcorsStations.get(2).status());

        String s3Coord = """
                November 25, 2026

                ITRF2020 Geod. CORS Pos. Ant. Ref. Point (ARP) [GRS80 Ellip.] Computed Vels.
                ____________________________________________________________________________________________________________________
                 SITE   EPOCH       Latitude          Longitude     Ellip. Ht.   Vn      Ve      Vu    Ctry.  State      Site
                ____________________________________________________________________________________________________________________
                AB02  2020.00  52 58 14.17078 N  168 51 16.81658 W   192.814   -19.4    -9.1     1.8    US      AK      Operational
                """;
        List<GnssReferenceStation> s3Stations = GnssStationCatalogClient.parseCatalogPayload(
                s3Coord, GnssStationNetwork.NOAA_CORS);
        assertEquals(1, s3Stations.size());
        assertEquals(192.814, s3Stations.get(0).altMeters(), 1e-3);

        List<GnssReferenceStation> mergedNoaa = GnssStationCatalogClient.deduplicateStations(
                List.of(ufcorsStations.get(1), s3Stations.get(0)));
        assertEquals(1, mergedNoaa.size());
        assertEquals(192.814, mergedNoaa.get(0).altMeters(), 1e-3);
        assertTrue(mergedNoaa.get(0).supportedConstellations().contains(GnssConstellation.GALILEO));

        // 3. Live M3G GeoJSON feature properties mapping
        String m3gGeoJson = """
                {
                  "features": [
                    {
                      "type": "Feature",
                      "properties": {
                        "GNSS Station ID": "AAER00FRA",
                        "Country/Region": "FRA",
                        "City": "CHARLEVILLE-MEZIERES",
                        "Lat/Lon/Height": "49.78247/4.64265/199.3",
                        "GNSS Antenna (Current)": "TRM57971.00     NONE",
                        "GNSS Receiver (Current)": "SEPT POLARX5",
                        "Satellite Constellation (Current)": "GPS+GLO+GAL+BDS+SBAS",
                        "Station Status": "Active"
                      }
                    }
                  ]
                }
                """;
        List<GnssReferenceStation> m3gStations = GnssStationCatalogClient.parseCatalogPayload(
                m3gGeoJson, GnssStationNetwork.EUREF);
        assertEquals(1, m3gStations.size());
        GnssReferenceStation aaer = m3gStations.get(0);
        assertEquals("AAER00FRA", aaer.stationId());
        assertEquals("CHARLEVILLE-MEZIERES", aaer.siteName());
        assertEquals("FRA", aaer.countryCode());
        assertEquals(49.78247, aaer.latDeg(), 1e-5);
        assertEquals(4.64265, aaer.lonDeg(), 1e-5);
        assertEquals(199.3, aaer.altMeters(), 1e-2);
        assertEquals("SEPT POLARX5", aaer.receiverModel());
        assertEquals("TRM57971.00", aaer.antennaModel());
        assertEquals("NONE", aaer.radomeCode());
        assertEquals(4, aaer.supportedConstellations().size());

        // 4. Schema v3 store auto-migrates retired All_CORS.txt (both with and without UFCORS present)
        Path fileWithUf = tmpDir.resolve("settings-with-ufcors.json");
        Files.writeString(fileWithUf, """
                {
                  "schemaVersion": 3,
                  "autoFetchOnStartup": false,
                  "defaultHorizonDays": 7,
                  "sources": [
                    {
                      "id": "noaa-cors-stations",
                      "feedCategory": "GNSS_STATION_CATALOG",
                      "providerScope": "NOAA_CORS",
                      "displayName": "NOAA CORS Station List",
                      "endpointUrl": "https://geodesy.noaa.gov/CORS/All_CORS.txt",
                      "enabled": true,
                      "priority": 3,
                      "authMode": "NONE",
                      "refreshIntervalMinutes": 1440
                    },
                    {
                      "id": "noaa-ufcors-stations",
                      "feedCategory": "GNSS_STATION_CATALOG",
                      "providerScope": "NOAA_CORS",
                      "displayName": "NOAA CORS Site List (UFCORS Fallback)",
                      "endpointUrl": "https://geodesy.noaa.gov/CORS/UFCORS_site_list.txt",
                      "enabled": true,
                      "priority": 4,
                      "authMode": "NONE",
                      "refreshIntervalMinutes": 1440
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);
        AwarenessSettings migratedWithUf = new AwarenessSettingsStore(fileWithUf).load();
        assertTrue(migratedWithUf.getSources().stream().noneMatch(
                s -> s.getEndpointUrl().contains("All_CORS.txt")));
        assertTrue(migratedWithUf.getSources().stream().anyMatch(
                s -> s.getEndpointUrl().contains("itrf2020_geo.comp.txt")));
        assertTrue(migratedWithUf.getSources().stream().anyMatch(
                s -> s.getEndpointUrl().contains("UFCORS_site_list.txt")));

        Path fileOnlyAllCors = tmpDir.resolve("settings-only-allcors.json");
        Files.writeString(fileOnlyAllCors, """
                {
                  "schemaVersion": 3,
                  "autoFetchOnStartup": false,
                  "defaultHorizonDays": 7,
                  "sources": [
                    {
                      "id": "noaa-cors-stations",
                      "feedCategory": "GNSS_STATION_CATALOG",
                      "providerScope": "NOAA_CORS",
                      "displayName": "NOAA CORS Station List",
                      "endpointUrl": "https://geodesy.noaa.gov/CORS/All_CORS.txt",
                      "enabled": true,
                      "priority": 3,
                      "authMode": "NONE",
                      "refreshIntervalMinutes": 1440
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);
        AwarenessSettings migratedSingle = new AwarenessSettingsStore(fileOnlyAllCors).load();
        assertEquals("https://geodesy.noaa.gov/CORS/UFCORS_site_list.txt",
                migratedSingle.getSources().get(0).getEndpointUrl());

        // 5. Merging stations with empty supportedConstellations (e.g., sparse M3G metadata) must not throw
        GnssReferenceStation emptyConstsA = new GnssReferenceStation(
                "TEST00EUR", "TEST", "Test A", "EUR", GnssStationNetwork.EUREF,
                EnumSet.of(GnssStationNetwork.EUREF), 48.0, 2.0, 100.0, null,
                "", "", "", "", Set.of(), List.of(), 0L, -1, Double.NaN, GnssStationStatus.UNKNOWN, "");
        GnssReferenceStation emptyConstsB = new GnssReferenceStation(
                "TEST00EUR", "TEST", "Test B", "EUR", GnssStationNetwork.IGS,
                EnumSet.of(GnssStationNetwork.IGS), 48.0, 2.0, 100.0, null,
                "POLARX5", "", "", "", Set.of(), List.of(), 0L, -1, Double.NaN, GnssStationStatus.UNKNOWN, "");
        List<GnssReferenceStation> emptyConstsMerged = GnssStationCatalogClient.deduplicateStations(
                List.of(emptyConstsA, emptyConstsB));
        assertEquals(1, emptyConstsMerged.size());
        assertEquals(2, emptyConstsMerged.get(0).networks().size());
        assertTrue(emptyConstsMerged.get(0).supportedConstellations().isEmpty());
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

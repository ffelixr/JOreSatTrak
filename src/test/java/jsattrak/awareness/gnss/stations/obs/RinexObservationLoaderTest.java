package jsattrak.awareness.gnss.stations.obs;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;
import jsattrak.awareness.config.AuthMode;
import jsattrak.awareness.config.DataSourceConfig;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.stations.GnssReferenceStation;
import jsattrak.awareness.gnss.stations.GnssStationNetwork;
import jsattrak.awareness.gnss.stations.GnssStationStatus;
import jsattrak.gui.JSatTrak;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class RinexObservationLoaderTest {

    private static String hdr(String content, String label) {
        return String.format(Locale.US, "%-60s%s\n", content, label);
    }

    private static String obs(double val, char lli, char ssi) {
        return String.format(Locale.US, "%14.3f%c%c", val, lli, ssi);
    }

    private static String buildRinex3Header() {
        return hdr("     3.04           OBSERVATION DATA    M", "RINEX VERSION / TYPE")
                + hdr("TEST                TEST                20261125 190000 UTC", "PGM / RUN BY / DATE")
                + hdr("MADR00ESP", "MARKER NAME")
                + hdr("", "MARKER NUMBER")
                + hdr("GEODETIC", "MARKER TYPE")
                + hdr("TESTOBS             TESTAGENCY", "OBSERVER / AGENCY")
                + hdr("001                 SEPT POLARX5        5.5.0", "REC # / TYPE / VERS")
                + hdr("002                 AOAD/M_T            NONE", "ANT # / TYPE")
                + hdr(String.format(Locale.US, "%14.4f%14.4f%14.4f", 4849202.3, -360328.9, 4114913.2),
                        "APPROX POSITION XYZ")
                + hdr(String.format(Locale.US, "%14.4f%14.4f%14.4f", 0.0, 0.0, 0.0), "ANTENNA: DELTA H/E/N")
                + hdr("G    6 C1C L1C S1C C2W L2W S2W", "SYS / # / OBS TYPES")
                + hdr("E    3 C1X L1X S1X", "SYS / # / OBS TYPES")
                + hdr("    30.000", "INTERVAL")
                + hdr("  2026    11    25    19    00    0.0000000     GPS", "TIME OF FIRST OBS")
                + hdr("", "END OF HEADER");
    }

    private static String buildRinex3Fixture() {
        return buildRinex3Header()
                + "> 2026 11 25 19 00  0.0000000  0  2\n"
                + "G01" + obs(21234567.890, ' ', '8') + obs(111600000.123, ' ', '8') + obs(46.500, ' ', ' ')
                + obs(21234570.111, ' ', '7') + obs(86961000.456, ' ', '7') + obs(43.000, ' ', ' ') + "\n"
                + "E11" + obs(23234567.000, ' ', '7') + obs(122098000.250, '1', '7') + obs(44.200, ' ', ' ') + "\n"
                + "> 2026 11 25 19 00 30.0000000  0  2\n"
                + "G01" + obs(21235100.000, ' ', '8') + obs(111602800.000, ' ', '8') + obs(46.800, ' ', ' ')
                + obs(21235102.200, ' ', '7') + obs(86963180.000, ' ', '7') + obs(43.200, ' ', ' ') + "\n"
                + "E11" + obs(23235000.000, ' ', '7') + obs(122100270.000, ' ', '7') + obs(44.600, ' ', ' ') + "\n";
    }

    private static String buildCrx3Fixture() {
        return hdr("3.0                 COMPACT RINEX FORMAT", "CRINEX VERS   / TYPE")
                + hdr("RNX2CRX ver.4.0.7                       25-Nov-26 19:00", "CRINEX PROG / DATE")
                + buildRinex3Header()
                + "> 2026 11 25 19 00  0.0000000  0  2      G01E11\n"
                + "\n"
                + "0&21234567890 0&111600000123 0&46500 0&21234570111 0&86961000456 0&43000  8 8   7 7   \n"
                + "0&23234567000 0&122098000250 0&44200  717  \n";
    }

    private static String buildRinex2SsiFixture() {
        return hdr("     2.11           OBSERVATION DATA    G (GPS)", "RINEX VERSION / TYPE")
                + hdr("TEST                TEST                20261125 190000 UTC", "PGM / RUN BY / DATE")
                + hdr("AB02", "MARKER NAME")
                + hdr("001", "MARKER NUMBER")
                + hdr("TESTOBS             TESTAGENCY", "OBSERVER / AGENCY")
                + hdr("001                 TRIMBLE NETR9       5.45", "REC # / TYPE / VERS")
                + hdr("002                 TRM59800.00         SCIS", "ANT # / TYPE")
                + hdr(String.format(Locale.US, "%14.4f%14.4f%14.4f", -3803528.1, -749190.2, 5068180.5),
                        "APPROX POSITION XYZ")
                + hdr(String.format(Locale.US, "%14.4f%14.4f%14.4f", 0.0, 0.0, 0.0), "ANTENNA: DELTA H/E/N")
                + hdr("     2    C1    L1", "# / TYPES OF OBSERV")
                + hdr("    30.0000", "INTERVAL")
                + hdr("  2026    11    25    19    00    0.0000000     GPS", "TIME OF FIRST OBS")
                + hdr("", "END OF HEADER")
                + " 26 11 25 19 00  0.0000000  0  1G03\n"
                + obs(21456789.123, ' ', '7') + obs(112756789.123, ' ', '7') + "\n";
    }

    @Test
    void parsesPlainGzipHatanakaAndSsiFallbackRinexFilesAndRejectsCorruptPayload(@TempDir Path tmpDir)
            throws Exception {
        JSatTrak.initOrekitData();

        byte[] rnx3Bytes = buildRinex3Fixture().getBytes(StandardCharsets.UTF_8);
        RinexObservationData plain = RinexObservationLoader.parse(rnx3Bytes, "MADR00ESP.rnx");
        assertEquals("MADR00ESP", plain.header().markerName());
        assertEquals("SEPT POLARX5", plain.header().receiverType());
        assertTrue(plain.header().antennaType().contains("AOAD/M_T"));
        assertEquals(2, plain.epochs().size());
        RinexObservationData.SatelliteObsSample g01 = plain.epochs().get(0).satellites().get("G01");
        assertNotNull(g01);
        assertEquals(46.5, g01.primaryCn0DbHz(GnssConstellation.GPS), 1e-6);
        assertFalse(g01.cn0FromSsiFallback());
        RinexObservationData.SatelliteObsSample e11 = plain.epochs().get(0).satellites().get("E11");
        assertNotNull(e11);
        assertTrue(e11.hasLossOfLock());

        RinexObservationData gz = RinexObservationLoader.parse(gzip(rnx3Bytes), "MADR00ESP.rnx.gz");
        assertEquals(plain.epochs().size(), gz.epochs().size());
        assertEquals(46.5, gz.epochs().get(0).satellites().get("G01").primaryCn0DbHz(GnssConstellation.GPS), 1e-6);

        RinexObservationData crx = RinexObservationLoader.parse(
                gzip(buildCrx3Fixture().getBytes(StandardCharsets.UTF_8)),
                "MADR00ESP_R_20263291900_01H_30S_MO.crx.gz");
        assertEquals(1, crx.epochs().size());
        assertEquals(46.5, crx.epochs().get(0).satellites().get("G01").primaryCn0DbHz(GnssConstellation.GPS), 1e-6);
        assertTrue(crx.epochs().get(0).satellites().get("E11").hasLossOfLock());

        RinexObservationData rnx2 = RinexObservationLoader.parse(
                gzip(buildRinex2SsiFixture().getBytes(StandardCharsets.UTF_8)), "ab023290.26o.gz");
        RinexObservationData.SatelliteObsSample g03 = rnx2.epochs().get(0).satellites().get("G03");
        assertNotNull(g03);
        assertEquals(42.0, g03.primaryCn0DbHz(GnssConstellation.GPS), 1e-6);
        assertTrue(g03.cn0FromSsiFallback());

        assertThrows(IOException.class, () -> RinexObservationLoader.parse(new byte[0], "empty.rnx"));
        assertThrows(IOException.class, () -> RinexObservationLoader.parse(
                "NOT A RINEX FILE".getBytes(StandardCharsets.UTF_8), "bad.rnx"));
    }

    @Test
    void fetchesFromBkgNrtOrHistoricalNoaaS3XmlAndCachesToDisk(@TempDir Path tmpDir) throws Exception {
        JSatTrak.initOrekitData();
        byte[] gzRnx3 = gzip(buildRinex3Fixture().getBytes(StandardCharsets.UTF_8));
        byte[] gzRnx2 = gzip(buildRinex2SsiFixture().getBytes(StandardCharsets.UTF_8));

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String nrtHtml = "<a href=\"MADR00ESP_R_20263291900_01H_30S_MO.rnx.gz\">file</a>";
        String histHtml = "<a href=\"MADR00ESP_R_20251000000_01D_30S_MO.rnx.gz\">hist</a>";
        String s3Xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ListBucketResult>
                  <Contents><Key>rinex/2026/329/ab02/ab023290.26o.gz</Key><LastModified>2026-11-25T19:15:00Z</LastModified></Contents>
                </ListBucketResult>
                """;
        server.createContext("/bkg/nrt/329/19/", ex -> respondBytes(ex, 200, "text/html", nrtHtml.getBytes(StandardCharsets.UTF_8)));
        server.createContext("/bkg/nrt/329/18/", ex -> respondBytes(ex, 404, "text/html", new byte[0]));
        server.createContext("/bkg/nrt/329/19/MADR00ESP_R_20263291900_01H_30S_MO.rnx.gz",
                ex -> respondBytes(ex, 200, "application/gzip", gzRnx3));
        server.createContext("/bkg/obs/2025/100/", ex -> respondBytes(ex, 200, "text/html", histHtml.getBytes(StandardCharsets.UTF_8)));
        server.createContext("/bkg/obs/2025/100/MADR00ESP_R_20251000000_01D_30S_MO.rnx.gz",
                ex -> respondBytes(ex, 200, "application/gzip", gzRnx3));
        server.createContext("/noaa/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.endsWith(".26o.gz")) {
                respondBytes(ex, 200, "application/gzip", gzRnx2);
            } else {
                respondBytes(ex, 200, "application/xml", s3Xml.getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();

        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            GnssObservationDiskCache cache = new GnssObservationDiskCache(tmpDir.resolve("obs-cache"));
            List<DataSourceConfig> archives = List.of(
                    new DataSourceConfig("bkg-igs", DataSourceConfig.FeedCategory.GNSS_OBSERVATION_ARCHIVE, "IGS",
                            "BKG", base + "/bkg/nrt/", true, 1, AuthMode.NONE, "", "", "", 15, false),
                    new DataSourceConfig("noaa-s3", DataSourceConfig.FeedCategory.GNSS_OBSERVATION_ARCHIVE, "NOAA_CORS",
                            "NOAA", base + "/noaa/", true, 2, AuthMode.NONE, "", "", "", 15, false)
            );
            GnssReferenceStation madr = new GnssReferenceStation(
                    "MADR00ESP", "MADR", "Madrid", "ESP", GnssStationNetwork.IGS,
                    EnumSet.of(GnssStationNetwork.IGS), 40.4292, -4.2497, 829.5, null,
                    "", "", "", "", EnumSet.of(GnssConstellation.GPS), List.of(), 0L, -1, Double.NaN,
                    GnssStationStatus.ONLINE, "");

            BkgHourlyRinexSource bkgSource = new BkgHourlyRinexSource(() -> archives, cache);
            assertTrue(bkgSource.supportsNetwork(GnssStationNetwork.IGS));
            assertFalse(bkgSource.isRealTimeStream());

            long targetNow = Instant.parse("2026-11-25T19:20:00Z").toEpochMilli();
            GnssObservationSource.ObservationFetchResult res1 = bkgSource.fetchObservations(
                    madr, new GnssObservationSource.ObservationQuery(targetNow, 60, true));
            assertEquals(2, res1.observationData().epochs().size());
            assertTrue(res1.resolvedUri().contains("329/19/"));

            long histEpoch = Instant.parse("2025-04-10T12:00:00Z").toEpochMilli();
            GnssObservationSource.ObservationFetchResult histRes = bkgSource.fetchObservations(
                    madr, new GnssObservationSource.ObservationQuery(histEpoch, 60, false));
            assertTrue(histRes.resolvedUri().contains("/obs/2025/100/"));

            GnssReferenceStation ab02 = new GnssReferenceStation(
                    "AB02", "AB02", "Nikolski", "USA", GnssStationNetwork.NOAA_CORS,
                    EnumSet.of(GnssStationNetwork.NOAA_CORS), 52.97, -168.85, 73.4, null,
                    "", "", "", "", EnumSet.of(GnssConstellation.GPS), List.of(), 0L, -1, Double.NaN,
                    GnssStationStatus.UNKNOWN, "");
            NoaaCorsS3RinexSource noaaSource = new NoaaCorsS3RinexSource(() -> archives, cache);
            GnssObservationSource.ObservationFetchResult noaaRes = noaaSource.fetchObservations(
                    ab02, new GnssObservationSource.ObservationQuery(targetNow, 60, true));
            assertEquals("AB02", noaaRes.resolvedStation().shortCode());
            assertEquals(42.0, noaaRes.observationData().epochs().get(0).satellites().get("G03")
                    .primaryCn0DbHz(GnssConstellation.GPS), 1e-6);

            server.stop(0);
            GnssObservationSource.ObservationFetchResult cachedRes = bkgSource.fetchObservations(
                    madr, new GnssObservationSource.ObservationQuery(targetNow, 60, true));
            assertEquals(2, cachedRes.observationData().epochs().size());

            Path localFile = tmpDir.resolve("LOCAL_SAMPLE.rnx.gz");
            Files.write(localFile, gzRnx3);
            LocalRinexFileSource localSource = new LocalRinexFileSource();
            GnssObservationSource.ObservationFetchResult localRes = localSource.fetchFromFile(localFile, null);
            assertEquals("MADR00ESP", localRes.resolvedStation().stationId());
            assertEquals(GnssStationNetwork.LOCAL, localRes.resolvedStation().primaryNetwork());
        } finally {
            server.stop(0);
        }
    }

    private static byte[] gzip(byte[] plain) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(plain);
        }
        return baos.toByteArray();
    }

    private static void respondBytes(
            com.sun.net.httpserver.HttpExchange ex,
            int code,
            String contentType,
            byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}

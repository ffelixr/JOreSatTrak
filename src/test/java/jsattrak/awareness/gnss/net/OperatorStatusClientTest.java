package jsattrak.awareness.gnss.net;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jsattrak.awareness.config.AuthMode;
import jsattrak.awareness.config.DataSourceConfig;
import jsattrak.awareness.gnss.GnssOutageNotice;
import jsattrak.awareness.gnss.OperatorStatusRecord;
import jsattrak.awareness.net.ConnectionTestResult;
import org.junit.jupiter.api.Test;

public class OperatorStatusClientTest {

    @Test
    void parsesNanuPlannedUnplannedAndCancelNotices() {
        String fcstdv = """
                NOTICE ADVISORY TO NAVSTAR USERS (NANU) 2026074
                SUBJ: SVN54 (PRN18) FORECAST OUTAGE DV
                1. NANU TYPE: FCSTDV
                   NANU NUMBER: 2026074
                   REFERENCE NANU: N/A
                   START TIME: 2026 327 0800
                   STOP TIME:  2026 327 1400
                """;
        String unusufn = """
                NOTICE ADVISORY TO NAVSTAR USERS (NANU) 2026071
                SUBJ: SVN62 (PRN07) UNUSABLE UNTIL FURTHER NOTICE
                1. NANU TYPE: UNUSUFN
                   NANU NUMBER: 2026071
                   START TIME: 2026 324 1402
                   STOP TIME:  UFN
                """;
        String cancel = """
                NOTICE ADVISORY TO NAVSTAR USERS (NANU) 2026075
                SUBJ: SVN54 (PRN18) FORECAST OUTAGE CANCEL
                1. NANU TYPE: FCSTCANC
                   NANU NUMBER: 2026075
                   REFERENCE NANU: 2026074
                """;

        GnssOutageNotice n1 = NavcenParser.parseSingleNanu(fcstdv).orElseThrow();
        assertEquals("G18", n1.prnId());
        assertTrue(n1.planned());
        assertFalse(n1.cancelled());
        assertTrue(n1.endEpochMillis() > n1.startEpochMillis());

        GnssOutageNotice nUn = NavcenParser.parseSingleNanu(unusufn).orElseThrow();
        assertEquals("G07", nUn.prnId());
        assertFalse(nUn.planned());
        assertEquals(0L, nUn.endEpochMillis());

        GnssOutageNotice n2 = NavcenParser.parseSingleNanu(cancel).orElseThrow();
        List<GnssOutageNotice> resolved = NavcenParser.applyCancellations(List.of(n1, nUn, n2));
        assertTrue(resolved.stream()
                .filter(n -> n.noticeId().contains("2026074"))
                .findFirst().orElseThrow().cancelled());
        assertFalse(resolved.stream()
                .filter(n -> n.noticeId().contains("2026071"))
                .findFirst().orElseThrow().cancelled());
    }

    @Test
    void parsesGscStatusXmlAndFallsBackGracefullyWhenGscNaguReturnsHtmlPortal() throws Exception {
        String nanuBody = """
                NOTICE ADVISORY TO NAVSTAR USERS (NANU) 2026074
                SUBJ: SVN54 (PRN18) FORECAST OUTAGE DV
                1. NANU TYPE: FCSTDV
                   NANU NUMBER: 2026074
                   START TIME: 2026 327 0800
                   STOP TIME:  2026 327 1400
                """;
        String navcenStatus = """
                PRN,SVN,Status,Remarks
                G01,G063,OPERATIONAL,Healthy
                G31,G079,COMMISSIONING,Pending USABINIT
                """;
        String gscStatusXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <constellation>
                  <satellite prn="E11" svn="GSAT0203" status="USABLE" remarks="Nominal"/>
                  <satellite prn="E14" svn="GSAT0202" status="NOT AVAILABLE" remarks="Eccentric orbit"/>
                  <satellite prn="E26" svn="GSAT0226" status="UNDER COMMISSIONING" remarks="In-orbit testing"/>
                </constellation>
                """;
        String gscNaguHtml = "<!DOCTYPE html><html><head><title>Portal</title></head><body>Sign in required</body></html>";

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/navcen/status", ex -> respond(ex, 200, "text/plain", navcenStatus));
            server.createContext("/navcen/nanu/", ex -> {
                String path = ex.getRequestURI().getPath();
                if (path.endsWith("/navcen/nanu/")) {
                    respond(ex, 200, "text/html",
                            "<html><body><a href=\"2026074.nnu\">2026074.nnu</a></body></html>");
                } else if (path.endsWith("2026074.nnu")) {
                    respond(ex, 200, "text/plain", nanuBody);
                } else {
                    ex.sendResponseHeaders(404, -1);
                    ex.close();
                }
            });
            server.createContext("/gsc/status", ex -> respond(ex, 200, "application/xml", gscStatusXml));
            server.createContext("/gsc/nagu", ex -> respond(ex, 200, "text/html", gscNaguHtml));
            server.start();

            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Map<String, List<DataSourceConfig>> scoped = new LinkedHashMap<>();
            scoped.put("GPS_STATUS", List.of(src("gps-st", "GPS_STATUS", base + "/navcen/status")));
            scoped.put("GPS_NANU", List.of(src("gps-na", "GPS_NANU", base + "/navcen/nanu/")));
            scoped.put("GALILEO_STATUS", List.of(src("gal-st", "GALILEO_STATUS", base + "/gsc/status")));
            scoped.put("GALILEO_NAGU", List.of(src("gal-na", "GALILEO_NAGU", base + "/gsc/nagu")));

            OperatorStatusClient client = new OperatorStatusClient();
            OperatorStatusClient.OperatorFetchResult result = client.fetchAll(scoped, 1_795_132_800_000L);

            assertTrue(result.gpsNoticesAvailable());
            assertFalse(result.galileoNoticesAvailable());
            assertTrue(result.scopeErrors().containsKey("GALILEO_NAGU"));

            assertEquals(OperatorStatusRecord.OperatorHealth.TESTING, result.statusByPrn().get("G31").status());
            assertEquals(OperatorStatusRecord.OperatorHealth.TESTING, result.statusByPrn().get("E26").status());
            assertEquals(OperatorStatusRecord.OperatorHealth.NOT_AVAILABLE, result.statusByPrn().get("E14").status());
            assertEquals(1, result.notices().size());
            assertEquals("G18", result.notices().get(0).prnId());

            ConnectionTestResult badNagu = client.testConnection(scoped.get("GALILEO_NAGU").get(0));
            assertFalse(badNagu.success());
            ConnectionTestResult okGsc = client.testConnection(scoped.get("GALILEO_STATUS").get(0));
            assertTrue(okGsc.success());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void parsesMultilineHtmlTablesBarePrnsSvn3DigitsAndRejectsUnrecognizedNaguXml() throws Exception {
        String navcenHtml = """
                <html><body>
                <p>Note: G12 is mentioned in operational overview prose without table context.</p>
                <table>
                  <tr><th>PRN</th><th>SVN</th><th>Remarks</th></tr>
                  <tr>
                    <td>05</td>
                    <td>SVN76</td>
                    <td></td>
                  </tr>
                  <tr>
                    <td>18</td>
                    <td>SVN75</td>
                    <td>UNUSABLE until further notice</td>
                  </tr>
                </table>
                </body></html>
                """;
        List<OperatorStatusRecord> gps = NavcenParser.parseConstellationStatus(navcenHtml, 1_000L);
        assertEquals(2, gps.size());
        assertEquals(OperatorStatusRecord.OperatorHealth.USABLE,
                gps.stream().filter(r -> "G05".equals(r.prnId())).findFirst().orElseThrow().status());
        assertEquals(OperatorStatusRecord.OperatorHealth.NOT_AVAILABLE,
                gps.stream().filter(r -> "G18".equals(r.prnId())).findFirst().orElseThrow().status());

        String nanu3DigitSvn = """
                NOTICE ADVISORY TO NAVSTAR USERS (NANU) 2025099
                SUBJ: SVN101 (PRN09) FORECAST OUTAGE DV
                1. NANU TYPE: FCSTDV
                   NANU NUMBER: 2025099
                   START TIME: 2025 366 0800
                   STOP TIME:  2025 366 1400
                """;
        GnssOutageNotice parsedNanu = NavcenParser.parseSingleNanu(nanu3DigitSvn).orElseThrow();
        assertEquals("G101", parsedNanu.svn());
        assertTrue(parsedNanu.startEpochMillis() > 0L);

        String gscHtml = """
                <table>
                  <tr><th>PRN</th><th>Status</th></tr>
                  <tr>
                    <td>E11</td>
                    <td>USABLE</td>
                  </tr>
                  <tr>
                    <td>E26</td>
                    <td>UNDER COMMISSIONING</td>
                  </tr>
                </table>
                """;
        List<OperatorStatusRecord> gal = GscGalileoParser.parseConstellationStatus(gscHtml, 1_000L);
        assertEquals(2, gal.size());
        assertEquals(OperatorStatusRecord.OperatorHealth.TESTING,
                gal.stream().filter(r -> "E26".equals(r.prnId())).findFirst().orElseThrow().status());

        // Unrecognized XML error payload must fail rather than falsely reporting galileoNoticesAvailable=true
        assertThrows(IOException.class, () -> GscGalileoParser.parseNagus("<error><code>403</code></error>"));
        assertThrows(IOException.class, () -> GscGalileoParser.parseNagus("<div><a href=\"/login\">Login</a></div>"));
    }

    private static DataSourceConfig src(String id, String scope, String url) {
        return new DataSourceConfig(
                id, DataSourceConfig.FeedCategory.GNSS_OPERATOR_STATUS, scope,
                id, url, true, 1, AuthMode.NONE, "", "", "", 15, false);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
        ex.close();
    }
}

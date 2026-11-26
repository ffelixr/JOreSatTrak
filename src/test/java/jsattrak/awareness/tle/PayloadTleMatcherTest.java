package jsattrak.awareness.tle;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import jsattrak.awareness.LaunchRecord;
import jsattrak.awareness.config.AuthMode;
import jsattrak.awareness.config.DataSourceConfig;
import jsattrak.awareness.net.ConnectionTestResult;
import jsattrak.utilities.TLE;
import org.junit.jupiter.api.Test;

public class PayloadTleMatcherTest {

    @Test
    public void testMatchesByCosparClassifiesDebrisAndFlagsDuplicates() {
        String tleText = ""
                + "STARLINK-9001\n"
                + "1 61001U 26142A   26324.50000000  .00001000  00000-0  50000-4 0  9991\n"
                + "2 61001  53.1600 100.0000 0001200  90.0000 270.0000 15.05000000    12\n"
                + "FALCON 9 R/B\n"
                + "1 61002U 26142B   26324.50000000  .00001000  00000-0  50000-4 0  9992\n"
                + "2 61002  53.1600 100.0000 0001200  90.0000 270.0000 15.05000000    13\n"
                + "FALCON 9 DEB\n"
                + "1 61003U 26142C   26324.50000000  .00001000  00000-0  50000-4 0  9993\n"
                + "2 61003  53.1600 100.0000 0001200  90.0000 270.0000 15.05000000    14\n"
                + "UNRELATED SAT\n"
                + "1 61099U 26140A   26324.50000000  .00001000  00000-0  50000-4 0  9994\n"
                + "2 61099  97.4000 100.0000 0001200  90.0000 270.0000 15.05000000    15\n";

        List<TLE> catalog = PayloadTleMatcher.parseThreeLineTles(tleText);
        assertEq(4, catalog.size());

        LaunchRecord launch = launchWithCospar("2026-142", "Starlink Group 12-4", true, "Success");

        List<PayloadTleMatch> matches = PayloadTleMatcher.matchLaunch(
                launch, catalog, List.of(), Set.of("starlink-9001"), Set.of(61003));

        assertEq(3, matches.size());
        assertEq(PayloadTleMatch.ObjectType.PAYLOAD, matches.get(0).objectType());
        assertEq("2026-142A", matches.get(0).internationalDesignator());
        assertEq(61001, matches.get(0).noradId());
        assertEq(false, matches.get(0).supplemental());
        assertEq(true, matches.get(0).alreadyTracked());

        assertEq(PayloadTleMatch.ObjectType.ROCKET_BODY, matches.get(1).objectType());
        assertEq(false, matches.get(1).alreadyTracked());

        assertEq(PayloadTleMatch.ObjectType.DEBRIS, matches.get(2).objectType());
        assertEq(true, matches.get(2).alreadyTracked());

        assertEq(LaunchTleStatus.TLES_READY, PayloadTleMatcher.evaluateStatus(launch, matches));
    }

    @Test
    public void testSuborbitalAndFailedReturnNoOrbitalPayloadAndSupGpTagged() {
        LaunchRecord suborbital = launchWithCospar(null, "NS-28", false, "Success");
        assertEq(LaunchTleStatus.NO_ORBITAL_PAYLOAD, PayloadTleMatcher.evaluateStatus(suborbital, List.of()));

        LaunchRecord failed = launchWithCospar(null, "Test Flight", true, "Failure");
        assertEq(LaunchTleStatus.NO_ORBITAL_PAYLOAD, PayloadTleMatcher.evaluateStatus(failed, List.of()));

        LaunchRecord upcoming = launchWithCospar(null, "Starlink Group 12-9", true, "Go");
        assertEq(LaunchTleStatus.AWAITING_TLE, PayloadTleMatcher.evaluateStatus(upcoming, List.of()));

        TLE sup = new TLE("STARLINK-GROUP-12-9-1",
                "1 99001U 26999A   26325.10000000  .00001000  00000-0  50000-4 0  9991",
                "2 99001  53.1600 100.0000 0001200  90.0000 270.0000 15.05000000    12");
        TLE nonMatching = new TLE("STARLINK-GROUP-12-90-1",
                "1 99002U 26998A   26325.10000000  .00001000  00000-0  50000-4 0  9992",
                "2 99002  53.1600 100.0000 0001200  90.0000 270.0000 15.05000000    13");

        List<PayloadTleMatch> matches = PayloadTleMatcher.matchLaunch(
                upcoming, List.of(), List.of(sup, nonMatching), Set.of(), Set.of());
        assertEq(1, matches.size());
        assertEq("STARLINK-GROUP-12-9-1", matches.get(0).objectName());
        assertEq(true, matches.get(0).supplemental());
        assertEq(LaunchTleStatus.SUPPLEMENTAL_READY, PayloadTleMatcher.evaluateStatus(upcoming, matches));
    }

    @Test
    public void testDesignatorYearPivotAndObjectClassification() {
        TLE sputnik = new TLE("SPUTNIK 1",
                "1 00002U 57001B   57277.00000000  .00000000  00000-0  00000-0 0  9991",
                "2 00002  65.1000 100.0000 0500000  90.0000 270.0000 14.95000000    12");
        TLE iss = new TLE("ISS (ZARYA)",
                "1 25544U 98067A   26324.50000000  .00001000  00000-0  50000-4 0  9991",
                "2 25544  51.6400 100.0000 0001200  90.0000 270.0000 15.50000000    12");
        TLE y2k = new TLE("Y2K SAT",
                "1 26001U 00001A   00001.00000000  .00001000  00000-0  50000-4 0  9991",
                "2 26001  53.0000 100.0000 0001200  90.0000 270.0000 15.00000000    12");
        TLE y2056 = new TLE("FUTURE SAT",
                "1 70001U 56012AA  56001.00000000  .00001000  00000-0  50000-4 0  9991",
                "2 70001  53.0000 100.0000 0001200  90.0000 270.0000 15.00000000    12");

        assertEq("1957-001", PayloadTleMatcher.extractDesignatorPrefix(sputnik));
        assertEq("1998-067", PayloadTleMatcher.extractDesignatorPrefix(iss));
        assertEq("2000-001", PayloadTleMatcher.extractDesignatorPrefix(y2k));
        assertEq("2056-012", PayloadTleMatcher.extractDesignatorPrefix(y2056));
        assertEq("", PayloadTleMatcher.extractDesignatorPrefix(null));

        assertEq(PayloadTleMatch.ObjectType.ROCKET_BODY, PayloadTleMatcher.classifyObject("CZ-2D R/B(1)"));
        assertEq(PayloadTleMatch.ObjectType.DEBRIS, PayloadTleMatcher.classifyObject("COSMOS 2251 DEB"));
        assertEq(PayloadTleMatch.ObjectType.PAYLOAD, PayloadTleMatcher.classifyObject("DEBRISAT-1"));
        assertEq(PayloadTleMatch.ObjectType.PAYLOAD, PayloadTleMatcher.classifyObject("STARLINK-9001"));
    }

    @Test
    public void testTleFeedClientFetchesThreeLineAndOmmCsvAndTestsConnection() throws Exception {
        String threeLineBody = ""
                + "STARLINK-9001\n"
                + "1 61001U 26142A   26324.50000000  .00001000  00000-0  50000-4 0  9991\n"
                + "2 61001  53.1600 100.0000 0001200  90.0000 270.0000 15.05000000    12\n";
        String ommCsvBody = ""
                + "OBJECT_NAME,OBJECT_ID,EPOCH,MEAN_MOTION,ECCENTRICITY,INCLINATION,RA_OF_ASC_NODE,ARG_OF_PERICENTER,MEAN_ANOMALY,EPHEMERIS_TYPE,CLASSIFICATION_TYPE,NORAD_CAT_ID,ELEMENT_SET_NO,REV_AT_EPOCH,BSTAR,MEAN_MOTION_DOT,MEAN_MOTION_DDOT\n"
                + "TEST-OMM,2026-142D,2026-11-20T12:00:00.000000,15.05,0.00012,53.16,100.0,90.0,270.0,0,U,61004,999,1,0.00005,0.00001,0\n";

        AtomicReference<String> receivedAuth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tle3", ex -> {
            receivedAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = threeLineBody.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.createContext("/omm.csv", ex -> {
            byte[] bytes = ommCsvBody.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.createContext("/err", ex -> {
            ex.sendResponseHeaders(503, -1);
            ex.close();
        });
        server.createContext("/html", ex -> {
            byte[] bytes = "<!DOCTYPE html><html><body>Index</body></html>".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            TleFeedClient client = new TleFeedClient();

            DataSourceConfig tleSrc = new DataSourceConfig("t1", DataSourceConfig.FeedCategory.TLE, "3LE",
                    "http://127.0.0.1:" + port + "/tle3", true, 1, AuthMode.BEARER_TOKEN, "", "sec-1", "", 15, false);
            List<TLE> tles = client.fetchTleCatalog(tleSrc);
            assertEq(1, tles.size());
            assertEq("STARLINK-9001", tles.get(0).getSatName());
            assertEq("Bearer sec-1", receivedAuth.get());

            ConnectionTestResult tleRes = client.testConnection(tleSrc);
            assertEq(true, tleRes.success());
            assertEq(200, tleRes.httpStatus());
            assertEq(1, tleRes.parsedCount());

            DataSourceConfig csvSrc = new DataSourceConfig("t2", DataSourceConfig.FeedCategory.TLE, "CSV",
                    "http://127.0.0.1:" + port + "/omm.csv", true, 2, AuthMode.NONE, "", "", "", 15, false);
            List<TLE> csvTles = client.fetchTleCatalog(csvSrc);
            assertEq(1, csvTles.size());
            assertEq("TEST-OMM", csvTles.get(0).getSatName());
            assertEq("2026-142", PayloadTleMatcher.extractDesignatorPrefix(csvTles.get(0)));

            DataSourceConfig errSrc = new DataSourceConfig("t3", DataSourceConfig.FeedCategory.TLE, "Err",
                    "http://127.0.0.1:" + port + "/err", true, 3, AuthMode.NONE, "", "", "", 15, false);
            ConnectionTestResult errRes = client.testConnection(errSrc);
            assertEq(false, errRes.success());
            assertEq(503, errRes.httpStatus());

            DataSourceConfig htmlSrc = new DataSourceConfig("t4", DataSourceConfig.FeedCategory.TLE, "Html",
                    "http://127.0.0.1:" + port + "/html", true, 4, AuthMode.NONE, "", "", "", 15, false);
            ConnectionTestResult htmlRes = client.testConnection(htmlSrc);
            assertEq(false, htmlRes.success());
            assertEq(200, htmlRes.httpStatus());
            assertEq(false, client.testConnection(new DataSourceConfig()).success());

            boolean threw = false;
            try {
                client.fetchTleCatalog(errSrc);
            } catch (IOException expected) {
                threw = true;
            }
            assertEq(true, threw);

            boolean threwOnHtml = false;
            try {
                client.fetchTleCatalog(htmlSrc);
            } catch (IOException expected) {
                threwOnHtml = true;
            }
            assertEq(true, threwOnHtml);

            DataSourceConfig badUrlSrc = new DataSourceConfig("t5", DataSourceConfig.FeedCategory.TLE, "BadUrl",
                    "http://[invalid host", true, 5, AuthMode.NONE, "", "", "", 15, false);
            boolean threwOnBadUrl = false;
            try {
                client.fetchTleCatalog(badUrlSrc);
            } catch (IOException expected) {
                threwOnBadUrl = true;
            }
            assertEq(true, threwOnBadUrl);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testOmmCsvPreservesDecimalPointOnSignedLeadingDotNumbers() throws Exception {
        String csv = ""
                + "OBJECT_NAME,OBJECT_ID,EPOCH,MEAN_MOTION,ECCENTRICITY,INCLINATION,RA_OF_ASC_NODE,ARG_OF_PERICENTER,MEAN_ANOMALY,EPHEMERIS_TYPE,CLASSIFICATION_TYPE,NORAD_CAT_ID,ELEMENT_SET_NO,REV_AT_EPOCH,BSTAR,MEAN_MOTION_DOT,MEAN_MOTION_DDOT\n"
                + "STARLINK-38230,2026-244B,2026-11-26T06:00:02.000160,15.45914417,.00041615,69.9989,137.8630,273.6892,4.0559,0,U,101201,999,605,-.62960181E-1,-.2696791E-1,0\n"
                + "STARLINK-38504,2026-244A,2026-11-26T04:35:02.996160,15.51667880,.0002521,70.0020,137.9745,273.0790,87.0108,0,U,101200,999,471,+.49584667E-3,+.29532E-3,0\n";

        List<TLE> parsed = jsattrak.utilities.OmmDataLoader.parseOmmCsv(new java.io.StringReader(csv));
        assertEq(2, parsed.size());
        assertEq(101201, PayloadTleMatcher.extractNoradId(parsed.get(0)));
        assertEq("2026-244", PayloadTleMatcher.extractDesignatorPrefix(parsed.get(0)));
        assertEq(101200, PayloadTleMatcher.extractNoradId(parsed.get(1)));
    }

    private static LaunchRecord launchWithCospar(String cosparId, String missionName, boolean orbital, String outcome) {
        return LaunchRecord.builder()
                .id("launch-" + missionName)
                .missionName(missionName)
                .cosparId(cosparId == null ? "" : cosparId)
                .orbital(orbital)
                .outcome(outcome)
                .status(outcome)
                .build();
    }

    private static void assertEq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("Expected <" + expected + "> but got <" + actual + ">");
        }
    }
}

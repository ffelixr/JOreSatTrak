package jsattrak.awareness.net;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import jsattrak.awareness.LaunchRecord;
import jsattrak.awareness.config.AuthMode;
import jsattrak.awareness.config.DataSourceConfig;
import org.junit.jupiter.api.Test;

public class LaunchApiClientTest {

    @Test
    public void testQueryParamAuthPreservesExistingQueryAndEncodesSecret() {
        DataSourceConfig cfg = new DataSourceConfig("s1", DataSourceConfig.FeedCategory.LAUNCH, "Mirror",
                "https://example.org/launch/upcoming/?mode=detailed", true, 1,
                AuthMode.QUERY_PARAM, "api_key", "a+b/c=1", "", 15, true);
        HttpRequest req = HttpRequestAuthApplier.buildRequest(cfg.getEndpointUrl(), cfg, Duration.ofSeconds(5));
        String uri = req.uri().toString();
        if (!uri.contains("?mode=detailed&api_key=a%2Bb%2Fc%3D1")) {
            throw new AssertionError("Expected & separator and URL-encoded secret, got: " + uri);
        }

        DataSourceConfig noQueryCfg = new DataSourceConfig("s2", DataSourceConfig.FeedCategory.LAUNCH, "Clean",
                "https://example.org/launch/upcoming/", true, 1,
                AuthMode.QUERY_PARAM, "token", "x y", "", 15, true);
        String cleanUri = HttpRequestAuthApplier.buildRequest(noQueryCfg.getEndpointUrl(), noQueryCfg, Duration.ofSeconds(5))
                .uri().toString();
        if (!cleanUri.endsWith("/upcoming/?token=x+y")) {
            throw new AssertionError("Expected ? separator for URL without existing query, got: " + cleanUri);
        }

        DataSourceConfig fragNoQueryCfg = new DataSourceConfig("s3", DataSourceConfig.FeedCategory.LAUNCH, "FragNoQuery",
                "https://example.org/launch/upcoming/#top", true, 1,
                AuthMode.QUERY_PARAM, "token", "abc", "", 15, true);
        String fragNoQueryUri = HttpRequestAuthApplier.buildRequest(
                fragNoQueryCfg.getEndpointUrl(), fragNoQueryCfg, Duration.ofSeconds(5)).uri().toString();
        assertEq("https://example.org/launch/upcoming/?token=abc#top", fragNoQueryUri);

        DataSourceConfig fragWithQueryCfg = new DataSourceConfig("s4", DataSourceConfig.FeedCategory.LAUNCH, "FragQuery",
                "https://example.org/launch/upcoming/?mode=detailed#section-2", true, 1,
                AuthMode.QUERY_PARAM, "api_key", "a+b", "", 15, true);
        String fragWithQueryUri = HttpRequestAuthApplier.buildRequest(
                fragWithQueryCfg.getEndpointUrl(), fragWithQueryCfg, Duration.ofSeconds(5)).uri().toString();
        assertEq("https://example.org/launch/upcoming/?mode=detailed&api_key=a%2Bb#section-2", fragWithQueryUri);
    }

    @Test
    public void testMalformedQueryParamUriRedactsSecretFromErrorMessage() {
        String rawSecret = "super-secret value/99";
        DataSourceConfig malformed = new DataSourceConfig("bad-qp", DataSourceConfig.FeedCategory.LAUNCH, "BadQP",
                "https://example.org/bad path/?mode=detailed", true, 1,
                AuthMode.QUERY_PARAM, "api_key", rawSecret, "", 15, false);

        IllegalArgumentException thrown = null;
        try {
            HttpRequestAuthApplier.buildRequest(malformed.getEndpointUrl(), malformed, Duration.ofSeconds(5));
        } catch (IllegalArgumentException ex) {
            thrown = ex;
        }
        if (thrown == null) {
            throw new AssertionError("Expected IllegalArgumentException for malformed URI");
        }
        String msg = thrown.getMessage() != null ? thrown.getMessage() : "";
        assertEq(false, msg.contains(rawSecret));
        assertEq(false, msg.contains("super-secret+value%2F99"));
        assertEq(true, thrown.getCause() == null);

        LaunchApiClient client = new LaunchApiClient();
        ConnectionTestResult result = client.testConnection(malformed);
        assertEq(false, result.success());
        assertEq(false, result.message().contains(rawSecret));
        assertEq(false, result.message().contains("super-secret+value%2F99"));
    }

    @Test
    public void testAllAuthModesAppliedToHttpRequest() {
        DataSourceConfig noneCfg = new DataSourceConfig("n", DataSourceConfig.FeedCategory.LAUNCH, "None",
                "https://example.org/launch/", true, 1, AuthMode.NONE, "X-Key", "ignored", "user", 15, false);
        HttpRequest noneReq = HttpRequestAuthApplier.buildRequest(noneCfg.getEndpointUrl(), noneCfg, Duration.ofSeconds(5));
        assertEq(true, noneReq.headers().firstValue("Authorization").isEmpty());
        assertEq(true, noneReq.headers().firstValue("X-Key").isEmpty());

        DataSourceConfig apiKeyDefaultHeader = new DataSourceConfig("ak1", DataSourceConfig.FeedCategory.LAUNCH, "ApiKeyDefault",
                "https://example.org/launch/", true, 1, AuthMode.API_KEY_HEADER, "", "Token secret123", "", 15, false);
        HttpRequest ak1Req = HttpRequestAuthApplier.buildRequest(apiKeyDefaultHeader.getEndpointUrl(), apiKeyDefaultHeader, Duration.ofSeconds(5));
        assertEq("Token secret123", ak1Req.headers().firstValue("Authorization").orElse(""));

        DataSourceConfig apiKeyCustomHeader = new DataSourceConfig("ak2", DataSourceConfig.FeedCategory.LAUNCH, "ApiKeyCustom",
                "https://example.org/launch/", true, 1, AuthMode.API_KEY_HEADER, "X-Api-Key", "k-999", "", 15, false);
        HttpRequest ak2Req = HttpRequestAuthApplier.buildRequest(apiKeyCustomHeader.getEndpointUrl(), apiKeyCustomHeader, Duration.ofSeconds(5));
        assertEq("k-999", ak2Req.headers().firstValue("X-Api-Key").orElse(""));

        DataSourceConfig bearerCfg = new DataSourceConfig("bt", DataSourceConfig.FeedCategory.LAUNCH, "Bearer",
                "https://example.org/launch/", true, 1, AuthMode.BEARER_TOKEN, "", "tok-abc", "", 15, false);
        HttpRequest btReq = HttpRequestAuthApplier.buildRequest(bearerCfg.getEndpointUrl(), bearerCfg, Duration.ofSeconds(5));
        assertEq("Bearer tok-abc", btReq.headers().firstValue("Authorization").orElse(""));

        DataSourceConfig basicCfg = new DataSourceConfig("ba", DataSourceConfig.FeedCategory.LAUNCH, "Basic",
                "https://example.org/launch/", true, 1, AuthMode.BASIC_AUTH, "", "s3cr3t!", "alice", 15, false);
        HttpRequest baReq = HttpRequestAuthApplier.buildRequest(basicCfg.getEndpointUrl(), basicCfg, Duration.ofSeconds(5));
        String expectedBasic = "Basic " + Base64.getEncoder().encodeToString("alice:s3cr3t!".getBytes(StandardCharsets.UTF_8));
        assertEq(expectedBasic, baReq.headers().firstValue("Authorization").orElse(""));
    }

    @Test
    public void testParserHandlesMissingOptionalFieldsAndMobilePads() {
        String json = "{\"results\":["
                + "{\"id\":\"ok1\",\"name\":\"Falcon 9 | Starlink\",\"net\":\"2026-11-21T10:00:00Z\","
                + "\"window_start\":\"2026-11-21T10:00:00Z\",\"window_end\":null,"
                + "\"status\":{\"abbrev\":\"Go\"},\"launch_service_provider\":{\"name\":\"SpaceX\"},"
                + "\"rocket\":{\"configuration\":{\"full_name\":\"Falcon 9 Block 5\"}},"
                + "\"mission\":null,"
                + "\"vidURLs\":[],"
                + "\"pad\":{\"name\":\"SLC-40\",\"latitude\":\"28.5619\",\"longitude\":\"-80.5774\","
                + "\"location\":{\"name\":\"Cape Canaveral SFS\",\"country_code\":\"USA\"}}},"
                + "{\"id\":\"sea1\",\"name\":\"Jielong-3 | Test\",\"net\":\"2026-11-22T05:00:00Z\","
                + "\"window_end\":\"2026-11-22T04:00:00Z\","
                + "\"status\":{\"abbrev\":\"TBC\"},"
                + "\"mission\":{\"name\":\"Test Payload\",\"description\":\"Sea demo\",\"orbit\":{\"abbrev\":\"SSO\"}},"
                + "\"cospar_id\":\"2026-111A\","
                + "\"vid_urls\":[{\"url\":\"https://stream.example/live\"}],"
                + "\"pad\":{\"name\":\"Sea Launch Platform\",\"latitude\":\"35.1\",\"longitude\":\"121.2\","
                + "\"location\":{\"name\":\"Yellow Sea\",\"country_code\":\"CHN\"}}},"
                + "{\"id\":\"sub1\",\"name\":\"New Shepard | NS-99\",\"net\":\"2026-11-23T12:00:00Z\","
                + "\"status\":{\"abbrev\":\"Success\"},"
                + "\"mission\":{\"name\":\"NS-99\",\"orbit\":{\"abbrev\":\"Sub\"}},"
                + "\"pad\":{\"name\":\"Pad One\",\"latitude\":\"0.0\",\"longitude\":\"0.0\","
                + "\"location\":{\"name\":\"Corn Ranch\",\"country_code\":\"USA\"}}}"
                + "]}";
        List<LaunchRecord> parsed = LaunchLibrary2Parser.parseResults(json);
        assertEq(3, parsed.size());

        LaunchRecord ok1 = parsed.get(0);
        assertEq("ok1", ok1.getId());
        assertEq("Falcon 9 | Starlink", ok1.getMissionName());
        assertEq("SpaceX", ok1.getProvider());
        assertEq("Falcon 9 Block 5", ok1.getVehicle());
        assertEq(Instant.parse("2026-11-21T10:00:00Z").toEpochMilli(), ok1.getNetEpochMillis());
        assertEq(ok1.getNetEpochMillis(), ok1.getWindowEndMillis());
        assertEq("", ok1.getWebcastUrl());
        assertEq(true, ok1.isOrbital());
        assertEq(true, ok1.isFixedLandPad());

        LaunchRecord sea1 = parsed.get(1);
        assertEq("Test Payload", sea1.getMissionName());
        assertEq("2026-111A", sea1.getCosparId());
        assertEq("SSO", sea1.getTargetOrbit());
        assertEq("https://stream.example/live", sea1.getWebcastUrl());
        assertEq(sea1.getNetEpochMillis(), sea1.getWindowEndMillis()); // window_end < net normalized to net
        assertEq(false, sea1.isFixedLandPad());

        LaunchRecord sub1 = parsed.get(2);
        assertEq(false, sub1.isOrbital());
        assertEq("Success", sub1.getOutcome());
        assertEq(false, sub1.isFixedLandPad()); // (0.0, 0.0) coordinates
    }

    @Test
    public void testWordBoundaryMobilePadClassification() {
        String json = "{\"results\":["
                + "{\"id\":\"seattle\",\"name\":\"Mission A\",\"net\":\"2026-11-21T10:00:00Z\","
                + "\"pad\":{\"name\":\"Pad 1\",\"latitude\":\"47.6062\",\"longitude\":\"-122.3321\","
                + "\"location\":{\"name\":\"Seattle Spaceport\",\"country_code\":\"USA\"}}},"
                + "{\"id\":\"township\",\"name\":\"Mission B\",\"net\":\"2026-11-21T11:00:00Z\","
                + "\"pad\":{\"name\":\"Township Launch Complex\",\"latitude\":\"34.0\",\"longitude\":\"-118.0\","
                + "\"location\":{\"name\":\"Kern County\",\"country_code\":\"USA\"}}},"
                + "{\"id\":\"sea-pad\",\"name\":\"Mission C\",\"net\":\"2026-11-21T12:00:00Z\","
                + "\"pad\":{\"name\":\"Sea Launch Platform\",\"latitude\":\"35.1\",\"longitude\":\"121.2\","
                + "\"location\":{\"name\":\"Pacific\",\"country_code\":\"USA\"}}},"
                + "{\"id\":\"ship-pad\",\"name\":\"Mission D\",\"net\":\"2026-11-21T13:00:00Z\","
                + "\"pad\":{\"name\":\"ASDS Ship\",\"latitude\":\"28.5\",\"longitude\":\"-79.5\","
                + "\"location\":{\"name\":\"Atlantic\",\"country_code\":\"USA\"}}}"
                + "]}";
        List<LaunchRecord> parsed = LaunchLibrary2Parser.parseResults(json);
        assertEq(4, parsed.size());
        assertEq(true, parsed.get(0).isFixedLandPad());  // "Seattle" must not match "sea"
        assertEq(true, parsed.get(1).isFixedLandPad());  // "Township" must not match "ship"
        assertEq(false, parsed.get(2).isFixedLandPad()); // "Sea Launch Platform" is mobile
        assertEq(false, parsed.get(3).isFixedLandPad()); // "ASDS Ship" is mobile
    }

    @Test
    public void testPriorityFailoverOnHttp429AndBearerHeaderSent() throws Exception {
        AtomicReference<String> receivedAuth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/primary/", ex -> { ex.sendResponseHeaders(429, -1); ex.close(); });
        server.createContext("/backup/", ex -> {
            receivedAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"results\":[]}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            DataSourceConfig p1 = new DataSourceConfig("p1", DataSourceConfig.FeedCategory.LAUNCH, "Primary",
                    "http://127.0.0.1:" + port + "/primary/", true, 1, AuthMode.NONE, "", "", "", 15, true);
            DataSourceConfig p2 = new DataSourceConfig("p2", DataSourceConfig.FeedCategory.LAUNCH, "Backup",
                    "http://127.0.0.1:" + port + "/backup/", true, 2, AuthMode.BEARER_TOKEN, "", "tok-xyz", "", 15, true);

            LaunchApiClient client = new LaunchApiClient();
            LaunchApiClient.LaunchFetchResult res = client.fetchUpcomingAndRecent(List.of(p1, p2), 1_795_132_800_000L);
            assertEq("Backup", res.sourceUsed());
            assertEq("Bearer tok-xyz", receivedAuth.get());
            assertEq(false, client.testConnection(p1).success());
            assertEq(429, client.testConnection(p1).httpStatus());
            assertEq(true, client.testConnection(p2).success());
            assertEq(200, client.testConnection(p2).httpStatus());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testPriorityOrderingSkipsDisabledFailsOverOn5xxAndFetchesSiblingPaths() throws Exception {
        List<String> requestedPaths = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/disabled/", ex -> {
            requestedPaths.add(ex.getRequestURI().toString());
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.createContext("/flaky/", ex -> {
            requestedPaths.add(ex.getRequestURI().toString());
            ex.sendResponseHeaders(503, -1);
            ex.close();
        });
        server.createContext("/exact/", ex -> {
            String fullUri = ex.getRequestURI().toString();
            requestedPaths.add(fullUri);
            String json = fullUri.contains("/previous/")
                    ? "{\"results\":[{\"id\":\"rec1\",\"name\":\"Prev\",\"net\":\"2026-11-19T12:00:00Z\",\"status\":{\"abbrev\":\"Success\"}}]}"
                    : "{\"results\":[{\"id\":\"up1\",\"name\":\"Next\",\"net\":\"2026-11-21T12:00:00Z\",\"status\":{\"abbrev\":\"Go\"}}]}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            DataSourceConfig disabledHighPri = new DataSourceConfig("d0", DataSourceConfig.FeedCategory.LAUNCH, "Disabled",
                    "http://127.0.0.1:" + port + "/disabled/", false, 0, AuthMode.NONE, "", "", "", 15, false);
            DataSourceConfig flakyPri1 = new DataSourceConfig("f1", DataSourceConfig.FeedCategory.LAUNCH, "Flaky503",
                    "http://127.0.0.1:" + port + "/flaky/", true, 1, AuthMode.NONE, "", "", "", 15, false);
            DataSourceConfig exactPri2 = new DataSourceConfig("e2", DataSourceConfig.FeedCategory.LAUNCH, "ExactMirror",
                    "http://127.0.0.1:" + port + "/exact/upcoming/?mode=detailed", true, 2, AuthMode.NONE, "", "", "", 15, false);

            LaunchApiClient client = new LaunchApiClient();
            // Intentionally pass out of priority order with a disabled source included
            LaunchApiClient.LaunchFetchResult res = client.fetchUpcomingAndRecent(
                    List.of(exactPri2, flakyPri1, disabledHighPri), 1_795_132_800_000L);

            assertEq("ExactMirror", res.sourceUsed());
            assertEq(1, res.upcoming().size());
            assertEq("up1", res.upcoming().get(0).getId());
            assertEq(1, res.recent().size());
            assertEq("rec1", res.recent().get(0).getId());
            assertEq(true, requestedPaths.stream().noneMatch(p -> p.startsWith("/disabled/")));
            assertEq(true, requestedPaths.contains("/exact/upcoming/?mode=detailed"));
            assertEq(true, requestedPaths.contains("/exact/previous/?mode=detailed"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testAllSourcesFailingThrowsIOException() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/down/", ex -> {
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            DataSourceConfig down = new DataSourceConfig("down", DataSourceConfig.FeedCategory.LAUNCH, "Down",
                    "http://127.0.0.1:" + port + "/down/", true, 1, AuthMode.NONE, "", "", "", 15, false);
            LaunchApiClient client = new LaunchApiClient();
            boolean threw = false;
            try {
                client.fetchUpcomingAndRecent(List.of(down), 1_795_132_800_000L);
            } catch (IOException expected) {
                threw = true;
            }
            assertEq(true, threw);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testIoExceptionFailoverAndNonFailover4xx() throws Exception {
        // Reserve and close a port so connecting to it triggers IOException
        int deadPort;
        try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
            deadPort = ss.getLocalPort();
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/unauth/", ex -> {
            ex.sendResponseHeaders(401, -1);
            ex.close();
        });
        server.createContext("/ok/", ex -> {
            byte[] body = "{\"results\":[]}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            DataSourceConfig dead = new DataSourceConfig("dead", DataSourceConfig.FeedCategory.LAUNCH, "Dead",
                    "http://127.0.0.1:" + deadPort + "/launch/", true, 1, AuthMode.NONE, "", "", "", 15, false);
            DataSourceConfig ok = new DataSourceConfig("ok", DataSourceConfig.FeedCategory.LAUNCH, "LiveBackup",
                    "http://127.0.0.1:" + port + "/ok/", true, 2, AuthMode.NONE, "", "", "", 15, false);
            DataSourceConfig unauth = new DataSourceConfig("unauth", DataSourceConfig.FeedCategory.LAUNCH, "Unauth",
                    "http://127.0.0.1:" + port + "/unauth/", true, 1, AuthMode.NONE, "", "", "", 15, false);

            LaunchApiClient client = new LaunchApiClient();
            LaunchApiClient.LaunchFetchResult res = client.fetchUpcomingAndRecent(List.of(dead, ok), 1_795_132_800_000L);
            assertEq("LiveBackup", res.sourceUsed());

            boolean threwOn401 = false;
            try {
                client.fetchUpcomingAndRecent(List.of(unauth, ok), 1_795_132_800_000L);
            } catch (IOException expected) {
                threwOn401 = expected.getMessage().contains("401");
            }
            assertEq(true, threwOn401);
            assertEq(false, client.testConnection(new DataSourceConfig()).success());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testHttp200HtmlOrMalformedJsonFailsOverAndFailsTestConnection() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/html/", ex -> {
            byte[] body = "<html><body>Captive Portal</body></html>".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/no-results/", ex -> {
            byte[] body = "{\"detail\":\"unexpected schema\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/good/", ex -> {
            byte[] body = "{\"results\":[{\"id\":\"g1\",\"name\":\"Good Launch\",\"net\":\"2026-11-21T10:00:00Z\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            DataSourceConfig htmlPrimary = new DataSourceConfig("html1", DataSourceConfig.FeedCategory.LAUNCH, "Portal",
                    "http://127.0.0.1:" + port + "/html/", true, 1, AuthMode.NONE, "", "", "", 15, false);
            DataSourceConfig schemaBad = new DataSourceConfig("bad2", DataSourceConfig.FeedCategory.LAUNCH, "BadSchema",
                    "http://127.0.0.1:" + port + "/no-results/", true, 2, AuthMode.NONE, "", "", "", 15, false);
            DataSourceConfig goodBackup = new DataSourceConfig("good3", DataSourceConfig.FeedCategory.LAUNCH, "GoodBackup",
                    "http://127.0.0.1:" + port + "/good/", true, 3, AuthMode.NONE, "", "", "", 15, false);

            LaunchApiClient client = new LaunchApiClient();
            LaunchApiClient.LaunchFetchResult res = client.fetchUpcomingAndRecent(
                    List.of(htmlPrimary, schemaBad, goodBackup), 1_795_132_800_000L);
            assertEq("GoodBackup", res.sourceUsed());
            assertEq(1, res.upcoming().size());

            ConnectionTestResult htmlTest = client.testConnection(htmlPrimary);
            assertEq(false, htmlTest.success());
            assertEq(200, htmlTest.httpStatus());

            ConnectionTestResult schemaTest = client.testConnection(schemaBad);
            assertEq(false, schemaTest.success());
            assertEq(200, schemaTest.httpStatus());

            // Non-strict parseResults remains safe for direct callers
            assertEq(0, LaunchLibrary2Parser.parseResults("<html>bad</html>").size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testMalformedEndpointUriFailsOverAndFailsTestConnection() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/valid/", ex -> {
            byte[] body = "{\"results\":[]}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            DataSourceConfig malformedUri = new DataSourceConfig("bad-uri", DataSourceConfig.FeedCategory.LAUNCH, "MalformedURI",
                    "http://127.0.0.1:" + port + "/bad uri with spaces/", true, 1, AuthMode.NONE, "", "", "", 15, false);
            DataSourceConfig validBackup = new DataSourceConfig("valid", DataSourceConfig.FeedCategory.LAUNCH, "ValidBackup",
                    "http://127.0.0.1:" + port + "/valid/", true, 2, AuthMode.NONE, "", "", "", 15, false);

            LaunchApiClient client = new LaunchApiClient();
            LaunchApiClient.LaunchFetchResult res = client.fetchUpcomingAndRecent(
                    List.of(malformedUri, validBackup), 1_795_132_800_000L);
            assertEq("ValidBackup", res.sourceUsed());

            ConnectionTestResult badUriTest = client.testConnection(malformedUri);
            assertEq(false, badUriTest.success());
            assertEq(0, badUriTest.httpStatus());
        } finally {
            server.stop(0);
        }
    }

    private static void assertEq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("Expected <" + expected + "> but got <" + actual + ">");
        }
    }
}

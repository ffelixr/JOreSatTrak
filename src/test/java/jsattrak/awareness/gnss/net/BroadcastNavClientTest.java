package jsattrak.awareness.gnss.net;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import jsattrak.awareness.config.AuthMode;
import jsattrak.awareness.config.DataSourceConfig;
import jsattrak.awareness.net.ConnectionTestResult;
import org.junit.jupiter.api.Test;

public class BroadcastNavClientTest {

    @Test
    void resolvesApacheDirectoryIndexToNewestGzippedBrdcAndFailsOverOn503() throws Exception {
        byte[] gzData = RinexNavHealthParserTest.gzip(RinexNavHealthParserTest.RINEX3_FIXTURE);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/fail", exchange -> {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
            });
            server.createContext("/brdc/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/brdc/")) {
                    String html = """
                            <!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 3.2 Final//EN">
                            <html><body>
                            <a href="../">Parent Directory</a>
                            <a href="BRDC00WRD_R_20263240000_01D_MN.rnx.gz">2026324</a>
                            <a href="BRDC00WRD_R_20263250000_01D_MN.rnx.gz">2026325</a>
                            </body></html>
                            """;
                    byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/html");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                } else if (path.endsWith("BRDC00WRD_R_20263250000_01D_MN.rnx.gz")) {
                    exchange.getResponseHeaders().add("Content-Type", "application/gzip");
                    exchange.sendResponseHeaders(200, gzData.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(gzData);
                    }
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
                exchange.close();
            });
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();

            DataSourceConfig failing = new DataSourceConfig(
                    "p1", DataSourceConfig.FeedCategory.GNSS_BROADCAST_NAV, "",
                    "Primary", base + "/fail", true, 1, AuthMode.NONE, "", "", "", 15, false);
            DataSourceConfig backup = new DataSourceConfig(
                    "p2", DataSourceConfig.FeedCategory.GNSS_BROADCAST_NAV, "",
                    "BKG Mock", base + "/brdc/", true, 2, AuthMode.NONE, "", "", "", 15, false);

            BroadcastNavClient client = new BroadcastNavClient();
            BroadcastNavClient.BroadcastFetchResult result = client.fetchLatest(List.of(failing, backup));
            assertEquals("BKG Mock", result.sourceName());
            assertTrue(result.resolvedFileUrl().endsWith("BRDC00WRD_R_20263250000_01D_MN.rnx.gz"));
            assertTrue(result.parsed().latestByPrn().containsKey("G01"));
            assertFalse(result.parsed().latestByPrn().get("C33").healthy());

            ConnectionTestResult testOk = client.testConnection(backup);
            assertTrue(testOk.success());
            assertTrue(testOk.parsedCount() >= 7);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void discoversAndParsesUnixCompressedBrdcLastRnxZ() throws Exception {
        byte[] zData = unixCompressLiteral9Bit(RinexNavHealthParserTest.RINEX3_FIXTURE);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/brdc/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/brdc/")) {
                    String html = """
                            <html><body>
                            <a href="2020/">2020/</a>
                            <a href="brdc_last.rnx.Z">brdc_last.rnx.Z</a>
                            </body></html>
                            """;
                    byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/html");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                } else if (path.endsWith("brdc_last.rnx.Z")) {
                    exchange.getResponseHeaders().add("Content-Type", "application/x-compress");
                    exchange.sendResponseHeaders(200, zData.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(zData);
                    }
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
                exchange.close();
            });
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            DataSourceConfig zSource = new DataSourceConfig(
                    "bkg-z", DataSourceConfig.FeedCategory.GNSS_BROADCAST_NAV, "",
                    "BKG NTRIP BRDC", base + "/brdc/", true, 1, AuthMode.NONE, "", "", "", 15, false);

            BroadcastNavClient client = new BroadcastNavClient();
            BroadcastNavClient.BroadcastFetchResult result = client.fetchLatest(List.of(zSource));
            assertTrue(result.resolvedFileUrl().endsWith("brdc_last.rnx.Z"));
            assertTrue(result.parsed().latestByPrn().containsKey("G01"));
            assertTrue(result.parsed().latestByPrn().get("G01").healthy());
        } finally {
            server.stop(0);
        }
    }

    private static byte[] unixCompressLiteral9Bit(String text) {
        byte[] raw = text.getBytes(StandardCharsets.US_ASCII);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(0x1F);
        out.write(0x9D);
        out.write(0x8C); // blockMode=true, maxBits=12
        long bitBuf = 0L;
        int bitCount = 0;
        int emittedSinceClear = 0;
        for (byte b : raw) {
            if (emittedSinceClear >= 200) {
                bitBuf |= (256L << bitCount);
                bitCount += 9;
                while (bitCount >= 8) {
                    out.write((int) (bitBuf & 0xFFL));
                    bitBuf >>>= 8;
                    bitCount -= 8;
                }
                emittedSinceClear = 0;
            }
            int code = b & 0xFF;
            bitBuf |= ((long) code << bitCount);
            bitCount += 9;
            while (bitCount >= 8) {
                out.write((int) (bitBuf & 0xFFL));
                bitBuf >>>= 8;
                bitCount -= 8;
            }
            emittedSinceClear++;
        }
        if (bitCount > 0) {
            out.write((int) (bitBuf & 0xFFL));
        }
        return out.toByteArray();
    }
}

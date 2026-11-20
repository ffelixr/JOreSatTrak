package jsattrak.awareness.tle;

import java.io.IOException;
import java.io.StringReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import jsattrak.awareness.config.DataSourceConfig;
import jsattrak.awareness.net.ConnectionTestResult;
import jsattrak.awareness.net.HttpRequestAuthApplier;
import jsattrak.utilities.OmmDataLoader;
import jsattrak.utilities.TLE;

/**
 * HTTP client for fetching and validating 3-line TLE and OMM CSV satellite element feeds.
 */
public class TleFeedClient {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public TleFeedClient() {
        this(HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                        .build(),
                DEFAULT_REQUEST_TIMEOUT);
    }

    public TleFeedClient(HttpClient httpClient, Duration requestTimeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.requestTimeout = requestTimeout != null ? requestTimeout : DEFAULT_REQUEST_TIMEOUT;
    }

    /**
     * Fetches and parses a TLE catalog (3-line TLE text or OMM CSV) from the configured source.
     */
    public List<TLE> fetchTleCatalog(DataSourceConfig source) throws IOException {
        if (source == null || source.getEndpointUrl() == null || source.getEndpointUrl().isBlank()) {
            throw new IOException("TLE source endpoint URL is empty");
        }
        HttpRequest request;
        try {
            request = HttpRequestAuthApplier.buildRequest(
                    source.getEndpointUrl(), source, requestTimeout);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid TLE source endpoint URL: " + e.getMessage(), e);
        }
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("TLE feed request interrupted", e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " from " + sourceLabel(source));
        }
        return parseCatalogResponse(response.body());
    }

    /**
     * Tests connectivity, authentication, and TLE/OMM CSV parsing for a single TLE source.
     */
    public ConnectionTestResult testConnection(DataSourceConfig source) {
        if (source == null || source.getEndpointUrl() == null || source.getEndpointUrl().isBlank()) {
            return new ConnectionTestResult(false, 0, 0, "Endpoint URL is empty");
        }
        try {
            HttpRequest request = HttpRequestAuthApplier.buildRequest(
                    source.getEndpointUrl(), source, requestTimeout);
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                try {
                    List<TLE> parsed = parseCatalogResponse(response.body());
                    return new ConnectionTestResult(true, status, parsed.size(),
                            "OK (" + parsed.size() + " TLEs parsed)");
                } catch (IOException e) {
                    return new ConnectionTestResult(false, status, 0, e.getMessage());
                }
            }
            return new ConnectionTestResult(false, status, 0, "HTTP " + status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ConnectionTestResult(false, 0, 0, "Connection test interrupted");
        } catch (Exception e) {
            String msg = e.getMessage();
            return new ConnectionTestResult(false, 0, 0,
                    (msg != null && !msg.isBlank()) ? msg : e.getClass().getSimpleName());
        }
    }

    static List<TLE> parseCatalogResponse(String body) throws IOException {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        String cleaned = stripUtf8Bom(body);
        if (looksLikeHtml(cleaned)) {
            throw new IOException("HTML response received; expected 3LE text or OMM CSV");
        }
        String firstLine = firstNonEmptyLine(cleaned);
        if (isOmmCsvHeader(firstLine)) {
            try {
                return List.copyOf(OmmDataLoader.parseOmmCsv(new StringReader(cleaned)));
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Failed to parse OMM CSV catalog: " + e.getMessage(), e);
            }
        }
        return PayloadTleMatcher.parseThreeLineTles(cleaned);
    }

    private static boolean isOmmCsvHeader(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        String upper = line.toUpperCase(Locale.ROOT);
        return upper.startsWith("OBJECT_NAME,")
                || (upper.contains("OBJECT_NAME") && upper.contains("NORAD_CAT_ID") && upper.contains(","));
    }

    private static boolean looksLikeHtml(String body) {
        if (body == null) {
            return false;
        }
        String first = firstNonEmptyLine(stripUtf8Bom(body)).toLowerCase(Locale.ROOT);
        return first.startsWith("<!doctype html") || first.startsWith("<html");
    }

    private static String stripUtf8Bom(String s) {
        return (!s.isEmpty() && s.charAt(0) == '\uFEFF') ? s.substring(1) : s;
    }

    private static String firstNonEmptyLine(String text) {
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return "";
    }

    private static String sourceLabel(DataSourceConfig source) {
        String name = source.getDisplayName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        String id = source.getId();
        return (id != null && !id.isBlank()) ? id : source.getEndpointUrl();
    }
}

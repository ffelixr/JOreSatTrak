package jsattrak.awareness.gnss.net;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import jsattrak.awareness.config.DataSourceConfig;
import jsattrak.awareness.gnss.GnssCatalogEntry;
import jsattrak.awareness.net.ConnectionTestResult;
import jsattrak.awareness.net.HttpRequestAuthApplier;

/**
 * HTTP client for fetching IGS SINEX satellite metadata with priority failover.
 */
public class GnssCatalogClient {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public GnssCatalogClient() {
        this(HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                        .build(),
                DEFAULT_REQUEST_TIMEOUT);
    }

    public GnssCatalogClient(HttpClient httpClient, Duration requestTimeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.requestTimeout = requestTimeout != null ? requestTimeout : DEFAULT_REQUEST_TIMEOUT;
    }

    public record CatalogFetchResult(List<GnssCatalogEntry> entries, String sourceName) {
    }

    public CatalogFetchResult fetchCatalog(List<DataSourceConfig> sources, long referenceEpochMillis)
            throws IOException {
        if (sources == null || sources.isEmpty()) {
            throw new IOException("No enabled GNSS catalog sources configured");
        }
        IOException lastFailure = null;
        for (DataSourceConfig source : sources) {
            if (source == null || !source.isEnabled()) {
                continue;
            }
            try {
                List<GnssCatalogEntry> entries = fetchFromSingleSource(source, referenceEpochMillis);
                return new CatalogFetchResult(entries, sourceLabel(source));
            } catch (IOException | RuntimeException e) {
                lastFailure = (e instanceof IOException io) ? io : new IOException(e.getMessage(), e);
            }
        }
        throw lastFailure != null ? lastFailure : new IOException("All GNSS catalog sources failed");
    }

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
                String body = response.body();
                if (looksLikeHtml(body)) {
                    return new ConnectionTestResult(false, status, 0, "HTML response received; expected SINEX");
                }
                List<GnssCatalogEntry> parsed = IgsMetadataParser.parseSinex(body, System.currentTimeMillis());
                if (parsed.isEmpty()) {
                    return new ConnectionTestResult(false, status, 0, "No active GNSS satellites parsed");
                }
                return new ConnectionTestResult(true, status, parsed.size(),
                        "OK (" + parsed.size() + " satellites parsed)");
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

    private List<GnssCatalogEntry> fetchFromSingleSource(DataSourceConfig source, long referenceEpochMillis)
            throws IOException {
        if (source.getEndpointUrl() == null || source.getEndpointUrl().isBlank()) {
            throw new IOException("Catalog endpoint URL is empty");
        }
        HttpRequest request;
        try {
            request = HttpRequestAuthApplier.buildRequest(source.getEndpointUrl(), source, requestTimeout);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid catalog endpoint URL: " + e.getMessage(), e);
        }
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Catalog request interrupted", e);
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " from " + sourceLabel(source));
        }
        String body = response.body();
        if (looksLikeHtml(body)) {
            throw new IOException("HTML response received from " + sourceLabel(source));
        }
        List<GnssCatalogEntry> entries = IgsMetadataParser.parseSinex(body, referenceEpochMillis);
        if (entries.isEmpty()) {
            throw new IOException("No GNSS satellites parsed from " + sourceLabel(source));
        }
        return entries;
    }

    private static boolean looksLikeHtml(String body) {
        if (body == null) {
            return false;
        }
        String trimmed = body.stripLeading().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("<!doctype html") || trimmed.startsWith("<html");
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

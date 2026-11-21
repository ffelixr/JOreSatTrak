package jsattrak.awareness.gnss.net;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jsattrak.awareness.config.DataSourceConfig;
import jsattrak.awareness.net.ConnectionTestResult;
import jsattrak.awareness.net.HttpRequestAuthApplier;

/**
 * HTTP client for multi-GNSS broadcast navigation RINEX feeds (direct files and BKG-style HTML
 * directory indexes) with priority-ordered failover.
 */
public class BroadcastNavClient {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern HREF_PATTERN = Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAV_FILE_PATTERN = Pattern.compile(
            ".*(?:\\.rnx(?:\\.gz)?|brdc.*\\.(?:gz|rnx|p|n)|brdm.*\\.(?:gz|rnx|p))$",
            Pattern.CASE_INSENSITIVE);

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public BroadcastNavClient() {
        this(HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                        .build(),
                DEFAULT_REQUEST_TIMEOUT);
    }

    public BroadcastNavClient(HttpClient httpClient, Duration requestTimeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.requestTimeout = requestTimeout != null ? requestTimeout : DEFAULT_REQUEST_TIMEOUT;
    }

    public record BroadcastFetchResult(
            RinexNavHealthParser.ParseResult parsed,
            String sourceName,
            String resolvedFileUrl) {
    }

    public BroadcastFetchResult fetchLatest(List<DataSourceConfig> sources) throws IOException {
        if (sources == null || sources.isEmpty()) {
            throw new IOException("No enabled GNSS broadcast navigation sources configured");
        }
        IOException lastFailure = null;
        for (DataSourceConfig source : sources) {
            if (source == null || !source.isEnabled()) {
                continue;
            }
            try {
                return fetchFromSingleSource(source);
            } catch (IOException | RuntimeException e) {
                lastFailure = (e instanceof IOException io) ? io : new IOException(e.getMessage(), e);
            }
        }
        throw lastFailure != null ? lastFailure : new IOException("All GNSS broadcast nav sources failed");
    }

    public ConnectionTestResult testConnection(DataSourceConfig source) {
        if (source == null || source.getEndpointUrl() == null || source.getEndpointUrl().isBlank()) {
            return new ConnectionTestResult(false, 0, 0, "Endpoint URL is empty");
        }
        try {
            BroadcastFetchResult result = fetchFromSingleSource(source);
            int satCount = result.parsed().latestByPrn().size();
            return new ConnectionTestResult(true, 200, satCount,
                    "OK (" + satCount + " satellites in broadcast nav)");
        } catch (IOException e) {
            return new ConnectionTestResult(false, extractHttpStatus(e.getMessage()), 0, e.getMessage());
        } catch (Exception e) {
            String msg = e.getMessage();
            return new ConnectionTestResult(false, 0, 0,
                    (msg != null && !msg.isBlank()) ? msg : e.getClass().getSimpleName());
        }
    }

    private BroadcastFetchResult fetchFromSingleSource(DataSourceConfig source) throws IOException {
        if (source.getEndpointUrl() == null || source.getEndpointUrl().isBlank()) {
            throw new IOException("Broadcast nav endpoint URL is empty");
        }
        byte[] firstBytes = sendGetBytes(source.getEndpointUrl(), source);
        String resolvedUrl = source.getEndpointUrl().trim();

        if (!isGzip(firstBytes) && looksLikeHtml(firstBytes)) {
            String html = new String(firstBytes, StandardCharsets.UTF_8);
            String newestHref = findNewestNavLink(html);
            if (newestHref == null) {
                throw new IOException("HTML response without RINEX navigation links from " + sourceLabel(source));
            }
            resolvedUrl = URI.create(resolvedUrl).resolve(newestHref).toString();
            firstBytes = sendGetBytes(resolvedUrl, source);
            if (!isGzip(firstBytes) && looksLikeHtml(firstBytes)) {
                throw new IOException("Resolved navigation link returned HTML from " + sourceLabel(source));
            }
        }

        RinexNavHealthParser.ParseResult parsed;
        try (InputStream in = new ByteArrayInputStream(firstBytes)) {
            parsed = RinexNavHealthParser.parse(in);
        }
        if (parsed.latestByPrn().isEmpty()) {
            throw new IOException("No valid GNSS navigation health records parsed from " + sourceLabel(source));
        }
        return new BroadcastFetchResult(parsed, sourceLabel(source), resolvedUrl);
    }

    private byte[] sendGetBytes(String url, DataSourceConfig source) throws IOException {
        HttpRequest request;
        try {
            request = HttpRequestAuthApplier.buildRequest(url, source, requestTimeout);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid broadcast nav URL: " + e.getMessage(), e);
        }
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Broadcast nav request interrupted", e);
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " from " + sourceLabel(source));
        }
        return response.body() != null ? response.body() : new byte[0];
    }

    static String findNewestNavLink(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        Matcher m = HREF_PATTERN.matcher(html);
        while (m.find()) {
            String href = m.group(1).trim();
            if (href.isEmpty() || href.startsWith("?") || href.startsWith("#") || href.endsWith("/")) {
                continue;
            }
            String filename = href;
            int slash = filename.lastIndexOf('/');
            if (slash >= 0) {
                filename = filename.substring(slash + 1);
            }
            if (NAV_FILE_PATTERN.matcher(filename).matches()) {
                candidates.add(href);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        // Prefer MN (mixed multi-GNSS) and latest lexicographical timestamp/DOY filename
        candidates.sort(Comparator.comparing((String h) -> h.toUpperCase(Locale.ROOT).contains("_MN.") ? 1 : 0)
                .thenComparing(Comparator.naturalOrder())
                .reversed());
        return candidates.get(0);
    }

    private static boolean isGzip(byte[] bytes) {
        return bytes != null && bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
    }

    private static boolean looksLikeHtml(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        String prefix = new String(bytes, 0, Math.min(bytes.length, 512), StandardCharsets.UTF_8)
                .stripLeading()
                .toLowerCase(Locale.ROOT);
        return prefix.startsWith("<!doctype") || prefix.startsWith("<html") || prefix.contains("<a href");
    }

    private static int extractHttpStatus(String msg) {
        if (msg != null && msg.startsWith("HTTP ")) {
            String[] parts = msg.split("\\s+");
            if (parts.length >= 2) {
                try {
                    return Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
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

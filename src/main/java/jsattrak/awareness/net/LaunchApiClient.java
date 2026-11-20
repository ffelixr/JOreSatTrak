package jsattrak.awareness.net;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import jsattrak.awareness.LaunchRecord;
import jsattrak.awareness.LaunchTimeEvaluator;
import jsattrak.awareness.config.DataSourceConfig;

/**
 * HTTP client for fetching upcoming and recent global launches from Launch Library 2
 * endpoints with priority-ordered failover across enabled sources.
 */
public class LaunchApiClient {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final long UPCOMING_LOOKBACK_MILLIS = 24L * 3600_000L;
    private static final long UPCOMING_LOOKAHEAD_MILLIS = 14L * 24L * 3600_000L;
    private static final int UPCOMING_LIMIT = 100;
    private static final int RECENT_LIMIT = 50;
    private static final int TEST_LIMIT = 10;

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public LaunchApiClient() {
        this(HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                        .build(),
                DEFAULT_REQUEST_TIMEOUT);
    }

    public LaunchApiClient(HttpClient httpClient, Duration requestTimeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.requestTimeout = requestTimeout != null ? requestTimeout : DEFAULT_REQUEST_TIMEOUT;
    }

    /**
     * Fetches upcoming and recent launches using enabled sources in ascending priority order.
     */
    public LaunchFetchResult fetchUpcomingAndRecent(List<DataSourceConfig> prioritizedSources, long realNowMillis)
            throws IOException {
        List<DataSourceConfig> enabled = filterAndSortEnabled(prioritizedSources);
        if (enabled.isEmpty()) {
            throw new IOException("No enabled launch data sources configured");
        }

        IOException lastFailure = null;
        for (DataSourceConfig source : enabled) {
            try {
                String upcomingUrl = buildUpcomingUrl(source.getEndpointUrl(), realNowMillis);
                String recentUrl = buildRecentUrl(source.getEndpointUrl(), realNowMillis);

                String upcomingBody = executeGetWithFailoverCheck(upcomingUrl, source);
                String recentBody = executeGetWithFailoverCheck(recentUrl, source);

                List<LaunchRecord> upcoming = LaunchLibrary2Parser.parseResults(upcomingBody);
                List<LaunchRecord> recent = LaunchLibrary2Parser.parseResults(recentBody);
                String sourceLabel = sourceLabel(source);
                return new LaunchFetchResult(upcoming, recent, sourceLabel);
            } catch (FailoverException e) {
                lastFailure = e.asIOException();
            }
        }

        throw lastFailure != null ? lastFailure : new IOException("All launch data sources failed");
    }

    /**
     * Tests connectivity, authentication, and JSON parsing for a single launch data source.
     */
    public ConnectionTestResult testConnection(DataSourceConfig source) {
        if (source == null || source.getEndpointUrl() == null || source.getEndpointUrl().isBlank()) {
            return new ConnectionTestResult(false, 0, 0, "Endpoint URL is empty");
        }
        try {
            String testUrl = buildTestUrl(source.getEndpointUrl());
            HttpRequest request = HttpRequestAuthApplier.buildRequest(testUrl, source, requestTimeout);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                List<LaunchRecord> parsed = LaunchLibrary2Parser.parseResults(response.body());
                return new ConnectionTestResult(true, status, parsed.size(),
                        "OK (" + parsed.size() + " launches parsed)");
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

    private String executeGetWithFailoverCheck(String url, DataSourceConfig source)
            throws IOException, FailoverException {
        HttpRequest request = HttpRequestAuthApplier.buildRequest(url, source, requestTimeout);
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Launch API request interrupted", e);
        } catch (IOException e) {
            throw new FailoverException(
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), e);
        }

        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return response.body();
        }
        String label = sourceLabel(source);
        if (status == 429 || (status >= 500 && status < 600)) {
            throw new FailoverException("HTTP " + status + " from " + label);
        }
        throw new IOException("HTTP " + status + " from " + label);
    }

    private static List<DataSourceConfig> filterAndSortEnabled(List<DataSourceConfig> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        return sources.stream()
                .filter(Objects::nonNull)
                .filter(DataSourceConfig::isEnabled)
                .filter(s -> s.getEndpointUrl() != null && !s.getEndpointUrl().isBlank())
                .sorted(Comparator.comparingInt(DataSourceConfig::getPriority))
                .toList();
    }

    static String buildUpcomingUrl(String endpointUrl, long realNowMillis) {
        EndpointParts parts = splitEndpoint(endpointUrl);
        if (endsWithSegment(parts.basePath, "upcoming")) {
            return endpointUrl.trim();
        }
        if (endsWithSegment(parts.basePath, "previous")) {
            String siblingPath = replaceTrailingSegment(parts.basePath, "previous", "upcoming");
            return combinePathAndQuery(siblingPath, parts.existingQuery);
        }
        String gte = formatIsoSecond(realNowMillis - UPCOMING_LOOKBACK_MILLIS);
        String lte = formatIsoSecond(realNowMillis + UPCOMING_LOOKAHEAD_MILLIS);
        String query = "window_start__gte=" + gte + "&window_start__lte=" + lte + "&limit=" + UPCOMING_LIMIT;
        return appendEndpointSegment(parts, "upcoming/", query);
    }

    static String buildRecentUrl(String endpointUrl, long realNowMillis) {
        EndpointParts parts = splitEndpoint(endpointUrl);
        if (endsWithSegment(parts.basePath, "previous")) {
            return endpointUrl.trim();
        }
        if (endsWithSegment(parts.basePath, "upcoming")) {
            String siblingPath = replaceTrailingSegment(parts.basePath, "upcoming", "previous");
            return combinePathAndQuery(siblingPath, parts.existingQuery);
        }
        String gte = formatIsoSecond(realNowMillis - LaunchTimeEvaluator.RECENT_WINDOW_MILLIS);
        String lte = formatIsoSecond(realNowMillis);
        String query = "net__gte=" + gte + "&net__lte=" + lte + "&limit=" + RECENT_LIMIT;
        return appendEndpointSegment(parts, "previous/", query);
    }

    static String buildTestUrl(String endpointUrl) {
        EndpointParts parts = splitEndpoint(endpointUrl);
        if (endsWithSegment(parts.basePath, "upcoming") || endsWithSegment(parts.basePath, "previous")) {
            return endpointUrl.trim();
        }
        return appendEndpointSegment(parts, "upcoming/", "limit=" + TEST_LIMIT);
    }

    private static String appendEndpointSegment(EndpointParts parts, String segment, String extraQuery) {
        String normalizedBase = parts.basePath.endsWith("/") ? parts.basePath : parts.basePath + "/";
        String fullPath = normalizedBase + segment;
        String combinedQuery;
        if (parts.existingQuery.isEmpty()) {
            combinedQuery = extraQuery;
        } else if (extraQuery.isEmpty()) {
            combinedQuery = parts.existingQuery;
        } else {
            combinedQuery = parts.existingQuery + "&" + extraQuery;
        }
        return combinePathAndQuery(fullPath, combinedQuery);
    }

    private static String combinePathAndQuery(String path, String query) {
        return (query == null || query.isEmpty()) ? path : path + "?" + query;
    }

    private static boolean endsWithSegment(String path, String segment) {
        return path.endsWith("/" + segment + "/") || path.endsWith("/" + segment);
    }

    private static String replaceTrailingSegment(String path, String oldSegment, String newSegment) {
        if (path.endsWith("/" + oldSegment + "/")) {
            return path.substring(0, path.length() - oldSegment.length() - 1) + newSegment + "/";
        }
        if (path.endsWith("/" + oldSegment)) {
            return path.substring(0, path.length() - oldSegment.length()) + newSegment;
        }
        return path;
    }

    private static EndpointParts splitEndpoint(String endpointUrl) {
        String trimmed = endpointUrl.trim();
        int qIdx = trimmed.indexOf('?');
        if (qIdx < 0) {
            return new EndpointParts(trimmed, "");
        }
        return new EndpointParts(trimmed.substring(0, qIdx), trimmed.substring(qIdx + 1));
    }

    private static String formatIsoSecond(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static String sourceLabel(DataSourceConfig source) {
        String name = source.getDisplayName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        String id = source.getId();
        return (id != null && !id.isBlank()) ? id : source.getEndpointUrl();
    }

    private record EndpointParts(String basePath, String existingQuery) {
    }

    private static final class FailoverException extends Exception {
        FailoverException(String message) {
            super(message);
        }

        FailoverException(String message, Throwable cause) {
            super(message, cause);
        }

        IOException asIOException() {
            Throwable cause = getCause();
            return (cause instanceof IOException ioe) ? ioe : new IOException(getMessage(), this);
        }
    }

    /**
     * Combined result of fetching upcoming and recent launch feeds from a single active source.
     */
    public record LaunchFetchResult(List<LaunchRecord> upcoming, List<LaunchRecord> recent, String sourceUsed) {
        public LaunchFetchResult {
            upcoming = upcoming == null ? List.of() : List.copyOf(upcoming);
            recent = recent == null ? List.of() : List.copyOf(recent);
            sourceUsed = sourceUsed == null ? "" : sourceUsed;
        }
    }
}

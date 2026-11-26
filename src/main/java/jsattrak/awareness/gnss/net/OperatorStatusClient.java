package jsattrak.awareness.gnss.net;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jsattrak.awareness.config.DataSourceConfig;
import jsattrak.awareness.gnss.GnssOutageNotice;
import jsattrak.awareness.gnss.OperatorStatusRecord;
import jsattrak.awareness.net.ConnectionTestResult;
import jsattrak.awareness.net.HttpRequestAuthApplier;

/**
 * Scope-grouped failover HTTP client for operator constellation status and outage notices
 * (NAVCEN GPS status/NANU and GSC Galileo status/NAGU).
 */
public class OperatorStatusClient {

    public static final String SCOPE_GPS_STATUS = "GPS_STATUS";
    public static final String SCOPE_GPS_NANU = "GPS_NANU";
    public static final String SCOPE_GALILEO_STATUS = "GALILEO_STATUS";
    public static final String SCOPE_GALILEO_NAGU = "GALILEO_NAGU";

    private static final int MAX_NANU_FILES_PER_FETCH = 40;
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern HREF_PATTERN = Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public OperatorStatusClient() {
        this(HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                        .build(),
                DEFAULT_REQUEST_TIMEOUT);
    }

    public OperatorStatusClient(HttpClient httpClient, Duration requestTimeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.requestTimeout = requestTimeout != null ? requestTimeout : DEFAULT_REQUEST_TIMEOUT;
    }

    public record OperatorFetchResult(
            Map<String, OperatorStatusRecord> statusByPrn,
            List<GnssOutageNotice> notices,
            boolean gpsNoticesAvailable,
            boolean galileoNoticesAvailable,
            Map<String, String> scopeErrors,
            Map<String, String> scopeSources) {
    }

    /**
     * Fetches each configured operator scope independently with priority failover inside each scope.
     * A failure in one scope never aborts the remaining scopes.
     */
    public OperatorFetchResult fetchAll(Map<String, List<DataSourceConfig>> scopedSources, long nowMillis) {
        Map<String, OperatorStatusRecord> statusByPrn = new LinkedHashMap<>();
        List<GnssOutageNotice> allNotices = new ArrayList<>();
        Map<String, String> scopeErrors = new LinkedHashMap<>();
        Map<String, String> scopeSources = new LinkedHashMap<>();
        boolean gpsNoticesAvailable = false;
        boolean galileoNoticesAvailable = false;

        if (scopedSources == null || scopedSources.isEmpty()) {
            return new OperatorFetchResult(Map.of(), List.of(), false, false, Map.of(), Map.of());
        }

        for (Map.Entry<String, List<DataSourceConfig>> entry : scopedSources.entrySet()) {
            String scope = entry.getKey() != null ? entry.getKey().trim().toUpperCase(Locale.ROOT) : "";
            List<DataSourceConfig> sources = entry.getValue();
            if (sources == null || sources.isEmpty()) {
                continue;
            }
            try {
                String usedSource = fetchScopeWithFailover(
                        scope, sources, nowMillis, statusByPrn, allNotices);
                scopeSources.put(scope, usedSource);
                if (SCOPE_GPS_NANU.equals(scope)) {
                    gpsNoticesAvailable = true;
                } else if (SCOPE_GALILEO_NAGU.equals(scope)) {
                    galileoNoticesAvailable = true;
                }
            } catch (IOException | RuntimeException e) {
                scopeErrors.put(scope, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }

        return new OperatorFetchResult(
                Map.copyOf(statusByPrn),
                List.copyOf(allNotices),
                gpsNoticesAvailable,
                galileoNoticesAvailable,
                Map.copyOf(scopeErrors),
                Map.copyOf(scopeSources));
    }

    public ConnectionTestResult testConnection(DataSourceConfig source) {
        if (source == null || source.getEndpointUrl() == null || source.getEndpointUrl().isBlank()) {
            return new ConnectionTestResult(false, 0, 0, "Endpoint URL is empty");
        }
        String scope = source.getProviderScope() != null
                ? source.getProviderScope().trim().toUpperCase(Locale.ROOT)
                : "";
        try {
            Map<String, OperatorStatusRecord> statuses = new LinkedHashMap<>();
            List<GnssOutageNotice> notices = new ArrayList<>();
            fetchSingleSource(scope, source, System.currentTimeMillis(), statuses, notices);
            int count = statuses.size() + notices.size();
            return new ConnectionTestResult(true, 200, count, "OK (" + count + " records parsed)");
        } catch (IOException e) {
            return new ConnectionTestResult(false, 0, 0, e.getMessage());
        } catch (Exception e) {
            String msg = e.getMessage();
            return new ConnectionTestResult(false, 0, 0,
                    (msg != null && !msg.isBlank()) ? msg : e.getClass().getSimpleName());
        }
    }

    private String fetchScopeWithFailover(
            String scope,
            List<DataSourceConfig> sources,
            long nowMillis,
            Map<String, OperatorStatusRecord> statusOut,
            List<GnssOutageNotice> noticesOut) throws IOException {
        IOException lastFailure = null;
        for (DataSourceConfig source : sources) {
            if (source == null || !source.isEnabled()) {
                continue;
            }
            try {
                fetchSingleSource(scope, source, nowMillis, statusOut, noticesOut);
                return sourceLabel(source);
            } catch (IOException | RuntimeException e) {
                lastFailure = (e instanceof IOException io) ? io : new IOException(e.getMessage(), e);
            }
        }
        throw lastFailure != null ? lastFailure : new IOException("No enabled sources for scope " + scope);
    }

    private void fetchSingleSource(
            String scope,
            DataSourceConfig source,
            long nowMillis,
            Map<String, OperatorStatusRecord> statusOut,
            List<GnssOutageNotice> noticesOut) throws IOException {
        String url = source.getEndpointUrl().trim();
        String body;
        try {
            body = sendGetString(url, source);
        } catch (IOException e) {
            if (SCOPE_GPS_NANU.equals(scope)
                    && url.toLowerCase(Locale.ROOT).contains("navcen.uscg.gov/sites/default/files/gps/nanu")) {
                url = "https://www.navcen.uscg.gov/gps-constellation";
                body = sendGetString(url, source);
            } else {
                throw e;
            }
        }

        if (SCOPE_GPS_STATUS.equals(scope)) {
            List<OperatorStatusRecord> parsed = NavcenParser.parseConstellationStatus(body, nowMillis);
            if (parsed.isEmpty()) {
                throw new IOException("No GPS status rows parsed from " + sourceLabel(source));
            }
            parsed.forEach(r -> statusOut.put(r.prnId(), r));
        } else if (SCOPE_GPS_NANU.equals(scope)) {
            List<GnssOutageNotice> parsed = fetchNanuDirectoryOrSingle(url, source, body, nowMillis);
            noticesOut.addAll(parsed);
        } else if (SCOPE_GALILEO_STATUS.equals(scope)) {
            List<OperatorStatusRecord> parsed = GscGalileoParser.parseConstellationStatus(body, nowMillis);
            if (parsed.isEmpty()) {
                throw new IOException("No Galileo status rows parsed from " + sourceLabel(source));
            }
            parsed.forEach(r -> statusOut.put(r.prnId(), r));
        } else if (SCOPE_GALILEO_NAGU.equals(scope)) {
            List<GnssOutageNotice> parsed;
            try {
                parsed = GscGalileoParser.parseNagus(body);
            } catch (IOException e) {
                if (url.toLowerCase(Locale.ROOT).contains("/system-service-status/nagu-information")) {
                    String activeBody = sendGetString("https://www.gsc-europa.eu/system-status/user-notifications", source);
                    parsed = GscGalileoParser.parseNagus(activeBody);
                } else {
                    throw e;
                }
            }
            noticesOut.addAll(parsed);
        } else {
            // Fallback inference from URL/content if scope wasn't set by user
            List<OperatorStatusRecord> gsc = GscGalileoParser.parseConstellationStatus(body, nowMillis);
            if (!gsc.isEmpty()) {
                gsc.forEach(r -> statusOut.put(r.prnId(), r));
                return;
            }
            List<OperatorStatusRecord> navcen = NavcenParser.parseConstellationStatus(body, nowMillis);
            if (!navcen.isEmpty()) {
                navcen.forEach(r -> statusOut.put(r.prnId(), r));
                return;
            }
            throw new IOException("Unrecognized operator status format from " + sourceLabel(source));
        }
    }

    private List<GnssOutageNotice> fetchNanuDirectoryOrSingle(
            String resolvedUrl,
            DataSourceConfig source,
            String body,
            long nowMillis) throws IOException {
        Optional<GnssOutageNotice> single = NavcenParser.parseSingleNanu(body);
        if (single.isPresent()) {
            return NavcenParser.applyCancellations(List.of(single.get()));
        }

        List<GnssOutageNotice> tableNanus = NavcenParser.parseHtmlTableNanus(body);
        if (!tableNanus.isEmpty()) {
            return tableNanus;
        }

        URI baseUri = URI.create(resolvedUrl);
        List<String> nnuLinks = extractLinks(body, ".nnu");
        if (nnuLinks.isEmpty()) {
            int year = Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC).getYear();
            String yearToken = year + "/";
            if (body.contains(yearToken) || baseUri.getPath().endsWith("/nanu/")) {
                URI yearUri = baseUri.resolve(yearToken);
                String yearBody = sendGetString(yearUri.toString(), source);
                baseUri = yearUri;
                nnuLinks = extractLinks(yearBody, ".nnu");
            }
        }
        if (nnuLinks.isEmpty()) {
            throw new IOException("No NANU files found at " + sourceLabel(source));
        }
        nnuLinks.sort(Comparator.reverseOrder());
        int limit = Math.min(MAX_NANU_FILES_PER_FETCH, nnuLinks.size());
        List<GnssOutageNotice> raw = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            String targetUrl = baseUri.resolve(nnuLinks.get(i)).toString();
            try {
                String nanuText = sendGetString(targetUrl, source);
                NavcenParser.parseSingleNanu(nanuText).ifPresent(raw::add);
            } catch (IOException ignored) {
            }
        }
        return NavcenParser.applyCancellations(raw);
    }

    private String sendGetString(String url, DataSourceConfig source) throws IOException {
        HttpRequest request;
        try {
            request = HttpRequestAuthApplier.buildRequest(url, source, requestTimeout);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid endpoint URL: " + e.getMessage(), e);
        }
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Operator status request interrupted", e);
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " from " + sourceLabel(source));
        }
        return response.body() != null ? response.body() : "";
    }

    private static List<String> extractLinks(String html, String suffix) {
        List<String> links = new ArrayList<>();
        Matcher m = HREF_PATTERN.matcher(html);
        while (m.find()) {
            String href = m.group(1).trim();
            if (href.toLowerCase(Locale.ROOT).endsWith(suffix)) {
                links.add(href);
            }
        }
        return links;
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

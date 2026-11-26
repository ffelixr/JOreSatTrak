package jsattrak.awareness.gnss.stations.obs;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jsattrak.awareness.config.DataSourceConfig;
import jsattrak.awareness.gnss.stations.GnssReferenceStation;
import jsattrak.awareness.gnss.stations.GnssStationNetwork;
import jsattrak.awareness.net.HttpRequestAuthApplier;

/**
 * {@link GnssObservationSource} querying BKG HTTPS hourly NRT archives ({@code nrt/{DOY}/{HH}/})
 * and historical daily archives ({@code obs/{YYYY}/{DOY}/}) for IGS and EUREF stations.
 */
public class BkgHourlyRinexSource implements GnssObservationSource {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final long ONE_DAY_MILLIS = 24L * 3600L * 1000L;
    private static final Pattern HREF_PATTERN =
            Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private final Supplier<List<DataSourceConfig>> archiveSourcesSupplier;
    private final GnssObservationDiskCache diskCache;
    private final HttpClient httpClient;
    private final Map<String, String> lastResolvedUriByStation = new ConcurrentHashMap<>();

    public BkgHourlyRinexSource(
            Supplier<List<DataSourceConfig>> archiveSourcesSupplier,
            GnssObservationDiskCache diskCache) {
        this(archiveSourcesSupplier, diskCache,
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(CONNECT_TIMEOUT)
                        .build());
    }

    public BkgHourlyRinexSource(
            Supplier<List<DataSourceConfig>> archiveSourcesSupplier,
            GnssObservationDiskCache diskCache,
            HttpClient httpClient) {
        this.archiveSourcesSupplier = Objects.requireNonNull(archiveSourcesSupplier, "archiveSourcesSupplier");
        this.diskCache = diskCache;
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public String getSourceId() {
        return "bkg-hourly-rinex";
    }

    @Override
    public String getDisplayName() {
        return "BKG Hourly / Historical RINEX";
    }

    @Override
    public boolean supportsNetwork(GnssStationNetwork network) {
        return network == GnssStationNetwork.IGS || network == GnssStationNetwork.EUREF;
    }

    @Override
    public boolean isRealTimeStream() {
        return false;
    }

    @Override
    public ObservationFetchResult fetchObservations(GnssReferenceStation station, ObservationQuery query)
            throws IOException {
        if (station == null) {
            throw new IOException("Station is required");
        }
        long nowMillis = System.currentTimeMillis();
        long targetMillis = (query != null && query.targetEpochMillis() > 0L) ? query.targetEpochMillis() : nowMillis;
        boolean preferLatest = query == null || query.preferLatestAvailable();
        boolean useHistoricalDaily = !preferLatest && (nowMillis - targetMillis > ONE_DAY_MILLIS);

        List<DataSourceConfig> matching = findMatchingSources(station);
        if (matching.isEmpty()) {
            throw new IOException("No enabled BKG observation archive configured for " + station.stationId());
        }

        IOException lastFailure = null;
        for (DataSourceConfig src : matching) {
            String base = src.getEndpointUrl().trim();
            if (!base.endsWith("/")) {
                base += "/";
            }
            List<String> candidateDirs = buildCandidateDirectoryUrls(base, targetMillis, useHistoricalDaily);
            for (String dirUrl : candidateDirs) {
                try {
                    String html = sendGetText(dirUrl, src);
                    String fileHref = selectBestStationFile(html, station);
                    if (fileHref == null) {
                        continue;
                    }
                    String fileUrl = URI.create(dirUrl).resolve(fileHref).toString();
                    byte[] payload = diskCache != null ? diskCache.get(fileUrl, nowMillis) : null;
                    if (payload == null) {
                        payload = sendGetBytes(fileUrl, src);
                        if (diskCache != null) {
                            diskCache.put(fileUrl, payload, nowMillis);
                        }
                    }
                    if (preferLatest) {
                        lastResolvedUriByStation.put(station.stationId(), fileUrl);
                    }
                    String fname = extractFileName(fileUrl);
                    RinexObservationData obs = RinexObservationLoader.parse(payload, fname);
                    return new ObservationFetchResult(
                            station, obs, src.getDisplayName(), fileUrl, nowMillis, formatLabel(fname));
                } catch (IOException | RuntimeException e) {
                    lastFailure = (e instanceof IOException io) ? io : new IOException(e.getMessage(), e);
                }
            }
        }

        // Check hint URL fallback
        if (!useHistoricalDaily && station.observationHintUrl() != null && !station.observationHintUrl().isBlank()) {
            try {
                String hintUrl = station.observationHintUrl();
                byte[] payload = diskCache != null ? diskCache.get(hintUrl, nowMillis) : null;
                if (payload == null) {
                    payload = sendGetBytes(hintUrl, matching.get(0));
                    if (diskCache != null) {
                        diskCache.put(hintUrl, payload, nowMillis);
                    }
                }
                RinexObservationData obs = RinexObservationLoader.parse(payload, extractFileName(hintUrl));
                if (preferLatest) {
                    lastResolvedUriByStation.put(station.stationId(), hintUrl);
                }
                return new ObservationFetchResult(
                        station, obs, getDisplayName(), hintUrl, nowMillis, formatLabel(hintUrl));
            } catch (IOException | RuntimeException e) {
                lastFailure = (e instanceof IOException io) ? io : new IOException(e.getMessage(), e);
            }
        }

        // Offline fallback: only serve previously cached latest URI if network probes failed
        if (lastFailure != null && preferLatest && diskCache != null) {
            String cachedUri = lastResolvedUriByStation.get(station.stationId());
            if (cachedUri != null) {
                byte[] bytes = diskCache.get(cachedUri, nowMillis);
                if (bytes != null) {
                    RinexObservationData data = RinexObservationLoader.parse(bytes, extractFileName(cachedUri));
                    return new ObservationFetchResult(
                            station, data, getDisplayName(), cachedUri, nowMillis, formatLabel(cachedUri));
                }
            }
        }

        throw lastFailure != null ? lastFailure
                : new IOException("No RINEX observation file found on BKG for " + station.stationId());
    }

    private List<DataSourceConfig> findMatchingSources(GnssReferenceStation station) {
        List<DataSourceConfig> all = archiveSourcesSupplier.get();
        if (all == null) {
            return List.of();
        }
        List<DataSourceConfig> preferred = new ArrayList<>();
        for (DataSourceConfig s : all) {
            if (s == null || !s.isEnabled() || s.getFeedCategory() != DataSourceConfig.FeedCategory.GNSS_OBSERVATION_ARCHIVE) {
                continue;
            }
            GnssStationNetwork scopeNet = GnssStationNetwork.fromScope(s.getProviderScope());
            if (station.networks().contains(scopeNet)) {
                preferred.add(s);
            }
        }
        preferred.sort(Comparator.comparingInt(DataSourceConfig::getPriority));
        return preferred;
    }

    private static List<String> buildCandidateDirectoryUrls(String base, long targetMillis, boolean historicalDaily) {
        ZonedDateTime utc = Instant.ofEpochMilli(targetMillis).atZone(ZoneOffset.UTC);
        List<String> urls = new ArrayList<>(3);
        if (historicalDaily) {
            String obsRoot = base.contains("/nrt/") ? base.replace("/nrt/", "/obs/") : base + "../obs/";
            urls.add(URI.create(obsRoot).normalize().toString()
                    + String.format(Locale.US, "%04d/%03d/", utc.getYear(), utc.getDayOfYear()));
            return urls;
        }
        for (int h = 0; h < 3; h++) {
            ZonedDateTime slot = utc.minusHours(h);
            urls.add(base + String.format(Locale.US, "%03d/%02d/", slot.getDayOfYear(), slot.getHour()));
        }
        return urls;
    }

    private static String selectBestStationFile(String html, GnssReferenceStation station) {
        if (html == null || html.isBlank()) {
            return null;
        }
        String idUpper = station.stationId().toUpperCase(Locale.US);
        String shortUpper = station.shortCode().toUpperCase(Locale.US);
        List<String> matches = new ArrayList<>();
        Matcher m = HREF_PATTERN.matcher(html);
        while (m.find()) {
            String href = m.group(1);
            String name = extractFileName(href);
            String upper = name.toUpperCase(Locale.US);
            boolean matchesId = (!idUpper.isBlank() && upper.startsWith(idUpper))
                    || (!shortUpper.isBlank() && upper.startsWith(shortUpper));
            if (matchesId && looksLikeObservationFile(upper)) {
                matches.add(href);
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        matches.sort(Comparator.comparingInt(BkgHourlyRinexSource::formatScore).reversed());
        return matches.get(0);
    }

    private static int formatScore(String href) {
        String u = href.toUpperCase(Locale.US);
        int score = 0;
        if (u.contains("_MO.")) {
            score += 40;
        }
        if (u.endsWith(".RNX.GZ") || u.endsWith(".RNX")) {
            score += 30;
        } else if (u.endsWith(".CRX.GZ") || u.endsWith(".CRX")) {
            score += 25;
        } else if (u.endsWith(".GZ")) {
            score += 10;
        }
        return score;
    }

    private static boolean looksLikeObservationFile(String upperName) {
        return upperName.endsWith(".CRX.GZ") || upperName.endsWith(".RNX.GZ")
                || upperName.endsWith(".CRX") || upperName.endsWith(".RNX")
                || upperName.matches(".*\\.\\d{2}[OD](?:\\.GZ|\\.Z)?$");
    }

    private String sendGetText(String url, DataSourceConfig source) throws IOException {
        return new String(sendGetBytes(url, source), StandardCharsets.UTF_8);
    }

    private byte[] sendGetBytes(String url, DataSourceConfig source) throws IOException {
        try {
            HttpRequest req = HttpRequestAuthApplier.buildRequest(url, source, REQUEST_TIMEOUT);
            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IOException("HTTP " + resp.statusCode() + " from " + url);
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted fetching " + url, e);
        }
    }

    private static String extractFileName(String urlOrPath) {
        int slash = urlOrPath.lastIndexOf('/');
        return slash >= 0 ? urlOrPath.substring(slash + 1) : urlOrPath;
    }

    private static String formatLabel(String fileName) {
        String u = fileName.toUpperCase(Locale.US);
        if (u.contains(".CRX")) {
            return "RINEX 3 Hatanaka (CRX)";
        }
        if (u.contains(".RNX")) {
            return "RINEX 3";
        }
        return "RINEX 2/3";
    }
}

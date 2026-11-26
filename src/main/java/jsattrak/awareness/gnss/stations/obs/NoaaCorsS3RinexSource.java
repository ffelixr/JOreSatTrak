package jsattrak.awareness.gnss.stations.obs;

import java.io.IOException;
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
 * {@link GnssObservationSource} fetching NOAA CORS observations via unauthenticated AWS S3
 * {@code ListObjectsV2} XML listings ({@code rinex/{YYYY}/{DDD}/{ssss}/}).
 */
public class NoaaCorsS3RinexSource implements GnssObservationSource {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern S3_CONTENTS_PATTERN = Pattern.compile(
            "<Contents>.*?<Key>([^<]+)</Key>(?:.*?<LastModified>([^<]+)</LastModified>)?.*?</Contents>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final Supplier<List<DataSourceConfig>> archiveSourcesSupplier;
    private final GnssObservationDiskCache diskCache;
    private final HttpClient httpClient;
    private final Map<String, String> lastResolvedUriByStation = new ConcurrentHashMap<>();

    public NoaaCorsS3RinexSource(
            Supplier<List<DataSourceConfig>> archiveSourcesSupplier,
            GnssObservationDiskCache diskCache) {
        this(archiveSourcesSupplier, diskCache,
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(CONNECT_TIMEOUT)
                        .build());
    }

    public NoaaCorsS3RinexSource(
            Supplier<List<DataSourceConfig>> archiveSourcesSupplier,
            GnssObservationDiskCache diskCache,
            HttpClient httpClient) {
        this.archiveSourcesSupplier = Objects.requireNonNull(archiveSourcesSupplier, "archiveSourcesSupplier");
        this.diskCache = diskCache;
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public String getSourceId() {
        return "noaa-cors-s3";
    }

    @Override
    public String getDisplayName() {
        return "NOAA CORS AWS S3";
    }

    @Override
    public boolean supportsNetwork(GnssStationNetwork network) {
        return network == GnssStationNetwork.NOAA_CORS;
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

        List<DataSourceConfig> matching = findNoaaSources();
        if (matching.isEmpty()) {
            throw new IOException("No enabled NOAA CORS S3 archive configured");
        }

        ZonedDateTime targetUtc = Instant.ofEpochMilli(targetMillis).atZone(ZoneOffset.UTC);
        int daysToTry = preferLatest ? 2 : 1;
        String codeLower = station.shortCode().toLowerCase(Locale.US);

        IOException lastFailure = null;
        for (DataSourceConfig src : matching) {
            String base = src.getEndpointUrl().trim();
            if (!base.endsWith("/")) {
                base += "/";
            }
            for (int d = 0; d < daysToTry; d++) {
                ZonedDateTime day = targetUtc.minusDays(d);
                String prefix = String.format(Locale.US, "rinex/%04d/%03d/%s/",
                        day.getYear(), day.getDayOfYear(), codeLower);
                String listUrl = base + "?list-type=2&prefix=" + prefix;
                try {
                    String xml = sendGetText(listUrl, src);
                    List<String> keys = selectObservationKeys(xml);
                    if (keys.isEmpty()) {
                        continue;
                    }
                    String chosenKey = keys.get(0);
                    String fileUrl = base + chosenKey;
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
                    String fname = extractFileName(chosenKey);
                    RinexObservationData obs = RinexObservationLoader.parse(payload, fname);
                    return new ObservationFetchResult(
                            station, obs, src.getDisplayName(), fileUrl, nowMillis, "NOAA S3 " + fname);
                } catch (IOException | RuntimeException e) {
                    lastFailure = (e instanceof IOException io) ? io : new IOException(e.getMessage(), e);
                }
            }
        }
        // Offline fallback: only serve previously cached latest URI if S3 requests failed
        if (lastFailure != null && preferLatest && diskCache != null) {
            String cachedUri = lastResolvedUriByStation.get(station.stationId());
            if (cachedUri != null) {
                byte[] cached = diskCache.get(cachedUri, nowMillis);
                if (cached != null) {
                    String fname = extractFileName(cachedUri);
                    RinexObservationData obs = RinexObservationLoader.parse(cached, fname);
                    return new ObservationFetchResult(
                            station, obs, getDisplayName(), cachedUri, nowMillis, "NOAA S3 " + fname);
                }
            }
        }
        throw lastFailure != null ? lastFailure
                : new IOException("No NOAA CORS S3 observation files found for " + station.shortCode());
    }

    private List<DataSourceConfig> findNoaaSources() {
        List<DataSourceConfig> all = archiveSourcesSupplier.get();
        if (all == null) {
            return List.of();
        }
        List<DataSourceConfig> out = new ArrayList<>();
        for (DataSourceConfig s : all) {
            if (s != null && s.isEnabled()
                    && s.getFeedCategory() == DataSourceConfig.FeedCategory.GNSS_OBSERVATION_ARCHIVE
                    && GnssStationNetwork.fromScope(s.getProviderScope()) == GnssStationNetwork.NOAA_CORS) {
                out.add(s);
            }
        }
        out.sort(Comparator.comparingInt(DataSourceConfig::getPriority));
        return out;
    }

    private static List<String> selectObservationKeys(String xml) {
        if (xml == null || xml.isBlank()) {
            return List.of();
        }
        record KeyEntry(String key, long lastMod) {
        }
        List<KeyEntry> entries = new ArrayList<>();
        Matcher m = S3_CONTENTS_PATTERN.matcher(xml);
        while (m.find()) {
            String key = m.group(1).trim();
            String u = key.toUpperCase(Locale.US);
            if (u.endsWith(".CRX.GZ") || u.endsWith(".RNX.GZ")
                    || u.matches(".*\\.\\d{2}[OD](?:\\.GZ|\\.Z)?$")) {
                long lm = 0L;
                if (m.group(2) != null) {
                    try {
                        lm = Instant.parse(m.group(2).trim()).toEpochMilli();
                    } catch (RuntimeException ignored) {
                    }
                }
                entries.add(new KeyEntry(key, lm));
            }
        }
        entries.sort(Comparator.comparingLong(KeyEntry::lastMod).reversed()
                .thenComparing(KeyEntry::key, Comparator.reverseOrder()));
        return entries.stream().map(KeyEntry::key).toList();
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

    private static String extractFileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}

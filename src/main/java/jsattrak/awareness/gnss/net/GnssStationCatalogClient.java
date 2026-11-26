package jsattrak.awareness.gnss.net;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jsattrak.awareness.config.DataSourceConfig;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.stations.GnssReferenceStation;
import jsattrak.awareness.gnss.stations.GnssStationNetwork;
import jsattrak.awareness.gnss.stations.GnssStationStatus;
import jsattrak.awareness.net.ConnectionTestResult;
import jsattrak.awareness.net.HttpRequestAuthApplier;

/**
 * HTTP client and parser for Layer 3 GNSS reference station catalogs (M3G JSON, IGS CSV,
 * NOAA CORS tables + lazy site-log enrichment) and lightweight BKG NRT directory latency probes.
 */
public class GnssStationCatalogClient {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(25);
    private static final double DEDUP_BASELINE_KM = 5.0;
    private static final Pattern LONG_RINEX_PATTERN = Pattern.compile(
            "([A-Z0-9]{4}[0-9A-Z]{5})_[A-Z]_(\\d{4})(\\d{3})(\\d{2})(\\d{2})_(\\d{2}[HDM])_(\\w+)_(\\w+)\\.(crx\\.gz|rnx\\.gz|crx|rnx|gz)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORT_RINEX_PATTERN = Pattern.compile(
            "([a-z0-9]{4})(\\d{3})([a-x0])\\.(\\d{2}[odn])(?:\\.gz|\\.Z)?",
            Pattern.CASE_INSENSITIVE);

    public record CatalogSyncResult(List<GnssReferenceStation> stations, String summaryMessage) {
    }

    private record SeedStationDto(
            String stationId,
            String shortCode,
            String siteName,
            String countryCode,
            String primaryNetwork,
            List<String> networks,
            double latDeg,
            double lonDeg,
            double altMeters,
            String receiverModel,
            String receiverFirmware,
            String antennaModel,
            String radomeCode,
            List<String> supportedConstellations) {
    }

    private record ArchiveProbeHit(
            long lastObsEpochMillis,
            int latencyMinutes,
            List<String> formats,
            String fileUrl) {
    }

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public GnssStationCatalogClient() {
        this(HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                        .build(),
                DEFAULT_REQUEST_TIMEOUT);
    }

    public GnssStationCatalogClient(HttpClient httpClient, Duration requestTimeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.requestTimeout = requestTimeout != null ? requestTimeout : DEFAULT_REQUEST_TIMEOUT;
    }

    /**
     * Loads the bundled offline reference-station seed (~36 global stations with UNKNOWN status).
     */
    public static List<GnssReferenceStation> loadBundledSeed() {
        try (InputStream is = GnssStationCatalogClient.class.getResourceAsStream("/gnss/reference-stations-seed.json")) {
            if (is == null) {
                return List.of();
            }
            Type listType = new TypeToken<List<SeedStationDto>>() {}.getType();
            List<SeedStationDto> dtos = new Gson().fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), listType);
            if (dtos == null || dtos.isEmpty()) {
                return List.of();
            }
            List<GnssReferenceStation> result = new ArrayList<>(dtos.size());
            for (SeedStationDto dto : dtos) {
                if (dto == null || dto.stationId == null || dto.stationId.isBlank()) {
                    continue;
                }
                GnssStationNetwork primary = parseNetwork(dto.primaryNetwork, GnssStationNetwork.IGS);
                EnumSet<GnssStationNetwork> nets = EnumSet.of(primary);
                if (dto.networks != null) {
                    for (String n : dto.networks) {
                        nets.add(parseNetwork(n, primary));
                    }
                }
                EnumSet<GnssConstellation> consts = EnumSet.noneOf(GnssConstellation.class);
                if (dto.supportedConstellations != null) {
                    for (String c : dto.supportedConstellations) {
                        addConstellationToken(c, consts);
                    }
                }
                result.add(new GnssReferenceStation(
                        dto.stationId,
                        dto.shortCode,
                        dto.siteName,
                        dto.countryCode,
                        primary,
                        nets,
                        dto.latDeg,
                        dto.lonDeg,
                        dto.altMeters,
                        null,
                        dto.receiverModel,
                        dto.receiverFirmware,
                        dto.antennaModel,
                        dto.radomeCode,
                        consts,
                        List.of(),
                        0L,
                        -1,
                        Double.NaN,
                        GnssStationStatus.UNKNOWN,
                        ""));
            }
            return Collections.unmodifiableList(result);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Fetches enabled catalog sources, cross-deduplicates stations (IGS/EUREF), and probes lightweight
     * BKG NRT 2-hour directory HTML listings to populate live availability and latency.
     */
    public CatalogSyncResult fetchMergedCatalog(
            List<DataSourceConfig> catalogSources,
            List<DataSourceConfig> archiveSources,
            long nowMillis) throws IOException {
        if (catalogSources == null || catalogSources.isEmpty()) {
            throw new IOException("No enabled GNSS station catalog sources configured");
        }
        List<GnssReferenceStation> rawCollected = new ArrayList<>();
        List<String> syncedLabels = new ArrayList<>();
        IOException lastFailure = null;

        for (DataSourceConfig source : catalogSources) {
            if (source == null || !source.isEnabled()
                    || source.getEndpointUrl() == null || source.getEndpointUrl().isBlank()) {
                continue;
            }
            try {
                String body = sendGetString(source.getEndpointUrl(), source);
                GnssStationNetwork network = GnssStationNetwork.fromScope(
                        !source.getProviderScope().isBlank() ? source.getProviderScope() : source.getDisplayName());
                List<GnssReferenceStation> parsed = parseCatalogPayload(body, network);
                if (!parsed.isEmpty()) {
                    rawCollected.addAll(parsed);
                    syncedLabels.add(network.getShortLabel() + ":" + parsed.size());
                }
            } catch (IOException | RuntimeException e) {
                lastFailure = (e instanceof IOException io) ? io : new IOException(e.getMessage(), e);
            }
        }

        if (rawCollected.isEmpty()) {
            throw lastFailure != null ? lastFailure : new IOException("All GNSS station catalog sources returned 0 stations");
        }

        List<GnssReferenceStation> merged = deduplicateStations(rawCollected);

        Map<String, ArchiveProbeHit> probeByKey = new LinkedHashMap<>();
        if (archiveSources != null) {
            for (DataSourceConfig arc : archiveSources) {
                if (arc == null || !arc.isEnabled()
                        || arc.getEndpointUrl() == null || arc.getEndpointUrl().isBlank()) {
                    continue;
                }
                GnssStationNetwork arcNet = GnssStationNetwork.fromScope(arc.getProviderScope());
                if (arcNet != GnssStationNetwork.IGS && arcNet != GnssStationNetwork.EUREF) {
                    continue;
                }
                probeBkgNrtDirectories(arc, nowMillis, probeByKey);
            }
        }

        if (!probeByKey.isEmpty()) {
            List<GnssReferenceStation> updated = new ArrayList<>(merged.size());
            for (GnssReferenceStation st : merged) {
                if (st.status() == GnssStationStatus.OFFLINE) {
                    updated.add(st);
                    continue;
                }
                ArchiveProbeHit hit = probeByKey.get(st.stationId());
                if (hit == null && !st.shortCode().isBlank()) {
                    hit = probeByKey.get(st.shortCode());
                }
                if (hit != null) {
                    GnssStationStatus stStatus = GnssStationStatus.classifyFromLatencyMinutes(hit.latencyMinutes(), true);
                    updated.add(st.withLatency(
                            hit.lastObsEpochMillis(),
                            hit.latencyMinutes(),
                            hit.formats(),
                            stStatus,
                            hit.fileUrl()));
                } else {
                    updated.add(st);
                }
            }
            merged = updated;
        }

        String summary = "Synced " + merged.size() + " stations (" + String.join(", ", syncedLabels) + ")";
        return new CatalogSyncResult(Collections.unmodifiableList(merged), summary);
    }

    /**
     * Fetches and parses an individual NOAA CORS {@code <ssss>.log.txt} hardware log to enrich
     * receiver, antenna, radome, constellations, and ECEF coordinates on demand.
     */
    public GnssReferenceStation enrichNoaaStationHardware(
            GnssReferenceStation station,
            String stationLogBaseUrl) throws IOException {
        if (station == null || stationLogBaseUrl == null || stationLogBaseUrl.isBlank()) {
            return station;
        }
        String base = stationLogBaseUrl.trim();
        if (!base.endsWith("/")) {
            base += "/";
        }
        String codeLower = station.shortCode().toLowerCase(Locale.US);
        String url = base + codeLower + ".log.txt";
        String text = sendGetString(url, null);
        return parseIgsSiteLogFields(station, text);
    }

    public ConnectionTestResult testConnection(DataSourceConfig source) {
        if (source == null || source.getEndpointUrl() == null || source.getEndpointUrl().isBlank()) {
            return new ConnectionTestResult(false, 0, 0, "Endpoint URL is empty");
        }
        try {
            DataSourceConfig.FeedCategory cat = source.getFeedCategory();
            if (cat == DataSourceConfig.FeedCategory.GNSS_OBSERVATION_ARCHIVE) {
                GnssStationNetwork net = GnssStationNetwork.fromScope(source.getProviderScope());
                String testUrl = source.getEndpointUrl().trim();
                if (net == GnssStationNetwork.NOAA_CORS) {
                    if (!testUrl.contains("list-type=2")) {
                        if (!testUrl.endsWith("/")) {
                            testUrl += "/";
                        }
                        testUrl += "?list-type=2&max-keys=5&prefix=rinex/";
                    }
                    HttpRequest req = HttpRequestAuthApplier.buildRequest(testUrl, source, requestTimeout);
                    HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                        return new ConnectionTestResult(true, resp.statusCode(), 1, "OK (NOAA CORS S3 reachable)");
                    }
                    return new ConnectionTestResult(false, resp.statusCode(), 0, "HTTP " + resp.statusCode());
                } else {
                    ZonedDateTime prevHour = Instant.now().atZone(ZoneOffset.UTC).minusHours(1);
                    String base = testUrl.endsWith("/") ? testUrl : testUrl + "/";
                    String url = base + String.format(Locale.US, "%03d/%02d/", prevHour.getDayOfYear(), prevHour.getHour());
                    HttpRequest req = HttpRequestAuthApplier.buildRequest(url, source, requestTimeout);
                    HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                        Map<String, ArchiveProbeHit> hits = new LinkedHashMap<>();
                        parseDirectoryListingHtml(resp.body(), url, System.currentTimeMillis(), hits);
                        return new ConnectionTestResult(true, resp.statusCode(), hits.size(),
                                "OK (" + hits.size() + " NRT files listed)");
                    }
                    return new ConnectionTestResult(false, resp.statusCode(), 0, "HTTP " + resp.statusCode());
                }
            }

            String body = sendGetString(source.getEndpointUrl(), source);
            GnssStationNetwork net = GnssStationNetwork.fromScope(
                    !source.getProviderScope().isBlank() ? source.getProviderScope() : source.getDisplayName());
            List<GnssReferenceStation> parsed = parseCatalogPayload(body, net);
            if (parsed.isEmpty()) {
                return new ConnectionTestResult(false, 200, 0, "0 reference stations parsed");
            }
            return new ConnectionTestResult(true, 200, parsed.size(),
                    "OK (" + parsed.size() + " stations parsed)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ConnectionTestResult(false, 0, 0, "Connection test interrupted");
        } catch (Exception e) {
            String msg = e.getMessage();
            return new ConnectionTestResult(false, extractHttpStatus(msg), 0,
                    (msg != null && !msg.isBlank()) ? msg : e.getClass().getSimpleName());
        }
    }

    // ---- Parsers ----

    public static List<GnssReferenceStation> parseCatalogPayload(String body, GnssStationNetwork defaultNetwork) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            return parseM3gJson(trimmed, defaultNetwork);
        }
        if (defaultNetwork == GnssStationNetwork.NOAA_CORS
                || trimmed.contains("Operational")
                || trimmed.contains("Decommissioned")) {
            List<GnssReferenceStation> noaa = parseNoaaCorsTable(trimmed);
            if (!noaa.isEmpty()) {
                return noaa;
            }
        }
        return parseCsvStations(trimmed, defaultNetwork);
    }

    private static List<GnssReferenceStation> parseM3gJson(String json, GnssStationNetwork network) {
        List<GnssReferenceStation> out = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(json);
            JsonArray array = null;
            if (root.isJsonArray()) {
                array = root.getAsJsonArray();
            } else if (root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();
                if (obj.has("stations") && obj.get("stations").isJsonArray()) {
                    array = obj.getAsJsonArray("stations");
                } else if (obj.has("data") && obj.get("data").isJsonArray()) {
                    array = obj.getAsJsonArray("data");
                } else if (obj.has("features") && obj.get("features").isJsonArray()) {
                    array = obj.getAsJsonArray("features");
                } else if (obj.has("sitelogs") && obj.get("sitelogs").isJsonArray()) {
                    array = obj.getAsJsonArray("sitelogs");
                } else {
                    for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                        if (e.getValue().isJsonObject()) {
                            GnssReferenceStation st = parseStationJsonObject(e.getKey(), e.getValue().getAsJsonObject(), network);
                            if (st != null) {
                                out.add(st);
                            }
                        }
                    }
                    return out;
                }
            }
            if (array == null) {
                return out;
            }
            for (JsonElement el : array) {
                if (el != null && el.isJsonObject()) {
                    GnssReferenceStation st = parseStationJsonObject(null, el.getAsJsonObject(), network);
                    if (st != null) {
                        out.add(st);
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    private static GnssReferenceStation parseStationJsonObject(
            String keyId,
            JsonObject rawObj,
            GnssStationNetwork network) {
        JsonObject obj = rawObj;
        JsonObject props = rawObj.has("properties") && rawObj.get("properties").isJsonObject()
                ? rawObj.getAsJsonObject("properties") : rawObj;

        String id = firstNonBlank(
                str(obj, "id"), str(obj, "stationId"), str(obj, "nineCharacterId"),
                str(props, "id"), str(props, "name"), keyId);
        if (id.isBlank()) {
            return null;
        }
        String siteName = firstNonBlank(
                str(props, "name"), str(props, "siteName"), str(props, "city"), str(props, "stationName"), id);
        String country = firstNonBlank(str(props, "country"), str(props, "countryCode"),
                id.length() == 9 ? id.substring(6, 9) : "");

        double lat = firstFinite(dbl(props, "lat"), dbl(props, "latitude"), dbl(props, "latDeg"), Double.NaN);
        double lon = firstFinite(dbl(props, "lon"), dbl(props, "longitude"), dbl(props, "lonDeg"), Double.NaN);
        double alt = firstFinite(dbl(props, "height"), dbl(props, "alt"), dbl(props, "elevation"), Double.NaN);

        if ((!Double.isFinite(lat) || !Double.isFinite(lon))
                && rawObj.has("geometry") && rawObj.get("geometry").isJsonObject()) {
            JsonObject geom = rawObj.getAsJsonObject("geometry");
            if (geom.has("coordinates") && geom.get("coordinates").isJsonArray()) {
                JsonArray coords = geom.getAsJsonArray("coordinates");
                if (coords.size() >= 2) {
                    lon = coords.get(0).getAsDouble();
                    lat = coords.get(1).getAsDouble();
                    if (coords.size() >= 3) {
                        alt = coords.get(2).getAsDouble();
                    }
                }
            }
        }
        double x = firstFinite(dbl(props, "x"), dbl(props, "xCoordinate"), Double.NaN);
        double y = firstFinite(dbl(props, "y"), dbl(props, "yCoordinate"), Double.NaN);
        double z = firstFinite(dbl(props, "z"), dbl(props, "zCoordinate"), Double.NaN);
        double[] ecef = (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && (x != 0.0 || y != 0.0 || z != 0.0))
                ? new double[]{x, y, z} : null;

        if ((!Double.isFinite(lat) || !Double.isFinite(lon)) && ecef != null) {
            double[] lla = jsattrak.awareness.gnss.stations.obs.LocalRinexFileSource.ecefToGeodeticDeg(
                    ecef[0], ecef[1], ecef[2]);
            if (Double.isFinite(lla[0]) && Double.isFinite(lla[1])) {
                lat = lla[0];
                lon = lla[1];
                if (!Double.isFinite(alt)) {
                    alt = lla[2];
                }
            }
        }

        if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
            return null;
        }
        if (!Double.isFinite(alt)) {
            alt = 0.0;
        }

        String rxModel = firstNonBlank(str(props, "receiver_type"), str(props, "receiverType"), str(props, "receiver"));
        String rxFw = firstNonBlank(str(props, "receiver_firmware"), str(props, "firmwareVersion"));
        String antModel = firstNonBlank(str(props, "antenna_type"), str(props, "antennaType"), str(props, "antenna"));
        String radome = firstNonBlank(str(props, "radome_type"), str(props, "radome"), str(props, "radomeCode"));

        EnumSet<GnssConstellation> consts = EnumSet.noneOf(GnssConstellation.class);
        if (props.has("constellations")) {
            JsonElement ce = props.get("constellations");
            if (ce.isJsonArray()) {
                for (JsonElement c : ce.getAsJsonArray()) {
                    if (c.isJsonPrimitive()) {
                        addConstellationToken(c.getAsString(), consts);
                    }
                }
            } else if (ce.isJsonPrimitive()) {
                addConstellationToken(ce.getAsString(), consts);
            }
        } else {
            String sys = firstNonBlank(str(props, "satelliteSystem"), str(props, "systems"));
            if (!sys.isBlank()) {
                addConstellationToken(sys, consts);
            }
        }

        return new GnssReferenceStation(
                id, null, siteName, country, network, EnumSet.of(network),
                lat, lon, alt, ecef, rxModel, rxFw, antModel, radome,
                consts, List.of(), 0L, -1, Double.NaN, GnssStationStatus.UNKNOWN, "");
    }

    private static List<GnssReferenceStation> parseNoaaCorsTable(String text) {
        List<GnssReferenceStation> out = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("SITE")) {
                continue;
            }
            // Match tokens at start (4-char ID) and trailing status + lat lon [alt]
            Matcher m = Pattern.compile(
                    "^([A-Za-z0-9]{4})\\s+(.+?)\\s+(Operational|Active|Non-Operational|Decommissioned|Retired|Inactive)\\s+(-?\\d+(?:\\.\\d+)?)\\s+(-?\\d+(?:\\.\\d+)?)(?:\\s+(-?\\d+(?:\\.\\d+)?))?.*$",
                    Pattern.CASE_INSENSITIVE).matcher(trimmed);
            if (!m.matches()) {
                continue;
            }
            String code = m.group(1).toUpperCase(Locale.US);
            String desc = m.group(2).trim().replaceAll("\\s{2,}", " ");
            String statusWord = m.group(3).toLowerCase(Locale.US);
            double lat = Double.parseDouble(m.group(4));
            double lon = Double.parseDouble(m.group(5));
            double alt = m.group(6) != null ? Double.parseDouble(m.group(6)) : 0.0;
            boolean active = statusWord.equals("operational") || statusWord.equals("active");
            GnssStationStatus stStatus = active ? GnssStationStatus.UNKNOWN : GnssStationStatus.OFFLINE;
            out.add(new GnssReferenceStation(
                    code, code, desc, "USA", GnssStationNetwork.NOAA_CORS, EnumSet.of(GnssStationNetwork.NOAA_CORS),
                    lat, lon, alt, null, "", "", "", "", EnumSet.of(GnssConstellation.GPS),
                    List.of("RINEX 2/3 .gz"), 0L, -1, Double.NaN, stStatus, ""));
        }
        return out;
    }

    private static List<GnssReferenceStation> parseCsvStations(String text, GnssStationNetwork network) {
        List<GnssReferenceStation> out = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        if (lines.length < 2) {
            return out;
        }
        String[] headers = lines[0].toLowerCase(Locale.US).split(",");
        int idIdx = findHeaderIndex(headers, "id", "site", "station", "name");
        int latIdx = findHeaderIndex(headers, "lat", "latitude");
        int lonIdx = findHeaderIndex(headers, "lon", "longitude");
        int altIdx = findHeaderIndex(headers, "height", "alt", "elevation");
        int rxIdx = findHeaderIndex(headers, "receiver", "receiver_type");
        int antIdx = findHeaderIndex(headers, "antenna", "antenna_type");
        if (idIdx < 0 || latIdx < 0 || lonIdx < 0) {
            return out;
        }
        for (int i = 1; i < lines.length; i++) {
            String row = lines[i].trim();
            if (row.isEmpty()) {
                continue;
            }
            String[] cols = row.split(",", -1);
            if (cols.length <= Math.max(idIdx, Math.max(latIdx, lonIdx))) {
                continue;
            }
            try {
                String id = cols[idIdx].replace("\"", "").trim();
                double lat = Double.parseDouble(cols[latIdx].replace("\"", "").trim());
                double lon = Double.parseDouble(cols[lonIdx].replace("\"", "").trim());
                double alt = (altIdx >= 0 && altIdx < cols.length && !cols[altIdx].isBlank())
                        ? Double.parseDouble(cols[altIdx].replace("\"", "").trim()) : 0.0;
                String rx = rxIdx >= 0 && rxIdx < cols.length ? cols[rxIdx].replace("\"", "").trim() : "";
                String ant = antIdx >= 0 && antIdx < cols.length ? cols[antIdx].replace("\"", "").trim() : "";
                if (!id.isBlank()) {
                    out.add(new GnssReferenceStation(
                            id, null, id, id.length() == 9 ? id.substring(6, 9) : "",
                            network, EnumSet.of(network), lat, lon, alt, null,
                            rx, "", ant, "", EnumSet.of(GnssConstellation.GPS),
                            List.of(), 0L, -1, Double.NaN, GnssStationStatus.UNKNOWN, ""));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    static GnssReferenceStation parseIgsSiteLogFields(GnssReferenceStation base, String logText) {
        if (base == null || logText == null || logText.isBlank()) {
            return base;
        }
        String rx = "";
        String fw = "";
        String ant = "";
        String radome = "";
        double x = Double.NaN;
        double y = Double.NaN;
        double z = Double.NaN;
        EnumSet<GnssConstellation> consts = EnumSet.noneOf(GnssConstellation.class);

        for (String line : logText.split("\\r?\\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String val = line.substring(colon + 1).trim();
            if (val.isEmpty()) {
                continue;
            }
            if (key.contains("receiver type")) {
                rx = val;
            } else if (key.contains("firmware version")) {
                fw = val;
            } else if (key.contains("satellite system")) {
                addConstellationToken(val, consts);
            } else if (key.contains("antenna type")) {
                String[] parts = val.split("\\s+");
                ant = parts[0];
                if (parts.length >= 2 && radome.isEmpty()) {
                    radome = parts[1];
                }
            } else if (key.contains("radome type")) {
                radome = val.split("\\s+")[0];
            } else if (key.startsWith("x coordinate")) {
                x = parseDoubleSafe(val, x);
            } else if (key.startsWith("y coordinate")) {
                y = parseDoubleSafe(val, y);
            } else if (key.startsWith("z coordinate")) {
                z = parseDoubleSafe(val, z);
            }
        }
        double[] ecef = (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z))
                ? new double[]{x, y, z} : null;
        return base.withHardware(rx, fw, ant, radome, consts, ecef);
    }

    static List<GnssReferenceStation> deduplicateStations(List<GnssReferenceStation> input) {
        Map<String, Integer> indexById = new HashMap<>();
        List<GnssReferenceStation> result = new ArrayList<>();

        for (GnssReferenceStation st : input) {
            if (st == null || st.stationId().isBlank()) {
                continue;
            }
            Integer existingIdx = indexById.get(st.stationId());
            if (existingIdx == null) {
                for (int i = 0; i < result.size(); i++) {
                    GnssReferenceStation existing = result.get(i);
                    if (existing.shortCode().equalsIgnoreCase(st.shortCode())
                            && existing.distanceKmTo(st.latDeg(), st.lonDeg()) <= DEDUP_BASELINE_KM) {
                        existingIdx = i;
                        break;
                    }
                }
            }
            if (existingIdx != null) {
                GnssReferenceStation existing = result.get(existingIdx);
                GnssReferenceStation merged = existing.mergeWith(st);
                result.set(existingIdx, merged);
                indexById.put(existing.stationId(), existingIdx);
                indexById.put(st.stationId(), existingIdx);
                indexById.put(merged.stationId(), existingIdx);
            } else {
                int newIdx = result.size();
                result.add(st);
                indexById.put(st.stationId(), newIdx);
            }
        }
        return result;
    }

    private void probeBkgNrtDirectories(
            DataSourceConfig archiveSource,
            long nowMillis,
            Map<String, ArchiveProbeHit> probeByKey) {
        String base = archiveSource.getEndpointUrl().trim();
        if (!base.endsWith("/")) {
            base += "/";
        }
        ZonedDateTime nowUtc = Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC);
        for (int h = 0; h < 2; h++) {
            ZonedDateTime dt = nowUtc.minusHours(h);
            String dirUrl = base + String.format(Locale.US, "%03d/%02d/", dt.getDayOfYear(), dt.getHour());
            try {
                String html = sendGetString(dirUrl, archiveSource);
                parseDirectoryListingHtml(html, dirUrl, nowMillis, probeByKey);
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }

    static void parseDirectoryListingHtml(
            String html,
            String dirUrl,
            long nowMillis,
            Map<String, ArchiveProbeHit> out) {
        if (html == null || html.isBlank()) {
            return;
        }
        Matcher hrefMatcher = Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
        while (hrefMatcher.find()) {
            String href = hrefMatcher.group(1);
            String fileName = href.contains("/") ? href.substring(href.lastIndexOf('/') + 1) : href;
            Matcher longM = LONG_RINEX_PATTERN.matcher(fileName);
            if (longM.find()) {
                String stId = longM.group(1).toUpperCase(Locale.US);
                int year = Integer.parseInt(longM.group(2));
                int doy = Integer.parseInt(longM.group(3));
                int hour = Integer.parseInt(longM.group(4));
                int minute = Integer.parseInt(longM.group(5));
                String durationStr = longM.group(6).toUpperCase(Locale.US);
                String intervalStr = longM.group(7).toUpperCase(Locale.US);
                String ext = longM.group(9).toUpperCase(Locale.US);

                long startEpoch = ZonedDateTime.of(year, 1, 1, hour, minute, 0, 0, ZoneOffset.UTC)
                        .plusDays(doy - 1L).toInstant().toEpochMilli();
                long durMillis = parseDurationMillis(durationStr);
                long endEpoch = startEpoch + durMillis;
                int latencyMin = (int) Math.max(0L, Math.round((nowMillis - endEpoch) / 60_000.0));
                String resolvedUrl = dirUrl + fileName;
                String fmt = "RINEX 3 " + ext + " (" + intervalStr + ")";
                recordProbeHit(out, stId, stId.substring(0, 4), endEpoch, latencyMin, fmt, resolvedUrl);
                continue;
            }
            Matcher shortM = SHORT_RINEX_PATTERN.matcher(fileName);
            if (shortM.find() && Character.toLowerCase(shortM.group(4).charAt(2)) != 'n') {
                String shortCode = shortM.group(1).toUpperCase(Locale.US);
                int doy = Integer.parseInt(shortM.group(2));
                char ses = Character.toLowerCase(shortM.group(3).charAt(0));
                int yy = Integer.parseInt(shortM.group(4).substring(0, 2));
                int year = 2000 + yy;
                int hour = (ses >= 'a' && ses <= 'x') ? (ses - 'a') : 0;
                long durMillis = (ses == '0') ? 86_400_000L : 3_600_000L;
                long startEpoch = ZonedDateTime.of(year, 1, 1, hour, 0, 0, 0, ZoneOffset.UTC)
                        .plusDays(doy - 1L).toInstant().toEpochMilli();
                long endEpoch = startEpoch + durMillis;
                int latencyMin = (int) Math.max(0L, Math.round((nowMillis - endEpoch) / 60_000.0));
                String fmt = "RINEX 2 (" + shortM.group(4).toUpperCase(Locale.US) + ")";
                recordProbeHit(out, shortCode, shortCode, endEpoch, latencyMin, fmt, dirUrl + fileName);
            }
        }
    }

    private static void recordProbeHit(
            Map<String, ArchiveProbeHit> out,
            String primaryKey,
            String shortKey,
            long epochMillis,
            int latencyMinutes,
            String format,
            String fileUrl) {
        ArchiveProbeHit existing = out.get(primaryKey);
        if (existing == null || epochMillis > existing.lastObsEpochMillis()) {
            List<String> fmts = new ArrayList<>();
            if (existing != null) {
                fmts.addAll(existing.formats());
            }
            if (!fmts.contains(format)) {
                fmts.add(format);
            }
            ArchiveProbeHit hit = new ArchiveProbeHit(epochMillis, latencyMinutes, List.copyOf(fmts), fileUrl);
            out.put(primaryKey, hit);
            if (shortKey != null && !shortKey.equals(primaryKey)) {
                ArchiveProbeHit existingShort = out.get(shortKey);
                if (existingShort == null || epochMillis >= existingShort.lastObsEpochMillis()) {
                    out.put(shortKey, hit);
                }
            }
        } else if (!existing.formats().contains(format)) {
            List<String> fmts = new ArrayList<>(existing.formats());
            fmts.add(format);
            out.put(primaryKey, new ArchiveProbeHit(existing.lastObsEpochMillis(), existing.latencyMinutes(),
                    List.copyOf(fmts), existing.fileUrl()));
        }
    }

    private static long parseDurationMillis(String duration) {
        if (duration.endsWith("H")) {
            return Long.parseLong(duration.substring(0, duration.length() - 1)) * 3_600_000L;
        }
        if (duration.endsWith("D")) {
            return Long.parseLong(duration.substring(0, duration.length() - 1)) * 86_400_000L;
        }
        if (duration.endsWith("M")) {
            return Long.parseLong(duration.substring(0, duration.length() - 1)) * 60_000L;
        }
        return 3_600_000L;
    }

    private String sendGetString(String url, DataSourceConfig source) throws IOException {
        try {
            HttpRequest request = (source != null)
                    ? HttpRequestAuthApplier.buildRequest(url, source, requestTimeout)
                    : HttpRequest.newBuilder().uri(java.net.URI.create(url)).timeout(requestTimeout).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = response.statusCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " from " + url);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted: " + url, e);
        }
    }

    private static void addConstellationToken(String token, Set<GnssConstellation> out) {
        if (token == null || token.isBlank()) {
            return;
        }
        String norm = token.trim().toUpperCase(Locale.US);
        if (norm.equals("G") || norm.contains("GPS")) {
            out.add(GnssConstellation.GPS);
        }
        if (norm.equals("E") || norm.contains("GAL")) {
            out.add(GnssConstellation.GALILEO);
        }
        if (norm.equals("R") || norm.contains("GLO")) {
            out.add(GnssConstellation.GLONASS);
        }
        if (norm.equals("C") || norm.contains("BDS") || norm.contains("BEI")) {
            out.add(GnssConstellation.BEIDOU);
        }
    }

    private static GnssStationNetwork parseNetwork(String name, GnssStationNetwork fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            return GnssStationNetwork.valueOf(name.trim().toUpperCase(Locale.US));
        } catch (IllegalArgumentException e) {
            return GnssStationNetwork.fromScope(name);
        }
    }

    private static int findHeaderIndex(String[] headers, String... aliases) {
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].replace("\"", "").trim();
            for (String a : aliases) {
                if (h.equals(a)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String str(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return "";
        }
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : "";
    }

    private static double dbl(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return Double.NaN;
        }
        try {
            return obj.get(key).getAsDouble();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private static double parseDoubleSafe(String val, double fallback) {
        try {
            return Double.parseDouble(val.split("\\s+")[0]);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    private static double firstFinite(double... values) {
        for (double v : values) {
            if (Double.isFinite(v)) {
                return v;
            }
        }
        return Double.NaN;
    }

    private static int extractHttpStatus(String msg) {
        if (msg == null) {
            return 0;
        }
        Matcher m = Pattern.compile("HTTP\\s+(\\d{3})").matcher(msg);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}

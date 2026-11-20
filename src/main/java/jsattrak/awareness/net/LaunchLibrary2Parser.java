package jsattrak.awareness.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import jsattrak.awareness.LaunchRecord;

/**
 * Defensive parser mapping Launch Library 2 JSON payloads into {@link LaunchRecord} instances.
 */
public final class LaunchLibrary2Parser {

    private static final double ZERO_COORD_EPSILON = 1e-6;

    private LaunchLibrary2Parser() {
    }

    /**
     * Parses the {@code results} array of a Launch Library 2 response body into immutable launch records.
     * Returns an empty list on null, blank, or malformed payloads.
     */
    public static List<LaunchRecord> parseResults(String jsonBody) {
        try {
            return parseResultsStrict(jsonBody);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    /**
     * Parses the {@code results} array of a Launch Library 2 response body, rejecting
     * non-JSON payloads, non-object roots, or responses missing a {@code results} array.
     */
    static List<LaunchRecord> parseResultsStrict(String jsonBody) {
        if (jsonBody == null || jsonBody.isBlank()) {
            throw new IllegalArgumentException("Empty Launch Library 2 response body");
        }
        JsonElement rootElement;
        try {
            rootElement = JsonParser.parseString(jsonBody);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Malformed Launch Library 2 JSON", e);
        }
        if (!rootElement.isJsonObject()) {
            throw new IllegalArgumentException("Launch Library 2 response root is not a JSON object");
        }

        JsonObject root = rootElement.getAsJsonObject();
        JsonArray results = optArray(root, "results");
        if (results == null) {
            throw new IllegalArgumentException("Launch Library 2 response is missing a 'results' array");
        }
        if (results.isEmpty()) {
            return List.of();
        }

        List<LaunchRecord> records = new ArrayList<>(results.size());
        for (JsonElement element : results) {
            if (!element.isJsonObject()) {
                continue;
            }
            LaunchRecord record = parseSingleLaunch(element.getAsJsonObject());
            if (record != null) {
                records.add(record);
            }
        }
        return List.copyOf(records);
    }

    private static LaunchRecord parseSingleLaunch(JsonObject item) {
        Long netMillis = parseIsoEpochMillis(optString(item, "net"));
        if (netMillis == null) {
            return null;
        }

        Long windowStartParsed = parseIsoEpochMillis(optString(item, "window_start"));
        long windowStartMillis = windowStartParsed != null ? windowStartParsed : netMillis;

        Long windowEndParsed = parseIsoEpochMillis(optString(item, "window_end"));
        long windowEndMillis = (windowEndParsed != null && windowEndParsed >= netMillis)
                ? windowEndParsed
                : netMillis;

        String id = optString(item, "id");
        String topLevelName = optString(item, "name");

        JsonObject missionObj = optObject(item, "mission");
        String missionName = optString(missionObj, "name");
        if (missionName.isBlank()) {
            missionName = topLevelName;
        }
        String description = optString(missionObj, "description");
        if (description.isBlank()) {
            description = firstNonBlank(optString(item, "failreason"), optString(item, "holdreason"));
        }

        JsonObject orbitObj = optObject(missionObj, "orbit");
        String targetOrbit = firstNonBlank(optString(orbitObj, "abbrev"), optString(orbitObj, "name"));
        boolean orbital = isOrbitalOrbit(optString(orbitObj, "abbrev"), optString(orbitObj, "name"));

        JsonObject providerObj = optObject(item, "launch_service_provider");
        String provider = optString(providerObj, "name");

        JsonObject rocketObj = optObject(item, "rocket");
        JsonObject configObj = optObject(rocketObj, "configuration");
        String vehicle = firstNonBlank(
                optString(configObj, "full_name"),
                optString(configObj, "name"),
                optString(rocketObj, "name"));

        String cosparId = firstNonBlank(optString(item, "cospar_id"), optString(item, "launch_designator"));

        JsonObject statusObj = optObject(item, "status");
        String status = firstNonBlank(optString(statusObj, "abbrev"), optString(statusObj, "name"));
        String outcome = deriveOutcome(optString(item, "outcome"), status);

        String webcastUrl = extractWebcastUrl(item);

        JsonObject padObj = optObject(item, "pad");
        String padName = optString(padObj, "name");
        JsonObject locationObj = optObject(padObj, "location");
        String spaceportName = optString(locationObj, "name");
        String country = extractCountry(padObj, locationObj);

        Double lat = parseCoordinate(padObj != null ? padObj.get("latitude") : null, -90.0, 90.0);
        Double lon = parseCoordinate(padObj != null ? padObj.get("longitude") : null, -180.0, 180.0);
        boolean hasValidCoords = lat != null && lon != null;
        double latDeg = hasValidCoords ? lat : 0.0;
        double lonDeg = hasValidCoords ? lon : 0.0;

        boolean fixedLandPad = isFixedLandPad(hasValidCoords, latDeg, lonDeg, padName, spaceportName);

        return LaunchRecord.builder()
                .id(id)
                .missionName(missionName)
                .provider(provider)
                .vehicle(vehicle)
                .targetOrbit(targetOrbit)
                .cosparId(cosparId)
                .netEpochMillis(netMillis)
                .windowStartMillis(windowStartMillis)
                .windowEndMillis(windowEndMillis)
                .status(status)
                .outcome(outcome)
                .orbital(orbital)
                .description(description)
                .webcastUrl(webcastUrl)
                .padName(padName)
                .spaceportName(spaceportName)
                .country(country)
                .latDeg(latDeg)
                .lonDeg(lonDeg)
                .fixedLandPad(fixedLandPad)
                .build();
    }

    private static boolean isOrbitalOrbit(String abbrev, String name) {
        if ("suborbital".equalsIgnoreCase(abbrev) || "sub".equalsIgnoreCase(abbrev)) {
            return false;
        }
        return !"suborbital".equalsIgnoreCase(name);
    }

    private static String deriveOutcome(String explicitOutcome, String status) {
        if (!explicitOutcome.isBlank()) {
            return explicitOutcome;
        }
        String lower = status.toLowerCase(Locale.ROOT);
        if (lower.contains("success") || lower.contains("failure") || lower.contains("partial")) {
            return status;
        }
        return "";
    }

    private static String extractWebcastUrl(JsonObject item) {
        String url = firstUrlFromArray(optArray(item, "vidURLs"));
        if (!url.isBlank()) {
            return url;
        }
        return firstUrlFromArray(optArray(item, "vid_urls"));
    }

    private static String firstUrlFromArray(JsonArray arr) {
        if (arr == null || arr.isEmpty()) {
            return "";
        }
        for (JsonElement elem : arr) {
            if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isString()) {
                String url = elem.getAsString().trim();
                if (!url.isEmpty()) {
                    return url;
                }
            } else if (elem.isJsonObject()) {
                String url = optString(elem.getAsJsonObject(), "url");
                if (!url.isBlank()) {
                    return url;
                }
            }
        }
        return "";
    }

    private static String extractCountry(JsonObject padObj, JsonObject locationObj) {
        String code = optString(locationObj, "country_code");
        if (!code.isBlank()) {
            return code;
        }
        String fromLocCountry = extractCountryField(locationObj != null ? locationObj.get("country") : null);
        if (!fromLocCountry.isBlank()) {
            return fromLocCountry;
        }
        return extractCountryField(padObj != null ? padObj.get("country") : null);
    }

    private static String extractCountryField(JsonElement elem) {
        if (elem == null || elem.isJsonNull()) {
            return "";
        }
        if (elem.isJsonPrimitive()) {
            return elem.getAsString().trim();
        }
        if (elem.isJsonObject()) {
            JsonObject obj = elem.getAsJsonObject();
            return firstNonBlank(
                    optString(obj, "alpha_3_code"),
                    optString(obj, "alpha_2_code"),
                    optString(obj, "name"));
        }
        return "";
    }

    private static boolean isFixedLandPad(
            boolean hasValidCoords, double latDeg, double lonDeg, String padName, String spaceportName) {
        if (!hasValidCoords) {
            return false;
        }
        if (Math.abs(latDeg) < ZERO_COORD_EPSILON && Math.abs(lonDeg) < ZERO_COORD_EPSILON) {
            return false;
        }
        return !containsMobileKeyword(padName) && !containsMobileKeyword(spaceportName);
    }

    private static boolean containsMobileKeyword(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("sea")
                || lower.contains("ocean")
                || lower.contains("barge")
                || lower.contains("air launch")
                || lower.contains("mobile")
                || lower.contains("ship");
    }

    private static Long parseIsoEpochMillis(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
            } catch (DateTimeParseException e) {
                return null;
            }
        }
    }

    private static Double parseCoordinate(JsonElement elem, double min, double max) {
        if (elem == null || elem.isJsonNull() || !elem.isJsonPrimitive()) {
            return null;
        }
        try {
            double val = elem.getAsJsonPrimitive().isNumber()
                    ? elem.getAsDouble()
                    : Double.parseDouble(elem.getAsString().trim());
            if (Double.isNaN(val) || Double.isInfinite(val) || val < min || val > max) {
                return null;
            }
            return val;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static JsonObject optObject(JsonObject parent, String member) {
        if (parent == null) {
            return null;
        }
        JsonElement elem = parent.get(member);
        return (elem != null && elem.isJsonObject()) ? elem.getAsJsonObject() : null;
    }

    private static JsonArray optArray(JsonObject parent, String member) {
        if (parent == null) {
            return null;
        }
        JsonElement elem = parent.get(member);
        return (elem != null && elem.isJsonArray()) ? elem.getAsJsonArray() : null;
    }

    private static String optString(JsonObject parent, String member) {
        if (parent == null) {
            return "";
        }
        JsonElement elem = parent.get(member);
        if (elem == null || elem.isJsonNull() || !elem.isJsonPrimitive()) {
            return "";
        }
        return elem.getAsString().trim();
    }

    private static String firstNonBlank(String... values) {
        for (String val : values) {
            if (val != null && !val.isBlank()) {
                return val;
            }
        }
        return "";
    }
}

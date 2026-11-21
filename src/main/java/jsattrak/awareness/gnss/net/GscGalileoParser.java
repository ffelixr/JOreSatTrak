package jsattrak.awareness.gnss.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.GnssOutageNotice;
import jsattrak.awareness.gnss.OperatorStatusRecord;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Parser for European GNSS Service Centre (GSC) Galileo constellation status and NAGU feeds
 * (XML, JSON, or structured table rows) with explicit detection of unparseable HTML portal responses.
 */
public final class GscGalileoParser {

    private static final Pattern GALILEO_PRN_PATTERN = Pattern.compile("\\bE(\\d{2})\\b", Pattern.CASE_INSENSITIVE);

    private GscGalileoParser() {
    }

    public static List<OperatorStatusRecord> parseConstellationStatus(String content, long fetchedAtMillis) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String trimmed = content.stripLeading();
        if (trimmed.startsWith("<") && !looksLikeHtml(trimmed)) {
            List<OperatorStatusRecord> xml = tryParseStatusXml(trimmed, fetchedAtMillis);
            if (!xml.isEmpty()) {
                return xml;
            }
        }
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            List<OperatorStatusRecord> json = tryParseStatusJson(trimmed, fetchedAtMillis);
            if (!json.isEmpty()) {
                return json;
            }
        }
        return parseStatusLines(content, fetchedAtMillis);
    }

    /**
     * Parses GSC NAGU notices (XML, JSON, or structured text). Throws {@link IOException} when the
     * response is an HTML portal page or contains no parseable notice structure, allowing the caller
     * to trigger the §11 graceful fallback.
     */
    public static List<GnssOutageNotice> parseNagus(String content) throws IOException {
        if (content == null || content.isBlank()) {
            throw new IOException("Empty GSC NAGU response");
        }
        String trimmed = content.stripLeading();
        if (looksLikeHtml(trimmed)) {
            throw new IOException("GSC NAGU returned an HTML portal page instead of machine-readable notices");
        }
        if (trimmed.startsWith("<")) {
            return parseNaguXml(trimmed);
        }
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return parseNaguJson(trimmed);
        }
        throw new IOException("Unsupported GSC NAGU format");
    }

    private static List<OperatorStatusRecord> tryParseStatusXml(String xml, long fetchedAtMillis) {
        try {
            Document doc = parseSecureXml(xml);
            NodeList nodes = doc.getElementsByTagName("*");
            List<OperatorStatusRecord> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (!(node instanceof Element el)) {
                    continue;
                }
                String prn = firstNonEmpty(
                        el.getAttribute("prn"),
                        childText(el, "prn"),
                        childText(el, "sv"),
                        childText(el, "id"));
                String rawStatus = firstNonEmpty(
                        el.getAttribute("status"),
                        childText(el, "status"),
                        childText(el, "operationalStatus"));
                if (prn.isEmpty() || rawStatus.isEmpty()) {
                    continue;
                }
                String normalizedPrn = normalizeGalileoPrn(prn);
                if (normalizedPrn.isEmpty() || !seen.add(normalizedPrn)) {
                    continue;
                }
                OperatorStatusRecord.OperatorHealth health = classifyGalileoStatus(rawStatus);
                if (health == null) {
                    continue;
                }
                String remarks = firstNonEmpty(el.getAttribute("remarks"), childText(el, "remarks"), "");
                String detail = remarks.isEmpty() ? "GSC status: " + rawStatus : "GSC status: " + rawStatus + " (" + remarks + ")";
                out.add(new OperatorStatusRecord(
                        normalizedPrn, GnssConstellation.GALILEO, "GSC", health, rawStatus, fetchedAtMillis, detail));
            }
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<OperatorStatusRecord> tryParseStatusJson(String json, long fetchedAtMillis) {
        try {
            JsonElement root = JsonParser.parseString(json);
            JsonArray arr = root.isJsonArray() ? root.getAsJsonArray()
                    : (root.isJsonObject() && root.getAsJsonObject().has("satellites")
                    ? root.getAsJsonObject().getAsJsonArray("satellites") : null);
            if (arr == null) {
                return List.of();
            }
            List<OperatorStatusRecord> out = new ArrayList<>();
            for (JsonElement item : arr) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject obj = item.getAsJsonObject();
                String prn = normalizeGalileoPrn(jsonStr(obj, "prn", "id", "sv"));
                String rawStatus = jsonStr(obj, "status", "operationalStatus");
                OperatorStatusRecord.OperatorHealth health = classifyGalileoStatus(rawStatus);
                if (!prn.isEmpty() && health != null) {
                    out.add(new OperatorStatusRecord(
                            prn, GnssConstellation.GALILEO, "GSC", health, rawStatus, fetchedAtMillis,
                            "GSC status: " + rawStatus));
                }
            }
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<OperatorStatusRecord> parseStatusLines(String content, long fetchedAtMillis) {
        Pattern trPattern = Pattern.compile("<tr\\b[^>]*>(.*?)</tr>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher trMatcher = trPattern.matcher(content);
        List<String> lines = new ArrayList<>();
        while (trMatcher.find()) {
            String row = trMatcher.group(1)
                    .replaceAll("(?i)<t[dh][^>]*>", " ")
                    .replaceAll("<[^>]+>", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (!row.isEmpty()) {
                lines.add(row);
            }
        }
        if (lines.isEmpty()) {
            String stripped = content.replaceAll("(?i)</tr>", "\n").replaceAll("<[^>]+>", " ");
            for (String s : stripped.split("\\R")) {
                lines.add(s);
            }
        }
        List<OperatorStatusRecord> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher m = GALILEO_PRN_PATTERN.matcher(line);
            if (!m.find()) {
                continue;
            }
            String prn = "E" + m.group(1);
            OperatorStatusRecord.OperatorHealth health = classifyGalileoStatus(line);
            if (health != null && seen.add(prn)) {
                out.add(new OperatorStatusRecord(
                        prn, GnssConstellation.GALILEO, "GSC", health, health.name(), fetchedAtMillis,
                        "GSC status: " + health.name()));
            }
        }
        return List.copyOf(out);
    }

    private static List<GnssOutageNotice> parseNaguXml(String xml) throws IOException {
        try {
            Document doc = parseSecureXml(xml);
            Element root = doc.getDocumentElement();
            String rootTag = root != null && root.getTagName() != null
                    ? root.getTagName().toLowerCase(Locale.ROOT)
                    : "";
            if (rootTag.equals("html") || rootTag.contains("error")) {
                throw new IOException("GSC NAGU returned error/HTML XML root <" + rootTag + ">");
            }
            NodeList nodes = doc.getElementsByTagName("*");
            List<GnssOutageNotice> out = new ArrayList<>();
            boolean sawNoticeContainer = rootTag.contains("nagu") || rootTag.contains("notice")
                    || rootTag.contains("constellation");
            for (int i = 0; i < nodes.getLength(); i++) {
                if (!(nodes.item(i) instanceof Element el)) {
                    continue;
                }
                String tag = el.getTagName().toLowerCase(Locale.ROOT);
                if (!tag.contains("nagu") && !tag.contains("notice") && !tag.contains("item")) {
                    continue;
                }
                sawNoticeContainer = true;
                String prn = normalizeGalileoPrn(firstNonEmpty(el.getAttribute("prn"), childText(el, "prn"), childText(el, "satellite")));
                String id = firstNonEmpty(el.getAttribute("id"), childText(el, "id"), childText(el, "number"), "");
                if (prn.isEmpty() || id.isEmpty()) {
                    continue;
                }
                String type = firstNonEmpty(el.getAttribute("type"), childText(el, "type"), "NAGU");
                long start = parseIsoMillis(firstNonEmpty(el.getAttribute("start"), childText(el, "start")));
                long end = parseIsoMillis(firstNonEmpty(el.getAttribute("end"), childText(el, "end")));
                boolean planned = type.toUpperCase(Locale.ROOT).contains("PLAN");
                boolean cancelled = type.toUpperCase(Locale.ROOT).contains("CANCEL");
                String summary = firstNonEmpty(el.getAttribute("summary"), childText(el, "summary"), type);
                out.add(new GnssOutageNotice(
                        id, GnssConstellation.GALILEO, prn, "", type, planned, cancelled,
                        start, end, summary, "GSC"));
            }
            if (out.isEmpty() && !sawNoticeContainer) {
                throw new IOException("Unrecognized GSC NAGU XML document <" + rootTag + ">");
            }
            return List.copyOf(out);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to parse GSC NAGU XML: " + e.getMessage(), e);
        }
    }

    private static List<GnssOutageNotice> parseNaguJson(String json) throws IOException {
        try {
            JsonElement root = JsonParser.parseString(json);
            JsonArray arr = root.isJsonArray() ? root.getAsJsonArray()
                    : (root.isJsonObject() && root.getAsJsonObject().has("nagus")
                    ? root.getAsJsonObject().getAsJsonArray("nagus") : null);
            if (arr == null) {
                throw new IOException("Unexpected GSC NAGU JSON layout");
            }
            List<GnssOutageNotice> out = new ArrayList<>();
            for (JsonElement item : arr) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject obj = item.getAsJsonObject();
                String prn = normalizeGalileoPrn(jsonStr(obj, "prn", "satellite"));
                String id = jsonStr(obj, "id", "number", "naguNumber");
                if (prn.isEmpty() || id.isEmpty()) {
                    continue;
                }
                String type = firstNonEmpty(jsonStr(obj, "type"), "NAGU", "");
                long start = parseIsoMillis(jsonStr(obj, "start", "startTime"));
                long end = parseIsoMillis(jsonStr(obj, "end", "endTime"));
                boolean planned = type.toUpperCase(Locale.ROOT).contains("PLAN");
                boolean cancelled = type.toUpperCase(Locale.ROOT).contains("CANCEL");
                String summary = firstNonEmpty(jsonStr(obj, "summary", "description"), type, "");
                out.add(new GnssOutageNotice(
                        id, GnssConstellation.GALILEO, prn, "", type, planned, cancelled,
                        start, end, summary, "GSC"));
            }
            return List.copyOf(out);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to parse GSC NAGU JSON: " + e.getMessage(), e);
        }
    }

    private static Document parseSecureXml(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        return dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static OperatorStatusRecord.OperatorHealth classifyGalileoStatus(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.contains("COMMISSIONING") || upper.contains("TESTING") || upper.contains("IN TEST")) {
            return OperatorStatusRecord.OperatorHealth.TESTING;
        }
        if (upper.contains("NOT AVAILABLE") || upper.contains("UNAVAILABLE")
                || upper.contains("UNUSABLE") || upper.contains("NOT USABLE")
                || upper.contains("OUT OF SERVICE") || upper.contains("REMOVED")) {
            return OperatorStatusRecord.OperatorHealth.NOT_AVAILABLE;
        }
        if (upper.contains("USABLE") || upper.contains("OPERATIONAL") || upper.contains("AVAILABLE")) {
            return OperatorStatusRecord.OperatorHealth.USABLE;
        }
        return null;
    }

    private static String normalizeGalileoPrn(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        if (upper.matches("E\\d{2}")) {
            return upper;
        }
        if (upper.matches("\\d{1,2}")) {
            return String.format("E%02d", Integer.parseInt(upper));
        }
        Matcher m = GALILEO_PRN_PATTERN.matcher(upper);
        return m.find() ? "E" + m.group(1) : "";
    }

    private static boolean looksLikeHtml(String text) {
        String lower = text.stripLeading().toLowerCase(Locale.ROOT);
        return lower.startsWith("<!doctype html")
                || lower.startsWith("<html")
                || lower.startsWith("<head")
                || lower.startsWith("<body")
                || lower.startsWith("<div")
                || (lower.contains("<html") && lower.contains("</html>"))
                || lower.contains("<a href");
    }

    private static long parseIsoMillis(String text) {
        if (text == null || text.isBlank()) {
            return 0L;
        }
        try {
            return Instant.parse(text.trim()).toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String childText(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        if (list != null && list.getLength() > 0 && list.item(0).getTextContent() != null) {
            return list.item(0).getTextContent().trim();
        }
        return "";
    }

    private static String jsonStr(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                return obj.get(key).getAsString().trim();
            }
        }
        return "";
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }
}

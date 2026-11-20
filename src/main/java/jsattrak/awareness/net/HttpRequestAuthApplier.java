package jsattrak.awareness.net;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import jsattrak.awareness.config.AuthMode;
import jsattrak.awareness.config.DataSourceConfig;

/**
 * Builds {@link HttpRequest} instances with authentication headers or query parameters
 * applied according to a {@link DataSourceConfig}.
 */
public final class HttpRequestAuthApplier {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    private HttpRequestAuthApplier() {
    }

    /**
     * Builds a GET {@link HttpRequest} for {@code url} with configured authentication applied.
     */
    public static HttpRequest buildRequest(String url, DataSourceConfig source, Duration timeout) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Request URL must not be blank");
        }

        Duration effectiveTimeout = timeout != null ? timeout : DEFAULT_TIMEOUT;
        AuthMode mode = (source != null && source.getAuthMode() != null) ? source.getAuthMode() : AuthMode.NONE;
        String finalUrl = url.trim();

        if (mode == AuthMode.QUERY_PARAM && source != null) {
            finalUrl = appendQueryParam(finalUrl, source.getAuthKeyName(), source.getAuthSecret());
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(finalUrl))
                .timeout(effectiveTimeout)
                .header("User-Agent", "JOreSatTrak/0.1")
                .GET();

        if (source != null) {
            applyHeaderAuth(builder, mode, source);
        }
        return builder.build();
    }

    private static void applyHeaderAuth(HttpRequest.Builder builder, AuthMode mode, DataSourceConfig source) {
        String secret = nullToEmpty(source.getAuthSecret());
        switch (mode) {
            case API_KEY_HEADER -> {
                String headerName = isBlank(source.getAuthKeyName()) ? "Authorization" : source.getAuthKeyName().trim();
                if (!secret.isEmpty()) {
                    builder.header(headerName, secret);
                }
            }
            case BEARER_TOKEN -> {
                if (!secret.isEmpty()) {
                    builder.header("Authorization", "Bearer " + secret);
                }
            }
            case BASIC_AUTH -> {
                String username = nullToEmpty(source.getAuthUsername());
                if (!username.isEmpty() || !secret.isEmpty()) {
                    String raw = username + ":" + secret;
                    String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
                    builder.header("Authorization", "Basic " + encoded);
                }
            }
            case NONE, QUERY_PARAM -> {
                // No auth header required
            }
        }
    }

    private static String appendQueryParam(String url, String keyName, String secret) {
        String trimmedKey = nullToEmpty(keyName).trim();
        if (trimmedKey.isEmpty()) {
            return url;
        }
        String encodedKey = URLEncoder.encode(trimmedKey, StandardCharsets.UTF_8);
        String encodedVal = URLEncoder.encode(nullToEmpty(secret), StandardCharsets.UTF_8);
        String separator;
        if (url.endsWith("?") || url.endsWith("&")) {
            separator = "";
        } else {
            separator = url.contains("?") ? "&" : "?";
        }
        return url + separator + encodedKey + "=" + encodedVal;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

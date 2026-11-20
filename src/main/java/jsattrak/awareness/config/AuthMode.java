package jsattrak.awareness.config;

/**
 * Supported authentication mechanisms for external launch and TLE data feeds.
 */
public enum AuthMode {
    NONE,
    API_KEY_HEADER,
    BEARER_TOKEN,
    QUERY_PARAM,
    BASIC_AUTH
}

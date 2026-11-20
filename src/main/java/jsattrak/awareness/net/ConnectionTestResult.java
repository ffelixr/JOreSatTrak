package jsattrak.awareness.net;

/**
 * Result of testing connectivity and payload parsing against an external data source.
 */
public record ConnectionTestResult(boolean success, int httpStatus, int parsedCount, String message) {

    public ConnectionTestResult {
        message = message == null ? "" : message;
    }
}

package jsattrak.awareness.gnss;

/**
 * Planned or unplanned satellite outage notice (e.g. GPS NANU or Galileo NAGU).
 */
public record GnssOutageNotice(
        String noticeId,
        GnssConstellation constellation,
        String prnId,
        String svn,
        String noticeType,
        boolean planned,
        boolean cancelled,
        long startEpochMillis,
        long endEpochMillis,
        String summary,
        String sourceName) {

    /**
     * Returns {@code true} if this notice is not cancelled and covers {@code epochMillis}.
     */
    public boolean isActiveAt(long epochMillis) {
        return !cancelled
                && startEpochMillis > 0L
                && epochMillis >= startEpochMillis
                && (endEpochMillis <= 0L || epochMillis <= endEpochMillis);
    }
}

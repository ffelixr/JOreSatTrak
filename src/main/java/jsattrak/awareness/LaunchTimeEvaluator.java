package jsattrak.awareness;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Pure time-evaluation helpers for launch urgency, upcoming/recent filtering, and countdown formatting.
 */
public final class LaunchTimeEvaluator {

    /** Post-NET inspection grace buffer (15 minutes). */
    public static final long GRACE_BUFFER_MILLIS = 15L * 60L * 1000L;
    /** Lookback window for recent launches (72 hours). */
    public static final long RECENT_WINDOW_MILLIS = 72L * 3600L * 1000L;

    private static final long MILLIS_PER_MINUTE = 60L * 1000L;
    private static final long MILLIS_PER_HOUR = 3600L * 1000L;
    private static final long MILLIS_PER_DAY = 24L * MILLIS_PER_HOUR;

    private static final Comparator<LaunchRecord> SOONEST_FIRST =
            Comparator.comparingLong(LaunchRecord::getNetEpochMillis)
                    .thenComparing(LaunchRecord::getId, Comparator.nullsLast(String::compareTo));

    private static final Comparator<LaunchRecord> MOST_RECENT_FIRST =
            Comparator.comparingLong(LaunchRecord::getNetEpochMillis).reversed()
                    .thenComparing(LaunchRecord::getId, Comparator.nullsLast(String::compareTo));

    private LaunchTimeEvaluator() {
    }

    /**
     * Evaluates the urgency band of a launch relative to the given simulation epoch.
     */
    public static LaunchUrgency evaluateUrgency(LaunchRecord launch, long simEpochMillis) {
        Objects.requireNonNull(launch, "launch");
        long expiryMillis = Math.max(launch.effectiveWindowEndMillis(), launch.getNetEpochMillis() + GRACE_BUFFER_MILLIS);
        if (simEpochMillis > expiryMillis) {
            return LaunchUrgency.EXPIRED;
        }
        long deltaMillis = launch.getNetEpochMillis() - simEpochMillis;
        if (deltaMillis < MILLIS_PER_DAY) {
            return LaunchUrgency.IMMINENT_OR_IN_WINDOW;
        }
        if (deltaMillis < 3L * MILLIS_PER_DAY) {
            return LaunchUrgency.SOON_1_TO_3D;
        }
        return LaunchUrgency.UPCOMING_3_TO_7D;
    }

    /**
     * Returns whether a launch should appear in the upcoming view for the given simulation epoch and horizon.
     */
    public static boolean isVisibleInUpcomingHorizon(LaunchRecord launch, long simEpochMillis, int horizonDays) {
        if (launch == null || horizonDays <= 0) {
            return false;
        }
        if (evaluateUrgency(launch, simEpochMillis) == LaunchUrgency.EXPIRED) {
            return false;
        }
        long horizonMillis = (long) horizonDays * MILLIS_PER_DAY;
        return (launch.getNetEpochMillis() - simEpochMillis) <= horizonMillis;
    }

    /**
     * Returns whether a launch falls inside the real-world past 72-hour recent window.
     */
    public static boolean isVisibleInRecentWindow(LaunchRecord launch, long realNowMillis) {
        if (launch == null) {
            return false;
        }
        long ageMillis = realNowMillis - launch.getNetEpochMillis();
        return ageMillis >= 0L && ageMillis <= RECENT_WINDOW_MILLIS;
    }

    /**
     * Filters and sorts upcoming launches soonest-first within the given horizon.
     */
    public static List<LaunchRecord> filterUpcoming(List<LaunchRecord> launches, long simEpochMillis, int horizonDays) {
        if (launches == null || launches.isEmpty() || horizonDays <= 0) {
            return List.of();
        }
        return launches.stream()
                .filter(launch -> isVisibleInUpcomingHorizon(launch, simEpochMillis, horizonDays))
                .sorted(SOONEST_FIRST)
                .toList();
    }

    /**
     * Filters and sorts launches from the past 72 real-world hours most-recent-first.
     */
    public static List<LaunchRecord> filterRecent(List<LaunchRecord> launches, long realNowMillis) {
        if (launches == null || launches.isEmpty()) {
            return List.of();
        }
        return launches.stream()
                .filter(launch -> isVisibleInRecentWindow(launch, realNowMillis))
                .sorted(MOST_RECENT_FIRST)
                .toList();
    }

    /**
     * Formats the T-minus / In Window / T-plus countdown label relative to {@code refEpochMillis}.
     */
    public static String formatCountdown(LaunchRecord launch, long refEpochMillis) {
        Objects.requireNonNull(launch, "launch");
        long net = launch.getNetEpochMillis();
        long effectiveEnd = launch.effectiveWindowEndMillis();

        if (effectiveEnd > net && refEpochMillis >= net && refEpochMillis <= effectiveEnd) {
            return "In Window";
        }

        if (refEpochMillis < net) {
            long remaining = net - refEpochMillis;
            long totalHours = remaining / MILLIS_PER_HOUR;
            if (totalHours >= 24L) {
                long days = totalHours / 24L;
                long hours = totalHours % 24L;
                return String.format("T-%dd %02dh", days, hours);
            }
            if (totalHours > 0L) {
                long mins = (remaining % MILLIS_PER_HOUR) / MILLIS_PER_MINUTE;
                return String.format("T-%02dh %02dm", totalHours, mins);
            }
            long mins = remaining / MILLIS_PER_MINUTE;
            return String.format("T-%02dm", mins);
        }

        long elapsed = refEpochMillis - net;
        long totalHours = elapsed / MILLIS_PER_HOUR;
        if (totalHours >= 24L) {
            long days = totalHours / 24L;
            long hours = totalHours % 24L;
            return String.format("T+%dd %02dh", days, hours);
        }
        if (totalHours >= 1L) {
            return String.format("T+%02dh", totalHours);
        }
        long mins = elapsed / MILLIS_PER_MINUTE;
        return String.format("T+%02dm", mins);
    }
}

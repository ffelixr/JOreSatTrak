package jsattrak.awareness;

import java.util.List;
import name.gano.astro.time.Time;
import org.junit.jupiter.api.Test;

public class LaunchTimeEvaluatorTest {

    @Test
    public void testTimeMillisRoundTrip() {
        Time t = new Time();
        long target = 1_795_132_800_000L; // 2026-11-20T00:00:00Z
        t.setTimeInMillis(target);
        if (Math.abs(t.getTimeInMillis() - target) > 1L) {
            throw new AssertionError("Time epoch millis mismatch: expected " + target + ", got " + t.getTimeInMillis());
        }
    }

    @Test
    public void testUrgencyBandsAndHorizonFiltering() {
        long now = 1_795_132_800_000L;
        LaunchRecord l10h = sample("l1", now + 10L * 3600_000L, now + 12L * 3600_000L);
        LaunchRecord l48h = sample("l2", now + 48L * 3600_000L, now + 49L * 3600_000L);
        LaunchRecord l5d  = sample("l3", now + 120L * 3600_000L, now + 120L * 3600_000L);
        LaunchRecord l9d  = sample("l4", now + 216L * 3600_000L, now + 216L * 3600_000L);

        assertEq(LaunchUrgency.IMMINENT_OR_IN_WINDOW, LaunchTimeEvaluator.evaluateUrgency(l10h, now));
        assertEq(LaunchUrgency.SOON_1_TO_3D, LaunchTimeEvaluator.evaluateUrgency(l48h, now));
        assertEq(LaunchUrgency.UPCOMING_3_TO_7D, LaunchTimeEvaluator.evaluateUrgency(l5d, now));

        List<LaunchRecord> h1 = LaunchTimeEvaluator.filterUpcoming(List.of(l9d, l5d, l48h, l10h), now, 1);
        List<LaunchRecord> h3 = LaunchTimeEvaluator.filterUpcoming(List.of(l9d, l5d, l48h, l10h), now, 3);
        List<LaunchRecord> h7 = LaunchTimeEvaluator.filterUpcoming(List.of(l9d, l5d, l48h, l10h), now, 7);

        assertEq(1, h1.size());
        assertEq(2, h3.size());
        assertEq(3, h7.size());
        // Soonest-first ordering
        assertEq("l1", h7.get(0).getId());
        assertEq("l2", h7.get(1).getId());
        assertEq("l3", h7.get(2).getId());

        // Countdown formats: T-09h 30m and T-2d 06h
        LaunchRecord l9h30m = sample("c1", now + 9L * 3600_000L + 30L * 60_000L, now + 10L * 3600_000L);
        LaunchRecord l2d06h = sample("c2", now + 54L * 3600_000L, now + 55L * 3600_000L);
        assertEq("T-09h 30m", LaunchTimeEvaluator.formatCountdown(l9h30m, now));
        assertEq("T-2d 06h", LaunchTimeEvaluator.formatCountdown(l2d06h, now));
    }

    @Test
    public void testInWindowGraceBufferAndMalformedWindowEnd() {
        long net = 1_795_132_800_000L;
        LaunchRecord multiHour = sample("w1", net, net + 2L * 3600_000L);
        LaunchRecord instant = sample("w2", net, net - 5000L); // malformed windowEnd < net -> treated as NET

        assertEq(net, instant.effectiveWindowEndMillis());

        // Inside multi-hour window -> In Window + IMMINENT
        assertEq(LaunchUrgency.IMMINENT_OR_IN_WINDOW, LaunchTimeEvaluator.evaluateUrgency(multiHour, net + 3600_000L));
        assertEq("In Window", LaunchTimeEvaluator.formatCountdown(multiHour, net + 3600_000L));
        // Instant window at T+4m and T+10m -> still visible inside 15m grace buffer
        assertEq(LaunchUrgency.IMMINENT_OR_IN_WINDOW, LaunchTimeEvaluator.evaluateUrgency(instant, net + 10L * 60_000L));
        assertEq("T+04m", LaunchTimeEvaluator.formatCountdown(instant, net + 4L * 60_000L));
        assertEq("T+10m", LaunchTimeEvaluator.formatCountdown(instant, net + 10L * 60_000L));
        // Past max(windowEnd, NET + 15m) -> EXPIRED
        assertEq(LaunchUrgency.EXPIRED, LaunchTimeEvaluator.evaluateUrgency(instant, net + 16L * 60_000L));
        assertEq(LaunchUrgency.EXPIRED, LaunchTimeEvaluator.evaluateUrgency(multiHour, net + 2L * 3600_000L + 1L));
    }

    @Test
    public void testSimClockInPastAndRecent72hWindow() {
        long realNow = 1_795_132_800_000L;
        long simPast = realNow - 24L * 3600_000L;
        LaunchRecord futureLaunch = sample("f1", realNow + 12L * 3600_000L, realNow + 13L * 3600_000L);
        // 36h away from simPast -> SOON_1_TO_3D and visible in 3d horizon
        assertEq(LaunchUrgency.SOON_1_TO_3D, LaunchTimeEvaluator.evaluateUrgency(futureLaunch, simPast));
        assertEq(true, LaunchTimeEvaluator.isVisibleInUpcomingHorizon(futureLaunch, simPast, 3));

        LaunchRecord recent6h = sample("r0", realNow - 6L * 3600_000L, realNow - 6L * 3600_000L);
        LaunchRecord recent12h = sample("r1", realNow - 12L * 3600_000L, realNow - 12L * 3600_000L);
        LaunchRecord recent28h = sample("r1b", realNow - 28L * 3600_000L, realNow - 28L * 3600_000L);
        LaunchRecord old80h = sample("r2", realNow - 80L * 3600_000L, realNow - 80L * 3600_000L);

        List<LaunchRecord> recent = LaunchTimeEvaluator.filterRecent(
                List.of(old80h, recent28h, recent12h, recent6h, futureLaunch), realNow);
        assertEq(3, recent.size());
        // Most-recent-first ordering
        assertEq("r0", recent.get(0).getId());
        assertEq("r1", recent.get(1).getId());
        assertEq("r1b", recent.get(2).getId());

        assertEq("T+06h", LaunchTimeEvaluator.formatCountdown(recent6h, realNow));
        assertEq("T+1d 04h", LaunchTimeEvaluator.formatCountdown(recent28h, realNow));
    }

    private static LaunchRecord sample(String id, long netMillis, long windowEndMillis) {
        return new LaunchRecord(
                id,
                "Mission " + id,
                "Test Provider",
                "Test Vehicle",
                "LEO",
                "",
                netMillis,
                netMillis,
                windowEndMillis,
                "Go",
                "Pending",
                true,
                "Test mission",
                "https://example.org/live",
                "SLC-40",
                "Cape Canaveral SFS",
                "USA",
                28.5618571,
                -80.577366,
                true
        );
    }

    private static void assertEq(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("Expected <" + expected + "> but was <" + actual + ">");
        }
    }
}

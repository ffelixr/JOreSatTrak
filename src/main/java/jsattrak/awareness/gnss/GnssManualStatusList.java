package jsattrak.awareness.gnss;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Container for user-editable manual GNSS status overrides and last-reviewed metadata.
 */
public class GnssManualStatusList {

    private String lastReviewedIso;
    private List<GnssManualOverride> entries = new ArrayList<>();

    public GnssManualStatusList() {
    }

    public GnssManualStatusList(String lastReviewedIso, List<GnssManualOverride> entries) {
        this.lastReviewedIso = lastReviewedIso;
        this.entries = entries != null ? new ArrayList<>(entries) : new ArrayList<>();
    }

    public String getLastReviewedIso() {
        return lastReviewedIso;
    }

    public void setLastReviewedIso(String lastReviewedIso) {
        this.lastReviewedIso = lastReviewedIso;
    }

    public List<GnssManualOverride> getEntries() {
        return entries;
    }

    public void setEntries(List<GnssManualOverride> entries) {
        this.entries = entries != null ? new ArrayList<>(entries) : new ArrayList<>();
    }

    public Optional<GnssManualOverride> findByPrn(String prnId) {
        if (prnId == null || prnId.isBlank() || entries == null) {
            return Optional.empty();
        }
        String normalized = prnId.trim().toUpperCase(Locale.ROOT);
        for (GnssManualOverride entry : entries) {
            if (entry != null
                    && entry.prnId() != null
                    && entry.prnId().trim().toUpperCase(Locale.ROOT).equals(normalized)
                    && entry.status() != null) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the age in whole days between {@code lastReviewedIso} and {@code nowMillis}.
     */
    public long ageDays(long nowMillis) {
        if (lastReviewedIso == null || lastReviewedIso.isBlank()) {
            return Long.MAX_VALUE;
        }
        try {
            LocalDate reviewed = LocalDate.parse(lastReviewedIso.trim());
            LocalDate nowDate = Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC).toLocalDate();
            return Math.max(0L, ChronoUnit.DAYS.between(reviewed, nowDate));
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    public boolean isStale(long nowMillis, int thresholdDays) {
        return ageDays(nowMillis) > thresholdDays;
    }
}

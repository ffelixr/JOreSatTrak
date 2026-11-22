package jsattrak.awareness.gnss.geometry;

/**
 * Selectable positioning geometry metric for KPI readouts and spatial DOP heatmaps.
 */
public enum GnssDopMetric {
    PDOP("PDOP", false),
    GDOP("GDOP", false),
    HDOP("HDOP", false),
    VDOP("VDOP", false),
    TDOP("TDOP", false),
    VISIBLE_SVS("Visible SVs", true);

    private final String displayName;
    private final boolean countMetric;

    GnssDopMetric(String displayName, boolean countMetric) {
        this.displayName = displayName;
        this.countMetric = countMetric;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isCountMetric() {
        return countMetric;
    }

    public double extract(GnssDopSolution solution) {
        if (solution == null) {
            return countMetric ? 0.0 : Double.NaN;
        }
        return switch (this) {
            case PDOP -> solution.pdop();
            case GDOP -> solution.gdop();
            case HDOP -> solution.hdop();
            case VDOP -> solution.vdop();
            case TDOP -> solution.tdop();
            case VISIBLE_SVS -> solution.visibleCount();
        };
    }

    public static GnssDopMetric fromNameOrDefault(String name, GnssDopMetric fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            return GnssDopMetric.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    @Override
    public String toString() {
        return displayName;
    }
}

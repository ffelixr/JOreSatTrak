package jsattrak.awareness;

/**
 * Abstraction for a live situational awareness feed (e.g., orbital launches, future vessel feeds)
 * hosted inside the Live Awareness hub.
 */
public interface AwarenessFeed {

    /**
     * Returns the stable identifier for this feed (e.g., {@code "launches"}).
     */
    String getFeedId();

    /**
     * Returns the human-readable tab title for this feed.
     */
    String getDisplayName();

    /**
     * Returns the number of currently active/visible items in this feed.
     */
    int getActiveItemCount();

    /**
     * Returns whether this feed's map overlay is enabled.
     */
    boolean isMapOverlayEnabled();

    /**
     * Enables or disables this feed's map overlay.
     */
    void setMapOverlayEnabled(boolean enabled);
}

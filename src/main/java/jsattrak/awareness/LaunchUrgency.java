package jsattrak.awareness;

/**
 * Urgency classification for an upcoming or in-window orbital launch relative to a reference epoch.
 */
public enum LaunchUrgency {
    /** Launch is less than 24 hours away, currently inside its launch window, or within the post-NET grace buffer. */
    IMMINENT_OR_IN_WINDOW,
    /** Launch is between 1 and 3 days away. */
    SOON_1_TO_3D,
    /** Launch is 3 or more days away (e.g., within the 3-7 day horizon). */
    UPCOMING_3_TO_7D,
    /** Launch window plus post-NET grace buffer has elapsed. */
    EXPIRED
}

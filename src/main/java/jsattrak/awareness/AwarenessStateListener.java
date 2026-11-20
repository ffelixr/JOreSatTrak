package jsattrak.awareness;

/**
 * Observer notified when Live Awareness launch/TLE data, selection, status, or UI focus requests change.
 */
public interface AwarenessStateListener {

    /**
     * Called when launch lists, TLE matches, horizon, or global overlay visibility have changed.
     */
    void onAwarenessDataChanged();

    /**
     * Called when the currently selected launch identifier changes.
     */
    void onSelectionChanged(String selectedLaunchId);

    /**
     * Called when the coordinator emits a non-blocking status or warning message.
     */
    void onStatusMessage(String message, boolean isWarning);

    /**
     * Called when a map click or external trigger requests opening or focusing the Live Awareness window.
     */
    default void onOpenUiRequested() {
    }
}

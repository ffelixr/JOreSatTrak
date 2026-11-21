package jsattrak.awareness.gnss;

/**
 * Observer notified when GNSS constellation data, selection, status messages, or UI focus requests change.
 */
public interface GnssStateListener {

    void onGnssDataChanged();

    void onGnssSelectionChanged(String selectedPrnId);

    void onGnssStatusMessage(String message, boolean isWarning);

    default void onOpenGnssUiRequested() {
    }
}

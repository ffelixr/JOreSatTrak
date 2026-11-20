package jsattrak.awareness.gui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JInternalFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import jsattrak.awareness.AwarenessFeed;
import jsattrak.awareness.AwarenessStateListener;
import jsattrak.awareness.LaunchRecord;
import jsattrak.awareness.LiveAwarenessService;

/**
 * Unified MDI hub hosting Live Awareness feed tabs (starting with Launches), manual Refresh,
 * sync/stale status badge, and quick access to Live Data Sources configuration.
 */
public class LiveAwarenessInternalFrame extends JInternalFrame {

    private final LiveAwarenessService service;
    private final JTabbedPane feedTabbedPane;
    private final LaunchesAwarenessTab launchesTab;
    private final JLabel syncStatusLabel;
    private final JButton refreshButton;
    private final JButton sourcesButton;
    private final AwarenessStateListener serviceListener;
    private final List<AwarenessFeed> feeds = new ArrayList<>();
    private final List<Boolean> lastTabOverlayStates = new ArrayList<>();

    private volatile boolean disposed;
    private long lastSimEpochMillis = System.currentTimeMillis();
    private boolean lastRealTimeMode = true;
    private TimeZone lastDisplayTz = TimeZone.getTimeZone("UTC");

    public LiveAwarenessInternalFrame(
            LiveAwarenessService service,
            Consumer<Long> onJumpToT0,
            BiConsumer<Double, Double> onCenterMaps,
            Consumer<LaunchRecord> onImportTles,
            Runnable onOpenSources) {
        this(service, onJumpToT0, onCenterMaps, onImportTles, null, onOpenSources);
    }

    public LiveAwarenessInternalFrame(
            LiveAwarenessService service,
            Consumer<Long> onJumpToT0,
            BiConsumer<Double, Double> onCenterMaps,
            Consumer<LaunchRecord> onImportTles,
            Runnable onOpenSatBrowser,
            Runnable onOpenSources) {
        super("Live Awareness", true, true, true, true);
        this.service = Objects.requireNonNull(service, "service");
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        Icon frameIcon = loadIcon("/icons/logo/JSatTrakLogo_16.png");
        if (frameIcon != null) {
            setFrameIcon(frameIcon);
        }

        JPanel root = new JPanel(new BorderLayout());

        // Top header bar
        JPanel headerBar = new JPanel(new BorderLayout(8, 0));
        headerBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor()),
                new EmptyBorder(4, 8, 4, 8)));

        syncStatusLabel = new JLabel("Not synced yet");
        syncStatusLabel.setForeground(mutedForeground());

        JPanel rightActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightActions.setOpaque(false);

        refreshButton = new JButton("Refresh");
        Icon refreshIcon = loadIcon("/icons/gnome_2_18/view-refresh.png");
        if (refreshIcon != null) {
            refreshButton.setIcon(refreshIcon);
        }
        refreshButton.setToolTipText("Fetch latest launches and TLE catalogs");
        refreshButton.addActionListener(e -> {
            service.refreshNowAsync();
            updateHeaderAndTabs();
        });

        sourcesButton = new JButton("\u2699 Sources");
        sourcesButton.setToolTipText("Configure Live Data Sources & Authentication...");
        sourcesButton.addActionListener(e -> {
            if (onOpenSources != null) {
                onOpenSources.run();
            }
        });

        rightActions.add(refreshButton);
        rightActions.add(sourcesButton);

        headerBar.add(syncStatusLabel, BorderLayout.CENTER);
        headerBar.add(rightActions, BorderLayout.EAST);
        root.add(headerBar, BorderLayout.NORTH);

        // Center tabbed pane of AwarenessFeed tabs
        feedTabbedPane = new JTabbedPane();
        launchesTab = new LaunchesAwarenessTab(service, onJumpToT0, onCenterMaps, onImportTles, onOpenSatBrowser);
        addFeedTab(service, launchesTab);
        root.add(feedTabbedPane, BorderLayout.CENTER);

        setContentPane(root);
        setSize(780, 560);
        setMinimumSize(new Dimension(540, 380));

        this.serviceListener = new AwarenessStateListener() {
            @Override
            public void onAwarenessDataChanged() {
                runOnEdt(LiveAwarenessInternalFrame.this::updateHeaderAndTabs);
            }

            @Override
            public void onSelectionChanged(String selectedLaunchId) {
            }

            @Override
            public void onStatusMessage(String message, boolean isWarning) {
                runOnEdt(() -> {
                    updateHeaderAndTabs();
                    if (message != null && !message.isBlank()) {
                        syncStatusLabel.setToolTipText(message);
                    }
                });
            }
        };
        service.addListener(serviceListener);
        updateHeaderAndTabs();
    }

    /**
     * Registers an {@link AwarenessFeed} tab in the hub.
     */
    public final void addFeedTab(AwarenessFeed feed, Component component) {
        Objects.requireNonNull(feed, "feed");
        Objects.requireNonNull(component, "component");
        feeds.add(feed);
        boolean overlayEnabled = feed.isMapOverlayEnabled();
        lastTabOverlayStates.add(overlayEnabled);
        int idx = feedTabbedPane.getTabCount();
        feedTabbedPane.addTab(formatTabTitle(feed, countForFeed(feed)), createLayerIcon(overlayEnabled),
                component);
        feedTabbedPane.setToolTipTextAt(idx, overlayEnabled
                ? feed.getDisplayName() + " map overlay active"
                : feed.getDisplayName() + " map overlay muted");
    }

    /**
     * Updates countdowns, formatted timestamps, tab badge counts, and sync indicator for the current
     * simulation clock and timezone without performing network I/O.
     */
    public void refreshDisplay(long simEpochMillis, boolean realTimeMode, TimeZone displayTz) {
        this.lastSimEpochMillis = simEpochMillis;
        this.lastRealTimeMode = realTimeMode;
        this.lastDisplayTz = displayTz != null ? displayTz : TimeZone.getTimeZone("UTC");

        launchesTab.refreshDisplay(simEpochMillis, realTimeMode, this.lastDisplayTz);
        updateHeaderAndTabs();
    }

    @Override
    public void dispose() {
        this.disposed = true;
        service.removeListener(serviceListener);
        launchesTab.dispose();
        super.dispose();
    }

    public LaunchesAwarenessTab getLaunchesTab() {
        return launchesTab;
    }

    public JTabbedPane getFeedTabbedPane() {
        return feedTabbedPane;
    }

    public JButton getRefreshButton() {
        return refreshButton;
    }

    public JButton getSourcesButton() {
        return sourcesButton;
    }

    public JLabel getSyncStatusLabel() {
        return syncStatusLabel;
    }

    private void runOnEdt(Runnable task) {
        if (disposed) {
            return;
        }
        Runnable guarded = () -> {
            if (!disposed) {
                task.run();
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            guarded.run();
        } else {
            SwingUtilities.invokeLater(guarded);
        }
    }

    private void updateHeaderAndTabs() {
        boolean refreshing = service.isRefreshing();
        if (refreshButton.isEnabled() == refreshing) {
            refreshButton.setEnabled(!refreshing);
        }

        String statusText;
        Color statusColor;
        String statusTooltip;
        boolean updateTooltip = true;

        if (refreshing) {
            statusText = "Syncing...";
            statusColor = defaultForeground();
            statusTooltip = null;
            updateTooltip = false;
        } else if (service.isDataStale()) {
            long lastSync = service.getLastSuccessfulSyncMillis();
            statusText = lastSync > 0L
                    ? "Stale \u00B7 last synced " + LaunchDetailsInspectorPanel.formatDateTime(lastSync, lastDisplayTz)
                    : "Stale \u00B7 sync failed (no cached data)";
            statusColor = new Color(0xFB, 0x8C, 0x00);
            String err = service.getLastErrorMessage();
            statusTooltip = (err != null && !err.isBlank()) ? err : "Last refresh failed";
        } else if (service.getLastSuccessfulSyncMillis() > 0L) {
            statusText = "Synced " + LaunchDetailsInspectorPanel.formatDateTime(
                    service.getLastSuccessfulSyncMillis(), lastDisplayTz);
            statusColor = mutedForeground();
            statusTooltip = "Last successful sync";
        } else {
            statusText = "Not synced yet (click Refresh to fetch)";
            statusColor = mutedForeground();
            statusTooltip = null;
        }

        if (!Objects.equals(syncStatusLabel.getText(), statusText)) {
            syncStatusLabel.setText(statusText);
        }
        if (!Objects.equals(syncStatusLabel.getForeground(), statusColor)) {
            syncStatusLabel.setForeground(statusColor);
        }
        if (updateTooltip && !Objects.equals(syncStatusLabel.getToolTipText(), statusTooltip)) {
            syncStatusLabel.setToolTipText(statusTooltip);
        }

        for (int i = 0; i < feeds.size() && i < feedTabbedPane.getTabCount(); i++) {
            AwarenessFeed feed = feeds.get(i);
            String title = formatTabTitle(feed, countForFeed(feed));
            if (!Objects.equals(feedTabbedPane.getTitleAt(i), title)) {
                feedTabbedPane.setTitleAt(i, title);
            }
            boolean overlayEnabled = feed.isMapOverlayEnabled();
            if (i >= lastTabOverlayStates.size() || lastTabOverlayStates.get(i) != overlayEnabled) {
                if (i < lastTabOverlayStates.size()) {
                    lastTabOverlayStates.set(i, overlayEnabled);
                } else {
                    lastTabOverlayStates.add(overlayEnabled);
                }
                feedTabbedPane.setIconAt(i, createLayerIcon(overlayEnabled));
                feedTabbedPane.setToolTipTextAt(i, overlayEnabled
                        ? feed.getDisplayName() + " map overlay active"
                        : feed.getDisplayName() + " map overlay muted");
            }
        }
    }

    private int countForFeed(AwarenessFeed feed) {
        if (feed == service) {
            return service.getVisibleUpcomingLaunches(lastSimEpochMillis).size();
        }
        return feed.getActiveItemCount();
    }

    private static String formatTabTitle(AwarenessFeed feed, int count) {
        return feed.getDisplayName() + " (" + count + ")";
    }

    private static Icon createLayerIcon(boolean overlayActive) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    if (overlayActive) {
                        g2.setColor(new Color(0x2E, 0x7D, 0x32));
                        g2.fillOval(x + 1, y + 2, 8, 8);
                    } else {
                        g2.setStroke(new BasicStroke(1.3f));
                        g2.setColor(mutedForeground());
                        g2.drawOval(x + 1, y + 2, 8, 8);
                    }
                } finally {
                    g2.dispose();
                }
            }

            @Override
            public int getIconWidth() {
                return 10;
            }

            @Override
            public int getIconHeight() {
                return 12;
            }
        };
    }

    private static Icon loadIcon(String resourcePath) {
        URL url = LiveAwarenessInternalFrame.class.getResource(resourcePath);
        return url != null ? new ImageIcon(url) : null;
    }

    private static Color borderColor() {
        Color c = UIManager.getColor("Component.borderColor");
        return c != null ? c : new Color(128, 128, 128, 90);
    }

    private static Color defaultForeground() {
        Color c = UIManager.getColor("Label.foreground");
        return c != null ? c : Color.DARK_GRAY;
    }

    private static Color mutedForeground() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : new Color(128, 128, 128);
    }
}

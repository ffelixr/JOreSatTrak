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
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
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
import jsattrak.awareness.gnss.GnssAwarenessService;
import jsattrak.awareness.gnss.GnssSatelliteState;
import jsattrak.awareness.gnss.GnssStateListener;
import jsattrak.awareness.gnss.geometry.GnssDopGrid;
import jsattrak.awareness.gnss.geometry.GnssGeometryListener;
import jsattrak.awareness.gnss.geometry.GnssGeometryService;
import jsattrak.awareness.gnss.gui.GnssAwarenessTab;
import jsattrak.awareness.gnss.gui.GnssGeometryTab;
import jsattrak.objects.GroundStation;

/**
 * Unified MDI hub hosting Live Awareness feed tabs (starting with Launches), manual Refresh,
 * sync/stale status badge, and quick access to Live Data Sources configuration.
 */
public class LiveAwarenessInternalFrame extends JInternalFrame {

    private final LiveAwarenessService service;
    private final GnssAwarenessService gnssService;
    private final GnssGeometryService geometryService;
    private final JTabbedPane feedTabbedPane;
    private final LaunchesAwarenessTab launchesTab;
    private final GnssAwarenessTab gnssTab;
    private final GnssGeometryTab gnssGeometryTab;
    private final JLabel syncStatusLabel;
    private final JButton refreshButton;
    private final JButton sourcesButton;
    private final AwarenessStateListener serviceListener;
    private final GnssStateListener gnssListener;
    private final GnssGeometryListener geometryListener;
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
        this(service, null, onJumpToT0, onCenterMaps, onImportTles, onOpenSatBrowser, onOpenSources, null, null);
    }

    public LiveAwarenessInternalFrame(
            LiveAwarenessService service,
            GnssAwarenessService gnssService,
            Consumer<Long> onJumpToT0,
            BiConsumer<Double, Double> onCenterMaps,
            Consumer<LaunchRecord> onImportTles,
            Runnable onOpenSatBrowser,
            Runnable onOpenSources,
            Consumer<GnssSatelliteState> onAddGnssToObjectList,
            Predicate<GnssSatelliteState> isGnssInObjectList) {
        this(service, gnssService, null, null, onJumpToT0, onCenterMaps, onImportTles,
                onOpenSatBrowser, onOpenSources, onAddGnssToObjectList, isGnssInObjectList);
    }

    public LiveAwarenessInternalFrame(
            LiveAwarenessService service,
            GnssAwarenessService gnssService,
            GnssGeometryService geometryService,
            Supplier<Collection<GroundStation>> groundStationsSupplier,
            Consumer<Long> onJumpToT0,
            BiConsumer<Double, Double> onCenterMaps,
            Consumer<LaunchRecord> onImportTles,
            Runnable onOpenSatBrowser,
            Runnable onOpenSources,
            Consumer<GnssSatelliteState> onAddGnssToObjectList,
            Predicate<GnssSatelliteState> isGnssInObjectList) {
        super("Live Awareness", true, true, true, true);
        this.service = Objects.requireNonNull(service, "service");
        this.gnssService = gnssService;
        this.geometryService = geometryService;
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
        refreshButton.setToolTipText("Fetch latest data for the active feed");
        refreshButton.addActionListener(e -> {
            AwarenessFeed active = activeFeed();
            if ((active == gnssService || active == geometryService) && gnssService != null) {
                gnssService.refreshNowAsync();
            } else {
                service.refreshNowAsync();
            }
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
        if (gnssService != null) {
            gnssTab = new GnssAwarenessTab(
                    gnssService,
                    onJumpToT0,
                    onCenterMaps,
                    onAddGnssToObjectList,
                    isGnssInObjectList);
            addFeedTab(gnssService, gnssTab);
        } else {
            gnssTab = null;
        }
        if (geometryService != null) {
            gnssGeometryTab = new GnssGeometryTab(geometryService, groundStationsSupplier, onJumpToT0);
            addFeedTab(geometryService, gnssGeometryTab);
        } else {
            gnssGeometryTab = null;
        }
        feedTabbedPane.addChangeListener(e -> updateHeaderAndTabs());
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                updateHeaderAndTabs();
            }

            @Override
            public void componentHidden(ComponentEvent e) {
                if (geometryService != null) {
                    geometryService.setGeometryTabVisible(false);
                }
            }
        });
        root.add(feedTabbedPane, BorderLayout.CENTER);

        setContentPane(root);
        setSize(820, 580);
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
                    if (activeFeed() == service && message != null && !message.isBlank()) {
                        syncStatusLabel.setToolTipText(message);
                    }
                });
            }
        };
        service.addListener(serviceListener);

        if (gnssService != null) {
            this.gnssListener = new GnssStateListener() {
                @Override
                public void onGnssDataChanged() {
                    runOnEdt(LiveAwarenessInternalFrame.this::updateHeaderAndTabs);
                }

                @Override
                public void onGnssSelectionChanged(String selectedPrnId) {
                }

                @Override
                public void onGnssStatusMessage(String message, boolean isWarning) {
                    runOnEdt(() -> {
                        updateHeaderAndTabs();
                        if (activeFeed() == gnssService && message != null && !message.isBlank()) {
                            syncStatusLabel.setToolTipText(message);
                        }
                    });
                }

                @Override
                public void onOpenGnssUiRequested() {
                    runOnEdt(() -> selectFeedTab(GnssAwarenessService.FEED_ID));
                }
            };
            gnssService.addListener(gnssListener);
        } else {
            this.gnssListener = null;
        }

        if (geometryService != null) {
            this.geometryListener = new GnssGeometryListener() {
                @Override
                public void onGeometryDataChanged() {
                    runOnEdt(LiveAwarenessInternalFrame.this::updateHeaderAndTabs);
                }

                @Override
                public void onHeatmapUpdated(GnssDopGrid grid) {
                    runOnEdt(LiveAwarenessInternalFrame.this::updateHeaderAndTabs);
                }
            };
            geometryService.addListener(this.geometryListener);
        } else {
            this.geometryListener = null;
        }

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
        if (gnssTab != null) {
            gnssTab.refreshDisplay(simEpochMillis, realTimeMode, this.lastDisplayTz);
        }
        if (gnssGeometryTab != null) {
            gnssGeometryTab.refreshDisplay(simEpochMillis, realTimeMode, this.lastDisplayTz);
        }
        updateHeaderAndTabs();
    }

    @Override
    public void dispose() {
        this.disposed = true;
        service.removeListener(serviceListener);
        if (gnssService != null && gnssListener != null) {
            gnssService.removeListener(gnssListener);
        }
        if (geometryService != null && geometryListener != null) {
            geometryService.removeListener(geometryListener);
            geometryService.setGeometryTabVisible(false);
        }
        launchesTab.dispose();
        if (gnssTab != null) {
            gnssTab.dispose();
        }
        if (gnssGeometryTab != null) {
            gnssGeometryTab.dispose();
        }
        super.dispose();
    }

    public LaunchesAwarenessTab getLaunchesTab() {
        return launchesTab;
    }

    public GnssAwarenessTab getGnssTab() {
        return gnssTab;
    }

    public GnssGeometryTab getGnssGeometryTab() {
        return gnssGeometryTab;
    }

    public void selectFeedTab(String feedId) {
        if (feedId == null || feedId.isBlank()) {
            return;
        }
        for (int i = 0; i < feeds.size() && i < feedTabbedPane.getTabCount(); i++) {
            if (feedId.equalsIgnoreCase(feeds.get(i).getFeedId())) {
                feedTabbedPane.setSelectedIndex(i);
                updateHeaderAndTabs();
                return;
            }
        }
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

    private AwarenessFeed activeFeed() {
        int idx = feedTabbedPane != null ? feedTabbedPane.getSelectedIndex() : -1;
        if (idx >= 0 && idx < feeds.size()) {
            return feeds.get(idx);
        }
        return service;
    }

    private void updateHeaderAndTabs() {
        AwarenessFeed active = activeFeed();
        if (geometryService != null) {
            geometryService.setGeometryTabVisible(active == geometryService && !disposed);
        }
        boolean isGnssActive = (active == gnssService || active == geometryService) && gnssService != null;
        boolean refreshing = isGnssActive ? gnssService.isRefreshing() : service.isRefreshing();
        boolean stale = isGnssActive ? gnssService.isDataStale() : service.isDataStale();
        long lastSync = isGnssActive ? gnssService.getLastSuccessfulSyncMillis() : service.getLastSuccessfulSyncMillis();
        String lastErr = isGnssActive ? gnssService.getLastErrorMessage() : service.getLastErrorMessage();

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
        } else if (stale) {
            statusText = lastSync > 0L
                    ? "Stale \u00B7 last synced " + LaunchDetailsInspectorPanel.formatDateTime(lastSync, lastDisplayTz)
                    : "Stale \u00B7 sync failed (no cached data)";
            statusColor = new Color(0xFB, 0x8C, 0x00);
            statusTooltip = (lastErr != null && !lastErr.isBlank()) ? lastErr : "Last refresh failed";
        } else if (lastSync > 0L) {
            statusText = "Synced " + LaunchDetailsInspectorPanel.formatDateTime(lastSync, lastDisplayTz);
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
        if (feed == gnssService && gnssService != null) {
            return gnssTab != null
                    ? gnssTab.getVisibleSatelliteCount()
                    : gnssService.getVisibleSatelliteStates(lastSimEpochMillis).size();
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

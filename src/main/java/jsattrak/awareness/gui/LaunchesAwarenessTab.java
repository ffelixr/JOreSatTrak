package jsattrak.awareness.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumnModel;
import jsattrak.awareness.AwarenessStateListener;
import jsattrak.awareness.LaunchRecord;
import jsattrak.awareness.LaunchTimeEvaluator;
import jsattrak.awareness.LaunchUrgency;
import jsattrak.awareness.LiveAwarenessService;
import jsattrak.awareness.map.LaunchOverlay2DRenderer;
import jsattrak.awareness.tle.LaunchTleStatus;
import jsattrak.awareness.tle.PayloadTleMatch;
import jsattrak.awareness.tle.PayloadTleMatcher;

/**
 * Launches feed tab inside {@link LiveAwarenessInternalFrame}, providing Upcoming/Recent switching,
 * 1d/3d/7d horizon controls, quick search, master map toggle, simulation clock badge, linked
 * launch table, and collapsible mission details inspector.
 */
public class LaunchesAwarenessTab extends JPanel {

    private final LiveAwarenessService service;
    private final BiConsumer<Double, Double> onCenterMaps;

    private final JToggleButton upcomingButton;
    private final JToggleButton recentButton;
    private final JPanel horizonPanel;
    private final JToggleButton horizon1dButton;
    private final JToggleButton horizon3dButton;
    private final JToggleButton horizon7dButton;
    private final JCheckBox showOnMapsCheckBox;
    private final JLabel simClockPill;
    private final JTextField searchField;

    private final LaunchesTableModel tableModel;
    private final JTable launchesTable;
    private final LaunchDetailsInspectorPanel inspectorPanel;
    private final JToggleButton inspectorToggleButton;
    private final JSplitPane splitPane;
    private final JPanel inspectorContainer;

    private final AwarenessStateListener serviceListener;

    private boolean upcomingMode = true;
    private long lastSimEpochMillis = System.currentTimeMillis();
    private boolean lastRealTimeMode = true;
    private TimeZone lastDisplayTz = TimeZone.getTimeZone("UTC");
    private boolean updatingControls;
    private boolean updatingTableSelection;
    private boolean inspectorExpanded = true;
    private int savedDividerLocation = 250;

    public LaunchesAwarenessTab(
            LiveAwarenessService service,
            Consumer<Long> onJumpToT0,
            BiConsumer<Double, Double> onCenterMaps,
            Consumer<LaunchRecord> onImportTles) {
        this(service, onJumpToT0, onCenterMaps, onImportTles, null);
    }

    public LaunchesAwarenessTab(
            LiveAwarenessService service,
            Consumer<Long> onJumpToT0,
            BiConsumer<Double, Double> onCenterMaps,
            Consumer<LaunchRecord> onImportTles,
            Runnable onOpenSatBrowser) {
        super(new BorderLayout(0, 6));
        this.service = Objects.requireNonNull(service, "service");
        this.onCenterMaps = onCenterMaps;
        setBorder(new EmptyBorder(6, 8, 8, 8));

        // Control strip
        JPanel controlsWrapper = new JPanel();
        controlsWrapper.setLayout(new BoxLayout(controlsWrapper, BoxLayout.Y_AXIS));
        controlsWrapper.setOpaque(false);

        JPanel topRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        topRow.setOpaque(false);
        topRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        ButtonGroup modeGroup = new ButtonGroup();
        upcomingButton = new JToggleButton("Upcoming", true);
        recentButton = new JToggleButton("Recent (72h)");
        modeGroup.add(upcomingButton);
        modeGroup.add(recentButton);

        horizonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        horizonPanel.setOpaque(false);
        ButtonGroup horizonGroup = new ButtonGroup();
        horizon1dButton = new JToggleButton("1d");
        horizon3dButton = new JToggleButton("3d");
        horizon7dButton = new JToggleButton("7d");
        horizonGroup.add(horizon1dButton);
        horizonGroup.add(horizon3dButton);
        horizonGroup.add(horizon7dButton);
        horizonPanel.add(horizon1dButton);
        horizonPanel.add(horizon3dButton);
        horizonPanel.add(horizon7dButton);

        showOnMapsCheckBox = new JCheckBox("Show on maps", service.isGlobalMapOverlayEnabled());
        showOnMapsCheckBox.setToolTipText("Global master toggle for launch overlays across all 2D/3D maps");

        simClockPill = createSimClockPill();
        simClockPill.setVisible(false);

        topRow.add(upcomingButton);
        topRow.add(horizonPanel);
        topRow.add(recentButton);
        topRow.add(Box.createHorizontalStrut(8));
        topRow.add(showOnMapsCheckBox);
        topRow.add(Box.createHorizontalStrut(6));
        topRow.add(simClockPill);

        JPanel searchRow = new JPanel(new BorderLayout(6, 0));
        searchRow.setOpaque(false);
        searchRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        searchRow.setBorder(new EmptyBorder(2, 4, 2, 4));
        JLabel searchLabel = new JLabel("Filter:");
        searchField = new JTextField();
        searchField.putClientProperty("JTextField.placeholderText", "Search mission, vehicle, provider, pad...");
        searchField.putClientProperty("JTextField.showClearButton", Boolean.TRUE);
        searchRow.add(searchLabel, BorderLayout.WEST);
        searchRow.add(searchField, BorderLayout.CENTER);

        controlsWrapper.add(topRow);
        controlsWrapper.add(searchRow);
        add(controlsWrapper, BorderLayout.NORTH);

        // Table
        tableModel = new LaunchesTableModel();
        launchesTable = new JTable(tableModel);
        launchesTable.setFillsViewportHeight(true);
        launchesTable.setRowHeight(24);
        launchesTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        launchesTable.getTableHeader().setReorderingAllowed(false);
        configureTableColumns(launchesTable.getColumnModel());

        JScrollPane tableScroll = new JScrollPane(launchesTable);
        tableScroll.setMinimumSize(new Dimension(400, 130));

        // Collapsible Details Inspector
        inspectorPanel = new LaunchDetailsInspectorPanel(
                onJumpToT0,
                onCenterMaps,
                onImportTles,
                service::refreshNowAsync,
                onOpenSatBrowser);

        inspectorContainer = new JPanel(new BorderLayout());
        inspectorContainer.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, borderLineColor()));

        JPanel inspectorHeader = new JPanel(new BorderLayout());
        inspectorHeader.setOpaque(false);
        inspectorHeader.setBorder(new EmptyBorder(2, 4, 2, 4));
        inspectorToggleButton = new JToggleButton("\u25BC Mission Details", true);
        inspectorToggleButton.setBorderPainted(false);
        inspectorToggleButton.setContentAreaFilled(false);
        inspectorToggleButton.setFocusPainted(false);
        inspectorToggleButton.setHorizontalAlignment(SwingConstants.LEFT);
        inspectorToggleButton.setFont(inspectorToggleButton.getFont().deriveFont(Font.BOLD));
        inspectorHeader.add(inspectorToggleButton, BorderLayout.WEST);

        inspectorContainer.add(inspectorHeader, BorderLayout.NORTH);
        inspectorContainer.add(inspectorPanel, BorderLayout.CENTER);

        splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, inspectorContainer);
        splitPane.setResizeWeight(0.58);
        splitPane.setDividerLocation(savedDividerLocation);
        splitPane.setContinuousLayout(true);
        add(splitPane, BorderLayout.CENTER);

        // Wire control listeners
        upcomingButton.addActionListener(e -> setUpcomingMode(true));
        recentButton.addActionListener(e -> setUpcomingMode(false));

        horizon1dButton.addActionListener(e -> selectHorizonFromUi(1));
        horizon3dButton.addActionListener(e -> selectHorizonFromUi(3));
        horizon7dButton.addActionListener(e -> selectHorizonFromUi(7));

        showOnMapsCheckBox.addActionListener(e -> {
            if (!updatingControls) {
                service.setGlobalMapOverlayEnabled(showOnMapsCheckBox.isSelected());
            }
        });

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                rebuildTable();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                rebuildTable();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                rebuildTable();
            }
        });

        launchesTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || updatingTableSelection) {
                return;
            }
            int row = launchesTable.getSelectedRow();
            LaunchRowEntry entry = tableModel.getRowAt(row);
            if (entry != null) {
                updatingTableSelection = true;
                try {
                    service.selectLaunch(entry.launch().getId());
                } finally {
                    updatingTableSelection = false;
                }
                inspectorPanel.setLaunch(entry.launch(), entry.tleStatus(), entry.tleMatchCount(), lastDisplayTz);
            } else {
                inspectorPanel.setLaunch(null, LaunchTleStatus.NO_ORBITAL_PAYLOAD, 0, lastDisplayTz);
            }
        });

        launchesTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && LaunchesAwarenessTab.this.onCenterMaps != null) {
                    int row = launchesTable.rowAtPoint(e.getPoint());
                    LaunchRowEntry entry = tableModel.getRowAt(row);
                    if (entry != null) {
                        LaunchRecord launch = entry.launch();
                        if (Double.isFinite(launch.getLatDeg()) && Double.isFinite(launch.getLonDeg())) {
                            LaunchesAwarenessTab.this.onCenterMaps.accept(launch.getLatDeg(), launch.getLonDeg());
                        }
                    }
                }
            }
        });

        inspectorToggleButton.addActionListener(e -> setInspectorExpanded(inspectorToggleButton.isSelected()));

        this.serviceListener = new AwarenessStateListener() {
            @Override
            public void onAwarenessDataChanged() {
                syncControlsFromService();
                rebuildTable();
            }

            @Override
            public void onSelectionChanged(String selectedLaunchId) {
                if (!updatingTableSelection) {
                    syncSelectionFromService(selectedLaunchId);
                }
            }

            @Override
            public void onStatusMessage(String message, boolean isWarning) {
            }
        };
        service.addListener(serviceListener);

        syncControlsFromService();
        rebuildTable();
    }

    /**
     * Updates countdowns, urgency dots, simulation clock pill visibility, and formatted windows.
     */
    public void refreshDisplay(long simEpochMillis, boolean realTimeMode, TimeZone displayTz) {
        this.lastSimEpochMillis = simEpochMillis;
        this.lastRealTimeMode = realTimeMode;
        this.lastDisplayTz = displayTz != null ? displayTz : TimeZone.getTimeZone("UTC");

        simClockPill.setVisible(!realTimeMode);
        syncControlsFromService();
        rebuildTable();
    }

    /**
     * Unregisters listeners from {@link LiveAwarenessService}.
     */
    public void dispose() {
        service.removeListener(serviceListener);
    }

    /**
     * Filters launches case-insensitively against mission, vehicle, provider, spaceport, pad, country,
     * target orbit, and COSPAR ID.
     */
    public static List<LaunchRecord> applySearchFilter(List<LaunchRecord> launches, String query) {
        if (launches == null || launches.isEmpty()) {
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return List.copyOf(launches);
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<LaunchRecord> filtered = new ArrayList<>();
        for (LaunchRecord launch : launches) {
            if (launch != null && matchesQuery(launch, needle)) {
                filtered.add(launch);
            }
        }
        return List.copyOf(filtered);
    }

    public boolean isUpcomingMode() {
        return upcomingMode;
    }

    public void setUpcomingMode(boolean upcoming) {
        if (this.upcomingMode != upcoming) {
            this.upcomingMode = upcoming;
            upcomingButton.setSelected(upcoming);
            recentButton.setSelected(!upcoming);
            horizonPanel.setVisible(upcoming);
            updateDynamicColumnHeaders();
            rebuildTable();
        }
    }

    public boolean isInspectorExpanded() {
        return inspectorExpanded;
    }

    public void setInspectorExpanded(boolean expanded) {
        if (this.inspectorExpanded == expanded) {
            return;
        }
        this.inspectorExpanded = expanded;
        inspectorToggleButton.setSelected(expanded);
        inspectorToggleButton.setText(expanded ? "\u25BC Mission Details" : "\u25B6 Mission Details");
        if (expanded) {
            inspectorPanel.setVisible(true);
            splitPane.setResizeWeight(0.58);
            splitPane.setDividerSize(UIManager.getInt("SplitPane.dividerSize") > 0
                    ? UIManager.getInt("SplitPane.dividerSize")
                    : 6);
            splitPane.setDividerLocation(savedDividerLocation);
        } else {
            int currentLoc = splitPane.getDividerLocation();
            if (currentLoc > 40) {
                savedDividerLocation = currentLoc;
            }
            inspectorPanel.setVisible(false);
            splitPane.setResizeWeight(1.0);
            splitPane.setDividerSize(0);
            int collapsedHeight = inspectorContainer.getPreferredSize().height;
            int totalHeight = splitPane.getHeight();
            if (totalHeight > collapsedHeight) {
                splitPane.setDividerLocation(totalHeight - collapsedHeight);
            }
        }
        splitPane.revalidate();
        splitPane.repaint();
    }

    public JTable getLaunchesTable() {
        return launchesTable;
    }

    public LaunchDetailsInspectorPanel getInspectorPanel() {
        return inspectorPanel;
    }

    public JLabel getSimClockPill() {
        return simClockPill;
    }

    public JCheckBox getShowOnMapsCheckBox() {
        return showOnMapsCheckBox;
    }

    public JToggleButton getUpcomingButton() {
        return upcomingButton;
    }

    public JToggleButton getRecentButton() {
        return recentButton;
    }

    public JToggleButton getHorizon1dButton() {
        return horizon1dButton;
    }

    public JToggleButton getHorizon3dButton() {
        return horizon3dButton;
    }

    public JToggleButton getHorizon7dButton() {
        return horizon7dButton;
    }

    public JTextField getSearchField() {
        return searchField;
    }

    public JToggleButton getInspectorToggleButton() {
        return inspectorToggleButton;
    }

    private void selectHorizonFromUi(int days) {
        if (!updatingControls) {
            service.setSelectedHorizonDays(days);
        }
    }

    private void syncControlsFromService() {
        updatingControls = true;
        try {
            showOnMapsCheckBox.setSelected(service.isGlobalMapOverlayEnabled());
            int days = service.getSelectedHorizonDays();
            horizon1dButton.setSelected(days == 1);
            horizon3dButton.setSelected(days == 3);
            horizon7dButton.setSelected(days == 7);
            horizonPanel.setVisible(upcomingMode);
            updateDynamicColumnHeaders();
        } finally {
            updatingControls = false;
        }
    }

    private void updateDynamicColumnHeaders() {
        TableColumnModel cm = launchesTable.getColumnModel();
        if (cm.getColumnCount() >= 5) {
            cm.getColumn(1).setHeaderValue(upcomingMode ? "Countdown" : "Since Liftoff");
            cm.getColumn(4).setHeaderValue(upcomingMode ? "Status" : "Outcome");
            launchesTable.getTableHeader().repaint();
        }
    }

    private void rebuildTable() {
        long refEpoch = upcomingMode ? lastSimEpochMillis : System.currentTimeMillis();
        List<LaunchRecord> baseList = upcomingMode
                ? service.getVisibleUpcomingLaunches(lastSimEpochMillis)
                : service.getVisibleRecentLaunches(refEpoch);
        List<LaunchRecord> filtered = applySearchFilter(baseList, searchField.getText());

        List<LaunchRowEntry> entries = new ArrayList<>(filtered.size());
        for (LaunchRecord launch : filtered) {
            LaunchUrgency urgency = upcomingMode
                    ? LaunchTimeEvaluator.evaluateUrgency(launch, lastSimEpochMillis)
                    : LaunchUrgency.EXPIRED;
            String countdown = LaunchTimeEvaluator.formatCountdown(launch, refEpoch);
            List<PayloadTleMatch> matches = service.getMatchedTles(launch, Set.of(), Set.of());
            LaunchTleStatus tleStatus = PayloadTleMatcher.evaluateStatus(launch, matches);
            entries.add(new LaunchRowEntry(launch, urgency, countdown, tleStatus, matches.size()));
        }

        updatingTableSelection = true;
        try {
            tableModel.setRows(entries, upcomingMode);
            restoreSelection(service.getSelectedLaunchId());
        } finally {
            updatingTableSelection = false;
        }
    }

    private void syncSelectionFromService(String selectedLaunchId) {
        if (selectedLaunchId == null || selectedLaunchId.isBlank()) {
            updatingTableSelection = true;
            try {
                launchesTable.clearSelection();
                inspectorPanel.setLaunch(null, LaunchTleStatus.NO_ORBITAL_PAYLOAD, 0, lastDisplayTz);
            } finally {
                updatingTableSelection = false;
            }
            return;
        }

        int row = tableModel.findRowById(selectedLaunchId);
        if (row < 0) {
            // If selected launch belongs to the other mode (e.g., Recent vs Upcoming), switch mode once
            boolean inRecent = service.getVisibleRecentLaunches(System.currentTimeMillis()).stream()
                    .anyMatch(r -> selectedLaunchId.equals(r.getId()));
            boolean inUpcoming = service.getVisibleUpcomingLaunches(lastSimEpochMillis).stream()
                    .anyMatch(r -> selectedLaunchId.equals(r.getId()));
            if (upcomingMode && inRecent) {
                setUpcomingMode(false);
                row = tableModel.findRowById(selectedLaunchId);
            } else if (!upcomingMode && inUpcoming) {
                setUpcomingMode(true);
                row = tableModel.findRowById(selectedLaunchId);
            }
        }

        updatingTableSelection = true;
        try {
            restoreSelection(selectedLaunchId);
        } finally {
            updatingTableSelection = false;
        }
    }

    private void restoreSelection(String selectedLaunchId) {
        int row = tableModel.findRowById(selectedLaunchId);
        if (row >= 0) {
            launchesTable.setRowSelectionInterval(row, row);
            Rectangle cellRect = launchesTable.getCellRect(row, 0, true);
            launchesTable.scrollRectToVisible(cellRect);
            LaunchRowEntry entry = tableModel.getRowAt(row);
            inspectorPanel.setLaunch(entry.launch(), entry.tleStatus(), entry.tleMatchCount(), lastDisplayTz);
        } else {
            launchesTable.clearSelection();
            LaunchRecord selected = service.getSelectedLaunch();
            if (selected != null) {
                List<PayloadTleMatch> matches = service.getMatchedTles(selected, Set.of(), Set.of());
                LaunchTleStatus status = PayloadTleMatcher.evaluateStatus(selected, matches);
                inspectorPanel.setLaunch(selected, status, matches.size(), lastDisplayTz);
            } else {
                inspectorPanel.setLaunch(null, LaunchTleStatus.NO_ORBITAL_PAYLOAD, 0, lastDisplayTz);
            }
        }
    }

    private static boolean matchesQuery(LaunchRecord launch, String needle) {
        return containsIgnoreCase(launch.getMissionName(), needle)
                || containsIgnoreCase(launch.getVehicle(), needle)
                || containsIgnoreCase(launch.getProvider(), needle)
                || containsIgnoreCase(launch.getSpaceportName(), needle)
                || containsIgnoreCase(launch.getPadName(), needle)
                || containsIgnoreCase(launch.getCountry(), needle)
                || containsIgnoreCase(launch.getTargetOrbit(), needle)
                || containsIgnoreCase(launch.getCosparId(), needle);
    }

    private static boolean containsIgnoreCase(String field, String needle) {
        return field != null && !field.isEmpty() && field.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static JLabel createSimClockPill() {
        JLabel pill = new JLabel("Relative to Sim Clock") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(new Color(0xFB, 0x8C, 0x00, 38));
                    g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                    g2.setColor(new Color(0xFB, 0x8C, 0x00, 175));
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                } finally {
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        pill.setOpaque(false);
        pill.setBorder(new EmptyBorder(2, 8, 2, 8));
        pill.setFont(pill.getFont().deriveFont(Font.BOLD, Math.max(10f, pill.getFont().getSize2D() - 1f)));
        pill.setToolTipText("Simulation clock is not in Real-Time mode; countdowns & urgency reflect Sim Clock");
        return pill;
    }

    private static void configureTableColumns(TableColumnModel columns) {
        if (columns.getColumnCount() < 6) {
            return;
        }
        columns.getColumn(0).setPreferredWidth(28);
        columns.getColumn(0).setMaxWidth(34);
        columns.getColumn(0).setCellRenderer(new UrgencyDotRenderer());

        columns.getColumn(1).setPreferredWidth(92);
        columns.getColumn(1).setMaxWidth(120);

        columns.getColumn(2).setPreferredWidth(210);
        columns.getColumn(3).setPreferredWidth(165);

        columns.getColumn(4).setPreferredWidth(85);
        columns.getColumn(4).setMaxWidth(115);
        columns.getColumn(4).setCellRenderer(new StatusBadgeRenderer());

        columns.getColumn(5).setPreferredWidth(125);
        columns.getColumn(5).setMaxWidth(160);
        columns.getColumn(5).setCellRenderer(new TleBadgeRenderer());
    }

    private static Color borderLineColor() {
        Color c = UIManager.getColor("Component.borderColor");
        return c != null ? c : new Color(128, 128, 128, 90);
    }

    private record LaunchRowEntry(
            LaunchRecord launch,
            LaunchUrgency urgency,
            String countdown,
            LaunchTleStatus tleStatus,
            int tleMatchCount) {
    }

    private static final class LaunchesTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = {
                "", "Countdown", "Mission / Vehicle", "Spaceport / Pad", "Status", "TLE"
        };

        private List<LaunchRowEntry> rows = List.of();
        private boolean upcomingMode = true;

        void setRows(List<LaunchRowEntry> rows, boolean upcomingMode) {
            this.rows = rows != null ? rows : List.of();
            this.upcomingMode = upcomingMode;
            fireTableDataChanged();
        }

        LaunchRowEntry getRowAt(int row) {
            return (row >= 0 && row < rows.size()) ? rows.get(row) : null;
        }

        int findRowById(String launchId) {
            if (launchId == null || launchId.isBlank()) {
                return -1;
            }
            for (int i = 0; i < rows.size(); i++) {
                if (launchId.equals(rows.get(i).launch().getId())) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            LaunchRowEntry entry = rows.get(rowIndex);
            LaunchRecord r = entry.launch();
            return switch (columnIndex) {
                case 0 -> entry.urgency();
                case 1 -> entry.countdown();
                case 2 -> formatMissionVehicle(r);
                case 3 -> formatSitePad(r);
                case 4 -> upcomingMode
                        ? nonBlankOr(r.getStatus(), "\u2014")
                        : nonBlankOr(r.getOutcome(), nonBlankOr(r.getStatus(), "\u2014"));
                case 5 -> formatTleBadgeText(entry, upcomingMode);
                default -> "";
            };
        }

        private static String formatMissionVehicle(LaunchRecord r) {
            String mission = nonBlankOr(r.getMissionName(), "Scheduled Launch");
            String vehicle = nonBlankOr(r.getVehicle(), "");
            return vehicle.isEmpty() ? mission : mission + " \u00B7 " + vehicle;
        }

        private static String formatSitePad(LaunchRecord r) {
            String port = nonBlankOr(r.getSpaceportName(), "");
            String pad = nonBlankOr(r.getPadName(), "");
            if (!port.isEmpty() && !pad.isEmpty() && !port.equalsIgnoreCase(pad)) {
                return port + " \u2014 " + pad;
            }
            return !port.isEmpty() ? port : (!pad.isEmpty() ? pad : "\u2014");
        }

        private static String formatTleBadgeText(LaunchRowEntry entry, boolean upcomingMode) {
            LaunchTleStatus status = entry.tleStatus();
            if (status == null) {
                return "";
            }
            if (upcomingMode) {
                return switch (status) {
                    case SUPPLEMENTAL_READY -> "Pre-launch TLE";
                    case TLES_READY -> "TLEs Ready (" + entry.tleMatchCount() + ")";
                    case AWAITING_TLE, NO_ORBITAL_PAYLOAD -> "";
                };
            }
            return switch (status) {
                case TLES_READY -> "TLEs Ready (" + entry.tleMatchCount() + ")";
                case SUPPLEMENTAL_READY -> "Supplemental TLE";
                case AWAITING_TLE -> "Awaiting TLE";
                case NO_ORBITAL_PAYLOAD -> "No Orbital Payload";
            };
        }
    }

    private static final class UrgencyDotRenderer extends DefaultTableCellRenderer {
        private LaunchUrgency currentUrgency;

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column);
            this.currentUrgency = (value instanceof LaunchUrgency u) ? u : LaunchUrgency.EXPIRED;
            setToolTipText(tooltipForUrgency(currentUrgency));
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int d = 10;
                int x = (getWidth() - d) / 2;
                int y = (getHeight() - d) / 2;
                g2.setColor(LaunchOverlay2DRenderer.colorForUrgency(currentUrgency));
                g2.fillOval(x, y, d, d);
            } finally {
                g2.dispose();
            }
        }

        private static String tooltipForUrgency(LaunchUrgency u) {
            return switch (u) {
                case IMMINENT_OR_IN_WINDOW -> "Imminent (< 24h) or In Window";
                case SOON_1_TO_3D -> "Soon (1\u20133 days)";
                case UPCOMING_3_TO_7D -> "Upcoming (3\u20137 days)";
                case EXPIRED -> "Past / Expired";
            };
        }
    }

    private static class PillBadgeRenderer extends DefaultTableCellRenderer {
        private Color pillAccent;
        private boolean hasPill;

        PillBadgeRenderer() {
            setOpaque(false);
        }

        void setPillAccent(Color accent, boolean visible) {
            this.pillAccent = accent;
            this.hasPill = visible && accent != null;
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setHorizontalAlignment(SwingConstants.CENTER);
            setBorder(new EmptyBorder(0, 6, 0, 6));
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color bg = getBackground();
                if (bg != null) {
                    g2.setColor(bg);
                    g2.fillRect(0, 0, getWidth(), getHeight());
                }

                if (hasPill && !getText().isEmpty()) {
                    Insets in = new Insets(3, 4, 3, 4);
                    int w = Math.max(0, getWidth() - in.left - in.right);
                    int h = Math.max(0, getHeight() - in.top - in.bottom);
                    g2.setColor(new Color(pillAccent.getRed(), pillAccent.getGreen(), pillAccent.getBlue(), 38));
                    g2.fillRoundRect(in.left, in.top, w, h, 10, 10);
                    g2.setColor(new Color(pillAccent.getRed(), pillAccent.getGreen(), pillAccent.getBlue(), 150));
                    g2.drawRoundRect(in.left, in.top, w - 1, h - 1, 10, 10);
                }
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    private static final class StatusBadgeRenderer extends PillBadgeRenderer {
        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            String text = value == null ? "" : value.toString().trim();
            String lower = text.toLowerCase(Locale.ROOT);
            Color accent = null;
            if (lower.equals("go") || lower.contains("success")) {
                accent = new Color(0x2E, 0x7D, 0x32);
            } else if (lower.equals("tbc") || lower.equals("hold") || lower.contains("partial")) {
                accent = new Color(0xFB, 0x8C, 0x00);
            } else if (lower.contains("fail") || lower.contains("cancel")) {
                accent = new Color(0xE5, 0x39, 0x35);
            } else if (!text.isEmpty() && !text.equals("\u2014")) {
                accent = new Color(0x78, 0x90, 0x9C);
            }
            setPillAccent(accent, accent != null && !isSelected);
            return super.getTableCellRendererComponent(table, text, isSelected, hasFocus, row, column);
        }
    }

    private static final class TleBadgeRenderer extends PillBadgeRenderer {
        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            String text = value == null ? "" : value.toString().trim();
            Color accent = null;
            if (text.startsWith("TLEs Ready")) {
                accent = new Color(0x2E, 0x7D, 0x32);
            } else if (text.contains("Supplemental") || text.contains("Pre-launch")) {
                accent = new Color(0x1E, 0x88, 0xE5);
            } else if (text.equals("Awaiting TLE")) {
                accent = new Color(0xFB, 0x8C, 0x00);
            } else if (text.equals("No Orbital Payload")) {
                accent = new Color(0x78, 0x90, 0x9C);
            }
            setPillAccent(accent, accent != null && !isSelected);
            return super.getTableCellRendererComponent(table, text, isSelected, hasFocus, row, column);
        }
    }

    private static String nonBlankOr(String candidate, String fallback) {
        return (candidate != null && !candidate.isBlank()) ? candidate.trim() : fallback;
    }
}

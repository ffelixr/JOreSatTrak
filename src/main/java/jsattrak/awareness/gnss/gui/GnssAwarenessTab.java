package jsattrak.awareness.gnss.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import jsattrak.awareness.gnss.GnssAwarenessService;
import jsattrak.awareness.gnss.GnssCatalogEntry;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.GnssDisplayStatus;
import jsattrak.awareness.gnss.GnssOutageNotice;
import jsattrak.awareness.gnss.GnssSatelliteState;
import jsattrak.awareness.gnss.GnssSourceHealth;
import jsattrak.awareness.gnss.GnssStateListener;
import jsattrak.awareness.gnss.GnssStatusResult;
import jsattrak.awareness.gnss.map.GnssOverlay2DRenderer;

/**
 * GNSS Constellation Status &amp; Satellite Availability tab inside {@code LiveAwarenessInternalFrame},
 * featuring per-constellation stacked status bars, filter chips, source-health indicators, sorted
 * satellite table, and the {@link GnssDetailsInspectorPanel}.
 */
public class GnssAwarenessTab extends JPanel implements GnssStateListener {

    private final GnssAwarenessService service;

    private final Map<GnssConstellation, JCheckBox> constellationCheckBoxes = new EnumMap<>(GnssConstellation.class);
    private final Map<GnssConstellation, StackedBarIndicator> constellationBars = new EnumMap<>(GnssConstellation.class);
    private final Map<GnssConstellation, JLabel> constellationCountLabels = new EnumMap<>(GnssConstellation.class);
    private final JCheckBox sbasPlaceholderCheckBox;

    private final Map<GnssDisplayStatus, JCheckBox> statusFilterCheckBoxes = new EnumMap<>(GnssDisplayStatus.class);
    private final JTextField searchField;
    private final JCheckBox showOnMapsCheckBox;
    private final JCheckBox showOrbitRingsCheckBox;
    private final JButton manualListButton;

    private final JPanel sourceHealthPanel;
    private final JLabel manualWarningLabel;
    private final JLabel simClockPill;
    private final JLabel syncDivergenceLabel;

    private final GnssTableModel tableModel;
    private final JTable satelliteTable;
    private final JScrollPane tableScrollPane;
    private final GnssDetailsInspectorPanel inspectorPanel;

    private volatile boolean disposed;
    private boolean updatingControlsFromService;
    private boolean syncingTableSelection;
    private long lastSimEpochMillis = System.currentTimeMillis();
    private boolean lastRealTimeMode = true;
    private TimeZone lastDisplayTz = TimeZone.getTimeZone("UTC");
    private List<GnssSourceHealth> lastRenderedHealth = List.of();
    private int lastVisibleCount;

    public GnssAwarenessTab(
            GnssAwarenessService service,
            Consumer<Long> onJumpToEpoch,
            BiConsumer<Double, Double> onCenterMaps,
            Consumer<GnssSatelliteState> onAddToObjectList,
            Predicate<GnssSatelliteState> isInObjectList) {
        super(new BorderLayout(6, 6));
        this.service = Objects.requireNonNull(service, "service");
        setBorder(new EmptyBorder(6, 6, 6, 6));

        // Top panel: Constellation summary strip + filter bar + health/clock row
        JPanel topStack = new JPanel();
        topStack.setLayout(new BoxLayout(topStack, BoxLayout.Y_AXIS));
        topStack.setOpaque(false);

        // 1. Constellation strip with stacked status bars + SBAS placeholder
        JPanel constellationStrip = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        constellationStrip.setBorder(BorderFactory.createTitledBorder("Constellations"));
        constellationStrip.setAlignmentX(Component.LEFT_ALIGNMENT);

        for (GnssConstellation c : GnssConstellation.layer1ActiveValues()) {
            JPanel card = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            card.setOpaque(false);

            JCheckBox cb = new JCheckBox(c.getDisplayName(), service.isConstellationEnabled(c));
            cb.setIconTextGap(4);
            cb.addActionListener(e -> {
                if (!updatingControlsFromService) {
                    service.setConstellationEnabled(c, cb.isSelected());
                }
            });
            JLabel shapeLabel = new JLabel(new SmallShapeIcon(c));
            StackedBarIndicator bar = new StackedBarIndicator();
            JLabel countsLabel = new JLabel("0");
            countsLabel.setFont(countsLabel.getFont().deriveFont(Math.max(10f, countsLabel.getFont().getSize2D() - 1f)));
            countsLabel.setForeground(mutedForeground());

            constellationCheckBoxes.put(c, cb);
            constellationBars.put(c, bar);
            constellationCountLabels.put(c, countsLabel);

            card.add(cb);
            card.add(shapeLabel);
            card.add(bar);
            card.add(countsLabel);
            constellationStrip.add(card);
        }

        // Disabled SBAS placeholder row
        sbasPlaceholderCheckBox = new JCheckBox("SBAS (EGNOS/WAAS/MSAS/GAGAN) \u2014 coming later", false);
        sbasPlaceholderCheckBox.setEnabled(false);
        sbasPlaceholderCheckBox.setToolTipText("Regional SBAS augmentation monitoring planned for a future release");
        constellationStrip.add(sbasPlaceholderCheckBox);
        topStack.add(constellationStrip);

        // 2. Status filter chips + search + map toggles + manual override editor button
        JPanel filterRow = new JPanel(new BorderLayout(8, 4));
        filterRow.setOpaque(false);
        filterRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel leftFilters = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        leftFilters.setOpaque(false);
        for (GnssDisplayStatus st : GnssDisplayStatus.values()) {
            JCheckBox cb = new JCheckBox(st.getDisplayLabel(), service.isStatusFilterEnabled(st));
            cb.setForeground(GnssOverlay2DRenderer.colorForStatus(st));
            cb.addActionListener(e -> {
                if (!updatingControlsFromService) {
                    service.setStatusFilterEnabled(st, cb.isSelected());
                }
            });
            statusFilterCheckBoxes.put(st, cb);
            leftFilters.add(cb);
        }

        JLabel searchLabel = new JLabel("Search:");
        searchField = new JTextField(12);
        searchField.setToolTipText("Filter by PRN, SVN, NORAD ID, block, or constellation");
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
        leftFilters.add(Box.createHorizontalStrut(6));
        leftFilters.add(searchLabel);
        leftFilters.add(searchField);

        JPanel rightToggles = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        rightToggles.setOpaque(false);

        showOnMapsCheckBox = new JCheckBox("Show on maps", service.isMapOverlayEnabled());
        showOnMapsCheckBox.addActionListener(e -> {
            if (!updatingControlsFromService) {
                service.setMapOverlayEnabled(showOnMapsCheckBox.isSelected());
            }
        });

        showOrbitRingsCheckBox = new JCheckBox("3D orbit rings", service.isShowOrbitRings3d());
        showOrbitRingsCheckBox.addActionListener(e -> {
            if (!updatingControlsFromService) {
                service.setShowOrbitRings3d(showOrbitRingsCheckBox.isSelected());
            }
        });

        manualListButton = new JButton("Manual Overrides...");
        manualListButton.addActionListener(e -> openManualStatusDialog());

        rightToggles.add(showOnMapsCheckBox);
        rightToggles.add(showOrbitRingsCheckBox);
        rightToggles.add(manualListButton);

        filterRow.add(leftFilters, BorderLayout.CENTER);
        filterRow.add(rightToggles, BorderLayout.EAST);
        topStack.add(filterRow);

        // 3. Health + Sim-clock & Divergence row
        JPanel healthRow = new JPanel(new BorderLayout(8, 2));
        healthRow.setOpaque(false);
        healthRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        sourceHealthPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        sourceHealthPanel.setOpaque(false);

        JPanel badgePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        badgePanel.setOpaque(false);

        manualWarningLabel = new JLabel("Manual list stale (>30d)");
        manualWarningLabel.setForeground(GnssOverlay2DRenderer.colorForStatus(GnssDisplayStatus.TESTING));
        manualWarningLabel.setFont(manualWarningLabel.getFont().deriveFont(Font.BOLD));
        manualWarningLabel.setVisible(false);

        simClockPill = new JLabel("Relative to Sim Clock");
        simClockPill.setOpaque(true);
        simClockPill.setBorder(new EmptyBorder(2, 7, 2, 7));
        simClockPill.setBackground(new Color(0x2B, 0x5C, 0x8F));
        simClockPill.setForeground(Color.WHITE);
        simClockPill.setFont(simClockPill.getFont().deriveFont(Font.BOLD));
        simClockPill.setVisible(false);

        syncDivergenceLabel = new JLabel("Sim clock >24h from live sync");
        syncDivergenceLabel.setForeground(GnssOverlay2DRenderer.colorForStatus(GnssDisplayStatus.TESTING));
        syncDivergenceLabel.setFont(syncDivergenceLabel.getFont().deriveFont(Font.BOLD));
        syncDivergenceLabel.setVisible(false);

        badgePanel.add(manualWarningLabel);
        badgePanel.add(syncDivergenceLabel);
        badgePanel.add(simClockPill);

        healthRow.add(sourceHealthPanel, BorderLayout.CENTER);
        healthRow.add(badgePanel, BorderLayout.EAST);
        topStack.add(healthRow);

        add(topStack, BorderLayout.NORTH);

        // Center: Table (top/left) + Inspector (bottom/right)
        tableModel = new GnssTableModel();
        satelliteTable = new JTable(tableModel);
        satelliteTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        satelliteTable.setRowHeight(22);
        satelliteTable.getColumnModel().getColumn(0).setPreferredWidth(55);
        satelliteTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        satelliteTable.getColumnModel().getColumn(2).setPreferredWidth(60);
        satelliteTable.getColumnModel().getColumn(3).setPreferredWidth(65);
        satelliteTable.getColumnModel().getColumn(4).setPreferredWidth(115);
        satelliteTable.getColumnModel().getColumn(4).setCellRenderer(new StatusCellRenderer());
        satelliteTable.getColumnModel().getColumn(5).setPreferredWidth(115);
        satelliteTable.getColumnModel().getColumn(6).setPreferredWidth(120);
        satelliteTable.getColumnModel().getColumn(7).setPreferredWidth(70);

        inspectorPanel = new GnssDetailsInspectorPanel(
                onJumpToEpoch,
                onCenterMaps,
                sat -> {
                    if (onAddToObjectList != null) {
                        onAddToObjectList.accept(sat);
                    }
                },
                isInObjectList);

        satelliteTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !syncingTableSelection) {
                int row = satelliteTable.getSelectedRow();
                if (row >= 0 && row < tableModel.rows.size()) {
                    GnssSatelliteState state = tableModel.rows.get(row);
                    service.selectSatellite(state.catalog().prnId());
                    inspectorPanel.setSatellite(state, lastSimEpochMillis, lastDisplayTz);
                } else {
                    inspectorPanel.setSatellite(null, lastSimEpochMillis, lastDisplayTz);
                }
            }
        });

        tableScrollPane = new JScrollPane(satelliteTable);
        tableScrollPane.setMinimumSize(new Dimension(380, 180));

        JScrollPane inspectorScroll = new JScrollPane(inspectorPanel);
        inspectorScroll.setBorder(BorderFactory.createEmptyBorder());
        inspectorScroll.setMinimumSize(new Dimension(340, 200));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableScrollPane, inspectorScroll);
        splitPane.setResizeWeight(0.55);
        splitPane.setOneTouchExpandable(true);
        add(splitPane, BorderLayout.CENTER);

        service.addListener(this);
        rebuildAll();
    }

    public void refreshDisplay(long simEpochMillis, boolean realTimeMode, TimeZone displayTz) {
        this.lastSimEpochMillis = simEpochMillis;
        this.lastRealTimeMode = realTimeMode;
        this.lastDisplayTz = displayTz != null ? displayTz : TimeZone.getTimeZone("UTC");
        rebuildAll();
    }

    public void dispose() {
        this.disposed = true;
        service.removeListener(this);
    }

    @Override
    public void onGnssDataChanged() {
        runOnEdt(this::rebuildAll);
    }

    @Override
    public void onGnssSelectionChanged(String prnId) {
        runOnEdt(() -> selectRowByPrn(prnId, true));
    }

    @Override
    public void onGnssStatusMessage(String message, boolean isWarning) {
        // Hub header handles top-level sync banner
    }

    @Override
    public void onOpenGnssUiRequested() {
        // Frame-level selection handles tab activation
    }

    public JTable getSatelliteTable() {
        return satelliteTable;
    }

    public GnssDetailsInspectorPanel getInspectorPanel() {
        return inspectorPanel;
    }

    public JCheckBox getConstellationCheckBox(GnssConstellation constellation) {
        return constellationCheckBoxes.get(constellation);
    }

    public JComponent getSbasPlaceholderComponent() {
        return sbasPlaceholderCheckBox;
    }

    public JLabel getSimClockPill() {
        return simClockPill;
    }

    public JLabel getSyncDivergenceLabel() {
        return syncDivergenceLabel;
    }

    public JTextField getSearchField() {
        return searchField;
    }

    public JLabel getManualWarningLabel() {
        return manualWarningLabel;
    }

    public int getVisibleSatelliteCount() {
        return lastVisibleCount;
    }

    private void rebuildAll() {
        updatingControlsFromService = true;
        try {
            for (GnssConstellation c : GnssConstellation.layer1ActiveValues()) {
                JCheckBox cb = constellationCheckBoxes.get(c);
                if (cb != null) {
                    cb.setSelected(service.isConstellationEnabled(c));
                }
            }
            for (GnssDisplayStatus st : GnssDisplayStatus.values()) {
                JCheckBox cb = statusFilterCheckBoxes.get(st);
                if (cb != null) {
                    cb.setSelected(service.isStatusFilterEnabled(st));
                }
            }
            showOnMapsCheckBox.setSelected(service.isMapOverlayEnabled());
            showOrbitRingsCheckBox.setSelected(service.isShowOrbitRings3d());
        } finally {
            updatingControlsFromService = false;
        }

        List<GnssSatelliteState> all = service.getAllSatelliteStates(lastSimEpochMillis);
        updateConstellationBars(all);
        updateHealthAndClockIndicators();
        rebuildTable(all);
    }

    private void updateConstellationBars(List<GnssSatelliteState> all) {
        Map<GnssConstellation, int[]> counts = new EnumMap<>(GnssConstellation.class);
        for (GnssConstellation c : GnssConstellation.layer1ActiveValues()) {
            counts.put(c, new int[4]); // 0=OP, 1=TEST, 2=UN, 3=NO
        }
        for (GnssSatelliteState s : all) {
            if (s.catalog() == null || s.catalog().constellation() == null) {
                continue;
            }
            int[] arr = counts.get(s.catalog().constellation());
            if (arr == null) {
                continue;
            }
            GnssDisplayStatus st = s.statusResult() != null
                    ? s.statusResult().status()
                    : GnssDisplayStatus.NO_STATUS_DATA;
            switch (st) {
                case OPERATIONAL -> arr[0]++;
                case TESTING -> arr[1]++;
                case UNAVAILABLE -> arr[2]++;
                case NO_STATUS_DATA -> arr[3]++;
            }
        }
        for (GnssConstellation c : GnssConstellation.layer1ActiveValues()) {
            int[] arr = counts.get(c);
            StackedBarIndicator bar = constellationBars.get(c);
            JLabel lbl = constellationCountLabels.get(c);
            if (bar != null) {
                bar.setCounts(arr[0], arr[1], arr[2], arr[3]);
                bar.setToolTipText(c.getDisplayName() + ": "
                        + arr[0] + " Operational, "
                        + arr[1] + " Testing, "
                        + arr[2] + " Unavailable, "
                        + arr[3] + " No status data");
            }
            if (lbl != null) {
                int total = arr[0] + arr[1] + arr[2] + arr[3];
                lbl.setText(arr[0] + "/" + total);
            }
        }
    }

    private void updateHealthAndClockIndicators() {
        List<GnssSourceHealth> currentHealth = service.getSourceHealthSummary();
        if (!Objects.equals(lastRenderedHealth, currentHealth)) {
            lastRenderedHealth = List.copyOf(currentHealth);
            sourceHealthPanel.removeAll();
            for (GnssSourceHealth h : currentHealth) {
                JLabel chip = new JLabel(h.displayName());
                chip.setOpaque(true);
                chip.setBorder(new EmptyBorder(2, 6, 2, 6));
                chip.setFont(chip.getFont().deriveFont(Math.max(10f, chip.getFont().getSize2D() - 1f)));
                if (!h.ok()) {
                    chip.setBackground(new Color(0x5B, 0x20, 0x24));
                    chip.setForeground(new Color(0xFF, 0xB4, 0xB4));
                } else if (h.stale()) {
                    chip.setBackground(new Color(0x4F, 0x39, 0x10));
                    chip.setForeground(new Color(0xFF, 0xDE, 0x9E));
                } else {
                    chip.setBackground(new Color(0x1E, 0x45, 0x2A));
                    chip.setForeground(new Color(0xB9, 0xF0, 0xC8));
                }
                chip.setToolTipText(h.message());
                sourceHealthPanel.add(chip);
            }
            sourceHealthPanel.revalidate();
            sourceHealthPanel.repaint();
        }

        manualWarningLabel.setVisible(service.isManualListStale());
        simClockPill.setVisible(!lastRealTimeMode);
        syncDivergenceLabel.setVisible(service.isSimClockFarFromSync(lastSimEpochMillis));
    }

    private void rebuildTable() {
        rebuildTable(service.getAllSatelliteStates(lastSimEpochMillis));
    }

    private void rebuildTable(List<GnssSatelliteState> allStates) {
        List<GnssSatelliteState> visible = new ArrayList<>();
        for (GnssSatelliteState s : allStates) {
            if (s.catalog() != null
                    && service.isConstellationEnabled(s.catalog().constellation())
                    && s.statusResult() != null
                    && service.isStatusFilterEnabled(s.statusResult().status())) {
                visible.add(s);
            }
        }
        visible.sort(java.util.Comparator
                .comparingInt((GnssSatelliteState s) -> s.statusResult().status().getSortRank())
                .thenComparing(s -> s.catalog().prnId()));
        this.lastVisibleCount = visible.size();

        String query = searchField.getText() != null
                ? searchField.getText().trim().toLowerCase(Locale.ROOT)
                : "";
        List<GnssSatelliteState> filtered;
        if (query.isEmpty()) {
            filtered = visible;
        } else {
            filtered = new ArrayList<>();
            for (GnssSatelliteState s : visible) {
                if (matchesQuery(s, query)) {
                    filtered.add(s);
                }
            }
        }

        // Preserve selection across refresh ticks without losing scroll position when rows stay same
        int prevSelectedRow = satelliteTable.getSelectedRow();
        String prevSelectedPrn = (prevSelectedRow >= 0 && prevSelectedRow < tableModel.rows.size())
                ? tableModel.rows.get(prevSelectedRow).catalog().prnId()
                : service.getSelectedPrnId();

        boolean sameOrder = tableModel.hasSamePrnOrder(filtered);
        syncingTableSelection = true;
        try {
            if (sameOrder) {
                tableModel.updateRowsInPlace(filtered);
            } else {
                tableModel.setRows(filtered);
                restoreSelectionWithoutScrolling(prevSelectedPrn);
            }
        } finally {
            syncingTableSelection = false;
        }

        int curRow = satelliteTable.getSelectedRow();
        if (curRow >= 0 && curRow < tableModel.rows.size()) {
            inspectorPanel.setSatellite(tableModel.rows.get(curRow), lastSimEpochMillis, lastDisplayTz);
        } else {
            inspectorPanel.setSatellite(null, lastSimEpochMillis, lastDisplayTz);
        }
    }

    private void restoreSelectionWithoutScrolling(String prnId) {
        if (prnId == null || prnId.isBlank()) {
            return;
        }
        for (int i = 0; i < tableModel.rows.size(); i++) {
            if (prnId.equalsIgnoreCase(tableModel.rows.get(i).catalog().prnId())) {
                satelliteTable.setRowSelectionInterval(i, i);
                return;
            }
        }
    }

    private void selectRowByPrn(String prnId, boolean scrollToRow) {
        syncingTableSelection = true;
        try {
            if (prnId == null || prnId.isBlank()) {
                satelliteTable.clearSelection();
                inspectorPanel.setSatellite(null, lastSimEpochMillis, lastDisplayTz);
                return;
            }
            for (int i = 0; i < tableModel.rows.size(); i++) {
                if (prnId.equalsIgnoreCase(tableModel.rows.get(i).catalog().prnId())) {
                    satelliteTable.setRowSelectionInterval(i, i);
                    if (scrollToRow) {
                        satelliteTable.scrollRectToVisible(satelliteTable.getCellRect(i, 0, true));
                    }
                    inspectorPanel.setSatellite(tableModel.rows.get(i), lastSimEpochMillis, lastDisplayTz);
                    return;
                }
            }
            service.getSatelliteState(prnId, lastSimEpochMillis)
                    .ifPresent(s -> inspectorPanel.setSatellite(s, lastSimEpochMillis, lastDisplayTz));
        } finally {
            syncingTableSelection = false;
        }
    }

    private static boolean matchesQuery(GnssSatelliteState s, String query) {
        GnssCatalogEntry c = s.catalog();
        if (c == null) {
            return false;
        }
        return contains(c.prnId(), query)
                || contains(c.svn(), query)
                || contains(String.valueOf(c.noradId()), query)
                || contains(c.blockType(), query)
                || (c.constellation() != null && contains(c.constellation().getDisplayName(), query));
    }

    private static boolean contains(String field, String query) {
        return field != null && field.toLowerCase(Locale.ROOT).contains(query);
    }

    private void openManualStatusDialog() {
        Window owner = SwingUtilities.getWindowAncestor(this);
        GnssManualStatusDialog dialog = new GnssManualStatusDialog(owner, service);
        dialog.setVisible(true);
    }

    private void runOnEdt(Runnable action) {
        if (disposed) {
            return;
        }
        Runnable guarded = () -> {
            if (!disposed) {
                action.run();
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            guarded.run();
        } else {
            SwingUtilities.invokeLater(guarded);
        }
    }

    private static Color mutedForeground() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : new Color(130, 136, 145);
    }

    private static final class GnssTableModel extends AbstractTableModel {
        private static final String[] COLS = {
                "PRN", "System", "SVN", "NORAD", "Status", "Primary Source", "Notice", "Alt (km)"
        };
        private List<GnssSatelliteState> rows = new ArrayList<>();

        void setRows(List<GnssSatelliteState> next) {
            this.rows = new ArrayList<>(next);
            fireTableDataChanged();
        }

        void updateRowsInPlace(List<GnssSatelliteState> next) {
            this.rows = new ArrayList<>(next);
            if (!rows.isEmpty()) {
                fireTableRowsUpdated(0, rows.size() - 1);
            }
        }

        boolean hasSamePrnOrder(List<GnssSatelliteState> next) {
            if (rows.size() != next.size()) {
                return false;
            }
            for (int i = 0; i < rows.size(); i++) {
                String a = rows.get(i).catalog().prnId();
                String b = next.get(i).catalog().prnId();
                if (!Objects.equals(a, b)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            GnssSatelliteState state = rows.get(rowIndex);
            GnssCatalogEntry cat = state.catalog();
            GnssStatusResult res = state.statusResult();
            return switch (columnIndex) {
                case 0 -> cat.prnId();
                case 1 -> cat.constellation() != null ? cat.constellation().getDisplayName() : "";
                case 2 -> cat.svn() != null && !cat.svn().isBlank() ? cat.svn() : "\u2014";
                case 3 -> cat.noradId() > 0 ? String.valueOf(cat.noradId()) : "\u2014";
                case 4 -> res != null && res.status() != null ? res.status() : GnssDisplayStatus.NO_STATUS_DATA;
                case 5 -> res != null && res.primarySource() != null ? res.primarySource() : "\u2014";
                case 6 -> formatNoticeCell(res);
                case 7 -> state.position() != null
                        ? String.format(Locale.ROOT, "%.0f", state.position().altMeters() / 1000.0)
                        : "\u2014";
                default -> "";
            };
        }

        private static String formatNoticeCell(GnssStatusResult res) {
            if (res == null) {
                return "\u2014";
            }
            GnssOutageNotice active = res.activeNotice();
            if (active != null) {
                return "ACTIVE: " + active.noticeId();
            }
            GnssOutageNotice planned = res.nextPlannedNotice();
            if (planned != null) {
                return "Planned: " + planned.noticeId();
            }
            return "\u2014";
        }
    }

    private static final class StatusCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (value instanceof GnssDisplayStatus status) {
                setText(status.getDisplayLabel());
                Color c = GnssOverlay2DRenderer.colorForStatus(status);
                setForeground(isSelected ? table.getSelectionForeground() : c);
                setFont(getFont().deriveFont(Font.BOLD));
            }
            return this;
        }
    }

    private static final class SmallShapeIcon implements Icon {
        private final GnssConstellation constellation;

        SmallShapeIcon(GnssConstellation constellation) {
            this.constellation = constellation;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                GnssOverlay2DRenderer.drawConstellationShape(
                        g2,
                        constellation,
                        x + 6.0,
                        y + 6.0,
                        4.5,
                        GnssOverlay2DRenderer.colorForStatus(GnssDisplayStatus.OPERATIONAL),
                        new Color(20, 24, 30));
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return 12;
        }

        @Override
        public int getIconHeight() {
            return 12;
        }
    }

    private static final class StackedBarIndicator extends JComponent {
        private int op;
        private int test;
        private int unavail;
        private int noData;

        StackedBarIndicator() {
            setPreferredSize(new Dimension(54, 10));
            setMinimumSize(new Dimension(54, 10));
        }

        void setCounts(int op, int test, int unavail, int noData) {
            this.op = op;
            this.test = test;
            this.unavail = unavail;
            this.noData = noData;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            int w = getWidth();
            int h = getHeight();
            int total = op + test + unavail + noData;
            g.setColor(new Color(45, 50, 58));
            g.fillRect(0, 1, w, h - 2);
            if (total <= 0) {
                return;
            }
            int x = 0;
            x += drawSegment(g, x, w, h, op, total, GnssDisplayStatus.OPERATIONAL);
            x += drawSegment(g, x, w, h, test, total, GnssDisplayStatus.TESTING);
            x += drawSegment(g, x, w, h, unavail, total, GnssDisplayStatus.UNAVAILABLE);
            drawSegment(g, x, w, h, noData, total, GnssDisplayStatus.NO_STATUS_DATA);
        }

        private static int drawSegment(
                Graphics g, int x, int totalW, int h, int count, int totalCount, GnssDisplayStatus status) {
            if (count <= 0) {
                return 0;
            }
            int segW = Math.max(2, (int) Math.round(totalW * (double) count / totalCount));
            if (x + segW > totalW) {
                segW = totalW - x;
            }
            if (segW <= 0) {
                return 0;
            }
            g.setColor(GnssOverlay2DRenderer.colorForStatus(status));
            g.fillRect(x, 1, segW, h - 2);
            return segW;
        }
    }
}

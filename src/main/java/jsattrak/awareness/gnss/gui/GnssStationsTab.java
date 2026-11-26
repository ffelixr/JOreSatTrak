package jsattrak.awareness.gnss.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.geometry.GnssGeometryService;
import jsattrak.awareness.gnss.geometry.GnssObserverPosition;
import jsattrak.awareness.gnss.stations.GnssReferenceStation;
import jsattrak.awareness.gnss.stations.GnssStationListener;
import jsattrak.awareness.gnss.stations.GnssStationNetwork;
import jsattrak.awareness.gnss.stations.GnssStationService;
import jsattrak.awareness.gnss.stations.GnssStationStatus;
import jsattrak.awareness.gnss.stations.analysis.GnssSppSolver;
import jsattrak.awareness.gnss.stations.analysis.GnssStationAnalysis;
import jsattrak.awareness.gnss.stations.analysis.GnssStationAnalysis.SatelliteMeasurementSummary;
import jsattrak.awareness.gnss.stations.analysis.RegionalScanReport;
import jsattrak.awareness.gnss.stations.analysis.RegionalScanReport.RegionalEventSeverity;
import jsattrak.awareness.gnss.stations.analysis.RegionalScanReport.StationAnomalySummary;
import jsattrak.awareness.gui.LaunchDetailsInspectorPanel;

/**
 * 4th {@code GNSS Stations} tab inside {@code LiveAwarenessInternalFrame} uniting reference
 * station browsing, hardware/latency metadata, single-station RINEX measurement inspection, and
 * regional C/N0 RF-interference scanning.
 */
public class GnssStationsTab extends JPanel implements GnssStationListener {

    private final GnssStationService stationService;
    private final GnssGeometryService geometryService;
    private final Runnable onSwitchToDopTab;
    private final BiConsumer<Double, Double> onCenterMaps;

    private final JCheckBox igsCheckBox;
    private final JCheckBox eurefCheckBox;
    private final JCheckBox noaaCheckBox;
    private final JCheckBox showOnMapsCheckBox;
    private final JComboBox<String> statusFilterCombo;
    private final JTextField searchField;

    private final StationTableModel stationTableModel = new StationTableModel();
    private final JTable stationTable;

    private final JLabel stationHeaderLabel;
    private final JLabel hardwareDetailsLabel;
    private final JLabel obsSourceBadgeLabel;
    private final JCheckBox matchSimEpochCheckBox;
    private final JButton fetchButton;
    private final JButton loadRinexButton;
    private final JButton loadNavButton;
    private final JButton clearNavButton;
    private final JButton scanRegionButton;
    private final JButton centerMapButton;
    private final JButton analyzeInDopButton;
    private final JSpinner maskSpinner;

    private final JLabel kpiCompletenessLabel;
    private final JLabel kpiCn0Label;
    private final JLabel kpiSlipsLabel;
    private final JLabel kpiMultipathLabel;
    private final JLabel kpiSppLabel;
    private final JLabel epochNoticeLabel;

    private final GnssObservedSkyplotPanel skyplotPanel = new GnssObservedSkyplotPanel();
    private final GnssCn0ChartPanel chartPanel = new GnssCn0ChartPanel();
    private final JComboBox<GnssCn0ChartPanel.ChartMode> chartModeCombo;
    private final SvTableModel svTableModel = new SvTableModel();

    private final JSpinner scanRadiusSpinner;
    private final JSpinner scanMaxStationsSpinner;
    private final JLabel scanBannerLabel;
    private final GnssCn0MatrixPanel matrixPanel;
    private final RegionalTableModel regionalTableModel = new RegionalTableModel();

    private final JTabbedPane rightSubTabs = new JTabbedPane();
    private TimeZone lastDisplayTz = TimeZone.getTimeZone("UTC");
    private boolean updatingControls;
    private volatile boolean disposed;

    public GnssStationsTab(
            GnssStationService stationService,
            GnssGeometryService geometryService,
            Runnable onSwitchToDopTab,
            BiConsumer<Double, Double> onCenterMaps) {
        this.stationService = Objects.requireNonNull(stationService, "stationService");
        this.geometryService = geometryService;
        this.onSwitchToDopTab = onSwitchToDopTab;
        this.onCenterMaps = onCenterMaps;

        setLayout(new BorderLayout(6, 6));
        setBorder(new EmptyBorder(6, 6, 6, 6));

        // ---- Top global filter & map toggle bar ----
        JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        igsCheckBox = new JCheckBox("IGS", stationService.isNetworkEnabled(GnssStationNetwork.IGS));
        eurefCheckBox = new JCheckBox("EUREF", stationService.isNetworkEnabled(GnssStationNetwork.EUREF));
        noaaCheckBox = new JCheckBox("NOAA", stationService.isNetworkEnabled(GnssStationNetwork.NOAA_CORS));
        statusFilterCombo = new JComboBox<>(new String[]{"All", "ONLINE", "DELAYED", "OFFLINE", "UNKNOWN"});
        searchField = new JTextField(14);
        searchField.setToolTipText("Filter by station ID, name, country, or receiver");
        showOnMapsCheckBox = new JCheckBox("Show Stations on maps", stationService.isMapOverlayEnabled());

        igsCheckBox.addActionListener(e -> onNetworkToggle(GnssStationNetwork.IGS, igsCheckBox.isSelected()));
        eurefCheckBox.addActionListener(e -> onNetworkToggle(GnssStationNetwork.EUREF, eurefCheckBox.isSelected()));
        noaaCheckBox.addActionListener(e -> onNetworkToggle(GnssStationNetwork.NOAA_CORS, noaaCheckBox.isSelected()));
        statusFilterCombo.addActionListener(e -> reloadStationTable());
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { reloadStationTable(); }
            @Override
            public void removeUpdate(DocumentEvent e) { reloadStationTable(); }
            @Override
            public void changedUpdate(DocumentEvent e) { reloadStationTable(); }
        });
        showOnMapsCheckBox.addActionListener(e -> {
            if (!updatingControls) {
                stationService.setMapOverlayEnabled(showOnMapsCheckBox.isSelected());
            }
        });

        filterBar.add(new JLabel("Networks:"));
        filterBar.add(igsCheckBox);
        filterBar.add(eurefCheckBox);
        filterBar.add(noaaCheckBox);
        filterBar.add(new JLabel("Status:"));
        filterBar.add(statusFilterCombo);
        filterBar.add(new JLabel("Search:"));
        filterBar.add(searchField);
        filterBar.add(Box.createHorizontalStrut(8));
        filterBar.add(showOnMapsCheckBox);
        add(filterBar, BorderLayout.NORTH);

        // ---- Left pane: Station directory table ----
        stationTable = new JTable(stationTableModel);
        stationTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        stationTable.setRowHeight(21);
        stationTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !updatingControls) {
                int viewRow = stationTable.getSelectedRow();
                if (viewRow >= 0) {
                    int modelRow = stationTable.convertRowIndexToModel(viewRow);
                    GnssReferenceStation st = stationTableModel.getAt(modelRow);
                    if (st != null) {
                        stationService.selectStation(st.stationId());
                    }
                }
            }
        });
        JScrollPane tableScroll = new JScrollPane(stationTable);
        tableScroll.setPreferredSize(new Dimension(320, 420));

        // ---- Right pane: Hardware header + controls + Inspector & Scan sub-tabs ----
        JPanel rightPane = new JPanel(new BorderLayout(6, 6));

        JPanel stationHeaderCard = new JPanel();
        stationHeaderCard.setLayout(new BoxLayout(stationHeaderCard, BoxLayout.Y_AXIS));
        stationHeaderCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor()),
                new EmptyBorder(4, 4, 6, 4)));

        stationHeaderLabel = new JLabel("Select a GNSS reference station");
        stationHeaderLabel.setFont(stationHeaderLabel.getFont().deriveFont(Font.BOLD, 13.0f));
        hardwareDetailsLabel = new JLabel("Receiver / Antenna metadata appears here");
        hardwareDetailsLabel.setForeground(mutedForeground());
        obsSourceBadgeLabel = new JLabel("No observations fetched yet");
        obsSourceBadgeLabel.setForeground(mutedForeground());

        JPanel metaLines = new JPanel(new GridLayout(3, 1, 0, 2));
        metaLines.setOpaque(false);
        metaLines.add(stationHeaderLabel);
        metaLines.add(hardwareDetailsLabel);
        metaLines.add(obsSourceBadgeLabel);

        JPanel actionStrip = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        matchSimEpochCheckBox = new JCheckBox("Match Sim Epoch", stationService.isMatchSimEpoch());
        fetchButton = new JButton(matchSimEpochCheckBox.isSelected() ? "Fetch Sim Obs" : "Fetch Latest Obs");
        loadRinexButton = new JButton("Load RINEX...");
        loadNavButton = new JButton("Load Nav/SP3...");
        clearNavButton = new JButton("Clear Nav");
        scanRegionButton = new JButton("Scan Region");
        centerMapButton = new JButton("Center Maps");
        analyzeInDopButton = new JButton("\u2192 DOP");
        analyzeInDopButton.setToolTipText("Set GNSS Geometry observer to this station and switch to GNSS Geometry");
        maskSpinner = new JSpinner(new SpinnerNumberModel(stationService.getElevationMaskDeg(), 0.0, 45.0, 1.0));
        ((JSpinner.NumberEditor) maskSpinner.getEditor()).getFormat().applyPattern("0");

        matchSimEpochCheckBox.addActionListener(e -> {
            boolean match = matchSimEpochCheckBox.isSelected();
            fetchButton.setText(match ? "Fetch Sim Obs" : "Fetch Latest Obs");
            if (!updatingControls) {
                stationService.setMatchSimEpoch(match);
            }
        });
        fetchButton.addActionListener(e -> stationService.fetchSelectedStationObservationsAsync());
        loadRinexButton.addActionListener(e -> onLoadRinexFiles());
        loadNavButton.addActionListener(e -> onLoadNavOrSp3File());
        clearNavButton.addActionListener(e -> stationService.clearLocalNavOrSp3File());
        scanRegionButton.addActionListener(e -> stationService.scanRegionalInterferenceAsync(stationService.getSelectedStationId()));
        centerMapButton.addActionListener(e -> {
            GnssReferenceStation st = stationService.getSelectedStation();
            if (st != null && onCenterMaps != null) {
                onCenterMaps.accept(st.latDeg(), st.lonDeg());
            }
        });
        analyzeInDopButton.addActionListener(e -> onAnalyzeInDop());
        maskSpinner.addChangeListener(e -> {
            if (!updatingControls && maskSpinner.getValue() instanceof Number n) {
                stationService.setElevationMaskDeg(n.doubleValue());
            }
        });

        actionStrip.add(matchSimEpochCheckBox);
        actionStrip.add(fetchButton);
        actionStrip.add(loadRinexButton);
        actionStrip.add(loadNavButton);
        actionStrip.add(clearNavButton);
        actionStrip.add(scanRegionButton);
        actionStrip.add(centerMapButton);
        actionStrip.add(analyzeInDopButton);
        actionStrip.add(new JLabel("Mask:"));
        actionStrip.add(maskSpinner);

        stationHeaderCard.add(metaLines);
        stationHeaderCard.add(actionStrip);
        rightPane.add(stationHeaderCard, BorderLayout.NORTH);

        // Sub-tab 0: Measurement Inspector
        JPanel inspectorPanel = new JPanel(new BorderLayout(6, 6));
        JPanel kpiStrip = new JPanel(new GridLayout(2, 3, 8, 3));
        kpiStrip.setBorder(new EmptyBorder(4, 6, 4, 6));
        kpiCompletenessLabel = new JLabel("Tracked: —");
        kpiCn0Label = new JLabel("Med C/N0: —");
        kpiSlipsLabel = new JLabel("Cycle Slips: —");
        kpiMultipathLabel = new JLabel("MP1 / MP2 RMS: —");
        kpiSppLabel = new JLabel("SPP Residual: —");
        epochNoticeLabel = new JLabel("");
        epochNoticeLabel.setForeground(new Color(0xFA, 0x8C, 0x16));
        kpiStrip.add(kpiCompletenessLabel);
        kpiStrip.add(kpiCn0Label);
        kpiStrip.add(kpiSlipsLabel);
        kpiStrip.add(kpiMultipathLabel);
        kpiStrip.add(kpiSppLabel);
        kpiStrip.add(epochNoticeLabel);
        inspectorPanel.add(kpiStrip, BorderLayout.NORTH);

        JPanel chartContainer = new JPanel(new BorderLayout(4, 4));
        JPanel chartModeBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        chartModeCombo = new JComboBox<>(GnssCn0ChartPanel.ChartMode.values());
        chartModeCombo.addActionListener(e -> chartPanel.setChartMode(
                (GnssCn0ChartPanel.ChartMode) chartModeCombo.getSelectedItem()));
        chartModeBar.add(new JLabel("Chart:"));
        chartModeBar.add(chartModeCombo);
        chartContainer.add(chartModeBar, BorderLayout.NORTH);
        chartContainer.add(chartPanel, BorderLayout.CENTER);

        JSplitPane visualSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, skyplotPanel, chartContainer);
        visualSplit.setResizeWeight(0.44);
        visualSplit.setContinuousLayout(true);

        JTable svTable = new JTable(svTableModel);
        svTable.setRowHeight(20);
        JScrollPane svScroll = new JScrollPane(svTable);
        svScroll.setPreferredSize(new Dimension(420, 130));

        JSplitPane inspectorVertSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, visualSplit, svScroll);
        inspectorVertSplit.setResizeWeight(0.68);
        inspectorVertSplit.setContinuousLayout(true);
        inspectorPanel.add(inspectorVertSplit, BorderLayout.CENTER);

        // Sub-tab 1: Regional Interference Scan
        JPanel regionalPanel = new JPanel(new BorderLayout(6, 6));
        JPanel scanTopBar = new JPanel(new BorderLayout(6, 4));
        JPanel scanParams = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        scanRadiusSpinner = new JSpinner(new SpinnerNumberModel(stationService.getScanRadiusKm(), 50.0, 2500.0, 50.0));
        scanMaxStationsSpinner = new JSpinner(new SpinnerNumberModel(stationService.getScanMaxStations(), 2, 16, 1));
        scanRadiusSpinner.addChangeListener(e -> {
            if (!updatingControls && scanRadiusSpinner.getValue() instanceof Number n) {
                stationService.setScanRadiusKm(n.doubleValue());
            }
        });
        scanMaxStationsSpinner.addChangeListener(e -> {
            if (!updatingControls && scanMaxStationsSpinner.getValue() instanceof Number n) {
                stationService.setScanMaxStations(n.intValue());
            }
        });
        scanParams.add(new JLabel("Radius (km):"));
        scanParams.add(scanRadiusSpinner);
        scanParams.add(new JLabel("Max stations:"));
        scanParams.add(scanMaxStationsSpinner);

        scanBannerLabel = new JLabel("No regional C/N0 scan executed yet");
        scanBannerLabel.setFont(scanBannerLabel.getFont().deriveFont(Font.BOLD, 12.0f));
        scanBannerLabel.setBorder(new EmptyBorder(4, 6, 4, 6));

        scanTopBar.add(scanParams, BorderLayout.NORTH);
        scanTopBar.add(scanBannerLabel, BorderLayout.SOUTH);
        regionalPanel.add(scanTopBar, BorderLayout.NORTH);

        matrixPanel = new GnssCn0MatrixPanel(stationId -> stationService.selectStation(stationId));
        JTable regionalTable = new JTable(regionalTableModel);
        regionalTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        regionalTable.setRowHeight(20);
        regionalTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !updatingControls) {
                int row = regionalTable.getSelectedRow();
                if (row >= 0) {
                    StationAnomalySummary sum = regionalTableModel.getAt(row);
                    if (sum != null && sum.station() != null) {
                        stationService.selectStation(sum.station().stationId());
                    }
                }
            }
        });
        JScrollPane regTableScroll = new JScrollPane(regionalTable);
        regTableScroll.setPreferredSize(new Dimension(420, 150));

        JSplitPane regionalSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(matrixPanel), regTableScroll);
        regionalSplit.setResizeWeight(0.62);
        regionalSplit.setContinuousLayout(true);
        regionalPanel.add(regionalSplit, BorderLayout.CENTER);

        rightSubTabs.addTab("Measurement Inspector", inspectorPanel);
        rightSubTabs.addTab("Regional Interference Scan", regionalPanel);
        rightPane.add(rightSubTabs, BorderLayout.CENTER);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableScroll, rightPane);
        mainSplit.setResizeWeight(0.36);
        mainSplit.setContinuousLayout(true);
        add(mainSplit, BorderLayout.CENTER);

        stationService.addListener(this);
        reloadStationTable();
        updateStationHeader();
        updateAnalysisView();
        updateRegionalView();
    }

    public JCheckBox getMatchSimEpochCheckBox() {
        return matchSimEpochCheckBox;
    }

    public JButton getFetchButton() {
        return fetchButton;
    }

    public JButton getAnalyzeInDopButton() {
        return analyzeInDopButton;
    }

    public JButton getScanRegionButton() {
        return scanRegionButton;
    }

    public JCheckBox getShowOnMapsCheckBox() {
        return showOnMapsCheckBox;
    }

    public GnssObservedSkyplotPanel getSkyplotPanel() {
        return skyplotPanel;
    }

    public GnssCn0ChartPanel getChartPanel() {
        return chartPanel;
    }

    public GnssCn0MatrixPanel getMatrixPanel() {
        return matrixPanel;
    }

    public void refreshDisplay(long simEpochMillis, boolean realTimeMode, TimeZone displayTz) {
        this.lastDisplayTz = displayTz != null ? displayTz : TimeZone.getTimeZone("UTC");
        stationService.updateSimEpochMillis(simEpochMillis, realTimeMode);
    }

    public void dispose() {
        this.disposed = true;
        stationService.removeListener(this);
    }

    private void onNetworkToggle(GnssStationNetwork net, boolean enabled) {
        if (!updatingControls) {
            stationService.setNetworkEnabled(net, enabled);
        }
    }

    private void onLoadRinexFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setDialogTitle("Select RINEX Observation File(s)");
        chooser.setFileFilter(new FileNameExtensionFilter(
                "RINEX Observation (*.rnx, *.crx, *.obs, *.gz, *.Z)", "rnx", "crx", "obs", "gz", "Z"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File[] selected = chooser.getSelectedFiles();
            if (selected != null && selected.length > 0) {
                List<java.nio.file.Path> paths = new ArrayList<>();
                for (File f : selected) {
                    paths.add(f.toPath());
                }
                stationService.loadLocalObservationFilesAsync(paths);
            }
        }
    }

    private void onLoadNavOrSp3File() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Load Local RINEX Nav or SP3 Ephemeris/Clock File");
        chooser.setFileFilter(new FileNameExtensionFilter(
                "RINEX Nav / SP3 (*.nav, *.rnx, *.sp3, *.n, *.gz)", "nav", "rnx", "sp3", "n", "gz"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
            try {
                stationService.importLocalNavOrSp3File(chooser.getSelectedFile().toPath());
            } catch (java.io.IOException | RuntimeException ex) {
                javax.swing.JOptionPane.showMessageDialog(
                        this,
                        "Failed to load Nav/SP3 file: " + ex.getMessage(),
                        "Load Nav/SP3",
                        javax.swing.JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void onAnalyzeInDop() {
        GnssReferenceStation st = stationService.getSelectedStation();
        if (st != null && geometryService != null) {
            geometryService.setObserver(new GnssObserverPosition(
                    st.stationId(),
                    st.latDeg(),
                    st.lonDeg(),
                    Double.isFinite(st.altMeters()) ? st.altMeters() : 0.0));
        }
        if (onSwitchToDopTab != null) {
            onSwitchToDopTab.run();
        }
    }

    private void reloadStationTable() {
        String filterText = searchField.getText();
        String statusStr = (String) statusFilterCombo.getSelectedItem();
        GnssStationStatus statusFilter = null;
        if (statusStr != null && !"All".equals(statusStr)) {
            statusFilter = GnssStationStatus.valueOf(statusStr);
        }
        List<GnssReferenceStation> filtered = stationService.getFilteredStations(filterText, statusFilter);
        updatingControls = true;
        try {
            stationTableModel.setRows(filtered);
            String selId = stationService.getSelectedStationId();
            if (selId != null) {
                for (int i = 0; i < filtered.size(); i++) {
                    if (selId.equalsIgnoreCase(filtered.get(i).stationId())) {
                        int viewIdx = stationTable.convertRowIndexToView(i);
                        if (viewIdx >= 0) {
                            stationTable.setRowSelectionInterval(viewIdx, viewIdx);
                        }
                        break;
                    }
                }
            }
        } finally {
            updatingControls = false;
        }
    }

    private void updateStationHeader() {
        GnssReferenceStation st = stationService.getSelectedStation();
        boolean hasSt = st != null;
        fetchButton.setEnabled(hasSt);
        scanRegionButton.setEnabled(hasSt);
        centerMapButton.setEnabled(hasSt && onCenterMaps != null);
        analyzeInDopButton.setEnabled(hasSt && geometryService != null);

        if (st == null) {
            stationHeaderLabel.setText("Select a GNSS reference station");
            hardwareDetailsLabel.setText("Receiver / Antenna metadata appears here");
            obsSourceBadgeLabel.setText(buildSourceBannerText());
            return;
        }

        String nets = st.networks().stream().map(GnssStationNetwork::getDisplayName).collect(Collectors.joining(", "));
        stationHeaderLabel.setText(String.format(Locale.US,
                "%s \u2014 %s (%s) [%.3f\u00B0, %.3f\u00B0, %.0f m] \u00B7 %s",
                st.stationId(), st.siteName(), st.countryCode(),
                st.latDeg(), st.lonDeg(), Double.isFinite(st.altMeters()) ? st.altMeters() : 0.0, nets));

        String rx = st.receiverModel().isBlank() ? "Unknown receiver" : st.receiverModel();
        String ant = st.antennaModel().isBlank() ? "Unknown antenna" : st.antennaModel();
        String consts = st.supportedConstellations().isEmpty()
                ? "—"
                : st.supportedConstellations().stream().map(GnssConstellation::getDisplayName).collect(Collectors.joining(" "));
        String latency = st.latencyMinutes() >= 0 ? st.latencyMinutes() + " min" : "N/A";
        hardwareDetailsLabel.setText(String.format(Locale.US,
                "Rx: %s | Ant: %s (%s) | Sys: %s | Status: %s (latency %s)",
                rx, ant, st.radomeCode(), consts, st.status().getDisplayLabel(), latency));
        obsSourceBadgeLabel.setText(buildSourceBannerText());
    }

    private String buildSourceBannerText() {
        String fmt = stationService.getLoadedObsFormat();
        String src = stationService.getLoadedObsSourceName();
        String localNav = stationService.getSharedEphemerisProvider().getLoadedLocalFileName();
        String eph = (localNav != null && !localNav.isBlank()) ? ("Local: " + localNav) : "Broadcast / TLE";
        String obsPart = (fmt != null && !fmt.isBlank()) ? (fmt + " via " + src) : "No observation loaded";
        return "Obs: " + obsPart + "  |  Orbits/Clocks: " + eph;
    }

    private void updateAnalysisView() {
        GnssStationAnalysis analysis = stationService.getLatestAnalysis();
        skyplotPanel.setAnalysis(analysis);
        chartPanel.setAnalysis(analysis, lastDisplayTz);
        obsSourceBadgeLabel.setText(buildSourceBannerText());

        if (analysis == null) {
            kpiCompletenessLabel.setText("Tracked: —");
            kpiCn0Label.setText("Med C/N0: —");
            kpiSlipsLabel.setText("Cycle Slips: —");
            kpiMultipathLabel.setText("MP1 / MP2 RMS: —");
            kpiSppLabel.setText("SPP Residual: —");
            epochNoticeLabel.setText("");
            svTableModel.setRows(List.of());
            return;
        }

        kpiCompletenessLabel.setText(String.format(Locale.US, "Tracked: %d/%d (%.0f%%)",
                analysis.trackedCount(), analysis.predictedCount(), analysis.trackingCompletenessPct()));
        kpiCn0Label.setText(Double.isFinite(analysis.medianCn0DbHz())
                ? String.format(Locale.US, "Med C/N0: %.1f dB-Hz (\u0394 %+.1f)",
                        analysis.medianCn0DbHz(), analysis.medianDeltaCn0DbHz())
                : "Med C/N0: —");
        kpiSlipsLabel.setText(String.format(Locale.US, "Slips: %d (LLI %d / GF %d, %.1f/100)",
                analysis.totalCycleSlips(), analysis.lliSlipCount(), analysis.gfSlipCount(), analysis.slipsPer100Obs()));
        kpiMultipathLabel.setText(Double.isFinite(analysis.mp1RmsMeters())
                ? String.format(Locale.US, "MP1: %.2f m | MP2: %.2f m", analysis.mp1RmsMeters(), analysis.mp2RmsMeters())
                : "MP1 / MP2: N/A");

        GnssSppSolver.SppSessionSummary spp = analysis.spp();
        if (spp != null && spp.inspectionEpoch() != null && spp.inspectionEpoch().sppAvailable()) {
            kpiSppLabel.setText(String.format(Locale.US, "SPP H: %.2f m | V: %.2f m (%d SVs)",
                    spp.inspectionEpoch().horizontalErrorM(),
                    spp.inspectionEpoch().verticalErrorM(),
                    spp.inspectionEpoch().usedSvCount()));
        } else {
            kpiSppLabel.setText("SPP: " + (spp != null && spp.inspectionEpoch() != null
                    ? spp.inspectionEpoch().statusMessage() : "N/A"));
        }

        if (analysis.epochOffsetFlagged()) {
            epochNoticeLabel.setText("Inspecting obs @ "
                    + LaunchDetailsInspectorPanel.formatDateTime(analysis.inspectionEpochMillis(), lastDisplayTz)
                    + " (offset from sim clock)");
        } else {
            epochNoticeLabel.setText("Epoch @ "
                    + LaunchDetailsInspectorPanel.formatDateTime(analysis.inspectionEpochMillis(), lastDisplayTz));
        }

        svTableModel.setRows(analysis.satellites());
    }

    private void updateRegionalView() {
        RegionalScanReport report = stationService.getLatestRegionalReport();
        matrixPanel.setReport(report);
        matrixPanel.setSelectedStationId(stationService.getSelectedStationId());
        if (report == null || report.stations().isEmpty()) {
            scanBannerLabel.setText("No regional C/N0 scan executed yet");
            scanBannerLabel.setForeground(mutedForeground());
            regionalTableModel.setRows(List.of());
            return;
        }
        scanBannerLabel.setText(report.severity().getDisplayLabel() + " \u2014 " + report.diagnosis());
        if (report.severity() == RegionalEventSeverity.POTENTIAL_INTERFERENCE) {
            scanBannerLabel.setForeground(new Color(0xE5, 0x48, 0x4D));
        } else if (report.severity() == RegionalEventSeverity.LOCAL_DEGRADATION) {
            scanBannerLabel.setForeground(new Color(0xFA, 0x8C, 0x16));
        } else {
            scanBannerLabel.setForeground(new Color(0x3F, 0xBF, 0x6A));
        }
        regionalTableModel.setRows(report.stations());
    }

    private void runEdt(Runnable r) {
        if (disposed) {
            return;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(() -> {
                if (!disposed) {
                    r.run();
                }
            });
        }
    }

    @Override
    public void onStationCatalogChanged() {
        runEdt(() -> {
            reloadStationTable();
            updateStationHeader();
        });
    }

    @Override
    public void onSelectedStationChanged(String selectedStationId) {
        runEdt(() -> {
            reloadStationTable();
            updateStationHeader();
            matrixPanel.setSelectedStationId(selectedStationId);
        });
    }

    @Override
    public void onInspectionUpdated(GnssStationAnalysis analysis) {
        runEdt(this::updateAnalysisView);
    }

    @Override
    public void onRegionalScanUpdated(RegionalScanReport report) {
        runEdt(this::updateRegionalView);
    }

    @Override
    public void onStationStatusMessage(String message, boolean isWarning) {
        runEdt(() -> {
            if (message != null && !message.isBlank()) {
                obsSourceBadgeLabel.setText(buildSourceBannerText() + "  [" + message + "]");
            }
        });
    }

    @Override
    public void onMapOverlayStateChanged(boolean enabled) {
        runEdt(() -> {
            updatingControls = true;
            try {
                showOnMapsCheckBox.setSelected(enabled);
            } finally {
                updatingControls = false;
            }
        });
    }

    private static Color borderColor() {
        Color c = UIManager.getColor("Component.borderColor");
        return c != null ? c : new Color(128, 128, 128, 90);
    }

    private static Color mutedForeground() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : new Color(128, 128, 128);
    }

    private static final class StationTableModel extends AbstractTableModel {
        private static final String[] COLS = {"Station", "Site", "Network", "Status", "Latency"};
        private List<GnssReferenceStation> rows = List.of();

        void setRows(List<GnssReferenceStation> next) {
            this.rows = next != null ? List.copyOf(next) : List.of();
            fireTableDataChanged();
        }

        GnssReferenceStation getAt(int row) {
            return (row >= 0 && row < rows.size()) ? rows.get(row) : null;
        }

        @Override
        public int getRowCount() { return rows.size(); }

        @Override
        public int getColumnCount() { return COLS.length; }

        @Override
        public String getColumnName(int col) { return COLS[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            GnssReferenceStation s = rows.get(row);
            return switch (col) {
                case 0 -> s.stationId();
                case 1 -> s.siteName();
                case 2 -> s.primaryNetwork().getDisplayName();
                case 3 -> s.status().getDisplayLabel();
                case 4 -> s.latencyMinutes() >= 0 ? s.latencyMinutes() + "m" : "—";
                default -> "";
            };
        }
    }

    private static final class SvTableModel extends AbstractTableModel {
        private static final String[] COLS = {"PRN", "Sys", "State", "El°", "Med C/N0", "ΔC/N0", "Slips", "MP1 m", "Obs"};
        private List<SatelliteMeasurementSummary> rows = List.of();

        void setRows(List<SatelliteMeasurementSummary> next) {
            this.rows = next != null ? List.copyOf(next) : List.of();
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() { return rows.size(); }

        @Override
        public int getColumnCount() { return COLS.length; }

        @Override
        public String getColumnName(int col) { return COLS[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            SatelliteMeasurementSummary s = rows.get(row);
            return switch (col) {
                case 0 -> s.prnId();
                case 1 -> s.constellation().getDisplayName();
                case 2 -> s.trackingState().getDisplayLabel();
                case 3 -> Double.isFinite(s.elevationDeg()) ? String.format(Locale.US, "%.1f", s.elevationDeg()) : "—";
                case 4 -> Double.isFinite(s.medianCn0DbHz()) ? String.format(Locale.US, "%.1f", s.medianCn0DbHz()) : "—";
                case 5 -> Double.isFinite(s.medianDeltaCn0DbHz()) ? String.format(Locale.US, "%+.1f", s.medianDeltaCn0DbHz()) : "—";
                case 6 -> s.slipCount();
                case 7 -> Double.isFinite(s.mp1RmsMeters()) ? String.format(Locale.US, "%.2f", s.mp1RmsMeters()) : "—";
                case 8 -> s.obsCount();
                default -> "";
            };
        }
    }

    private static final class RegionalTableModel extends AbstractTableModel {
        private static final String[] COLS = {"Station", "Dist km", "Level", "Med ΔC/N0", "Miss %", "Slips/100", "Reason"};
        private List<StationAnomalySummary> rows = List.of();

        void setRows(List<StationAnomalySummary> next) {
            this.rows = next != null ? List.copyOf(next) : List.of();
            fireTableDataChanged();
        }

        StationAnomalySummary getAt(int row) {
            return (row >= 0 && row < rows.size()) ? rows.get(row) : null;
        }

        @Override
        public int getRowCount() { return rows.size(); }

        @Override
        public int getColumnCount() { return COLS.length; }

        @Override
        public String getColumnName(int col) { return COLS[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            StationAnomalySummary s = rows.get(row);
            return switch (col) {
                case 0 -> s.station().stationId();
                case 1 -> String.format(Locale.US, "%.0f", s.distanceKm());
                case 2 -> s.level().getDisplayLabel();
                case 3 -> Double.isFinite(s.medianDeltaCn0DbHz())
                        ? String.format(Locale.US, "%+.1f", s.medianDeltaCn0DbHz()) : "—";
                case 4 -> String.format(Locale.US, "%.0f%%", s.missingFraction() * 100.0);
                case 5 -> String.format(Locale.US, "%.1f", s.slipsPer100Obs());
                case 6 -> s.reason();
                default -> "";
            };
        }
    }
}

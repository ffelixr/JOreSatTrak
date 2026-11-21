package jsattrak.awareness.gnss.gui;

import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.geometry.GnssDopGrid;
import jsattrak.awareness.gnss.geometry.GnssDopMetric;
import jsattrak.awareness.gnss.geometry.GnssDopSolution;
import jsattrak.awareness.gnss.geometry.GnssEphemerisProvider;
import jsattrak.awareness.gnss.geometry.GnssGeometryListener;
import jsattrak.awareness.gnss.geometry.GnssGeometryService;
import jsattrak.awareness.gnss.geometry.GnssObserverPosition;
import jsattrak.awareness.gnss.geometry.GnssOrbitSource;
import jsattrak.objects.GroundStation;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.File;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@code GNSS Geometry} tab inside {@code LiveAwarenessInternalFrame} presenting observer controls,
 * broadcast/SP3 ephemeris selection, DOP KPI strip, Polar Skyplot, 24-hour DOP time-series, and
 * spatial heatmap controls.
 */
public class GnssGeometryTab extends JPanel implements GnssGeometryListener {

    private static final String CUSTOM_COORDS_LABEL = "Custom Coordinates";
    private static final long POINT_THROTTLE_MILLIS = 1000L;

    private final GnssGeometryService service;
    private final Supplier<Collection<GroundStation>> groundStationsSupplier;

    private final JComboBox<Object> observerCombo;
    private final JTextField latField;
    private final JTextField lonField;
    private final JTextField altField;
    private final JToggleButton pickOnMapButton;
    private final JSpinner elevationMaskSpinner;
    private final JCheckBox includeNonOpCheckBox;
    private final JLabel orbitSourceBadge;
    private final JButton loadFileButton;
    private final JButton clearFileButton;

    private final JCheckBox showHeatmapCheckBox;
    private final JComboBox<GnssDopMetric> metricCombo;
    private final JComboBox<String> gridResolutionCombo;
    private final JSlider opacitySlider;
    private final JLabel opacityValueLabel;

    private final JLabel gdopLabel;
    private final JLabel pdopLabel;
    private final JLabel hdopLabel;
    private final JLabel vdopLabel;
    private final JLabel tdopLabel;
    private final JLabel visibleCountLabel;
    private final JLabel constellationBreakdownLabel;

    private final GnssSkyplotPanel skyplotPanel;
    private final GnssDopChartPanel chartPanel;

    private volatile boolean disposed;
    private boolean updatingControls;
    private long lastSimEpochMillis = System.currentTimeMillis();
    private TimeZone lastDisplayTz = TimeZone.getTimeZone("UTC");
    private long lastSolvedEpochMillis = Long.MIN_VALUE;

    private record GroundStationOption(GroundStation station) {
        @Override
        public String toString() {
            return station.getStationName();
        }
    }

    public GnssGeometryTab(
            GnssGeometryService service,
            Supplier<Collection<GroundStation>> groundStationsSupplier,
            Consumer<Long> onJumpToEpoch) {
        super(new BorderLayout(6, 6));
        this.service = Objects.requireNonNull(service, "service");
        this.groundStationsSupplier = groundStationsSupplier;
        setBorder(new EmptyBorder(6, 6, 6, 6));

        JPanel topStack = new JPanel();
        topStack.setLayout(new BoxLayout(topStack, BoxLayout.Y_AXIS));
        topStack.setOpaque(false);

        // ---- Row 1: Observer & Ephemeris Controls ----
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row1.setOpaque(false);
        row1.setAlignmentX(Component.LEFT_ALIGNMENT);

        row1.add(new JLabel("Observer:"));
        observerCombo = new JComboBox<>();
        observerCombo.setPreferredSize(new Dimension(150, 24));
        row1.add(observerCombo);

        GnssObserverPosition initObs = service.getObserver();
        row1.add(new JLabel("Lat:"));
        latField = new JTextField(String.format(Locale.US, "%.4f", initObs.latDeg()), 6);
        row1.add(latField);
        row1.add(new JLabel("Lon:"));
        lonField = new JTextField(String.format(Locale.US, "%.4f", initObs.lonDeg()), 6);
        row1.add(lonField);
        row1.add(new JLabel("Alt(m):"));
        altField = new JTextField(String.format(Locale.US, "%.0f", initObs.altMeters()), 5);
        row1.add(altField);

        pickOnMapButton = new JToggleButton("Pick on Map");
        pickOnMapButton.setToolTipText("Click any 2D, Polar, or 3D map to set the GNSS observer coordinates");
        row1.add(pickOnMapButton);

        row1.add(new JLabel("Elev Mask:"));
        elevationMaskSpinner = new JSpinner(new SpinnerNumberModel(service.getElevationMaskDeg(), 0.0, 45.0, 1.0));
        elevationMaskSpinner.setPreferredSize(new Dimension(62, 24));
        row1.add(elevationMaskSpinner);

        includeNonOpCheckBox = new JCheckBox("Include non-op", service.isIncludeNonOperational());
        includeNonOpCheckBox.setToolTipText("What-if analysis: include UNAVAILABLE / TESTING satellites in DOP");
        row1.add(includeNonOpCheckBox);

        orbitSourceBadge = createPillLabel("BRDC");
        row1.add(orbitSourceBadge);

        loadFileButton = new JButton("Load RINEX/SP3...");
        row1.add(loadFileButton);

        clearFileButton = new JButton("Clear");
        clearFileButton.setVisible(!service.getLoadedLocalFileName().isBlank());
        row1.add(clearFileButton);

        topStack.add(row1);

        // ---- Row 2: Spatial Heatmap Controls ----
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        row2.setOpaque(false);
        row2.setAlignmentX(Component.LEFT_ALIGNMENT);

        showHeatmapCheckBox = new JCheckBox("Show DOP Heatmap on maps", service.isMapOverlayEnabled());
        showHeatmapCheckBox.setToolTipText("Master switch to display global DOP heatmap on 2D, Polar, and 3D maps");
        row2.add(showHeatmapCheckBox);

        row2.add(new JLabel("Metric:"));
        metricCombo = new JComboBox<>(GnssDopMetric.values());
        metricCombo.setSelectedItem(service.getHeatmapMetric());
        row2.add(metricCombo);

        row2.add(new JLabel("Grid:"));
        gridResolutionCombo = new JComboBox<>(new String[]{"5\u00B0 (Fast)", "2.5\u00B0 (Fine)"});
        gridResolutionCombo.setSelectedIndex(service.getHeatmapResolutionDeg() <= 3.0 ? 1 : 0);
        row2.add(gridResolutionCombo);

        row2.add(new JLabel("Opacity:"));
        int initPct = (int) Math.round(service.getHeatmapOpacity() * 100.0);
        opacitySlider = new JSlider(15, 85, Math.max(15, Math.min(85, initPct)));
        opacitySlider.setPreferredSize(new Dimension(100, 22));
        row2.add(opacitySlider);
        opacityValueLabel = new JLabel(opacitySlider.getValue() + "%");
        row2.add(opacityValueLabel);

        topStack.add(row2);

        // ---- Row 3: Live KPI Strip ----
        JPanel kpiStrip = new JPanel(new BorderLayout(8, 2));
        kpiStrip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, borderColor()),
                new EmptyBorder(4, 4, 4, 4)));
        kpiStrip.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel kpiCards = new JPanel(new GridLayout(1, 6, 8, 0));
        kpiCards.setOpaque(false);
        gdopLabel = createKpiCard(kpiCards, "GDOP");
        pdopLabel = createKpiCard(kpiCards, "PDOP");
        hdopLabel = createKpiCard(kpiCards, "HDOP");
        vdopLabel = createKpiCard(kpiCards, "VDOP");
        tdopLabel = createKpiCard(kpiCards, "TDOP");
        visibleCountLabel = createKpiCard(kpiCards, "Visible SVs");
        kpiStrip.add(kpiCards, BorderLayout.CENTER);

        constellationBreakdownLabel = new JLabel(" ");
        constellationBreakdownLabel.setFont(constellationBreakdownLabel.getFont().deriveFont(11f));
        constellationBreakdownLabel.setForeground(mutedForeground());
        kpiStrip.add(constellationBreakdownLabel, BorderLayout.EAST);

        topStack.add(kpiStrip);
        add(topStack, BorderLayout.NORTH);

        // ---- Center Split Pane: Skyplot (Left) + 24h Chart (Right) ----
        JPanel skyContainer = new JPanel(new BorderLayout(2, 2));
        skyContainer.setBorder(BorderFactory.createTitledBorder("Skyplot (Az / El)"));
        skyplotPanel = new GnssSkyplotPanel();
        skyContainer.add(skyplotPanel, BorderLayout.CENTER);

        JPanel chartContainer = new JPanel(new BorderLayout(2, 2));
        chartContainer.setBorder(BorderFactory.createTitledBorder("24h DOP & Visible SVs (Click to Jump Sim Clock)"));
        chartPanel = new GnssDopChartPanel(onJumpToEpoch);
        chartContainer.add(chartPanel, BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, skyContainer, chartContainer);
        splitPane.setResizeWeight(0.42);
        splitPane.setContinuousLayout(true);
        add(splitPane, BorderLayout.CENTER);

        // Wire interactions
        wireInteractions();
        refreshGroundStationList();
        this.service.addListener(this);
    }

    private void wireInteractions() {
        observerCombo.addActionListener(e -> {
            if (updatingControls) {
                return;
            }
            Object sel = observerCombo.getSelectedItem();
            if (sel instanceof GroundStationOption opt) {
                GroundStation gs = opt.station();
                GnssObserverPosition pos = new GnssObserverPosition(
                        gs.getStationName(), gs.getLatitude(), gs.getLongitude(), gs.getAltitude());
                service.setObserver(pos);
            }
        });

        ActionListener commitFields = e -> commitCustomObserverFields();
        FocusAdapter focusCommit = new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                commitCustomObserverFields();
            }
        };
        latField.addActionListener(commitFields);
        lonField.addActionListener(commitFields);
        altField.addActionListener(commitFields);
        latField.addFocusListener(focusCommit);
        lonField.addFocusListener(focusCommit);
        altField.addFocusListener(focusCommit);

        pickOnMapButton.addActionListener(e -> {
            if (!updatingControls) {
                service.setPickOnMapMode(pickOnMapButton.isSelected());
            }
        });

        elevationMaskSpinner.addChangeListener(e -> {
            if (!updatingControls && elevationMaskSpinner.getValue() instanceof Number n) {
                service.setElevationMaskDeg(n.doubleValue());
                forceRecomputeNow();
            }
        });

        includeNonOpCheckBox.addActionListener(e -> {
            if (!updatingControls) {
                service.setIncludeNonOperational(includeNonOpCheckBox.isSelected());
                forceRecomputeNow();
            }
        });

        showHeatmapCheckBox.addActionListener(e -> {
            if (!updatingControls) {
                service.setMapOverlayEnabled(showHeatmapCheckBox.isSelected());
                if (showHeatmapCheckBox.isSelected()) {
                    service.requestHeatmapAsync(lastSimEpochMillis);
                }
            }
        });

        metricCombo.addActionListener(e -> {
            if (!updatingControls && metricCombo.getSelectedItem() instanceof GnssDopMetric m) {
                service.setHeatmapMetric(m);
                if (service.isMapOverlayEnabled()) {
                    service.requestHeatmapAsync(lastSimEpochMillis);
                }
            }
        });

        gridResolutionCombo.addActionListener(e -> {
            if (!updatingControls) {
                double res = gridResolutionCombo.getSelectedIndex() == 1 ? 2.5 : 5.0;
                service.setHeatmapResolutionDeg(res);
                if (service.isMapOverlayEnabled()) {
                    service.requestHeatmapAsync(lastSimEpochMillis);
                }
            }
        });

        opacitySlider.addChangeListener(e -> {
            opacityValueLabel.setText(opacitySlider.getValue() + "%");
            if (!updatingControls && !opacitySlider.getValueIsAdjusting()) {
                service.setHeatmapOpacity(opacitySlider.getValue() / 100.0);
            }
        });

        loadFileButton.addActionListener(e -> chooseAndLoadLocalFile());
        clearFileButton.addActionListener(e -> service.clearLocalEphemerisFile());
    }

    private void commitCustomObserverFields() {
        if (updatingControls) {
            return;
        }
        try {
            double lat = Double.parseDouble(latField.getText().trim());
            double lon = Double.parseDouble(lonField.getText().trim());
            double alt = Double.parseDouble(altField.getText().trim());
            updatingControls = true;
            observerCombo.setSelectedItem(CUSTOM_COORDS_LABEL);
            updatingControls = false;
            service.setObserver(new GnssObserverPosition(CUSTOM_COORDS_LABEL, lat, lon, alt));
        } catch (NumberFormatException ignored) {
            updatingControls = false;
        }
    }

    private void chooseAndLoadLocalFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select RINEX Navigation or SP3 Orbit File");
        chooser.setFileFilter(new FileNameExtensionFilter(
                "GNSS Ephemeris (*.rnx, *.rnx.gz, *.sp3, *.sp3.gz, *.eph, *.n, *.p)",
                "rnx", "gz", "sp3", "eph", "n", "p"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File selected = chooser.getSelectedFile();
        if (selected == null) {
            return;
        }
        loadFileButton.setEnabled(false);
        new SwingWorker<GnssEphemerisProvider.LocalLoadSummary, Void>() {
            @Override
            protected GnssEphemerisProvider.LocalLoadSummary doInBackground() throws Exception {
                return service.loadLocalEphemerisFile(selected.toPath());
            }

            @Override
            protected void done() {
                if (disposed) {
                    return;
                }
                loadFileButton.setEnabled(true);
                try {
                    get();
                    clearFileButton.setVisible(!service.getLoadedLocalFileName().isBlank());
                    forceRecomputeNow();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    JOptionPane.showMessageDialog(
                            GnssGeometryTab.this,
                            "Could not load ephemeris file:\n" + cause.getMessage(),
                            "Invalid Ephemeris File",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    public void refreshGroundStationList() {
        updatingControls = true;
        try {
            Object prev = observerCombo.getSelectedItem();
            observerCombo.removeAllItems();
            observerCombo.addItem(CUSTOM_COORDS_LABEL);
            if (groundStationsSupplier != null) {
                Collection<GroundStation> stations = groundStationsSupplier.get();
                if (stations != null) {
                    for (GroundStation gs : stations) {
                        if (gs != null && gs.getStationName() != null) {
                            observerCombo.addItem(new GroundStationOption(gs));
                        }
                    }
                }
            }
            if (prev instanceof GroundStationOption opt) {
                for (int i = 1; i < observerCombo.getItemCount(); i++) {
                    Object item = observerCombo.getItemAt(i);
                    if (item instanceof GroundStationOption cand
                            && opt.station().getStationName().equals(cand.station().getStationName())) {
                        observerCombo.setSelectedIndex(i);
                        return;
                    }
                }
            }
            observerCombo.setSelectedItem(CUSTOM_COORDS_LABEL);
        } finally {
            updatingControls = false;
        }
    }

    /**
     * Updates point DOP KPIs, skyplot, 24h chart cursor, and background requests for the active simulation clock.
     */
    public void refreshDisplay(long simEpochMillis, boolean realTimeMode, TimeZone displayTz) {
        this.lastSimEpochMillis = simEpochMillis;
        this.lastDisplayTz = displayTz != null ? displayTz : TimeZone.getTimeZone("UTC");

        chartPanel.setSimEpochMillis(simEpochMillis, this.lastDisplayTz);
        service.request24hSeriesAsync(simEpochMillis);
        chartPanel.setSeries(service.getLatest24hSeries(), simEpochMillis, this.lastDisplayTz);

        if (this.lastSolvedEpochMillis == Long.MIN_VALUE
                || Math.abs(simEpochMillis - this.lastSolvedEpochMillis) >= POINT_THROTTLE_MILLIS) {
            forceRecomputeNow();
        }
        if (service.isMapOverlayEnabled()) {
            service.requestHeatmapAsync(simEpochMillis);
        }
    }

    private void forceRecomputeNow() {
        this.lastSolvedEpochMillis = this.lastSimEpochMillis;
        GnssDopSolution sol = service.computePointSolution(this.lastSimEpochMillis);
        applySolutionToUi(sol);
        service.request24hSeriesAsync(this.lastSimEpochMillis);
        chartPanel.setSeries(service.getLatest24hSeries(), this.lastSimEpochMillis, this.lastDisplayTz);
    }

    private void applySolutionToUi(GnssDopSolution sol) {
        skyplotPanel.setSolution(sol);
        if (sol == null || !sol.fixAvailable()) {
            String noFix = "No Fix";
            Color mag = GnssDopGrid.colorForDop(Double.NaN, 255);
            setKpiText(gdopLabel, noFix, mag);
            setKpiText(pdopLabel, noFix, mag);
            setKpiText(hdopLabel, noFix, mag);
            setKpiText(vdopLabel, noFix, mag);
            setKpiText(tdopLabel, noFix, mag);
        } else {
            setKpiText(gdopLabel, formatDop(sol.gdop()), GnssDopGrid.colorForDop(sol.gdop(), 255));
            setKpiText(pdopLabel, formatDop(sol.pdop()), GnssDopGrid.colorForDop(sol.pdop(), 255));
            setKpiText(hdopLabel, formatDop(sol.hdop()), GnssDopGrid.colorForDop(sol.hdop(), 255));
            setKpiText(vdopLabel, formatDop(sol.vdop()), GnssDopGrid.colorForDop(sol.vdop(), 255));
            setKpiText(tdopLabel, formatDop(sol.tdop()), GnssDopGrid.colorForDop(sol.tdop(), 255));
        }

        int vis = sol != null ? sol.visibleCount() : 0;
        visibleCountLabel.setText(String.valueOf(vis));
        visibleCountLabel.setForeground(GnssDopGrid.colorForVisibleCount(vis, 255));

        if (sol != null) {
            var map = sol.visibleByConstellation();
            constellationBreakdownLabel.setText(String.format(Locale.US,
                    "\u25CF GPS:%d  \u25C6 GAL:%d  \u25B2 GLO:%d  \u25A0 BDS:%d",
                    map.getOrDefault(GnssConstellation.GPS, 0),
                    map.getOrDefault(GnssConstellation.GALILEO, 0),
                    map.getOrDefault(GnssConstellation.GLONASS, 0),
                    map.getOrDefault(GnssConstellation.BEIDOU, 0)));

            GnssOrbitSource.Summary os = sol.orbitSource();
            if (os != null) {
                orbitSourceBadge.setText(os.getBadgeText());
                orbitSourceBadge.setToolTipText(os.getTooltipText());
                Color badgeColor = switch (os.primary()) {
                    case SP3, BRDC -> new Color(0x2E, 0x7D, 0x32);
                    case MIXED, TLE_FALLBACK -> new Color(0xED, 0x6C, 0x02);
                    case NONE -> mutedForeground();
                };
                orbitSourceBadge.setForeground(badgeColor);
            }
        }
        clearFileButton.setVisible(!service.getLoadedLocalFileName().isBlank());
    }

    private void syncControlsFromService() {
        updatingControls = true;
        try {
            GnssObserverPosition obs = service.getObserver();
            if (obs != null) {
                latField.setText(String.format(Locale.US, "%.4f", obs.latDeg()));
                lonField.setText(String.format(Locale.US, "%.4f", obs.lonDeg()));
                altField.setText(String.format(Locale.US, "%.0f", obs.altMeters()));
            }
            pickOnMapButton.setSelected(service.isPickOnMapMode());
            showHeatmapCheckBox.setSelected(service.isMapOverlayEnabled());
            elevationMaskSpinner.setValue(service.getElevationMaskDeg());
            includeNonOpCheckBox.setSelected(service.isIncludeNonOperational());
            clearFileButton.setVisible(!service.getLoadedLocalFileName().isBlank());
        } finally {
            updatingControls = false;
        }
    }

    public void dispose() {
        this.disposed = true;
        this.service.removeListener(this);
    }

    // ---- GnssGeometryListener ----

    @Override
    public void onGeometryDataChanged() {
        runOnEdt(() -> {
            syncControlsFromService();
            forceRecomputeNow();
        });
    }

    @Override
    public void onObserverChanged(GnssObserverPosition observer) {
        runOnEdt(() -> {
            syncControlsFromService();
            forceRecomputeNow();
        });
    }

    @Override
    public void onPickOnMapModeChanged(boolean pickMode) {
        runOnEdt(() -> {
            updatingControls = true;
            pickOnMapButton.setSelected(pickMode);
            updatingControls = false;
        });
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

    // ---- Test Accessors ----

    public JComboBox<Object> getObserverCombo() {
        return observerCombo;
    }

    public JSpinner getElevationMaskSpinner() {
        return elevationMaskSpinner;
    }

    public JToggleButton getPickOnMapButton() {
        return pickOnMapButton;
    }

    public JCheckBox getShowHeatmapCheckBox() {
        return showHeatmapCheckBox;
    }

    public JLabel getOrbitSourceBadge() {
        return orbitSourceBadge;
    }

    public JLabel getGdopLabel() {
        return gdopLabel;
    }

    public JLabel getPdopLabel() {
        return pdopLabel;
    }

    public JLabel getVisibleCountLabel() {
        return visibleCountLabel;
    }

    public GnssSkyplotPanel getSkyplotPanel() {
        return skyplotPanel;
    }

    public GnssDopChartPanel getChartPanel() {
        return chartPanel;
    }

    // ---- Helpers ----

    private static JLabel createKpiCard(JPanel parent, String title) {
        JPanel card = new JPanel(new BorderLayout(0, 1));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor()),
                new EmptyBorder(3, 6, 3, 6)));
        JLabel titleLbl = new JLabel(title);
        titleLbl.setFont(titleLbl.getFont().deriveFont(10f));
        titleLbl.setForeground(mutedForeground());
        JLabel valLbl = new JLabel("--");
        valLbl.setFont(valLbl.getFont().deriveFont(Font.BOLD, 13f));
        card.add(titleLbl, BorderLayout.NORTH);
        card.add(valLbl, BorderLayout.CENTER);
        parent.add(card);
        return valLbl;
    }

    private static JLabel createPillLabel(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 11f));
        lbl.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor()),
                new EmptyBorder(2, 6, 2, 6)));
        return lbl;
    }

    private static void setKpiText(JLabel lbl, String txt, Color color) {
        lbl.setText(txt);
        lbl.setForeground(color);
    }

    private static String formatDop(double val) {
        return Double.isFinite(val) ? String.format(Locale.US, "%.2f", val) : "No Fix";
    }

    private static Color borderColor() {
        Color c = UIManager.getColor("Component.borderColor");
        return c != null ? c : new Color(128, 128, 128, 90);
    }

    private static Color mutedForeground() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : new Color(128, 128, 128);
    }
}

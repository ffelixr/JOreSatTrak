package jsattrak.awareness.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import jsattrak.awareness.LiveAwarenessService;
import jsattrak.awareness.config.AuthMode;
import jsattrak.awareness.config.AwarenessSettings;
import jsattrak.awareness.config.DataSourceConfig;
import jsattrak.awareness.gnss.GnssAwarenessService;
import jsattrak.awareness.gnss.net.BroadcastNavClient;
import jsattrak.awareness.gnss.net.GnssCatalogClient;
import jsattrak.awareness.gnss.net.OperatorStatusClient;
import jsattrak.awareness.net.ConnectionTestResult;
import jsattrak.awareness.net.LaunchApiClient;
import jsattrak.awareness.tle.TleFeedClient;

/**
 * Configuration dialog for Launch and New-Launch TLE data sources, authentication credentials,
 * failover priority ordering, refresh interval, companion GS sync, and non-blocking connectivity testing.
 */
public class LiveDataSourcesDialog extends JDialog {

    private final DataSourcesEditorPanel editorPanel;
    private boolean saved;

    public LiveDataSourcesDialog(Window owner, LiveAwarenessService service) {
        this(owner, service, null, new LaunchApiClient(), new TleFeedClient(),
                new GnssCatalogClient(), new BroadcastNavClient(), new OperatorStatusClient());
    }

    public LiveDataSourcesDialog(Window owner, LiveAwarenessService service, GnssAwarenessService gnssService) {
        this(owner, service, gnssService, new LaunchApiClient(), new TleFeedClient(),
                new GnssCatalogClient(), new BroadcastNavClient(), new OperatorStatusClient());
    }

    public LiveDataSourcesDialog(
            Window owner,
            LiveAwarenessService service,
            LaunchApiClient launchApiClient,
            TleFeedClient tleFeedClient) {
        this(owner, service, null, launchApiClient, tleFeedClient,
                new GnssCatalogClient(), new BroadcastNavClient(), new OperatorStatusClient());
    }

    public LiveDataSourcesDialog(
            Window owner,
            LiveAwarenessService service,
            GnssAwarenessService gnssService,
            LaunchApiClient launchApiClient,
            TleFeedClient tleFeedClient,
            GnssCatalogClient gnssCatalogClient,
            BroadcastNavClient broadcastNavClient,
            OperatorStatusClient operatorStatusClient) {
        super(owner, "Live Data Sources", ModalityType.APPLICATION_MODAL);
        this.editorPanel = new DataSourcesEditorPanel(
                service,
                gnssService,
                launchApiClient,
                tleFeedClient,
                gnssCatalogClient,
                broadcastNavClient,
                operatorStatusClient,
                this::handleEditorClose);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                editorPanel.cancelActiveTest();
            }
        });
        setContentPane(editorPanel);
        getRootPane().setDefaultButton(editorPanel.getSaveButton());
        getRootPane().registerKeyboardAction(
                e -> {
                    editorPanel.cancelActiveTest();
                    dispose();
                },
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        pack();
        setMinimumSize(new Dimension(780, 480));
        setLocationRelativeTo(owner);
    }

    public boolean isSaved() {
        return saved;
    }

    public DataSourcesEditorPanel getEditorPanel() {
        return editorPanel;
    }

    private void handleEditorClose() {
        this.saved = editorPanel != null && editorPanel.isSaved();
        dispose();
    }

    /**
     * Selectable refresh cadence option in {@link DataSourcesEditorPanel}.
     */
    public record RefreshIntervalOption(int minutes, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * Reusable headless-safe editor panel powering {@link LiveDataSourcesDialog}.
     */
    public static class DataSourcesEditorPanel extends JPanel {

        private static final RefreshIntervalOption[] STANDARD_INTERVALS = {
                new RefreshIntervalOption(5, "5 minutes"),
                new RefreshIntervalOption(15, "15 minutes"),
                new RefreshIntervalOption(30, "30 minutes"),
                new RefreshIntervalOption(60, "60 minutes"),
                new RefreshIntervalOption(1440, "24 hours"),
                new RefreshIntervalOption(0, "Manual only")
        };

        private static final DataSourceConfig.FeedCategory[] TAB_CATEGORIES = DataSourceConfig.FeedCategory.values();

        private final LiveAwarenessService service;
        private final GnssAwarenessService gnssService;
        private final LaunchApiClient launchApiClient;
        private final TleFeedClient tleFeedClient;
        private final GnssCatalogClient gnssCatalogClient;
        private final BroadcastNavClient broadcastNavClient;
        private final OperatorStatusClient operatorStatusClient;
        private final Runnable onClose;
        private final int preservedDefaultHorizonDays;
        private final boolean preservedGnssShowOrbitRings3d;
        private final Map<String, Boolean> preservedGnssConstellationEnabled;
        private final Map<String, Boolean> preservedGnssStatusFilter;

        private final JCheckBox autoFetchCheckBox;
        private final JCheckBox gnssFeedEnabledCheckBox;
        private final JTabbedPane categoryTabs;
        private final EnumMap<DataSourceConfig.FeedCategory, DefaultListModel<DataSourceConfig>> categoryModels =
                new EnumMap<>(DataSourceConfig.FeedCategory.class);
        private final EnumMap<DataSourceConfig.FeedCategory, JList<DataSourceConfig>> categoryLists =
                new EnumMap<>(DataSourceConfig.FeedCategory.class);
        private final DefaultListModel<DataSourceConfig> launchSourcesModel;
        private final DefaultListModel<DataSourceConfig> tleSourcesModel;
        private final JList<DataSourceConfig> launchSourcesList;
        private final JList<DataSourceConfig> tleSourcesList;

        private final JButton addSourceButton;
        private final JButton removeSourceButton;
        private final JButton moveUpButton;
        private final JButton moveDownButton;

        private final JCheckBox enabledCheckBox;
        private final JTextField displayNameField;
        private final JTextField endpointUrlField;
        private final JComboBox<AuthMode> authModeCombo;
        private final JLabel authKeyNameLabel;
        private final JTextField authKeyNameField;
        private final JLabel authUsernameLabel;
        private final JTextField authUsernameField;
        private final JLabel authSecretLabel;
        private final JPasswordField authSecretField;
        private final JCheckBox showSecretCheckBox;
        private final char defaultEchoChar;
        private final JComboBox<RefreshIntervalOption> refreshIntervalCombo;
        private final JCheckBox companionGsSyncCheckBox;
        private final JLabel providerScopeLabel;
        private final JComboBox<String> providerScopeCombo;

        private final JButton testConnectionButton;
        private final JLabel testStatusLabel;
        private final JButton resetDefaultsButton;
        private final JButton cancelButton;
        private final JButton saveButton;

        private boolean loadingEditor;
        private boolean saved;
        private boolean testInProgress;
        private SwingWorker<ConnectionTestResult, Void> activeWorker;
        private int customIdCounter = 1;

        public DataSourcesEditorPanel(
                LiveAwarenessService service,
                LaunchApiClient launchApiClient,
                TleFeedClient tleFeedClient,
                Runnable onClose) {
            this(service, null, launchApiClient, tleFeedClient,
                    new GnssCatalogClient(), new BroadcastNavClient(), new OperatorStatusClient(), onClose);
        }

        public DataSourcesEditorPanel(
                LiveAwarenessService service,
                GnssAwarenessService gnssService,
                LaunchApiClient launchApiClient,
                TleFeedClient tleFeedClient,
                GnssCatalogClient gnssCatalogClient,
                BroadcastNavClient broadcastNavClient,
                OperatorStatusClient operatorStatusClient,
                Runnable onClose) {
            super(new BorderLayout(10, 10));
            this.service = Objects.requireNonNull(service, "service");
            this.gnssService = gnssService;
            this.launchApiClient = launchApiClient != null ? launchApiClient : new LaunchApiClient();
            this.tleFeedClient = tleFeedClient != null ? tleFeedClient : new TleFeedClient();
            this.gnssCatalogClient = gnssCatalogClient != null ? gnssCatalogClient : new GnssCatalogClient();
            this.broadcastNavClient = broadcastNavClient != null ? broadcastNavClient : new BroadcastNavClient();
            this.operatorStatusClient = operatorStatusClient != null ? operatorStatusClient : new OperatorStatusClient();
            this.onClose = onClose;

            setBorder(new EmptyBorder(12, 12, 12, 12));

            AwarenessSettings initial = service.getSettings();
            if (initial == null) {
                initial = AwarenessSettings.createDefaults();
            }
            AwarenessSettings gnssInitial = (gnssService != null && gnssService.getSettings() != null)
                    ? gnssService.getSettings()
                    : initial;
            this.preservedDefaultHorizonDays = initial.getDefaultHorizonDays();
            this.preservedGnssShowOrbitRings3d = gnssInitial.isGnssShowOrbitRings3d();
            this.preservedGnssConstellationEnabled = new java.util.LinkedHashMap<>(gnssInitial.getGnssConstellationEnabled());
            this.preservedGnssStatusFilter = new java.util.LinkedHashMap<>(gnssInitial.getGnssStatusFilter());

            // Top: global startup + GNSS feed preferences
            JPanel topPanel = new JPanel(new BorderLayout(12, 4));
            topPanel.setOpaque(false);
            autoFetchCheckBox = new JCheckBox(
                    "Auto-fetch launch & TLE feeds on application startup",
                    initial.isAutoFetchOnStartup());
            autoFetchCheckBox.setToolTipText(
                    "When disabled, JOreSatTrak makes no outbound API calls until Live Awareness is opened or Refresh is clicked");
            gnssFeedEnabledCheckBox = new JCheckBox(
                    "Enable GNSS Awareness feed",
                    gnssInitial.isGnssFeedEnabled());
            gnssFeedEnabledCheckBox.setToolTipText(
                    "When disabled, the GNSS background feed will not fetch external GNSS catalogs, orbits, or status");
            topPanel.add(autoFetchCheckBox, BorderLayout.WEST);
            topPanel.add(gnssFeedEnabledCheckBox, BorderLayout.EAST);
            add(topPanel, BorderLayout.NORTH);

            // Left: categorized source lists + priority controls
            categoryTabs = new JTabbedPane();
            for (DataSourceConfig.FeedCategory cat : TAB_CATEGORIES) {
                DefaultListModel<DataSourceConfig> model = new DefaultListModel<>();
                JList<DataSourceConfig> list = createSourceList(model);
                list.addListSelectionListener(e -> {
                    if (!e.getValueIsAdjusting() && activeCategory() == cat) {
                        clearTestStatus();
                        loadSelectedSourceIntoEditor();
                        updateListActionButtons();
                    }
                });
                categoryModels.put(cat, model);
                categoryLists.put(cat, list);
                categoryTabs.addTab(tabTitleFor(cat), new JScrollPane(list));
            }
            this.launchSourcesModel = categoryModels.get(DataSourceConfig.FeedCategory.LAUNCH);
            this.tleSourcesModel = categoryModels.get(DataSourceConfig.FeedCategory.TLE);
            this.launchSourcesList = categoryLists.get(DataSourceConfig.FeedCategory.LAUNCH);
            this.tleSourcesList = categoryLists.get(DataSourceConfig.FeedCategory.TLE);

            populateModelsFromSettings(initial);

            JPanel listActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
            listActions.setOpaque(false);
            addSourceButton = new JButton("Add");
            removeSourceButton = new JButton("Remove");
            moveUpButton = new JButton("Up");
            moveDownButton = new JButton("Down");
            listActions.add(addSourceButton);
            listActions.add(removeSourceButton);
            listActions.add(moveUpButton);
            listActions.add(moveDownButton);

            JPanel leftPanel = new JPanel(new BorderLayout(0, 4));
            leftPanel.setMinimumSize(new Dimension(260, 280));
            leftPanel.setPreferredSize(new Dimension(280, 340));
            leftPanel.add(categoryTabs, BorderLayout.CENTER);
            leftPanel.add(listActions, BorderLayout.SOUTH);

            // Right: selected source details editor
            JPanel editorContainer = new JPanel(new BorderLayout(8, 8));
            editorContainer.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createTitledBorder("Source Configuration"),
                    new EmptyBorder(6, 8, 6, 8)));

            JPanel form = new JPanel(new GridBagLayout());
            form.setOpaque(false);

            enabledCheckBox = new JCheckBox("Enabled", true);
            displayNameField = new JTextField(22);
            endpointUrlField = new JTextField(28);
            authModeCombo = new JComboBox<>(AuthMode.values());
            authModeCombo.setRenderer(new AuthModeRenderer());
            authKeyNameLabel = new JLabel("Header / Param:");
            authKeyNameField = new JTextField(18);
            authUsernameLabel = new JLabel("Username:");
            authUsernameField = new JTextField(18);
            authSecretLabel = new JLabel("Secret / Token:");
            authSecretField = new JPasswordField(18);
            this.defaultEchoChar = authSecretField.getEchoChar() != 0 ? authSecretField.getEchoChar() : '\u2022';
            showSecretCheckBox = new JCheckBox("Show");
            refreshIntervalCombo = new JComboBox<>(STANDARD_INTERVALS);
            companionGsSyncCheckBox = new JCheckBox("Sync fixed launch pads to Ground Station catalog");
            providerScopeLabel = new JLabel("Operator Scope:");
            providerScopeCombo = new JComboBox<>(new String[] {
                    "", "GPS_STATUS", "GPS_NANU", "GALILEO_STATUS", "GALILEO_NAGU"
            });
            providerScopeCombo.setEditable(true);

            int row = 0;
            addFormRow(form, row++, new JLabel("Status:"), enabledCheckBox);
            addFormRow(form, row++, new JLabel("Display Name:"), displayNameField);
            addFormRow(form, row++, new JLabel("Endpoint URL:"), endpointUrlField);
            addFormRow(form, row++, providerScopeLabel, providerScopeCombo);
            addFormRow(form, row++, new JLabel("Authentication:"), authModeCombo);
            addFormRow(form, row++, authKeyNameLabel, authKeyNameField);
            addFormRow(form, row++, authUsernameLabel, authUsernameField);

            JPanel secretPanel = new JPanel(new BorderLayout(6, 0));
            secretPanel.setOpaque(false);
            secretPanel.add(authSecretField, BorderLayout.CENTER);
            secretPanel.add(showSecretCheckBox, BorderLayout.EAST);
            addFormRow(form, row++, authSecretLabel, secretPanel);

            addFormRow(form, row++, new JLabel("Refresh Interval:"), refreshIntervalCombo);

            GridBagConstraints gsGbc = new GridBagConstraints();
            gsGbc.gridx = 0;
            gsGbc.gridy = row;
            gsGbc.gridwidth = 2;
            gsGbc.anchor = GridBagConstraints.WEST;
            gsGbc.insets = new Insets(6, 2, 2, 2);
            form.add(companionGsSyncCheckBox, gsGbc);

            editorContainer.add(form, BorderLayout.NORTH);

            // Test Connection inline strip
            JPanel testPanel = new JPanel(new BorderLayout(8, 0));
            testPanel.setOpaque(false);
            testPanel.setBorder(new EmptyBorder(6, 0, 0, 0));
            testConnectionButton = new JButton("Test Connection");
            testStatusLabel = new JLabel(" ");
            testStatusLabel.setForeground(mutedForeground());
            testPanel.add(testConnectionButton, BorderLayout.WEST);
            testPanel.add(testStatusLabel, BorderLayout.CENTER);
            editorContainer.add(testPanel, BorderLayout.SOUTH);

            JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, editorContainer);
            splitPane.setResizeWeight(0.36);
            splitPane.setContinuousLayout(true);
            add(splitPane, BorderLayout.CENTER);

            // Bottom dialog buttons
            JPanel footer = new JPanel(new BorderLayout());
            footer.setOpaque(false);
            resetDefaultsButton = new JButton("Reset Defaults");
            JPanel leftFooter = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            leftFooter.setOpaque(false);
            leftFooter.add(resetDefaultsButton);

            JPanel rightFooter = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            rightFooter.setOpaque(false);
            cancelButton = new JButton("Cancel");
            saveButton = new JButton("Save");
            rightFooter.add(cancelButton);
            rightFooter.add(saveButton);

            footer.add(leftFooter, BorderLayout.WEST);
            footer.add(rightFooter, BorderLayout.EAST);
            add(footer, BorderLayout.SOUTH);

            // Wire interactions
            categoryTabs.addChangeListener(e -> {
                clearTestStatus();
                loadSelectedSourceIntoEditor();
                updateListActionButtons();
            });

            addSourceButton.addActionListener(e -> addNewSource());
            removeSourceButton.addActionListener(e -> removeSelectedSource());
            moveUpButton.addActionListener(e -> moveSelectedSource(-1));
            moveDownButton.addActionListener(e -> moveSelectedSource(1));

            showSecretCheckBox.addActionListener(e -> updateSecretMasking());
            testConnectionButton.addActionListener(e -> runConnectionTest());
            resetDefaultsButton.addActionListener(e -> resetToDefaults());
            cancelButton.addActionListener(e -> {
                cancelActiveTest();
                this.saved = false;
                if (this.onClose != null) {
                    this.onClose.run();
                }
            });
            saveButton.addActionListener(e -> saveAndClose());

            attachEditorSyncListeners();

            for (DataSourceConfig.FeedCategory cat : TAB_CATEGORIES) {
                DefaultListModel<DataSourceConfig> model = categoryModels.get(cat);
                JList<DataSourceConfig> list = categoryLists.get(cat);
                if (model != null && list != null && !model.isEmpty()) {
                    list.setSelectedIndex(0);
                }
            }
            loadSelectedSourceIntoEditor();
            updateListActionButtons();
        }

        public AwarenessSettings getWorkingSettingsSnapshot() {
            AwarenessSettings settings = new AwarenessSettings();
            settings.setSchemaVersion(AwarenessSettings.CURRENT_SCHEMA_VERSION);
            settings.setAutoFetchOnStartup(autoFetchCheckBox.isSelected());
            settings.setGnssFeedEnabled(gnssFeedEnabledCheckBox.isSelected());
            settings.setDefaultHorizonDays(preservedDefaultHorizonDays);
            AwarenessSettings liveGnss = (gnssService != null && gnssService.getSettings() != null)
                    ? gnssService.getSettings()
                    : null;
            settings.setGnssShowOrbitRings3d(liveGnss != null
                    ? liveGnss.isGnssShowOrbitRings3d()
                    : preservedGnssShowOrbitRings3d);
            settings.setGnssConstellationEnabled(liveGnss != null
                    ? liveGnss.getGnssConstellationEnabled()
                    : preservedGnssConstellationEnabled);
            settings.setGnssStatusFilter(liveGnss != null
                    ? liveGnss.getGnssStatusFilter()
                    : preservedGnssStatusFilter);

            List<DataSourceConfig> combined = new ArrayList<>();
            for (DataSourceConfig.FeedCategory cat : TAB_CATEGORIES) {
                DefaultListModel<DataSourceConfig> model = categoryModels.get(cat);
                if (model == null) {
                    continue;
                }
                renormalizePriorities(model);
                for (int i = 0; i < model.size(); i++) {
                    combined.add(copySource(model.get(i)));
                }
            }
            settings.setSources(combined);
            return settings;
        }

        public JCheckBox getGnssFeedEnabledCheckBox() {
            return gnssFeedEnabledCheckBox;
        }

        public JComboBox<String> getProviderScopeCombo() {
            return providerScopeCombo;
        }

        public void setGnssFeedEnabledForTest(boolean enabled) {
            gnssFeedEnabledCheckBox.setSelected(enabled);
        }

        public void updateSourceUrlById(String id, String newUrl) {
            if (id == null || id.isBlank()) {
                return;
            }
            for (DataSourceConfig.FeedCategory cat : TAB_CATEGORIES) {
                DefaultListModel<DataSourceConfig> model = categoryModels.get(cat);
                if (model == null) {
                    continue;
                }
                for (int i = 0; i < model.size(); i++) {
                    DataSourceConfig item = model.get(i);
                    if (id.equals(item.getId())) {
                        item.setEndpointUrl(newUrl != null ? newUrl.trim() : "");
                        if (getSelectedSource() == item) {
                            endpointUrlField.setText(item.getEndpointUrl());
                        }
                        return;
                    }
                }
            }
        }

        public void save() {
            saveAndClose();
        }

        public void selectRefreshIntervalMinutes(int minutes) {
            DefaultComboBoxModel<RefreshIntervalOption> model =
                    (DefaultComboBoxModel<RefreshIntervalOption>) refreshIntervalCombo.getModel();
            for (int i = 0; i < model.getSize(); i++) {
                RefreshIntervalOption opt = model.getElementAt(i);
                if (opt.minutes() == minutes) {
                    refreshIntervalCombo.setSelectedIndex(i);
                    return;
                }
            }
            RefreshIntervalOption custom = new RefreshIntervalOption(minutes, minutes + " minutes");
            model.addElement(custom);
            refreshIntervalCombo.setSelectedItem(custom);
        }

        public boolean isSaved() {
            return saved;
        }

        public boolean isTestInProgress() {
            return testInProgress;
        }

        public void cancelActiveTest() {
            if (activeWorker != null && !activeWorker.isDone()) {
                activeWorker.cancel(true);
            }
            testInProgress = false;
        }

        public JCheckBox getAutoFetchCheckBox() {
            return autoFetchCheckBox;
        }

        public JTabbedPane getCategoryTabs() {
            return categoryTabs;
        }

        public JList<DataSourceConfig> getLaunchSourcesList() {
            return launchSourcesList;
        }

        public JList<DataSourceConfig> getTleSourcesList() {
            return tleSourcesList;
        }

        public JButton getAddSourceButton() {
            return addSourceButton;
        }

        public JButton getRemoveSourceButton() {
            return removeSourceButton;
        }

        public JButton getMoveUpButton() {
            return moveUpButton;
        }

        public JButton getMoveDownButton() {
            return moveDownButton;
        }

        public JCheckBox getEnabledCheckBox() {
            return enabledCheckBox;
        }

        public JTextField getDisplayNameField() {
            return displayNameField;
        }

        public JTextField getEndpointUrlField() {
            return endpointUrlField;
        }

        public JComboBox<AuthMode> getAuthModeCombo() {
            return authModeCombo;
        }

        public JTextField getAuthKeyNameField() {
            return authKeyNameField;
        }

        public JTextField getAuthUsernameField() {
            return authUsernameField;
        }

        public JPasswordField getAuthSecretField() {
            return authSecretField;
        }

        public JCheckBox getShowSecretCheckBox() {
            return showSecretCheckBox;
        }

        public JComboBox<RefreshIntervalOption> getRefreshIntervalCombo() {
            return refreshIntervalCombo;
        }

        public JCheckBox getCompanionGsSyncCheckBox() {
            return companionGsSyncCheckBox;
        }

        public JButton getTestConnectionButton() {
            return testConnectionButton;
        }

        public JLabel getTestStatusLabel() {
            return testStatusLabel;
        }

        public JButton getResetDefaultsButton() {
            return resetDefaultsButton;
        }

        public JButton getCancelButton() {
            return cancelButton;
        }

        public JButton getSaveButton() {
            return saveButton;
        }

        private static String tabTitleFor(DataSourceConfig.FeedCategory category) {
            return switch (category) {
                case LAUNCH -> "Launch Sources";
                case TLE -> "New-Launch TLE Sources";
                case GNSS_CATALOG -> "GNSS Catalog (IGS)";
                case GNSS_ORBITS_TLE -> "GNSS TLE Orbits";
                case GNSS_BROADCAST_NAV -> "GNSS Broadcast Nav (BKG)";
                case GNSS_OPERATOR_STATUS -> "GNSS Operator Status";
            };
        }

        private void populateModelsFromSettings(AwarenessSettings settings) {
            for (DefaultListModel<DataSourceConfig> model : categoryModels.values()) {
                model.clear();
            }
            if (settings == null || settings.getSources() == null) {
                return;
            }
            EnumMap<DataSourceConfig.FeedCategory, List<DataSourceConfig>> grouped =
                    new EnumMap<>(DataSourceConfig.FeedCategory.class);
            for (DataSourceConfig.FeedCategory cat : TAB_CATEGORIES) {
                grouped.put(cat, new ArrayList<>());
            }
            for (DataSourceConfig src : settings.getSources()) {
                if (src == null) {
                    continue;
                }
                DataSourceConfig copy = copySource(src);
                DataSourceConfig.FeedCategory cat = copy.getFeedCategory() != null
                        ? copy.getFeedCategory()
                        : DataSourceConfig.FeedCategory.LAUNCH;
                copy.setFeedCategory(cat);
                grouped.get(cat).add(copy);
            }
            for (DataSourceConfig.FeedCategory cat : TAB_CATEGORIES) {
                List<DataSourceConfig> items = grouped.get(cat);
                items.sort(Comparator.comparingInt(DataSourceConfig::getPriority));
                DefaultListModel<DataSourceConfig> model = categoryModels.get(cat);
                items.forEach(model::addElement);
                renormalizePriorities(model);
            }
        }

        private boolean isLaunchCategoryActive() {
            return activeCategory() == DataSourceConfig.FeedCategory.LAUNCH;
        }

        private DataSourceConfig.FeedCategory activeCategory() {
            int idx = categoryTabs.getSelectedIndex();
            if (idx >= 0 && idx < TAB_CATEGORIES.length) {
                return TAB_CATEGORIES[idx];
            }
            return DataSourceConfig.FeedCategory.LAUNCH;
        }

        private DefaultListModel<DataSourceConfig> activeModel() {
            return categoryModels.get(activeCategory());
        }

        private JList<DataSourceConfig> activeList() {
            return categoryLists.get(activeCategory());
        }

        private DataSourceConfig getSelectedSource() {
            return activeList().getSelectedValue();
        }

        private void loadSelectedSourceIntoEditor() {
            DataSourceConfig src = getSelectedSource();
            loadingEditor = true;
            try {
                boolean hasSelection = src != null;
                setEditorEnabled(hasSelection);
                boolean isOpCategory = activeCategory() == DataSourceConfig.FeedCategory.GNSS_OPERATOR_STATUS;
                providerScopeLabel.setVisible(isOpCategory);
                providerScopeCombo.setVisible(isOpCategory);
                if (!hasSelection) {
                    enabledCheckBox.setSelected(false);
                    displayNameField.setText("");
                    endpointUrlField.setText("");
                    providerScopeCombo.setSelectedItem("");
                    authModeCombo.setSelectedItem(AuthMode.NONE);
                    authKeyNameField.setText("");
                    authUsernameField.setText("");
                    authSecretField.setText("");
                    refreshIntervalCombo.setSelectedIndex(1);
                    companionGsSyncCheckBox.setSelected(false);
                    companionGsSyncCheckBox.setVisible(isLaunchCategoryActive());
                    showSecretCheckBox.setSelected(false);
                    updateAuthFieldStates();
                    updateSecretMasking();
                    return;
                }

                enabledCheckBox.setSelected(src.isEnabled());
                displayNameField.setText(nonNull(src.getDisplayName()));
                endpointUrlField.setText(nonNull(src.getEndpointUrl()));
                boolean isOperator = src.getFeedCategory() == DataSourceConfig.FeedCategory.GNSS_OPERATOR_STATUS;
                providerScopeLabel.setVisible(isOperator);
                providerScopeCombo.setVisible(isOperator);
                providerScopeCombo.setSelectedItem(nonNull(src.getProviderScope()));
                AuthMode mode = src.getAuthMode() != null ? src.getAuthMode() : AuthMode.NONE;
                authModeCombo.setSelectedItem(mode);
                authKeyNameField.setText(nonNull(src.getAuthKeyName()));
                authUsernameField.setText(nonNull(src.getAuthUsername()));
                authSecretField.setText(nonNull(src.getAuthSecret()));
                selectRefreshIntervalMinutes(src.getRefreshIntervalMinutes());
                boolean isLaunch = src.getFeedCategory() == DataSourceConfig.FeedCategory.LAUNCH;
                companionGsSyncCheckBox.setVisible(isLaunch);
                companionGsSyncCheckBox.setSelected(isLaunch && src.isSyncFixedPadsToGroundStations());
                showSecretCheckBox.setSelected(false);
                updateAuthFieldStates();
                updateSecretMasking();
            } finally {
                loadingEditor = false;
            }
        }

        private void saveEditorToSelectedSource() {
            if (loadingEditor) {
                return;
            }
            DataSourceConfig src = getSelectedSource();
            if (src == null) {
                return;
            }
            src.setEnabled(enabledCheckBox.isSelected());
            src.setDisplayName(displayNameField.getText().trim());
            src.setEndpointUrl(endpointUrlField.getText().trim());
            if (src.getFeedCategory() == DataSourceConfig.FeedCategory.GNSS_OPERATOR_STATUS) {
                Object scopeVal = providerScopeCombo.getSelectedItem();
                src.setProviderScope(scopeVal != null ? scopeVal.toString().trim() : "");
            }
            AuthMode mode = (AuthMode) authModeCombo.getSelectedItem();
            src.setAuthMode(mode != null ? mode : AuthMode.NONE);
            src.setAuthKeyName(authKeyNameField.getText().trim());
            src.setAuthUsername(authUsernameField.getText().trim());
            src.setAuthSecret(new String(authSecretField.getPassword()));
            RefreshIntervalOption interval = (RefreshIntervalOption) refreshIntervalCombo.getSelectedItem();
            src.setRefreshIntervalMinutes(interval != null ? interval.minutes() : 15);
            src.setSyncFixedPadsToGroundStations(
                    src.getFeedCategory() == DataSourceConfig.FeedCategory.LAUNCH
                            && companionGsSyncCheckBox.isSelected());
            activeList().repaint();
        }

        private void attachEditorSyncListeners() {
            DocumentListener docSync = new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    saveEditorToSelectedSource();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    saveEditorToSelectedSource();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    saveEditorToSelectedSource();
                }
            };
            displayNameField.getDocument().addDocumentListener(docSync);
            endpointUrlField.getDocument().addDocumentListener(docSync);
            authKeyNameField.getDocument().addDocumentListener(docSync);
            authUsernameField.getDocument().addDocumentListener(docSync);
            authSecretField.getDocument().addDocumentListener(docSync);

            enabledCheckBox.addActionListener(e -> saveEditorToSelectedSource());
            providerScopeCombo.addActionListener(e -> saveEditorToSelectedSource());
            refreshIntervalCombo.addActionListener(e -> saveEditorToSelectedSource());
            companionGsSyncCheckBox.addActionListener(e -> saveEditorToSelectedSource());
            authModeCombo.addActionListener(e -> {
                updateAuthFieldStates();
                saveEditorToSelectedSource();
            });
        }

        private void setEditorEnabled(boolean enabled) {
            enabledCheckBox.setEnabled(enabled);
            displayNameField.setEnabled(enabled);
            endpointUrlField.setEnabled(enabled);
            providerScopeCombo.setEnabled(enabled);
            authModeCombo.setEnabled(enabled);
            refreshIntervalCombo.setEnabled(enabled);
            companionGsSyncCheckBox.setEnabled(enabled);
            testConnectionButton.setEnabled(enabled && !testInProgress);
        }

        private void updateAuthFieldStates() {
            boolean hasSelection = getSelectedSource() != null;
            AuthMode mode = (AuthMode) authModeCombo.getSelectedItem();
            if (mode == null) {
                mode = AuthMode.NONE;
            }
            boolean needsKeyName = hasSelection && (mode == AuthMode.API_KEY_HEADER || mode == AuthMode.QUERY_PARAM);
            boolean needsUsername = hasSelection && mode == AuthMode.BASIC_AUTH;
            boolean needsSecret = hasSelection && mode != AuthMode.NONE;

            authKeyNameLabel.setText(mode == AuthMode.QUERY_PARAM ? "Query Param:" : "Header Name:");
            authKeyNameLabel.setEnabled(needsKeyName);
            authKeyNameField.setEnabled(needsKeyName);

            authUsernameLabel.setEnabled(needsUsername);
            authUsernameField.setEnabled(needsUsername);

            authSecretLabel.setText(mode == AuthMode.BASIC_AUTH ? "Password:" : "Secret / Token:");
            authSecretLabel.setEnabled(needsSecret);
            authSecretField.setEnabled(needsSecret);
            showSecretCheckBox.setEnabled(needsSecret);
        }

        private void updateSecretMasking() {
            authSecretField.setEchoChar(showSecretCheckBox.isSelected() ? (char) 0 : defaultEchoChar);
        }

        private void updateListActionButtons() {
            JList<DataSourceConfig> list = activeList();
            DefaultListModel<DataSourceConfig> model = activeModel();
            int idx = list.getSelectedIndex();
            boolean hasSelection = idx >= 0 && idx < model.getSize();
            removeSourceButton.setEnabled(hasSelection);
            moveUpButton.setEnabled(hasSelection && idx > 0);
            moveDownButton.setEnabled(hasSelection && idx < model.getSize() - 1);
        }

        private void addNewSource() {
            DataSourceConfig.FeedCategory category = activeCategory();
            DefaultListModel<DataSourceConfig> model = activeModel();
            String id = generateUniqueSourceId(category);
            int defaultInterval = (category == DataSourceConfig.FeedCategory.GNSS_CATALOG
                    || category == DataSourceConfig.FeedCategory.GNSS_ORBITS_TLE) ? 1440 : 15;
            String defaultName = switch (category) {
                case LAUNCH -> "Custom Launch Source";
                case TLE -> "Custom TLE Feed";
                case GNSS_CATALOG -> "Custom GNSS Catalog";
                case GNSS_ORBITS_TLE -> "Custom GNSS TLE Feed";
                case GNSS_BROADCAST_NAV -> "Custom Broadcast Nav";
                case GNSS_OPERATOR_STATUS -> "Custom Operator Status";
            };
            DataSourceConfig created = new DataSourceConfig(
                    id,
                    category,
                    "",
                    defaultName,
                    "",
                    true,
                    model.getSize() + 1,
                    AuthMode.NONE,
                    "",
                    "",
                    "",
                    defaultInterval,
                    category == DataSourceConfig.FeedCategory.LAUNCH);
            model.addElement(created);
            renormalizePriorities(model);
            activeList().setSelectedIndex(model.getSize() - 1);
            updateListActionButtons();
            displayNameField.requestFocusInWindow();
        }

        private void removeSelectedSource() {
            DefaultListModel<DataSourceConfig> model = activeModel();
            int idx = activeList().getSelectedIndex();
            if (idx < 0 || idx >= model.getSize()) {
                return;
            }
            model.remove(idx);
            renormalizePriorities(model);
            if (!model.isEmpty()) {
                activeList().setSelectedIndex(Math.min(idx, model.getSize() - 1));
            } else {
                loadSelectedSourceIntoEditor();
            }
            updateListActionButtons();
        }

        private void moveSelectedSource(int delta) {
            DefaultListModel<DataSourceConfig> model = activeModel();
            int idx = activeList().getSelectedIndex();
            int target = idx + delta;
            if (idx < 0 || target < 0 || target >= model.getSize()) {
                return;
            }
            DataSourceConfig moving = model.get(idx);
            model.set(idx, model.get(target));
            model.set(target, moving);
            renormalizePriorities(model);
            activeList().setSelectedIndex(target);
            activeList().ensureIndexIsVisible(target);
            updateListActionButtons();
        }

        private void runConnectionTest() {
            saveEditorToSelectedSource();
            DataSourceConfig selected = getSelectedSource();
            if (selected == null) {
                return;
            }
            final DataSourceConfig snapshot = copySource(selected);
            cancelActiveTest();

            testInProgress = true;
            testConnectionButton.setEnabled(false);
            testStatusLabel.setText("Testing connection...");
            testStatusLabel.setForeground(mutedForeground());
            testStatusLabel.setToolTipText(null);

            SwingWorker<ConnectionTestResult, Void> worker = new SwingWorker<>() {
                @Override
                protected ConnectionTestResult doInBackground() {
                    DataSourceConfig.FeedCategory cat = snapshot.getFeedCategory();
                    if (cat == null) {
                        cat = DataSourceConfig.FeedCategory.LAUNCH;
                    }
                    return switch (cat) {
                        case LAUNCH -> launchApiClient.testConnection(snapshot);
                        case TLE, GNSS_ORBITS_TLE -> tleFeedClient.testConnection(snapshot);
                        case GNSS_CATALOG -> gnssCatalogClient.testConnection(snapshot);
                        case GNSS_BROADCAST_NAV -> broadcastNavClient.testConnection(snapshot);
                        case GNSS_OPERATOR_STATUS -> operatorStatusClient.testConnection(snapshot);
                    };
                }

                @Override
                protected void done() {
                    if (isCancelled()) {
                        return;
                    }
                    testInProgress = false;
                    testConnectionButton.setEnabled(getSelectedSource() != null);
                    try {
                        ConnectionTestResult res = get();
                        if (res == null) {
                            setTestResult(false, "No response");
                        } else if (res.success()) {
                            String text = res.httpStatus() > 0
                                    ? "HTTP " + res.httpStatus() + " \u2014 " + res.message()
                                    : res.message();
                            setTestResult(true, text);
                        } else {
                            String prefix = res.httpStatus() > 0
                                    ? "HTTP " + res.httpStatus() + " \u2014 "
                                    : "Failed \u2014 ";
                            String detail = res.message().startsWith("HTTP ") ? res.message() : prefix + res.message();
                            setTestResult(false, detail);
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        setTestResult(false, "Test interrupted");
                    } catch (ExecutionException ex) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        setTestResult(false, nonBlankOr(cause.getMessage(), cause.getClass().getSimpleName()));
                    }
                }
            };
            this.activeWorker = worker;
            worker.execute();
        }

        private void setTestResult(boolean success, String text) {
            testStatusLabel.setText(text);
            testStatusLabel.setToolTipText(text);
            testStatusLabel.setForeground(success
                    ? new Color(0x2E, 0x7D, 0x32)
                    : new Color(0xE5, 0x39, 0x35));
        }

        private void clearTestStatus() {
            cancelActiveTest();
            testConnectionButton.setEnabled(getSelectedSource() != null);
            testStatusLabel.setText(" ");
            testStatusLabel.setToolTipText(null);
            testStatusLabel.setForeground(mutedForeground());
        }

        private void resetToDefaults() {
            AwarenessSettings defaults = AwarenessSettings.createDefaults();
            autoFetchCheckBox.setSelected(defaults.isAutoFetchOnStartup());
            gnssFeedEnabledCheckBox.setSelected(defaults.isGnssFeedEnabled());
            populateModelsFromSettings(defaults);
            for (DataSourceConfig.FeedCategory cat : TAB_CATEGORIES) {
                DefaultListModel<DataSourceConfig> model = categoryModels.get(cat);
                JList<DataSourceConfig> list = categoryLists.get(cat);
                if (model != null && list != null && !model.isEmpty()) {
                    list.setSelectedIndex(0);
                }
            }
            loadSelectedSourceIntoEditor();
            updateListActionButtons();
            clearTestStatus();
        }

        private void saveAndClose() {
            saveEditorToSelectedSource();
            cancelActiveTest();
            AwarenessSettings updated = getWorkingSettingsSnapshot();
            try {
                service.updateSettings(updated);
                if (gnssService != null) {
                    gnssService.applySettingsSnapshotWithoutSaving(updated);
                }
                this.saved = true;
                if (this.onClose != null) {
                    this.onClose.run();
                }
            } catch (IOException ex) {
                String msg = "Unable to save Live Awareness settings: "
                        + nonBlankOr(ex.getMessage(), ex.getClass().getSimpleName());
                setTestResult(false, msg);
                if (!java.awt.GraphicsEnvironment.isHeadless()) {
                    JOptionPane.showMessageDialog(this, msg, "Save Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }

        private String generateUniqueSourceId(DataSourceConfig.FeedCategory category) {
            Set<String> existing = new HashSet<>();
            for (DataSourceConfig.FeedCategory cat : TAB_CATEGORIES) {
                DefaultListModel<DataSourceConfig> model = categoryModels.get(cat);
                if (model != null) {
                    for (int i = 0; i < model.size(); i++) {
                        existing.add(model.get(i).getId());
                    }
                }
            }
            String prefix = switch (category) {
                case LAUNCH -> "custom-launch-";
                case TLE -> "custom-tle-";
                case GNSS_CATALOG -> "custom-gnss-cat-";
                case GNSS_ORBITS_TLE -> "custom-gnss-tle-";
                case GNSS_BROADCAST_NAV -> "custom-gnss-nav-";
                case GNSS_OPERATOR_STATUS -> "custom-gnss-op-";
            };
            String candidate;
            do {
                candidate = prefix + (customIdCounter++);
            } while (existing.contains(candidate));
            return candidate;
        }

        private static void renormalizePriorities(DefaultListModel<DataSourceConfig> model) {
            for (int i = 0; i < model.getSize(); i++) {
                DataSourceConfig item = model.get(i);
                if (item != null) {
                    item.setPriority(i + 1);
                }
            }
        }

        private static JList<DataSourceConfig> createSourceList(DefaultListModel<DataSourceConfig> model) {
            JList<DataSourceConfig> list = new JList<>(model);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setCellRenderer(new SourceListCellRenderer());
            return list;
        }

        private static void addFormRow(JPanel form, int row, Component label, Component field) {
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridy = row;
            gbc.insets = new Insets(3, 2, 3, 6);
            gbc.anchor = GridBagConstraints.WEST;

            gbc.gridx = 0;
            gbc.weightx = 0.0;
            gbc.fill = GridBagConstraints.NONE;
            form.add(label, gbc);

            gbc.gridx = 1;
            gbc.weightx = 1.0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.insets = new Insets(3, 2, 3, 2);
            form.add(field, gbc);
        }

        private static DataSourceConfig copySource(DataSourceConfig source) {
            return new DataSourceConfig(
                    nonNull(source.getId()),
                    source.getFeedCategory() != null ? source.getFeedCategory() : DataSourceConfig.FeedCategory.LAUNCH,
                    nonNull(source.getProviderScope()),
                    nonNull(source.getDisplayName()),
                    nonNull(source.getEndpointUrl()),
                    source.isEnabled(),
                    source.getPriority(),
                    source.getAuthMode() != null ? source.getAuthMode() : AuthMode.NONE,
                    nonNull(source.getAuthKeyName()),
                    nonNull(source.getAuthSecret()),
                    nonNull(source.getAuthUsername()),
                    source.getRefreshIntervalMinutes(),
                    source.isSyncFixedPadsToGroundStations());
        }
    }

    private static final class SourceListCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof DataSourceConfig cfg) {
                String name = !isBlank(cfg.getDisplayName())
                        ? cfg.getDisplayName().trim()
                        : (!isBlank(cfg.getEndpointUrl()) ? cfg.getEndpointUrl().trim() : cfg.getId());
                String statusTag = cfg.isEnabled() ? "" : " [Disabled]";
                setText("#" + (index + 1) + "  " + name + statusTag);
                setToolTipText(cfg.getEndpointUrl());
                if (!isSelected && !cfg.isEnabled()) {
                    setForeground(mutedForeground());
                }
                setFont(getFont().deriveFont(cfg.isEnabled() ? Font.PLAIN : Font.ITALIC));
            }
            return this;
        }
    }

    private static final class AuthModeRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof AuthMode mode) {
                setText(switch (mode) {
                    case NONE -> "None (Public)";
                    case API_KEY_HEADER -> "API Key Header";
                    case BEARER_TOKEN -> "Bearer Token";
                    case QUERY_PARAM -> "Query Parameter";
                    case BASIC_AUTH -> "Basic Auth";
                });
            }
            return this;
        }
    }

    private static Color mutedForeground() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : new Color(128, 128, 128);
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    private static String nonBlankOr(String s, String fallback) {
        return (s != null && !s.isBlank()) ? s : fallback;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

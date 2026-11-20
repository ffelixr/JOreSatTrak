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
import java.util.HashSet;
import java.util.List;
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
        this(owner, service, new LaunchApiClient(), new TleFeedClient());
    }

    public LiveDataSourcesDialog(
            Window owner,
            LiveAwarenessService service,
            LaunchApiClient launchApiClient,
            TleFeedClient tleFeedClient) {
        super(owner, "Live Data Sources", ModalityType.APPLICATION_MODAL);
        this.editorPanel = new DataSourcesEditorPanel(
                service,
                launchApiClient,
                tleFeedClient,
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
                new RefreshIntervalOption(0, "Manual only")
        };

        private final LiveAwarenessService service;
        private final LaunchApiClient launchApiClient;
        private final TleFeedClient tleFeedClient;
        private final Runnable onClose;
        private final int preservedDefaultHorizonDays;

        private final JCheckBox autoFetchCheckBox;
        private final JTabbedPane categoryTabs;
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
            super(new BorderLayout(10, 10));
            this.service = Objects.requireNonNull(service, "service");
            this.launchApiClient = launchApiClient != null ? launchApiClient : new LaunchApiClient();
            this.tleFeedClient = tleFeedClient != null ? tleFeedClient : new TleFeedClient();
            this.onClose = onClose;

            setBorder(new EmptyBorder(12, 12, 12, 12));

            AwarenessSettings initial = service.getSettings();
            if (initial == null) {
                initial = AwarenessSettings.createDefaults();
            }
            this.preservedDefaultHorizonDays = initial.getDefaultHorizonDays();

            // Top: global startup preference
            JPanel topPanel = new JPanel(new BorderLayout(8, 4));
            topPanel.setOpaque(false);
            autoFetchCheckBox = new JCheckBox(
                    "Auto-fetch launch & TLE feeds on application startup",
                    initial.isAutoFetchOnStartup());
            autoFetchCheckBox.setToolTipText(
                    "When disabled, JOreSatTrak makes no outbound API calls until Live Awareness is opened or Refresh is clicked");
            topPanel.add(autoFetchCheckBox, BorderLayout.WEST);
            add(topPanel, BorderLayout.NORTH);

            // Left: categorized source lists + priority controls
            launchSourcesModel = new DefaultListModel<>();
            tleSourcesModel = new DefaultListModel<>();
            populateModelsFromSettings(initial);

            launchSourcesList = createSourceList(launchSourcesModel);
            tleSourcesList = createSourceList(tleSourcesModel);

            categoryTabs = new JTabbedPane();
            categoryTabs.addTab("Launch Sources", new JScrollPane(launchSourcesList));
            categoryTabs.addTab("New-Launch TLE Sources", new JScrollPane(tleSourcesList));

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

            int row = 0;
            addFormRow(form, row++, new JLabel("Status:"), enabledCheckBox);
            addFormRow(form, row++, new JLabel("Display Name:"), displayNameField);
            addFormRow(form, row++, new JLabel("Endpoint URL:"), endpointUrlField);
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
            launchSourcesList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting() && isLaunchCategoryActive()) {
                    clearTestStatus();
                    loadSelectedSourceIntoEditor();
                    updateListActionButtons();
                }
            });
            tleSourcesList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting() && !isLaunchCategoryActive()) {
                    clearTestStatus();
                    loadSelectedSourceIntoEditor();
                    updateListActionButtons();
                }
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

            if (!launchSourcesModel.isEmpty()) {
                launchSourcesList.setSelectedIndex(0);
            }
            if (!tleSourcesModel.isEmpty()) {
                tleSourcesList.setSelectedIndex(0);
            }
            loadSelectedSourceIntoEditor();
            updateListActionButtons();
        }

        public AwarenessSettings getWorkingSettingsSnapshot() {
            AwarenessSettings settings = new AwarenessSettings();
            settings.setAutoFetchOnStartup(autoFetchCheckBox.isSelected());
            settings.setDefaultHorizonDays(preservedDefaultHorizonDays);

            List<DataSourceConfig> combined = new ArrayList<>();
            renormalizePriorities(launchSourcesModel);
            renormalizePriorities(tleSourcesModel);
            for (int i = 0; i < launchSourcesModel.size(); i++) {
                combined.add(copySource(launchSourcesModel.get(i)));
            }
            for (int i = 0; i < tleSourcesModel.size(); i++) {
                combined.add(copySource(tleSourcesModel.get(i)));
            }
            settings.setSources(combined);
            return settings;
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

        private void populateModelsFromSettings(AwarenessSettings settings) {
            launchSourcesModel.clear();
            tleSourcesModel.clear();
            if (settings == null || settings.getSources() == null) {
                return;
            }
            List<DataSourceConfig> launches = new ArrayList<>();
            List<DataSourceConfig> tles = new ArrayList<>();
            for (DataSourceConfig src : settings.getSources()) {
                if (src == null) {
                    continue;
                }
                DataSourceConfig copy = copySource(src);
                if (copy.getFeedCategory() == DataSourceConfig.FeedCategory.TLE) {
                    tles.add(copy);
                } else {
                    copy.setFeedCategory(DataSourceConfig.FeedCategory.LAUNCH);
                    launches.add(copy);
                }
            }
            launches.sort(Comparator.comparingInt(DataSourceConfig::getPriority));
            tles.sort(Comparator.comparingInt(DataSourceConfig::getPriority));
            launches.forEach(launchSourcesModel::addElement);
            tles.forEach(tleSourcesModel::addElement);
            renormalizePriorities(launchSourcesModel);
            renormalizePriorities(tleSourcesModel);
        }

        private boolean isLaunchCategoryActive() {
            return categoryTabs.getSelectedIndex() == 0;
        }

        private DataSourceConfig.FeedCategory activeCategory() {
            return isLaunchCategoryActive()
                    ? DataSourceConfig.FeedCategory.LAUNCH
                    : DataSourceConfig.FeedCategory.TLE;
        }

        private DefaultListModel<DataSourceConfig> activeModel() {
            return isLaunchCategoryActive() ? launchSourcesModel : tleSourcesModel;
        }

        private JList<DataSourceConfig> activeList() {
            return isLaunchCategoryActive() ? launchSourcesList : tleSourcesList;
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
                if (!hasSelection) {
                    enabledCheckBox.setSelected(false);
                    displayNameField.setText("");
                    endpointUrlField.setText("");
                    authModeCombo.setSelectedItem(AuthMode.NONE);
                    authKeyNameField.setText("");
                    authUsernameField.setText("");
                    authSecretField.setText("");
                    refreshIntervalCombo.setSelectedIndex(1);
                    companionGsSyncCheckBox.setSelected(false);
                    companionGsSyncCheckBox.setVisible(isLaunchCategoryActive());
                    updateAuthFieldStates();
                    return;
                }

                enabledCheckBox.setSelected(src.isEnabled());
                displayNameField.setText(nonNull(src.getDisplayName()));
                endpointUrlField.setText(nonNull(src.getEndpointUrl()));
                AuthMode mode = src.getAuthMode() != null ? src.getAuthMode() : AuthMode.NONE;
                authModeCombo.setSelectedItem(mode);
                authKeyNameField.setText(nonNull(src.getAuthKeyName()));
                authUsernameField.setText(nonNull(src.getAuthUsername()));
                authSecretField.setText(nonNull(src.getAuthSecret()));
                selectRefreshIntervalMinutes(src.getRefreshIntervalMinutes());
                boolean isLaunch = src.getFeedCategory() == DataSourceConfig.FeedCategory.LAUNCH;
                companionGsSyncCheckBox.setVisible(isLaunch);
                companionGsSyncCheckBox.setSelected(isLaunch && src.isSyncFixedPadsToGroundStations());
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
            DataSourceConfig created = new DataSourceConfig(
                    id,
                    category,
                    category == DataSourceConfig.FeedCategory.LAUNCH ? "Custom Launch Source" : "Custom TLE Feed",
                    "",
                    true,
                    model.getSize() + 1,
                    AuthMode.NONE,
                    "",
                    "",
                    "",
                    15,
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
                    if (snapshot.getFeedCategory() == DataSourceConfig.FeedCategory.TLE) {
                        return tleFeedClient.testConnection(snapshot);
                    }
                    return launchApiClient.testConnection(snapshot);
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
            populateModelsFromSettings(defaults);
            if (!launchSourcesModel.isEmpty()) {
                launchSourcesList.setSelectedIndex(0);
            }
            if (!tleSourcesModel.isEmpty()) {
                tleSourcesList.setSelectedIndex(0);
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
            for (int i = 0; i < launchSourcesModel.size(); i++) {
                existing.add(launchSourcesModel.get(i).getId());
            }
            for (int i = 0; i < tleSourcesModel.size(); i++) {
                existing.add(tleSourcesModel.get(i).getId());
            }
            String prefix = category == DataSourceConfig.FeedCategory.TLE ? "custom-tle-" : "custom-launch-";
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

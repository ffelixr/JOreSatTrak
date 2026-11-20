package jsattrak.awareness.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumnModel;
import jsattrak.awareness.LaunchRecord;
import jsattrak.awareness.tle.PayloadTleMatch;
import jsattrak.utilities.TLE;

/**
 * Modal dialog allowing the user to selectively import matched payload TLEs into the scenario.
 */
public class PayloadTleImportDialog extends JDialog {

    private final PayloadTlePickerPanel pickerPanel;
    private boolean confirmed;
    private boolean cancelled;

    public PayloadTleImportDialog(Frame owner, LaunchRecord launch, List<PayloadTleMatch> matches) {
        this((Window) owner, launch, matches);
    }

    public PayloadTleImportDialog(Window owner, LaunchRecord launch, List<PayloadTleMatch> matches) {
        super(owner, buildTitle(launch), ModalityType.APPLICATION_MODAL);
        this.pickerPanel = new PayloadTlePickerPanel(
                launch,
                matches,
                () -> {
                    this.confirmed = true;
                    this.cancelled = false;
                    dispose();
                },
                () -> {
                    this.confirmed = false;
                    this.cancelled = true;
                    dispose();
                });

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (!confirmed) {
                    cancelled = true;
                }
            }
        });
        setContentPane(pickerPanel);
        getRootPane().setDefaultButton(pickerPanel.getAddSelectedButton());
        getRootPane().registerKeyboardAction(
                e -> {
                    confirmed = false;
                    cancelled = true;
                    dispose();
                },
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        pack();
        setMinimumSize(new Dimension(700, 400));
        setLocationRelativeTo(owner);
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public List<PayloadTleMatch> getSelectedMatches() {
        if (cancelled) {
            return List.of();
        }
        return pickerPanel.getSelectedMatches();
    }

    public boolean isUpdateExistingEnabled() {
        return pickerPanel.isUpdateExistingEnabled();
    }

    public PayloadTleTableModel getTableModel() {
        return pickerPanel.getTableModel();
    }

    public PayloadTlePickerPanel getPickerPanel() {
        return pickerPanel;
    }

    private static String buildTitle(LaunchRecord launch) {
        String mission = (launch != null && launch.getMissionName() != null && !launch.getMissionName().isBlank())
                ? launch.getMissionName().trim()
                : "Launch";
        return "Import Payload TLEs — " + mission;
    }

    /**
     * Reusable headless-safe picker panel backing {@link PayloadTleImportDialog}.
     */
    public static class PayloadTlePickerPanel extends JPanel {

        private final PayloadTleTableModel tableModel;
        private final JTable table;
        private final JButton selectAllPayloadsButton;
        private final JButton selectAllButton;
        private final JButton clearButton;
        private final JCheckBox updateExistingCheckBox;
        private final JButton addSelectedButton;
        private final JButton cancelButton;
        private final JLabel selectionSummaryLabel;

        public PayloadTlePickerPanel(
                LaunchRecord launch,
                List<PayloadTleMatch> matches,
                Runnable onConfirm,
                Runnable onCancel) {
            super(new BorderLayout(10, 10));
            setBorder(new EmptyBorder(12, 12, 12, 12));

            this.tableModel = new PayloadTleTableModel(matches);
            this.table = new JTable(tableModel);
            this.table.setFillsViewportHeight(true);
            this.table.setRowHeight(24);
            this.table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            this.table.getTableHeader().setReorderingAllowed(false);
            configureColumns(table.getColumnModel());

            // Header panel
            JPanel headerPanel = new JPanel();
            headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));
            headerPanel.setOpaque(false);

            String mission = (launch != null && !isBlank(launch.getMissionName()))
                    ? launch.getMissionName().trim()
                    : "Selected Launch";
            String vehicle = (launch != null && !isBlank(launch.getVehicle()))
                    ? " · " + launch.getVehicle().trim()
                    : "";
            String cospar = (launch != null && !isBlank(launch.getCosparId()))
                    ? " · COSPAR " + launch.getCosparId().trim()
                    : "";

            JLabel titleLabel = new JLabel(mission + vehicle + cospar);
            titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, titleLabel.getFont().getSize2D() + 1.5f));
            titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            headerPanel.add(titleLabel);

            long trackedCount = tableModel.getMatches().stream().filter(PayloadTleMatch::alreadyTracked).count();
            boolean hasSupplemental = tableModel.getMatches().stream().anyMatch(PayloadTleMatch::supplemental);
            String subtitle = tableModel.getRowCount() + " matched catalog object(s)"
                    + (trackedCount > 0 ? " (" + trackedCount + " already tracked)" : "");
            JLabel subtitleLabel = new JLabel(subtitle);
            subtitleLabel.setForeground(secondaryForeground());
            subtitleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            headerPanel.add(Box.createVerticalStrut(3));
            headerPanel.add(subtitleLabel);

            if (hasSupplemental) {
                JLabel supBanner = new JLabel(
                        "Includes Supplemental / Pre-launch TLEs (operator-supplied predictions, not radar-tracked).") {
                    @Override
                    protected void paintComponent(java.awt.Graphics g) {
                        java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                        try {
                            g2.setRenderingHint(
                                    java.awt.RenderingHints.KEY_ANTIALIASING,
                                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                            g2.setColor(new Color(0xFB, 0x8C, 0x00, 28));
                            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
                        } finally {
                            g2.dispose();
                        }
                        super.paintComponent(g);
                    }
                };
                supBanner.setOpaque(false);
                supBanner.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(0xFB, 0x8C, 0x00, 150), 1, true),
                        new EmptyBorder(4, 8, 4, 8)));
                supBanner.setAlignmentX(Component.LEFT_ALIGNMENT);
                headerPanel.add(Box.createVerticalStrut(6));
                headerPanel.add(supBanner);
            }
            add(headerPanel, BorderLayout.NORTH);

            // Center table
            JScrollPane scrollPane = new JScrollPane(table);
            scrollPane.setPreferredSize(new Dimension(680, 230));
            add(scrollPane, BorderLayout.CENTER);

            // Bottom toolbar + actions
            JPanel southPanel = new JPanel();
            southPanel.setLayout(new BoxLayout(southPanel, BoxLayout.Y_AXIS));
            southPanel.setOpaque(false);

            JPanel quickSelectRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            quickSelectRow.setOpaque(false);
            quickSelectRow.setAlignmentX(Component.LEFT_ALIGNMENT);

            selectAllPayloadsButton = new JButton("Select All Payloads");
            selectAllButton = new JButton("Select All");
            clearButton = new JButton("Clear");
            selectionSummaryLabel = new JLabel();
            selectionSummaryLabel.setForeground(secondaryForeground());

            quickSelectRow.add(selectAllPayloadsButton);
            quickSelectRow.add(selectAllButton);
            quickSelectRow.add(clearButton);
            quickSelectRow.add(Box.createHorizontalStrut(8));
            quickSelectRow.add(selectionSummaryLabel);

            updateExistingCheckBox = new JCheckBox("Update TLE in place for already-tracked satellites", true);
            updateExistingCheckBox.setAlignmentX(Component.LEFT_ALIGNMENT);

            JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            buttonRow.setOpaque(false);
            buttonRow.setAlignmentX(Component.LEFT_ALIGNMENT);

            cancelButton = new JButton("Cancel");
            addSelectedButton = new JButton("Add Selected to Object List");

            buttonRow.add(cancelButton);
            buttonRow.add(addSelectedButton);

            southPanel.add(quickSelectRow);
            southPanel.add(Box.createVerticalStrut(6));
            southPanel.add(updateExistingCheckBox);
            southPanel.add(Box.createVerticalStrut(8));
            southPanel.add(buttonRow);
            add(southPanel, BorderLayout.SOUTH);

            selectAllPayloadsButton.addActionListener(e -> tableModel.selectAllPayloads());
            selectAllButton.addActionListener(e -> tableModel.selectAll());
            clearButton.addActionListener(e -> tableModel.clearSelection());
            addSelectedButton.addActionListener(e -> {
                if (onConfirm != null) {
                    onConfirm.run();
                }
            });
            cancelButton.addActionListener(e -> {
                if (onCancel != null) {
                    onCancel.run();
                }
            });

            tableModel.addTableModelListener(e -> updateSelectionState());
            updateSelectionState();
        }

        private void updateSelectionState() {
            int count = tableModel.getSelectedMatches().size();
            addSelectedButton.setEnabled(count > 0);
            selectionSummaryLabel.setText(count + " selected for import");
        }

        public PayloadTleTableModel getTableModel() {
            return tableModel;
        }

        public List<PayloadTleMatch> getSelectedMatches() {
            return tableModel.getSelectedMatches();
        }

        public boolean isUpdateExistingEnabled() {
            return updateExistingCheckBox.isSelected();
        }

        public void setUpdateExistingEnabled(boolean enabled) {
            updateExistingCheckBox.setSelected(enabled);
        }

        public JButton getSelectAllPayloadsButton() {
            return selectAllPayloadsButton;
        }

        public JButton getSelectAllButton() {
            return selectAllButton;
        }

        public JButton getClearButton() {
            return clearButton;
        }

        public JCheckBox getUpdateExistingCheckBox() {
            return updateExistingCheckBox;
        }

        public JButton getAddSelectedButton() {
            return addSelectedButton;
        }

        public JButton getCancelButton() {
            return cancelButton;
        }

        public JTable getTable() {
            return table;
        }

        private static void configureColumns(TableColumnModel columns) {
            if (columns.getColumnCount() < 7) {
                return;
            }
            columns.getColumn(0).setPreferredWidth(52);
            columns.getColumn(0).setMaxWidth(64);
            columns.getColumn(1).setPreferredWidth(165);
            columns.getColumn(2).setPreferredWidth(92);
            columns.getColumn(3).setPreferredWidth(76);
            columns.getColumn(4).setPreferredWidth(92);
            columns.getColumn(5).setPreferredWidth(155);
            columns.getColumn(6).setPreferredWidth(105);

            DefaultTableCellRenderer statusRenderer = new DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(
                        JTable tbl, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                    Component c = super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column);
                    setHorizontalAlignment(SwingConstants.CENTER);
                    if (!isSelected) {
                        String text = value == null ? "" : value.toString();
                        if ("Already tracked".equals(text)) {
                            setForeground(new Color(0xFB, 0x8C, 0x00));
                        } else {
                            setForeground(UIManager.getColor("Table.foreground"));
                        }
                    }
                    return c;
                }
            };
            columns.getColumn(6).setCellRenderer(statusRenderer);
        }
    }

    /**
     * Table model managing payload TLE selection with smart defaults.
     */
    public static class PayloadTleTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = {
                "Import", "Object Name", "Designator", "NORAD ID", "Type", "Source / Epoch", "Status"
        };
        private static final DateTimeFormatter EPOCH_FORMAT =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

        private final List<PayloadTleMatch> matches;
        private final boolean[] selected;

        public PayloadTleTableModel(List<PayloadTleMatch> matches) {
            if (matches == null || matches.isEmpty()) {
                this.matches = List.of();
            } else {
                List<PayloadTleMatch> clean = new ArrayList<>(matches.size());
                for (PayloadTleMatch m : matches) {
                    if (m != null) {
                        clean.add(m);
                    }
                }
                this.matches = List.copyOf(clean);
            }
            this.selected = new boolean[this.matches.size()];
            for (int i = 0; i < this.matches.size(); i++) {
                PayloadTleMatch match = this.matches.get(i);
                this.selected[i] = match.objectType() == PayloadTleMatch.ObjectType.PAYLOAD
                        && !match.alreadyTracked();
            }
        }

        public List<PayloadTleMatch> getMatches() {
            return matches;
        }

        public PayloadTleMatch getMatchAt(int row) {
            return (row >= 0 && row < matches.size()) ? matches.get(row) : null;
        }

        public boolean isSelected(int row) {
            if (row < 0 || row >= selected.length) {
                return false;
            }
            return selected[row];
        }

        public void setSelected(int row, boolean value) {
            if (row < 0 || row >= selected.length) {
                return;
            }
            if (selected[row] != value) {
                selected[row] = value;
                fireTableCellUpdated(row, 0);
            }
        }

        /**
         * Selects all non-tracked payloads while keeping rocket bodies, debris, and already-tracked
         * entries unchecked unless the user explicitly toggles them.
         */
        public void selectAllPayloads() {
            for (int i = 0; i < matches.size(); i++) {
                PayloadTleMatch m = matches.get(i);
                if (m == null) {
                    selected[i] = false;
                } else if (m.objectType() == PayloadTleMatch.ObjectType.PAYLOAD && !m.alreadyTracked()) {
                    selected[i] = true;
                } else if (m.objectType() != PayloadTleMatch.ObjectType.PAYLOAD) {
                    selected[i] = false;
                }
            }
            if (!matches.isEmpty()) {
                fireTableRowsUpdated(0, matches.size() - 1);
            }
        }

        public void selectAll() {
            for (int i = 0; i < selected.length; i++) {
                selected[i] = true;
            }
            if (!matches.isEmpty()) {
                fireTableRowsUpdated(0, matches.size() - 1);
            }
        }

        public void clearSelection() {
            for (int i = 0; i < selected.length; i++) {
                selected[i] = false;
            }
            if (!matches.isEmpty()) {
                fireTableRowsUpdated(0, matches.size() - 1);
            }
        }

        public List<PayloadTleMatch> getSelectedMatches() {
            List<PayloadTleMatch> result = new ArrayList<>();
            for (int i = 0; i < matches.size(); i++) {
                if (selected[i] && matches.get(i) != null) {
                    result.add(matches.get(i));
                }
            }
            return List.copyOf(result);
        }

        @Override
        public int getRowCount() {
            return matches.size();
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
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            PayloadTleMatch match = matches.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> selected[rowIndex];
                case 1 -> match.objectName();
                case 2 -> !match.internationalDesignator().isBlank() ? match.internationalDesignator() : "\u2014";
                case 3 -> match.noradId() > 0 ? String.valueOf(match.noradId()) : "\u2014";
                case 4 -> formatObjectType(match.objectType());
                case 5 -> formatSourceAndEpoch(match);
                case 6 -> match.alreadyTracked() ? "Already tracked" : "New";
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex == 0 && aValue instanceof Boolean b) {
                setSelected(rowIndex, b);
            }
        }

        private static String formatObjectType(PayloadTleMatch.ObjectType type) {
            if (type == null) {
                return "Payload";
            }
            return switch (type) {
                case PAYLOAD -> "Payload";
                case ROCKET_BODY -> "Rocket Body";
                case DEBRIS -> "Debris";
            };
        }

        private static String formatSourceAndEpoch(PayloadTleMatch match) {
            String base = match.supplemental()
                    ? "SupGP (Pre-launch)"
                    : (!match.sourceLabel().isBlank() ? match.sourceLabel() : "Official");
            String epoch = formatTleEpoch(match.tle());
            return epoch.isEmpty() ? base : base + " · " + epoch;
        }

        static String formatTleEpoch(TLE tle) {
            if (tle == null || tle.getLine1() == null || tle.getLine1().length() < 32) {
                return "";
            }
            String field = tle.getLine1().substring(18, 32).trim();
            if (field.length() < 5) {
                return "";
            }
            try {
                int yy = Integer.parseInt(field.substring(0, 2));
                int fullYear = (yy >= 57) ? (1900 + yy) : (2000 + yy);
                double dayOfYear = Double.parseDouble(field.substring(2));
                if (!Double.isFinite(dayOfYear) || dayOfYear < 1.0 || dayOfYear >= 367.0) {
                    return "";
                }
                ZonedDateTime yearStart = ZonedDateTime.of(fullYear, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
                long offsetSeconds = Math.round((dayOfYear - 1.0) * 86400.0);
                Instant epochInstant = yearStart.toInstant().plusSeconds(offsetSeconds);
                return EPOCH_FORMAT.format(epochInstant);
            } catch (NumberFormatException e) {
                return "";
            }
        }
    }

    private static Color secondaryForeground() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : new Color(128, 128, 128);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

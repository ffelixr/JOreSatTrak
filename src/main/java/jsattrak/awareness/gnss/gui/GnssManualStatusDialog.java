package jsattrak.awareness.gnss.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import jsattrak.awareness.gnss.GnssAwarenessService;
import jsattrak.awareness.gnss.GnssDisplayStatus;
import jsattrak.awareness.gnss.GnssManualOverride;
import jsattrak.awareness.gnss.GnssManualStatusList;

/**
 * Modal editor for curated manual GNSS status overrides (typically GLONASS/BeiDou flight-testing
 * entries and last-reviewed metadata).
 */
public class GnssManualStatusDialog extends JDialog {

    private final ManualStatusEditorPanel editorPanel;
    private boolean saved;

    public GnssManualStatusDialog(Window owner, GnssAwarenessService service) {
        super(owner, "GNSS Manual Status Overrides", ModalityType.APPLICATION_MODAL);
        this.editorPanel = new ManualStatusEditorPanel(service, this::handleClose);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setContentPane(editorPanel);
        getRootPane().setDefaultButton(editorPanel.getSaveButton());
        getRootPane().registerKeyboardAction(
                e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setMinimumSize(new Dimension(620, 380));
        setLocationRelativeTo(owner);
    }

    public boolean isSaved() {
        return saved;
    }

    public ManualStatusEditorPanel getEditorPanel() {
        return editorPanel;
    }

    private void handleClose() {
        this.saved = editorPanel != null && editorPanel.isSaved();
        dispose();
    }

    /**
     * Reusable headless-safe editor panel for manual GNSS overrides.
     */
    public static class ManualStatusEditorPanel extends JPanel {

        private final GnssAwarenessService service;
        private final Runnable onClose;
        private final ManualOverrideTableModel tableModel;
        private final JTable table;
        private final JLabel reviewedLabel;
        private final JLabel staleBadgeLabel;
        private final JButton markReviewedButton;
        private final JButton saveButton;

        private String workingLastReviewedIso;
        private boolean saved;

        public ManualStatusEditorPanel(GnssAwarenessService service, Runnable onClose) {
            super(new BorderLayout(8, 8));
            this.service = Objects.requireNonNull(service, "service");
            this.onClose = onClose;
            setBorder(new EmptyBorder(12, 12, 12, 12));

            GnssManualStatusList current = service.getManualStatusList();
            this.workingLastReviewedIso = current != null && current.getLastReviewedIso() != null
                    ? current.getLastReviewedIso()
                    : "";

            // Header: review date & stale status
            JPanel header = new JPanel(new BorderLayout(8, 4));
            header.setOpaque(false);

            JPanel reviewInfo = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
            reviewInfo.setOpaque(false);
            reviewedLabel = new JLabel();
            reviewedLabel.setFont(reviewedLabel.getFont().deriveFont(Font.BOLD));
            staleBadgeLabel = new JLabel("Stale (>30 days since review)");
            staleBadgeLabel.setForeground(new Color(0xF0, 0xA8, 0x30));
            staleBadgeLabel.setFont(staleBadgeLabel.getFont().deriveFont(Font.BOLD));
            reviewInfo.add(reviewedLabel);
            reviewInfo.add(staleBadgeLabel);

            markReviewedButton = new JButton("Mark Reviewed Today");
            markReviewedButton.addActionListener(e -> markReviewedToday());

            header.add(reviewInfo, BorderLayout.WEST);
            header.add(markReviewedButton, BorderLayout.EAST);
            add(header, BorderLayout.NORTH);

            // Center table
            tableModel = new ManualOverrideTableModel();
            if (current != null && current.getEntries() != null) {
                for (GnssManualOverride entry : current.getEntries()) {
                    if (entry != null) {
                        tableModel.rows.add(new MutableRow(
                                entry.prnId() != null ? entry.prnId() : "",
                                entry.status() != null ? entry.status() : GnssDisplayStatus.TESTING,
                                entry.note() != null ? entry.note() : ""));
                    }
                }
            }

            table = new JTable(tableModel);
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setRowHeight(24);
            JComboBox<GnssDisplayStatus> statusCombo = new JComboBox<>(GnssDisplayStatus.values());
            table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(statusCombo));
            table.getColumnModel().getColumn(0).setPreferredWidth(80);
            table.getColumnModel().getColumn(1).setPreferredWidth(140);
            table.getColumnModel().getColumn(2).setPreferredWidth(320);

            add(new JScrollPane(table), BorderLayout.CENTER);

            // Footer actions
            JPanel footer = new JPanel(new BorderLayout());
            footer.setOpaque(false);

            JPanel leftBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            leftBtns.setOpaque(false);
            JButton addBtn = new JButton("Add Row");
            addBtn.addActionListener(e -> addEntry("", GnssDisplayStatus.TESTING, ""));
            JButton removeBtn = new JButton("Remove Selected");
            removeBtn.addActionListener(e -> removeSelectedRow());
            leftBtns.add(addBtn);
            leftBtns.add(removeBtn);

            JPanel rightBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            rightBtns.setOpaque(false);
            JButton cancelBtn = new JButton("Cancel");
            cancelBtn.addActionListener(e -> {
                this.saved = false;
                if (this.onClose != null) {
                    this.onClose.run();
                }
            });
            saveButton = new JButton("Save");
            saveButton.addActionListener(e -> save());
            rightBtns.add(cancelBtn);
            rightBtns.add(saveButton);

            footer.add(leftBtns, BorderLayout.WEST);
            footer.add(rightBtns, BorderLayout.EAST);
            add(footer, BorderLayout.SOUTH);

            updateReviewHeader();
        }

        public void addEntry(String prnId, GnssDisplayStatus status, String note) {
            stopCellEditing();
            tableModel.rows.add(new MutableRow(
                    prnId != null ? prnId.trim().toUpperCase(Locale.ROOT) : "",
                    status != null ? status : GnssDisplayStatus.TESTING,
                    note != null ? note : ""));
            int idx = tableModel.rows.size() - 1;
            tableModel.fireTableRowsInserted(idx, idx);
            table.setRowSelectionInterval(idx, idx);
        }

        public void removeSelectedRow() {
            stopCellEditing();
            int row = table.getSelectedRow();
            if (row >= 0 && row < tableModel.rows.size()) {
                tableModel.rows.remove(row);
                tableModel.fireTableRowsDeleted(row, row);
            }
        }

        public void markReviewedToday() {
            this.workingLastReviewedIso = LocalDate.now(ZoneOffset.UTC).toString();
            updateReviewHeader();
        }

        public void save() {
            stopCellEditing();
            List<GnssManualOverride> clean = new ArrayList<>();
            for (MutableRow r : tableModel.rows) {
                String prn = r.prnId != null ? r.prnId.trim().toUpperCase(Locale.ROOT) : "";
                if (!prn.isEmpty() && r.status != null) {
                    clean.add(new GnssManualOverride(prn, r.status, r.note != null ? r.note.trim() : ""));
                }
            }
            GnssManualStatusList toSave = new GnssManualStatusList(workingLastReviewedIso, clean);
            try {
                service.saveManualStatusList(toSave);
                this.saved = true;
                if (this.onClose != null) {
                    this.onClose.run();
                }
            } catch (IOException ex) {
                String msg = "Unable to save manual GNSS overrides: " + ex.getMessage();
                if (!java.awt.GraphicsEnvironment.isHeadless()) {
                    JOptionPane.showMessageDialog(this, msg, "Save Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }

        public boolean isSaved() {
            return saved;
        }

        public String getLastReviewedIso() {
            return workingLastReviewedIso;
        }

        public JButton getSaveButton() {
            return saveButton;
        }

        public JButton getMarkReviewedButton() {
            return markReviewedButton;
        }

        public JTable getTable() {
            return table;
        }

        private void stopCellEditing() {
            if (table.isEditing() && table.getCellEditor() != null) {
                table.getCellEditor().stopCellEditing();
            }
        }

        private void updateReviewHeader() {
            String iso = (workingLastReviewedIso == null || workingLastReviewedIso.isBlank())
                    ? "Never"
                    : workingLastReviewedIso;
            reviewedLabel.setText("Last reviewed (UTC): " + iso);
            GnssManualStatusList probe = new GnssManualStatusList(workingLastReviewedIso, List.of());
            boolean stale = probe.isStale(System.currentTimeMillis(), 30);
            staleBadgeLabel.setVisible(stale);
            if (!stale) {
                reviewedLabel.setForeground(defaultForeground());
            }
        }

        private static Color defaultForeground() {
            Color c = UIManager.getColor("Label.foreground");
            return c != null ? c : Color.DARK_GRAY;
        }

        private static final class MutableRow {
            String prnId;
            GnssDisplayStatus status;
            String note;

            MutableRow(String prnId, GnssDisplayStatus status, String note) {
                this.prnId = prnId;
                this.status = status;
                this.note = note;
            }
        }

        private static final class ManualOverrideTableModel extends AbstractTableModel {
            private static final String[] COLS = {"PRN", "Status", "Note"};
            final List<MutableRow> rows = new ArrayList<>();

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
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 1 ? GnssDisplayStatus.class : String.class;
            }

            @Override
            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return true;
            }

            @Override
            public Object getValueAt(int rowIndex, int columnIndex) {
                MutableRow r = rows.get(rowIndex);
                return switch (columnIndex) {
                    case 0 -> r.prnId;
                    case 1 -> r.status;
                    case 2 -> r.note;
                    default -> "";
                };
            }

            @Override
            public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
                MutableRow r = rows.get(rowIndex);
                switch (columnIndex) {
                    case 0 -> r.prnId = aValue != null ? aValue.toString().trim().toUpperCase(Locale.ROOT) : "";
                    case 1 -> {
                        if (aValue instanceof GnssDisplayStatus s) {
                            r.status = s;
                        }
                    }
                    case 2 -> r.note = aValue != null ? aValue.toString() : "";
                    default -> {
                    }
                }
                fireTableCellUpdated(rowIndex, columnIndex);
            }
        }
    }
}

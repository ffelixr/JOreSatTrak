package jsattrak.awareness.gnss.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import jsattrak.awareness.gnss.GnssCatalogEntry;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.GnssDisplayStatus;
import jsattrak.awareness.gnss.GnssEvidenceItem;
import jsattrak.awareness.gnss.GnssOrbitPropagator;
import jsattrak.awareness.gnss.GnssOutageNotice;
import jsattrak.awareness.gnss.GnssSatelliteState;
import jsattrak.awareness.gnss.GnssStatusResult;
import jsattrak.awareness.gnss.map.GnssOverlay2DRenderer;
import jsattrak.awareness.gui.LaunchDetailsInspectorPanel;

/**
 * Detail inspector for a selected GNSS satellite, rendering identity metadata, derived status
 * badge, manual-override warning banner, outage notice details, ordered evidence trail, and actions.
 */
public class GnssDetailsInspectorPanel extends JPanel {

    private static final Color AMBER_BG = new Color(0x5A, 0x3E, 0x10);
    private static final Color AMBER_BORDER = new Color(0xF0, 0xA8, 0x30);
    private static final Color AMBER_TEXT = new Color(0xFF, 0xE0, 0xA3);

    private final Consumer<Long> onJumpToEpoch;
    private final BiConsumer<Double, Double> onCenterMaps;
    private final Consumer<GnssSatelliteState> onAddToObjectList;
    private final Predicate<GnssSatelliteState> isInObjectList;

    private final JLabel titleLabel;
    private final JLabel statusBadgeLabel;
    private final JPanel manualOverrideBanner;
    private final JLabel manualOverrideTextLabel;

    private final JLabel constellationValueLabel;
    private final JLabel blockOrbitValueLabel;
    private final JLabel svnNoradValueLabel;
    private final JLabel planeSlotValueLabel;
    private final JLabel sourceRuleValueLabel;
    private final JLabel positionValueLabel;

    private final JPanel noticePanel;
    private final JLabel noticeBannerLabel;
    private final JTextArea outageDetailArea;

    private final JPanel evidencePanel;
    private final JScrollPane evidenceScroll;

    private final JButton addToObjectListButton;
    private final JButton centerMapsButton;
    private final JButton jumpToOutageButton;

    private GnssSatelliteState currentState;
    private GnssConstellation currentShapeConstellation = GnssConstellation.GPS;
    private Color currentStatusColor = GnssOverlay2DRenderer.colorForStatus(GnssDisplayStatus.NO_STATUS_DATA);
    private long jumpTargetEpochMillis;

    public GnssDetailsInspectorPanel(
            Consumer<Long> onJumpToEpoch,
            BiConsumer<Double, Double> onCenterMaps,
            Consumer<GnssSatelliteState> onAddToObjectList,
            Predicate<GnssSatelliteState> isInObjectList) {
        super(new BorderLayout(8, 8));
        this.onJumpToEpoch = onJumpToEpoch;
        this.onCenterMaps = onCenterMaps;
        this.onAddToObjectList = onAddToObjectList;
        this.isInObjectList = isInObjectList;

        setBorder(new EmptyBorder(6, 8, 6, 8));

        // Header strip: title + status badge
        JPanel headerPanel = new JPanel(new BorderLayout(8, 4));
        headerPanel.setOpaque(false);

        titleLabel = new JLabel("Select a GNSS satellite");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, titleLabel.getFont().getSize2D() + 2f));
        headerPanel.add(titleLabel, BorderLayout.CENTER);

        statusBadgeLabel = new JLabel(" ");
        statusBadgeLabel.setOpaque(true);
        statusBadgeLabel.setBorder(new EmptyBorder(3, 8, 3, 8));
        statusBadgeLabel.setFont(statusBadgeLabel.getFont().deriveFont(Font.BOLD));
        statusBadgeLabel.setIcon(new ConstellationIcon());
        headerPanel.add(statusBadgeLabel, BorderLayout.EAST);

        // Amber banner for manual operational override
        manualOverrideBanner = new JPanel(new BorderLayout(6, 2));
        manualOverrideBanner.setBackground(AMBER_BG);
        manualOverrideBanner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(AMBER_BORDER, 1),
                new EmptyBorder(5, 8, 5, 8)));
        manualOverrideTextLabel = new JLabel("Manual override active \u2014 overrides operator/broadcast status");
        manualOverrideTextLabel.setForeground(AMBER_TEXT);
        manualOverrideTextLabel.setFont(manualOverrideTextLabel.getFont().deriveFont(Font.BOLD));
        manualOverrideBanner.add(manualOverrideTextLabel, BorderLayout.CENTER);
        manualOverrideBanner.setVisible(false);

        JPanel northStack = new JPanel();
        northStack.setLayout(new BoxLayout(northStack, BoxLayout.Y_AXIS));
        northStack.setOpaque(false);
        headerPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        manualOverrideBanner.setAlignmentX(Component.LEFT_ALIGNMENT);
        northStack.add(headerPanel);
        northStack.add(Box.createVerticalStrut(4));
        northStack.add(manualOverrideBanner);

        // Metadata grid
        JPanel metaGrid = new JPanel(new GridBagLayout());
        metaGrid.setOpaque(false);
        constellationValueLabel = createValueLabel();
        blockOrbitValueLabel = createValueLabel();
        svnNoradValueLabel = createValueLabel();
        planeSlotValueLabel = createValueLabel();
        sourceRuleValueLabel = createValueLabel();
        positionValueLabel = createValueLabel();

        addGridRow(metaGrid, 0, "System:", constellationValueLabel, "Block / Orbit:", blockOrbitValueLabel);
        addGridRow(metaGrid, 1, "SVN / NORAD:", svnNoradValueLabel, "Plane / Slot:", planeSlotValueLabel);
        addGridRow(metaGrid, 2, "Primary / Rule:", sourceRuleValueLabel, "Sub-Sat (UTC):", positionValueLabel);
        metaGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
        northStack.add(Box.createVerticalStrut(4));
        northStack.add(metaGrid);

        // Outage Notice box
        noticePanel = new JPanel(new BorderLayout(4, 3));
        noticePanel.setOpaque(false);
        noticePanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Outage / Advisory Notice"),
                new EmptyBorder(4, 6, 4, 6)));
        noticeBannerLabel = new JLabel("No active or upcoming notice");
        noticeBannerLabel.setFont(noticeBannerLabel.getFont().deriveFont(Font.BOLD));
        outageDetailArea = new JTextArea(2, 30);
        outageDetailArea.setEditable(false);
        outageDetailArea.setLineWrap(true);
        outageDetailArea.setWrapStyleWord(true);
        outageDetailArea.setOpaque(false);
        noticePanel.add(noticeBannerLabel, BorderLayout.NORTH);
        noticePanel.add(outageDetailArea, BorderLayout.CENTER);
        noticePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        northStack.add(Box.createVerticalStrut(4));
        northStack.add(noticePanel);

        add(northStack, BorderLayout.NORTH);

        // Reasoning trail / evidence list
        evidencePanel = new JPanel();
        evidencePanel.setLayout(new BoxLayout(evidencePanel, BoxLayout.Y_AXIS));
        evidencePanel.setBorder(new EmptyBorder(4, 6, 4, 6));
        evidenceScroll = new JScrollPane(evidencePanel);
        evidenceScroll.setBorder(BorderFactory.createTitledBorder("Status Reasoning & Evidence"));
        evidenceScroll.setPreferredSize(new Dimension(340, 140));
        add(evidenceScroll, BorderLayout.CENTER);

        // Action strip
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        actions.setOpaque(false);

        addToObjectListButton = new JButton("Add to Object List");
        addToObjectListButton.addActionListener(e -> {
            if (currentState != null && onAddToObjectList != null) {
                onAddToObjectList.accept(currentState);
                refreshActionButtonStates();
            }
        });

        centerMapsButton = new JButton("Center Maps");
        centerMapsButton.addActionListener(e -> {
            if (currentState != null && currentState.position() != null && onCenterMaps != null) {
                GnssOrbitPropagator.GeodeticPosition pos = currentState.position();
                onCenterMaps.accept(pos.latDeg(), pos.lonDeg());
            }
        });

        jumpToOutageButton = new JButton("Jump Sim to outage start");
        jumpToOutageButton.addActionListener(e -> {
            if (jumpTargetEpochMillis > 0L && onJumpToEpoch != null) {
                onJumpToEpoch.accept(jumpTargetEpochMillis);
            }
        });

        actions.add(addToObjectListButton);
        actions.add(centerMapsButton);
        actions.add(jumpToOutageButton);
        add(actions, BorderLayout.SOUTH);

        setSatellite(null, 0L, TimeZone.getTimeZone("UTC"));
    }

    public void setSatellite(GnssSatelliteState state, long simEpochMillis, TimeZone displayTz) {
        this.currentState = state;
        TimeZone tz = displayTz != null ? displayTz : TimeZone.getTimeZone("UTC");

        if (state == null || state.catalog() == null) {
            titleLabel.setText("Select a GNSS satellite");
            currentShapeConstellation = GnssConstellation.GPS;
            currentStatusColor = GnssOverlay2DRenderer.colorForStatus(GnssDisplayStatus.NO_STATUS_DATA);
            statusBadgeLabel.setText("No Selection");
            statusBadgeLabel.setBackground(new Color(55, 62, 70));
            statusBadgeLabel.setForeground(Color.WHITE);
            manualOverrideBanner.setVisible(false);

            constellationValueLabel.setText("\u2014");
            blockOrbitValueLabel.setText("\u2014");
            svnNoradValueLabel.setText("\u2014");
            planeSlotValueLabel.setText("\u2014");
            sourceRuleValueLabel.setText("\u2014");
            positionValueLabel.setText("\u2014");

            noticeBannerLabel.setText("No satellite selected");
            outageDetailArea.setText("");
            jumpTargetEpochMillis = 0L;
            rebuildEvidenceList(List.of(), tz);
            refreshActionButtonStates();
            return;
        }

        GnssCatalogEntry cat = state.catalog();
        GnssStatusResult res = state.statusResult();
        GnssDisplayStatus displayStatus = res != null && res.status() != null
                ? res.status()
                : GnssDisplayStatus.NO_STATUS_DATA;

        currentShapeConstellation = cat.constellation() != null ? cat.constellation() : GnssConstellation.GPS;
        currentStatusColor = GnssOverlay2DRenderer.colorForStatus(displayStatus);

        titleLabel.setText(cat.prnId() + "  \u2014  " + currentShapeConstellation.getDisplayName()
                + (nonBlank(cat.blockType()) ? " (" + cat.blockType() + ")" : ""));

        statusBadgeLabel.setText(displayStatus.getDisplayLabel());
        statusBadgeLabel.setBackground(new Color(30, 36, 44));
        statusBadgeLabel.setForeground(currentStatusColor);
        statusBadgeLabel.repaint();

        boolean manualActive = res != null && res.manualOperationalOverride();
        manualOverrideBanner.setVisible(manualActive);

        constellationValueLabel.setText(currentShapeConstellation.getDisplayName()
                + " (" + currentShapeConstellation.getRegionCode() + ")");
        blockOrbitValueLabel.setText(nonBlankOr(cat.blockType(), "\u2014")
                + " / " + (cat.orbitClass() != null ? cat.orbitClass().name() : "\u2014"));
        svnNoradValueLabel.setText(nonBlankOr(cat.svn(), "\u2014")
                + " / " + (cat.noradId() > 0 ? String.valueOf(cat.noradId()) : "\u2014"));
        planeSlotValueLabel.setText(nonBlankOr(cat.plane(), "\u2014")
                + " / " + nonBlankOr(cat.slot(), "\u2014"));
        sourceRuleValueLabel.setText(res != null
                ? nonBlankOr(res.primarySource(), "\u2014") + " (" + nonBlankOr(res.decidedByRule(), "\u2014") + ")"
                : "\u2014");

        if (state.position() != null) {
            GnssOrbitPropagator.GeodeticPosition pos = state.position();
            positionValueLabel.setText(String.format(Locale.ROOT, "%.2f\u00B0, %.2f\u00B0 \u00B7 %.0f km",
                    pos.latDeg(), pos.lonDeg(), pos.altMeters() / 1000.0));
        } else {
            positionValueLabel.setText("No TLE / orbit data");
        }

        // Notices: active or next planned
        GnssOutageNotice active = res != null ? res.activeNotice() : null;
        GnssOutageNotice planned = res != null ? res.nextPlannedNotice() : null;
        GnssOutageNotice focusNotice = planned != null ? planned : active;
        if (active != null && planned != null && !active.equals(planned)) {
            noticeBannerLabel.setText("Active: " + formatNoticeHeader(active) + " | Planned: " + formatNoticeHeader(planned));
            noticeBannerLabel.setForeground(GnssOverlay2DRenderer.colorForStatus(GnssDisplayStatus.UNAVAILABLE));
            outageDetailArea.setText(formatNoticeDetail(active, tz) + "\n" + formatNoticeDetail(planned, tz));
            jumpTargetEpochMillis = planned.startEpochMillis() > 0L ? planned.startEpochMillis() : active.startEpochMillis();
        } else if (focusNotice != null) {
            String prefix = active != null ? "Active outage: " : "Planned outage: ";
            noticeBannerLabel.setText(prefix + formatNoticeHeader(focusNotice));
            noticeBannerLabel.setForeground(active != null
                    ? GnssOverlay2DRenderer.colorForStatus(GnssDisplayStatus.UNAVAILABLE)
                    : GnssOverlay2DRenderer.colorForStatus(GnssDisplayStatus.TESTING));
            outageDetailArea.setText(formatNoticeDetail(focusNotice, tz));
            jumpTargetEpochMillis = focusNotice.startEpochMillis();
        } else {
            noticeBannerLabel.setText("No active or upcoming outage notice");
            noticeBannerLabel.setForeground(mutedForeground());
            outageDetailArea.setText(simEpochMillis > 0L
                    ? "Evaluated at " + LaunchDetailsInspectorPanel.formatDateTime(simEpochMillis, tz)
                    : "\u2014");
            jumpTargetEpochMillis = 0L;
        }
        outageDetailArea.setCaretPosition(0);

        rebuildEvidenceList(res != null && res.evidence() != null ? res.evidence() : List.of(), tz);
        refreshActionButtonStates();
    }

    private void rebuildEvidenceList(List<GnssEvidenceItem> evidence, TimeZone tz) {
        evidencePanel.removeAll();
        if (evidence.isEmpty()) {
            JLabel empty = new JLabel("No evidence items recorded.");
            empty.setForeground(mutedForeground());
            evidencePanel.add(empty);
        } else {
            for (GnssEvidenceItem item : evidence) {
                JPanel row = new JPanel(new BorderLayout(6, 1));
                row.setOpaque(false);
                row.setBorder(new EmptyBorder(2, 2, 4, 2));
                row.setAlignmentX(Component.LEFT_ALIGNMENT);

                String ts = item.timestampMillis() > 0L
                        ? LaunchDetailsInspectorPanel.formatDateTime(item.timestampMillis(), tz)
                        : "\u2014";
                String headerText = (item.decisive() ? "[DECISIVE] " : "")
                        + nonBlankOr(item.source(), "Source")
                        + "  \u00B7  " + nonBlankOr(item.rawValue(), "\u2014")
                        + "  \u00B7  " + ts;
                JLabel top = new JLabel(headerText);
                top.setFont(top.getFont().deriveFont(item.decisive() ? Font.BOLD : Font.PLAIN));
                top.setForeground(colorForSeverity(item.severity()));

                JLabel reason = new JLabel(nonBlankOr(item.reason(), ""));
                reason.setFont(reason.getFont().deriveFont(item.decisive() ? Font.BOLD : Font.PLAIN,
                        Math.max(10f, reason.getFont().getSize2D() - 1f)));
                if (!item.decisive()) {
                    reason.setForeground(mutedForeground());
                }

                row.add(top, BorderLayout.NORTH);
                row.add(reason, BorderLayout.CENTER);
                evidencePanel.add(row);
            }
        }
        evidencePanel.revalidate();
        evidencePanel.repaint();
    }

    public void refreshActionButtonStates() {
        boolean hasState = currentState != null;
        boolean alreadyInObjectList = hasState && isInObjectList != null && isInObjectList.test(currentState);
        boolean hasTle = hasState && currentState.tle() != null;

        if (alreadyInObjectList) {
            addToObjectListButton.setText("In Object List");
            addToObjectListButton.setEnabled(false);
            addToObjectListButton.setToolTipText("This GNSS satellite is already in the Scenario Object List");
        } else {
            addToObjectListButton.setText("Add to Object List");
            addToObjectListButton.setEnabled(hasTle);
            addToObjectListButton.setToolTipText(hasTle
                    ? "Promote this satellite into the Scenario Object List"
                    : "Unavailable without matched TLE");
        }

        boolean hasPos = hasState && currentState.position() != null;
        centerMapsButton.setEnabled(hasPos);
        jumpToOutageButton.setEnabled(jumpTargetEpochMillis > 0L);
    }

    public JButton getAddToObjectListButton() {
        return addToObjectListButton;
    }

    public JButton getCenterMapsButton() {
        return centerMapsButton;
    }

    public JButton getJumpToOutageButton() {
        return jumpToOutageButton;
    }

    public JPanel getManualOverrideBanner() {
        return manualOverrideBanner;
    }

    public String getNoticeBannerText() {
        return noticeBannerLabel.getText();
    }

    public String getOutageInfoText() {
        return outageDetailArea.getText();
    }

    public JPanel getEvidencePanel() {
        return evidencePanel;
    }

    private static String formatNoticeHeader(GnssOutageNotice notice) {
        return nonBlankOr(notice.noticeId(), "Notice")
                + (nonBlank(notice.noticeType()) ? " (" + notice.noticeType() + ")" : "");
    }

    private static String formatNoticeDetail(GnssOutageNotice notice, TimeZone tz) {
        String start = LaunchDetailsInspectorPanel.formatDateTime(notice.startEpochMillis(), tz);
        String end = notice.endEpochMillis() > 0L
                ? LaunchDetailsInspectorPanel.formatDateTime(notice.endEpochMillis(), tz)
                : "UFN";
        return nonBlankOr(notice.noticeId(), "Notice") + " [" + start + " \u2192 " + end + "] "
                + nonBlankOr(notice.summary(), "");
    }

    private static Color colorForSeverity(GnssEvidenceItem.Severity severity) {
        if (severity == null) {
            return defaultForeground();
        }
        return switch (severity) {
            case OK -> GnssOverlay2DRenderer.colorForStatus(GnssDisplayStatus.OPERATIONAL);
            case WARN -> GnssOverlay2DRenderer.colorForStatus(GnssDisplayStatus.TESTING);
            case ERROR -> GnssOverlay2DRenderer.colorForStatus(GnssDisplayStatus.UNAVAILABLE);
            case MUTED -> mutedForeground();
        };
    }

    private static void addGridRow(
            JPanel grid,
            int row,
            String key1,
            JLabel val1,
            String key2,
            JLabel val2) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = row;
        gbc.insets = new Insets(2, 2, 2, 8);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        grid.add(createKeyLabel(key1), gbc);

        gbc.gridx = 1;
        gbc.weightx = 0.45;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        grid.add(val1, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        grid.add(createKeyLabel(key2), gbc);

        gbc.gridx = 3;
        gbc.weightx = 0.55;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(2, 2, 2, 0);
        grid.add(val2, gbc);
    }

    private static JLabel createKeyLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(mutedForeground());
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    private static JLabel createValueLabel() {
        JLabel l = new JLabel("\u2014");
        l.setMinimumSize(new Dimension(60, 16));
        return l;
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nonBlankOr(String s, String fallback) {
        return nonBlank(s) ? s.trim() : fallback;
    }

    private static Color defaultForeground() {
        Color c = UIManager.getColor("Label.foreground");
        return c != null ? c : Color.DARK_GRAY;
    }

    private static Color mutedForeground() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : new Color(130, 136, 145);
    }

    private final class ConstellationIcon implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                GnssOverlay2DRenderer.drawConstellationShape(
                        g2,
                        currentShapeConstellation,
                        x + 6.0,
                        y + 6.0,
                        5.0,
                        currentStatusColor,
                        new Color(18, 22, 28));
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
}

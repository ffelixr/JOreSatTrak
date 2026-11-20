package jsattrak.awareness.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import jsattrak.awareness.LaunchRecord;
import jsattrak.awareness.tle.LaunchTleStatus;

/**
 * Collapsible detail inspector displaying mission metadata, timezone-aware launch windows,
 * webcast link, and simulation/map/TLE actions for the selected launch.
 */
public class LaunchDetailsInspectorPanel extends JPanel {

    private static final DateTimeFormatter DATE_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final DateTimeFormatter TIME_ONLY_FMT =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter DATE_TIME_SEC_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private final Consumer<Long> onJumpToT0;
    private final BiConsumer<Double, Double> onCenterMaps;
    private final Consumer<LaunchRecord> onImportTles;
    private final Runnable onCheckNow;
    private final Runnable onOpenSatBrowser;

    private final JLabel missionTitleLabel;
    private final JLabel providerValueLabel;
    private final JLabel vehicleValueLabel;
    private final JLabel orbitValueLabel;
    private final JLabel cosparValueLabel;
    private final JLabel windowValueLabel;
    private final JLabel siteValueLabel;
    private final JLabel coordsValueLabel;
    private final JLabel statusValueLabel;
    private final JTextArea descriptionArea;
    private final JScrollPane descriptionScrollPane;
    private final JButton webcastButton;

    private final JButton jumpToT0Button;
    private final JButton centerMapsButton;
    private final JButton importTlesButton;
    private final JButton checkNowButton;
    private final JButton openSatBrowserButton;

    private LaunchRecord currentLaunch;
    private LaunchTleStatus currentTleStatus;
    private int currentMatchCount = -1;
    private String currentDisplayTzId;
    private String currentDescriptionText = "";
    private String currentWebcastUrl = "";

    public LaunchDetailsInspectorPanel(
            Consumer<Long> onJumpToT0,
            BiConsumer<Double, Double> onCenterMaps,
            Consumer<LaunchRecord> onImportTles) {
        this(onJumpToT0, onCenterMaps, onImportTles, null, null);
    }

    public LaunchDetailsInspectorPanel(
            Consumer<Long> onJumpToT0,
            BiConsumer<Double, Double> onCenterMaps,
            Consumer<LaunchRecord> onImportTles,
            Runnable onCheckNow,
            Runnable onOpenSatBrowser) {
        super(new BorderLayout(8, 6));
        this.onJumpToT0 = onJumpToT0;
        this.onCenterMaps = onCenterMaps;
        this.onImportTles = onImportTles;
        this.onCheckNow = onCheckNow;
        this.onOpenSatBrowser = onOpenSatBrowser;

        setBorder(new EmptyBorder(6, 8, 8, 8));

        // Header: mission title + clickable webcast link
        JPanel headerPanel = new JPanel(new BorderLayout(8, 2));
        headerPanel.setOpaque(false);
        missionTitleLabel = new JLabel("Select a launch to inspect mission details.");
        missionTitleLabel.setFont(
                missionTitleLabel.getFont().deriveFont(Font.BOLD, missionTitleLabel.getFont().getSize2D() + 1.0f));
        headerPanel.add(missionTitleLabel, BorderLayout.CENTER);

        webcastButton = createLinkButton();
        webcastButton.addActionListener(e -> openWebcastInBrowser());
        headerPanel.add(webcastButton, BorderLayout.EAST);
        add(headerPanel, BorderLayout.NORTH);

        // Center: metadata grid + optional description
        JPanel centerPanel = new JPanel(new BorderLayout(6, 6));
        centerPanel.setOpaque(false);

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);

        providerValueLabel = createValueLabel();
        vehicleValueLabel = createValueLabel();
        orbitValueLabel = createValueLabel();
        cosparValueLabel = createValueLabel();
        windowValueLabel = createValueLabel();
        siteValueLabel = createValueLabel();
        coordsValueLabel = createValueLabel();
        statusValueLabel = createValueLabel();

        addGridRow(grid, 0, "Window:", windowValueLabel, "Status:", statusValueLabel);
        addGridRow(grid, 1, "Provider:", providerValueLabel, "Vehicle:", vehicleValueLabel);
        addGridRow(grid, 2, "Target Orbit:", orbitValueLabel, "COSPAR ID:", cosparValueLabel);
        addGridRow(grid, 3, "Pad / Site:", siteValueLabel, "Coordinates:", coordsValueLabel);
        centerPanel.add(grid, BorderLayout.NORTH);

        descriptionArea = new JTextArea(2, 40);
        descriptionArea.setEditable(false);
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);
        descriptionArea.setOpaque(false);
        descriptionArea.setBorder(new EmptyBorder(2, 2, 2, 2));
        Font labelFont = UIManager.getFont("Label.font");
        if (labelFont != null) {
            descriptionArea.setFont(labelFont);
        }
        descriptionScrollPane = new JScrollPane(descriptionArea);
        descriptionScrollPane.setBorder(BorderFactory.createEmptyBorder());
        descriptionScrollPane.setOpaque(false);
        descriptionScrollPane.getViewport().setOpaque(false);
        descriptionScrollPane.setPreferredSize(new Dimension(400, 44));
        centerPanel.add(descriptionScrollPane, BorderLayout.CENTER);

        add(centerPanel, BorderLayout.CENTER);

        // Footer: action buttons
        JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actionsPanel.setOpaque(false);

        jumpToT0Button = new JButton("Jump Sim to T-0");
        jumpToT0Button.setToolTipText("Exit Real-Time mode and set simulation clock to launch NET");
        jumpToT0Button.addActionListener(e -> {
            if (currentLaunch != null && this.onJumpToT0 != null) {
                this.onJumpToT0.accept(currentLaunch.getNetEpochMillis());
            }
        });

        centerMapsButton = new JButton("Center Maps");
        centerMapsButton.setToolTipText("Center open 2D, 2D Polar, and 3D Earth views on the launch pad");
        centerMapsButton.addActionListener(e -> {
            if (currentLaunch != null && this.onCenterMaps != null) {
                this.onCenterMaps.accept(currentLaunch.getLatDeg(), currentLaunch.getLonDeg());
            }
        });

        importTlesButton = new JButton("Fetch / Import Payload TLEs...");
        importTlesButton.addActionListener(e -> {
            if (currentLaunch != null && this.onImportTles != null) {
                this.onImportTles.accept(currentLaunch);
            }
        });

        checkNowButton = new JButton("Check Now");
        checkNowButton.setToolTipText("Refresh launch & TLE feeds now");
        checkNowButton.addActionListener(e -> {
            if (this.onCheckNow != null) {
                this.onCheckNow.run();
            }
        });

        openSatBrowserButton = new JButton("Open Satellite Browser");
        openSatBrowserButton.setToolTipText("Open Satellite Browser to browse tle-new catalog manually");
        openSatBrowserButton.addActionListener(e -> {
            if (this.onOpenSatBrowser != null) {
                this.onOpenSatBrowser.run();
            }
        });

        actionsPanel.add(jumpToT0Button);
        actionsPanel.add(centerMapsButton);
        actionsPanel.add(importTlesButton);
        actionsPanel.add(checkNowButton);
        actionsPanel.add(openSatBrowserButton);
        add(actionsPanel, BorderLayout.SOUTH);

        setLaunch(null, LaunchTleStatus.NO_ORBITAL_PAYLOAD, 0, TimeZone.getTimeZone("UTC"));
    }

    /**
     * Populates the inspector with the given launch, TLE availability status, and display timezone.
     */
    public void setLaunch(LaunchRecord launch, LaunchTleStatus tleStatus, int matchCount, TimeZone displayTz) {
        TimeZone effectiveTz = displayTz != null ? displayTz : TimeZone.getTimeZone("UTC");
        String tzId = effectiveTz.getID();
        LaunchTleStatus effectiveStatus = tleStatus != null ? tleStatus : LaunchTleStatus.AWAITING_TLE;

        if (launch == null) {
            if (this.currentLaunch == null && this.currentDisplayTzId != null) {
                return;
            }
            this.currentLaunch = null;
            this.currentTleStatus = LaunchTleStatus.NO_ORBITAL_PAYLOAD;
            this.currentMatchCount = 0;
            this.currentDisplayTzId = tzId;
            this.currentDescriptionText = "";
            missionTitleLabel.setText("Select a launch to inspect mission details.");
            providerValueLabel.setText("\u2014");
            vehicleValueLabel.setText("\u2014");
            orbitValueLabel.setText("\u2014");
            cosparValueLabel.setText("\u2014");
            windowValueLabel.setText("\u2014");
            siteValueLabel.setText("\u2014");
            coordsValueLabel.setText("\u2014");
            statusValueLabel.setText("\u2014");
            descriptionArea.setText("");
            descriptionScrollPane.setVisible(false);
            updateWebcastButton("");
            jumpToT0Button.setEnabled(false);
            centerMapsButton.setEnabled(false);
            importTlesButton.setEnabled(false);
            importTlesButton.setText("Fetch / Import Payload TLEs...");
            importTlesButton.setToolTipText(null);
            checkNowButton.setVisible(false);
            openSatBrowserButton.setVisible(false);
            revalidate();
            repaint();
            return;
        }

        if (Objects.equals(this.currentLaunch, launch)
                && this.currentTleStatus == effectiveStatus
                && this.currentMatchCount == matchCount
                && Objects.equals(this.currentDisplayTzId, tzId)) {
            return;
        }

        boolean sameLaunchId = this.currentLaunch != null && Objects.equals(this.currentLaunch.getId(), launch.getId());
        this.currentLaunch = launch;
        this.currentTleStatus = effectiveStatus;
        this.currentMatchCount = matchCount;
        this.currentDisplayTzId = tzId;

        missionTitleLabel.setText(nonBlankOr(launch.getMissionName(), "Scheduled Launch"));
        providerValueLabel.setText(nonBlankOr(launch.getProvider(), "\u2014"));
        vehicleValueLabel.setText(nonBlankOr(launch.getVehicle(), "\u2014"));
        orbitValueLabel.setText(nonBlankOr(launch.getTargetOrbit(), launch.isOrbital() ? "Orbital" : "Suborbital"));
        cosparValueLabel.setText(nonBlankOr(launch.getCosparId(), "Pending"));
        windowValueLabel.setText(formatLaunchWindow(launch, effectiveTz));
        siteValueLabel.setText(formatSite(launch));
        coordsValueLabel.setText(formatCoordinates(launch));
        statusValueLabel.setText(formatStatusAndOutcome(launch));

        String desc = launch.getDescription() != null ? launch.getDescription().trim() : "";
        if (!sameLaunchId || !Objects.equals(this.currentDescriptionText, desc)) {
            this.currentDescriptionText = desc;
            descriptionArea.setText(desc);
            descriptionArea.setCaretPosition(0);
            descriptionScrollPane.setVisible(!desc.isEmpty());
        }

        updateWebcastButton(launch.getWebcastUrl());

        jumpToT0Button.setEnabled(launch.getNetEpochMillis() > 0L);
        centerMapsButton.setEnabled(Double.isFinite(launch.getLatDeg()) && Double.isFinite(launch.getLonDeg()));

        switch (effectiveStatus) {
            case TLES_READY -> {
                importTlesButton.setEnabled(true);
                importTlesButton.setText(matchCount > 0
                        ? "Fetch / Import Payload TLEs... (" + matchCount + ")"
                        : "Fetch / Import Payload TLEs...");
                importTlesButton.setToolTipText("Inspect and import matched payload TLEs into Object List");
                checkNowButton.setVisible(false);
                openSatBrowserButton.setVisible(false);
            }
            case SUPPLEMENTAL_READY -> {
                importTlesButton.setEnabled(true);
                importTlesButton.setText(matchCount > 0
                        ? "Fetch / Import Pre-launch TLEs... (" + matchCount + ")"
                        : "Fetch / Import Pre-launch TLEs...");
                importTlesButton.setToolTipText("Inspect and import Supplemental / Pre-launch TLEs");
                checkNowButton.setVisible(false);
                openSatBrowserButton.setVisible(false);
            }
            case AWAITING_TLE -> {
                importTlesButton.setEnabled(false);
                importTlesButton.setText("Fetch / Import Payload TLEs...");
                importTlesButton.setToolTipText(
                        "Awaiting TLE cataloging — use Check Now or Open Satellite Browser");
                checkNowButton.setVisible(true);
                openSatBrowserButton.setVisible(true);
            }
            case NO_ORBITAL_PAYLOAD -> {
                importTlesButton.setEnabled(false);
                importTlesButton.setText("Fetch / Import Payload TLEs...");
                importTlesButton.setToolTipText("Suborbital or failed launch — no orbital payload expected");
                checkNowButton.setVisible(false);
                openSatBrowserButton.setVisible(false);
            }
        }
        revalidate();
        repaint();
    }

    /**
     * Formats the launch window open–close interval (and NET if distinct) in {@code displayTz}.
     */
    public static String formatLaunchWindow(LaunchRecord launch, TimeZone displayTz) {
        if (launch == null) {
            return "\u2014";
        }
        TimeZone tz = displayTz != null ? displayTz : TimeZone.getTimeZone("UTC");
        ZoneId zoneId = tz.toZoneId();
        long net = launch.getNetEpochMillis();
        long start = launch.getWindowStartMillis() > 0L ? launch.getWindowStartMillis() : net;
        long end = launch.effectiveWindowEndMillis();
        if (start <= 0L && end <= 0L) {
            return "TBD";
        }

        ZonedDateTime zStart = Instant.ofEpochMilli(start).atZone(zoneId);
        ZonedDateTime zEnd = Instant.ofEpochMilli(end).atZone(zoneId);
        String tzLabel = formatZoneShortName(tz, start);

        String formatted;
        if (end > start) {
            if (zStart.toLocalDate().equals(zEnd.toLocalDate())) {
                formatted = DATE_TIME_FMT.format(zStart) + " \u2013 " + TIME_ONLY_FMT.format(zEnd) + " " + tzLabel;
            } else {
                formatted = DATE_TIME_FMT.format(zStart) + " \u2013 " + DATE_TIME_FMT.format(zEnd) + " " + tzLabel;
            }
            if (net > 0L && net != start) {
                ZonedDateTime zNet = Instant.ofEpochMilli(net).atZone(zoneId);
                formatted += " (NET " + TIME_ONLY_FMT.format(zNet) + ")";
            }
        } else {
            formatted = DATE_TIME_FMT.format(zStart) + " " + tzLabel;
        }
        return formatted;
    }

    /**
     * Formats a single epoch timestamp in {@code displayTz}.
     */
    public static String formatDateTime(long epochMillis, TimeZone displayTz) {
        if (epochMillis <= 0L) {
            return "\u2014";
        }
        TimeZone tz = displayTz != null ? displayTz : TimeZone.getTimeZone("UTC");
        ZonedDateTime zdt = Instant.ofEpochMilli(epochMillis).atZone(tz.toZoneId());
        return DATE_TIME_SEC_FMT.format(zdt) + " " + formatZoneShortName(tz, epochMillis);
    }

    /**
     * Validates that a webcast or mission URL uses a safe {@code http} or {@code https} URI with a host.
     */
    public static boolean isSafeWebcastUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            return scheme != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public JButton getJumpToT0Button() {
        return jumpToT0Button;
    }

    public JButton getCenterMapsButton() {
        return centerMapsButton;
    }

    public JButton getImportTlesButton() {
        return importTlesButton;
    }

    public JButton getCheckNowButton() {
        return checkNowButton;
    }

    public JButton getOpenSatBrowserButton() {
        return openSatBrowserButton;
    }

    public JButton getWebcastButton() {
        return webcastButton;
    }

    public JTextArea getDescriptionArea() {
        return descriptionArea;
    }

    public String getDisplayedWindowText() {
        return windowValueLabel.getText();
    }

    public String getMissionLabelText() {
        return missionTitleLabel.getText();
    }

    private void updateWebcastButton(String webcastUrl) {
        if (isSafeWebcastUrl(webcastUrl)) {
            this.currentWebcastUrl = webcastUrl.trim();
            webcastButton.setEnabled(true);
            webcastButton.setText("<html><u>Open Webcast / Mission Page</u> \u2197</html>");
            webcastButton.setToolTipText(this.currentWebcastUrl);
        } else {
            this.currentWebcastUrl = "";
            webcastButton.setEnabled(false);
            webcastButton.setText("No webcast link");
            webcastButton.setToolTipText(null);
        }
    }

    private void openWebcastInBrowser() {
        if (!isSafeWebcastUrl(currentWebcastUrl) || java.awt.GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(currentWebcastUrl));
            }
        } catch (Exception ignored) {
            // Avoid interrupting operator UI if OS browser launch is unavailable
        }
    }

    private static String formatZoneShortName(TimeZone tz, long epochMillis) {
        if ("UTC".equalsIgnoreCase(tz.getID()) || "GMT".equalsIgnoreCase(tz.getID())) {
            return "UTC";
        }
        return tz.getDisplayName(tz.inDaylightTime(new Date(epochMillis)), TimeZone.SHORT, Locale.ROOT);
    }

    private static String formatSite(LaunchRecord launch) {
        String spaceport = nonBlankOr(launch.getSpaceportName(), "");
        String pad = nonBlankOr(launch.getPadName(), "");
        String country = nonBlankOr(launch.getCountry(), "");

        StringBuilder sb = new StringBuilder();
        if (!spaceport.isEmpty()) {
            sb.append(spaceport);
        }
        if (!pad.isEmpty() && !pad.equalsIgnoreCase(spaceport)) {
            if (sb.length() > 0) {
                sb.append(" \u2014 ");
            }
            sb.append(pad);
        }
        if (!country.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" (").append(country).append(")");
            } else {
                sb.append(country);
            }
        }
        return sb.length() > 0 ? sb.toString() : "\u2014";
    }

    private static String formatCoordinates(LaunchRecord launch) {
        if (!Double.isFinite(launch.getLatDeg()) || !Double.isFinite(launch.getLonDeg())) {
            return "\u2014";
        }
        String base = String.format(Locale.ROOT, "%.4f\u00B0, %.4f\u00B0", launch.getLatDeg(), launch.getLonDeg());
        return launch.isFixedLandPad() ? base : base + " (Non-fixed)";
    }

    private static String formatStatusAndOutcome(LaunchRecord launch) {
        String status = nonBlankOr(launch.getStatus(), "");
        String outcome = nonBlankOr(launch.getOutcome(), "");
        if (!status.isEmpty() && !outcome.isEmpty() && !status.equalsIgnoreCase(outcome)) {
            return status + " / " + outcome;
        }
        return !outcome.isEmpty() ? outcome : (!status.isEmpty() ? status : "\u2014");
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
        gbc.weightx = 0.55;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        grid.add(val1, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        grid.add(createKeyLabel(key2), gbc);

        gbc.gridx = 3;
        gbc.weightx = 0.45;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(2, 2, 2, 0);
        grid.add(val2, gbc);
    }

    private static JLabel createKeyLabel(String text) {
        JLabel label = new JLabel(text);
        Color muted = UIManager.getColor("Label.disabledForeground");
        if (muted != null) {
            label.setForeground(muted);
        }
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    private static JLabel createValueLabel() {
        JLabel label = new JLabel("\u2014");
        label.setMinimumSize(new Dimension(60, 16));
        return label;
    }

    private static JButton createLinkButton() {
        JButton btn = new JButton("No webcast link");
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setFocusPainted(false);
        btn.setOpaque(false);
        btn.setHorizontalAlignment(SwingConstants.RIGHT);
        btn.setMargin(new Insets(0, 4, 0, 4));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Color linkColor = UIManager.getColor("Component.linkColor");
        btn.setForeground(linkColor != null ? linkColor : new Color(0x2B, 0x79, 0xC2));
        return btn;
    }

    private static String nonBlankOr(String candidate, String fallback) {
        return (candidate != null && !candidate.isBlank()) ? candidate.trim() : fallback;
    }
}

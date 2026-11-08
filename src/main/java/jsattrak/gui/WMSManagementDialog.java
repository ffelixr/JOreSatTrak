package jsattrak.gui;

import gov.nasa.worldwind.WorldWindow;
import gov.nasa.worldwindx.examples.RobustWMSLayersPanel;
import gov.nasa.worldwindx.examples.WMSLayersPanel;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Dialog to manage OGC Web Map Service (WMS) endpoints, connect/disconnect,
 * inspect available layers, and add/remove custom servers.
 */
public class WMSManagementDialog extends JDialog {

    public enum Status {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    public static class WMSEndpoint {
        private final String name;
        private final String url;
        private final boolean isDefault;
        private Status status = Status.DISCONNECTED;
        private String statusMessage = "Disconnected";
        private WMSLayersPanel layersPanel = null;

        public WMSEndpoint(String name, String url, boolean isDefault) {
            this.name = name;
            this.url = url;
            this.isDefault = isDefault;
        }

        public String getName() {
            return name;
        }

        public String getUrl() {
            return url;
        }

        public boolean isDefault() {
            return isDefault;
        }

        public Status getStatus() {
            return status;
        }

        public void setStatus(Status status) {
            this.status = status;
        }

        public String getStatusMessage() {
            return statusMessage;
        }

        public void setStatusMessage(String statusMessage) {
            this.statusMessage = statusMessage;
        }

        public WMSLayersPanel getLayersPanel() {
            return layersPanel;
        }

        public void setLayersPanel(WMSLayersPanel layersPanel) {
            this.layersPanel = layersPanel;
        }
    }

    private static final String PREF_CUSTOM_SERVERS = "jsattrak.wms.custom_servers";

    private final WorldWindow wwd;
    private final Component parentComponent;
    private final List<WMSEndpoint> endpoints = new ArrayList<>();

    private JPanel listContainerPanel;
    private JButton addBtn;
    private JButton removeBtn;

    public WMSManagementDialog(Window owner, WorldWindow wwd) {
        super(owner, "Manage Web Map Services", ModalityType.MODELESS);
        this.parentComponent = owner;
        this.wwd = wwd;
        initData();
        initComponents();
    }

    public WMSManagementDialog(Dialog owner, WorldWindow wwd) {
        super(owner, "Manage Web Map Services", false);
        this.parentComponent = owner;
        this.wwd = wwd;
        initData();
        initComponents();
    }

    private void initData() {
        // Default endpoints
        endpoints.add(new WMSEndpoint("NASA GIBS (EOSDIS)", "https://gibs.earthdata.nasa.gov/wms/epsg4326/best/wms.cgi", true));
        endpoints.add(new WMSEndpoint("EOX Maps Sentinel-2", "https://tiles.maps.eox.at/wms", true));
        endpoints.add(new WMSEndpoint("USGS National Map Topo", "https://basemap.nationalmap.gov/arcgis/services/USGSTopo/MapServer/WMSServer", true));

        // Load custom endpoints from Preferences
        try {
            Preferences prefs = Preferences.userNodeForPackage(WMSManagementDialog.class);
            String saved = prefs.get(PREF_CUSTOM_SERVERS, "");
            if (saved != null && !saved.trim().isEmpty()) {
                String[] entries = saved.split(";");
                for (String entry : entries) {
                    if (entry.trim().isEmpty()) continue;
                    String[] parts = entry.split("\\|", 2);
                    if (parts.length == 2) {
                        endpoints.add(new WMSEndpoint(parts[0].trim(), parts[1].trim(), false));
                    } else if (parts.length == 1) {
                        endpoints.add(new WMSEndpoint(parts[0].trim(), parts[0].trim(), false));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error loading custom WMS preferences: " + e.getMessage());
        }
    }

    private void saveCustomEndpoints() {
        try {
            Preferences prefs = Preferences.userNodeForPackage(WMSManagementDialog.class);
            StringBuilder sb = new StringBuilder();
            for (WMSEndpoint ep : endpoints) {
                if (!ep.isDefault()) {
                    if (sb.length() > 0) sb.append(";");
                    sb.append(ep.getName().replace("|", " ").replace(";", " "))
                      .append("|")
                      .append(ep.getUrl().replace("|", "%7C").replace(";", "%3B"));
                }
            }
            prefs.put(PREF_CUSTOM_SERVERS, sb.toString());
        } catch (Exception e) {
            System.err.println("Error saving custom WMS preferences: " + e.getMessage());
        }
    }

    private void initComponents() {
        setLayout(new BorderLayout(8, 8));

        // Top info header
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBorder(new EmptyBorder(10, 12, 6, 12));
        JLabel headerLabel = new JLabel("<html><b>Configured WMS Endpoints</b><br>"
                + "<font color='#666666'>Connect to load layer catalogues and overlay dynamic maps onto the 3D globe.</font></html>");
        headerPanel.add(headerLabel, BorderLayout.CENTER);
        add(headerPanel, BorderLayout.NORTH);

        // Center: scrollable list of servers
        listContainerPanel = new JPanel();
        listContainerPanel.setLayout(new BoxLayout(listContainerPanel, BoxLayout.Y_AXIS));
        listContainerPanel.setBorder(new EmptyBorder(4, 8, 4, 8));

        JScrollPane scrollPane = new JScrollPane(listContainerPanel);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(12);
        add(scrollPane, BorderLayout.CENTER);

        // Bottom toolbar
        JPanel bottomPanel = new JPanel(new BorderLayout(8, 8));
        bottomPanel.setBorder(new EmptyBorder(6, 12, 10, 12));

        JPanel leftActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        addBtn = new JButton("Add Endpoint...");
        addBtn.setIcon(loadIcon("/icons/gnome_2_18/window-new.png"));
        addBtn.addActionListener(e -> onAddEndpoint());
        leftActions.add(addBtn);

        removeBtn = new JButton("Delete Custom...");
        removeBtn.setIcon(loadIcon("/icons/other/Delete16.gif"));
        removeBtn.addActionListener(e -> onDeleteCustomEndpoint());
        leftActions.add(removeBtn);

        JPanel rightActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> setVisible(false));
        rightActions.add(closeBtn);

        bottomPanel.add(leftActions, BorderLayout.WEST);
        bottomPanel.add(rightActions, BorderLayout.EAST);
        add(bottomPanel, BorderLayout.SOUTH);

        rebuildServerListUI();

        setSize(650, 420);
        if (parentComponent != null) {
            Point p = parentComponent.getLocation();
            setLocation(Math.max(0, p.x + 30), Math.max(0, p.y + 30));
        } else {
            setLocationRelativeTo(null);
        }
    }

    private Icon loadIcon(String path) {
        try {
            java.net.URL url = getClass().getResource(path);
            if (url != null) {
                return new ImageIcon(url);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void rebuildServerListUI() {
        listContainerPanel.removeAll();

        for (int i = 0; i < endpoints.size(); i++) {
            WMSEndpoint ep = endpoints.get(i);
            JPanel rowPanel = createEndpointRow(ep);
            listContainerPanel.add(rowPanel);
            listContainerPanel.add(Box.createRigidArea(new Dimension(0, 6)));
        }

        listContainerPanel.revalidate();
        listContainerPanel.repaint();
    }

    private JPanel createEndpointRow(WMSEndpoint ep) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
        row.setPreferredSize(new Dimension(580, 56));
        row.setBorder(new CompoundBorder(
                new LineBorder(new Color(215, 215, 215), 1, true),
                new EmptyBorder(6, 10, 6, 10)
        ));

        // Left info: Name & URL
        JPanel leftInfo = new JPanel();
        leftInfo.setLayout(new BoxLayout(leftInfo, BoxLayout.Y_AXIS));
        leftInfo.setOpaque(false);

        JLabel nameLabel = new JLabel(ep.getName() + (ep.isDefault() ? " [Default]" : ""));
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 12f));
        leftInfo.add(nameLabel);

        JLabel urlLabel = new JLabel(ep.getUrl());
        urlLabel.setFont(urlLabel.getFont().deriveFont(Font.PLAIN, 11f));
        urlLabel.setForeground(Color.GRAY);
        leftInfo.add(Box.createRigidArea(new Dimension(0, 2)));
        leftInfo.add(urlLabel);

        row.add(leftInfo, BorderLayout.CENTER);

        // Right panel: Status label + Action buttons (Start, Stop, Layers)
        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        rightPanel.setOpaque(false);

        JLabel statusLabel = new JLabel();
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 11f));
        updateStatusLabel(statusLabel, ep);
        rightPanel.add(statusLabel);

        // Start button
        JButton startBtn = new JButton();
        startBtn.setToolTipText("Connect to WMS server and fetch layers");
        startBtn.setPreferredSize(new Dimension(28, 26));
        Icon playIcon = loadIcon("/icons/other/Play16.gif");
        if (playIcon != null) {
            startBtn.setIcon(playIcon);
        } else {
            startBtn.setText("▶");
        }

        // Stop button
        JButton stopBtn = new JButton();
        stopBtn.setToolTipText("Disconnect from WMS server");
        stopBtn.setPreferredSize(new Dimension(28, 26));
        Icon stopIcon = loadIcon("/icons/gnome_2_18/process-stop.png");
        if (stopIcon != null) {
            stopBtn.setIcon(stopIcon);
        } else {
            stopBtn.setText("■");
        }

        // View Layers button
        JButton viewLayersBtn = new JButton("Layers");
        viewLayersBtn.setToolTipText("Open Available Layers window for this server");
        viewLayersBtn.setIcon(loadIcon("/icons/other/folder-remote.png"));
        viewLayersBtn.setFont(viewLayersBtn.getFont().deriveFont(Font.PLAIN, 11f));
        viewLayersBtn.setEnabled(ep.getStatus() == Status.CONNECTED);

        // Button state logic
        if (ep.getStatus() == Status.CONNECTED) {
            startBtn.setEnabled(false);
            stopBtn.setEnabled(true);
        } else if (ep.getStatus() == Status.CONNECTING) {
            startBtn.setEnabled(false);
            stopBtn.setEnabled(true);
        } else {
            startBtn.setEnabled(true);
            stopBtn.setEnabled(false);
        }

        startBtn.addActionListener(e -> connectEndpoint(ep, statusLabel, startBtn, stopBtn, viewLayersBtn));
        stopBtn.addActionListener(e -> disconnectEndpoint(ep, statusLabel, startBtn, stopBtn, viewLayersBtn));
        viewLayersBtn.addActionListener(e -> openLayersWindow(ep));

        rightPanel.add(startBtn);
        rightPanel.add(stopBtn);
        rightPanel.add(viewLayersBtn);

        row.add(rightPanel, BorderLayout.EAST);

        return row;
    }

    private void updateStatusLabel(JLabel label, WMSEndpoint ep) {
        switch (ep.getStatus()) {
            case CONNECTED:
                label.setText("● Connected");
                label.setForeground(new Color(25, 135, 84)); // Bootstrap green
                break;
            case CONNECTING:
                label.setText("◌ Connecting...");
                label.setForeground(new Color(13, 110, 253)); // Blue
                break;
            case ERROR:
                label.setText("▲ " + ep.getStatusMessage());
                label.setForeground(new Color(220, 53, 69)); // Red
                break;
            case DISCONNECTED:
            default:
                label.setText("○ Disconnected");
                label.setForeground(Color.DARK_GRAY);
                break;
        }
    }

    private void connectEndpoint(WMSEndpoint ep, JLabel statusLabel, JButton startBtn, JButton stopBtn, JButton layersBtn) {
        ep.setStatus(Status.CONNECTING);
        ep.setStatusMessage("Connecting...");
        updateStatusLabel(statusLabel, ep);
        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        layersBtn.setEnabled(false);

        new Thread(() -> {
            try {
                // Pre-test URI
                new URI(ep.getUrl().trim());
                RobustWMSLayersPanel panel = new RobustWMSLayersPanel(wwd, ep.getUrl().trim(), new Dimension(350, 400));
                
                // Allow WorldWind background loader to initiate
                SwingUtilities.invokeLater(() -> {
                    ep.setLayersPanel(panel);
                    ep.setStatus(Status.CONNECTED);
                    ep.setStatusMessage("Connected");
                    updateStatusLabel(statusLabel, ep);
                    startBtn.setEnabled(false);
                    stopBtn.setEnabled(true);
                    layersBtn.setEnabled(true);
                });
            } catch (URISyntaxException ex) {
                SwingUtilities.invokeLater(() -> {
                    ep.setStatus(Status.ERROR);
                    ep.setStatusMessage("Invalid URL syntax");
                    updateStatusLabel(statusLabel, ep);
                    startBtn.setEnabled(true);
                    stopBtn.setEnabled(false);
                    layersBtn.setEnabled(false);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    ep.setStatus(Status.ERROR);
                    ep.setStatusMessage(ex.getMessage() != null ? ex.getMessage() : "Error connecting");
                    updateStatusLabel(statusLabel, ep);
                    startBtn.setEnabled(true);
                    stopBtn.setEnabled(false);
                    layersBtn.setEnabled(false);
                });
            }
        }).start();
    }

    private void disconnectEndpoint(WMSEndpoint ep, JLabel statusLabel, JButton startBtn, JButton stopBtn, JButton layersBtn) {
        ep.setLayersPanel(null);
        ep.setStatus(Status.DISCONNECTED);
        ep.setStatusMessage("Disconnected");
        updateStatusLabel(statusLabel, ep);
        startBtn.setEnabled(true);
        stopBtn.setEnabled(false);
        layersBtn.setEnabled(false);
    }

    private void openLayersWindow(WMSEndpoint ep) {
        if (ep.getLayersPanel() == null) {
            JOptionPane.showMessageDialog(this, "Server is not connected yet.", "WMS Layers", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JDialog layerDialog;
        if (this.getOwner() instanceof Frame) {
            layerDialog = new JDialog((Frame) this.getOwner(), ep.getName() + " - Available Layers", false);
        } else if (this.getOwner() instanceof Dialog) {
            layerDialog = new JDialog((Dialog) this.getOwner(), ep.getName() + " - Available Layers", false);
        } else {
            layerDialog = new JDialog((Frame) null, ep.getName() + " - Available Layers", false);
        }

        layerDialog.getContentPane().setLayout(new BorderLayout());
        layerDialog.getContentPane().add(ep.getLayersPanel(), BorderLayout.CENTER);

        JPanel closePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton close = new JButton("Close");
        close.addActionListener(e -> layerDialog.setVisible(false));
        closePanel.add(close);
        layerDialog.getContentPane().add(closePanel, BorderLayout.SOUTH);

        layerDialog.setSize(400, 450);
        layerDialog.setLocationRelativeTo(this);
        layerDialog.setVisible(true);
    }

    private void onAddEndpoint() {
        JTextField nameField = new JTextField(20);
        JTextField urlField = new JTextField("https://", 25);

        JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
        form.add(new JLabel("Service Display Name:"));
        form.add(nameField);
        form.add(new JLabel("WMS Server URL:"));
        form.add(urlField);

        int result = JOptionPane.showConfirmDialog(this, form, "Add WMS Server", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            String name = nameField.getText().trim();
            String url = urlField.getText().trim();

            if (url.isEmpty() || url.equalsIgnoreCase("https://") || url.equalsIgnoreCase("http://")) {
                JOptionPane.showMessageDialog(this, "Please enter a valid WMS server URL.", "Invalid URL", JOptionPane.ERROR_MESSAGE);
                return;
            }

            try {
                new URI(url);
            } catch (URISyntaxException e) {
                JOptionPane.showMessageDialog(this, "Invalid URL format: " + e.getMessage(), "Invalid URL", JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (name.isEmpty()) {
                name = url;
            }

            endpoints.add(new WMSEndpoint(name, url, false));
            saveCustomEndpoints();
            rebuildServerListUI();
        }
    }

    private void onDeleteCustomEndpoint() {
        List<WMSEndpoint> customList = new ArrayList<>();
        for (WMSEndpoint ep : endpoints) {
            if (!ep.isDefault()) {
                customList.add(ep);
            }
        }

        if (customList.isEmpty()) {
            JOptionPane.showMessageDialog(this, "There are no custom endpoints to delete.\n(Default endpoints cannot be deleted.)", "Delete Custom Endpoint", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String[] options = new String[customList.size()];
        for (int i = 0; i < customList.size(); i++) {
            options[i] = customList.get(i).getName() + " (" + customList.get(i).getUrl() + ")";
        }

        String selected = (String) JOptionPane.showInputDialog(
                this,
                "Select a custom WMS endpoint to delete:",
                "Delete Custom Endpoint",
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]
        );

        if (selected != null) {
            for (WMSEndpoint ep : customList) {
                if (selected.startsWith(ep.getName())) {
                    endpoints.remove(ep);
                    break;
                }
            }
            saveCustomEndpoints();
            rebuildServerListUI();
        }
    }
}

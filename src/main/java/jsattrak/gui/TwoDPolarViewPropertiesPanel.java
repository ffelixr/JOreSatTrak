/**
 * =====================================================================
 *   This file is part of JSatTrak / JOreSatTrak.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 * =====================================================================
 */

package jsattrak.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.Serializable;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JInternalFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

/**
 * Properties configuration panel for {@link J2DPolarEarthPanel}.
 */
public class TwoDPolarViewPropertiesPanel extends JPanel implements Serializable
{
    private J2DPolarEarthPanel polarPanel;
    private JSatTrak app;
    private JInternalFrame iframe;

    // UI Controls
    private JComboBox<String> poleModeComboBox;
    private JTextField minLatTextField;
    private JTextField zoomIncrementTextField;
    private JCheckBox latLongLinesCheckBox;

    private JPanel bgColorPanel;
    private JPanel discColorPanel;
    private JPanel gridColorPanel;

    private JCheckBox showDateTimeCheckBox;
    private JPanel dateTimeColorPanel;
    private JTextField dateTimeXOffsetTextField;
    private JTextField dateTimeYOffsetTextField;

    private JCheckBox drawRegionsCheckBox;
    private JPanel regionsColorPanel;
    private JComboBox<String> regionsComboBox;

    private JCheckBox showDarknessCheckBox;
    private JPanel sunColorPanel;
    private JTextField sunResTextField;
    private JSlider alphaSlider;
    private JLabel alphaValLabel;

    private JTextField drawingLineWidthTextField;
    private JTextField labelFontSizeTextField;

    public TwoDPolarViewPropertiesPanel()
    {
        initComponents();
    }

    public TwoDPolarViewPropertiesPanel(J2DPolarEarthPanel polarPanel, JSatTrak app)
    {
        this.polarPanel = polarPanel;
        this.app = app;
        initComponents();
        loadFromPanel();
    }

    public void setInternalFrame(JInternalFrame iframe)
    {
        this.iframe = iframe;
    }

    private void initComponents()
    {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel mainGrid = new JPanel(new GridLayout(1, 2, 10, 0));

        // Left Column: Projection, Colors, Date/Time
        JPanel leftCol = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;

        // Pole Mode
        gbc.gridx = 0;
        gbc.gridy = row;
        leftCol.add(new JLabel("Polar Mode:"), gbc);
        poleModeComboBox = new JComboBox<String>(new String[] {
            "North Pole",
            "South Pole",
            "Dual Pole (North & South)"
        });
        gbc.gridx = 1;
        leftCol.add(poleModeComboBox, gbc);
        row++;

        // Min Latitude
        gbc.gridx = 0;
        gbc.gridy = row;
        leftCol.add(new JLabel("Boundary Lat (0\u00B0-80\u00B0):"), gbc);
        minLatTextField = new JTextField("0.0", 6);
        gbc.gridx = 1;
        leftCol.add(minLatTextField, gbc);
        row++;

        // Zoom Increment
        gbc.gridx = 0;
        gbc.gridy = row;
        leftCol.add(new JLabel("Zoom Factor Step:"), gbc);
        zoomIncrementTextField = new JTextField("1.5", 6);
        gbc.gridx = 1;
        leftCol.add(zoomIncrementTextField, gbc);
        row++;

        // Lat/Lon Grid
        latLongLinesCheckBox = new JCheckBox("Show Polar Lat/Lon Grid");
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        leftCol.add(latLongLinesCheckBox, gbc);
        gbc.gridwidth = 1;
        row++;

        // Color pickers
        bgColorPanel = createColorSwatch(Color.DARK_GRAY);
        JButton bgColorBtn = new JButton("Window BG Color");
        bgColorBtn.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                pickColor(bgColorPanel, "Choose Window Background Color");
            }
        });
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        leftCol.add(createColorRow(bgColorPanel, bgColorBtn), gbc);
        row++;

        discColorPanel = createColorSwatch(new Color(15, 28, 48));
        JButton discColorBtn = new JButton("Ocean / Disc Color");
        discColorBtn.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                pickColor(discColorPanel, "Choose Polar Disc Ocean Color");
            }
        });
        gbc.gridy = row;
        leftCol.add(createColorRow(discColorPanel, discColorBtn), gbc);
        row++;

        gridColorPanel = createColorSwatch(new Color(110, 130, 160));
        JButton gridColorBtn = new JButton("Grid Line Color");
        gridColorBtn.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                pickColor(gridColorPanel, "Choose Polar Grid Color");
            }
        });
        gbc.gridy = row;
        leftCol.add(createColorRow(gridColorPanel, gridColorBtn), gbc);
        row++;

        // Date/Time section
        JPanel dtPanel = new JPanel(new GridBagLayout());
        dtPanel.setBorder(BorderFactory.createTitledBorder("Date / Time Overlay"));
        GridBagConstraints dgbc = new GridBagConstraints();
        dgbc.insets = new Insets(3, 3, 3, 3);
        dgbc.anchor = GridBagConstraints.WEST;

        showDateTimeCheckBox = new JCheckBox("Show Date/Time");
        dateTimeColorPanel = createColorSwatch(new Color(100, 180, 255));
        JButton dtColorBtn = new JButton("Text Color");
        dtColorBtn.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                pickColor(dateTimeColorPanel, "Choose Text Overlay Color");
            }
        });

        dgbc.gridx = 0;
        dgbc.gridy = 0;
        dgbc.gridwidth = 2;
        dtPanel.add(showDateTimeCheckBox, dgbc);
        dgbc.gridx = 2;
        dgbc.gridwidth = 2;
        dtPanel.add(createColorRow(dateTimeColorPanel, dtColorBtn), dgbc);

        dgbc.gridwidth = 1;
        dgbc.gridx = 0;
        dgbc.gridy = 1;
        dtPanel.add(new JLabel("x Offset:"), dgbc);
        dateTimeXOffsetTextField = new JTextField("10", 4);
        dgbc.gridx = 1;
        dtPanel.add(dateTimeXOffsetTextField, dgbc);
        dgbc.gridx = 2;
        dtPanel.add(new JLabel("y Offset:"), dgbc);
        dateTimeYOffsetTextField = new JTextField("6", 4);
        dgbc.gridx = 3;
        dtPanel.add(dateTimeYOffsetTextField, dgbc);

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        leftCol.add(dtPanel, gbc);

        // Right Column: Region Coastlines, Darkness, Line/Font
        JPanel rightCol = new JPanel(new GridBagLayout());
        GridBagConstraints rgbc = new GridBagConstraints();
        rgbc.insets = new Insets(4, 4, 4, 4);
        rgbc.anchor = GridBagConstraints.WEST;
        rgbc.fill = GridBagConstraints.HORIZONTAL;
        int rrow = 0;

        // Region Coastlines Panel
        JPanel regPanel = new JPanel(new GridBagLayout());
        regPanel.setBorder(BorderFactory.createTitledBorder("Coastline / Region Drawing"));
        GridBagConstraints cgbc = new GridBagConstraints();
        cgbc.insets = new Insets(3, 3, 3, 3);
        cgbc.anchor = GridBagConstraints.WEST;

        drawRegionsCheckBox = new JCheckBox("Draw Coastlines");
        regionsColorPanel = createColorSwatch(new Color(180, 210, 190));
        JButton regColorBtn = new JButton("Outline Color");
        regColorBtn.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                pickColor(regionsColorPanel, "Choose Coastline Outline Color");
            }
        });
        cgbc.gridx = 0;
        cgbc.gridy = 0;
        regPanel.add(drawRegionsCheckBox, cgbc);
        cgbc.gridx = 1;
        regPanel.add(createColorRow(regionsColorPanel, regColorBtn), cgbc);

        cgbc.gridx = 0;
        cgbc.gridy = 1;
        regPanel.add(new JLabel("Data Set:"), cgbc);
        regionsComboBox = new JComboBox<String>();
        cgbc.gridx = 1;
        regPanel.add(regionsComboBox, cgbc);

        rgbc.gridx = 0;
        rgbc.gridy = rrow++;
        rgbc.gridwidth = 2;
        rightCol.add(regPanel, rgbc);

        // Darkness Panel
        JPanel sunPanel = new JPanel(new GridBagLayout());
        sunPanel.setBorder(BorderFactory.createTitledBorder("Solar Terminator / Darkness"));
        GridBagConstraints sgbc = new GridBagConstraints();
        sgbc.insets = new Insets(3, 3, 3, 3);
        sgbc.anchor = GridBagConstraints.WEST;

        showDarknessCheckBox = new JCheckBox("Show Darkness Outline/Fill");
        sgbc.gridx = 0;
        sgbc.gridy = 0;
        sgbc.gridwidth = 3;
        sunPanel.add(showDarknessCheckBox, sgbc);

        sunColorPanel = createColorSwatch(Color.BLACK);
        JButton sunColorBtn = new JButton("Color");
        sunColorBtn.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                pickColor(sunColorPanel, "Choose Darkness Color");
            }
        });
        sgbc.gridwidth = 1;
        sgbc.gridx = 0;
        sgbc.gridy = 1;
        sunPanel.add(createColorRow(sunColorPanel, sunColorBtn), sgbc);
        sgbc.gridx = 1;
        sunPanel.add(new JLabel("Resolution:"), sgbc);
        sunResTextField = new JTextField("91", 4);
        sgbc.gridx = 2;
        sunPanel.add(sunResTextField, sgbc);

        sgbc.gridx = 0;
        sgbc.gridy = 2;
        sunPanel.add(new JLabel("Fill Alpha:"), sgbc);
        alphaSlider = new JSlider(0, 100, 55);
        alphaValLabel = new JLabel("0.55");
        alphaSlider.addChangeListener(new ChangeListener()
        {
            @Override
            public void stateChanged(ChangeEvent e)
            {
                int val = alphaSlider.getValue();
                alphaValLabel.setText(String.format("%.2f", val / 100.0));
            }
        });
        sgbc.gridx = 1;
        sunPanel.add(alphaSlider, sgbc);
        sgbc.gridx = 2;
        sunPanel.add(alphaValLabel, sgbc);

        rgbc.gridx = 0;
        rgbc.gridy = rrow++;
        rgbc.gridwidth = 2;
        rightCol.add(sunPanel, rgbc);

        // Line Width & Font Size
        rgbc.gridwidth = 1;
        rgbc.gridx = 0;
        rgbc.gridy = rrow;
        rightCol.add(new JLabel("Drawing Line Width:"), rgbc);
        drawingLineWidthTextField = new JTextField("2.0", 6);
        rgbc.gridx = 1;
        rightCol.add(drawingLineWidthTextField, rgbc);
        rrow++;

        rgbc.gridx = 0;
        rgbc.gridy = rrow;
        rightCol.add(new JLabel("Label Font Size:"), rgbc);
        labelFontSizeTextField = new JTextField("12.0", 6);
        rgbc.gridx = 1;
        rightCol.add(labelFontSizeTextField, rgbc);

        mainGrid.add(leftCol);
        mainGrid.add(rightCol);
        add(mainGrid, BorderLayout.CENTER);

        // Bottom Buttons (Apply, OK, Cancel)
        JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton applyBtn = new JButton("Apply");
        JButton okBtn = new JButton("Ok");
        JButton cancelBtn = new JButton("Cancel");

        applyBtn.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                saveSettings();
                if (app != null)
                {
                    app.forceRepainting(false);
                }
            }
        });

        okBtn.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                saveSettings();
                if (app != null)
                {
                    app.forceRepainting(false);
                }
                if (iframe != null)
                {
                    iframe.dispose();
                }
            }
        });

        cancelBtn.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                if (iframe != null)
                {
                    iframe.dispose();
                }
            }
        });

        buttonBar.add(applyBtn);
        buttonBar.add(okBtn);
        buttonBar.add(cancelBtn);
        add(buttonBar, BorderLayout.SOUTH);
    }

    private JPanel createColorSwatch(Color c)
    {
        JPanel p = new JPanel();
        p.setBackground(c);
        p.setPreferredSize(new Dimension(20, 20));
        p.setBorder(BorderFactory.createLineBorder(Color.BLACK));
        return p;
    }

    private JPanel createColorRow(JPanel swatch, JButton btn)
    {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.add(swatch);
        row.add(btn);
        return row;
    }

    private void pickColor(JPanel swatch, String title)
    {
        Color chosen = JColorChooser.showDialog(app, title, swatch.getBackground());
        if (chosen != null)
        {
            swatch.setBackground(chosen);
        }
    }

    private void loadFromPanel()
    {
        if (polarPanel == null)
        {
            return;
        }
        poleModeComboBox.setSelectedIndex(Math.max(0, Math.min(2, polarPanel.getPolarMode())));
        minLatTextField.setText("" + polarPanel.getMinAbsLatitudeDeg());
        zoomIncrementTextField.setText("" + polarPanel.getZoomIncrementMultiplier());
        latLongLinesCheckBox.setSelected(polarPanel.getShowLatLonLines());

        bgColorPanel.setBackground(polarPanel.getBackgroundColor());
        discColorPanel.setBackground(polarPanel.getDiscBackgroundColor());
        gridColorPanel.setBackground(polarPanel.getGridColor());

        showDateTimeCheckBox.setSelected(polarPanel.isShowDateTime());
        dateTimeColorPanel.setBackground(polarPanel.getDateTimeColor());
        dateTimeXOffsetTextField.setText("" + polarPanel.getXDateTimeOffset());
        dateTimeYOffsetTextField.setText("" + polarPanel.getYDateTimeOffset());

        // Populate region datasets
        regionsComboBox.removeAllItems();
        File dir = new File(polarPanel.getPathRegionFiles());
        File[] dirList = dir.listFiles();
        if (dirList != null)
        {
            for (File f : dirList)
            {
                if (f.isFile())
                {
                    regionsComboBox.addItem(f.getName());
                }
            }
        }
        if (regionsComboBox.getItemCount() == 0)
        {
            regionsComboBox.addItem(polarPanel.getRegionFileName());
        }
        regionsComboBox.setSelectedItem(polarPanel.getRegionFileName());
        drawRegionsCheckBox.setSelected(polarPanel.isRegionDrawOn());
        regionsColorPanel.setBackground(polarPanel.getRegionLineColor());

        showDarknessCheckBox.setSelected(polarPanel.isDrawSun());
        sunColorPanel.setBackground(polarPanel.getSunColor());
        sunResTextField.setText("" + polarPanel.getNumPtsSunFootPrint());
        int alphaInt = (int) Math.round(100.0 * polarPanel.getSunAlpha());
        alphaSlider.setValue(alphaInt);
        alphaValLabel.setText(String.format("%.2f", alphaInt / 100.0));

        drawingLineWidthTextField.setText("" + polarPanel.getDrawingLineWidth());
        labelFontSizeTextField.setText("" + polarPanel.getTextLabelFontSize());
    }

    public void saveSettings()
    {
        if (polarPanel == null)
        {
            return;
        }
        polarPanel.setPolarMode(poleModeComboBox.getSelectedIndex());

        try
        {
            polarPanel.setMinAbsLatitudeDeg(Double.parseDouble(minLatTextField.getText().trim()));
        }
        catch (Exception ignored)
        {
        }

        try
        {
            polarPanel.setZoomIncrementMultiplier(Double.parseDouble(zoomIncrementTextField.getText().trim()));
        }
        catch (Exception ignored)
        {
        }

        polarPanel.setShowLatLonLines(latLongLinesCheckBox.isSelected());
        polarPanel.setBackgroundColor(bgColorPanel.getBackground());
        polarPanel.setDiscBackgroundColor(discColorPanel.getBackground());
        polarPanel.setGridColor(gridColorPanel.getBackground());

        polarPanel.setShowDateTime(showDateTimeCheckBox.isSelected());
        polarPanel.setDateTimeColor(dateTimeColorPanel.getBackground());
        try
        {
            polarPanel.setXDateTimeOffset(Integer.parseInt(dateTimeXOffsetTextField.getText().trim()));
        }
        catch (Exception ignored)
        {
        }
        try
        {
            polarPanel.setYDateTimeOffset(Integer.parseInt(dateTimeYOffsetTextField.getText().trim()));
        }
        catch (Exception ignored)
        {
        }

        Object selRegion = regionsComboBox.getSelectedItem();
        String regionFile = selRegion != null ? selRegion.toString() : polarPanel.getRegionFileName();
        polarPanel.setRegionDrawingOptions(
                drawRegionsCheckBox.isSelected(),
                regionFile,
                regionsColorPanel.getBackground());

        polarPanel.setDrawSun(showDarknessCheckBox.isSelected());
        polarPanel.setSunColor(sunColorPanel.getBackground());
        try
        {
            polarPanel.setNumPtsSunFootPrint(Integer.parseInt(sunResTextField.getText().trim()));
        }
        catch (Exception ignored)
        {
        }
        polarPanel.setSunAlpha(alphaSlider.getValue() / 100.0f);

        try
        {
            polarPanel.setDrawingLineWidth(Float.parseFloat(drawingLineWidthTextField.getText().trim()));
        }
        catch (Exception ignored)
        {
        }
        try
        {
            polarPanel.setTextLabelFontSize(Float.parseFloat(labelFontSizeTextField.getText().trim()));
        }
        catch (Exception ignored)
        {
        }

        polarPanel.repaint();
    }
}


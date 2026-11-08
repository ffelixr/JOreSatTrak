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

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.Serializable;
import java.util.Hashtable;
import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JToggleButton;
import javax.swing.border.BevelBorder;
import jsattrak.objects.AbstractSatellite;
import jsattrak.objects.GroundStation;
import jsattrak.utilities.LandMassRegions;
import name.gano.astro.bodies.Sun;
import name.gano.astro.time.Time;

/**
 * Container JPanel for the 2D Polar Projection Window.
 */
public class J2DPolarEarthPanel extends JPanel implements ComponentListener, Serializable
{
    private Color backgroundColor = Color.DARK_GRAY;
    private J2dPolarEarthLabel polarLabel;

    public transient JPopupMenu popup;
    private transient JRadioButtonMenuItem northPoleMenu, southPoleMenu, dualPoleMenu;
    private transient JMenuItem latlonLinesMenu, toggleCoastlinesMenu, resetViewMenu, moreOptionsMenu;

    private transient JToggleButton zoomInToggleButton;
    private transient JToggleButton zoomOutToggleButton;
    private transient JToggleButton recenterToggleButton;

    private transient Time currentTime;
    private transient JSatTrak app;
    private Sun sun;

    private LandMassRegions landMass = new LandMassRegions();

    public J2DPolarEarthPanel()
    {
        super(true);
        Hashtable<String, AbstractSatellite> satHash = new Hashtable<String, AbstractSatellite>();
        Hashtable<String, GroundStation> gsHash = new Hashtable<String, GroundStation>();
        iniObject(satHash, gsHash);
    }

    public J2DPolarEarthPanel(
            Hashtable<String, AbstractSatellite> satHash,
            Hashtable<String, GroundStation> gsHash,
            JToggleButton zoomInToggleButton,
            JToggleButton zoomOutToggleButton,
            JToggleButton recenterToggleButton,
            Time currentTime,
            Sun sun,
            JSatTrak app)
    {
        super(true);
        this.currentTime = currentTime;
        this.app = app;
        this.sun = sun;
        this.zoomInToggleButton = zoomInToggleButton;
        this.zoomOutToggleButton = zoomOutToggleButton;
        this.recenterToggleButton = recenterToggleButton;

        iniObject(satHash, gsHash);
        this.setBackground(backgroundColor);
    }

    private void iniObject(Hashtable<String, AbstractSatellite> satHash, Hashtable<String, GroundStation> gsHash)
    {
        // Enable vector landmass outlines by default for the polar view
        try
        {
            landMass.setRegionOptions(true, "worldlandmass.rl", new Color(180, 210, 190));
        }
        catch (Exception e)
        {
            // Ignore if region file is missing in headless test environment
        }

        polarLabel = new J2dPolarEarthLabel(satHash, gsHash, backgroundColor, currentTime, sun, this);
        polarLabel.setHorizontalAlignment(JLabel.CENTER);

        super.addComponentListener(this);
        setLayout(new GridLayout(1, 1));
        add(polarLabel);

        setMinimumSize(new Dimension(0, 0));
        setMaximumSize(new Dimension(0, 0));

        // Build Popup Menu
        popup = new JPopupMenu();
        ActionListener menuListener = new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent event)
            {
                Object src = event.getSource();
                if (src == northPoleMenu)
                {
                    setPolarMode(J2dPolarEarthLabel.MODE_NORTH);
                }
                else if (src == southPoleMenu)
                {
                    setPolarMode(J2dPolarEarthLabel.MODE_SOUTH);
                }
                else if (src == dualPoleMenu)
                {
                    setPolarMode(J2dPolarEarthLabel.MODE_DUAL);
                }
                else if (src == latlonLinesMenu)
                {
                    polarLabel.showLatLong = !polarLabel.showLatLong;
                    polarLabel.repaint();
                }
                else if (src == toggleCoastlinesMenu)
                {
                    setRegionDrawingOptions(!isRegionDrawOn(), getRegionFileName(), getRegionLineColor());
                }
                else if (src == resetViewMenu)
                {
                    polarLabel.resetZoomAndPan();
                    polarLabel.repaint();
                }
                else if (src == moreOptionsMenu)
                {
                    if (getApp() != null)
                    {
                        getApp().open2dPolarWindowOptions(J2DPolarEarthPanel.this);
                    }
                }
            }
        };

        ButtonGroup poleGroup = new ButtonGroup();
        northPoleMenu = new JRadioButtonMenuItem("North Pole View", true);
        southPoleMenu = new JRadioButtonMenuItem("South Pole View", false);
        dualPoleMenu = new JRadioButtonMenuItem("Dual Pole View (North & South)", false);
        poleGroup.add(northPoleMenu);
        poleGroup.add(southPoleMenu);
        poleGroup.add(dualPoleMenu);

        northPoleMenu.addActionListener(menuListener);
        southPoleMenu.addActionListener(menuListener);
        dualPoleMenu.addActionListener(menuListener);

        popup.add(northPoleMenu);
        popup.add(southPoleMenu);
        popup.add(dualPoleMenu);
        popup.addSeparator();

        latlonLinesMenu = new JMenuItem("Toggle Lat/Lon Grid");
        latlonLinesMenu.addActionListener(menuListener);
        popup.add(latlonLinesMenu);

        toggleCoastlinesMenu = new JMenuItem("Toggle Coastlines");
        toggleCoastlinesMenu.addActionListener(menuListener);
        popup.add(toggleCoastlinesMenu);

        resetViewMenu = new JMenuItem("Reset Zoom / View");
        resetViewMenu.addActionListener(menuListener);
        popup.add(resetViewMenu);
        popup.addSeparator();

        moreOptionsMenu = new JMenuItem("More Options...");
        moreOptionsMenu.addActionListener(menuListener);
        popup.add(moreOptionsMenu);

        popup.setBorder(new BevelBorder(BevelBorder.RAISED));

        MousePopupListener mouseHandler = new MousePopupListener();
        addMouseListener(mouseHandler);
        addMouseMotionListener(mouseHandler);
        addMouseWheelListener(mouseHandler);
    }

    class MousePopupListener extends MouseAdapter
    {
        private Point lastDragPt = null;

        @Override
        public void mousePressed(MouseEvent e)
        {
            checkPopup(e);
            lastDragPt = e.getPoint();

            if (e.getButton() == MouseEvent.BUTTON2)
            {
                double[] ll = polarLabel.findLLFromScreenXY(e.getX(), e.getY());
                if (ll != null)
                {
                    System.out.println(String.format("Polar Loc (Lat, Lon): %.3f, %.3f", ll[0], ll[1]));
                }
                if (zoomInToggleButton != null)
                {
                    zoomInToggleButton.setSelected(false);
                }
                if (zoomOutToggleButton != null)
                {
                    zoomOutToggleButton.setSelected(false);
                }
                if (recenterToggleButton != null)
                {
                    recenterToggleButton.setSelected(false);
                }
            }

            if (e.getButton() == MouseEvent.BUTTON1)
            {
                if (zoomInToggleButton != null && zoomInToggleButton.isSelected())
                {
                    zoomAtPoint(e.getX(), e.getY(), true);
                }
                else if (zoomOutToggleButton != null && zoomOutToggleButton.isSelected())
                {
                    zoomAtPoint(e.getX(), e.getY(), false);
                }
                else if (recenterToggleButton != null && recenterToggleButton.isSelected())
                {
                    recenterAtPoint(e.getX(), e.getY());
                }
                else if (e.getClickCount() == 2)
                {
                    polarLabel.resetZoomAndPan();
                    polarLabel.repaint();
                }
            }
        }

        @Override
        public void mouseClicked(MouseEvent e)
        {
            checkPopup(e);
        }

        @Override
        public void mouseReleased(MouseEvent e)
        {
            checkPopup(e);
            lastDragPt = null;
        }

        @Override
        public void mouseDragged(MouseEvent e)
        {
            if (lastDragPt != null && !e.isPopupTrigger())
            {
                int dx = e.getX() - lastDragPt.x;
                int dy = e.getY() - lastDragPt.y;
                lastDragPt = e.getPoint();

                int[][] discs = polarLabel.computeDiscGeometries(polarLabel.getWidth(), polarLabel.getHeight());
                if (discs.length > 0 && discs[0][3] > 0)
                {
                    double radius = discs[0][3];
                    polarLabel.setPanXNorm(polarLabel.getPanXNorm() + dx / radius);
                    polarLabel.setPanYNorm(polarLabel.getPanYNorm() + dy / radius);
                    polarLabel.repaint();
                }
            }
        }

        @Override
        public void mouseMoved(MouseEvent e)
        {
            double[] ll = polarLabel.findLLFromScreenXY(e.getX(), e.getY());
            if (ll != null)
            {
                String ns = ll[0] >= 0 ? "N" : "S";
                String ew = ll[1] >= 0 ? "E" : "W";
                polarLabel.setHoverCoordString(
                        String.format("%.2f\u00B0%s, %.2f\u00B0%s", Math.abs(ll[0]), ns, Math.abs(ll[1]), ew));
            }
            else
            {
                polarLabel.setHoverCoordString("");
            }
            polarLabel.repaint();
        }

        @Override
        public void mouseExited(MouseEvent e)
        {
            polarLabel.setHoverCoordString("");
            polarLabel.repaint();
        }

        @Override
        public void mouseWheelMoved(MouseWheelEvent e)
        {
            boolean zoomIn = e.getWheelRotation() < 0;
            zoomAtPoint(e.getX(), e.getY(), zoomIn);
        }

        private void zoomAtPoint(int x, int y, boolean zoomIn)
        {
            double oldZoom = polarLabel.getZoomFactor();
            double mult = polarLabel.getZoomIncrementMultiplier();
            double newZoom = zoomIn ? (oldZoom * mult) : (oldZoom / mult);
            if (newZoom <= 1.0001)
            {
                polarLabel.resetZoomAndPan();
            }
            else
            {
                polarLabel.setZoomFactor(newZoom);
            }
            polarLabel.repaint();
        }

        private void recenterAtPoint(int x, int y)
        {
            int[][] discs = polarLabel.computeDiscGeometries(polarLabel.getWidth(), polarLabel.getHeight());
            for (int[] d : discs)
            {
                int cx = d[1];
                int cy = d[2];
                int r = d[3];
                if (Math.hypot(x - cx, y - cy) <= r && r > 0)
                {
                    double dxNorm = (cx - x) / (double) r;
                    double dyNorm = (cy - y) / (double) r;
                    polarLabel.setPanXNorm(polarLabel.getPanXNorm() + dxNorm);
                    polarLabel.setPanYNorm(polarLabel.getPanYNorm() + dyNorm);
                    polarLabel.repaint();
                    break;
                }
            }
        }

        private void checkPopup(MouseEvent e)
        {
            if (e.isPopupTrigger())
            {
                syncPopupRadioButtons();
                popup.show(J2DPolarEarthPanel.this, e.getX(), e.getY());
            }
        }
    }

    private void syncPopupRadioButtons()
    {
        if (northPoleMenu == null)
        {
            return;
        }
        int mode = polarLabel.getPolarMode();
        northPoleMenu.setSelected(mode == J2dPolarEarthLabel.MODE_NORTH);
        southPoleMenu.setSelected(mode == J2dPolarEarthLabel.MODE_SOUTH);
        dualPoleMenu.setSelected(mode == J2dPolarEarthLabel.MODE_DUAL);
    }

    @Override
    public void componentResized(ComponentEvent e)
    {
        polarLabel.repaint();
    }

    @Override
    public void componentHidden(ComponentEvent e)
    {
    }

    @Override
    public void componentMoved(ComponentEvent e)
    {
    }

    @Override
    public void componentShown(ComponentEvent e)
    {
    }

    public BufferedImage offScreenRender()
    {
        int w = Math.max(1, this.getWidth());
        int h = Math.max(1, this.getHeight());
        BufferedImage buff = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics g = buff.getGraphics();
        polarLabel.paintComponent(g);
        g.dispose();
        return buff;
    }

    // Delegated properties
    public JSatTrak getApp()
    {
        return app;
    }

    public J2dPolarEarthLabel getPolarLabel()
    {
        return polarLabel;
    }

    public LandMassRegions getLandMass()
    {
        return landMass;
    }

    public void setSatHashTable(Hashtable<String, AbstractSatellite> satHash)
    {
        polarLabel.setSatHashTable(satHash);
    }

    public int getPolarMode()
    {
        return polarLabel.getPolarMode();
    }

    public void setPolarMode(int mode)
    {
        polarLabel.setPolarMode(mode);
        syncPopupRadioButtons();
        polarLabel.repaint();
    }

    public double getMinAbsLatitudeDeg()
    {
        return polarLabel.getMinAbsLatitudeDeg();
    }

    public void setMinAbsLatitudeDeg(double minAbsLatDeg)
    {
        polarLabel.setMinAbsLatitudeDeg(minAbsLatDeg);
        polarLabel.repaint();
    }

    public double getZoomFactor()
    {
        return polarLabel.getZoomFactor();
    }

    public void setZoomFactor(double zoomFactor)
    {
        polarLabel.setZoomFactor(zoomFactor);
        polarLabel.repaint();
    }

    public double getZoomIncrementMultiplier()
    {
        return polarLabel.getZoomIncrementMultiplier();
    }

    public void setZoomIncrementMultiplier(double mult)
    {
        polarLabel.setZoomIncrementMultiplier(mult);
    }

    public double getPanXNorm()
    {
        return polarLabel.getPanXNorm();
    }

    public void setPanXNorm(double panXNorm)
    {
        polarLabel.setPanXNorm(panXNorm);
    }

    public double getPanYNorm()
    {
        return polarLabel.getPanYNorm();
    }

    public void setPanYNorm(double panYNorm)
    {
        polarLabel.setPanYNorm(panYNorm);
    }

    public boolean getShowLatLonLines()
    {
        return polarLabel.showLatLong;
    }

    public void setShowLatLonLines(boolean show)
    {
        polarLabel.showLatLong = show;
        polarLabel.repaint();
    }

    public Color getBackgroundColor()
    {
        return backgroundColor;
    }

    public void setBackgroundColor(Color backgroundColor)
    {
        this.backgroundColor = backgroundColor;
        this.setBackground(backgroundColor);
        if (polarLabel != null)
        {
            polarLabel.setBackgroundColor(backgroundColor);
            polarLabel.repaint();
        }
    }

    public Color getDiscBackgroundColor()
    {
        return polarLabel.getDiscBackgroundColor();
    }

    public void setDiscBackgroundColor(Color color)
    {
        polarLabel.setDiscBackgroundColor(color);
        polarLabel.repaint();
    }

    public Color getGridColor()
    {
        return polarLabel.getGridColor();
    }

    public void setGridColor(Color color)
    {
        polarLabel.setGridColor(color);
        polarLabel.repaint();
    }

    public boolean isShowDateTime()
    {
        return polarLabel.isShowDateTime();
    }

    public void setShowDateTime(boolean showDateTime)
    {
        polarLabel.setShowDateTime(showDateTime);
    }

    public int getXDateTimeOffset()
    {
        return polarLabel.getXDateTimeOffset();
    }

    public void setXDateTimeOffset(int offset)
    {
        polarLabel.setXDateTimeOffset(offset);
    }

    public int getYDateTimeOffset()
    {
        return polarLabel.getYDateTimeOffset();
    }

    public void setYDateTimeOffset(int offset)
    {
        polarLabel.setYDateTimeOffset(offset);
    }

    public Color getDateTimeColor()
    {
        return polarLabel.getDateTimeColor();
    }

    public void setDateTimeColor(Color color)
    {
        polarLabel.setDateTimeColor(color);
    }

    public boolean isDrawSun()
    {
        return polarLabel.isDrawSun();
    }

    public void setDrawSun(boolean drawSun)
    {
        polarLabel.setDrawSun(drawSun);
    }

    public Color getSunColor()
    {
        return polarLabel.getSunColor();
    }

    public void setSunColor(Color sunColor)
    {
        polarLabel.setSunColor(sunColor);
    }

    public int getNumPtsSunFootPrint()
    {
        return polarLabel.getNumPtsSunFootPrint();
    }

    public void setNumPtsSunFootPrint(int numPts)
    {
        polarLabel.setNumPtsSunFootPrint(numPts);
    }

    public float getSunAlpha()
    {
        return polarLabel.getSunAlpha();
    }

    public void setSunAlpha(float sunAlpha)
    {
        polarLabel.setSunAlpha(sunAlpha);
    }

    public String getPathRegionFiles()
    {
        return landMass.getRootDir();
    }

    public String getRegionFileName()
    {
        return landMass.getDataFileName();
    }

    public boolean isRegionDrawOn()
    {
        return landMass.isShowLandMassOutlines();
    }

    public Color getRegionLineColor()
    {
        return landMass.getLandOutlineColor();
    }

    public void setRegionDrawingOptions(boolean show, String fileName, Color color)
    {
        landMass.setRegionOptions(show, fileName, color);
        polarLabel.repaint();
    }

    public float getDrawingLineWidth()
    {
        return polarLabel.getDrawingLineWidth();
    }

    public void setDrawingLineWidth(float lineWidth)
    {
        polarLabel.setDrawingLineWidth(lineWidth);
    }

    public float getTextLabelFontSize()
    {
        return polarLabel.getTextLabelFontSize();
    }

    public void setTextLabelFontSize(float fontSize)
    {
        polarLabel.setTextLabelFontSize(fontSize);
    }
}

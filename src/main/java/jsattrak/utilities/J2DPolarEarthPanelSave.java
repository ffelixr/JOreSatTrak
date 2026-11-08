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

package jsattrak.utilities;

import java.awt.Color;
import java.awt.Dimension;
import java.io.Serializable;
import javax.swing.JInternalFrame;
import jsattrak.gui.J2DPolarEarthPanel;

/**
 * Persistence state snapshot for {@link J2DPolarEarthPanel} in .jst scenario files.
 */
public class J2DPolarEarthPanelSave implements Serializable
{
    private int xPos;
    private int yPos;
    private int width;
    private int height;

    private int polarMode;
    private double minAbsLatitudeDeg;
    private double zoomFactor;
    private double zoomIncrementMultiplier;
    private double panXNorm;
    private double panYNorm;

    private Color backgroundColor;
    private Color discBackgroundColor;
    private Color gridColor;
    private boolean showLatLong;

    private boolean showDateTime;
    private int xDateTimeOffset;
    private int yDateTimeOffset;
    private Color dateTimeColor;

    private boolean drawSun;
    private Color sunColor;
    private int numPtsSunFootPrint;
    private float sunAlpha;

    private Color landOutlineColor;
    private String dataFileName;
    private boolean showLandMassOutlines;

    private float drawingLineWidth = 2.0f;
    private float textLabelFontSize = 12.0f;

    public J2DPolarEarthPanelSave(J2DPolarEarthPanel panel, int x, int y, Dimension d)
    {
        this.xPos = x;
        this.yPos = y;
        this.width = d.width;
        this.height = d.height;

        this.polarMode = panel.getPolarMode();
        this.minAbsLatitudeDeg = panel.getMinAbsLatitudeDeg();
        this.zoomFactor = panel.getZoomFactor();
        this.zoomIncrementMultiplier = panel.getZoomIncrementMultiplier();
        this.panXNorm = panel.getPanXNorm();
        this.panYNorm = panel.getPanYNorm();

        this.backgroundColor = panel.getBackgroundColor();
        this.discBackgroundColor = panel.getDiscBackgroundColor();
        this.gridColor = panel.getGridColor();
        this.showLatLong = panel.getShowLatLonLines();

        this.showDateTime = panel.isShowDateTime();
        this.xDateTimeOffset = panel.getXDateTimeOffset();
        this.yDateTimeOffset = panel.getYDateTimeOffset();
        this.dateTimeColor = panel.getDateTimeColor();

        this.drawSun = panel.isDrawSun();
        this.sunColor = panel.getSunColor();
        this.numPtsSunFootPrint = panel.getNumPtsSunFootPrint();
        this.sunAlpha = panel.getSunAlpha();

        this.landOutlineColor = panel.getRegionLineColor();
        this.dataFileName = panel.getRegionFileName();
        this.showLandMassOutlines = panel.isRegionDrawOn();

        this.drawingLineWidth = panel.getDrawingLineWidth();
        this.textLabelFontSize = panel.getTextLabelFontSize();
    }

    public void copySettings2PanelAndFrame(J2DPolarEarthPanel newPanel, JInternalFrame iframe)
    {
        iframe.setSize(width, height);
        iframe.setLocation(xPos, yPos);

        newPanel.setPolarMode(polarMode);
        newPanel.setMinAbsLatitudeDeg(minAbsLatitudeDeg);
        newPanel.setZoomFactor(zoomFactor);
        newPanel.setZoomIncrementMultiplier(zoomIncrementMultiplier);
        newPanel.setPanXNorm(panXNorm);
        newPanel.setPanYNorm(panYNorm);

        if (backgroundColor != null)
        {
            newPanel.setBackgroundColor(backgroundColor);
        }
        if (discBackgroundColor != null)
        {
            newPanel.setDiscBackgroundColor(discBackgroundColor);
        }
        if (gridColor != null)
        {
            newPanel.setGridColor(gridColor);
        }
        newPanel.setShowLatLonLines(showLatLong);

        newPanel.setShowDateTime(showDateTime);
        newPanel.setXDateTimeOffset(xDateTimeOffset);
        newPanel.setYDateTimeOffset(yDateTimeOffset);
        if (dateTimeColor != null)
        {
            newPanel.setDateTimeColor(dateTimeColor);
        }

        newPanel.setDrawSun(drawSun);
        if (sunColor != null)
        {
            newPanel.setSunColor(sunColor);
        }
        newPanel.setNumPtsSunFootPrint(numPtsSunFootPrint);
        newPanel.setSunAlpha(sunAlpha);

        if (dataFileName != null && landOutlineColor != null)
        {
            newPanel.setRegionDrawingOptions(showLandMassOutlines, dataFileName, landOutlineColor);
        }

        newPanel.setDrawingLineWidth(drawingLineWidth);
        newPanel.setTextLabelFontSize(textLabelFontSize);
        newPanel.repaint();
    }
}


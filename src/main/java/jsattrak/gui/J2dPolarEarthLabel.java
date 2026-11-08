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

import gov.nasa.worldwind.geom.LatLon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.ActionEvent;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.io.Serializable;
import java.text.DecimalFormat;
import java.util.Hashtable;
import java.util.Vector;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JLabel;
import javax.swing.KeyStroke;
import jsattrak.objects.AbstractSatellite;
import jsattrak.objects.GroundStation;
import jsattrak.utilities.LandMassRegions;
import name.gano.astro.AstroConst;
import name.gano.astro.bodies.Sun;
import name.gano.astro.time.Time;

/**
 * 2D Azimuthal Equidistant Polar Projection rendering component.
 * Supports North Pole, South Pole, and Dual-Pole (North + South) views
 * with vector landmass coastlines, polar lat/lon grids, solar terminator
 * shading, ground stations, satellite ground tracks, and footprints.
 */
public class J2dPolarEarthLabel extends JLabel implements Serializable
{
    public static final int MODE_NORTH = 0;
    public static final int MODE_SOUTH = 1;
    public static final int MODE_DUAL = 2;

    private int polarMode = MODE_NORTH;

    // Minimum absolute latitude (degrees, 0 = Equator, 30 = 30 deg, 60 = Polar cap)
    private double minAbsLatitudeDeg = 0.0;

    // Zoom and pan parameters
    private double zoomFactor = 1.0;
    private double zoomIncrementMultiplier = 1.5;
    private double panXNorm = 0.0; // normalized pan offset (-1 to +1 of radius)
    private double panYNorm = 0.0;

    // Display options
    private boolean showDateTime = true;
    private int xDateTimeOffset = 10;
    private int yDateTimeOffset = 6;
    private Color dateTimeColor = new Color(100, 180, 255);

    public boolean showLatLong = true;
    private Color gridColor = new Color(110, 130, 160);
    private Color discBackgroundColor = new Color(15, 28, 48);
    private Color backgroundColor = Color.DARK_GRAY;

    // Sun / terminator options
    private boolean drawSun = true;
    private Color sunColor = Color.BLACK;
    private int numPtsSunFootPrint = 91;
    private float sunAlpha = 0.55f;

    // Line width and font size
    private float drawingLineWidth = 2.0f;
    private float textLabelFontSize = 12.0f;

    // Hover coordinate readout
    private String hoverCoordString = "";

    // References to simulation state
    private transient Hashtable<String, AbstractSatellite> satHash = new Hashtable<String, AbstractSatellite>();
    private transient Hashtable<String, GroundStation> gsHash = new Hashtable<String, GroundStation>();
    private Sun sun;
    private transient Time currentTime;
    private transient J2DPolarEarthPanel polarPanel;
    private transient RenderingHints renderHints;

    // Layout cache from last paint
    private int lastTotalWidth = 0;
    private int lastTotalHeight = 0;

    public boolean showFPS = false;
    private final DecimalFormat df = new DecimalFormat("#,##0.000");

    public J2dPolarEarthLabel(
            Hashtable<String, AbstractSatellite> satHash,
            Hashtable<String, GroundStation> gsHash,
            Color backgroundColor,
            Time currentTime,
            Sun sun,
            J2DPolarEarthPanel polarPanel)
    {
        this.satHash = satHash;
        this.gsHash = gsHash;
        this.backgroundColor = backgroundColor;
        this.currentTime = currentTime;
        this.sun = sun;
        this.polarPanel = polarPanel;

        renderHints = new RenderingHints(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Action toggleShowFPS = new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                showFPS = !showFPS;
                if (J2dPolarEarthLabel.this.polarPanel != null)
                {
                    J2dPolarEarthLabel.this.polarPanel.repaint();
                }
            }
        };
        this.getInputMap().put(KeyStroke.getKeyStroke("F"), "toggleShowFPS");
        this.getActionMap().put("toggleShowFPS", toggleShowFPS);
    }

    /**
     * Computes the maximum visible angular co-latitude (degrees from pole)
     * given minimum absolute latitude and zoom factor.
     */
    public double getMaxAngularRadiusDeg()
    {
        double baseSpan = Math.max(5.0, 90.0 - minAbsLatitudeDeg);
        return Math.max(1.0, baseSpan / Math.max(1.0, zoomFactor));
    }

    /**
     * Converts geographic (latDeg, lonDeg) to screen pixel coordinates (x, y)
     * in an Azimuthal Equidistant polar projection.
     *
     * @param latDeg             Latitude in degrees [-90, 90]
     * @param lonDeg             Longitude in degrees [-180, 180]
     * @param poleSign           +1 for North Pole, -1 for South Pole
     * @param cx                 Disc center X in pixels (including any pan offset)
     * @param cy                 Disc center Y in pixels (including any pan offset)
     * @param radiusPx           Disc outer radius in pixels
     * @param maxAngularRadiusDeg Angular co-latitude span from pole to outer rim in degrees
     * @return int[]{x, y} pixel coordinates
     */
    public static int[] latLonToPolarXY(
            double latDeg,
            double lonDeg,
            int poleSign,
            int cx,
            int cy,
            double radiusPx,
            double maxAngularRadiusDeg)
    {
        double colatDeg = 90.0 - poleSign * latDeg;
        double r = radiusPx * (colatDeg / maxAngularRadiusDeg);
        double lonRad = Math.toRadians(lonDeg);

        int x = (int) Math.round(cx + r * Math.sin(lonRad));
        int y = (int) Math.round(cy + poleSign * r * Math.cos(lonRad));
        return new int[] { x, y };
    }

    /**
     * Inverse mapping from screen pixel coordinates (x, y) to geographic (latDeg, lonDeg).
     *
     * @param x                  Screen X pixel
     * @param y                  Screen Y pixel
     * @param poleSign           +1 for North Pole, -1 for South Pole
     * @param cx                 Disc center X in pixels (including any pan offset)
     * @param cy                 Disc center Y in pixels (including any pan offset)
     * @param radiusPx           Disc outer radius in pixels
     * @param maxAngularRadiusDeg Angular co-latitude span from pole to outer rim in degrees
     * @return double[]{latDeg, lonDeg}, or null if outside valid globe range
     */
    public static double[] polarXYToLatLon(
            int x,
            int y,
            int poleSign,
            int cx,
            int cy,
            double radiusPx,
            double maxAngularRadiusDeg)
    {
        if (radiusPx <= 0.0)
        {
            return null;
        }
        double dx = x - cx;
        double dy = y - cy;
        double r = Math.hypot(dx, dy);
        double colatDeg = (r / radiusPx) * maxAngularRadiusDeg;
        if (colatDeg > 180.0)
        {
            return null;
        }
        double latDeg = poleSign * (90.0 - colatDeg);
        if (latDeg > 90.0)
        {
            latDeg = 90.0;
        }
        else if (latDeg < -90.0)
        {
            latDeg = -90.0;
        }
        double lonDeg = Math.toDegrees(Math.atan2(dx, poleSign * dy));
        return new double[] { latDeg, lonDeg };
    }

    /**
     * Resolves a mouse pixel position (x, y) in the component to [latDeg, lonDeg, poleSign],
     * or returns null if outside the rendered polar disc(s).
     */
    public double[] findLLFromScreenXY(int x, int y)
    {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0)
        {
            return null;
        }

        int[][] discs = computeDiscGeometries(w, h);
        double maxAng = getMaxAngularRadiusDeg();

        for (int[] d : discs)
        {
            int poleSign = d[0];
            int cx = d[1];
            int cy = d[2];
            int radius = d[3];

            double distFromDiscCenter = Math.hypot(x - cx, y - cy);
            if (distFromDiscCenter <= radius)
            {
                int panCx = cx + (int) Math.round(panXNorm * radius);
                int panCy = cy + (int) Math.round(panYNorm * radius);
                double[] ll = polarXYToLatLon(x, y, poleSign, panCx, panCy, radius, maxAng);
                if (ll != null)
                {
                    return new double[] { ll[0], ll[1], poleSign };
                }
            }
        }
        return null;
    }

    /**
     * Computes disc layout geometries for the current mode and window dimensions.
     * Each entry is int[]{poleSign, cx, cy, radiusPx}.
     */
    public int[][] computeDiscGeometries(int w, int h)
    {
        int padding = 28;
        if (polarMode == MODE_NORTH)
        {
            int r = Math.max(10, Math.min(w, h) / 2 - padding);
            return new int[][] { { +1, w / 2, h / 2, r } };
        }
        else if (polarMode == MODE_SOUTH)
        {
            int r = Math.max(10, Math.min(w, h) / 2 - padding);
            return new int[][] { { -1, w / 2, h / 2, r } };
        }
        else
        {
            // MODE_DUAL: side-by-side if w >= h, else stacked vertically
            if (w >= h)
            {
                int halfW = w / 2;
                int r = Math.max(10, Math.min(halfW, h) / 2 - padding);
                return new int[][] {
                    { +1, halfW / 2, h / 2, r },
                    { -1, halfW + halfW / 2, h / 2, r }
                };
            }
            else
            {
                int halfH = h / 2;
                int r = Math.max(10, Math.min(w, halfH) / 2 - padding);
                return new int[][] {
                    { +1, w / 2, halfH / 2, r },
                    { -1, w / 2, halfH + halfH / 2, r }
                };
            }
        }
    }

    @Override
    protected void paintComponent(Graphics g)
    {
        super.paintComponent(g);

        if (renderHints == null)
        {
            renderHints = new RenderingHints(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        }

        Graphics2D g2 = (Graphics2D) g;
        Dimension dim = getSize();
        int w = dim.width;
        int h = dim.height;
        lastTotalWidth = w;
        lastTotalHeight = h;

        g2.setRenderingHints(renderHints);

        // Fill window background
        g2.setPaint(backgroundColor);
        g2.fillRect(0, 0, w, h);

        Font baseFont = g2.getFont().deriveFont(textLabelFontSize);
        g2.setFont(baseFont);

        int[][] discs = computeDiscGeometries(w, h);
        for (int[] d : discs)
        {
            drawPolarDisc(g2, d[0], d[1], d[2], d[3]);
        }

        // Draw Date/Time and hover coordinates
        if (showDateTime && currentTime != null)
        {
            g2.setPaint(dateTimeColor);
            g2.setFont(baseFont);
            g2.drawString(currentTime.getDateTimeStr(), xDateTimeOffset, h - yDateTimeOffset);
        }

        if (hoverCoordString != null && !hoverCoordString.isEmpty())
        {
            g2.setPaint(dateTimeColor);
            FontMetrics fm = g2.getFontMetrics();
            int strW = fm.stringWidth(hoverCoordString);
            g2.drawString(hoverCoordString, Math.max(xDateTimeOffset, w - strW - 10), h - yDateTimeOffset);
        }

        if (showFPS && polarPanel != null && polarPanel.getApp() != null)
        {
            g2.setPaint(dateTimeColor);
            g2.drawString("FPS: " + df.format(polarPanel.getApp().getFpsAnimation()), w - 95, 18);
        }
    }

    private void drawPolarDisc(Graphics2D g2, int poleSign, int discCx, int discCy, int radius)
    {
        Shape oldClip = g2.getClip();
        Ellipse2D.Double discCircle = new Ellipse2D.Double(discCx - radius, discCy - radius, 2.0 * radius, 2.0 * radius);

        // 1. Fill disc ocean/background
        g2.setPaint(discBackgroundColor);
        g2.fill(discCircle);

        // Apply circular clip so all map features stay inside the polar disc
        g2.clip(discCircle);

        int projCx = discCx + (int) Math.round(panXNorm * radius);
        int projCy = discCy + (int) Math.round(panYNorm * radius);
        double maxAng = getMaxAngularRadiusDeg();

        // 2. Draw vector landmasses
        if (polarPanel != null && polarPanel.getLandMass() != null && polarPanel.getLandMass().isShowLandMassOutlines())
        {
            drawLandMassesPolar(g2, polarPanel.getLandMass(), poleSign, projCx, projCy, radius, maxAng);
        }

        // 3. Draw solar terminator & night shading
        if (drawSun && sun != null)
        {
            drawSunTerminatorPolar(g2, discCircle, poleSign, projCx, projCy, radius, maxAng);
        }

        // 4. Draw polar Lat/Lon grid
        if (showLatLong)
        {
            drawPolarGrid(g2, poleSign, projCx, projCy, radius, maxAng);
        }

        // 5. Draw Ground Stations
        g2.setStroke(new BasicStroke(drawingLineWidth));
        if (gsHash != null)
        {
            for (GroundStation gs : gsHash.values())
            {
                if (gs.isShow2D())
                {
                    double gsLat = gs.getLatitude();
                    double gsLon = gs.getLongitude();
                    if (90.0 - poleSign * gsLat <= maxAng * 1.35)
                    {
                        g2.setPaint(gs.getStationColor());
                        int[] xy = latLonToPolarXY(gsLat, gsLon, poleSign, projCx, projCy, radius, maxAng);
                        int sz = gs.getGroundStation2DPixelSize();
                        g2.fillRect(xy[0] - sz / 2, xy[1] - sz / 2, sz, sz);
                        if (gs.isShow2DName())
                        {
                            g2.drawString(gs.getStationName().trim(), xy[0] + sz / 2 + 3, xy[1] + 4);
                        }
                    }
                }
            }
        }

        // 6. Draw Satellite Ground Tracks (Lead & Lag)
        if (satHash != null)
        {
            g2.setStroke(new BasicStroke(drawingLineWidth));
            for (AbstractSatellite sat : satHash.values())
            {
                if (sat.getPlot2D() && sat.getShowGroundTrack() && sat.getGroundTrackIni())
                {
                    g2.setPaint(sat.getSatColor());
                    drawTrackSeriesPolar(g2, sat, true, poleSign, projCx, projCy, radius, maxAng);
                    drawTrackSeriesPolar(g2, sat, false, poleSign, projCx, projCy, radius, maxAng);
                }
            }

            // 7. Draw Satellite Footprints
            for (AbstractSatellite sat : satHash.values())
            {
                if (sat.getPlot2D() && sat.getPlot2DFootPrint())
                {
                    drawSatFootPrintPolar(
                            g2,
                            sat.getLatitude(),
                            sat.getLongitude(),
                            sat.getAltitude(),
                            sat.isFillFootPrint(),
                            sat.getSatColor(),
                            0.2f,
                            sat.getNumPtsFootPrint(),
                            poleSign,
                            projCx,
                            projCy,
                            radius,
                            maxAng);
                }
            }

            // 8. Draw Current Satellite Positions & Names
            for (AbstractSatellite sat : satHash.values())
            {
                if (sat.getPlot2D())
                {
                    double latDeg = Math.toDegrees(sat.getLatitude());
                    double lonDeg = Math.toDegrees(sat.getLongitude());
                    if (90.0 - poleSign * latDeg <= maxAng * 1.35)
                    {
                        g2.setPaint(sat.getSatColor());
                        int[] xy = latLonToPolarXY(latDeg, lonDeg, poleSign, projCx, projCy, radius, maxAng);
                        g2.fillOval(xy[0] - 5, xy[1] - 5, 10, 10);
                        if (sat.isShowName2D())
                        {
                            g2.drawString(sat.getName().trim(), xy[0] + 8, xy[1] + 4);
                        }
                    }
                }
            }
        }

        // Restore clip to draw outer border and rim labels
        g2.setClip(oldClip);

        // 9. Draw outer disc border and labels
        g2.setPaint(gridColor.brighter());
        g2.setStroke(new BasicStroke(1.5f));
        g2.draw(discCircle);

        drawRimLabelsAndTitle(g2, poleSign, discCx, discCy, radius, maxAng);
    }

    private void drawLandMassesPolar(
            Graphics2D g2,
            LandMassRegions landMass,
            int poleSign,
            int cx,
            int cy,
            int radius,
            double maxAng)
    {
        if (landMass.getLandMassHash() == null)
        {
            return;
        }
        g2.setPaint(landMass.getLandOutlineColor());
        g2.setStroke(new BasicStroke(1.0f));

        double cullColat = Math.min(165.0, maxAng * 1.45 + 15.0);

        for (String key : landMass.getLandMassHash().keySet())
        {
            Vector<float[]> vec = landMass.getLandMassVector(key);
            if (vec == null || vec.isEmpty())
            {
                continue;
            }

            int n = vec.size();
            int[] xPts = new int[n + 1];
            int[] yPts = new int[n + 1];
            int ptsCount = 0;

            for (int i = 0; i < n; i++)
            {
                float[] ll = vec.elementAt(i);
                double colat = 90.0 - poleSign * ll[0];
                if (colat <= cullColat)
                {
                    int[] xy = latLonToPolarXY(ll[0], ll[1], poleSign, cx, cy, radius, maxAng);
                    xPts[ptsCount] = xy[0];
                    yPts[ptsCount] = xy[1];
                    ptsCount++;
                }
                else
                {
                    if (ptsCount > 1)
                    {
                        g2.drawPolyline(xPts, yPts, ptsCount);
                    }
                    ptsCount = 0;
                }
            }

            if (ptsCount > 1)
            {
                g2.drawPolyline(xPts, yPts, ptsCount);
            }
        }
    }

    private void drawSunTerminatorPolar(
            Graphics2D g2,
            Ellipse2D.Double discCircle,
            int poleSign,
            int cx,
            int cy,
            int radius,
            double maxAng)
    {
        double[] darkLLA = sun.getCurrentDarkLLA();
        double darkLatRad = darkLLA[0];
        double darkLonRad = darkLLA[1];

        // Pole-relative dark latitude: psi_d = poleSign * darkLatRad
        double psiD = poleSign * darkLatRad;
        double sinPsiD = Math.sin(psiD);
        double cosPsiD = Math.cos(psiD);

        int nPts = Math.max(36, numPtsSunFootPrint);
        Path2D.Double termPath = new Path2D.Double();

        if (Math.abs(sinPsiD) < 1e-5)
        {
            // Equinox case: terminator passes directly through the pole along darkLon +/- 90 deg
            double lon1 = Math.toDegrees(darkLonRad - Math.PI / 2.0);
            double lon2 = Math.toDegrees(darkLonRad + Math.PI / 2.0);
            double rRim = radius * 2.0;
            int[] p1 = latLonToPolarXY(-poleSign * 85.0, lon1, poleSign, cx, cy, radius, maxAng);
            int[] p2 = latLonToPolarXY(-poleSign * 85.0, lon2, poleSign, cx, cy, radius, maxAng);
            termPath.moveTo(p1[0], p1[1]);
            termPath.lineTo(p2[0], p2[1]);
            double midLon = Math.toRadians(Math.toDegrees(darkLonRad));
            int[] pMid = new int[] {
                (int) Math.round(cx + rRim * Math.sin(midLon)),
                (int) Math.round(cy + poleSign * rRim * Math.cos(midLon))
            };
            termPath.lineTo(pMid[0], pMid[1]);
            termPath.closePath();

            Color transColor = new Color(
                    sunColor.getRed() / 255.0f,
                    sunColor.getGreen() / 255.0f,
                    sunColor.getBlue() / 255.0f,
                    sunAlpha);
            g2.setPaint(transColor);
            g2.fill(termPath);
            g2.setPaint(sunColor);
            g2.drawLine(p1[0], p1[1], p2[0], p2[1]);
            return;
        }

        // General case: for each longitude lambda, terminator pole-relative latitude is:
        // tan(psi_term) = -cos(psi_d) * cos(lambda - darkLon) / sin(psi_d)
        for (int i = 0; i <= nPts; i++)
        {
            double lonRad = -Math.PI + (2.0 * Math.PI * i) / nPts;
            double tanPsi = -cosPsiD * Math.cos(lonRad - darkLonRad) / sinPsiD;
            double psiRad = Math.atan(tanPsi);
            double latDeg = poleSign * Math.toDegrees(psiRad);
            double lonDeg = Math.toDegrees(lonRad);

            int[] xy = latLonToPolarXY(latDeg, lonDeg, poleSign, cx, cy, radius, maxAng);
            if (i == 0)
            {
                termPath.moveTo(xy[0], xy[1]);
            }
            else
            {
                termPath.lineTo(xy[0], xy[1]);
            }
        }
        termPath.closePath();

        Color transColor = new Color(
                sunColor.getRed() / 255.0f,
                sunColor.getGreen() / 255.0f,
                sunColor.getBlue() / 255.0f,
                sunAlpha);
        g2.setPaint(transColor);

        if (sinPsiD > 0)
        {
            // Pole itself is in darkness -> interior of termPath is dark
            g2.fill(termPath);
        }
        else
        {
            // Pole itself is in daylight -> exterior of termPath inside disc is dark
            Area darkArea = new Area(discCircle);
            darkArea.subtract(new Area(termPath));
            g2.fill(darkArea);
        }

        g2.setPaint(sunColor);
        g2.setStroke(new BasicStroke(1.2f));
        g2.draw(termPath);
    }

    private void drawPolarGrid(
            Graphics2D g2,
            int poleSign,
            int cx,
            int cy,
            int radius,
            double maxAng)
    {
        g2.setPaint(gridColor);
        float[] dashPattern = { 3.0f, 3.0f };
        g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, dashPattern, 0.0f));

        int latStep = (maxAng <= 45.0) ? 10 : (maxAng <= 60.0 ? 15 : 30);

        // Concentric latitude circles
        for (int colat = latStep; colat <= 180; colat += latStep)
        {
            if (colat > maxAng * 1.4)
            {
                break;
            }
            int rPx = (int) Math.round(radius * (colat / maxAng));
            g2.drawOval(cx - rPx, cy - rPx, 2 * rPx, 2 * rPx);

            int latVal = poleSign * (90 - colat);
            String label = (latVal >= 0 ? "+" : "") + latVal + "\u00B0";
            g2.drawString(label, cx + 3, cy + poleSign * rPx - 2);
        }

        // Radial longitude meridians every 30 degrees
        int maxR = (int) Math.round(radius * 1.45);
        for (int lon = -150; lon <= 180; lon += 30)
        {
            double lonRad = Math.toRadians(lon);
            int xEnd = (int) Math.round(cx + maxR * Math.sin(lonRad));
            int yEnd = (int) Math.round(cy + poleSign * maxR * Math.cos(lonRad));
            g2.drawLine(cx, cy, xEnd, yEnd);
        }
    }

    private void drawRimLabelsAndTitle(
            Graphics2D g2,
            int poleSign,
            int discCx,
            int discCy,
            int radius,
            double maxAng)
    {
        Font origFont = g2.getFont();
        Font smallFont = origFont.deriveFont(Math.max(10.0f, textLabelFontSize - 1.0f));
        g2.setFont(smallFont);
        FontMetrics fm = g2.getFontMetrics();
        g2.setPaint(gridColor.brighter());

        if (showLatLong && Math.hypot(panXNorm, panYNorm) < 0.05)
        {
            int[] labeledLons = { 0, 90, 180, -90 };
            for (int lon : labeledLons)
            {
                double lonRad = Math.toRadians(lon);
                double labelR = radius + 12.0;
                int lx = (int) Math.round(discCx + labelR * Math.sin(lonRad));
                int ly = (int) Math.round(discCy + poleSign * labelR * Math.cos(lonRad));
                String text = (lon == 0) ? "0\u00B0" : (lon == 180 ? "180\u00B0" : (lon > 0 ? lon + "\u00B0E" : (-lon) + "\u00B0W"));
                int tw = fm.stringWidth(text);
                g2.drawString(text, lx - tw / 2, ly + fm.getAscent() / 2 - 1);
            }
        }

        // Draw Hemisphere Title in upper-left of disc area
        Font titleFont = origFont.deriveFont(Font.BOLD, textLabelFontSize);
        g2.setFont(titleFont);
        g2.setPaint(dateTimeColor);
        double edgeLat = poleSign * (90.0 - maxAng);
        String poleTitle = (poleSign > 0 ? "North Pole" : "South Pole")
                + String.format(" (\u2265 %.0f\u00B0%s)", Math.abs(edgeLat), poleSign > 0 ? "N" : "S");
        g2.drawString(poleTitle, Math.max(8, discCx - radius), Math.max(16, discCy - radius - 8));

        g2.setFont(origFont);
    }

    private void drawTrackSeriesPolar(
            Graphics2D g2,
            AbstractSatellite sat,
            boolean isLead,
            int poleSign,
            int cx,
            int cy,
            int radius,
            double maxAng)
    {
        int numPts = isLead ? sat.getNumGroundTrackLeadPts() : sat.getNumGroundTrackLagPts();
        if (numPts <= 1)
        {
            return;
        }

        int[] xPts = new int[numPts];
        int[] yPts = new int[numPts];
        int ptsCount = 0;
        double cullColat = Math.min(170.0, maxAng * 1.45 + 15.0);

        for (int j = 0; j < numPts; j++)
        {
            double[] lla = isLead ? sat.getGroundTrackLlaLeadPt(j) : sat.getGroundTrackLlaLagPt(j);
            if (lla == null || Double.isNaN(lla[0]) || Double.isNaN(lla[1]))
            {
                if (ptsCount > 1)
                {
                    g2.drawPolyline(xPts, yPts, ptsCount);
                }
                ptsCount = 0;
                continue;
            }

            double latDeg = Math.toDegrees(lla[0]);
            double lonDeg = Math.toDegrees(lla[1]);
            double colat = 90.0 - poleSign * latDeg;

            if (colat <= cullColat)
            {
                int[] xy = latLonToPolarXY(latDeg, lonDeg, poleSign, cx, cy, radius, maxAng);
                xPts[ptsCount] = xy[0];
                yPts[ptsCount] = xy[1];
                ptsCount++;
            }
            else
            {
                if (ptsCount > 1)
                {
                    g2.drawPolyline(xPts, yPts, ptsCount);
                }
                ptsCount = 0;
            }
        }

        if (ptsCount > 1)
        {
            g2.drawPolyline(xPts, yPts, ptsCount);
        }
    }

    private void drawSatFootPrintPolar(
            Graphics2D g2,
            double latRad,
            double lonRad,
            double altMeters,
            boolean fillFootPrint,
            Color satColor,
            float alpha,
            int numPtsFootPrint,
            int poleSign,
            int cx,
            int cy,
            int radius,
            double maxAng)
    {
        if (altMeters <= 0.0 || Double.isNaN(latRad) || Double.isNaN(lonRad))
        {
            return;
        }

        double lambda0Deg = Math.toDegrees(Math.acos(AstroConst.R_Earth / (AstroConst.R_Earth + altMeters)));
        double satColatDeg = 90.0 - poleSign * Math.toDegrees(latRad);

        // If the entire footprint is well outside the visible disc, skip it
        if (satColatDeg - lambda0Deg > maxAng * 1.35)
        {
            return;
        }

        Vector<LatLon> llVec = J2dEarthLabel2.getFootPrintLatLonList(latRad, lonRad, altMeters, Math.max(21, numPtsFootPrint));
        if (llVec == null || llVec.isEmpty())
        {
            return;
        }

        Polygon poly = new Polygon();
        for (LatLon ll : llVec)
        {
            double ptLatDeg = ll.getLatitude().getDegrees();
            double ptLonDeg = ll.getLongitude().getDegrees();
            int[] xy = latLonToPolarXY(ptLatDeg, ptLonDeg, poleSign, cx, cy, radius, maxAng);
            poly.addPoint(xy[0], xy[1]);
        }

        if (fillFootPrint && poly.npoints > 2)
        {
            Color transColor = new Color(
                    satColor.getRed() / 255.0f,
                    satColor.getGreen() / 255.0f,
                    satColor.getBlue() / 255.0f,
                    alpha);
            g2.setPaint(transColor);
            g2.fillPolygon(poly);
        }

        g2.setPaint(satColor);
        g2.setStroke(new BasicStroke(drawingLineWidth));
        g2.drawPolygon(poly);
    }

    // Getters & Setters
    public int getPolarMode()
    {
        return polarMode;
    }

    public void setPolarMode(int polarMode)
    {
        this.polarMode = polarMode;
    }

    public double getMinAbsLatitudeDeg()
    {
        return minAbsLatitudeDeg;
    }

    public void setMinAbsLatitudeDeg(double minAbsLatitudeDeg)
    {
        this.minAbsLatitudeDeg = Math.max(0.0, Math.min(85.0, minAbsLatitudeDeg));
    }

    public double getZoomFactor()
    {
        return zoomFactor;
    }

    public void setZoomFactor(double zoomFactor)
    {
        this.zoomFactor = Math.max(1.0, zoomFactor);
    }

    public double getZoomIncrementMultiplier()
    {
        return zoomIncrementMultiplier;
    }

    public void setZoomIncrementMultiplier(double zoomIncrementMultiplier)
    {
        this.zoomIncrementMultiplier = Math.max(1.05, zoomIncrementMultiplier);
    }

    public double getPanXNorm()
    {
        return panXNorm;
    }

    public void setPanXNorm(double panXNorm)
    {
        this.panXNorm = Math.max(-1.5, Math.min(1.5, panXNorm));
    }

    public double getPanYNorm()
    {
        return panYNorm;
    }

    public void setPanYNorm(double panYNorm)
    {
        this.panYNorm = Math.max(-1.5, Math.min(1.5, panYNorm));
    }

    public void resetZoomAndPan()
    {
        this.zoomFactor = 1.0;
        this.panXNorm = 0.0;
        this.panYNorm = 0.0;
    }

    public boolean isShowDateTime()
    {
        return showDateTime;
    }

    public void setShowDateTime(boolean showDateTime)
    {
        this.showDateTime = showDateTime;
    }

    public int getXDateTimeOffset()
    {
        return xDateTimeOffset;
    }

    public void setXDateTimeOffset(int xDateTimeOffset)
    {
        this.xDateTimeOffset = xDateTimeOffset;
    }

    public int getYDateTimeOffset()
    {
        return yDateTimeOffset;
    }

    public void setYDateTimeOffset(int yDateTimeOffset)
    {
        this.yDateTimeOffset = yDateTimeOffset;
    }

    public Color getDateTimeColor()
    {
        return dateTimeColor;
    }

    public void setDateTimeColor(Color dateTimeColor)
    {
        this.dateTimeColor = dateTimeColor;
    }

    public Color getGridColor()
    {
        return gridColor;
    }

    public void setGridColor(Color gridColor)
    {
        this.gridColor = gridColor;
    }

    public Color getDiscBackgroundColor()
    {
        return discBackgroundColor;
    }

    public void setDiscBackgroundColor(Color discBackgroundColor)
    {
        this.discBackgroundColor = discBackgroundColor;
    }

    public Color getBackgroundColor()
    {
        return backgroundColor;
    }

    public void setBackgroundColor(Color backgroundColor)
    {
        this.backgroundColor = backgroundColor;
    }

    public boolean isDrawSun()
    {
        return drawSun;
    }

    public void setDrawSun(boolean drawSun)
    {
        this.drawSun = drawSun;
    }

    public Color getSunColor()
    {
        return sunColor;
    }

    public void setSunColor(Color sunColor)
    {
        this.sunColor = sunColor;
    }

    public int getNumPtsSunFootPrint()
    {
        return numPtsSunFootPrint;
    }

    public void setNumPtsSunFootPrint(int numPtsSunFootPrint)
    {
        this.numPtsSunFootPrint = numPtsSunFootPrint;
    }

    public float getSunAlpha()
    {
        return sunAlpha;
    }

    public void setSunAlpha(float sunAlpha)
    {
        this.sunAlpha = sunAlpha;
    }

    public float getDrawingLineWidth()
    {
        return drawingLineWidth;
    }

    public void setDrawingLineWidth(float drawingLineWidth)
    {
        this.drawingLineWidth = drawingLineWidth;
    }

    public float getTextLabelFontSize()
    {
        return textLabelFontSize;
    }

    public void setTextLabelFontSize(float textLabelFontSize)
    {
        this.textLabelFontSize = textLabelFontSize;
    }

    public void setHoverCoordString(String hoverCoordString)
    {
        this.hoverCoordString = hoverCoordString;
    }

    public void setSatHashTable(Hashtable<String, AbstractSatellite> satHash)
    {
        this.satHash = satHash;
    }

    public int getLastTotalWidth()
    {
        return lastTotalWidth;
    }

    public int getLastTotalHeight()
    {
        return lastTotalHeight;
    }
}


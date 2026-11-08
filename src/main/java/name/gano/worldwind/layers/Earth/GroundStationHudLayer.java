/*
 * =====================================================================
 *   This file is part of JSatTrak.
 *
 *   Copyright 2007-2013 Shawn E. Gano
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
package name.gano.worldwind.layers.Earth;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import gov.nasa.worldwind.geom.Angle;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.geom.Vec4;
import gov.nasa.worldwind.globes.Globe;
import gov.nasa.worldwind.layers.RenderableLayer;
import gov.nasa.worldwind.render.DrawContext;
import gov.nasa.worldwind.render.TextRenderer;
import gov.nasa.worldwind.util.OGLTextRenderer;
import java.awt.Color;
import java.awt.Font;
import java.awt.Rectangle;
import java.util.Locale;
import jsattrak.objects.AbstractSatellite;
import jsattrak.objects.GroundStation;
import name.gano.worldwind.view.GroundStationToSatView;

/**
 * Renders the Telescope / Antenna Boresight HUD overlay when a 3D window is in
 * {@link GroundStationToSatView} mode:
 * <ul>
 *   <li>Projected local horizon (0° El) and Ground Station elevation constraint mask curve</li>
 *   <li>Cardinal compass ticks (N, NE, E, SE, S, SW, W, NW) along the local horizon</li>
 *   <li>Center antenna/telescope boresight crosshair and target diamond marker</li>
 *   <li>Live Azimuth, Elevation, Range, FOV, and visibility status telemetry panel</li>
 * </ul>
 */
public class GroundStationHudLayer extends RenderableLayer
{
    private static final Font HUD_TITLE_FONT = new Font("Monospaced", Font.BOLD, 12);
    private static final Font HUD_TEXT_FONT = new Font("Monospaced", Font.PLAIN, 11);
    private static final Font COMPASS_FONT = new Font("SansSerif", Font.BOLD, 12);

    private static final String[] CARDINAL_LABELS = { "N", "NE", "E", "SE", "S", "SW", "W", "NW" };
    private static final double[] CARDINAL_AZ_DEG = { 0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0 };

    private boolean showHud = true;

    public GroundStationHudLayer()
    {
        super();
        this.setName("GS to Sat Boresight HUD");
        this.setPickEnabled(false);
    }

    public boolean isShowHud()
    {
        return showHud;
    }

    public void setShowHud(boolean showHud)
    {
        this.showHud = showHud;
    }

    @Override
    protected void doRender(DrawContext dc)
    {
        if (!showHud || dc == null || !(dc.getView() instanceof GroundStationToSatView))
        {
            return;
        }

        GroundStationToSatView gsView = (GroundStationToSatView) dc.getView();
        GroundStation gs = gsView.getGroundStation();
        AbstractSatellite sat = gsView.getSatellite();
        Globe globe = dc.getGlobe();
        if (gs == null || sat == null || globe == null)
        {
            return;
        }

        Rectangle vp = gsView.getViewport();
        if (vp == null || vp.width <= 0 || vp.height <= 0)
        {
            return;
        }

        GL2 gl = dc.getGL().getGL2();

        gl.glPushAttrib(GL2.GL_ENABLE_BIT | GL2.GL_COLOR_BUFFER_BIT | GL2.GL_LINE_BIT | GL2.GL_CURRENT_BIT | GL2.GL_DEPTH_BUFFER_BIT);
        gl.glDisable(GL.GL_DEPTH_TEST);
        gl.glDisable(GL.GL_TEXTURE_2D);
        gl.glDisable(GL2.GL_LIGHTING);
        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        gl.glEnable(GL.GL_LINE_SMOOTH);

        gl.glMatrixMode(GL2.GL_PROJECTION);
        gl.glPushMatrix();
        gl.glLoadIdentity();
        gl.glOrtho(0.0, vp.width, 0.0, vp.height, -1.0, 1.0);

        gl.glMatrixMode(GL2.GL_MODELVIEW);
        gl.glPushMatrix();
        gl.glLoadIdentity();

        try
        {
            renderSkyHorizonAndMaskCurves(dc, gl, gsView, gs, globe, vp);
            renderTargetIndicator(gl, gsView, sat, globe, vp);
            renderBoresightReticle(gl, gsView, vp);
            renderTelemetryPanel(dc, gl, gsView, gs, sat, vp);
        }
        finally
        {
            gl.glMatrixMode(GL2.GL_MODELVIEW);
            gl.glPopMatrix();
            gl.glMatrixMode(GL2.GL_PROJECTION);
            gl.glPopMatrix();
            gl.glPopAttrib();
        }
    }

    private void renderSkyHorizonAndMaskCurves(
            DrawContext dc,
            GL2 gl,
            GroundStationToSatView gsView,
            GroundStation gs,
            Globe globe,
            Rectangle vp)
    {
        Position eyePos = gsView.getCurrentEyePosition();
        Vec4 eyePt = gsView.getCurrentEyePoint();
        if (eyePos == null || eyePt == null)
        {
            return;
        }

        Vec4 up = globe.computeSurfaceNormalAtLocation(eyePos.getLatitude(), eyePos.getLongitude());
        Vec4 north = globe.computeNorthPointingTangentAtLocation(eyePos.getLatitude(), eyePos.getLongitude());
        Vec4 east = north.cross3(up).normalize3();
        north = up.cross3(east).normalize3();

        double domeRadius = 50000.0;

        // 1. Local Horizon Curve (El = 0 deg)
        gl.glLineWidth(1.4f);
        gl.glColor4f(0.35f, 0.82f, 1.0f, 0.72f);
        drawElevationRing(gl, gsView, eyePt, east, north, up, domeRadius, 0.0, vp);

        // 2. Elevation Constraint Mask Curve (El = gs.getElevationConst() deg)
        double maskElDeg = gs.getElevationConst();
        if (maskElDeg > 0.1 && maskElDeg < 89.5)
        {
            gl.glEnable(GL2.GL_LINE_STIPPLE);
            gl.glLineStipple(1, (short) 0xF0F0);
            gl.glLineWidth(1.3f);
            gl.glColor4f(1.0f, 0.78f, 0.20f, 0.78f);
            drawElevationRing(gl, gsView, eyePt, east, north, up, domeRadius, maskElDeg, vp);
            gl.glDisable(GL2.GL_LINE_STIPPLE);
        }

        // 3. Cardinal / Intercardinal Compass Marks along Horizon (El = 0 deg)
        TextRenderer compassRenderer = OGLTextRenderer.getOrCreateTextRenderer(dc.getTextRendererCache(), COMPASS_FONT);
        for (int i = 0; i < CARDINAL_AZ_DEG.length; i++)
        {
            double azRad = Math.toRadians(CARDINAL_AZ_DEG[i]);
            Vec4 dir = east.multiply3(Math.sin(azRad)).add3(north.multiply3(Math.cos(azRad)));
            Vec4 worldPt = eyePt.add3(dir.multiply3(domeRadius));
            Vec4 screenPt = gsView.project(worldPt);
            if (screenPt != null && screenPt.z >= 0.0 && screenPt.z <= 1.0
                    && screenPt.x >= 0 && screenPt.x <= vp.width
                    && screenPt.y >= 0 && screenPt.y <= vp.height)
            {
                gl.glColor4f(0.35f, 0.82f, 1.0f, 0.90f);
                gl.glLineWidth(1.8f);
                gl.glBegin(GL.GL_LINES);
                gl.glVertex2d(screenPt.x, screenPt.y - 5.0);
                gl.glVertex2d(screenPt.x, screenPt.y + 5.0);
                gl.glEnd();

                String label = CARDINAL_LABELS[i];
                compassRenderer.beginRendering(vp.width, vp.height);
                compassRenderer.setColor(new Color(140, 230, 255, 235));
                int textW = (int) compassRenderer.getBounds(label).getWidth();
                compassRenderer.draw(label, (int) Math.round(screenPt.x - textW / 2.0), (int) Math.round(screenPt.y + 8.0));
                compassRenderer.endRendering();
            }
        }
    }

    private void drawElevationRing(
            GL2 gl,
            GroundStationToSatView gsView,
            Vec4 eyePt,
            Vec4 east,
            Vec4 north,
            Vec4 up,
            double radius,
            double elDeg,
            Rectangle vp)
    {
        double elRad = Math.toRadians(elDeg);
        double cosEl = Math.cos(elRad);
        double sinEl = Math.sin(elRad);

        boolean drawing = false;
        Vec4 prevScreen = null;

        for (int azStep = 0; azStep <= 360; azStep += 2)
        {
            double azRad = Math.toRadians(azStep);
            Vec4 horiz = east.multiply3(Math.sin(azRad)).add3(north.multiply3(Math.cos(azRad)));
            Vec4 dir = horiz.multiply3(cosEl).add3(up.multiply3(sinEl));
            Vec4 worldPt = eyePt.add3(dir.multiply3(radius));
            Vec4 screenPt = gsView.project(worldPt);

            boolean valid = (screenPt != null && screenPt.z >= 0.0 && screenPt.z <= 1.0
                    && screenPt.x >= -vp.width * 0.5 && screenPt.x <= vp.width * 1.5
                    && screenPt.y >= -vp.height * 0.5 && screenPt.y <= vp.height * 1.5);

            if (valid && prevScreen != null)
            {
                double jump = Math.hypot(screenPt.x - prevScreen.x, screenPt.y - prevScreen.y);
                if (jump > vp.width * 0.5)
                {
                    if (drawing)
                    {
                        gl.glEnd();
                        drawing = false;
                    }
                }
            }

            if (valid)
            {
                if (!drawing)
                {
                    gl.glBegin(GL.GL_LINE_STRIP);
                    drawing = true;
                }
                gl.glVertex2d(screenPt.x, screenPt.y);
                prevScreen = screenPt;
            }
            else
            {
                if (drawing)
                {
                    gl.glEnd();
                    drawing = false;
                }
                prevScreen = null;
            }
        }

        if (drawing)
        {
            gl.glEnd();
        }
    }

    private void renderTargetIndicator(
            GL2 gl,
            GroundStationToSatView gsView,
            AbstractSatellite sat,
            Globe globe,
            Rectangle vp)
    {
        if (!gsView.isValidTrackingData())
        {
            return;
        }

        Vec4 satPt = globe.computePointFromPosition(
                Angle.fromRadiansLatitude(sat.getLatitude()),
                Angle.fromRadiansLongitude(sat.getLongitude()),
                sat.getAltitude());
        if (satPt == null)
        {
            return;
        }

        Vec4 screenPt = gsView.project(satPt);
        if (screenPt == null || screenPt.z < 0.0 || screenPt.z > 1.0)
        {
            return;
        }

        Color statusColor = getStatusColor(gsView);
        gl.glColor4f(
                statusColor.getRed() / 255.0f,
                statusColor.getGreen() / 255.0f,
                statusColor.getBlue() / 255.0f,
                0.95f);
        gl.glLineWidth(1.8f);

        double s = 10.0;
        gl.glBegin(GL.GL_LINE_LOOP);
        gl.glVertex2d(screenPt.x, screenPt.y + s);
        gl.glVertex2d(screenPt.x + s, screenPt.y);
        gl.glVertex2d(screenPt.x, screenPt.y - s);
        gl.glVertex2d(screenPt.x - s, screenPt.y);
        gl.glEnd();
    }

    private void renderBoresightReticle(GL2 gl, GroundStationToSatView gsView, Rectangle vp)
    {
        double cx = vp.width / 2.0;
        double cy = vp.height / 2.0;

        Color statusColor = getStatusColor(gsView);
        gl.glColor4f(
                statusColor.getRed() / 255.0f,
                statusColor.getGreen() / 255.0f,
                statusColor.getBlue() / 255.0f,
                0.85f);
        gl.glLineWidth(1.5f);

        double innerGap = 6.0;
        double outerArm = 26.0;
        double ringRadius = 18.0;

        // Crosshair arms
        gl.glBegin(GL.GL_LINES);
        gl.glVertex2d(cx - outerArm, cy);
        gl.glVertex2d(cx - innerGap, cy);
        gl.glVertex2d(cx + innerGap, cy);
        gl.glVertex2d(cx + outerArm, cy);

        gl.glVertex2d(cx, cy - outerArm);
        gl.glVertex2d(cx, cy - innerGap);
        gl.glVertex2d(cx, cy + innerGap);
        gl.glVertex2d(cx, cy + outerArm);
        gl.glEnd();

        // Reticle circle
        gl.glBegin(GL.GL_LINE_LOOP);
        for (int i = 0; i < 36; i++)
        {
            double ang = i * (2.0 * Math.PI / 36.0);
            gl.glVertex2d(cx + ringRadius * Math.cos(ang), cy + ringRadius * Math.sin(ang));
        }
        gl.glEnd();
    }

    private void renderTelemetryPanel(
            DrawContext dc,
            GL2 gl,
            GroundStationToSatView gsView,
            GroundStation gs,
            AbstractSatellite sat,
            Rectangle vp)
    {
        int boxX = 12;
        int boxH = 82;
        int boxW = 395;
        int boxY = vp.height - boxH - 12;

        Color statusColor = getStatusColor(gsView);

        // Translucent dark background
        gl.glColor4f(0.06f, 0.08f, 0.12f, 0.78f);
        gl.glBegin(GL2.GL_QUADS);
        gl.glVertex2i(boxX, boxY);
        gl.glVertex2i(boxX + boxW, boxY);
        gl.glVertex2i(boxX + boxW, boxY + boxH);
        gl.glVertex2i(boxX, boxY + boxH);
        gl.glEnd();

        // Border in status color
        gl.glLineWidth(1.5f);
        gl.glColor4f(
                statusColor.getRed() / 255.0f,
                statusColor.getGreen() / 255.0f,
                statusColor.getBlue() / 255.0f,
                0.90f);
        gl.glBegin(GL.GL_LINE_LOOP);
        gl.glVertex2i(boxX, boxY);
        gl.glVertex2i(boxX + boxW, boxY);
        gl.glVertex2i(boxX + boxW, boxY + boxH);
        gl.glVertex2i(boxX, boxY + boxH);
        gl.glEnd();

        String titleLine = String.format(Locale.US, "GS -> SAT: %s -> %s",
                gs.getStationName().trim(), sat.getName().trim());
        String aerLine = String.format(Locale.US, "Az: %6.2f°   El: %+6.2f° (Mask: %.1f°)   Rng: %.1f km",
                gsView.getCurrentAzDeg(),
                gsView.getCurrentElDeg(),
                gs.getElevationConst(),
                gsView.getCurrentRangeMeters() / 1000.0);
        double fovDeg = gsView.getFieldOfView() != null ? gsView.getFieldOfView().getDegrees() : 45.0;
        String fovLine = String.format(Locale.US, "FOV: %4.1f°   Offset: dAz=%+.1f°, dEl=%+.1f°  [Wheel:Zoom | DblClk:Reset]",
                fovDeg, gsView.getAzOffsetDeg(), gsView.getElOffsetDeg());

        String statusText;
        if (!gsView.isValidTrackingData())
        {
            statusText = "STATUS: EPHEMERIS UNAVAILABLE";
        }
        else if (gsView.isTargetAboveElevationMask())
        {
            statusText = "STATUS: IN VIEW (ABOVE ELEVATION MASK)";
        }
        else if (gsView.isTargetAboveHorizon())
        {
            statusText = "STATUS: ABOVE HORIZON (BELOW ELEVATION MASK)";
        }
        else if (gsView.isClampToHorizon())
        {
            statusText = "STATUS: BELOW HORIZON (CAMERA CLAMPED TO 0° HORIZON)";
        }
        else
        {
            statusText = "STATUS: BELOW HORIZON (X-RAY THROUGH-EARTH TRACKING)";
        }

        TextRenderer titleRenderer = OGLTextRenderer.getOrCreateTextRenderer(dc.getTextRendererCache(), HUD_TITLE_FONT);
        titleRenderer.beginRendering(vp.width, vp.height);
        titleRenderer.setColor(Color.WHITE);
        titleRenderer.draw(titleLine, boxX + 10, boxY + boxH - 18);
        titleRenderer.setColor(statusColor);
        titleRenderer.draw(statusText, boxX + 10, boxY + 8);
        titleRenderer.endRendering();

        TextRenderer textRenderer = OGLTextRenderer.getOrCreateTextRenderer(dc.getTextRendererCache(), HUD_TEXT_FONT);
        textRenderer.beginRendering(vp.width, vp.height);
        textRenderer.setColor(new Color(215, 235, 255));
        textRenderer.draw(aerLine, boxX + 10, boxY + boxH - 37);
        textRenderer.setColor(new Color(180, 200, 220));
        textRenderer.draw(fovLine, boxX + 10, boxY + boxH - 55);
        textRenderer.endRendering();
    }

    private Color getStatusColor(GroundStationToSatView gsView)
    {
        if (!gsView.isValidTrackingData())
        {
            return new Color(255, 90, 90);
        }
        if (gsView.isTargetAboveElevationMask())
        {
            return new Color(70, 240, 110);
        }
        if (gsView.isTargetAboveHorizon())
        {
            return new Color(255, 195, 55);
        }
        return new Color(255, 85, 85);
    }

    @Override
    public String toString()
    {
        return "GS to Sat Boresight HUD";
    }
}


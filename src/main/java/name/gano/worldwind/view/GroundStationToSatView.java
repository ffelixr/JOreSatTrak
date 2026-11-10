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
package name.gano.worldwind.view;

import com.jogamp.opengl.GL;
import gov.nasa.worldwind.geom.Angle;
import gov.nasa.worldwind.geom.Frustum;
import gov.nasa.worldwind.geom.Matrix;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.geom.Vec4;
import gov.nasa.worldwind.globes.Globe;
import gov.nasa.worldwind.render.DrawContext;
import gov.nasa.worldwind.view.ViewUtil;
import gov.nasa.worldwind.view.orbit.BasicOrbitView;
import java.awt.Rectangle;
import jsattrak.objects.AbstractSatellite;
import jsattrak.objects.GroundStation;

/**
 * First-person 3D WorldWind camera view positioned at a {@link GroundStation}
 * and dynamically oriented along the line-of-sight toward a target {@link AbstractSatellite}.
 * <p>
 * Extends {@link BasicOrbitView} so existing JSatTrak persistence and view management casts
 * remain compatible while overriding {@link #doApply(DrawContext)} to use first-person
 * observer-to-target camera matrices.
 */
public class GroundStationToSatView extends BasicOrbitView
{
    public static final double DEFAULT_TOWER_HEIGHT_METERS = 15.0;
    public static final double DEFAULT_FOV_DEGREES = 45.0;
    public static final double MIN_FOV_DEGREES = 0.5;
    public static final double MAX_FOV_DEGREES = 90.0;

    private GroundStation groundStation;
    private AbstractSatellite satellite;

    private boolean clampToHorizon = true;
    private double towerHeightMeters = DEFAULT_TOWER_HEIGHT_METERS;
    private double azOffsetDeg = 0.0;
    private double elOffsetDeg = 0.0;

    // Live computed tracking state (updated every frame / update)
    private double currentAzDeg = 0.0;
    private double currentElDeg = 0.0;
    private double currentEffectiveElDeg = 0.0;
    private double currentRangeMeters = 0.0;
    private boolean targetAboveHorizon = false;
    private boolean targetAboveElevationMask = false;
    private boolean validTrackingData = false;

    public GroundStationToSatView()
    {
        super();
        initLimits();
    }

    public GroundStationToSatView(GroundStation groundStation, AbstractSatellite satellite, boolean clampToHorizon)
    {
        super();
        this.groundStation = groundStation;
        this.satellite = satellite;
        this.clampToHorizon = clampToHorizon;
        initLimits();
    }

    private void initLimits()
    {
        this.detectCollisions = false;
        if (this.getOrbitViewLimits() != null)
        {
            this.getOrbitViewLimits().setPitchLimits(Angle.ZERO, Angle.POS180);
            this.getOrbitViewLimits().setZoomLimits(0.0, 1.0E9);
        }
        this.setFieldOfView(Angle.fromDegrees(DEFAULT_FOV_DEGREES));
    }

    /**
     * Computes the geometric Azimuth, Elevation, Range, and camera Heading/Pitch in the
     * given {@link Globe}'s local East-North-Up (ENU) tangent frame at the Ground Station.
     *
     * @param globe The WorldWind Globe used for Cartesian conversion
     * @return double[] { azDeg, elDeg, effectiveElDeg, rangeMeters, headingDeg, pitchDeg }
     *         or null if inputs/ephemeris are invalid
     */
    public double[] updateTrackingState(Globe globe)
    {
        if (globe == null || groundStation == null || satellite == null)
        {
            validTrackingData = false;
            return null;
        }

        double satLatRad = satellite.getLatitude();
        double satLonRad = satellite.getLongitude();
        double satAltM = satellite.getAltitude();

        if (Double.isNaN(satLatRad) || Double.isNaN(satLonRad) || Double.isNaN(satAltM))
        {
            validTrackingData = false;
            return null;
        }

        Angle gsLat = Angle.fromDegrees(groundStation.getLatitude());
        Angle gsLon = Angle.fromDegrees(groundStation.getLongitude());
        double gsAltM = groundStation.getAltitude() + towerHeightMeters;

        Position gsEyePos = new Position(gsLat, gsLon, gsAltM);
        this.eyePosition = gsEyePos;
        this.center = gsEyePos;
        this.zoom = 0.0;

        Vec4 eyePt = globe.computePointFromPosition(gsEyePos);
        Vec4 satPt = globe.computePointFromPosition(
                Angle.fromRadiansLatitude(satLatRad),
                Angle.fromRadiansLongitude(satLonRad),
                satAltM);

        if (eyePt == null || satPt == null)
        {
            validTrackingData = false;
            return null;
        }

        Vec4 los = satPt.subtract3(eyePt);
        double range = los.getLength3();
        if (range <= 0.0 || Double.isNaN(range))
        {
            validTrackingData = false;
            return null;
        }

        Vec4 losUnit = los.normalize3();
        Vec4 up = globe.computeSurfaceNormalAtLocation(gsLat, gsLon);
        Vec4 north = globe.computeNorthPointingTangentAtLocation(gsLat, gsLon);
        Vec4 east = north.cross3(up).normalize3();
        // Ensure orthonormal North in tangent plane
        north = up.cross3(east).normalize3();

        double eComp = losUnit.dot3(east);
        double nComp = losUnit.dot3(north);
        double uComp = Math.max(-1.0, Math.min(1.0, losUnit.dot3(up)));

        double azDeg = Math.toDegrees(Math.atan2(eComp, nComp));
        if (azDeg < 0.0)
        {
            azDeg += 360.0;
        }
        double elDeg = Math.toDegrees(Math.asin(uComp));

        double effectiveElDeg = (clampToHorizon && elDeg < 0.0) ? 0.0 : elDeg;

        double headingDeg = (azDeg + azOffsetDeg) % 360.0;
        if (headingDeg > 180.0)
        {
            headingDeg -= 360.0;
        }
        else if (headingDeg < -180.0)
        {
            headingDeg += 360.0;
        }

        double pitchDeg = 90.0 + effectiveElDeg + elOffsetDeg;
        if (pitchDeg < 0.0)
        {
            pitchDeg = 0.0;
        }
        else if (pitchDeg > 180.0)
        {
            pitchDeg = 180.0;
        }

        this.currentAzDeg = azDeg;
        this.currentElDeg = elDeg;
        this.currentEffectiveElDeg = effectiveElDeg;
        this.currentRangeMeters = range;
        this.targetAboveHorizon = (elDeg >= 0.0);
        this.targetAboveElevationMask = (elDeg >= groundStation.getElevationConst());
        this.validTrackingData = true;

        this.heading = Angle.fromDegrees(headingDeg);
        this.pitch = Angle.fromDegrees(pitchDeg);
        this.roll = Angle.ZERO;

        return new double[] { azDeg, elDeg, effectiveElDeg, range, headingDeg, pitchDeg };
    }

    @Override
    protected void doApply(DrawContext dc)
    {
        if (dc == null || dc.getGL() == null || dc.getGlobe() == null)
        {
            return;
        }

        this.dc = dc;
        this.globe = dc.getGlobe();

        updateTrackingState(this.globe);

        if (this.eyePosition == null)
        {
            if (groundStation != null)
            {
                this.eyePosition = Position.fromDegrees(
                        groundStation.getLatitude(),
                        groundStation.getLongitude(),
                        groundStation.getAltitude() + towerHeightMeters);
            }
            else
            {
                this.eyePosition = Position.ZERO;
            }
        }

        this.modelview = ViewUtil.computeTransformMatrix(
                this.globe,
                this.eyePosition,
                this.heading != null ? this.heading : Angle.ZERO,
                this.pitch != null ? this.pitch : Angle.POS90,
                this.roll != null ? this.roll : Angle.ZERO);

        if (this.modelview == null)
        {
            this.modelview = Matrix.IDENTITY;
        }

        this.modelviewInv = this.modelview.getInverse();
        if (this.modelviewInv == null)
        {
            this.modelviewInv = Matrix.IDENTITY;
        }

        int[] viewportArray = new int[4];
        this.dc.getGL().glGetIntegerv(GL.GL_VIEWPORT, viewportArray, 0);
        this.viewport = new Rectangle(
                viewportArray[0],
                viewportArray[1],
                viewportArray[2],
                viewportArray[3]);

        this.nearClipDistance = computeNearClipDistance();
        this.farClipDistance = computeFarClipDistance();

        double viewportWidth = this.viewport.getWidth() <= 0.0 ? 1.0 : this.viewport.getWidth();
        double viewportHeight = this.viewport.getHeight() <= 0.0 ? 1.0 : this.viewport.getHeight();

        this.projection = Matrix.fromPerspective(
                this.fieldOfView,
                viewportWidth,
                viewportHeight,
                this.nearClipDistance,
                this.farClipDistance);
        this.frustum = Frustum.fromPerspective(
                this.fieldOfView,
                (int) viewportWidth,
                (int) viewportHeight,
                this.nearClipDistance,
                this.farClipDistance);

        loadGLViewState(dc, this.modelview, this.projection);
        afterDoApply();
    }

    @Override
    protected double computeNearClipDistance()
    {
        return 5.0;
    }

    @Override
    protected double computeFarClipDistance()
    {
        double minFar = 6.0E7; // 60,000 km default so GEO orbits are visible
        if (validTrackingData && currentRangeMeters > 0)
        {
            return Math.max(minFar, currentRangeMeters * 2.5);
        }
        return minFar;
    }

    @Override
    public Vec4 getCurrentEyePoint()
    {
        if (this.globe != null && this.eyePosition != null)
        {
            Vec4 pt = this.globe.computePointFromPosition(this.eyePosition);
            if (pt != null)
            {
                return pt;
            }
        }
        return super.getCurrentEyePoint();
    }

    @Override
    public Position getCurrentEyePosition()
    {
        if (this.eyePosition != null)
        {
            return this.eyePosition;
        }
        return super.getCurrentEyePosition();
    }

    public void incrementAzOffset(double deltaDeg)
    {
        this.azOffsetDeg += deltaDeg;
        if (this.azOffsetDeg > 180.0)
        {
            this.azOffsetDeg -= 360.0;
        }
        else if (this.azOffsetDeg < -180.0)
        {
            this.azOffsetDeg += 360.0;
        }
        updateModelViewStateID();
    }

    public void incrementElOffset(double deltaDeg)
    {
        this.elOffsetDeg = Math.max(-90.0, Math.min(90.0, this.elOffsetDeg + deltaDeg));
        updateModelViewStateID();
    }

    public void resetOffsets()
    {
        this.azOffsetDeg = 0.0;
        this.elOffsetDeg = 0.0;
        this.setFieldOfView(Angle.fromDegrees(DEFAULT_FOV_DEGREES));
        updateModelViewStateID();
    }

    /**
     * Scales the camera Field of View by the given factor (e.g. < 1.0 to zoom in, > 1.0 to zoom out).
     *
     * @param scaleFactor multiplicative factor applied to current FOV in degrees
     */
    public void zoomFov(double scaleFactor)
    {
        double curFov = this.getFieldOfView() != null ? this.getFieldOfView().getDegrees() : DEFAULT_FOV_DEGREES;
        double nextFov = Math.max(MIN_FOV_DEGREES, Math.min(MAX_FOV_DEGREES, curFov * scaleFactor));
        this.setFieldOfView(Angle.fromDegrees(nextFov));
        updateModelViewStateID();
    }

    public GroundStation getGroundStation()
    {
        return groundStation;
    }

    public void setGroundStation(GroundStation groundStation)
    {
        this.groundStation = groundStation;
    }

    public AbstractSatellite getSatellite()
    {
        return satellite;
    }

    public void setSatellite(AbstractSatellite satellite)
    {
        this.satellite = satellite;
    }

    public boolean isClampToHorizon()
    {
        return clampToHorizon;
    }

    public void setClampToHorizon(boolean clampToHorizon)
    {
        this.clampToHorizon = clampToHorizon;
    }

    public double getTowerHeightMeters()
    {
        return towerHeightMeters;
    }

    public void setTowerHeightMeters(double towerHeightMeters)
    {
        this.towerHeightMeters = Math.max(1.0, towerHeightMeters);
    }

    public double getAzOffsetDeg()
    {
        return azOffsetDeg;
    }

    public void setAzOffsetDeg(double azOffsetDeg)
    {
        this.azOffsetDeg = azOffsetDeg;
    }

    public double getElOffsetDeg()
    {
        return elOffsetDeg;
    }

    public void setElOffsetDeg(double elOffsetDeg)
    {
        this.elOffsetDeg = elOffsetDeg;
    }

    public double getCurrentAzDeg()
    {
        return currentAzDeg;
    }

    public double getCurrentElDeg()
    {
        return currentElDeg;
    }

    public double getCurrentEffectiveElDeg()
    {
        return currentEffectiveElDeg;
    }

    public double getCurrentRangeMeters()
    {
        return currentRangeMeters;
    }

    public boolean isTargetAboveHorizon()
    {
        return targetAboveHorizon;
    }

    public boolean isTargetAboveElevationMask()
    {
        return targetAboveElevationMask;
    }

    public boolean isValidTrackingData()
    {
        return validTrackingData;
    }
}


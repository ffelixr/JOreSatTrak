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

import gov.nasa.worldwind.geom.Angle;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.geom.Vec4;
import gov.nasa.worldwind.render.DrawContext;
import gov.nasa.worldwind.view.orbit.BasicOrbitView;
import jsattrak.objects.AbstractSatellite;

public class BasicModelView3 extends BasicOrbitView
{
    private double xOffset = 0;
    private double yOffset = 0;
    private double zOffset = 0;
    private double XYOffsetMultiplier = 0.05 * Math.PI / 180.0;
    private double ZOffsetMultiplier = 2500;
    private AbstractSatellite sat;

    public BasicModelView3()
    {
        super();
    }

    public BasicModelView3(AbstractSatellite sat)
    {
        super();
        this.sat = sat;
    }

    @Override
    protected void doApply(DrawContext dc)
    {
        if (sat == null)
        {
            setCenterPosition(Position.fromRadians(0 + xOffset, 0 + yOffset, 750000 + zOffset));
        }
        else
        {
            Vec4 v = dc.getGlobe().computePointFromPosition(
                Angle.fromRadians(sat.getLatitude() + xOffset),
                Angle.fromRadians(sat.getLongitude() + yOffset),
                sat.getAltitude() + zOffset);
            Position p = dc.getGlobe().computePositionFromPoint(v);
            setCenterPosition(p);
        }

        super.doApply(dc);
    }

    public double getXOffset()
    {
        return xOffset;
    }

    public void setXOffset(double xOffset)
    {
        this.xOffset = xOffset;
    }

    public double getYOffset()
    {
        return yOffset;
    }

    public void setYOffset(double yOffset)
    {
        this.yOffset = yOffset;
    }

    public double getZOffset()
    {
        return zOffset;
    }

    public void setZOffset(double zOffset)
    {
        this.zOffset = zOffset;
    }

    public void resetOffsets()
    {
        setZOffset(0);
        setYOffset(0);
        setXOffset(0);
    }

    public void incrementXOffset(double multiplierPosNeg)
    {
        xOffset += XYOffsetMultiplier * multiplierPosNeg;
    }

    public void incrementYOffset(double multiplierPosNeg)
    {
        yOffset += XYOffsetMultiplier * multiplierPosNeg;
    }

    public void incrementZOffset(double multiplierPosNeg)
    {
        zOffset += ZOffsetMultiplier * multiplierPosNeg;
    }

    public AbstractSatellite getSat()
    {
        return sat;
    }

    public void setSat(AbstractSatellite sat)
    {
        this.sat = sat;
    }
}
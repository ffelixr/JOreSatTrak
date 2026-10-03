/*
 * Orginal code from: http://forum.worldwindcentral.com/showthread.php?t=13111
 * Updates by: Shawn E. Gano
 *  - Updated 24 Sept 2008 - added get and set for slices and stacks (and changed default)
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

package name.gano.worldwind.geom;

import gov.nasa.worldwind.geom.Angle;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.geom.Vec4;
import gov.nasa.worldwind.globes.Globe;
import gov.nasa.worldwind.render.DrawContext;
import gov.nasa.worldwind.render.Renderable;
import java.awt.Color;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.glu.GLU;
import com.jogamp.opengl.glu.GLUquadric;

public class Cone implements Renderable
{

    private Angle myPointLat;
    private Angle myPointLon;
    private Angle myOrientation; // degrees tilted counterclockwise from due East
    private Angle myElevation; // degrees tilted up from the surface
    private double myGroundRange;  // radius of cone base
    private double myCeiling; // length from cone tip to center of base
    private Globe myGlobe;
    private Color color;
    private Vec4 topCenter; // point at center of cylinder base
    
    // solid geometry generation parameters
    private int slices = 30;  // 30
    private int stacks = 1;  // 30
    

    public Cone(Globe globe, double lat, double lon, double mGroundRange, double mCeiling, Angle orientation, Angle elevation, Color theColor)
    {

        this.myPointLat = Angle.fromDegreesLatitude(lat);
        this.myPointLon = Angle.fromDegreesLongitude(lon);
        this.myGlobe = globe;
        this.topCenter = myGlobe.computePointFromPosition(myPointLat, myPointLon, 0.0);
        this.myOrientation = orientation;
        this.myElevation = elevation;
        this.myGroundRange = mGroundRange;
        this.myCeiling = mCeiling;
        this.color = theColor;

    }

    public void render(DrawContext dc)
    {

        Position p = myGlobe.computePositionFromPoint(this.topCenter);

        GL2 gl = dc.getGL().getGL2();

        gl.glPushAttrib(GL2.GL_TEXTURE_BIT | GL2.GL_ENABLE_BIT | GL2.GL_CURRENT_BIT);
        gl.glDisable(GL.GL_TEXTURE_2D);

        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        gl.glColor4ub((byte) getColor().getRed(), (byte) getColor().getGreen(), (byte) getColor().getBlue(), (byte) getColor().getAlpha());

        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glMatrixMode(GL2.GL_MODELVIEW);
        gl.glPushMatrix();

        gl.glTranslated(this.topCenter.x, this.topCenter.y, this.topCenter.z);

        gl.glRotated(90 + p.getLongitude().getDegrees(), 0, 1, 0);
        gl.glRotated(myOrientation.getDegrees(), -1, 0, 0);
        gl.glRotated(p.getLatitude().getDegrees() * myOrientation.sin(), 0, 1, 0);
        gl.glRotated(myElevation.getDegrees(), 0, -1, 0);
        gl.glRotated(p.getLatitude().getDegrees() * myElevation.sin(), -1, 0, 0);

        GLU glu = dc.getGLU();
        GLUquadric quadric = glu.gluNewQuadric();
        glu.gluQuadricDrawStyle(quadric, GLU.GLU_FILL);

        glu.gluCylinder(quadric, 0, this.myGroundRange, this.myCeiling, slices, stacks);
        gl.glTranslated(0, 0, this.myCeiling);
        glu.gluDisk(quadric, 0d, this.myGroundRange, slices, stacks);
        glu.gluDeleteQuadric(quadric);

        gl.glPopMatrix();
        gl.glPopAttrib();

    }

    public void setVertexPosition(double x, double y, double z)
    {
        this.topCenter = new Vec4(x,y,z,0);
        
    }
    
    public void setLatLonRadians(double lat, double lon, double alt)
    {
        this.myPointLat = Angle.fromRadiansLatitude(lat);
        this.myPointLon = Angle.fromRadiansLatitude(lon);
        this.topCenter = myGlobe.computePointFromPosition(myPointLat, myPointLon, alt);
        
    }

    // corrected spelling SEG 30 Mar 2010
    public void setLatitudeRadians(double latRad)
    {
        this.myPointLat = Angle.fromRadiansLatitude(latRad);
        this.topCenter = myGlobe.computePointFromPosition(myPointLat, myPointLon, 0.0);
    }

    // old typo - for backwards compatibility (thanks csuski - from the forum)
    public void setLatitudeReadians(double latRad)
    {
        setLatitudeRadians(latRad);
    }


    public void setLongitudeRadians(double longitude)
    {
        this.myPointLon = Angle.fromRadiansLatitude(longitude);
        this.topCenter = myGlobe.computePointFromPosition(myPointLat, myPointLon, 0.0);
    }

    public void setGroundRange(double myGroundRange)
    {
        this.myGroundRange = myGroundRange;
    }

    public void setHeight(double myCeiling)
    {
        this.myCeiling = myCeiling;
    }

    public Color getColor()
    {
        return color;
    }

    public void setColor(Color color)
    {
        this.color = color;
    }
}

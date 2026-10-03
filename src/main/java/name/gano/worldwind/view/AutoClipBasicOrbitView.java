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

import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.view.orbit.BasicOrbitView;

/**
 * Adds better auto clipping calculations for JSatTrak applications (needing to see beyond the earth)
 * Currently only works for Earth Centered view - not model centered
 * @author Shawn E. Gano
 */
public class AutoClipBasicOrbitView extends BasicOrbitView
{
    private double refRadius = 6378137.0;

    private float autoNearClipFactor = 5.0f; // the near clip plane is set to zoomFactor/this value
    private float autoFarClipFactor = 200.0f;

    @Override
    protected double computeNearClipDistance()
    {
        Position eyePos = getCurrentEyePosition();
        double near = computeNearDistance(eyePos);
        return near / 4.0;
    }

    @Override
    protected double computeFarClipDistance()
    {
        Position eyePos = getCurrentEyePosition();
        return computeFarDistance(eyePos) * 4.0;
    }
}

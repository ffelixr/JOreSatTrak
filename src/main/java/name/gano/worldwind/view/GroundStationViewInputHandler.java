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

import gov.nasa.worldwind.avlist.AVKey;
import gov.nasa.worldwind.awt.AWTInputHandler;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;

/**
 * Custom input handler for {@link GroundStationToSatView}:
 * <ul>
 *   <li><b>Mouse Wheel</b>: Zooms the camera's Field of View (FOV) in/out like a telescope.</li>
 *   <li><b>Left-Click Drag</b>: Adjusts angular Azimuth and Elevation offsets relative to the satellite boresight.</li>
 *   <li><b>Double-Click / 'R' Key</b>: Resets angular offsets and restores the default 45° FOV.</li>
 * </ul>
 */
public class GroundStationViewInputHandler extends AWTInputHandler
{
    private Point lastDragPoint = null;

    public GroundStationViewInputHandler()
    {
        super();
    }

    @Override
    public void mousePressed(MouseEvent mouseEvent)
    {
        if (this.wwd == null || mouseEvent == null)
        {
            return;
        }
        this.mousePoint = mouseEvent.getPoint();
        this.lastDragPoint = mouseEvent.getPoint();
        this.callMousePressedListeners(mouseEvent);
    }

    @Override
    public void mouseReleased(MouseEvent mouseEvent)
    {
        if (this.wwd == null || mouseEvent == null)
        {
            return;
        }
        this.mousePoint = mouseEvent.getPoint();
        this.lastDragPoint = null;
        this.callMouseReleasedListeners(mouseEvent);
    }

    @Override
    public void mouseDragged(MouseEvent mouseEvent)
    {
        if (this.wwd == null || mouseEvent == null)
        {
            return;
        }

        Point prev = this.lastDragPoint != null ? this.lastDragPoint : this.mousePoint;
        Point cur = mouseEvent.getPoint();
        this.lastDragPoint = cur;
        this.mousePoint = cur;

        this.callMouseDraggedListeners(mouseEvent);

        if (this.wwd.getView() instanceof GroundStationToSatView)
        {
            GroundStationToSatView gsView = (GroundStationToSatView) this.wwd.getView();
            if (prev != null)
            {
                int dx = cur.x - prev.x;
                int dy = cur.y - prev.y;

                double fovDeg = gsView.getFieldOfView() != null ? gsView.getFieldOfView().getDegrees() : 45.0;
                int viewH = (gsView.getViewport() != null && gsView.getViewport().height > 0)
                        ? gsView.getViewport().height : 500;
                double degPerPixel = fovDeg / Math.max(100.0, viewH);

                // Dragging right/left adjusts azimuth offset; dragging up/down adjusts elevation offset
                gsView.incrementAzOffset(-dx * degPerPixel);
                gsView.incrementElOffset(dy * degPerPixel);

                gsView.firePropertyChange(AVKey.VIEW, null, gsView);
                this.wwd.redraw();
            }
            mouseEvent.consume();
        }
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent mouseWheelEvent)
    {
        if (this.wwd == null || mouseWheelEvent == null)
        {
            return;
        }

        this.callMouseWheelMovedListeners(mouseWheelEvent);

        if (this.wwd.getView() instanceof GroundStationToSatView)
        {
            GroundStationToSatView gsView = (GroundStationToSatView) this.wwd.getView();
            int wheelRotation = mouseWheelEvent.getWheelRotation();
            if (wheelRotation != 0)
            {
                double factor = Math.pow(1.12, wheelRotation);
                gsView.zoomFov(factor);
                gsView.firePropertyChange(AVKey.VIEW, null, gsView);
                this.wwd.redraw();
            }
            mouseWheelEvent.consume();
        }
    }

    @Override
    public void mouseClicked(MouseEvent mouseEvent)
    {
        if (this.wwd == null || mouseEvent == null)
        {
            return;
        }

        this.callMouseClickedListeners(mouseEvent);

        if (this.wwd.getView() instanceof GroundStationToSatView)
        {
            if (mouseEvent.getButton() == MouseEvent.BUTTON1 && mouseEvent.getClickCount() >= 2)
            {
                GroundStationToSatView gsView = (GroundStationToSatView) this.wwd.getView();
                gsView.resetOffsets();
                gsView.firePropertyChange(AVKey.VIEW, null, gsView);
                this.wwd.redraw();
                mouseEvent.consume();
            }
        }
    }

    @Override
    public void keyPressed(KeyEvent keyEvent)
    {
        if (this.wwd == null || keyEvent == null)
        {
            return;
        }

        this.callKeyPressedListeners(keyEvent);

        if (this.wwd.getView() instanceof GroundStationToSatView)
        {
            GroundStationToSatView gsView = (GroundStationToSatView) this.wwd.getView();
            double stepDeg = Math.max(0.1, (gsView.getFieldOfView() != null ? gsView.getFieldOfView().getDegrees() : 45.0) * 0.05);

            switch (keyEvent.getKeyCode())
            {
                case KeyEvent.VK_R:
                case KeyEvent.VK_HOME:
                    gsView.resetOffsets();
                    break;
                case KeyEvent.VK_LEFT:
                    gsView.incrementAzOffset(-stepDeg);
                    break;
                case KeyEvent.VK_RIGHT:
                    gsView.incrementAzOffset(stepDeg);
                    break;
                case KeyEvent.VK_UP:
                    gsView.incrementElOffset(stepDeg);
                    break;
                case KeyEvent.VK_DOWN:
                    gsView.incrementElOffset(-stepDeg);
                    break;
                case KeyEvent.VK_EQUALS:
                case KeyEvent.VK_PLUS:
                case KeyEvent.VK_ADD:
                    gsView.zoomFov(1.0 / 1.15);
                    break;
                case KeyEvent.VK_MINUS:
                case KeyEvent.VK_SUBTRACT:
                    gsView.zoomFov(1.15);
                    break;
                default:
                    return;
            }
            gsView.firePropertyChange(AVKey.VIEW, null, gsView);
            this.wwd.redraw();
            keyEvent.consume();
        }
    }
}


package jsattrak.gui;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.Hashtable;
import javax.swing.JInternalFrame;
import javax.swing.JToggleButton;
import jsattrak.objects.AbstractSatellite;
import jsattrak.objects.GroundStation;
import jsattrak.objects.SatelliteTleSGP4;
import jsattrak.utilities.J2DPolarEarthPanelSave;
import name.gano.astro.bodies.Sun;
import name.gano.astro.time.Time;

/**
 * Unit tests for the 2D Polar Projection Window ({@link J2dPolarEarthLabel},
 * {@link J2DPolarEarthPanel}, and {@link J2DPolarEarthPanelSave}).
 */
public class PolarProjectionTest
{
    static
    {
        System.setProperty("java.awt.headless", "true");
        JSatTrak.initOrekitData();
    }

    public void testPoleCenters()
    {
        int cx = 250, cy = 250;
        double radius = 200.0;
        double maxAng = 90.0;

        int[] northPoleXY = J2dPolarEarthLabel.latLonToPolarXY(90.0, 45.0, +1, cx, cy, radius, maxAng);
        if (northPoleXY[0] != cx || northPoleXY[1] != cy)
        {
            throw new AssertionError("North Pole (+90 deg) must map to disc center");
        }

        int[] southPoleXY = J2dPolarEarthLabel.latLonToPolarXY(-90.0, -120.0, -1, cx, cy, radius, maxAng);
        if (southPoleXY[0] != cx || southPoleXY[1] != cy)
        {
            throw new AssertionError("South Pole (-90 deg) must map to disc center");
        }
    }

    public void testForwardAndInverseProjectionRoundTrip()
    {
        int cx = 300, cy = 300;
        double radius = 240.0;
        double maxAng = 90.0;

        double[][] testCoords = {
            { 75.0, 0.0 },
            { 60.0, 90.0 },
            { 45.0, -90.0 },
            { 30.0, 179.0 },
            { 30.0, -179.0 },
            { 15.0, -45.0 }
        };

        for (double[] ll : testCoords)
        {
            // North Pole round-trip
            int[] xyN = J2dPolarEarthLabel.latLonToPolarXY(ll[0], ll[1], +1, cx, cy, radius, maxAng);
            double[] invN = J2dPolarEarthLabel.polarXYToLatLon(xyN[0], xyN[1], +1, cx, cy, radius, maxAng);
            if (invN == null || Math.abs(invN[0] - ll[0]) > 1.0 || Math.abs(invN[1] - ll[1]) > 1.0)
            {
                throw new AssertionError("North Pole round-trip failed for (" + ll[0] + ", " + ll[1] + ")");
            }

            // South Pole round-trip
            int[] xyS = J2dPolarEarthLabel.latLonToPolarXY(-ll[0], ll[1], -1, cx, cy, radius, maxAng);
            double[] invS = J2dPolarEarthLabel.polarXYToLatLon(xyS[0], xyS[1], -1, cx, cy, radius, maxAng);
            if (invS == null || Math.abs(invS[0] - (-ll[0])) > 1.0 || Math.abs(invS[1] - ll[1]) > 1.0)
            {
                throw new AssertionError("South Pole round-trip failed for (" + (-ll[0]) + ", " + ll[1] + ")");
            }
        }
    }

    public void testInternationalDateLineContinuity()
    {
        int cx = 250, cy = 250;
        double radius = 200.0;
        double maxAng = 90.0;

        int[] xyPos180 = J2dPolarEarthLabel.latLonToPolarXY(70.0, 179.9, +1, cx, cy, radius, maxAng);
        int[] xyNeg180 = J2dPolarEarthLabel.latLonToPolarXY(70.0, -179.9, +1, cx, cy, radius, maxAng);
        double dist = Math.hypot(xyPos180[0] - xyNeg180[0], xyPos180[1] - xyNeg180[1]);
        if (dist > 2.0)
        {
            throw new AssertionError("Points across +/-180 deg date line must be continuous, dist=" + dist);
        }
    }

    public void testDiscGeometriesAndModes()
    {
        J2DPolarEarthPanel panel = new J2DPolarEarthPanel();
        J2dPolarEarthLabel label = panel.getPolarLabel();

        panel.setPolarMode(J2dPolarEarthLabel.MODE_NORTH);
        int[][] northDiscs = label.computeDiscGeometries(600, 400);
        if (northDiscs.length != 1 || northDiscs[0][0] != +1)
        {
            throw new AssertionError("MODE_NORTH should produce 1 disc with poleSign=+1");
        }

        panel.setPolarMode(J2dPolarEarthLabel.MODE_SOUTH);
        int[][] southDiscs = label.computeDiscGeometries(600, 400);
        if (southDiscs.length != 1 || southDiscs[0][0] != -1)
        {
            throw new AssertionError("MODE_SOUTH should produce 1 disc with poleSign=-1");
        }

        panel.setPolarMode(J2dPolarEarthLabel.MODE_DUAL);
        int[][] dualLandscape = label.computeDiscGeometries(800, 400);
        if (dualLandscape.length != 2 || dualLandscape[0][0] != +1 || dualLandscape[1][0] != -1)
        {
            throw new AssertionError("MODE_DUAL should produce 2 discs (+1 and -1)");
        }
        if (dualLandscape[0][1] >= dualLandscape[1][1])
        {
            throw new AssertionError("Landscape MODE_DUAL should place North disc left of South disc");
        }
    }

    public void testOffScreenRenderAndPersistence() throws Exception
    {
        Time time = new Time(2026, 11, 8, 12, 0, 0.0);
        Sun sun = new Sun(time.getMJD());
        Hashtable<String, AbstractSatellite> satHash = new Hashtable<String, AbstractSatellite>();
        Hashtable<String, GroundStation> gsHash = new Hashtable<String, GroundStation>();

        SatelliteTleSGP4 iss = new SatelliteTleSGP4(
                "ISS (ZARYA)",
                "1 25544U 98067A   21243.51095395  .00001875  00000-0  42731-4 0  9994",
                "2 25544  51.6442 208.5947 0003534 319.4494 162.8835 15.48881082300253");
        iss.propogate2JulDate(time.getJulianDate());
        satHash.put(iss.getName(), iss);

        GroundStation svalbard = new GroundStation("Svalbard", new double[] { 78.2298, 15.4078, 450.0 }, time.getJulianDate());
        gsHash.put(svalbard.getStationName(), svalbard);

        J2DPolarEarthPanel panel = new J2DPolarEarthPanel(
                satHash,
                gsHash,
                new JToggleButton(),
                new JToggleButton(),
                new JToggleButton(),
                time,
                sun,
                null);
        panel.setSize(600, 400);
        panel.getPolarLabel().setSize(600, 400);

        for (int mode : new int[] { J2dPolarEarthLabel.MODE_NORTH, J2dPolarEarthLabel.MODE_SOUTH, J2dPolarEarthLabel.MODE_DUAL })
        {
            panel.setPolarMode(mode);
            BufferedImage img = panel.offScreenRender();
            if (img == null || img.getWidth() != 600 || img.getHeight() != 400)
            {
                throw new AssertionError("Offscreen render failed for mode " + mode);
            }
        }

        // Verify persistence save & restore
        panel.setPolarMode(J2dPolarEarthLabel.MODE_DUAL);
        panel.setMinAbsLatitudeDeg(30.0);
        panel.setZoomFactor(2.0);

        J2DPolarEarthPanelSave savedState = new J2DPolarEarthPanelSave(panel, 40, 50, new Dimension(640, 480));
        J2DPolarEarthPanel restoredPanel = new J2DPolarEarthPanel();
        JInternalFrame dummyFrame = new JInternalFrame();
        savedState.copySettings2PanelAndFrame(restoredPanel, dummyFrame);

        if (restoredPanel.getPolarMode() != J2dPolarEarthLabel.MODE_DUAL
                || Math.abs(restoredPanel.getMinAbsLatitudeDeg() - 30.0) > 1e-6
                || Math.abs(restoredPanel.getZoomFactor() - 2.0) > 1e-6)
        {
            throw new AssertionError("J2DPolarEarthPanelSave failed to restore panel state");
        }
    }

    public static void main(String[] args) throws Exception
    {
        PolarProjectionTest test = new PolarProjectionTest();
        test.testPoleCenters();
        test.testForwardAndInverseProjectionRoundTrip();
        test.testInternationalDateLineContinuity();
        test.testDiscGeometriesAndModes();
        test.testOffScreenRenderAndPersistence();
        System.out.println("PolarProjectionTest: All 5 unit test methods passed!");
    }
}

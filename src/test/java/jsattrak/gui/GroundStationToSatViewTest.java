package jsattrak.gui;

import gov.nasa.worldwind.globes.Earth;
import gov.nasa.worldwind.globes.Globe;
import java.awt.Color;
import jsattrak.objects.AbstractSatellite;
import jsattrak.objects.GroundStation;
import jsattrak.utilities.TLE;
import name.gano.worldwind.modelloader.WWModel3D_new;
import name.gano.worldwind.view.GroundStationToSatView;

/**
 * Unit tests for {@link GroundStationToSatView} camera orientation, local ENU
 * Azimuth/Elevation/Range tracking, horizon clamping, FOV zooming, and angular offsets.
 */
public class GroundStationToSatViewTest
{
    static
    {
        gov.nasa.worldwind.WorldWind.setOfflineMode(true);
    }

    private static final double EPSILON = 1e-2;

    private static void assertEquals(String msg, double expected, double actual, double delta)
    {
        if (Math.abs(expected - actual) > delta)
        {
            throw new AssertionError(msg + " — expected: " + expected + ", actual: " + actual);
        }
    }

    private static void assertTrue(String msg, boolean condition)
    {
        if (!condition)
        {
            throw new AssertionError(msg);
        }
    }

    private static void assertFalse(String msg, boolean condition)
    {
        if (condition)
        {
            throw new AssertionError(msg);
        }
    }

    /**
     * Minimal test satellite stub that returns configurable Latitude (radians),
     * Longitude (radians), and Altitude (meters).
     */
    private static class StubSatellite extends AbstractSatellite
    {
        private final String name;
        private final double latRad;
        private final double lonRad;
        private final double altMeters;

        StubSatellite(String name, double latDeg, double lonDeg, double altMeters)
        {
            this.name = name;
            this.latRad = Math.toRadians(latDeg);
            this.lonRad = Math.toRadians(lonDeg);
            this.altMeters = altMeters;
        }

        @Override
        public String getName()
        {
            return name;
        }

        @Override
        public double[] getLLA()
        {
            return new double[] { latRad, lonRad, altMeters };
        }

        @Override
        public double getLatitude()
        {
            return latRad;
        }

        @Override
        public double getLongitude()
        {
            return lonRad;
        }

        @Override
        public double getAltitude()
        {
            return altMeters;
        }

        @Override public double[] calculateJ2KPositionFromUT(double julDate) { return new double[3]; }
        @Override public double[] calculateTemePositionFromUT(double julDate) { return new double[3]; }
        @Override public double getCurrentJulDate() { return 0; }
        @Override public int getGrnTrkPointsPerPeriod() { return 0; }
        @Override public boolean getGroundTrackIni() { return false; }
        @Override public double getGroundTrackLagPeriodMultiplier() { return 0; }
        @Override public double getGroundTrackLeadPeriodMultiplier() { return 0; }
        @Override public double[] getGroundTrackLlaLagPt(int index) { return new double[3]; }
        @Override public double[] getGroundTrackLlaLeadPt(int index) { return new double[3]; }
        @Override public double[] getGroundTrackXyzLagPt(int index) { return new double[3]; }
        @Override public double[] getGroundTrackXyzLeadPt(int index) { return new double[3]; }
        @Override public double[] getJ2000Position() { return new double[3]; }
        @Override public double[] getJ2000Velocity() { return new double[3]; }
        @Override public double[] getKeplarianElements() { return new double[6]; }
        @Override public double[][] getTemePosLag() { return new double[0][0]; }
        @Override public double[][] getTemePosLead() { return new double[0][0]; }
        @Override public int getNumGroundTrackLagPts() { return 0; }
        @Override public int getNumGroundTrackLeadPts() { return 0; }
        @Override public int getNumPtsFootPrint() { return 0; }
        @Override public double getPeriod() { return 0; }
        @Override public boolean getPlot2D() { return false; }
        @Override public boolean getPlot2DFootPrint() { return false; }
        @Override public double[] getTEMEPos() { return new double[3]; }
        @Override public Color getSatColor() { return Color.RED; }
        @Override public double getSatTleEpochJulDate() { return 0; }
        @Override public boolean getShowGroundTrack() { return false; }
        @Override public double[] getTimeLag() { return new double[0]; }
        @Override public double[] getTimeLead() { return new double[0]; }
        @Override public double getTleAgeDays() { return 0; }
        @Override public double getTleEpochJD() { return 0; }
        @Override public boolean isFillFootPrint() { return false; }
        @Override public boolean isShow3D() { return true; }
        @Override public boolean isShow3DFootprint() { return false; }
        @Override public boolean isShow3DName() { return true; }
        @Override public boolean isShow3DOrbitTrace() { return false; }
        @Override public boolean isShow3DOrbitTraceECI() { return false; }
        @Override public boolean isShowGroundTrack3d() { return false; }
        @Override public boolean isShowName2D() { return false; }
        @Override public void propogate2JulDate(double julDate) {}
        @Override public void setFillFootPrint(boolean fillFootPrint) {}
        @Override public void setGrnTrkPointsPerPeriod(int grnTrkPointsPerPeriod) {}
        @Override public void setGroundTrackIni2False() {}
        @Override public void setGroundTrackLagPeriodMultiplier(double groundTrackLagPeriodMultiplier) {}
        @Override public void setGroundTrackLeadPeriodMultiplier(double groundTrackLeadPeriodMultiplier) {}
        @Override public void setNumPtsFootPrint(int numPtsFootPrint) {}
        @Override public void setPlot2DFootPrint(boolean plot2DFootPrint) {}
        @Override public void setPlot2d(boolean plot2d) {}
        @Override public void setSatColor(Color satColor) {}
        @Override public void setShow3D(boolean show3D) {}
        @Override public void setShow3DFootprint(boolean show3DFootprint) {}
        @Override public void setShow3DName(boolean show3DName) {}
        @Override public void setShow3DOrbitTrace(boolean show3DOrbitTrace) {}
        @Override public void setShow3DOrbitTraceECI(boolean show3DOrbitTraceECI) {}
        @Override public void setShowGroundTrack(boolean showGrndTrk) {}
        @Override public void setShowGroundTrack3d(boolean showGroundTrack3d) {}
        @Override public void setShowName2D(boolean showName2D) {}
        @Override public void updateTleData(TLE newTLE) {}
        @Override public boolean isUse3dModel() { return false; }
        @Override public void setUse3dModel(boolean use3dModel) {}
        @Override public String getThreeDModelPath() { return ""; }
        @Override public void setThreeDModelPath(String path) {}
        @Override public WWModel3D_new getThreeDModel() { return null; }
        @Override public double[] getTEMEVelocity() { return new double[3]; }
        @Override public double getThreeDModelSizeFactor() { return 1.0; }
        @Override public void setThreeDModelSizeFactor(double modelSizeFactor) {}
    }

    public void testZenithDueNorthAndDueEastTracking()
    {
        Globe globe = new Earth();
        GroundStation gs = new GroundStation("TestGS", new double[] { 0.0, 0.0, 0.0 }, 10.0);
        GroundStationToSatView view = new GroundStationToSatView();
        view.setGroundStation(gs);

        // 1. Target directly overhead (Zenith at 0 deg Lat, 0 deg Lon, 500 km altitude)
        StubSatellite zenithSat = new StubSatellite("ZenithSat", 0.0, 0.0, 500000.0);
        view.setSatellite(zenithSat);
        view.updateTrackingState(globe);

        assertEquals("Zenith elevation should be 90 deg", 90.0, view.getCurrentElDeg(), 0.1);
        assertTrue("Zenith target is above horizon", view.isTargetAboveHorizon());
        assertTrue("Zenith target satisfies 10 deg elevation mask", view.isTargetAboveElevationMask());
        assertEquals("Camera pitch at zenith should be 180 deg", 180.0, view.getPitch().degrees, 0.1);

        // 2. Target due North (Lat = +5 deg, Lon = 0 deg, Alt = 800 km)
        StubSatellite northSat = new StubSatellite("NorthSat", 5.0, 0.0, 800000.0);
        view.setSatellite(northSat);
        view.updateTrackingState(globe);

        double azNorth = view.getCurrentAzDeg();
        assertTrue("Due North azimuth should be near 0 or 360 deg", azNorth < 0.1 || azNorth > 359.9);
        assertTrue("Due North satellite at 800 km should be above horizon", view.getCurrentElDeg() > 40.0);
        assertEquals("Camera pitch should equal 90 + elevation",
                90.0 + view.getCurrentElDeg(), view.getPitch().degrees, EPSILON);

        // 3. Target due East (Lat = 0 deg, Lon = +5 deg, Alt = 800 km)
        StubSatellite eastSat = new StubSatellite("EastSat", 0.0, 5.0, 800000.0);
        view.setSatellite(eastSat);
        view.updateTrackingState(globe);

        assertEquals("Due East azimuth should be 90 deg", 90.0, view.getCurrentAzDeg(), 0.1);
        assertEquals("Camera heading should match due East azimuth (90 deg)", 90.0, view.getHeading().degrees, 0.1);
        assertEquals("Camera pitch should equal 90 + elevation",
                90.0 + view.getCurrentElDeg(), view.getPitch().degrees, EPSILON);
    }

    public void testBelowHorizonClampingAndUnclampedXRayMode()
    {
        Globe globe = new Earth();
        GroundStation gs = new GroundStation("TestGS", new double[] { 0.0, 0.0, 0.0 }, 10.0);
        // Satellite on the opposite side of the Earth (due East, 90 deg longitude, 400 km altitude -> below horizon)
        StubSatellite belowHorizonSat = new StubSatellite("BelowSat", 0.0, 90.0, 400000.0);

        GroundStationToSatView view = new GroundStationToSatView();
        view.setGroundStation(gs);
        view.setSatellite(belowHorizonSat);

        // Default: clampToHorizon = true
        assertTrue("Clamp to horizon should be enabled by default", view.isClampToHorizon());
        view.updateTrackingState(globe);

        assertTrue("Geometric elevation should be negative", view.getCurrentElDeg() < -30.0);
        assertFalse("Target should be flagged as below horizon", view.isTargetAboveHorizon());
        assertFalse("Target should not satisfy elevation mask", view.isTargetAboveElevationMask());
        assertEquals("When clamped to horizon, camera pitch should stay at 90 deg (0 deg local horizon)",
                90.0, view.getPitch().degrees, EPSILON);
        assertEquals("Camera heading should still follow target azimuth (90 deg East)",
                90.0, view.getHeading().degrees, 0.2);

        // Unclamped (3D x-ray tracking through Earth): clampToHorizon = false
        view.setClampToHorizon(false);
        view.updateTrackingState(globe);

        assertTrue("When unclamped, camera pitch should dip below 90 deg (< 0 deg elevation)",
                view.getPitch().degrees < 60.0);
        assertEquals("Camera pitch should equal 90 + negative elevation",
                90.0 + view.getCurrentElDeg(), view.getPitch().degrees, EPSILON);
    }

    public void testFovZoomAndAngularOffsets()
    {
        Globe globe = new Earth();
        GroundStation gs = new GroundStation("TestGS", new double[] { 0.0, 0.0, 0.0 }, 5.0);
        StubSatellite eastSat = new StubSatellite("EastSat", 0.0, 5.0, 800000.0);

        GroundStationToSatView view = new GroundStationToSatView();
        view.setGroundStation(gs);
        view.setSatellite(eastSat);

        // Verify FOV zoom clamping between [0.5, 90.0] degrees
        view.zoomFov(0.001);
        assertEquals("Minimum FOV should clamp to 0.5 deg",
                GroundStationToSatView.MIN_FOV_DEGREES, view.getFieldOfView().degrees, EPSILON);

        view.zoomFov(1000.0);
        assertEquals("Maximum FOV should clamp to 90.0 deg",
                GroundStationToSatView.MAX_FOV_DEGREES, view.getFieldOfView().degrees, EPSILON);

        // Apply angular offsets and check camera heading/pitch shift
        view.incrementAzOffset(10.0);
        view.incrementElOffset(5.0);
        view.updateTrackingState(globe);

        assertEquals("Camera heading should include +10 deg Az offset",
                view.getCurrentAzDeg() + 10.0, view.getHeading().degrees, EPSILON);
        assertEquals("Camera pitch should include +5 deg El offset",
                90.0 + view.getCurrentElDeg() + 5.0, view.getPitch().degrees, EPSILON);

        // Reset offsets
        view.resetOffsets();
        view.updateTrackingState(globe);
        assertEquals("Az offset should reset to 0", 0.0, view.getAzOffsetDeg(), EPSILON);
        assertEquals("El offset should reset to 0", 0.0, view.getElOffsetDeg(), EPSILON);
        assertEquals("FOV should reset to default 45 deg",
                GroundStationToSatView.DEFAULT_FOV_DEGREES, view.getFieldOfView().degrees, EPSILON);
        assertEquals("Camera heading should return to exact target azimuth after reset",
                view.getCurrentAzDeg(), view.getHeading().degrees, EPSILON);
    }

    public void testTrackingPanelInitialization()
    {
        System.setProperty("java.awt.headless", "true");
        JSatTrak.initOrekitData();
        java.util.Hashtable<String, AbstractSatellite> satHash = new java.util.Hashtable<>();
        java.util.Hashtable<String, GroundStation> gsHash = new java.util.Hashtable<>();
        name.gano.astro.time.Time time = new name.gano.astro.time.Time();

        JTrackingPanel panel = new JTrackingPanel(satHash, gsHash, "2026-11-08 20:00:00 UTC", time, null);
        assertTrue("JTrackingPanel should initialize without NPE", panel != null);
        assertTrue("PolarPlotLabel should be accessible", panel.getPolarPlotLabel() != null);
    }

    public static void main(String[] args)
    {
        GroundStationToSatViewTest test = new GroundStationToSatViewTest();
        test.testZenithDueNorthAndDueEastTracking();
        test.testBelowHorizonClampingAndUnclampedXRayMode();
        test.testFovZoomAndAngularOffsets();
        test.testTrackingPanelInitialization();
        System.out.println("GroundStationToSatViewTest: All 4 unit test methods passed!");
    }
}

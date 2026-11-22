package jsattrak.awareness.gnss.gui;

import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.GnssDisplayStatus;
import jsattrak.awareness.gnss.geometry.GnssDopSample;
import jsattrak.awareness.gnss.geometry.GnssDopSolution;
import jsattrak.awareness.gnss.geometry.GnssObserverPosition;
import jsattrak.awareness.gnss.geometry.GnssOrbitSource;
import jsattrak.awareness.gnss.geometry.SatelliteSkyPoint;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GnssGeometryWidgetsTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void skyplotAndDopChartPaintCleanlyAndSupportHitTestAndClickToJump() throws Exception {
        long t0 = 1_795_132_800_000L;
        GnssObserverPosition obs = new GnssObserverPosition("Madrid", 40.4168, -3.7038, 650.0);
        List<SatelliteSkyPoint> skyPoints = List.of(
                new SatelliteSkyPoint("G01", GnssConstellation.GPS, GnssDisplayStatus.OPERATIONAL, 0.0, 90.0, true, true, GnssOrbitSource.BRDC),
                new SatelliteSkyPoint("E11", GnssConstellation.GALILEO, GnssDisplayStatus.OPERATIONAL, 90.0, 30.0, true, true, GnssOrbitSource.BRDC),
                new SatelliteSkyPoint("G07", GnssConstellation.GPS, GnssDisplayStatus.UNAVAILABLE, 180.0, 45.0, true, false, GnssOrbitSource.BRDC),
                new SatelliteSkyPoint("R05", GnssConstellation.GLONASS, GnssDisplayStatus.OPERATIONAL, 270.0, 5.0, false, false, GnssOrbitSource.TLE_FALLBACK)
        );
        GnssDopSolution sol = new GnssDopSolution(
                t0, obs, 10.0, true, 2.4, 2.1, 1.2, 1.7, 1.1,
                3, 2, Map.of(GnssConstellation.GPS, 2, GnssConstellation.GALILEO, 1),
                skyPoints, new GnssOrbitSource.Summary(GnssOrbitSource.BRDC, 0, 3, 0, false, ""));

        List<GnssDopSample> series = new ArrayList<>(145);
        long start = t0 - 6L * 3600_000L;
        for (int i = 0; i < 145; i++) {
            long ep = start + i * 10L * 60_000L;
            boolean noFixGap = (i >= 60 && i <= 66);
            series.add(new GnssDopSample(
                    ep,
                    !noFixGap,
                    noFixGap ? Double.NaN : 2.5,
                    noFixGap ? Double.NaN : 2.1,
                    noFixGap ? Double.NaN : 1.2,
                    noFixGap ? Double.NaN : 1.7,
                    noFixGap ? Double.NaN : 1.1,
                    noFixGap ? 3 : 8,
                    Map.of(GnssConstellation.GPS, noFixGap ? 3 : 5)));
        }

        SwingUtilities.invokeAndWait(() -> {
            GnssSkyplotPanel skyplot = new GnssSkyplotPanel();
            skyplot.setSize(300, 300);
            skyplot.setSolution(sol);
            BufferedImage skyImg = new BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gSky = skyImg.createGraphics();
            skyplot.paint(gSky);
            gSky.dispose();

            SatelliteSkyPoint hit = skyplot.findSatelliteAt(150, 150);
            assertNotNull(hit, "Expected zenith satellite hit near (150, 150)");
            assertEquals("G01", hit.prnId());

            AtomicReference<Long> jumped = new AtomicReference<>();
            GnssDopChartPanel chart = new GnssDopChartPanel(jumped::set);
            chart.setSize(500, 260);
            chart.setSeries(series, t0, TimeZone.getTimeZone("UTC"));
            BufferedImage chartImg = new BufferedImage(500, 260, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gChart = chartImg.createGraphics();
            chart.paint(gChart);
            gChart.dispose();

            long midExpected = start + 72L * 10L * 60_000L;
            long midActual = chart.epochAtX(250);
            assertTrue(midActual >= start && midActual <= start + 144L * 10L * 60_000L);
            assertEquals(midExpected, midActual, 3600_000L);

            MouseEvent click = new MouseEvent(
                    chart, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                    0, 250, 130, 1, false, MouseEvent.BUTTON1);
            for (var ml : chart.getMouseListeners()) {
                ml.mouseClicked(click);
            }
            assertNotNull(jumped.get());
        });
    }
}

package jsattrak.awareness.gnss.map;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import javax.swing.JInternalFrame;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.stations.GnssReferenceStation;
import jsattrak.awareness.gnss.stations.GnssStationNetwork;
import jsattrak.awareness.gnss.stations.GnssStationStatus;
import jsattrak.awareness.gnss.stations.analysis.RegionalScanReport;
import jsattrak.awareness.gnss.stations.analysis.RegionalScanReport.RegionalEventSeverity;
import jsattrak.gui.J2DEarthPanel;
import jsattrak.gui.J2DPolarEarthPanel;
import jsattrak.utilities.J2DEarthPanelSave;
import jsattrak.utilities.J2DPolarEarthPanelSave;
import org.junit.jupiter.api.Test;

public class GnssStationsMapOverlay2DTest {

    @Test
    void clustersDenseStationsHitsSingleStationDrawsInterferenceRingAndPreservesLegacySaveDefaults() {
        List<GnssReferenceStation> denseStations = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            double lat = 40.0 + (i / 5) * 0.15;
            double lon = -4.0 + (i % 5) * 0.15;
            String id = String.format(Locale.US, "ST%02d00ESP", i);
            denseStations.add(makeStation(id, lat, lon, GnssStationStatus.ONLINE));
        }

        RegionalScanReport interferenceReport = new RegionalScanReport(
                RegionalEventSeverity.POTENTIAL_INTERFERENCE,
                "Simulated regional L1 interference",
                EnumSet.of(GnssConstellation.GPS, GnssConstellation.GALILEO),
                40.3,
                -3.7,
                500.0,
                List.of(),
                List.of("G01", "E11"),
                Map.of());

        BiFunction<Double, Double, int[]> eqProjector = (lat, lon) -> new int[]{
                (int) Math.round((lon + 180.0) / 360.0 * 600.0),
                (int) Math.round((90.0 - lat) / 180.0 * 300.0)
        };

        GnssStationsOverlay2DRenderer renderer = new GnssStationsOverlay2DRenderer();
        BufferedImage eqImg = new BufferedImage(600, 300, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gEq = eqImg.createGraphics();
        GnssStationsOverlay2DRenderer.RenderSummary eqSummary = renderer.renderEquirectangular(
                gEq,
                denseStations,
                "ST0000ESP",
                interferenceReport,
                new Rectangle(0, 0, 600, 300),
                eqProjector);
        gEq.dispose();

        assertTrue(eqSummary.renderedClusters() < 25,
                "Expected dense stations to cluster (<25 clusters), got " + eqSummary.renderedClusters());
        assertTrue(eqSummary.markerCount() > 0, "Expected non-empty rendered markers");
        assertTrue(eqSummary.interferenceZoneDrawn(), "Expected interference ring to be drawn");

        // Verify crimson ring / marker pixels present on image
        boolean foundCrimsonPixel = false;
        for (int y = 0; y < eqImg.getHeight() && !foundCrimsonPixel; y++) {
            for (int x = 0; x < eqImg.getWidth(); x++) {
                int argb = eqImg.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                if (a > 120 && r > 180 && g < 110 && b < 110) {
                    foundCrimsonPixel = true;
                    break;
                }
            }
        }
        assertTrue(foundCrimsonPixel, "Expected crimson interference ring pixels");

        // Single well-separated station hit-test in equirectangular and polar projections
        GnssReferenceStation isolated = makeStation("KIRU00SWE", 67.857, 20.968, GnssStationStatus.ONLINE);
        BufferedImage singleEqImg = new BufferedImage(600, 300, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gSingleEq = singleEqImg.createGraphics();
        renderer.renderEquirectangular(
                gSingleEq,
                List.of(isolated),
                null,
                null,
                new Rectangle(0, 0, 600, 300),
                eqProjector);
        gSingleEq.dispose();

        int[] eqPt = eqProjector.apply(isolated.latDeg(), isolated.lonDeg());
        assertEquals("KIRU00SWE", renderer.hitTestEquirectangular(eqPt[0], eqPt[1], 12.0));

        BiFunction<Double, Double, int[]> polarProjector = (lat, lon) -> {
            double colatRad = Math.toRadians(90.0 - lat);
            double rPx = (colatRad / (Math.PI / 2.0)) * 180.0;
            double theta = Math.toRadians(lon);
            return new int[]{
                    (int) Math.round(200 + rPx * Math.sin(theta)),
                    (int) Math.round(200 + rPx * Math.cos(theta)),
                    1
            };
        };

        BufferedImage polarImg = new BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gPolar = polarImg.createGraphics();
        GnssStationsOverlay2DRenderer.RenderSummary polarSummary = renderer.renderPolar(
                gPolar,
                List.of(isolated),
                "KIRU00SWE",
                null,
                new Rectangle(0, 0, 400, 400),
                polarProjector);
        gPolar.dispose();

        assertEquals(1, polarSummary.markerCount());
        int[] polarPt = polarProjector.apply(isolated.latDeg(), isolated.lonDeg());
        assertEquals("KIRU00SWE", renderer.hitTestPolar(polarPt[0], polarPt[1], 12.0));

        String tooltip = renderer.formatTooltipHtml(isolated, interferenceReport);
        assertTrue(tooltip.contains("KIRU") && tooltip.contains("SEPTENTRIO"),
                "Expected HTML tooltip with station code & receiver, got: " + tooltip);

        // Legacy save defaults: null showGnssStations resolves to true in both 2D & Polar saves
        J2DEarthPanel panel2d = new J2DEarthPanel();
        assertTrue(panel2d.isShowGnssStations(), "J2DEarthPanel default showGnssStations must be true");
        J2DEarthPanelSave save2d = new J2DEarthPanelSave(panel2d, 0, 0, new java.awt.Dimension(600, 300));
        save2d.setShowGnssStations(null);
        assertTrue(J2DEarthPanelSave.resolveShowGnssStationsDefault(save2d.getShowGnssStations()));
        panel2d.setShowGnssStations(false);
        save2d.copySettings2PanelAndFrame(panel2d, new JInternalFrame());
        assertTrue(panel2d.isShowGnssStations(), "Legacy null save field must restore true");

        J2DPolarEarthPanel panelPolar = new J2DPolarEarthPanel();
        assertTrue(panelPolar.isShowGnssStations(), "J2DPolarEarthPanel default showGnssStations must be true");
        J2DPolarEarthPanelSave savePolar = new J2DPolarEarthPanelSave(panelPolar, 0, 0, new java.awt.Dimension(400, 400));
        savePolar.setShowGnssStations(null);
        assertTrue(J2DPolarEarthPanelSave.resolveShowGnssStationsDefault(savePolar.getShowGnssStations()));
        panelPolar.setShowGnssStations(false);
        savePolar.copySettings2PanelAndFrame(panelPolar, new JInternalFrame());
        assertTrue(panelPolar.isShowGnssStations(), "Legacy null polar save field must restore true");
    }

    private static GnssReferenceStation makeStation(String id, double lat, double lon, GnssStationStatus status) {
        return new GnssReferenceStation(
                id,
                id.substring(0, 4),
                id + " Site",
                "ESP",
                GnssStationNetwork.IGS,
                Set.of(GnssStationNetwork.IGS, GnssStationNetwork.EUREF),
                lat,
                lon,
                650.0,
                null,
                "SEPTENTRIO POLARX5",
                "5.5.0",
                "SEPCHOKE_B3E6",
                "NONE",
                EnumSet.of(GnssConstellation.GPS, GnssConstellation.GALILEO),
                List.of("RINEX3"),
                1_795_720_000_000L,
                18,
                99.5,
                status,
                null);
    }
}

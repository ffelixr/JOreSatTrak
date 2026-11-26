package jsattrak.awareness.gnss.stations.analysis;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.stations.GnssReferenceStation;
import jsattrak.awareness.gnss.stations.GnssStationNetwork;
import jsattrak.awareness.gnss.stations.GnssStationStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RegionalCn0AnomalyDetectorTest {

    @Test
    void distinguishesLowElevationAttenuationLocalFaultAndRegionalPotentialInterference() {
        long t0 = Instant.parse("2026-11-25T18:00:00Z").toEpochMilli();
        GnssReferenceStation st1 = station("ST0100ESP", 40.40, -3.70);
        GnssReferenceStation st2 = station("ST0200ESP", 40.70, -3.40); // ~42 km away
        GnssReferenceStation st3 = station("ST0300ESP", 40.20, -4.10);
        GnssReferenceStation st4 = station("ST0400ESP", 41.00, -3.90);

        // 1. 4 stations where only el=11 deg satellites have low C/N0 (34 dB-Hz) -> NOMINAL
        List<RegionalCn0AnomalyDetector.StationObservationInput> lowElOnly = List.of(
                input(st1, t0, 11.0, 34.0, 0.0, 0),
                input(st2, t0, 11.0, 34.0, 0.0, 0),
                input(st3, t0, 11.0, 34.0, 0.0, 0),
                input(st4, t0, 11.0, 34.0, 0.0, 0)
        );
        RegionalScanReport lowElReport = RegionalCn0AnomalyDetector.evaluate(lowElOnly);
        assertEquals(RegionalScanReport.RegionalEventSeverity.NOMINAL, lowElReport.severity());

        // 2. 1 station drops -8 dB-Hz at el=45 deg while 3 neighbors stay NOMINAL -> LOCAL_DEGRADATION
        List<RegionalCn0AnomalyDetector.StationObservationInput> singleDrop = List.of(
                input(st1, t0, 45.0, 37.5, 0.0, 0), // expected at 45 deg ~45.5 -> delta -8.0
                input(st2, t0, 45.0, 45.8, 0.0, 0),
                input(st3, t0, 45.0, 45.6, 0.0, 0),
                input(st4, t0, 45.0, 46.0, 0.0, 0)
        );
        RegionalScanReport localReport = RegionalCn0AnomalyDetector.evaluate(singleDrop);
        assertEquals(RegionalScanReport.RegionalEventSeverity.LOCAL_DEGRADATION, localReport.severity());

        // 3. 2 neighboring stations both show >= -7 dB-Hz drops on GPS at el >= 15 within overlapping 15-min windows -> POTENTIAL_INTERFERENCE
        List<RegionalCn0AnomalyDetector.StationObservationInput> multiDrop = List.of(
                input(st1, t0, 45.0, 37.0, 0.0, 0),
                input(st2, t0 + 300_000L, 50.0, 38.0, 0.0, 0),
                input(st3, t0, 45.0, 45.8, 0.0, 0),
                input(st4, t0, 45.0, 46.1, 0.0, 0)
        );
        RegionalScanReport regionalReport = RegionalCn0AnomalyDetector.evaluate(multiDrop);
        assertEquals(RegionalScanReport.RegionalEventSeverity.POTENTIAL_INTERFERENCE, regionalReport.severity());
        assertTrue(regionalReport.affectedConstellations().contains(GnssConstellation.GPS));
        assertTrue(regionalReport.radiusKm() >= 20.0);
        assertTrue(regionalReport.centroidLatDeg() >= 40.35 && regionalReport.centroidLatDeg() <= 40.75);
        assertFalse(regionalReport.prnColumns().isEmpty());
        assertTrue(regionalReport.deltaCn0Matrix().get("ST0100ESP").get("G01") <= -7.0);

        // 4. Unknown-elevation (NaN) SVs must NOT bypass the < 15 deg horizon filter
        List<RegionalCn0AnomalyDetector.StationObservationInput> nanElevationOnly = List.of(
                input(st1, t0, Double.NaN, 34.0, 0.0, 0),
                input(st2, t0, Double.NaN, 33.0, 0.0, 0)
        );
        RegionalScanReport nanElReport = RegionalCn0AnomalyDetector.evaluate(nanElevationOnly);
        assertEquals(RegionalScanReport.RegionalEventSeverity.NOMINAL, nanElReport.severity());
    }

    private static GnssReferenceStation station(String id, double lat, double lon) {
        return new GnssReferenceStation(
                id, id.substring(0, 4), id, "ESP", GnssStationNetwork.IGS,
                EnumSet.of(GnssStationNetwork.IGS), lat, lon, 600.0, null,
                "SEPT", "", "AOAD", "NONE", EnumSet.of(GnssConstellation.GPS),
                List.of(), 0L, 10, Double.NaN, GnssStationStatus.ONLINE, "");
    }

    private static RegionalCn0AnomalyDetector.StationObservationInput input(
            GnssReferenceStation st,
            long epochMillis,
            double elDeg,
            double cn0DbHz,
            double missingFraction,
            int slipCount) {
        double expected = RinexObservationAnalyzer.expectedCn0DbHz(elDeg);
        double delta = cn0DbHz - expected;
        List<GnssStationAnalysis.SatelliteMeasurementSummary> sats = new ArrayList<>();
        List<GnssStationAnalysis.Cn0TimeSample> timeSamples = new ArrayList<>();
        String[] prns = {"G01", "G02", "G03", "G04"};
        for (int i = 0; i < prns.length; i++) {
            sats.add(new GnssStationAnalysis.SatelliteMeasurementSummary(
                    prns[i], GnssConstellation.GPS, GnssStationAnalysis.TrackingState.TRACKED,
                    90.0 * i, elDeg, cn0DbHz, cn0DbHz, delta, slipCount, 0.2, 20));
            timeSamples.add(new GnssStationAnalysis.Cn0TimeSample(
                    epochMillis, prns[i], GnssConstellation.GPS, elDeg, cn0DbHz, delta));
        }
        int totalPred = missingFraction > 0.0 ? 8 : 4;
        int missing = (int) Math.round(totalPred * missingFraction);
        GnssStationAnalysis analysis = new GnssStationAnalysis(
                st, epochMillis, false, 10.0,
                totalPred, totalPred - missing, missing, 100.0 * (1.0 - missingFraction),
                cn0DbHz, delta, slipCount, slipCount, 0, slipCount > 0 ? 6.0 : 0.0,
                0.2, 0.25,
                new GnssSppSolver.SppSessionSummary(
                        GnssSppSolver.SppEpochResult.unavailable(epochMillis, "N/A"),
                        Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0),
                sats, List.of(), timeSamples);
        return new RegionalCn0AnomalyDetector.StationObservationInput(st, analysis, 10.0);
    }
}

package jsattrak.awareness.gnss.geometry;

import jsattrak.awareness.gnss.GnssConstellation;
import jsattrak.awareness.gnss.GnssDisplayStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GnssDopCalculatorTest {

    private static GnssDopCalculator.CandidateSatellite svAlongEnu(
            String prn,
            GnssConstellation constellation,
            GnssDisplayStatus status,
            GnssOrbitSource source,
            double[] obsEcef,
            double azDeg,
            double elDeg,
            double rangeMeters) {
        double az = Math.toRadians(azDeg);
        double el = Math.toRadians(elDeg);
        double uE = Math.cos(el) * Math.sin(az);
        double uN = Math.cos(el) * Math.cos(az);
        double uU = Math.sin(el);
        // At observer (lat=0, lon=0): eE=[0,1,0], eN=[0,0,1], eU=[1,0,0]
        double satX = obsEcef[0] + rangeMeters * uU;
        double satY = obsEcef[1] + rangeMeters * uE;
        double satZ = obsEcef[2] + rangeMeters * uN;
        return new GnssDopCalculator.CandidateSatellite(prn, constellation, status, source, satX, satY, satZ);
    }

    @Test
    void computesExactClosedFormDopForSymmetricFourSatelliteConstellation() {
        GnssObserverPosition observer = new GnssObserverPosition("Equator", 0.0, 0.0, 0.0);
        double[] obsEcef = GnssDopCalculator.geodeticToEcef(0.0, 0.0, 0.0);
        double r = 20_200_000.0;
        List<GnssDopCalculator.CandidateSatellite> sats = List.of(
                svAlongEnu("G01", GnssConstellation.GPS, GnssDisplayStatus.OPERATIONAL, GnssOrbitSource.BRDC, obsEcef, 0.0, 90.0, r),
                svAlongEnu("G02", GnssConstellation.GPS, GnssDisplayStatus.OPERATIONAL, GnssOrbitSource.BRDC, obsEcef, 0.0, 30.0, r),
                svAlongEnu("E11", GnssConstellation.GALILEO, GnssDisplayStatus.OPERATIONAL, GnssOrbitSource.BRDC, obsEcef, 120.0, 30.0, r),
                svAlongEnu("C19", GnssConstellation.BEIDOU, GnssDisplayStatus.OPERATIONAL, GnssOrbitSource.BRDC, obsEcef, 240.0, 30.0, r)
        );

        GnssDopSolution sol = GnssDopCalculator.computePointDop(1_700_000_000_000L, observer, 10.0, false, sats);
        assertTrue(sol.fixAvailable());
        assertEquals(4, sol.visibleCount());
        assertEquals(4, sol.usedCount());
        assertEquals(4.0 / 3.0, sol.hdop(), 1e-6);
        assertEquals(Math.sqrt(16.0 / 3.0), sol.vdop(), 1e-6);
        assertEquals(Math.sqrt(7.0 / 3.0), sol.tdop(), 1e-6);
        assertEquals(8.0 / 3.0, sol.pdop(), 1e-6);
        assertEquals(Math.sqrt(85.0 / 9.0), sol.gdop(), 1e-6);
        assertEquals(GnssOrbitSource.BRDC, sol.orbitSource().primary());
    }

    @Test
    void handlesFewerThanFourSatellitesCoplanarDegeneracyElevationMaskAndHealthFilter() {
        GnssObserverPosition observer = new GnssObserverPosition("Equator", 0.0, 0.0, 0.0);
        double[] obsEcef = GnssDopCalculator.geodeticToEcef(0.0, 0.0, 0.0);
        double r = 20_200_000.0;

        // 1. Fewer than 4 operational SVs -> No Fix, NaN DOPs
        List<GnssDopCalculator.CandidateSatellite> threeSats = List.of(
                svAlongEnu("G01", GnssConstellation.GPS, GnssDisplayStatus.OPERATIONAL, GnssOrbitSource.BRDC, obsEcef, 0.0, 90.0, r),
                svAlongEnu("G02", GnssConstellation.GPS, GnssDisplayStatus.OPERATIONAL, GnssOrbitSource.BRDC, obsEcef, 0.0, 30.0, r),
                svAlongEnu("E11", GnssConstellation.GALILEO, GnssDisplayStatus.OPERATIONAL, GnssOrbitSource.BRDC, obsEcef, 120.0, 30.0, r)
        );
        GnssDopSolution underDetermined = GnssDopCalculator.computePointDop(1_700_000_000_000L, observer, 10.0, false, threeSats);
        assertFalse(underDetermined.fixAvailable());
        assertEquals(3, underDetermined.visibleCount());
        assertEquals(3, underDetermined.usedCount());
        assertTrue(Double.isNaN(underDetermined.pdop()));
        assertTrue(Double.isNaN(underDetermined.gdop()));
        assertTrue(Double.isNaN(underDetermined.hdop()));
        assertTrue(Double.isNaN(underDetermined.vdop()));
        assertTrue(Double.isNaN(underDetermined.tdop()));

        // 2. 4 SVs all at identical elevation=15 deg (coplanar U = const -> U column proportional to T column)
        List<GnssDopCalculator.CandidateSatellite> coplanar = List.of(
                svAlongEnu("G01", GnssConstellation.GPS, GnssDisplayStatus.OPERATIONAL, GnssOrbitSource.BRDC, obsEcef, 0.0, 15.0, r),
                svAlongEnu("G02", GnssConstellation.GPS, GnssDisplayStatus.OPERATIONAL, GnssOrbitSource.BRDC, obsEcef, 90.0, 15.0, r),
                svAlongEnu("G03", GnssConstellation.GPS, GnssDisplayStatus.OPERATIONAL, GnssOrbitSource.BRDC, obsEcef, 180.0, 15.0, r),
                svAlongEnu("G04", GnssConstellation.GPS, GnssDisplayStatus.OPERATIONAL, GnssOrbitSource.BRDC, obsEcef, 270.0, 15.0, r)
        );
        GnssDopSolution singular = GnssDopCalculator.computePointDop(1_700_000_000_000L, observer, 10.0, false, coplanar);
        assertFalse(singular.fixAvailable());
        assertTrue(Double.isNaN(singular.pdop()));
        assertTrue(Double.isNaN(singular.gdop()));

        // 3. 5 SVs: 3 healthy above 15° + 1 UNAVAILABLE above 15° + 1 healthy below 15° mask (el=10°)
        List<GnssDopCalculator.CandidateSatellite> filtered = List.of(
                svAlongEnu("G01", GnssConstellation.GPS, GnssDisplayStatus.OPERATIONAL, GnssOrbitSource.BRDC, obsEcef, 0.0, 90.0, r),
                svAlongEnu("G02", GnssConstellation.GPS, GnssDisplayStatus.OPERATIONAL, GnssOrbitSource.BRDC, obsEcef, 0.0, 30.0, r),
                svAlongEnu("E11", GnssConstellation.GALILEO, GnssDisplayStatus.OPERATIONAL, GnssOrbitSource.BRDC, obsEcef, 120.0, 30.0, r),
                svAlongEnu("R05", GnssConstellation.GLONASS, GnssDisplayStatus.UNAVAILABLE, GnssOrbitSource.TLE_FALLBACK, obsEcef, 240.0, 30.0, r),
                svAlongEnu("C19", GnssConstellation.BEIDOU, GnssDisplayStatus.OPERATIONAL, GnssOrbitSource.BRDC, obsEcef, 60.0, 10.0, r)
        );
        GnssDopSolution defaultFiltered = GnssDopCalculator.computePointDop(1_700_000_000_000L, observer, 15.0, false, filtered);
        assertFalse(defaultFiltered.fixAvailable());
        assertEquals(4, defaultFiltered.visibleCount()); // 3 operational + 1 unavailable above 15°
        assertEquals(3, defaultFiltered.usedCount());
        assertEquals(2, defaultFiltered.visibleByConstellation().getOrDefault(GnssConstellation.GPS, 0));
        assertEquals(1, defaultFiltered.visibleByConstellation().getOrDefault(GnssConstellation.GALILEO, 0));
        assertEquals(1, defaultFiltered.visibleByConstellation().getOrDefault(GnssConstellation.GLONASS, 0));
        assertEquals(0, defaultFiltered.visibleByConstellation().getOrDefault(GnssConstellation.BEIDOU, 0));

        // What-if mode includes the UNAVAILABLE SV -> 4 used -> fixAvailable == true
        GnssDopSolution whatIf = GnssDopCalculator.computePointDop(1_700_000_000_000L, observer, 15.0, true, filtered);
        assertTrue(whatIf.fixAvailable());
        assertEquals(4, whatIf.usedCount());
        assertEquals(GnssOrbitSource.MIXED, whatIf.orbitSource().primary());
    }
}

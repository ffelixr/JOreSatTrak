package jsattrak.awareness.gnss;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import jsattrak.gui.JSatTrak;
import jsattrak.utilities.TLE;
import org.junit.jupiter.api.Test;

public class GnssOrbitPropagatorTest {

    @Test
    void propagatesGpsMeoAltitudeComputesTrackAndHandlesMissingTle() {
        JSatTrak.initOrekitData();
        TLE gps = new TLE("GPS BIIR-5 (PRN 22)",
                "1 26407U 00040A   26312.06320080  .00000016  00000+0  00000+0 0  9991",
                "2 26407  54.8263 209.4537 0115270 305.0010 182.1874  2.00558916192834");
        var pos = GnssOrbitPropagator.propagateGeodetic(gps, 1_795_132_800_000L).orElseThrow();
        assertTrue(pos.altMeters() > 19_000_000.0 && pos.altMeters() < 22_000_000.0);
        assertEquals(90, GnssOrbitPropagator.computeGroundTrack(gps, 1_795_132_800_000L, 90).size());
        assertEquals(72, GnssOrbitPropagator.computeOrbitRing(gps, 1_795_132_800_000L, 72).size());
        assertTrue(GnssOrbitPropagator.propagateGeodetic(null, 1_795_132_800_000L).isEmpty());

        List<TLE> bundled = GnssOrbitPropagator.loadBundledFallbackTles(Path.of("data/tle"));
        assertTrue(bundled.size() > 50);
    }
}

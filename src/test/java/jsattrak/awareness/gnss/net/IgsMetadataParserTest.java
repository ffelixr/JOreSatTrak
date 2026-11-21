package jsattrak.awareness.gnss.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import jsattrak.awareness.gnss.GnssCatalogEntry;
import jsattrak.awareness.gnss.GnssOrbitClass;
import jsattrak.utilities.TLE;
import org.junit.jupiter.api.Test;

public class IgsMetadataParserTest {

    @Test
    void resolvesEpochActivePrnReassignmentAndJoinsTlesByCosparFallback() {
        String sinex = """
                %=SNX 2.02 IGS
                +SATELLITE/IDENTIFIER
                 G054 2003-010A GPS-IIR          27704 2003:090:00000
                 G078 2023-009A GPS-III          55268 2023:018:00000
                 E201 2014-050A GALILEO-2        40128 2014:234:00000
                 C201 2018-003A BEIDOU-3M-CAST         2018:011:00000
                 C219 2018-085A BEIDOU-3G-CAST         2018:305:00000
                 J001 2010-045A QZSS-1           37158 2010:254:00000
                -SATELLITE/IDENTIFIER
                +SATELLITE/PRN
                 G054 2003:090:00000 2023:020:00000 G18
                 G078 2023:021:00000 0000:000:00000 G18
                 E201 2014:234:00000 0000:000:00000 E18
                 C201 2018:011:00000 0000:000:00000 C19
                 C219 2018:305:00000 0000:000:00000 C59
                 J001 2010:254:00000 0000:000:00000 J01
                -SATELLITE/PRN
                +SATELLITE/PLANE
                 G078 2023:021:00000 0000:000:00000 A 2
                -SATELLITE/PLANE
                %ENDSNX
                """;
        long epoch2026 = 1_795_132_800_000L; // 2026-11-21
        List<GnssCatalogEntry> entries = IgsMetadataParser.parseSinex(sinex, epoch2026);
        // J01 (QZSS) filtered out; G054 retired -> 4 active entries (G18, E18, C19, C59)
        assertEquals(4, entries.size());

        GnssCatalogEntry g18 = entries.stream().filter(e -> e.prnId().equals("G18")).findFirst().orElseThrow();
        assertEquals("G078", g18.svn());
        assertEquals(55268, g18.noradId());
        assertEquals("A", g18.plane());
        assertEquals("2", g18.slot());

        GnssCatalogEntry c59 = entries.stream().filter(e -> e.prnId().equals("C59")).findFirst().orElseThrow();
        assertEquals(GnssOrbitClass.GEO, c59.orbitClass());

        // C19 has no NORAD ID in SINEX; join must succeed via COSPAR 2018-003A <-> 18003A
        TLE c19Tle = new TLE("BEIDOU-3 M1",
                "1 43107U 18003A   26312.00000000  .00000000  00000+0  00000+0 0  9994",
                "2 43107  55.0000 100.0000 0005000  90.0000 270.0000  1.86230000 50001");
        TLE e18Tle = new TLE("GSAT0201",
                "1 40128U 14050A   26308.01414678 -.00000069  00000+0  00000+0 0  9999",
                "2 40128  48.7281 267.6865 1693340 184.7387 173.4405  1.85519507 80914");
        Map<String, TLE> matched = IgsMetadataParser.matchTlesToCatalog(entries, List.of(c19Tle, e18Tle));
        assertTrue(matched.containsKey("C19"));
        assertTrue(matched.containsKey("E18"));
    }

    @Test
    void doesNotMisparseYearTokenAsNoradIdWhenNoradBlankAndHandlesInvalidSinexDates() {
        String sinex = """
                %=SNX 2.02 IGS
                +SATELLITE/IDENTIFIER
                 C201 2018-003A BEIDOU 2018-BLOCK       2018:011:00000
                 G078 2023-009A GPS-III          55268 2023:366:00000
                -SATELLITE/IDENTIFIER
                +SATELLITE/PRN
                 C201 2018:011:00000 9999:999:99999 C19
                 G078 2023:366:00000 0000:000:00000 G18
                -SATELLITE/PRN
                %ENDSNX
                """;
        long epoch2026 = 1_795_132_800_000L;
        List<GnssCatalogEntry> entries = assertDoesNotThrow(() -> IgsMetadataParser.parseSinex(sinex, epoch2026));
        assertEquals(2, entries.size());
        GnssCatalogEntry c19 = entries.stream().filter(e -> "C19".equals(e.prnId())).findFirst().orElseThrow();
        assertTrue(c19.noradId() <= 0, "Expected missing NORAD to remain <= 0, was " + c19.noradId());
        GnssCatalogEntry g18 = entries.stream().filter(e -> "G18".equals(e.prnId())).findFirst().orElseThrow();
        assertEquals(55268, g18.noradId());
    }
}

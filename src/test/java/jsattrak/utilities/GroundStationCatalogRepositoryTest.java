package jsattrak.utilities;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jsattrak.awareness.LaunchRecord;
import org.junit.jupiter.api.Test;

public class GroundStationCatalogRepositoryTest {

    @Test
    public void testSyncFixedPadsOnlyAndNeverMutatesBaseDb() throws Exception {
        File tmpDir = Files.createTempDirectory("gs-repo-test").toFile();
        File baseDb = new File(tmpDir, "groundstations_db.csv");
        String originalBase = "Launch,Cape Canaveral ETR USA LC,28.5,-81.0,0\n";
        Files.writeString(baseDb.toPath(), originalBase);
        File customDb = new File(tmpDir, "groundstations_custom.csv");
        File launchDb = new File(tmpDir, "groundstations_launches.csv");

        GroundStationCatalogRepository repo = new GroundStationCatalogRepository(baseDb, customDb, launchDb);
        LaunchRecord fixedWithComma = padLaunch("1", "SLC-40, Pad A", "Cape Canaveral SFS, FL", 28.5619, -80.5774, true);
        LaunchRecord seaBarge = padLaunch("2", "Offshore Platform", "Yellow Sea", 35.1, 121.2, false);
        LaunchRecord zeroCoord = padLaunch("3", "Unknown Pad", "TBD", 0.0, 0.0, true);

        int addedFirst = repo.syncLaunchPads(List.of(fixedWithComma, seaBarge, zeroCoord));
        int addedSecond = repo.syncLaunchPads(List.of(fixedWithComma));

        assertEq(1, addedFirst);
        assertEq(0, addedSecond); // idempotent deduplication
        assertEq(originalBase, Files.readString(baseDb.toPath())); // base DB untouched

        Map<String, List<GroundStationCatalogRepository.CatalogEntry>> grouped = repo.loadGroupedCatalog();
        List<GroundStationCatalogRepository.CatalogEntry> apiEntries = grouped.get(GroundStationCatalogRepository.LAUNCH_API_NETWORK);
        if (apiEntries == null) {
            throw new AssertionError("Expected Launch (API) group to be present");
        }
        assertEq(1, apiEntries.size());
        if (apiEntries.get(0).name().contains(",")) {
            throw new AssertionError("CSV comma in pad name must be sanitized");
        }
    }

    @Test
    public void testNetworkOrderingAndOmissionOfEmptyLaunchApi() throws Exception {
        File tmpDir = Files.createTempDirectory("gs-repo-order").toFile();
        File baseDb = new File(tmpDir, "groundstations_db.csv");
        Files.writeString(baseDb.toPath(),
                "ACRES,Alice Springs,-23.75881,133.882599,576.542\n"
                + "Launch,Kourou France Europe LC,5.2,-52.8,0\n");
        File customDb = new File(tmpDir, "groundstations_custom.csv");
        File launchDb = new File(tmpDir, "nested/groundstations_launches.csv");

        GroundStationCatalogRepository repo = new GroundStationCatalogRepository(baseDb, customDb, launchDb);
        Map<String, List<GroundStationCatalogRepository.CatalogEntry>> beforeSync = repo.loadGroupedCatalog();
        assertEq(List.of("ACRES", "Launch", "Custom"), new ArrayList<>(beforeSync.keySet()));
        assertEq(0, beforeSync.get("Custom").size());

        LaunchRecord kourouPad = padLaunch("k1", "ELA-4", "Guiana Space Centre", 5.236, -52.768, true);
        int added = repo.syncLaunchPads(List.of(kourouPad));
        assertEq(1, added);

        Map<String, List<GroundStationCatalogRepository.CatalogEntry>> afterSync = repo.loadGroupedCatalog();
        assertEq(List.of("ACRES", "Launch", "Custom", GroundStationCatalogRepository.LAUNCH_API_NETWORK),
                new ArrayList<>(afterSync.keySet()));
        assertEq("Guiana Space Centre - ELA-4",
                afterSync.get(GroundStationCatalogRepository.LAUNCH_API_NETWORK).get(0).name());
    }

    @Test
    public void testDeduplicationByNormalizedNameAndProximityAndSanitization() throws Exception {
        File tmpDir = Files.createTempDirectory("gs-repo-dedup").toFile();
        File baseDb = new File(tmpDir, "groundstations_db.csv");
        File customDb = new File(tmpDir, "groundstations_custom.csv");
        File launchDb = new File(tmpDir, "groundstations_launches.csv");

        GroundStationCatalogRepository repo = new GroundStationCatalogRepository(baseDb, customDb, launchDb);

        LaunchRecord first = padLaunch("1", "SLC-40", "Cape Canaveral \"SFS\",\nFL", 28.5619, -80.5774, true);
        LaunchRecord normalizedMatch = padLaunch("2", "slc 40", "cape canaveral sfs fl", 28.5630, -80.5760, true);
        LaunchRecord mountA = padLaunch("3", "Orbital Launch Mount A", "Starbase", 25.9972, -97.1561, true);
        LaunchRecord mountB = padLaunch("4", "Orbital Launch Mount B", "Starbase", 25.9974, -97.1541, true);

        int added = repo.syncLaunchPads(List.of(first, normalizedMatch, mountA, mountB));
        assertEq(3, added);

        List<GroundStationCatalogRepository.CatalogEntry> apiEntries =
                repo.loadGroupedCatalog().get(GroundStationCatalogRepository.LAUNCH_API_NETWORK);
        assertEq(3, apiEntries.size());
        String firstName = apiEntries.get(0).name();
        if (firstName.contains("\"") || firstName.contains("\n") || firstName.contains("\r") || firstName.contains(",")) {
            throw new AssertionError("Unsanitized CSV characters found in pad name: " + firstName);
        }
    }

    @Test
    public void testAppendCustomStationHandlesMissingParentAndNoTrailingNewline() throws Exception {
        File tmpDir = Files.createTempDirectory("gs-repo-custom").toFile();
        File baseDb = new File(tmpDir, "missing_base.csv");
        File customDb = new File(tmpDir, "sub/groundstations_custom.csv");
        File launchDb = new File(tmpDir, "missing_launches.csv");

        GroundStationCatalogRepository repo = new GroundStationCatalogRepository(baseDb, customDb, launchDb);
        Map<String, List<GroundStationCatalogRepository.CatalogEntry>> emptyCatalog = repo.loadGroupedCatalog();
        assertEq(List.of("Custom"), new ArrayList<>(emptyCatalog.keySet()));
        assertEq(0, emptyCatalog.get("Custom").size());

        repo.appendCustomStation("Site \"One\", Alpha\nTest", 29.51, -95.02, 12.5);
        // Simulate legacy file without trailing newline
        String content = Files.readString(customDb.toPath());
        if (content.endsWith("\n")) {
            Files.writeString(customDb.toPath(), content.substring(0, content.length() - 1));
        }
        repo.appendCustomStation("Second Site", 30.0, -96.0, 0.0);

        List<GroundStationCatalogRepository.CatalogEntry> customEntries = repo.loadGroupedCatalog().get("Custom");
        assertEq(2, customEntries.size());
        if (customEntries.get(0).name().contains(",") || customEntries.get(0).name().contains("\"")) {
            throw new AssertionError("Custom station name must be sanitized: " + customEntries.get(0).name());
        }
        assertEq("Second Site", customEntries.get(1).name());
    }

    private static LaunchRecord padLaunch(
            String id,
            String padName,
            String spaceportName,
            double latDeg,
            double lonDeg,
            boolean fixedLandPad) {
        return LaunchRecord.builder()
                .id(id)
                .missionName("Mission " + id)
                .padName(padName)
                .spaceportName(spaceportName)
                .latDeg(latDeg)
                .lonDeg(lonDeg)
                .fixedLandPad(fixedLandPad)
                .build();
    }

    private static void assertEq(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("Expected <" + expected + "> but got <" + actual + ">");
        }
    }
}

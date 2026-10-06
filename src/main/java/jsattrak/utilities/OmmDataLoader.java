/**
 * =====================================================================
 *   This file is part of JSatTrak.
 *
 *   Copyright 2007-2026 Shawn E. Gano & JOreSatTrak contributors
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
package jsattrak.utilities;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;

/**
 * Ingestion and parser utility for CCSDS OMM (Orbit Mean-Elements Message)
 * formatted catalogs (CSV, XML, JSON) providing seamless bridges to JSatTrak's
 * TLE and Orekit propagation pipelines.
 */
public class OmmDataLoader {

    /**
     * Parses OMM records from a CSV reader (such as CelesTrak's FORMAT=csv).
     *
     * @param reader reader providing CSV content
     * @return list of converted TLE objects
     * @throws Exception if stream reading or parsing fails
     */
    public static List<TLE> parseOmmCsv(Reader reader) throws Exception {
        List<TLE> results = new ArrayList<>();
        BufferedReader br = (reader instanceof BufferedReader) ? (BufferedReader) reader : new BufferedReader(reader);

        String headerLine = br.readLine();
        if (headerLine == null) {
            return results;
        }

        // Map column names to index
        String[] headers = splitCsvLine(headerLine);
        Map<String, Integer> colMap = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            colMap.put(headers[i].trim().toUpperCase(), i);
        }

        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            String[] cols = splitCsvLine(line);
            try {
                TLE tle = parseOmmCsvRow(cols, colMap);
                if (tle != null) {
                    results.add(tle);
                }
            } catch (Exception e) {
                // Log warning and continue reading subsequent satellites
                System.out.println("Warning parsing OMM CSV row: " + e.getMessage());
            }
        }

        return results;
    }

    /**
     * Parses OMM records from a local CSV file.
     */
    public static List<TLE> parseOmmCsv(File csvFile) throws Exception {
        try (FileReader fr = new FileReader(csvFile)) {
            return parseOmmCsv(fr);
        }
    }

    /**
     * Parses OMM records from an InputStream.
     */
    public static List<TLE> parseOmmCsv(InputStream is) throws Exception {
        try (InputStreamReader isr = new InputStreamReader(is, "UTF-8")) {
            return parseOmmCsv(isr);
        }
    }

    private static TLE parseOmmCsvRow(String[] cols, Map<String, Integer> colMap) throws Exception {
        String name = getCol(cols, colMap, "OBJECT_NAME", "UNKNOWN");
        String objectId = getCol(cols, colMap, "OBJECT_ID", "");
        String epochStr = getCol(cols, colMap, "EPOCH", "");
        
        int satNum = (int) Math.round(parseDouble(getCol(cols, colMap, "NORAD_CAT_ID", "0")));
        char classification = 'U';
        String classStr = getCol(cols, colMap, "CLASSIFICATION_TYPE", "U");
        if (!classStr.isEmpty()) {
            classification = classStr.charAt(0);
        }

        // COSPAR ID components (e.g. "2026-162A" or "98067A")
        int launchYear = 2000;
        int launchNumber = 1;
        String launchPiece = "A";
        if (!objectId.isEmpty()) {
            try {
                if (objectId.contains("-")) {
                    String[] parts = objectId.split("-");
                    launchYear = Integer.parseInt(parts[0].trim());
                    String rest = parts[1].trim();
                    // Separate digits from letter piece
                    int idx = 0;
                    while (idx < rest.length() && Character.isDigit(rest.charAt(idx))) {
                        idx++;
                    }
                    if (idx > 0) {
                        launchNumber = Integer.parseInt(rest.substring(0, idx));
                    }
                    if (idx < rest.length()) {
                        launchPiece = rest.substring(idx);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        int ephemerisType = (int) Math.round(parseDouble(getCol(cols, colMap, "EPHEMERIS_TYPE", "0")));
        int elementNumber = (int) Math.round(parseDouble(getCol(cols, colMap, "ELEMENT_SET_NO", "999")));

        // Ensure Orekit data repository is initialized
        try {
            org.orekit.time.TimeScalesFactory.getUTC();
        } catch (Throwable t) {
            jsattrak.gui.JSatTrak.initOrekitData();
        }

        AbsoluteDate epoch;
        if (epochStr.contains("T")) {
            epoch = new AbsoluteDate(epochStr, TimeScalesFactory.getUTC());
        } else {
            epoch = new AbsoluteDate(epochStr.replace(" ", "T"), TimeScalesFactory.getUTC());
        }

        // Convert mean motion from revolutions/day to rad/s
        double revPerDay = parseDouble(getCol(cols, colMap, "MEAN_MOTION", "0"));
        double meanMotion = revPerDay * (2.0 * Math.PI / 86400.0);

        // MEAN_MOTION_DOT in rev/day^2 to rad/s^2 (Orekit multiplies internal nDot by 2, standard definition)
        double mmDotRev = parseDouble(getCol(cols, colMap, "MEAN_MOTION_DOT", "0"));
        double meanMotionDot = mmDotRev * (2.0 * Math.PI / (86400.0 * 86400.0));

        // MEAN_MOTION_DDOT in rev/day^3 to rad/s^3
        double mmDDotRev = parseDouble(getCol(cols, colMap, "MEAN_MOTION_DDOT", "0"));
        double meanMotionDotDot = mmDDotRev * (2.0 * Math.PI / (86400.0 * 86400.0 * 86400.0));

        double eccentricity = parseDouble(getCol(cols, colMap, "ECCENTRICITY", "0"));
        double inclination = Math.toRadians(parseDouble(getCol(cols, colMap, "INCLINATION", "0")));
        double pa = Math.toRadians(parseDouble(getCol(cols, colMap, "ARG_OF_PERICENTER", "0")));
        double raan = Math.toRadians(parseDouble(getCol(cols, colMap, "RA_OF_ASC_NODE", "0")));
        double meanAnomaly = Math.toRadians(parseDouble(getCol(cols, colMap, "MEAN_ANOMALY", "0")));
        int revAtEpoch = (int) Math.round(parseDouble(getCol(cols, colMap, "REV_AT_EPOCH", "0")));
        double bStar = parseDouble(getCol(cols, colMap, "BSTAR", "0"));

        // Instantiate native Orekit TLE directly from mean elements (handles Alpha-5 automatically)
        org.orekit.propagation.analytical.tle.TLE orekitTle =
                new org.orekit.propagation.analytical.tle.TLE(
                        satNum, classification, launchYear, launchNumber, launchPiece,
                        ephemerisType, elementNumber, epoch, meanMotion, meanMotionDot, meanMotionDotDot,
                        eccentricity, inclination, pa, raan, meanAnomaly, revAtEpoch, bStar);

        return new TLE(name, orekitTle.getLine1(), orekitTle.getLine2());
    }

    private static String getCol(String[] cols, Map<String, Integer> colMap, String name, String defaultVal) {
        Integer idx = colMap.get(name);
        if (idx != null && idx < cols.length) {
            String val = cols[idx].trim();
            if (!val.isEmpty()) {
                return val;
            }
        }
        return defaultVal;
    }

    private static double parseDouble(String str) {
        String s = str.trim();
        if (s.isEmpty()) {
            return 0.0;
        }
        if (s.startsWith(".")) {
            s = "0" + s;
        } else if (s.startsWith("-.")) {
            s = "-0" + s.substring(2);
        } else if (s.startsWith("+.")) {
            s = "+0" + s.substring(2);
        }
        return Double.parseDouble(s);
    }

    private static String[] splitCsvLine(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                tokens.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        tokens.add(sb.toString());
        return tokens.toArray(new String[0]);
    }
}

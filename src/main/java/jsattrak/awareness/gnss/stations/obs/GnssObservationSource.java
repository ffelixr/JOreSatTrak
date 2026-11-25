package jsattrak.awareness.gnss.stations.obs;

import java.io.IOException;
import jsattrak.awareness.gnss.stations.GnssReferenceStation;
import jsattrak.awareness.gnss.stations.GnssStationNetwork;

/**
 * Pluggable acquisition contract (Option C) for GNSS station measurements: archive sources
 * ({@code BkgHourlyRinexSource}, {@code NoaaCorsS3RinexSource}, {@code LocalRinexFileSource})
 * today, and future real-time {@code isRealTimeStream() == true} NTRIP/RTCM3 sources later.
 */
public interface GnssObservationSource {

    record ObservationQuery(
            long targetEpochMillis,
            int windowMinutes,
            boolean preferLatestAvailable) {
    }

    record ObservationFetchResult(
            GnssReferenceStation resolvedStation,
            RinexObservationData observationData,
            String sourceName,
            String resolvedUri,
            long fetchedAtMillis,
            String formatLabel) {
    }

    String getSourceId();

    String getDisplayName();

    boolean supportsNetwork(GnssStationNetwork network);

    boolean isRealTimeStream();

    ObservationFetchResult fetchObservations(GnssReferenceStation station, ObservationQuery query) throws IOException;
}

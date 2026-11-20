package jsattrak.awareness.map;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import jsattrak.awareness.LaunchRecord;
import jsattrak.awareness.LaunchTimeEvaluator;
import jsattrak.awareness.LaunchUrgency;

/**
 * Zoom-aware screen-space clustering for launch map markers.
 */
public final class LaunchClusterer {

    public static final double DEFAULT_CLUSTER_RADIUS_PX = 22.0;

    private static final Comparator<ProjectedLaunch> SOONEST_FIRST =
            Comparator.comparingLong((ProjectedLaunch p) -> p.record.getNetEpochMillis())
                    .thenComparing(p -> p.record.getId(), Comparator.nullsLast(String::compareTo));

    private LaunchClusterer() {
    }

    public static List<LaunchMapCluster> clusterByScreenDistance(
            List<LaunchRecord> launches,
            long simEpochMillis,
            String selectedLaunchId,
            Function<LaunchRecord, int[]> projector) {
        return clusterByScreenDistance(
                launches, simEpochMillis, selectedLaunchId, projector, DEFAULT_CLUSTER_RADIUS_PX);
    }

    public static List<LaunchMapCluster> clusterByScreenDistance(
            List<LaunchRecord> launches,
            long simEpochMillis,
            String selectedLaunchId,
            Function<LaunchRecord, int[]> projector,
            double clusterRadiusPx) {
        if (launches == null || launches.isEmpty() || projector == null) {
            return List.of();
        }
        double radius = (Double.isFinite(clusterRadiusPx) && clusterRadiusPx >= 0.0)
                ? clusterRadiusPx
                : DEFAULT_CLUSTER_RADIUS_PX;

        List<ProjectedLaunch> projected = new ArrayList<>(launches.size());
        for (LaunchRecord record : launches) {
            if (record == null || !Double.isFinite(record.getLatDeg()) || !Double.isFinite(record.getLonDeg())) {
                continue;
            }
            int[] xy = projector.apply(record);
            if (xy == null || xy.length < 2) {
                continue;
            }
            projected.add(new ProjectedLaunch(record, xy[0], xy[1]));
        }
        if (projected.isEmpty()) {
            return List.of();
        }

        projected.sort(SOONEST_FIRST);

        String normalizedSelectedId = (selectedLaunchId == null || selectedLaunchId.isBlank())
                ? null
                : selectedLaunchId;

        List<ClusterBuilder> builders = new ArrayList<>();
        for (ProjectedLaunch item : projected) {
            boolean itemSelected = normalizedSelectedId != null
                    && Objects.equals(normalizedSelectedId, item.record.getId());
            ClusterBuilder closest = findClosestCluster(builders, item.x, item.y, radius);
            if (closest != null) {
                closest.add(item, itemSelected);
            } else {
                builders.add(new ClusterBuilder(item, itemSelected));
            }
        }

        List<LaunchMapCluster> clusters = new ArrayList<>(builders.size());
        for (ClusterBuilder builder : builders) {
            clusters.add(builder.build(simEpochMillis));
        }
        return List.copyOf(clusters);
    }

    private static ClusterBuilder findClosestCluster(
            List<ClusterBuilder> builders,
            int x,
            int y,
            double radius) {
        ClusterBuilder best = null;
        double bestDist = Double.MAX_VALUE;
        for (ClusterBuilder candidate : builders) {
            double dist = Math.hypot(x - candidate.centroidScreenX(), y - candidate.centroidScreenY());
            if (dist <= radius && dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        return best;
    }

    private static double normalizeDeltaLonDeg(double deltaLon) {
        double d = deltaLon;
        while (d > 180.0) {
            d -= 360.0;
        }
        while (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    private static double normalizeLonDeg(double lonDeg) {
        double normalized = lonDeg;
        while (normalized > 180.0) {
            normalized -= 360.0;
        }
        while (normalized < -180.0) {
            normalized += 360.0;
        }
        return normalized;
    }

    private record ProjectedLaunch(LaunchRecord record, int x, int y) {
    }

    private static final class ClusterBuilder {
        private final List<LaunchRecord> members = new ArrayList<>();
        private final double baseLonDeg;
        private double sumLatDeg;
        private double sumDeltaLonDeg;
        private double sumX;
        private double sumY;
        private boolean selected;

        ClusterBuilder(ProjectedLaunch seed, boolean selected) {
            this.baseLonDeg = seed.record.getLonDeg();
            add(seed, selected);
        }

        void add(ProjectedLaunch item, boolean itemSelected) {
            members.add(item.record);
            sumLatDeg += item.record.getLatDeg();
            sumDeltaLonDeg += normalizeDeltaLonDeg(item.record.getLonDeg() - baseLonDeg);
            sumX += item.x;
            sumY += item.y;
            if (itemSelected) {
                selected = true;
            }
        }

        double centroidScreenX() {
            return sumX / members.size();
        }

        double centroidScreenY() {
            return sumY / members.size();
        }

        LaunchMapCluster build(long simEpochMillis) {
            int count = members.size();
            double centroidLat = sumLatDeg / count;
            double centroidLon = normalizeLonDeg(baseLonDeg + (sumDeltaLonDeg / count));
            int screenX = (int) Math.round(sumX / count);
            int screenY = (int) Math.round(sumY / count);
            LaunchUrgency urgency = LaunchTimeEvaluator.evaluateUrgency(members.get(0), simEpochMillis);
            return new LaunchMapCluster(
                    centroidLat,
                    centroidLon,
                    screenX,
                    screenY,
                    urgency,
                    members,
                    selected);
        }
    }
}

package jsattrak.awareness.map;

import java.util.List;
import java.util.Objects;
import jsattrak.awareness.LaunchRecord;
import jsattrak.awareness.LaunchUrgency;

/**
 * Immutable screen-space cluster of one or more upcoming or selected launches.
 */
public final class LaunchMapCluster {

    private final double centroidLatDeg;
    private final double centroidLonDeg;
    private final int screenX;
    private final int screenY;
    private final LaunchUrgency soonestUrgency;
    private final List<LaunchRecord> launches;
    private final boolean selected;

    public LaunchMapCluster(
            double centroidLatDeg,
            double centroidLonDeg,
            int screenX,
            int screenY,
            LaunchUrgency soonestUrgency,
            List<LaunchRecord> launches,
            boolean selected) {
        Objects.requireNonNull(soonestUrgency, "soonestUrgency");
        Objects.requireNonNull(launches, "launches");
        if (launches.isEmpty()) {
            throw new IllegalArgumentException("launches must not be empty");
        }
        this.centroidLatDeg = centroidLatDeg;
        this.centroidLonDeg = centroidLonDeg;
        this.screenX = screenX;
        this.screenY = screenY;
        this.soonestUrgency = soonestUrgency;
        this.launches = List.copyOf(launches);
        this.selected = selected;
    }

    public double getCentroidLatDeg() {
        return centroidLatDeg;
    }

    public double getCentroidLonDeg() {
        return centroidLonDeg;
    }

    public int getScreenX() {
        return screenX;
    }

    public int getScreenY() {
        return screenY;
    }

    public LaunchUrgency getSoonestUrgency() {
        return soonestUrgency;
    }

    public List<LaunchRecord> getLaunches() {
        return launches;
    }

    public boolean isSelected() {
        return selected;
    }

    public LaunchRecord getSoonest() {
        return launches.get(0);
    }

    public int getCount() {
        return launches.size();
    }

    public LaunchMapCluster withSelected(boolean selected) {
        if (this.selected == selected) {
            return this;
        }
        return new LaunchMapCluster(
                centroidLatDeg,
                centroidLonDeg,
                screenX,
                screenY,
                soonestUrgency,
                launches,
                selected);
    }
}

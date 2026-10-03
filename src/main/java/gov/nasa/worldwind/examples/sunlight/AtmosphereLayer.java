package gov.nasa.worldwind.examples.sunlight;

import gov.nasa.worldwind.layers.SkyGradientLayer;
import gov.nasa.worldwind.geom.Vec4;

public class AtmosphereLayer extends SkyGradientLayer {
    protected Vec4 sunDirection;

    public AtmosphereLayer() {
        super();
    }

    public Vec4 getSunDirection() {
        return this.sunDirection;
    }

    public void setSunDirection(Vec4 sunDirection) {
        this.sunDirection = sunDirection;
    }
}

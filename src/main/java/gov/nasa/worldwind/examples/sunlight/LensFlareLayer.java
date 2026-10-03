package gov.nasa.worldwind.examples.sunlight;

import gov.nasa.worldwind.layers.RenderableLayer;
import gov.nasa.worldwind.geom.Vec4;

public class LensFlareLayer extends RenderableLayer {
    public static final String PRESET_BOLD = "LensFlare.Preset.Bold";
    protected Vec4 sunDirection;

    public LensFlareLayer() {
        super();
    }

    public static LensFlareLayer getPresetInstance(String preset) {
        return new LensFlareLayer();
    }

    public Vec4 getSunDirection() {
        return this.sunDirection;
    }

    public void setSunDirection(Vec4 sunDirection) {
        this.sunDirection = sunDirection;
    }
}

package gov.nasa.worldwind.examples.sunlight;

import gov.nasa.worldwind.terrain.RectangularTessellator;
import gov.nasa.worldwind.geom.Vec4;
import java.awt.Color;

public class RectangularNormalTessellator extends RectangularTessellator {
    protected Vec4 lightDirection;
    protected Color lightColor = Color.WHITE;
    protected Color ambientColor = new Color(0.5f, 0.5f, 0.5f);

    public RectangularNormalTessellator() {
        super();
    }

    public Vec4 getLightDirection() {
        return this.lightDirection;
    }

    public void setLightDirection(Vec4 lightDirection) {
        this.lightDirection = lightDirection;
    }

    public Color getLightColor() {
        return this.lightColor;
    }

    public void setLightColor(Color lightColor) {
        this.lightColor = lightColor;
    }

    public Color getAmbientColor() {
        return this.ambientColor;
    }

    public void setAmbientColor(Color ambientColor) {
        this.ambientColor = ambientColor;
    }
}

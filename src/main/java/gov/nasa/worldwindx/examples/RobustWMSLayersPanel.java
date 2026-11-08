package gov.nasa.worldwindx.examples;

import gov.nasa.worldwind.WorldWindow;
import gov.nasa.worldwind.avlist.AVKey;
import gov.nasa.worldwind.avlist.AVListImpl;
import gov.nasa.worldwind.ogc.wms.WMSCapabilities;
import gov.nasa.worldwind.ogc.wms.WMSLayerCapabilities;
import gov.nasa.worldwind.ogc.wms.WMSLayerStyle;

import java.awt.Dimension;
import java.net.URISyntaxException;

/**
 * Extension of WMSLayersPanel located in package gov.nasa.worldwindx.examples so that it
 * can directly access the protected constructor and fields of WMSLayersPanel.LayerInfo.
 *
 * Protects against NullPointerException in WMSLayersPanel.makeTitle() caused by WMS servers
 * (like NASA GIBS) having individual layer names containing commas (e.g. "Forests, Mangrove").
 */
public class RobustWMSLayersPanel extends WMSLayersPanel {

    public RobustWMSLayersPanel(WorldWindow wwd, String server, Dimension size) throws URISyntaxException {
        super(wwd, server, size);
    }

    @Override
    protected LayerInfo createLayerInfo(WMSCapabilities caps, WMSLayerCapabilities layerCaps, WMSLayerStyle style) {
        LayerInfo layerInfo = new LayerInfo();
        layerInfo.caps = caps;
        layerInfo.params = new AVListImpl();
        layerInfo.params.setValue(AVKey.LAYER_NAMES, layerCaps.getName());
        if (style != null) {
            layerInfo.params.setValue(AVKey.STYLE_NAMES, style.getName());
        }
        String layerAbstract = layerCaps.getLayerAbstract();
        if (layerAbstract != null && !layerAbstract.isEmpty()) {
            layerInfo.params.setValue(AVKey.LAYER_ABSTRACT, layerAbstract);
        }
        // Direct title assignment avoids commas in layer names from triggering makeTitle's faulty split(",")
        String title = layerCaps.getTitle();
        if (title == null || title.trim().isEmpty()) {
            title = layerCaps.getName();
        }
        if (style != null && style.getTitle() != null && !style.getTitle().trim().isEmpty()) {
            title = title + " : " + style.getTitle();
        }
        layerInfo.params.setValue(AVKey.DISPLAY_NAME, title);
        return layerInfo;
    }
}

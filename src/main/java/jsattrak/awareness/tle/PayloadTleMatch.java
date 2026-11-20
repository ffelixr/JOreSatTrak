package jsattrak.awareness.tle;

import jsattrak.utilities.TLE;

/**
 * Candidate TLE match associated with a launch, including object classification and duplicate status.
 */
public record PayloadTleMatch(
        String objectName,
        String internationalDesignator,
        int noradId,
        ObjectType objectType,
        String sourceLabel,
        boolean supplemental,
        boolean alreadyTracked,
        TLE tle) {

    /**
     * Catalog object classification derived from the TLE object name.
     */
    public enum ObjectType {
        PAYLOAD,
        ROCKET_BODY,
        DEBRIS
    }

    public PayloadTleMatch {
        objectName = objectName == null ? "" : objectName;
        internationalDesignator = internationalDesignator == null ? "" : internationalDesignator;
        objectType = objectType == null ? ObjectType.PAYLOAD : objectType;
        sourceLabel = sourceLabel == null ? "" : sourceLabel;
    }
}

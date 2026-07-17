package com.github.baokhang83.analysis;

import java.util.List;

/**
 * Structural snapshot of a single package together with its detected
 * {@link LayerType} and any layer-specific issues.
 */
public record PackageLayerInfo(

        String    packageName,
        LayerType layer,

        /** Nc — total class files in this package (non-recursive). */
        int    totalClasses,

        /** Na — abstract classes + interfaces. */
        int    abstractClasses,

        /** A = Na / Nc */
        double abstractness,

        /** I = Ce / (Ca + Ce) using project-internal coupling counts. */
        double instability,

        /**
         * Human-readable issues detected for this layer, e.g.
         * "concrete class in port package" or "depends on infrastructure".
         */
        List<String> issues

) {}

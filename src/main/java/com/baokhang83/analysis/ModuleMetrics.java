package com.baokhang83.analysis;

import java.util.List;

/**
 * Metrics snapshot for a single logical module.
 *
 * <p>A logical module is a group of packages sharing the same first segment
 * after the project's common package prefix (e.g. all {@code com.example.order.*}
 * packages form the {@code order} module).</p>
 */
public record ModuleMetrics(

        /** Short module name (first distinctive segment, e.g. {@code order}). */
        String moduleName,

        /** All Java packages grouped into this module. */
        List<String> packages,

        /** Total number of class files across all packages in this module (Nc). */
        int totalClasses,

        /** Number of other modules this module depends on (outgoing). */
        int ce,

        /** Number of other modules that depend on this module (incoming). */
        int ca,

        /** I = Ce / (Ca + Ce) */
        double instability,

        /**
         * Propagation Cost: fraction of all modules reachable transitively
         * from this one (including itself).  Range [1/n, 1.0].
         * High PC = large blast radius when this module changes.
         */
        double propagationCost,

        /** Core / Periphery quadrant. */
        ModuleZone zone,

        /** Names of other modules this module directly depends on (Ce edges). */
        List<String> dependsOn,

        /** Names of other modules that directly depend on this module (Ca edges). */
        List<String> dependedOnBy

) {}

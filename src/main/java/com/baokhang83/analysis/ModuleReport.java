package com.baokhang83.analysis;

import java.util.List;

/**
 * Full result of a module-level architectural analysis.
 */
public record ModuleReport(

        /** How modules were detected, e.g. {@code "logical (depth=1)"}. */
        String detectionMode,

        /** Longest common package prefix shared by all project packages. */
        String commonPrefix,

        /** All detected modules with their metrics, sorted by module name. */
        List<ModuleMetrics> modules,

        /**
         * Detected dependency cycles, each represented as the ordered list of
         * module names forming the cycle (last element repeats the first).
         * Empty if the module graph is acyclic.
         */
        List<List<String>> cycles,

        /** Pre-formatted ASCII Dependency Structure Matrix. */
        String dsm

) {
    /** Acyclicity Index = 1 − (modules participating in any cycle / total modules). */
    public double acyclicityIndex() {
        if (modules.isEmpty()) return 1.0;
        long inCycle = modules.stream()
                .filter(m -> cycles.stream()
                        .anyMatch(c -> c.contains(m.moduleName())))
                .count();
        return Math.round((1.0 - (double) inCycle / modules.size()) * 1000.0) / 1000.0;
    }
}

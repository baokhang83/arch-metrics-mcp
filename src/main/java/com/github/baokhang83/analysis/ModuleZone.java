package com.github.baokhang83.analysis;

/**
 * Core / Periphery quadrant classification based on a module's
 * afferent (Ca) and efferent (Ce) coupling at the module level.
 *
 * <pre>
 *             Ca (incoming)
 *             HIGH  │  LOW
 *          ─────────┼──────────
 *   Ce     HIGH  CORE     PERIPHERAL
 *   (out)  ─────┼──────────┼──────────
 *          LOW   SHARED    ISOLATED
 * </pre>
 *
 * Thresholds: Ca > 0 = "something depends on this module";
 *             Ce > 0 = "this module depends on something".
 */
public enum ModuleZone {

    /** High Ca + High Ce — central hub; risky to change, hard to reuse. */
    CORE,

    /** High Ca + Low Ce — stable utility/library; safe foundation. */
    SHARED,

    /** Low Ca + High Ce — feature/leaf module; depends on many, used by none. */
    PERIPHERAL,

    /** Low Ca + Low Ce — standalone; no coupling in either direction. */
    ISOLATED;

    public static ModuleZone classify(int ca, int ce) {
        boolean highCa = ca > 0;
        boolean highCe = ce > 0;
        if (highCa && highCe) return CORE;
        if (highCa)           return SHARED;
        if (highCe)           return PERIPHERAL;
        return ISOLATED;
    }
}

package com.github.baokhang83.analysis;

import java.util.List;

/**
 * Full coupling detail for a single package.
 *
 * <p>Efferent = packages <em>this</em> package depends on (Ce).
 * Afferent   = packages that depend <em>on this</em> package (Ca).</p>
 */
public record CouplingReport(

        String packageName,
        int    totalClasses,

        /** Ce: all packages this package depends on, sorted alphabetically. */
        List<CouplingEntry> efferent,

        /** Ca: all project packages that depend on this package, sorted alphabetically. */
        List<CouplingEntry> afferent

) {
    /** Efferent coupling count (Ce). */
    public int ce() { return efferent.size(); }

    /** Afferent coupling count (Ca). */
    public int ca() { return afferent.size(); }
}

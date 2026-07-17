package com.github.baokhang83.analysis;

import java.util.List;

/**
 * A directed dependency edge that crosses a hexagonal layer boundary in the
 * wrong direction (inner layer → outer layer).
 *
 * <p>Only two violation types are recognised:
 * <ul>
 *   <li>DOMAIN → ADAPTER — domain code directly uses an infrastructure class</li>
 *   <li>PORT   → ADAPTER — a port interface knows about an adapter implementation</li>
 * </ul>
 * DOMAIN → PORT edges are <em>not</em> flagged because it is common (and valid)
 * for the domain to define its own outbound port interfaces.</p>
 */
public record BoundaryViolation(

        /** Package in which the violation originates (the inner layer). */
        String fromPackage,

        /** Architectural layer of {@code fromPackage}. */
        LayerType fromLayer,

        /** Package depended upon (the outer layer — the wrong direction). */
        String toPackage,

        /** Architectural layer of {@code toPackage}. */
        LayerType toLayer,

        /**
         * Simple class names inside {@code fromPackage} that carry references
         * to {@code toPackage} (empty if not tracked at class level).
         */
        List<String> causingClasses

) {
    /** Human-readable one-liner for this violation. */
    public String summary() {
        return fromLayer.name() + " (" + fromPackage + ")  →  "
             + toLayer.name() + " (" + toPackage + ")";
    }
}

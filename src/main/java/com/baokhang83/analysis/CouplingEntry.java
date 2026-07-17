package com.baokhang83.analysis;

/**
 * A single entry in a coupling list — one package this package depends on (Ce),
 * or one package that depends on this package (Ca) — together with a human-readable
 * label describing what kind of package it is.
 */
public record CouplingEntry(

        /** Fully-qualified package name. */
        String packageName,

        /**
         * Category label, one of:
         * {@code (framework)}, {@code (domain)}, {@code (port)},
         * {@code (adapter)}, {@code (external)}, {@code (internal)}.
         */
        String label

) {}

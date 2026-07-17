package com.baokhang83.analysis;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Immutable snapshot of Robert C. Martin's Main Sequence metrics for a single package.
 *
 * <pre>
 *   A  = abstractness    = Na / Nc          [0=concrete,  1=fully abstract]
 *   I  = instability     = Ce / (Ca + Ce)   [0=stable,    1=unstable]
 *   D  = distance        = |A + I - 1|      [0=on ideal line, 1=max deviation]
 * </pre>
 */
public final class PackageMetrics {

    /** Fully-qualified package name. Serialised as "package" to match Martin's notation. */
    @JsonProperty("package")
    public final String packageName;

    /** ISO-8601 UTC timestamp, e.g. {@code 2026-05-24T12:00:00.000Z}. */
    public final String timestamp;

    /** Nc — total number of classes / interfaces in the package. */
    public final int totalClasses;

    /** Na — number of abstract classes + interfaces. */
    public final int abstractClasses;

    /** A = Na / Nc */
    public final double abstractness;

    /** Ce — number of distinct external packages this package depends on. */
    public final int efferentCoupling;

    /** Ca — number of distinct external packages that depend on this package. */
    public final int afferentCoupling;

    /** I = Ce / (Ca + Ce) */
    public final double instability;

    /** D = |A + I - 1| */
    public final double distanceFromMainSequence;

    @JsonCreator
    public PackageMetrics(
            @JsonProperty("package")                    String packageName,
            @JsonProperty("timestamp")                  String timestamp,
            @JsonProperty("totalClasses")               int totalClasses,
            @JsonProperty("abstractClasses")            int abstractClasses,
            @JsonProperty("abstractness")               double abstractness,
            @JsonProperty("efferentCoupling")           int efferentCoupling,
            @JsonProperty("afferentCoupling")           int afferentCoupling,
            @JsonProperty("instability")                double instability,
            @JsonProperty("distanceFromMainSequence")   double distanceFromMainSequence) {

        this.packageName               = packageName;
        this.timestamp                 = timestamp;
        this.totalClasses              = totalClasses;
        this.abstractClasses           = abstractClasses;
        this.abstractness              = abstractness;
        this.efferentCoupling          = efferentCoupling;
        this.afferentCoupling          = afferentCoupling;
        this.instability               = instability;
        this.distanceFromMainSequence  = distanceFromMainSequence;
    }
}

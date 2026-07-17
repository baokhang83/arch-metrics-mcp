package com.github.baokhang83.analysis;

/**
 * The three canonical layers of Hexagonal Architecture (Ports & Adapters).
 * <pre>
 *   DOMAIN   — innermost; pure business logic, no outward dependencies
 *   PORT     — defines inbound and outbound contracts (interfaces)
 *   ADAPTER  — outermost; infrastructure implementations (REST, DB, messaging…)
 * </pre>
 * Dependencies must always point inward: ADAPTER → PORT → DOMAIN.
 * Any edge in the opposite direction is a boundary violation.
 */
public enum LayerType {

    /** Innermost layer: domain model, business rules, entities. */
    DOMAIN,

    /** Middle layer: port interfaces, use-case definitions. */
    PORT,

    /** Outermost layer: infrastructure adapters (REST, DB, messaging, config…). */
    ADAPTER,

    /** Package could not be classified by convention. */
    UNKNOWN;

    /** True for DOMAIN, PORT, ADAPTER — false for UNKNOWN. */
    public boolean isKnown() {
        return this != UNKNOWN;
    }

    /**
     * Returns true if {@code this} is architecturally inner relative to {@code other},
     * i.e. {@code other} should be allowed to depend on {@code this} but not vice-versa.
     */
    public boolean isInnerThan(LayerType other) {
        // ordinal: DOMAIN=0, PORT=1, ADAPTER=2, UNKNOWN=3
        if (!this.isKnown() || !other.isKnown()) return false;
        return this.ordinal() < other.ordinal();
    }
}

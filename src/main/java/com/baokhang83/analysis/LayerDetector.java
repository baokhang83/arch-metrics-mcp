package com.baokhang83.analysis;

import java.util.Set;

/**
 * Classifies a Java package name into a {@link LayerType} using naming-convention
 * heuristics (Option A — no external configuration required).
 *
 * <p>Priority order (first match wins): ADAPTER > PORT > DOMAIN > UNKNOWN.
 * Adapter names are checked first because they tend to be the most distinctive
 * ({@code rest}, {@code persistence}, {@code kafka}, etc.).</p>
 *
 * <h3>Recognised conventions</h3>
 * <pre>
 *   DOMAIN:  domain, core, entity, entities, business, model
 *   PORT:    port, ports, usecase, usecases, application, api, inbound, outbound
 *   ADAPTER: adapter, adapters, infrastructure, infra, persistence, repository,
 *            repositories, rest, web, http, controller, controllers, messaging,
 *            kafka, rabbitmq, amqp, jms, config, configuration, external,
 *            client, clients
 * </pre>
 */
public final class LayerDetector {

    // -------------------------------------------------------------------------
    // Convention sets — checked as individual dot-split segments
    // -------------------------------------------------------------------------

    private static final Set<String> ADAPTER_SEGMENTS = Set.of(
            "adapter", "adapters",
            "infrastructure", "infra",
            "persistence",
            "repository", "repositories",
            "rest", "web", "http",
            "controller", "controllers",
            "messaging", "kafka", "rabbitmq", "amqp", "jms",
            "config", "configuration",
            "external",
            "client", "clients"
    );

    private static final Set<String> PORT_SEGMENTS = Set.of(
            "port", "ports",
            "usecase", "usecases",
            "application",
            "api",
            "inbound", "outbound"
    );

    private static final Set<String> DOMAIN_SEGMENTS = Set.of(
            "domain", "core",
            "entity", "entities",
            "business", "model"
    );

    /**
     * Known external infrastructure library prefixes.
     * A domain package that has Ce edges to any of these is penalised
     * in the Domain Isolation Score even if the target is not a project package.
     */
    static final Set<String> INFRA_PREFIXES = Set.of(
            "org.springframework",
            "org.hibernate", "javax.persistence", "jakarta.persistence",
            "io.netty",
            "com.amazonaws",
            "org.apache.kafka", "org.apache.activemq",
            "com.fasterxml.jackson",
            "com.zaxxer.hikari", "io.r2dbc",
            "org.mongodb", "io.lettuce",
            "org.jdbi", "org.jooq"
    );

    private LayerDetector() {}

    /**
     * Classifies {@code packageName} into a {@link LayerType} by matching each
     * dot-separated segment against the convention sets.
     *
     * @param packageName fully-qualified Java package name, e.g. {@code com.example.adapter.rest}
     * @return the detected layer, or {@link LayerType#UNKNOWN} if no convention matches
     */
    public static LayerType classify(String packageName) {
        if (packageName == null || packageName.isBlank()) return LayerType.UNKNOWN;
        String[] segments = packageName.toLowerCase().split("\\.");

        for (String s : segments) {
            if (ADAPTER_SEGMENTS.contains(s)) return LayerType.ADAPTER;
        }
        for (String s : segments) {
            if (PORT_SEGMENTS.contains(s)) return LayerType.PORT;
        }
        for (String s : segments) {
            if (DOMAIN_SEGMENTS.contains(s)) return LayerType.DOMAIN;
        }
        return LayerType.UNKNOWN;
    }

    /**
     * Returns the segment that triggered the classification (for diagnostic output).
     */
    public static String matchedSegment(String packageName, LayerType layer) {
        if (packageName == null || layer == LayerType.UNKNOWN) return "";
        String[] segments = packageName.toLowerCase().split("\\.");
        Set<String> set = switch (layer) {
            case ADAPTER -> ADAPTER_SEGMENTS;
            case PORT    -> PORT_SEGMENTS;
            case DOMAIN  -> DOMAIN_SEGMENTS;
            default      -> Set.of();
        };
        for (String s : segments) {
            if (set.contains(s)) return s;
        }
        return "";
    }

    /** Returns true if {@code pkg} looks like a known infrastructure library. */
    public static boolean isKnownInfraPackage(String pkg) {
        if (pkg == null) return false;
        for (String prefix : INFRA_PREFIXES) {
            if (pkg.startsWith(prefix)) return true;
        }
        return false;
    }
}

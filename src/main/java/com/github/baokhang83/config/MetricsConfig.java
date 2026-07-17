package com.github.baokhang83.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Parsed representation of {@code .arch-metrics.yml} at the project root.
 *
 * <p>Example file:
 * <pre>
 * suppress_zones:
 *   - package: com.example.api.model
 *     reason: generated OpenAPI DTOs — records have no interfaces by design
 *   - package: com.example.App
 *     reason: "@SpringBootApplication entry point — high Ce is expected"
 *   - package: com.example.config
 *     reason: "@Configuration classes — wire the whole app, high Ce expected"
 * </pre>
 *
 * <p>The {@code package} value is matched as a <em>prefix</em>: specifying
 * {@code com.example.api.model} suppresses that package <em>and</em> any
 * sub-packages (e.g. {@code com.example.api.model.generated}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetricsConfig {

    /** Packages whose zone warnings should be suppressed in reports. */
    @JsonProperty("suppress_zones")
    public List<Suppression> suppressZones = List.of();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Suppression {

        /** Package name or prefix to suppress (prefix match). */
        @JsonProperty("package")
        public String packagePrefix;

        /** Human-readable reason for the suppression. */
        public String reason;
    }
}

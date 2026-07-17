package com.baokhang83.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads {@link MetricsConfig} from {@code .arch-metrics.yml} at the project root.
 *
 * <p>Returns an empty (all-defaults) config when the file is absent or unreadable,
 * so callers never need to null-check the result.</p>
 */
public final class MetricsConfigLoader {

    private static final String CONFIG_FILE = ".arch-metrics.yml";

    private MetricsConfigLoader() {}

    /**
     * Attempts to read {@code {projectRoot}/.arch-metrics.yml}.
     * On any error (missing file, parse failure) a default empty config is returned
     * and a warning is printed to {@code stderr}.
     */
    public static MetricsConfig load(Path projectRoot) {
        Path configFile = projectRoot.resolve(CONFIG_FILE);
        if (!Files.isReadable(configFile)) {
            return new MetricsConfig();         // file absent → no suppressions
        }
        try {
            ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
            MetricsConfig cfg = yaml.readValue(configFile.toFile(), MetricsConfig.class);
            System.err.println("[arch-metrics] Loaded " + CONFIG_FILE + " ("
                    + (cfg.suppressZones == null ? 0 : cfg.suppressZones.size())
                    + " suppression(s))");
            return cfg;
        } catch (IOException e) {
            System.err.println("[arch-metrics] Failed to parse " + CONFIG_FILE
                    + ": " + e.getMessage() + " — ignoring");
            return new MetricsConfig();
        }
    }
}

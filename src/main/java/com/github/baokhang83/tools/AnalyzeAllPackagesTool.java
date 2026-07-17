package com.github.baokhang83.tools;

import com.github.baokhang83.analysis.*;
import com.github.baokhang83.config.MetricsConfig;
import com.github.baokhang83.config.MetricsConfigLoader;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiFunction;

/**
 * MCP tool: {@code analyze_all_packages}
 *
 * <p>Scans every package in the project and returns a single unified table
 * combining hexagonal layer classification with Main Sequence metrics
 * (Nc, A, I, D) and zone labels.</p>
 *
 * <p>Packages listed in {@code .arch-metrics.yml} under {@code suppress_zones}
 * are shown with a ⬜ tag instead of a zone warning so known false positives
 * don't pollute the report.</p>
 */
public final class AnalyzeAllPackagesTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {},
              "required": []
            }
            """;

    private AnalyzeAllPackagesTool() {}

    public static McpServerFeatures.SyncToolSpecification build(Path projectRoot) {

        var tool = new McpSchema.Tool(
                "analyze_all_packages",
                "Returns a unified per-package table combining hexagonal layer classification "
                + "(DOMAIN / PORT / ADAPTER / UNKNOWN) with Main Sequence metrics "
                + "(Nc, Abstractness A, Instability I, Distance D) and zone label "
                + "(Zone of Pain / Zone of Uselessness / Main Sequence). "
                + "Packages configured in .arch-metrics.yml suppress_zones are tagged "
                + "as suppressed instead of flagged. Sorted by D descending (worst first).",
                INPUT_SCHEMA);

        BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> handler =
                (exchange, args) -> {
                    HexagonalReport report;
                    try {
                        report = new HexagonalAnalyzer(projectRoot).analyze();
                    } catch (IllegalArgumentException e) {
                        return errorResult(e.getMessage());
                    } catch (IOException e) {
                        System.err.println("[arch-metrics] IO error in analyze_all_packages: "
                                + e.getMessage());
                        return errorResult("IO failure: " + e.getMessage());
                    }

                    MetricsConfig config = MetricsConfigLoader.load(projectRoot);
                    String output = format(report, config);
                    System.err.println(output);
                    return okResult(output);
                };

        return new McpServerFeatures.SyncToolSpecification(tool, handler);
    }

    // =========================================================================
    // Formatting
    // =========================================================================

    static String format(HexagonalReport r, MetricsConfig config) {

        // Flatten all packages from all layers
        List<PackageLayerInfo> all = new ArrayList<>();
        for (List<PackageLayerInfo> list : r.layers().values()) {
            all.addAll(list);
        }

        if (all.isEmpty()) {
            return "analyze_all_packages\n" + "=".repeat(60)
                    + "\n(no packages found — run 'mvn compile' first)";
        }

        // Build the suppressed package set from config (prefix matching)
        Set<String> suppressedPrefixes = new HashSet<>();
        if (config.suppressZones != null) {
            for (MetricsConfig.Suppression s : config.suppressZones) {
                if (s.packagePrefix != null && !s.packagePrefix.isBlank()) {
                    suppressedPrefixes.add(s.packagePrefix.trim());
                }
            }
        }

        // Sort: zone problems first (ZONE_OF_PAIN / ZONE_OF_USELESSNESS by D desc),
        //       then Main Sequence by D desc, suppressed last alphabetically.
        all.sort(Comparator
                .comparingInt((PackageLayerInfo p) -> {
                    if (isSuppressed(p.packageName(), suppressedPrefixes)) return 2;
                    ZoneClassifier.Zone z = ZoneClassifier.classify(
                            p.abstractness(), p.instability());
                    return z == ZoneClassifier.Zone.MAIN_SEQUENCE ? 1 : 0;
                })
                .thenComparingDouble(p -> -distanceOf(p))   // D desc within group
                .thenComparing(PackageLayerInfo::packageName));

        // Column widths
        int pkgW = Math.max(38,
                all.stream().mapToInt(p -> p.packageName().length()).max().orElse(38));

        StringBuilder sb = new StringBuilder();
        sb.append("All Packages — Main Sequence + Hexagonal Layer\n");
        sb.append("=".repeat(pkgW + 46)).append("\n\n");

        // Header
        sb.append(String.format("  %-" + pkgW + "s  %-8s  %3s  %6s  %6s  %6s  %s%n",
                "Package", "Layer", "Nc", "A", "I", "D", "Zone"));
        sb.append("  ").append("─".repeat(pkgW + 44)).append("\n");

        for (PackageLayerInfo p : all) {
            double d  = distanceOf(p);
            boolean suppressed = isSuppressed(p.packageName(), suppressedPrefixes);
            String zoneStr = suppressed
                    ? "⬜ suppressed"
                    : ZoneClassifier.label(p.abstractness(), p.instability(), d);

            sb.append(String.format("  %-" + pkgW + "s  %-8s  %3d  %6.3f  %6.3f  %6.3f  %s%n",
                    p.packageName(),
                    p.layer().name(),
                    p.totalClasses(),
                    p.abstractness(),
                    p.instability(),
                    d,
                    zoneStr));
        }

        // Count summary
        long painCount = all.stream()
                .filter(p -> !isSuppressed(p.packageName(), suppressedPrefixes))
                .filter(p -> ZoneClassifier.classify(p.abstractness(), p.instability())
                        != ZoneClassifier.Zone.MAIN_SEQUENCE)
                .count();
        long suppressedCount = all.stream()
                .filter(p -> isSuppressed(p.packageName(), suppressedPrefixes))
                .count();

        sb.append("\n─".repeat(1)).append("─".repeat(Math.min(pkgW + 44, 79))).append("\n");
        sb.append(String.format("Total: %d packages  |  %d zone problem(s)  |  %d suppressed%n",
                all.size(), painCount, suppressedCount));

        // Show suppression reasons if any
        if (!suppressedPrefixes.isEmpty() && config.suppressZones != null) {
            sb.append("\nSuppressed (.arch-metrics.yml):\n");
            for (MetricsConfig.Suppression s : config.suppressZones) {
                sb.append(String.format("  %s%n    → %s%n",
                        s.packagePrefix,
                        s.reason != null ? s.reason : "(no reason given)"));
            }
        }

        return sb.toString().stripTrailing();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static double distanceOf(PackageLayerInfo p) {
        return Math.round(Math.abs(p.abstractness() + p.instability() - 1.0) * 1000.0) / 1000.0;
    }

    /** Returns true if any configured prefix is a prefix-of (or equals) {@code packageName}. */
    private static boolean isSuppressed(String packageName, Set<String> prefixes) {
        for (String prefix : prefixes) {
            if (packageName.equals(prefix) || packageName.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private static McpSchema.CallToolResult okResult(String text) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(text)), false);
    }

    private static McpSchema.CallToolResult errorResult(String msg) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("Error: " + msg)), true);
    }
}

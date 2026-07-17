package com.github.baokhang83.tools;

import com.github.baokhang83.analysis.PackageAnalyzer;
import com.github.baokhang83.analysis.PackageMetrics;
import com.github.baokhang83.analysis.ZoneClassifier;
import com.github.baokhang83.stats.StatsLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * MCP tool: {@code analyze_package}
 *
 * <p>Scans compiled bytecode for the given package, computes Main Sequence metrics,
 * writes a JSON snapshot to {@code .stats/}, and returns a formatted summary.</p>
 */
public final class AnalyzePackageTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "package_name": {
                  "type": "string",
                  "description": "Fully qualified Java package name, e.g. com.example.service"
                }
              },
              "required": ["package_name"]
            }
            """;

    private AnalyzePackageTool() {}

    public static McpServerFeatures.SyncToolSpecification build(
            Path projectRoot, ObjectMapper mapper) {

        var tool = new McpSchema.Tool(
                "analyze_package",
                "Scans compiled bytecode for a Java package and computes Robert C. Martin's "
                + "Main Sequence metrics: Abstractness (A), Instability (I), and Distance from "
                + "the Main Sequence (D). Writes a timestamped JSON snapshot to .stats/.",
                INPUT_SCHEMA);

        BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> handler =
                (exchange, args) -> {
                    String packageName = (String) args.get("package_name");
                    if (packageName == null || packageName.isBlank()) {
                        return errorResult("package_name is required");
                    }

                    Instant now = Instant.now();
                    PackageMetrics metrics;
                    try {
                        metrics = new PackageAnalyzer(projectRoot).analyze(packageName, now);
                    } catch (IllegalArgumentException e) {
                        return errorResult(e.getMessage());
                    } catch (IOException e) {
                        System.err.println("[main-sequence-mcp] IO error during analysis: "
                                + e.getMessage());
                        return errorResult("IO failure during analysis: " + e.getMessage());
                    }

                    Path savedTo;
                    try {
                        savedTo = new StatsLogger(projectRoot, mapper)
                                .writeSnapshot(metrics, now);
                    } catch (IOException e) {
                        System.err.println("[main-sequence-mcp] Failed to write snapshot: "
                                + e.getMessage());
                        return errorResult("Analysis succeeded but snapshot write failed: "
                                + e.getMessage());
                    }

                    String summary = formatSummary(metrics, savedTo, projectRoot);
                    System.err.println(summary);
                    return okResult(summary);
                };

        return new McpServerFeatures.SyncToolSpecification(tool, handler);
    }

    // -------------------------------------------------------------------------
    // Formatting
    // -------------------------------------------------------------------------

    static String formatSummary(PackageMetrics m, Path savedTo, Path projectRoot) {
        String title = "Main Sequence Analysis: " + m.packageName;
        String bar   = "=".repeat(title.length());

        String relativePath;
        try {
            relativePath = projectRoot.relativize(savedTo).toString();
        } catch (IllegalArgumentException e) {
            relativePath = savedTo.toString();
        }

        return String.format("""
                %s
                %s
                Classes (Nc):              %d
                Abstract/Interfaces (Na):  %d
                Abstractness (A):          %.3f  [0=concrete, 1=fully abstract]
                Efferent Coupling (Ce):    %d     [packages this package depends on]
                Afferent Coupling (Ca):    %d     [packages depending on this package]
                Instability (I):           %.3f  [0=stable, 1=unstable]
                Distance (D):              %.3f  [0=on main sequence, 1=max deviation]
                Zone:                      %s

                Snapshot saved: %s""",
                title, bar,
                m.totalClasses,
                m.abstractClasses,
                m.abstractness,
                m.efferentCoupling,
                m.afferentCoupling,
                m.instability,
                m.distanceFromMainSequence,
                ZoneClassifier.label(m.abstractness, m.instability, m.distanceFromMainSequence),
                relativePath);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static McpSchema.CallToolResult okResult(String text) {
        return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(text)), false);
    }

    private static McpSchema.CallToolResult errorResult(String message) {
        return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("Error: " + message)), true);
    }
}

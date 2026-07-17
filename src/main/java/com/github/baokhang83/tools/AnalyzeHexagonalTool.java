package com.github.baokhang83.tools;

import com.github.baokhang83.analysis.*;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiFunction;

/**
 * MCP tool: {@code analyze_hexagonal}
 *
 * <p>Scans all compiled classes in the project, classifies every package into
 * DOMAIN / PORT / ADAPTER by naming convention (Option A), then reports
 * per-layer metrics and the overall Hexagonal Architecture score.</p>
 */
public final class AnalyzeHexagonalTool {

    // No input parameters — always analyses the full project under PROJECT_ROOT
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {},
              "required": []
            }
            """;

    private AnalyzeHexagonalTool() {}

    public static McpServerFeatures.SyncToolSpecification build(Path projectRoot) {

        var tool = new McpSchema.Tool(
                "analyze_hexagonal",
                "Classifies every package in the project into DOMAIN / PORT / ADAPTER by "
                + "naming convention, then reports per-layer abstractness, instability, "
                + "port purity, domain isolation, and an overall Hexagonal Architecture score.",
                INPUT_SCHEMA);

        BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> handler =
                (exchange, args) -> {
                    HexagonalReport report;
                    try {
                        report = new HexagonalAnalyzer(projectRoot).analyze();
                    } catch (IllegalArgumentException e) {
                        return errorResult(e.getMessage());
                    } catch (IOException e) {
                        System.err.println("[arch-metrics] IO error in analyze_hexagonal: "
                                + e.getMessage());
                        return errorResult("IO failure: " + e.getMessage());
                    }

                    String output = format(report);
                    System.err.println(output);
                    return okResult(output);
                };

        return new McpServerFeatures.SyncToolSpecification(tool, handler);
    }

    // =========================================================================
    // Formatting
    // =========================================================================

    static String format(HexagonalReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hexagonal Architecture Report\n");
        sb.append("=".repeat(60)).append("\n");
        sb.append("Layer detection: convention-based (Option A)\n\n");

        appendLayer(sb, LayerType.DOMAIN,   r, "DOMAIN  — innermost; pure business logic");
        appendLayer(sb, LayerType.PORT,     r, "PORTS   — use-cases, inbound/outbound contracts");
        appendLayer(sb, LayerType.ADAPTER,  r, "ADAPTERS — infrastructure implementations");
        appendLayer(sb, LayerType.UNKNOWN,  r, "UNKNOWN  — unclassified (no convention matched)");

        // Violations summary
        sb.append("─".repeat(60)).append("\n");
        sb.append("BOUNDARY VIOLATIONS: ").append(r.boundaryViolations().size()).append("\n");
        if (r.boundaryViolations().isEmpty()) {
            sb.append("  ✅ none\n");
        } else {
            for (BoundaryViolation v : r.boundaryViolations()) {
                sb.append("  ❌ ").append(v.summary()).append("\n");
                if (!v.causingClasses().isEmpty()) {
                    sb.append("     Classes: ")
                      .append(String.join(", ", v.causingClasses())).append("\n");
                }
            }
        }

        // Scores
        sb.append("\n─".repeat(1)).append("─".repeat(59)).append("\n");
        sb.append("SCORES\n");
        sb.append(String.format("  Domain Isolation (DIS):    %.3f  (1.0 = domain has no infra deps)%n",
                r.domainIsolationScore()));
        sb.append(String.format("  Port Purity:               %.3f  (1.0 = all port pkgs are interfaces)%n",
                r.portPurityScore()));
        sb.append(String.format("  Adapter Concreteness:      %.3f  (1.0 = all adapters are concrete)%n",
                r.adapterConcretnessScore()));
        sb.append(String.format("  Overall Hexagonal Score:   %.3f / 1.000%n",
                r.overallHexagonalScore()));

        return sb.toString().stripTrailing();
    }

    private static void appendLayer(StringBuilder sb, LayerType layer,
                                    HexagonalReport r, String heading) {
        List<PackageLayerInfo> pkgs = r.layers().getOrDefault(layer, List.of());
        sb.append("─".repeat(60)).append("\n");
        sb.append("  ").append(heading).append("\n");

        if (pkgs.isEmpty()) {
            sb.append("  (no packages detected)\n\n");
            return;
        }

        for (PackageLayerInfo p : pkgs) {
            sb.append(String.format("  %-48s  Nc=%-3d A=%.3f I=%.3f%n",
                    p.packageName(), p.totalClasses(), p.abstractness(), p.instability()));
            for (String issue : p.issues()) {
                sb.append("    ⚠ ").append(issue).append("\n");
            }
        }

        // Layer-level summary line
        if (layer == LayerType.PORT && r.portPurityScore() < 1.0) {
            sb.append(String.format("  → Port Purity: %.3f  (expected 1.000)%n",
                    r.portPurityScore()));
        }
        if (layer == LayerType.DOMAIN && r.domainIsolationScore() < 1.0) {
            sb.append(String.format("  → Domain Isolation: %.3f  (expected 1.000)%n",
                    r.domainIsolationScore()));
        }
        sb.append("\n");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static McpSchema.CallToolResult okResult(String text) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(text)), false);
    }

    private static McpSchema.CallToolResult errorResult(String msg) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("Error: " + msg)), true);
    }
}

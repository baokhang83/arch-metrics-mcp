package com.github.baokhang83.tools;

import com.github.baokhang83.analysis.*;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * MCP tool: {@code find_boundary_violations}
 *
 * <p>Scans the whole project and lists every directed dependency edge that crosses
 * a hexagonal layer boundary in the wrong direction, including the exact class
 * responsible for each violation.</p>
 *
 * <p>Violation types detected:
 * <ul>
 *   <li>DOMAIN → ADAPTER — domain code directly references an infrastructure class</li>
 *   <li>PORT   → ADAPTER — a port interface references an adapter implementation</li>
 * </ul>
 */
public final class FindBoundaryViolationsTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {},
              "required": []
            }
            """;

    private FindBoundaryViolationsTool() {}

    public static McpServerFeatures.SyncToolSpecification build(Path projectRoot) {

        var tool = new McpSchema.Tool(
                "find_boundary_violations",
                "Lists every dependency edge that violates the Hexagonal Architecture "
                + "Dependency Rule (inner layer → outer layer), including the specific "
                + "class causing each violation. Violation types: DOMAIN→ADAPTER, PORT→ADAPTER.",
                INPUT_SCHEMA);

        BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> handler =
                (exchange, args) -> {
                    HexagonalReport report;
                    try {
                        report = new HexagonalAnalyzer(projectRoot).analyze();
                    } catch (IllegalArgumentException e) {
                        return errorResult(e.getMessage());
                    } catch (IOException e) {
                        System.err.println("[arch-metrics] IO error in find_boundary_violations: "
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
        List<BoundaryViolation> violations = r.boundaryViolations();
        StringBuilder sb = new StringBuilder();

        sb.append("Boundary Violation Report\n");
        sb.append("=".repeat(60)).append("\n");
        sb.append("Total violations: ").append(violations.size()).append("\n\n");

        if (violations.isEmpty()) {
            sb.append("✅ No boundary violations found.\n");
            sb.append("   All detected DOMAIN and PORT packages respect the\n");
            sb.append("   Dependency Rule (no inner→outer edges).\n");
            appendLayerSummary(sb, r);
            return sb.toString().stripTrailing();
        }

        int i = 1;
        for (BoundaryViolation v : violations) {
            sb.append(String.format("❌ VIOLATION %d — %s → %s%n",
                    i++, v.fromLayer(), v.toLayer()));
            sb.append(String.format("   From (%-8s): %s%n", v.fromLayer(), v.fromPackage()));
            sb.append(String.format("   To   (%-8s): %s%n", v.toLayer(), v.toPackage()));

            if (v.causingClasses().isEmpty()) {
                sb.append("   Causing classes: (none identified)\n");
            } else {
                sb.append("   Causing classes:\n");
                for (String cls : v.causingClasses()) {
                    sb.append("     → ").append(cls).append("\n");
                }
            }
            sb.append("\n");
        }

        sb.append("─".repeat(60)).append("\n");
        sb.append("Recommendation:\n");
        sb.append("  Inner layers (DOMAIN, PORT) must not reference outer layer\n");
        sb.append("  (ADAPTER) implementations. Introduce port interfaces and\n");
        sb.append("  inject adapters through Dependency Inversion.\n");

        appendLayerSummary(sb, r);
        return sb.toString().stripTrailing();
    }

    private static void appendLayerSummary(StringBuilder sb, HexagonalReport r) {
        sb.append("\n─".repeat(1)).append("─".repeat(59)).append("\n");
        sb.append("Layer package counts:\n");
        for (LayerType lt : new LayerType[]{
                LayerType.DOMAIN, LayerType.PORT, LayerType.ADAPTER, LayerType.UNKNOWN}) {
            List<PackageLayerInfo> pkgs = r.layers().getOrDefault(lt, List.of());
            if (!pkgs.isEmpty()) {
                sb.append(String.format("  %-8s %d package(s)%n", lt, pkgs.size()));
            }
        }
        sb.append(String.format("%nOverall Hexagonal Score: %.3f / 1.000%n",
                r.overallHexagonalScore()));
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

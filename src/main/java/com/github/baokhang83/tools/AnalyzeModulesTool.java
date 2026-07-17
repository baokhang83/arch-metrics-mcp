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
 * MCP tool: {@code analyze_modules}
 *
 * <p>Groups packages into logical modules by the first package segment after the
 * project's common prefix, then returns three complementary views:
 * <ol>
 *   <li><b>Core / Periphery classification</b> — CORE / SHARED / PERIPHERAL / ISOLATED</li>
 *   <li><b>Propagation Cost (PC)</b> — fraction of modules transitively affected by a change</li>
 *   <li><b>Dependency Structure Matrix (DSM)</b> — ASCII matrix of all inter-module deps</li>
 * </ol>
 */
public final class AnalyzeModulesTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "depth": {
                  "type": "integer",
                  "description": "Number of package segments after the common prefix to use as the module name. depth=1 (default): 'infrastructure' groups all sub-packages; depth=2: 'infrastructure.rest' and 'infrastructure.persistence' become separate nodes. Range: 1–4.",
                  "default": 1,
                  "minimum": 1,
                  "maximum": 4
                }
              },
              "required": []
            }
            """;

    private AnalyzeModulesTool() {}

    public static McpServerFeatures.SyncToolSpecification build(Path projectRoot) {

        var tool = new McpSchema.Tool(
                "analyze_modules",
                "Groups packages into logical modules and returns: Core/Periphery zone "
                + "classification, Propagation Cost (PC = fraction of system affected by a "
                + "change), and an ASCII Dependency Structure Matrix (DSM). "
                + "The optional 'depth' parameter (default 1) controls granularity: "
                + "depth=1 groups all of 'infrastructure.*' into one node; "
                + "depth=2 splits it into 'infrastructure.rest', 'infrastructure.persistence', etc.",
                INPUT_SCHEMA);

        BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> handler =
                (exchange, args) -> {
                    // depth is optional; default 1, clamped to [1,4] inside the analyzer
                    int depth = 1;
                    Object depthArg = args.get("depth");
                    if (depthArg instanceof Number n) {
                        depth = n.intValue();
                    }

                    ModuleReport report;
                    try {
                        report = new ModuleAnalyzer(projectRoot).analyze(depth);
                    } catch (IllegalArgumentException e) {
                        return errorResult(e.getMessage());
                    } catch (IOException e) {
                        System.err.println("[arch-metrics] IO error in analyze_modules: "
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

    static String format(ModuleReport r) {
        StringBuilder sb = new StringBuilder();

        sb.append("Module Analysis Report\n");
        sb.append("=".repeat(60)).append("\n");
        sb.append("Detection:     ").append(r.detectionMode()).append("\n");
        sb.append("Common prefix: ").append(
                r.commonPrefix().isEmpty() ? "(none)" : r.commonPrefix()).append("\n");
        sb.append("Modules found: ").append(r.modules().size()).append("\n\n");

        if (r.modules().isEmpty()) {
            sb.append("(no modules detected — project may not be compiled)\n");
            return sb.toString().stripTrailing();
        }

        // ------------------------------------------------------------------
        // Section 1: Core / Periphery table
        // ------------------------------------------------------------------
        sb.append("─".repeat(60)).append("\n");
        sb.append("CORE / PERIPHERY CLASSIFICATION\n\n");

        String hdr = String.format("  %-20s  %4s  %4s  %6s  %6s  %-10s  %s",
                "Module", "Ce", "Ca", "I", "PC", "Zone", "Packages");
        sb.append(hdr).append("\n");
        sb.append("  ").append("─".repeat(hdr.length() - 2)).append("\n");

        // Group by zone for visual separation
        for (ModuleZone zone : ModuleZone.values()) {
            boolean first = true;
            for (ModuleMetrics m : r.modules()) {
                if (m.zone() != zone) continue;
                if (first) {
                    sb.append(String.format("%n  ▸ %s%n", zoneLabel(zone)));
                    first = false;
                }
                sb.append(String.format("  %-20s  %4d  %4d  %6.3f  %6.3f  %-10s  %s%n",
                        m.moduleName(),
                        m.ce(), m.ca(),
                        m.instability(),
                        m.propagationCost(),
                        zone.name(),
                        m.packages().size() + " pkg(s)"));
                if (!m.dependsOn().isEmpty()) {
                    sb.append(String.format("    depends on: %s%n",
                            String.join(", ", m.dependsOn())));
                }
                if (!m.dependedOnBy().isEmpty()) {
                    sb.append(String.format("    used by:    %s%n",
                            String.join(", ", m.dependedOnBy())));
                }
            }
        }

        // ------------------------------------------------------------------
        // Section 2: Propagation Cost ranked list
        // ------------------------------------------------------------------
        sb.append("\n─".repeat(1)).append("─".repeat(59)).append("\n");
        sb.append("PROPAGATION COST  (PC — fraction of modules affected by a change)\n\n");

        List<ModuleMetrics> byPC = r.modules().stream()
                .sorted((a, b) -> Double.compare(b.propagationCost(), a.propagationCost()))
                .toList();

        for (ModuleMetrics m : byPC) {
            int barLen = (int) Math.round(m.propagationCost() * 30);
            String bar = "█".repeat(barLen) + "░".repeat(30 - barLen);
            sb.append(String.format("  %-20s  %.3f  %s%n",
                    m.moduleName(), m.propagationCost(), bar));
        }

        // ------------------------------------------------------------------
        // Section 3: Cycles
        // ------------------------------------------------------------------
        sb.append("\n─".repeat(1)).append("─".repeat(59)).append("\n");
        if (r.cycles().isEmpty()) {
            sb.append("CYCLES: none  ✅  (acyclicity index: 1.000)\n");
        } else {
            sb.append(String.format("CYCLES: %d detected  ❌  (acyclicity index: %.3f)%n",
                    r.cycles().size(), r.acyclicityIndex()));
            for (List<String> cycle : r.cycles()) {
                sb.append("  ").append(String.join(" → ", cycle)).append("\n");
            }
        }

        // ------------------------------------------------------------------
        // Section 4: DSM
        // ------------------------------------------------------------------
        sb.append("\n─".repeat(1)).append("─".repeat(59)).append("\n");
        sb.append("DEPENDENCY STRUCTURE MATRIX\n");
        sb.append("  X = below diagonal (expected),  ↑ = above diagonal (smell)\n\n");
        sb.append(r.dsm()).append("\n");

        return sb.toString().stripTrailing();
    }

    private static String zoneLabel(ModuleZone z) {
        return switch (z) {
            case CORE       -> "CORE       — high Ca AND high Ce (central hub, risky to change)";
            case SHARED     -> "SHARED     — high Ca, low Ce (stable utility, depended upon)";
            case PERIPHERAL -> "PERIPHERAL — low Ca, high Ce (feature/leaf module)";
            case ISOLATED   -> "ISOLATED   — no coupling in either direction";
        };
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

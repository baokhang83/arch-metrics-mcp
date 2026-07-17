package com.baokhang83.tools;

import com.baokhang83.analysis.CouplingAnalyzer;
import com.baokhang83.analysis.CouplingEntry;
import com.baokhang83.analysis.CouplingReport;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * MCP tool: {@code show_coupling}
 *
 * <p>Shows the full efferent (Ce) and afferent (Ca) coupling lists for a
 * named package, with each entry categorised as {@code (framework)},
 * {@code (domain)}, {@code (port)}, {@code (adapter)}, {@code (external)},
 * or {@code (internal)}.</p>
 */
public final class ShowCouplingTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "package_name": {
                  "type": "string",
                  "description": "Fully qualified Java package name, e.g. com.example.infrastructure.batch"
                }
              },
              "required": ["package_name"]
            }
            """;

    private ShowCouplingTool() {}

    public static McpServerFeatures.SyncToolSpecification build(Path projectRoot) {

        var tool = new McpSchema.Tool(
                "show_coupling",
                "Shows detailed efferent (Ce — packages this package depends on) and "
                + "afferent (Ca — project packages that depend on this package) coupling "
                + "lists for a named Java package. Each entry is labelled as "
                + "(framework), (domain), (port), (adapter), (external), or (internal).",
                INPUT_SCHEMA);

        BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> handler =
                (exchange, args) -> {
                    String packageName = (String) args.get("package_name");
                    if (packageName == null || packageName.isBlank()) {
                        return errorResult("package_name is required");
                    }

                    CouplingReport report;
                    try {
                        report = new CouplingAnalyzer(projectRoot).analyze(packageName);
                    } catch (IllegalArgumentException e) {
                        return errorResult(e.getMessage());
                    } catch (IOException e) {
                        System.err.println("[arch-metrics] IO error in show_coupling: "
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

    static String format(CouplingReport r) {
        StringBuilder sb = new StringBuilder();

        sb.append("Coupling Detail: ").append(r.packageName()).append("\n");
        sb.append("=".repeat(60)).append("\n");
        sb.append(String.format("Classes: %d  |  Ce (efferent): %d  |  Ca (afferent): %d%n",
                r.totalClasses(), r.ce(), r.ca()));

        double instability = (r.ce() + r.ca() == 0) ? 0.0
                : (double) r.ce() / (r.ce() + r.ca());
        sb.append(String.format("Instability (I): %.3f  [0=stable, 1=unstable]%n", instability));

        // ------------------------------------------------------------------
        // Efferent section
        // ------------------------------------------------------------------
        sb.append("\n─".repeat(1)).append("─".repeat(59)).append("\n");
        sb.append("Efferent (this → external)   Ce = ").append(r.ce()).append("\n\n");

        if (r.efferent().isEmpty()) {
            sb.append("  (none — this package has no external dependencies)\n");
        } else {
            int pkgW = r.efferent().stream()
                    .mapToInt(e -> e.packageName().length()).max().orElse(40);
            pkgW = Math.max(pkgW, 40);

            appendCouplingGroup(sb, r.efferent(), "(framework)", pkgW, "── Framework / library deps ──");
            appendCouplingGroup(sb, r.efferent(), "(domain)",    pkgW, "── Domain ──");
            appendCouplingGroup(sb, r.efferent(), "(port)",      pkgW, "── Port ──");
            appendCouplingGroup(sb, r.efferent(), "(adapter)",   pkgW, "── Adapter ──");
            appendCouplingGroup(sb, r.efferent(), "(internal)",  pkgW, "── Internal (layer unknown) ──");
            appendCouplingGroup(sb, r.efferent(), "(external)",  pkgW, "── External (third-party) ──");
        }

        // ------------------------------------------------------------------
        // Afferent section
        // ------------------------------------------------------------------
        sb.append("\n─".repeat(1)).append("─".repeat(59)).append("\n");
        sb.append("Afferent (external → this)   Ca = ").append(r.ca()).append("\n\n");

        if (r.afferent().isEmpty()) {
            sb.append("  (none — nothing in this project depends on this package)\n");
        } else {
            int pkgW = r.afferent().stream()
                    .mapToInt(e -> e.packageName().length()).max().orElse(40);
            pkgW = Math.max(pkgW, 40);

            appendCouplingGroup(sb, r.afferent(), "(domain)",   pkgW, "── Domain ──");
            appendCouplingGroup(sb, r.afferent(), "(port)",     pkgW, "── Port ──");
            appendCouplingGroup(sb, r.afferent(), "(adapter)",  pkgW, "── Adapter ──");
            appendCouplingGroup(sb, r.afferent(), "(internal)", pkgW, "── Internal ──");
        }

        return sb.toString().stripTrailing();
    }

    /**
     * Appends one labelled group of coupling entries (only entries matching
     * {@code label} are printed; skipped silently if none match).
     */
    private static void appendCouplingGroup(StringBuilder sb,
                                             List<CouplingEntry> entries,
                                             String label,
                                             int pkgWidth,
                                             String groupHeading) {
        List<CouplingEntry> group = entries.stream()
                .filter(e -> e.label().equals(label))
                .toList();
        if (group.isEmpty()) return;

        sb.append("  ").append(groupHeading).append("\n");
        for (CouplingEntry e : group) {
            sb.append(String.format("    → %-" + pkgWidth + "s  %s%n",
                    e.packageName(), e.label()));
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

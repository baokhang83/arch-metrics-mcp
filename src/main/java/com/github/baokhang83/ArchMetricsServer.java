package com.github.baokhang83;

import com.github.baokhang83.tools.AnalyzeAllPackagesTool;
import com.github.baokhang83.tools.AnalyzeHexagonalTool;
import com.github.baokhang83.tools.AnalyzeModulesTool;
import com.github.baokhang83.tools.AnalyzePackageTool;
import com.github.baokhang83.tools.FindBoundaryViolationsTool;
import com.github.baokhang83.tools.ShowCouplingTool;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Path;

/**
 * Entry point for the ArchMetrics MCP server.
 *
 * <p>Communicates over stdio (JSON-RPC 2.0). <strong>All diagnostic output must go to
 * {@code System.err}</strong> — {@code System.out} is reserved exclusively for the
 * MCP protocol transport.</p>
 *
 * <p>Set the {@code PROJECT_ROOT} environment variable to point at the Java project
 * whose packages you want to analyse. Defaults to the current working directory.</p>
 *
 * <h3>Exposed tools</h3>
 * <ul>
 *   <li>{@code analyze_package}        — Main Sequence metrics for a single package</li>
 *   <li>{@code analyze_hexagonal}      — Full Hexagonal Architecture report for the project</li>
 *   <li>{@code find_boundary_violations} — Detailed list of Dependency Rule violations</li>
 * </ul>
 */
public final class ArchMetricsServer {

    public static void main(String[] args) throws InterruptedException {

        // ------------------------------------------------------------------
        // 1. Resolve the project root to analyse
        // ------------------------------------------------------------------
        String rootEnv    = System.getenv("PROJECT_ROOT");
        Path   projectRoot = (rootEnv != null && !rootEnv.isBlank())
                ? Path.of(rootEnv)
                : Path.of(System.getProperty("user.dir"));

        System.err.println("[arch-metrics] PROJECT_ROOT: " + projectRoot.toAbsolutePath());

        // ------------------------------------------------------------------
        // 2. Shared Jackson ObjectMapper
        // ------------------------------------------------------------------
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // ------------------------------------------------------------------
        // 3. Build MCP tool specifications
        // ------------------------------------------------------------------
        McpServerFeatures.SyncToolSpecification analyzeSpec     = AnalyzePackageTool.build(projectRoot, mapper);
        McpServerFeatures.SyncToolSpecification hexagonalSpec   = AnalyzeHexagonalTool.build(projectRoot);
        McpServerFeatures.SyncToolSpecification violationsSpec  = FindBoundaryViolationsTool.build(projectRoot);
        McpServerFeatures.SyncToolSpecification modulesSpec     = AnalyzeModulesTool.build(projectRoot);
        McpServerFeatures.SyncToolSpecification allPackagesSpec = AnalyzeAllPackagesTool.build(projectRoot);
        McpServerFeatures.SyncToolSpecification couplingSpec    = ShowCouplingTool.build(projectRoot);

        // ------------------------------------------------------------------
        // 4. Build and start the MCP server on stdio
        // ------------------------------------------------------------------
        McpSyncServer server = McpServer.sync(new StdioServerTransportProvider())
                .serverInfo("arch-metrics", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .tools(analyzeSpec, hexagonalSpec, violationsSpec, modulesSpec,
                        allPackagesSpec, couplingSpec)
                .build();

        System.err.println("[arch-metrics] Server started — listening on stdio");

        // ------------------------------------------------------------------
        // 5. Register shutdown hook and block the main thread
        // ------------------------------------------------------------------
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("[arch-metrics] Shutting down...");
            server.closeGracefully();
        }, "shutdown-hook"));

        Thread.currentThread().join();
    }
}

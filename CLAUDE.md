# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**ArchMetrics** (`com.baokhang83:arch-metrics`) — a Java MCP server that exposes three tools to Claude for measuring architectural quality of any Java project.

- **Java version:** 21 (OpenJDK)
- **Build tool:** Maven with `maven-shade-plugin` (produces a single runnable uber-jar)
- **MCP SDK:** `io.modelcontextprotocol.sdk:mcp:0.10.0` — real packages are `io.modelcontextprotocol.server` and `io.modelcontextprotocol.spec` (not `.sdk`)
- **Bytecode analysis:** ASM 9.7.1

## Commands

```bash
# Build the runnable uber-jar
mvn clean package

# Run the server (PROJECT_ROOT points at the project to analyse)
PROJECT_ROOT=/path/to/project java -jar target/arch-metrics-1.0-SNAPSHOT.jar
```

The compiled classes being analysed must exist under `{PROJECT_ROOT}/target/classes/`.

## MCP Tool Configuration

Add to the target project's `.claude/mcp.json`:

```json
{
  "mcpServers": {
    "arch-metrics": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/arch-metrics-1.0-SNAPSHOT.jar"],
      "env": { "PROJECT_ROOT": "/path/to/project/to/analyse" }
    }
  }
}
```

## Exposed MCP Tools

### `analyze_package`
Input: `package_name`. Scans that package's compiled classes, computes Main Sequence metrics (A, I, D=|A+I-1|), writes a timestamped JSON snapshot to `.stats/{pkg}/`.

### `analyze_hexagonal`
No input. Walks all classes in the project, classifies every package into DOMAIN/PORT/ADAPTER by naming convention, returns per-layer metrics and overall Hexagonal Architecture score (DIS, Port Purity, Adapter Concreteness).

### `find_boundary_violations`
No input. Same scan as `analyze_hexagonal`; returns only the violation edges (DOMAIN->ADAPTER, PORT->ADAPTER) with exact class names causing each violation.

## Architecture

```
src/main/java/com/baokhang83/
├── ArchMetricsServer.java              main() — PROJECT_ROOT env var, McpSyncServer on stdio
├── analysis/
│   ├── PackageMetrics.java             Immutable Jackson-annotated data class for Main Sequence
│   ├── ClassVisitorCollector.java      ASM ClassVisitor: isAbstract + referenced packages per class
│   ├── PackageAnalyzer.java            Main Sequence: Ce (full) + Ca (SKIP_CODE) scan per package
│   ├── LayerType.java                  Enum: DOMAIN, PORT, ADAPTER, UNKNOWN
│   ├── LayerDetector.java              Convention-based classification + INFRA_PREFIXES for DIS
│   ├── BoundaryViolation.java          Record: fromPkg/Layer, toPkg/Layer, causingClasses
│   ├── PackageLayerInfo.java           Record: per-package layer snapshot with issues list
│   ├── HexagonalReport.java            Record: full report (layers map + violations + scores)
│   └── HexagonalAnalyzer.java          Single-pass scan: Ce graph + classRefs + violation detection
├── stats/
│   └── StatsLogger.java                Writes PackageMetrics to .stats/{pkg}/{ts}.json
└── tools/
    ├── AnalyzePackageTool.java         SyncToolSpecification for analyze_package
    ├── AnalyzeHexagonalTool.java       SyncToolSpecification for analyze_hexagonal
    └── FindBoundaryViolationsTool.java SyncToolSpecification for find_boundary_violations
```

## Key Design Notes

**stdout discipline:** `System.out` is MCP JSON-RPC only. All diagnostics go to `System.err`.

**Layer detection priority:** ADAPTER checked first (most distinctive names), then PORT, then DOMAIN.

**HexagonalAnalyzer** does one full-project pass (no SKIP_CODE) to also catch body-level violation references. Builds `classRefs` map (FQN -> referenced packages) to name exact causing classes in violations.

**Hexagonal scoring weights:** DIS x 0.40 + PortPurity x 0.30 + ViolationFreeness x 0.30

**Layer conventions (Option A):**

| Layer   | Matched package name segments |
|---------|-------------------------------|
| DOMAIN  | domain, core, entity, entities, business, model |
| PORT    | port, ports, usecase, usecases, application, api, inbound, outbound |
| ADAPTER | adapter, adapters, infrastructure, infra, persistence, repository, repositories, rest, web, http, controller, controllers, messaging, kafka, rabbitmq, amqp, jms, config, configuration, external, client, clients |

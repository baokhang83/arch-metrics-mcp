# ArchMetrics MCP

> Make the architecture of a Java project visible to your coding assistant.

ArchMetrics is a local [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server that analyzes compiled Java bytecode and turns architectural structure into readable reports. It measures package stability, surfaces coupling, detects dependency cycles, and checks common Hexagonal Architecture boundaries—without requiring changes to the project being analyzed.

It is designed for Claude Code and other MCP clients that support local `stdio` servers.

## What it can show you

- **Main Sequence metrics** — abstractness, instability, afferent/efferent coupling, and distance from the ideal sequence.
- **Package health at a glance** — every package ranked by architectural risk and classified into the Zone of Pain, Zone of Uselessness, or Main Sequence.
- **Hexagonal Architecture fitness** — convention-based domain, port, and adapter detection with isolation, purity, and concreteness scores.
- **Boundary violations** — exact classes responsible for `DOMAIN → ADAPTER` and `PORT → ADAPTER` dependencies.
- **Coupling details** — categorized incoming and outgoing dependencies for a selected package.
- **Module topology** — core/periphery classification, propagation cost, cycles, acyclicity, and an ASCII Dependency Structure Matrix.

ArchMetrics reads `.class` files with [ASM](https://asm.ow2.io/), so it sees dependencies in the compiled result rather than trying to infer them from source text.

## Requirements

- Java 21 or newer
- Maven
- A Java project compiled to the standard Maven output directory, `target/classes/`
- An MCP client with `stdio` server support

## Quick start

### 1. Build ArchMetrics

```bash
git clone https://github.com/baokhang83/arch-metrics-mcp.git
cd arch-metrics-mcp
mvn clean package
```

This creates a self-contained executable JAR:

```text
target/arch-metrics-1.0-SNAPSHOT.jar
```

### 2. Compile the project you want to analyze

```bash
cd /path/to/your/java-project
mvn compile
```

ArchMetrics expects the compiled classes at:

```text
/path/to/your/java-project/target/classes/
```

### 3. Register the MCP server

Add the following server definition to your MCP client configuration. For the Claude Code setup used by this project, place it in `.claude/mcp.json` inside the project being analyzed:

```json
{
  "mcpServers": {
    "arch-metrics": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/arch-metrics-mcp/target/arch-metrics-1.0-SNAPSHOT.jar"
      ],
      "env": {
        "PROJECT_ROOT": "/absolute/path/to/your/java-project"
      }
    }
  }
}
```

Use absolute paths so the server does not depend on the MCP client's working directory. If `PROJECT_ROOT` is omitted, ArchMetrics analyzes its current working directory.

### 4. Ask your assistant

For example:

```text
Analyze all packages and explain the three most concerning results.
```

```text
Find any hexagonal architecture boundary violations and suggest how to fix them.
```

```text
Analyze modules at depth 2 and explain the cycles in the dependency matrix.
```

## MCP tools

| Tool | Input | What it returns |
| --- | --- | --- |
| `analyze_all_packages` | None | A risk-ranked table of every package with its detected layer, `Nc`, `A`, `I`, `D`, and Main Sequence zone. |
| `analyze_package` | `package_name` | Detailed Main Sequence metrics for one package and a timestamped JSON snapshot under `.stats/`. |
| `show_coupling` | `package_name` | Complete `Ce` and `Ca` package lists labeled as framework, domain, port, adapter, internal, or external. |
| `analyze_hexagonal` | None | Per-layer metrics, architecture issues, boundary violations, and aggregate Hexagonal Architecture scores. |
| `find_boundary_violations` | None | Each invalid inner-to-outer dependency edge and the classes that cause it. |
| `analyze_modules` | Optional `depth` from `1` to `4` | Logical modules, core/periphery zones, propagation costs, cycles, acyclicity, and a Dependency Structure Matrix. |

Example tool arguments:

```json
{
  "package_name": "com.example.order.application"
}
```

```json
{
  "depth": 2
}
```

## Metrics

### Package metrics

ArchMetrics uses Robert C. Martin's package metrics:

| Metric | Meaning |
| --- | --- |
| `Nc` | Number of classes and interfaces in the package. |
| `Na` | Number of abstract classes and interfaces. |
| `A = Na / Nc` | Abstractness. `0` is fully concrete; `1` is fully abstract. |
| `Ce` | Efferent coupling: distinct packages this package depends on. |
| `Ca` | Afferent coupling: distinct project packages that depend on this package. |
| `I = Ce / (Ca + Ce)` | Instability. `0` is maximally stable; `1` is maximally unstable. |
| `D = \|A + I - 1\|` | Distance from the Main Sequence. `0` is ideal; `1` is the maximum deviation. |

Packages are then grouped into three zones:

| Zone | Rule | Interpretation |
| --- | --- | --- |
| Zone of Pain | `A < 0.5` and `I < 0.5` | Concrete and stable; changes are expensive for dependents. |
| Zone of Uselessness | `A > 0.5` and `I > 0.5` | Abstract and unstable; abstractions may have little value. |
| Main Sequence | Everything else | A healthier balance of abstractness and stability. |

### Hexagonal Architecture scores

Packages are classified by matching individual package-name segments. Matching is case-insensitive and uses the priority `ADAPTER → PORT → DOMAIN → UNKNOWN`.

| Layer | Recognized package segments |
| --- | --- |
| Domain | `domain`, `core`, `entity`, `entities`, `business`, `model` |
| Port | `port`, `ports`, `usecase`, `usecases`, `application`, `api`, `inbound`, `outbound` |
| Adapter | `adapter`, `adapters`, `infrastructure`, `infra`, `persistence`, `repository`, `repositories`, `rest`, `web`, `http`, `controller`, `controllers`, `messaging`, `kafka`, `rabbitmq`, `amqp`, `jms`, `config`, `configuration`, `external`, `client`, `clients` |

The architecture report includes:

- **Domain Isolation Score (DIS):** the fraction of domain dependencies that do not point to adapter packages or known infrastructure libraries.
- **Port Purity:** average abstractness of port packages.
- **Adapter Concreteness:** average concreteness (`1 - A`) of adapter packages.
- **Overall Hexagonal Score:** `DIS × 0.40 + Port Purity × 0.30 + Violation Freeness × 0.30`.

The boundary checker reports project-internal `DOMAIN → ADAPTER` and `PORT → ADAPTER` edges. Dependencies from domain packages to recognized external infrastructure libraries also reduce the Domain Isolation Score.

### Module metrics

`analyze_modules` finds the longest package prefix shared by the project, then groups packages using the requested number of segments after that prefix.

For example, with the common prefix `com.example`:

| Package | `depth: 1` | `depth: 2` |
| --- | --- | --- |
| `com.example.infrastructure.rest` | `infrastructure` | `infrastructure.rest` |
| `com.example.infrastructure.persistence` | `infrastructure` | `infrastructure.persistence` |

Each module is classified as `CORE`, `SHARED`, `PERIPHERAL`, or `ISOLATED` from its incoming and outgoing coupling. Propagation Cost is the fraction of modules transitively reachable from that module, including itself.

## Suppressing known package-zone warnings

Some packages are concrete or highly coupled by design—for example, generated DTOs, configuration packages, or application entry points. Add `.arch-metrics.yml` to the root of the project being analyzed to keep those packages visible without flagging their Main Sequence zone:

```yaml
suppress_zones:
  - package: com.example.api.model
    reason: Generated OpenAPI DTOs are concrete by design

  - package: com.example.config
    reason: Configuration classes intentionally wire the application
```

Package values use prefix matching, so suppressing `com.example.api.model` also suppresses its subpackages. Suppressions affect `analyze_all_packages`; they do not remove packages from analysis or hide boundary violations.

## Snapshots

Every `analyze_package` call writes a timestamped JSON result to the analyzed project:

```text
.stats/
└── com.example.order.application/
    └── 20260717_084300_123.json
```

These snapshots can be committed or processed by CI to track architectural drift over time.

## How it works

1. The MCP client starts ArchMetrics as a local Java process over `stdio`.
2. ArchMetrics scans `.class` files below `${PROJECT_ROOT}/target/classes` with ASM.
3. Bytecode references are aggregated into package and module dependency graphs.
4. Tool-specific metrics and violations are returned as human-readable MCP text results.

Standard output is reserved for MCP's JSON-RPC transport. Diagnostics and formatted reports are written to standard error, so logging cannot corrupt the protocol stream.

## Limitations

- Only compiled classes under `target/classes/` are analyzed; run `mvn compile` after source changes.
- Layer detection is convention-based. Packages that do not match a recognized segment are reported as `UNKNOWN`.
- Reflection, runtime dependency injection, service discovery, and configuration-only relationships may not appear in bytecode references.
- Generated and synthetic classes in `target/classes/` are included in the results.
- The current layout targets Maven-style Java projects; alternative build output directories are not configurable.

## Development

Build and verify the server with:

```bash
mvn clean verify
```

Run it directly when testing an MCP transport integration:

```bash
PROJECT_ROOT=/path/to/project java -jar target/arch-metrics-1.0-SNAPSHOT.jar
```

The process intentionally remains running while it listens for MCP JSON-RPC messages on standard input.

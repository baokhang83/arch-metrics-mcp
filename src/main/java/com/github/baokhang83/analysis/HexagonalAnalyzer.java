package com.github.baokhang83.analysis;

import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Single-pass hexagonal architecture analyser.
 *
 * <p>Walks every {@code .class} file under {@code {projectRoot}/target/classes/},
 * builds a complete package-level dependency graph, classifies each package into a
 * {@link LayerType} by naming convention, then computes all Hexagonal Architecture
 * metrics and boundary violations.</p>
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Scan all {@code .class} files with full bytecode analysis (no {@code SKIP_CODE})
 *       to capture body-level references as well as structural ones.</li>
 *   <li>Accumulate per-package Nc / Na counts and a directed Ce graph.</li>
 *   <li>Track per-class referenced-package sets so that boundary violations can
 *       name the exact class causing the violation.</li>
 *   <li>Classify packages via {@link LayerDetector}.</li>
 *   <li>Invert the Ce graph to obtain Ca for instability computation.</li>
 *   <li>Detect violation edges and build the {@link HexagonalReport}.</li>
 * </ol>
 */
public final class HexagonalAnalyzer {

    private final Path projectRoot;

    // Collected during the single scan pass -----------------------------------
    /** package → total class count (Nc) */
    private final Map<String, Integer> pkgNc = new HashMap<>();
    /** package → abstract/interface count (Na) */
    private final Map<String, Integer> pkgNa = new HashMap<>();
    /** package → packages it depends on (Ce graph, project packages only as keys) */
    private final Map<String, Set<String>> ceGraph = new HashMap<>();
    /**
     * FQN (e.g. {@code com.example.domain.UserService}) → packages it references.
     * Used to name the exact class causing a boundary violation.
     */
    private final Map<String, Set<String>> classRefs = new HashMap<>();
    /** FQN → owning package */
    private final Map<String, String> classPkg = new HashMap<>();

    public HexagonalAnalyzer(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Performs the full analysis and returns a {@link HexagonalReport}.
     *
     * @throws IllegalArgumentException if {@code target/classes/} does not exist
     * @throws IOException              on any I/O error
     */
    public HexagonalReport analyze() throws IOException {
        Path classesRoot = projectRoot.resolve("target/classes");
        if (!Files.isDirectory(classesRoot)) {
            throw new IllegalArgumentException(
                    "target/classes not found under " + projectRoot.toAbsolutePath()
                    + " — run 'mvn compile' first");
        }

        // ------------------------------------------------------------------
        // Pass 1: scan every .class file
        // ------------------------------------------------------------------
        try (Stream<Path> all = Files.walk(classesRoot)) {
            for (Path f : all.filter(p -> p.toString().endsWith(".class")).toList()) {
                scanClassFile(classesRoot, f);
            }
        }

        // ------------------------------------------------------------------
        // Pass 2: classify packages and invert Ce graph → Ca graph
        // ------------------------------------------------------------------
        Map<String, LayerType> pkgLayers = new HashMap<>();
        for (String pkg : pkgNc.keySet()) {
            pkgLayers.put(pkg, LayerDetector.classify(pkg));
        }

        // Ca graph: package → packages that depend on it (project-internal only)
        Map<String, Set<String>> caGraph = buildInvertedGraph();

        // ------------------------------------------------------------------
        // Pass 3: build PackageLayerInfo per package
        // ------------------------------------------------------------------
        Map<LayerType, List<PackageLayerInfo>> layers = new EnumMap<>(LayerType.class);

        for (Map.Entry<String, Integer> e : pkgNc.entrySet()) {
            String  pkg       = e.getKey();
            int     nc        = e.getValue();
            int     na        = pkgNa.getOrDefault(pkg, 0);
            double  a         = nc == 0 ? 0.0 : round3((double) na / nc);
            int     ce        = ceGraph.getOrDefault(pkg, Set.of()).size();
            int     ca        = caGraph.getOrDefault(pkg, Set.of()).size();
            double  instab    = (ce + ca == 0) ? 0.0 : round3((double) ce / (ce + ca));
            LayerType layer   = pkgLayers.get(pkg);
            List<String> issues = layerIssues(pkg, layer, a, pkgLayers);

            layers.computeIfAbsent(layer, k -> new ArrayList<>())
                  .add(new PackageLayerInfo(pkg, layer, nc, na, a, instab, issues));
        }

        // Sort each layer's list by package name for deterministic output
        layers.values().forEach(list -> list.sort(Comparator.comparing(PackageLayerInfo::packageName)));

        // ------------------------------------------------------------------
        // Pass 4: find boundary violations
        // ------------------------------------------------------------------
        List<BoundaryViolation> violations = findViolations(pkgLayers);

        // ------------------------------------------------------------------
        // Pass 5: compute aggregate scores
        // ------------------------------------------------------------------
        double dis                = computeDIS(pkgLayers);
        double portPurity         = computePortPurity(pkgLayers);
        double adapterConcreteness = computeAdapterConcreteness(pkgLayers);
        double overall            = computeOverall(dis, portPurity, violations, pkgLayers);

        return new HexagonalReport(layers, violations, dis, portPurity, adapterConcreteness, overall);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /** Scans a single {@code .class} file and updates all data structures. */
    private void scanClassFile(Path classesRoot, Path classFile) {
        String pkg = owningPackage(classesRoot, classFile);
        String fqn = deriveFQN(classesRoot, classFile);

        pkgNc.merge(pkg, 1, Integer::sum);

        ClassVisitorCollector visitor = new ClassVisitorCollector(pkg);
        try {
            // Full bytecode analysis (no SKIP_CODE) — catches body-level refs
            new ClassReader(Files.readAllBytes(classFile))
                    .accept(visitor, ClassReader.SKIP_DEBUG);
        } catch (Exception ex) {
            System.err.println("[arch-metrics] Skipping malformed class "
                    + classFile.getFileName() + ": " + ex.getMessage());
            return;
        }

        if (visitor.isAbstract()) pkgNa.merge(pkg, 1, Integer::sum);

        Set<String> refs = visitor.getReferencedPackages();
        ceGraph.computeIfAbsent(pkg, k -> new HashSet<>()).addAll(refs);
        classRefs.put(fqn, refs);
        classPkg.put(fqn, pkg);
    }

    /** Builds the inverted (Ca) graph from the Ce graph. */
    private Map<String, Set<String>> buildInvertedGraph() {
        Map<String, Set<String>> ca = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : ceGraph.entrySet()) {
            for (String dep : e.getValue()) {
                // Only record Ca for packages that are themselves in the project
                if (pkgNc.containsKey(dep)) {
                    ca.computeIfAbsent(dep, k -> new HashSet<>()).add(e.getKey());
                }
            }
        }
        return ca;
    }

    /**
     * Returns layer-specific issues for a package, e.g. concrete class in port,
     * or domain depending on infrastructure.
     */
    private List<String> layerIssues(String pkg, LayerType layer, double abstractness,
                                     Map<String, LayerType> pkgLayers) {
        List<String> issues = new ArrayList<>();
        if (layer == LayerType.PORT && abstractness < 1.0) {
            int concretes = pkgNc.getOrDefault(pkg, 0) - pkgNa.getOrDefault(pkg, 0);
            issues.add("port package has " + concretes + " concrete class(es) — expected interfaces only");
        }
        if (layer == LayerType.DOMAIN) {
            for (String dep : ceGraph.getOrDefault(pkg, Set.of())) {
                if (pkgLayers.getOrDefault(dep, LayerType.UNKNOWN) == LayerType.ADAPTER) {
                    issues.add("depends on ADAPTER package: " + dep);
                } else if (LayerDetector.isKnownInfraPackage(dep)) {
                    issues.add("depends on infrastructure library: " + dep);
                }
            }
        }
        return issues;
    }

    /**
     * Finds all directed Ce edges that violate the Dependency Rule.
     * Violation: inner layer → outer layer.
     * <ul>
     *   <li>DOMAIN → ADAPTER</li>
     *   <li>PORT   → ADAPTER</li>
     * </ul>
     * (DOMAIN → PORT is not flagged; domain defining outbound port interfaces is valid.)
     */
    private List<BoundaryViolation> findViolations(Map<String, LayerType> pkgLayers) {
        List<BoundaryViolation> violations = new ArrayList<>();

        for (Map.Entry<String, Set<String>> edge : ceGraph.entrySet()) {
            String    fromPkg   = edge.getKey();
            LayerType fromLayer = pkgLayers.getOrDefault(fromPkg, LayerType.UNKNOWN);
            if (!fromLayer.isKnown()) continue;

            for (String toPkg : edge.getValue()) {
                // Only flag project-internal edges
                if (!pkgNc.containsKey(toPkg)) continue;
                LayerType toLayer = pkgLayers.getOrDefault(toPkg, LayerType.UNKNOWN);
                if (!toLayer.isKnown()) continue;

                if (isViolation(fromLayer, toLayer)) {
                    List<String> causing = findCausingClasses(fromPkg, toPkg);
                    violations.add(new BoundaryViolation(fromPkg, fromLayer, toPkg, toLayer, causing));
                }
            }
        }

        violations.sort(Comparator.comparing(BoundaryViolation::summary));
        return violations;
    }

    /** True for DOMAIN→ADAPTER and PORT→ADAPTER edges. */
    private static boolean isViolation(LayerType from, LayerType to) {
        return (from == LayerType.DOMAIN || from == LayerType.PORT)
            && to == LayerType.ADAPTER;
    }

    /**
     * Returns simple class names inside {@code fromPkg} that carry a reference
     * to {@code toPkg}, sorted alphabetically.
     */
    private List<String> findCausingClasses(String fromPkg, String toPkg) {
        return classRefs.entrySet().stream()
                .filter(e -> fromPkg.equals(classPkg.get(e.getKey()))
                          && e.getValue().contains(toPkg))
                .map(e -> simpleClassName(e.getKey()))
                .sorted()
                .collect(Collectors.toList());
    }

    // =========================================================================
    // Score computations
    // =========================================================================

    /**
     * Domain Isolation Score = 1 − bad_deps / total_domain_deps
     * where bad_deps are Ce edges from domain packages pointing to adapter packages
     * or known external infrastructure libraries.
     */
    private double computeDIS(Map<String, LayerType> pkgLayers) {
        int total = 0, bad = 0;
        for (Map.Entry<String, LayerType> e : pkgLayers.entrySet()) {
            if (e.getValue() != LayerType.DOMAIN) continue;
            for (String dep : ceGraph.getOrDefault(e.getKey(), Set.of())) {
                total++;
                LayerType depLayer = pkgLayers.getOrDefault(dep, LayerType.UNKNOWN);
                if (depLayer == LayerType.ADAPTER || LayerDetector.isKnownInfraPackage(dep)) bad++;
            }
        }
        return total == 0 ? 1.0 : round3(1.0 - (double) bad / total);
    }

    /** Port Purity Score = average abstractness across all PORT packages (ideal: 1.0). */
    private double computePortPurity(Map<String, LayerType> pkgLayers) {
        List<Double> vals = new ArrayList<>();
        for (Map.Entry<String, LayerType> e : pkgLayers.entrySet()) {
            if (e.getValue() != LayerType.PORT) continue;
            int nc = pkgNc.getOrDefault(e.getKey(), 0);
            int na = pkgNa.getOrDefault(e.getKey(), 0);
            if (nc > 0) vals.add((double) na / nc);
        }
        return vals.isEmpty() ? 1.0 : round3(vals.stream().mapToDouble(d -> d).average().orElse(1.0));
    }

    /** Adapter Concreteness = average (1 − A) across all ADAPTER packages (ideal: 1.0). */
    private double computeAdapterConcreteness(Map<String, LayerType> pkgLayers) {
        List<Double> vals = new ArrayList<>();
        for (Map.Entry<String, LayerType> e : pkgLayers.entrySet()) {
            if (e.getValue() != LayerType.ADAPTER) continue;
            int nc = pkgNc.getOrDefault(e.getKey(), 0);
            int na = pkgNa.getOrDefault(e.getKey(), 0);
            if (nc > 0) vals.add(1.0 - (double) na / nc);
        }
        return vals.isEmpty() ? 1.0 : round3(vals.stream().mapToDouble(d -> d).average().orElse(1.0));
    }

    /**
     * Overall Hexagonal Score = DIS × 0.40 + portPurity × 0.30 + violationFreeness × 0.30
     * violationFreeness = 1 − violations / max(1, total_cross_layer_edges)
     */
    private double computeOverall(double dis, double portPurity,
                                  List<BoundaryViolation> violations,
                                  Map<String, LayerType> pkgLayers) {
        // Count all Ce edges between known-layer packages (cross-layer or same layer)
        long crossLayerEdges = ceGraph.entrySet().stream()
                .flatMap(e -> e.getValue().stream()
                        .filter(dep -> pkgNc.containsKey(dep))
                        .map(dep -> Map.entry(
                                pkgLayers.getOrDefault(e.getKey(), LayerType.UNKNOWN),
                                pkgLayers.getOrDefault(dep, LayerType.UNKNOWN)))
                        .filter(p -> p.getKey().isKnown() && p.getValue().isKnown()))
                .count();

        double violationFreeness = crossLayerEdges == 0
                ? 1.0
                : Math.max(0.0, 1.0 - (double) violations.size() / crossLayerEdges);

        return round3(0.40 * dis + 0.30 * portPurity + 0.30 * violationFreeness);
    }

    // =========================================================================
    // Static utility methods
    // =========================================================================

    /** Derives the Java package name from a class file path relative to classesRoot. */
    private static String owningPackage(Path classesRoot, Path classFile) {
        Path relative = classesRoot.relativize(classFile);
        Path parent   = relative.getParent();
        if (parent == null) return "";
        return parent.toString().replace('/', '.').replace('\\', '.');
    }

    /** Derives the fully-qualified class name (no {@code .class} suffix). */
    private static String deriveFQN(Path classesRoot, Path classFile) {
        Path relative = classesRoot.relativize(classFile);
        String path   = relative.toString().replace('/', '.').replace('\\', '.');
        return path.endsWith(".class") ? path.substring(0, path.length() - 6) : path;
    }

    /** Returns the simple class name from a fully-qualified name. */
    private static String simpleClassName(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}

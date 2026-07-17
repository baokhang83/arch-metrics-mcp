package com.baokhang83.analysis;

import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Scans all compiled classes in the project and returns a detailed coupling
 * breakdown for a named package: which packages it depends on (Ce) and which
 * project packages depend on it (Ca), each labelled by category.
 *
 * <p>Uses full bytecode analysis (no {@code SKIP_CODE}) to capture body-level
 * references in Ce, ensuring accurate counts.</p>
 */
public final class CouplingAnalyzer {

    private final Path projectRoot;

    public CouplingAnalyzer(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    /**
     * Runs a full project scan and returns coupling detail for {@code targetPackage}.
     *
     * @throws IllegalArgumentException if {@code target/classes/} does not exist
     * @throws IOException              on any I/O error
     */
    public CouplingReport analyze(String targetPackage) throws IOException {
        Path classesRoot = projectRoot.resolve("target/classes");
        if (!Files.isDirectory(classesRoot)) {
            throw new IllegalArgumentException(
                    "target/classes not found under " + projectRoot.toAbsolutePath()
                    + " — run 'mvn compile' first");
        }

        // ceGraph: pkg → ALL packages it references (project + external)
        Map<String, Set<String>> ceGraph = new HashMap<>();
        // pkgNc: package → class count (identifies project packages)
        Map<String, Integer> pkgNc = new HashMap<>();

        try (Stream<Path> all = Files.walk(classesRoot)) {
            for (Path f : all.filter(p -> p.toString().endsWith(".class")).toList()) {
                String pkg = owningPackage(classesRoot, f);
                pkgNc.merge(pkg, 1, Integer::sum);

                ClassVisitorCollector visitor = new ClassVisitorCollector(pkg);
                try {
                    new ClassReader(Files.readAllBytes(f))
                            .accept(visitor, ClassReader.SKIP_DEBUG);  // full bytecode — best Ce
                } catch (Exception ex) {
                    System.err.println("[arch-metrics] Skipping " + f.getFileName()
                            + ": " + ex.getMessage());
                    continue;
                }
                ceGraph.computeIfAbsent(pkg, k -> new HashSet<>())
                       .addAll(visitor.getReferencedPackages());
            }
        }

        // caGraph: invert ceGraph for project-internal packages only
        Map<String, Set<String>> caGraph = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : ceGraph.entrySet()) {
            for (String dep : e.getValue()) {
                if (pkgNc.containsKey(dep)) {
                    caGraph.computeIfAbsent(dep, k -> new HashSet<>()).add(e.getKey());
                }
            }
        }

        if (!pkgNc.containsKey(targetPackage)) {
            throw new IllegalArgumentException(
                    "Package not found in compiled classes: " + targetPackage
                    + " — check the spelling and that 'mvn compile' has been run");
        }

        // Build efferent list
        List<CouplingEntry> efferent = ceGraph.getOrDefault(targetPackage, Set.of())
                .stream()
                .sorted()
                .map(dep -> new CouplingEntry(dep, labelPackage(dep, pkgNc)))
                .collect(Collectors.toList());

        // Build afferent list (project-internal only)
        List<CouplingEntry> afferent = caGraph.getOrDefault(targetPackage, Set.of())
                .stream()
                .sorted()
                .map(dep -> new CouplingEntry(dep, labelPackage(dep, pkgNc)))
                .collect(Collectors.toList());

        return new CouplingReport(
                targetPackage,
                pkgNc.getOrDefault(targetPackage, 0),
                efferent,
                afferent);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Assigns a category label to a package name:
     * <ul>
     *   <li>{@code (framework)} — known infrastructure library (Spring, Hibernate, …)</li>
     *   <li>{@code (domain)}    — project-internal DOMAIN layer</li>
     *   <li>{@code (port)}      — project-internal PORT layer</li>
     *   <li>{@code (adapter)}   — project-internal ADAPTER layer</li>
     *   <li>{@code (internal)}  — project-internal, layer unknown</li>
     *   <li>{@code (external)}  — third-party package not in INFRA_PREFIXES</li>
     * </ul>
     */
    private static String labelPackage(String pkg, Map<String, Integer> pkgNc) {
        if (LayerDetector.isKnownInfraPackage(pkg)) return "(framework)";
        if (!pkgNc.containsKey(pkg)) return "(external)";
        return switch (LayerDetector.classify(pkg)) {
            case DOMAIN  -> "(domain)";
            case PORT    -> "(port)";
            case ADAPTER -> "(adapter)";
            default      -> "(internal)";
        };
    }

    private static String owningPackage(Path classesRoot, Path classFile) {
        Path relative = classesRoot.relativize(classFile);
        Path parent   = relative.getParent();
        if (parent == null) return "";
        return parent.toString().replace('/', '.').replace('\\', '.');
    }
}

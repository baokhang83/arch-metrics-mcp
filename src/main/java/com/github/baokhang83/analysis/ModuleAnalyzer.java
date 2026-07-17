package com.github.baokhang83.analysis;

import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds a logical module graph from compiled bytecode and computes three views:
 * <ul>
 *   <li><b>Core / Periphery classification</b> — quadrant based on Ca and Ce</li>
 *   <li><b>Propagation Cost (PC)</b> — fraction of modules transitively reachable</li>
 *   <li><b>Dependency Structure Matrix (DSM)</b> — ASCII matrix of inter-module deps</li>
 * </ul>
 *
 * <h3>Module detection — logical (configurable depth)</h3>
 * <ol>
 *   <li>Discover all packages under {@code target/classes/}.</li>
 *   <li>Find the longest common package prefix (e.g. {@code com.example}).</li>
 *   <li>The first {@code depth} segments after the prefix become the module name
 *       (depth=1: {@code com.example.infrastructure.rest} → {@code infrastructure};
 *        depth=2: → {@code infrastructure.rest}).</li>
 *   <li>Packages that ARE the prefix (root classes) form the {@code (root)} module.</li>
 * </ol>
 */
public final class ModuleAnalyzer {

    private final Path projectRoot;

    // collected during single scan pass
    private final Map<String, Integer>     pkgNc      = new HashMap<>();
    private final Map<String, Set<String>> pkgCeGraph = new HashMap<>();

    public ModuleAnalyzer(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Analyses the project using depth=1 (one segment after common prefix).
     * Equivalent to {@code analyze(1)}.
     */
    public ModuleReport analyze() throws IOException {
        return analyze(1);
    }

    /**
     * Analyses the project, grouping packages into modules by taking
     * {@code depth} dot-separated segments after the common prefix.
     *
     * <p>Examples (prefix = {@code com.example}):
     * <ul>
     *   <li>depth=1: {@code com.example.infrastructure.rest} → module {@code infrastructure}</li>
     *   <li>depth=2: {@code com.example.infrastructure.rest} → module {@code infrastructure.rest}</li>
     * </ul>
     *
     * @param depth number of segments to use; clamped to [1, 4]
     */
    public ModuleReport analyze(int depth) throws IOException {
        depth = Math.max(1, Math.min(4, depth));   // clamp to sane range

        Path classesRoot = projectRoot.resolve("target/classes");
        if (!Files.isDirectory(classesRoot)) {
            throw new IllegalArgumentException(
                    "target/classes not found under " + projectRoot.toAbsolutePath()
                    + " — run 'mvn compile' first");
        }

        // ------------------------------------------------------------------
        // 1. Scan all .class files → pkgNc + pkgCeGraph
        // ------------------------------------------------------------------
        try (Stream<Path> all = Files.walk(classesRoot)) {
            for (Path f : all.filter(p -> p.toString().endsWith(".class")).toList()) {
                scanClass(classesRoot, f);
            }
        }

        Set<String> allPkgs = pkgNc.keySet();
        if (allPkgs.isEmpty()) {
            return new ModuleReport("logical (depth=" + depth + ")", "", List.of(), List.of(),
                    "(no compiled classes found)");
        }

        // ------------------------------------------------------------------
        // 2. Detect common prefix + assign packages to modules
        // ------------------------------------------------------------------
        String commonPrefix = detectCommonPrefix(allPkgs);
        final int resolvedDepth = depth;
        Map<String, String> pkgToModule = new HashMap<>();
        for (String pkg : allPkgs) {
            pkgToModule.put(pkg, packageToModule(pkg, commonPrefix, resolvedDepth));
        }

        // ------------------------------------------------------------------
        // 3. Build module-level Ce graph (project-internal only)
        // ------------------------------------------------------------------
        Map<String, Set<String>> modCeGraph = new HashMap<>();
        for (String fromPkg : allPkgs) {
            String fromMod = pkgToModule.get(fromPkg);
            for (String toPkg : pkgCeGraph.getOrDefault(fromPkg, Set.of())) {
                if (!pkgNc.containsKey(toPkg)) continue;           // external lib
                String toMod = pkgToModule.get(toPkg);
                if (toMod == null || toMod.equals(fromMod)) continue; // intra-module
                modCeGraph.computeIfAbsent(fromMod, k -> new HashSet<>()).add(toMod);
            }
        }

        // ------------------------------------------------------------------
        // 4. Build Ca graph (inverted Ce)
        // ------------------------------------------------------------------
        Map<String, Set<String>> modCaGraph = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : modCeGraph.entrySet()) {
            for (String dep : e.getValue()) {
                modCaGraph.computeIfAbsent(dep, k -> new HashSet<>()).add(e.getKey());
            }
        }

        // ------------------------------------------------------------------
        // 5. Collect all module names and their package sets
        // ------------------------------------------------------------------
        Map<String, Set<String>> modulePkgs = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : pkgToModule.entrySet()) {
            modulePkgs.computeIfAbsent(e.getValue(), k -> new TreeSet<>()).add(e.getKey());
        }
        Set<String> allModules = modulePkgs.keySet();
        int n = allModules.size();

        // ------------------------------------------------------------------
        // 6. Compute per-module metrics
        // ------------------------------------------------------------------
        List<ModuleMetrics> metrics = new ArrayList<>();
        for (String mod : allModules) {
            int ce = modCeGraph.getOrDefault(mod, Set.of()).size();
            int ca = modCaGraph.getOrDefault(mod, Set.of()).size();
            double instab = (ce + ca == 0) ? 0.0 : round3((double) ce / (ce + ca));
            double pc     = computePC(mod, modCeGraph, n);
            ModuleZone zone = ModuleZone.classify(ca, ce);

            int totalClasses = modulePkgs.get(mod).stream()
                    .mapToInt(p -> pkgNc.getOrDefault(p, 0)).sum();

            List<String> dependsOn     = sorted(modCeGraph.getOrDefault(mod, Set.of()));
            List<String> dependedOnBy  = sorted(modCaGraph.getOrDefault(mod, Set.of()));

            metrics.add(new ModuleMetrics(mod, sorted(modulePkgs.get(mod)),
                    totalClasses, ce, ca, instab, pc, zone, dependsOn, dependedOnBy));
        }
        metrics.sort(Comparator.comparing(ModuleMetrics::moduleName));

        // ------------------------------------------------------------------
        // 7. Detect cycles
        // ------------------------------------------------------------------
        List<List<String>> cycles = detectCycles(modCeGraph, allModules);

        // ------------------------------------------------------------------
        // 8. Build DSM (topological sort; fall back to alpha if cycles)
        // ------------------------------------------------------------------
        List<String> sortedMods = topoSort(allModules, modCeGraph);
        String dsm = buildDSM(sortedMods, modCeGraph, !cycles.isEmpty());

        return new ModuleReport("logical (depth=" + depth + ")", commonPrefix, metrics, cycles, dsm);
    }

    // =========================================================================
    // Scan
    // =========================================================================

    private void scanClass(Path classesRoot, Path classFile) {
        String pkg = owningPackage(classesRoot, classFile);
        pkgNc.merge(pkg, 1, Integer::sum);
        ClassVisitorCollector visitor = new ClassVisitorCollector(pkg);
        try {
            new ClassReader(Files.readAllBytes(classFile))
                    .accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        } catch (Exception ex) {
            System.err.println("[arch-metrics] Skipping malformed class "
                    + classFile.getFileName() + ": " + ex.getMessage());
            return;
        }
        // keep only project packages as Ce targets (filtered later by pkgNc.containsKey)
        pkgCeGraph.computeIfAbsent(pkg, k -> new HashSet<>())
                  .addAll(visitor.getReferencedPackages());
    }

    // =========================================================================
    // Module detection
    // =========================================================================

    /**
     * Finds the longest dot-separated prefix shared by all package names.
     * E.g. {com.example.order, com.example.payment} → "com.example"
     */
    static String detectCommonPrefix(Set<String> packages) {
        if (packages.isEmpty()) return "";
        List<String[]> segs = packages.stream()
                .map(p -> p.split("\\.")).collect(Collectors.toList());
        String[] first = segs.get(0);
        int len = first.length;
        for (String[] s : segs) {
            int i = 0;
            while (i < len && i < s.length && s[i].equals(first[i])) i++;
            len = i;
        }
        return String.join(".", Arrays.copyOf(first, len));
    }

    /**
     * Returns the module name for a package by taking up to {@code depth}
     * dot-separated segments after {@code commonPrefix}.
     *
     * <p>Examples (prefix = {@code com.example}, depth = 2):
     * <ul>
     *   <li>{@code com.example.infrastructure.rest} → {@code infrastructure.rest}</li>
     *   <li>{@code com.example.domain}              → {@code domain}  (only 1 seg available)</li>
     *   <li>{@code com.example}                     → {@code (root)}</li>
     * </ul>
     */
    static String packageToModule(String pkg, String commonPrefix, int depth) {
        if (commonPrefix.isEmpty()) {
            String[] s = pkg.split("\\.");
            return s.length == 0 ? pkg
                    : String.join(".", Arrays.copyOf(s, Math.min(depth, s.length)));
        }
        if (pkg.equals(commonPrefix)) return "(root)";
        if (pkg.startsWith(commonPrefix + ".")) {
            String rest = pkg.substring(commonPrefix.length() + 1);
            String[] parts = rest.split("\\.");
            return String.join(".", Arrays.copyOf(parts, Math.min(depth, parts.length)));
        }
        return pkg; // fallback: unexpected
    }

    /** Depth-1 convenience overload (keeps existing call sites working). */
    static String packageToModule(String pkg, String commonPrefix) {
        return packageToModule(pkg, commonPrefix, 1);
    }

    // =========================================================================
    // Graph algorithms
    // =========================================================================

    /** BFS reachability from {@code start}; returns fraction of all modules reached. */
    private static double computePC(String start, Map<String, Set<String>> ceGraph, int total) {
        if (total == 0) return 0.0;
        Set<String> reached = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(start);
        reached.add(start);
        while (!queue.isEmpty()) {
            for (String dep : ceGraph.getOrDefault(queue.poll(), Set.of())) {
                if (reached.add(dep)) queue.add(dep);
            }
        }
        return round3((double) reached.size() / total);
    }

    /**
     * DFS-based cycle detection. Returns each cycle as an ordered list of module
     * names where the first and last element are the same.
     */
    private static List<List<String>> detectCycles(Map<String, Set<String>> ceGraph,
                                                    Set<String> allModules) {
        Set<String> visited  = new HashSet<>();
        Set<String> inStack  = new HashSet<>();
        Deque<String> stack  = new ArrayDeque<>();
        List<List<String>> cycles = new ArrayList<>();

        for (String mod : allModules) {
            if (!visited.contains(mod)) {
                dfsCycles(mod, ceGraph, visited, inStack, stack, cycles);
            }
        }
        return cycles;
    }

    private static void dfsCycles(String node, Map<String, Set<String>> ceGraph,
                                   Set<String> visited, Set<String> inStack,
                                   Deque<String> stack, List<List<String>> cycles) {
        visited.add(node);
        inStack.add(node);
        stack.push(node);

        for (String dep : ceGraph.getOrDefault(node, Set.of())) {
            if (!visited.contains(dep)) {
                dfsCycles(dep, ceGraph, visited, inStack, stack, cycles);
            } else if (inStack.contains(dep)) {
                // Extract cycle from top of stack down to dep
                List<String> cycle = new ArrayList<>();
                for (String s : stack) {        // stack is LIFO, iterate gives reverse
                    cycle.add(0, s);
                    if (s.equals(dep)) break;
                }
                cycle.add(dep);                 // close the loop
                // Avoid duplicate cycles (same set, different starting point)
                Set<String> cycleSet = new HashSet<>(cycle);
                boolean dup = cycles.stream().anyMatch(c -> new HashSet<>(c).equals(cycleSet));
                if (!dup) cycles.add(cycle);
            }
        }

        stack.pop();
        inStack.remove(node);
    }

    /**
     * Kahn's algorithm topological sort (providers first).
     * If cycles exist, remaining nodes are appended alphabetically.
     */
    private static List<String> topoSort(Set<String> allModules,
                                          Map<String, Set<String>> ceGraph) {
        // in-degree counts for topological sort based on Ce direction:
        // if A → B (A depends on B), B is a "provider" and should come first.
        // So in-degree here counts how many modules depend ON this module.
        Map<String, Integer> inDeg = new HashMap<>();
        for (String m : allModules) inDeg.put(m, 0);
        for (String from : allModules) {
            for (String to : ceGraph.getOrDefault(from, Set.of())) {
                if (allModules.contains(to)) {
                    // 'from' depends on 'to' → 'to' should come before 'from'
                    // so we track how many things point TO each node (Ca direction)
                    // to find "roots" (modules nothing depends on yet)
                    inDeg.merge(from, 0, Integer::sum); // ensure key exists
                }
            }
        }
        // Recompute: track how many outgoing modules 'to' has as prerequisites
        Map<String, Integer> ceInDeg = new HashMap<>();
        for (String m : allModules) ceInDeg.put(m, 0);
        for (String from : allModules) {
            for (String to : ceGraph.getOrDefault(from, Set.of())) {
                if (allModules.contains(to)) ceInDeg.merge(from, 1, Integer::sum);
            }
        }
        // Nodes with ceInDeg == 0 depend on nothing → they are leaf consumers, go last.
        // We want providers (high Ca, low Ce) first.
        // Use Ca-based in-degree: nodes nothing depends on (Ca=0) go last.
        Map<String, Integer> caInDeg = new HashMap<>();
        for (String m : allModules) caInDeg.put(m, 0);
        for (String from : allModules) {
            for (String to : ceGraph.getOrDefault(from, Set.of())) {
                if (allModules.contains(to)) caInDeg.merge(to, 1, Integer::sum);
            }
        }

        PriorityQueue<String> queue = new PriorityQueue<>(
                Comparator.comparingInt((String m) -> -caInDeg.getOrDefault(m, 0))
                          .thenComparing(Comparator.naturalOrder()));

        for (String m : allModules) {
            if (ceInDeg.getOrDefault(m, 0) == 0) queue.add(m);
        }

        List<String> sorted = new ArrayList<>();
        Set<String> done    = new HashSet<>();

        while (!queue.isEmpty()) {
            String m = queue.poll();
            sorted.add(m);
            done.add(m);
            // "unlock" modules whose only remaining dependency is m
            for (String candidate : allModules) {
                if (done.contains(candidate)) continue;
                Set<String> deps = ceGraph.getOrDefault(candidate, Set.of());
                if (deps.stream().allMatch(d -> done.contains(d) || !allModules.contains(d))) {
                    if (!queue.contains(candidate)) queue.add(candidate);
                }
            }
        }

        // Append any remaining (cyclic) nodes alphabetically
        allModules.stream().filter(m -> !done.contains(m)).sorted().forEach(sorted::add);
        return sorted;
    }

    // =========================================================================
    // DSM builder
    // =========================================================================

    private static String buildDSM(List<String> sortedMods,
                                    Map<String, Set<String>> ceGraph,
                                    boolean hasCycles) {
        int n = sortedMods.size();
        if (n == 0) return "(no modules)";

        // Column headers: abbreviate to at most 8 chars
        List<String> abbrevs = sortedMods.stream()
                .map(m -> m.length() > 8 ? m.substring(0, 7) + "…" : m)
                .collect(Collectors.toList());

        int labelW = sortedMods.stream().mapToInt(String::length).max().orElse(0);
        int colW   = abbrevs.stream().mapToInt(String::length).max().orElse(4) + 2;

        StringBuilder sb = new StringBuilder();
        String note = hasCycles ? " (⚠ cycles present — topo order approximate)" : "";
        sb.append("Dependency Structure Matrix — ").append(n).append(" modules")
          .append(note).append("\n");
        sb.append("Rows = consumer,  Columns = provider,  X = depends on,  · = self\n\n");

        // Header row
        sb.append(" ".repeat(labelW + 3));
        for (String ab : abbrevs) sb.append(padCenter(ab, colW));
        sb.append("\n");

        // Separator
        sb.append(" ".repeat(labelW + 3));
        sb.append("─".repeat(colW * n));
        sb.append("\n");

        // Data rows
        for (int i = 0; i < n; i++) {
            String rowMod  = sortedMods.get(i);
            Set<String> deps = ceGraph.getOrDefault(rowMod, Set.of());
            sb.append(String.format("%-" + labelW + "s  │", rowMod));
            for (int j = 0; j < n; j++) {
                String colMod = sortedMods.get(j);
                String cell;
                if (i == j) {
                    cell = "·";
                } else if (deps.contains(colMod)) {
                    // Below diagonal = OK (row depends on earlier-sorted provider)
                    // Above diagonal = potential smell if acyclic; cycle if flagged
                    cell = i > j ? "X" : "↑";   // ↑ = above-diagonal dep (reversed)
                } else {
                    cell = " ";
                }
                sb.append(padCenter(cell, colW));
            }
            sb.append("\n");
        }

        // Legend for column abbreviations
        sb.append("\n");
        for (int i = 0; i < n; i++) {
            sb.append(String.format("  %-8s = %s%n", abbrevs.get(i), sortedMods.get(i)));
        }

        return sb.toString().stripTrailing();
    }

    private static String padCenter(String s, int width) {
        int pad = width - s.length();
        int left  = pad / 2;
        int right = pad - left;
        return " ".repeat(left) + s + " ".repeat(right);
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    private static String owningPackage(Path classesRoot, Path classFile) {
        Path relative = classesRoot.relativize(classFile);
        Path parent   = relative.getParent();
        if (parent == null) return "";
        return parent.toString().replace('/', '.').replace('\\', '.');
    }

    private static List<String> sorted(Collection<String> c) {
        return c.stream().sorted().collect(Collectors.toList());
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}

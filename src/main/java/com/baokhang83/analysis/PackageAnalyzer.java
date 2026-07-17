package com.baokhang83.analysis;

import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Computes Main Sequence metrics for a single Java package by scanning compiled
 * {@code .class} files under {@code {projectRoot}/target/classes/}.
 *
 * <p>Instantiate once per analysis call (stateless after construction).</p>
 */
public final class PackageAnalyzer {

    private static final DateTimeFormatter TS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                             .withZone(ZoneOffset.UTC);

    private final Path projectRoot;

    public PackageAnalyzer(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    /**
     * Analyses {@code packageName} and returns a fully-populated {@link PackageMetrics}.
     *
     * @param packageName  fully-qualified package name, e.g. {@code com.example.service}
     * @param capturedAt   the instant recorded as the snapshot timestamp
     * @throws IllegalArgumentException if {@code target/classes/} or the package dir is missing
     * @throws IOException              if reading any {@code .class} file fails
     */
    public PackageMetrics analyze(String packageName, Instant capturedAt) throws IOException {

        Path classesRoot = projectRoot.resolve("target/classes");
        if (!Files.isDirectory(classesRoot)) {
            throw new IllegalArgumentException(
                    "target/classes not found under " + projectRoot.toAbsolutePath()
                    + " — run 'mvn compile' first");
        }

        Path packageDir = classesRoot.resolve(packageName.replace('.', '/'));
        if (!Files.isDirectory(packageDir)) {
            throw new IllegalArgumentException(
                    "Package not found: " + packageName
                    + " (looked in " + packageDir.toAbsolutePath() + ")");
        }

        // ------------------------------------------------------------------
        // Step 1: scan classes in the target package (non-recursive) for Ce
        // ------------------------------------------------------------------
        int nc = 0;
        int na = 0;
        Set<String> cePackages = new HashSet<>();

        try (Stream<Path> files = Files.list(packageDir)) {
            for (Path classFile : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                ClassVisitorCollector visitor = new ClassVisitorCollector(packageName);
                try {
                    new ClassReader(Files.readAllBytes(classFile))
                            .accept(visitor, ClassReader.SKIP_DEBUG);
                } catch (Exception e) {
                    System.err.println("[main-sequence-mcp] Skipping malformed class "
                            + classFile.getFileName() + ": " + e.getMessage());
                    continue;
                }
                nc++;
                if (visitor.isAbstract()) na++;
                cePackages.addAll(visitor.getReferencedPackages());
            }
        }

        int ce = cePackages.size();

        // ------------------------------------------------------------------
        // Step 2: scan ALL other classes in the project for Ca
        //         (structural refs only → SKIP_CODE for speed)
        // ------------------------------------------------------------------
        Set<String> caPackages = new HashSet<>();

        try (Stream<Path> all = Files.walk(classesRoot)) {
            for (Path classFile : all.filter(p -> p.toString().endsWith(".class")
                                              && !p.startsWith(packageDir)).toList()) {

                String scannerPkg = owningPackage(classesRoot, classFile);
                ClassVisitorCollector visitor = new ClassVisitorCollector(scannerPkg);
                try {
                    new ClassReader(Files.readAllBytes(classFile))
                            .accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                } catch (Exception e) {
                    System.err.println("[main-sequence-mcp] Skipping malformed class "
                            + classFile.getFileName() + ": " + e.getMessage());
                    continue;
                }
                if (visitor.getReferencedPackages().contains(packageName)) {
                    caPackages.add(scannerPkg);
                }
            }
        }

        int ca = caPackages.size();

        // ------------------------------------------------------------------
        // Step 3: derived metrics (guard division by zero)
        // ------------------------------------------------------------------
        double a = (nc == 0)        ? 0.0 : (double) na / nc;
        double inst = (ca + ce == 0) ? 0.0 : (double) ce / (ca + ce);
        double d = Math.abs(a + inst - 1.0);

        return new PackageMetrics(
                packageName,
                TS_FORMATTER.format(capturedAt),
                nc, na,
                round3(a),
                ce, ca,
                round3(inst),
                round3(d));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Derives the Java package name of a {@code .class} file from its path
     * relative to {@code classesRoot}.
     * e.g. {@code classesRoot/com/example/Foo.class} → {@code com.example}
     */
    private static String owningPackage(Path classesRoot, Path classFile) {
        Path relative = classesRoot.relativize(classFile);
        Path parent = relative.getParent();
        if (parent == null) return ""; // default package
        return parent.toString().replace('/', '.').replace('\\', '.');
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}

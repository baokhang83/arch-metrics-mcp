package com.github.baokhang83.analysis;

import org.objectweb.asm.*;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * ASM {@link ClassVisitor} that collects, for a single {@code .class} file:
 * <ul>
 *   <li>whether the class/interface is abstract ({@link #isAbstract()})</li>
 *   <li>the set of external package names it references ({@link #getReferencedPackages()})</li>
 * </ul>
 *
 * <p>The {@code targetPackage} supplied at construction is excluded from the result set
 * so that intra-package references don't inflate coupling counts.
 * Standard JDK prefixes ({@code java.*}, {@code javax.*}, {@code sun.*}, {@code jdk.*},
 * {@code com.sun.*}) are also filtered out.</p>
 */
final class ClassVisitorCollector extends ClassVisitor {

    private final String targetPackage;
    private boolean isAbstract;
    private final Set<String> referencedPackages = new HashSet<>();

    ClassVisitorCollector(String targetPackage) {
        super(Opcodes.ASM9);
        this.targetPackage = targetPackage == null ? "" : targetPackage;
    }

    // -------------------------------------------------------------------------
    // ClassVisitor overrides
    // -------------------------------------------------------------------------

    @Override
    public void visit(int version, int access, String name, String signature,
                      String superName, String[] interfaces) {
        isAbstract = (access & Opcodes.ACC_ABSTRACT) != 0
                  || (access & Opcodes.ACC_INTERFACE) != 0;
        if (superName != null) addByInternalName(superName);
        if (interfaces != null) {
            for (String iface : interfaces) addByInternalName(iface);
        }
        parseSignature(signature);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String desc,
                                   String signature, Object value) {
        processDescriptor(desc);
        parseSignature(signature);
        return null;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc,
                                     String signature, String[] exceptions) {
        processMethodDescriptor(desc);
        parseSignature(signature);
        if (exceptions != null) {
            for (String ex : exceptions) addByInternalName(ex);
        }
        // Return a visitor for method-body instructions (used for Ce; ignored when SKIP_CODE).
        return new MethodVisitor(Opcodes.ASM9) {
            @Override
            public void visitTypeInsn(int opcode, String type) {
                addByInternalName(type);
            }
            @Override
            public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                addByInternalName(owner);
                processDescriptor(desc);
            }
            @Override
            public void visitMethodInsn(int opcode, String owner, String name,
                                        String desc, boolean itf) {
                addByInternalName(owner);
                processMethodDescriptor(desc);
            }
        };
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    boolean isAbstract() { return isAbstract; }

    Set<String> getReferencedPackages() {
        return Collections.unmodifiableSet(referencedPackages);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Extracts a package name from an ASM internal class name such as
     * {@code com/example/service/Foo} or {@code [Lcom/example/Foo;} and adds it
     * to the referenced-packages set after filtering.
     */
    private void addByInternalName(String internalName) {
        if (internalName == null || internalName.isEmpty()) return;

        // Strip leading array descriptors: "[[[Lcom/example/Foo;" → "com/example/Foo;"
        int i = 0;
        while (i < internalName.length() && internalName.charAt(i) == '[') i++;
        if (i > 0) {
            internalName = internalName.substring(i);
            // Object array element: "Lcom/example/Foo;" → "com/example/Foo"
            if (internalName.startsWith("L") && internalName.endsWith(";")) {
                internalName = internalName.substring(1, internalName.length() - 1);
            } else {
                return; // primitive array
            }
        }

        addReferencedPackage(toPackage(internalName));
    }

    /** Converts {@code com/example/service/Foo} → {@code com.example.service}. */
    private static String toPackage(String internalName) {
        int lastSlash = internalName.lastIndexOf('/');
        if (lastSlash < 0) return ""; // default (unnamed) package
        return internalName.substring(0, lastSlash).replace('/', '.');
    }

    /**
     * Adds a package name to the result set, applying all filters.
     */
    private void addReferencedPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        if (pkg.equals(targetPackage)) return;                       // self
        if (!pkg.contains(".")) return;                              // unnamed / single-segment
        if (pkg.startsWith("java.")) return;
        if (pkg.startsWith("javax.")) return;
        if (pkg.startsWith("sun.")) return;
        if (pkg.startsWith("jdk.")) return;
        if (pkg.startsWith("com.sun.")) return;
        referencedPackages.add(pkg);
    }

    /** Parses a field descriptor such as {@code Ljava/util/List;}. */
    private void processDescriptor(String desc) {
        if (desc == null || desc.isEmpty()) return;
        Type type = Type.getType(desc);
        processType(type);
    }

    /** Parses a method descriptor, extracting all parameter and return types. */
    private void processMethodDescriptor(String desc) {
        if (desc == null || desc.isEmpty()) return;
        for (Type arg : Type.getArgumentTypes(desc)) processType(arg);
        processType(Type.getReturnType(desc));
    }

    private void processType(Type type) {
        switch (type.getSort()) {
            case Type.OBJECT -> addReferencedPackage(toPackage(type.getInternalName()));
            case Type.ARRAY  -> processType(type.getElementType());
            // primitives and void: nothing to do
        }
    }

    /**
     * Parses a generic signature string using {@link SignatureReader}, visiting
     * every class type reference to collect packages.
     */
    private void parseSignature(String signature) {
        if (signature == null || signature.isEmpty()) return;
        try {
            new SignatureReader(signature).accept(new SignatureVisitor(Opcodes.ASM9) {
                @Override
                public void visitClassType(String name) {
                    addReferencedPackage(toPackage(name));
                }
            });
        } catch (Exception ignored) {
            // Malformed signature — skip silently
        }
    }
}

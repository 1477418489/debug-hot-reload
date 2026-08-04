package dev.hotreload.agent.classes;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ClassChangeAnalyzer {
    public enum ChangeKind {
        IDENTICAL,
        CODE_OR_ATTRIBUTE,
        ANNOTATION,
        STRUCTURE,
        UNKNOWN
    }

    /**
     * 注解集合内部持有 {@link AnnotationFingerprint} 指纹（含属性值），
     * 相等性比较用指纹——仅修改注解属性值（如 @GetMapping 路径）也计为注解变更。
     * 对外 getter 仍返回类型 descriptor 视图，保持既有语义。
     */
    public static final class ClassShape {
        private final Set<String> fields;
        private final Set<String> methods;
        private final Set<String> classAnnotationFingerprints;
        private final Map<String, Set<String>> methodAnnotationFingerprints;
        private final Set<String> classAnnotations;
        private final Map<String, Set<String>> methodAnnotations;

        ClassShape(Set<String> fields, Set<String> methods, Set<String> classAnnotationFingerprints,
                   Map<String, Set<String>> methodAnnotationFingerprints) {
            this.fields = fields;
            this.methods = methods;
            this.classAnnotationFingerprints = classAnnotationFingerprints;
            this.methodAnnotationFingerprints = methodAnnotationFingerprints;
            this.classAnnotations = descriptorView(classAnnotationFingerprints);
            Map<String, Set<String>> descriptors = new LinkedHashMap<String, Set<String>>();
            for (Map.Entry<String, Set<String>> entry : methodAnnotationFingerprints.entrySet()) {
                descriptors.put(entry.getKey(), descriptorView(entry.getValue()));
            }
            this.methodAnnotations = Collections.unmodifiableMap(descriptors);
        }

        public Set<String> getFields() { return fields; }
        public Set<String> getMethods() { return methods; }
        public Set<String> getClassAnnotations() { return classAnnotations; }
        public Map<String, Set<String>> getMethodAnnotations() { return methodAnnotations; }

        public boolean looksLikeSpringComponent() {
            for (String annotation : classAnnotations) {
                if (isSpringStereotype(annotation)) {
                    return true;
                }
            }
            return false;
        }

        /** 条目为完整指纹：mapping 注解的属性值变化（改路径）也会改变该集合。 */
        public Set<String> webMappingAnnotations() {
            Set<String> out = new LinkedHashSet<String>();
            for (String fingerprint : classAnnotationFingerprints) {
                if (isWebMappingAnnotation(fingerprint)) out.add("c:" + fingerprint);
            }
            for (Map.Entry<String, Set<String>> entry : methodAnnotationFingerprints.entrySet()) {
                for (String fingerprint : entry.getValue()) {
                    if (isWebMappingAnnotation(fingerprint)) {
                        out.add(entry.getKey() + ":" + fingerprint);
                    }
                }
            }
            return out;
        }

        boolean sameAnnotations(ClassShape other) {
            return classAnnotationFingerprints.equals(other.classAnnotationFingerprints)
                    && methodAnnotationFingerprints.equals(other.methodAnnotationFingerprints);
        }

        private static Set<String> descriptorView(Set<String> fingerprints) {
            Set<String> descriptors = new LinkedHashSet<String>();
            for (String fingerprint : fingerprints) {
                descriptors.add(AnnotationFingerprint.descriptorOf(fingerprint));
            }
            return Collections.unmodifiableSet(descriptors);
        }
    }

    public static final class Analysis {
        private final ChangeKind kind;
        private final ClassShape before;
        private final ClassShape after;
        private final boolean annotationChanged;
        private final boolean structureChanged;
        private final boolean webMappingAnnotationChanged;

        Analysis(ChangeKind kind, ClassShape before, ClassShape after,
                 boolean annotationChanged, boolean structureChanged,
                 boolean webMappingAnnotationChanged) {
            this.kind = kind;
            this.before = before;
            this.after = after;
            this.annotationChanged = annotationChanged;
            this.structureChanged = structureChanged;
            this.webMappingAnnotationChanged = webMappingAnnotationChanged;
        }

        public ChangeKind getKind() { return kind; }
        public ClassShape getBefore() { return before; }
        public ClassShape getAfter() { return after; }
        public boolean isAnnotationChanged() { return annotationChanged; }
        public boolean isStructureChanged() { return structureChanged; }
        public boolean isWebMappingAnnotationChanged() { return webMappingAnnotationChanged; }

        public boolean needsSpringRebind() {
            return annotationChanged || structureChanged || (after != null && after.looksLikeSpringComponent());
        }

        /** True when RequestMapping-style metadata or type structure changed. */
        public boolean needsRequestMappingRefresh() {
            return structureChanged || webMappingAnnotationChanged;
        }
    }

    private ClassChangeAnalyzer() { }

    public static ClassShape readShape(byte[] bytecode) {
        return readShape(bytecode, ClassChangeAnalyzer.class.getClassLoader());
    }

    /** loader 用于补齐注解默认属性值，须与反射侧同一 loader 才能两侧指纹一致。 */
    public static ClassShape readShape(byte[] bytecode, ClassLoader loader) {
        Objects.requireNonNull(bytecode, "bytecode");
        Collector collector = new Collector(loader);
        new ClassReader(bytecode).accept(collector, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return collector.toShape();
    }

    /**
     * Prefer live reflection metadata. ClassLoader.getResourceAsStream often returns the
     * newly compiled disk class after Build, which hides structure deltas and breaks hot reload.
     */
    public static ClassShape readShape(Class<?> type) {
        Objects.requireNonNull(type, "type");
        ClassShape live = readLiveShape(type);
        if (live != null) return live;
        return readShapeFromClassResource(type);
    }

    public static ClassShape readLiveShape(Class<?> type) {
        Objects.requireNonNull(type, "type");
        try {
            Set<String> fields = new LinkedHashSet<String>();
            for (Field field : type.getDeclaredFields()) {
                if (field.isSynthetic()) continue;
                fields.add(field.getName() + ":" + Type.getDescriptor(field.getType()));
            }
            Set<String> methods = new LinkedHashSet<String>();
            Map<String, Set<String>> methodAnnotations = new LinkedHashMap<String, Set<String>>();
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (constructor.isSynthetic()) continue;
                String key = "<init>" + Type.getConstructorDescriptor(constructor);
                methods.add(key);
                methodAnnotations.put(key, annotationFingerprints(constructor.getDeclaredAnnotations()));
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge()) continue;
                String key = method.getName() + Type.getMethodDescriptor(method);
                methods.add(key);
                methodAnnotations.put(key, annotationFingerprints(method.getDeclaredAnnotations()));
            }
            Set<String> classAnnotations = annotationFingerprints(type.getDeclaredAnnotations());
            return new ClassShape(
                    Collections.unmodifiableSet(fields),
                    Collections.unmodifiableSet(methods),
                    Collections.unmodifiableSet(classAnnotations),
                    Collections.unmodifiableMap(methodAnnotations));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ClassShape readShapeFromClassResource(Class<?> type) {
        String resource = type.getName().replace('.', '/') + ".class";
        ClassLoader loader = type.getClassLoader();
        InputStream input = loader == null
                ? ClassLoader.getSystemResourceAsStream(resource)
                : loader.getResourceAsStream(resource);
        if (input == null) return null;
        try {
            try {
                return readShape(readAll(input), loader);
            } finally {
                input.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    public static Analysis compare(Class<?> loaded, byte[] nextBytecode) {
        ClassShape after = readShape(nextBytecode, loaded.getClassLoader());
        // Always prefer live JVM shape for "before". Disk resource is often already the new class
        // after IDEA compile, which would hide add/delete method/field and cause HotSpot
        // "add/delete method not implemented" on redefine.
        ClassShape before = readLiveShape(loaded);
        if (before == null) {
            ClassShape resource = readShapeFromClassResource(loaded);
            if (resource == null) {
                return new Analysis(ChangeKind.UNKNOWN, null, after, true, true, true);
            }
            // If disk already matches after, it is not a trustworthy "before" snapshot.
            if (sameSchema(resource, after)) {
                return new Analysis(ChangeKind.UNKNOWN, resource, after, true, true, true);
            }
            before = resource;
        }
        boolean structureChanged = !sameSchema(before, after);
        boolean annotationChanged = !before.sameAnnotations(after);
        boolean webMappingAnnotationChanged = !before.webMappingAnnotations().equals(after.webMappingAnnotations());
        if (structureChanged) {
            return new Analysis(ChangeKind.STRUCTURE, before, after, annotationChanged, true, webMappingAnnotationChanged);
        }
        if (annotationChanged) {
            return new Analysis(ChangeKind.ANNOTATION, before, after, true, false, webMappingAnnotationChanged);
        }
        return new Analysis(ChangeKind.CODE_OR_ATTRIBUTE, before, after, false, false, false);
    }

    static boolean sameSchema(ClassShape left, ClassShape right) {
        if (left == null || right == null) return false;
        return left.fields.equals(right.fields) && left.methods.equals(right.methods);
    }

    static boolean isWebMappingAnnotation(String descriptorOrFingerprint) {
        if (descriptorOrFingerprint == null) return false;
        String descriptor = AnnotationFingerprint.descriptorOf(descriptorOrFingerprint);
        return descriptor.endsWith("RequestMapping;")
                || descriptor.endsWith("GetMapping;")
                || descriptor.endsWith("PostMapping;")
                || descriptor.endsWith("PutMapping;")
                || descriptor.endsWith("DeleteMapping;")
                || descriptor.endsWith("PatchMapping;")
                || descriptor.endsWith("MessageMapping;")
                || descriptor.endsWith("SubscribeMapping;");
    }

    private static boolean isSpringStereotype(String annotation) {
        return annotation.endsWith("Component;")
                || annotation.endsWith("Service;")
                || annotation.endsWith("Repository;")
                || annotation.endsWith("Controller;")
                || annotation.endsWith("RestController;")
                || annotation.endsWith("Configuration;");
    }

    private static Set<String> annotationFingerprints(Annotation[] annotations) {
        Set<String> out = new LinkedHashSet<String>();
        if (annotations == null) return out;
        for (Annotation annotation : annotations) {
            if (annotation == null) continue;
            out.add(AnnotationFingerprint.of(annotation));
        }
        return out;
    }

    private static byte[] readAll(InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static final class Collector extends ClassVisitor {
        private final ClassLoader loader;
        private final Set<String> fields = new LinkedHashSet<String>();
        private final Set<String> methods = new LinkedHashSet<String>();
        private final Set<String> classAnnotations = new LinkedHashSet<String>();
        private final Map<String, Set<String>> methodAnnotations = new LinkedHashMap<String, Set<String>>();

        private Collector(ClassLoader loader) {
            super(Opcodes.ASM9);
            this.loader = loader;
        }

        @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            // 仅收集 RUNTIME 可见注解，与反射侧 getDeclaredAnnotations 一致；
            // CLASS-retention 注解（如 @NotNull）反射不可见，纳入会造成永久性注解误报。
            if (!visible) return null;
            return new FingerprintingAnnotationVisitor(descriptor, loader, classAnnotations);
        }

        @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            if ((access & Opcodes.ACC_SYNTHETIC) == 0) {
                fields.add(name + ":" + descriptor);
            }
            return null;
        }

        @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if ((access & Opcodes.ACC_SYNTHETIC) != 0 || (access & Opcodes.ACC_BRIDGE) != 0) {
                return null;
            }
            final String key = name + descriptor;
            methods.add(key);
            final Set<String> fingerprints = new LinkedHashSet<String>();
            methodAnnotations.put(key, fingerprints);
            return new MethodVisitor(Opcodes.ASM9) {
                @Override public AnnotationVisitor visitAnnotation(String annotationDesc, boolean visible) {
                    if (!visible) return null;
                    return new FingerprintingAnnotationVisitor(annotationDesc, loader, fingerprints);
                }
            };
        }

        private ClassShape toShape() {
            Map<String, Set<String>> frozenMethods = new LinkedHashMap<String, Set<String>>();
            for (Map.Entry<String, Set<String>> entry : methodAnnotations.entrySet()) {
                frozenMethods.put(entry.getKey(), Collections.unmodifiableSet(new LinkedHashSet<String>(entry.getValue())));
            }
            return new ClassShape(
                    Collections.unmodifiableSet(new LinkedHashSet<String>(fields)),
                    Collections.unmodifiableSet(new LinkedHashSet<String>(methods)),
                    Collections.unmodifiableSet(new LinkedHashSet<String>(classAnnotations)),
                    Collections.unmodifiableMap(frozenMethods));
        }
    }

    /** 收集显式属性值并在 visitEnd 时产出与反射侧一致的指纹。 */
    private static class FingerprintingAnnotationVisitor extends AnnotationVisitor {
        private final String descriptor;
        private final ClassLoader loader;
        private final Set<String> sink;
        private final Map<String, Object> values = new LinkedHashMap<String, Object>();

        private FingerprintingAnnotationVisitor(String descriptor, ClassLoader loader, Set<String> sink) {
            super(Opcodes.ASM9);
            this.descriptor = descriptor;
            this.loader = loader;
            this.sink = sink;
        }

        @Override public void visit(String name, Object value) {
            if (name != null) values.put(name, value);
        }

        @Override public void visitEnum(String name, String enumDesc, String value) {
            if (name != null) values.put(name, value);
        }

        @Override public AnnotationVisitor visitArray(final String name) {
            final java.util.List<Object> items = new java.util.ArrayList<Object>();
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override public void visit(String n, Object value) { items.add(value); }
                @Override public void visitEnum(String n, String enumDesc, String value) { items.add(value); }
                @Override public AnnotationVisitor visitAnnotation(String n, String desc) {
                    final Set<String> nested = new LinkedHashSet<String>();
                    return new FingerprintingAnnotationVisitor(desc, loader, nested) {
                        @Override public void visitEnd() {
                            super.visitEnd();
                            items.addAll(nested);
                        }
                    };
                }
                @Override public void visitEnd() {
                    if (name != null) values.put(name, items);
                }
            };
        }

        @Override public AnnotationVisitor visitAnnotation(final String name, String desc) {
            final Set<String> nested = new LinkedHashSet<String>();
            return new FingerprintingAnnotationVisitor(desc, loader, nested) {
                @Override public void visitEnd() {
                    super.visitEnd();
                    if (name != null && !nested.isEmpty()) values.put(name, nested.iterator().next());
                }
            };
        }

        @Override public void visitEnd() {
            sink.add(AnnotationFingerprint.of(descriptor, values, loader));
        }
    }
}

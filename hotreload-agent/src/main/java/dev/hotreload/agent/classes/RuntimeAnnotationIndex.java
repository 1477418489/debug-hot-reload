package dev.hotreload.agent.classes;

import dev.hotreload.bootstrap.HotReloadBridge;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Source-of-truth annotation view parsed from the latest redefined bytecode.
 * Used when HotSpot leaves stale RuntimeVisibleAnnotations visible through reflection.
 * Memory bounded to {@link #MAX_ENTRIES} classes.
 */
public final class RuntimeAnnotationIndex {
    static final int MAX_ENTRIES = 512;
    private static final ConcurrentHashMap<String, ClassAnnotations> BY_CLASS =
            new ConcurrentHashMap<String, ClassAnnotations>();
    private static final ConcurrentLinkedQueue<String> INSERT_ORDER = new ConcurrentLinkedQueue<String>();

    private RuntimeAnnotationIndex() { }

    public static void update(Class<?> type, byte[] bytecode) {
        if (type == null || bytecode == null) return;
        ClassAnnotations parsed = ClassAnnotations.parse(bytecode);
        String name = type.getName();
        ClassAnnotations previous = BY_CLASS.put(name, parsed);
        if (previous == null) INSERT_ORDER.add(name);
        trimToLimit();
        publishToBridge(name, parsed);
    }

    public static void clear(Class<?> type) {
        if (type == null) return;
        BY_CLASS.remove(type.getName());
        HotReloadBridge.replaceClassAnnotations(type.getName(), null, null);
    }

    public static void clearAll() {
        BY_CLASS.clear();
        INSERT_ORDER.clear();
    }

    public static int size() {
        return BY_CLASS.size();
    }

    private static void trimToLimit() {
        while (BY_CLASS.size() > MAX_ENTRIES) {
            String oldest = INSERT_ORDER.poll();
            if (oldest == null) break;
            if (!BY_CLASS.containsKey(oldest)) continue;
            BY_CLASS.remove(oldest);
            try {
                HotReloadBridge.replaceClassAnnotations(oldest, null, null);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void publishToBridge(String className, ClassAnnotations parsed) {
        try {
            Map<String, Map<String, String>> classAnns = new LinkedHashMap<String, Map<String, String>>();
            for (Ann ann : parsed.getClassAnnotations()) {
                classAnns.put(descriptorToBinary(ann.getDescriptor()), new LinkedHashMap<String, String>(ann.getAttrs()));
            }
            Map<String, Map<String, Map<String, String>>> methodAnns =
                    new LinkedHashMap<String, Map<String, Map<String, String>>>();
            for (Map.Entry<String, Set<Ann>> entry : parsed.getMethodAnnotations().entrySet()) {
                Map<String, Map<String, String>> byType = new LinkedHashMap<String, Map<String, String>>();
                for (Ann ann : entry.getValue()) {
                    byType.put(descriptorToBinary(ann.getDescriptor()),
                            new LinkedHashMap<String, String>(ann.getAttrs()));
                }
                methodAnns.put(entry.getKey(), byType);
            }
            HotReloadBridge.replaceClassAnnotations(className, classAnns, methodAnns);
        } catch (Throwable ignored) {
            // Bootstrap bridge may be unavailable in unit tests.
        }
    }

    private static String descriptorToBinary(String descriptor) {
        return Type.getType(descriptor).getClassName();
    }

    public static ClassAnnotations get(Class<?> type) {
        if (type == null) return null;
        return BY_CLASS.get(type.getName());
    }

    public static boolean hasMethodAnnotation(Method method, String annotationSimpleName) {
        if (method == null || annotationSimpleName == null) return false;
        Class<?> owner = method.getDeclaringClass();
        while (owner != null && (owner.getName().contains("$$") || owner.getName().contains("CGLIB"))) {
            owner = owner.getSuperclass();
        }
        ClassAnnotations view = get(owner != null ? owner : method.getDeclaringClass());
        if (view == null) return false;
        String key = method.getName() + Type.getMethodDescriptor(method);
        Set<Ann> anns = view.getMethodAnnotations().get(key);
        if (anns == null) {
            for (Map.Entry<String, Set<Ann>> entry : view.getMethodAnnotations().entrySet()) {
                if (entry.getKey() != null && entry.getKey().startsWith(method.getName() + "(")) {
                    anns = entry.getValue();
                    break;
                }
            }
        }
        if (anns == null) return false;
        for (Ann ann : anns) {
            if (annotationSimpleName.equals(ann.simpleName)
                    || ann.getDescriptor().endsWith("/" + annotationSimpleName + ";")
                    || ann.getDescriptor().endsWith("$" + annotationSimpleName + ";")) {
                return true;
            }
        }
        return false;
    }

    public static String describe(Class<?> type) {
        if (type == null) return "annotationsVisible=none";
        ClassAnnotations view = get(type);
        if (view == null) return type.getSimpleName() + "=none";
        StringBuilder builder = new StringBuilder();
        builder.append(type.getSimpleName()).append('{');
        builder.append(joinClass(view.getClassAnnotations()));
        if (!view.getMethodAnnotations().isEmpty()) {
            if (builder.charAt(builder.length() - 1) != '{') builder.append('|');
            int i = 0;
            // Prefer high-signal methods for compact diagnostics.
            java.util.List<Map.Entry<String, Set<Ann>>> entries =
                    new java.util.ArrayList<Map.Entry<String, Set<Ann>>>(view.getMethodAnnotations().entrySet());
            java.util.Collections.sort(entries, new java.util.Comparator<Map.Entry<String, Set<Ann>>>() {
                public int compare(Map.Entry<String, Set<Ann>> left, Map.Entry<String, Set<Ann>> right) {
                    return Integer.compare(score(right.getValue()), score(left.getValue()));
                }
            });
            for (Map.Entry<String, Set<Ann>> entry : entries) {
                if (i++ > 0) builder.append(',');
                String methodName = entry.getKey();
                int paren = methodName.indexOf('(');
                if (paren > 0) methodName = methodName.substring(0, paren);
                builder.append(methodName).append('@').append(joinMethod(entry.getValue()));
                if (i >= 6) break;
            }
        }
        builder.append('}');
        return builder.toString();
    }

    private static int score(Set<Ann> anns) {
        int score = 0;
        for (Ann ann : anns) {
            if ("PreAuthorize".equals(ann.simpleName) || "Transactional".equals(ann.simpleName)
                    || "Secured".equals(ann.simpleName) || "Cacheable".equals(ann.simpleName)
                    || "Async".equals(ann.simpleName)) {
                score += 20;
            } else if (ann.simpleName.endsWith("Mapping")) score += 10;
            else if (ann.simpleName.startsWith("Api")) score -= 5;
            else score += 1;
        }
        return score;
    }

    private static String joinClass(Set<Ann> anns) {
        StringBuilder builder = new StringBuilder();
        int i = 0;
        for (Ann ann : anns) {
            if (ann.simpleName.startsWith("Api")) continue;
            if (i++ > 0) builder.append('+');
            builder.append(ann.simpleName);
        }
        return builder.toString();
    }

    private static String joinMethod(Set<Ann> anns) {
        StringBuilder builder = new StringBuilder();
        int i = 0;
        for (Ann ann : anns) {
            if (i++ > 0) builder.append('+');
            builder.append(ann.simpleName);
            if (!ann.getAttrs().isEmpty()) {
                builder.append('(');
                int a = 0;
                for (Map.Entry<String, String> attr : ann.getAttrs().entrySet()) {
                    if (a++ > 0) builder.append(',');
                    builder.append(attr.getKey()).append('=').append(attr.getValue());
                    if (a >= 2) break;
                }
                builder.append(')');
            }
        }
        return builder.toString();
    }

    public static final class Ann {
        final String descriptor;
        final String simpleName;
        final Map<String, String> attrs;

        Ann(String descriptor, Map<String, String> attrs) {
            this.descriptor = descriptor;
            this.simpleName = simpleNameOf(descriptor);
            this.attrs = attrs == null ? Collections.<String, String>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<String, String>(attrs));
        }

        public String getDescriptor() { return descriptor; }
        public String getSimpleName() { return simpleName; }
        public Map<String, String> getAttrs() { return attrs; }

        private static String simpleNameOf(String descriptor) {
            String binary = Type.getType(descriptor).getClassName();
            int idx = binary.lastIndexOf('.');
            String simple = idx < 0 ? binary : binary.substring(idx + 1);
            int dollar = simple.lastIndexOf('$');
            return dollar < 0 ? simple : simple.substring(dollar + 1);
        }
    }

    public static final class ClassAnnotations {
        private final Set<Ann> classAnnotations;
        private final Map<String, Set<Ann>> methodAnnotations;

        private ClassAnnotations(Set<Ann> classAnnotations, Map<String, Set<Ann>> methodAnnotations) {
            this.classAnnotations = classAnnotations;
            this.methodAnnotations = methodAnnotations;
        }

        public Set<Ann> getClassAnnotations() { return classAnnotations; }
        public Map<String, Set<Ann>> getMethodAnnotations() { return methodAnnotations; }

        public static ClassAnnotations parse(byte[] bytecode) {
            final Set<Ann> classAnns = new LinkedHashSet<Ann>();
            final Map<String, Set<Ann>> methodAnns = new LinkedHashMap<String, Set<Ann>>();
            ClassReader reader = new ClassReader(bytecode);
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    final Map<String, String> attrs = new LinkedHashMap<String, String>();
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override public void visit(String name, Object value) {
                            if (name != null) attrs.put(name, String.valueOf(value));
                        }
                        @Override public void visitEnd() {
                            classAnns.add(new Ann(descriptor, attrs));
                        }
                    };
                }

                @Override public MethodVisitor visitMethod(int access, final String name, final String descriptor,
                                                           String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public AnnotationVisitor visitAnnotation(String annDesc, boolean visible) {
                            final Map<String, String> attrs = new LinkedHashMap<String, String>();
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override public void visit(String attrName, Object value) {
                                    if (attrName != null) attrs.put(attrName, String.valueOf(value));
                                }
                                @Override public void visitEnd() {
                                    String key = name + descriptor;
                                    Set<Ann> anns = methodAnns.get(key);
                                    if (anns == null) {
                                        anns = new LinkedHashSet<Ann>();
                                        methodAnns.put(key, anns);
                                    }
                                    anns.add(new Ann(annDesc, attrs));
                                }
                            };
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return new ClassAnnotations(
                    Collections.unmodifiableSet(new LinkedHashSet<Ann>(classAnns)),
                    Collections.unmodifiableMap(new LinkedHashMap<String, Set<Ann>>(methodAnns)));
        }
    }
}

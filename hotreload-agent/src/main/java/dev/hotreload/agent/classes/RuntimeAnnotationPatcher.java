package dev.hotreload.agent.classes;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Forces runtime method/class annotations to match redefined bytecode.
 * HotSpot redefineClasses on JDK 8 often keeps stale RuntimeVisibleAnnotations
 * visible through reflection even after reflectionData is cleared.
 */
public final class RuntimeAnnotationPatcher {
    private RuntimeAnnotationPatcher() { }

    public static final class PatchReport {
        private final int methodsPatched;
        private final int classPatched;
        private final int failures;
        private final List<String> diffs;

        PatchReport(int methodsPatched, int classPatched, int failures, List<String> diffs) {
            this.methodsPatched = methodsPatched;
            this.classPatched = classPatched;
            this.failures = failures;
            this.diffs = diffs;
        }

        public int getMethodsPatched() { return methodsPatched; }
        public int getClassPatched() { return classPatched; }
        public int getFailures() { return failures; }
        public boolean isComplete() { return failures == 0; }
        public List<String> getDiffs() { return diffs; }

        public String summary() {
            StringBuilder builder = new StringBuilder(96);
            builder.append("annotationPatch=methods=").append(methodsPatched)
                    .append(",class=").append(classPatched)
                    .append(",failures=").append(failures);
            if (!diffs.isEmpty()) {
                builder.append(",diff=");
                for (int i = 0; i < diffs.size(); i++) {
                    if (i > 0) builder.append(';');
                    builder.append(diffs.get(i));
                    if (builder.length() > 360) {
                        builder.append(";...");
                        break;
                    }
                }
            }
            return builder.toString();
        }
    }

    public static PatchReport patch(Class<?> type, byte[] bytecode) {
        if (type == null || bytecode == null || bytecode.length == 0) {
            return new PatchReport(0, 0, 0, Collections.<String>emptyList());
        }
        AnnotationModel model = AnnotationModel.read(bytecode, type.getClassLoader());
        List<String> diffs = new ArrayList<String>();
        int methodsPatched = 0;
        int classPatched = 0;
        int failures = 0;

        Map<Class<? extends Annotation>, Annotation> classRuntime = currentAnnotations(type);
        Map<Class<? extends Annotation>, Annotation> classDesired = model.classAnnotations;
        String classDiff = diff("class", classRuntime.keySet(), classDesired.keySet());
        if (classDiff != null) diffs.add(classDiff);

        for (Method method : type.getDeclaredMethods()) {
            String key = method.getName() + org.objectweb.asm.Type.getMethodDescriptor(method);
            Map<Class<? extends Annotation>, Annotation> desired = model.methodAnnotations.get(key);
            if (desired == null) desired = Collections.emptyMap();
            Map<Class<? extends Annotation>, Annotation> runtime = currentAnnotations(method);
            String methodDiff = diff(method.getName(), runtime.keySet(), desired.keySet());
            if (methodDiff != null) diffs.add(methodDiff);
            if (forceDeclaredAnnotations(method, desired)) {
                methodsPatched++;
            } else if (methodDiff != null) {
                failures++;
            }
        }
        if (forceDeclaredAnnotations(type, classDesired)) {
            classPatched = 1;
        } else if (classDiff != null) {
            failures++;
        }
        return new PatchReport(methodsPatched, classPatched, failures, diffs);
    }

    /**
     * Re-apply annotations from {@link RuntimeAnnotationIndex} without original bytecode.
     * Used after Spring rebind clears Class.reflectionData / annotationData.
     */
    public static int reapplyFromIndex(java.util.Collection<Class<?>> types) {
        if (types == null || types.isEmpty()) return 0;
        int count = 0;
        for (Class<?> type : types) {
            if (type == null) continue;
            if (reapplyFromIndex(type)) count++;
        }
        return count;
    }

    public static boolean reapplyFromIndex(Class<?> type) {
        if (type == null) return false;
        RuntimeAnnotationIndex.ClassAnnotations view = RuntimeAnnotationIndex.get(type);
        if (view == null) return false;
        boolean changed = false;
        try {
            Map<Class<? extends Annotation>, Annotation> classDesired =
                    toAnnotationMap(view.getClassAnnotations(), type.getClassLoader());
            if (forceDeclaredAnnotations(type, classDesired)) changed = true;
            for (Method method : type.getDeclaredMethods()) {
                String key = method.getName() + org.objectweb.asm.Type.getMethodDescriptor(method);
                java.util.Set<RuntimeAnnotationIndex.Ann> anns = view.getMethodAnnotations().get(key);
                if (anns == null) {
                    for (Map.Entry<String, java.util.Set<RuntimeAnnotationIndex.Ann>> entry
                            : view.getMethodAnnotations().entrySet()) {
                        if (entry.getKey() != null && entry.getKey().startsWith(method.getName() + "(")) {
                            anns = entry.getValue();
                            break;
                        }
                    }
                }
                Map<Class<? extends Annotation>, Annotation> desired =
                        toAnnotationMap(anns, type.getClassLoader());
                if (forceDeclaredAnnotations(method, desired)) changed = true;
            }
        } catch (Throwable ignored) {
            return false;
        }
        return changed;
    }

    private static Map<Class<? extends Annotation>, Annotation> toAnnotationMap(
            java.util.Collection<RuntimeAnnotationIndex.Ann> anns, ClassLoader loader) {
        Map<Class<? extends Annotation>, Annotation> map =
                new LinkedHashMap<Class<? extends Annotation>, Annotation>();
        if (anns == null) return map;
        for (RuntimeAnnotationIndex.Ann ann : anns) {
            if (ann == null) continue;
            try {
                String binary = org.objectweb.asm.Type.getType(ann.getDescriptor()).getClassName();
                Class<?> type = Class.forName(binary, false, loader);
                if (!Annotation.class.isAssignableFrom(type)) continue;
                @SuppressWarnings("unchecked")
                Class<? extends Annotation> cast = (Class<? extends Annotation>) type;
                Map<String, Object> raw = new LinkedHashMap<String, Object>();
                for (Map.Entry<String, String> entry : ann.getAttrs().entrySet()) {
                    raw.put(entry.getKey(), entry.getValue());
                }
                map.put(cast, createAnnotation(cast, raw));
            } catch (Throwable ignored) {
                // skip unavailable annotation types
            }
        }
        return map;
    }

    public static String describeFromBytecode(Class<?> type, byte[] bytecode) {
        if (type == null || bytecode == null) return "annotationsBytecode=none";
        AnnotationModel model = AnnotationModel.read(bytecode, type.getClassLoader());
        StringBuilder builder = new StringBuilder("annotationsBytecode=");
        builder.append(type.getSimpleName()).append('{');
        builder.append(joinNames(model.classAnnotations.keySet()));
        builder.append('|');
        int printed = 0;
        // Prefer methods with runtime annotations for patch ordering.
        List<Map.Entry<String, Map<Class<? extends Annotation>, Annotation>>> entries =
                new ArrayList<Map.Entry<String, Map<Class<? extends Annotation>, Annotation>>>(model.methodAnnotations.entrySet());
        Collections.sort(entries, new java.util.Comparator<Map.Entry<String, Map<Class<? extends Annotation>, Annotation>>>() {
            public int compare(Map.Entry<String, Map<Class<? extends Annotation>, Annotation>> left,
                               Map.Entry<String, Map<Class<? extends Annotation>, Annotation>> right) {
                return Integer.compare(score(right.getValue()), score(left.getValue()));
            }
        });
        for (Map.Entry<String, Map<Class<? extends Annotation>, Annotation>> entry : entries) {
            if (entry.getValue().isEmpty()) continue;
            String methodName = entry.getKey();
            int paren = methodName.indexOf('(');
            if (paren > 0) methodName = methodName.substring(0, paren);
            if (printed++ > 0) builder.append(',');
            builder.append(methodName).append('@').append(joinCompact(entry.getValue()));
            if (printed >= 8 || builder.length() > 420) break;
        }
        builder.append('}');
        return builder.toString();
    }

    private static int score(Map<Class<? extends Annotation>, Annotation> anns) {
        int score = 0;
        for (Class<?> type : anns.keySet()) {
            String name = type.getSimpleName();
            if ("PreAuthorize".equals(name) || "Transactional".equals(name)
                    || "Secured".equals(name) || "Cacheable".equals(name)
                    || "Async".equals(name)) {
                score += 20;
            } else if (name.endsWith("Mapping")) score += 10;
            else score += 1;
        }
        return score;
    }

    private static String joinNames(Set<Class<? extends Annotation>> types) {
        StringBuilder builder = new StringBuilder();
        int i = 0;
        for (Class<? extends Annotation> type : types) {
            if (i++ > 0) builder.append('+');
            builder.append(type.getSimpleName());
        }
        return builder.toString();
    }

    private static String joinCompact(Map<Class<? extends Annotation>, Annotation> anns) {
        StringBuilder builder = new StringBuilder();
        int i = 0;
        for (Map.Entry<Class<? extends Annotation>, Annotation> entry : anns.entrySet()) {
            String simple = entry.getKey().getSimpleName();
            if ("Api".equals(simple) || simple.startsWith("Api")) continue;
            if (i++ > 0) builder.append('+');
            builder.append(simple);
            Object alias = readAttr(entry.getValue(), "value");
            if (alias == null) alias = firstNonDefaultAttr(entry.getValue());
            if (alias != null && String.valueOf(alias).length() > 0) {
                builder.append('(').append(alias).append(')');
            }
            if (builder.length() > 160) break;
        }
        return builder.toString();
    }

    private static Object readAttr(Annotation annotation, String name) {
        try {
            Method method = annotation.annotationType().getMethod(name);
            return method.invoke(annotation);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Generic fallback: first attribute with a non-empty value, no framework-specific names. */
    private static Object firstNonDefaultAttr(Annotation annotation) {
        try {
            for (Method method : annotation.annotationType().getDeclaredMethods()) {
                if (method.getParameterTypes().length != 0) continue;
                String name = method.getName();
                if ("annotationType".equals(name) || "hashCode".equals(name)
                        || "toString".equals(name) || "equals".equals(name)) continue;
                Object value = method.invoke(annotation);
                if (value == null) continue;
                String rendered = String.valueOf(value);
                if (rendered.isEmpty() || "[]".equals(rendered) || "{}".equals(rendered)) continue;
                return rendered;
            }
        } catch (Throwable ignored) {
            // best-effort diagnostics only
        }
        return null;
    }

    private static String diff(String owner, Set<Class<? extends Annotation>> before,
                               Set<Class<? extends Annotation>> after) {
        Set<String> removed = new LinkedHashSet<String>();
        Set<String> added = new LinkedHashSet<String>();
        for (Class<? extends Annotation> type : before) {
            if (!after.contains(type)) removed.add(type.getSimpleName());
        }
        for (Class<? extends Annotation> type : after) {
            if (!before.contains(type)) added.add(type.getSimpleName());
        }
        if (removed.isEmpty() && added.isEmpty()) return null;
        StringBuilder builder = new StringBuilder(owner);
        if (!removed.isEmpty()) builder.append(":-").append(join(removed));
        if (!added.isEmpty()) builder.append(":+").append(join(added));
        return builder.toString();
    }

    private static String join(Set<String> values) {
        StringBuilder builder = new StringBuilder();
        int i = 0;
        for (String value : values) {
            if (i++ > 0) builder.append(',');
            builder.append(value);
        }
        return builder.toString();
    }

    private static Map<Class<? extends Annotation>, Annotation> currentAnnotations(java.lang.reflect.AnnotatedElement element) {
        Map<Class<? extends Annotation>, Annotation> map = new LinkedHashMap<Class<? extends Annotation>, Annotation>();
        try {
            for (Annotation annotation : element.getDeclaredAnnotations()) {
                map.put(annotation.annotationType(), annotation);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return map;
    }

    private static boolean writeDeclaredAnnotations(Object target,
                                                    Map<Class<? extends Annotation>, Annotation> desired) {
        return forceDeclaredAnnotations(target, desired);
    }

    private static boolean forceDeclaredAnnotations(Object target,
                                                    Map<Class<? extends Annotation>, Annotation> desired) {
        if (target == null) return false;
        if (target instanceof Method) {
            return forceMethodAnnotations((Method) target, desired);
        }
        if (target instanceof Class) {
            Map<Class<? extends Annotation>, Annotation> copy =
                    new LinkedHashMap<Class<? extends Annotation>, Annotation>(desired);
            Object annotationData = unsafeGet(target, findField(Class.class, "annotationData"));
            if (annotationData == null) return false;
            boolean changed = false;
            if (unsafeSet(annotationData, findField(annotationData.getClass(), "declaredAnnotations"), copy)) {
                changed = true;
            }
            if (unsafeSet(annotationData, findField(annotationData.getClass(), "annotations"), copy)) {
                changed = true;
            }
            return changed;
        }
        return false;
    }

    private static boolean forceMethodAnnotations(Method method,
                                                 Map<Class<? extends Annotation>, Annotation> desired) {
        java.lang.reflect.Field annField = findField(method.getClass(), "declaredAnnotations");
        if (annField == null) {
            // Executable.declaredAnnotations
            annField = findField(java.lang.reflect.Executable.class, "declaredAnnotations");
        }
        if (annField == null) return false;

        Object root = method;
        java.lang.reflect.Field rootField = findField(Method.class, "root");
        if (rootField != null) {
            Object maybeRoot = unsafeGet(method, rootField);
            if (maybeRoot instanceof Method) {
                root = maybeRoot;
            }
        }
        // Prefer Method.getRoot() when available.
        try {
            Method getRoot = Method.class.getDeclaredMethod("getRoot");
            getRoot.setAccessible(true);
            Object viaApi = getRoot.invoke(method);
            if (viaApi instanceof Method) root = viaApi;
        } catch (Throwable ignored) {
            // keep field-walk root
        }

        Map<Class<? extends Annotation>, Annotation> rootMap =
                new LinkedHashMap<Class<? extends Annotation>, Annotation>(desired);
        boolean ok = unsafeSet(root, annField, rootMap);
        // Keep leaf in sync as well for already-cached Method objects.
        if (root != method) {
            unsafeSet(method, annField, null);
            unsafeSet(method, annField, new LinkedHashMap<Class<? extends Annotation>, Annotation>(desired));
        }
        // Verify via reflection API, not field identity.
        try {
            Method fresh = method.getDeclaringClass().getDeclaredMethod(
                    method.getName(), method.getParameterTypes());
            Annotation[] anns = fresh.getDeclaredAnnotations();
            if (desired.isEmpty()) {
                return ok && (anns == null || anns.length == 0);
            }
            for (Class<? extends Annotation> type : desired.keySet()) {
                if (fresh.getDeclaredAnnotation(type) == null) {
                    // last resort: put map on the fresh instance too
                    unsafeSet(fresh, annField, new LinkedHashMap<Class<? extends Annotation>, Annotation>(desired));
                    Object root2 = unsafeGet(fresh, rootField);
                    if (root2 != null) {
                        unsafeSet(root2, annField, new LinkedHashMap<Class<? extends Annotation>, Annotation>(desired));
                    }
                    return fresh.getDeclaredAnnotation(type) != null;
                }
            }
            return true;
        } catch (Throwable ignored) {
            return ok;
        }
    }

    private static Object unsafeGet(Object target, java.lang.reflect.Field field) {
        if (target == null || field == null) return null;
        try {
            Object unsafe = unsafe();
            if (unsafe == null) {
                field.setAccessible(true);
                return field.get(target);
            }
            long offset = objectFieldOffset(unsafe, field);
            Method getObject = unsafe.getClass().getMethod("getObject", Object.class, long.class);
            return getObject.invoke(unsafe, target, Long.valueOf(offset));
        } catch (Throwable ignored) {
            try {
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored2) {
                return null;
            }
        }
    }

    private static boolean unsafeSet(Object target, java.lang.reflect.Field field, Object value) {
        if (target == null || field == null) return false;
        try {
            Object unsafe = unsafe();
            if (unsafe == null) {
                field.setAccessible(true);
                field.set(target, value);
                return true;
            }
            long offset = objectFieldOffset(unsafe, field);
            try {
                Method putObjectVolatile = unsafe.getClass().getMethod(
                        "putObjectVolatile", Object.class, long.class, Object.class);
                putObjectVolatile.invoke(unsafe, target, Long.valueOf(offset), value);
            } catch (NoSuchMethodException missing) {
                Method putObject = unsafe.getClass().getMethod("putObject", Object.class, long.class, Object.class);
                putObject.invoke(unsafe, target, Long.valueOf(offset), value);
            }
            return true;
        } catch (Throwable ignored) {
            try {
                field.setAccessible(true);
                field.set(target, value);
                return true;
            } catch (Throwable ignored2) {
                return false;
            }
        }
    }

    private static long objectFieldOffset(Object unsafe, java.lang.reflect.Field field) throws Exception {
        Method objectFieldOffset = unsafe.getClass().getMethod("objectFieldOffset", java.lang.reflect.Field.class);
        return ((Long) objectFieldOffset.invoke(unsafe, field)).longValue();
    }

    private static Object unsafe() {
        try {
            java.lang.reflect.Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            return theUnsafe.get(null);
        } catch (Throwable ignored) {
            try {
                java.lang.reflect.Field theUnsafe = Class.forName("jdk.internal.misc.Unsafe").getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                return theUnsafe.get(null);
            } catch (Throwable ignored2) {
                return null;
            }
        }
    }

    private static java.lang.reflect.Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static final class AnnotationModel {
        private final Map<Class<? extends Annotation>, Annotation> classAnnotations =
                new LinkedHashMap<Class<? extends Annotation>, Annotation>();
        private final Map<String, Map<Class<? extends Annotation>, Annotation>> methodAnnotations =
                new LinkedHashMap<String, Map<Class<? extends Annotation>, Annotation>>();

        static AnnotationModel read(byte[] bytecode, ClassLoader loader) {
            final AnnotationModel model = new AnnotationModel();
            final ClassLoader cl = loader == null ? ClassLoader.getSystemClassLoader() : loader;
            ClassReader reader = new ClassReader(bytecode);
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (!visible) return null;
                    return new CollectingAnnotationVisitor(descriptor, cl) {
                        @Override protected void onComplete(Class<? extends Annotation> type, Annotation annotation) {
                            if (type != null && annotation != null) model.classAnnotations.put(type, annotation);
                        }
                    };
                }

                @Override public MethodVisitor visitMethod(int access, final String name, final String descriptor,
                                                           String signature, String[] exceptions) {
                    final String key = name + descriptor;
                    final Map<Class<? extends Annotation>, Annotation> anns =
                            new LinkedHashMap<Class<? extends Annotation>, Annotation>();
                    model.methodAnnotations.put(key, anns);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public AnnotationVisitor visitAnnotation(String annDesc, boolean visible) {
                            if (!visible) return null;
                            return new CollectingAnnotationVisitor(annDesc, cl) {
                                @Override protected void onComplete(Class<? extends Annotation> type, Annotation annotation) {
                                    if (type != null && annotation != null) anns.put(type, annotation);
                                }
                            };
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return model;
        }
    }

    private abstract static class CollectingAnnotationVisitor extends AnnotationVisitor {
        private final String descriptor;
        private final ClassLoader loader;
        private final Map<String, Object> values = new LinkedHashMap<String, Object>();

        CollectingAnnotationVisitor(String descriptor, ClassLoader loader) {
            super(Opcodes.ASM9);
            this.descriptor = descriptor;
            this.loader = loader;
        }

        @Override public void visit(String name, Object value) {
            values.put(name, unwrap(value));
        }

        @Override public void visitEnum(String name, String enumDesc, String value) {
            values.put(name, loadEnum(enumDesc, value));
        }

        @Override public AnnotationVisitor visitArray(final String name) {
            final List<Object> items = new ArrayList<Object>();
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override public void visit(String n, Object value) { items.add(unwrap(value)); }
                @Override public void visitEnum(String n, String enumDesc, String value) {
                    items.add(loadEnum(enumDesc, value));
                }
                @Override public void visitEnd() { values.put(name, items); }
            };
        }

        @Override public AnnotationVisitor visitAnnotation(final String name, final String desc) {
            return new CollectingAnnotationVisitor(desc, loader) {
                @Override protected void onComplete(Class<? extends Annotation> type, Annotation annotation) {
                    values.put(name, annotation);
                }
            };
        }

        @Override public void visitEnd() {
            Class<? extends Annotation> type = loadAnnotationType(descriptor);
            if (type == null) {
                onComplete(null, null);
                return;
            }
            onComplete(type, createAnnotation(type, values));
        }

        protected abstract void onComplete(Class<? extends Annotation> type, Annotation annotation);

        private Class<? extends Annotation> loadAnnotationType(String desc) {
            try {
                String binary = org.objectweb.asm.Type.getType(desc).getClassName();
                Class<?> type = Class.forName(binary, false, loader);
                if (Annotation.class.isAssignableFrom(type)) {
                    @SuppressWarnings("unchecked")
                    Class<? extends Annotation> cast = (Class<? extends Annotation>) type;
                    return cast;
                }
            } catch (Throwable ignored) {
                // annotation type may not be visible
            }
            return null;
        }

        private Object loadEnum(String enumDesc, String value) {
            try {
                String binary = org.objectweb.asm.Type.getType(enumDesc).getClassName();
                Class<?> enumType = Class.forName(binary, false, loader);
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object constant = Enum.valueOf((Class<? extends Enum>) enumType.asSubclass(Enum.class), value);
                return constant;
            } catch (Throwable ignored) {
                return value;
            }
        }

        private static Object unwrap(Object value) {
            if (value instanceof org.objectweb.asm.Type) {
                return ((org.objectweb.asm.Type) value).getClassName();
            }
            return value;
        }
    }

    @SuppressWarnings("unchecked")
    private static Annotation createAnnotation(final Class<? extends Annotation> type,
                                               final Map<String, Object> rawValues) {
        final Map<String, Object> values = new LinkedHashMap<String, Object>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.getParameterTypes().length != 0) continue;
            Object value = rawValues.get(method.getName());
            if (value == null) {
                value = method.getDefaultValue();
            } else {
                value = coerce(value, method.getReturnType());
            }
            values.put(method.getName(), value);
        }
        return (Annotation) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("annotationType".equals(name)) return type;
                        if ("hashCode".equals(name)) return annotationHash(type, values);
                        if ("equals".equals(name) && args != null && args.length == 1) {
                            return annotationEquals(type, values, args[0]);
                        }
                        if ("toString".equals(name)) return "@" + type.getName() + values;
                        if (values.containsKey(name)) return values.get(name);
                        return method.getDefaultValue();
                    }
                });
    }

    private static Object coerce(Object value, Class<?> expected) {
        if (value == null) return null;
        if (expected.isInstance(value)) return value;
        if (expected.isArray() && value instanceof List) {
            List<?> list = (List<?>) value;
            Class<?> component = expected.getComponentType();
            Object array = Array.newInstance(component, list.size());
            for (int i = 0; i < list.size(); i++) {
                Array.set(array, i, coerce(list.get(i), component));
            }
            return array;
        }
        if (expected == Class.class && value instanceof String) {
            try {
                return Class.forName((String) value, false, expected.getClassLoader());
            } catch (Throwable ignored) {
                return value;
            }
        }
        return value;
    }

    private static int annotationHash(Class<?> type, Map<String, Object> values) {
        int hash = 0;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object value = entry.getValue();
            int memberHash = value == null ? 0 : (value.getClass().isArray() ? Arrays.deepHashCode(new Object[]{value}) : value.hashCode());
            hash += (127 * entry.getKey().hashCode()) ^ memberHash;
        }
        return hash;
    }

    private static boolean annotationEquals(Class<?> type, Map<String, Object> values, Object other) {
        if (!(other instanceof Annotation)) return false;
        Annotation annotation = (Annotation) other;
        if (!type.equals(annotation.annotationType())) return false;
        try {
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                Method method = type.getMethod(entry.getKey());
                Object left = entry.getValue();
                Object right = method.invoke(annotation);
                if (left == null ? right != null : !leftEquals(left, right)) return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean leftEquals(Object left, Object right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        if (left.getClass().isArray()) return Arrays.deepEquals(new Object[]{left}, new Object[]{right});
        return left.equals(right);
    }
}

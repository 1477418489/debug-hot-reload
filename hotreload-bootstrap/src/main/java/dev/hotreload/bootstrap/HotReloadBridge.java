package dev.hotreload.bootstrap;

import java.lang.annotation.Annotation;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;

public final class HotReloadBridge {
    public static final int API_VERSION = 1;
    private static final int MAX_OWNED_IDS = 20_000;
    private static final String[] TRACKED_MAPS = {
            "mappedStatements", "resultMaps", "parameterMaps", "keyGenerators",
            "sqlFragments", "caches", "cacheRefMap"
    };

    private static final Object MONITOR = new Object();
    private static final ReferenceQueue<Object> COLLECTED = new ReferenceQueue<Object>();
    private static final Map<IdentityWeakReference, ConfigurationState> CONFIGURATIONS =
            new HashMap<IdentityWeakReference, ConfigurationState>();
    private static volatile boolean active = true;

    /**
     * Latest bytecode annotation view, keyed by class binary name.
     * Values: methodKey (name+descriptor) -> annotation binary name -> attribute map.
     */
    private static final ConcurrentHashMap<String, Map<String, Map<String, Map<String, String>>>> METHOD_ANNOTATIONS =
            new ConcurrentHashMap<String, Map<String, Map<String, Map<String, String>>>>();
    private static final ConcurrentHashMap<String, Map<String, Map<String, String>>> CLASS_ANNOTATIONS =
            new ConcurrentHashMap<String, Map<String, Map<String, String>>>();

    private HotReloadBridge() {
    }

    public static int apiVersion() { return API_VERSION; }

    public static void activate() {
        synchronized (MONITOR) {
            CONFIGURATIONS.clear();
            METHOD_ANNOTATIONS.clear();
            CLASS_ANNOTATIONS.clear();
            drainCollected();
            active = true;
        }
    }

    public static void deactivate() {
        synchronized (MONITOR) {
            active = false;
            CONFIGURATIONS.clear();
            METHOD_ANNOTATIONS.clear();
            CLASS_ANNOTATIONS.clear();
            drainCollected();
        }
    }

    public static void registerConfiguration(Object configuration, Class<?> factoryClass) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(factoryClass, "factoryClass");
        synchronized (MONITOR) {
            if (!active) return;
            purgeCollected();
            IdentityWeakReference lookup = new IdentityWeakReference(configuration);
            ConfigurationState existing = CONFIGURATIONS.get(lookup);
            if (existing == null) {
                CONFIGURATIONS.put(new IdentityWeakReference(configuration, COLLECTED),
                        new ConfigurationState(factoryClass.getName(), true));
            } else {
                existing.activate(factoryClass.getName());
            }
        }
    }

    public static void unregisterConfiguration(Object configuration) {
        if (configuration == null) return;
        synchronized (MONITOR) {
            purgeCollected();
            CONFIGURATIONS.remove(new IdentityWeakReference(configuration));
        }
    }

    public static List<ConfigurationHandle> snapshotConfigurations() {
        synchronized (MONITOR) {
            if (!active) return Collections.emptyList();
            purgeCollected();
            List<ConfigurationHandle> handles = new ArrayList<ConfigurationHandle>(CONFIGURATIONS.size());
            for (Map.Entry<IdentityWeakReference, ConfigurationState> entry : CONFIGURATIONS.entrySet()) {
                Object configuration = entry.getKey().get();
                if (configuration != null && entry.getValue().isActive()) {
                    handles.add(new ConfigurationHandle(configuration, entry.getValue()));
                }
            }
            return Collections.unmodifiableList(handles);
        }
    }

    // ---- runtime annotation index (used by Spring/Method advice) ----

    public static void replaceClassAnnotations(String className,
                                               Map<String, Map<String, String>> classAnnotations,
                                               Map<String, Map<String, Map<String, String>>> methodAnnotations) {
        if (className == null || className.isEmpty()) return;
        if (classAnnotations == null) CLASS_ANNOTATIONS.remove(className);
        else CLASS_ANNOTATIONS.put(className, freezeAttrs(classAnnotations));
        if (methodAnnotations == null) METHOD_ANNOTATIONS.remove(className);
        else METHOD_ANNOTATIONS.put(className, freezeMethods(methodAnnotations));
    }

    public static boolean hasIndexedClass(String className) {
        return className != null && (METHOD_ANNOTATIONS.containsKey(className) || CLASS_ANNOTATIONS.containsKey(className));
    }

    public static Boolean hasMethodAnnotation(String className, String methodKey, String annotationTypeName) {
        if (className == null || methodKey == null || annotationTypeName == null) return null;
        Map<String, Map<String, Map<String, String>>> methods = METHOD_ANNOTATIONS.get(className);
        if (methods == null) return null;
        Map<String, Map<String, String>> anns = methods.get(methodKey);
        if (anns == null) return Boolean.FALSE;
        return anns.containsKey(annotationTypeName) ? Boolean.TRUE : Boolean.FALSE;
    }

    public static Map<String, String> getMethodAnnotationAttributes(String className, String methodKey,
                                                                    String annotationTypeName) {
        if (className == null || methodKey == null || annotationTypeName == null) return null;
        Map<String, Map<String, Map<String, String>>> methods = METHOD_ANNOTATIONS.get(className);
        if (methods == null) return null;
        Map<String, Map<String, String>> anns = methods.get(methodKey);
        if (anns == null) return Collections.emptyMap();
        Map<String, String> attrs = anns.get(annotationTypeName);
        return attrs == null ? null : attrs;
    }

    public static Annotation resolveMethodAnnotation(Method method, Class<? extends Annotation> annotationType) {
        if (method == null || annotationType == null) return null;
        Class<?> owner = userClass(method.getDeclaringClass());
        if (owner == null) return null;
        String className = owner.getName();
        if (!hasIndexedClass(className)) {
            // Also try the original declaring class name in case index used raw type.
            Class<?> raw = method.getDeclaringClass();
            if (raw != null && hasIndexedClass(raw.getName())) {
                owner = raw;
                className = raw.getName();
            } else {
                return null; // no override for this class
            }
        }
        String methodKey = method.getName() + orgDescriptor(method);
        Boolean present = hasMethodAnnotation(className, methodKey, annotationType.getName());
        if (present == null) {
            // method key might differ for bridge/synthetic methods; try by name only for bridge/synthetic method checks
            present = hasMethodAnnotationByName(className, method.getName(), annotationType.getName());
        }
        if (present == null) return null;
        if (!present.booleanValue()) {
            return AbsentAnnotation.INSTANCE;
        }
        Map<String, String> attrs = getMethodAnnotationAttributes(className, methodKey, annotationType.getName());
        if (attrs == null) {
            attrs = getMethodAnnotationAttributesByName(className, method.getName(), annotationType.getName());
        }
        return createAnnotationProxy(annotationType, attrs);
    }

    public static Boolean hasMethodAnnotationByName(String className, String methodName, String annotationTypeName) {
        if (className == null || methodName == null || annotationTypeName == null) return null;
        Map<String, Map<String, Map<String, String>>> methods = METHOD_ANNOTATIONS.get(className);
        if (methods == null) return null;
        boolean any = false;
        for (Map.Entry<String, Map<String, Map<String, String>>> entry : methods.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.startsWith(methodName + "(")) continue;
            any = true;
            if (entry.getValue() != null && entry.getValue().containsKey(annotationTypeName)) {
                return Boolean.TRUE;
            }
        }
        // any=true means method name exists without the annotation; any=false means unknown name.
        return any ? Boolean.FALSE : null;
    }

    public static Map<String, String> getMethodAnnotationAttributesByName(String className, String methodName,
                                                                           String annotationTypeName) {
        if (className == null || methodName == null || annotationTypeName == null) return null;
        Map<String, Map<String, Map<String, String>>> methods = METHOD_ANNOTATIONS.get(className);
        if (methods == null) return null;
        for (Map.Entry<String, Map<String, Map<String, String>>> entry : methods.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.startsWith(methodName + "(")) continue;
            if (entry.getValue() == null) continue;
            Map<String, String> attrs = entry.getValue().get(annotationTypeName);
            if (attrs != null) return attrs;
        }
        return null;
    }

    private static Class<?> userClass(Class<?> type) {
        if (type == null) return null;
        Class<?> current = type;
        while (current != null) {
            String name = current.getName();
            if (name.contains("$$") || name.contains("CGLIB") || name.contains("$ByteBuddy$") || name.contains("$Proxy")) {
                current = current.getSuperclass();
                continue;
            }
            return current;
        }
        return type;
    }

    /**
     * Returns declared annotations for a method from the bytecode index.
     * null means "no override for this class" so callers keep original reflection.
     * Empty array means "intentionally no annotations after hot reload".
     */
    public static Annotation[] resolveDeclaredAnnotations(Method method) {
        if (method == null) return null;
        Class<?> owner = userClass(method.getDeclaringClass());
        if (owner == null) return null;
        String className = owner.getName();
        if (!hasIndexedClass(className)) {
            Class<?> raw = method.getDeclaringClass();
            if (raw != null && hasIndexedClass(raw.getName())) {
                owner = raw;
                className = raw.getName();
            } else {
                return null;
            }
        }
        Map<String, Map<String, Map<String, String>>> methods = METHOD_ANNOTATIONS.get(className);
        if (methods == null) return null;
        String methodKey = method.getName() + orgDescriptor(method);
        Map<String, Map<String, String>> byType = methods.get(methodKey);
        if (byType == null) {
            // Fallback by name for bridge/synthetic methods.
            for (Map.Entry<String, Map<String, Map<String, String>>> entry : methods.entrySet()) {
                String key = entry.getKey();
                if (key != null && key.startsWith(method.getName() + "(")) {
                    byType = entry.getValue();
                    break;
                }
            }
        }
        if (byType == null) {
            // Indexed class, method present or not: empty means no annotations.
            return new Annotation[0];
        }
        List<Annotation> out = new ArrayList<Annotation>(byType.size());
        for (Map.Entry<String, Map<String, String>> entry : byType.entrySet()) {
            String typeName = entry.getKey();
            if (typeName == null) continue;
            try {
                Class<?> annType = Class.forName(typeName, false, owner.getClassLoader());
                if (!Annotation.class.isAssignableFrom(annType)) continue;
                @SuppressWarnings("unchecked")
                Class<? extends Annotation> cast = (Class<? extends Annotation>) annType;
                Annotation proxy = createAnnotationProxy(cast, entry.getValue());
                if (proxy != null && !isAbsentMarker(proxy)) out.add(proxy);
            } catch (Throwable ignored) {
                // Annotation type may not be visible in this loader.
            }
        }
        return out.toArray(new Annotation[out.size()]);
    }

    public static boolean isAbsentMarker(Object annotation) {
        return annotation == AbsentAnnotation.INSTANCE;
    }

    public static Object consumeAbsentMarker(Object annotation) {
        return isAbsentMarker(annotation) ? null : annotation;
    }

    private static String orgDescriptor(Method method) {
        StringBuilder builder = new StringBuilder(64);
        builder.append('(');
        for (Class<?> param : method.getParameterTypes()) {
            builder.append(typeDescriptor(param));
        }
        builder.append(')');
        builder.append(typeDescriptor(method.getReturnType()));
        return builder.toString();
    }

    private static String typeDescriptor(Class<?> type) {
        if (type.isPrimitive()) {
            if (type == Void.TYPE) return "V";
            if (type == Integer.TYPE) return "I";
            if (type == Boolean.TYPE) return "Z";
            if (type == Byte.TYPE) return "B";
            if (type == Character.TYPE) return "C";
            if (type == Short.TYPE) return "S";
            if (type == Long.TYPE) return "J";
            if (type == Float.TYPE) return "F";
            if (type == Double.TYPE) return "D";
        }
        if (type.isArray()) {
            // Class.getName for arrays is already close to descriptor but uses dots in element names.
            return type.getName().replace('.', '/');
        }
        return "L" + type.getName().replace('.', '/') + ";";
    }

    private static Map<String, Map<String, String>> freezeAttrs(Map<String, Map<String, String>> input) {
        Map<String, Map<String, String>> copy = new LinkedHashMap<String, Map<String, String>>();
        for (Map.Entry<String, Map<String, String>> entry : input.entrySet()) {
            Map<String, String> attrs = entry.getValue() == null
                    ? Collections.<String, String>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<String, String>(entry.getValue()));
            copy.put(entry.getKey(), attrs);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, Map<String, Map<String, String>>> freezeMethods(
            Map<String, Map<String, Map<String, String>>> input) {
        Map<String, Map<String, Map<String, String>>> copy =
                new LinkedHashMap<String, Map<String, Map<String, String>>>();
        for (Map.Entry<String, Map<String, Map<String, String>>> methodEntry : input.entrySet()) {
            Map<String, Map<String, String>> anns = new LinkedHashMap<String, Map<String, String>>();
            if (methodEntry.getValue() != null) {
                for (Map.Entry<String, Map<String, String>> annEntry : methodEntry.getValue().entrySet()) {
                    Map<String, String> attrs = annEntry.getValue() == null
                            ? Collections.<String, String>emptyMap()
                            : Collections.unmodifiableMap(new LinkedHashMap<String, String>(annEntry.getValue()));
                    anns.put(annEntry.getKey(), attrs);
                }
            }
            copy.put(methodEntry.getKey(), Collections.unmodifiableMap(anns));
        }
        return Collections.unmodifiableMap(copy);
    }

    @SuppressWarnings("unchecked")
    private static Annotation createAnnotationProxy(final Class<? extends Annotation> type,
                                                    final Map<String, String> attrs) {
        final Map<String, Object> values = new LinkedHashMap<String, Object>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.getParameterTypes().length != 0) continue;
            Object value = null;
            if (attrs != null && attrs.containsKey(method.getName())) {
                value = coerceAttribute(attrs.get(method.getName()), method.getReturnType());
            }
            if (value == null) value = method.getDefaultValue();
            values.put(method.getName(), value);
        }
        return (Annotation) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("annotationType".equals(name)) return type;
                        if ("hashCode".equals(name)) return Integer.valueOf(annotationHash(values));
                        if ("toString".equals(name)) return "@" + type.getName() + values;
                        if ("equals".equals(name) && args != null && args.length == 1) {
                            return Boolean.valueOf(annotationEquals(type, values, args[0]));
                        }
                        if (values.containsKey(name)) return values.get(name);
                        return method.getDefaultValue();
                    }
                });
    }

    /** Annotation 规范 hashCode：sum of (127 * memberName.hashCode()) ^ memberValue.hashCode()。 */
    private static int annotationHash(Map<String, Object> values) {
        int hash = 0;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object value = entry.getValue();
            int memberHash;
            if (value == null) {
                memberHash = 0;
            } else if (value.getClass().isArray()) {
                memberHash = java.util.Arrays.deepHashCode(new Object[]{value});
            } else {
                memberHash = value.hashCode();
            }
            hash += (127 * entry.getKey().hashCode()) ^ memberHash;
        }
        return hash;
    }

    /** Annotation 规范 equals：同 annotationType 且逐成员值相等，而非仅比较类型。 */
    private static boolean annotationEquals(Class<? extends Annotation> type,
                                            Map<String, Object> values, Object other) {
        if (!(other instanceof Annotation)) return false;
        Annotation annotation = (Annotation) other;
        if (!type.equals(annotation.annotationType())) return false;
        try {
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                Method member = type.getMethod(entry.getKey());
                Object left = entry.getValue();
                Object right = member.invoke(annotation);
                if (left == null ? right != null : !memberEquals(left, right)) return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean memberEquals(Object left, Object right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        if (left.getClass().isArray()) {
            return java.util.Arrays.deepEquals(new Object[]{left}, new Object[]{right});
        }
        return left.equals(right);
    }

    private static Object coerceAttribute(String raw, Class<?> expected) {
        if (raw == null) return null;
        if (expected == String.class) return raw;
        if (expected == Boolean.TYPE || expected == Boolean.class) return Boolean.valueOf(raw);
        if (expected == Integer.TYPE || expected == Integer.class) {
            try { return Integer.valueOf(raw); } catch (NumberFormatException e) { return 0; }
        }
        if (expected == Long.TYPE || expected == Long.class) {
            try { return Long.valueOf(raw); } catch (NumberFormatException e) { return 0L; }
        }
        if (expected.isEnum()) {
            try {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object value = Enum.valueOf((Class<? extends Enum>) expected.asSubclass(Enum.class), raw);
                return value;
            } catch (Throwable ignored) {
                return null;
            }
        }
        if (expected.isArray() && expected.getComponentType() == String.class) {
            if (raw.isEmpty()) return new String[0];
            return raw.split(",");
        }
        return raw;
    }

    /** Marker meaning "annotation intentionally absent after hot reload". */
    public enum AbsentAnnotation implements Annotation {
        INSTANCE;
        public Class<? extends Annotation> annotationType() { return Annotation.class; }
    }

    public static Object enterRead(Object configuration) {
        ConfigurationState state = find(configuration);
        if (state == null) return null;
        Lock lock = state.getLock().readLock();
        lock.lock();
        return new ReadLockToken(lock);
    }

    public static void exitRead(Object token) {
        if (token instanceof ReadLockToken) ((ReadLockToken) token).release();
    }

    public static WriteLockToken enterWrite(Object configuration, long timeoutMillis) {
        if (timeoutMillis < 0) throw new IllegalArgumentException("timeoutMillis must not be negative");
        ConfigurationState state = find(configuration);
        if (state == null) return null;
        Lock lock = state.getLock().writeLock();
        try {
            return lock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS) ? new WriteLockToken(lock) : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public static void exitWrite(WriteLockToken token) {
        if (token != null) token.release();
    }

    public static void markReloadUnsafe(Object configuration) {
        ConfigurationState state = find(configuration);
        if (state != null) state.markReloadUnsafe();
    }

    public static Object beginMapperParse(Object configuration, Object parser, String runtimeResource) {
        if (configuration == null || parser == null || runtimeResource == null || runtimeResource.isEmpty()) return null;
        ConfigurationState state = findOrCreate(configuration);
        if (state == null) return null;
        Lock lock = state.getLock().writeLock();
        lock.lock();
        try {
            Map<String, Map<String, Object>> before = snapshotMapEntries(configuration);
            String copiedResource = new String(runtimeResource);
            String resourceId = RuntimeResourceId.normalize(copiedResource);
            if (resourceId == null) {
                state.markReloadUnsafe();
                lock.unlock();
                return null;
            }
            return new MapperParseToken(state, configuration, parser, copiedResource,
                    resourceId, before, lock);
        } catch (Exception failure) {
            state.markReloadUnsafe();
            lock.unlock();
            return null;
        } catch (LinkageError failure) {
            state.markReloadUnsafe();
            lock.unlock();
            return null;
        }
    }

    public static boolean endMapperParse(Object token, boolean success) {
        if (!(token instanceof MapperParseToken)) return false;
        MapperParseToken parse = (MapperParseToken) token;
        if (!parse.ended.compareAndSet(false, true)) return parse.published;
        boolean published = false;
        try {
            if (!success) return false;
            Map<String, Map<String, Object>> after = snapshotMapEntries(parse.configuration);
            Map<String, Map<String, Object>> owned = new LinkedHashMap<String, Map<String, Object>>();
            int total = 0;
            for (String mapName : TRACKED_MAPS) {
                Map<String, Object> difference = new LinkedHashMap<String, Object>(after.get(mapName));
                difference.keySet().removeAll(parse.before.get(mapName).keySet());
                total += difference.size();
                if (total > MAX_OWNED_IDS) {
                    parse.state.markReloadUnsafe();
                    return false;
                }
                owned.put(mapName, difference);
            }
            String namespace = stringField(fieldValue(fieldValue(parse.parser, "builderAssistant"),
                    "currentNamespace"));
            if (namespace == null || namespace.isEmpty()) {
                parse.state.markReloadUnsafe();
                return false;
            }
            ResourceMetadata previous = parse.state.getResourceMetadata(parse.runtimeResource);
            long version = previous == null ? 0L : previous.getVersion() + 1L;
            ResourceMetadata metadata = new ResourceMetadata(parse.runtimeResource, parse.resourceId,
                    parse.parser.getClass().getName(), namespace, owned, null, version);
            published = parse.state.putResourceMetadata(metadata)
                    && parse.state.getResourceMetadata(parse.runtimeResource) == metadata;
            if (!published) parse.state.markReloadUnsafe();
            return published;
        } catch (Exception failure) {
            parse.state.markReloadUnsafe();
            return false;
        } catch (LinkageError failure) {
            parse.state.markReloadUnsafe();
            return false;
        } finally {
            parse.lock.unlock();
            parse.published = published;
        }
    }

    public static boolean updateResourceSha256(Object configuration, String runtimeResource, byte[] sha256) {
        if (sha256 == null || sha256.length != 32) throw new IllegalArgumentException("sha256 must contain 32 bytes");
        ConfigurationState state = find(configuration);
        if (state == null) return false;
        ResourceMetadata metadata = state.getResourceMetadata(runtimeResource);
        if (metadata == null) return false;
        ResourceMetadata updated = metadata.withSha256(sha256);
        return state.putResourceMetadata(updated) && state.getResourceMetadata(runtimeResource) == updated;
    }

    public static boolean restoreResourceMetadata(Object configuration, ResourceMetadata metadata) {
        ConfigurationState state = find(configuration);
        return state != null && metadata != null && state.putResourceMetadata(metadata)
                && state.getResourceMetadata(metadata.getRuntimeResource()) == metadata;
    }

    private static ConfigurationState find(Object configuration) {
        if (configuration == null || !active) return null;
        synchronized (MONITOR) {
            if (!active) return null;
            purgeCollected();
            return CONFIGURATIONS.get(new IdentityWeakReference(configuration));
        }
    }

    private static ConfigurationState findOrCreate(Object configuration) {
        if (!active) return null;
        synchronized (MONITOR) {
            if (!active) return null;
            purgeCollected();
            IdentityWeakReference lookup = new IdentityWeakReference(configuration);
            ConfigurationState state = CONFIGURATIONS.get(lookup);
            if (state == null) {
                state = new ConfigurationState("", false);
                CONFIGURATIONS.put(new IdentityWeakReference(configuration, COLLECTED), state);
            }
            return state;
        }
    }

    private static Map<String, Map<String, Object>> snapshotMapEntries(Object configuration) throws Exception {
        Map<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>();
        for (String mapName : TRACKED_MAPS) {
            Object value = fieldValue(configuration, mapName);
            if (!(value instanceof Map)) throw new IllegalStateException("Missing map: " + mapName);
            Map<?, ?> source = (Map<?, ?>) value;
            Map<String, Object> entries = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                Object key = entry.getKey();
                if (!(key instanceof String)) continue;
                String id = (String) key;
                if (!"cacheRefMap".equals(mapName)
                        && isStrictMapAlias(source, id, entry.getValue())) continue;
                entries.put(new String(id), entry.getValue());
            }
            result.put(mapName, entries);
        }
        return result;
    }

    private static boolean isStrictMapAlias(Map<?, ?> source, String key, Object shortValue) {
        if (key.indexOf('.') >= 0) return false;
        String suffix = "." + key;
        boolean foundQualifiedKey = false;
        for (Map.Entry<?, ?> candidate : source.entrySet()) {
            Object candidateKey = candidate.getKey();
            if (!(candidateKey instanceof String)
                    || !((String) candidateKey).endsWith(suffix)) continue;
            foundQualifiedKey = true;
            // StrictMap aliases retain the same object as their qualified entry. A distinct
            // value is a real dotless namespace and must remain in ownership metadata.
            if (candidate.getValue() == shortValue) return true;
        }
        if (!foundQualifiedKey || shortValue == null) return false;
        return shortValue.getClass().getName().endsWith("$StrictMap$Ambiguity");
    }

    private static Object fieldValue(Object target, String name) throws Exception {
        if (target == null) return null;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                if (!field.isAccessible()) field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static String stringField(Object value) {
        return value instanceof String ? new String((String) value) : null;
    }

    private static void purgeCollected() {
        IdentityWeakReference reference;
        while ((reference = (IdentityWeakReference) COLLECTED.poll()) != null) {
            CONFIGURATIONS.remove(reference);
        }
    }

    private static void drainCollected() {
        while (COLLECTED.poll() != null) {
            // The registry is already empty while activation state changes.
        }
    }

    private static final class ReadLockToken {
        private final Lock lock;
        private final AtomicBoolean released = new AtomicBoolean();

        private ReadLockToken(Lock lock) { this.lock = lock; }

        private void release() {
            if (released.compareAndSet(false, true)) lock.unlock();
        }
    }

    private static final class MapperParseToken {
        private final ConfigurationState state;
        private final Object configuration;
        private final Object parser;
        private final String runtimeResource;
        private final String resourceId;
        private final Map<String, Map<String, Object>> before;
        private final Lock lock;
        private final AtomicBoolean ended = new AtomicBoolean();
        private volatile boolean published;

        private MapperParseToken(ConfigurationState state, Object configuration, Object parser,
                                 String runtimeResource, String resourceId,
                                 Map<String, Map<String, Object>> before, Lock lock) {
            this.state = state;
            this.configuration = configuration;
            this.parser = parser;
            this.runtimeResource = runtimeResource;
            this.resourceId = resourceId;
            this.before = before;
            this.lock = lock;
        }
    }

    private static final class IdentityWeakReference extends WeakReference<Object> {
        private final int identityHashCode;

        private IdentityWeakReference(Object referent) {
            super(referent);
            this.identityHashCode = System.identityHashCode(referent);
        }

        private IdentityWeakReference(Object referent, ReferenceQueue<Object> queue) {
            super(referent, queue);
            this.identityHashCode = System.identityHashCode(referent);
        }

        @Override public int hashCode() { return identityHashCode; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityWeakReference)) return false;
            Object referent = get();
            return referent != null && referent == ((IdentityWeakReference) other).get();
        }
    }
}

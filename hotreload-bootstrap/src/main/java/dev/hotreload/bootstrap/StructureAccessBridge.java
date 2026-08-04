package dev.hotreload.bootstrap;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bootstrap-visible reflection bridge so generation subclasses can touch private/package members.
 * Lives on the bootstrap classloader (via agent bootstrap jar), so app classes can always call it.
 */
public final class StructureAccessBridge {
    /** 上限远高于正常热更会话用量；触顶整体清空重建，防止历代 generation 类被钉住。 */
    private static final int MAX_CACHE_ENTRIES = 8192;
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<String, Field>();
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<String, Method>();

    private StructureAccessBridge() { }

    public static Object getField(Object target, String ownerBinary, String fieldName) {
        try {
            return resolveField(target, ownerBinary, fieldName).get(target);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("hr_getField:" + ownerBinary + "." + fieldName, e);
        }
    }

    public static void setFieldValueFirst(Object value, Object target, String ownerBinary, String fieldName) {
        setField(target, ownerBinary, fieldName, value);
    }

    public static void setField(Object target, String ownerBinary, String fieldName, Object value) {
        try {
            resolveField(target, ownerBinary, fieldName).set(target, value);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("hr_setField:" + ownerBinary + "." + fieldName, e);
        }
    }

    public static boolean getBoolean(Object target, String ownerBinary, String fieldName) {
        Object value = getField(target, ownerBinary, fieldName);
        return value != null && (Boolean) value;
    }

    public static void setBoolean(Object target, String ownerBinary, String fieldName, boolean value) {
        setField(target, ownerBinary, fieldName, Boolean.valueOf(value));
    }

    public static byte getByte(Object target, String ownerBinary, String fieldName) {
        Object value = getField(target, ownerBinary, fieldName);
        return value == null ? 0 : ((Number) value).byteValue();
    }

    public static void setByte(Object target, String ownerBinary, String fieldName, byte value) {
        setField(target, ownerBinary, fieldName, Byte.valueOf(value));
    }

    public static short getShort(Object target, String ownerBinary, String fieldName) {
        Object value = getField(target, ownerBinary, fieldName);
        return value == null ? 0 : ((Number) value).shortValue();
    }

    public static void setShort(Object target, String ownerBinary, String fieldName, short value) {
        setField(target, ownerBinary, fieldName, Short.valueOf(value));
    }

    public static char getChar(Object target, String ownerBinary, String fieldName) {
        Object value = getField(target, ownerBinary, fieldName);
        return value == null ? 0 : (Character) value;
    }

    public static void setChar(Object target, String ownerBinary, String fieldName, char value) {
        setField(target, ownerBinary, fieldName, Character.valueOf(value));
    }

    public static int getInt(Object target, String ownerBinary, String fieldName) {
        Object value = getField(target, ownerBinary, fieldName);
        return value == null ? 0 : ((Number) value).intValue();
    }

    public static void setInt(Object target, String ownerBinary, String fieldName, int value) {
        setField(target, ownerBinary, fieldName, Integer.valueOf(value));
    }

    public static long getLong(Object target, String ownerBinary, String fieldName) {
        Object value = getField(target, ownerBinary, fieldName);
        return value == null ? 0L : ((Number) value).longValue();
    }

    public static void setLong(Object target, String ownerBinary, String fieldName, long value) {
        setField(target, ownerBinary, fieldName, Long.valueOf(value));
    }

    public static float getFloat(Object target, String ownerBinary, String fieldName) {
        Object value = getField(target, ownerBinary, fieldName);
        return value == null ? 0F : ((Number) value).floatValue();
    }

    public static void setFloat(Object target, String ownerBinary, String fieldName, float value) {
        setField(target, ownerBinary, fieldName, Float.valueOf(value));
    }

    public static double getDouble(Object target, String ownerBinary, String fieldName) {
        Object value = getField(target, ownerBinary, fieldName);
        return value == null ? 0D : ((Number) value).doubleValue();
    }

    public static void setDouble(Object target, String ownerBinary, String fieldName, double value) {
        setField(target, ownerBinary, fieldName, Double.valueOf(value));
    }

    public static Object invoke(Object target, String ownerBinary, String methodName, String descriptor, Object[] args) {
        try {
            Method method = resolveMethod(target, ownerBinary, methodName, descriptor);
            return method.invoke(target, args == null ? new Object[0] : args);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("hr_invoke:" + ownerBinary + "." + methodName + descriptor, e);
        }
    }

    private static Field resolveField(Object target, String ownerBinary, String fieldName) throws Exception {
        String key = ownerBinary + "#" + fieldName;
        Field cached = FIELDS.get(key);
        // 跨 generation 重验证：字段可能缓存自旧的 __HrGenN 兄弟类（shadow 声明），
        // 对新 generation 实例 get/set 会抛 IllegalArgumentException，须重新解析覆盖。
        if (cached != null && appliesTo(cached.getDeclaringClass(), target)) {
            return cached;
        }
        Field found = findField(target, ownerBinary, fieldName);
        if (found == null) {
            throw new NoSuchFieldException(key);
        }
        found.setAccessible(true);
        trimIfNeeded(FIELDS);
        FIELDS.put(key, found);
        return found;
    }

    private static boolean appliesTo(Class<?> declaringClass, Object target) {
        return target == null || declaringClass.isInstance(target);
    }

    private static void trimIfNeeded(Map<?, ?> cache) {
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
    }

    private static Field findField(Object target, String ownerBinary, String fieldName) {
        // Prefer walking the live instance hierarchy (handles __HrGen / CGLIB wrappers).
        if (target != null) {
            Class<?> cur = target.getClass();
            while (cur != null && cur != Object.class) {
                try {
                    Field field = cur.getDeclaredField(fieldName);
                    return field;
                } catch (NoSuchFieldException ignored) {
                    cur = cur.getSuperclass();
                }
            }
        }
        Class<?> type = loadOwner(target, ownerBinary);
        Class<?> cur = type;
        while (cur != null && cur != Object.class) {
            try {
                return cur.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    private static Method resolveMethod(Object target, String ownerBinary, String methodName, String descriptor)
            throws Exception {
        String key = ownerBinary + "#" + methodName + descriptor;
        Method cached = METHODS.get(key);
        // 与 resolveField 相同的跨 generation 重验证。
        if (cached != null && appliesTo(cached.getDeclaringClass(), target)) {
            return cached;
        }
        Class<?> type = loadOwner(target, ownerBinary);
        ClassLoader loader = type.getClassLoader();
        Class<?>[] params = parseArgumentTypes(descriptor, loader);
        Method found = null;
        Class<?> cur = type;
        while (cur != null && cur != Object.class) {
            for (Method candidate : cur.getDeclaredMethods()) {
                if (!candidate.getName().equals(methodName)) {
                    continue;
                }
                if (Arrays.equals(candidate.getParameterTypes(), params)) {
                    found = candidate;
                    break;
                }
            }
            if (found != null) {
                break;
            }
            cur = cur.getSuperclass();
        }
        if (found == null && target != null) {
            cur = target.getClass();
            while (cur != null && cur != Object.class) {
                for (Method candidate : cur.getDeclaredMethods()) {
                    if (!candidate.getName().equals(methodName)) {
                        continue;
                    }
                    if (Arrays.equals(candidate.getParameterTypes(), params)) {
                        found = candidate;
                        break;
                    }
                }
                if (found != null) {
                    break;
                }
                cur = cur.getSuperclass();
            }
        }
        if (found == null) {
            throw new NoSuchMethodException(key);
        }
        found.setAccessible(true);
        trimIfNeeded(METHODS);
        METHODS.put(key, found);
        return found;
    }

    private static Class<?> loadOwner(Object target, String ownerBinary) {
        if (target != null) {
            Class<?> cur = target.getClass();
            while (cur != null) {
                if (ownerBinary.equals(cur.getName()) || stripGeneration(cur.getName()).equals(ownerBinary)) {
                    return cur;
                }
                cur = cur.getSuperclass();
            }
        }
        ClassLoader[] loaders = new ClassLoader[] {
                target != null ? target.getClass().getClassLoader() : null,
                Thread.currentThread().getContextClassLoader(),
                StructureAccessBridge.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (int i = 0; i < loaders.length; i++) {
            ClassLoader loader = loaders[i];
            if (loader == null) {
                continue;
            }
            try {
                return Class.forName(ownerBinary, false, loader);
            } catch (ClassNotFoundException ignored) {
                // try next
            }
        }
        try {
            return Class.forName(ownerBinary);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("hr_owner_missing:" + ownerBinary, e);
        }
    }

    private static String stripGeneration(String name) {
        int idx = name.indexOf("__HrGen");
        if (idx > 0) {
            return name.substring(0, idx);
        }
        idx = name.indexOf("$$HrGen");
        if (idx > 0) {
            return name.substring(0, idx);
        }
        idx = name.indexOf("$$EnhancerBy");
        if (idx > 0) {
            return name.substring(0, idx);
        }
        idx = name.indexOf("$$FastClassBy");
        if (idx > 0) {
            return name.substring(0, idx);
        }
        return name;
    }

    private static Class<?>[] parseArgumentTypes(String descriptor, ClassLoader loader) throws ClassNotFoundException {
        if (descriptor == null || descriptor.length() < 2 || descriptor.charAt(0) != '(') {
            return new Class<?>[0];
        }
        int end = descriptor.indexOf(')');
        if (end < 0) {
            return new Class<?>[0];
        }
        String body = descriptor.substring(1, end);
        java.util.ArrayList<Class<?>> list = new java.util.ArrayList<Class<?>>();
        int i = 0;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c == 'B') { list.add(byte.class); i++; }
            else if (c == 'C') { list.add(char.class); i++; }
            else if (c == 'D') { list.add(double.class); i++; }
            else if (c == 'F') { list.add(float.class); i++; }
            else if (c == 'I') { list.add(int.class); i++; }
            else if (c == 'J') { list.add(long.class); i++; }
            else if (c == 'S') { list.add(short.class); i++; }
            else if (c == 'Z') { list.add(boolean.class); i++; }
            else if (c == 'L') {
                int semi = body.indexOf(';', i);
                String binary = body.substring(i + 1, semi).replace('/', '.');
                list.add(Class.forName(binary, false, loader));
                i = semi + 1;
            } else if (c == '[') {
                int start = i;
                while (i < body.length() && body.charAt(i) == '[') i++;
                if (i < body.length() && body.charAt(i) == 'L') {
                    int semi = body.indexOf(';', i);
                    i = semi + 1;
                } else {
                    i++;
                }
                String desc = body.substring(start, i).replace('/', '.');
                list.add(Class.forName(desc, false, loader));
            } else {
                throw new IllegalArgumentException("bad descriptor token: " + c + " in " + descriptor);
            }
        }
        return list.toArray(new Class<?>[list.size()]);
    }
}

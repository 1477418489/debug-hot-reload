package dev.hotreload.agent.mybatis;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;

/**
 * Best-effort cache invalidation after mapper XML reloads.
 * Works with MyBatis 3.x and MyBatis-Plus without hard-coding project types.
 */
public final class MyBatisCacheInvalidator {
    private MyBatisCacheInvalidator() { }

    public static String invalidate(Object configuration) {
        if (configuration == null) return "cacheInvalidate=none";
        int cachesCleared = 0;
        int localCaches = 0;
        int plusHints = 0;
        try {
            Object caches = invoke(configuration, "getCaches");
            if (caches instanceof Collection) {
                for (Object cache : (Collection<?>) caches) {
                    if (invokeIfPresent(cache, "clear")) {
                        cachesCleared++;
                    }
                }
            } else if (caches instanceof Map) {
                for (Object cache : ((Map<?, ?>) caches).values()) {
                    if (invokeIfPresent(cache, "clear")) {
                        cachesCleared++;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // DefaultSqlSessionFactory localCache may not be reachable; clear mapped statement caches.
        try {
            Object statements = readField(configuration, "mappedStatements");
            if (statements instanceof Map) {
                for (Object value : ((Map<?, ?>) statements).values()) {
                    if (value == null) continue;
                    Object cache = invoke(value, "getCache");
                    if (invokeIfPresent(cache, "clear")) localCaches++;
                }
            }
        } catch (Throwable ignored) {
        }

        // MyBatis-Plus metadata / helper caches (optional).
        ClassLoader loader = configuration.getClass().getClassLoader();
        plusHints += clearStaticMap(loader, "com.baomidou.mybatisplus.core.toolkit.TableInfoHelper", "TABLE_INFO_CACHE");
        plusHints += clearStaticMap(loader, "com.baomidou.mybatisplus.core.toolkit.ReflectionKit", "CLASS_FIELD_CACHE");
        plusHints += clearStaticMap(loader, "com.baomidou.mybatisplus.core.metadata.TableInfoHelper", "TABLE_INFO_CACHE");

        return "cacheInvalidate=caches=" + cachesCleared
                + ",statementCaches=" + localCaches
                + ",plusHints=" + plusHints;
    }

    private static int clearStaticMap(ClassLoader loader, String className, String fieldName) {
        try {
            Class<?> type = Class.forName(className, false, loader);
            Field field = type.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof Map) {
                ((Map<?, ?>) value).clear();
                return 1;
            }
            if (value instanceof Collection) {
                ((Collection<?>) value).clear();
                return 1;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static boolean invokeIfPresent(Object target, String methodName) {
        if (target == null) return false;
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            method.invoke(target);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object invoke(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object readField(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }
}

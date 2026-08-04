package dev.hotreload.agent.classes;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;

/**
 * Clears JDK reflection / annotation caches so HotSpot redefineClasses annotation
 * and method attribute updates become visible to {@code Class}/{@code Method} APIs.
 * Critical on JDK 8 where {@code Class.annotationData} and {@code reflectionData}
 * keep the pre-redefine view until invalidated.
 */
public final class ReflectionDataInvalidator {
    private ReflectionDataInvalidator() { }

    public static int invalidateAll(Collection<Class<?>> types) {
        if (types == null || types.isEmpty()) return 0;
        int count = 0;
        for (Class<?> type : types) {
            if (type == null) continue;
            if (invalidate(type)) count++;
        }
        return count;
    }

    public static boolean invalidate(Class<?> type) {
        if (type == null) return false;
        boolean changed = false;
        changed |= clearInstanceField(type, "reflectionData");
        changed |= clearInstanceField(type, "annotationData");
        changed |= clearInstanceField(type, "annotationType");
        changed |= clearInstanceField(type, "classValueMap");
        changed |= invokeClassClearers(type);
        // Intentionally do NOT clear Method/Field.declaredAnnotations here.
        // On JDK8 redefine, Method.annotations bytes are often stale; clearing the map
        // forces re-parse of those stale bytes and undoes RuntimeAnnotationPatcher.
        return changed;
    }

    private static boolean invokeClassClearers(Class<?> type) {
        boolean changed = false;
        // Some JDK builds expose helpers; ignore if absent.
        for (String name : new String[] {"clearAnnotationData", "resetCaches"}) {
            try {
                Method method = Class.class.getDeclaredMethod(name);
                method.setAccessible(true);
                method.invoke(type);
                changed = true;
            } catch (Throwable ignored) {
                // Best-effort.
            }
        }
        return changed;
    }

    private static boolean clearDeclaredMemberCaches(Class<?> type) {
        boolean changed = false;
        // Force Method/Field objects obtained later to re-resolve annotations.
        try {
            for (Method method : type.getDeclaredMethods()) {
                changed |= clearInstanceField(method, "declaredAnnotations");
                changed |= clearInstanceField(method, "root");
            }
        } catch (Throwable ignored) {
            // class may be in incomplete state
        }
        try {
            for (Field field : type.getDeclaredFields()) {
                changed |= clearInstanceField(field, "declaredAnnotations");
                changed |= clearInstanceField(field, "root");
            }
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            changed |= clearInstanceField(type, "enumConstants");
            changed |= clearInstanceField(type, "enumConstantDirectory");
        } catch (Throwable ignored) {
            // ignore
        }
        return changed;
    }

    private static boolean clearInstanceField(Object target, String fieldName) {
        if (target == null) return false;
        Field field = findField(target.getClass(), fieldName);
        if (field == null) return false;
        try {
            field.setAccessible(true);
            Object current = field.get(target);
            if (current == null) return false;
            field.set(target, null);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Field findField(Class<?> type, String name) {
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
}

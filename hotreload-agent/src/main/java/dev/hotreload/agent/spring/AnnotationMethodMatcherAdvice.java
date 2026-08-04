package dev.hotreload.agent.spring;

import dev.hotreload.bootstrap.HotReloadBridge;
import net.bytebuddy.asm.Advice;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Advice must be fully self-contained: ByteBuddy inlines this into the target matcher class.
 * Do not call private helpers from other classes (causes IllegalAccessError under JDK8).
 */
public final class AnnotationMethodMatcherAdvice {
    private AnnotationMethodMatcherAdvice() { }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.This Object matcher,
                              @Advice.Argument(0) Method method,
                              @Advice.Return(readOnly = false) boolean returned) {
        if (method == null || matcher == null) return;
        Class<? extends Annotation> annotationType = null;
        try {
            Class<?> current = matcher.getClass();
            Field field = null;
            while (current != null && field == null) {
                try {
                    field = current.getDeclaredField("annotationType");
                } catch (NoSuchFieldException ignored) {
                    current = current.getSuperclass();
                }
            }
            if (field != null) {
                field.setAccessible(true);
                Object value = field.get(matcher);
                if (value instanceof Class && Annotation.class.isAssignableFrom((Class<?>) value)) {
                    @SuppressWarnings("unchecked")
                    Class<? extends Annotation> cast = (Class<? extends Annotation>) value;
                    annotationType = cast;
                }
            }
        } catch (Throwable ignored) {
            return;
        }
        if (annotationType == null) return;
        Annotation override = HotReloadBridge.resolveMethodAnnotation(method, annotationType);
        if (override == null) return; // no index for this class
        returned = !HotReloadBridge.isAbsentMarker(override);
    }
}

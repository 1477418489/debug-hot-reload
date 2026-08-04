package dev.hotreload.agent.spring;

import dev.hotreload.bootstrap.HotReloadBridge;
import net.bytebuddy.asm.Advice;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * Overrides Spring AnnotationUtils.findAnnotation/getAnnotation(Method, Class).
 */
public final class FindAnnotationOnMethodAdvice {
    private FindAnnotationOnMethodAdvice() { }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Argument(0) Method method,
                              @Advice.Argument(1) Class<?> annotationType,
                              @Advice.Return(readOnly = false) Object returned) {
        if (method == null || annotationType == null || !Annotation.class.isAssignableFrom(annotationType)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Class<? extends Annotation> type = (Class<? extends Annotation>) annotationType;
        Annotation override = HotReloadBridge.resolveMethodAnnotation(method, type);
        if (override == null) {
            // No index for this class; keep original Spring result.
            return;
        }
        if (HotReloadBridge.isAbsentMarker(override)) {
            returned = null;
            return;
        }
        returned = override;
    }
}

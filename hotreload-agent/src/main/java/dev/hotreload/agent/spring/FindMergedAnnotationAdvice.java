package dev.hotreload.agent.spring;

import dev.hotreload.bootstrap.HotReloadBridge;
import net.bytebuddy.asm.Advice;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;

/**
 * Overrides AnnotatedElementUtils.findMergedAnnotation for Method elements.
 */
public final class FindMergedAnnotationAdvice {
    private FindMergedAnnotationAdvice() { }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Argument(0) AnnotatedElement element,
                              @Advice.Argument(1) Class<?> annotationType,
                              @Advice.Return(readOnly = false) Object returned) {
        if (!(element instanceof Method) || annotationType == null
                || !Annotation.class.isAssignableFrom(annotationType)) {
            return;
        }
        Method method = (Method) element;
        @SuppressWarnings("unchecked")
        Class<? extends Annotation> type = (Class<? extends Annotation>) annotationType;
        Annotation override = HotReloadBridge.resolveMethodAnnotation(method, type);
        if (override == null) return;
        if (HotReloadBridge.isAbsentMarker(override)) {
            returned = null;
            return;
        }
        returned = override;
    }
}

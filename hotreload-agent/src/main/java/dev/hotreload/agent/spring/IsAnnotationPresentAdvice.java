package dev.hotreload.agent.spring;

import dev.hotreload.bootstrap.HotReloadBridge;
import net.bytebuddy.asm.Advice;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;

/**
 * Overrides isAnnotationPresent/hasAnnotation style helpers when the first arg is a Method.
 */
public final class IsAnnotationPresentAdvice {
    private IsAnnotationPresentAdvice() { }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.AllArguments Object[] args,
                              @Advice.Return(readOnly = false) boolean returned) {
        if (args == null || args.length < 2 || !(args[0] instanceof Method) || !(args[1] instanceof Class)) {
            return;
        }
        Method method = (Method) args[0];
        Class<?> annotationType = (Class<?>) args[1];
        if (!Annotation.class.isAssignableFrom(annotationType)) return;
        @SuppressWarnings("unchecked")
        Class<? extends Annotation> type = (Class<? extends Annotation>) annotationType;
        Annotation override = HotReloadBridge.resolveMethodAnnotation(method, type);
        if (override == null) return;
        returned = !HotReloadBridge.isAbsentMarker(override);
    }
}

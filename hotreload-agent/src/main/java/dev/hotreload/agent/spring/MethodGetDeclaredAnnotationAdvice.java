package dev.hotreload.agent.spring;

import dev.hotreload.bootstrap.HotReloadBridge;
import net.bytebuddy.asm.Advice;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

public final class MethodGetDeclaredAnnotationAdvice {
    private MethodGetDeclaredAnnotationAdvice() { }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.This Object self,
                              @Advice.Argument(0) Class<?> annotationType,
                              @Advice.Return(readOnly = false) Object returned) {
        if (!(self instanceof Method) || annotationType == null || !Annotation.class.isAssignableFrom(annotationType)) return;
        Method method = (Method) self;
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

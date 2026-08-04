package dev.hotreload.agent.spring;

import dev.hotreload.bootstrap.HotReloadBridge;
import net.bytebuddy.asm.Advice;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * Forces Method.getDeclaredAnnotations() to follow the latest bytecode index.
 * AspectJ @annotation pointcuts commonly use this API, not getAnnotation(Class).
 */
public final class MethodGetDeclaredAnnotationsAdvice {
    private MethodGetDeclaredAnnotationsAdvice() { }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.This Object self,
                              @Advice.Return(readOnly = false) Object returned) {
        if (!(self instanceof Method)) return;
        Method method = (Method) self;
        Annotation[] override = HotReloadBridge.resolveDeclaredAnnotations(method);
        if (override == null) return;
        returned = override;
    }
}

package dev.hotreload.agent.spring;

import dev.hotreload.bootstrap.HotReloadBridge;
import net.bytebuddy.asm.Advice;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * Forces Method.getAnnotations() to follow the latest bytecode index.
 */
public final class MethodGetAnnotationsAdvice {
    private MethodGetAnnotationsAdvice() { }

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

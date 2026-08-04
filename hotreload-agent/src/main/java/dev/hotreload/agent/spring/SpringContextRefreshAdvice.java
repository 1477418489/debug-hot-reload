package dev.hotreload.agent.spring;

import net.bytebuddy.asm.Advice;

final class SpringContextRefreshAdvice {
    private SpringContextRefreshAdvice() { }

    @Advice.OnMethodExit(suppress = Throwable.class)
    static void onExit(@Advice.This Object applicationContext) {
        SpringContextRegistry.register(applicationContext);
    }
}

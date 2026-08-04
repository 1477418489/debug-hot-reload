package dev.hotreload.agent.instrument;

import dev.hotreload.bootstrap.HotReloadBridge;
import net.bytebuddy.asm.Advice;

final class FactoryConstructorAdvice {
    private FactoryConstructorAdvice() {
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    static void onExit(@Advice.Argument(0) Object configuration, @Advice.This Object factory) {
        HotReloadBridge.registerConfiguration(configuration, factory.getClass());
    }
}

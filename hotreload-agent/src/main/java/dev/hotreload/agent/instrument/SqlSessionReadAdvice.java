package dev.hotreload.agent.instrument;

import dev.hotreload.bootstrap.HotReloadBridge;
import net.bytebuddy.asm.Advice;

final class SqlSessionReadAdvice {
    private SqlSessionReadAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static Object onEnter(@Advice.FieldValue("configuration") Object configuration) {
        return HotReloadBridge.enterRead(configuration);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void onExit(@Advice.Enter Object token) {
        HotReloadBridge.exitRead(token);
    }
}

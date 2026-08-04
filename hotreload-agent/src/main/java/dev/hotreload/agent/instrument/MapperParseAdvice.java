package dev.hotreload.agent.instrument;

import dev.hotreload.bootstrap.HotReloadBridge;
import net.bytebuddy.asm.Advice;

final class MapperParseAdvice {
    private MapperParseAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static Object onEnter(@Advice.This Object parser,
                          @Advice.FieldValue("configuration") Object configuration,
                          @Advice.FieldValue("resource") String resource) {
        return HotReloadBridge.beginMapperParse(configuration, parser, resource);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void onExit(@Advice.Enter Object token, @Advice.Thrown Throwable failure) {
        HotReloadBridge.endMapperParse(token, failure == null);
    }
}

package dev.hotreload.agent.spring;

import dev.hotreload.agent.logging.AgentSessionLogger;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

public final class SpringContextInstrumentation implements AutoCloseable {
    private final Instrumentation instrumentation;
    private final ClassFileTransformer transformer;
    private final AgentSessionLogger logger;
    private final AtomicBoolean closed = new AtomicBoolean();

    private SpringContextInstrumentation(Instrumentation instrumentation, ClassFileTransformer transformer,
                                         AgentSessionLogger logger) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.logger = logger;
    }

    public static SpringContextInstrumentation install(Instrumentation instrumentation, AgentSessionLogger logger) {
        if (instrumentation == null) throw new NullPointerException("instrumentation");
        if (logger == null) throw new NullPointerException("logger");
        AgentBuilder builder = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .ignore(net.bytebuddy.matcher.ElementMatchers.nameStartsWith("net.bytebuddy.")
                        .or(net.bytebuddy.matcher.ElementMatchers.nameStartsWith("dev.hotreload.")))
                .type(named("org.springframework.context.support.AbstractApplicationContext"))
                .transform(new AgentBuilder.Transformer() {
                    @Override public DynamicType.Builder<?> transform(DynamicType.Builder<?> typeBuilder,
                            TypeDescription typeDescription, ClassLoader classLoader, JavaModule module,
                            ProtectionDomain protectionDomain) {
                        return typeBuilder.visit(Advice.to(SpringContextRefreshAdvice.class)
                                .on(isMethod().and(named("finishRefresh")).and(takesArguments(0))));
                    }
                });
        ClassFileTransformer transformer = builder.installOn(instrumentation);
        logger.log(Level.INFO, "SPRING_CONTEXT_INSTRUMENTATION_READY", Collections.<String, String>emptyMap());
        return new SpringContextInstrumentation(instrumentation, transformer, logger);
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        boolean removed = instrumentation.removeTransformer(transformer);
        logger.log(Level.INFO, "SPRING_CONTEXT_INSTRUMENTATION_STOP", fields("resultCode",
                removed ? "REMOVED" : "NOT_REGISTERED"));
    }

    private static Map<String, String> fields(String key, String value) {
        Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put(key, value);
        return fields;
    }
}

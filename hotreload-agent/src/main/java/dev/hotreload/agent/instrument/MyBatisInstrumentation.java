package dev.hotreload.agent.instrument;

import dev.hotreload.agent.logging.AgentSessionLogger;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
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

import static net.bytebuddy.matcher.ElementMatchers.*;

public final class MyBatisInstrumentation implements AutoCloseable {
    private final Instrumentation instrumentation;
    private final ClassFileTransformer transformer;
    private final AgentSessionLogger logger;
    private final AtomicBoolean closed = new AtomicBoolean();

    private MyBatisInstrumentation(Instrumentation instrumentation, ClassFileTransformer transformer,
                                   AgentSessionLogger logger) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.logger = logger;
    }

    public static MyBatisInstrumentation install(Instrumentation instrumentation, AgentSessionLogger logger) {
        if (instrumentation == null) throw new NullPointerException("instrumentation");
        if (logger == null) throw new NullPointerException("logger");
        AgentBuilder builder = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .ignore(nameStartsWith("net.bytebuddy.").or(nameStartsWith("dev.hotreload.")))
                .with(new LoggingListener(logger))
                .type(named("org.apache.ibatis.session.defaults.DefaultSqlSessionFactory"))
                .transform(new AgentBuilder.Transformer() {
                    @Override public DynamicType.Builder<?> transform(DynamicType.Builder<?> typeBuilder,
                            TypeDescription typeDescription, ClassLoader classLoader, JavaModule module,
                            ProtectionDomain protectionDomain) {
                        return typeBuilder.visit(Advice.to(FactoryConstructorAdvice.class)
                                .on(isConstructor().and(takesArguments(1))));
                    }
                })
                .type(named("org.apache.ibatis.builder.xml.XMLMapperBuilder")
                        .or(named("com.baomidou.mybatisplus.core.MybatisXMLMapperBuilder")))
                .transform(new AgentBuilder.Transformer() {
                    @Override public DynamicType.Builder<?> transform(DynamicType.Builder<?> typeBuilder,
                            TypeDescription typeDescription, ClassLoader classLoader, JavaModule module,
                            ProtectionDomain protectionDomain) {
                        return typeBuilder.visit(Advice.to(MapperParseAdvice.class)
                                .on(isMethod().and(named("parse")).and(takesArguments(0))));
                    }
                })
                .type(named("org.apache.ibatis.session.defaults.DefaultSqlSession"))
                .transform(new AgentBuilder.Transformer() {
                    @Override public DynamicType.Builder<?> transform(DynamicType.Builder<?> typeBuilder,
                            TypeDescription typeDescription, ClassLoader classLoader, JavaModule module,
                            ProtectionDomain protectionDomain) {
                        return typeBuilder.visit(Advice.to(SqlSessionReadAdvice.class)
                                .on(isMethod().and(nameStartsWith("select").or(named("insert"))
                                        .or(named("update")).or(named("delete")))));
                    }
                });
        ClassFileTransformer transformer = builder.installOn(instrumentation);
        logger.log(Level.INFO, "MYBATIS_INSTRUMENTATION_READY", Collections.<String, String>emptyMap());
        return new MyBatisInstrumentation(instrumentation, transformer, logger);
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        boolean removed = instrumentation.removeTransformer(transformer);
        logger.log(Level.INFO, "MYBATIS_INSTRUMENTATION_STOP", fields("resultCode",
                removed ? "REMOVED" : "NOT_REGISTERED"));
    }

    private static Map<String, String> fields(String... keyValues) {
        Map<String, String> fields = new LinkedHashMap<String, String>();
        if (keyValues != null) {
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                fields.put(keyValues[i], keyValues[i + 1]);
            }
        }
        return fields;
    }

    private static final class LoggingListener extends AgentBuilder.Listener.Adapter {
        private final AgentSessionLogger logger;

        private LoggingListener(AgentSessionLogger logger) { this.logger = logger; }

        @Override public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                                                JavaModule module, boolean loaded,
                                                DynamicType dynamicType) {
            logger.log(Level.INFO, "MYBATIS_TYPE_INSTRUMENTED", fields("resultCode", "SUCCESS",
                    "typeName", typeDescription.getName()));
        }

        @Override public void onError(String typeName, ClassLoader classLoader, JavaModule module,
                                      boolean loaded, Throwable throwable) {
            logger.log(Level.WARNING, "MYBATIS_TYPE_INSTRUMENT_FAILED", fields("resultCode",
                    throwable.getClass().getSimpleName(), "typeName", typeName == null ? "unknown" : typeName));
        }
    }
}

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * Intercepts Spring / JDK annotation lookups so hot-reloaded annotations
 * are resolved from the latest bytecode index instead of stale Method metadata.
 */
public final class SpringAnnotationInstrumentation implements AutoCloseable {
    private final Instrumentation instrumentation;
    private final ClassFileTransformer transformer;
    private final AgentSessionLogger logger;
    private final AtomicBoolean closed = new AtomicBoolean();

    private SpringAnnotationInstrumentation(Instrumentation instrumentation, ClassFileTransformer transformer,
                                            AgentSessionLogger logger) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.logger = logger;
    }

    public static SpringAnnotationInstrumentation install(Instrumentation instrumentation, AgentSessionLogger logger) {
        if (instrumentation == null) throw new NullPointerException("instrumentation");
        if (logger == null) throw new NullPointerException("logger");
        // DCEVM-8 has a VM-level bug retransforming java.lang.reflect.Method/Executable:
        // the JMX platform MBean introspection then dies with ArrayStoreException at startup.
        // On enhanced runtimes those JDK8 annotation-cache workarounds are unnecessary anyway —
        // redefine keeps class identity and reflection rebuilds annotations from new bytecode.
        boolean instrumentJdkReflection =
                dev.hotreload.agent.classes.EngineCapabilityProbe.capability(instrumentation)
                        != dev.hotreload.agent.classes.EngineCapabilityProbe.Capability.ENHANCED;

        AgentBuilder builder = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .ignore(net.bytebuddy.matcher.ElementMatchers.nameStartsWith("net.bytebuddy.")
                        .or(net.bytebuddy.matcher.ElementMatchers.nameStartsWith("dev.hotreload.")))
                // Spring annotation utilities
                .type(named("org.springframework.core.annotation.AnnotationUtils")
                        .or(named("org.springframework.core.annotation.AnnotatedElementUtils")))
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(DynamicType.Builder<?> typeBuilder,
                                                            TypeDescription typeDescription,
                                                            ClassLoader classLoader,
                                                            JavaModule module,
                                                            ProtectionDomain protectionDomain) {
                        return typeBuilder
                                .visit(Advice.to(FindAnnotationOnMethodAdvice.class).on(
                                        isMethod().and(named("findAnnotation"))
                                                .and(takesArguments(2))
                                                .and(takesArgument(0, java.lang.reflect.Method.class))
                                                .and(takesArgument(1, Class.class))))
                                .visit(Advice.to(FindAnnotationOnMethodAdvice.class).on(
                                        isMethod().and(named("getAnnotation"))
                                                .and(takesArguments(2))
                                                .and(takesArgument(0, java.lang.reflect.Method.class))
                                                .and(takesArgument(1, Class.class))))
                                .visit(Advice.to(FindMergedAnnotationAdvice.class).on(
                                        isMethod().and(named("findMergedAnnotation"))
                                                .and(takesArguments(2))
                                                .and(takesArgument(0, java.lang.reflect.AnnotatedElement.class))
                                                .and(takesArgument(1, Class.class))))
                                .visit(Advice.to(IsAnnotationPresentAdvice.class).on(
                                        isMethod().and(named("isAnnotationPresent").or(named("hasAnnotation")))
                                                .and(takesArguments(2))
                                                .and(takesArgument(1, Class.class))));
                    }
                })
                // Only Spring's matcher. Do NOT match Baomidou/custom *AnnotationMethodMatcher
                // classes (their classloader cannot access private advice helpers).
                .type(named("org.springframework.aop.support.annotation.AnnotationMethodMatcher"))
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(DynamicType.Builder<?> typeBuilder,
                                                            TypeDescription typeDescription,
                                                            ClassLoader classLoader,
                                                            JavaModule module,
                                                            ProtectionDomain protectionDomain) {
                        return typeBuilder.visit(Advice.to(AnnotationMethodMatcherAdvice.class).on(
                                isMethod().and(named("matches"))
                                        .and(takesArguments(2))
                                        .and(takesArgument(0, java.lang.reflect.Method.class))));
                    }
                });

        if (instrumentJdkReflection) {
            // JDK Method annotation APIs used by Spring + AspectJ pointcuts (stock JVM only)
            builder = builder
                    .type(named("java.lang.reflect.Method").or(named("java.lang.reflect.Executable")))
                    .transform(new AgentBuilder.Transformer() {
                        @Override
                        public DynamicType.Builder<?> transform(DynamicType.Builder<?> typeBuilder,
                                                                TypeDescription typeDescription,
                                                                ClassLoader classLoader,
                                                                JavaModule module,
                                                                ProtectionDomain protectionDomain) {
                            return typeBuilder
                                    .visit(Advice.to(MethodGetAnnotationAdvice.class).on(
                                            isMethod().and(named("getAnnotation")).and(takesArguments(1))))
                                    .visit(Advice.to(MethodIsAnnotationPresentAdvice.class).on(
                                            isMethod().and(named("isAnnotationPresent")).and(takesArguments(1))))
                                    .visit(Advice.to(MethodGetDeclaredAnnotationAdvice.class).on(
                                            isMethod().and(named("getDeclaredAnnotation")).and(takesArguments(1))))
                                    .visit(Advice.to(MethodGetDeclaredAnnotationsAdvice.class).on(
                                            isMethod().and(named("getDeclaredAnnotations")).and(takesArguments(0))))
                                    .visit(Advice.to(MethodGetAnnotationsAdvice.class).on(
                                            isMethod().and(named("getAnnotations")).and(takesArguments(0))));
                        }
                    });
        }

        ClassFileTransformer transformer = builder.installOn(instrumentation);
        int retransformed = 0;
        List<String> targets = new ArrayList<String>();
        try {
            List<String> names = new ArrayList<String>();
            names.add("org.springframework.core.annotation.AnnotationUtils");
            names.add("org.springframework.core.annotation.AnnotatedElementUtils");
            names.add("org.springframework.aop.support.annotation.AnnotationMethodMatcher");
            if (instrumentJdkReflection) {
                names.add("java.lang.reflect.Method");
                names.add("java.lang.reflect.Executable");
            }
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                Class<?> loaded = null;
                try { loaded = Class.forName(name, false, null); } catch (Throwable ignored) { }
                if (loaded == null) {
                    try { loaded = Class.forName(name, false, Thread.currentThread().getContextClassLoader()); }
                    catch (Throwable ignored) { }
                }
                if (loaded == null || !instrumentation.isModifiableClass(loaded)) continue;
                try {
                    instrumentation.retransformClasses(loaded);
                    retransformed++;
                    targets.add(name);
                } catch (Throwable ignored) {
                    // continue
                }
            }
        } catch (Throwable failure) {
            logger.log(Level.WARNING, "SPRING_ANNOTATION_RETRANSFORM_FAILED", fields(
                    "resultCode", failure.getClass().getSimpleName()));
        }
        Map<String, String> ready = new LinkedHashMap<String, String>();
        ready.put("resultCode", "SUCCESS");
        ready.put("detail", "retransformed=" + retransformed + ",targets=" + targets.size()
                + ",jdkReflection=" + (instrumentJdkReflection ? "instrumented" : "skipped:enhancedRuntime"));
        logger.log(Level.INFO, "SPRING_ANNOTATION_INSTRUMENTATION_READY", ready);
        return new SpringAnnotationInstrumentation(instrumentation, transformer, logger);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        boolean removed = instrumentation.removeTransformer(transformer);
        logger.log(Level.INFO, "SPRING_ANNOTATION_INSTRUMENTATION_STOP", fields("resultCode",
                removed ? "REMOVED" : "NOT_REGISTERED"));
    }

    private static Map<String, String> fields(String key, String value) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put(key, value);
        return map;
    }
}

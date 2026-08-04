package dev.hotreload.agent.spring;

import dev.hotreload.agent.classes.GenerationClassLoader;
import dev.hotreload.agent.logging.AgentSessionLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringFrameworkRebinderTest {
    @TempDir Path tempDirectory;

    @Test
    void rebindWithNoContextReturnsZeroesAndAnnotationDiagnostics() throws Exception {
        AgentSessionLogger logger = new AgentSessionLogger("launch-test", tempDirectory.resolve("rebind.log").toAbsolutePath());
        try {
            SpringFrameworkRebinder rebinder = new SpringFrameworkRebinder(logger);
            SpringFrameworkRebinder.RebindReport report = rebinder.rebind(
                    Collections.<Class<?>>singletonList(SampleController.class));
            assertEquals(0, report.getContextCount());
            assertEquals(0, report.getBeansRecreated());
            assertEquals(0, report.getFailures());
            String summary = report.summary();
            assertTrue(summary.contains("annotationsVisible="), summary);
            assertTrue(summary.contains("SampleController"), summary);
            assertTrue(summary.contains("Deprecated"), summary);
            assertTrue(summary.contains("listOrders@Deprecated") || summary.contains("Deprecated"), summary);
        } finally {
            logger.close();
        }
    }

    @Test
    void rebindReportSummaryOrdersOperationalDetailsBeforeAnnotations() {
        Set<String> details = new LinkedHashSet<String>();
        details.add("annotationsVisible=DemoOrderController{RestController|listOrders@DemoScope(alias=d)}");
        details.add("recreated=demoOrderController:Proxy:proxy=true");
        details.add("registered=demoOrderController:mappingKeys=9");
        details.add("mappingRefresh=ok unregistered=9 registeredControllers=1 restoredKeys=9 mappings=1");
        SpringFrameworkRebinder.RebindReport report =
                new SpringFrameworkRebinder.RebindReport(1, 1, 2, 1, 0, details);
        String summary = report.summary();
        assertTrue(summary.contains("contexts=1"), summary);
        assertTrue(summary.contains("beansRecreated=1"), summary);
        assertTrue(summary.contains("registeredControllers=1"), summary);
        assertTrue(summary.contains("proxy=true"), summary);
        assertTrue(summary.contains("DemoScope(alias=d)"), summary);
        assertFalse(summary.contains("detectFailed"), summary);
        int mappingPos = summary.indexOf("mappingRefresh=");
        int annPos = summary.indexOf("annotationsVisible=");
        assertTrue(mappingPos >= 0 && annPos > mappingPos, summary);
    }

    @Test
    void requiredSpringFailuresMarkTheRebindIncomplete() {
        for (String detail : Arrays.asList(
                "contextFailed=IllegalStateException",
                "recreateFailed=demo:IllegalStateException",
                "beanNamesFailed=DemoService",
                "refreshFailed=DemoService:IllegalStateException",
                "reinjectFailed=demo:IllegalStateException",
                "upgradeBeanDefFailed=DemoService",
                "conditionalBeanRegistrationRequiresRestart=com.example.DemoService",
                "dynamicBeanRegistrationFailed=com.example.DemoService",
                "dynamicBeanBindFailed=com.example.DemoService",
                "detectFailed=IllegalStateException",
                "mappingRefreshFailed=IllegalStateException",
                "selfCheck=WARN:partialRoutes expected=3 restored=2",
                "mappingFallbackResult=initHandlerMethodsMissing",
                "mappingFallbackResult=before=4 after=2 recovered=false")) {
            Set<String> details = new LinkedHashSet<String>(Collections.singleton(detail));
            SpringFrameworkRebinder.RebindReport report =
                    new SpringFrameworkRebinder.RebindReport(1, 0, 0, 0, 1, details);
            assertTrue(report.hasIncompleteChanges(), detail);
        }
        SpringFrameworkRebinder.RebindReport cacheOnly = new SpringFrameworkRebinder.RebindReport(
                1, 0, 0, 0, 1, new LinkedHashSet<String>(Collections.singleton(
                "jacksonCacheClearFailed=IllegalStateException")));
        assertFalse(cacheOnly.hasIncompleteChanges());
    }

    @Test
    void generationBindingReportRequiresEveryChangedBusinessType() {
        Set<String> required = new LinkedHashSet<String>(Arrays.asList(
                "com.example.OrderService", "com.example.OrderController"));
        Set<String> fullyBound = new LinkedHashSet<String>(Arrays.asList(
                "generationBound=com.example.OrderService:orderService",
                "generationBound=com.example.OrderController:orderController"));
        SpringFrameworkRebinder.RebindReport complete = new SpringFrameworkRebinder.RebindReport(
                1, 0, 0, 2, 0, fullyBound);
        assertFalse(complete.hasUnboundGenerations(required));

        Set<String> partiallyBound = new LinkedHashSet<String>(Collections.singleton(
                "generationBound=com.example.OrderService:orderService"));
        SpringFrameworkRebinder.RebindReport incomplete = new SpringFrameworkRebinder.RebindReport(
                1, 0, 0, 1, 0, partiallyBound);
        assertTrue(incomplete.hasUnboundGenerations(required));
        assertFalse(incomplete.hasUnboundGenerations(Collections.<String>emptySet()));
    }

    @Test
    void needsMappingFallbackWhenPartialKeysRestored() {
        // User log: unregistered=9 restoredKeys=3 => afterRegister < before
        assertTrue(SpringFrameworkRebinder.needsMappingFallback(9, 9, 3, 1, true));
        assertTrue(SpringFrameworkRebinder.needsMappingFallback(9, 9, 0, 0, false));
        assertTrue(SpringFrameworkRebinder.needsMappingFallback(9, 9, 0, 1, true));
        // Full restore should not fallback
        assertFalse(SpringFrameworkRebinder.needsMappingFallback(9, 9, 9, 1, true));
        // No routes removed and count preserved
        assertFalse(SpringFrameworkRebinder.needsMappingFallback(0, 0, 0, 0, true));
        // Controllers not re-registered after removals
        assertTrue(SpringFrameworkRebinder.needsMappingFallback(4, 4, 4, 0, false));
    }

    @Deprecated
    static class SampleController {
        @Deprecated
        public void listOrders() { }
    }

    @Test
    void guessBeanNameStripsGenerationSuffix() {
        assertEquals("demoOrderController",
                SpringFrameworkRebinder.guessBeanNameForTest(
                        "com.example.demo.DemoOrderController__HrGen1"));
        // Nested test class keeps outer$Inner simple name; production controllers are top-level.
        String nested = SpringFrameworkRebinder.guessBeanNameForTest(
                SampleController.class.getName() + "__HrGen3");
        assertNotNull(nested);
        assertTrue(nested.toLowerCase().contains("samplecontroller"), nested);
        assertFalse(nested.contains("__HrGen"), nested);
    }

    @Test
    void isGenerationClassNameRecognizesBothStyles() {
        assertTrue(SpringFrameworkRebinder.isGenerationClassName(
                "com.demo.Foo__HrGen1"));
        assertTrue(SpringFrameworkRebinder.isGenerationClassName(
                "com.demo.Foo$$HrGen2"));
        assertFalse(SpringFrameworkRebinder.isGenerationClassName(
                "com.demo.Foo$$EnhancerBySpringCGLIB$$abc"));
    }

    @Test
    void shouldReplaceBeanClassNeverDowngradesLiveGeneration() {
        String base = "com.example.demo.DemoOrderController";
        String gen1 = base + "__HrGen1";
        String gen2 = base + "__HrGen2";
        assertTrue(SpringFrameworkRebinder.shouldReplaceBeanClass(base, gen1));
        assertTrue(SpringFrameworkRebinder.shouldReplaceBeanClass(gen1, gen2));
        // Regression: the base-type pass must not downgrade a definition already on a generation.
        assertFalse(SpringFrameworkRebinder.shouldReplaceBeanClass(gen1, base));
        assertFalse(SpringFrameworkRebinder.shouldReplaceBeanClass(gen2, gen1));
        assertFalse(SpringFrameworkRebinder.shouldReplaceBeanClass(gen1, gen1));
        assertFalse(SpringFrameworkRebinder.shouldReplaceBeanClass(gen1, null));
        assertFalse(SpringFrameworkRebinder.shouldReplaceBeanClass(null, gen1));
        assertFalse(SpringFrameworkRebinder.shouldReplaceBeanClass("com.a.Foo", "com.b.Bar"));
        assertFalse(SpringFrameworkRebinder.shouldReplaceBeanClass(base + "$$HrGen9", base + "$$HrGen3"));
    }

    @Test
    void annotationLookupTraversesMetaAnnotations() {
        assertTrue(SpringFrameworkRebinder.hasAnnotationNamed(
                MetaAnnotatedComponent.class, RootComponentMarker.class.getName()));
        assertFalse(SpringFrameworkRebinder.hasAnnotationNamed(
                MetaAnnotatedComponent.class, "com.example.MissingAnnotation"));
    }

    @Test
    void recreateKeepsLiveGenerationWhenBaseTypeArrivesSecond() throws Exception {
        Class<?> base = defineTinyClass("dev.hotreload.agent.spring.FakeReloadController");
        Class<?> gen = defineTinyClass("dev.hotreload.agent.spring.FakeReloadController__HrGen7");
        FakeBeanFactory factory = new FakeBeanFactory("fakeReloadController", base.getName());
        FakeInjectionProcessor injection = new FakeInjectionProcessor();
        injection.injectionMetadataCache.put("fakeReloadController", "stale");
        factory.postProcessors.add(injection);
        FakeContext context = new FakeContext(factory);
        AgentSessionLogger logger = new AgentSessionLogger("launch-test",
                tempDirectory.resolve("recreate.log").toAbsolutePath());
        try {
            SpringFrameworkRebinder rebinder = new SpringFrameworkRebinder(logger);
            Set<String> details = new LinkedHashSet<String>();
            int recreated = rebinder.recreateBeansOfTypes(context,
                    Arrays.<Class<?>>asList(gen, base), details);
            // One bean, one recreation: generation pass wins, base pass is a no-op.
            assertEquals(1, recreated, String.valueOf(details));
            assertEquals(1, context.getBeanCount, String.valueOf(details));
            assertEquals(1, factory.destroyCount, String.valueOf(details));
            assertEquals(gen, factory.definition.replacedWith, String.valueOf(details));
            assertEquals(gen.getName(), factory.definition.getBeanClassName());
            assertTrue(String.valueOf(details).contains("beanClassKept=fakeReloadController"),
                    String.valueOf(details));
            // Stale @Autowired/@Value metadata must be evicted before recreation.
            assertFalse(injection.injectionMetadataCache.containsKey("fakeReloadController"),
                    String.valueOf(injection.injectionMetadataCache));
        } finally {
            logger.close();
        }
    }

    @Test
    void pruneLookupsRemovesOnlyRemovedKeysAndKeepsOtherControllers() {
        FakeMappingRegistry registry = new FakeMappingRegistry();
        Object removedKey = "GET /demo/order/list";
        Object survivorKey = "GET /other/list";
        registry.mappingLookup.put(removedKey, "removedHandler");
        registry.mappingLookup.put(survivorKey, "otherHandler");
        registry.pathLookup.put("/demo/order/list",
                new ArrayList<Object>(Collections.singletonList(removedKey)));
        registry.pathLookup.put("/other/list",
                new ArrayList<Object>(Collections.singletonList(survivorKey)));

        SpringFrameworkRebinder.pruneLookupsForKeys(registry, Collections.singletonList(removedKey));

        assertFalse(registry.mappingLookup.containsKey(removedKey));
        assertTrue(registry.mappingLookup.containsKey(survivorKey));
        // Regression: pruning must not clear other controllers' direct-path entries.
        assertFalse(registry.pathLookup.containsKey("/demo/order/list"));
        assertEquals(Collections.singletonList(survivorKey), registry.pathLookup.get("/other/list"));
    }

    @Test
    void refreshInPlaceKeepsInstanceAndReinjects() throws Exception {
        Class<?> base = defineTinyClass("dev.hotreload.agent.spring.FakeInPlaceController");
        FakeBeanFactory factory = new FakeBeanFactory("fakeInPlaceController", base.getName());
        Object instance = base.getDeclaredConstructor().newInstance();
        factory.singletons.put("fakeInPlaceController", instance);
        FakeInjectionProcessor injection = new FakeInjectionProcessor();
        injection.injectionMetadataCache.put("fakeInPlaceController", "stale");
        factory.postProcessors.add(injection);
        FakeContext context = new FakeContext(factory);
        AgentSessionLogger logger = new AgentSessionLogger("launch-test",
                tempDirectory.resolve("inplace.log").toAbsolutePath());
        try {
            SpringFrameworkRebinder rebinder = new SpringFrameworkRebinder(logger);
            Set<String> details = new LinkedHashSet<String>();
            Set<String> deep = new LinkedHashSet<String>(Collections.singletonList(base.getName()));
            int touched = rebinder.refreshBeansInPlace(context,
                    Collections.<Class<?>>singletonList(base), deep, details);
            assertEquals(1, touched, String.valueOf(details));
            // E2 contract: no destroy, no recreate — same instance re-injected.
            assertEquals(0, factory.destroyCount, String.valueOf(details));
            assertEquals(0, context.getBeanCount, String.valueOf(details));
            assertEquals(1, injection.processInjectionCalls, String.valueOf(details));
            assertSame(instance, injection.lastInjected);
            assertFalse(injection.injectionMetadataCache.containsKey("fakeInPlaceController"));
            assertTrue(String.valueOf(details).contains("refreshedInPlace=fakeInPlaceController"),
                    String.valueOf(details));
        } finally {
            logger.close();
        }
    }

    @Test
    void refreshInPlaceSkipsBodyOnlyChanges() throws Exception {
        Class<?> base = defineTinyClass("dev.hotreload.agent.spring.FakeBodyOnlyService");
        FakeBeanFactory factory = new FakeBeanFactory("fakeBodyOnlyService", base.getName());
        factory.singletons.put("fakeBodyOnlyService", base.getDeclaredConstructor().newInstance());
        FakeInjectionProcessor injection = new FakeInjectionProcessor();
        factory.postProcessors.add(injection);
        FakeContext context = new FakeContext(factory);
        AgentSessionLogger logger = new AgentSessionLogger("launch-test",
                tempDirectory.resolve("bodyonly.log").toAbsolutePath());
        try {
            SpringFrameworkRebinder rebinder = new SpringFrameworkRebinder(logger);
            Set<String> details = new LinkedHashSet<String>();
            int touched = rebinder.refreshBeansInPlace(context,
                    Collections.<Class<?>>singletonList(base),
                    new LinkedHashSet<String>(), details);
            assertEquals(0, touched, String.valueOf(details));
            assertEquals(0, factory.destroyCount);
            assertEquals(0, injection.processInjectionCalls);
            assertTrue(String.valueOf(details).contains("refreshSkipped=fakeBodyOnlyService:bodyOnly"),
                    String.valueOf(details));
        } finally {
            logger.close();
        }
    }

    @Test
    void refreshInPlaceRecreatesWhenAdvisorWouldApplyNow() throws Exception {
        // Generic mechanism: Spring's own advisor recomputation (no annotation name list) says
        // this un-proxied bean now matches an aspect -> recreate so the proxy can be woven.
        FakeBeanFactory factory = new FakeBeanFactory("txIntroducedService",
                TxIntroducedService.class.getName());
        factory.singletons.put("txIntroducedService", new TxIntroducedService());
        FakeContext context = new FakeContext(factory);
        factory.postProcessors.add(new FakeAutoProxyCreator(new Object[]{"someAdvisor"}));
        AgentSessionLogger logger = new AgentSessionLogger("launch-test",
                tempDirectory.resolve("proxyintro.log").toAbsolutePath());
        try {
            SpringFrameworkRebinder rebinder = new SpringFrameworkRebinder(logger);
            Set<String> details = new LinkedHashSet<String>();
            int touched = rebinder.refreshBeansInPlace(context,
                    Collections.<Class<?>>singletonList(TxIntroducedService.class), null, details);
            assertEquals(1, touched, String.valueOf(details));
            assertEquals(1, factory.destroyCount, String.valueOf(details));
            assertEquals(1, context.getBeanCount, String.valueOf(details));
            assertTrue(String.valueOf(details).contains("proxyIntroduced=txIntroducedService"),
                    String.valueOf(details));
        } finally {
            logger.close();
        }
    }

    @Test
    void refreshInPlaceStaysInPlaceWhenNoAdvisorApplies() throws Exception {
        // Same bean, but Spring reports no eligible advisors -> keep the instance (state!).
        FakeBeanFactory factory = new FakeBeanFactory("txIntroducedService",
                TxIntroducedService.class.getName());
        factory.singletons.put("txIntroducedService", new TxIntroducedService());
        FakeContext context = new FakeContext(factory);
        factory.postProcessors.add(new FakeAutoProxyCreator(null));
        AgentSessionLogger logger = new AgentSessionLogger("launch-test",
                tempDirectory.resolve("noadvisor.log").toAbsolutePath());
        try {
            SpringFrameworkRebinder rebinder = new SpringFrameworkRebinder(logger);
            Set<String> details = new LinkedHashSet<String>();
            int touched = rebinder.refreshBeansInPlace(context,
                    Collections.<Class<?>>singletonList(TxIntroducedService.class), null, details);
            assertEquals(1, touched, String.valueOf(details));
            assertEquals(0, factory.destroyCount, String.valueOf(details));
            assertTrue(String.valueOf(details).contains("refreshedInPlace=txIntroducedService"),
                    String.valueOf(details));
        } finally {
            logger.close();
        }
    }

    public static class TxIntroducedService {
        public void save() { }
    }

    /** Reflection target mimicking AbstractAutoProxyCreator's advisor recomputation. */
    static final class FakeAutoProxyCreator {
        private final Object[] advisors;

        FakeAutoProxyCreator(Object[] advisors) {
            this.advisors = advisors;
        }

        public Object[] getAdvicesAndAdvisorsForBean(Class<?> beanClass, String beanName, Object targetSource) {
            return advisors;
        }
    }

    @Test
    void proxyOutdatedDetectsMissingOverrides() {
        // Partial proxy lacks b(): new methods would bypass interceptors -> stale.
        assertTrue(SpringFrameworkRebinder.proxyOutdated(PartialProxy.class, ProxyTarget.class));
        assertFalse(SpringFrameworkRebinder.proxyOutdated(FullProxy.class, ProxyTarget.class));
        // JDK interface proxies expose a fixed method set: never treated as stale.
        Object jdkProxy = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Runnable.class},
                (proxy, method, args) -> null);
        assertFalse(SpringFrameworkRebinder.proxyOutdated(jdkProxy.getClass(), ProxyTarget.class));
        assertFalse(SpringFrameworkRebinder.proxyOutdated(ProxyTarget.class, ProxyTarget.class));
    }

    public static class ProxyTarget {
        public void a() { }
        public void b() { }
    }

    public static class PartialProxy extends ProxyTarget {
        @Override public void a() { }
    }

    public static class FullProxy extends ProxyTarget {
        @Override public void a() { }
        @Override public void b() { }
    }

    @Test
    void isChangedTypeDoesNotMatchSiblingsThroughSharedBase() throws Exception {
        // Regression: an identity-kept controller sharing BaseController with the whole app
        // must never drag sibling controllers into unregistration (1554-routes-gone incident).
        assertTrue(SpringFrameworkRebinder.isChangedType(ProxyTarget.class,
                Collections.<Class<?>>singletonList(ProxyTarget.class)));
        assertFalse(SpringFrameworkRebinder.isChangedType(PartialProxy.class,
                Collections.<Class<?>>singletonList(ProxyTarget.class)));
        assertFalse(SpringFrameworkRebinder.isChangedType(ProxyTarget.class,
                Collections.<Class<?>>singletonList(PartialProxy.class)));
        // Generation pairs keep the wide assignability match (old registrations use the base type).
        Class<?> gen = defineTinySubclass(
                "dev.hotreload.agent.spring.ProxyTargetFake__HrGen9", ProxyTarget.class);
        assertTrue(SpringFrameworkRebinder.isChangedType(ProxyTarget.class,
                Collections.<Class<?>>singletonList(gen)));
        assertTrue(SpringFrameworkRebinder.isChangedType(gen,
                Collections.<Class<?>>singletonList(ProxyTarget.class)));
    }

    private static Class<?> defineTinySubclass(String binaryName, Class<?> superClass) {
        String internal = binaryName.replace('.', '/');
        String superInternal = superClass.getName().replace('.', '/');
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internal, null, superInternal, null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superInternal, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        writer.visitEnd();
        return new GenerationClassLoader(SpringFrameworkRebinderTest.class.getClassLoader(),
                binaryName, writer.toByteArray()).defineTarget();
    }

    private static Class<?> defineTinyClass(String binaryName) {
        String internal = binaryName.replace('.', '/');
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        writer.visitEnd();
        return new GenerationClassLoader(SpringFrameworkRebinderTest.class.getClassLoader(),
                binaryName, writer.toByteArray()).defineTarget();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.ANNOTATION_TYPE)
    @interface RootComponentMarker { }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @RootComponentMarker
    @interface MetaComponentMarker { }

    @MetaComponentMarker
    static final class MetaAnnotatedComponent { }

    /** Reflection target mimicking RootBeanDefinition for the rebinder's method lookups. */
    static final class FakeBeanDefinition {
        private String beanClassName;
        Class<?> replacedWith;

        FakeBeanDefinition(String beanClassName) {
            this.beanClassName = beanClassName;
        }

        public String getBeanClassName() {
            return beanClassName;
        }

        public void setBeanClass(Class<?> beanClass) {
            this.replacedWith = beanClass;
            this.beanClassName = beanClass.getName();
        }
    }

    /** Reflection target mimicking DefaultListableBeanFactory. */
    static final class FakeBeanFactory {
        private final String beanName;
        final FakeBeanDefinition definition;
        final List<Object> postProcessors = new ArrayList<Object>();
        final Map<String, Object> singletons = new LinkedHashMap<String, Object>();
        int destroyCount;

        FakeBeanFactory(String beanName, String beanClassName) {
            this.beanName = beanName;
            this.definition = new FakeBeanDefinition(beanClassName);
        }

        public String[] getBeanNamesForType(Class<?> type, boolean includeNonSingletons, boolean allowEagerInit) {
            return new String[]{beanName};
        }

        public boolean containsBeanDefinition(String name) {
            return beanName.equals(name);
        }

        public FakeBeanDefinition getBeanDefinition(String name) {
            return definition;
        }

        public List<Object> getBeanPostProcessors() {
            return postProcessors;
        }

        public Object getSingleton(String name) {
            return singletons.get(name);
        }

        public void destroySingleton(String name) {
            destroyCount++;
            singletons.remove(name);
        }
    }

    /** Reflection target mimicking AutowiredAnnotationBeanPostProcessor's metadata cache. */
    static final class FakeInjectionProcessor {
        final Map<Object, Object> injectionMetadataCache = new LinkedHashMap<Object, Object>();
        int processInjectionCalls;
        Object lastInjected;

        public void processInjection(Object bean) {
            processInjectionCalls++;
            lastInjected = bean;
        }
    }

    /** Reflection target mimicking ConfigurableApplicationContext. */
    static final class FakeContext {
        private final FakeBeanFactory factory;
        int getBeanCount;

        FakeContext(FakeBeanFactory factory) {
            this.factory = factory;
        }

        public FakeBeanFactory getBeanFactory() {
            return factory;
        }

        public Object getBean(String name) {
            getBeanCount++;
            return new Object();
        }
    }

    /** Reflection target mimicking AbstractHandlerMethodMapping.MappingRegistry fields. */
    static final class FakeMappingRegistry {
        final Map<Object, Object> mappingLookup = new LinkedHashMap<Object, Object>();
        final Map<Object, List<Object>> pathLookup = new LinkedHashMap<Object, List<Object>>();
    }
}

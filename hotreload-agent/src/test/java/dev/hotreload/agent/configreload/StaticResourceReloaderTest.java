package dev.hotreload.agent.configreload;

import dev.hotreload.agent.logging.AgentSessionLogger;
import dev.hotreload.agent.spring.SpringContextRegistry;
import dev.hotreload.protocol.message.OperationStatus;
import dev.hotreload.protocol.message.ReloadErrorCode;
import dev.hotreload.protocol.message.ReloadResponse;
import dev.hotreload.protocol.message.ResourceReloadRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticResourceReloaderTest {
    @TempDir Path tempDirectory;

    @BeforeEach
    @AfterEach
    void clearContexts() throws Exception {
        Field contexts = SpringContextRegistry.class.getDeclaredField("CONTEXTS");
        contexts.setAccessible(true);
        ((List<?>) contexts.get(null)).clear();
    }

    @Test void clearsHandlersFromTheStandardMvcResourceUrlProvider() throws Exception {
        FakeContext context = new FakeContext();
        SpringContextRegistry.register(context);
        AgentSessionLogger logger = new AgentSessionLogger("static-resource",
                tempDirectory.resolve("agent.log").toAbsolutePath());
        try {
            StaticResourceReloader reloader = new StaticResourceReloader(logger);

            ReloadResponse response = reloader.reload(new ResourceReloadRequest(
                    "request", "token", "static/app.css", new byte[0], "css"));

            assertEquals(OperationStatus.SUCCESS, response.getStatus());
            assertEquals(1, context.resolverCache.clearCount);
            assertEquals(1, context.transformerCache.clearCount);
            assertEquals(Collections.singletonList("mvcResourceUrlProvider"), context.requestedBeans);
            assertEquals("cache=cleared", response.getItems().get(0).getDiagnostic());
        } finally {
            logger.close();
        }
    }

    @Test void fallsBackToTheLegacyResourceUrlProviderName() throws Exception {
        FakeLegacyContext context = new FakeLegacyContext();
        SpringContextRegistry.register(context);
        AgentSessionLogger logger = new AgentSessionLogger("static-resource-legacy",
                tempDirectory.resolve("legacy-agent.log").toAbsolutePath());
        try {
            ReloadResponse response = new StaticResourceReloader(logger).reload(
                    new ResourceReloadRequest("request", "token", "static/app.css",
                            new byte[0], "css"));

            assertEquals(OperationStatus.SUCCESS, response.getStatus());
            assertEquals(1, context.cache.clearCount);
            assertEquals(2, context.requestedBeans.size());
            assertEquals("mvcResourceUrlProvider", context.requestedBeans.get(0));
            assertEquals("resourceUrlProvider", context.requestedBeans.get(1));
        } finally {
            logger.close();
        }
    }

    @Test void clearsResolverAndTransformerOwnedCaches() {
        FakeSpringCache resolverCache = new FakeSpringCache();
        FakeSpringCache transformerCache = new FakeSpringCache();
        FakeResourceHandler handler = new FakeResourceHandler(
                new FakeCachingComponent(resolverCache),
                new FakeCachingComponent(transformerCache));

        assertTrue(StaticResourceReloader.clearResourceHandlerCaches(handler));
        assertEquals(1, resolverCache.clearCount);
        assertEquals(1, transformerCache.clearCount);
    }

    @Test void neverFallsBackToScanningEveryBeanWhenMvcIsUnavailable() throws Exception {
        FakeBeanFactory beanFactory = new FakeBeanFactory();
        FakeContextWithBeanFactory context = new FakeContextWithBeanFactory(beanFactory);
        SpringContextRegistry.register(context);
        AgentSessionLogger logger = new AgentSessionLogger("static-resource-no-mvc",
                tempDirectory.resolve("no-mvc-agent.log").toAbsolutePath());
        try {
            new StaticResourceReloader(logger).reload(new ResourceReloadRequest(
                    "request", "token", "static/app.css", new byte[]{1}, "css"));

            if (beanFactory.queriedType != null) {
                assertNotEquals(Object.class, beanFactory.queriedType);
            }
        } finally {
            logger.close();
        }
    }

    @Test void reportsSkippedWhenNoResourceCacheIsAvailable() throws Exception {
        FakeContextWithBeanFactory context = new FakeContextWithBeanFactory(new FakeBeanFactory());
        SpringContextRegistry.register(context);
        AgentSessionLogger logger = new AgentSessionLogger("static-resource-skipped",
                tempDirectory.resolve("skipped-agent.log").toAbsolutePath());
        try {
            ReloadResponse response = new StaticResourceReloader(logger).reload(
                    new ResourceReloadRequest("request", "token", "static/app.css",
                            new byte[0], "css"));

            assertEquals(OperationStatus.SKIPPED, response.getStatus());
            assertEquals(OperationStatus.SKIPPED, response.getItems().get(0).getStatus());
            assertEquals("SKIPPED", response.getItems().get(0).getMessage());
        } finally {
            logger.close();
        }
    }

    @Test void cacheClearFailureRequiresRestart() throws Exception {
        FakeFailingContext context = new FakeFailingContext();
        SpringContextRegistry.register(context);
        AgentSessionLogger logger = new AgentSessionLogger("static-resource-failed",
                tempDirectory.resolve("failed-agent.log").toAbsolutePath());
        try {
            ReloadResponse response = new StaticResourceReloader(logger).reload(
                    new ResourceReloadRequest("request", "token", "static/app.css",
                            new byte[0], "css"));

            assertEquals(OperationStatus.RESTART_REQUIRED, response.getStatus());
            assertEquals(ReloadErrorCode.INTERNAL_ERROR, response.getErrorCode());
            assertEquals("cache=failed", response.getItems().get(0).getDiagnostic());
        } finally {
            logger.close();
        }
    }

    @Test void cacheComponentDiscoveryFailureRequiresRestart() throws Exception {
        FakeGetterFailingContext context = new FakeGetterFailingContext();
        SpringContextRegistry.register(context);
        AgentSessionLogger logger = new AgentSessionLogger("static-resource-getter-failed",
                tempDirectory.resolve("getter-failed-agent.log").toAbsolutePath());
        try {
            ReloadResponse response = new StaticResourceReloader(logger).reload(
                    new ResourceReloadRequest("request", "token", "static/app.css",
                            new byte[0], "css"));

            assertEquals(OperationStatus.RESTART_REQUIRED, response.getStatus());
            assertEquals(ReloadErrorCode.INTERNAL_ERROR, response.getErrorCode());
        } finally {
            logger.close();
        }
    }

    @Test void ignoresAnUnrelatedBeanUsingTheLegacyProviderName() throws Exception {
        FakeUnrelatedProviderContext context = new FakeUnrelatedProviderContext();
        SpringContextRegistry.register(context);
        AgentSessionLogger logger = new AgentSessionLogger("static-resource-unrelated",
                tempDirectory.resolve("unrelated-agent.log").toAbsolutePath());
        try {
            ReloadResponse response = new StaticResourceReloader(logger).reload(
                    new ResourceReloadRequest("request", "token", "static/app.css",
                            new byte[0], "css"));

            assertEquals(OperationStatus.SKIPPED, response.getStatus());
            assertEquals(0, context.unrelated.clearCount);
        } finally {
            logger.close();
        }
    }

    private static final class FakeContext {
        private final FakeSpringCache resolverCache = new FakeSpringCache();
        private final FakeSpringCache transformerCache = new FakeSpringCache();
        private final List<String> requestedBeans = new ArrayList<String>();
        private final FakeResourceUrlProvider provider;

        private FakeContext() {
            FakeResourceHandler handler = new FakeResourceHandler(
                    new FakeCachingComponent(resolverCache),
                    new FakeCachingComponent(transformerCache));
            provider = new FakeResourceUrlProvider(handler);
        }

        public Object getBean(String name) {
            requestedBeans.add(name);
            return "mvcResourceUrlProvider".equals(name) ? provider : null;
        }

        public Object getBeanFactory() { return null; }
    }

    private static final class FakeLegacyContext {
        private final FakeLegacyResourceUrlProvider cache =
                new FakeLegacyResourceUrlProvider();
        private final List<String> requestedBeans = new ArrayList<String>();

        public Object getBean(String name) {
            requestedBeans.add(name);
            return "resourceUrlProvider".equals(name) ? cache : null;
        }

        public Object getBeanFactory() { return null; }
    }

    private static final class FakeFailingContext {
        private final FakeResourceUrlProvider provider = new FakeResourceUrlProvider(
                new FakeResourceHandler(new FakeFailingCachingComponent(),
                        new FakeCachingComponent(new FakeSpringCache())));

        public Object getBean(String name) {
            return "mvcResourceUrlProvider".equals(name) ? provider : null;
        }

        public Object getBeanFactory() { return null; }
    }

    private static final class FakeGetterFailingContext {
        private final FakeResourceUrlProvider provider = new FakeResourceUrlProvider(
                new FakeGetterFailingHandler());

        public Object getBean(String name) {
            return "mvcResourceUrlProvider".equals(name) ? provider : null;
        }

        public Object getBeanFactory() { return null; }
    }

    private static final class FakeCache {
        private int clearCount;

        public void clearCache() { clearCount++; }
    }

    private static final class FakeLegacyResourceUrlProvider
            extends org.springframework.web.servlet.resource.ResourceUrlProvider {
        private int clearCount;

        public void clearCache() { clearCount++; }
    }

    private static final class FakeUnrelatedProviderContext {
        private final FakeCache unrelated = new FakeCache();

        public Object getBean(String name) {
            return "resourceUrlProvider".equals(name) ? unrelated : null;
        }

        public Object getBeanFactory() { return null; }
    }

    private static final class FakeResourceHandler {
        private final List<Object> resolvers;
        private final List<Object> transformers;

        private FakeResourceHandler(Object resolver, Object transformer) {
            this.resolvers = Collections.singletonList(resolver);
            this.transformers = Collections.singletonList(transformer);
        }

        public List<Object> getResourceResolvers() { return resolvers; }
        public List<Object> getResourceTransformers() { return transformers; }
    }

    private static final class FakeGetterFailingHandler {
        public List<Object> getResourceResolvers() {
            throw new IllegalStateException("injected resolver lookup failure");
        }

        public List<Object> getResourceTransformers() { return Collections.emptyList(); }
    }

    private static final class FakeResourceUrlProvider
            extends org.springframework.web.servlet.resource.ResourceUrlProvider {
        private final Map<String, Object> handlerMap = new LinkedHashMap<String, Object>();

        private FakeResourceUrlProvider(Object handler) {
            handlerMap.put("/webjars/**", handler);
            handlerMap.put("/**", handler);
        }

        public Map<String, Object> getHandlerMap() { return handlerMap; }
    }

    private static final class FakeCachingComponent {
        private final FakeSpringCache cache;

        private FakeCachingComponent(FakeSpringCache cache) { this.cache = cache; }

        public FakeSpringCache getCache() { return cache; }
    }

    private static final class FakeFailingCachingComponent {
        public FakeFailingCache getCache() { return new FakeFailingCache(); }
    }

    private static final class FakeFailingCache {
        public void clear() { throw new IllegalStateException("injected clear failure"); }
    }

    private static final class FakeSpringCache {
        private int clearCount;

        public void clear() { clearCount++; }
    }

    private static final class FakeContextWithBeanFactory {
        private final FakeBeanFactory beanFactory;

        private FakeContextWithBeanFactory(FakeBeanFactory beanFactory) {
            this.beanFactory = beanFactory;
        }

        public Object getBean(String name) { return null; }
        public FakeBeanFactory getBeanFactory() { return beanFactory; }
    }

    private static final class FakeBeanFactory {
        private Class<?> queriedType;

        public String[] getBeanNamesForType(Class<?> type, boolean includeNonSingletons,
                                            boolean allowEagerInit) {
            queriedType = type;
            return new String[0];
        }

        public Object getBean(String name) { return null; }
    }
}

package dev.hotreload.agent.configreload;

import dev.hotreload.agent.logging.AgentSessionLogger;
import dev.hotreload.agent.spring.SpringContextRegistry;
import dev.hotreload.protocol.message.OperationStatus;
import dev.hotreload.protocol.message.ReloadErrorCode;
import dev.hotreload.protocol.message.ReloadItemResult;
import dev.hotreload.protocol.message.ReloadResponse;
import dev.hotreload.protocol.message.ResourceReloadRequest;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * 静态资源热重载：清理 Spring 资源缓存（ResourceUrlProvider、CachingResourceResolver 等）。
 */
public final class StaticResourceReloader {
    private final AgentSessionLogger logger;

    public StaticResourceReloader(AgentSessionLogger logger) {
        if (logger == null) throw new NullPointerException("logger");
        this.logger = logger;
    }

    public ReloadResponse reload(ResourceReloadRequest request) {
        long started = System.nanoTime();
        String path = request.getResourcePath();
        logger.log(Level.INFO, "STATIC_RECEIVED", fields(
                "requestId", request.getRequestId(),
                "resourceId", path,
                "payloadBytes", Integer.toString(request.getContentLength()),
                "contentType", request.getContentType()));

        try {
            List<Object> contexts = SpringContextRegistry.snapshot();
            if (contexts.isEmpty()) {
                return itemResponse(request, OperationStatus.FAILED, ReloadErrorCode.BRIDGE_UNAVAILABLE,
                        "spring_context_missing");
            }

            int cleared = 0;
            List<ReloadItemResult> items = new ArrayList<ReloadItemResult>();

            for (int i = 0; i < contexts.size(); i++) {
                Object context = contexts.get(i);
                boolean cacheClearedOk = clearResourceCache(context);

                if (cacheClearedOk) cleared++;

                String detail = "cache=" + (cacheClearedOk ? "cleared" : "unavailable");
                items.add(new ReloadItemResult(path + "@ctx" + i,
                        cacheClearedOk ? OperationStatus.SUCCESS : OperationStatus.SKIPPED,
                        null, cacheClearedOk ? "SUCCESS" : "SKIPPED", detail));
            }

            OperationStatus status = cleared > 0 ? OperationStatus.SUCCESS : OperationStatus.SKIPPED;
            logger.log(Level.INFO, "STATIC_RELOAD_RESULT", fields(
                    "requestId", request.getRequestId(),
                    "resourceId", path,
                    "resultCode", status.name(),
                    "detail", "contexts=" + contexts.size() + ",cleared=" + cleared
                            + ",ms=" + ((System.nanoTime() - started) / 1_000_000L)));
            return new ReloadResponse(request.getRequestId(), status, null, status.name(), items);

        } catch (Throwable failure) {
            logger.log(Level.WARNING, "STATIC_RELOAD_RESULT", fields(
                    "requestId", request.getRequestId(),
                    "resourceId", path,
                    "resultCode", "FAILED",
                    "detail", failure.getClass().getSimpleName()));
            return itemResponse(request, OperationStatus.FAILED, ReloadErrorCode.INTERNAL_ERROR,
                    failure.getClass().getSimpleName());
        }
    }

    /** Clears only Spring MVC components that directly own static-resource caches. */
    private boolean clearResourceCache(Object context) {
        try {
            ClassLoader loader = context.getClass().getClassLoader();
            boolean cleared = false;
            Set<Object> handlers = Collections.newSetFromMap(
                    new IdentityHashMap<Object, Boolean>());
            Class<?> providerType = loadClassSafe(loader,
                    "org.springframework.web.servlet.resource.ResourceUrlProvider");

            Object urlProvider = getBeanSafe(context, "mvcResourceUrlProvider");
            if (providerType == null || !providerType.isInstance(urlProvider)) {
                urlProvider = null;
            }
            if (urlProvider == null) {
                urlProvider = getBeanSafe(context, "resourceUrlProvider");
                if (providerType == null || !providerType.isInstance(urlProvider)) {
                    urlProvider = null;
                }
            }
            cleared |= clearCacheComponent(urlProvider);
            addProviderHandlers(urlProvider, handlers);

            // Standard Spring MVC handlers live in ResourceUrlProvider.handlerMap and are not
            // necessarily standalone beans. Exact-type discovery is only a compatibility fallback.
            if (handlers.isEmpty()) {
                Object beanFactory = invoke(context, "getBeanFactory");
                Class<?> handlerType = loadClassSafe(loader,
                        "org.springframework.web.servlet.resource.ResourceHttpRequestHandler");
                if (beanFactory != null && handlerType != null) {
                    Object names = invoke(beanFactory, "getBeanNamesForType",
                            new Class<?>[]{Class.class, boolean.class, boolean.class},
                            new Object[]{handlerType, false, false});
                    if (names instanceof String[]) {
                        for (String beanName : (String[]) names) {
                            Object handler = invoke(beanFactory, "getBean",
                                    new Class<?>[]{String.class}, new Object[]{beanName});
                            if (handler != null) handlers.add(handler);
                        }
                    }
                }
            }

            for (Object handler : handlers) {
                cleared |= clearResourceHandlerCaches(handler);
            }

            return cleared;
        } catch (Throwable failure) {
            logger.log(Level.FINE, "RESOURCE_CACHE_CLEAR_FAILED", fields(
                    "reason", failure.getClass().getSimpleName(),
                    "message", failure.getMessage() != null ? failure.getMessage() : ""));
            return false;
        }
    }

    private static void addProviderHandlers(Object provider, Set<Object> handlers) {
        if (provider == null || handlers == null) return;
        Object handlerMap = invoke(provider, "getHandlerMap");
        if (!(handlerMap instanceof Map)) return;
        for (Object handler : ((Map<?, ?>) handlerMap).values()) {
            if (handler != null) handlers.add(handler);
        }
    }

    static boolean clearResourceHandlerCaches(Object handler) {
        if (handler == null) return false;
        boolean cleared = clearCacheComponents(invoke(handler, "getResourceResolvers"));
        cleared |= clearCacheComponents(invoke(handler, "getResourceTransformers"));
        return cleared;
    }

    private static boolean clearCacheComponents(Object components) {
        if (!(components instanceof Iterable)) return false;
        boolean cleared = false;
        for (Object component : (Iterable<?>) components) {
            cleared |= clearCacheComponent(component);
        }
        return cleared;
    }

    private static boolean clearCacheComponent(Object component) {
        if (component == null) return false;
        if (invokeIfPresent(component, "clearCache")) return true;
        Object cache = invoke(component, "getCache");
        return cache != null && invokeIfPresent(cache, "clear");
    }

    private Object getBeanSafe(Object context, String beanName) {
        try {
            Method getBean = findMethod(context.getClass(), "getBean", new Class<?>[]{String.class});
            if (getBean == null) return null;
            getBean.setAccessible(true);
            return getBean.invoke(context, beanName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Class<?> loadClassSafe(ClassLoader loader, String className) {
        try {
            return Class.forName(className, false, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private ReloadResponse itemResponse(ResourceReloadRequest request, OperationStatus status,
                                        ReloadErrorCode code, String diagnostic) {
        ReloadItemResult item = new ReloadItemResult(request.getResourcePath(), status, code,
                status.name(), diagnostic);
        return new ReloadResponse(request.getRequestId(), status, code, status.name(),
                Collections.singletonList(item));
    }

    private static Object invoke(Object target, String method) {
        return invoke(target, method, new Class<?>[0], new Object[0]);
    }

    private static Object invoke(Object target, String method, Class<?>[] types, Object[] args) {
        try {
            Method m = findMethod(target.getClass(), method, types);
            if (m == null) return null;
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean invokeIfPresent(Object target, String method) {
        Method candidate = findMethod(target.getClass(), method);
        if (candidate == null) return false;
        try {
            candidate.setAccessible(true);
            candidate.invoke(target);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Method findMethod(Class<?> type, String name) {
        return findMethod(type, name, new Class<?>[0]);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>[] types) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, types);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        try {
            return type.getMethod(name, types);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Map<String, String> fields(String... kv) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        if (kv != null) {
            for (int i = 0; i + 1 < kv.length; i += 2) map.put(kv[i], kv[i + 1]);
        }
        return map;
    }
}

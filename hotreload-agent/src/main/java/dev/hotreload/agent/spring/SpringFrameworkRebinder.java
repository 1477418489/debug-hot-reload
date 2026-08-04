package dev.hotreload.agent.spring;

import dev.hotreload.agent.classes.ReflectionDataInvalidator;
import dev.hotreload.agent.classes.AnnotationHotReloadDiagnostics;
import dev.hotreload.agent.classes.RuntimeAnnotationIndex;
import dev.hotreload.agent.classes.RuntimeAnnotationPatcher;
import dev.hotreload.agent.classes.HotReloadClassRegistry;

import dev.hotreload.agent.logging.AgentSessionLogger;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Best-effort Spring rebind after class redefine/define.
 * Goals:
 * 1) make redefined annotations visible (JDK reflection + Spring annotation caches)
 * 2) clear AOP auto-proxy instance caches and recreate affected beans
 * 3) surgically refresh RequestMappingHandlerMapping without duplicate mappings
 */
public final class SpringFrameworkRebinder {
    private final AgentSessionLogger logger;

    public SpringFrameworkRebinder(AgentSessionLogger logger) {
        if (logger == null) throw new NullPointerException("logger");
        this.logger = logger;
    }

    public RebindReport rebind(Collection<Class<?>> changedTypes) {
        return rebind(changedTypes, true);
    }

    /**
     * @param refreshMappings when false, skip RequestMapping unregister/register.
     *        Use for non-mapping annotation/method-body changes to avoid partial route loss.
     */
    public RebindReport rebind(Collection<Class<?>> changedTypes, boolean refreshMappings) {
        return rebind(changedTypes, refreshMappings, null);
    }

    /**
     * @param refreshMappings when false, skip RequestMapping unregister/register.
     *        Use for non-mapping annotation/method-body changes to avoid partial route loss.
     * @param deepChangedNames binary names whose structure or annotations changed; null means
     *        treat every type as deep-changed (conservative). Body-only classes skip bean work.
     */
    public RebindReport rebind(Collection<Class<?>> changedTypes, boolean refreshMappings,
                               Set<String> deepChangedNames) {
        List<Class<?>> types = preferLiveGenerations(normalizeTypes(changedTypes));
        // E2 (enhanced redefine): class identity kept, no generation classes in the batch.
        // Beans stay alive — refresh injections in place instead of destroy+recreate.
        boolean identityKept = true;
        for (Class<?> type : types) {
            if (type != null && isGenerationClassName(type.getName())) {
                identityKept = false;
                break;
            }
        }
        List<Object> contexts = SpringContextRegistry.snapshot();
        if (contexts.isEmpty()) {
            contexts = discoverContextsFromLoadedClasses(types);
        }

        int reflectionInvalidated = ReflectionDataInvalidator.invalidateAll(types);
        // E3 only: clearing Class.reflectionData undoes RuntimeAnnotationPatcher, so re-apply
        // bytecode truth. E2 keeps class identity — real reflection IS the truth and forced
        // synthetic annotations would lose Spring @AliasFor merging.
        int reapplied = identityKept ? 0 : RuntimeAnnotationPatcher.reapplyFromIndex(types);
        int contextCount = contexts.size();
        int mappingRefreshed = 0;
        int annotationCachesCleared = 0;
        int beansRecreated = 0;
        int failures = 0;
        // Keep high-signal operational details first; annotations go last and are compact.
        Set<String> headDetails = new LinkedHashSet<String>();
        if (reflectionInvalidated > 0) {
            headDetails.add("reflectionInvalidated=" + reflectionInvalidated);
        }
        if (reapplied > 0) {
            headDetails.add("annotationRepatched=" + reapplied);
        }
        if (!refreshMappings) {
            headDetails.add("mappingRefresh=skippedNonMappingChange");
        }

        for (Object context : contexts) {
            try {
                ClassLoader loader = context.getClass().getClassLoader();
                annotationCachesCleared += clearAnnotationCaches(loader);
                clearAopCaches(context, loader, headDetails);
                clearFactoryCaches(context, headDetails);
                clearJacksonCaches(context, loader, headDetails);
                clearMethodAttributeCaches(context, loader, headDetails);

                // Recreate first so detectHandlerMethods sees a fresh proxy with updated annotations.
                if (identityKept) {
                    beansRecreated += refreshBeansInPlace(context, types, deepChangedNames, headDetails);
                } else {
                    beansRecreated += recreateBeansOfTypes(context, types, headDetails);
                }
                // One re-apply after recreate is enough; reflection invalidation already reapplied once above.
                if (!identityKept) {
                    reapplied += RuntimeAnnotationPatcher.reapplyFromIndex(types);
                }
                // AspectJ @annotation pointcuts still see stale metadata on JDK8; force index-aware matching.
                headDetails.add(AnnotationPointcutRebinder.rebind(context, types));
                if (refreshMappings && refreshRequestMappings(context, types, headDetails)) {
                    mappingRefreshed++;
                }
                if (AnnotationHotReloadDiagnostics.verboseEnabled()) {
                    probeAnnotationVisibility(types, headDetails);
                    AnnotationHotReloadDiagnostics.advisorAndProxyProbe(logger, "rebind", context, types);
                }
                headDetails.add(AnnotationHotReloadDiagnostics.compactProbeForReport(types));
            } catch (Throwable failure) {
                failures++;
                headDetails.add("contextFailed=" + failure.getClass().getSimpleName());
                logger.log(Level.WARNING, "SPRING_REBIND_CONTEXT_FAILED", fields(
                        "resultCode", failure.getClass().getSimpleName(),
                        "detail", String.valueOf(failure.getMessage())));
            }
        }

        Set<String> details = new LinkedHashSet<String>(headDetails);
        // Prefer bytecode-index truth for diagnostics when available.
        for (Class<?> type : types) {
            String fromIndex = RuntimeAnnotationIndex.describe(type);
            if (fromIndex != null && !fromIndex.endsWith("=none")) {
                details.add(fromIndex);
            }
        }
        details.add(describeAnnotations(types));

        RebindReport report = new RebindReport(contextCount, mappingRefreshed, annotationCachesCleared,
                beansRecreated, failures, details);
        logger.log(failures == 0 ? Level.INFO : Level.WARNING, "SPRING_REBIND_RESULT", fields(
                "configurationCount", Integer.toString(contextCount),
                "resultCode", failures == 0 ? "SUCCESS" : "PARTIAL",
                "detail", report.summary()));
        return report;
    }


    private static List<Class<?>> preferLiveGenerations(List<Class<?>> types) {
        if (types == null || types.isEmpty()) return types;
        List<Class<?>> out = new ArrayList<Class<?>>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Class<?> type : types) {
            if (type == null) continue;
            Class<?> live = HotReloadClassRegistry.resolveLive(type.getName());
            if (live == null) {
                // Registry key is original business name; generation class names need strip.
                live = HotReloadClassRegistry.resolveLive(
                        HotReloadClassRegistry.stripGenerationName(type.getName()));
            }
            Class<?> use = live != null ? live : type;
            if (seen.add(use.getName())) {
                out.add(use);
            }
            // Always keep original business type alongside generation for bean lookup.
            Class<?> base = originalBusinessType(use);
            if (base != null && base != use && seen.add(base.getName())) {
                out.add(base);
            }
        }
        return out;
    }

    private static List<Class<?>> normalizeTypes(Collection<Class<?>> changedTypes) {
        List<Class<?>> types = new ArrayList<Class<?>>();
        if (changedTypes == null) return types;
        Set<String> seen = new LinkedHashSet<String>();
        for (Class<?> type : changedTypes) {
            if (type == null) continue;
            Class<?> user = userClass(type);
            if (user == null) continue;
            if (seen.add(user.getName())) types.add(user);
        }
        return types;
    }

    private static List<Object> discoverContextsFromLoadedClasses(Collection<Class<?>> changedTypes) {
        List<Object> found = new ArrayList<Object>();
        try {
            ClassLoader loader = null;
            if (changedTypes != null) {
                for (Class<?> type : changedTypes) {
                    if (type != null && type.getClassLoader() != null) {
                        loader = type.getClassLoader();
                        break;
                    }
                }
            }
            if (loader == null) loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) return found;

            try {
                Class<?> holder = Class.forName("org.springframework.web.context.ContextLoader", false, loader);
                Method getCurrent = holder.getMethod("getCurrentWebApplicationContext");
                Object context = getCurrent.invoke(null);
                if (context != null) {
                    SpringContextRegistry.register(context);
                    found.add(context);
                }
            } catch (Throwable ignored) {
                // Spring web may not be present.
            }
        } catch (Throwable ignored) {
            // Spring may not be present.
        }
        return found;
    }

    private int clearAnnotationCaches(ClassLoader loader) {
        int cleared = 0;
        cleared += invokeClearCache(loader, "org.springframework.core.annotation.AnnotationUtils", "clearCache");
        cleared += invokeClearCache(loader, "org.springframework.core.annotation.AnnotatedElementUtils", "clearCache");
        cleared += invokeClearCache(loader, "org.springframework.util.ReflectionUtils", "clearCache");
        cleared += clearStaticMap(loader, "org.springframework.core.annotation.AnnotationUtils",
                "findAnnotationCache", "annotatedInterfaceCache", "metaPresentCache",
                "declaredAnnotationsCache", "annotatedBaseTypeCache", "synthesizableCache",
                "attributeAliasesCache", "attributeMethodsCache", "aliasMapCache");
        cleared += clearStaticMap(loader, "org.springframework.core.annotation.AnnotatedElementUtils",
                "mergedAnnotationsCache");
        cleared += clearStaticMap(loader, "org.springframework.core.MethodIntrospector", "metadataCache");
        return cleared;
    }

    private void clearAopCaches(Object context, ClassLoader loader, Set<String> details) {
        int cleared = 0;
        // Static caches (older Spring versions / shared builders)
        cleared += clearStaticMap(loader, "org.springframework.aop.framework.autoproxy.AbstractAdvisorAutoProxyCreator",
                "advisedBeans", "advisorRetrievalCache", "earlyProxyReferences", "proxyTypes");
        cleared += clearStaticMap(loader,
                "org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator",
                "aspectJAdvisorsBuilder");
        cleared += clearStaticMap(loader,
                "org.springframework.aop.aspectj.annotation.BeanFactoryAspectJAdvisorsBuilder",
                "advisorsCache", "aspectFactoryCache");

        // Real Spring AOP caches are INSTANCE fields on auto-proxy creators.
        try {
            Class<?> creatorType = Class.forName(
                    "org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator", false, loader);
            Collection<Object> creators = beansOfType(context, creatorType);
            for (Object creator : creators) {
                cleared += clearInstanceMaps(creator,
                        "advisedBeans", "earlyProxyReferences", "proxyTypes", "advisorRetrievalCache");
                Object builder = readField(creator, "aspectJAdvisorsBuilder");
                if (builder != null) {
                    // advisorsCache/aspectFactoryCache are maps and must be cleared.
                    // aspectBeanNames is a volatile List that is only rescanned when null.
                    // Clearing it to empty would permanently hide all aspects.
                    cleared += clearInstanceMaps(builder, "advisorsCache", "aspectFactoryCache");
                    if (nullInstanceField(builder, "aspectBeanNames")) {
                        cleared++;
                    }
                }
                Object advisorCache = readField(creator, "aspectJAdvisorsCache");
                clearIfMapOrCollection(advisorCache);
            }
            if (!creators.isEmpty()) {
                details.add("aopCreators=" + creators.size());
            }
        } catch (Throwable ignored) {
            // Spring AOP may not be present.
        }

        // Pointcut expression / advisor matching caches
        try {
            Class<?> pointcutType = Class.forName(
                    "org.springframework.aop.aspectj.AspectJExpressionPointcut", false, loader);
            Collection<Object> pointcuts = beansOfType(context, pointcutType);
            for (Object pointcut : pointcuts) {
                cleared += clearInstanceMaps(pointcut, "shadowMatchCache");
            }
        } catch (Throwable ignored) {
            // ignore
        }

        // Clear annotation-based pointcut method matcher caches.
        try {
            Collection<Object> advisors = beansOfType(context, Class.forName(
                    "org.springframework.aop.Advisor", false, loader));
            for (Object advisor : advisors) {
                Object advice = invoke(advisor, "getAdvice");
                Object pointcut = invoke(advisor, "getPointcut");
                if (pointcut != null) {
                    cleared += clearInstanceMaps(pointcut, "shadowMatchCache", "methodMatcher");
                    Object matcher = invoke(pointcut, "getMethodMatcher");
                    if (matcher != null) {
                        cleared += clearInstanceMaps(matcher, "shadowMatchCache", "cache", "methodCache");
                        // force re-evaluation by nulling internal caches when present
                        if (nullInstanceField(matcher, "methodCache")) cleared++;
                        if (nullInstanceField(matcher, "cache")) cleared++;
                    }
                }
                // also clear IntroductionAwareMethodMatcher wrappers
            }
            details.add("advisorsScanned=" + advisors.size());
        } catch (Throwable ignored) {
            // ignore
        }

        // ReflectAnnotationMatchingPointcut / AnnotationMethodMatcher static or instance caches.
        cleared += clearStaticMap(loader,
                "org.springframework.aop.support.annotation.AnnotationMethodMatcher",
                "methodCache");

        // CGLIB caches generated proxy classes keyed by superclass. With identity-kept redefine
        // the key never changes, so a rebuilt bean would reuse the OLD proxy class lacking
        // overrides for new methods — calls then land on the proxy instance itself, whose
        // injected fields are always null (values live on the AOP target). Reset the cache so
        // the next enhancement regenerates the proxy class from post-redefine reflection.
        cleared += resetCglibGeneratedClassCache(loader, details);

        if (cleared > 0) details.add("aopCaches=cleared:" + cleared);
    }

    private static int resetCglibGeneratedClassCache(ClassLoader loader, Set<String> details) {
        int reset = 0;
        for (String generatorName : new String[] {
                "org.springframework.cglib.core.AbstractClassGenerator",
                "net.sf.cglib.core.AbstractClassGenerator"}) {
            try {
                Class<?> type = Class.forName(generatorName, false, loader);
                Field cacheField = findField(type, "CACHE");
                if (cacheField == null) continue;
                cacheField.setAccessible(true);
                Object cache = cacheField.get(null);
                if (!(cache instanceof Map)) continue;
                for (Object data : new ArrayList<Object>(((Map<?, ?>) cache).values())) {
                    if (data == null) continue;
                    // Clear ONLY generatedClasses. reservedClassNames MUST survive: the naming
                    // policy consults it to append a fresh _N suffix — wiping it regenerates the
                    // same proxy class name and defineClass dies with a duplicate LinkageError.
                    Object generated = readField(data, "generatedClasses");
                    Object innerMap = generated == null ? null : readField(generated, "map");
                    if (innerMap instanceof Map) {
                        ((Map<?, ?>) innerMap).clear();
                        reset++;
                    }
                }
            } catch (Throwable ignored) {
                // cglib flavor not on classpath
            }
        }
        if (reset > 0) details.add("cglibClassCache=reset:" + reset);
        return reset;
    }

    /**
     * Jackson keeps per-class (de)serializers; annotation edits like @JsonIgnore/@JsonFormat
     * stay invisible on redefined classes until those caches flush. Best-effort, Jackson optional.
     */
    private void clearJacksonCaches(Object context, ClassLoader loader, Set<String> details) {
        try {
            Class<?> mapperType = Class.forName("com.fasterxml.jackson.databind.ObjectMapper", false, loader);
            Collection<Object> mappers = beansOfType(context, mapperType);
            int flushed = 0;
            for (Object mapper : mappers) {
                if (mapper == null) continue;
                Object serializerProvider = readField(mapper, "_serializerProvider");
                if (serializerProvider != null) {
                    invokeIfPresent(serializerProvider, "flushCachedSerializers");
                    flushed++;
                }
                Object deserializationContext = readField(mapper, "_deserializationContext");
                Object deserializerCache = deserializationContext == null
                        ? null : readField(deserializationContext, "_cache");
                if (deserializerCache != null) {
                    invokeIfPresent(deserializerCache, "flushCachedDeserializers");
                }
                clearInstanceMap(mapper, "_rootDeserializers");
            }
            if (!mappers.isEmpty()) {
                details.add("jacksonCaches=flushed:" + flushed + "/" + mappers.size());
            }
        } catch (ClassNotFoundException absent) {
            // Jackson not on classpath.
        } catch (Throwable failure) {
            details.add("jacksonCacheClearFailed=" + failure.getClass().getSimpleName());
        }
    }

    /**
     * @Transactional / @PreAuthorize attribute sources cache per (method, targetClass);
     * without eviction, annotation add/remove on existing methods keeps the old attributes.
     */
    private void clearMethodAttributeCaches(Object context, ClassLoader loader, Set<String> details) {
        int cleared = 0;
        for (String typeName : new String[] {
                "org.springframework.transaction.interceptor.TransactionAttributeSource",
                "org.springframework.security.access.method.MethodSecurityMetadataSource"}) {
            try {
                Class<?> type = Class.forName(typeName, false, loader);
                for (Object source : beansOfType(context, type)) {
                    cleared += clearInstanceMaps(source, "attributeCache");
                }
            } catch (Throwable ignored) {
                // framework flavor not on classpath
            }
        }
        if (cleared > 0) details.add("methodAttributeCaches=cleared:" + cleared);
    }

    private int invokeClearCache(ClassLoader loader, String className, String methodName) {
        try {
            Class<?> type = Class.forName(className, false, loader);
            Method method = type.getMethod(methodName);
            method.invoke(null);
            return 1;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private int clearStaticMap(ClassLoader loader, String className, String... fieldNames) {
        int cleared = 0;
        try {
            Class<?> type = Class.forName(className, false, loader);
            for (String fieldName : fieldNames) {
                try {
                    Field field = findField(type, fieldName);
                    if (field == null) continue;
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value instanceof Map) {
                        ((Map<?, ?>) value).clear();
                        cleared++;
                    } else if (value instanceof Collection) {
                        ((Collection<?>) value).clear();
                        cleared++;
                    } else if (value != null) {
                        try {
                            field.set(null, null);
                            cleared++;
                        } catch (Throwable ignored) {
                            // not null-able
                        }
                    }
                } catch (Throwable ignored) {
                    // Best-effort only.
                }
            }
        } catch (Throwable ignored) {
            // Class not present.
        }
        return cleared;
    }

    private void clearFactoryCaches(Object context, Set<String> details) {
        try {
            Object factory = invoke(context, "getBeanFactory");
            if (factory == null) return;
            invokeIfPresent(factory, "clearMetadataCache");
            invokeIfPresent(factory, "clearByTypeCache");
            clearInstanceMap(factory, "allBeanNamesByType");
            clearInstanceMap(factory, "singletonBeanNamesByType");
            clearInstanceMap(factory, "filteredBeanNamesByTypeCache");
            // Do NOT clear mergedBeanDefinitions: destroying that map can break singleton recreation.
            details.add("factoryCaches=cleared");
        } catch (Throwable failure) {
            details.add("factoryCacheClearFailed=" + failure.getClass().getSimpleName());
        }
    }

    int recreateBeansOfTypes(Object context, Collection<Class<?>> changedTypes, Set<String> details) {
        if (changedTypes == null || changedTypes.isEmpty()) return 0;
        int recreated = 0;
        Object factory = invoke(context, "getBeanFactory");
        // Same bean resolves from both generation and base type; recreate once (generation first).
        Set<String> handledBeanNames = new LinkedHashSet<String>();
        for (Class<?> changed : changedTypes) {
            if (changed == null) continue;
            try {
                Class<?> searchType = originalBusinessType(changed);
                // If this is a generation class for an existing bean name, force bean class upgrade.
                upgradeBeanDefinitions(factory, changed, details);
                // Prefer original type names first (Spring still knows original bean class), then gen type.
                String[] names = beanNamesForType(context, factory, searchType);
                if ((names == null || names.length == 0) && searchType != changed) {
                    names = beanNamesForType(context, factory, changed);
                }
                // Always merge guessed controller/service bean name — getBeanNamesByType can
                // miss generation subclasses when factory type caches are stale.
                String guessed = guessBeanName(searchType);
                if (guessed == null) guessed = guessBeanName(changed);
                LinkedHashSet<String> nameSet = new LinkedHashSet<String>();
                if (names != null) {
                    for (int i = 0; i < names.length; i++) {
                        if (names[i] != null) nameSet.add(names[i]);
                    }
                }
                if (guessed != null && containsBeanDefinition(factory, guessed)) {
                    nameSet.add(guessed);
                }
                if (nameSet.isEmpty()) {
                    // register a lightweight singleton definition for brand-new @Component-like classes
                    if (looksLikeComponent(changed) && registerDynamicBean(factory, changed, details)) {
                        String dyn = guessBeanName(changed);
                        if (dyn != null) nameSet.add(dyn);
                    } else {
                        details.add("noBean=" + changed.getSimpleName()
                                + (guessed == null ? "" : ":guess=" + guessed));
                        continue;
                    }
                }
                names = nameSet.toArray(new String[nameSet.size()]);
                for (String name : names) {
                    if (!handledBeanNames.add(name)) {
                        continue;
                    }
                    try {
                        // Stale injection metadata keeps the old @Autowired/@Value/@Resource view
                        // after redefine (same Class identity), so recreation would skip new fields.
                        clearInjectionMetadata(factory, name, changed);
                        destroySingleton(factory, name);
                        Object bean = getBeanOrThrow(context, name);
                        if (bean != null) {
                            recreated++;
                            boolean proxy = isProxyClass(bean.getClass());
                            details.add("recreated=" + name + ":" + bean.getClass().getSimpleName()
                                    + ":proxy=" + proxy
                                    + (HotReloadClassRegistry.generation(originalBusinessType(changed).getName()) > 0
                                    ? ":gen=" + HotReloadClassRegistry.generation(originalBusinessType(changed).getName()) : ""));
                        }
                    } catch (Throwable failure) {
                        details.add("recreateFailed=" + name + ":" + rootName(failure)
                                + ":" + rootMessage(failure));
                    }
                }
            } catch (Throwable failure) {
                details.add("beanNamesFailed=" + changed.getSimpleName());
            }
        }
        return recreated;
    }


    /**
     * E2 path (class identity kept by enhanced redefine): live instances already run the new
     * code, so beans are refreshed in place — injection metadata rebuilt and new
     * @Autowired/@Value/@Resource fields injected on the existing instance (state preserved).
     * Bean recreation only happens when a CGLIB proxy went stale (method set changed and the
     * proxy class lacks overrides for new methods, which would bypass interceptors).
     */
    int refreshBeansInPlace(Object context, Collection<Class<?>> changedTypes,
                            Set<String> deepChangedNames, Set<String> details) {
        if (changedTypes == null || changedTypes.isEmpty()) return 0;
        int touched = 0;
        Object factory = invoke(context, "getBeanFactory");
        Set<String> handledBeanNames = new LinkedHashSet<String>();
        for (Class<?> changed : changedTypes) {
            if (changed == null) continue;
            try {
                boolean deepChange = deepChangedNames == null
                        || deepChangedNames.contains(changed.getName())
                        || deepChangedNames.contains(originalBusinessType(changed).getName());
                String[] names = beanNamesForType(context, factory, changed);
                String guessed = guessBeanName(changed);
                LinkedHashSet<String> nameSet = new LinkedHashSet<String>();
                if (names != null) {
                    for (int i = 0; i < names.length; i++) {
                        if (names[i] != null) nameSet.add(names[i]);
                    }
                }
                if (guessed != null && containsBeanDefinition(factory, guessed)) {
                    nameSet.add(guessed);
                }
                if (nameSet.isEmpty()) {
                    if (looksLikeComponent(changed) && registerDynamicBean(factory, changed, details)) {
                        String dyn = guessBeanName(changed);
                        if (dyn != null && getBeanQuietly(context, dyn) != null) {
                            touched++;
                            details.add("registeredNewBean=" + dyn);
                        }
                    }
                    continue;
                }
                for (String name : nameSet) {
                    if (!handledBeanNames.add(name)) continue;
                    Object bean = getSingletonIfPresent(factory, name);
                    if (bean == null) {
                        details.add("notInstantiated=" + name);
                        continue;
                    }
                    if (!deepChange) {
                        details.add("refreshSkipped=" + name + ":bodyOnly");
                        continue;
                    }
                    boolean proxy = isProxyClass(bean.getClass());
                    if (proxy && proxyOutdated(bean.getClass(), changed)) {
                        // Stale CGLIB proxy: new methods would bypass security/tx interceptors.
                        try {
                            clearInjectionMetadata(factory, name, changed);
                            destroySingleton(factory, name);
                            Object recreated = getBeanOrThrow(context, name);
                            if (recreated != null) {
                                touched++;
                                details.add("proxyRebuilt=" + name + ":" + recreated.getClass().getSimpleName());
                            }
                        } catch (Throwable failure) {
                            details.add("recreateFailed=" + name + ":" + rootName(failure)
                                    + ":" + rootMessage(failure));
                        }
                        continue;
                    }
                    if (!proxy && wouldBeProxiedNow(context, name, changed)) {
                        // Un-proxied bean whose new annotations now match SOME advisor
                        // (standard or custom aspect alike — Spring itself decides): recreate so
                        // the auto-proxy creator can weave; in-place refresh cannot add a proxy.
                        try {
                            clearInjectionMetadata(factory, name, changed);
                            destroySingleton(factory, name);
                            Object recreated = getBeanOrThrow(context, name);
                            if (recreated != null) {
                                touched++;
                                details.add("proxyIntroduced=" + name
                                        + ":proxy=" + isProxyClass(recreated.getClass()));
                            }
                        } catch (Throwable failure) {
                            details.add("recreateFailed=" + name + ":" + rootName(failure)
                                    + ":" + rootMessage(failure));
                        }
                        continue;
                    }
                    // Keep instance and state: rebuild injection metadata, re-inject on the AOP target.
                    Object target = proxy ? unwrapAopTarget(bean) : bean;
                    clearInjectionMetadata(factory, name, changed);
                    int processors = reinject(factory, target, name, details);
                    if (proxy) {
                        Object advised = advisedConfigOf(bean);
                        // Per-method interceptor chains are cached on the proxy config; without a
                        // flush, annotation add/remove keeps serving the OLD chain forever.
                        if (advised != null && clearInstanceMaps(advised, "methodCache") > 0) {
                            details.add("proxyChainCache=flushed:" + name);
                        }
                        // First introduction of an aspect annotation: its advisor was never
                        // eligible for this bean, chain recomputation cannot help — rebuild.
                        if (advisorCoverageOutdated(context, name, changed, advised)) {
                            try {
                                destroySingleton(factory, name);
                                Object recreated = getBeanOrThrow(context, name);
                                if (recreated != null) {
                                    touched++;
                                    details.add("advisorIntroduced=" + name);
                                }
                            } catch (Throwable failure) {
                                details.add("recreateFailed=" + name + ":" + rootName(failure)
                                        + ":" + rootMessage(failure));
                            }
                            continue;
                        }
                    }
                    touched++;
                    details.add("refreshedInPlace=" + name + ":proxy=" + proxy
                            + ":injectors=" + processors);
                }
            } catch (Throwable failure) {
                details.add("refreshFailed=" + changed.getSimpleName() + ":" + rootName(failure));
            }
        }
        return touched;
    }

    /** Extract the AdvisedSupport config from a CGLIB or JDK Spring AOP proxy. */
    private static Object advisedConfigOf(Object proxy) {
        if (proxy == null) return null;
        try {
            // CGLIB: callback 0 is DynamicAdvisedInterceptor holding "advised".
            Object callback = readField(proxy, "CGLIB$CALLBACK_0");
            if (callback != null) {
                Object advised = readField(callback, "advised");
                if (advised != null) return advised;
            }
            if (java.lang.reflect.Proxy.isProxyClass(proxy.getClass())) {
                Object handler = java.lang.reflect.Proxy.getInvocationHandler(proxy);
                Object advised = readField(handler, "advised");
                if (advised != null) return advised;
            }
        } catch (Throwable ignored) {
            // unknown proxy flavor
        }
        return null;
    }

    /**
     * Ask Spring itself whether this (currently un-proxied) bean would be proxied now:
     * any auto-proxy creator returning eligible advisors means an aspect annotation was
     * introduced. Fully generic: no annotation name list, custom aspects included.
     */
    boolean wouldBeProxiedNow(Object context, String beanName, Class<?> targetClass) {
        if (targetClass == null) return false;
        try {
            Object factory = invoke(context, "getBeanFactory");
            for (Object creator : advisorRecomputers(factory)) {
                Method recompute = findMethodNamed(creator.getClass(),
                        "getAdvicesAndAdvisorsForBean", 3);
                recompute.setAccessible(true);
                Object result = recompute.invoke(creator, targetClass, beanName, null);
                if (result instanceof Object[] && ((Object[]) result).length > 0) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // no AOP infrastructure: nothing to introduce
        }
        return false;
    }

    /** Auto-proxy creators are BeanPostProcessors: pick any processor exposing the recompute hook. */
    private static List<Object> advisorRecomputers(Object factory) {
        List<Object> creators = new ArrayList<Object>();
        Object processors = invoke(factory, "getBeanPostProcessors");
        if (processors instanceof Collection) {
            for (Object processor : (Collection<?>) processors) {
                if (processor != null
                        && findMethodNamed(processor.getClass(), "getAdvicesAndAdvisorsForBean", 3) != null) {
                    creators.add(processor);
                }
            }
        }
        return creators;
    }

    private static Method findMethodNamed(Class<?> type, String name, int parameterCount) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (name.equals(method.getName())
                        && method.getParameterTypes().length == parameterCount) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /**
     * True when the auto-proxy creator would now give this bean MORE advisors than its live
     * proxy carries — i.e. an aspect annotation was introduced whose advisor was never eligible
     * before. Removal needs no rebuild: recomputed chains simply stop matching the method.
     */
    private boolean advisorCoverageOutdated(Object context, String beanName, Class<?> targetClass,
                                            Object advised) {
        if (advised == null || targetClass == null) return false;
        try {
            Object advisors = invoke(advised, "getAdvisors");
            if (!(advisors instanceof Object[])) return false;
            int effective = ((Object[]) advisors).length;
            for (Object advisor : (Object[]) advisors) {
                Object advice = invoke(advisor, "getAdvice");
                if (advice != null
                        && advice.getClass().getName().endsWith("ExposeInvocationInterceptor")) {
                    effective--;
                }
            }
            ClassLoader loader = context.getClass().getClassLoader();
            Object factory = invoke(context, "getBeanFactory");
            for (Object creator : advisorRecomputers(factory)) {
                Method recompute = findMethodNamed(creator.getClass(),
                        "getAdvicesAndAdvisorsForBean", 3);
                recompute.setAccessible(true);
                Object result = recompute.invoke(creator, targetClass, beanName, null);
                if (result instanceof Object[] && ((Object[]) result).length > effective) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // best-effort; the chain-cache flush already covers matcher-level changes
        }
        return false;
    }

    /** CGLIB proxy is stale when the target declares invocable methods the proxy never overrode. */
    static boolean proxyOutdated(Class<?> proxyClass, Class<?> targetClass) {
        if (proxyClass == null || targetClass == null || proxyClass == targetClass) return false;
        // JDK interface proxies expose a fixed interface method set; new class methods are not
        // reachable through them, so there is no interceptor-bypass risk to fix by rebuilding.
        if (java.lang.reflect.Proxy.isProxyClass(proxyClass)) return false;
        try {
            for (Method method : targetClass.getDeclaredMethods()) {
                int modifiers = method.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(modifiers)
                        || java.lang.reflect.Modifier.isPrivate(modifiers)
                        || java.lang.reflect.Modifier.isFinal(modifiers)
                        || method.isSynthetic() || method.isBridge()) {
                    continue;
                }
                if (!declaresMethod(proxyClass, method)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            // Be conservative: rebuilding is safe, a stale proxy is not.
            return true;
        }
    }

    private static boolean declaresMethod(Class<?> type, Method wanted) {
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals(wanted.getName())) continue;
            if (java.util.Arrays.equals(method.getParameterTypes(), wanted.getParameterTypes())) {
                return true;
            }
        }
        return false;
    }

    /** Resolve the AOP target instance so field injection reaches the real object, not the proxy. */
    private static Object unwrapAopTarget(Object bean) {
        if (bean == null) return null;
        try {
            Class<?> advised = Class.forName("org.springframework.aop.framework.Advised",
                    false, bean.getClass().getClassLoader());
            if (!advised.isInstance(bean)) return bean;
            Object targetSource = invoke(bean, "getTargetSource");
            Object target = invoke(targetSource, "getTarget");
            return target != null ? target : bean;
        } catch (Throwable ignored) {
            return bean;
        }
    }

    /**
     * Re-run field injection on a live instance via the annotation post-processors.
     * AutowiredAnnotationBeanPostProcessor.processInjection is a public Spring API for exactly
     * this; @Resource goes through CommonAnnotationBeanPostProcessor.postProcessProperties.
     */
    private static int reinject(Object factory, Object targetInstance, String beanName, Set<String> details) {
        if (targetInstance == null) return 0;
        int applied = 0;
        try {
            Object processors = invoke(factory, "getBeanPostProcessors");
            if (!(processors instanceof Collection)) return 0;
            for (Object processor : (Collection<?>) processors) {
                if (processor == null) continue;
                Method processInjection = findMethod(processor.getClass(), "processInjection", Object.class);
                if (processInjection != null) {
                    try {
                        processInjection.setAccessible(true);
                        processInjection.invoke(processor, targetInstance);
                        applied++;
                    } catch (Throwable failure) {
                        details.add("reinjectFailed=" + beanName + ":" + rootName(failure)
                                + ":" + rootMessage(failure));
                    }
                    continue;
                }
                String processorName = processor.getClass().getName();
                if (processorName.endsWith("CommonAnnotationBeanPostProcessor")) {
                    if (invokePostProcessProperties(processor, targetInstance, beanName)) {
                        applied++;
                    }
                }
            }
        } catch (Throwable ignored) {
            // best-effort
        }
        return applied;
    }

    private static boolean invokePostProcessProperties(Object processor, Object bean, String beanName) {
        try {
            Class<?> propertyValues = Class.forName("org.springframework.beans.PropertyValues",
                    false, processor.getClass().getClassLoader());
            Method modern = findMethod(processor.getClass(), "postProcessProperties",
                    propertyValues, Object.class, String.class);
            if (modern != null) {
                modern.setAccessible(true);
                modern.invoke(processor, null, bean, beanName);
                return true;
            }
            Class<?> descriptors = Class.forName("[Ljava.beans.PropertyDescriptor;",
                    false, processor.getClass().getClassLoader());
            Method legacy = findMethod(processor.getClass(), "postProcessPropertyValues",
                    propertyValues, descriptors, Object.class, String.class);
            if (legacy != null) {
                legacy.setAccessible(true);
                legacy.invoke(processor, null, null, bean, beanName);
                return true;
            }
        } catch (Throwable ignored) {
            // best-effort
        }
        return false;
    }

    private static Object getSingletonIfPresent(Object factory, String name) {
        if (factory == null || name == null) return null;
        try {
            Method twoArg = findMethod(factory.getClass(), "getSingleton", String.class, boolean.class);
            if (twoArg != null) {
                twoArg.setAccessible(true);
                return twoArg.invoke(factory, name, Boolean.FALSE);
            }
            Method oneArg = findMethod(factory.getClass(), "getSingleton", String.class);
            if (oneArg != null) {
                oneArg.setAccessible(true);
                return oneArg.invoke(factory, name);
            }
        } catch (Throwable ignored) {
            // best-effort
        }
        return null;
    }

    private static Object getBeanQuietly(Object context, String name) {
        try {
            return getBeanOrThrow(context, name);
        } catch (Throwable ignored) {
            return null;
        }
    }


    /** Unwrap generation subclasses back to the original business type Spring registered. */
    private static Class<?> originalBusinessType(Class<?> type) {
        Class<?> cur = type;
        while (cur != null && isGenerationClassName(cur.getName())) {
            Class<?> superType = cur.getSuperclass();
            if (superType == null || superType == Object.class) break;
            cur = superType;
        }
        return cur == null ? type : cur;
    }

    static boolean isGenerationClassName(String name) {
        return name != null && (name.contains("__HrGen") || name.contains("$$HrGen"));
    }

    /** Test hook: derive Spring default bean name from a binary name. */
    static String guessBeanNameForTest(String binaryName) {
        if (binaryName == null) return null;
        try {
            // Avoid Class.forName for generation names that are not loaded.
            String simple = binaryName;
            int slash = simple.lastIndexOf('.');
            if (slash >= 0) simple = simple.substring(slash + 1);
            int enhancer = simple.indexOf("$$");
            if (enhancer > 0) simple = simple.substring(0, enhancer);
            int gen = simple.indexOf("__HrGen");
            if (gen > 0) simple = simple.substring(0, gen);
            if (simple.isEmpty()) return null;
            return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
        } catch (Throwable ignored) {
            return null;
        }
    }

    void upgradeBeanDefinitions(Object factory, Class<?> changed, Set<String> details) {
        if (factory == null || changed == null) return;
        try {
            Class<?> searchType = originalBusinessType(changed);
            // Find bean names that currently resolve to original/business type.
            String[] names = beanNamesForType(null, factory, searchType);
            if ((names == null || names.length == 0) && searchType != changed) {
                names = beanNamesForType(null, factory, changed);
            }
            if (names == null || names.length == 0) {
                // also scan by simple name
                String guessed = guessBeanName(searchType);
                if (guessed == null) guessed = guessBeanName(changed);
                if (guessed != null) names = new String[]{guessed};
            }
            if (names == null) return;
            for (String name : names) {
                if (!containsBeanDefinition(factory, name)) continue;
                Object def = invoke(factory, "getBeanDefinition", new Class<?>[]{String.class}, new Object[]{name});
                if (def == null) continue;
                String currentClassName = beanClassNameOf(def);
                if (!shouldReplaceBeanClass(currentClassName, changed.getName())) {
                    // types carry [generation, base]; the base pass must never downgrade the
                    // definition back below the live generation (lost new @RequestMapping methods).
                    details.add("beanClassKept=" + name + ":" + currentClassName);
                    continue;
                }
                // RootBeanDefinition.setBeanClass(Class)
                Method setBeanClass = findMethod(def.getClass(), "setBeanClass", Class.class);
                if (setBeanClass != null) {
                    setBeanClass.setAccessible(true);
                    setBeanClass.invoke(def, changed);
                    details.add("beanClassUpgraded=" + name + "->" + changed.getName());
                } else {
                    Method setBeanClassName = findMethod(def.getClass(), "setBeanClassName", String.class);
                    if (setBeanClassName != null) {
                        setBeanClassName.setAccessible(true);
                        setBeanClassName.invoke(def, changed.getName());
                        details.add("beanClassNameUpgraded=" + name);
                    }
                }
                // Clear merged definition cache entry if present.
                try {
                    Object merged = readField(factory, "mergedBeanDefinitions");
                    if (merged instanceof Map) ((Map<?, ?>) merged).remove(name);
                } catch (Throwable ignored) { }
            }
        } catch (Throwable failure) {
            details.add("upgradeBeanDefFailed=" + changed.getSimpleName());
        }
    }

    private boolean registerDynamicBean(Object factory, Class<?> type, Set<String> details) {
        if (factory == null || type == null) return false;
        String name = guessBeanName(type);
        if (name == null || containsBeanDefinition(factory, name)) return false;
        try {
            ClassLoader loader = type.getClassLoader();
            Class<?> rbdType = Class.forName("org.springframework.beans.factory.support.RootBeanDefinition", false, loader);
            Object def = rbdType.getConstructor(Class.class).newInstance(type);
            Method register = findMethod(factory.getClass(), "registerBeanDefinition", String.class, Class.forName(
                    "org.springframework.beans.factory.config.BeanDefinition", false, loader));
            if (register == null) return false;
            register.setAccessible(true);
            register.invoke(factory, name, def);
            details.add("dynamicBeanRegistered=" + name + ":" + type.getSimpleName());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean containsBeanDefinition(Object factory, String name) {
        if (factory == null || name == null) return false;
        try {
            Method m = findMethod(factory.getClass(), "containsBeanDefinition", String.class);
            if (m == null) return false;
            Object v = m.invoke(factory, name);
            return v instanceof Boolean && ((Boolean) v).booleanValue();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Bean definition class replacement guard: never downgrade a live generation back to
     * its base class or an older generation. Base class counts as generation 0.
     */
    static boolean shouldReplaceBeanClass(String currentName, String candidateName) {
        if (candidateName == null) return false;
        if (currentName == null) return true;
        if (currentName.equals(candidateName)) return false;
        String currentBase = HotReloadClassRegistry.stripGenerationName(currentName);
        String candidateBase = HotReloadClassRegistry.stripGenerationName(candidateName);
        if (!currentBase.equals(candidateBase)) return true;
        return generationOrdinal(candidateName) > generationOrdinal(currentName);
    }

    /** StructureSubclassFactory SEQ is globally monotonic: later generations always get a larger N. */
    private static int generationOrdinal(String binaryName) {
        int idx = Math.max(binaryName.lastIndexOf("__HrGen"), binaryName.lastIndexOf("$$HrGen"));
        if (idx < 0) return 0;
        int value = 0;
        boolean digits = false;
        for (int i = idx + "__HrGen".length(); i < binaryName.length(); i++) {
            char c = binaryName.charAt(i);
            if (c < '0' || c > '9') break;
            value = value * 10 + (c - '0');
            digits = true;
        }
        return digits ? value : 0;
    }

    private static String beanClassNameOf(Object def) {
        Object name = invoke(def, "getBeanClassName");
        return name instanceof String ? (String) name : null;
    }

    /** getBean that surfaces the real failure instead of swallowing it into a silent null. */
    private static Object getBeanOrThrow(Object context, String name) throws Throwable {
        Method method = findMethod(context.getClass(), "getBean", String.class);
        if (method == null) return null;
        method.setAccessible(true);
        try {
            return method.invoke(context, name);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw failure.getCause() != null ? failure.getCause() : failure;
        }
    }

    /**
     * Evict injection/lifecycle metadata cached by annotation post-processors
     * (AutowiredAnnotationBeanPostProcessor, CommonAnnotationBeanPostProcessor, ...).
     * Their caches key by bean name or Class and never refresh on redefine.
     */
    static void clearInjectionMetadata(Object factory, String beanName, Class<?> changed) {
        try {
            Object processors = invoke(factory, "getBeanPostProcessors");
            if (!(processors instanceof Collection)) return;
            for (Object processor : (Collection<?>) processors) {
                if (processor == null) continue;
                removeCacheEntry(processor, "injectionMetadataCache", beanName, changed);
                removeCacheEntry(processor, "lifecycleMetadataCache", beanName, changed);
            }
        } catch (Throwable ignored) {
            // Best-effort: missing processors/fields on unknown Spring versions.
        }
    }

    private static void removeCacheEntry(Object target, String fieldName, String beanName, Class<?> changed) {
        Object value = readField(target, fieldName);
        if (!(value instanceof Map)) return;
        Map<?, ?> cache = (Map<?, ?>) value;
        // injectionMetadataCache keys by beanName (or class name fallback); lifecycle by Class.
        if (beanName != null) cache.remove(beanName);
        if (changed != null) {
            cache.remove(changed);
            cache.remove(changed.getName());
            Class<?> business = originalBusinessType(changed);
            if (business != null && business != changed) {
                cache.remove(business);
                cache.remove(business.getName());
            }
        }
    }

    private static String guessBeanName(Class<?> type) {
        if (type == null) return null;
        Class<?> business = originalBusinessType(type);
        String simple = business == null ? type.getSimpleName() : business.getSimpleName();
        if (simple == null || simple.isEmpty()) return null;
        // Drop Spring CGLIB noise if still present.
        int enhancer = simple.indexOf("$$");
        if (enhancer > 0) simple = simple.substring(0, enhancer);
        int gen = simple.indexOf("__HrGen");
        if (gen > 0) simple = simple.substring(0, gen);
        if (simple.isEmpty()) return null;
        return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
    }

    private static boolean looksLikeComponent(Class<?> type) {
        if (type == null) return false;
        try {
            Annotation[] anns = type.getAnnotations();
            for (Annotation ann : anns) {
                String n = ann.annotationType().getName();
                if (n.endsWith(".Component") || n.endsWith(".Service") || n.endsWith(".Repository")
                        || n.endsWith(".Controller") || n.endsWith(".RestController")
                        || n.endsWith(".Configuration")) return true;
            }
        } catch (Throwable ignored) { }
        // also consult bytecode index
        try {
            String desc = RuntimeAnnotationIndex.describe(type);
            if (desc != null && (desc.contains("Component") || desc.contains("Service")
                    || desc.contains("Repository") || desc.contains("Controller")
                    || desc.contains("Configuration"))) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    private static String[] beanNamesForType(Object context, Object factory, Class<?> type) {
        try {
            if (factory != null) {
                Method method = findMethod(factory.getClass(), "getBeanNamesForType",
                        Class.class, boolean.class, boolean.class);
                if (method != null) {
                    method.setAccessible(true);
                    // includeNonSingletons=true, allowEagerInit=false
                    Object names = method.invoke(factory, type, Boolean.TRUE, Boolean.FALSE);
                    if (names instanceof String[]) return (String[]) names;
                }
            }
            Method method = findMethod(context.getClass(), "getBeanNamesForType", Class.class);
            if (method != null) {
                Object names = method.invoke(context, type);
                if (names instanceof String[]) return (String[]) names;
            }
        } catch (Throwable ignored) {
            // Fall through.
        }
        return new String[0];
    }

    private static void destroySingleton(Object factory, String beanName) {
        if (factory == null || beanName == null) return;
        try {
            Method destroy = findMethod(factory.getClass(), "destroySingleton", String.class);
            if (destroy != null) {
                destroy.setAccessible(true);
                destroy.invoke(factory, beanName);
            }
            Method removeSingleton = findMethod(factory.getClass(), "removeSingleton", String.class);
            if (removeSingleton != null) {
                removeSingleton.setAccessible(true);
                removeSingleton.invoke(factory, beanName);
            }
        } catch (Throwable ignored) {
            // Best-effort.
        }
    }

    private boolean refreshRequestMappings(Object context, Collection<Class<?>> changedTypes, Set<String> details) {
        try {
            ClassLoader loader = context.getClass().getClassLoader();
            Collection<Object> mappings = new ArrayList<Object>();
            String[] mappingTypeNames = new String[] {
                    "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping",
                    "org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping"
            };
            for (String typeName : mappingTypeNames) {
                try {
                    Class<?> mappingType = Class.forName(typeName, false, loader);
                    for (Object bean : mappingBeans(context, mappingType)) {
                        if (bean != null && !mappings.contains(bean)) mappings.add(bean);
                    }
                } catch (ClassNotFoundException ignored) {
                    // framework flavor not on classpath
                }
            }
            if (mappings.isEmpty()) {
                details.add("mappingRefresh=noMappingBean");
                return false;
            }

            int refreshed = 0;
            int unregistered = 0;
            int registeredControllers = 0;
            int restoredKeys = 0;
            Set<String> detectFailures = new LinkedHashSet<String>();
            Set<String> registeredNames = new LinkedHashSet<String>();
            boolean hasGeneration = false;
            for (Class<?> changed : changedTypes) {
                if (changed != null && isGenerationClassName(changed.getName())) {
                    hasGeneration = true;
                    break;
                }
            }

            for (Object mapping : mappings) {
                int before = countMappingsForTypes(mapping, changedTypes);
                int removed = unregisterHandlersForTypes(mapping, changedTypes, details);
                unregistered += removed;

                RegisterResult result = registerHandlersForTypes(
                        context, mapping, changedTypes, details, detectFailures, registeredNames);
                registeredControllers += result.controllers;

                int afterRegister = countMappingsForTypes(mapping, changedTypes);
                int localRestored = Math.max(result.mappingKeys, afterRegister);
                restoredKeys += localRestored;

                // Partial restore is the main 404 cause: unregistered=9 but restoredKeys=3.
                boolean controllersOk = result.controllers > 0 || !hasControllerType(mapping, changedTypes);
                boolean needsFallback = hasGeneration
                        || needsMappingFallback(before, removed, afterRegister, result.controllers, controllersOk);
                if (needsFallback) {
                    details.add("mappingFallback=initHandlerMethods before=" + before
                            + " removed=" + removed
                            + " afterRegister=" + afterRegister
                            + " controllers=" + result.controllers
                            + " keys=" + result.mappingKeys);
                    // Resolve init first: resetting the registry without a rebuild drops all routes.
                    Method initHandlerMethods = findMethod(mapping.getClass(), "initHandlerMethods");
                    if (initHandlerMethods == null) {
                        details.add("mappingFallbackResult=initHandlerMethodsMissing");
                    } else {
                        initHandlerMethods.setAccessible(true);
                        Object registryObj = readField(mapping, "mappingRegistry");
                        Object writeLock = acquireWriteLock(registryObj);
                        try {
                            hardResetMappingRegistry(mapping);
                            initHandlerMethods.invoke(mapping);
                        } catch (Throwable fallbackCrash) {
                            // The registry may now be half-empty (routes gone app-wide):
                            // force an honest RESTART_REQUIRED instead of reporting success.
                            details.add("selfCheck=FAILED:mappingFallbackCrashed="
                                    + rootName(fallbackCrash) + ":" + rootMessage(fallbackCrash));
                        } finally {
                            releaseWriteLock(writeLock);
                        }
                        int afterFallback = countMappingsForTypes(mapping, changedTypes);
                        restoredKeys += Math.max(0, afterFallback - localRestored);
                        if (afterFallback > 0) {
                            registeredControllers = Math.max(registeredControllers, 1);
                        }
                        details.add("mappingFallbackResult=before=" + before
                                + " after=" + afterFallback
                                + " recovered=" + (afterFallback >= before));
                    }
                }
                if (removed > 0 || result.controllers > 0 || result.mappingKeys > 0 || needsFallback) {
                    refreshed++;
                }
            }
            // Only keep detect failures for beans that never registered on any mapping bean.
            for (String failure : detectFailures) {
                String beanName = failure;
                int sep = failure.indexOf(':');
                if (sep > 0) beanName = failure.substring(0, sep);
                if (!registeredNames.contains(beanName)) {
                    details.add("detectFailed=" + failure);
                } else {
                    details.add("detectIgnored=" + beanName + ":secondaryMapping");
                }
            }
            // Self check: bytecode-declared mapping methods must be present in the registry.
            // Counting mismatch downgrades the verdict instead of reporting a false success.
            int expectedMappings = countMappingAnnotatedMethods(changedTypes);
            if (expectedMappings > 0 && unregistered > 0 && restoredKeys == 0) {
                details.add("selfCheck=FAILED:routesLost expected=" + expectedMappings
                        + " unregistered=" + unregistered + " restored=0");
            } else if (expectedMappings > 0 && restoredKeys > 0 && restoredKeys < expectedMappings) {
                details.add("selfCheck=WARN:partialRoutes expected=" + expectedMappings
                        + " restored=" + restoredKeys);
            }
            details.add("mappingRefresh=ok unregistered=" + unregistered
                    + " registeredControllers=" + registeredControllers
                    + " restoredKeys=" + restoredKeys
                    + " mappings=" + mappings.size());
            return refreshed > 0 || unregistered > 0 || registeredControllers > 0;
        } catch (Throwable failure) {
            details.add("mappingRefreshFailed=" + rootName(failure));
            return false;
        }
    }

    /** Count declared handler methods carrying any *Mapping annotation (self-check baseline). */
    static int countMappingAnnotatedMethods(Collection<Class<?>> types) {
        if (types == null) return 0;
        int count = 0;
        Set<String> seen = new LinkedHashSet<String>();
        for (Class<?> type : types) {
            if (type == null) continue;
            // [generation, base] pairs describe one business class; count the first (live) one.
            if (!seen.add(originalBusinessType(type).getName())) continue;
            try {
                for (Method method : type.getDeclaredMethods()) {
                    for (Annotation annotation : safeAnnotations(method)) {
                        if (annotation.annotationType().getSimpleName().endsWith("Mapping")) {
                            count++;
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // best-effort baseline
            }
        }
        return count;
    }

    /**
     * Decide whether surgical re-register lost routes and needs full initHandlerMethods.
     * Exposed package-private for unit tests.
     */
    static boolean needsMappingFallback(int before, int removed, int afterRegister,
                                        int controllers, boolean controllersOk) {
        if (removed <= 0 && afterRegister >= before) {
            return !controllersOk && before > 0;
        }
        // Any net loss of mapping keys for the changed controller is a hard failure.
        if (before > 0 && afterRegister < before) {
            return true;
        }
        if (removed > 0 && afterRegister < removed) {
            return true;
        }
        if (removed > 0 && afterRegister == 0) {
            return true;
        }
        if (removed > 0 && !controllersOk) {
            return true;
        }
        if (removed > 0 && controllers <= 0 && afterRegister < Math.max(before, removed)) {
            return true;
        }
        return false;
    }

    private Collection<Object> mappingBeans(Object context, Class<?> mappingType) {
        return beansOfType(context, mappingType);
    }

    private Collection<Object> beansOfType(Object context, Class<?> type) {
        List<Object> result = new ArrayList<Object>();
        try {
            Method getBeansOfType = findMethod(context.getClass(), "getBeansOfType", Class.class);
            if (getBeansOfType != null) {
                Object beans = getBeansOfType.invoke(context, type);
                if (beans instanceof Map) {
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) beans).entrySet()) {
                        if (entry.getValue() != null) result.add(entry.getValue());
                    }
                }
            }
        } catch (Throwable ignored) {
            // Fall back to single getBean.
        }
        if (result.isEmpty()) {
            Object single = invoke(context, "getBean", new Class<?>[]{Class.class}, new Object[]{type});
            if (single != null) result.add(single);
        }
        return result;
    }

    private int unregisterHandlersForTypes(Object mapping, Collection<Class<?>> changedTypes, Set<String> details) {
        if (changedTypes == null || changedTypes.isEmpty()) return 0;
        Object registry = readField(mapping, "mappingRegistry");
        if (registry == null) return 0;
        Object registryMap = readField(registry, "registry");
        if (!(registryMap instanceof Map)) return 0;

        List<Object> toRemove = new ArrayList<Object>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) registryMap).entrySet()) {
            Object mappingKey = entry.getKey();
            Object registration = entry.getValue();
            Class<?> beanType = resolveRegistrationBeanType(registration);
            if (beanType != null && isChangedType(beanType, changedTypes)) {
                toRemove.add(mappingKey);
            }
        }
        if (toRemove.isEmpty()) return 0;

        Method unregisterMapping = findUnaryMethod(mapping.getClass(), "unregisterMapping");
        Method registryUnregister = findUnaryMethod(registry.getClass(), "unregister");
        int count = 0;
        // Spring MappingRegistry guards lookups with readWriteLock; raw map mutation must too.
        // unregisterMapping re-enters the same write lock on this thread, which is safe.
        Object writeLock = acquireWriteLock(registry);
        try {
            for (Object mappingKey : toRemove) {
                boolean removed = false;
                if (unregisterMapping != null) {
                    try {
                        unregisterMapping.setAccessible(true);
                        unregisterMapping.invoke(mapping, mappingKey);
                        removed = true;
                    } catch (Throwable ignored) { }
                }
                if (!removed && registryUnregister != null) {
                    try {
                        registryUnregister.setAccessible(true);
                        registryUnregister.invoke(registry, mappingKey);
                        removed = true;
                    } catch (Throwable ignored) { }
                }
                if (!removed) {
                    ((Map<?, ?>) registryMap).remove(mappingKey);
                    removed = true;
                }
                if (removed) count++;
            }
            // unregisterMapping maintains lookups itself; pruning covers the raw-removal path
            // without clearing other controllers' path/name/cors entries wholesale.
            pruneLookupsForKeys(registry, toRemove);
        } finally {
            releaseWriteLock(writeLock);
        }
        details.add("unregisteredKeys=" + count);
        return count;
    }

    private RegisterResult registerHandlersForTypes(Object context, Object mapping, Collection<Class<?>> changedTypes,
                                                    Set<String> details, Set<String> detectFailures,
                                                    Set<String> registeredNames) {
        if (changedTypes == null || changedTypes.isEmpty()) return new RegisterResult(0, 0);
        Method detectHandlerMethods = findMethod(mapping.getClass(), "detectHandlerMethods", Object.class);
        if (detectHandlerMethods == null) {
            details.add("detectHandlerMethods=missing");
            return new RegisterResult(0, 0);
        }
        detectHandlerMethods.setAccessible(true);
        Method isHandler = findMethod(mapping.getClass(), "isHandler", Class.class);
        if (isHandler != null) isHandler.setAccessible(true);

        int controllers = 0;
        int mappingKeys = 0;
        Object factory = invoke(context, "getBeanFactory");
        for (Class<?> changed : changedTypes) {
            if (changed == null) continue;
            Class<?> business = originalBusinessType(changed);
            Class<?> live = HotReloadClassRegistry.resolveLive(business.getName());
            if (live == null) live = changed;
            try {
                // isHandler must see the Spring-registered business type, not generation name.
                if (isHandler != null
                        && !Boolean.TRUE.equals(isHandler.invoke(mapping, business))
                        && !Boolean.TRUE.equals(isHandler.invoke(mapping, live))) {
                    details.add("notHandler=" + changed.getSimpleName());
                    continue;
                }
            } catch (Throwable ignored) {
                // try anyway
            }

            String[] names = beanNamesForType(context, factory, business);
            if (names == null || names.length == 0) {
                names = beanNamesForType(context, factory, live);
            }
            LinkedHashSet<String> nameSet = new LinkedHashSet<String>();
            if (names != null) {
                for (int i = 0; i < names.length; i++) {
                    if (names[i] != null) nameSet.add(names[i]);
                }
            }
            String guessed = guessBeanName(business);
            if (guessed != null && containsBeanDefinition(factory, guessed)) {
                nameSet.add(guessed);
            }
            if (nameSet.isEmpty()) {
                details.add("noHandlerBean=" + changed.getSimpleName()
                        + (guessed == null ? "" : ":guess=" + guessed));
                continue;
            }
            names = nameSet.toArray(new String[nameSet.size()]);
            for (String name : names) {
                boolean ok = false;
                Throwable last = null;
                int before = countMappingsForTypes(mapping, changedTypes);
                // 1) bean name (preferred by Spring)
                try {
                    detectHandlerMethods.invoke(mapping, name);
                    ok = true;
                } catch (Throwable failure) {
                    last = failure;
                }
                // 2) bean instance
                if (!ok) {
                    try {
                        Object bean = invoke(context, "getBean", new Class<?>[]{String.class}, new Object[]{name});
                        if (bean != null) {
                            detectHandlerMethods.invoke(mapping, bean);
                            ok = true;
                        }
                    } catch (Throwable failure) {
                        last = failure;
                    }
                }
                // Intentionally NEVER call detectHandlerMethods(FQCN).
                int after = countMappingsForTypes(mapping, changedTypes);
                int added = Math.max(0, after - before);
                // Some mapping beans throw on detect but still end up with mappings via side paths;
                // treat "keys increased" as success even if the invoke threw after partial work.
                if (!ok && added > 0) {
                    ok = true;
                }
                if (ok) {
                    controllers++;
                    mappingKeys += added;
                    registeredNames.add(name);
                    details.add("registered=" + name + ":mappingKeys=" + added);
                } else {
                    detectFailures.add(name + ":" + rootName(last) + ":" + rootMessage(last));
                }
            }
        }
        return new RegisterResult(controllers, mappingKeys);
    }

    private boolean hasControllerType(Object mapping, Collection<Class<?>> changedTypes) {
        Method isHandler = findMethod(mapping.getClass(), "isHandler", Class.class);
        if (isHandler == null) return true;
        try {
            isHandler.setAccessible(true);
            for (Class<?> changed : changedTypes) {
                if (changed != null && Boolean.TRUE.equals(isHandler.invoke(mapping, changed))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return true;
        }
        return false;
    }

    private int countMappingsForTypes(Object mapping, Collection<Class<?>> changedTypes) {
        Object registry = readField(mapping, "mappingRegistry");
        if (registry == null) return 0;
        Object registryMap = readField(registry, "registry");
        if (!(registryMap instanceof Map)) return 0;
        int count = 0;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) registryMap).entrySet()) {
            Class<?> beanType = resolveRegistrationBeanType(entry.getValue());
            if (beanType != null && isChangedType(beanType, changedTypes)) {
                count++;
            }
        }
        return count;
    }

    private Class<?> resolveRegistrationBeanType(Object registration) {
        if (registration == null) return null;
        Object handlerMethod = readField(registration, "handlerMethod");
        if (handlerMethod == null) {
            handlerMethod = readField(registration, "method");
        }
        if (handlerMethod == null) return null;

        Object beanType = invoke(handlerMethod, "getBeanType");
        if (beanType instanceof Class) {
            return userClass((Class<?>) beanType);
        }
        Object methodObj = invoke(handlerMethod, "getMethod");
        if (methodObj instanceof Method) {
            return userClass(((Method) methodObj).getDeclaringClass());
        }
        Object bean = invoke(handlerMethod, "getBean");
        if (bean instanceof Class) {
            return userClass((Class<?>) bean);
        }
        if (bean != null && !(bean instanceof String)) {
            return userClass(bean.getClass());
        }
        return null;
    }

    private static Class<?> userClass(Class<?> type) {
        if (type == null) return null;
        Class<?> current = type;
        while (current != null && current.getName().contains("$$")) {
            current = current.getSuperclass();
        }
        return current == null ? type : current;
    }

    static boolean isChangedType(Class<?> beanType, Collection<Class<?>> changedTypes) {
        Class<?> user = userClass(beanType);
        if (user == null) return false;
        for (Class<?> changed : changedTypes) {
            if (changed == null) continue;
            Class<?> changedUser = userClass(changed);
            if (changedUser == null) continue;
            if (user == changedUser) return true;
            if (user.getName().equals(changedUser.getName())) return true;
            // Assignability matching is ONLY for generation pairs (gen subclass <-> its base).
            // For identity-kept classes it would match every sibling sharing a common base
            // controller and unregister the whole application's routes.
            if (isGenerationClassName(user.getName()) || isGenerationClassName(changedUser.getName())) {
                if (changedUser.isAssignableFrom(user) || user.isAssignableFrom(changedUser)) return true;
            }
        }
        return false;
    }

    private static boolean isProxyClass(Class<?> type) {
        if (type == null) return false;
        String name = type.getName();
        return name.contains("$$") || name.contains("$Proxy") || name.contains("CGLIB") || name.contains("ByteBuddy");
    }

    private void probeAnnotationVisibility(Collection<Class<?>> types, Set<String> details) {
        details.add(AnnotationHotReloadDiagnostics.compactProbeForReport(types));
    }

    private String describeAnnotations(Collection<Class<?>> types) {
        if (types == null || types.isEmpty()) return "annotationsVisible=none";
        StringBuilder builder = new StringBuilder("annotationsVisible=");
        int classes = 0;
        for (Class<?> type : types) {
            if (type == null) continue;
            if (classes++ > 0) builder.append(';');
            builder.append(type.getSimpleName()).append('{');
            builder.append(joinCompactAnnotations(safeAnnotations(type)));
            builder.append('|');
            Method[] methods = type.getDeclaredMethods();
            // Prefer security/aop-related annotated methods first for compact diagnostics.
            java.util.Arrays.sort(methods, new java.util.Comparator<Method>() {
                public int compare(Method left, Method right) {
                    int cmp = Integer.compare(annotationPriority(right), annotationPriority(left));
                    if (cmp != 0) return cmp;
                    return left.getName().compareTo(right.getName());
                }
            });
            int methodPrinted = 0;
            int importantPrinted = 0;
            for (Method method : methods) {
                Annotation[] anns = safeAnnotations(method);
                if (anns.length == 0) continue;
                String compact = joinCompactAnnotations(anns);
                if (compact.isEmpty()) continue;
                boolean important = compact.contains("PreAuthorize") || compact.contains("Secured")
                        || compact.contains("Transactional") || compact.contains("Cacheable")
                        || compact.contains("Async");
                if (!important && (methodPrinted >= 8 || builder.length() > 420)) {
                    continue;
                }
                if (methodPrinted++ > 0) builder.append(',');
                builder.append(method.getName()).append('@').append(compact);
                if (important) importantPrinted++;
                if (importantPrinted >= 12 && methodPrinted >= 12) break;
                if (builder.length() > 520 && importantPrinted > 0) break;
            }
            builder.append('}');
            if (builder.length() > 560) break;
        }
        return builder.toString();
    }

    /** Diagnostics ordering only: security/tx/cache annotations first, then mappings, then the rest. */
    private static int annotationPriority(Method method) {
        int score = 0;
        for (Annotation annotation : safeAnnotations(method)) {
            String name = annotation.annotationType().getSimpleName();
            if ("PreAuthorize".equals(name) || "Secured".equals(name)
                    || "Transactional".equals(name) || "Cacheable".equals(name)
                    || "Async".equals(name)) {
                score += 20;
            } else if (name.endsWith("Mapping")) {
                score += 10;
            } else {
                score += 1;
            }
        }
        return score;
    }

    private static String joinCompactAnnotations(Annotation[] annotations) {
        if (annotations == null || annotations.length == 0) return "";
        StringBuilder builder = new StringBuilder();
        int printed = 0;
        for (Annotation annotation : annotations) {
            String compact = compactAnnotation(annotation);
            if (compact == null || compact.isEmpty()) continue;
            if (printed++ > 0) builder.append('+');
            builder.append(compact);
            if (printed >= 8 || builder.length() > 180) break;
        }
        return builder.toString();
    }

    private static Annotation[] safeAnnotations(java.lang.reflect.AnnotatedElement element) {
        try {
            Annotation[] annotations = element.getDeclaredAnnotations();
            return annotations == null ? new Annotation[0] : annotations;
        } catch (Throwable ignored) {
            return new Annotation[0];
        }
    }

    private static String compactAnnotation(Annotation annotation) {
        if (annotation == null) return null;
        String name = annotation.annotationType().getSimpleName();
        // Drop noisy documentation-only annotations from diagnostics.
        if ("Api".equals(name) || "ApiOperation".equals(name) || "ApiImplicitParams".equals(name)
                || "ApiImplicitParam".equals(name) || "ApiModel".equals(name)
                || "ApiModelProperty".equals(name) || "ApiResponses".equals(name)
                || "ApiResponse".equals(name) || "Schema".equals(name)
                || "Operation".equals(name) || "Tag".equals(name)
                || "Parameter".equals(name) || "Parameters".equals(name)) {
            return null;
        }
        // Keep mapping/security/aop annotations short and useful.
        if ("PreAuthorize".equals(name) || "Secured".equals(name)
                || "RolesAllowed".equals(name) || "Transactional".equals(name)
                || "Cacheable".equals(name) || "CacheEvict".equals(name)
                || "RequestMapping".equals(name) || "GetMapping".equals(name)
                || "PostMapping".equals(name) || "PutMapping".equals(name)
                || "DeleteMapping".equals(name) || "PatchMapping".equals(name)
                || "RestController".equals(name) || "Controller".equals(name)
                || "Service".equals(name) || "Component".equals(name)
                || "Repository".equals(name) || "Deprecated".equals(name)
                || "Async".equals(name) || "Scheduled".equals(name)) {
            String attr = firstUsefulAttribute(annotation);
            return attr == null ? name : name + '(' + attr + ')';
        }
        return name;
    }

    private static String firstUsefulAttribute(Annotation annotation) {
        try {
            Method[] methods = annotation.annotationType().getDeclaredMethods();
            // Generic preference order only: common Spring attribute names, then any non-default one.
            for (String preferred : new String[] {
                    "value", "path", "name", "readOnly"
            }) {
                for (Method method : methods) {
                    if (!preferred.equals(method.getName()) || method.getParameterTypes().length != 0) continue;
                    Object value = method.invoke(annotation);
                    String rendered = renderAttr(value);
                    if (rendered != null) return preferred + '=' + rendered;
                }
            }
            for (Method method : methods) {
                if (method.getParameterTypes().length != 0) continue;
                String methodName = method.getName();
                if ("annotationType".equals(methodName) || "hashCode".equals(methodName)
                        || "toString".equals(methodName) || "equals".equals(methodName)) continue;
                Object value = method.invoke(annotation);
                String rendered = renderAttr(value);
                if (rendered != null) return methodName + '=' + rendered;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return null;
    }

    private static String renderAttr(Object value) {
        if (value == null) return null;
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            if (length == 0) return null;
            Object first = java.lang.reflect.Array.get(value, 0);
            String firstText = String.valueOf(first);
            if (firstText.isEmpty()) return null;
            return length == 1 ? firstText : firstText + "...";
        }
        String rendered = String.valueOf(value);
        if (rendered.isEmpty() || "[]".equals(rendered) || "{}".equals(rendered)
                || "false".equals(rendered) || "0".equals(rendered)) {
            // keep false/0 only for aliases? skip pure noise defaults
            if ("false".equals(rendered) || "0".equals(rendered)) return null;
            return null;
        }
        if (rendered.length() > 32) rendered = rendered.substring(0, 32);
        // strip object identity noise like [Ljava.lang.String;@abcd
        if (rendered.contains("@") && rendered.contains("[")) return null;
        return rendered;
    }

    private void hardResetMappingRegistry(Object mapping) {
        Object mappingRegistry = readField(mapping, "mappingRegistry");
        if (mappingRegistry == null) return;
        clearIfMapOrCollection(readField(mappingRegistry, "registry"));
        clearIfMapOrCollection(readField(mappingRegistry, "mappingLookup"));
        clearIfMapOrCollection(readField(mappingRegistry, "urlLookup"));
        clearIfMapOrCollection(readField(mappingRegistry, "nameLookup"));
        clearIfMapOrCollection(readField(mappingRegistry, "corsLookup"));
        clearIfMapOrCollection(readField(mappingRegistry, "pathLookup"));
    }

    /** Remove only the given mapping keys from lookup maps; other controllers' entries must survive. */
    static void pruneLookupsForKeys(Object registry, Collection<Object> removedKeys) {
        if (registry == null || removedKeys == null || removedKeys.isEmpty()) return;
        Object mappingLookup = readField(registry, "mappingLookup");
        if (mappingLookup instanceof Map) {
            for (Object key : removedKeys) {
                ((Map<?, ?>) mappingLookup).remove(key);
            }
        }
        // urlLookup (Spring <=5.2) / pathLookup (5.3+): MultiValueMap<path, mappingKey>.
        pruneMultiValueLookup(readField(registry, "urlLookup"), removedKeys);
        pruneMultiValueLookup(readField(registry, "pathLookup"), removedKeys);
        // nameLookup/corsLookup keep stale-but-unreachable entries on the raw-removal path;
        // clearing them wholesale would drop CORS and name lookups for every other controller.
    }

    private static void pruneMultiValueLookup(Object lookup, Collection<Object> removedKeys) {
        if (!(lookup instanceof Map)) return;
        List<Object> emptyKeys = new ArrayList<Object>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) lookup).entrySet()) {
            Object value = entry.getValue();
            if (!(value instanceof Collection)) continue;
            ((Collection<?>) value).removeAll(removedKeys);
            if (((Collection<?>) value).isEmpty()) {
                emptyKeys.add(entry.getKey());
            }
        }
        for (Object key : emptyKeys) {
            ((Map<?, ?>) lookup).remove(key);
        }
    }

    /** Take MappingRegistry.readWriteLock write lock when present; requests hold its read lock. */
    private static Object acquireWriteLock(Object mappingRegistry) {
        Object lock = readField(mappingRegistry, "readWriteLock");
        if (lock instanceof java.util.concurrent.locks.ReadWriteLock) {
            java.util.concurrent.locks.Lock writeLock =
                    ((java.util.concurrent.locks.ReadWriteLock) lock).writeLock();
            writeLock.lock();
            return writeLock;
        }
        return null;
    }

    private static void releaseWriteLock(Object writeLock) {
        if (writeLock instanceof java.util.concurrent.locks.Lock) {
            try {
                ((java.util.concurrent.locks.Lock) writeLock).unlock();
            } catch (Throwable ignored) {
                // never propagate from cleanup
            }
        }
    }

    private static Method findUnaryMethod(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (name.equals(method.getName()) && method.getParameterTypes().length == 1) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        for (Method method : type.getMethods()) {
            if (name.equals(method.getName()) && method.getParameterTypes().length == 1) {
                return method;
            }
        }
        return null;
    }

    private static String rootName(Throwable failure) {
        Throwable root = failure;
        while (root != null && root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root == null ? "unknown" : root.getClass().getSimpleName();
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root != null && root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root == null || root.getMessage() == null) return "none";
        String message = root.getMessage().replace('\n', ' ').replace('\r', ' ');
        return message.length() > 160 ? message.substring(0, 160) : message;
    }

    private static boolean nullInstanceField(Object target, String fieldName) {
        if (target == null || fieldName == null) return false;
        Field field = findField(target.getClass(), fieldName);
        if (field == null) return false;
        try {
            field.setAccessible(true);
            field.set(target, null);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int clearInstanceMaps(Object target, String... fieldNames) {
        int cleared = 0;
        if (target == null || fieldNames == null) return 0;
        for (String fieldName : fieldNames) {
            Object value = readField(target, fieldName);
            if (value instanceof Map) {
                ((Map<?, ?>) value).clear();
                cleared++;
            } else if (value instanceof Collection) {
                ((Collection<?>) value).clear();
                cleared++;
            }
        }
        return cleared;
    }

    private static void clearInstanceMap(Object target, String fieldName) {
        Object value = readField(target, fieldName);
        clearIfMapOrCollection(value);
    }

    private static void clearIfMapOrCollection(Object value) {
        if (value instanceof Map) ((Map<?, ?>) value).clear();
        else if (value instanceof Collection) ((Collection<?>) value).clear();
    }

    private static Object invoke(Object target, String methodName) {
        return invoke(target, methodName, new Class<?>[0], new Object[0]);
    }

    private static Object invoke(Object target, String methodName, Class<?>[] types, Object[] args) {
        if (target == null) return null;
        try {
            Method method = findMethod(target.getClass(), methodName, types);
            if (method == null) return null;
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void invokeIfPresent(Object target, String methodName) {
        invoke(target, methodName, new Class<?>[0], new Object[0]);
    }

    private static Object readField(Object target, String name) {
        if (target == null) return null;
        Field field = findField(target.getClass(), name);
        if (field == null) return null;
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        try {
            return type.getMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Map<String, String> fields(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) map.put(keyValues[i], keyValues[i + 1]);
        return map;
    }

    private static final class RegisterResult {
        private final int controllers;
        private final int mappingKeys;

        private RegisterResult(int controllers, int mappingKeys) {
            this.controllers = controllers;
            this.mappingKeys = mappingKeys;
        }
    }

    public static final class RebindReport {
        private final int contextCount;
        private final int mappingRefreshed;
        private final int annotationCachesCleared;
        private final int beansRecreated;
        private final int failures;
        private final Set<String> details;

        RebindReport(int contextCount, int mappingRefreshed, int annotationCachesCleared,
                     int beansRecreated, int failures, Set<String> details) {
            this.contextCount = contextCount;
            this.mappingRefreshed = mappingRefreshed;
            this.annotationCachesCleared = annotationCachesCleared;
            this.beansRecreated = beansRecreated;
            this.failures = failures;
            this.details = details;
        }

        public int getContextCount() { return contextCount; }
        public int getMappingRefreshed() { return mappingRefreshed; }
        public int getAnnotationCachesCleared() { return annotationCachesCleared; }
        public int getBeansRecreated() { return beansRecreated; }
        public int getFailures() { return failures; }

        /** Self-check verdict: mapping routes were unregistered but never restored. */
        public boolean hasRoutesLost() {
            for (String detail : details) {
                if (detail != null && detail.startsWith("selfCheck=FAILED")) return true;
            }
            return false;
        }

        public String summary() {
            StringBuilder builder = new StringBuilder(160);
            builder.append("contexts=").append(contextCount)
                    .append(",mappingRefreshed=").append(mappingRefreshed)
                    .append(",annotationCachesCleared=").append(annotationCachesCleared)
                    .append(",beansRecreated=").append(beansRecreated)
                    .append(",failures=").append(failures);
            if (!details.isEmpty()) {
                builder.append(",detail=");
                List<String> ordered = new ArrayList<String>(details);
                // Ensure mapping/proxy signals appear before annotation dumps.
                java.util.Collections.sort(ordered, new java.util.Comparator<String>() {
                    public int compare(String left, String right) {
                        return Integer.compare(detailRank(left), detailRank(right));
                    }
                });
                int i = 0;
                for (String detail : ordered) {
                    if (i++ > 0) builder.append('|');
                    builder.append(detail.replace(' ', '_'));
                    if (builder.length() > 900) {
                        builder.append("|...");
                        break;
                    }
                }
            }
            return builder.toString();
        }

        private static int detailRank(String detail) {
            if (detail == null) return 100;
            if (detail.startsWith("mappingRefresh=")) return 10;
            if (detail.startsWith("registered=")) return 20;
            if (detail.startsWith("unregisteredKeys=")) return 30;
            if (detail.startsWith("recreated=")) return 40;
            if (detail.startsWith("aop")) return 50;
            if (detail.startsWith("factoryCaches=")) return 60;
            if (detail.startsWith("reflectionInvalidated=")) return 70;
            if (detail.startsWith("annotationsVisible=")) return 200;
            if (detail.startsWith("mappingFallback") || detail.startsWith("detectFailed")
                    || detail.startsWith("recreateFailed") || detail.startsWith("contextFailed")
                    || detail.startsWith("selfCheck=")) {
                return 5;
            }
            return 100;
        }
    }
}

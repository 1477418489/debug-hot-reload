package dev.hotreload.agent.spring;

import dev.hotreload.bootstrap.HotReloadBridge;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generic annotation AOP rebind after class hot-reload.
 *
 * 单一机制（适用任意注解，无名单）：
 * 1) 清空 advisor/代理的按方法匹配缓存，拦截链按新注解重算；
 * 2) 注解型 MethodMatcher 包装为 index-aware——字节码索引持有该类注解真相时优先索引判定，
 *    否则原样委托原 matcher。
 * 真实 advice 始终由 Spring 自己的拦截链执行；本类绝不替换 advisor/advice 本身，
 * 合成 advice 无法复刻 @Around/@After/@AfterThrowing 语义。
 */
public final class AnnotationPointcutRebinder {
    private AnnotationPointcutRebinder() { }

    public static String rebind(Object context, Collection changedTypes) {
        if (context == null) return "pointcutRebind=skipped";
        int cachesCleared = 0, matchersPatched = 0, matchProbes = 0;
        Set details = new LinkedHashSet();
        try {
            if (changedTypes != null) {
                for (Object o : changedTypes) {
                    Class type = (Class) o;
                    if (type == null) continue;
                    cachesCleared += clearCaches(context, type);
                    // 仅当索引确实持有该类注解真相时才包装 matcher（E2 不发布索引，纯清缓存即可）。
                    if (HotReloadBridge.hasIndexedClass(type.getName())) {
                        matchersPatched += patchGenericAnnotationAdvisors(context, type, details);
                    }
                    matchProbes += probe(context, type, details);
                }
            }
        } catch (Throwable failure) {
            return "pointcutRebind=failed:" + failure.getClass().getSimpleName() + ":" + safe(failure.getMessage());
        }
        return "pointcutRebind=matchersPatched=" + matchersPatched + ",cachesCleared=" + cachesCleared
                + ",matchProbes=" + matchProbes + (details.isEmpty() ? "" : "|" + join(details, 14));
    }

    private static int patchGenericAnnotationAdvisors(Object context, Class type, Set details) {
        int patched = 0;
        String[] names = beanNamesForType(context, type);
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            Object bean = invoke(context, "getBean", new Class[]{String.class}, new Object[]{name});
            Object advised = findAdvised(bean);
            if (advised == null) continue;
            Object support = resolveAdvisedSupport(advised);
            Object arr = invoke(advised, "getAdvisors");
            if (!(arr instanceof Object[])) continue;
            Object[] advisors = (Object[]) arr;
            for (int j = 0; j < advisors.length; j++) {
                Object advisor = advisors[j];
                if (advisor == null) continue;
                Object pointcut = invoke(advisor, "getPointcut");
                if (pointcut == null) continue;
                Object matcher = invoke(pointcut, "getMethodMatcher");
                if (matcher == null || isIndexAwareMatcher(matcher)) continue;
                Class annotationType = detectAnnotationTypeFromMatcher(matcher);
                if (annotationType == null) {
                    // AspectJ expression pointcut: clear its caches so next match re-evaluates.
                    if (clearMapField(pointcut, "shadowMatchCache") || clearMapField(matcher, "shadowMatchCache")
                            || clearMapField(matcher, "cache") || clearMapField(matcher, "methodCache")) {
                        patched++;
                    }
                    continue;
                }
                try {
                    Object wrapped = wrapMatcher(matcher, annotationType);
                    if (wrapped != null && writeField(pointcut, "methodMatcher", wrapped)) {
                        patched++;
                        details.add("annotationMatcher@" + name + ":" + annotationType.getSimpleName());
                    } else if (clearMapField(matcher, "methodCache") || clearMapField(matcher, "cache")) {
                        patched++;
                    }
                } catch (Throwable ignored) { }
            }
            clearOn(support);
            clearOn(advised);
            invokeNoReturn(support, "adviceChanged");
        }
        return patched;
    }

    private static Class detectAnnotationTypeFromMatcher(Object matcher) {
        if (matcher == null) return null;
        String[] fields = new String[] {"annotationType", "annotationClass", "annotation", "type"};
        for (int i = 0; i < fields.length; i++) {
            Object value = readField(matcher, fields[i]);
            if (value instanceof Class) {
                Class cls = (Class) value;
                if (Annotation.class.isAssignableFrom(cls)) return cls;
            }
        }
        return null;
    }

    private static Object wrapMatcher(Object original, Class annotationType) {
        try {
            ClassLoader loader = original.getClass().getClassLoader();
            Class methodMatcherType = Class.forName("org.springframework.aop.MethodMatcher", false, loader);
            return Proxy.newProxyInstance(loader, new Class[]{methodMatcherType},
                    new IndexAwareMatcherHandler(original, annotationType));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 包装原注解 matcher：索引持有该方法所属类的注解真相时按索引判定，否则委托原 matcher。
     * isRuntime 等其余行为全部委托，不改变原 matcher 语义。
     */
    static final class IndexAwareMatcherHandler implements java.lang.reflect.InvocationHandler {
        private final Object delegate;
        private final Class annotationType;

        IndexAwareMatcherHandler(Object delegate, Class annotationType) {
            this.delegate = delegate;
            this.annotationType = annotationType;
        }

        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("matches".equals(name) && args != null && args.length >= 1 && args[0] instanceof Method) {
                Method m = (Method) args[0];
                Class targetClass = args.length > 1 && args[1] instanceof Class ? (Class) args[1] : null;
                Method specific = mostSpecific(m, targetClass);
                Annotation override = HotReloadBridge.resolveMethodAnnotation(specific, annotationType);
                if (override != null) {
                    return Boolean.valueOf(!HotReloadBridge.isAbsentMarker(override));
                }
                Class owner = userClass(targetClass == null ? m.getDeclaringClass() : targetClass);
                if (owner != null && HotReloadBridge.hasIndexedClass(owner.getName())) {
                    return Boolean.valueOf(shouldApply(specific, annotationType));
                }
                return method.invoke(delegate, args);
            }
            if ("equals".equals(name)) return Boolean.valueOf(proxy == (args == null ? null : args[0]));
            if ("hashCode".equals(name)) return Integer.valueOf(System.identityHashCode(proxy));
            if ("toString".equals(name)) return "IndexAwareAnnotationMatcher(" + annotationType.getSimpleName() + ")";
            return method.invoke(delegate, args);
        }
    }

    private static boolean isIndexAwareMatcher(Object matcher) {
        try {
            return Proxy.isProxyClass(matcher.getClass())
                    && Proxy.getInvocationHandler(matcher) instanceof IndexAwareMatcherHandler;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isIndexAware(Object advisor) {
        try {
            Object pointcut = invoke(advisor, "getPointcut");
            if (pointcut == null) return false;
            Object matcher = invoke(pointcut, "getMethodMatcher");
            return matcher != null && isIndexAwareMatcher(matcher);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean shouldApply(Method specific, Class annotationType) {
        if (specific == null) return false;
        Annotation override = HotReloadBridge.resolveMethodAnnotation(specific, annotationType);
        if (override != null) return !HotReloadBridge.isAbsentMarker(override);
        try { return specific.getAnnotation(annotationType) != null; } catch (Throwable ignored) { return false; }
    }

    private static Method mostSpecific(Method method, Class targetClass) {
        if (method == null) return null;
        if (targetClass == null) return method;
        try {
            Class aopUtils = Class.forName("org.springframework.aop.support.AopUtils", false, targetClass.getClassLoader());
            Method m = aopUtils.getMethod("getMostSpecificMethod", Method.class, Class.class);
            Object resolved = m.invoke(null, new Object[]{method, targetClass});
            if (resolved instanceof Method) return (Method) resolved;
        } catch (Throwable ignored) { }
        Class user = userClass(targetClass);
        if (user != null) {
            try { return user.getDeclaredMethod(method.getName(), method.getParameterTypes()); }
            catch (Throwable ignored) { }
        }
        return method;
    }

    private static int clearCaches(Object context, Class type) {
        int cleared = 0;
        String[] names = beanNamesForType(context, type);
        for (int i = 0; i < names.length; i++) {
            Object bean = invoke(context, "getBean", new Class[]{String.class}, new Object[]{names[i]});
            Object advised = findAdvised(bean);
            if (advised != null) {
                cleared += clearOn(resolveAdvisedSupport(advised));
                cleared += clearOn(advised);
            }
        }
        return cleared;
    }

    private static int clearOn(Object target) {
        if (target == null) return 0;
        int cleared = 0;
        if (clearMapField(target, "methodCache")) cleared++;
        Object factory = readField(target, "advisorChainFactory");
        if (factory != null && clearMapField(factory, "methodCache")) cleared++;
        Object methodCache = readField(target, "methodCache");
        if (methodCache != null && !(methodCache instanceof Map)) {
            if (nullInstanceField(target, "methodCache")) cleared++;
        }
        return cleared;
    }

    /**
     * CGLIB/JDK proxies implement Advised, but methodCache lives on the internal AdvisedSupport.
     */
    private static Object resolveAdvisedSupport(Object advisedOrProxy) {
        if (advisedOrProxy == null) return null;
        String name = advisedOrProxy.getClass().getName();
        if (name.contains("AdvisedSupport") || name.endsWith("ProxyFactory")
                || name.contains("ProxyFactoryBean")) {
            return advisedOrProxy;
        }
        Object direct = readField(advisedOrProxy, "advised");
        if (direct != null && direct != advisedOrProxy) return direct;

        Field[] fields = advisedOrProxy.getClass().getDeclaredFields();
        for (int i = 0; i < fields.length; i++) {
            Field field = fields[i];
            String fieldName = field.getName();
            if (fieldName == null) continue;
            if (!(fieldName.startsWith("CGLIB$CALLBACK") || fieldName.contains("CALLBACK"))) continue;
            try {
                field.setAccessible(true);
                Object callback = field.get(advisedOrProxy);
                if (callback == null) continue;
                Object advised = readField(callback, "advised");
                if (advised != null) return advised;
                Object outer = readField(callback, "this$0");
                if (outer != null) {
                    advised = readField(outer, "advised");
                    if (advised != null) return advised;
                }
            } catch (Throwable ignored) { }
        }

        try {
            if (Proxy.isProxyClass(advisedOrProxy.getClass())) {
                Object handler = Proxy.getInvocationHandler(advisedOrProxy);
                Object advised = readField(handler, "advised");
                if (advised != null) return advised;
            }
        } catch (Throwable ignored) { }
        return advisedOrProxy;
    }

    private static int probe(Object context, Class type, Set details) {
        int probes = 0;
        try {
            Method[] methods = type.getDeclaredMethods();
            if (methods == null || methods.length == 0) return 0;
            int printed = 0;
            for (int i = 0; i < methods.length && printed < 4; i++) {
                Method focus = methods[i];
                if (focus == null || focus.isSynthetic() || focus.isBridge()) continue;
                Annotation[] anns = focus.getDeclaredAnnotations();
                boolean interesting = anns != null && anns.length > 0;
                if (!interesting) {
                    interesting = (focus.getModifiers() & java.lang.reflect.Modifier.PUBLIC) != 0 && printed < 2;
                }
                if (!interesting) continue;
                details.add("match." + focus.getName() + ".anns=" + summarizeAnnotations(focus));
                probes++;
                printed++;
            }
            String[] names = beanNamesForType(context, type);
            if (names.length > 0) {
                Object bean = invoke(context, "getBean", new Class[]{String.class}, new Object[]{names[0]});
                Object advised = findAdvised(bean);
                if (advised != null) {
                    Object support = resolveAdvisedSupport(advised);
                    Object[] advisors = (Object[]) invoke(advised, "getAdvisors");
                    int indexAware = 0;
                    if (advisors != null) {
                        for (int i = 0; i < advisors.length; i++) {
                            if (isIndexAware(advisors[i])) indexAware++;
                        }
                    }
                    details.add("beanIndexAwareAdvisors=" + indexAware);
                    Method focus = methods[0];
                    for (int i = 0; i < methods.length; i++) {
                        if ((methods[i].getModifiers() & java.lang.reflect.Modifier.PUBLIC) != 0) {
                            focus = methods[i];
                            break;
                        }
                    }
                    Object chain = invoke(support, "getInterceptorsAndDynamicInterceptionAdvice",
                            new Class[]{Method.class, Class.class}, new Object[]{focus, type});
                    if (chain instanceof List) {
                        details.add("chain." + focus.getName() + ".size=" + ((List) chain).size());
                    }
                    Object methodCache = readField(support, "methodCache");
                    if (methodCache instanceof Map) {
                        details.add("methodCacheSize=" + ((Map) methodCache).size());
                    }
                }
            }
        } catch (Throwable failure) {
            details.add("probeFailed=" + failure.getClass().getSimpleName());
        }
        return probes;
    }

    private static String summarizeAnnotations(Method method) {
        try {
            Annotation[] anns = method.getDeclaredAnnotations();
            if (anns == null || anns.length == 0) return "none";
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (int i = 0; i < anns.length && n < 4; i++) {
                if (anns[i] == null) continue;
                if (n > 0) sb.append('+');
                sb.append(anns[i].annotationType().getSimpleName());
                n++;
            }
            return n == 0 ? "none" : sb.toString();
        } catch (Throwable ignored) {
            return "err";
        }
    }

    private static Object findAdvised(Object bean) {
        if (bean == null) return null;
        try {
            Class advisedType = Class.forName("org.springframework.aop.framework.Advised", false, bean.getClass().getClassLoader());
            if (advisedType.isInstance(bean)) return bean;
        } catch (Throwable ignored) { }
        return null;
    }

    private static Class userClass(Class type) {
        Class current = type;
        while (current != null) {
            String name = current.getName();
            if (name.contains("$$") || name.contains("CGLIB") || name.contains("$Proxy") || name.contains("ByteBuddy")) {
                current = current.getSuperclass(); continue;
            }
            return current;
        }
        return type;
    }

    private static String[] beanNamesForType(Object context, Class type) {
        try {
            Object factory = invoke(context, "getBeanFactory");
            if (factory != null) {
                Method method = findMethod(factory.getClass(), "getBeanNamesForType",
                        new Class[]{Class.class, boolean.class, boolean.class});
                if (method != null) {
                    Object names = method.invoke(factory, new Object[]{type, Boolean.TRUE, Boolean.FALSE});
                    if (names instanceof String[]) return (String[]) names;
                }
            }
            Method method = findMethod(context.getClass(), "getBeanNamesForType", new Class[]{Class.class});
            if (method != null) {
                Object names = method.invoke(context, new Object[]{type});
                if (names instanceof String[]) return (String[]) names;
            }
        } catch (Throwable ignored) { }
        return new String[0];
    }

    private static boolean writeField(Object target, String name, Object value) {
        Field field = findField(target.getClass(), name);
        if (field == null) return false;
        try {
            field.setAccessible(true);
            field.set(target, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean clearMapField(Object target, String fieldName) {
        Object value = readField(target, fieldName);
        if (value instanceof Map) { ((Map) value).clear(); return true; }
        return false;
    }

    private static boolean nullInstanceField(Object target, String fieldName) {
        Field field = findField(target.getClass(), fieldName);
        if (field == null) return false;
        try {
            field.setAccessible(true);
            if (field.get(target) == null) return false;
            field.set(target, null);
            return true;
        } catch (Throwable ignored) { return false; }
    }

    private static Object readField(Object target, String name) {
        if (target == null) return null;
        Field field = findField(target.getClass(), name);
        if (field == null) return null;
        try { field.setAccessible(true); return field.get(target); } catch (Throwable ignored) { return null; }
    }

    private static Field findField(Class type, String name) {
        Class current = type;
        while (current != null) {
            try { return current.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { current = current.getSuperclass(); }
        }
        return null;
    }

    private static Method findMethod(Class type, String name, Class[] params) {
        Class current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, params);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) { current = current.getSuperclass(); }
        }
        try {
            Method method = type.getMethod(name, params);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) { return null; }
    }

    private static boolean invokeNoReturn(Object target, String name) {
        if (target == null) return false;
        try {
            Method method = findMethod(target.getClass(), name, new Class[0]);
            if (method == null) return false;
            method.invoke(target, new Object[0]);
            return true;
        } catch (Throwable ignored) { return false; }
    }

    private static Object invoke(Object target, String name) {
        return invoke(target, name, new Class[0], new Object[0]);
    }

    private static Object invoke(Object target, String name, Class[] types, Object[] args) {
        if (target == null) return null;
        try {
            Method method = findMethod(target.getClass(), name, types);
            if (method == null) return null;
            return method.invoke(target, args);
        } catch (Throwable ignored) { return null; }
    }

    private static String join(Set values, int max) {
        StringBuilder builder = new StringBuilder();
        int i = 0;
        for (Object value : values) {
            if (i++ > 0) builder.append(',');
            builder.append(String.valueOf(value));
            if (i >= max) break;
        }
        return builder.toString();
    }

    private static String safe(String value) {
        if (value == null) return "none";
        return value.length() <= 80 ? value : value.substring(0, 80);
    }
}

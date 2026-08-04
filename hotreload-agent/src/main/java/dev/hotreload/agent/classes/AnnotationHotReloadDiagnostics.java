package dev.hotreload.agent.classes;

import dev.hotreload.agent.logging.AgentSessionLogger;
import dev.hotreload.bootstrap.HotReloadBridge;
import org.objectweb.asm.Type;

import java.lang.annotation.Annotation;
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
 * Stage-oriented diagnostics for annotation hot-reload.
 * Reports annotation index / reflection / advisor state for changed types.
 * No project-specific annotation hardcoding.
 */
public final class AnnotationHotReloadDiagnostics {
    private static final String[] FOCUS_METHODS = {
            "list", "export", "select", "getInfo", "save", "update", "delete", "query", "create", "add"
    };

    private AnnotationHotReloadDiagnostics() { }

    /** Verbose multi-stage probes are off by default to keep class reload cheap. */
    public static boolean verboseEnabled() {
        return Boolean.getBoolean("hotreload.annotation.verbose");
    }

    public static void pipelineStart(AgentSessionLogger logger, String requestId, Collection<Class<?>> types) {
        logger.log(Level.INFO, "ANNOTATION_PIPELINE_START", fields(
                "requestId", requestId,
                "classCount", Integer.toString(types == null ? 0 : types.size()),
                "detail", joinClassNames(types)));
    }

    public static void bytecodePublished(AgentSessionLogger logger, String requestId, Class<?> type, byte[] bytecode) {
        RuntimeAnnotationIndex.ClassAnnotations view = RuntimeAnnotationIndex.get(type);
        String focus = describeFocusMethods(type, view);
        boolean indexed = HotReloadBridge.hasIndexedClass(type.getName());
        logger.log(Level.INFO, "ANNOTATION_INDEX_PUBLISHED", fields(
                "requestId", requestId,
                "itemId", type.getName(),
                "resultCode", indexed ? "INDEXED" : "NOT_INDEXED",
                "detail", focus + "|bridgeIndexed=" + indexed
                        + "|describe=" + RuntimeAnnotationIndex.describe(type)));
    }

    public static void reflectAndSpringProbe(AgentSessionLogger logger, String requestId, Collection<Class<?>> types) {
        if (types == null) return;
        for (Class<?> type : types) {
            if (type == null) continue;
            ProbeResult probe = probeType(type);
            logger.log(Level.INFO, "ANNOTATION_REFLECT_PROBE", fields(
                    "requestId", requestId,
                    "itemId", type.getName(),
                    "resultCode", "OK",
                    "detail", probe.summary));
        }
    }

    public static void advisorAndProxyProbe(AgentSessionLogger logger, String requestId,
                                            Object context, Collection<Class<?>> types) {
        if (context == null || types == null) return;
        for (Class<?> type : types) {
            if (type == null) continue;
            String detail = probeAdvisorsAndProxy(context, type);
            logger.log(Level.INFO, "ANNOTATION_ADVISOR_PROBE", fields(
                    "requestId", requestId,
                    "itemId", type.getName(),
                    "resultCode", "OK",
                    "detail", detail));
        }
    }

    public static void pipelineEnd(AgentSessionLogger logger, String requestId, String overallDetail,
                                   Collection<Class<?>> types) {
        StringBuilder verdict = new StringBuilder();
        if (types != null) {
            for (Class<?> type : types) {
                if (type == null) continue;
                ProbeResult probe = probeType(type);
                if (verdict.length() > 0) verdict.append(';');
                verdict.append(type.getSimpleName()).append('{').append(probe.verdict).append('}');
            }
        }
        logger.log(Level.INFO, "ANNOTATION_PIPELINE_END", fields(
                "requestId", requestId,
                "resultCode", "DONE",
                "detail", "verdict=" + verdict + "|summary=" + safe(overallDetail, 700)));
    }

    public static String compactProbeForReport(Collection<Class<?>> types) {
        if (types == null || types.isEmpty()) return "annotationProbe=none";
        StringBuilder builder = new StringBuilder();
        for (Class<?> type : types) {
            if (type == null) continue;
            if (builder.length() > 0) builder.append(';');
            builder.append(probeType(type).summary);
        }
        return builder.toString();
    }

    private static ProbeResult probeType(Class<?> type) {
        StringBuilder summary = new StringBuilder();
        summary.append(type.getSimpleName()).append('{');
        StringBuilder verdict = new StringBuilder();
        int printed = 0;
        Method[] methods = type.getDeclaredMethods();
        List<Method> ordered = new ArrayList<Method>();
        Set<Method> seen = new LinkedHashSet<Method>();
        for (String focus : FOCUS_METHODS) {
            for (Method method : methods) {
                if (focus.equals(method.getName()) && seen.add(method)) ordered.add(method);
            }
        }
        for (Method method : methods) {
            if (seen.add(method)) ordered.add(method);
        }

        for (Method method : ordered) {
            boolean focus = isFocus(method.getName());
            String indexAnns = indexAnnotationNames(method);
            String reflectAnns = reflectAnnotationNames(method);
            String bridgeAnns = bridgeAnnotationNames(method);
            boolean hasAny = (indexAnns != null && !"none".equals(indexAnns))
                    || (reflectAnns != null && !"none".equals(reflectAnns))
                    || (bridgeAnns != null && !"none".equals(bridgeAnns));
            if (!focus && !hasAny) continue;
            if (printed++ > 0) summary.append(',');
            summary.append(method.getName())
                    .append(":idx=").append(indexAnns)
                    .append(",bridge=").append(bridgeAnns)
                    .append(",reflect=").append(reflectAnns);

            if (focus || hasAny) {
                if (verdict.length() > 0) verdict.append(',');
                verdict.append(method.getName()).append('=');
                boolean indexYes = indexAnns != null && !"none".equals(indexAnns);
                boolean reflectYes = reflectAnns != null && !"none".equals(reflectAnns);
                if (indexYes && !reflectYes) verdict.append("INDEX_YES_REFLECT_NO");
                else if (!indexYes && reflectYes) verdict.append("INDEX_NO_REFLECT_YES");
                else if (indexYes && reflectYes) verdict.append("BOTH_YES");
                else verdict.append("BOTH_NO");
            }
            if (printed >= 8) break;
        }
        if (printed == 0) summary.append("none");
        summary.append('}');
        return new ProbeResult(summary.toString(), verdict.length() == 0 ? "none" : verdict.toString());
    }

    private static String probeAdvisorsAndProxy(Object context, Class<?> type) {
        StringBuilder detail = new StringBuilder();
        try {
            Object factory = invoke(context, "getBeanFactory");
            String[] names = beanNamesForType(context, factory, type);
            detail.append("beans=").append(names == null ? 0 : names.length);
            if (names != null) {
                int shown = 0;
                for (String name : names) {
                    Object bean = invoke(context, "getBean", new Class[]{String.class}, new Object[]{name});
                    if (bean == null) continue;
                    if (shown++ > 0) detail.append(',');
                    detail.append(name)
                            .append(":proxy=").append(isProxy(bean.getClass()))
                            .append(":advised=").append(isAdvised(bean))
                            .append(":indexAwareAdvisors=").append(countIndexAwareAdvisors(bean));
                    if (shown >= 4) break;
                }
            }
        } catch (Throwable failure) {
            detail.append("probeFailed=").append(failure.getClass().getSimpleName());
        }
        return detail.toString();
    }

    private static int countIndexAwareAdvisors(Object bean) {
        try {
            if (!isAdvised(bean)) return 0;
            Object advisors = invoke(bean, "getAdvisors");
            if (!(advisors instanceof Object[])) return 0;
            Object[] arr = (Object[]) advisors;
            int count = 0;
            for (int i = 0; i < arr.length; i++) {
                if (arr[i] == null) continue;
                String text = String.valueOf(arr[i]);
                if (text.contains("IndexAware") || text.contains("annotationAspectJ")
                        || text.contains("annotationMatcher")) count++;
                Object advice = invoke(arr[i], "getAdvice");
                if (advice != null) {
                    String adviceName = advice.getClass().getName() + String.valueOf(advice);
                    if (adviceName.contains("IndexAware")) count++;
                }
            }
            return count;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String indexAnnotationNames(Method method) {
        try {
            RuntimeAnnotationIndex.ClassAnnotations view = RuntimeAnnotationIndex.get(userClass(method.getDeclaringClass()));
            if (view == null) view = RuntimeAnnotationIndex.get(method.getDeclaringClass());
            if (view == null) return "none";
            String key = method.getName() + Type.getMethodDescriptor(method);
            Set<RuntimeAnnotationIndex.Ann> anns = view.getMethodAnnotations().get(key);
            if (anns == null || anns.isEmpty()) {
                // name fallback
                for (Map.Entry<String, Set<RuntimeAnnotationIndex.Ann>> entry : view.getMethodAnnotations().entrySet()) {
                    if (entry.getKey() != null && entry.getKey().startsWith(method.getName() + "(")) {
                        anns = entry.getValue();
                        break;
                    }
                }
            }
            if (anns == null || anns.isEmpty()) return "none";
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (RuntimeAnnotationIndex.Ann ann : anns) {
                if (n++ > 0) sb.append('+');
                sb.append(ann.getSimpleName());
                if (n >= 4) break;
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "err";
        }
    }

    private static String reflectAnnotationNames(Method method) {
        try {
            Annotation[] anns = method.getDeclaredAnnotations();
            if (anns == null || anns.length == 0) return "none";
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (Annotation ann : anns) {
                if (ann == null) continue;
                if (n++ > 0) sb.append('+');
                sb.append(ann.annotationType().getSimpleName());
                if (n >= 4) break;
            }
            return n == 0 ? "none" : sb.toString();
        } catch (Throwable ignored) {
            return "err";
        }
    }

    private static String bridgeAnnotationNames(Method method) {
        try {
            Annotation[] anns = HotReloadBridge.resolveDeclaredAnnotations(method);
            if (anns == null) return "na";
            if (anns.length == 0) return "none";
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (Annotation ann : anns) {
                if (ann == null || HotReloadBridge.isAbsentMarker(ann)) continue;
                if (n++ > 0) sb.append('+');
                sb.append(ann.annotationType().getSimpleName());
                if (n >= 4) break;
            }
            return n == 0 ? "none" : sb.toString();
        } catch (Throwable ignored) {
            return "err";
        }
    }

    private static String describeFocusMethods(Class<?> type, RuntimeAnnotationIndex.ClassAnnotations view) {
        StringBuilder builder = new StringBuilder("focus=");
        int printed = 0;
        if (view != null) {
            for (String focus : FOCUS_METHODS) {
                for (Map.Entry<String, Set<RuntimeAnnotationIndex.Ann>> entry : view.getMethodAnnotations().entrySet()) {
                    if (entry.getKey() == null || !entry.getKey().startsWith(focus + "(")) continue;
                    if (printed++ > 0) builder.append(',');
                    builder.append(focus).append('@');
                    boolean first = true;
                    for (RuntimeAnnotationIndex.Ann ann : entry.getValue()) {
                        if (!first) builder.append('+');
                        first = false;
                        builder.append(ann.getSimpleName());
                    }
                }
            }
        }
        if (printed == 0) builder.append("none");
        return builder.toString();
    }

    private static boolean isFocus(String name) {
        for (String focus : FOCUS_METHODS) {
            if (focus.equals(name)) return true;
        }
        return false;
    }

    private static boolean isProxy(Class<?> type) {
        if (type == null) return false;
        String n = type.getName();
        return n.contains("$$") || n.contains("CGLIB") || n.contains("$Proxy") || n.contains("ByteBuddy");
    }

    private static boolean isAdvised(Object bean) {
        try {
            Class<?> advised = Class.forName("org.springframework.aop.framework.Advised", false, bean.getClass().getClassLoader());
            return advised.isInstance(bean);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Class<?> userClass(Class<?> type) {
        Class<?> current = type;
        while (current != null) {
            String name = current.getName();
            if (name.contains("$$") || name.contains("CGLIB") || name.contains("$Proxy") || name.contains("ByteBuddy")) {
                current = current.getSuperclass();
                continue;
            }
            return current;
        }
        return type;
    }

    private static String[] beanNamesForType(Object context, Object factory, Class<?> type) {
        try {
            if (factory != null) {
                Method method = findMethod(factory.getClass(), "getBeanNamesForType",
                        Class.class, boolean.class, boolean.class);
                if (method != null) {
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
        }
        return new String[0];
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
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params) {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, params);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        try {
            Method method = type.getMethod(name, params);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String joinClassNames(Collection<Class<?>> types) {
        if (types == null || types.isEmpty()) return "none";
        StringBuilder builder = new StringBuilder();
        int i = 0;
        for (Class<?> type : types) {
            if (type == null) continue;
            if (i++ > 0) builder.append(',');
            builder.append(type.getSimpleName());
            if (i >= 8) break;
        }
        return builder.length() == 0 ? "none" : builder.toString();
    }

    private static String safe(String value, int max) {
        if (value == null) return "none";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static Map<String, String> fields(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) map.put(keyValues[i], keyValues[i + 1]);
        return map;
    }

    private static final class ProbeResult {
        private final String summary;
        private final String verdict;
        private ProbeResult(String summary, String verdict) {
            this.summary = summary;
            this.verdict = verdict;
        }
    }
}

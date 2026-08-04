package dev.hotreload.agent.classes;

import org.objectweb.asm.Type;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 注解规范化指纹：descriptor 或 descriptor(name=value|...)，属性名排序。
 * 反射侧与字节码侧用同一规则渲染，保证同一逻辑注解两侧指纹一致：
 * - 注解类型可加载时按「全属性视图」（显式值覆盖默认值）渲染，
 *   消除「源码显式写了默认值」与「省略」之间的差异；
 * - 不可加载时退化为 descriptor + 显式属性（两侧同一 loader，可加载性一致）。
 * 指纹只用于相等性比较；渲染歧义只会造成保守的多余刷新，不会漏报变更。
 */
final class AnnotationFingerprint {
    private AnnotationFingerprint() { }

    /** 反射侧：live Class/Method 上的真实注解。 */
    static String of(Annotation annotation) {
        if (annotation == null) return "";
        Class<? extends Annotation> type = annotation.annotationType();
        Map<String, String> attrs = new TreeMap<String, String>();
        for (Method attribute : type.getDeclaredMethods()) {
            if (attribute.getParameterTypes().length != 0) continue;
            String rendered;
            try {
                attribute.setAccessible(true);
                rendered = render(attribute.invoke(annotation));
            } catch (Throwable failure) {
                rendered = "?err";
            }
            attrs.put(attribute.getName(), rendered);
        }
        return format(Type.getDescriptor(type), attrs);
    }

    /** 字节码侧：ASM 收集的显式属性值，按注解类型补齐默认值后渲染。 */
    static String of(String descriptor, Map<String, Object> explicitValues, ClassLoader loader) {
        Map<String, String> attrs = new TreeMap<String, String>();
        Class<?> type = loadAnnotationType(descriptor, loader);
        if (type != null) {
            for (Method attribute : type.getDeclaredMethods()) {
                if (attribute.getParameterTypes().length != 0) continue;
                String name = attribute.getName();
                Object value = explicitValues != null && explicitValues.containsKey(name)
                        ? explicitValues.get(name) : attribute.getDefaultValue();
                attrs.put(name, render(value));
            }
        } else if (explicitValues != null) {
            for (Map.Entry<String, Object> entry : explicitValues.entrySet()) {
                attrs.put(entry.getKey(), render(entry.getValue()));
            }
        }
        return format(descriptor, attrs);
    }

    /** 提取指纹中的 descriptor 部分（无属性时即整个指纹）。 */
    static String descriptorOf(String fingerprint) {
        if (fingerprint == null) return "";
        int paren = fingerprint.indexOf('(');
        return paren < 0 ? fingerprint : fingerprint.substring(0, paren);
    }

    private static String format(String descriptor, Map<String, String> attrs) {
        if (attrs.isEmpty()) return descriptor;
        StringBuilder builder = new StringBuilder(descriptor.length() + 32);
        builder.append(descriptor).append('(');
        boolean first = true;
        for (Map.Entry<String, String> entry : attrs.entrySet()) {
            if (!first) builder.append('|');
            first = false;
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.append(')').toString();
    }

    private static String render(Object value) {
        if (value == null) return "null";
        if (value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Number) {
            return String.valueOf(value);
        }
        if (value instanceof Enum) return ((Enum<?>) value).name();
        // 与 ASM Type.getClassName() 对齐：数组类渲染为 java.lang.String[] 而非 [Ljava.lang.String;
        if (value instanceof Class) return Type.getType((Class<?>) value).getClassName();
        if (value instanceof Type) return ((Type) value).getClassName();
        if (value instanceof Annotation) return of((Annotation) value);
        if (value instanceof List) {
            StringBuilder builder = new StringBuilder("[");
            List<?> list = (List<?>) value;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) builder.append(',');
                builder.append(render(list.get(i)));
            }
            return builder.append(']').toString();
        }
        if (value.getClass().isArray()) {
            StringBuilder builder = new StringBuilder("[");
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                if (i > 0) builder.append(',');
                builder.append(render(Array.get(value, i)));
            }
            return builder.append(']').toString();
        }
        return String.valueOf(value);
    }

    private static Class<?> loadAnnotationType(String descriptor, ClassLoader loader) {
        try {
            Class<?> type = Class.forName(Type.getType(descriptor).getClassName(), false, loader);
            return Annotation.class.isAssignableFrom(type) ? type : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}

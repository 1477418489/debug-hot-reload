package dev.hotreload.agent.spring;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Tracks Spring ApplicationContext instances observed in the target JVM.
 * Contexts are stored by identity using weak references.
 */
public final class SpringContextRegistry {
    private static final Object MONITOR = new Object();
    private static final List<WeakReference<Object>> CONTEXTS = new ArrayList<WeakReference<Object>>();

    private SpringContextRegistry() { }

    public static void register(Object applicationContext) {
        if (applicationContext == null) return;
        synchronized (MONITOR) {
            purge();
            for (WeakReference<Object> reference : CONTEXTS) {
                if (reference.get() == applicationContext) return;
            }
            CONTEXTS.add(new WeakReference<Object>(applicationContext));
        }
    }

    public static List<Object> snapshot() {
        synchronized (MONITOR) {
            purge();
            List<Object> result = new ArrayList<Object>(CONTEXTS.size());
            for (WeakReference<Object> reference : CONTEXTS) {
                Object context = reference.get();
                if (context != null) result.add(context);
            }
            return result;
        }
    }

    public static int size() {
        return snapshot().size();
    }

    private static void purge() {
        java.util.Iterator<WeakReference<Object>> iterator = CONTEXTS.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().get() == null) iterator.remove();
        }
    }
}

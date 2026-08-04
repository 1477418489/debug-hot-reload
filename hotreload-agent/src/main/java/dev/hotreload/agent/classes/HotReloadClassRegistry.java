package dev.hotreload.agent.classes;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Tracks the current live Class object for a binary name after structural hot-reload.
 * Memory bounded: only latest generation per name, weak Class refs, and at most {@link #MAX_ENTRIES}.
 */
public final class HotReloadClassRegistry {
    static final int MAX_ENTRIES = 512;

    private static final ConcurrentHashMap<String, WeakReference<Class<?>>> LIVE =
            new ConcurrentHashMap<String, WeakReference<Class<?>>>();
    private static final ConcurrentHashMap<String, Integer> GENERATION = new ConcurrentHashMap<String, Integer>();
    private static final ConcurrentLinkedQueue<String> INSERT_ORDER = new ConcurrentLinkedQueue<String>();

    private HotReloadClassRegistry() { }

    public static void put(String binaryName, Class<?> type) {
        if (binaryName == null || type == null) return;
        WeakReference<Class<?>> previous = LIVE.put(binaryName, new WeakReference<Class<?>>(type));
        Integer prev = GENERATION.get(binaryName);
        GENERATION.put(binaryName, Integer.valueOf(prev == null ? 1 : prev.intValue() + 1));
        if (previous == null) {
            INSERT_ORDER.add(binaryName);
        }
        trimToLimit();
    }

    public static Class<?> get(String binaryName) {
        if (binaryName == null) return null;
        WeakReference<Class<?>> ref = LIVE.get(binaryName);
        if (ref == null) return null;
        Class<?> type = ref.get();
        if (type == null) {
            LIVE.remove(binaryName, ref);
            GENERATION.remove(binaryName);
        }
        return type;
    }

    /**
     * Resolve live generation class for either original or generation binary name.
     * Registry keys are always the original business type name.
     */
    public static Class<?> resolveLive(String binaryName) {
        if (binaryName == null) return null;
        Class<?> direct = get(binaryName);
        if (direct != null) return direct;
        String original = stripGenerationName(binaryName);
        if (!original.equals(binaryName)) {
            return get(original);
        }
        return null;
    }

    public static String stripGenerationName(String binaryName) {
        if (binaryName == null) return null;
        int idx = binaryName.indexOf("__HrGen");
        if (idx > 0) return binaryName.substring(0, idx);
        idx = binaryName.indexOf("$$HrGen");
        if (idx > 0) return binaryName.substring(0, idx);
        return binaryName;
    }

    public static boolean isGenerationClassName(String name) {
        return name != null && (name.contains("__HrGen") || name.contains("$$HrGen"));
    }

    public static int generation(String binaryName) {
        Integer g = GENERATION.get(binaryName);
        return g == null ? 0 : g.intValue();
    }

    public static int size() {
        return LIVE.size();
    }

    public static void clear() {
        LIVE.clear();
        GENERATION.clear();
        INSERT_ORDER.clear();
    }

    public static Map<String, Class<?>> snapshot() {
        Map<String, Class<?>> map = new ConcurrentHashMap<String, Class<?>>();
        for (Map.Entry<String, WeakReference<Class<?>>> entry : LIVE.entrySet()) {
            Class<?> type = entry.getValue() == null ? null : entry.getValue().get();
            if (type != null) map.put(entry.getKey(), type);
        }
        return map;
    }

    private static void trimToLimit() {
        while (LIVE.size() > MAX_ENTRIES) {
            String oldest = INSERT_ORDER.poll();
            if (oldest == null) break;
            if (!LIVE.containsKey(oldest)) continue;
            LIVE.remove(oldest);
            GENERATION.remove(oldest);
        }
    }
}

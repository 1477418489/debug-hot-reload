package dev.hotreload.idea.run;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import dev.hotreload.idea.logging.PluginSessionDiagnostics;
import dev.hotreload.idea.settings.HotReloadSettings;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * Prevents IntelliJ built-in HotSwap from fighting this plugin.
 *
 * Built-in HotSwap uses stock redefineClasses and will popup/report
 * "虚拟机不支持的操作: add/delete method not implemented" for structure changes,
 * even when our Agent already reloaded successfully via Generation ClassLoader.
 *
 * RUN_HOTSWAP_AFTER_COMPILE 是持久化的 application 级设置：被覆盖的原值必须同样
 * 持久化（存入 {@link HotReloadSettings}），IDE 崩溃后下次启动仍能恢复；
 * veto listener 按 Project 管理，多项目同时 Debug 互不串扰。
 */
public final class IdeaBuiltinHotSwapGuard {
    private static final Object MONITOR = new Object();
    private static final Map<Project, Object> VETO_LISTENERS = new HashMap<Project, Object>();
    private static final SessionCounts<Project> SESSIONS = new SessionCounts<Project>();

    private IdeaBuiltinHotSwapGuard() { }

    public static void onSessionActivated(Project project, PluginSessionDiagnostics diagnostics, String launchId) {
        if (project == null) return;
        synchronized (MONITOR) {
            if (project.isDisposed()) return;
            SESSIONS.open(project);
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            synchronized (MONITOR) {
                if (project.isDisposed()) {
                    SESSIONS.removeAll(project);
                    removeVetoListener(project);
                    if (SESSIONS.total() == 0) restoreBuiltinHotSwap(diagnostics, launchId);
                    return;
                }
                if (!SESSIONS.has(project)) return;
                disableBuiltinHotSwap(project, diagnostics, launchId, SESSIONS.total());
            }
        });
    }

    public static void onSessionClosed(Project project, PluginSessionDiagnostics diagnostics, String launchId) {
        boolean removeProjectListener;
        synchronized (MONITOR) {
            if (!SESSIONS.close(project)) return;
            removeProjectListener = !SESSIONS.has(project);
        }
        boolean shouldRemoveProjectListener = removeProjectListener;
        ApplicationManager.getApplication().invokeLater(() -> {
            synchronized (MONITOR) {
                if (shouldRemoveProjectListener && !SESSIONS.has(project)) {
                    removeVetoListener(project);
                }
                if (SESSIONS.total() == 0) restoreBuiltinHotSwap(diagnostics, launchId);
            }
        });
    }

    public static void forceRestore(Project project, PluginSessionDiagnostics diagnostics) {
        if (project == null) return;
        synchronized (MONITOR) {
            SESSIONS.removeAll(project);
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            synchronized (MONITOR) {
                if (!SESSIONS.has(project)) removeVetoListener(project);
                if (SESSIONS.total() == 0) restoreBuiltinHotSwap(diagnostics, null);
            }
        });
    }

    /**
     * 崩溃恢复：上次 IDE 异常退出时若 previous 值仍滞留在持久化设置里
     * （无活跃会话却有保存值），把内置 HotSwap 设置还原。项目服务启动时调用。
     */
    public static void recoverStaleOverride(PluginSessionDiagnostics diagnostics) {
        synchronized (MONITOR) {
            if (SESSIONS.total() > 0) return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            synchronized (MONITOR) {
                if (SESSIONS.total() == 0) restoreBuiltinHotSwap(diagnostics, null);
            }
        });
    }

    /** package-visible for tests */
    static int activeSessionsForTest() {
        synchronized (MONITOR) {
            return SESSIONS.total();
        }
    }

    static void resetForTest() {
        synchronized (MONITOR) {
            VETO_LISTENERS.clear();
            SESSIONS.clear();
        }
    }

    private static void disableBuiltinHotSwap(Project project, PluginSessionDiagnostics diagnostics,
                                             String launchId, int active) {
        try {
            Object settings = debuggerSettings();
            if (settings != null) {
                Field runHotSwap = findField(settings.getClass(), "RUN_HOTSWAP_AFTER_COMPILE");
                if (runHotSwap != null) {
                    runHotSwap.setAccessible(true);
                    String current = String.valueOf(runHotSwap.get(settings));
                    String never = constant(settings.getClass(), "RUN_HOTSWAP_NEVER", "0");
                    if (!never.equals(current)) {
                        // current != never means this is not an override written by this guard.
                        // Replace any stale crash-recovery value before installing a new override.
                        if (savePreviousRunHotSwap(current)) {
                            runHotSwap.set(settings, never);
                            if (diagnostics != null) {
                                diagnostics.info("IDEA_HOTSWAP_DISABLED", launchId,
                                        "reason", "avoid_builtin_redefine_conflict",
                                        "previous", current,
                                        "activeSessions", Integer.toString(active));
                            }
                        } else if (diagnostics != null) {
                            diagnostics.warn("IDEA_HOTSWAP_DISABLE_FAILED", launchId,
                                    "reason", "recovery_state_unavailable");
                        }
                    } else if (diagnostics != null) {
                        diagnostics.info("IDEA_HOTSWAP_ALREADY_OFF", launchId,
                                "activeSessions", Integer.toString(active));
                    }
                }
            }
            installVetoListener(project, diagnostics, launchId);
        } catch (Throwable failure) {
            if (diagnostics != null) {
                diagnostics.warn("IDEA_HOTSWAP_DISABLE_FAILED", launchId,
                        "reason", failure.getClass().getSimpleName());
            }
        }
    }

    private static void restoreBuiltinHotSwap(PluginSessionDiagnostics diagnostics, String launchId) {
        try {
            String previous = savedPreviousRunHotSwap();
            if (previous == null) return;
            Object settings = debuggerSettings();
            if (settings == null) return;
            Field runHotSwap = findField(settings.getClass(), "RUN_HOTSWAP_AFTER_COMPILE");
            if (runHotSwap == null) return;
            runHotSwap.setAccessible(true);
            String current = String.valueOf(runHotSwap.get(settings));
            String never = constant(settings.getClass(), "RUN_HOTSWAP_NEVER", "0");
            if (never.equals(current)) {
                runHotSwap.set(settings, previous);
                if (diagnostics != null) {
                    diagnostics.info("IDEA_HOTSWAP_RESTORED", launchId, "value", previous);
                }
            } else if (diagnostics != null) {
                diagnostics.info("IDEA_HOTSWAP_RESTORE_SKIPPED", launchId,
                        "reason", "setting_changed_externally",
                        "current", current);
            }
            if (!savePreviousRunHotSwap(null) && diagnostics != null) {
                diagnostics.warn("IDEA_HOTSWAP_RESTORE_FAILED", launchId,
                        "reason", "recovery_state_clear_failed");
            }
        } catch (Throwable failure) {
            if (diagnostics != null) {
                diagnostics.warn("IDEA_HOTSWAP_RESTORE_FAILED", launchId,
                        "reason", failure.getClass().getSimpleName());
            }
        }
    }

    private static String savedPreviousRunHotSwap() {
        try {
            return HotReloadSettings.getInstance().getPreviousRunHotSwap();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean savePreviousRunHotSwap(String value) {
        try {
            HotReloadSettings settings = HotReloadSettings.getInstance();
            if (settings == null) return false;
            settings.setPreviousRunHotSwap(value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void installVetoListener(Project project, PluginSessionDiagnostics diagnostics, String launchId) {
        if (project == null || project.isDisposed()) return;
        synchronized (MONITOR) {
            if (VETO_LISTENERS.containsKey(project)) return;
        }
        try {
            ClassLoader cl = pluginAwareClassLoader(project);
            Class<?> hotSwapUiType = Class.forName("com.intellij.debugger.ui.HotSwapUI", true, cl);
            Method getInstance = hotSwapUiType.getMethod("getInstance", Project.class);
            Object ui = getInstance.invoke(null, project);
            if (ui == null) return;
            Class<?> listenerType = Class.forName("com.intellij.debugger.ui.HotSwapVetoableListener", true, cl);
            Object listener = Proxy.newProxyInstance(cl, new Class<?>[]{listenerType}, (proxy, method, args) -> {
                String name = method.getName();
                if ("shouldHotSwap".equals(name)) {
                    synchronized (MONITOR) {
                        return !SESSIONS.has(project);
                    }
                }
                if ("equals".equals(name)) {
                    return args != null && args.length == 1 && proxy == args[0];
                }
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("toString".equals(name)) return "MyBatisHotReloadHotSwapVeto";
                Class<?> rt = method.getReturnType();
                if (rt == boolean.class) return false;
                if (rt == int.class) return 0;
                return null;
            });
            Method addListener = hotSwapUiType.getMethod("addListener", listenerType);
            addListener.invoke(ui, listener);
            synchronized (MONITOR) {
                VETO_LISTENERS.put(project, listener);
            }
            if (diagnostics != null) {
                diagnostics.info("IDEA_HOTSWAP_VETO_INSTALLED", launchId);
            }
        } catch (Throwable failure) {
            if (diagnostics != null) {
                diagnostics.warn("IDEA_HOTSWAP_VETO_FAILED", launchId,
                        "reason", failure.getClass().getSimpleName());
            }
        }
    }

    private static void removeVetoListener(Project project) {
        if (project == null) return;
        Object listener;
        synchronized (MONITOR) {
            listener = VETO_LISTENERS.get(project);
        }
        if (listener == null) return;
        if (project.isDisposed()) {
            synchronized (MONITOR) {
                VETO_LISTENERS.remove(project, listener);
            }
            return;
        }
        try {
            ClassLoader cl = pluginAwareClassLoader(project);
            Class<?> hotSwapUiType = Class.forName("com.intellij.debugger.ui.HotSwapUI", true, cl);
            Method getInstance = hotSwapUiType.getMethod("getInstance", Project.class);
            Object ui = getInstance.invoke(null, project);
            if (ui == null) return;
            Class<?> listenerType = Class.forName("com.intellij.debugger.ui.HotSwapVetoableListener", true, cl);
            Method removeListener = hotSwapUiType.getMethod("removeListener", listenerType);
            removeListener.invoke(ui, listener);
            synchronized (MONITOR) {
                VETO_LISTENERS.remove(project, listener);
            }
        } catch (Throwable ignored) {
            // Keep the reference so a later session close or project disposal can retry removal.
        }
    }


    private static ClassLoader pluginAwareClassLoader(Project project) {
        ClassLoader[] candidates = new ClassLoader[] {
                Thread.currentThread().getContextClassLoader(),
                IdeaBuiltinHotSwapGuard.class.getClassLoader(),
                project == null ? null : project.getClass().getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader candidate : candidates) {
            if (candidate == null) continue;
            try {
                Class.forName("com.intellij.debugger.ui.HotSwapUI", false, candidate);
                return candidate;
            } catch (Throwable ignored) {
            }
        }
        return IdeaBuiltinHotSwapGuard.class.getClassLoader();
    }

    private static Object debuggerSettings() {
        try {
            Class<?> type = Class.forName("com.intellij.debugger.settings.DebuggerSettings");
            Method getInstance = type.getMethod("getInstance");
            return getInstance.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String constant(Class<?> type, String name, String fallback) {
        try {
            Field field = type.getField(name);
            Object value = field.get(null);
            return value == null ? fallback : String.valueOf(value);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> cur = type;
        while (cur != null) {
            try {
                return cur.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    /** Guarded by {@link #MONITOR}; package-visible for deterministic counter tests. */
    static final class SessionCounts<K> {
        private final Map<K, Integer> counts = new HashMap<K, Integer>();
        private int total;

        int open(K key) {
            if (key == null) throw new NullPointerException("key");
            Integer current = counts.get(key);
            counts.put(key, current == null ? 1 : current + 1);
            return ++total;
        }

        boolean close(K key) {
            Integer current = counts.get(key);
            if (current == null || current <= 0) return false;
            if (current == 1) counts.remove(key);
            else counts.put(key, current - 1);
            total--;
            return true;
        }

        int removeAll(K key) {
            Integer removed = counts.remove(key);
            if (removed == null || removed <= 0) return 0;
            total -= removed;
            return removed;
        }

        boolean has(K key) {
            Integer count = counts.get(key);
            return count != null && count > 0;
        }

        int total() {
            return total;
        }

        void clear() {
            counts.clear();
            total = 0;
        }
    }
}

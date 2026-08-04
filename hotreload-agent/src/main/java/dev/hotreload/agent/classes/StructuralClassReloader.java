package dev.hotreload.agent.classes;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Handles class structure changes (new/deleted fields/methods) and brand-new classes.
 *
 * Stock HotSpot cannot redefine schema changes. Structural reloads use an assignable
 * subclass generation ({@code Original__HrGenN extends Original}) so Spring casts keep working.
 * Name must avoid "$$" (Spring getUserClass would strip to superclass and miss new mappings).
 * Never redefine schema deltas with redefineClasses (avoids add/delete method not implemented).
 */
public final class StructuralClassReloader {
    public static final class Result {
        public final Class<?> liveClass;
        public final String mode; // redefined | generation | defined | failed
        public final String detail;
        public final boolean success;

        Result(Class<?> liveClass, String mode, String detail, boolean success) {
            this.liveClass = liveClass;
            this.mode = mode;
            this.detail = detail;
            this.success = success;
        }
    }

    private final Instrumentation instrumentation;
    /** E2 capability injected once at session start; probing must stay off the reload path. */
    private final boolean enhancedRedefineCapable;

    public StructuralClassReloader(Instrumentation instrumentation) {
        this(instrumentation, false);
    }

    public StructuralClassReloader(Instrumentation instrumentation, boolean enhancedRedefineCapable) {
        this.instrumentation = instrumentation;
        this.enhancedRedefineCapable = enhancedRedefineCapable;
    }

    public Result reloadExisting(Class<?> loaded, byte[] bytecode) {
        return reloadExisting(loaded, bytecode, false);
    }

    /**
     * @param knownStructureChange when true, skip redefine and go straight to generation.
     */
    public Result reloadExisting(Class<?> loaded, byte[] bytecode, boolean knownStructureChange) {
        if (loaded == null || bytecode == null) {
            return new Result(null, "failed", "null_input", false);
        }
        boolean structure = knownStructureChange;
        ClassChangeAnalyzer.Analysis analysis = null;
        if (!structure) {
            try {
                analysis = ClassChangeAnalyzer.compare(loaded, bytecode);
                structure = analysis != null && analysis.isStructureChanged();
            } catch (Throwable ignored) {
                // treat as unknown; try redefine then generation
            }
        } else {
            try {
                analysis = ClassChangeAnalyzer.compare(loaded, bytecode);
            } catch (Throwable ignored) {
            }
        }
        if (structure) {
            // E2 primary engine: enhanced redefine (DCEVM/JBR) keeps class identity and all
            // live instances — no generation subclass, no bean recreation chain needed.
            if (enhancedRedefineCapable
                    && instrumentation != null
                    && !isGenerationClassName(loaded.getName())
                    && instrumentation.isModifiableClass(loaded)) {
                try {
                    instrumentation.redefineClasses(new ClassDefinition(loaded, bytecode));
                    // No annotation index/patch here: enhanced redefine keeps class identity and
                    // reflection rebuilds annotations from the new bytecode. Synthesized annotation
                    // proxies would LOSE Spring @AliasFor merging ({GET []} ambiguous mappings).
                    HotReloadClassRegistry.put(loaded.getName(), loaded);
                    return new Result(loaded, "redefined", "enhanced_redefine:identity_kept", true);
                } catch (Throwable enhancedRejected) {
                    // Even DCEVM rejects some shapes (enum constants, hierarchy changes):
                    // fall back to the generation subclass path.
                    Result generated = defineAssignableGeneration(loaded, bytecode, analysis,
                            "enhanced_rejected:" + rootMessage(enhancedRejected));
                    if (generated.success) {
                        return generated;
                    }
                    return new Result(loaded, "failed",
                            rootName(enhancedRejected) + ":" + rootMessage(enhancedRejected)
                                    + "|generation=" + generated.detail, false);
                }
            }
            return defineAssignableGeneration(loaded, bytecode, analysis,
                    knownStructureChange ? "known_structure" : "detected_structure");
        }

        // Compatible shape only: try redefine, then generation on schema rejection.
        try {
            if (instrumentation != null
                    && instrumentation.isModifiableClass(loaded)
                    && instrumentation.isRedefineClassesSupported()) {
                instrumentation.redefineClasses(new ClassDefinition(loaded, bytecode));
                try {
                    RuntimeAnnotationIndex.update(loaded, bytecode);
                    RuntimeAnnotationPatcher.patch(loaded, bytecode);
                } catch (Throwable ignored) {
                    // annotation patch is best-effort; redefine already applied
                }
                HotReloadClassRegistry.put(loaded.getName(), loaded);
                return new Result(loaded, "redefined", "jvm_redefine_ok", true);
            }
        } catch (Throwable redefineFailed) {
            Result generated = defineAssignableGeneration(loaded, bytecode, analysis,
                    "redefine_rejected:" + rootMessage(redefineFailed));
            if (generated.success) {
                return generated;
            }
            return new Result(loaded, "failed",
                    rootName(redefineFailed) + ":" + rootMessage(redefineFailed)
                            + "|generation=" + generated.detail,
                    false);
        }
        return defineAssignableGeneration(loaded, bytecode, analysis, "redefine_unavailable");
    }

    private Result defineAssignableGeneration(Class<?> loaded, byte[] bytecode,
                                              ClassChangeAnalyzer.Analysis analysis, String reason) {
        // Prefer assignable subclass to avoid "Foo cannot be cast to Foo" across loaders.
        try {
            Class<?> base = resolveBaseType(loaded);
            StructureSubclassFactory.Built built = StructureSubclassFactory.build(base, bytecode);
            ClassLoader parent = base.getClassLoader();
            GenerationClassLoader gen = new GenerationClassLoader(parent, built.binaryName, built.bytecode);
            Class<?> next = gen.defineTarget();
            if (!base.isAssignableFrom(next)) {
                return new Result(loaded, "failed",
                        "generation_not_assignable:" + next.getName() + "!->" + base.getName(), false);
            }
            try {
                RuntimeAnnotationIndex.update(next, built.bytecode);
                RuntimeAnnotationPatcher.patch(next, built.bytecode);
            } catch (Throwable ignored) {
            }
            // Registry key stays the original binary name so subsequent reloads resolve the live type.
            HotReloadClassRegistry.put(base.getName(), next);
            return new Result(next, "generation",
                    "loader=" + gen.getTag()
                            + ",gen=" + HotReloadClassRegistry.generation(base.getName())
                            + ",subclass=" + next.getName()
                            + ",assignable=true"
                            + ",bridgedFields=" + built.bridgedFields
                            + ",bridgedMethods=" + built.bridgedMethods
                            + ",reason=" + reason, true);
        } catch (Throwable subclassFailed) {
            // Last resort: same-name generation (may ClassCast in Spring). Prefer failure clarity.
            return defineSameNameGeneration(loaded, bytecode, reason + "|subclass_failed:"
                    + rootName(subclassFailed) + ":" + rootMessage(subclassFailed));
        }
    }

    private Result defineSameNameGeneration(Class<?> loaded, byte[] bytecode, String reason) {
        try {
            ClassLoader parent = loaded.getClassLoader();
            if (parent instanceof GenerationClassLoader) {
                parent = parent.getParent();
            }
            GenerationClassLoader gen = new GenerationClassLoader(parent, loaded.getName(), bytecode);
            Class<?> next = gen.defineTarget();
            try {
                RuntimeAnnotationIndex.update(next, bytecode);
                RuntimeAnnotationPatcher.patch(next, bytecode);
            } catch (Throwable ignored) {
            }
            HotReloadClassRegistry.put(loaded.getName(), next);
            return new Result(next, "generation", "loader=" + gen.getTag()
                    + ",gen=" + HotReloadClassRegistry.generation(loaded.getName())
                    + ",subclass=false,assignable=false,reason=" + reason, true);
        } catch (Throwable failure) {
            return new Result(loaded, "failed", "generation_failed:" + rootName(failure)
                    + ":" + rootMessage(failure), false);
        }
    }

    static boolean isGenerationClassName(String name) {
        return name != null && (name.contains("__HrGen") || name.contains("$$HrGen"));
    }

    private static Class<?> resolveBaseType(Class<?> loaded) {
        Class<?> cur = loaded;
        // Unwrap previous generation subclasses and Spring CGLIB enhancers back to the business type.
        while (cur != null) {
            String name = cur.getName();
            boolean wrapper = isGenerationClassName(name)
                    || name.contains("$$EnhancerBySpringCGLIB")
                    || name.contains("$$EnhancerByCGLIB")
                    || name.contains("$$FastClassBySpringCGLIB")
                    || name.contains("$$FastClassByCGLIB");
            if (!wrapper) {
                break;
            }
            Class<?> superType = cur.getSuperclass();
            if (superType == null || superType == Object.class) {
                break;
            }
            cur = superType;
        }
        return cur == null ? loaded : cur;
    }

    public Result defineNew(String binaryName, byte[] bytecode) {
        try {
            Class<?> defined = NewClassDefiner.define(binaryName, bytecode, instrumentation);
            RuntimeAnnotationIndex.update(defined, bytecode);
            RuntimeAnnotationPatcher.patch(defined, bytecode);
            HotReloadClassRegistry.put(binaryName, defined);
            return new Result(defined, "defined", "new_class", true);
        } catch (Throwable first) {
            try {
                ClassLoader parent = Thread.currentThread().getContextClassLoader();
                if (parent == null) parent = ClassLoader.getSystemClassLoader();
                GenerationClassLoader gen = new GenerationClassLoader(parent, binaryName, bytecode);
                Class<?> defined = gen.defineTarget();
                RuntimeAnnotationIndex.update(defined, bytecode);
                RuntimeAnnotationPatcher.patch(defined, bytecode);
                HotReloadClassRegistry.put(binaryName, defined);
                return new Result(defined, "defined", "generation_define:" + gen.getTag(), true);
            } catch (Throwable second) {
                return new Result(null, "failed", "define_failed:" + rootName(first)
                        + "/" + rootName(second), false);
            }
        }
    }

    public static Map<String, Class<?>> currentTypes(Iterable<String> binaryNames) {
        Map<String, Class<?>> map = new LinkedHashMap<String, Class<?>>();
        if (binaryNames == null) return map;
        for (String name : binaryNames) {
            Class<?> live = HotReloadClassRegistry.get(name);
            if (live != null) map.put(name, live);
        }
        return map;
    }

    public static List<Class<?>> asList(Map<String, Class<?>> map) {
        return new ArrayList<Class<?>>(map.values());
    }

    public static boolean isLikelyStructureFailure(Throwable error) {
        Throwable cur = error;
        while (cur != null) {
            if (containsStructureToken(String.valueOf(cur.getMessage()))) {
                return true;
            }
            if (containsStructureToken(String.valueOf(cur))) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    static boolean containsStructureToken(String normalizedMessage) {
        if (normalizedMessage == null) return false;
        String msg = normalizedMessage.toLowerCase(Locale.ROOT);
        return msg.contains("schema")
                || msg.contains("add method")
                || msg.contains("delete method")
                || msg.contains("add field")
                || msg.contains("delete field")
                || msg.contains("hierarchy")
                || msg.contains("class redefinition failed")
                || msg.contains("not implemented")
                || msg.contains("attempted to add")
                || msg.contains("attempted to delete")
                || msg.contains("attempted to change")
                || msg.contains("虚拟机不支持")
                || msg.contains("不支持的操作")
                || msg.contains("删除方法")
                || msg.contains("新增方法")
                || msg.contains("删除字段")
                || msg.contains("新增字段")
                || msg.contains("nesthost")
                || msg.contains("nestmembers")
                || msg.contains("permitted")
                || msg.contains("record attribute")
                || msg.contains("superclass")
                || msg.contains("interfaces");
    }

    private static String rootName(Throwable error) {
        Throwable cur = error;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur.getClass().getSimpleName();
    }

    private static String rootMessage(Throwable error) {
        Throwable cur = error;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String msg = cur.getMessage();
        if (msg == null) return "none";
        msg = msg.replace('\n', ' ').replace('\r', ' ');
        return msg.length() > 120 ? msg.substring(0, 120) : msg;
    }
}

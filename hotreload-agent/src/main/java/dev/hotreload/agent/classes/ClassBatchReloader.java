package dev.hotreload.agent.classes;

import dev.hotreload.agent.logging.AgentSessionLogger;
import dev.hotreload.agent.spring.SpringFrameworkRebinder;
import dev.hotreload.protocol.message.ClassReloadRequest;
import dev.hotreload.protocol.message.ClassUpdate;
import dev.hotreload.protocol.message.OperationStatus;
import dev.hotreload.protocol.message.ReloadErrorCode;
import dev.hotreload.protocol.message.ReloadItemResult;
import dev.hotreload.protocol.message.ReloadResponse;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class ClassBatchReloader {
    private final Instrumentation instrumentation;
    private final AgentSessionLogger logger;
    private final ClassUpdateValidator validator = new ClassUpdateValidator();
    private final SpringFrameworkRebinder springRebinder;
    private final StructuralClassReloader structuralReloader;
    /** E2: annotations are natively correct after redefine; synthetic patches would corrupt them. */
    private final boolean enhancedRedefineCapable;

    public ClassBatchReloader(Instrumentation instrumentation, AgentSessionLogger logger) {
        this(instrumentation, logger, false);
    }

    public ClassBatchReloader(Instrumentation instrumentation, AgentSessionLogger logger,
                              boolean enhancedRedefineCapable) {
        if (instrumentation == null) throw new NullPointerException("instrumentation");
        if (logger == null) throw new NullPointerException("logger");
        this.instrumentation = instrumentation;
        this.logger = logger;
        this.springRebinder = new SpringFrameworkRebinder(logger);
        this.structuralReloader = new StructuralClassReloader(instrumentation, enhancedRedefineCapable);
        this.enhancedRedefineCapable = enhancedRedefineCapable;
    }

    public ReloadResponse reload(ClassReloadRequest request) {
        long started = System.nanoTime();
        List<ClassUpdate> updates = request.getUpdates();
        logger.log(Level.INFO, "CLASS_BATCH_RECEIVED", fields("requestId", request.getRequestId(),
                "classCount", Integer.toString(updates.size()), "classNames", summarize(updates),
                "payloadBytes", Long.toString(payloadBytes(updates))));
        if (!instrumentation.isRedefineClassesSupported()) {
            return loggedFailure(request, ReloadErrorCode.CLASS_REDEFINE_UNSUPPORTED,
                    OperationStatus.FAILED, started);
        }

        ClassUpdateValidator.ValidationResult validation = validator.validate(updates, targetClassMajorVersion());
        if (!validation.isSuccess()) {
            logger.log(Level.WARNING, "CLASS_VALIDATION_RESULT", fields("requestId", request.getRequestId(),
                    "resultCode", validation.getErrorCode().name()));
            return loggedFailure(request, validation.getItemId(), validation.getErrorCode(),
                    OperationStatus.FAILED, started);
        }

        Map<String, List<Class<?>>> loadedByName = loadedClasses(updates);
        List<ReloadItemResult> items = new ArrayList<ReloadItemResult>(updates.size());
        List<ClassDefinition> redefineBatch = new ArrayList<ClassDefinition>();
        List<Class<?>> redefinedTargets = new ArrayList<Class<?>>();
        Map<Class<?>, byte[]> annotationUpdates = new LinkedHashMap<Class<?>, byte[]>();
        List<Class<?>> springCandidates = new ArrayList<Class<?>>();
        Set<String> rebindHints = new LinkedHashSet<String>();
        // Structure/annotation deltas need bean-level refresh; body-only classes must keep state.
        Set<String> deepChangedNames = new LinkedHashSet<String>();
        // RequestMapping refresh is expensive and unsafe when partial; only when mapping metadata changes.
        boolean needsMappingRefresh = false;
        int definedCount = 0;
        int redefinedCount = 0;
        int failedCount = 0;
        boolean restartRequired = false;
        ReloadErrorCode firstError = null;

        for (ClassUpdate update : updates) {
            List<Class<?>> matches = loadedByName.get(update.getBinaryName());
            Class<?> target = resolveLoadedTarget(update.getBinaryName(), matches, rebindHints);
            if (target == null && (matches == null || matches.isEmpty())) {
                ItemOutcome defined = defineNewClass(request, update);
                items.add(defined.item);
                if (defined.item.getStatus() == OperationStatus.SUCCESS) {
                    definedCount++;
                    if (defined.loadedClass != null) springCandidates.add(defined.loadedClass);
                    rebindHints.add("defined=" + update.getBinaryName());
                    deepChangedNames.add(update.getBinaryName());
                    // Newly defined handlers may introduce routes.
                    needsMappingRefresh = true;
                } else {
                    failedCount++;
                    if (firstError == null) firstError = defined.item.getErrorCode();
                    if (defined.item.getStatus() == OperationStatus.RESTART_REQUIRED) restartRequired = true;
                }
                continue;
            }
            if (target == null) {
                int count = matches == null ? 0 : matches.size();
                ReloadItemResult item = item(update.getBinaryName(), OperationStatus.FAILED,
                        ReloadErrorCode.CLASS_AMBIGUOUS, "loadedCount=" + count);
                items.add(item);
                failedCount++;
                if (firstError == null) firstError = ReloadErrorCode.CLASS_AMBIGUOUS;
                continue;
            }
            if (!instrumentation.isModifiableClass(target)) {
                ReloadItemResult item = item(update.getBinaryName(), OperationStatus.FAILED,
                        ReloadErrorCode.CLASS_UNMODIFIABLE, "unmodifiable");
                items.add(item);
                failedCount++;
                if (firstError == null) firstError = ReloadErrorCode.CLASS_UNMODIFIABLE;
                continue;
            }

            ClassChangeAnalyzer.Analysis analysis = ClassChangeAnalyzer.compare(target, update.getBytecode());
            // Always treat Spring-looking controllers/services as rebind candidates; annotation
            // changes may be invisible to ClassLoader resource comparison on JDK8.
            // JDK8 redefine does not reliably refresh RuntimeVisibleAnnotations.
            // Always force-patch annotations for redefined classes, then Spring-rebind.
            boolean forceAnnotationPatch = true;
            logger.log(Level.INFO, "CLASS_CHANGE_ANALYSIS", fields(
                    "requestId", request.getRequestId(),
                    "itemId", update.getBinaryName(),
                    "resultCode", analysis.getKind().name(),
                    "detail", "annotationChanged=" + analysis.isAnnotationChanged()
                            + ",structureChanged=" + analysis.isStructureChanged()
                            + ",webMappingChanged=" + analysis.isWebMappingAnnotationChanged()
                            + ",forceAnnotationPatch=" + forceAnnotationPatch));
            if (analysis.isAnnotationChanged()) rebindHints.add("annotation=" + update.getBinaryName());
            if (analysis.isStructureChanged()) rebindHints.add("structure=" + update.getBinaryName());
            if (analysis.isAnnotationChanged() || analysis.isStructureChanged()) {
                deepChangedNames.add(update.getBinaryName());
            }
            if (analysis.needsRequestMappingRefresh()) {
                needsMappingRefresh = true;
                rebindHints.add("mappingRefreshNeeded=" + update.getBinaryName());
            }
            if (analysis.isStructureChanged()) {
                StructuralClassReloader.Result structural = structuralReloader.reloadExisting(target, update.getBytecode(), true);
                logger.log(structural.success ? Level.INFO : Level.WARNING, "CLASS_STRUCTURE_RELOAD", fields(
                        "requestId", request.getRequestId(),
                        "itemId", update.getBinaryName(),
                        "resultCode", structural.success ? "SUCCESS" : "FAILED",
                        "detail", "mode=" + structural.mode + "," + structural.detail));
                if (structural.success && structural.liveClass != null) {
                    redefinedCount++;
                    redefinedTargets.add(structural.liveClass);
                    springCandidates.add(structural.liveClass);
                    // Generation mode only: liveClass is the __HrGenN subclass, keep its business
                    // base so Spring bean lookup by registered type succeeds. Under enhanced
                    // redefine liveClass IS the original class — adding its superclass would drag
                    // shared base controllers into the rebind and unregister the whole app's routes.
                    if (structural.liveClass != target) {
                        Class<?> base = structural.liveClass.getSuperclass();
                        if (base != null && base != Object.class) {
                            springCandidates.add(base);
                        }
                    }
                    annotationUpdates.put(structural.liveClass, update.getBytecode());
                    // Structure always needs mapping rebind (new/removed methods/fields).
                    needsMappingRefresh = true;
                    items.add(item(update.getBinaryName(), OperationStatus.SUCCESS, null,
                            "structure:" + structural.mode + ":" + structural.detail));
                    rebindHints.add("structureMode=" + structural.mode + ":" + update.getBinaryName());
                    rebindHints.add("mappingRefreshNeeded=structure:" + update.getBinaryName());
                    continue;
                }
                // Never fall through to HotSpot redefine for structure changes.
                // redefineClasses would throw add/delete method not implemented on stock JVMs.
                ReloadItemResult failedItem = item(update.getBinaryName(), OperationStatus.RESTART_REQUIRED,
                        ReloadErrorCode.CLASS_STRUCTURE_CHANGED,
                        "structure_generation_failed:" + structural.detail);
                items.add(failedItem);
                failedCount++;
                restartRequired = true;
                if (firstError == null) firstError = ReloadErrorCode.CLASS_STRUCTURE_CHANGED;
                rebindHints.add("structureMode=failed:" + update.getBinaryName());
                continue;
            }
            redefineBatch.add(new ClassDefinition(target, update.getBytecode()));
            redefinedTargets.add(target);
            if (forceAnnotationPatch) {
                annotationUpdates.put(target, update.getBytecode());
            }
            if (analysis.needsSpringRebind() || forceAnnotationPatch || analysis.isStructureChanged()) {
                springCandidates.add(target);
            }
        }

        if (!redefineBatch.isEmpty()) {
            AnnotationHotReloadDiagnostics.pipelineStart(logger, request.getRequestId(), redefinedTargets);
            logger.log(Level.INFO, "REDEFINE_BEGIN", fields("requestId", request.getRequestId(),
                    "classCount", Integer.toString(redefineBatch.size())));
            try {
                instrumentation.redefineClasses(redefineBatch.toArray(new ClassDefinition[redefineBatch.size()]));
                redefinedCount += redefineBatch.size();
                for (Class<?> redefined : redefinedTargets) {
                    HotReloadClassRegistry.put(redefined.getName(), redefined);
                }
                int reflectionInvalidated = ReflectionDataInvalidator.invalidateAll(redefinedTargets);
                int annotationPatchedMethods = 0;
                int annotationPatchedClasses = 0;
                // E2 (enhanced runtime): reflection now serves the new bytecode natively.
                // Index publication + forced patches would replace real annotations with
                // synthesized proxies that lose Spring @AliasFor merging — skip entirely.
                if (!enhancedRedefineCapable) {
                    for (Map.Entry<Class<?>, byte[]> entry : annotationUpdates.entrySet()) {
                        RuntimeAnnotationIndex.update(entry.getKey(), entry.getValue());
                        rebindHints.add(RuntimeAnnotationIndex.describe(entry.getKey()));
                        rebindHints.add("annotationIndex=published:" + entry.getKey().getSimpleName());
                        AnnotationHotReloadDiagnostics.bytecodePublished(logger, request.getRequestId(),
                                entry.getKey(), entry.getValue());
                        RuntimeAnnotationPatcher.PatchReport patch =
                                RuntimeAnnotationPatcher.patch(entry.getKey(), entry.getValue());
                        annotationPatchedMethods += patch.getMethodsPatched();
                        annotationPatchedClasses += patch.getClassPatched();
                        rebindHints.add(patch.summary());
                        logger.log(Level.INFO, "ANNOTATION_PATCH", fields(
                                "requestId", request.getRequestId(),
                                "itemId", entry.getKey().getName(),
                                "resultCode", "SUCCESS",
                                "detail", patch.summary()));
                    }
                } else {
                    rebindHints.add("annotationPatch=skipped:enhancedRuntime");
                }
                if (AnnotationHotReloadDiagnostics.verboseEnabled()) {
                    AnnotationHotReloadDiagnostics.reflectAndSpringProbe(logger, request.getRequestId(), redefinedTargets);
                }
                logger.log(Level.INFO, "REDEFINE_END", fields("requestId", request.getRequestId(),
                        "itemId", "batch", "resultCode", "SUCCESS",
                        "durationMs", Long.toString(elapsedMillis(started)),
                        "classCount", Integer.toString(redefinedCount),
                        "detail", "reflectionInvalidated=" + reflectionInvalidated
                                + ",annotationPatchedMethods=" + annotationPatchedMethods
                                + ",annotationPatchedClasses=" + annotationPatchedClasses));
                for (Class<?> target : redefinedTargets) {
                    springCandidates.add(target);
                }
            } catch (UnmodifiableClassException e) {
                return finalizeBatch(request, started, items, definedCount, 0, updates.size(), true,
                        ReloadErrorCode.CLASS_UNMODIFIABLE, OperationStatus.FAILED, rebindHints, null);
            } catch (UnsupportedClassVersionError e) {
                return finalizeBatch(request, started, items, definedCount, 0, updates.size(), true,
                        ReloadErrorCode.CLASS_VERSION_UNSUPPORTED, OperationStatus.FAILED, rebindHints, null);
            } catch (UnsupportedOperationException e) {
                // Last-chance structure recovery: analysis may have missed the delta, or JVM
                // rejected a redefine that structural path can still handle via generation class.
                List<ClassDefinition> survivors = new ArrayList<ClassDefinition>();
                int recovered = 0;
                for (ClassDefinition definition : redefineBatch) {
                    Class<?> target = definition.getDefinitionClass();
                    byte[] bytecode = definition.getDefinitionClassFile();
                    // Always force knownStructure=true so we never call redefineClasses again.
                    StructuralClassReloader.Result structural =
                            structuralReloader.reloadExisting(target, bytecode, true);
                    logger.log(structural.success ? Level.INFO : Level.WARNING, "CLASS_STRUCTURE_FALLBACK", fields(
                            "requestId", request.getRequestId(),
                            "itemId", target.getName(),
                            "resultCode", structural.success ? "SUCCESS" : "FAILED",
                            "detail", "mode=" + structural.mode + "," + structural.detail
                                    + ",cause=" + String.valueOf(e.getMessage())));
                    if (structural.success && structural.liveClass != null) {
                        recovered++;
                        redefinedCount++;
                        redefinedTargets.add(structural.liveClass);
                        springCandidates.add(structural.liveClass);
                        annotationUpdates.put(structural.liveClass, bytecode);
                        deepChangedNames.add(target.getName());
                        items.add(item(target.getName(), OperationStatus.SUCCESS, null,
                                "structure-fallback:" + structural.mode + ":" + structural.detail));
                        rebindHints.add("structureFallback=" + structural.mode + ":" + target.getName());
                        needsMappingRefresh = true;
                    } else {
                        survivors.add(definition);
                    }
                }
                if (!survivors.isEmpty()) {
                    ReloadErrorCode code = isStructureChange(e) || StructuralClassReloader.isLikelyStructureFailure(e)
                            ? ReloadErrorCode.CLASS_STRUCTURE_CHANGED : ReloadErrorCode.CLASS_REDEFINE_FAILED;
                    OperationStatus status = code == ReloadErrorCode.CLASS_STRUCTURE_CHANGED
                            ? OperationStatus.RESTART_REQUIRED : OperationStatus.FAILED;
                    rebindHints.add("structureFallbackRecovered=" + recovered
                            + ",remaining=" + survivors.size());
                    return finalizeBatch(request, started, items, definedCount, recovered, updates.size(), true,
                            code, status, rebindHints, e.getMessage());
                }
                rebindHints.add("structureFallbackRecovered=" + recovered);
                // All classes recovered via generation path; continue to spring rebind below.
            } catch (ClassFormatError e) {
                return finalizeBatch(request, started, items, definedCount, 0, updates.size(), true,
                        ReloadErrorCode.CLASS_REDEFINE_FAILED, OperationStatus.FAILED, rebindHints, e.getMessage());
            } catch (NoClassDefFoundError e) {
                return finalizeBatch(request, started, items, definedCount, 0, updates.size(), true,
                        ReloadErrorCode.CLASS_REDEFINE_FAILED, OperationStatus.FAILED, rebindHints, e.getMessage());
            } catch (ClassCircularityError e) {
                return finalizeBatch(request, started, items, definedCount, 0, updates.size(), true,
                        ReloadErrorCode.CLASS_REDEFINE_FAILED, OperationStatus.FAILED, rebindHints, e.getMessage());
            } catch (LinkageError e) {
                return finalizeBatch(request, started, items, definedCount, 0, updates.size(), true,
                        ReloadErrorCode.CLASS_REDEFINE_FAILED, OperationStatus.FAILED, rebindHints, e.getMessage());
            } catch (Exception e) {
                return finalizeBatch(request, started, items, definedCount, 0, updates.size(), true,
                        ReloadErrorCode.CLASS_REDEFINE_FAILED, OperationStatus.FAILED, rebindHints,
                        e.getClass().getSimpleName());
            } catch (InternalError e) {
                return finalizeBatch(request, started, items, definedCount, 0, updates.size(), true,
                        ReloadErrorCode.CLASS_REDEFINE_FAILED, OperationStatus.FAILED, rebindHints,
                        e.getClass().getSimpleName());
            } catch (Error e) {
                return finalizeBatch(request, started, items, definedCount, 0, updates.size(), true,
                        ReloadErrorCode.CLASS_REDEFINE_FAILED, OperationStatus.FAILED, rebindHints,
                        e.getClass().getSimpleName());
            }
        }

        SpringFrameworkRebinder.RebindReport rebindReport = null;
        // Always rebind after redefine/define: even pure method-body changes can leave
        // stale RequestMappingHandlerMethod/AOP proxies when Spring caches bean type metadata.
        if (!springCandidates.isEmpty() || redefinedCount > 0 || definedCount > 0 || !rebindHints.isEmpty()) {
            Set<Class<?>> unique = new LinkedHashSet<Class<?>>(springCandidates);
            for (Class<?> target : redefinedTargets) unique.add(target);
            logger.log(Level.INFO, "ANNOTATION_REBIND_BEGIN", fields(
                    "requestId", request.getRequestId(),
                    "classCount", Integer.toString(unique.size()),
                    "detail", "candidates=" + unique.size()));
            rebindHints.add("mappingRefresh=" + (needsMappingRefresh ? "required" : "skipNonMapping"));
            rebindReport = springRebinder.rebind(unique, needsMappingRefresh, deepChangedNames);
            // E3 only: rebind clears Class reflection caches; re-apply annotation truth from index.
            // Under E2 real reflection IS the truth — re-applying would corrupt it.
            int repatched = enhancedRedefineCapable ? 0 : RuntimeAnnotationPatcher.reapplyFromIndex(unique);
            rebindHints.add(rebindReport.summary());
            String probe = AnnotationHotReloadDiagnostics.compactProbeForReport(unique);
            rebindHints.add(probe);
            if (AnnotationHotReloadDiagnostics.verboseEnabled()) { AnnotationHotReloadDiagnostics.reflectAndSpringProbe(logger, request.getRequestId(), unique); }
            AnnotationHotReloadDiagnostics.pipelineEnd(logger, request.getRequestId(), rebindReport.summary() + "|repatched=" + repatched + "|" + probe, unique);
            logger.log(Level.INFO, "ANNOTATION_POST_REBIND_PROBE", fields(
                    "requestId", request.getRequestId(),
                    "resultCode", "OK",
                    "detail", "repatched=" + repatched + "|" + probe));
        }

        // Self-check verdict overrides success: never report OK when routes were lost.
        if (failedCount == 0 && rebindReport != null && rebindReport.hasRoutesLost()) {
            // Keep the full spring summary: diagnosing register failures needs
            // notHandler/noHandlerBean/detectFailed details, not just the verdict.
            String summary = rebindReport.summary();
            String detail = "selfCheck=routesLost;spring=" + summary;
            logger.log(Level.WARNING, "CLASS_BATCH_RESULT", fields("requestId", request.getRequestId(),
                    "resultCode", "RESTART_REQUIRED", "durationMs", Long.toString(elapsedMillis(started)),
                    "detail", detail));
            String diagnostic = "selfCheck=routesLost;" + (summary.length() > 640
                    ? summary.substring(0, 640) + "..." : summary);
            List<ReloadItemResult> checked = new ArrayList<ReloadItemResult>();
            for (ClassUpdate update : updates) {
                checked.add(item(update.getBinaryName(), OperationStatus.RESTART_REQUIRED,
                        ReloadErrorCode.SPRING_REBIND_INCOMPLETE, diagnostic));
            }
            return new ReloadResponse(request.getRequestId(), OperationStatus.RESTART_REQUIRED,
                    ReloadErrorCode.SPRING_REBIND_INCOMPLETE, detail, checked);
        }

        if (failedCount == 0) {
            String springDetail = rebindReport == null ? "spring=skipped" : "spring=" + rebindReport.summary();
            String probeDetail = null;
            for (String hint : rebindHints) {
                if (hint != null && hint.contains("indexAware=")) {
                    probeDetail = hint;
                    break;
                }
            }
            if (probeDetail == null) {
                probeDetail = AnnotationHotReloadDiagnostics.compactProbeForReport(
                        new LinkedHashSet<Class<?>>(springCandidates.isEmpty() ? redefinedTargets : springCandidates));
            }
            String technical = "defined=" + definedCount + ",redefined=" + redefinedCount + ","
                    + springDetail + ";" + probeDetail;
            String detail = chineseClassSummary(definedCount, redefinedCount, springDetail) + " | " + technical;
            // Fill success items after rebind so diagnostics include spring summary.
            if (items.isEmpty() || items.size() < updates.size()) {
                items = new ArrayList<ReloadItemResult>();
                for (ClassUpdate update : updates) {
                    String kind = "redefined";
                    for (String hint : rebindHints) {
                        if (hint.startsWith("defined=") && hint.endsWith(update.getBinaryName())) {
                            kind = "defined";
                            break;
                        }
                    }
                    items.add(item(update.getBinaryName(), OperationStatus.SUCCESS, null, kind + ";" + springDetail));
                }
            } else {
                List<ReloadItemResult> enriched = new ArrayList<ReloadItemResult>(items.size());
                for (ReloadItemResult existing : items) {
                    enriched.add(item(existing.getItemId(), existing.getStatus(), existing.getErrorCode(),
                            existing.getDiagnostic() + ";" + springDetail));
                }
                items = enriched;
            }
            logger.log(Level.INFO, "CLASS_BATCH_RESULT", fields("requestId", request.getRequestId(),
                    "resultCode", "SUCCESS", "durationMs", Long.toString(elapsedMillis(started)),
                    "detail", detail));
            return new ReloadResponse(request.getRequestId(), OperationStatus.SUCCESS, null, detail, items);
        }

        OperationStatus status = restartRequired ? OperationStatus.RESTART_REQUIRED : OperationStatus.FAILED;
        ReloadErrorCode code = firstError == null ? ReloadErrorCode.CLASS_REDEFINE_FAILED : firstError;
        return finalizeBatch(request, started, items, definedCount, redefinedCount, failedCount, false,
                code, status, rebindHints, null);
    }


    private ItemOutcome defineNewClass(ClassReloadRequest request, ClassUpdate update) {
        logger.log(Level.INFO, "CLASS_DEFINE_BEGIN", fields("requestId", request.getRequestId(),
                "itemId", update.getBinaryName()));
        StructuralClassReloader.Result structural = structuralReloader.defineNew(
                update.getBinaryName(), update.getBytecode());
        if (structural.success && structural.liveClass != null) {
            logger.log(Level.INFO, "CLASS_DEFINE_END", fields("requestId", request.getRequestId(),
                    "itemId", update.getBinaryName(), "resultCode", "SUCCESS",
                    "detail", "mode=" + structural.mode + "," + structural.detail));
            return new ItemOutcome(item(update.getBinaryName(), OperationStatus.SUCCESS, null,
                    "defined:" + structural.mode), structural.liveClass);
        }
        try {
            Class<?> defined = NewClassDefiner.define(update.getBinaryName(), update.getBytecode(), instrumentation);
            HotReloadClassRegistry.put(update.getBinaryName(), defined);
            logger.log(Level.INFO, "CLASS_DEFINE_END", fields("requestId", request.getRequestId(),
                    "itemId", update.getBinaryName(), "resultCode", "SUCCESS",
                    "detail", "loader=" + (defined.getClassLoader() == null ? "bootstrap"
                            : defined.getClassLoader().getClass().getName())));
            return new ItemOutcome(item(update.getBinaryName(), OperationStatus.SUCCESS, null, "defined"), defined);
        } catch (Throwable failure) {
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            // Class may already exist in a loader but not appear in getAllLoadedClasses snapshot timing.
            Class<?> existing = findLoaded(update.getBinaryName());
            if (existing != null && instrumentation.isModifiableClass(existing)) {
                try {
                    instrumentation.redefineClasses(new ClassDefinition(existing, update.getBytecode()));
                    logger.log(Level.INFO, "CLASS_DEFINE_END", fields("requestId", request.getRequestId(),
                            "itemId", update.getBinaryName(), "resultCode", "SUCCESS",
                            "detail", "fallback=redefine_existing"));
                    return new ItemOutcome(item(update.getBinaryName(), OperationStatus.SUCCESS, null,
                            "redefined-existing"), existing);
                } catch (Throwable redefineFailure) {
                    root = redefineFailure;
                }
            }
            logger.log(Level.WARNING, "CLASS_DEFINE_END", fields("requestId", request.getRequestId(),
                    "itemId", update.getBinaryName(), "resultCode", "CLASS_REDEFINE_FAILED",
                    "detail", root.getClass().getSimpleName()));
            return new ItemOutcome(item(update.getBinaryName(), OperationStatus.FAILED,
                    ReloadErrorCode.CLASS_REDEFINE_FAILED,
                    "defineFailed=" + root.getClass().getSimpleName()), null);
        }
    }

    private Class<?> findLoaded(String binaryName) {
        return LoadedClassLookup.find(instrumentation, binaryName);
    }

    private ReloadResponse finalizeBatch(ClassReloadRequest request, long started, List<ReloadItemResult> existing,
                                         int definedCount, int redefinedCount, int failedOrTotal, boolean batchFailed,
                                         ReloadErrorCode code, OperationStatus status, Set<String> hints,
                                         String errorDetail) {
        List<ReloadItemResult> items = new ArrayList<ReloadItemResult>(existing);
        if (batchFailed) {
            // 已有条目（含 define-new/structure 路径的 SUCCESS）一律不再追加，
            // 否则同一类会同时出现 SUCCESS 与 FAILED 两条矛盾结果。
            Set<String> reported = new LinkedHashSet<String>();
            for (ReloadItemResult item : existing) {
                reported.add(item.getItemId());
            }
            for (ClassUpdate update : request.getUpdates()) {
                if (!reported.contains(update.getBinaryName())) {
                    items.add(item(update.getBinaryName(), status, code,
                            errorDetail == null ? code.name() : errorDetail));
                }
            }
        }
        String detail = "defined=" + definedCount + ",redefined=" + redefinedCount
                + ",failed=" + failedOrTotal
                + (errorDetail == null ? "" : ",error=" + errorDetail)
                + (hints == null || hints.isEmpty() ? "" : ",hints=" + join(hints));
        logger.log(Level.WARNING, "CLASS_BATCH_RESULT", fields("requestId", request.getRequestId(),
                "resultCode", code.name(), "durationMs", Long.toString(elapsedMillis(started)),
                "detail", detail));
        return new ReloadResponse(request.getRequestId(), status, code, code.name(), items);
    }

    private ReloadResponse loggedFailure(ClassReloadRequest request, ReloadErrorCode code,
                                         OperationStatus status, long started) {
        return loggedFailure(request, "batch", code, status, started);
    }

    private ReloadResponse loggedFailure(ClassReloadRequest request, String itemId, ReloadErrorCode code,
                                         OperationStatus status, long started) {
        String failureItem = itemId == null ? "batch" : itemId;
        logger.log(Level.WARNING, "REDEFINE_END", fields("requestId", request.getRequestId(),
                "itemId", failureItem, "resultCode", code.name(),
                "durationMs", Long.toString(elapsedMillis(started))));
        ReloadItemResult item = item(failureItem, status, code, code.name());
        return new ReloadResponse(request.getRequestId(), status, code, code.name(),
                Collections.singletonList(item));
    }


    /**
     * After generation hot-reload, JVM often keeps both the original class and the new
     * generation class with the same binary name. Prefer the live generation from registry.
     */
    static Class<?> resolveLoadedTarget(String binaryName, List<Class<?>> matches, Set<String> hints) {
        Class<?> live = HotReloadClassRegistry.get(binaryName);
        if (live != null) {
            if (hints != null) {
                hints.add("targetResolved=registryGen:" + HotReloadClassRegistry.generation(binaryName));
            }
            return live;
        }
        if (matches == null || matches.isEmpty()) {
            return null;
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        for (Class<?> candidate : matches) {
            if (candidate != null && candidate.getClassLoader() instanceof GenerationClassLoader) {
                if (hints != null) {
                    hints.add("targetResolved=generationLoader");
                }
                return candidate;
            }
        }
        for (Class<?> candidate : matches) {
            if (candidate == null) {
                continue;
            }
            ClassLoader loader = candidate.getClassLoader();
            if (loader != null) {
                String tag = String.valueOf(loader);
                if (tag.contains("hr-gen-") || tag.contains("GenerationClassLoader")) {
                    if (hints != null) {
                        hints.add("targetResolved=generationTag");
                    }
                    return candidate;
                }
            }
        }
        return null;
    }
    private Map<String, List<Class<?>>> loadedClasses(List<ClassUpdate> updates) {
        List<String> names = new ArrayList<String>(updates.size());
        for (ClassUpdate update : updates) names.add(update.getBinaryName());
        return LoadedClassLookup.indexRequested(instrumentation, names);
    }

    private static boolean isStructureChange(UnsupportedOperationException failure) {
        return StructuralClassReloader.isLikelyStructureFailure(failure);
    }

    private static int targetClassMajorVersion() {
        String value = System.getProperty("java.class.version", "52");
        int dot = value.indexOf('.');
        String integer = dot < 0 ? value : value.substring(0, dot);
        try {
            return Integer.parseInt(integer);
        } catch (NumberFormatException e) {
            return 52;
        }
    }

    private static ReloadItemResult item(String itemId, OperationStatus status, ReloadErrorCode code, String diagnostic) {
        String message = code == null ? status.name() : code.name();
        return new ReloadItemResult(itemId, status, code, message, diagnostic == null ? "" : diagnostic);
    }

    private static String chineseClassSummary(int definedCount, int redefinedCount, String springDetail) {
        StringBuilder cn = new StringBuilder();
        cn.append("类热更新成功");
        cn.append("：新定义=").append(definedCount);
        cn.append("，重定义=").append(redefinedCount);
        if (springDetail != null) {
            if (springDetail.contains("mappingRefresh=skippedNonMappingChange")
                    || springDetail.contains("mappingRefresh=skipNonMapping")) {
                cn.append("，请求映射未变更已跳过刷新");
            } else if (springDetail.contains("mappingRefreshed=")) {
                cn.append("，映射刷新=").append(extractMetric(springDetail, "mappingRefreshed="));
            }
            if (springDetail.contains("mappingFallback=")) {
                cn.append("，映射部分恢复失败已全量重建");
            }
            if (springDetail.contains("beansRecreated=")) {
                cn.append("，Bean重建=").append(extractMetric(springDetail, "beansRecreated="));
            }
            if (springDetail.contains("indexAware=true")) {
                cn.append("，索引感知拦截链=是");
            } else if (springDetail.contains("indexAware=false")) {
                cn.append("，索引感知拦截链=否");
            }
            if (springDetail.contains("genericPatched=")) {
                cn.append("，通用注解重绑已执行");
            }
            if (springDetail.contains("annotationAspectJ@") || springDetail.contains("annotationMatcher@")) {
                cn.append("，注解切面/匹配器已更新");
            }
        }
        return cn.toString();
    }

    private static String extractMetric(String text, String token) {
        int idx = text.indexOf(token);
        if (idx < 0) return "?";
        int start = idx + token.length();
        int end = start;
        while (end < text.length()) {
            char c = text.charAt(end);
            if (c >= '0' && c <= '9') end++;
            else break;
        }
        return end == start ? "?" : text.substring(start, end);
    }

    private static long payloadBytes(List<ClassUpdate> updates) {
        long total = 0L;
        for (ClassUpdate update : updates) total += update.getBytecodeLength();
        return total;
    }

    private static String summarize(List<ClassUpdate> updates) {
        StringBuilder result = new StringBuilder();
        for (ClassUpdate update : updates) {
            if (result.length() > 0) result.append(',');
            if (result.length() + update.getBinaryName().length() > 480) {
                result.append("...");
                break;
            }
            result.append(update.getBinaryName());
        }
        return result.toString();
    }

    private static String join(Set<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append('|');
            builder.append(value);
            if (builder.length() > 420) {
                builder.append("|...");
                break;
            }
        }
        return builder.toString();
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static Map<String, String> fields(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) map.put(keyValues[i], keyValues[i + 1]);
        return map;
    }

    private static final class ItemOutcome {
        private final ReloadItemResult item;
        private final Class<?> loadedClass;

        private ItemOutcome(ReloadItemResult item, Class<?> loadedClass) {
            this.item = item;
            this.loadedClass = loadedClass;
        }
    }
}



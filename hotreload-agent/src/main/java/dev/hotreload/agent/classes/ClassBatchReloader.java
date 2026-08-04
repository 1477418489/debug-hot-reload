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
        List<RedefineCandidate> pendingRedefines = new ArrayList<RedefineCandidate>();
        // This list contains only updates that were actually applied. Pending body updates are
        // added after the atomic redefine call succeeds.
        List<Class<?>> redefinedTargets = new ArrayList<Class<?>>();
        Map<Class<?>, byte[]> annotationUpdates = new LinkedHashMap<Class<?>, byte[]>();
        List<Class<?>> springCandidates = new ArrayList<Class<?>>();
        Set<String> rebindHints = new LinkedHashSet<String>();
        // Structure/annotation deltas need bean-level refresh; body-only classes must keep state.
        Set<String> deepChangedNames = new LinkedHashSet<String>();
        Set<String> generationRequiredNames = new LinkedHashSet<String>();
        // RequestMapping refresh is expensive and unsafe when partial; only when mapping metadata changes.
        boolean needsMappingRefresh = false;
        int definedCount = 0;
        int redefinedCount = 0;
        int failedCount = 0;
        boolean restartRequired = false;
        boolean requiredPostProcessingFailed = false;
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
                ReloadItemResult item = item(update.getBinaryName(), OperationStatus.RESTART_REQUIRED,
                        ReloadErrorCode.CLASS_AMBIGUOUS, "loadedCount=" + count);
                items.add(item);
                failedCount++;
                restartRequired = true;
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
                    deepChangedNames.add(update.getBinaryName());
                    if ("generation".equals(structural.mode)) {
                        generationRequiredNames.add(update.getBinaryName());
                    }
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
            pendingRedefines.add(new RedefineCandidate(update, target, analysis, forceAnnotationPatch));
        }

        if (!redefineBatch.isEmpty()) {
            List<Class<?>> batchTargets = candidateTargets(pendingRedefines);
            Throwable redefineFailure = null;
            ReloadErrorCode redefineFailureCode = null;
            OperationStatus redefineFailureStatus = OperationStatus.FAILED;
            boolean redefineApplied = false;
            AnnotationHotReloadDiagnostics.pipelineStart(logger, request.getRequestId(), batchTargets);
            logger.log(Level.INFO, "REDEFINE_BEGIN", fields("requestId", request.getRequestId(),
                    "classCount", Integer.toString(redefineBatch.size())));
            try {
                instrumentation.redefineClasses(redefineBatch.toArray(new ClassDefinition[redefineBatch.size()]));
                redefineApplied = true;
                redefinedCount += redefineBatch.size();
                for (RedefineCandidate candidate : pendingRedefines) {
                    Class<?> redefined = candidate.target;
                    redefinedTargets.add(redefined);
                    if (candidate.forceAnnotationPatch) {
                        annotationUpdates.put(redefined, candidate.update.getBytecode());
                    }
                    springCandidates.add(redefined);
                    if (candidate.analysis.isAnnotationChanged()) {
                        deepChangedNames.add(candidate.update.getBinaryName());
                    }
                    if (candidate.analysis.needsRequestMappingRefresh()) {
                        needsMappingRefresh = true;
                        rebindHints.add("mappingRefreshNeeded=" + candidate.update.getBinaryName());
                    }
                    items.add(item(candidate.update.getBinaryName(), OperationStatus.SUCCESS, null,
                            "redefined"));
                }
                for (RedefineCandidate candidate : pendingRedefines) {
                    HotReloadClassRegistry.put(candidate.update.getBinaryName(), candidate.target);
                }
                int reflectionInvalidated = ReflectionDataInvalidator.invalidateAll(batchTargets);
                int annotationPatchedMethods = 0;
                int annotationPatchedClasses = 0;
                // E2 (enhanced runtime): reflection now serves the new bytecode natively.
                // Index publication + forced patches would replace real annotations with
                // synthesized proxies that lose Spring @AliasFor merging — skip entirely.
                if (!enhancedRedefineCapable) {
                    for (Map.Entry<Class<?>, byte[]> entry : annotationUpdates.entrySet()) {
                        try {
                            RuntimeAnnotationIndex.update(entry.getKey(), entry.getValue());
                            rebindHints.add(RuntimeAnnotationIndex.describe(entry.getKey()));
                            rebindHints.add("annotationIndex=published:" + entry.getKey().getSimpleName());
                            AnnotationHotReloadDiagnostics.bytecodePublished(logger, request.getRequestId(),
                                    entry.getKey(), entry.getValue());
                            RuntimeAnnotationPatcher.PatchReport patch =
                                    RuntimeAnnotationPatcher.patch(entry.getKey(), entry.getValue());
                            annotationPatchedMethods += patch.getMethodsPatched();
                            annotationPatchedClasses += patch.getClassPatched();
                            if (!patch.isComplete()) {
                                requiredPostProcessingFailed = true;
                                rebindHints.add("annotationPatchIncomplete="
                                        + entry.getKey().getName() + ":" + patch.summary());
                            }
                            rebindHints.add(patch.summary());
                            logger.log(patch.isComplete() ? Level.INFO : Level.WARNING,
                                    "ANNOTATION_PATCH", fields(
                                    "requestId", request.getRequestId(),
                                    "itemId", entry.getKey().getName(),
                                    "resultCode", patch.isComplete() ? "SUCCESS" : "FAILED",
                                    "detail", patch.summary()));
                        } catch (Throwable patchFailure) {
                            requiredPostProcessingFailed = true;
                            rebindHints.add("annotationPatchFailed=" + entry.getKey().getName()
                                    + ":" + failureDetail(patchFailure));
                            logger.log(Level.WARNING, "ANNOTATION_PATCH", fields(
                                    "requestId", request.getRequestId(),
                                    "itemId", entry.getKey().getName(),
                                    "resultCode", "FAILED",
                                    "detail", failureDetail(patchFailure)));
                        }
                    }
                } else {
                    rebindHints.add("annotationPatch=skipped:enhancedRuntime");
                }
                if (AnnotationHotReloadDiagnostics.verboseEnabled()) {
                    AnnotationHotReloadDiagnostics.reflectAndSpringProbe(logger, request.getRequestId(), batchTargets);
                }
                logger.log(Level.INFO, "REDEFINE_END", fields("requestId", request.getRequestId(),
                        "itemId", "batch", "resultCode", "SUCCESS",
                        "durationMs", Long.toString(elapsedMillis(started)),
                        "classCount", Integer.toString(redefinedCount),
                        "detail", "reflectionInvalidated=" + reflectionInvalidated
                                + ",annotationPatchedMethods=" + annotationPatchedMethods
                                + ",annotationPatchedClasses=" + annotationPatchedClasses));
            } catch (UnmodifiableClassException e) {
                if (redefineApplied) {
                    requiredPostProcessingFailed = true;
                    rebindHints.add("postRedefineFailed=" + failureDetail(e));
                } else {
                    redefineFailure = e;
                    redefineFailureCode = ReloadErrorCode.CLASS_UNMODIFIABLE;
                }
            } catch (UnsupportedClassVersionError e) {
                if (redefineApplied) {
                    requiredPostProcessingFailed = true;
                    rebindHints.add("postRedefineFailed=" + failureDetail(e));
                } else {
                    redefineFailure = e;
                    redefineFailureCode = ReloadErrorCode.CLASS_VERSION_UNSUPPORTED;
                }
            } catch (UnsupportedOperationException e) {
                if (redefineApplied) {
                    requiredPostProcessingFailed = true;
                    rebindHints.add("postRedefineFailed=" + failureDetail(e));
                } else {
                    // The JVM reports only one exception for an atomic batch. Retry each class
                    // separately so a single hidden schema delta does not force unrelated body
                    // changes through Generation as well.
                    int recovered = 0;
                    int unrecovered = 0;
                    for (RedefineCandidate candidate : pendingRedefines) {
                        Class<?> target = candidate.target;
                        byte[] bytecode = candidate.update.getBytecode();
                        StructuralClassReloader.Result structural = null;
                        Throwable itemFailure = e;
                        ReloadErrorCode itemFailureCode = ReloadErrorCode.CLASS_REDEFINE_FAILED;
                        OperationStatus itemFailureStatus = OperationStatus.FAILED;
                        try {
                            instrumentation.redefineClasses(new ClassDefinition(target, bytecode));
                            ReflectionDataInvalidator.invalidateAll(
                                    Collections.<Class<?>>singletonList(target));
                            if (!enhancedRedefineCapable) {
                                try {
                                    RuntimeAnnotationIndex.update(target, bytecode);
                                    RuntimeAnnotationPatcher.PatchReport patch =
                                            RuntimeAnnotationPatcher.patch(target, bytecode);
                                    rebindHints.add(patch.summary());
                                    if (!patch.isComplete()) {
                                        requiredPostProcessingFailed = true;
                                        rebindHints.add("annotationPatchIncomplete="
                                                + target.getName() + ":" + patch.summary());
                                    }
                                } catch (Throwable patchFailure) {
                                    requiredPostProcessingFailed = true;
                                    rebindHints.add("annotationPatchFailed=" + target.getName()
                                            + ":" + failureDetail(patchFailure));
                                }
                            }
                            HotReloadClassRegistry.put(candidate.update.getBinaryName(), target);
                            structural = new StructuralClassReloader.Result(target, "redefined",
                                    "isolated_redefine_after_batch_rejection", true);
                        } catch (UnsupportedOperationException individualFailure) {
                            itemFailure = individualFailure;
                            structural = structuralReloader.reloadExisting(target, bytecode, true);
                            if (isStructureChange(individualFailure) || isStructureChange(e)) {
                                itemFailureCode = ReloadErrorCode.CLASS_STRUCTURE_CHANGED;
                                itemFailureStatus = OperationStatus.RESTART_REQUIRED;
                            }
                        } catch (UnmodifiableClassException individualFailure) {
                            itemFailure = individualFailure;
                            itemFailureCode = ReloadErrorCode.CLASS_UNMODIFIABLE;
                        } catch (UnsupportedClassVersionError individualFailure) {
                            itemFailure = individualFailure;
                            itemFailureCode = ReloadErrorCode.CLASS_VERSION_UNSUPPORTED;
                        } catch (Throwable individualFailure) {
                            itemFailure = individualFailure;
                        }
                        logger.log(structural != null && structural.success ? Level.INFO : Level.WARNING,
                                "CLASS_STRUCTURE_FALLBACK", fields(
                                "requestId", request.getRequestId(),
                                "itemId", candidate.update.getBinaryName(),
                                "resultCode", structural != null && structural.success ? "SUCCESS" : "FAILED",
                                "detail", "mode=" + (structural == null ? "failed" : structural.mode)
                                        + "," + (structural == null ? failureDetail(itemFailure) : structural.detail)
                                        + ",cause=" + failureDetail(itemFailure)));
                        if (structural != null && structural.success && structural.liveClass != null) {
                            recovered++;
                            redefinedCount++;
                            redefinedTargets.add(structural.liveClass);
                            springCandidates.add(structural.liveClass);
                            if (structural.liveClass != target) {
                                Class<?> base = structural.liveClass.getSuperclass();
                                if (base != null && base != Object.class) springCandidates.add(base);
                            }
                            if ("generation".equals(structural.mode)) {
                                deepChangedNames.add(candidate.update.getBinaryName());
                                generationRequiredNames.add(candidate.update.getBinaryName());
                                needsMappingRefresh = true;
                            } else {
                                if (candidate.analysis.isAnnotationChanged()) {
                                    deepChangedNames.add(candidate.update.getBinaryName());
                                }
                                if (candidate.analysis.needsRequestMappingRefresh()) {
                                    needsMappingRefresh = true;
                                }
                            }
                            items.add(item(candidate.update.getBinaryName(), OperationStatus.SUCCESS, null,
                                    "structure-fallback:" + structural.mode + ":" + structural.detail));
                            rebindHints.add("structureFallback=" + structural.mode + ":"
                                    + candidate.update.getBinaryName());
                        } else {
                            unrecovered++;
                            failedCount++;
                            if (itemFailureStatus == OperationStatus.RESTART_REQUIRED) restartRequired = true;
                            if (firstError == null) firstError = itemFailureCode;
                            String structuralDetail = structural == null
                                    ? failureDetail(itemFailure) : structural.detail;
                            items.add(item(candidate.update.getBinaryName(), itemFailureStatus, itemFailureCode,
                                    "structure_fallback_failed:" + structuralDetail
                                            + ",cause=" + failureDetail(itemFailure)));
                        }
                    }
                    rebindHints.add("structureFallbackRecovered=" + recovered + ",remaining=" + unrecovered);
                    // Earlier structural/new successes still require Spring rebind below.
                }
            } catch (LinkageError e) {
                if (redefineApplied) {
                    requiredPostProcessingFailed = true;
                    rebindHints.add("postRedefineFailed=" + failureDetail(e));
                } else {
                    redefineFailure = e;
                    redefineFailureCode = ReloadErrorCode.CLASS_REDEFINE_FAILED;
                }
            } catch (Exception e) {
                if (redefineApplied) {
                    requiredPostProcessingFailed = true;
                    rebindHints.add("postRedefineFailed=" + failureDetail(e));
                } else {
                    redefineFailure = e;
                    redefineFailureCode = ReloadErrorCode.CLASS_REDEFINE_FAILED;
                }
            } catch (InternalError e) {
                if (redefineApplied) {
                    requiredPostProcessingFailed = true;
                    rebindHints.add("postRedefineFailed=" + failureDetail(e));
                } else {
                    redefineFailure = e;
                    redefineFailureCode = ReloadErrorCode.CLASS_REDEFINE_FAILED;
                }
            } catch (Error e) {
                if (redefineApplied) {
                    requiredPostProcessingFailed = true;
                    rebindHints.add("postRedefineFailed=" + failureDetail(e));
                } else {
                    redefineFailure = e;
                    redefineFailureCode = ReloadErrorCode.CLASS_REDEFINE_FAILED;
                }
            }
            if (redefineFailure != null) {
                int rejected = appendRedefineFailures(items, pendingRedefines,
                        redefineFailureCode, redefineFailureStatus, failureDetail(redefineFailure));
                failedCount += rejected;
                if (firstError == null) firstError = redefineFailureCode;
                rebindHints.add("redefineRejected=" + rejected + ":" + failureDetail(redefineFailure));
            }
        }

        SpringFrameworkRebinder.RebindReport rebindReport = null;
        // Always rebind after redefine/define: even pure method-body changes can leave
        // stale RequestMappingHandlerMethod/AOP proxies when Spring caches bean type metadata.
        if (!springCandidates.isEmpty() || redefinedCount > 0 || definedCount > 0) {
            Set<Class<?>> unique = new LinkedHashSet<Class<?>>(springCandidates);
            for (Class<?> target : redefinedTargets) unique.add(target);
            logger.log(Level.INFO, "ANNOTATION_REBIND_BEGIN", fields(
                    "requestId", request.getRequestId(),
                    "classCount", Integer.toString(unique.size()),
                    "detail", "candidates=" + unique.size()));
            rebindHints.add("mappingRefresh=" + (needsMappingRefresh ? "required" : "skipNonMapping"));
            try {
                rebindReport = springRebinder.rebind(unique, needsMappingRefresh, deepChangedNames);
                // E3 only: rebind clears Class reflection caches; re-apply annotation truth from index.
                // Under E2 real reflection IS the truth — re-applying would corrupt it.
                int repatched = 0;
                if (!enhancedRedefineCapable) {
                    try {
                        int repatchFailures = 0;
                        for (Class<?> type : unique) {
                            if (type == null || RuntimeAnnotationIndex.get(type) == null) continue;
                            if (RuntimeAnnotationPatcher.reapplyFromIndex(type)) repatched++;
                            else repatchFailures++;
                        }
                        if (repatchFailures > 0) {
                            requiredPostProcessingFailed = true;
                            rebindHints.add("annotationRepatchIncomplete=" + repatchFailures);
                        }
                    } catch (Throwable repatchFailure) {
                        requiredPostProcessingFailed = true;
                        rebindHints.add("annotationRepatchFailed=" + failureDetail(repatchFailure));
                    }
                }
                rebindHints.add(rebindReport.summary());
                String probe = AnnotationHotReloadDiagnostics.compactProbeForReport(unique);
                rebindHints.add(probe);
                if (AnnotationHotReloadDiagnostics.verboseEnabled()) { AnnotationHotReloadDiagnostics.reflectAndSpringProbe(logger, request.getRequestId(), unique); }
                AnnotationHotReloadDiagnostics.pipelineEnd(logger, request.getRequestId(), rebindReport.summary() + "|repatched=" + repatched + "|" + probe, unique);
                logger.log(Level.INFO, "ANNOTATION_POST_REBIND_PROBE", fields(
                        "requestId", request.getRequestId(),
                        "resultCode", "OK",
                        "detail", "repatched=" + repatched + "|" + probe));
            } catch (Throwable rebindFailure) {
                requiredPostProcessingFailed = true;
                rebindHints.add("springRebindFailed=" + failureDetail(rebindFailure));
                logger.log(Level.WARNING, "ANNOTATION_REBIND_END", fields(
                        "requestId", request.getRequestId(), "resultCode", "FAILED",
                        "detail", failureDetail(rebindFailure)));
            }
        }

        // Required Spring changes are part of the Class reload contract. A partial rebind must
        // never leave successfully-applied bytecode reported as fully successful.
        boolean generationBindingIncomplete = !generationRequiredNames.isEmpty()
                && (rebindReport == null
                || rebindReport.hasUnboundGenerations(generationRequiredNames));
        if (generationBindingIncomplete) {
            rebindHints.add("generationBindingIncomplete=" + join(generationRequiredNames));
        }
        if (requiredPostProcessingFailed || generationBindingIncomplete
                || rebindReport != null && rebindReport.hasIncompleteChanges()) {
            String summary = rebindReport == null ? "unavailable" : rebindReport.summary();
            String detail = "springRebind=incomplete;spring=" + summary;
            logger.log(Level.WARNING, "CLASS_BATCH_RESULT", fields("requestId", request.getRequestId(),
                    "resultCode", "RESTART_REQUIRED", "durationMs", Long.toString(elapsedMillis(started)),
                    "detail", detail));
            String diagnostic = "springRebind=incomplete;" + (summary.length() > 640
                    ? summary.substring(0, 640) + "..." : summary);
            List<ReloadItemResult> checked = new ArrayList<ReloadItemResult>();
            for (ReloadItemResult existing : orderedItems(updates, items)) {
                if (existing.getStatus() == OperationStatus.SUCCESS) {
                    checked.add(item(existing.getItemId(), OperationStatus.RESTART_REQUIRED,
                            ReloadErrorCode.SPRING_REBIND_INCOMPLETE, diagnostic));
                } else {
                    checked.add(existing);
                }
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
            List<ReloadItemResult> enriched = new ArrayList<ReloadItemResult>(items.size());
            for (ReloadItemResult existing : orderedItems(updates, items)) {
                enriched.add(item(existing.getItemId(), existing.getStatus(), existing.getErrorCode(),
                        existing.getDiagnostic() + ";" + springDetail));
            }
            items = enriched;
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
        // The class may already have been defined before required annotation initialization
        // failed. Retrying it as a plain redefine would hide that partial state as success.
        if (structural.liveClass != null) {
            logger.log(Level.WARNING, "CLASS_DEFINE_END", fields("requestId", request.getRequestId(),
                    "itemId", update.getBinaryName(), "resultCode", "RESTART_REQUIRED",
                    "detail", structural.detail));
            return new ItemOutcome(item(update.getBinaryName(), OperationStatus.RESTART_REQUIRED,
                    ReloadErrorCode.CLASS_REDEFINE_FAILED, structural.detail), structural.liveClass);
        }
        // Class may already exist in a loader but not appear in the instrumentation snapshot yet.
        Class<?> existing = findLoaded(update.getBinaryName());
        if (existing != null && instrumentation.isModifiableClass(existing)) {
            try {
                instrumentation.redefineClasses(new ClassDefinition(existing, update.getBytecode()));
                HotReloadClassRegistry.put(update.getBinaryName(), existing);
                logger.log(Level.INFO, "CLASS_DEFINE_END", fields("requestId", request.getRequestId(),
                        "itemId", update.getBinaryName(), "resultCode", "SUCCESS",
                        "detail", "fallback=redefine_existing"));
                return new ItemOutcome(item(update.getBinaryName(), OperationStatus.SUCCESS, null,
                        "redefined-existing"), existing);
            } catch (Throwable redefineFailure) {
                structural = new StructuralClassReloader.Result(null, "failed",
                        structural.detail + "|existing_redefine_failed=" + failureDetail(redefineFailure), false);
            }
        }
        logger.log(Level.WARNING, "CLASS_DEFINE_END", fields("requestId", request.getRequestId(),
                "itemId", update.getBinaryName(), "resultCode", "RESTART_REQUIRED",
                "detail", structural.detail));
        return new ItemOutcome(item(update.getBinaryName(), OperationStatus.RESTART_REQUIRED,
                ReloadErrorCode.CLASS_REDEFINE_FAILED, structural.detail), null);
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
        items = orderedItems(request.getUpdates(), items);
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
        Class<?> generationCandidate = null;
        for (Class<?> candidate : matches) {
            if (candidate == null) {
                continue;
            }
            ClassLoader loader = candidate.getClassLoader();
            boolean generationLoader = loader instanceof GenerationClassLoader;
            if (!generationLoader && loader != null) {
                String tag = String.valueOf(loader);
                generationLoader = tag.contains("hr-gen-") || tag.contains("GenerationClassLoader");
            }
            if (generationLoader) {
                if (generationCandidate != null && generationCandidate != candidate) return null;
                generationCandidate = candidate;
            }
        }
        if (generationCandidate != null && hints != null) {
            hints.add("targetResolved=generationLoader");
        }
        if (generationCandidate != null) return generationCandidate;
        return null;
    }
    private Map<String, List<Class<?>>> loadedClasses(List<ClassUpdate> updates) {
        List<String> names = new ArrayList<String>(updates.size());
        for (ClassUpdate update : updates) names.add(update.getBinaryName());
        return LoadedClassLookup.indexRequested(instrumentation, names);
    }

    private static List<Class<?>> candidateTargets(List<RedefineCandidate> candidates) {
        List<Class<?>> targets = new ArrayList<Class<?>>(candidates.size());
        for (RedefineCandidate candidate : candidates) targets.add(candidate.target);
        return targets;
    }

    private static int appendRedefineFailures(List<ReloadItemResult> items,
                                              List<RedefineCandidate> candidates,
                                              ReloadErrorCode code, OperationStatus status,
                                              String diagnostic) {
        for (RedefineCandidate candidate : candidates) {
            items.add(item(candidate.update.getBinaryName(), status, code, diagnostic));
        }
        return candidates.size();
    }

    private static List<ReloadItemResult> orderedItems(List<ClassUpdate> updates,
                                                       List<ReloadItemResult> items) {
        Map<String, ReloadItemResult> byId = new LinkedHashMap<String, ReloadItemResult>();
        for (ReloadItemResult item : items) byId.put(item.getItemId(), item);
        List<ReloadItemResult> ordered = new ArrayList<ReloadItemResult>(updates.size());
        for (ClassUpdate update : updates) {
            ReloadItemResult result = byId.get(update.getBinaryName());
            if (result != null) ordered.add(result);
        }
        return ordered;
    }

    private static String failureDetail(Throwable failure) {
        if (failure == null) return "unknown";
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        if (message == null || message.trim().isEmpty()) return root.getClass().getSimpleName();
        message = message.replace('\r', ' ').replace('\n', ' ');
        if (message.length() > 180) message = message.substring(0, 180);
        return root.getClass().getSimpleName() + ":" + message;
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

    private static final class RedefineCandidate {
        private final ClassUpdate update;
        private final Class<?> target;
        private final ClassChangeAnalyzer.Analysis analysis;
        private final boolean forceAnnotationPatch;

        private RedefineCandidate(ClassUpdate update, Class<?> target,
                                  ClassChangeAnalyzer.Analysis analysis,
                                  boolean forceAnnotationPatch) {
            this.update = update;
            this.target = target;
            this.analysis = analysis;
            this.forceAnnotationPatch = forceAnnotationPatch;
        }
    }
}



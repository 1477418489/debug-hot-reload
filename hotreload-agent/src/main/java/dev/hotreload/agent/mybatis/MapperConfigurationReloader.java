package dev.hotreload.agent.mybatis;

import dev.hotreload.agent.logging.AgentSessionLogger;
import dev.hotreload.bootstrap.ConfigurationHandle;
import dev.hotreload.bootstrap.HotReloadBridge;
import dev.hotreload.bootstrap.ResourceMetadata;
import dev.hotreload.bootstrap.WriteLockToken;
import dev.hotreload.protocol.message.MapperReloadRequest;
import dev.hotreload.protocol.message.OperationStatus;
import dev.hotreload.protocol.message.ReloadErrorCode;
import dev.hotreload.protocol.message.ReloadItemResult;
import dev.hotreload.protocol.message.ReloadResponse;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class MapperConfigurationReloader {
    private static final long WRITE_LOCK_TIMEOUT_MILLIS = 5_000L;
    private static final Object TRANSACTION_MONITOR = new Object();

    private final AgentSessionLogger logger;
    private final MapperXmlPreflight preflight = new MapperXmlPreflight();

    public MapperConfigurationReloader(AgentSessionLogger logger) {
        if (logger == null) throw new NullPointerException("logger");
        this.logger = logger;
    }

    public ReloadResponse reload(MapperReloadRequest request) {
        long started = System.nanoTime();
        logger.log(Level.INFO, "XML_RECEIVED", fields("requestId", request.getRequestId(),
                "resourceId", request.getUpdate().getResourceId(),
                "resourceHash", shortHash(request.getUpdate().getSha256()),
                "payloadBytes", Integer.toString(request.getUpdate().getContentLength())));
        try {
            ReloadResponse response = reloadInternal(request, started);
            logResult(request, response, started, null);
            return response;
        } catch (RuntimeException failure) {
            logResult(request, null, started, failure);
            throw failure;
        } catch (LinkageError failure) {
            logResult(request, null, started, failure);
            throw failure;
        } catch (InternalError failure) {
            logResult(request, null, started, failure);
            throw failure;
        }
    }

    private ReloadResponse reloadInternal(MapperReloadRequest request, long started) {
        final MapperDocument document;
        try {
            document = preflight.preflight(request.getUpdate());
            logger.log(Level.INFO, "XML_PREFLIGHT_RESULT", fields("requestId", request.getRequestId(),
                    "resultCode", "SUCCESS", "namespace", document.getNamespace()));
        } catch (IllegalArgumentException e) {
            logger.log(Level.WARNING, "XML_PREFLIGHT_RESULT", fields("requestId", request.getRequestId(),
                    "resultCode", ReloadErrorCode.XML_INVALID.name()));
            return failure(request, ReloadErrorCode.XML_INVALID);
        }

        List<ConfigurationHandle> handles = HotReloadBridge.snapshotConfigurations();
        int configurationCount = handles.size();
        logger.log(Level.INFO, "XML_CONFIG_SNAPSHOT", fields("requestId", request.getRequestId(),
                "resourceId", document.getResourceId(),
                "configurationCount", Integer.toString(configurationCount),
                "namespace", document.getNamespace()));
        if (handles.isEmpty()) {
            return failure(request, ReloadErrorCode.MYBATIS_NOT_FOUND, document.getResourceId(),
                    "configurationCount=0");
        }

        OwnerResolution resolution = resolveOwners(handles, document.getResourceId(), document.getNamespace());
        logger.log(resolution.errorCode == null ? Level.INFO : Level.WARNING, "XML_RESOURCE_LOOKUP", fields(
                "requestId", request.getRequestId(),
                "resourceId", document.getResourceId(),
                "namespace", document.getNamespace(),
                "configurationCount", Integer.toString(configurationCount),
                "ownerCount", Integer.toString(resolution.ownerCount),
                "matchedOwnerCount", Integer.toString(resolution.targets.size()),
                "resultCode", resolution.errorCode == null ? "SUCCESS" : resolution.errorCode.name()));
        if (resolution.errorCode != null) {
            return failure(request, resolution.errorCode, document.getResourceId(),
                    "configurationCount=" + configurationCount
                            + " ownerCount=" + resolution.ownerCount
                            + " matchedOwnerCount=" + resolution.targets.size());
        }

        synchronized (TRANSACTION_MONITOR) {
            return reloadAtomically(request, document, resolution.targets, started);
        }
    }

    private ReloadResponse reloadAtomically(MapperReloadRequest request, MapperDocument document,
                                            List<TargetLookup> targets, long started) {
        List<TransactionTarget> transactions = new ArrayList<TransactionTarget>(targets.size());
        for (int i = 0; i < targets.size(); i++) {
            TargetLookup target = targets.get(i);
            Object configuration = target.handle.getConfiguration();
            transactions.add(new TransactionTarget(target, configuration,
                    itemId(document.getResourceId(), target, i)));
        }
        for (TransactionTarget transaction : transactions) {
            if (transaction.configuration == null) {
                return abortWithoutMutation(request, transactions, transaction,
                        OperationStatus.FAILED, ReloadErrorCode.MYBATIS_NOT_FOUND,
                        "configuration_missing");
            }
            if (transaction.target.handle.isReloadUnsafe()) {
                return abortWithoutMutation(request, transactions, transaction,
                        OperationStatus.RESTART_REQUIRED, ReloadErrorCode.ROLLBACK_FAILED,
                        "reload_unsafe");
            }
            if (!document.getNamespace().equals(transaction.target.metadata.getNamespace())) {
                return abortWithoutMutation(request, transactions, transaction,
                        OperationStatus.FAILED, ReloadErrorCode.XML_INVALID,
                        "namespace_mismatch");
            }
            if (hasCacheTopology(transaction.target.metadata)) {
                return abortWithoutMutation(request, transactions, transaction,
                        OperationStatus.RESTART_REQUIRED, ReloadErrorCode.XML_RELOAD_FAILED,
                        "mapper_cache_topology_requires_restart");
            }
        }

        List<TransactionTarget> lockOrder = new ArrayList<TransactionTarget>(transactions);
        Collections.sort(lockOrder, new Comparator<TransactionTarget>() {
            @Override public int compare(TransactionTarget left, TransactionTarget right) {
                int identity = Integer.compare(System.identityHashCode(left.configuration),
                        System.identityHashCode(right.configuration));
                return identity != 0 ? identity : left.itemId.compareTo(right.itemId);
            }
        });

        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(WRITE_LOCK_TIMEOUT_MILLIS);
        try {
            for (TransactionTarget transaction : lockOrder) {
                long lockStarted = System.nanoTime();
                long remainingMillis = remainingLockMillis(deadline);
                transaction.writeToken = remainingMillis < 0L ? null
                        : HotReloadBridge.enterWrite(transaction.configuration, remainingMillis);
                logger.log(Level.INFO, "XML_LOCK_WAIT", fields("requestId", request.getRequestId(),
                        "itemId", transaction.itemId,
                        "durationMs", Long.toString(elapsedMillis(lockStarted)),
                        "ownerCount", Integer.toString(transactions.size())));
                if (transaction.writeToken == null) {
                    return abortWithoutMutation(request, transactions, transaction,
                            OperationStatus.FAILED, ReloadErrorCode.RELOAD_BUSY,
                            "write_lock_timeout");
                }
            }

            for (TransactionTarget transaction : transactions) {
                if (transaction.target.handle.isReloadUnsafe()) {
                    return abortWithoutMutation(request, transactions, transaction,
                            OperationStatus.RESTART_REQUIRED, ReloadErrorCode.ROLLBACK_FAILED,
                            "reload_unsafe");
                }
                ResourceMetadata expected = transaction.target.metadata;
                ResourceMetadata current = transaction.target.handle.getResourceMetadata(
                        expected.getRuntimeResource());
                if (current == null || current.getVersion() != expected.getVersion()
                        || !document.getNamespace().equals(current.getNamespace())) {
                    return abortWithoutMutation(request, transactions, transaction,
                            OperationStatus.FAILED, ReloadErrorCode.CONFIGURATION_DRIFT,
                            "version_drift");
                }
                transaction.metadata = current;
                transaction.digestKnown = true;
                transaction.changed = !Arrays.equals(document.getSha256(), current.getSha256());
            }

            TransactionTarget preparing = null;
            try {
                for (TransactionTarget transaction : lockOrder) {
                    if (!transaction.changed) continue;
                    preparing = transaction;
                    transaction.snapshot = ConfigurationSnapshot.capture(transaction.configuration);
                }
                for (TransactionTarget transaction : lockOrder) {
                    if (!transaction.changed) continue;
                    preparing = transaction;
                    transaction.snapshot.verifyOwned(transaction.metadata);
                }
            } catch (ConfigurationSnapshot.OwnershipDriftException drift) {
                return abortWithoutMutation(request, transactions, preparing,
                        OperationStatus.FAILED, ReloadErrorCode.CONFIGURATION_DRIFT,
                        "ownership_drift");
            } catch (Exception snapshotFailure) {
                return abortWithoutMutation(request, transactions, preparing,
                        OperationStatus.FAILED, ReloadErrorCode.CONFIGURATION_DRIFT,
                        "snapshot_failed=" + snapshotFailure.getClass().getSimpleName());
            } catch (LinkageError snapshotFailure) {
                return abortWithoutMutation(request, transactions, preparing,
                        OperationStatus.FAILED, ReloadErrorCode.CONFIGURATION_DRIFT,
                        "snapshot_failed=" + snapshotFailure.getClass().getSimpleName());
            }

            TransactionTarget active = null;
            try {
                for (TransactionTarget transaction : lockOrder) {
                    if (!transaction.changed) continue;
                    active = transaction;
                    transaction.mutationStarted = true;
                    transaction.snapshot.removeOwned(transaction.metadata);
                    parse(transaction.configuration, transaction.metadata, document.getContent());
                    ResourceMetadata parsed = transaction.target.handle.getResourceMetadata(
                            transaction.metadata.getRuntimeResource());
                    if (parsed == null || parsed.getVersion() <= transaction.metadata.getVersion()
                            || !document.getNamespace().equals(parsed.getNamespace())
                            || !parsed.hasCompleteOwnedIdentities()) {
                        throw new TransactionFailure(ReloadErrorCode.CONFIGURATION_DRIFT,
                                "post_parse_validation_failed");
                    }
                    if (!HotReloadBridge.updateResourceSha256(transaction.configuration,
                            transaction.metadata.getRuntimeResource(), document.getSha256())) {
                        throw new TransactionFailure(ReloadErrorCode.CONFIGURATION_DRIFT,
                                "digest_publication_failed");
                    }
                    transaction.updatedMetadata = transaction.target.handle.getResourceMetadata(
                            transaction.metadata.getRuntimeResource());
                    if (transaction.updatedMetadata == null
                            || transaction.updatedMetadata.getVersion() <= parsed.getVersion()
                            || !Arrays.equals(document.getSha256(),
                            transaction.updatedMetadata.getSha256())) {
                        throw new TransactionFailure(ReloadErrorCode.CONFIGURATION_DRIFT,
                                "digest_validation_failed");
                    }
                }
            } catch (ConfigurationSnapshot.OwnershipDriftException drift) {
                return rollbackAndAbort(request, transactions, active,
                        ReloadErrorCode.CONFIGURATION_DRIFT, "ownership_drift", started);
            } catch (MetadataCaptureException captureFailure) {
                return rollbackAndAbort(request, transactions, active,
                        ReloadErrorCode.CONFIGURATION_DRIFT, "metadata_capture_failed", started);
            } catch (TransactionFailure failure) {
                return rollbackAndAbort(request, transactions, active,
                        failure.errorCode, failure.diagnostic, started);
            } catch (Exception parseFailure) {
                return rollbackAndAbort(request, transactions, active,
                        ReloadErrorCode.XML_RELOAD_FAILED,
                        "parse_failed=" + parseFailure.getClass().getSimpleName(), started);
            } catch (LinkageError parseFailure) {
                return rollbackAndAbort(request, transactions, active,
                        ReloadErrorCode.XML_RELOAD_FAILED,
                        "parse_failed=" + parseFailure.getClass().getSimpleName(), started);
            } catch (Error parseFailure) {
                if (isFatal(parseFailure)) {
                    rollbackAndAbort(request, transactions, active,
                            ReloadErrorCode.XML_RELOAD_FAILED,
                            "parse_failed=" + parseFailure.getClass().getSimpleName(), started);
                    throw parseFailure;
                }
                return rollbackAndAbort(request, transactions, active,
                        ReloadErrorCode.XML_RELOAD_FAILED,
                        "parse_failed=" + parseFailure.getClass().getSimpleName(), started);
            }

            List<ReloadItemResult> items = new ArrayList<ReloadItemResult>(transactions.size());
            int changedCount = 0;
            for (TransactionTarget transaction : transactions) {
                if (!transaction.changed) {
                    logger.log(Level.INFO, "XML_SKIPPED", fields("requestId", request.getRequestId(),
                            "itemId", transaction.itemId, "resultCode", "SKIPPED",
                            "namespace", document.getNamespace()));
                    items.add(item(transaction.itemId, OperationStatus.SKIPPED, null,
                            "digest_unchanged"));
                    continue;
                }
                changedCount++;
                logger.log(Level.INFO, "XML_COMMIT", fields("requestId", request.getRequestId(),
                        "itemId", transaction.itemId, "namespace", document.getNamespace(),
                        "ownedCount", Integer.toString(ownedCount(transaction.updatedMetadata)),
                        "durationMs", Long.toString(elapsedMillis(started))));
                String cacheDetail = MyBatisCacheInvalidator.invalidate(transaction.configuration);
                logger.log(Level.INFO, "XML_CACHE_INVALIDATE", fields("requestId", request.getRequestId(),
                        "itemId", transaction.itemId, "detail", cacheDetail));
                items.add(item(transaction.itemId, OperationStatus.SUCCESS, null,
                        "factory=" + safeFactory(transaction.target.handle) + ";" + cacheDetail));
            }
            OperationStatus status = changedCount == 0
                    ? OperationStatus.SKIPPED : OperationStatus.SUCCESS;
            return transactionResponse(request, status, null, items);
        } finally {
            for (int i = lockOrder.size() - 1; i >= 0; i--) {
                HotReloadBridge.exitWrite(lockOrder.get(i).writeToken);
            }
        }
    }

    private ReloadResponse rollbackAndAbort(MapperReloadRequest request,
                                            List<TransactionTarget> transactions,
                                            TransactionTarget failed,
                                            ReloadErrorCode failureCode,
                                            String diagnostic, long started) {
        boolean restored = true;
        for (int i = transactions.size() - 1; i >= 0; i--) {
            TransactionTarget transaction = transactions.get(i);
            if (!transaction.mutationStarted || transaction.snapshot == null) continue;
            try {
                transaction.snapshot.restore();
            } catch (Exception restoreFailure) {
                restored = false;
            } catch (LinkageError restoreFailure) {
                restored = false;
            } catch (Error restoreFailure) {
                if (isFatal(restoreFailure)) {
                    markTransactionUnsafe(transactions);
                    throw restoreFailure;
                }
                restored = false;
            }
            try {
                if (!HotReloadBridge.restoreResourceMetadata(
                        transaction.configuration, transaction.metadata)) {
                    restored = false;
                }
            } catch (RuntimeException restoreFailure) {
                restored = false;
            } catch (LinkageError restoreFailure) {
                restored = false;
            } catch (Error restoreFailure) {
                if (isFatal(restoreFailure)) {
                    markTransactionUnsafe(transactions);
                    throw restoreFailure;
                }
                restored = false;
            }
        }
        for (TransactionTarget transaction : transactions) {
            if (transaction.changed && transaction.target.handle.isReloadUnsafe()) {
                restored = false;
            }
        }
        if (!restored) markTransactionUnsafe(transactions);

        ReloadErrorCode resultCode = restored ? failureCode : ReloadErrorCode.ROLLBACK_FAILED;
        logger.log(restored ? Level.WARNING : Level.SEVERE, "XML_ROLLBACK", fields(
                "requestId", request.getRequestId(), "resultCode", resultCode.name(),
                "targetCount", Integer.toString(transactions.size()),
                "durationMs", Long.toString(elapsedMillis(started))));
        if (!restored) {
            List<ReloadItemResult> items = new ArrayList<ReloadItemResult>(transactions.size());
            for (TransactionTarget transaction : transactions) {
                if (transaction.changed) {
                    items.add(item(transaction.itemId, OperationStatus.RESTART_REQUIRED,
                            ReloadErrorCode.ROLLBACK_FAILED, "transaction_rollback_failed"));
                } else {
                    items.add(item(transaction.itemId, OperationStatus.SKIPPED, null,
                            transaction.digestKnown ? "digest_unchanged" : "transaction_aborted"));
                }
            }
            return transactionResponse(request, OperationStatus.RESTART_REQUIRED,
                    ReloadErrorCode.ROLLBACK_FAILED, items);
        }

        List<ReloadItemResult> items = new ArrayList<ReloadItemResult>(transactions.size());
        for (TransactionTarget transaction : transactions) {
            if (transaction == failed) {
                items.add(item(transaction.itemId, OperationStatus.FAILED,
                        failureCode, diagnostic));
            } else if (transaction.changed) {
                items.add(item(transaction.itemId, OperationStatus.SKIPPED, null,
                        transaction.mutationStarted
                                ? "transaction_rolled_back" : "transaction_aborted"));
            } else {
                items.add(item(transaction.itemId, OperationStatus.SKIPPED, null,
                        transaction.digestKnown ? "digest_unchanged" : "transaction_aborted"));
            }
        }
        return transactionResponse(request, OperationStatus.FAILED, failureCode, items);
    }

    private static ReloadResponse abortWithoutMutation(MapperReloadRequest request,
                                                       List<TransactionTarget> transactions,
                                                       TransactionTarget failed,
                                                       OperationStatus failureStatus,
                                                       ReloadErrorCode failureCode,
                                                       String diagnostic) {
        List<ReloadItemResult> items = new ArrayList<ReloadItemResult>(transactions.size());
        for (TransactionTarget transaction : transactions) {
            if (transaction == failed) {
                items.add(item(transaction.itemId, failureStatus, failureCode, diagnostic));
            } else {
                items.add(item(transaction.itemId, OperationStatus.SKIPPED, null,
                        transaction.digestKnown && !transaction.changed
                                ? "digest_unchanged" : "transaction_aborted"));
            }
        }
        return transactionResponse(request, failureStatus, failureCode, items);
    }

    private static ReloadResponse transactionResponse(MapperReloadRequest request,
                                                      OperationStatus status,
                                                      ReloadErrorCode errorCode,
                                                      List<ReloadItemResult> items) {
        String message = errorCode == null ? status.name() : errorCode.name();
        return new ReloadResponse(request.getRequestId(), status, errorCode, message, items);
    }

    private static long remainingLockMillis(long deadline) {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0L) return -1L;
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        return remainingMillis == 0L ? 1L : remainingMillis;
    }

    private static boolean isFatal(Error failure) {
        return failure instanceof ThreadDeath || failure instanceof VirtualMachineError;
    }

    private static void markTransactionUnsafe(List<TransactionTarget> transactions) {
        for (TransactionTarget transaction : transactions) {
            if (transaction.changed) HotReloadBridge.markReloadUnsafe(transaction.configuration);
        }
    }

    private void logResult(MapperReloadRequest request, ReloadResponse response, long started,
                           Throwable failure) {
        String resultCode;
        Level level;
        if (failure != null) {
            resultCode = ReloadErrorCode.INTERNAL_ERROR.name();
            level = Level.SEVERE;
        } else {
            resultCode = response.getErrorCode() == null
                    ? response.getStatus().name() : response.getErrorCode().name();
            level = response.getStatus() == OperationStatus.SUCCESS
                    || response.getStatus() == OperationStatus.SKIPPED ? Level.INFO : Level.WARNING;
        }
        logger.log(level, "XML_RESULT", fields("requestId", request.getRequestId(),
                "resourceId", request.getUpdate().getResourceId(),
                "resultCode", resultCode, "durationMs", Long.toString(elapsedMillis(started)),
                "detail", response == null || response.getMessage() == null || response.getMessage().isEmpty()
                        ? "none" : response.getMessage()));
    }

    private static void parse(Object configuration, ResourceMetadata metadata, byte[] content) throws Exception {
        ClassLoader loader = configuration.getClass().getClassLoader();
        Class<?> parserClass = Class.forName(metadata.getParserClassName(), true, loader);
        Object sqlFragments = ConfigurationSnapshot.fieldValue(configuration, "sqlFragments");
        Constructor<?> constructor = findParserConstructor(parserClass, configuration.getClass());
        Object parser = constructor.newInstance(new ByteArrayInputStream(content), configuration,
                metadata.getRuntimeResource(), sqlFragments);
        Object capture = HotReloadBridge.beginMapperParse(configuration, parser, metadata.getRuntimeResource());
        if (capture == null) throw new MetadataCaptureException();
        boolean success = false;
        try {
            Method parse = parserClass.getMethod("parse");
            parse.invoke(parser);
            success = true;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("Mapper parser failed without an exception cause", cause);
        } finally {
            if (success && !HotReloadBridge.endMapperParse(capture, true)) {
                throw new MetadataCaptureException();
            }
            if (!success) HotReloadBridge.endMapperParse(capture, false);
        }
    }

    private static final class MetadataCaptureException extends Exception {
    }

    private static final class TransactionFailure extends Exception {
        private final ReloadErrorCode errorCode;
        private final String diagnostic;

        private TransactionFailure(ReloadErrorCode errorCode, String diagnostic) {
            this.errorCode = errorCode;
            this.diagnostic = diagnostic;
        }
    }

    private static Constructor<?> findParserConstructor(Class<?> parserClass, Class<?> configurationClass)
            throws NoSuchMethodException {
        for (Constructor<?> constructor : parserClass.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 4 && java.io.InputStream.class.isAssignableFrom(parameters[0])
                    && parameters[1].isAssignableFrom(configurationClass)
                    && parameters[2] == String.class && Map.class.isAssignableFrom(parameters[3])) {
                if (!constructor.isAccessible()) constructor.setAccessible(true);
                return constructor;
            }
        }
        throw new NoSuchMethodException(parserClass.getName() + " mapper constructor");
    }

    private static OwnerResolution resolveOwners(List<ConfigurationHandle> handles, String resourceId,
                                                  String namespace) {
        List<TargetLookup> owners = new ArrayList<TargetLookup>();
        for (ConfigurationHandle handle : handles) {
            if (handle.getConfiguration() == null) continue;
            MetadataLookup lookup = findMetadata(handle, resourceId);
            if (lookup.errorCode == ReloadErrorCode.RESOURCE_ID_AMBIGUOUS) {
                return new OwnerResolution(Collections.<TargetLookup>emptyList(), owners.size() + 1,
                        ReloadErrorCode.RESOURCE_ID_AMBIGUOUS);
            }
            if (lookup.errorCode == null) {
                owners.add(new TargetLookup(handle, lookup.metadata, null, 1));
            }
        }
        if (owners.isEmpty()) {
            return new OwnerResolution(Collections.<TargetLookup>emptyList(), 0,
                    ReloadErrorCode.RESOURCE_NOT_LOADED);
        }
        List<TargetLookup> matched = new ArrayList<TargetLookup>();
        for (TargetLookup owner : owners) {
            if (namespace.equals(owner.metadata.getNamespace())) {
                matched.add(owner);
            }
        }
        if (matched.isEmpty()) {
            return new OwnerResolution(Collections.<TargetLookup>emptyList(), owners.size(),
                    ReloadErrorCode.XML_INVALID);
        }
        return new OwnerResolution(matched, owners.size(), null);
    }

    private static String itemId(String resourceId, TargetLookup target, int index) {
        Object configuration = target.handle.getConfiguration();
        String factory = safeFactory(target.handle);
        String identity = configuration == null ? "missing"
                : Integer.toHexString(System.identityHashCode(configuration));
        return resourceId + "@" + factory + "#" + identity + "[" + index + "]";
    }

    private static String safeFactory(ConfigurationHandle handle) {
        String factory = handle.getFactoryClassName();
        if (factory == null || factory.isEmpty()) return "unknownFactory";
        int dot = factory.lastIndexOf('.');
        return dot >= 0 ? factory.substring(dot + 1) : factory;
    }

    private static ReloadItemResult item(String itemId, OperationStatus status, ReloadErrorCode code,
                                         String diagnostic) {
        String message = code == null ? status.name() : code.name();
        return new ReloadItemResult(itemId, status, code, message, diagnostic == null ? "" : diagnostic);
    }

    private static MetadataLookup findMetadata(ConfigurationHandle handle, String resourceId) {
        ResourceMetadata match = null;
        int matchCount = 0;
        for (ResourceMetadata metadata : handle.getResourceMetadata()) {
            if (resourceId.equals(metadata.getResourceId())) {
                match = metadata;
                matchCount++;
            }
        }
        if (matchCount == 0) return new MetadataLookup(null, ReloadErrorCode.RESOURCE_NOT_LOADED);
        if (matchCount > 1) return new MetadataLookup(null, ReloadErrorCode.RESOURCE_ID_AMBIGUOUS);
        return new MetadataLookup(match, null);
    }

    private static int ownedCount(ResourceMetadata metadata) {
        int count = 0;
        for (List<String> ids : metadata.getOwnedIds().values()) count += ids.size();
        return count;
    }

    private static boolean hasCacheTopology(ResourceMetadata metadata) {
        return !metadata.getOwnedIds("caches").isEmpty()
                || !metadata.getOwnedIds("cacheRefMap").isEmpty();
    }

    private static ReloadResponse failure(MapperReloadRequest request, ReloadErrorCode code) {
        return failure(request, code, request.getUpdate().getResourceId(), "");
    }

    private static ReloadResponse failure(MapperReloadRequest request, ReloadErrorCode code,
                                          String resourceId, String diagnostic) {
        return response(request, OperationStatus.FAILED, code, resourceId, diagnostic);
    }

    private static ReloadResponse restart(MapperReloadRequest request, ReloadErrorCode code) {
        return restart(request, code, request.getUpdate().getResourceId(), "");
    }

    private static ReloadResponse restart(MapperReloadRequest request, ReloadErrorCode code,
                                          String resourceId, String diagnostic) {
        return response(request, OperationStatus.RESTART_REQUIRED, code, resourceId, diagnostic);
    }

    private static ReloadResponse response(MapperReloadRequest request, OperationStatus status, ReloadErrorCode code) {
        return response(request, status, code, request.getUpdate().getResourceId(), "");
    }

    private static ReloadResponse response(MapperReloadRequest request, OperationStatus status, ReloadErrorCode code,
                                           String resourceId, String diagnostic) {
        String safeResourceId = resourceId == null || resourceId.isEmpty()
                ? request.getUpdate().getResourceId() : resourceId;
        String safeDiagnostic = diagnostic == null ? "" : diagnostic;
        String message = code == null ? "" : code.name();
        ReloadItemResult item = new ReloadItemResult(safeResourceId, status, code, message, safeDiagnostic);
        return new ReloadResponse(request.getRequestId(), status, code, message,
                Collections.singletonList(item));
    }

    private static final class TargetLookup {
        private final ConfigurationHandle handle;
        private final ResourceMetadata metadata;
        private final ReloadErrorCode errorCode;
        private final int ownerCount;

        private TargetLookup(ConfigurationHandle handle, ResourceMetadata metadata,
                             ReloadErrorCode errorCode, int ownerCount) {
            this.handle = handle;
            this.metadata = metadata;
            this.errorCode = errorCode;
            this.ownerCount = ownerCount;
        }
    }

    private static final class TransactionTarget {
        private final TargetLookup target;
        private final Object configuration;
        private final String itemId;
        private ResourceMetadata metadata;
        private ResourceMetadata updatedMetadata;
        private ConfigurationSnapshot snapshot;
        private WriteLockToken writeToken;
        private boolean digestKnown;
        private boolean changed;
        private boolean mutationStarted;

        private TransactionTarget(TargetLookup target, Object configuration, String itemId) {
            this.target = target;
            this.configuration = configuration;
            this.itemId = itemId;
        }
    }

    private static final class OwnerResolution {
        private final List<TargetLookup> targets;
        private final int ownerCount;
        private final ReloadErrorCode errorCode;

        private OwnerResolution(List<TargetLookup> targets, int ownerCount, ReloadErrorCode errorCode) {
            this.targets = targets;
            this.ownerCount = ownerCount;
            this.errorCode = errorCode;
        }
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static String shortHash(byte[] digest) {
        StringBuilder value = new StringBuilder(12);
        for (int i = 0; i < 6 && i < digest.length; i++) value.append(String.format("%02x", digest[i] & 0xff));
        return value.toString();
    }

    private static Map<String, String> fields(String... keyValues) {
        Map<String, String> fields = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) fields.put(keyValues[i], keyValues[i + 1]);
        return fields;
    }

    private static final class MetadataLookup {
        private final ResourceMetadata metadata;
        private final ReloadErrorCode errorCode;

        private MetadataLookup(ResourceMetadata metadata, ReloadErrorCode errorCode) {
            this.metadata = metadata;
            this.errorCode = errorCode;
        }
    }
}

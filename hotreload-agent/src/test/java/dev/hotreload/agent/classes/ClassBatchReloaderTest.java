package dev.hotreload.agent.classes;

import dev.hotreload.agent.logging.AgentSessionLogger;
import dev.hotreload.protocol.message.ClassReloadRequest;
import dev.hotreload.protocol.message.ClassUpdate;
import dev.hotreload.protocol.message.OperationStatus;
import dev.hotreload.protocol.message.ReloadErrorCode;
import dev.hotreload.protocol.message.ReloadResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ClassBatchReloaderTest {
    @TempDir Path tempDirectory;

    @Test void rejectsUnsupportedRedefinitionBeforeScanningLoadedClasses() throws Exception {
        FakeInstrumentation fake = new FakeInstrumentation(false, new Class<?>[]{Target.class});
        AgentSessionLogger logger = logger("unsupported");
        try {
            ReloadResponse response = new ClassBatchReloader(fake.proxy(), logger).reload(request(Target.class));
            assertEquals(OperationStatus.FAILED, response.getStatus());
            assertEquals(ReloadErrorCode.CLASS_REDEFINE_UNSUPPORTED, response.getErrorCode());
            assertEquals(0, fake.loadedClassCalls.get());
        } finally {
            logger.close();
        }
        String logs = readLogs("unsupported.log");
        assertTrue(logs.contains("event=REDEFINE_END"), logs);
        assertTrue(logs.contains("resultCode=CLASS_REDEFINE_UNSUPPORTED"), logs);
        assertTrue(logs.matches("(?s).*event=REDEFINE_END[^\\r\\n]*durationMs=\\d+.*"), logs);
    }

    @Test void validatesThenRedefinesTheWholeBatchOnce() throws Exception {
        FakeInstrumentation fake = new FakeInstrumentation(true, new Class<?>[]{Target.class, SecondTarget.class});
        AgentSessionLogger logger = logger("success");
        try {
            ClassReloadRequest request = new ClassReloadRequest("r", "token", Arrays.asList(
                    update(Target.class), update(SecondTarget.class)));
            ReloadResponse response = new ClassBatchReloader(fake.proxy(), logger).reload(request);
            assertEquals(OperationStatus.SUCCESS, response.getStatus());
            assertEquals(1, fake.redefineCalls.get());
            assertEquals(2, fake.definitions.get().length);
        } finally {
            logger.close();
        }
        assertTerminalLog(readLogs("success.log"), "r", "batch", null);
    }

    @Test void rejectsNameMismatchDuplicateUnloadedAndUnmodifiableClasses() throws Exception {
        AgentSessionLogger logger = logger("validation");
        try {
            FakeInstrumentation loaded = new FakeInstrumentation(true, new Class<?>[]{Target.class});
            ReloadResponse mismatch = new ClassBatchReloader(loaded.proxy(), logger).reload(
                    new ClassReloadRequest("r", "t", Collections.singletonList(
                            new ClassUpdate("other.Name", bytes(Target.class)))));
            assertEquals(ReloadErrorCode.CLASS_NAME_INVALID, mismatch.getErrorCode());

            ReloadResponse duplicate = new ClassBatchReloader(loaded.proxy(), logger).reload(
                    new ClassReloadRequest("r", "t", Arrays.asList(update(Target.class), update(Target.class))));
            assertEquals(ReloadErrorCode.CLASS_DUPLICATE, duplicate.getErrorCode());

            FakeInstrumentation empty = new FakeInstrumentation(true, new Class<?>[0]);
            // Target is loadable by the test classloader; when not listed as loaded we define/redefine it.
            ReloadResponse unloaded = new ClassBatchReloader(empty.proxy(), logger).reload(request(Target.class));
            assertEquals(OperationStatus.SUCCESS, unloaded.getStatus());
            assertEquals(1, unloaded.getItems().size());
            assertTrue(unloaded.getItems().get(0).getDiagnostic().contains("defined")
                    || unloaded.getItems().get(0).getDiagnostic().contains("redefined"),
                    unloaded.getItems().get(0).getDiagnostic());

            loaded.modifiable = false;
            assertEquals(ReloadErrorCode.CLASS_UNMODIFIABLE,
                    new ClassBatchReloader(loaded.proxy(), logger).reload(request(Target.class)).getErrorCode());
            assertEquals(0, loaded.redefineCalls.get());
        } finally {
            logger.close();
        }
    }

    @Test void logsTerminalResultWhenValidationFails() throws Exception {
        AgentSessionLogger logger = logger("validation-terminal");
        try {
            FakeInstrumentation loaded = new FakeInstrumentation(true, new Class<?>[]{Target.class});
            new ClassBatchReloader(loaded.proxy(), logger).reload(
                    new ClassReloadRequest("validation-request", "t", Arrays.asList(
                            update(Target.class), update(Target.class))));
        } finally {
            logger.close();
        }
        String logs = readLogs("validation-terminal.log");
        assertTrue(logs.contains("requestId=validation-request"), logs);
        assertTrue(logs.contains("resultCode=CLASS_DUPLICATE") || logs.contains("CLASS_DUPLICATE"), logs);
    }

    @Test void logsTerminalResultWhenClassIsNotLoaded() throws Exception {
        AgentSessionLogger logger = logger("not-loaded-terminal");
        try {
            FakeInstrumentation empty = new FakeInstrumentation(true, new Class<?>[0]);
            new ClassBatchReloader(empty.proxy(), logger).reload(
                    new ClassReloadRequest("not-loaded-request", "t",
                            Collections.singletonList(update(Target.class))));
        } finally {
            logger.close();
        }
        String notLoadedLogs = readLogs("not-loaded-terminal.log");
        assertTrue(notLoadedLogs.contains("requestId=not-loaded-request"), notLoadedLogs);
        assertTrue(notLoadedLogs.contains("itemId=" + Target.class.getName()), notLoadedLogs);
        assertTrue(notLoadedLogs.contains("event=CLASS_DEFINE_BEGIN")
                || notLoadedLogs.contains("event=CLASS_DEFINE_END")
                || notLoadedLogs.contains("event=CLASS_BATCH_RESULT"), notLoadedLogs);
    }

    @Test void generationWithoutSpringBeanRequiresRestart() throws Exception {
        FakeInstrumentation fake = new FakeInstrumentation(true, new Class<?>[]{AddMethodSample.class});
        fake.redefineFailure = new UnsupportedOperationException(
                "class redefinition failed: attempted to add a method");
        AgentSessionLogger logger = logger("add-method");
        try {
            ClassUpdate update = new ClassUpdate(AddMethodSample.class.getName(),
                    addMethodSampleWithExtraMethod());
            ReloadResponse response = new ClassBatchReloader(fake.proxy(), logger).reload(
                    new ClassReloadRequest("r-add", "token", Collections.singletonList(update)));
            assertEquals(OperationStatus.RESTART_REQUIRED, response.getStatus(), response.getMessage());
            assertEquals(ReloadErrorCode.SPRING_REBIND_INCOMPLETE, response.getErrorCode());
            assertEquals(OperationStatus.RESTART_REQUIRED, response.getItems().get(0).getStatus());
            assertTrue(response.getItems().get(0).getDiagnostic().contains("springRebind=incomplete"),
                    response.getItems().get(0).getDiagnostic());
            assertNotNull(HotReloadClassRegistry.get(AddMethodSample.class.getName()));
            Class<?> live = HotReloadClassRegistry.get(AddMethodSample.class.getName());
            assertNotNull(live);
            assertNotSame(AddMethodSample.class, live);
            assertTrue(AddMethodSample.class.isAssignableFrom(live), String.valueOf(live));
        } finally {
            logger.close();
            HotReloadClassRegistry.clear();
        }
        String logs = readLogs("add-method.log");
        assertTrue(logs.contains("CLASS_STRUCTURE_FALLBACK")
                || logs.contains("CLASS_STRUCTURE_RELOAD")
                || logs.contains("structureFallback")
                || logs.contains("generation"), logs);
    }

    @Test void requiresRestartWhenFinalClassCannotUseAssignableGeneration() throws Exception {
        FakeInstrumentation fake = new FakeInstrumentation(true, new Class<?>[]{Target.class});
        fake.redefineFailure = new UnsupportedOperationException(
                "class redefinition failed: attempted to change the schema");
        AgentSessionLogger logger = logger("schema");
        try {
            ReloadResponse response = new ClassBatchReloader(fake.proxy(), logger).reload(request(Target.class));
            assertEquals(OperationStatus.RESTART_REQUIRED, response.getStatus(), response.getMessage());
            assertEquals(ReloadErrorCode.CLASS_STRUCTURE_CHANGED, response.getErrorCode());
            assertTrue(response.getItems().get(0).getDiagnostic().contains("final_class_cannot_subclass"),
                    response.getItems().get(0).getDiagnostic());
        } finally {
            logger.close();
            HotReloadClassRegistry.clear();
        }
    }

    @Test void mapsJvmInternalRedefinitionFailureToFailedResponse() throws Exception {
        FakeInstrumentation fake = new FakeInstrumentation(true, new Class<?>[]{Target.class});
        fake.redefineFailure = new InternalError("redefine failed");
        AgentSessionLogger logger = logger("internal-error");
        try {
            ReloadResponse response = new ClassBatchReloader(fake.proxy(), logger).reload(request(Target.class));
            assertEquals(OperationStatus.FAILED, response.getStatus());
            assertEquals(ReloadErrorCode.CLASS_REDEFINE_FAILED, response.getErrorCode());
        } finally {
            logger.close();
        }
    }

    @Test void hierarchyRejectionOnFinalClassRequiresRestart() throws Exception {
        FakeInstrumentation fake = new FakeInstrumentation(true, new Class<?>[]{Target.class});
        fake.redefineFailure = new UnsupportedOperationException(
                "class redefinition failed: attempted to change the superclass or interfaces");
        AgentSessionLogger logger = logger("hierarchy");
        try {
            ReloadResponse response = new ClassBatchReloader(fake.proxy(), logger).reload(request(Target.class));
            assertEquals(OperationStatus.RESTART_REQUIRED, response.getStatus(), response.getMessage());
            assertEquals(ReloadErrorCode.CLASS_STRUCTURE_CHANGED, response.getErrorCode());
        } finally {
            logger.close();
            HotReloadClassRegistry.clear();
        }
    }

    @Test void detectsAddedMethodBeforeRedefineAndUsesStructuralPath() throws Exception {
        // Live shape of AddMethodSample has only hello(); next bytecode adds added().
        // Analyzer must mark STRUCTURE so structural path runs even if redefine would fail.
        FakeInstrumentation fake = new FakeInstrumentation(true, new Class<?>[]{AddMethodSample.class});
        fake.redefineFailure = new UnsupportedOperationException(
                "class redefinition failed: attempted to add a method");
        AgentSessionLogger logger = logger("detect-structure");
        try {
            ClassChangeAnalyzer.Analysis analysis = ClassChangeAnalyzer.compare(
                    AddMethodSample.class, addMethodSampleWithExtraMethod());
            assertTrue(analysis.isStructureChanged());
            assertEquals(ClassChangeAnalyzer.ChangeKind.STRUCTURE, analysis.getKind());

            ReloadResponse response = new ClassBatchReloader(fake.proxy(), logger).reload(
                    new ClassReloadRequest("r-detect", "token", Collections.singletonList(
                            new ClassUpdate(AddMethodSample.class.getName(), addMethodSampleWithExtraMethod()))));
            assertEquals(OperationStatus.RESTART_REQUIRED, response.getStatus(), response.getMessage());
            assertEquals(ReloadErrorCode.SPRING_REBIND_INCOMPLETE, response.getErrorCode());
            assertEquals(0, fake.redefineCalls.get(), "detected add method must skip redefineClasses");
            assertTrue(response.getItems().get(0).getDiagnostic().contains("springRebind=incomplete"),
                    response.getItems().get(0).getDiagnostic());
        } finally {
            logger.close();
            HotReloadClassRegistry.clear();
        }
    }


    @Test void prefersRegistryGenerationWhenMultipleClassesShareBinaryName() throws Exception {
        // Simulate original + generation both loaded under same binary name.
        Class<?> original = AddMethodSample.class;
        GenerationClassLoader gen = new GenerationClassLoader(
                original.getClassLoader(), original.getName(), addMethodSampleWithExtraMethod());
        Class<?> generation = gen.defineTarget();
        HotReloadClassRegistry.put(original.getName(), generation);

        FakeInstrumentation fake = new FakeInstrumentation(true, new Class<?>[]{original, generation});
        AgentSessionLogger logger = logger("ambiguous-resolve");
        try {
            ClassUpdate update = new ClassUpdate(original.getName(), addMethodSampleWithExtraMethod());
            ReloadResponse response = new ClassBatchReloader(fake.proxy(), logger).reload(
                    new ClassReloadRequest("r-amb", "token", Collections.singletonList(update)));
            assertNotEquals(ReloadErrorCode.CLASS_AMBIGUOUS, response.getErrorCode(), response.getMessage());
            assertEquals(OperationStatus.SUCCESS, response.getStatus(), response.getMessage());
            assertEquals(generation, HotReloadClassRegistry.get(original.getName()));
        } finally {
            logger.close();
            HotReloadClassRegistry.clear();
        }
    }

    @Test void resolveLoadedTargetPrefersRegistryOverAmbiguousMatches() {
        Class<?> original = AddMethodSample.class;
        GenerationClassLoader gen = new GenerationClassLoader(
                original.getClassLoader(), original.getName(), addMethodSampleWithExtraMethod());
        Class<?> generation = gen.defineTarget();
        HotReloadClassRegistry.put(original.getName(), generation);
        try {
            List<Class<?>> matches = Arrays.asList(original, generation);
            Set<String> hints = new LinkedHashSet<String>();
            Class<?> resolved = ClassBatchReloader.resolveLoadedTarget(original.getName(), matches, hints);
            assertSame(generation, resolved);
            assertTrue(hints.toString().contains("registryGen"));
        } finally {
            HotReloadClassRegistry.clear();
        }
    }
    private AgentSessionLogger logger(String name) throws Exception {
        return new AgentSessionLogger(name, tempDirectory.resolve(name + ".log").toAbsolutePath());
    }

    private String readLogs(String prefix) throws Exception {
        StringBuilder result = new StringBuilder();
        try (java.util.stream.Stream<Path> paths = Files.list(tempDirectory)) {
            for (Path path : (Iterable<Path>) paths
                    .filter(candidate -> candidate.getFileName().toString().startsWith(prefix))::iterator) {
                result.append(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
            }
        }
        return result.toString();
    }

    private static void assertTerminalLog(String logs, String requestId, String itemId,
                                          ReloadErrorCode errorCode) {
        assertTrue(logs.contains("event=REDEFINE_END"), logs);
        assertTrue(logs.contains("requestId=" + requestId), logs);
        assertTrue(logs.contains("itemId=" + itemId), logs);
        assertTrue(logs.contains("resultCode=" + (errorCode == null ? "SUCCESS" : errorCode.name())), logs);
        assertTrue(logs.matches("(?s).*event=REDEFINE_END[^\\r\\n]*durationMs=\\d+.*"), logs);
    }


    @Test void detectedMethodDeletionRequiresRestartAndSkipsHotSpot() throws Exception {
        FakeInstrumentation fake = new FakeInstrumentation(true, new Class<?>[]{DeleteMethodSample.class});
        fake.redefineFailure = new UnsupportedOperationException(
                "虚拟机不支持的操作: delete method not implemented");
        AgentSessionLogger logger = logger("delete-method");
        try {
            ClassChangeAnalyzer.Analysis analysis = ClassChangeAnalyzer.compare(
                    DeleteMethodSample.class, deleteMethodSampleWithoutHello());
            assertTrue(analysis.isStructureChanged(), "delete method must be STRUCTURE");
            ClassUpdate update = new ClassUpdate(DeleteMethodSample.class.getName(),
                    deleteMethodSampleWithoutHello());
            ReloadResponse response = new ClassBatchReloader(fake.proxy(), logger).reload(
                    new ClassReloadRequest("r-del", "token", Collections.singletonList(update)));
            assertEquals(OperationStatus.RESTART_REQUIRED, response.getStatus(), response.getMessage());
            assertEquals(ReloadErrorCode.CLASS_STRUCTURE_CHANGED, response.getErrorCode());
            assertEquals(0, fake.redefineCalls.get(), "structure delete must not call redefineClasses");
            assertTrue(response.getItems().get(0).getDiagnostic()
                            .contains("generation_cannot_remove_or_change_methods"),
                    response.getItems().get(0).getDiagnostic());
            assertNull(HotReloadClassRegistry.get(DeleteMethodSample.class.getName()));
        } finally {
            logger.close();
            HotReloadClassRegistry.clear();
        }
        String logs = readLogs("delete-method.log");
        assertTrue(logs.contains("CLASS_STRUCTURE_RELOAD"), logs);
    }

    @Test void detectedFieldDeletionRequiresRestartAndSkipsHotSpot() throws Exception {
        FakeInstrumentation fake = new FakeInstrumentation(true, new Class<?>[]{DeleteFieldSample.class});
        fake.redefineFailure = new UnsupportedOperationException("delete field not implemented");
        AgentSessionLogger logger = logger("delete-field");
        try {
            ClassUpdate update = new ClassUpdate(DeleteFieldSample.class.getName(),
                    deleteFieldSampleWithoutField());
            ReloadResponse response = new ClassBatchReloader(fake.proxy(), logger).reload(
                    new ClassReloadRequest("r-delf", "token", Collections.singletonList(update)));
            assertEquals(OperationStatus.RESTART_REQUIRED, response.getStatus(), response.getMessage());
            assertEquals(ReloadErrorCode.CLASS_STRUCTURE_CHANGED, response.getErrorCode());
            assertEquals(0, fake.redefineCalls.get());
            assertTrue(response.getItems().get(0).getDiagnostic()
                            .contains("generation_cannot_remove_or_retype_fields"),
                    response.getItems().get(0).getDiagnostic());
        } finally {
            logger.close();
            HotReloadClassRegistry.clear();
        }
    }

    @Test void successiveStructureReloadsPreferRegistryGeneration() throws Exception {
        FakeInstrumentation fake = new FakeInstrumentation(true, new Class<?>[]{AddMethodSample.class});
        fake.redefineFailure = new UnsupportedOperationException("add method not implemented");
        AgentSessionLogger logger = logger("successive-gen");
        try {
            ClassUpdate first = new ClassUpdate(AddMethodSample.class.getName(), addMethodSampleWithExtraMethod());
            ReloadResponse r1 = new ClassBatchReloader(fake.proxy(), logger).reload(
                    new ClassReloadRequest("r1", "token", Collections.singletonList(first)));
            assertEquals(OperationStatus.RESTART_REQUIRED, r1.getStatus(), r1.getMessage());
            assertEquals(ReloadErrorCode.SPRING_REBIND_INCOMPLETE, r1.getErrorCode());
            Class<?> gen1 = HotReloadClassRegistry.get(AddMethodSample.class.getName());
            assertNotNull(gen1);

            // Simulate JVM still listing original + gen1 under same binary name.
            FakeInstrumentation ambiguous = new FakeInstrumentation(true,
                    new Class<?>[]{AddMethodSample.class, gen1});
            ambiguous.redefineFailure = new UnsupportedOperationException("delete method not implemented");
            ClassUpdate second = new ClassUpdate(AddMethodSample.class.getName(),
                    addMethodSampleWithExtraMethodAndField());
            ReloadResponse r2 = new ClassBatchReloader(ambiguous.proxy(), logger).reload(
                    new ClassReloadRequest("r2", "token", Collections.singletonList(second)));
            assertEquals(OperationStatus.RESTART_REQUIRED, r2.getStatus(), r2.getMessage());
            assertEquals(ReloadErrorCode.SPRING_REBIND_INCOMPLETE, r2.getErrorCode());
            assertNotEquals(ReloadErrorCode.CLASS_AMBIGUOUS, r2.getErrorCode(), r2.getMessage());
            Class<?> gen2 = HotReloadClassRegistry.get(AddMethodSample.class.getName());
            assertNotNull(gen2);
            assertNotSame(gen1, gen2);
        } finally {
            logger.close();
            HotReloadClassRegistry.clear();
        }
    }


    @Test void chineseDeleteMethodOnFinalClassRequiresRestart() throws Exception {
        // Compatible shape so analysis is not STRUCTURE; redefine throws CN HotSpot message.
        FakeInstrumentation fake = new FakeInstrumentation(true, new Class<?>[]{Target.class});
        fake.redefineFailure = new UnsupportedOperationException(
                "虚拟机不支持的操作: delete method not implemented");
        AgentSessionLogger logger = logger("cn-delete-fallback");
        try {
            ReloadResponse response = new ClassBatchReloader(fake.proxy(), logger).reload(request(Target.class));
            assertEquals(OperationStatus.RESTART_REQUIRED, response.getStatus(), response.getMessage());
            assertEquals(ReloadErrorCode.CLASS_STRUCTURE_CHANGED, response.getErrorCode());
            assertEquals(2, fake.redefineCalls.get(),
                    "a rejected batch is retried once as an isolated class");
        } finally {
            logger.close();
            HotReloadClassRegistry.clear();
        }
    }

    @Test void detectsAddedFieldBeforeRedefineAndSkipsHotSpot() throws Exception {
        FakeInstrumentation fake = new FakeInstrumentation(true, new Class<?>[]{DeleteFieldSample.class});
        fake.redefineFailure = new UnsupportedOperationException("add field not implemented");
        AgentSessionLogger logger = logger("add-field");
        try {
            // DeleteFieldSample currently has a field; rebuild with extra field => structure add field relative? 
            // Use AddMethodSample live (no field) + bytecode with field.
            FakeInstrumentation fake2 = new FakeInstrumentation(true, new Class<?>[]{AddMethodSample.class});
            fake2.redefineFailure = new UnsupportedOperationException("add field not implemented");
            ClassUpdate update = new ClassUpdate(AddMethodSample.class.getName(),
                    addMethodSampleWithExtraMethodAndField());
            ClassChangeAnalyzer.Analysis analysis = ClassChangeAnalyzer.compare(
                    AddMethodSample.class, addMethodSampleWithExtraMethodAndField());
            assertTrue(analysis.isStructureChanged());
            ReloadResponse response = new ClassBatchReloader(fake2.proxy(), logger).reload(
                    new ClassReloadRequest("r-addf", "token", Collections.singletonList(update)));
            assertEquals(OperationStatus.RESTART_REQUIRED, response.getStatus(), response.getMessage());
            assertEquals(ReloadErrorCode.SPRING_REBIND_INCOMPLETE, response.getErrorCode());
            assertEquals(0, fake2.redefineCalls.get(), "add field structure must skip redefine");
        } finally {
            logger.close();
            HotReloadClassRegistry.clear();
        }
    }

    @Test void englishDeleteMethodOnFinalClassRequiresRestart() throws Exception {
        FakeInstrumentation fake = new FakeInstrumentation(true, new Class<?>[]{Target.class});
        fake.redefineFailure = new UnsupportedOperationException(
                "class redefinition failed: attempted to delete a method");
        AgentSessionLogger logger = logger("en-delete-fallback");
        try {
            ReloadResponse response = new ClassBatchReloader(fake.proxy(), logger).reload(request(Target.class));
            assertEquals(OperationStatus.RESTART_REQUIRED, response.getStatus(), response.getMessage());
            assertEquals(ReloadErrorCode.CLASS_STRUCTURE_CHANGED, response.getErrorCode());
        } finally {
            logger.close();
            HotReloadClassRegistry.clear();
        }
    }

    @Test void mixedBatchRebindsAppliedStructureWhenBodyRedefineFails() throws Exception {
        FakeInstrumentation fake = new FakeInstrumentation(true,
                new Class<?>[]{AddMethodSample.class, Target.class});
        fake.redefineFailure = new InternalError("body redefine failed");
        AgentSessionLogger logger = logger("mixed-partial");
        try {
            ClassReloadRequest request = new ClassReloadRequest("mixed", "token", Arrays.asList(
                    new ClassUpdate(AddMethodSample.class.getName(), addMethodSampleWithExtraMethod()),
                    update(Target.class)));
            ReloadResponse response = new ClassBatchReloader(fake.proxy(), logger).reload(request);

            assertEquals(OperationStatus.RESTART_REQUIRED, response.getStatus());
            assertEquals(ReloadErrorCode.SPRING_REBIND_INCOMPLETE, response.getErrorCode());
            assertEquals(2, response.getItems().size());
            assertEquals(AddMethodSample.class.getName(), response.getItems().get(0).getItemId());
            assertEquals(OperationStatus.RESTART_REQUIRED, response.getItems().get(0).getStatus());
            assertEquals(ReloadErrorCode.SPRING_REBIND_INCOMPLETE,
                    response.getItems().get(0).getErrorCode());
            assertEquals(Target.class.getName(), response.getItems().get(1).getItemId());
            assertEquals(OperationStatus.FAILED, response.getItems().get(1).getStatus());
            assertEquals(1, fake.definitions.get().length);
            assertNotNull(HotReloadClassRegistry.get(AddMethodSample.class.getName()));
        } finally {
            logger.close();
            HotReloadClassRegistry.clear();
        }
    }

    @Test void isolatesClassesAfterAtomicBatchSchemaRejection() throws Exception {
        FakeInstrumentation fake = new FakeInstrumentation(true,
                new Class<?>[]{Target.class, SecondTarget.class});
        fake.batchRedefineFailure = new UnsupportedOperationException(
                "class redefinition failed: attempted to add a method");
        AgentSessionLogger logger = logger("isolated-batch-retry");
        try {
            ReloadResponse response = new ClassBatchReloader(fake.proxy(), logger).reload(
                    new ClassReloadRequest("isolated", "token", Arrays.asList(
                            update(Target.class), update(SecondTarget.class))));

            assertEquals(OperationStatus.SUCCESS, response.getStatus(), response.getMessage());
            assertEquals(3, fake.redefineCalls.get(),
                    "one rejected batch must be followed by one redefine per class");
            assertEquals(OperationStatus.SUCCESS, response.getItems().get(0).getStatus());
            assertEquals(OperationStatus.SUCCESS, response.getItems().get(1).getStatus());
            assertSame(Target.class, HotReloadClassRegistry.get(Target.class.getName()));
            assertSame(SecondTarget.class, HotReloadClassRegistry.get(SecondTarget.class.getName()));
        } finally {
            logger.close();
            HotReloadClassRegistry.clear();
        }
    }

    @Test void generationFallbackAfterJvmRejectionStillRequiresSpringBinding() throws Exception {
        FakeInstrumentation fake = new FakeInstrumentation(true, new Class<?>[]{AddMethodSample.class});
        fake.redefineFailure = new UnsupportedOperationException(
                "class redefinition failed: attempted to change the schema");
        AgentSessionLogger logger = logger("fallback-generation-binding");
        try {
            ReloadResponse response = new ClassBatchReloader(fake.proxy(), logger).reload(
                    new ClassReloadRequest("r-fallback", "token", Collections.singletonList(
                            new ClassUpdate(AddMethodSample.class.getName(), addMethodSampleSameShape()))));

            assertEquals(2, fake.redefineCalls.get(),
                    "the initial batch and isolated recovery both attempt redefine");
            assertEquals(OperationStatus.RESTART_REQUIRED, response.getStatus(), response.getMessage());
            assertEquals(ReloadErrorCode.SPRING_REBIND_INCOMPLETE, response.getErrorCode());
            assertNotNull(HotReloadClassRegistry.get(AddMethodSample.class.getName()));
        } finally {
            logger.close();
            HotReloadClassRegistry.clear();
        }
    }

    @Test void resolveLoadedTargetRejectsMultipleUnregisteredGenerations() {
        Class<?> original = AddMethodSample.class;
        byte[] bytecode = addMethodSampleWithExtraMethod();
        Class<?> first = new GenerationClassLoader(
                original.getClassLoader(), original.getName(), bytecode).defineTarget();
        Class<?> second = new GenerationClassLoader(
                original.getClassLoader(), original.getName(), bytecode).defineTarget();

        assertNull(ClassBatchReloader.resolveLoadedTarget(original.getName(),
                Arrays.asList(original, first, second), new LinkedHashSet<String>()));
    }

    @Test void ambiguousLoadedTargetsRequireRestartInsteadOfGuessing() throws Exception {
        Class<?> original = AddMethodSample.class;
        byte[] bytecode = addMethodSampleSameShape();
        Class<?> first = new GenerationClassLoader(
                original.getClassLoader(), original.getName(), bytecode).defineTarget();
        Class<?> second = new GenerationClassLoader(
                original.getClassLoader(), original.getName(), bytecode).defineTarget();
        FakeInstrumentation fake = new FakeInstrumentation(
                true, new Class<?>[]{original, first, second});
        AgentSessionLogger logger = logger("ambiguous-loaded-targets");
        try {
            ReloadResponse response = new ClassBatchReloader(fake.proxy(), logger).reload(
                    new ClassReloadRequest("ambiguous", "token", Collections.singletonList(
                            new ClassUpdate(original.getName(), bytecode))));

            assertEquals(OperationStatus.RESTART_REQUIRED, response.getStatus());
            assertEquals(ReloadErrorCode.CLASS_AMBIGUOUS, response.getErrorCode());
            assertEquals(OperationStatus.RESTART_REQUIRED, response.getItems().get(0).getStatus());
            assertEquals(0, fake.redefineCalls.get());
        } finally {
            logger.close();
            HotReloadClassRegistry.clear();
        }
    }

    @Test void definesNewClassInTheApplicationLoader() throws Exception {
        String binaryName = "dev.hotreload.agent.classes.BatchLookupDefinedSample";
        FakeInstrumentation fake = new FakeInstrumentation(true,
                new Class<?>[]{ClassBatchReloaderTest.class});
        AgentSessionLogger logger = logger("define-lookup");
        try {
            ReloadResponse response = new ClassBatchReloader(fake.proxy(), logger).reload(
                    new ClassReloadRequest("new", "token", Collections.singletonList(
                            new ClassUpdate(binaryName, namedEmptyClass(binaryName)))));

            assertEquals(OperationStatus.SUCCESS, response.getStatus(), response.getMessage());
            Class<?> defined = HotReloadClassRegistry.get(binaryName);
            assertNotNull(defined);
            assertSame(ClassBatchReloaderTest.class.getClassLoader(), defined.getClassLoader());
        } finally {
            logger.close();
            HotReloadClassRegistry.clear();
        }
    }


    private static ClassReloadRequest request(Class<?> type) {
        return new ClassReloadRequest("r", "token", Collections.singletonList(update(type)));
    }

    private static ClassUpdate update(Class<?> type) {
        return new ClassUpdate(type.getName(), bytes(type));
    }

    private static byte[] bytes(Class<?> type) {
        String internalName = type.getName().replace('.', '/');
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] namedEmptyClass(String binaryName) {
        String internalName = binaryName.replace('.', '/');
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] deleteMethodSampleWithoutHello() {
        String internal = Type.getInternalName(DeleteMethodSample.class);
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        MethodVisitor value = writer.visitMethod(Opcodes.ACC_PUBLIC, "value", "()I", null, null);
        value.visitCode();
        value.visitInsn(Opcodes.ICONST_1);
        value.visitInsn(Opcodes.IRETURN);
        value.visitMaxs(1, 1);
        value.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] deleteFieldSampleWithoutField() {
        String internal = Type.getInternalName(DeleteFieldSample.class);
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        MethodVisitor hello = writer.visitMethod(Opcodes.ACC_PUBLIC, "hello", "()Ljava/lang/String;", null, null);
        hello.visitCode();
        hello.visitLdcInsn("ok");
        hello.visitInsn(Opcodes.ARETURN);
        hello.visitMaxs(1, 1);
        hello.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] addMethodSampleWithExtraMethodAndField() {
        String internal = Type.getInternalName(AddMethodSample.class);
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
        FieldVisitor field = writer.visitField(Opcodes.ACC_PRIVATE, "extra", "I", null, null);
        field.visitEnd();
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        MethodVisitor hello = writer.visitMethod(Opcodes.ACC_PUBLIC, "hello", "()Ljava/lang/String;", null, null);
        hello.visitCode();
        hello.visitLdcInsn("ok");
        hello.visitInsn(Opcodes.ARETURN);
        hello.visitMaxs(1, 1);
        hello.visitEnd();
        MethodVisitor added = writer.visitMethod(Opcodes.ACC_PUBLIC, "added", "()V", null, null);
        added.visitCode();
        added.visitInsn(Opcodes.RETURN);
        added.visitMaxs(0, 1);
        added.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] addMethodSampleSameShape() {
        String internal = Type.getInternalName(AddMethodSample.class);
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        MethodVisitor hello = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "hello", "()Ljava/lang/String;", null, null);
        hello.visitCode();
        hello.visitLdcInsn("updated");
        hello.visitInsn(Opcodes.ARETURN);
        hello.visitMaxs(1, 1);
        hello.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] addMethodSampleWithExtraMethod() {
        String internal = Type.getInternalName(AddMethodSample.class);
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        MethodVisitor hello = writer.visitMethod(Opcodes.ACC_PUBLIC, "hello", "()Ljava/lang/String;", null, null);
        hello.visitCode();
        hello.visitLdcInsn("ok");
        hello.visitInsn(Opcodes.ARETURN);
        hello.visitMaxs(1, 1);
        hello.visitEnd();
        MethodVisitor added = writer.visitMethod(Opcodes.ACC_PUBLIC, "added", "()V", null, null);
        added.visitCode();
        added.visitInsn(Opcodes.RETURN);
        added.visitMaxs(0, 1);
        added.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class Target { }
    private static final class SecondTarget { }

    public static class AddMethodSample {
        public String hello() { return "ok"; }
    }

    public static class DeleteMethodSample {
        public String hello() { return "ok"; }
        public int value() { return 1; }
    }

    public static class DeleteFieldSample {
        private int value;
        public String hello() { return "ok"; }
    }

    private static final class FakeInstrumentation {
        private final boolean supported;
        private final Class<?>[] loaded;
        private final AtomicInteger loadedClassCalls = new AtomicInteger();
        private final AtomicInteger redefineCalls = new AtomicInteger();
        private final AtomicReference<ClassDefinition[]> definitions = new AtomicReference<ClassDefinition[]>();
        private volatile boolean modifiable = true;
        private volatile Throwable redefineFailure;
        private volatile Throwable batchRedefineFailure;

        private FakeInstrumentation(boolean supported, Class<?>[] loaded) {
            this.supported = supported;
            this.loaded = loaded;
        }

        private Instrumentation proxy() {
            return (Instrumentation) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Instrumentation.class}, (proxy, method, arguments) -> {
                        if ("isRedefineClassesSupported".equals(method.getName())) return supported;
                        if ("getAllLoadedClasses".equals(method.getName())) {
                            loadedClassCalls.incrementAndGet();
                            return loaded;
                        }
                        if ("isModifiableClass".equals(method.getName())) return modifiable;
                        if ("redefineClasses".equals(method.getName())) {
                            redefineCalls.incrementAndGet();
                            definitions.set((ClassDefinition[]) arguments[0]);
                            if (batchRedefineFailure != null && definitions.get().length > 1) {
                                throw batchRedefineFailure;
                            }
                            if (redefineFailure != null) throw redefineFailure;
                            return null;
                        }
                        return primitiveDefault(method.getReturnType());
                    });
        }
    }

    private static Object primitiveDefault(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return (char) 0;
        return null;
    }
}


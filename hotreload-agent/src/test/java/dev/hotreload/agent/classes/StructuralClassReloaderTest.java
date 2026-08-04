package dev.hotreload.agent.classes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StructuralClassReloaderTest {
    @AfterEach
    void clearRegistry() {
        HotReloadClassRegistry.clear();
    }

    @Test
    void generationLoaderDefinesIndependentClassVersion() {
        byte[] bytecode = tinyClassBytes("dev.hotreload.agent.classes.GenSampleA");
        GenerationClassLoader loader = new GenerationClassLoader(
                StructuralClassReloaderTest.class.getClassLoader(),
                "dev.hotreload.agent.classes.GenSampleA",
                bytecode);
        Class<?> defined = loader.defineTarget();
        assertNotNull(defined);
        assertEquals("dev.hotreload.agent.classes.GenSampleA", defined.getName());
        HotReloadClassRegistry.put(defined.getName(), defined);
        assertEquals(defined, HotReloadClassRegistry.get(defined.getName()));
        assertTrue(HotReloadClassRegistry.generation(defined.getName()) >= 1);
    }

    @Test
    void generationLoaderServesBytecodeAsResource() throws Exception {
        byte[] bytecode = tinyClassBytes("dev.hotreload.agent.classes.GenResourceSample");
        GenerationClassLoader loader = new GenerationClassLoader(
                StructuralClassReloaderTest.class.getClassLoader(),
                "dev.hotreload.agent.classes.GenResourceSample",
                bytecode);
        Class<?> defined = loader.defineTarget();
        // Spring's LocalVariableTableParameterNameDiscoverer reads bytes exactly this way.
        java.io.InputStream stream = defined.getResourceAsStream("GenResourceSample.class");
        assertNotNull(stream, "generation class bytes must be readable for parameter name discovery");
        assertArrayEquals(bytecode, readAll(stream));
        java.net.URL url = loader.getResource("dev/hotreload/agent/classes/GenResourceSample.class");
        assertNotNull(url);
        assertArrayEquals(bytecode, readAll(url.openStream()));
        // Unknown resources still delegate parent-first.
        assertNull(loader.getResource("dev/hotreload/agent/classes/NoSuchResource.class"));
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        in.close();
        return out.toByteArray();
    }

    @Test
    void defineNewFallsBackWithoutInstrumentationRedefine() {
        byte[] bytecode = tinyClassBytes("dev.hotreload.agent.classes.GenSampleB");
        GenerationClassLoader loader = new GenerationClassLoader(
                getClass().getClassLoader(),
                "dev.hotreload.agent.classes.GenSampleB",
                bytecode);
        Class<?> cls = loader.defineTarget();
        RuntimeAnnotationIndex.update(cls, bytecode);
        assertNotNull(RuntimeAnnotationIndex.get(cls));
    }

    @Test
    void enhancedCapableReloaderRedefinesStructureInPlace() {
        final AtomicInteger redefineCalls = new AtomicInteger();
        Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
                StructuralClassReloaderTest.class.getClassLoader(),
                new Class<?>[]{Instrumentation.class},
                (proxy, method, args) -> {
                    if ("isRedefineClassesSupported".equals(method.getName())) return true;
                    if ("isModifiableClass".equals(method.getName())) return true;
                    if ("redefineClasses".equals(method.getName())) {
                        redefineCalls.incrementAndGet();
                        return null; // enhanced JVM accepts the schema change
                    }
                    if ("getAllLoadedClasses".equals(method.getName())) return new Class<?>[0];
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    return null;
                });
        StructuralClassReloader reloader = new StructuralClassReloader(instrumentation, true);
        StructuralClassReloader.Result result =
                reloader.reloadExisting(Sample.class, sampleWithExtraMethod(), true);
        assertTrue(result.success, result.detail);
        // E2: identity kept — no generation subclass, live class IS the original class.
        assertEquals("redefined", result.mode, result.detail);
        assertSame(Sample.class, result.liveClass);
        assertEquals(1, redefineCalls.get());
        assertTrue(result.detail.contains("enhanced_redefine"), result.detail);
    }

    @Test
    void enhancedRejectedShapeFallsBackToGeneration() {
        // Even DCEVM rejects some shapes; the reloader must degrade to the generation path.
        Instrumentation instrumentation = failingInstrumentation(
                new UnsupportedOperationException("class redefinition failed: hierarchy change"));
        StructuralClassReloader reloader = new StructuralClassReloader(instrumentation, true);
        StructuralClassReloader.Result result =
                reloader.reloadExisting(Sample.class, sampleWithExtraMethod(), true);
        assertTrue(result.success, result.detail);
        assertEquals("generation", result.mode, result.detail);
        assertTrue(result.detail.contains("enhanced_rejected"), result.detail);
    }

    @Test
    void reloadExistingFallsBackToGenerationWhenJvmRejectsAddMethod() {
        Instrumentation instrumentation = failingInstrumentation(
                new UnsupportedOperationException("class redefinition failed: attempted to add a method"));
        StructuralClassReloader reloader = new StructuralClassReloader(instrumentation);
        StructuralClassReloader.Result result = reloader.reloadExisting(Sample.class, sampleWithExtraMethod());
        assertTrue(result.success, result.detail);
        assertEquals("generation", result.mode, result.detail);
        assertNotNull(result.liveClass);
        assertNotSame(Sample.class, result.liveClass);
        assertTrue(Sample.class.isAssignableFrom(result.liveClass), result.liveClass.getName());
        assertTrue(result.liveClass.getName().contains("__HrGen"), result.liveClass.getName());
        assertEquals(result.liveClass, HotReloadClassRegistry.get(Sample.class.getName()));
    }

    @Test
    void reloadExistingFallsBackToGenerationWhenJvmRejectsDeleteMethodCn() {
        Instrumentation instrumentation = failingInstrumentation(
                new UnsupportedOperationException("虚拟机不支持的操作: delete method not implemented"));
        StructuralClassReloader reloader = new StructuralClassReloader(instrumentation);
        // Force unknown analysis path by using compatible-looking bytecode that still fails redefine.
        StructuralClassReloader.Result result = reloader.reloadExisting(Sample.class, sampleSameShape());
        assertTrue(result.success, result.detail);
        assertEquals("generation", result.mode, result.detail);
        assertTrue(result.detail.contains("redefine_rejected") || result.detail.contains("generation"), result.detail);
    }

    @Test
    void reloadExistingFallsBackToGenerationWhenJvmRejectsSchemaChange() {
        Instrumentation instrumentation = failingInstrumentation(
                new UnsupportedOperationException("class redefinition failed: attempted to change the schema"));
        StructuralClassReloader reloader = new StructuralClassReloader(instrumentation);
        StructuralClassReloader.Result result = reloader.reloadExisting(Sample.class, sampleWithExtraMethod());
        assertTrue(result.success, result.detail);
        assertEquals("generation", result.mode);
    }

    @Test
    void skipsRedefineWhenKnownStructureChange() {
        final AtomicInteger redefineCalls = new AtomicInteger();
        Instrumentation instrumentation = countingInstrumentation(redefineCalls,
                new UnsupportedOperationException("add method not implemented"));
        StructuralClassReloader reloader = new StructuralClassReloader(instrumentation);
        StructuralClassReloader.Result result =
                reloader.reloadExisting(Sample.class, sampleWithExtraMethod(), true);
        assertTrue(result.success, result.detail);
        assertEquals("generation", result.mode);
        assertEquals(0, redefineCalls.get(), "known structure should not call redefineClasses");
        assertTrue(result.detail.contains("known_structure"), result.detail);
    }


    @Test
    void skipsRedefineWhenAddFieldDetected() {
        final AtomicInteger redefineCalls = new AtomicInteger();
        Instrumentation instrumentation = countingInstrumentation(redefineCalls,
                new UnsupportedOperationException("add field not implemented"));
        StructuralClassReloader reloader = new StructuralClassReloader(instrumentation);
        StructuralClassReloader.Result result =
                reloader.reloadExisting(Sample.class, sampleWithExtraMethodAndField(), false);
        assertTrue(result.success, result.detail);
        assertEquals("generation", result.mode, result.detail);
        assertEquals(0, redefineCalls.get(), "detected add field must not call redefineClasses");
    }

    @Test
    void skipsRedefineWhenAddMethodDetected() {
        final AtomicInteger redefineCalls = new AtomicInteger();
        Instrumentation instrumentation = countingInstrumentation(redefineCalls,
                new UnsupportedOperationException("add method not implemented"));
        StructuralClassReloader reloader = new StructuralClassReloader(instrumentation);
        StructuralClassReloader.Result result =
                reloader.reloadExisting(Sample.class, sampleWithExtraMethod(), false);
        assertTrue(result.success, result.detail);
        assertEquals("generation", result.mode, result.detail);
        assertEquals(0, redefineCalls.get(), "detected add method must not call redefineClasses");
    }

    @Test
    void deletedMethodRequiresRestartInsteadOfAnInexactGeneration() {
        final AtomicInteger redefineCalls = new AtomicInteger();
        Instrumentation instrumentation = countingInstrumentation(redefineCalls,
                new UnsupportedOperationException("delete method not implemented"));
        StructuralClassReloader reloader = new StructuralClassReloader(instrumentation);
        StructuralClassReloader.Result result =
                reloader.reloadExisting(SampleWithFieldAndMethods.class, sampleWithoutHelloMethod(), false);
        assertFalse(result.success, result.detail);
        assertEquals("failed", result.mode, result.detail);
        assertTrue(result.detail.contains("generation_cannot_remove_or_change_methods"), result.detail);
        assertEquals(0, redefineCalls.get(), "detected delete method must not call redefineClasses");
    }

    @Test
    void deletedFieldRequiresRestartInsteadOfAnInexactGeneration() {
        final AtomicInteger redefineCalls = new AtomicInteger();
        Instrumentation instrumentation = countingInstrumentation(redefineCalls,
                new UnsupportedOperationException("delete field not implemented"));
        StructuralClassReloader reloader = new StructuralClassReloader(instrumentation);
        StructuralClassReloader.Result result =
                reloader.reloadExisting(SampleWithFieldAndMethods.class, sampleWithoutField(), false);
        assertFalse(result.success, result.detail);
        assertEquals("failed", result.mode, result.detail);
        assertTrue(result.detail.contains("generation_cannot_remove_or_retype_fields"), result.detail);
        assertEquals(0, redefineCalls.get(), "detected delete field must not call redefineClasses");
    }

    @Test
    void changedSuperclassRequiresRestartInsteadOfGeneration() {
        StructuralClassReloader reloader = new StructuralClassReloader(
                failingInstrumentation(new UnsupportedOperationException("superclass change")));
        StructuralClassReloader.Result result = reloader.reloadExisting(
                Sample.class, sampleWithHierarchy(Sample.class, "java/util/ArrayList", null), true);

        assertFalse(result.success, result.detail);
        assertEquals("failed", result.mode);
        assertTrue(result.detail.contains("superclass_change_requires_restart"), result.detail);
    }

    @Test
    void removedInterfaceRequiresRestartInsteadOfGeneration() {
        StructuralClassReloader reloader = new StructuralClassReloader(
                failingInstrumentation(new UnsupportedOperationException("interface change")));
        StructuralClassReloader.Result result = reloader.reloadExisting(
                InterfaceSample.class,
                sampleWithHierarchy(InterfaceSample.class, "java/lang/Object", new String[0]), true);

        assertFalse(result.success, result.detail);
        assertEquals("failed", result.mode);
        assertTrue(result.detail.contains("removed_interface_requires_restart"), result.detail);
    }

    @Test
    void successiveGenerationReloadsAdvanceRegistry() {
        Instrumentation instrumentation = failingInstrumentation(
                new UnsupportedOperationException("delete method not implemented"));
        StructuralClassReloader reloader = new StructuralClassReloader(instrumentation);
        StructuralClassReloader.Result first =
                reloader.reloadExisting(Sample.class, sampleWithExtraMethod(), true);
        assertTrue(first.success, first.detail);
        Class<?> gen1 = first.liveClass;
        StructuralClassReloader.Result second =
                reloader.reloadExisting(gen1, sampleWithExtraMethodAndField(), true);
        assertTrue(second.success, second.detail);
        assertNotSame(gen1, second.liveClass);
        assertEquals(second.liveClass, HotReloadClassRegistry.get(Sample.class.getName()));
        assertTrue(HotReloadClassRegistry.generation(Sample.class.getName()) >= 2);
    }

    @Test
    void structureTokenDetectsCnAndEnDeleteMessages() {
        assertTrue(StructuralClassReloader.containsStructureToken(
                "虚拟机不支持的操作: delete method not implemented"));
        assertTrue(StructuralClassReloader.containsStructureToken(
                "class redefinition failed: attempted to delete a method"));
        assertTrue(StructuralClassReloader.containsStructureToken(
                "UnsupportedOperationException: add method not implemented"));
        assertTrue(StructuralClassReloader.isLikelyStructureFailure(
                new UnsupportedOperationException("虚拟机不支持的操作: delete method not implemented")));
    }

    @Test
    void finalClassStructureChangeNeverReportsUnassignableSuccess() {
        StructuralClassReloader reloader = new StructuralClassReloader(
                failingInstrumentation(new UnsupportedOperationException("add method not implemented")));
        StructuralClassReloader.Result result = reloader.reloadExisting(
                FinalSample.class, sampleShape(FinalSample.class, false, true, true), true);

        assertFalse(result.success, result.detail);
        assertEquals("failed", result.mode);
        assertSame(FinalSample.class, result.liveClass);
        assertTrue(result.detail.contains("final_class_cannot_subclass"), result.detail);
        assertNull(HotReloadClassRegistry.get(FinalSample.class.getName()));
    }

    public static class Sample {
        public String hello() { return "ok"; }
    }

    public static class SampleWithFieldAndMethods {
        private int value;
        public String hello() { return "ok"; }
        public int value() { return value; }
    }

    interface Marker { }

    public static class InterfaceSample implements Marker {
        public String hello() { return "ok"; }
    }

    public static final class FinalSample {
        public String hello() { return "ok"; }
    }

    private static Instrumentation failingInstrumentation(final Throwable failure) {
        return countingInstrumentation(new AtomicInteger(), failure);
    }

    private static Instrumentation countingInstrumentation(final AtomicInteger redefineCalls,
                                                           final Throwable failure) {
        return (Instrumentation) Proxy.newProxyInstance(
                StructuralClassReloaderTest.class.getClassLoader(),
                new Class<?>[]{Instrumentation.class},
                (proxy, method, args) -> {
                    if ("isRedefineClassesSupported".equals(method.getName())) return true;
                    if ("isModifiableClass".equals(method.getName())) return true;
                    if ("redefineClasses".equals(method.getName())) {
                        redefineCalls.incrementAndGet();
                        throw failure;
                    }
                    if ("getAllLoadedClasses".equals(method.getName())) return new Class<?>[0];
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    return null;
                });
    }

    private static byte[] sampleSameShape() {
        return sampleShape(Sample.class, false, false, true);
    }

    private static byte[] sampleWithExtraMethod() {
        return sampleShape(Sample.class, false, true, true);
    }

    private static byte[] sampleWithExtraMethodAndField() {
        return sampleShape(Sample.class, true, true, true);
    }

    private static byte[] sampleWithoutHelloMethod() {
        // keep ctor + value(), drop hello()
        String internal = Type.getInternalName(SampleWithFieldAndMethods.class);
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
        FieldVisitor field = cw.visitField(Opcodes.ACC_PRIVATE, "value", "I", null, null);
        field.visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        MethodVisitor value = cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()I", null, null);
        value.visitCode();
        value.visitVarInsn(Opcodes.ALOAD, 0);
        value.visitFieldInsn(Opcodes.GETFIELD, internal, "value", "I");
        value.visitInsn(Opcodes.IRETURN);
        value.visitMaxs(1, 1);
        value.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] sampleWithoutField() {
        String internal = Type.getInternalName(SampleWithFieldAndMethods.class);
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        MethodVisitor hello = cw.visitMethod(Opcodes.ACC_PUBLIC, "hello", "()Ljava/lang/String;", null, null);
        hello.visitCode();
        hello.visitLdcInsn("ok");
        hello.visitInsn(Opcodes.ARETURN);
        hello.visitMaxs(1, 1);
        hello.visitEnd();
        MethodVisitor value = cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()I", null, null);
        value.visitCode();
        value.visitInsn(Opcodes.ICONST_0);
        value.visitInsn(Opcodes.IRETURN);
        value.visitMaxs(1, 1);
        value.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] sampleWithHierarchy(Class<?> type, String superInternal, String[] interfaces) {
        String internal = Type.getInternalName(type);
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internal, null, superInternal, interfaces);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superInternal, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        MethodVisitor hello = cw.visitMethod(
                Opcodes.ACC_PUBLIC, "hello", "()Ljava/lang/String;", null, null);
        hello.visitCode();
        hello.visitLdcInsn("ok");
        hello.visitInsn(Opcodes.ARETURN);
        hello.visitMaxs(1, 1);
        hello.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] sampleShape(Class<?> type, boolean withField, boolean withAdded, boolean withHello) {
        String internal = Type.getInternalName(type);
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
        if (withField) {
            FieldVisitor field = cw.visitField(Opcodes.ACC_PRIVATE, "extra", "I", null, null);
            field.visitEnd();
        }
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        if (withHello) {
            MethodVisitor hello = cw.visitMethod(Opcodes.ACC_PUBLIC, "hello", "()Ljava/lang/String;", null, null);
            hello.visitCode();
            hello.visitLdcInsn("ok");
            hello.visitInsn(Opcodes.ARETURN);
            hello.visitMaxs(1, 1);
            hello.visitEnd();
        }
        if (withAdded) {
            MethodVisitor added = cw.visitMethod(Opcodes.ACC_PUBLIC, "added", "()V", null, null);
            added.visitCode();
            added.visitInsn(Opcodes.RETURN);
            added.visitMaxs(0, 1);
            added.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] tinyClassBytes(String binaryName) {
        String internal = binaryName.replace('.', '/');
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}

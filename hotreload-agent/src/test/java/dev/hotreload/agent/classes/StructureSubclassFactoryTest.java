package dev.hotreload.agent.classes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StructureSubclassFactoryTest {
    @AfterEach
    void clear() {
        HotReloadClassRegistry.clear();
    }

    public static class BaseController {
        public String hello() { return "ok"; }
    }

    public static class PrivateFieldController {
        private Object service = "injected";
        private int counter = 7;
        String packageValue = "pkg";

        public String readService() {
            return String.valueOf(service);
        }

        public int readCounter() {
            return counter;
        }

        public String readPackage() {
            return packageValue;
        }

        public void writeService(Object value) {
            this.service = value;
        }
    }

    @Test
    void builtSubclassIsAssignableAndKeepsNewMethod() throws Exception {
        byte[] next = withAddedMethod(BaseController.class);
        StructureSubclassFactory.Built built = StructureSubclassFactory.build(BaseController.class, next);
        assertTrue(built.binaryName.contains("__HrGen"));
        GenerationClassLoader loader = new GenerationClassLoader(
                BaseController.class.getClassLoader(), built.binaryName, built.bytecode);
        Class<?> gen = loader.defineTarget();
        assertTrue(BaseController.class.isAssignableFrom(gen));
        assertNotEquals(BaseController.class.getName(), gen.getName());
        Object instance = gen.getDeclaredConstructor().newInstance();
        assertTrue(instance instanceof BaseController);
        assertEquals("ok", gen.getMethod("hello").invoke(instance));
        assertNotNull(gen.getMethod("added"));
        gen.getMethod("added").invoke(instance);
    }

    @Test
    void structuralReloadUsesAssignableSubclassAndAvoidsSameBinaryName() throws Exception {
        AtomicInteger redefineCalls = new AtomicInteger();
        Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Instrumentation.class},
                (proxy, method, args) -> {
                    if ("isRedefineClassesSupported".equals(method.getName())) return true;
                    if ("isModifiableClass".equals(method.getName())) return true;
                    if ("redefineClasses".equals(method.getName())) {
                        redefineCalls.incrementAndGet();
                        throw new UnsupportedOperationException("add method not implemented");
                    }
                    if ("getAllLoadedClasses".equals(method.getName())) return new Class<?>[0];
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    return null;
                });
        StructuralClassReloader reloader = new StructuralClassReloader(instrumentation);
        StructuralClassReloader.Result result =
                reloader.reloadExisting(BaseController.class, withAddedMethod(BaseController.class), true);
        assertTrue(result.success, result.detail);
        assertEquals("generation", result.mode, result.detail);
        assertEquals(0, redefineCalls.get());
        assertTrue(BaseController.class.isAssignableFrom(result.liveClass), result.liveClass.getName());
        assertTrue(result.liveClass.getName().contains("__HrGen"), result.liveClass.getName());
        assertTrue(result.detail.contains("assignable=true"), result.detail);
        assertSame(result.liveClass, HotReloadClassRegistry.get(BaseController.class.getName()));
        Object asBase = result.liveClass.cast(
                result.liveClass.getDeclaredConstructor().newInstance());
        BaseController casted = (BaseController) result.liveClass.getDeclaredConstructor().newInstance();
        assertEquals("ok", casted.hello());
    }

    @Test
    void subclassCanReadAndWritePrivateParentFieldsWithoutIllegalAccessError() throws Exception {
        byte[] next = withAddedMethodAndPrivateFieldUse(PrivateFieldController.class);
        StructureSubclassFactory.Built built =
                StructureSubclassFactory.build(PrivateFieldController.class, next);
        assertTrue(countGetFieldToOwner(built.bytecode, Type.getInternalName(PrivateFieldController.class)) == 0,
                "private/package parent field GETFIELD must be rewritten");
        assertTrue(countBridgeCalls(built.bytecode) > 0, "bridge calls expected");
        assertTrue(built.bridgedFields > 0, "bridgedFields=" + built.bridgedFields);

        GenerationClassLoader loader = new GenerationClassLoader(
                PrivateFieldController.class.getClassLoader(), built.binaryName, built.bytecode);
        Class<?> gen = loader.defineTarget();
        Object instance = gen.getDeclaredConstructor().newInstance();
        assertTrue(instance instanceof PrivateFieldController);

        // Seed parent private fields on the subclass instance.
        Field service = PrivateFieldController.class.getDeclaredField("service");
        service.setAccessible(true);
        service.set(instance, "live-service");
        Field counter = PrivateFieldController.class.getDeclaredField("counter");
        counter.setAccessible(true);
        counter.setInt(instance, 42);
        Field packageValue = PrivateFieldController.class.getDeclaredField("packageValue");
        packageValue.setAccessible(true);
        packageValue.set(instance, "live-pkg");

        assertEquals("live-service", gen.getMethod("readService").invoke(instance));
        assertEquals(42, gen.getMethod("readCounter").invoke(instance));
        assertEquals("live-pkg", gen.getMethod("readPackage").invoke(instance));

        gen.getMethod("writeService", Object.class).invoke(instance, "updated");
        assertEquals("updated", service.get(instance));
        assertEquals("updated", gen.getMethod("readService").invoke(instance));

        // Newly added method on generation class also reads private parent field.
        assertEquals("updated", gen.getMethod("addedRead").invoke(instance));
    }

    @Test
    void structuralReloadPrivateFieldControllerKeepsInjectedFieldReadable() throws Exception {
        Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Instrumentation.class},
                (proxy, method, args) -> {
                    if ("isRedefineClassesSupported".equals(method.getName())) return true;
                    if ("isModifiableClass".equals(method.getName())) return true;
                    if ("redefineClasses".equals(method.getName())) {
                        throw new UnsupportedOperationException("add method not implemented");
                    }
                    if ("getAllLoadedClasses".equals(method.getName())) return new Class<?>[0];
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    return null;
                });
        StructuralClassReloader reloader = new StructuralClassReloader(instrumentation);
        StructuralClassReloader.Result result = reloader.reloadExisting(
                PrivateFieldController.class,
                withAddedMethodAndPrivateFieldUse(PrivateFieldController.class),
                true);
        assertTrue(result.success, result.detail);
        Object instance = result.liveClass.getDeclaredConstructor().newInstance();
        Field service = PrivateFieldController.class.getDeclaredField("service");
        service.setAccessible(true);
        service.set(instance, "svc");
        assertEquals("svc", result.liveClass.getMethod("readService").invoke(instance));
        assertEquals("svc", result.liveClass.getMethod("addedRead").invoke(instance));
    }

    private static int countGetFieldToOwner(byte[] bytecode, final String owner) {
        final AtomicInteger count = new AtomicInteger();
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String fieldOwner, String fieldName, String fieldDesc) {
                        if ((opcode == Opcodes.GETFIELD || opcode == Opcodes.PUTFIELD) && owner.equals(fieldOwner)) {
                            count.incrementAndGet();
                        }
                    }
                };
            }
        }, 0);
        return count.get();
    }

    private static int countBridgeCalls(byte[] bytecode) {
        final String bridge = "dev/hotreload/bootstrap/StructureAccessBridge";
        final AtomicInteger count = new AtomicInteger();
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean isInterface) {
                        if (bridge.equals(owner)) {
                            count.incrementAndGet();
                        }
                    }
                };
            }
        }, 0);
        return count.get();
    }

    private static byte[] withAddedMethod(Class<?> type) {
        String internal = Type.getInternalName(type);
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
        MethodVisitor added = cw.visitMethod(Opcodes.ACC_PUBLIC, "added", "()V", null, null);
        added.visitCode();
        added.visitInsn(Opcodes.RETURN);
        added.visitMaxs(0, 1);
        added.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Builds a class file shaped like a reloaded controller: same private fields/methods plus one new method.
     */
    private static byte[] withAddedMethodAndPrivateFieldUse(Class<?> type) throws Exception {
        String internal = Type.getInternalName(type);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);

        FieldVisitor service = cw.visitField(Opcodes.ACC_PRIVATE, "service", "Ljava/lang/Object;", null, null);
        service.visitEnd();
        FieldVisitor counter = cw.visitField(Opcodes.ACC_PRIVATE, "counter", "I", null, null);
        counter.visitEnd();
        FieldVisitor packageValue = cw.visitField(0, "packageValue", "Ljava/lang/String;", null, null);
        packageValue.visitEnd();

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitLdcInsn("injected");
        init.visitFieldInsn(Opcodes.PUTFIELD, internal, "service", "Ljava/lang/Object;");
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitIntInsn(Opcodes.BIPUSH, 7);
        init.visitFieldInsn(Opcodes.PUTFIELD, internal, "counter", "I");
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitLdcInsn("pkg");
        init.visitFieldInsn(Opcodes.PUTFIELD, internal, "packageValue", "Ljava/lang/String;");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor readService = cw.visitMethod(Opcodes.ACC_PUBLIC, "readService", "()Ljava/lang/String;", null, null);
        readService.visitCode();
        readService.visitVarInsn(Opcodes.ALOAD, 0);
        readService.visitFieldInsn(Opcodes.GETFIELD, internal, "service", "Ljava/lang/Object;");
        readService.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf",
                "(Ljava/lang/Object;)Ljava/lang/String;", false);
        readService.visitInsn(Opcodes.ARETURN);
        readService.visitMaxs(0, 0);
        readService.visitEnd();

        MethodVisitor readCounter = cw.visitMethod(Opcodes.ACC_PUBLIC, "readCounter", "()I", null, null);
        readCounter.visitCode();
        readCounter.visitVarInsn(Opcodes.ALOAD, 0);
        readCounter.visitFieldInsn(Opcodes.GETFIELD, internal, "counter", "I");
        readCounter.visitInsn(Opcodes.IRETURN);
        readCounter.visitMaxs(0, 0);
        readCounter.visitEnd();

        MethodVisitor readPackage = cw.visitMethod(Opcodes.ACC_PUBLIC, "readPackage", "()Ljava/lang/String;", null, null);
        readPackage.visitCode();
        readPackage.visitVarInsn(Opcodes.ALOAD, 0);
        readPackage.visitFieldInsn(Opcodes.GETFIELD, internal, "packageValue", "Ljava/lang/String;");
        readPackage.visitInsn(Opcodes.ARETURN);
        readPackage.visitMaxs(0, 0);
        readPackage.visitEnd();

        MethodVisitor writeService = cw.visitMethod(Opcodes.ACC_PUBLIC, "writeService", "(Ljava/lang/Object;)V", null, null);
        writeService.visitCode();
        writeService.visitVarInsn(Opcodes.ALOAD, 0);
        writeService.visitVarInsn(Opcodes.ALOAD, 1);
        writeService.visitFieldInsn(Opcodes.PUTFIELD, internal, "service", "Ljava/lang/Object;");
        writeService.visitInsn(Opcodes.RETURN);
        writeService.visitMaxs(0, 0);
        writeService.visitEnd();

        MethodVisitor addedRead = cw.visitMethod(Opcodes.ACC_PUBLIC, "addedRead", "()Ljava/lang/String;", null, null);
        addedRead.visitCode();
        addedRead.visitVarInsn(Opcodes.ALOAD, 0);
        addedRead.visitFieldInsn(Opcodes.GETFIELD, internal, "service", "Ljava/lang/Object;");
        addedRead.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf",
                "(Ljava/lang/Object;)Ljava/lang/String;", false);
        addedRead.visitInsn(Opcodes.ARETURN);
        addedRead.visitMaxs(0, 0);
        addedRead.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void subclassShadowsFieldWhenTypeChanges() throws Exception {
        byte[] next = withCounterTypeChangedToString(PrivateFieldController.class);
        StructureSubclassFactory.Built built =
                StructureSubclassFactory.build(PrivateFieldController.class, next);
        GenerationClassLoader loader = new GenerationClassLoader(
                PrivateFieldController.class.getClassLoader(), built.binaryName, built.bytecode);
        Class<?> gen = loader.defineTarget();
        // Type-changed field must be redeclared on the generation subclass with the new type.
        assertEquals(String.class, gen.getDeclaredField("counter").getType());
        Object instance = gen.getDeclaredConstructor().newInstance();
        // New code reads the shadowed String field: no CCE against the parent int field.
        assertEquals("7txt", gen.getMethod("readCounterText").invoke(instance));
    }

    /** Same class shape but counter changed from int to String (field type change). */
    private static byte[] withCounterTypeChangedToString(Class<?> type) {
        String internal = Type.getInternalName(type);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
        FieldVisitor counter = cw.visitField(Opcodes.ACC_PRIVATE, "counter", "Ljava/lang/String;", null, null);
        counter.visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitLdcInsn("7txt");
        init.visitFieldInsn(Opcodes.PUTFIELD, internal, "counter", "Ljava/lang/String;");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor read = cw.visitMethod(Opcodes.ACC_PUBLIC, "readCounterText", "()Ljava/lang/String;", null, null);
        read.visitCode();
        read.visitVarInsn(Opcodes.ALOAD, 0);
        read.visitFieldInsn(Opcodes.GETFIELD, internal, "counter", "Ljava/lang/String;");
        read.visitInsn(Opcodes.ARETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void generationNameAvoidsSpringUserClassStripAndKeepsNewMappingMethod() throws Exception {
        byte[] next = withAddedMethod(BaseController.class);
        StructureSubclassFactory.Built built = StructureSubclassFactory.build(BaseController.class, next);
        assertFalse(built.binaryName.contains("$$"), built.binaryName);
        assertTrue(built.binaryName.contains("__HrGen"), built.binaryName);
        GenerationClassLoader loader = new GenerationClassLoader(
                BaseController.class.getClassLoader(), built.binaryName, built.bytecode);
        Class<?> gen = loader.defineTarget();
        // Spring ClassUtils.getUserClass strips names containing "$$" only.
        Class<?> user = gen;
        while (user != null && user.getName().contains("$$")) {
            user = user.getSuperclass();
        }
        assertSame(gen, user, "generation class must survive Spring getUserClass");
        boolean foundAdded = false;
        for (java.lang.reflect.Method m : user.getDeclaredMethods()) {
            if ("added".equals(m.getName())) {
                foundAdded = true;
                break;
            }
        }
        assertTrue(foundAdded, "new method must be visible for RequestMapping detectHandlerMethods");
    }

}

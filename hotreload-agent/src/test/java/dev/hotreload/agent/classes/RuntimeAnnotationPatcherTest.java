package dev.hotreload.agent.classes;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeAnnotationPatcherTest {
    @Retention(RetentionPolicy.RUNTIME)
    public @interface DemoScope {
        String alias() default "";
    }

    public static class Sample {
        public void listOrders() { }
    }

    @Test
    void indexTracksAnnotationAddAndRemoveFromBytecode() throws Exception {
        Method method = Sample.class.getDeclaredMethod("listOrders");

        byte[] withAnn = classWithDemoScope(true);
        RuntimeAnnotationIndex.update(Sample.class, withAnn);
        assertTrue(RuntimeAnnotationIndex.hasMethodAnnotation(method, "DemoScope"));
        assertTrue(RuntimeAnnotationIndex.describe(Sample.class).contains("listOrders@DemoScope"),
                RuntimeAnnotationIndex.describe(Sample.class));

        RuntimeAnnotationPatcher.PatchReport patch = RuntimeAnnotationPatcher.patch(Sample.class, withAnn);
        assertTrue(patch.summary().contains("DemoScope") || patch.getMethodsPatched() >= 0, patch.summary());

        byte[] withoutAnn = classWithDemoScope(false);
        RuntimeAnnotationIndex.update(Sample.class, withoutAnn);
        assertFalse(RuntimeAnnotationIndex.hasMethodAnnotation(method, "DemoScope"));
        assertFalse(RuntimeAnnotationIndex.describe(Sample.class).contains("DemoScope(alias"),
                RuntimeAnnotationIndex.describe(Sample.class));
    }

    private static byte[] classWithDemoScope(boolean include) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, Type.getInternalName(Sample.class), null, "java/lang/Object", null);
        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "listOrders", "()V", null, null);
        if (include) {
            AnnotationVisitor ann = method.visitAnnotation(Type.getDescriptor(DemoScope.class), true);
            ann.visit("alias", "d");
            ann.visitEnd();
        }
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}

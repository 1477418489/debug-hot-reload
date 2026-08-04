package dev.hotreload.agent.classes;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.junit.jupiter.api.Assertions.*;

class ClassChangeAnalyzerTest {
    @Test void detectsAnnotationAndStructureChanges() {
        byte[] base = classBytes(false, false, false);
        byte[] annotated = classBytes(true, false, false);
        byte[] structured = classBytes(false, true, false);

        ClassChangeAnalyzer.ClassShape baseShape = ClassChangeAnalyzer.readShape(base);
        ClassChangeAnalyzer.ClassShape annotatedShape = ClassChangeAnalyzer.readShape(annotated);
        ClassChangeAnalyzer.ClassShape structuredShape = ClassChangeAnalyzer.readShape(structured);

        assertFalse(baseShape.getClassAnnotations().contains("Ldemo/DemoScope;"));
        assertTrue(annotatedShape.getClassAnnotations().contains("Ldemo/DemoScope;"));
        assertTrue(structuredShape.getFields().contains("extra:I"));
    }


    @Test void liveShapeDetectsDeletedMethodAsStructureChange() {
        ClassChangeAnalyzer.ClassShape before = ClassChangeAnalyzer.readLiveShape(LiveRichSample.class);
        assertNotNull(before);
        assertTrue(before.getMethods().contains("hello()Ljava/lang/String;"));
        assertTrue(before.getMethods().contains("value()I"));
        assertTrue(before.getFields().contains("value:I"));

        byte[] deletedMethod = liveRichWithoutHello();
        ClassChangeAnalyzer.Analysis analysis = ClassChangeAnalyzer.compare(LiveRichSample.class, deletedMethod);
        assertTrue(analysis.isStructureChanged(), "deleted method must be structure change");
        assertEquals(ClassChangeAnalyzer.ChangeKind.STRUCTURE, analysis.getKind());
        assertFalse(analysis.getAfter().getMethods().contains("hello()Ljava/lang/String;"));
    }

    @Test void liveShapeDetectsDeletedFieldAsStructureChange() {
        byte[] deletedField = liveRichWithoutField();
        ClassChangeAnalyzer.Analysis analysis = ClassChangeAnalyzer.compare(LiveRichSample.class, deletedField);
        assertTrue(analysis.isStructureChanged(), "deleted field must be structure change");
        assertFalse(analysis.getAfter().getFields().contains("value:I"));
    }

    @Test void liveShapeDetectsAddedFieldAsStructureChange() {
        byte[] addedField = liveSampleWithAddedField();
        ClassChangeAnalyzer.Analysis analysis = ClassChangeAnalyzer.compare(LiveSample.class, addedField);
        assertTrue(analysis.isStructureChanged(), "added field must be structure change");
        assertTrue(analysis.getAfter().getFields().contains("extra:I"));
    }

    @Test void sameSchemaHelperComparesFieldsAndMethodsOnly() {
        ClassChangeAnalyzer.ClassShape a = ClassChangeAnalyzer.readLiveShape(LiveSample.class);
        ClassChangeAnalyzer.ClassShape b = ClassChangeAnalyzer.readShape(liveSampleWithAddedMethod());
        assertNotNull(a);
        assertNotNull(b);
        assertFalse(ClassChangeAnalyzer.sameSchema(a, b));
        assertTrue(ClassChangeAnalyzer.sameSchema(a, a));
    }

    @Test void liveShapeDetectsAddedMethodAsStructureChange() {
        ClassChangeAnalyzer.ClassShape before = ClassChangeAnalyzer.readLiveShape(LiveSample.class);
        assertNotNull(before);
        assertTrue(before.getMethods().contains("hello()Ljava/lang/String;"));
        assertFalse(before.getMethods().contains("added()V"));

        byte[] next = liveSampleWithAddedMethod();
        ClassChangeAnalyzer.Analysis analysis = ClassChangeAnalyzer.compare(LiveSample.class, next);
        assertTrue(analysis.isStructureChanged(), "added method must be structure change");
        assertEquals(ClassChangeAnalyzer.ChangeKind.STRUCTURE, analysis.getKind());
        assertTrue(analysis.getAfter().getMethods().contains("added()V"));
    }

    @Test void detectsWebMappingAnnotationChangeSeparatelyFromBusinessAnnotation() {
        byte[] withGet = controllerBytes(true, false);
        byte[] withoutGet = controllerBytes(false, false);
        byte[] withBusiness = controllerBytes(true, true);

        ClassChangeAnalyzer.ClassShape withGetShape = ClassChangeAnalyzer.readShape(withGet);
        ClassChangeAnalyzer.ClassShape withoutGetShape = ClassChangeAnalyzer.readShape(withoutGet);
        ClassChangeAnalyzer.ClassShape withBusinessShape = ClassChangeAnalyzer.readShape(withBusiness);

        assertFalse(withGetShape.webMappingAnnotations().isEmpty());
        assertTrue(withoutGetShape.webMappingAnnotations().isEmpty());
        assertEquals(withGetShape.webMappingAnnotations(), withBusinessShape.webMappingAnnotations());

        assertTrue(ClassChangeAnalyzer.isWebMappingAnnotation("Lorg/springframework/web/bind/annotation/GetMapping;"));
        assertFalse(ClassChangeAnalyzer.isWebMappingAnnotation("Ldemo/DemoScope;"));
    }

    @Test void needsRequestMappingRefreshOnlyWhenMappingOrStructureChanges() {
        byte[] base = controllerBytes(true, true);
        byte[] businessOnly = controllerBytes(true, false);
        byte[] mappingRemoved = controllerBytes(false, true);
        byte[] structure = controllerWithExtraField(true, true);

        ClassChangeAnalyzer.ClassShape baseShape = ClassChangeAnalyzer.readShape(base);
        ClassChangeAnalyzer.ClassShape businessShape = ClassChangeAnalyzer.readShape(businessOnly);
        ClassChangeAnalyzer.ClassShape mappingShape = ClassChangeAnalyzer.readShape(mappingRemoved);
        ClassChangeAnalyzer.ClassShape structureShape = ClassChangeAnalyzer.readShape(structure);

        boolean businessWebSame = baseShape.webMappingAnnotations().equals(businessShape.webMappingAnnotations());
        boolean mappingWebSame = baseShape.webMappingAnnotations().equals(mappingShape.webMappingAnnotations());
        boolean structureFieldsSame = baseShape.getFields().equals(structureShape.getFields());

        assertTrue(businessWebSame);
        assertFalse(mappingWebSame);
        assertFalse(structureFieldsSame);
    }

    public static class LiveSample {
        public String hello() { return "ok"; }
    }

    public static class LiveRichSample {
        private int value;
        public String hello() { return "ok"; }
        public int value() { return value; }
    }


    private static byte[] liveRichWithoutHello() {
        String internal = Type.getInternalName(LiveRichSample.class);
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
        FieldVisitor field = writer.visitField(Opcodes.ACC_PRIVATE, "value", "I", null, null);
        field.visitEnd();
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        MethodVisitor value = writer.visitMethod(Opcodes.ACC_PUBLIC, "value", "()I", null, null);
        value.visitCode();
        value.visitVarInsn(Opcodes.ALOAD, 0);
        value.visitFieldInsn(Opcodes.GETFIELD, internal, "value", "I");
        value.visitInsn(Opcodes.IRETURN);
        value.visitMaxs(1, 1);
        value.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] liveRichWithoutField() {
        String internal = Type.getInternalName(LiveRichSample.class);
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
        MethodVisitor value = writer.visitMethod(Opcodes.ACC_PUBLIC, "value", "()I", null, null);
        value.visitCode();
        value.visitInsn(Opcodes.ICONST_0);
        value.visitInsn(Opcodes.IRETURN);
        value.visitMaxs(1, 1);
        value.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] liveSampleWithAddedField() {
        String internal = Type.getInternalName(LiveSample.class);
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
        writer.visitEnd();
        return writer.toByteArray();
    }
    private static byte[] liveSampleWithAddedMethod() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, Type.getInternalName(LiveSample.class),
                null, "java/lang/Object", null);
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

    private static byte[] classBytes(boolean withAnnotation, boolean withField, boolean unused) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "demo/Sample", null, "java/lang/Object", null);
        if (withAnnotation) {
            AnnotationVisitor annotation = writer.visitAnnotation("Ldemo/DemoScope;", true);
            annotation.visit("alias", "d");
            annotation.visitEnd();
        }
        if (withField) {
            FieldVisitor field = writer.visitField(Opcodes.ACC_PRIVATE, "extra", "I", null, null);
            field.visitEnd();
        }
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

    private static byte[] controllerBytes(boolean withGetMapping, boolean withBusinessAnnotation) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "demo/Ctrl", null, "java/lang/Object", null);
        AnnotationVisitor rest = writer.visitAnnotation("Lorg/springframework/web/bind/annotation/RestController;", true);
        rest.visitEnd();
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        MethodVisitor list = writer.visitMethod(Opcodes.ACC_PUBLIC, "list", "()V", null, null);
        if (withGetMapping) {
            AnnotationVisitor get = list.visitAnnotation("Lorg/springframework/web/bind/annotation/GetMapping;", true);
            get.visitEnd();
        }
        if (withBusinessAnnotation) {
            AnnotationVisitor scope = list.visitAnnotation("Ldemo/DemoScope;", true);
            scope.visitEnd();
        }
        list.visitCode();
        list.visitInsn(Opcodes.RETURN);
        list.visitMaxs(0, 1);
        list.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] controllerWithExtraField(boolean withGetMapping, boolean withBusinessAnnotation) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "demo/Ctrl", null, "java/lang/Object", null);
        AnnotationVisitor rest = writer.visitAnnotation("Lorg/springframework/web/bind/annotation/RestController;", true);
        rest.visitEnd();
        FieldVisitor field = writer.visitField(Opcodes.ACC_PRIVATE, "extra", "I", null, null);
        field.visitEnd();
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        MethodVisitor list = writer.visitMethod(Opcodes.ACC_PUBLIC, "list", "()V", null, null);
        if (withGetMapping) {
            AnnotationVisitor get = list.visitAnnotation("Lorg/springframework/web/bind/annotation/GetMapping;", true);
            get.visitEnd();
        }
        if (withBusinessAnnotation) {
            AnnotationVisitor scope = list.visitAnnotation("Ldemo/DemoScope;", true);
            scope.visitEnd();
        }
        list.visitCode();
        list.visitInsn(Opcodes.RETURN);
        list.visitMaxs(0, 1);
        list.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}


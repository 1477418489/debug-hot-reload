package dev.hotreload.agent.classes;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.LocalVariablesSorter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds assignable subclass Original__HrGenN extends Original.
 * NOTE: must NOT use "$$" in the binary name — Spring ClassUtils.getUserClass
 * treats any "$$" as CGLIB and drops to the superclass, hiding new methods.
 * Private/package parent field and private method access is rewritten to
 * bootstrap StructureAccessBridge.
 */
public final class StructureSubclassFactory {
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String BRIDGE = "dev/hotreload/bootstrap/StructureAccessBridge";

    public static final class Built {
        public final String binaryName;
        public final String internalName;
        public final byte[] bytecode;
        public final String tag;
        public final int bridgedFields;
        public final int bridgedMethods;

        Built(String binaryName, String internalName, byte[] bytecode, String tag,
              int bridgedFields, int bridgedMethods) {
            this.binaryName = binaryName;
            this.internalName = internalName;
            this.bytecode = bytecode;
            this.tag = tag;
            this.bridgedFields = bridgedFields;
            this.bridgedMethods = bridgedMethods;
        }
    }

    private StructureSubclassFactory() { }

    public static Built build(Class<?> original, byte[] reloadedBytecode) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(reloadedBytecode, "reloadedBytecode");
        if ((original.getModifiers() & Modifier.FINAL) != 0) {
            throw new IllegalStateException("final_class_cannot_subclass:" + original.getName());
        }
        final String originalBinary = original.getName();
        final String originalInternal = originalBinary.replace('.', '/');
        int gen = SEQ.incrementAndGet();
        final String tag = "hr-gen-" + gen;
        final String genBinary = originalBinary + "__HrGen" + gen;
        final String genInternal = genBinary.replace('.', '/');
        final MemberIndex parentMembers = MemberIndex.of(original);
        final AtomicInteger bridgedFields = new AtomicInteger();
        final AtomicInteger bridgedMethods = new AtomicInteger();
        // name -> new descriptor for fields whose type changed; subclass redeclares (shadows) them
        // so new code reads/writes the new type instead of CCE-ing through the parent bridge.
        final Map<String, String> redeclaredFields = new HashMap<String, String>();

        ClassReader reader = new ClassReader(reloadedBytecode);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            private String sourceSuper = "java/lang/Object";

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                sourceSuper = superName == null ? "java/lang/Object" : superName;
                super.visit(version, access & ~Opcodes.ACC_FINAL, genInternal, null, originalInternal, interfaces);
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                String parentDescriptor = parentMembers.fieldDescriptor(name);
                if (parentDescriptor != null) {
                    if (parentDescriptor.equals(descriptor)) {
                        // Same field lives in the parent; the subclass reuses it.
                        return null;
                    }
                    // Field type changed: shadow it on the subclass with the new type.
                    redeclaredFields.put(name, descriptor);
                }
                return super.visitField(access, name, descriptor, signature, value);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv == null) {
                    return null;
                }
                final String methodName = name;
                final String srcSuper = sourceSuper;
                // LocalVariablesSorter: newLocal 分配的桥接暂存槽与原方法体局部变量互不冲突
                // （手工按「参数后第一槽」计算会覆写方法体已占用的 slot，静默破坏局部变量值）。
                // 子类化必须走 protected (api,...) 构造器；public 构造器对子类抛 IllegalStateException。
                return new LocalVariablesSorter(Opcodes.ASM9, access, descriptor, mv) {
                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if (originalInternal.equals(type)) {
                            super.visitTypeInsn(opcode, genInternal);
                        } else {
                            super.visitTypeInsn(opcode, type);
                        }
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        // Shadowed (type-changed) fields belong to the generation subclass itself.
                        String shadowDescriptor = redeclaredFields.get(name);
                        if (shadowDescriptor != null && shadowDescriptor.equals(descriptor)) {
                            super.visitFieldInsn(opcode, genInternal, name, descriptor);
                            return;
                        }
                        String declaringOwner = parentMembers.declaringOwner(name);
                        boolean parentField = declaringOwner != null
                                && (parentMembers.isHierarchyOwner(owner)
                                || originalInternal.equals(owner)
                                || genInternal.equals(owner));
                        if (parentField && parentMembers.needsBridge(name)) {
                            rewriteParentField(opcode, declaringOwner, name, descriptor);
                            bridgedFields.incrementAndGet();
                            return;
                        }
                        if (parentField) {
                            super.visitFieldInsn(opcode, declaringOwner, name, descriptor);
                            return;
                        }
                        if (originalInternal.equals(owner) || genInternal.equals(owner)) {
                            super.visitFieldInsn(opcode, genInternal, name, descriptor);
                            return;
                        }
                        super.visitFieldInsn(opcode, owner, name, descriptor);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                        if ("<init>".equals(methodName) && opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name)
                                && (srcSuper.equals(owner) || originalInternal.equals(owner)
                                || genInternal.equals(owner) || "java/lang/Object".equals(owner))) {
                            super.visitMethodInsn(Opcodes.INVOKESPECIAL, originalInternal, name, descriptor, false);
                            return;
                        }
                        if ((originalInternal.equals(owner) || genInternal.equals(owner)
                                || parentMembers.isHierarchyOwner(owner))
                                && parentMembers.privateMethod(name, descriptor)) {
                            rewritePrivateMethod(name, descriptor);
                            bridgedMethods.incrementAndGet();
                            return;
                        }
                        if (originalInternal.equals(owner) || genInternal.equals(owner)) {
                            super.visitMethodInsn(opcode, originalInternal, name, descriptor, isInterface);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }

                    private void rewriteParentField(int opcode, String ownerInternal, String name, String descriptor) {
                        Type fieldType = Type.getType(descriptor);
                        String ownerBinary = ownerInternal.replace('/', '.');
                        if (opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC) {
                            super.visitFieldInsn(opcode, ownerInternal, name, descriptor);
                            return;
                        }
                        if (opcode == Opcodes.GETFIELD) {
                            mv.visitLdcInsn(ownerBinary);
                            mv.visitLdcInsn(name);
                            if (fieldType.getSort() == Type.OBJECT || fieldType.getSort() == Type.ARRAY) {
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "getField",
                                        "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;",
                                        false);
                                if (fieldType.getSort() == Type.OBJECT) {
                                    mv.visitTypeInsn(Opcodes.CHECKCAST, fieldType.getInternalName());
                                } else {
                                    mv.visitTypeInsn(Opcodes.CHECKCAST, descriptor);
                                }
                            } else {
                                String getter = primitiveGetter(fieldType);
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, getter,
                                        "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)" + descriptor,
                                        false);
                            }
                            return;
                        }
                        if (opcode == Opcodes.PUTFIELD) {
                            if (fieldType.getSize() == 2) {
                                mv.visitInsn(Opcodes.DUP2_X1);
                                mv.visitInsn(Opcodes.POP2);
                                boxIfNeeded(fieldType);
                            } else {
                                boxIfNeeded(fieldType);
                                mv.visitInsn(Opcodes.SWAP);
                            }
                            mv.visitLdcInsn(ownerBinary);
                            mv.visitLdcInsn(name);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "setFieldValueFirst",
                                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V",
                                    false);
                            return;
                        }
                        super.visitFieldInsn(opcode, ownerInternal, name, descriptor);
                    }

                    private void rewritePrivateMethod(String name, String descriptor) {
                        Type[] args = Type.getArgumentTypes(descriptor);
                        Type returnType = Type.getReturnType(descriptor);
                        int[] slots = new int[args.length];
                        for (int i = args.length - 1; i >= 0; i--) {
                            slots[i] = newLocal(args[i]);
                            mv.visitVarInsn(args[i].getOpcode(Opcodes.ISTORE), slots[i]);
                        }
                        mv.visitLdcInsn(originalBinary);
                        mv.visitLdcInsn(name);
                        mv.visitLdcInsn(descriptor);
                        pushInt(args.length);
                        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
                        for (int i = 0; i < args.length; i++) {
                            mv.visitInsn(Opcodes.DUP);
                            pushInt(i);
                            mv.visitVarInsn(args[i].getOpcode(Opcodes.ILOAD), slots[i]);
                            boxIfNeeded(args[i]);
                            mv.visitInsn(Opcodes.AASTORE);
                        }
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "invoke",
                                "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;",
                                false);
                        if (returnType.getSort() == Type.VOID) {
                            mv.visitInsn(Opcodes.POP);
                        } else if (returnType.getSort() == Type.OBJECT || returnType.getSort() == Type.ARRAY) {
                            if (returnType.getSort() == Type.OBJECT) {
                                mv.visitTypeInsn(Opcodes.CHECKCAST, returnType.getInternalName());
                            } else {
                                mv.visitTypeInsn(Opcodes.CHECKCAST, returnType.getDescriptor());
                            }
                        } else {
                            unbox(returnType);
                        }
                    }

                    private void pushInt(int value) {
                        if (value >= -1 && value <= 5) {
                            mv.visitInsn(Opcodes.ICONST_0 + value);
                        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
                            mv.visitIntInsn(Opcodes.BIPUSH, value);
                        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
                            mv.visitIntInsn(Opcodes.SIPUSH, value);
                        } else {
                            mv.visitLdcInsn(Integer.valueOf(value));
                        }
                    }

                    private void unbox(Type type) {
                        switch (type.getSort()) {
                            case Type.BOOLEAN:
                                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
                                break;
                            case Type.BYTE:
                                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "byteValue", "()B", false);
                                break;
                            case Type.CHAR:
                                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Character");
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false);
                                break;
                            case Type.SHORT:
                                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "shortValue", "()S", false);
                                break;
                            case Type.INT:
                                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);
                                break;
                            case Type.FLOAT:
                                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false);
                                break;
                            case Type.LONG:
                                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J", false);
                                break;
                            case Type.DOUBLE:
                                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false);
                                break;
                            default:
                                break;
                        }
                    }

                    private void boxIfNeeded(Type fieldType) {
                        switch (fieldType.getSort()) {
                            case Type.BOOLEAN:
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
                                break;
                            case Type.BYTE:
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
                                break;
                            case Type.CHAR:
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
                                break;
                            case Type.SHORT:
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
                                break;
                            case Type.INT:
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
                                break;
                            case Type.FLOAT:
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
                                break;
                            case Type.LONG:
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
                                break;
                            case Type.DOUBLE:
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
                                break;
                            default:
                                break;
                        }
                    }

                    private String primitiveGetter(Type fieldType) {
                        switch (fieldType.getSort()) {
                            case Type.BOOLEAN: return "getBoolean";
                            case Type.BYTE: return "getByte";
                            case Type.CHAR: return "getChar";
                            case Type.SHORT: return "getShort";
                            case Type.INT: return "getInt";
                            case Type.FLOAT: return "getFloat";
                            case Type.LONG: return "getLong";
                            case Type.DOUBLE: return "getDouble";
                            default: return "getField";
                        }
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return new Built(genBinary, genInternal, writer.toByteArray(), tag,
                bridgedFields.get(), bridgedMethods.get());
    }

    private static final class MemberIndex {
        private final Set<String> hierarchyOwners = new HashSet<String>();
        private final Map<String, String> fieldDeclaringOwner = new HashMap<String, String>();
        private final Map<String, Integer> fieldAccess = new HashMap<String, Integer>();
        private final Map<String, String> fieldDescriptors = new HashMap<String, String>();
        private final Map<String, Integer> methodAccess = new HashMap<String, Integer>();

        static MemberIndex of(Class<?> type) {
            MemberIndex index = new MemberIndex();
            Class<?> cur = type;
            while (cur != null && cur != Object.class) {
                index.hierarchyOwners.add(cur.getName().replace('.', '/'));
                for (Field field : cur.getDeclaredFields()) {
                    if (field.isSynthetic()) {
                        continue;
                    }
                    if (!index.fieldAccess.containsKey(field.getName())) {
                        index.fieldAccess.put(field.getName(), field.getModifiers());
                        index.fieldDeclaringOwner.put(field.getName(), Type.getInternalName(cur));
                        index.fieldDescriptors.put(field.getName(), Type.getDescriptor(field.getType()));
                    }
                }
                for (Method method : cur.getDeclaredMethods()) {
                    if (method.isSynthetic() || method.isBridge()) {
                        continue;
                    }
                    String key = method.getName() + Type.getMethodDescriptor(method);
                    if (!index.methodAccess.containsKey(key)) {
                        index.methodAccess.put(key, method.getModifiers());
                    }
                }
                cur = cur.getSuperclass();
            }
            return index;
        }

        String fieldDescriptor(String name) {
            return fieldDescriptors.get(name);
        }

        boolean isHierarchyOwner(String ownerInternal) {
            return ownerInternal != null && hierarchyOwners.contains(ownerInternal);
        }

        String declaringOwner(String name) {
            return fieldDeclaringOwner.get(name);
        }

        boolean needsBridge(String name) {
            Integer access = fieldAccess.get(name);
            if (access == null) {
                return false;
            }
            return Modifier.isPrivate(access)
                    || (!Modifier.isPublic(access) && !Modifier.isProtected(access));
        }

        boolean privateMethod(String name, String desc) {
            Integer access = methodAccess.get(name + desc);
            return access != null && Modifier.isPrivate(access);
        }
    }
}

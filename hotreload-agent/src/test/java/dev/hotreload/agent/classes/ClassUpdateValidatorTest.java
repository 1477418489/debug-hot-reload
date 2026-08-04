package dev.hotreload.agent.classes;

import dev.hotreload.protocol.message.ClassUpdate;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassUpdateValidatorTest {
    @Test void acceptsUnicodeBinaryNames() {
        String binaryName = "demo.\u7c7b\u578b";
        String internalName = binaryName.replace('.', '/');
        ClassWriter writer = new ClassWriter(0);
        writer.visit(52, 0x0001, internalName, null, "java/lang/Object", null);
        writer.visitEnd();

        ClassUpdateValidator.ValidationResult result = new ClassUpdateValidator().validate(
                Collections.singletonList(new ClassUpdate(binaryName, writer.toByteArray())), 52);
        assertTrue(result.isSuccess(), result.getErrorCode() == null ? "validation failed" :
                result.getErrorCode().name());
    }
}

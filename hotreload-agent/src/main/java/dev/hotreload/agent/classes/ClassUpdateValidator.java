package dev.hotreload.agent.classes;

import dev.hotreload.protocol.message.ClassUpdate;
import dev.hotreload.protocol.message.ReloadErrorCode;
import org.objectweb.asm.ClassReader;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ClassUpdateValidator {
    ValidationResult validate(List<ClassUpdate> updates, int targetMajorVersion) {
        Set<String> names = new HashSet<String>();
        for (ClassUpdate update : updates) {
            String requestedName = update.getBinaryName();
            if (!isBinaryName(requestedName)) {
                return ValidationResult.failure(requestedName, ReloadErrorCode.CLASS_NAME_INVALID);
            }
            if (!names.add(requestedName)) {
                return ValidationResult.failure(requestedName, ReloadErrorCode.CLASS_DUPLICATE);
            }
            byte[] bytecode = update.getBytecode();
            final ClassReader reader;
            try {
                reader = new ClassReader(bytecode);
            } catch (RuntimeException e) {
                return ValidationResult.failure(requestedName, ReloadErrorCode.CLASS_NAME_INVALID);
            }
            String declaredName = reader.getClassName().replace('/', '.');
            if (!requestedName.equals(declaredName)) {
                return ValidationResult.failure(requestedName, ReloadErrorCode.CLASS_NAME_INVALID);
            }
            int major = ((bytecode[6] & 0xff) << 8) | (bytecode[7] & 0xff);
            if (major > targetMajorVersion) {
                return ValidationResult.failure(requestedName, ReloadErrorCode.CLASS_VERSION_UNSUPPORTED);
            }
        }
        return ValidationResult.success();
    }

    private static boolean isBinaryName(String value) {
        if (value == null || value.isEmpty()) return false;
        String[] segments = value.split("\\.", -1);
        for (String segment : segments) {
            if (segment.isEmpty()) return false;
            int offset = 0;
            int codePoint = segment.codePointAt(offset);
            if (!isIdentifierStart(codePoint)) return false;
            offset += Character.charCount(codePoint);
            while (offset < segment.length()) {
                codePoint = segment.codePointAt(offset);
                if (!isIdentifierPart(codePoint)) return false;
                offset += Character.charCount(codePoint);
            }
        }
        return true;
    }

    private static boolean isIdentifierStart(int codePoint) {
        return codePoint == '$' || Character.isJavaIdentifierStart(codePoint);
    }

    private static boolean isIdentifierPart(int codePoint) {
        return codePoint == '$' || Character.isJavaIdentifierPart(codePoint);
    }

    static final class ValidationResult {
        private final String itemId;
        private final ReloadErrorCode errorCode;

        private ValidationResult(String itemId, ReloadErrorCode errorCode) {
            this.itemId = itemId;
            this.errorCode = errorCode;
        }

        static ValidationResult success() { return new ValidationResult(null, null); }
        static ValidationResult failure(String itemId, ReloadErrorCode errorCode) {
            return new ValidationResult(itemId, errorCode);
        }
        boolean isSuccess() { return errorCode == null; }
        String getItemId() { return itemId; }
        ReloadErrorCode getErrorCode() { return errorCode; }
    }
}

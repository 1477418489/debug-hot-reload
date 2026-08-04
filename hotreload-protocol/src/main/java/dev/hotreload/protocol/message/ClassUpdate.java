package dev.hotreload.protocol.message;

import java.util.Arrays;
import java.util.Objects;

public final class ClassUpdate {
    private final String binaryName;
    private final byte[] bytecode;

    public ClassUpdate(String binaryName, byte[] bytecode) {
        this.binaryName = MessageChecks.text(binaryName, "binaryName");
        this.bytecode = MessageChecks.bytes(bytecode, "bytecode");
    }

    public String getBinaryName() { return binaryName; }
    public byte[] getBytecode() { return bytecode.clone(); }
    public int getBytecodeLength() { return bytecode.length; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ClassUpdate)) return false;
        ClassUpdate that = (ClassUpdate) other;
        return binaryName.equals(that.binaryName) && Arrays.equals(bytecode, that.bytecode);
    }

    @Override public int hashCode() { return 31 * Objects.hash(binaryName) + Arrays.hashCode(bytecode); }
}

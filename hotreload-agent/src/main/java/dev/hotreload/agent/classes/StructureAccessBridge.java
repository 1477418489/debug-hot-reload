package dev.hotreload.agent.classes;

/**
 * Compatibility shim. Generation subclasses call the bootstrap-resident
 * {@link dev.hotreload.bootstrap.StructureAccessBridge} so the helper is visible
 * under every application ClassLoader.
 */
@Deprecated
public final class StructureAccessBridge {
    private StructureAccessBridge() { }

    public static Object getField(Object target, String ownerBinary, String fieldName) {
        return dev.hotreload.bootstrap.StructureAccessBridge.getField(target, ownerBinary, fieldName);
    }

    public static void setFieldValueFirst(Object value, Object target, String ownerBinary, String fieldName) {
        dev.hotreload.bootstrap.StructureAccessBridge.setFieldValueFirst(value, target, ownerBinary, fieldName);
    }

    public static void setField(Object target, String ownerBinary, String fieldName, Object value) {
        dev.hotreload.bootstrap.StructureAccessBridge.setField(target, ownerBinary, fieldName, value);
    }

    public static Object invoke(Object target, String ownerBinary, String methodName, String descriptor, Object[] args) {
        return dev.hotreload.bootstrap.StructureAccessBridge.invoke(target, ownerBinary, methodName, descriptor, args);
    }
}

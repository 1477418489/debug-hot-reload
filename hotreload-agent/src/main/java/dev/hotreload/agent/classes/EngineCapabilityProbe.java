package dev.hotreload.agent.classes;

import java.lang.instrument.Instrumentation;

/**
 * Passive JVM hot reload capability detection. MUST stay side-effect free:
 * probing with a real redefine in premain crashes DCEVM-8 sessions later
 * (JMX MBean introspection ArrayStoreException), so we only read VM identity.
 * ENHANCED false-positives are safe: the first structural redefine that gets
 * rejected falls back to the generation path automatically.
 */
public final class EngineCapabilityProbe {
    public enum Capability { ENHANCED, STANDARD, NONE }

    private EngineCapabilityProbe() { }

    public static Capability capability(Instrumentation instrumentation) {
        if (instrumentation == null || !instrumentation.isRedefineClassesSupported()) {
            return Capability.NONE;
        }
        return detect(System.getProperty("java.vm.name"),
                System.getProperty("java.vm.vendor"),
                System.getProperty("java.specification.version"));
    }

    /**
     * Pure decision function (unit-testable):
     * - DCEVM altjvm reports "Dynamic Code Evolution ... VM" as the VM name.
     * - JetBrains Runtime 17+ ships enhanced class redefinition
     *   (activated by -XX:+AllowEnhancedClassRedefinition, injected by the plugin).
     */
    static Capability detect(String vmName, String vmVendor, String specVersion) {
        if (vmName != null && vmName.contains("Dynamic Code Evolution")) {
            return Capability.ENHANCED;
        }
        if (vmVendor != null && vmVendor.contains("JetBrains")
                && featureVersion(specVersion) >= 17) {
            return Capability.ENHANCED;
        }
        return Capability.STANDARD;
    }

    /** "1.8" -> 8, "17" -> 17; unknown -> 0. */
    static int featureVersion(String specVersion) {
        if (specVersion == null || specVersion.isEmpty()) return 0;
        String value = specVersion.startsWith("1.") ? specVersion.substring(2) : specVersion;
        int end = 0;
        while (end < value.length() && Character.isDigit(value.charAt(end))) end++;
        if (end == 0) return 0;
        try {
            return Integer.parseInt(value.substring(0, end));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}

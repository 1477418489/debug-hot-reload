package dev.hotreload.agent.classes;

import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EngineCapabilityProbeTest {
    @Test
    void detectRecognizesDcevmByVmName() {
        assertEquals(EngineCapabilityProbe.Capability.ENHANCED,
                EngineCapabilityProbe.detect("Dynamic Code Evolution 64-Bit Server VM",
                        "Oracle Corporation", "1.8"));
    }

    @Test
    void detectRecognizesJbr17PlusOnly() {
        assertEquals(EngineCapabilityProbe.Capability.ENHANCED,
                EngineCapabilityProbe.detect("OpenJDK 64-Bit Server VM", "JetBrains s.r.o.", "21"));
        assertEquals(EngineCapabilityProbe.Capability.ENHANCED,
                EngineCapabilityProbe.detect("OpenJDK 64-Bit Server VM", "JetBrains s.r.o.", "17"));
        // JBR 11 has no default enhanced redefinition: never over-promise.
        assertEquals(EngineCapabilityProbe.Capability.STANDARD,
                EngineCapabilityProbe.detect("OpenJDK 64-Bit Server VM", "JetBrains s.r.o.", "11"));
    }

    @Test
    void detectTreatsStockJvmAsStandard() {
        assertEquals(EngineCapabilityProbe.Capability.STANDARD,
                EngineCapabilityProbe.detect("Java HotSpot(TM) 64-Bit Server VM",
                        "Oracle Corporation", "1.8"));
        assertEquals(EngineCapabilityProbe.Capability.STANDARD,
                EngineCapabilityProbe.detect(null, null, null));
    }

    @Test
    void featureVersionParsesLegacyAndModernFormats() {
        assertEquals(8, EngineCapabilityProbe.featureVersion("1.8"));
        assertEquals(17, EngineCapabilityProbe.featureVersion("17"));
        assertEquals(21, EngineCapabilityProbe.featureVersion("21"));
        assertEquals(0, EngineCapabilityProbe.featureVersion(null));
        assertEquals(0, EngineCapabilityProbe.featureVersion("abc"));
    }

    @Test
    void capabilityIsNoneWithoutRedefineSupport() {
        assertEquals(EngineCapabilityProbe.Capability.NONE, EngineCapabilityProbe.capability(null));
        Instrumentation unsupported = (Instrumentation) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Instrumentation.class},
                (proxy, method, args) -> {
                    if ("isRedefineClassesSupported".equals(method.getName())) return false;
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    return null;
                });
        assertEquals(EngineCapabilityProbe.Capability.NONE,
                EngineCapabilityProbe.capability(unsupported));
    }

    @Test
    void capabilityOnThisStockTestJvmIsStandard() {
        Instrumentation supported = (Instrumentation) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Instrumentation.class},
                (proxy, method, args) -> {
                    if ("isRedefineClassesSupported".equals(method.getName())) return true;
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    return null;
                });
        // Gradle test JVM is a stock Temurin/Oracle build, never DCEVM/JBR.
        assertEquals(EngineCapabilityProbe.Capability.STANDARD,
                EngineCapabilityProbe.capability(supported));
    }
}

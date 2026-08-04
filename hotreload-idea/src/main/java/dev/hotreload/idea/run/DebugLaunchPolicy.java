package dev.hotreload.idea.run;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class DebugLaunchPolicy {
    private static final Set<String> SUPPORTED_CONFIGURATION_TYPES = new HashSet<String>(Arrays.asList(
            "Application",
            "SpringBootApplicationConfigurationType"
    ));

    private DebugLaunchPolicy() {
    }

    public static boolean isConfigurationTypeSupported(String configurationTypeId) {
        return SUPPORTED_CONFIGURATION_TYPES.contains(configurationTypeId);
    }

    public static boolean isSupported(String executorId, String configurationTypeId, Integer jdkFeature) {
        return "Debug".equals(executorId)
                && isConfigurationTypeSupported(configurationTypeId)
                && jdkFeature != null
                && jdkFeature.intValue() >= 8;
    }
}

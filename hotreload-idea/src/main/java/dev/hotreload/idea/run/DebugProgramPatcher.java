package dev.hotreload.idea.run;

import com.intellij.execution.Executor;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.runners.JavaProgramPatcher;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import dev.hotreload.idea.client.HotReloadProjectService;
import dev.hotreload.idea.settings.HotReloadSettingsResolver;

import java.nio.file.Files;
import java.nio.file.Path;

public final class DebugProgramPatcher extends JavaProgramPatcher {
    private static final Logger LOG = Logger.getInstance(DebugProgramPatcher.class);

    @Override public void patchJavaParameters(Executor executor, RunProfile runProfile,
                                              JavaParameters javaParameters) {
        if (executor == null || runProfile == null || javaParameters == null
                || !"Debug".equals(executor.getId()) || !(runProfile instanceof RunConfiguration)) {
            return;
        }
        RunConfiguration configuration = (RunConfiguration) runProfile;
        String configurationTypeId = configuration.getType().getId();
        if (!DebugLaunchPolicy.isConfigurationTypeSupported(configurationTypeId)) return;

        Project project = configuration.getProject();
        HotReloadSettingsResolver.Snapshot effectiveSettings;
        try {
            effectiveSettings = HotReloadSettingsResolver.resolve(project);
        } catch (Throwable failure) {
            LOG.warn("event=PATCH_SKIPPED reason=settings_unavailable configuration="
                    + configuration.getName(), failure);
            return;
        }
        if (!effectiveSettings.isProjectEnabled()) {
            LOG.info("event=PATCH_SKIPPED reason=project_disabled configuration="
                    + configuration.getName());
            return;
        }
        if (!effectiveSettings.isRunConfigurationEnabled(configurationTypeId,
                configuration.getName())) {
            LOG.info("event=PATCH_SKIPPED reason=configuration_excluded configuration="
                    + configuration.getName());
            return;
        }
        if (!effectiveSettings.hasAnyReloadFeatureEnabled()) {
            LOG.info("event=PATCH_SKIPPED reason=no_features_enabled configuration="
                    + configuration.getName());
            return;
        }
        Integer jdkFeature = jdkFeature(javaParameters.getJdk());
        if (!DebugLaunchPolicy.isSupported("Debug", configurationTypeId, jdkFeature)) {
            logPatch(project, true, "PATCH_SKIPPED", null,
                    "reason", "unsupported_jdk",
                    "configuration", configuration.getName(),
                    "configurationType", configurationTypeId,
                    "jdkFeature", jdkFeature == null ? "unknown" : Integer.toString(jdkFeature));
            return;
        }
        Path agentJar = locateAgentJar();
        if (agentJar == null || !Files.isRegularFile(agentJar)) {
            logPatch(project, true, "PATCH_SKIPPED", null,
                    "reason", "agent_jar_missing",
                    "configuration", configuration.getName());
            return;
        }
        String agentPrefix = "-javaagent:" + agentJar;
        for (String argument : javaParameters.getVMParametersList().getList()) {
            if (argument.equals(agentPrefix) || argument.startsWith(agentPrefix + "=")) {
                logPatch(project, false, "PATCH_SKIPPED", null,
                        "reason", "agent_already_present",
                        "configuration", configuration.getName());
                return;
            }
        }

        try {
            Path launchRoot = PathManager.getSystemDir()
                    .resolve("mybatis-debug-hotreload")
                    .resolve(safeProjectId(project.getLocationHash()))
                    .toAbsolutePath().normalize();
            AgentLaunchSpec spec = project.getService(HotReloadProjectService.class)
                    .prepareLaunch(runProfile, launchRoot, agentJar,
                            javaParameters.getClassPath().getPathList());
            javaParameters.getVMParametersList().add(spec.getVmArgument());
            maybeEnableEnhancedRuntime(javaParameters, project, configuration, spec.getLaunchId(),
                    effectiveSettings.isEnhancedRuntimeEnabled());
            logPatch(project, false, "PATCH_APPLIED", spec.getLaunchId(),
                    "configuration", configuration.getName(),
                    "configurationType", configurationTypeId,
                    "agentJar", agentJar.getFileName().toString(),
                    "classpathEntries", Integer.toString(javaParameters.getClassPath().getPathList().size()));
        } catch (Exception failure) {
            logPatch(project, true, "PATCH_FAILED", null,
                    "reason", failure.getClass().getSimpleName(),
                    "configuration", configuration.getName());
        }
    }

    /**
     * 精确检测后才注入增强运行时参数：
     * - JDK 内含 dcevm altjvm（目录内确有 JVM 库文件）→ -XXaltjvm=dcevm
     * - JetBrains Runtime 且 JDK 17+（release 文件 IMPLEMENTOR 含 JetBrains）→ -XX:+AllowEnhancedClassRedefinition
     * 检测不准会让应用直接起不来（Unrecognized VM option / 缺失 jvm 库），因此绝不盲注：
     * JBR 11 及以下不支持该参数，空的 dcevm 残留目录同样必须排除。
     */
    private static void maybeEnableEnhancedRuntime(JavaParameters javaParameters, Project project,
                                                   RunConfiguration configuration, String launchId,
                                                   boolean enabled) {
        try {
            if (!enabled) return;
            EnhancedRuntimeSupport.Result support =
                    EnhancedRuntimeSupport.inspect(javaParameters.getJdk());
            if (!support.isAvailable()) return;
            com.intellij.execution.configurations.ParametersList vm = javaParameters.getVMParametersList();
            if (support.getMode() == EnhancedRuntimeSupport.Mode.DCEVM
                    && hasParameterWithPrefix(vm, "-XXaltjvm=")
                    && !vm.hasParameter("-XXaltjvm=dcevm")) {
                logPatch(project, false, "ENHANCED_RUNTIME_SKIPPED", launchId,
                        "reason", "custom_altjvm_present",
                        "configuration", configuration.getName());
                return;
            }
            if (support.getMode() == EnhancedRuntimeSupport.Mode.JBR
                    && vm.hasParameter("-XX:-AllowEnhancedClassRedefinition")) {
                logPatch(project, false, "ENHANCED_RUNTIME_SKIPPED", launchId,
                        "reason", "explicitly_disabled",
                        "configuration", configuration.getName());
                return;
            }
            for (String argument : support.getVmArguments()) {
                if (argument.startsWith("-XX:TieredStopAtLevel=")
                        && hasParameterWithPrefix(vm, "-XX:TieredStopAtLevel=")) {
                    continue;
                }
                if (!vm.hasParameter(argument)) vm.add(argument);
            }
            logPatch(project, false, "ENHANCED_RUNTIME_ENABLED", launchId,
                    "mode", support.getMode().name().toLowerCase(java.util.Locale.ROOT),
                    "configuration", configuration.getName());
        } catch (Throwable ignored) {
            // 注入失败不阻断 Debug 启动。
        }
    }

    private static boolean hasParameterWithPrefix(
            com.intellij.execution.configurations.ParametersList parameters, String prefix) {
        for (String argument : parameters.getList()) {
            if (argument.startsWith(prefix)) return true;
        }
        return false;
    }

    private static void logPatch(Project project, boolean warn, String event, String launchId, String... fields) {
        StringBuilder line = new StringBuilder("event=").append(event);
        if (launchId != null) line.append(" launchId=").append(launchId);
        for (int i = 0; fields != null && i + 1 < fields.length; i += 2) {
            line.append(' ').append(fields[i]).append('=').append(fields[i + 1]);
        }
        if (warn) LOG.warn(line.toString());
        else LOG.info(line.toString());
        if (project == null || project.isDisposed()) return;
        HotReloadProjectService service = project.getService(HotReloadProjectService.class);
        if (service == null) return;
        if (warn) service.recordWarning(event, fields.length >= 2 ? fields[1] : "unknown");
        else service.recordInfo(event, launchId, fields);
    }

    private static Integer jdkFeature(Sdk sdk) {
        return sdk == null ? null : EnhancedRuntimeSupport.jdkFeature(sdk.getVersionString());
    }

    static Integer jdkFeature(String versionString) {
        return EnhancedRuntimeSupport.jdkFeature(versionString);
    }

    private static Path locateAgentJar() {
        Path pluginJar = PathManager.getJarForClass(DebugProgramPatcher.class);
        if (pluginJar == null) return null;
        Path libDirectory = Files.isDirectory(pluginJar) ? pluginJar : pluginJar.getParent();
        return libDirectory == null ? null
                : libDirectory.resolve("agent").resolve("hotreload-agent.jar").toAbsolutePath().normalize();
    }

    private static String safeProjectId(String value) {
        if (value == null || value.isEmpty()) return "project";
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}

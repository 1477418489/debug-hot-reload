package dev.hotreload.idea.run;

import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.util.lang.JavaVersion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class EnhancedRuntimeSupport {
    public enum Mode { NONE, DCEVM, JBR }
    public enum Reason { AVAILABLE, SDK_MISSING, HOME_MISSING, JBR_REQUIRES_JDK_17, UNSUPPORTED }

    private EnhancedRuntimeSupport() {
    }

    public static Result inspect(Sdk sdk) {
        if (sdk == null) return Result.unavailable(Reason.SDK_MISSING, null, null);
        String home = sdk.getHomePath();
        if (home == null || home.isEmpty()) {
            return Result.unavailable(Reason.HOME_MISSING, null, jdkFeature(sdk.getVersionString()));
        }
        try {
            return inspect(Path.of(home).toAbsolutePath().normalize(),
                    jdkFeature(sdk.getVersionString()));
        } catch (RuntimeException invalidPath) {
            return Result.unavailable(Reason.HOME_MISSING, null, jdkFeature(sdk.getVersionString()));
        }
    }

    static Result inspect(Path home, Integer feature) {
        if (home == null || !Files.isDirectory(home)) {
            return Result.unavailable(Reason.HOME_MISSING, home, feature);
        }
        if (hasUsableDcevm(home.resolve("jre").resolve("bin").resolve("dcevm"))
                || hasUsableDcevm(home.resolve("bin").resolve("dcevm"))
                || hasUsableDcevm(home.resolve("lib").resolve("dcevm"))) {
            return Result.available(Mode.DCEVM, home, feature,
                    Arrays.asList("-XXaltjvm=dcevm", "-XX:TieredStopAtLevel=1"));
        }
        if (isJetBrainsRuntime(home)) {
            if (feature == null || feature.intValue() < 17) {
                return Result.unavailable(Reason.JBR_REQUIRES_JDK_17, home, feature);
            }
            return Result.available(Mode.JBR, home, feature,
                    Collections.singletonList("-XX:+AllowEnhancedClassRedefinition"));
        }
        return Result.unavailable(Reason.UNSUPPORTED, home, feature);
    }

    public static Integer jdkFeature(String versionString) {
        if (versionString == null) return null;
        JavaVersion version = JavaVersion.tryParse(versionString);
        return version == null ? null : Integer.valueOf(version.feature);
    }

    private static boolean hasUsableDcevm(Path directory) {
        if (!Files.isDirectory(directory)) return false;
        return Files.isRegularFile(directory.resolve("jvm.dll"))
                || Files.isRegularFile(directory.resolve("libjvm.so"))
                || Files.isRegularFile(directory.resolve("libjvm.dylib"));
    }

    private static boolean isJetBrainsRuntime(Path home) {
        try {
            Path release = home.resolve("release");
            if (!Files.isRegularFile(release)) return false;
            for (String line : Files.readAllLines(release)) {
                if (line.startsWith("IMPLEMENTOR=") && line.contains("JetBrains")) return true;
            }
        } catch (Exception ignored) {
            // An unreadable release descriptor cannot safely prove JBR support.
        }
        return false;
    }

    public static final class Result {
        private final Mode mode;
        private final Reason reason;
        private final Path home;
        private final Integer jdkFeature;
        private final List<String> vmArguments;

        private Result(Mode mode, Reason reason, Path home, Integer jdkFeature,
                       List<String> vmArguments) {
            this.mode = mode;
            this.reason = reason;
            this.home = home;
            this.jdkFeature = jdkFeature;
            this.vmArguments = Collections.unmodifiableList(
                    new ArrayList<String>(vmArguments));
        }

        private static Result available(Mode mode, Path home, Integer feature,
                                        List<String> arguments) {
            return new Result(mode, Reason.AVAILABLE, home, feature, arguments);
        }

        private static Result unavailable(Reason reason, Path home, Integer feature) {
            return new Result(Mode.NONE, reason, home, feature, Collections.emptyList());
        }

        public boolean isAvailable() { return mode != Mode.NONE; }
        public Mode getMode() { return mode; }
        public Reason getReason() { return reason; }
        public Path getHome() { return home; }
        public Integer getJdkFeature() { return jdkFeature; }
        public List<String> getVmArguments() { return vmArguments; }
    }
}

package dev.hotreload.agent.compat;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Best-effort detection of target stack versions and hot-reload capabilities.
 * Reflection only; never loads optional frameworks eagerly beyond Class.forName(false).
 */
public final class RuntimeEnvironmentProbe {
    private RuntimeEnvironmentProbe() { }

    public static RuntimeEnvironment probe() { return probe(false); }

    public static RuntimeEnvironment probe(boolean redefineSupported) {
        return probe(null, redefineSupported);
    }

    public static RuntimeEnvironment probe(Instrumentation instrumentation) {
        boolean redefine = instrumentation != null && instrumentation.isRedefineClassesSupported();
        return probe(instrumentation, redefine);
    }

    public static RuntimeEnvironment probe(Instrumentation instrumentation, boolean redefineSupported) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = ClassLoader.getSystemClassLoader();

        String spring = packageVersion(loader, "org.springframework.core.SpringVersion", "getVersion");
        if (spring == null) spring = jarVersion(loader, "org.springframework.core.SpringVersion");

        String boot = packageVersion(loader, "org.springframework.boot.SpringBootVersion", "getVersion");
        if (boot == null) boot = jarVersion(loader, "org.springframework.boot.SpringBootVersion");

        String mybatis = jarVersion(loader, "org.apache.ibatis.session.Configuration");
        String plus = jarVersion(loader, "com.baomidou.mybatisplus.core.MybatisConfiguration");
        if (plus == null) plus = jarVersion(loader, "com.baomidou.mybatisplus.core.MybatisXMLMapperBuilder");

        boolean jakarta = classPresent(loader, "jakarta.servlet.Servlet")
                || classPresent(loader, "jakarta.servlet.http.HttpServletRequest");
        boolean springPresent = classPresent(loader, "org.springframework.context.ApplicationContext")
                || classPresent(loader, "org.springframework.web.servlet.DispatcherServlet");
        boolean mybatisPresent = classPresent(loader, "org.apache.ibatis.session.Configuration")
                || classPresent(loader, "org.apache.ibatis.session.SqlSessionFactory");

        boolean redefine = redefineSupported;

        List<String> caps = new ArrayList<String>();
        caps.add("mapperXml");
        if (redefine) caps.add("classRedefine");
        if (springPresent) {
            caps.add("springBeanRecreate");
            caps.add("requestMappingRefresh");
            caps.add("annotationRebind");
            caps.add("configPropertyReload");
        }
        if (mybatisPresent) caps.add("mybatisStatementReload");
        if (plus != null) caps.add("mybatisPlus");
        caps.add(jakarta ? "jakarta" : "javax");

        Set<String> unique = new LinkedHashSet<String>(caps);
        return new RuntimeEnvironment(
                System.getProperty("java.version", "unknown"),
                System.getProperty("java.vendor", "unknown"),
                spring,
                boot,
                mybatis,
                plus,
                jakarta,
                redefine,
                springPresent,
                mybatisPresent,
                new ArrayList<String>(unique));
    }

    private static boolean classPresent(ClassLoader loader, String name) {
        try {
            Class.forName(name, false, loader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String packageVersion(ClassLoader loader, String typeName, String method) {
        try {
            Class<?> type = Class.forName(typeName, false, loader);
            if (method != null) {
                Object value = type.getMethod(method).invoke(null);
                if (value != null) return String.valueOf(value);
            }
            Package pkg = type.getPackage();
            if (pkg != null && pkg.getImplementationVersion() != null) return pkg.getImplementationVersion();
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String jarVersion(ClassLoader loader, String typeName) {
        try {
            Class<?> type = Class.forName(typeName, false, loader);
            Package pkg = type.getPackage();
            if (pkg != null) {
                if (pkg.getImplementationVersion() != null) return pkg.getImplementationVersion();
                if (pkg.getSpecificationVersion() != null) return pkg.getSpecificationVersion();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}

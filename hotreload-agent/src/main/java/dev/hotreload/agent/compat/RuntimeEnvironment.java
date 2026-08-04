package dev.hotreload.agent.compat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable snapshot of the target JVM / framework stack. */
public final class RuntimeEnvironment {
    private final String jdkVersion;
    private final String jdkVendor;
    private final String springVersion;
    private final String springBootVersion;
    private final String mybatisVersion;
    private final String mybatisPlusVersion;
    private final boolean jakartaServlet;
    private final boolean classRedefineSupported;
    private final boolean springPresent;
    private final boolean mybatisPresent;
    private final List<String> capabilities;

    public RuntimeEnvironment(String jdkVersion, String jdkVendor, String springVersion, String springBootVersion,
                              String mybatisVersion, String mybatisPlusVersion, boolean jakartaServlet,
                              boolean classRedefineSupported, boolean springPresent, boolean mybatisPresent,
                              List<String> capabilities) {
        this.jdkVersion = jdkVersion == null ? "unknown" : jdkVersion;
        this.jdkVendor = jdkVendor == null ? "unknown" : jdkVendor;
        this.springVersion = springVersion;
        this.springBootVersion = springBootVersion;
        this.mybatisVersion = mybatisVersion;
        this.mybatisPlusVersion = mybatisPlusVersion;
        this.jakartaServlet = jakartaServlet;
        this.classRedefineSupported = classRedefineSupported;
        this.springPresent = springPresent;
        this.mybatisPresent = mybatisPresent;
        this.capabilities = Collections.unmodifiableList(new ArrayList<String>(
                capabilities == null ? Collections.<String>emptyList() : capabilities));
    }

    public String getJdkVersion() { return jdkVersion; }
    public String getJdkVendor() { return jdkVendor; }
    public String getSpringVersion() { return springVersion; }
    public String getSpringBootVersion() { return springBootVersion; }
    public String getMybatisVersion() { return mybatisVersion; }
    public String getMybatisPlusVersion() { return mybatisPlusVersion; }
    public boolean isJakartaServlet() { return jakartaServlet; }
    public boolean isClassRedefineSupported() { return classRedefineSupported; }
    public boolean isSpringPresent() { return springPresent; }
    public boolean isMybatisPresent() { return mybatisPresent; }
    public List<String> getCapabilities() { return capabilities; }

    public Map<String, String> asLogFields() {
        Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("jdk", jdkVersion);
        fields.put("vendor", jdkVendor);
        fields.put("spring", springVersion == null ? "none" : springVersion);
        fields.put("springBoot", springBootVersion == null ? "none" : springBootVersion);
        fields.put("mybatis", mybatisVersion == null ? "none" : mybatisVersion);
        fields.put("mybatisPlus", mybatisPlusVersion == null ? "none" : mybatisPlusVersion);
        fields.put("servletApi", jakartaServlet ? "jakarta" : "javax");
        fields.put("classRedefine", Boolean.toString(classRedefineSupported));
        fields.put("capabilities", join(capabilities));
        return fields;
    }

    public String summary() {
        StringBuilder out = new StringBuilder(160);
        out.append("jdk=").append(jdkVersion);
        out.append(",spring=").append(nullSafe(springVersion));
        out.append(",boot=").append(nullSafe(springBootVersion));
        out.append(",mybatis=").append(nullSafe(mybatisVersion));
        out.append(",plus=").append(nullSafe(mybatisPlusVersion));
        out.append(",servlet=").append(jakartaServlet ? "jakarta" : "javax");
        out.append(",redefine=").append(classRedefineSupported);
        out.append(",caps=").append(capabilities.size());
        return out.toString();
    }

    private static String nullSafe(String value) { return value == null ? "none" : value; }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append('|');
            out.append(values.get(i));
        }
        return out.toString();
    }
}

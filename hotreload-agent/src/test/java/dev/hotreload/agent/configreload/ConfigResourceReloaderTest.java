package dev.hotreload.agent.configreload;

import dev.hotreload.protocol.message.ResourceReloadRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigResourceReloaderTest {
    @Test
    void parseSimpleYamlFlattensNestedKeys() {
        String yaml = "server:\n  port: 8080\n  servlet:\n    context-path: /app\nspring:\n  datasource:\n    url: jdbc:h2:mem:test\n";
        Map<String, String> map = ConfigResourceReloader.parseSimpleYaml(yaml);
        assertEquals("8080", map.get("server.port"));
        assertEquals("/app", map.get("server.servlet.context-path"));
        assertEquals("jdbc:h2:mem:test", map.get("spring.datasource.url"));
    }

    @Test
    void parseSimpleYamlIgnoresComments() {
        String yaml = "# comment\napp:\n  name: demo\n  enabled: true\n";
        Map<String, String> map = ConfigResourceReloader.parseSimpleYaml(yaml);
        assertEquals("demo", map.get("app.name"));
        assertEquals("true", map.get("app.enabled"));
    }

    @Test
    void parseSimpleYamlRejectsStructuresThatWouldOtherwiseApplyPartially() {
        assertThrows(IllegalArgumentException.class, () ->
                ConfigResourceReloader.parseSimpleYaml("app:\n  tags:\n    - one\n"));
        assertThrows(IllegalArgumentException.class, () ->
                ConfigResourceReloader.parseSimpleYaml("app: one\n---\napp: two\n"));
        assertThrows(IllegalArgumentException.class, () ->
                ConfigResourceReloader.parseSimpleYaml("base: &base value\ncopy: *base\n"));
        assertThrows(IllegalArgumentException.class, () ->
                ConfigResourceReloader.parseSimpleYaml("app:\nother: value\n"));
        assertThrows(IllegalArgumentException.class, () ->
                ConfigResourceReloader.parseSimpleYaml("app:\n  name: first\napp:\n  name: second\n"));
        assertThrows(IllegalArgumentException.class, () ->
                ConfigResourceReloader.parseSimpleYaml("app:\n  first: one\n second: two\n"));
        assertThrows(IllegalArgumentException.class, () ->
                ConfigResourceReloader.parseSimpleYaml("server.port:8080\n"));
    }

    @Test
    void parsesAColonInsideAnUnquotedKeyOnlyWhenFollowedByAMappingSeparator() {
        Map<String, String> map = ConfigResourceReloader.parseSimpleYaml(
                "namespace:key: value\nendpoint: http://localhost:8080/path\n");

        assertEquals("value", map.get("namespace:key"));
        assertEquals("http://localhost:8080/path", map.get("endpoint"));
    }

    @Test
    void reportsTheYamlParseFailureReason() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                ConfigResourceReloader.parseSimpleYaml("app:\n  tags:\n    - one\n"));

        assertEquals("IllegalArgumentException: YAML sequences are unsupported",
                ConfigResourceReloader.failureDiagnostic(failure));
    }

    @Test
    void parsesAnEmptySupportedConfigButRejectsUnsupportedFormats() throws Exception {
        Map<String, String> empty = ConfigResourceReloader.parse(new ResourceReloadRequest(
                "empty", "token", "application.properties", new byte[0], "properties"));

        assertNotNull(empty);
        assertTrue(empty.isEmpty());
        assertNull(ConfigResourceReloader.parse(new ResourceReloadRequest(
                "unsupported", "token", "data/config.xml", new byte[]{1}, "xml")));
    }

    @Test
    void installsAndReplacesPropertySourcesUsingTheirDeclaredBaseType() {
        FakeMutablePropertySources sources = new FakeMutablePropertySources();
        FakeMapPropertySource first = new FakeMapPropertySource("hotreload:application.yml");
        FakeMapPropertySource replacement = new FakeMapPropertySource("hotreload:application.yml");

        assertTrue(ConfigResourceReloader.installPropertySource(
                sources, "hotreload:application.yml", first, false));
        assertSame(first, sources.current);
        assertTrue(ConfigResourceReloader.installPropertySource(
                sources, "hotreload:application.yml", replacement, true));
        assertSame(replacement, sources.current);
        assertEquals(1, sources.addFirstCalls);
        assertEquals(1, sources.replaceCalls);
        assertTrue(ConfigResourceReloader.removePropertySource(
                sources, "hotreload:application.yml"));
        assertNull(sources.current);
        assertEquals(1, sources.removeCalls);
    }

    @Test
    void replacesOnlyTheMatchingApplicationSourceAtItsExistingPrecedence() {
        FakeMapPropertySource commandLine = new FakeMapPropertySource("commandLineArgs");
        FakeMapPropertySource application = new FakeMapPropertySource(
                "Config resource 'class path resource [config/application.yml]' via location 'classpath:/config/'");
        FakeMutablePropertySources sources = new FakeMutablePropertySources(commandLine, application);
        List<String> originals = ConfigResourceReloader.originalPropertySourceNames(
                sources, "config/application.yml", "hotreload:config/application.yml");
        FakeMapPropertySource replacement = new FakeMapPropertySource(
                "hotreload:config/application.yml");

        assertEquals(Arrays.asList(application.getName()), originals);
        assertTrue(ConfigResourceReloader.installReplacingPropertySources(
                sources, replacement.getName(), replacement, originals));
        assertEquals(Arrays.asList("commandLineArgs", "hotreload:config/application.yml"),
                sources.names());
        assertFalse(ConfigResourceReloader.sourceNameContainsResource(
                "class path resource [myapplication.yml]", "application.yml"));
        assertFalse(ConfigResourceReloader.sourceNameContainsResource(
                "class path resource [config/application.yml]", "application.yml"));
        assertTrue(ConfigResourceReloader.sourceNameContainsResource(
                "applicationConfig: [classpath:/application.yml]", "application.yml"));
        assertTrue(ConfigResourceReloader.sourceNameContainsResource(
                "class path resource [config/application.yml]", "config/application.yml"));
        assertFalse(ConfigResourceReloader.sourceNameContainsResource(
                "applicationConfig: [file:./config/application.yml]", "config/application.yml"));
        assertFalse(ConfigResourceReloader.sourceNameContainsResource(
                "Config resource 'file [D:/external/config/application.yml]'",
                "config/application.yml"));
        assertFalse(ConfigResourceReloader.sourceNameContainsResource(
                "notclasspath:/config/application.yml", "config/application.yml"));
    }

    @Test
    void anEmptyReloadKeepsAReplaceablePropertySourceAnchor() {
        FakeMapPropertySource original = new FakeMapPropertySource(
                "Config resource 'class path resource [application.yml]'");
        FakeMutablePropertySources sources = new FakeMutablePropertySources(original);
        FakeMapPropertySource empty = new FakeMapPropertySource("hotreload:application.yml");

        String cleared = ConfigResourceReloader.installReloadedPropertySource(
                sources, empty.getName(), empty, null,
                Collections.singletonList(original.getName()), 0);

        assertTrue(cleared.startsWith("ok keys=0"));
        assertEquals(Collections.singletonList("hotreload:application.yml"), sources.names());

        FakeMapPropertySource repopulated = new FakeMapPropertySource("hotreload:application.yml");
        String updated = ConfigResourceReloader.installReloadedPropertySource(
                sources, repopulated.getName(), repopulated, empty,
                Collections.<String>emptyList(), 1);

        assertTrue(updated.startsWith("ok keys=1"));
        assertSame(repopulated, sources.current);
        assertEquals(Collections.singletonList("hotreload:application.yml"), sources.names());
    }

    private abstract static class FakePropertySource {
        private final String name;

        private FakePropertySource(String name) {
            this.name = name;
        }

        public String getName() { return name; }
    }

    private static final class FakeMapPropertySource extends FakePropertySource {
        private FakeMapPropertySource(String name) {
            super(name);
        }
    }

    private static final class FakeMutablePropertySources implements Iterable<FakePropertySource> {
        private final Map<String, FakePropertySource> values =
                new LinkedHashMap<String, FakePropertySource>();
        private FakePropertySource current;
        private int addFirstCalls;
        private int replaceCalls;
        private int removeCalls;

        private FakeMutablePropertySources(FakePropertySource... initial) {
            if (initial != null) {
                for (FakePropertySource source : initial) values.put(source.getName(), source);
            }
        }

        public void addFirst(FakePropertySource source) {
            current = source;
            addFirstCalls++;
            LinkedHashMap<String, FakePropertySource> reordered =
                    new LinkedHashMap<String, FakePropertySource>();
            reordered.put(source.getName(), source);
            reordered.putAll(values);
            values.clear();
            values.putAll(reordered);
        }

        public void addLast(FakePropertySource source) {
            current = source;
            values.put(source.getName(), source);
        }

        public void addBefore(String relativeName, FakePropertySource source) {
            if (!values.containsKey(relativeName)) throw new IllegalArgumentException("missing source");
            LinkedHashMap<String, FakePropertySource> reordered =
                    new LinkedHashMap<String, FakePropertySource>();
            for (Map.Entry<String, FakePropertySource> entry : values.entrySet()) {
                if (entry.getKey().equals(relativeName)) reordered.put(source.getName(), source);
                reordered.put(entry.getKey(), entry.getValue());
            }
            values.clear();
            values.putAll(reordered);
        }

        public void replace(String name, FakePropertySource source) {
            if (!values.containsKey(name)) throw new IllegalArgumentException("missing source");
            current = source;
            replaceCalls++;
            values.put(name, source);
        }

        public FakePropertySource remove(String name) {
            FakePropertySource removed = values.remove(name);
            if (removed == current) current = null;
            removeCalls++;
            return removed;
        }

        private List<String> names() {
            return new ArrayList<String>(values.keySet());
        }

        @Override public Iterator<FakePropertySource> iterator() {
            return values.values().iterator();
        }
    }
}

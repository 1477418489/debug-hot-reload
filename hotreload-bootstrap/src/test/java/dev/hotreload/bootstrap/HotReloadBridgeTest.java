package dev.hotreload.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HotReloadBridgeTest {
    @Test void tracksConfigurationsByIdentityWithoutRetainingFactoryClass() {
        String first = new String("same");
        String second = new String("same");

        HotReloadBridge.registerConfiguration(first, FakeFactory.class);
        HotReloadBridge.registerConfiguration(second, FakeFactory.class);
        try {
            List<ConfigurationHandle> handles = HotReloadBridge.snapshotConfigurations();
            assertTrue(containsIdentity(handles, first));
            assertTrue(containsIdentity(handles, second));
            assertEquals(FakeFactory.class.getName(), handleFor(handles, first).getFactoryClassName());
        } finally {
            HotReloadBridge.unregisterConfiguration(first);
            HotReloadBridge.unregisterConfiguration(second);
        }
    }

    @Test void coordinatesReadAndWriteLocksAndUnsafeStateIsMonotonic() {
        Object configuration = new Object();
        HotReloadBridge.registerConfiguration(configuration, FakeFactory.class);
        try {
            Object readToken = HotReloadBridge.enterRead(configuration);
            assertNull(HotReloadBridge.enterWrite(configuration, 1));
            HotReloadBridge.exitRead(readToken);

            WriteLockToken writeToken = HotReloadBridge.enterWrite(configuration, 100);
            assertNotNull(writeToken);
            HotReloadBridge.exitWrite(writeToken);

            assertFalse(handleFor(HotReloadBridge.snapshotConfigurations(), configuration).isReloadUnsafe());
            HotReloadBridge.markReloadUnsafe(configuration);
            assertTrue(handleFor(HotReloadBridge.snapshotConfigurations(), configuration).isReloadUnsafe());
            HotReloadBridge.registerConfiguration(configuration, FakeFactory.class);
            assertTrue(handleFor(HotReloadBridge.snapshotConfigurations(), configuration).isReloadUnsafe());
        } finally {
            HotReloadBridge.unregisterConfiguration(configuration);
        }
    }

    @Test void capturesOnlyIdsAddedByOneMapperParse() {
        FakeConfiguration configuration = new FakeConfiguration();
        configuration.mappedStatements.put("plus.injected", new Object());
        FakeParser parser = new FakeParser("mappers/DemoMapper.xml", "demo.Mapper");

        Object token = HotReloadBridge.beginMapperParse(configuration, parser, parser.resource);
        Object statement = new Object();
        configuration.mappedStatements.put("demo.Mapper.find", statement);
        configuration.resultMaps.put("demo.Mapper.result", new Object());
        HotReloadBridge.endMapperParse(token, true);
        HotReloadBridge.registerConfiguration(configuration, FakeFactory.class);
        try {
            ConfigurationHandle handle = handleFor(HotReloadBridge.snapshotConfigurations(), configuration);
            ResourceMetadata metadata = handle.getResourceMetadata("mappers/DemoMapper.xml");
            assertNotNull(metadata);
            assertEquals("mappers/DemoMapper.xml", metadata.getResourceId());
            assertEquals("demo.Mapper", metadata.getNamespace());
            assertEquals(FakeParser.class.getName(), metadata.getParserClassName());
            assertEquals(java.util.Collections.singletonList("demo.Mapper.find"),
                    metadata.getOwnedIds("mappedStatements"));
            assertSame(statement, metadata.getOwnedObject("mappedStatements", "demo.Mapper.find"));
            assertFalse(metadata.getOwnedIds("mappedStatements").contains("plus.injected"));
        } finally {
            HotReloadBridge.unregisterConfiguration(configuration);
        }
    }

    @Test void distinguishesStrictMapAliasesFromDotlessCacheNamespaces() {
        FakeConfiguration configuration = new FakeConfiguration();
        FakeParser parser = new FakeParser("mappers/CacheMapper.xml", "plainNamespace");
        Object token = HotReloadBridge.beginMapperParse(configuration, parser, parser.resource);
        Object qualifiedCache = new Object();
        Object dotlessCache = new Object();
        configuration.caches.put("demo.Mapper", qualifiedCache);
        configuration.caches.put("Mapper", qualifiedCache);
        configuration.caches.put("tenant.plainNamespace", new Object());
        configuration.caches.put("plainNamespace", dotlessCache);
        configuration.cacheRefMap.put("plainNamespace", "shared.Cache");

        assertTrue(HotReloadBridge.endMapperParse(token, true));
        HotReloadBridge.registerConfiguration(configuration, FakeFactory.class);
        try {
            ResourceMetadata metadata = handleFor(
                    HotReloadBridge.snapshotConfigurations(), configuration)
                    .getResourceMetadata(parser.resource);
            assertNotNull(metadata);
            assertSame(qualifiedCache, metadata.getOwnedObject("caches", "demo.Mapper"));
            assertFalse(metadata.getOwnedIds("caches").contains("Mapper"));
            assertSame(dotlessCache, metadata.getOwnedObject("caches", "plainNamespace"));
            assertEquals("shared.Cache",
                    metadata.getOwnedObject("cacheRefMap", "plainNamespace"));
        } finally {
            HotReloadBridge.unregisterConfiguration(configuration);
        }
    }

    @Test void failedCaptureDoesNotRepublishStaleOwnership() {
        FakeConfiguration configuration = new FakeConfiguration();
        String resource = "mappers/DemoMapper.xml";
        FakeParser valid = new FakeParser(resource, "demo.Mapper");
        Object initial = HotReloadBridge.beginMapperParse(configuration, valid, resource);
        configuration.mappedStatements.put("demo.Mapper.find", new Object());
        assertTrue(HotReloadBridge.endMapperParse(initial, true));
        HotReloadBridge.registerConfiguration(configuration, FakeFactory.class);
        try {
            ConfigurationHandle handle = handleFor(HotReloadBridge.snapshotConfigurations(), configuration);
            ResourceMetadata before = handle.getResourceMetadata(resource);
            FakeParser invalid = new FakeParser(resource, null);
            Object token = HotReloadBridge.beginMapperParse(configuration, invalid, resource);
            configuration.mappedStatements.put("demo.Mapper.other", new Object());

            assertFalse(HotReloadBridge.endMapperParse(token, true));
            assertSame(before, handle.getResourceMetadata(resource));
            assertTrue(handle.isReloadUnsafe());
        } finally {
            HotReloadBridge.unregisterConfiguration(configuration);
        }
    }

    @Test void doesNotPublishOwnershipForAMissingObjectIdentity() {
        FakeConfiguration configuration = new FakeConfiguration();
        String resource = "mappers/NullMapper.xml";
        FakeParser parser = new FakeParser(resource, "demo.Mapper");
        Object token = HotReloadBridge.beginMapperParse(configuration, parser, resource);
        configuration.mappedStatements.put("demo.Mapper.nullValue", null);

        assertFalse(HotReloadBridge.endMapperParse(token, true));
        HotReloadBridge.registerConfiguration(configuration, FakeFactory.class);
        try {
            ConfigurationHandle handle = handleFor(HotReloadBridge.snapshotConfigurations(), configuration);
            assertNull(handle.getResourceMetadata(resource));
            assertTrue(handle.isReloadUnsafe());
        } finally {
            HotReloadBridge.unregisterConfiguration(configuration);
        }
    }

    @Test void runtimeResourceUrisRejectAuthoritiesQueriesAndFragments() {
        assertEquals("mappers/Demo.xml", RuntimeResourceId.normalize(
                "FILE:/C:/workspace/target/classes/mappers/Demo.xml"));
        assertNull(RuntimeResourceId.normalize(
                "file://host/C:/workspace/target/classes/mappers/Demo.xml"));
        assertNull(RuntimeResourceId.normalize(
                "file:/C:/workspace/target/classes/mappers/Demo.xml?version=2"));
        assertNull(RuntimeResourceId.normalize(
                "file:/C:/workspace/target/classes/mappers/Demo.xml#fragment"));
    }

    @Test void capturesClasspathRelativeIdFromSpringFileResource() {
        FakeConfiguration configuration = new FakeConfiguration();
        String runtimeResource = "file [C:\\workspace\\target\\classes\\mappers\\DemoMapper.xml]";
        FakeParser parser = new FakeParser(runtimeResource, "demo.Mapper");

        Object token = HotReloadBridge.beginMapperParse(configuration, parser, runtimeResource);
        configuration.mappedStatements.put("demo.Mapper.find", new Object());
        HotReloadBridge.endMapperParse(token, true);
        HotReloadBridge.registerConfiguration(configuration, FakeFactory.class);
        try {
            ResourceMetadata metadata = handleFor(HotReloadBridge.snapshotConfigurations(), configuration)
                    .getResourceMetadata(runtimeResource);
            assertNotNull(metadata);
            assertEquals("mappers/DemoMapper.xml", metadata.getResourceId());
        } finally {
            HotReloadBridge.unregisterConfiguration(configuration);
        }
    }

    @Test void capturesClasspathRelativeIdFromGradleClassOutput() {
        FakeConfiguration configuration = new FakeConfiguration();
        String runtimeResource = "file:/C:/workspace/build/classes/java/main/mappers/GradleMapper.xml";
        FakeParser parser = new FakeParser(runtimeResource, "demo.Mapper");

        Object token = HotReloadBridge.beginMapperParse(configuration, parser, runtimeResource);
        configuration.mappedStatements.put("demo.Mapper.find", new Object());
        HotReloadBridge.endMapperParse(token, true);
        HotReloadBridge.registerConfiguration(configuration, FakeFactory.class);
        try {
            ResourceMetadata metadata = handleFor(HotReloadBridge.snapshotConfigurations(), configuration)
                    .getResourceMetadata(runtimeResource);
            assertNotNull(metadata);
            assertEquals("mappers/GradleMapper.xml", metadata.getResourceId());
        } finally {
            HotReloadBridge.unregisterConfiguration(configuration);
        }
    }

    @Test void preservesAClassesDirectoryInsideTheResourceId() {
        FakeConfiguration configuration = new FakeConfiguration();
        String runtimeResource = "file:/C:/workspace/target/classes/mappers/classes/NestedMapper.xml";
        FakeParser parser = new FakeParser(runtimeResource, "demo.Mapper");

        Object token = HotReloadBridge.beginMapperParse(configuration, parser, runtimeResource);
        configuration.mappedStatements.put("demo.Mapper.find", new Object());
        HotReloadBridge.endMapperParse(token, true);
        HotReloadBridge.registerConfiguration(configuration, FakeFactory.class);
        try {
            ResourceMetadata metadata = handleFor(HotReloadBridge.snapshotConfigurations(), configuration)
                    .getResourceMetadata(runtimeResource);
            assertNotNull(metadata);
            assertEquals("mappers/classes/NestedMapper.xml", metadata.getResourceId());
        } finally {
            HotReloadBridge.unregisterConfiguration(configuration);
        }
    }

    private static boolean containsIdentity(List<ConfigurationHandle> handles, Object configuration) {
        for (ConfigurationHandle handle : handles) {
            if (handle.getConfiguration() == configuration) return true;
        }
        return false;
    }

    private static ConfigurationHandle handleFor(List<ConfigurationHandle> handles, Object configuration) {
        for (ConfigurationHandle handle : handles) {
            if (handle.getConfiguration() == configuration) return handle;
        }
        fail("configuration was not registered");
        return null;
    }

    private static final class FakeFactory {
    }

    private static final class FakeConfiguration {
        private final Map<String, Object> mappedStatements = new LinkedHashMap<String, Object>();
        private final Map<String, Object> resultMaps = new LinkedHashMap<String, Object>();
        private final Map<String, Object> parameterMaps = new LinkedHashMap<String, Object>();
        private final Map<String, Object> keyGenerators = new LinkedHashMap<String, Object>();
        private final Map<String, Object> sqlFragments = new LinkedHashMap<String, Object>();
        private final Map<String, Object> caches = new LinkedHashMap<String, Object>();
        private final Map<String, Object> cacheRefMap = new LinkedHashMap<String, Object>();
    }

    private static final class FakeParser {
        private final String resource;
        private final FakeBuilderAssistant builderAssistant;

        private FakeParser(String resource, String namespace) {
            this.resource = resource;
            this.builderAssistant = new FakeBuilderAssistant(namespace);
        }
    }

    private static final class FakeBuilderAssistant {
        private final String currentNamespace;

        private FakeBuilderAssistant(String currentNamespace) {
            this.currentNamespace = currentNamespace;
        }
    }
}

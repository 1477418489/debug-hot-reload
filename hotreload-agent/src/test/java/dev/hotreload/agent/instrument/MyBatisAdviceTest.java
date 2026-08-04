package dev.hotreload.agent.instrument;

import dev.hotreload.bootstrap.ConfigurationHandle;
import dev.hotreload.bootstrap.HotReloadBridge;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MyBatisAdviceTest {
    @Test void factoryAdviceRegistersTheConfigurationByIdentity() {
        Object configuration = new Object();
        FactoryConstructorAdvice.onExit(configuration, new Object());
        try {
            List<ConfigurationHandle> handles = HotReloadBridge.snapshotConfigurations();
            assertEquals(1, handles.size());
            assertSame(configuration, handles.get(0).getConfiguration());
        } finally {
            HotReloadBridge.unregisterConfiguration(configuration);
        }
    }

    @Test void mapperAdviceUsesTheBridgeCaptureAndReadAdviceReleasesItsLock() {
        FakeConfiguration configuration = new FakeConfiguration();
        FakeParser parser = new FakeParser();
        Object parseToken = MapperParseAdvice.onEnter(parser, configuration, "mappers/Test.xml");
        configuration.mappedStatements.put("demo.Mapper.find", new Object());
        MapperParseAdvice.onExit(parseToken, null);
        FactoryConstructorAdvice.onExit(configuration, new Object());
        Object readToken = SqlSessionReadAdvice.onEnter(configuration);
        assertNotNull(readToken);
        SqlSessionReadAdvice.onExit(readToken);
        HotReloadBridge.unregisterConfiguration(configuration);
    }

    private static final class FakeConfiguration {
        private final java.util.Map<String, Object> mappedStatements = new java.util.LinkedHashMap<String, Object>();
        private final java.util.Map<String, Object> resultMaps = new java.util.LinkedHashMap<String, Object>();
        private final java.util.Map<String, Object> parameterMaps = new java.util.LinkedHashMap<String, Object>();
        private final java.util.Map<String, Object> keyGenerators = new java.util.LinkedHashMap<String, Object>();
        private final java.util.Map<String, Object> sqlFragments = new java.util.LinkedHashMap<String, Object>();
        private final java.util.Map<String, Object> caches = new java.util.LinkedHashMap<String, Object>();
    }

    private static final class FakeParser {
        private final FakeBuilderAssistant builderAssistant = new FakeBuilderAssistant();
        private final String resource = "mappers/Test.xml";
    }

    private static final class FakeBuilderAssistant {
        private final String currentNamespace = "demo.Mapper";
    }
}

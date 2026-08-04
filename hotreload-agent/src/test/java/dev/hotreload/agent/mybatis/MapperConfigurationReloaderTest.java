package dev.hotreload.agent.mybatis;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisXMLMapperBuilder;
import dev.hotreload.agent.logging.AgentSessionLogger;
import dev.hotreload.bootstrap.HotReloadBridge;
import dev.hotreload.protocol.message.MapperReloadRequest;
import dev.hotreload.protocol.message.MapperUpdate;
import dev.hotreload.protocol.message.OperationStatus;
import dev.hotreload.protocol.message.ReloadErrorCode;
import dev.hotreload.protocol.message.ReloadResponse;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.defaults.DefaultSqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MapperConfigurationReloaderTest {
    private static final String RESOURCE = "mappers/DemoMapper.xml";
    @TempDir Path tempDirectory;

    @Test void reloadsPlainMyBatisAndRollsBackAParserFailure() throws Exception {
        Configuration configuration = new Configuration();
        parse(configuration, xml("SELECT 1"), false);
        HotReloadBridge.registerConfiguration(configuration, DefaultSqlSessionFactory.class);
        AgentSessionLogger logger = new AgentSessionLogger("plain", tempDirectory.resolve("plain.log").toAbsolutePath());
        try {
            MapperConfigurationReloader reloader = new MapperConfigurationReloader(logger);
            ReloadResponse success = reloader.reload(request("r1", xml("SELECT 2")));
            assertEquals(OperationStatus.SUCCESS, success.getStatus());
            assertTrue(sql(configuration, "demo.Mapper.find").contains("SELECT 2"));

            ReloadResponse duplicate = reloader.reload(request("r2", xml("SELECT 2")));
            assertEquals(OperationStatus.SKIPPED, duplicate.getStatus());

            ReloadResponse failed = reloader.reload(request("r3", duplicateStatementXml()));
            assertEquals(OperationStatus.FAILED, failed.getStatus());
            assertEquals(ReloadErrorCode.XML_RELOAD_FAILED, failed.getErrorCode());
            assertTrue(sql(configuration, "demo.Mapper.find").contains("SELECT 2"));
        } finally {
            HotReloadBridge.unregisterConfiguration(configuration);
            logger.close();
        }
    }

    @Test void preservesStatementsInjectedOutsideThePlusXmlParser() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        parse(configuration, xml("SELECT 1"), true);
        MappedStatement injected = new MappedStatement.Builder(configuration, "demo.Mapper.injected",
                new StaticSqlSource(configuration, "SELECT 9"), SqlCommandType.SELECT).build();
        configuration.addMappedStatement(injected);
        HotReloadBridge.registerConfiguration(configuration, DefaultSqlSessionFactory.class);
        AgentSessionLogger logger = new AgentSessionLogger("plus", tempDirectory.resolve("plus.log").toAbsolutePath());
        try {
            ReloadResponse response = new MapperConfigurationReloader(logger).reload(request("r", xml("SELECT 3")));
            assertEquals(OperationStatus.SUCCESS, response.getStatus());
            assertSame(injected, configuration.getMappedStatement("demo.Mapper.injected"));
            assertTrue(sql(configuration, "demo.Mapper.find").contains("SELECT 3"));
        } finally {
            HotReloadBridge.unregisterConfiguration(configuration);
            logger.close();
        }
    }

    @Test void rejectsOwnershipDriftWithoutDeletingAReplacementObject() throws Exception {
        Configuration configuration = new Configuration();
        parse(configuration, xml("SELECT 1"), false);
        HotReloadBridge.registerConfiguration(configuration, DefaultSqlSessionFactory.class);
        MappedStatement replacement = new MappedStatement.Builder(configuration, "demo.Mapper.find",
                new StaticSqlSource(configuration, "SELECT 99"), SqlCommandType.SELECT).build();
        @SuppressWarnings("unchecked") Map<Object, Object> statements = (Map<Object, Object>)
                ConfigurationSnapshot.fieldValue(configuration, "mappedStatements");
        statements.remove("demo.Mapper.find");
        statements.put("demo.Mapper.find", replacement);
        AgentSessionLogger logger = new AgentSessionLogger("identity-drift",
                tempDirectory.resolve("identity-drift.log").toAbsolutePath());
        try {
            ReloadResponse response = new MapperConfigurationReloader(logger)
                    .reload(request("r", xml("SELECT 2")));

            assertEquals(OperationStatus.FAILED, response.getStatus());
            assertEquals(ReloadErrorCode.CONFIGURATION_DRIFT, response.getErrorCode());
            assertSame(replacement, configuration.getMappedStatement("demo.Mapper.find"));
        } finally {
            HotReloadBridge.unregisterConfiguration(configuration);
            logger.close();
        }
    }

    @Test void doesNotMatchAResourceByFilenameSuffix() throws Exception {
        Configuration configuration = new Configuration();
        parse(configuration, RESOURCE, xml("SELECT 1"), false);
        HotReloadBridge.registerConfiguration(configuration, DefaultSqlSessionFactory.class);
        AgentSessionLogger logger = new AgentSessionLogger("exact-id",
                tempDirectory.resolve("exact-id.log").toAbsolutePath());
        try {
            ReloadResponse response = new MapperConfigurationReloader(logger)
                    .reload(request("r", "DemoMapper.xml", xml("SELECT 2")));

            assertEquals(OperationStatus.FAILED, response.getStatus());
            assertEquals(ReloadErrorCode.RESOURCE_NOT_LOADED, response.getErrorCode());
            assertTrue(sql(configuration, "demo.Mapper.find").contains("SELECT 1"));
        } finally {
            HotReloadBridge.unregisterConfiguration(configuration);
            logger.close();
        }
        String logs = readLogs("exact-id.log");
        assertTrue(logs.contains("event=XML_RESULT"), logs);
        assertTrue(logs.contains("resultCode=RESOURCE_NOT_LOADED"), logs);
        assertTrue(logs.matches("(?s).*event=XML_RESULT[^\\r\\n]*durationMs=\\d+.*"), logs);
    }


    @Test void reloadsMapperOwnedByExactlyOneOfMultipleConfigurations() throws Exception {
        Configuration owner = new Configuration();
        Configuration other = new Configuration();
        parse(owner, xml("SELECT 1"), false);
        parse(other, "mappers/OtherMapper.xml", xml("other.Mapper", "SELECT 9"), false);
        HotReloadBridge.registerConfiguration(owner, DefaultSqlSessionFactory.class);
        HotReloadBridge.registerConfiguration(other, DefaultSqlSessionFactory.class);
        AgentSessionLogger logger = new AgentSessionLogger("multi-owner",
                tempDirectory.resolve("multi-owner.log").toAbsolutePath());
        try {
            ReloadResponse response = new MapperConfigurationReloader(logger)
                    .reload(request("r-multi", xml("SELECT 2")));

            assertEquals(OperationStatus.SUCCESS, response.getStatus());
            assertTrue(sql(owner, "demo.Mapper.find").contains("SELECT 2"));
            assertTrue(sql(other, "other.Mapper.find").contains("SELECT 9"));
        } finally {
            HotReloadBridge.unregisterConfiguration(owner);
            HotReloadBridge.unregisterConfiguration(other);
            logger.close();
        }
        String logs = readLogs("multi-owner");
        assertTrue(logs.contains("configurationCount=2"));
        assertTrue(logs.contains("resourceId=mappers/DemoMapper.xml"));
    }

    @Test void reloadsAllSameNamespaceOwnersWhenResourceIsShared() throws Exception {
        Configuration first = new Configuration();
        Configuration second = new Configuration();
        parse(first, xml("SELECT 1"), false);
        parse(second, xml("SELECT 1"), false);
        HotReloadBridge.registerConfiguration(first, DefaultSqlSessionFactory.class);
        HotReloadBridge.registerConfiguration(second, DefaultSqlSessionFactory.class);
        AgentSessionLogger logger = new AgentSessionLogger("multi-same",
                tempDirectory.resolve("multi-same.log").toAbsolutePath());
        try {
            ReloadResponse response = new MapperConfigurationReloader(logger)
                    .reload(request("r-same", xml("SELECT 2")));

            assertEquals(OperationStatus.SUCCESS, response.getStatus());
            assertNull(response.getErrorCode());
            assertTrue(sql(first, "demo.Mapper.find").contains("SELECT 2"));
            assertTrue(sql(second, "demo.Mapper.find").contains("SELECT 2"));
            assertEquals(2, response.getItems().size());
        } finally {
            HotReloadBridge.unregisterConfiguration(first);
            HotReloadBridge.unregisterConfiguration(second);
            logger.close();
        }
        String logs = readLogs("multi-same");
        assertTrue(logs.contains("ownerCount=2"));
        assertTrue(logs.contains("matchedOwnerCount=2"));
    }

    @SuppressWarnings("unchecked")
    @Test void preservesDotlessCacheWhenItsNameSharesAQualifiedSuffix() throws Exception {
        Map<String, Object> caches = new LinkedHashMap<String, Object>();
        Object qualified = new Object();
        Object dotless = new Object();
        caches.put("tenant.shared", qualified);
        caches.put("shared", dotless);

        Method canonicalEntries = ConfigurationSnapshot.class.getDeclaredMethod(
                "canonicalEntries", String.class, Map.class);
        canonicalEntries.setAccessible(true);
        Map<Object, Object> canonical =
                (Map<Object, Object>) canonicalEntries.invoke(null, "caches", caches);
        assertSame(dotless, canonical.get("shared"));

        caches.put("tenant.alias", qualified);
        caches.put("alias", qualified);
        canonical = (Map<Object, Object>) canonicalEntries.invoke(null, "caches", caches);
        assertFalse(canonical.containsKey("alias"));
    }

    @Test void requiresRestartWhenRemovingAnExistingMapperCache() throws Exception {
        Configuration configuration = new Configuration();
        parse(configuration, cachedXml("SELECT 1"), false);
        HotReloadBridge.registerConfiguration(configuration, DefaultSqlSessionFactory.class);
        AgentSessionLogger logger = new AgentSessionLogger("cache-topology",
                tempDirectory.resolve("cache-topology.log").toAbsolutePath());
        try {
            ReloadResponse response = new MapperConfigurationReloader(logger)
                    .reload(request("r-cache", xml("SELECT 2")));

            assertEquals(OperationStatus.RESTART_REQUIRED, response.getStatus());
            assertEquals(ReloadErrorCode.XML_RELOAD_FAILED, response.getErrorCode());
            assertTrue(sql(configuration, "demo.Mapper.find").contains("SELECT 1"));
        } finally {
            HotReloadBridge.unregisterConfiguration(configuration);
            logger.close();
        }
    }

    @Test void leavesEveryOwnerUntouchedWhenOneOwnerIsReloadUnsafe() throws Exception {
        Configuration first = new Configuration();
        Configuration second = new Configuration();
        parse(first, xml("SELECT 1"), false);
        parse(second, xml("SELECT 1"), false);
        HotReloadBridge.registerConfiguration(first, DefaultSqlSessionFactory.class);
        HotReloadBridge.registerConfiguration(second, DefaultSqlSessionFactory.class);
        HotReloadBridge.markReloadUnsafe(second);
        AgentSessionLogger logger = new AgentSessionLogger("multi-unsafe",
                tempDirectory.resolve("multi-unsafe.log").toAbsolutePath());
        try {
            ReloadResponse response = new MapperConfigurationReloader(logger)
                    .reload(request("r-unsafe", xml("SELECT 2")));

            assertEquals(OperationStatus.RESTART_REQUIRED, response.getStatus());
            assertEquals(ReloadErrorCode.ROLLBACK_FAILED, response.getErrorCode());
            assertTrue(sql(first, "demo.Mapper.find").contains("SELECT 1"));
            assertTrue(sql(second, "demo.Mapper.find").contains("SELECT 1"));
            assertTrue(response.getItems().stream()
                    .noneMatch(item -> item.getStatus() == OperationStatus.SUCCESS));
        } finally {
            HotReloadBridge.unregisterConfiguration(first);
            HotReloadBridge.unregisterConfiguration(second);
            logger.close();
        }
    }

    @Test void rollsBackEarlierOwnersWhenALaterOwnerCannotParse() throws Exception {
        Configuration left = new Configuration();
        Configuration right = new Configuration();
        while (System.identityHashCode(left) == System.identityHashCode(right)) {
            right = new Configuration();
        }
        Configuration first = System.identityHashCode(left) < System.identityHashCode(right)
                ? left : right;
        Configuration second = first == left ? right : left;
        parse(first, xml("SELECT 1"), false);
        FailingXmlMapperBuilder.fail = false;
        parseWithFailingBuilder(second, xml("SELECT 1"));
        HotReloadBridge.registerConfiguration(first, DefaultSqlSessionFactory.class);
        HotReloadBridge.registerConfiguration(second, DefaultSqlSessionFactory.class);
        AgentSessionLogger logger = new AgentSessionLogger("multi-rollback",
                tempDirectory.resolve("multi-rollback.log").toAbsolutePath());
        try {
            FailingXmlMapperBuilder.fail = true;
            ReloadResponse response = new MapperConfigurationReloader(logger)
                    .reload(request("r-rollback", xml("SELECT 2")));

            assertEquals(OperationStatus.FAILED, response.getStatus());
            assertEquals(ReloadErrorCode.XML_RELOAD_FAILED, response.getErrorCode());
            assertTrue(sql(first, "demo.Mapper.find").contains("SELECT 1"));
            assertTrue(sql(second, "demo.Mapper.find").contains("SELECT 1"));
            assertTrue(response.getItems().stream().anyMatch(item ->
                    "transaction_rolled_back".equals(item.getDiagnostic())));
        } finally {
            FailingXmlMapperBuilder.fail = false;
            HotReloadBridge.unregisterConfiguration(first);
            HotReloadBridge.unregisterConfiguration(second);
            logger.close();
        }
    }

    @Test void reloadsOnlyNamespaceMatchingOwnersAmongMultipleConfigurations() throws Exception {
        Configuration matching = new Configuration();
        Configuration otherNamespace = new Configuration();
        parse(matching, xml("SELECT 1"), false);
        parse(otherNamespace, RESOURCE, xml("other.Mapper", "SELECT 9"), false);
        HotReloadBridge.registerConfiguration(matching, DefaultSqlSessionFactory.class);
        HotReloadBridge.registerConfiguration(otherNamespace, DefaultSqlSessionFactory.class);
        AgentSessionLogger logger = new AgentSessionLogger("multi-ns",
                tempDirectory.resolve("multi-ns.log").toAbsolutePath());
        try {
            ReloadResponse response = new MapperConfigurationReloader(logger)
                    .reload(request("r-ns", xml("SELECT 2")));

            assertEquals(OperationStatus.SUCCESS, response.getStatus());
            assertTrue(sql(matching, "demo.Mapper.find").contains("SELECT 2"));
            assertTrue(sql(otherNamespace, "other.Mapper.find").contains("SELECT 9"));
            assertEquals(1, response.getItems().size());
        } finally {
            HotReloadBridge.unregisterConfiguration(matching);
            HotReloadBridge.unregisterConfiguration(otherNamespace);
            logger.close();
        }
    }

    @Test void returnsResourceNotLoadedWhenNoneOfMultipleConfigurationsOwnResource() throws Exception {
        Configuration first = new Configuration();
        Configuration second = new Configuration();
        parse(first, "mappers/A.xml", xml("a.Mapper", "SELECT 1"), false);
        parse(second, "mappers/B.xml", xml("b.Mapper", "SELECT 2"), false);
        HotReloadBridge.registerConfiguration(first, DefaultSqlSessionFactory.class);
        HotReloadBridge.registerConfiguration(second, DefaultSqlSessionFactory.class);
        AgentSessionLogger logger = new AgentSessionLogger("multi-none",
                tempDirectory.resolve("multi-none.log").toAbsolutePath());
        try {
            ReloadResponse response = new MapperConfigurationReloader(logger)
                    .reload(request("r-none", RESOURCE, xml("SELECT 3")));

            assertEquals(OperationStatus.FAILED, response.getStatus());
            assertEquals(ReloadErrorCode.RESOURCE_NOT_LOADED, response.getErrorCode());
        } finally {
            HotReloadBridge.unregisterConfiguration(first);
            HotReloadBridge.unregisterConfiguration(second);
            logger.close();
        }
    }

    @Test void reportsAnAmbiguousExactResourceIdWithoutMutation() throws Exception {
        Configuration configuration = new Configuration();
        parse(configuration, RESOURCE, xml("SELECT 1"), false);
        String springResource = "file [C:\\workspace\\target\\classes\\" + RESOURCE.replace('/', '\\') + "]";
        parse(configuration, springResource, xml("other.Mapper", "SELECT 9"), false);
        HotReloadBridge.registerConfiguration(configuration, DefaultSqlSessionFactory.class);
        AgentSessionLogger logger = new AgentSessionLogger("ambiguous-id",
                tempDirectory.resolve("ambiguous-id.log").toAbsolutePath());
        try {
            ReloadResponse response = new MapperConfigurationReloader(logger)
                    .reload(request("r", RESOURCE, xml("SELECT 2")));

            assertEquals(OperationStatus.FAILED, response.getStatus());
            assertEquals(ReloadErrorCode.RESOURCE_ID_AMBIGUOUS, response.getErrorCode());
            assertTrue(sql(configuration, "demo.Mapper.find").contains("SELECT 1"));
        } finally {
            HotReloadBridge.unregisterConfiguration(configuration);
            logger.close();
        }
    }

    private static void parse(Configuration configuration, byte[] content, boolean plus) {
        parse(configuration, RESOURCE, content, plus);
    }

    private static void parse(Configuration configuration, String resource, byte[] content, boolean plus) {
        Object parser = plus
                ? new MybatisXMLMapperBuilder(new ByteArrayInputStream(content), configuration, resource,
                        configuration.getSqlFragments())
                : new XMLMapperBuilder(new ByteArrayInputStream(content), configuration, resource,
                        configuration.getSqlFragments());
        Object token = HotReloadBridge.beginMapperParse(configuration, parser, resource);
        boolean success = false;
        try {
            if (parser instanceof MybatisXMLMapperBuilder) ((MybatisXMLMapperBuilder) parser).parse();
            else ((XMLMapperBuilder) parser).parse();
            success = true;
        } finally {
            HotReloadBridge.endMapperParse(token, success);
        }
    }

    private static void parseWithFailingBuilder(Configuration configuration, byte[] content) {
        FailingXmlMapperBuilder parser = new FailingXmlMapperBuilder(
                new ByteArrayInputStream(content), configuration, RESOURCE,
                configuration.getSqlFragments());
        Object token = HotReloadBridge.beginMapperParse(configuration, parser, RESOURCE);
        boolean success = false;
        try {
            parser.parse();
            success = true;
        } finally {
            HotReloadBridge.endMapperParse(token, success);
        }
    }

    private static MapperReloadRequest request(String requestId, byte[] content) throws Exception {
        return request(requestId, RESOURCE, content);
    }

    private static MapperReloadRequest request(String requestId, String resourceId, byte[] content) throws Exception {
        return new MapperReloadRequest(requestId, "token",
                new MapperUpdate(resourceId, MessageDigest.getInstance("SHA-256").digest(content), content));
    }

    private static String sql(Configuration configuration, String id) {
        return configuration.getMappedStatement(id).getBoundSql(null).getSql();
    }

    private String readLogs(String prefix) throws Exception {
        StringBuilder result = new StringBuilder();
        try (java.util.stream.Stream<Path> paths = Files.list(tempDirectory)) {
            for (Path path : (Iterable<Path>) paths
                    .filter(candidate -> candidate.getFileName().toString().startsWith(prefix))::iterator) {
                result.append(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
            }
        }
        return result.toString();
    }

    private static byte[] xml(String sql) {
        return xml("demo.Mapper", sql);
    }

    private static byte[] xml(String namespace, String sql) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" "
                + "\"https://mybatis.org/dtd/mybatis-3-mapper.dtd\">"
                + "<mapper namespace=\"" + namespace + "\"><select id=\"find\" resultType=\"int\">"
                + sql + "</select></mapper>").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] duplicateStatementXml() {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<mapper namespace=\"demo.Mapper\">"
                + "<select id=\"find\" resultType=\"int\">SELECT 4</select>"
                + "<select id=\"find\" resultType=\"int\">SELECT 5</select>"
                + "</mapper>").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] cachedXml(String sql) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" "
                + "\"https://mybatis.org/dtd/mybatis-3-mapper.dtd\">"
                + "<mapper namespace=\"demo.Mapper\"><cache/>"
                + "<select id=\"find\" resultType=\"int\">"
                + sql + "</select></mapper>").getBytes(StandardCharsets.UTF_8);
    }

    public static final class FailingXmlMapperBuilder extends XMLMapperBuilder {
        private static volatile boolean fail;

        public FailingXmlMapperBuilder(InputStream inputStream, Configuration configuration,
                                       String resource, Map<String, XNode> sqlFragments) {
            super(inputStream, configuration, resource, sqlFragments);
        }

        @Override public void parse() {
            if (fail) throw new IllegalStateException("forced mapper parse failure");
            super.parse();
        }
    }
}

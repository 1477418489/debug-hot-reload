package dev.hotreload.protocol.util;

import dev.hotreload.protocol.ProtocolLimits;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceTypeDetectorTest {
    @Test void detectsMapperXmlByRootElementRegardlessOfDirectory() throws Exception {
        assertTrue(ResourceTypeDetector.isMapperXml("sql/check/Demo.xml",
                xml("<mapper namespace=\"demo.Mapper\"/>")));
        assertTrue(ResourceTypeDetector.isMapperXml("com/example/dao/DemoMapper.XML",
                xml("<?xml version=\"1.0\"?><mapper namespace=\"demo.Mapper\">")));
        assertTrue(ResourceTypeDetector.isMapperXml("custom\\location\\Demo.xml",
                xml("<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" "
                        + "\"https://mybatis.org/dtd/mybatis-3-mapper.dtd\">"
                        + "<mapper namespace=\"demo.Mapper\"/>")));
        assertFalse(ResourceTypeDetector.isStaticResource("mapper/check/DemoMapper.xml"));
    }

    @Test void keepsXmlUnderStaticRootsInTheStaticPipeline() throws Exception {
        assertFalse(ResourceTypeDetector.isMapperXml("static/feed.xml",
                xml("<mapper namespace=\"demo.Mapper\"/>")));
        assertTrue(ResourceTypeDetector.isStaticResource("static/feed.xml"));
        assertFalse(ResourceTypeDetector.isMapperXml("public/mapper/feed.xml",
                xml("<mapper namespace=\"demo.Mapper\"/>")));
        assertTrue(ResourceTypeDetector.isStaticResource("public/mapper/feed.xml"));
        assertFalse(ResourceTypeDetector.isMapperXml("resources/mapper/feed.xml",
                xml("<mapper namespace=\"demo.Mapper\"/>")));
        assertTrue(ResourceTypeDetector.isStaticResource("resources/mapper/feed.xml"));
    }

    @Test void ignoresUnrelatedXml() throws Exception {
        assertFalse(ResourceTypeDetector.isMapperXml("dbgit/mysql/ddl-zongzhi.xml",
                xml("<databaseChangeLog/>")));
        assertFalse(ResourceTypeDetector.isStaticResource("dbgit/mysql/ddl-zongzhi.xml"));
        assertFalse(ResourceTypeDetector.isMapperXml("logback.xml",
                xml("<configuration/>")));
        assertFalse(ResourceTypeDetector.isStaticResource("logback.xml"));
        assertFalse(ResourceTypeDetector.isMapperXml("sql/Demo.txt",
                xml("<mapper namespace=\"demo.Mapper\"/>")));
    }

    @Test void excludesTemplateEngineResourcesFromTheStaticPipeline() throws Exception {
        assertFalse(ResourceTypeDetector.isStaticResource("templates/page.html"));
        assertFalse(ResourceTypeDetector.isStaticResource("templates/assets/app.css"));
        assertFalse(ResourceTypeDetector.isStaticResource("views/page.ftl"));
        assertFalse(ResourceTypeDetector.isStaticResource("views/page.mustache"));
        assertFalse(ResourceTypeDetector.isStaticResource("views/page.jsp"));
        assertFalse(ResourceTypeDetector.isMapperXml("templates/mapper-shaped.xml",
                xml("<mapper namespace=\"not.a.mybatis.resource\"/>")));
    }

    @Test void doesNotConfuseStaticFilenamesWithSpringConfigPrefixes() {
        assertTrue(ResourceTypeDetector.isStaticResource("bootstrap.js"));
        assertTrue(ResourceTypeDetector.isStaticResource("application.json"));
        assertTrue(ResourceTypeDetector.isStaticResource("static/application.properties"));
        assertTrue(ResourceTypeDetector.isStaticResource("public/bootstrap.yml"));
        assertTrue(ResourceTypeDetector.isStaticResource("resources/application.properties"));
        assertTrue(ResourceTypeDetector.isStaticResource(
                "META-INF/resources/application.yaml"));
        assertFalse(ResourceTypeDetector.isStaticResource("bootstrap.yml"));
        assertFalse(ResourceTypeDetector.isStaticResource("application.properties"));
    }

    @Test void boundsMapperDetectionBeforeTheRootElement() throws Exception {
        byte[] oversizedProlog = new byte[ProtocolLimits.MAX_ITEM_BYTES + 1];
        java.util.Arrays.fill(oversizedProlog, (byte) ' ');

        assertFalse(ResourceTypeDetector.isMapperXml("mappers/Huge.xml",
                new ByteArrayInputStream(oversizedProlog)));
    }

    @Test void treatsUncheckedParserInputFailuresAsNotMapperXml() throws Exception {
        InputStream broken = new InputStream() {
            @Override public int read() {
                throw new IllegalStateException("broken XML provider input");
            }
        };

        assertFalse(ResourceTypeDetector.isMapperXml("mappers/Broken.xml", broken));
    }

    private static ByteArrayInputStream xml(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}

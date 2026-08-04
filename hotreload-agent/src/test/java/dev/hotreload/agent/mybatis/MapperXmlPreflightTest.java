package dev.hotreload.agent.mybatis;

import dev.hotreload.protocol.message.MapperUpdate;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;

class MapperXmlPreflightTest {
    private final MapperXmlPreflight preflight = new MapperXmlPreflight();

    @Test void acceptsAStandaloneMapperWithLocalReferences() throws Exception {
        byte[] content = xml("<sql id=\"columns\">id</sql>"
                + "<select id=\"find\" resultType=\"int\">SELECT <include refid=\"columns\"/> FROM demo</select>");

        MapperDocument document = preflight.preflight(new MapperUpdate("mappers/DemoMapper.xml",
                sha256(content), content));

        assertEquals("demo.Mapper", document.getNamespace());
        assertArrayEquals(content, document.getContent());
    }

    @Test void rejectsDigestMismatchEntitiesCacheLangAndCrossNamespaceReferences() throws Exception {
        byte[] valid = xml("<select id=\"find\" resultType=\"int\">SELECT 1</select>");
        assertThrows(IllegalArgumentException.class, () -> preflight.preflight(
                new MapperUpdate("mappers/DemoMapper.xml", new byte[32], valid)));

        assertRejected("<!ENTITY secret \"value\">", "<select id=\"find\">&secret;</select>");
        assertRejected(null, "<cache/><select id=\"find\">SELECT 1</select>");
        assertRejected(null, "<select id=\"find\" lang=\"custom\">SELECT 1</select>");
        assertRejected(null, "<select id=\"find\" resultMap=\"other.Mapper.result\">SELECT 1</select>");
    }

    @Test void rejectsDocumentsBeyondTheDepthAndNodeBudgetsWithoutRecursion() throws Exception {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 256; i++) deep.append("<if test=\"true\">");
        deep.append("SELECT 1");
        for (int i = 0; i < 256; i++) deep.append("</if>");
        assertRejected(null, deep.toString());

        StringBuilder manyNodes = new StringBuilder();
        for (int i = 0; i < 100_001; i++) manyNodes.append("<if test=\"true\"/>");
        assertRejected(null, manyNodes.toString());
    }

    private void assertRejected(String internalSubset, String body) throws Exception {
        String doctype = "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" "
                + "\"https://mybatis.org/dtd/mybatis-3-mapper.dtd\""
                + (internalSubset == null ? ">" : " [" + internalSubset + "]>");
        byte[] content = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + doctype
                + "<mapper namespace=\"demo.Mapper\">" + body + "</mapper>")
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> preflight.preflight(
                new MapperUpdate("mappers/DemoMapper.xml", sha256(content), content)));
    }

    private static byte[] xml(String body) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" "
                + "\"https://mybatis.org/dtd/mybatis-3-mapper.dtd\">"
                + "<mapper namespace=\"demo.Mapper\">" + body + "</mapper>")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sha256(byte[] content) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(content);
    }
}

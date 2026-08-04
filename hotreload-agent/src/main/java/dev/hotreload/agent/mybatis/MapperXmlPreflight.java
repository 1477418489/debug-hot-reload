package dev.hotreload.agent.mybatis;

import dev.hotreload.protocol.message.MapperUpdate;
import org.xml.sax.Attributes;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.DefaultHandler2;

import javax.xml.XMLConstants;
import javax.xml.parsers.FactoryConfigurationError;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Performs bounded, non-DOM validation before a mapper reaches MyBatis. */
final class MapperXmlPreflight {
    private static final String MAPPER_PUBLIC_ID = "-//mybatis.org//DTD Mapper 3.0//EN";
    private static final String MAPPER_DTD = "https://mybatis.org/dtd/mybatis-3-mapper.dtd";
    private static final String MAPPER_DTD_HTTP = "http://mybatis.org/dtd/mybatis-3-mapper.dtd";
    private static final int MAX_DEPTH = 256;
    private static final int MAX_NODES = 100_000;
    private static final int MAX_ATTRIBUTES = 200_000;
    private static final long MAX_TEXT_CHARS = 4L * 1024L * 1024L;
    private static final Set<String> REFERENCE_ATTRIBUTES = new HashSet<String>(Arrays.asList(
            "refid", "resultMap", "parameterMap", "extends", "select"));

    MapperDocument preflight(MapperUpdate update) {
        if (update == null) throw new NullPointerException("update");
        byte[] content = update.getContent();
        if (!MessageDigest.isEqual(update.getSha256(), sha256(content))) {
            throw new IllegalArgumentException("Mapper SHA-256 does not match its content");
        }
        String xml = strictUtf8(content);
        String namespace = parseAndValidate(xml);
        return new MapperDocument(update.getResourceId(), namespace, update.getSha256(), content);
    }

    private static String strictUtf8(byte[] content) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
            return decoded.toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Mapper XML must be strict UTF-8", e);
        }
    }

    private static String parseAndValidate(String xml) {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            setFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
            setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", false);
            SAXParser parser = factory.newSAXParser();
            setProperty(parser, XMLConstants.ACCESS_EXTERNAL_DTD, "");
            setProperty(parser, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            XMLReader reader = parser.getXMLReader();
            BoundedMapperHandler handler = new BoundedMapperHandler();
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            reader.setEntityResolver(handler);
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
            reader.setProperty("http://xml.org/sax/properties/declaration-handler", handler);
            reader.parse(new InputSource(new StringReader(xml)));
            if (handler.getNamespace() == null) {
                throw new IllegalArgumentException("Mapper namespace must not be empty");
            }
            return handler.getNamespace();
        } catch (StackOverflowError e) {
            throw new IllegalArgumentException("Mapper XML nesting exceeds the safety limit", e);
        } catch (SAXException e) {
            throw new IllegalArgumentException("Mapper XML is not safe, well-formed XML", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("Mapper XML could not be read", e);
        } catch (ParserConfigurationException e) {
            throw new IllegalArgumentException("Mapper XML parser is unavailable", e);
        } catch (FactoryConfigurationError e) {
            throw new IllegalArgumentException("Mapper XML parser is unavailable", e);
        } catch (LinkageError e) {
            throw new IllegalArgumentException("Mapper XML parser is unavailable", e);
        } catch (RuntimeException e) {
            if (e instanceof IllegalArgumentException) throw (IllegalArgumentException) e;
            throw new IllegalArgumentException("Mapper XML parser failed", e);
        }
    }

    private static void setFeature(SAXParserFactory factory, String name, boolean value)
            throws ParserConfigurationException, SAXNotRecognizedException, SAXNotSupportedException {
        factory.setFeature(name, value);
    }

    private static void setProperty(SAXParser parser, String name, Object value) {
        try {
            parser.setProperty(name, value);
        } catch (SAXNotRecognizedException ignored) {
            // Java 8 providers may omit JAXP properties; SAX entity features remain mandatory.
        } catch (SAXNotSupportedException ignored) {
            // The SAX entity features above are mandatory; Java 8 providers may omit JAXP attributes.
        }
    }

    private static final class BoundedMapperHandler extends DefaultHandler2
            implements EntityResolver, ErrorHandler {
        private int depth;
        private long nodes;
        private long attributes;
        private long textChars;
        private boolean sawRoot;
        private boolean sawDtd;
        private String namespace;

        String getNamespace() { return namespace; }

        @Override public void startDocument() {
            depth = 0;
            nodes = 0L;
            attributes = 0L;
            textChars = 0L;
        }

        @Override public void startElement(String uri, String localName, String qName, Attributes values)
                throws SAXException {
            if (++depth > MAX_DEPTH) throw reject("Mapper XML nesting exceeds the safety limit");
            if (++nodes > MAX_NODES) throw reject("Mapper XML node budget exceeded");
            String name = localName(localName, qName);
            if (!sawRoot) {
                sawRoot = true;
                if (!"mapper".equals(name)) throw reject("Mapper XML root must be <mapper>");
                String rawNamespace = values.getValue("namespace");
                namespace = rawNamespace == null ? null : rawNamespace.trim();
                if (namespace == null || namespace.isEmpty()) {
                    throw reject("Mapper namespace must not be empty");
                }
            }
            if ("cache".equals(name) || "cache-ref".equals(name)) {
                throw reject("Mapper caches require restart");
            }
            if ("include".equals(name) && "http://www.w3.org/2001/XInclude".equals(uri)) {
                throw reject("XInclude is not supported");
            }
            for (int i = 0; i < values.getLength(); i++) {
                if (++attributes > MAX_ATTRIBUTES) throw reject("Mapper XML attribute budget exceeded");
                String attributeName = localName(values.getLocalName(i), values.getQName(i));
                String value = values.getValue(i);
                addText(value == null ? 0 : value.length());
                if ("lang".equals(attributeName) || "schemaLocation".equals(attributeName)) {
                    throw reject("Mapper attribute requires restart: " + attributeName);
                }
                if (REFERENCE_ATTRIBUTES.contains(attributeName)) validateReferences(value);
            }
        }

        @Override public void endElement(String uri, String localName, String qName) throws SAXException {
            if (depth <= 0) throw reject("Malformed XML element depth");
            depth--;
        }

        @Override public void characters(char[] ch, int start, int length) throws SAXException {
            addText(length);
        }

        @Override public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
            addText(length);
        }

        @Override public void comment(char[] ch, int start, int length) throws SAXException {
            addText(length);
            if (++nodes > MAX_NODES) throw reject("Mapper XML node budget exceeded");
        }

        @Override public void processingInstruction(String target, String data) throws SAXException {
            if (++nodes > MAX_NODES) throw reject("Mapper XML node budget exceeded");
        }

        @Override public void startDTD(String name, String publicId, String systemId) throws SAXException {
            if (sawDtd || !"mapper".equals(name) || !MAPPER_PUBLIC_ID.equals(publicId)
                    || !(MAPPER_DTD.equals(systemId) || MAPPER_DTD_HTTP.equals(systemId))) {
                throw reject("Only the official MyBatis Mapper DTD is supported");
            }
            sawDtd = true;
        }

        @Override public void internalEntityDecl(String name, String value) throws SAXException {
            throw reject("XML entities are not supported");
        }

        @Override public void externalEntityDecl(String name, String publicId, String systemId)
                throws SAXException {
            throw reject("XML entities are not supported");
        }

        @Override public void skippedEntity(String name) throws SAXException {
            throw reject("XML entities are not supported");
        }

        @Override public void startEntity(String name) throws SAXException {
            if (!"[dtd]".equals(name)) throw reject("XML entities are not supported");
        }

        @Override public InputSource resolveEntity(String publicId, String systemId) {
            return new InputSource(new StringReader(""));
        }

        @Override public InputSource resolveEntity(String name, String publicId, String baseURI,
                                                   String systemId) {
            return new InputSource(new StringReader(""));
        }

        @Override public void warning(SAXParseException exception) throws SAXException { throw exception; }
        @Override public void error(SAXParseException exception) throws SAXException { throw exception; }
        @Override public void fatalError(SAXParseException exception) throws SAXException { throw exception; }

        private void validateReferences(String raw) throws SAXException {
            if (raw == null) return;
            int tokenStart = -1;
            for (int index = 0; index <= raw.length(); index++) {
                boolean separator = index == raw.length() || raw.charAt(index) == ','
                        || Character.isWhitespace(raw.charAt(index));
                if (!separator) {
                    if (tokenStart < 0) tokenStart = index;
                    continue;
                }
                if (tokenStart >= 0) validateReference(raw, tokenStart, index);
                tokenStart = -1;
            }
        }

        private void validateReference(String raw, int start, int end) throws SAXException {
            int lastDot = -1;
            for (int index = end - 1; index >= start; index--) {
                if (raw.charAt(index) == '.') {
                    lastDot = index;
                    break;
                }
            }
            if (lastDot >= start && (end - start <= namespace.length()
                    || !raw.regionMatches(start, namespace, 0, namespace.length())
                    || raw.charAt(start + namespace.length()) != '.')) {
                throw reject("Cross-namespace mapper references require restart");
            }
        }

        private void addText(int length) throws SAXException {
            textChars += length;
            if (textChars > MAX_TEXT_CHARS) throw reject("Mapper XML text budget exceeded");
            if (++nodes > MAX_NODES) throw reject("Mapper XML node budget exceeded");
        }

        private static String localName(String localName, String qName) {
            return localName == null || localName.isEmpty() ? qName : localName;
        }

        private static SAXException reject(String message) { return new SAXException(message); }
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}

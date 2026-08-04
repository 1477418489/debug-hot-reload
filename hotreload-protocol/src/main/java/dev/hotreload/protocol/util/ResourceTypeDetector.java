package dev.hotreload.protocol.util;

import dev.hotreload.protocol.ProtocolLimits;
import org.xml.sax.Attributes;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.FactoryConfigurationError;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.Locale;

/**
 * 统一的资源类型检测工具：区分配置文件和静态资源。
 * IDEA 端和 Agent 端共用，确保判断逻辑一致。
 */
public final class ResourceTypeDetector {
    private ResourceTypeDetector() {}

    /**
     * 判断是否为静态资源（HTML/CSS/JS/图片/字体等）
     * @param resourcePath 资源相对路径（如 "static/app.js"）
     * @return true = 静态资源，false = 配置文件或其他
     */
    public static boolean isStaticResource(String resourcePath) {
        String normalized = normalize(resourcePath);
        if (normalized == null) return false;

        // Template engines own their own caches and lifecycle. This static-resource pipeline
        // intentionally does not call into those unrelated components.
        if (isTemplateRoot(normalized)) {
            return false;
        }

        // Location takes precedence: config-shaped files under Web roots are still served as
        // static resources, while the dedicated configuration pipeline excludes these roots.
        if (isStaticRoot(normalized)) {
            return true;
        }

        if (normalized.endsWith(".properties") || normalized.endsWith(".yml")
                || normalized.endsWith(".yaml")) {
            return false;
        }

        String ext = getExtension(normalized);
        if (ext == null) return false;

        switch (ext) {
            // HTML/CSS/JS
            case "html":
            case "htm":
            case "css":
            case "js":
            case "json":
            // 图片
            case "png":
            case "jpg":
            case "jpeg":
            case "gif":
            case "svg":
            case "ico":
            case "webp":
            // 字体
            case "woff":
            case "woff2":
            case "ttf":
            case "eot":
            case "otf":
                return true;
            default:
                return false;
        }
    }

    /** Detects a MyBatis Mapper by its root element without resolving external entities. */
    public static boolean isMapperXml(String resourcePath, InputStream content) throws IOException {
        String normalized = normalize(resourcePath);
        if (normalized == null || !normalized.endsWith(".xml")
                || isStaticRoot(normalized) || isTemplateRoot(normalized) || content == null) {
            return false;
        }
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            SAXParser parser = factory.newSAXParser();
            setProperty(parser, XMLConstants.ACCESS_EXTERNAL_DTD, "");
            setProperty(parser, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            XMLReader reader = parser.getXMLReader();
            reader.setEntityResolver(EmptyEntityResolver.INSTANCE);
            reader.setContentHandler(new RootElementHandler());
            try {
                reader.parse(new InputSource(new LimitedInputStream(
                        content, ProtocolLimits.MAX_ITEM_BYTES)));
            } catch (RootElementFound found) {
                return found.mapper;
            } catch (DetectionLimitException tooLarge) {
                return false;
            }
            return false;
        } catch (DetectionLimitException tooLarge) {
            return false;
        } catch (ParserConfigurationException e) {
            return false;
        } catch (SAXException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        } catch (FactoryConfigurationError e) {
            return false;
        } catch (LinkageError e) {
            return false;
        }
    }

    private static boolean isStaticRoot(String normalized) {
        return normalized.startsWith("static/")
                || normalized.startsWith("public/")
                || normalized.startsWith("resources/")
                || normalized.startsWith("meta-inf/resources/");
    }

    private static boolean isTemplateRoot(String normalized) {
        return normalized.startsWith("templates/");
    }

    private static String normalize(String resourcePath) {
        if (resourcePath == null || resourcePath.isEmpty()) return null;
        return resourcePath.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    /**
     * Type detection runs on the VFS callback thread.  Do not let a malformed or unrelated
     * multi-megabyte XML file turn a cheap root check into an unbounded read.
     */
    private static final class LimitedInputStream extends InputStream {
        private final InputStream delegate;
        private int remaining;

        private LimitedInputStream(InputStream delegate, int limit) {
            this.delegate = delegate;
            this.remaining = limit;
        }

        @Override public int read() throws IOException {
            if (remaining == 0) {
                if (delegate.read() >= 0) throw new DetectionLimitException();
                return -1;
            }
            int value = delegate.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (buffer == null) throw new NullPointerException("buffer");
            if (offset < 0 || length < 0 || offset > buffer.length - length) {
                throw new IndexOutOfBoundsException();
            }
            if (length == 0) return 0;
            if (remaining == 0) {
                if (delegate.read() >= 0) throw new DetectionLimitException();
                return -1;
            }
            int read = delegate.read(buffer, offset, Math.min(length, remaining));
            if (read > 0) remaining -= read;
            return read;
        }

        @Override public void close() {
            // The caller owns the original VFS stream.
        }
    }

    private static final class DetectionLimitException extends IOException {
    }

    private static String getExtension(String filename) {
        if (filename == null) return null;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return null;
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static void setProperty(SAXParser parser, String name, Object value) {
        try {
            parser.setProperty(name, value);
        } catch (SAXException ignored) {
            // Java 8 providers may omit JAXP access properties; entity resolution remains disabled.
        }
    }

    private static final class RootElementHandler extends DefaultHandler {
        @Override public void startElement(String uri, String localName, String qName, Attributes attributes)
                throws SAXException {
            String name = localName == null || localName.isEmpty() ? qName : localName;
            throw new RootElementFound("mapper".equals(name));
        }
    }

    private static final class RootElementFound extends SAXException {
        private final boolean mapper;

        private RootElementFound(boolean mapper) {
            this.mapper = mapper;
        }
    }

    private static final class EmptyEntityResolver implements EntityResolver {
        private static final EmptyEntityResolver INSTANCE = new EmptyEntityResolver();

        @Override public InputSource resolveEntity(String publicId, String systemId) {
            return new InputSource(new StringReader(""));
        }
    }
}

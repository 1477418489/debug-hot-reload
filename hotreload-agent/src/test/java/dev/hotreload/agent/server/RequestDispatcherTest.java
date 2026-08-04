package dev.hotreload.agent.server;

import dev.hotreload.agent.logging.AgentSessionLogger;
import dev.hotreload.protocol.message.ClassReloadRequest;
import dev.hotreload.protocol.message.ClassUpdate;
import dev.hotreload.protocol.message.MapperReloadRequest;
import dev.hotreload.protocol.message.MapperUpdate;
import dev.hotreload.protocol.message.ReloadErrorCode;
import dev.hotreload.protocol.message.ReloadRequest;
import dev.hotreload.protocol.message.ReloadResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestDispatcherTest {
    @TempDir Path tempDirectory;

    @Test void routesMapperAndClassRequests() throws Exception {
        AgentSessionLogger logger = new AgentSessionLogger("dispatcher",
                tempDirectory.resolve("agent.log").toAbsolutePath());
        Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Instrumentation.class}, (proxy, method, arguments) -> primitiveDefault(method.getReturnType()));
        try {
            RequestDispatcher dispatcher = new RequestDispatcher(instrumentation, logger);
            ReloadResponse classResponse = dispatcher.handle(new ClassReloadRequest("c", "token",
                    Collections.singletonList(new ClassUpdate("example.Type", new byte[]{1}))));
            assertEquals(ReloadErrorCode.CLASS_REDEFINE_UNSUPPORTED, classResponse.getErrorCode());

            ReloadResponse mapperResponse = dispatcher.handle(new MapperReloadRequest("m", "token",
                    new MapperUpdate("mappers/Test.xml", new byte[32], "<mapper/>".getBytes("UTF-8"))));
            assertEquals(ReloadErrorCode.XML_INVALID, mapperResponse.getErrorCode());
        } finally {
            logger.close();
        }
    }

    @Test void preservesTheRequestIdForAnUnsupportedReloadRequest() throws Exception {
        AgentSessionLogger logger = new AgentSessionLogger("unsupported",
                tempDirectory.resolve("unsupported-agent.log").toAbsolutePath());
        Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Instrumentation.class}, (proxy, method, arguments) -> primitiveDefault(method.getReturnType()));
        try {
            RequestDispatcher dispatcher = new RequestDispatcher(instrumentation, logger);
            ReloadResponse response = dispatcher.handle(new ReloadRequest() {
                @Override public String getRequestId() { return "future-request"; }
                @Override public String getToken() { return "token"; }
            });

            assertEquals("future-request", response.getRequestId());
            assertEquals(ReloadErrorCode.INTERNAL_ERROR, response.getErrorCode());
        } finally {
            logger.close();
        }
    }

    private static Object primitiveDefault(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return (char) 0;
        return null;
    }
}

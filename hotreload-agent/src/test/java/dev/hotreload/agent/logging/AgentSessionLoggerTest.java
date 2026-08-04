package dev.hotreload.agent.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class AgentSessionLoggerTest {
    @TempDir Path tempDirectory;

    @Test void writesBoundedSingleLineDiagnosticsWithoutSensitiveFields() throws Exception {
        Path pattern = tempDirectory.resolve("agent.log");
        AgentSessionLogger logger = new AgentSessionLogger("launch-1", pattern, 1024, 2);
        Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("requestId", "request-1");
        fields.put("queueSize", "2");
        fields.put("token", "super-secret");
        fields.put("xml", "<mapper>secret</mapper>");

        logger.log(Level.INFO, "HELLO_FAILED", fields);
        logger.close();
        logger.close();

        String content = readAllLogs(tempDirectory);
        assertTrue(content.contains("event=HELLO_FAILED"));
        assertTrue(content.contains("launchId=launch-1"));
        assertTrue(content.contains("requestId=request-1"));
        assertTrue(content.contains("thread="));
        assertFalse(content.contains("super-secret"));
        assertFalse(content.contains("<mapper>"));
    }

    private static String readAllLogs(Path directory) throws Exception {
        StringBuilder result = new StringBuilder();
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                result.append(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
            }
        }
        return result.toString();
    }
}

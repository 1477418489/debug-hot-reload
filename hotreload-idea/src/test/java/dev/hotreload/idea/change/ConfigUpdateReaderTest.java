package dev.hotreload.idea.change;

import dev.hotreload.protocol.ProtocolLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigUpdateReaderTest {
    @TempDir Path temporaryDirectory;

    @Test void readsAnEmptySupportedConfigFromItsSourceRoot() throws Exception {
        Path sourceRoot = Files.createDirectories(temporaryDirectory.resolve("resources"));
        Path config = sourceRoot.resolve("config/application-local.yml");
        Files.createDirectories(config.getParent());
        Files.createFile(config);

        ConfigUpdateReader.Result result = ConfigUpdateReader.read(sourceRoot, config);

        assertEquals("config/application-local.yml", result.getResourceId());
        assertEquals(0, result.getContentLength());
        assertArrayEquals(new byte[0], result.getContent());
    }

    @Test void rejectsFilesOutsideTheSourceRootOrAboveTheProtocolLimit() throws Exception {
        Path sourceRoot = Files.createDirectories(temporaryDirectory.resolve("resources"));
        Path outside = Files.write(temporaryDirectory.resolve("application.yml"), new byte[]{1});
        Path oversized = sourceRoot.resolve("application.properties");
        Files.write(oversized, new byte[ProtocolLimits.MAX_ITEM_BYTES + 1]);

        assertThrows(IllegalArgumentException.class,
                () -> ConfigUpdateReader.read(sourceRoot, outside));
        assertThrows(IllegalArgumentException.class,
                () -> ConfigUpdateReader.read(sourceRoot, oversized));
    }

    @Test void rejectsConfigShapedFilesOwnedByStaticOrTemplatePipelines() throws Exception {
        Path sourceRoot = Files.createDirectories(temporaryDirectory.resolve("resources"));
        Path staticConfig = sourceRoot.resolve("static/application.properties");
        Files.createDirectories(staticConfig.getParent());
        Files.write(staticConfig, new byte[]{1});

        assertThrows(IllegalArgumentException.class,
                () -> ConfigUpdateReader.read(sourceRoot, staticConfig));
    }

    @Test void rejectsAnEmptySpringProfileSuffix() {
        assertFalse(ConfigUpdateReader.isReloadableConfigPath("application-.yml"));
        assertFalse(ConfigUpdateReader.isReloadableConfigPath("bootstrap-.properties"));
    }
}

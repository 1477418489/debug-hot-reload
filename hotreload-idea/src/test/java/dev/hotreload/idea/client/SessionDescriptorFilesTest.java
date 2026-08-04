package dev.hotreload.idea.client;

import dev.hotreload.protocol.session.SessionDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SessionDescriptorFilesTest {
    @TempDir Path tempDirectory;

    @Test void deletesOnlyDescriptorOwnedByTheExpectedLaunch() throws Exception {
        Path descriptor = tempDirectory.resolve("session.properties");
        new SessionDescriptor("launch-a", 1, 1234).writeAtomically(descriptor);

        assertFalse(SessionDescriptorFiles.deleteIfOwned(descriptor, "launch-b"));
        assertTrue(Files.exists(descriptor));
        assertTrue(SessionDescriptorFiles.deleteIfOwned(descriptor, "launch-a"));
        assertFalse(Files.exists(descriptor));
    }
}

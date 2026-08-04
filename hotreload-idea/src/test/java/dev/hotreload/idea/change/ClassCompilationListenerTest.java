package dev.hotreload.idea.change;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.hotreload.protocol.message.ClassUpdate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClassCompilationListenerTest {
    @TempDir Path tempDirectory;

    @Test void acceptsAFileUriRegardlessOfSchemeCase() {
        String upperCaseUri = tempDirectory.toUri().toString().replaceFirst("^file:", "FILE:");

        assertEquals(tempDirectory.toAbsolutePath().normalize(),
                ClassCompilationListener.toPath(upperCaseUri));
    }

    @Test void rejectsNonFileAndDecoratedUris() {
        assertThrows(IllegalArgumentException.class,
                () -> ClassCompilationListener.toPath("https://example.invalid/classes"));
        assertThrows(IllegalArgumentException.class,
                () -> ClassCompilationListener.toPath("file:/classes?build=1"));
        assertThrows(IllegalArgumentException.class,
                () -> ClassCompilationListener.toPath("file:/classes#output"));
    }

    @Test void keepsConcurrentCompilerThreadsInSeparateBatches() throws Exception {
        Path firstRoot = createClass("first-output", "demo/First.class", new byte[]{1});
        Path secondRoot = createClass("second-output", "demo/Second.class", new byte[]{2});
        List<List<ClassUpdate>> reloaded = Collections.synchronizedList(
                new ArrayList<List<ClassUpdate>>());
        List<String> warnings = Collections.synchronizedList(new ArrayList<String>());
        ClassCompilationListener listener = new ClassCompilationListener(
                new ClassCompilationListener.SessionOperations() {
                    @Override public boolean isJavaReloadEnabled() {
                        return true;
                    }

                    @Override public String activeDebugLaunchForOutput(Path outputRoot) {
                        return "launch";
                    }

                    @Override public void recordWarning(String event, String reason) {
                        warnings.add(event + ":" + reason);
                    }

                    @Override public void reloadClasses(String launchId, List<ClassUpdate> updates) {
                        reloaded.add(updates);
                    }
                });
        CountDownLatch generated = new CountDownLatch(2);
        CountDownLatch finish = new CountDownLatch(1);
        Thread first = compilerThread(listener, firstRoot, "demo/First.class", generated, finish);
        Thread second = compilerThread(listener, secondRoot, "demo/Second.class", generated, finish);

        first.start();
        second.start();
        assertTrue(generated.await(2, TimeUnit.SECONDS));
        finish.countDown();
        first.join(2_000L);
        second.join(2_000L);

        assertEquals(2, reloaded.size());
        Set<String> binaryNames = new HashSet<String>();
        for (List<ClassUpdate> batch : reloaded) {
            assertEquals(1, batch.size());
            binaryNames.add(batch.get(0).getBinaryName());
        }
        assertEquals(new HashSet<String>(java.util.Arrays.asList("demo.First", "demo.Second")), binaryNames);
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test void splitsOneCompilerCompletionByDebugLaunch() throws Exception {
        Path firstRoot = createClass("launch-a-output", "demo/First.class", new byte[]{1});
        Path secondRoot = createClass("launch-b-output", "demo/Second.class", new byte[]{2});
        List<String> routedLaunches = new ArrayList<String>();
        List<List<ClassUpdate>> reloaded = new ArrayList<List<ClassUpdate>>();
        ClassCompilationListener listener = new ClassCompilationListener(
                new ClassCompilationListener.SessionOperations() {
                    @Override public boolean isJavaReloadEnabled() { return true; }

                    @Override public String activeDebugLaunchForOutput(Path outputRoot) {
                        return outputRoot.equals(firstRoot) ? "launch-a" : "launch-b";
                    }

                    @Override public void recordWarning(String event, String reason) {
                        throw new AssertionError(event + ":" + reason);
                    }

                    @Override public void reloadClasses(String launchId, List<ClassUpdate> updates) {
                        routedLaunches.add(launchId);
                        reloaded.add(updates);
                    }
                });

        listener.fileGenerated(firstRoot.toString(), "demo/First.class");
        listener.fileGenerated(secondRoot.toString(), "demo/Second.class");
        listener.compilationFinished(false, 0, 0, null);

        assertEquals(java.util.Arrays.asList("launch-a", "launch-b"), routedLaunches);
        assertEquals("demo.First", reloaded.get(0).get(0).getBinaryName());
        assertEquals("demo.Second", reloaded.get(1).get(0).getBinaryName());
    }

    @Test void usesClassSpecificRoutingSoShadowedOutputIsNotReloaded() throws Exception {
        Path output = createClass("shadowed-output", "demo/Shadowed.class", new byte[]{1});
        List<List<ClassUpdate>> reloaded = new ArrayList<List<ClassUpdate>>();
        ClassCompilationListener listener = new ClassCompilationListener(
                new ClassCompilationListener.SessionOperations() {
                    @Override public boolean isJavaReloadEnabled() { return true; }

                    @Override public String activeDebugLaunchForOutput(Path outputRoot) {
                        return "wrong-generic-route";
                    }

                    @Override public String activeDebugLaunchForClass(Path outputRoot,
                                                                       String relativePath) {
                        assertEquals(output, outputRoot);
                        assertEquals("demo/Shadowed.class", relativePath);
                        return null;
                    }

                    @Override public void recordWarning(String event, String reason) {
                        throw new AssertionError(event + ":" + reason);
                    }

                    @Override public void reloadClasses(String launchId, List<ClassUpdate> updates) {
                        reloaded.add(updates);
                    }
                });

        listener.fileGenerated(output.toString(), "demo/Shadowed.class");
        listener.compilationFinished(false, 0, 0, null);

        assertTrue(reloaded.isEmpty());
    }

    private Path createClass(String directory, String relativePath, byte[] content) throws Exception {
        Path root = tempDirectory.resolve(directory);
        Path classFile = root.resolve(relativePath);
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, content);
        return root;
    }

    private static Thread compilerThread(ClassCompilationListener listener, Path root, String relativePath,
                                         CountDownLatch generated, CountDownLatch finish) {
        return new Thread(() -> {
            try {
                listener.fileGenerated(root.toString(), relativePath);
                generated.countDown();
                finish.await(2, TimeUnit.SECONDS);
                listener.compilationFinished(false, 0, 0, null);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }
}

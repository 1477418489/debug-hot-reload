package dev.hotreload.integration;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

final class JvmFixtureProcess implements AutoCloseable {
    private static final String MAIN_CLASS = "dev.hotreload.integration.plain.PlainMyBatisApplication";
    private static final String OUTPUT_PREFIX = "HOTRELOAD_FIXTURE ";

    private final Process process;
    private final BufferedWriter input;
    private final ArrayBlockingQueue<String> output = new ArrayBlockingQueue<String>(256);
    private final Deque<String> recentOutput = new ArrayDeque<String>();
    private final ExecutorService readerExecutor;

    private JvmFixtureProcess(Process process) {
        this.process = process;
        this.input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.readerExecutor = Executors.newSingleThreadExecutor(new ReaderThreadFactory());
        readerExecutor.execute(this::readOutput);
    }

    static JvmFixtureProcess start(Path javaHome, Path agentJar, String fixtureClasspath,
                                   Path sessionPath, Path logPattern, String token,
                                   String launchId) throws IOException {
        return start(javaHome, agentJar, fixtureClasspath, sessionPath, logPattern, token,
                launchId, MAIN_CLASS, java.util.Collections.<String>emptyList());
    }

    static JvmFixtureProcess start(Path javaHome, Path agentJar, String fixtureClasspath,
                                   Path sessionPath, Path logPattern, String token,
                                   String launchId, String mainClass,
                                   List<String> extraVmArgs) throws IOException {
        Path javaExecutable = javaHome.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
        if (!Files.isRegularFile(javaExecutable)) {
            throw new IOException("Java executable does not exist: " + javaExecutable);
        }
        List<String> command = new ArrayList<String>();
        Path credentialPath = sessionPath.getParent().resolve("credential.token");
        Files.write(credentialPath, token.getBytes(StandardCharsets.US_ASCII),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        command.add(javaExecutable.toString());
        command.addAll(extraVmArgs);
        command.add("-javaagent:" + agentJar + "=" + options(sessionPath, logPattern,
                credentialPath, launchId));
        command.add("-cp");
        command.add(fixtureClasspath);
        command.add(mainClass);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        return new JvmFixtureProcess(builder.start());
    }

    void awaitReady() throws Exception {
        assertMessage("READY", await("READY"));
    }

    String command(String command) throws Exception {
        send(command);
        String value = await(command + "=");
        return value.substring((command + "=").length());
    }

    String commandUnchecked(String command) {
        try {
            return command(command);
        } catch (Exception e) {
            return "";
        }
    }

    void stopGracefully() throws Exception {
        if (!process.isAlive()) throw failure("fixture exited before STOP");
        send("STOP");
        assertMessage("STOPPED", await("STOPPED"));
        if (!process.waitFor(10, TimeUnit.SECONDS)) throw failure("fixture did not stop");
        if (process.exitValue() != 0) throw failure("fixture exited with " + process.exitValue());
    }

    private void send(String command) throws IOException {
        input.write(command);
        input.newLine();
        input.flush();
    }

    private String await(String expectedPrefix) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            String line = output.poll(100L, TimeUnit.MILLISECONDS);
            if (line != null && line.startsWith(expectedPrefix)) return line;
            if (!process.isAlive() && output.isEmpty()) throw failure("fixture exited while waiting for " + expectedPrefix);
        }
        throw failure("timed out waiting for " + expectedPrefix);
    }

    private void readOutput() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                remember(line);
                if (!line.startsWith(OUTPUT_PREFIX)) continue;
                String message = line.substring(OUTPUT_PREFIX.length());
                if (!output.offer(message)) {
                    output.poll();
                    output.offer(message);
                }
            }
        } catch (IOException e) {
            remember("reader failure: " + e.getClass().getSimpleName());
        }
    }

    synchronized String recentOutputText() {
        return String.join(System.lineSeparator(), recentOutput);
    }

    private synchronized void remember(String line) {
        if (recentOutput.size() == 200) recentOutput.removeFirst();
        recentOutput.addLast(line);
    }

    private synchronized AssertionError failure(String message) {
        return new AssertionError(message + System.lineSeparator() + String.join(System.lineSeparator(), recentOutput));
    }

    @Override public void close() throws Exception {
        try {
            input.close();
        } catch (IOException ignored) {
            // Process cleanup below is authoritative.
        }
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
        readerExecutor.shutdownNow();
        readerExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }

    private static String options(Path sessionPath, Path logPattern, Path credentialPath, String launchId) {
        return "session=" + encode(sessionPath) + ",log=" + encode(logPattern)
                + ",token=file:" + encode(credentialPath) + ",launch=" + launchId;
    }

    private static String encode(Path path) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(path.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void assertMessage(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + " but received " + actual);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static final class ReaderThreadFactory implements ThreadFactory {
        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "hotreload-e2e-output");
            thread.setDaemon(true);
            return thread;
        }
    }
}

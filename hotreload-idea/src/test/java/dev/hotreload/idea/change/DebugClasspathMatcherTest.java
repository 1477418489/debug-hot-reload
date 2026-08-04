package dev.hotreload.idea.change;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugClasspathMatcherTest {
    @TempDir Path tempDirectory;

    @Test void acceptsAResourcePresentOnlyInTheDebugOutputRoot() throws Exception {
        Path output = tempDirectory.resolve("app-classes");
        Path resource = output.resolve("mappers/Probe.xml");
        Files.createDirectories(resource.getParent());
        Files.write(resource, new byte[]{1});

        assertTrue(DebugClasspathMatcher.containsOutputRoot(output, Collections.singleton(output)));
        assertTrue(DebugClasspathMatcher.isReloadableMapperResource(output, "mappers/Probe.xml",
                Collections.singleton(output)));
    }

    @Test void prefersEventModuleOutputEvenIfSameRelativePathExistsElsewhere() throws Exception {
        Path first = tempDirectory.resolve("first");
        Path second = tempDirectory.resolve("second");
        for (Path root : Arrays.asList(first, second)) {
            Path resource = root.resolve("mappers/Probe.xml");
            Files.createDirectories(resource.getParent());
            Files.write(resource, new byte[]{1});
        }

        DebugClasspathMatcher.Decision decision = DebugClasspathMatcher.evaluateMapperResource(
                first, "mappers/Probe.xml", Arrays.asList(first, second));
        assertTrue(decision.isAccepted(), decision.summary());
        assertEquals(2, decision.getMatchCount());
        assertTrue(decision.getPreferredRoot().contains("first"), decision.getPreferredRoot());
        assertTrue(DebugClasspathMatcher.isReloadableMapperResource(first, "mappers/Probe.xml",
                Arrays.asList(first, second)));
    }

    @Test void acceptsSourceFallbackWhenEventModuleOutputDoesNotContainResource() throws Exception {
        Path first = tempDirectory.resolve("first");
        Path second = tempDirectory.resolve("second");
        Files.createDirectories(first);
        Files.createDirectories(second);

        DebugClasspathMatcher.Decision decision = DebugClasspathMatcher.evaluateMapperResource(
                first, "mappers/Probe.xml", Arrays.asList(first, second));
        assertTrue(decision.isAccepted(), decision.summary());
        assertEquals("ok_source_content_fallback", decision.reason());
        assertEquals(DebugClasspathMatcher.DecisionCode.OK_SOURCE_FALLBACK, decision.getCode());
        assertEquals(0, decision.getMatchCount());
        assertTrue(decision.getPreferredRoot().contains("first"), decision.getPreferredRoot());
    }

    @Test void rejectsMapperSourceFallbackWhenAnotherClasspathEntrySuppliesTheId() throws Exception {
        Path output = Files.createDirectories(tempDirectory.resolve("mapper-output-missing"));
        Path later = resourceRoot("mapper-later", "mappers/Probe.xml");

        DebugClasspathMatcher.Decision decision = DebugClasspathMatcher.evaluateMapperResource(
                output, "mappers/Probe.xml", Arrays.asList(output, later));

        assertFalse(decision.isAccepted(), decision.summary());
        assertEquals(DebugClasspathMatcher.DecisionCode.RESOURCE_SHADOWED, decision.getCode());
    }

    @Test void rejectsAMapperResourceShadowedByAnEarlierJar() throws Exception {
        Path archive = tempDirectory.resolve("mapper-dependency.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new JarEntry("mappers/Probe.xml"));
            output.write(new byte[]{1});
            output.closeEntry();
        }
        Path classes = resourceRoot("mapper-classes", "mappers/Probe.xml");

        DebugClasspathMatcher.Decision decision = DebugClasspathMatcher.evaluateMapperResource(
                classes, "mappers/Probe.xml", Arrays.asList(archive, classes));

        assertFalse(decision.isAccepted(), decision.summary());
        assertEquals(DebugClasspathMatcher.DecisionCode.RESOURCE_SHADOWED, decision.getCode());
    }

    @Test void rejectsAnOutputRootThatIsNotOnTheDebugClasspath() throws Exception {
        Path output = tempDirectory.resolve("app-classes");
        Path other = tempDirectory.resolve("other");
        Path resource = output.resolve("mappers/Probe.xml");
        Files.createDirectories(resource.getParent());
        Files.write(resource, new byte[]{1});
        Files.createDirectories(other);

        assertFalse(DebugClasspathMatcher.containsOutputRoot(output, Collections.singleton(other)));
        assertFalse(DebugClasspathMatcher.isReloadableMapperResource(output, "mappers/Probe.xml",
                Collections.singleton(other)));
    }

    @Test void rejectsAStaticResourceShadowedByAnEarlierDirectory() throws Exception {
        Path dependency = resourceRoot("dependency", "static/app.css");
        Path output = Files.createDirectories(tempDirectory.resolve("app-classes"));

        DebugClasspathMatcher.Decision decision = DebugClasspathMatcher.evaluateOrderedResource(
                output, "static/app.css", Arrays.asList(dependency, output));

        assertFalse(decision.isAccepted(), decision.summary());
        assertEquals(DebugClasspathMatcher.DecisionCode.RESOURCE_SHADOWED, decision.getCode());
        assertEquals("resource_shadowed_on_debug_classpath", decision.reason());
    }

    @Test void rejectsAClassGeneratedInALaterShadowedOutputRoot() throws Exception {
        Path dependency = resourceRoot("class-dependency", "demo/Duplicate.class");
        Path output = resourceRoot("class-output", "demo/Duplicate.class");

        DebugClasspathMatcher.Decision decision = DebugClasspathMatcher.evaluateLoadedResource(
                output, "demo/Duplicate.class", Arrays.asList(dependency, output));

        assertFalse(decision.isAccepted(), decision.summary());
        assertEquals(DebugClasspathMatcher.DecisionCode.RESOURCE_SHADOWED, decision.getCode());
    }

    @Test void routesAGeneratedClassBeforeItsFinalFileIsVisible() throws Exception {
        Path output = Files.createDirectories(tempDirectory.resolve("pending-class-output"));

        DebugClasspathMatcher.Decision decision = DebugClasspathMatcher.evaluateOrderedResource(
                output, "demo/Pending.class", Collections.singletonList(output));

        assertTrue(decision.isAccepted(), decision.summary());
        assertEquals(DebugClasspathMatcher.DecisionCode.OK, decision.getCode());
    }

    @Test void acceptsAStaticResourceWhenDuplicatesOnlyOccurLater() throws Exception {
        Path output = Files.createDirectories(tempDirectory.resolve("app-classes"));
        Path dependency = resourceRoot("dependency", "static/app.css");

        DebugClasspathMatcher.Decision decision = DebugClasspathMatcher.evaluateOrderedResource(
                output, "static/app.css", Arrays.asList(output, dependency));

        assertTrue(decision.isAccepted(), decision.summary());
    }

    @Test void acceptsAConfigurationOnlyWhenTheEventOutputCurrentlyContainsIt() throws Exception {
        Path output = resourceRoot("app-classes", "application.yml");
        Path dependency = resourceRoot("dependency", "application.yml");

        DebugClasspathMatcher.Decision decision = DebugClasspathMatcher.evaluateLoadedResource(
                output, "application.yml", Arrays.asList(output, dependency));

        assertTrue(decision.isAccepted(), decision.summary());
    }

    @Test void rejectsAConfigurationMissingFromTheEventOutput() throws Exception {
        Path output = Files.createDirectories(tempDirectory.resolve("app-classes"));
        Path dependency = resourceRoot("dependency", "application.yml");

        DebugClasspathMatcher.Decision decision = DebugClasspathMatcher.evaluateLoadedResource(
                output, "application.yml", Arrays.asList(output, dependency));

        assertFalse(decision.isAccepted(), decision.summary());
        assertEquals(DebugClasspathMatcher.DecisionCode.RESOURCE_NOT_IN_OUTPUT,
                decision.getCode());
        assertEquals("resource_missing_in_module_output", decision.reason());
    }

    @Test void rejectsAStaticResourceShadowedByAnEarlierJar() throws Exception {
        Path archive = tempDirectory.resolve("dependency.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new JarEntry("static/app.css"));
            output.write(new byte[]{1});
            output.closeEntry();
        }
        Path classes = Files.createDirectories(tempDirectory.resolve("app-classes"));

        DebugClasspathMatcher.Decision decision = DebugClasspathMatcher.evaluateOrderedResource(
                classes, "static/app.css", Arrays.asList(archive, classes));

        assertFalse(decision.isAccepted(), decision.summary());
        assertEquals(DebugClasspathMatcher.DecisionCode.RESOURCE_SHADOWED, decision.getCode());
    }

    @Test void ignoresAnOrdinaryClasspathFileBeforeTheOutputDirectory() throws Exception {
        Path unrelated = Files.write(tempDirectory.resolve("classpath.args"),
                new byte[]{1, 2, 3});
        Path output = resourceRoot("app-classes-plain-file", "static/app.css");

        DebugClasspathMatcher.Decision decision = DebugClasspathMatcher.evaluateOrderedResource(
                output, "static/app.css", Arrays.asList(unrelated, output));

        assertTrue(decision.isAccepted(), decision.summary());
        assertEquals(DebugClasspathMatcher.DecisionCode.OK, decision.getCode());
    }

    @Test void rejectsShadowingWhenTheEventOutputIsNotOnTheClasspath() throws Exception {
        Path earlier = resourceRoot("dependency-before-output", "static/app.css");
        Path output = Files.createDirectories(tempDirectory.resolve("not-on-classpath"));

        DebugClasspathMatcher.Decision decision = DebugClasspathMatcher.evaluateOrderedResource(
                output, "static/app.css", Collections.singletonList(earlier));

        assertFalse(decision.isAccepted(), decision.summary());
        assertEquals(DebugClasspathMatcher.DecisionCode.OUTPUT_NOT_ON_CLASSPATH,
                decision.getCode());
    }

    @Test void convertsAnOsInvalidResourcePathIntoABoundedDecision() throws Exception {
        Path output = Files.createDirectories(tempDirectory.resolve("invalid-resource-output"));

        DebugClasspathMatcher.Decision decision = DebugClasspathMatcher.evaluateOrderedResource(
                output, "static/bad\u0000name.css", Collections.singletonList(output));

        assertFalse(decision.isAccepted(), decision.summary());
        assertEquals(DebugClasspathMatcher.DecisionCode.BAD_INPUT, decision.getCode());
    }

    private Path resourceRoot(String name, String resourceId) throws Exception {
        Path root = tempDirectory.resolve(name);
        Path resource = root.resolve(resourceId);
        Files.createDirectories(resource.getParent());
        Files.write(resource, new byte[]{1});
        return root;
    }
}

import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    `java-library`
    id("com.gradleup.shadow") version "8.3.6"
}

val agentRuntime by configurations.creating

dependencies {
    implementation(project(":hotreload-protocol"))
    implementation("net.bytebuddy:byte-buddy:1.15.11")
    implementation("org.ow2.asm:asm:9.7.1")
    implementation("org.ow2.asm:asm-commons:9.7.1")
    compileOnly(project(":hotreload-bootstrap"))
    agentRuntime(project(":hotreload-protocol"))
    agentRuntime("net.bytebuddy:byte-buddy:1.15.11")
    agentRuntime("org.ow2.asm:asm:9.7.1")
    agentRuntime("org.ow2.asm:asm-commons:9.7.1")

    testImplementation(project(":hotreload-bootstrap"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("net.bytebuddy:byte-buddy:1.15.11")
    testImplementation("org.mybatis:mybatis:3.5.19")
    testImplementation("com.baomidou:mybatis-plus-core:3.5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

val bootstrapJar = project(":hotreload-bootstrap").tasks.named<Jar>("jar")
val protocolJar = project(":hotreload-protocol").tasks.named<Jar>("jar")

tasks.processResources {
    dependsOn(bootstrapJar)
    from(bootstrapJar.flatMap { it.archiveFile }) {
        into("bootstrap")
        rename { "hotreload-bootstrap.jar" }
    }
}

fun Manifest.agentAttributes() {
    attributes(
        "Premain-Class" to "dev.hotreload.agent.HotReloadAgent",
        "Can-Redefine-Classes" to "true",
        "Can-Retransform-Classes" to "true"
    )
}

tasks.jar {
    manifest { agentAttributes() }
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("agent")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { agentAttributes() }
    configurations = listOf(agentRuntime)
    relocate("net.bytebuddy", "dev.hotreload.agent.internal.bytebuddy")
    relocate("org.objectweb.asm", "dev.hotreload.agent.internal.asm")
    relocate("dev.hotreload.protocol", "dev.hotreload.agent.internal.protocol")
    mergeServiceFiles()
    exclude("bootstrap/hotreload-bootstrap.jar")
    exclude("dev/hotreload/bootstrap/**")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    dependsOn(tasks.classes, protocolJar)
    doLast {
        val archive = archiveFile.get().asFile
        val bootstrap = bootstrapJar.get().archiveFile.get().asFile
        val temporary = archive.resolveSibling(archive.name + ".tmp")
        ZipFile(archive).use { input ->
            ZipOutputStream(Files.newOutputStream(temporary.toPath())).use { output ->
                input.entries().asSequence().forEach { entry ->
                    if (entry.name == "bootstrap/hotreload-bootstrap.jar") return@forEach
                    val copied = ZipEntry(entry.name)
                    if (entry.time >= 0L) copied.time = entry.time
                    output.putNextEntry(copied)
                    if (!entry.isDirectory) input.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
                val embeddedBootstrap = ZipEntry("bootstrap/hotreload-bootstrap.jar")
                embeddedBootstrap.time = bootstrap.lastModified()
                output.putNextEntry(embeddedBootstrap)
                Files.newInputStream(bootstrap.toPath()).use { it.copyTo(output) }
                output.closeEntry()
            }
        }
        Files.move(temporary.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

tasks.register("verifyAgentJar") {
    group = "verification"
    description = "Checks that the standalone Agent JAR does not expose unrelocated dependencies."
    val shaded = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar")
    dependsOn(shaded)
    doLast {
        val archive = shaded.get().archiveFile.get().asFile
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().map { entry -> entry.name }.toList()
            val forbidden = listOf("net/bytebuddy/", "org/objectweb/asm/", "dev/hotreload/protocol/")
            check(entries.none { entry -> forbidden.any { entry.startsWith(it) } }) {
                "Agent JAR contains an unrelocated dependency package"
            }
            check(entries.any { it.startsWith("dev/hotreload/agent/internal/bytebuddy/") }) {
                "Agent JAR is missing relocated Byte Buddy classes"
            }
            check(entries.any { it.startsWith("dev/hotreload/agent/internal/asm/") }) {
                "Agent JAR is missing relocated ASM classes"
            }
            check(entries.any { it.startsWith("dev/hotreload/agent/internal/protocol/") }) {
                "Agent JAR is missing relocated protocol classes"
            }
            check(entries.count { it == "bootstrap/hotreload-bootstrap.jar" } == 1) {
                "Agent JAR must contain exactly one embedded bootstrap JAR"
            }
            check(entries.none { it.startsWith("dev/hotreload/bootstrap/") }) {
                "Agent JAR contains flattened bootstrap classes"
            }
        }
    }
}

tasks.named("check") {
    dependsOn("verifyAgentJar")
}

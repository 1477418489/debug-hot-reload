plugins {
    `java-library`
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

repositories {
    intellijPlatform { defaultRepositories() }
}

val localIdeaPath = providers.gradleProperty("ideaLocalPath")
val ideaCompilerHome = providers.gradleProperty("ideaCompilerHome")

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.named<JavaCompile>("compileJava") {
    options.release.set(21)
}

dependencies {
    implementation(project(":hotreload-protocol"))
    intellijPlatform {
        if (localIdeaPath.isPresent) local(localIdeaPath.get()) else intellijIdeaCommunity("2024.3")
        bundledPlugin("com.intellij.java")
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

if (ideaCompilerHome.isPresent) {
    tasks.withType<JavaCompile>().configureEach {
        options.isFork = true
        options.forkOptions.javaHome = file(ideaCompilerHome.get())
    }
}

val agentJar = project(":hotreload-agent").tasks.named<Jar>("shadowJar")
val verifyAgentJar = project(":hotreload-agent").tasks.named("verifyAgentJar")

tasks.named<Sync>("prepareSandbox") {
    // Packaging must validate the shaded Agent before copying it into the sandbox.
    dependsOn(verifyAgentJar)
    from(agentJar) {
        into("${project.name}/lib/agent")
        rename { "hotreload-agent.jar" }
    }
}

tasks.named("instrumentCode") { enabled = false }
tasks.named("instrumentTestCode") { enabled = false }
tasks.named("prepareTest") {
    (this as org.jetbrains.intellij.platform.gradle.tasks.aware.CoroutinesJavaAgentAware)
        .coroutinesJavaAgentFile
        .set(layout.buildDirectory.file("disabled-coroutines-javaagent.jar"))
}
tasks.withType<Test>().configureEach {
    doFirst {
        val platformArguments = jvmArgumentProviders
            .flatMap { it.asArguments().toList() }
            .filterNot { it.contains("kotlinx.coroutines.debug") }
        jvmArgumentProviders.clear()
        jvmArgs(platformArguments)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
            untilBuild = "262.*"
        }
    }
}

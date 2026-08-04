plugins {
    java
}

group = "dev.hotreload"
version = "1.0.1"

subprojects {
    apply(plugin = "java-library")
    group = rootProject.group
    version = rootProject.version

    repositories { mavenCentral() }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    tasks.named<JavaCompile>("compileJava") {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:-options")
        val java8Modules = setOf(
            "hotreload-protocol",
            "hotreload-bootstrap",
            "hotreload-agent",
            "plain-mybatis",
            "spring5-mybatis",
            "boot2-mybatis",
            "boot2-mybatis-plus"
        )
        options.release.set(if (project.name in java8Modules) 8 else 17)
    }

    tasks.named<JavaCompile>("compileTestJava") {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

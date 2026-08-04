plugins { `java-library` }

dependencies {
    testImplementation(project(":hotreload-protocol"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

val agentJar = project(":hotreload-agent").tasks.named<Jar>("shadowJar")
val plainFixture = project(":integration:plain-mybatis")
val fixtureClasspath = files(
    plainFixture.layout.buildDirectory.dir("classes/java/main"),
    plainFixture.layout.buildDirectory.dir("resources/main"),
    plainFixture.configurations.named("runtimeClasspath")
)
val matrixProperties = listOf(
    "hotreload.jdk8.home",
    "hotreload.jdk11.home",
    "hotreload.jdk21.home"
)

tasks.test {
    exclude("**/PlainAgentJvmIntegrationTest.class")
}

tasks.register<Test>("fullMatrixTest") {
    group = "verification"
    description = "Runs the minimal Agent integration flow on JDK 8, 11 and 21."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    include("**/PlainAgentJvmIntegrationTest.class")
    dependsOn(agentJar, ":integration:plain-mybatis:classes",
        ":integration:plain-mybatis:compileReloadJava")

    doFirst {
        val missing = matrixProperties.filterNot { providers.gradleProperty(it).isPresent }
        if (missing.isNotEmpty()) {
            throw GradleException("Missing required JDK properties: ${missing.joinToString()}")
        }
        systemProperty("hotreload.agent.jar", agentJar.get().archiveFile.get().asFile.absolutePath)
        systemProperty("hotreload.fixture.classpath", fixtureClasspath.asPath)
        systemProperty(
            "hotreload.fixture.reload.classes",
            plainFixture.layout.buildDirectory.dir("classes/java/reload").get().asFile.absolutePath
        )
        matrixProperties.forEach { systemProperty(it, providers.gradleProperty(it).get()) }
    }
}

val springFixture = project(":integration:boot2-mybatis")
val springFixtureClasspath = files(
    springFixture.layout.buildDirectory.dir("classes/java/main"),
    springFixture.layout.buildDirectory.dir("resources/main"),
    springFixture.configurations.named("runtimeClasspath")
)

tasks.test {
    exclude("**/SpringMvcAgentJvmIntegrationTest.class")
}

tasks.register<Test>("springMvcTest") {
    group = "verification"
    description = "Controller add-method hot reload on Spring Boot 2.7 + JDK8/DCEVM (E2)."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    include("**/SpringMvcAgentJvmIntegrationTest.class")
    dependsOn(agentJar, ":integration:boot2-mybatis:classes",
        ":integration:boot2-mybatis:compileReloadJava",
        ":integration:boot2-mybatis:compileReload2Java")

    doFirst {
        val jdk8 = providers.gradleProperty("hotreload.jdk8.home")
            .orElse(providers.systemProperty("hotreload.jdk8.home"))
        if (!jdk8.isPresent) throw GradleException("Missing hotreload.jdk8.home")
        systemProperty("hotreload.jdk8.home", jdk8.get())
        systemProperty("hotreload.agent.jar", agentJar.get().archiveFile.get().asFile.absolutePath)
        systemProperty("hotreload.spring.fixture.classpath", springFixtureClasspath.asPath)
        systemProperty(
            "hotreload.spring.reload.classes",
            springFixture.layout.buildDirectory.dir("classes/java/reload").get().asFile.absolutePath
        )
        systemProperty(
            "hotreload.spring.reload2.classes",
            springFixture.layout.buildDirectory.dir("classes/java/reload2").get().asFile.absolutePath
        )
        systemProperty(
            "hotreload.spring.main.classes",
            springFixture.layout.buildDirectory.dir("classes/java/main").get().asFile.absolutePath
        )
    }
}

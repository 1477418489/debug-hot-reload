pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

rootProject.name = "idea-hot-reload"
include(":hotreload-protocol", ":hotreload-bootstrap", ":hotreload-agent", ":hotreload-idea")
include(
    ":integration:test-support",
    ":integration:plain-mybatis",
    ":integration:spring5-mybatis",
    ":integration:boot2-mybatis",
    ":integration:boot2-mybatis-plus",
    ":integration:boot3-mybatis",
    ":integration:boot3-mybatis-plus",
    ":integration:e2e-tests"
)

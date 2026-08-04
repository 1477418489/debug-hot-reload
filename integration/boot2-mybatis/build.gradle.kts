plugins { `java-library` }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web:2.7.18")
    implementation("org.springframework.boot:spring-boot-starter-aop:2.7.18")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

// v2 sources for hot reload payloads (same classes with an added handler method).
val reload by sourceSets.creating {
    java.srcDir("src/reload/java")
    compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
}

val reload2 by sourceSets.creating {
    java.srcDir("src/reload2/java")
    compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
}

tasks.named<JavaCompile>(reload2.compileJavaTaskName) {
    options.encoding = "UTF-8"
    options.release.set(8)
}

tasks.named<JavaCompile>(reload.compileJavaTaskName) {
    options.encoding = "UTF-8"
    options.release.set(8)
}

tasks.named<JavaCompile>("compileJava") {
    options.encoding = "UTF-8"
    options.release.set(8)
}

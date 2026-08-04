plugins { `java-library` }

dependencies {
    implementation("org.mybatis:mybatis:3.5.19")
}

val reload by sourceSets.creating {
    java.srcDir("src/reload/java")
}

tasks.named<JavaCompile>(reload.compileJavaTaskName) {
    options.encoding = "UTF-8"
    options.release.set(8)
}

import org.gradle.api.tasks.compile.JavaCompile

plugins {
    java
}

val rendererVersion = providers.gradleProperty("rendererVersion")
val toolchainJavaVersion = providers.gradleProperty("java_toolchain_version")
    .orElse(JavaVersion.current().majorVersion)
    .get()
    .toInt()

dependencies {
    implementation("top.ceroxe.rt:renderer-api:${rendererVersion.get()}") {
        isTransitive = false
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(toolchainJavaVersion)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

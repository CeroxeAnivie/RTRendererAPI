import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.JavaExec

plugins {
    java
}

val rendererVersion = providers.gradleProperty("rendererVersion")
val toolchainJavaVersion = 25

dependencies {
    implementation("top.ceroxe.rt:renderer-api:${rendererVersion.get()}")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(toolchainJavaVersion)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // This isolated consumer is part of the Java 25 compatibility gate. Project properties must
    // never be able to relax or raise the bytecode/API baseline under test.
    options.release = 25
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.register("verifyPublishedRuntimeClasspath") {
    group = "verification"
    description = "Proves that the published POM supplies the backend and its Windows native runtime."

    doLast {
        val declaredModules = configurations.named("implementation").get().dependencies
            .map { "${it.group}:${it.name}:${it.version}" }
            .toSet()
        val expectedDeclaration = setOf(
            "top.ceroxe.rt:renderer-api:${rendererVersion.get()}"
        )
        if (declaredModules != expectedDeclaration) {
            throw GradleException(
                "Consumer must declare only renderer-api, found: $declaredModules"
            )
        }

        val resolvedComponents = configurations.named("runtimeClasspath").get()
            .incoming.resolutionResult.allComponents
            .mapNotNull { it.moduleVersion }
            .map { "${it.group}:${it.name}:${it.version}" }
            .toSet()
        val version = rendererVersion.get()
        val requiredComponents = listOf(
            "top.ceroxe.rt:renderer-api:$version",
            "top.ceroxe.rt:renderer-core:$version",
            "top.ceroxe.rt:renderer-nvidia:$version"
        )
        val missingComponents = requiredComponents.filterNot(resolvedComponents::contains)
        if (missingComponents.isNotEmpty()) {
            throw GradleException("Published modules were not resolved: $missingComponents")
        }

        val resolvedNames = configurations.named("runtimeClasspath").get().files
            .map { it.name }
            .toSet()
        val requiredNames = listOf(
            "lwjgl-3.4.3.jar",
            "lwjgl-3.4.3-natives-windows.jar",
            "lwjgl-vma-3.4.3.jar",
            "lwjgl-vma-3.4.3-natives-windows.jar",
            "lwjgl-shaderc-3.4.3.jar",
            "lwjgl-shaderc-3.4.3-natives-windows.jar"
        )
        val missing = requiredNames.filterNot(resolvedNames::contains)
        if (missing.isNotEmpty()) {
            throw GradleException("Published runtime classpath is incomplete: $missing")
        }

        configurations.named("runtimeClasspath").get().files.singleOrNull {
            it.name == "renderer-nvidia-$version.jar"
        } ?: throw GradleException("Published runtime classpath omits renderer-nvidia-$version.jar")
    }
}

val verifyPublishedApiRuntime = tasks.register<JavaExec>("verifyPublishedApiRuntime") {
    group = "verification"
    description = "Executes public API, provider linkage and embedded native hashes from published JARs without opening a GPU."
    dependsOn(tasks.named("classes"), tasks.named("verifyPublishedRuntimeClasspath"))
    classpath = sourceSets.main.get().runtimeClasspath
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(toolchainJavaVersion)
    })
    mainClass.set("consumer.PublishedApiRuntimeSelfTest")
    args(rendererVersion.get(), providers.gradleProperty("stagingRepository").get())
}

tasks.named("check") { dependsOn(verifyPublishedApiRuntime) }

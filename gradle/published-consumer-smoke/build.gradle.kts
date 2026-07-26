import org.gradle.api.tasks.compile.JavaCompile

plugins {
    java
}

val rendererVersion = providers.gradleProperty("rendererVersion")

dependencies {
    implementation("top.ceroxe.rt:renderer-api:${rendererVersion.get()}")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
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
            "top.ceroxe.rt:renderer-core:$version"
        )
        val missingComponents = requiredComponents.filterNot(resolvedComponents::contains)
        if (missingComponents.isNotEmpty()) {
            throw GradleException("Published modules were not resolved: $missingComponents")
        }

        val resolvedNames = configurations.named("runtimeClasspath").get().files
            .map { it.name }
            .toSet()
        val requiredNames = listOf(
            "lwjgl-3.4.2.jar",
            "lwjgl-3.4.2-natives-windows.jar",
            "lwjgl-vma-3.4.2.jar",
            "lwjgl-vma-3.4.2-natives-windows.jar",
            "lwjgl-shaderc-3.4.2.jar",
            "lwjgl-shaderc-3.4.2-natives-windows.jar"
        )
        val missing = requiredNames.filterNot(resolvedNames::contains)
        if (missing.isNotEmpty()) {
            throw GradleException("Published runtime classpath is incomplete: $missing")
        }
    }
}

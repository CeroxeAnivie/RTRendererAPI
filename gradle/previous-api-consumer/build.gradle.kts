import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
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

val previousRendererApiAbi = layout.buildDirectory.file(
    "abi/renderer-api-${rendererVersion.get()}.abi"
)

tasks.register("generatePreviousRendererApiAbi") {
    group = "verification"
    description = "Generates the exhaustive public ABI from the previous Maven Central API JAR."
    inputs.files(configurations.named("compileClasspath"))
    inputs.property("rendererVersion", rendererVersion)
    outputs.file(previousRendererApiAbi)

    doLast {
        val artifacts = configurations.compileClasspath.get().resolvedConfiguration.resolvedArtifacts
            .filter { artifact ->
                artifact.moduleVersion.id.group == "top.ceroxe.rt"
                        && artifact.name == "renderer-api"
                        && artifact.moduleVersion.id.version == rendererVersion.get()
            }
        if (artifacts.size != 1) {
            throw GradleException(
                "Expected exactly one previous Maven Central renderer-api JAR, found ${artifacts.size}"
            )
        }
        val apiJar = artifacts.single().file
        val classNames = ZipFile(apiJar).use { archive ->
            archive.entries().asSequence()
                .map { it.name }
                .filter { it.endsWith(".class") }
                .filterNot { it.endsWith("/package-info.class") || it == "module-info.class" }
                .map { it.removeSuffix(".class").replace('/', '.') }
                .distinct()
                .sorted()
                .toList()
        }
        if (classNames.isEmpty()) {
            throw GradleException("Previous Maven Central renderer-api JAR contains no class files: $apiJar")
        }

        val launcher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(toolchainJavaVersion)
        }.get()
        val javapExecutable = launcher.metadata.installationPath.file("bin/javap.exe").asFile
        if (!javapExecutable.isFile) {
            throw GradleException("JDK javap executable is missing: $javapExecutable")
        }
        val raw = providers.exec {
            commandLine(
                listOf(
                    javapExecutable.absolutePath,
                    "-classpath",
                    apiJar.absolutePath,
                    "-public",
                    "-s",
                    "-constants"
                ) + classNames
            )
        }.standardOutput.asText.get()

        // javap accepts explicitly named non-public classes, so retain only public binaries.
        val publicBlocks = raw.split(Regex("(?=Compiled from )"))
            .filter { Regex("(?m)^public\\s").containsMatchIn(it) }
            .map { it.replace("\r\n", "\n").trim() }
        if (publicBlocks.isEmpty()) {
            throw GradleException("Previous Maven Central renderer-api JAR exposes no public binary types")
        }
        val output = previousRendererApiAbi.get().asFile
        output.parentFile.mkdirs()
        output.writeText(publicBlocks.joinToString("\n\n", postfix = "\n"), StandardCharsets.UTF_8)
    }
}

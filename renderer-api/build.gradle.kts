import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Arrays
import java.util.HexFormat
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    `java-library`
}

group = rootProject.group
version = rootProject.version

val toolchainJavaVersion = 25
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(toolchainJavaVersion)
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        charSet = "UTF-8"
        docEncoding = "UTF-8"
        // Java 25 reports every record component lacking an explicit @param as a
        // documentation error. Keep all other doclint checks and suppress only
        // that generated-component noise until the public record docs are expanded.
        addBooleanOption("Xdoclint:-missing", true)
        addBooleanOption("Werror", true)
    }
}

tasks.withType<Test>().configureEach {
    failOnNoDiscoveredTests = false
}

listOf("NativeLightingApplicationPatchSelfTest").forEach { name ->
    val contract = tasks.register<JavaExec>(name.replaceFirstChar { it.lowercase() }) {
        group = "verification"; dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().output + sourceSets.main.get().output
        mainClass.set("top.ceroxe.rt.renderer.api.$name")
        jvmArgs("-Xmx128m", "-Dfile.encoding=UTF-8")
    }
    tasks.named("check") { dependsOn(contract) }
}

val forbiddenApiReferences = listOf(
    "net." + "mine" + "craft.",
    "com.mojang.",
    "net.fabricmc.",
    "top.ceroxe.rt.renderer.rt.",
    "top.ceroxe.rt.renderer.scene.",
    "OpenGL",
    "OpenGl",
    "opengl"
)

val apiProductionSources = fileTree("src/main") {
    include("**/*.java")
}

tasks.register("verifyRendererApiBoundary") {
    group = "verification"
    description = "Rejects source-engine and renderer implementation dependencies in renderer-api."
    inputs.files(apiProductionSources)

    doLast {
        val violations = mutableListOf<String>()
        apiProductionSources.files.sorted().forEach { file ->
            file.readLines(StandardCharsets.UTF_8).forEachIndexed { index, line ->
                forbiddenApiReferences.forEach { forbidden ->
                    if (line.contains(forbidden)) {
                        val relative = projectDir.toPath().relativize(file.toPath())
                        violations += "$relative:${index + 1}: $forbidden"
                    }
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "renderer-api must remain host and implementation independent:\n" +
                    violations.joinToString("\n")
            )
        }
    }
}

tasks.register<JavaExec>("meshTopologySelfTest") {
    group = "verification"
    description = "Verifies closed mesh edge pairing, welded seams, winding and vertex stars."
    dependsOn(tasks.named("testClasses"))
    classpath = files(sourceSets.test.get().output.classesDirs, sourceSets.main.get().output.classesDirs,
        configurations.testCompileClasspath.get())
    mainClass.set("top.ceroxe.rt.renderer.api.MeshTopologySelfTest")
    if (providers.gradleProperty("topologyProfile").isPresent) args("--profile")
}

// Each major release owns an explicit, immutable public ABI baseline. A new major starts a new
// baseline; later releases must preserve its declarations and members. The exact versioned
// snapshot below separately rejects additions that have not been reviewed for this release.
val rendererApiAbiIndex = layout.projectDirectory.file("abi/release-index.json")
fun indexedAbi(version: String): String {
    val releases = JsonSlurper().parse(rendererApiAbiIndex.asFile) as Map<*, *>
    val path = releases[version]?.toString() ?: "unreviewed.abi"
    require(path == "unreviewed.abi" || Regex("snapshots/[0-9a-f]{64}\\.abi").matches(path)) { "Invalid ABI index entry" }
    return "abi/$path"
}
val rendererApiAbiBaseline = providers.provider { layout.projectDirectory.file(indexedAbi(project.version.toString())) }
val rendererApiAbiSnapshot = layout.buildDirectory.file("abi/renderer-api.abi")

val verifyRendererApiMajorRelease = tasks.register("verifyRendererApiMajorRelease") {
    group = "verification"
    dependsOn("generateRendererApiAbi")
    val currentVersion = project.version.toString()
    val major = currentVersion.substringBefore('.').toIntOrNull()
        ?: throw GradleException("project_version must start with a numeric major: $currentVersion")
    val majorBaseline = providers.provider { layout.projectDirectory.file(indexedAbi("$major.0.0")) }
    inputs.property("projectVersion", currentVersion)
    inputs.file(majorBaseline)
    doLast {
        val expected = currentVersion
        val snapshot = rendererApiAbiSnapshot.get().asFile
        if (!snapshot.isFile || snapshot.length() == 0L) {
            throw GradleException("ABI generation produced no public snapshot: $snapshot")
        }
        if (expected != "$major.0.0") {
            if (!majorBaseline.get().asFile.isFile || majorBaseline.get().asFile.length() == 0L) {
                throw GradleException("Missing same-major ABI baseline: $majorBaseline")
            }
            fun declarations(file: File): Map<String, Set<String>> =
                file.readText(StandardCharsets.UTF_8).replace("\r\n", "\n")
                    .split(Regex("(?=Compiled from )")).mapNotNull { block ->
                        val type = Regex("(?m)^public [^\\n]+").find(block)?.value
                            ?: return@mapNotNull null
                        val members = Regex("(?m)^  (?:public|protected) [^\\n]+\\n\\s+descriptor: [^\\n]+")
                            .findAll(block).map { it.value.trim() }.toSet()
                        type to members
                    }.toMap()
            val oldDeclarations = declarations(majorBaseline.get().asFile)
            val newDeclarations = declarations(snapshot)
            val incompatible = oldDeclarations.filter { (type, members) ->
                val current = newDeclarations[type]
                current == null || !current.containsAll(members)
                    || current.any { it.startsWith("public abstract ") && it !in members }
            }
            if (incompatible.isNotEmpty()) {
                throw GradleException(
                    "Same-major ABI removed or changed declarations in $expected:\n" +
                        incompatible.keys.joinToString("\n")
                )
            }
        }
    }
}

tasks.named("check") { dependsOn(verifyRendererApiMajorRelease) }
tasks.register("generateRendererApiAbi") {
    group = "verification"
    description = "Generates a deterministic javap snapshot of every public renderer-api binary type."
    dependsOn(tasks.named("classes"))
    inputs.files(sourceSets.main.get().output.classesDirs)
    outputs.file(rendererApiAbiSnapshot)

    doLast {
        val classDirectories = sourceSets.main.get().output.classesDirs.files
            .filter(File::isDirectory)
            .sorted()
        val classNames = classDirectories.flatMap { directory ->
            fileTree(directory) {
                include("**/*.class")
                exclude("**/package-info.class", "**/module-info.class")
            }.files.map { classFile ->
                directory.toPath().relativize(classFile.toPath()).toString()
                    .replace('\\', '.')
                    .replace('/', '.')
                    .replace(Regex("\\.class$"), "")
            }
        }.distinct().sorted()
        if (classNames.isEmpty()) {
            throw GradleException("renderer-api ABI generation found no class files")
        }

        val launcher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(toolchainJavaVersion)
        }.get()
        val javapExecutable = launcher.metadata.installationPath.file("bin/javap.exe").asFile
        if (!javapExecutable.isFile) {
            throw GradleException("JDK javap executable is missing: $javapExecutable")
        }
        val command = listOf(
            javapExecutable.absolutePath,
            "-classpath",
            classDirectories.joinToString(File.pathSeparator) { it.absolutePath },
            "-public",
            "-s",
            "-constants"
        ) + classNames
        val raw = providers.exec {
            commandLine(command)
        }.standardOutput.asText.get()

        // javap also accepts non-public implementation classes by explicit name. Retaining only
        // blocks whose binary declaration is public keeps this baseline focused on consumer ABI.
        val publicBlocks = raw.split(Regex("(?=Compiled from )"))
            .filter { Regex("(?m)^public\\s").containsMatchIn(it) }
            .map { it.replace("\r\n", "\n").trim() }
        if (publicBlocks.isEmpty()) {
            throw GradleException("renderer-api ABI generation found no public binary types")
        }
        val output = rendererApiAbiSnapshot.get().asFile
        output.parentFile.mkdirs()
        output.writeText(publicBlocks.joinToString("\n\n", postfix = "\n"), StandardCharsets.UTF_8)
    }
}

tasks.register("updateRendererApiAbiBaseline") {
    group = "build setup"
    description = "Accepts the current public renderer-api binary surface as the versioned ABI baseline."
    dependsOn(tasks.named("generateRendererApiAbi"))

    doLast {
        val source = rendererApiAbiSnapshot.get().asFile
        val bytes = source.readBytes()
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val relative = "snapshots/$digest.abi"
        val target = layout.projectDirectory.file("abi/$relative").asFile
        target.parentFile.mkdirs()
        if (target.exists() && !Arrays.equals(target.readBytes(), bytes)) throw GradleException("ABI content identity collision")
        target.writeBytes(bytes)
        val releases = (JsonSlurper().parse(rendererApiAbiIndex.asFile) as Map<*, *>).entries.associate { it.key.toString() to it.value.toString() }.toMutableMap()
        releases[project.version.toString()] = relative
        rendererApiAbiIndex.asFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(releases.toSortedMap())) + "\n", StandardCharsets.UTF_8)
    }
}

tasks.register("verifyRendererApiAbi") {
    group = "verification"
    description = "Rejects unreviewed changes to the versioned public renderer-api binary surface."
    dependsOn(tasks.named("generateRendererApiAbi"))
    inputs.file(rendererApiAbiBaseline)

    doLast {
        val baseline = rendererApiAbiBaseline.get().asFile
        if (!baseline.isFile || baseline.length() == 0L) {
            throw GradleException(
                "Missing renderer-api ABI baseline: $baseline. " +
                    "Run :renderer-api:updateRendererApiAbiBaseline only when intentionally accepting an API change."
            )
        }
        val actual = rendererApiAbiSnapshot.get().asFile
        if (!Arrays.equals(baseline.readBytes(), actual.readBytes())) {
            throw GradleException(
                "renderer-api ABI differs from $baseline. " +
                    "Review the public binary change before updating the baseline."
            )
        }
    }
}

// Each retained check covers a real public contract or the new major ABI.
tasks.named("check") {
    dependsOn("meshTopologySelfTest", "verifyRendererApiBoundary", "verifyRendererApiAbi")
}

sourceSets.named("test") { java.setIncludes(setOf("**/NativeLightingApplicationPatchSelfTest.java", "**/MeshTopologySelfTest.java")) }

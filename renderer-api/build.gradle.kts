import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Arrays
import java.util.HexFormat
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    `java-library`
}

data class RuntimeProjectCoordinate(
    val group: String,
    val name: String,
    val version: String
)

data class RuntimeArtifact(
    val group: String,
    val name: String,
    val version: String,
    val classifier: String?,
    val file: File
)

data class RuntimeLicensePolicy(
    val id: String,
    val name: String
)

data class RuntimeArtifactInventory(
    val file: String,
    val classifier: String?,
    val sha256: String
)

data class RuntimeModuleInventory(
    val coordinate: String,
    val license: RuntimeLicensePolicy,
    val artifacts: List<RuntimeArtifactInventory>
)

group = rootProject.group
version = rootProject.version

val javaVersion = rootProject.providers.gradleProperty("java_version").get().toInt()

dependencies {
    // Consumers declare one coordinate. The API remains compile-time independent from the
    // backend, while the published runtime graph supplies the Windows NVIDIA implementation.
    runtimeOnly(project(":renderer-core"))
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = javaVersion
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        charSet = "UTF-8"
        docEncoding = "UTF-8"
        addBooleanOption("Werror", true)
    }
}

tasks.withType<Test>().configureEach {
    failOnNoDiscoveredTests = false
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

tasks.register<JavaExec>("rendererApiContractSelfTest") {
    group = "verification"
    description = "Verifies immutable scene, frame, provider, and GPU-frame ownership contracts."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.api.RendererApiContractSelfTest")
}

val rendererApiAbiBaseline = layout.projectDirectory.file("abi/renderer-api-${project.version}.abi")
val rendererApiAbiSnapshot = layout.buildDirectory.file("abi/renderer-api-${project.version}.abi")

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
            languageVersion = JavaLanguageVersion.of(javaVersion)
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
        val target = rendererApiAbiBaseline.asFile
        target.parentFile.mkdirs()
        target.writeBytes(source.readBytes())
    }
}

tasks.register("verifyRendererApiAbi") {
    group = "verification"
    description = "Rejects unreviewed changes to the versioned public renderer-api binary surface."
    dependsOn(tasks.named("generateRendererApiAbi"))
    inputs.file(rendererApiAbiBaseline)

    doLast {
        val baseline = rendererApiAbiBaseline.asFile
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

val runtimeSbomFile = rootProject.layout.buildDirectory.file("release/renderer-runtime.cdx.json")
val runtimeLicenseInventoryFile = rootProject.layout.buildDirectory.file("release/runtime-licenses.json")
val publishedApiGroup = group.toString()
val publishedApiName = name
val publishedApiVersion = version.toString()
val runtimeProjectCoordinates = rootProject.subprojects.associate { child ->
    child.path to RuntimeProjectCoordinate(
        child.group.toString(),
        child.name,
        child.version.toString()
    )
}
val runtimeLicensePolicy = mapOf(
    "top.ceroxe.rt:renderer-core" to RuntimeLicensePolicy("MIT", "MIT License"),
    "it.unimi.dsi:fastutil" to RuntimeLicensePolicy("Apache-2.0", "Apache License 2.0"),
    "org.joml:joml" to RuntimeLicensePolicy("MIT", "MIT License"),
    "org.lwjgl:lwjgl" to RuntimeLicensePolicy("BSD-3-Clause", "BSD 3-Clause License"),
    "org.lwjgl:lwjgl-shaderc" to RuntimeLicensePolicy("BSD-3-Clause", "BSD 3-Clause License"),
    "org.lwjgl:lwjgl-vma" to RuntimeLicensePolicy("BSD-3-Clause", "BSD 3-Clause License"),
    "org.lwjgl:lwjgl-vulkan" to RuntimeLicensePolicy("BSD-3-Clause", "BSD 3-Clause License"),
    "org.jetbrains:annotations" to RuntimeLicensePolicy("Apache-2.0", "Apache License 2.0"),
    "org.jetbrains.kotlin:kotlin-stdlib" to RuntimeLicensePolicy("Apache-2.0", "Apache License 2.0"),
    "org.jetbrains.kotlin:kotlin-stdlib-common" to RuntimeLicensePolicy("Apache-2.0", "Apache License 2.0"),
    "org.jetbrains.kotlin:kotlin-stdlib-jdk7" to RuntimeLicensePolicy("Apache-2.0", "Apache License 2.0"),
    "org.jetbrains.kotlin:kotlin-stdlib-jdk8" to RuntimeLicensePolicy("Apache-2.0", "Apache License 2.0")
)

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}

tasks.register("generateRuntimeSupplyChainMetadata") {
    group = "publishing"
    description = "Generates deterministic CycloneDX 1.6 SBOM and exact runtime license inventory."
    dependsOn(tasks.named("jar"))
    dependsOn(":renderer-core:jar")
    inputs.files(configurations.named("runtimeClasspath"))
    outputs.files(runtimeSbomFile, runtimeLicenseInventoryFile)

    doLast {
        val artifacts = configurations.named("runtimeClasspath").get().incoming.artifactView {
            isLenient = false
        }.artifacts.artifacts.map { resolvedArtifact ->
            val component = resolvedArtifact.id.componentIdentifier
            val coordinate = when (component) {
                is ModuleComponentIdentifier -> RuntimeProjectCoordinate(
                    component.group,
                    component.module,
                    component.version
                )
                is ProjectComponentIdentifier -> runtimeProjectCoordinates[component.projectPath]
                    ?: throw GradleException(
                        "Unknown runtime project component: ${component.projectPath}"
                    )
                else -> throw GradleException("Unsupported runtime component identity: $component")
            }
            val unclassifiedName = "${coordinate.name}-${coordinate.version}.jar"
            val artifactClassifier = if (resolvedArtifact.file.name == unclassifiedName) {
                null
            } else {
                val prefix = "${coordinate.name}-${coordinate.version}-"
                if (resolvedArtifact.file.name.startsWith(prefix) &&
                    resolvedArtifact.file.name.endsWith(".jar")
                ) {
                    resolvedArtifact.file.name.substring(
                        prefix.length,
                        resolvedArtifact.file.name.length - ".jar".length
                    )
                } else {
                    null
                }
            }
            RuntimeArtifact(
                coordinate.group,
                coordinate.name,
                coordinate.version,
                artifactClassifier,
                resolvedArtifact.file
            )
        }.sortedWith(
            compareBy(
                RuntimeArtifact::group,
                RuntimeArtifact::name,
                RuntimeArtifact::version,
                { it.classifier.orEmpty() },
                { it.file.name }
            )
        )
        if (artifacts.isEmpty()) {
            throw GradleException("renderer-api runtime classpath resolved no artifacts")
        }

        val groupedArtifacts = artifacts.groupBy { "${it.group}:${it.name}:${it.version}" }
        val unknownLicenses = groupedArtifacts.keys.filter { coordinate ->
            val moduleKey = coordinate.substringBeforeLast(':')
            moduleKey !in runtimeLicensePolicy
        }.sorted()
        if (unknownLicenses.isNotEmpty()) {
            throw GradleException("Runtime license policy is missing: $unknownLicenses")
        }

        val inventory = groupedArtifacts.map { (coordinate, moduleArtifacts) ->
            val moduleKey = coordinate.substringBeforeLast(':')
            val policy = checkNotNull(runtimeLicensePolicy[moduleKey])
            RuntimeModuleInventory(
                coordinate,
                policy,
                moduleArtifacts.map { artifact ->
                    RuntimeArtifactInventory(
                        artifact.file.name,
                        artifact.classifier,
                        sha256(artifact.file)
                    )
                }.sortedBy(RuntimeArtifactInventory::file)
            )
        }.sortedBy(RuntimeModuleInventory::coordinate)

        val components = inventory.map { module ->
            val parts = module.coordinate.split(':', limit = 3)
            val primaryArtifact = module.artifacts.firstOrNull { it.classifier == null }
                ?: module.artifacts.first()
            mapOf<String, Any>(
                "type" to "library",
                "bom-ref" to "pkg:maven/${parts[0]}/${parts[1]}@${parts[2]}",
                "group" to parts[0],
                "name" to parts[1],
                "version" to parts[2],
                "purl" to "pkg:maven/${parts[0]}/${parts[1]}@${parts[2]}",
                "licenses" to listOf(
                    mapOf("license" to mapOf("id" to module.license.id, "name" to module.license.name))
                ),
                "hashes" to listOf(
                    mapOf("alg" to "SHA-256", "content" to primaryArtifact.sha256)
                ),
                "properties" to module.artifacts.map { artifact ->
                    mapOf(
                        "name" to "top.ceroxe.rt.artifact.${artifact.file}.sha256",
                        "value" to artifact.sha256
                    )
                }
            )
        }
        val rootReference = "pkg:maven/$publishedApiGroup/$publishedApiName@$publishedApiVersion"
        val sbom = mapOf<String, Any>(
            "bomFormat" to "CycloneDX",
            "specVersion" to "1.6",
            "version" to 1,
            "metadata" to mapOf(
                "component" to mapOf(
                    "type" to "library",
                    "bom-ref" to rootReference,
                    "group" to publishedApiGroup,
                    "name" to publishedApiName,
                    "version" to publishedApiVersion,
                    "purl" to rootReference,
                    "licenses" to listOf(
                        mapOf("license" to mapOf("id" to "MIT", "name" to "MIT License"))
                    )
                )
            ),
            "components" to components,
            "dependencies" to listOf(
                mapOf(
                    "ref" to rootReference,
                    "dependsOn" to components.map { it.getValue("bom-ref") }.sortedBy(Any::toString)
                )
            )
        )

        val sbomOutput = runtimeSbomFile.get().asFile
        sbomOutput.parentFile.mkdirs()
        sbomOutput.writeText(
            JsonOutput.prettyPrint(JsonOutput.toJson(sbom)) + "\n",
            StandardCharsets.UTF_8
        )
        val licenseDocument = mapOf(
            "schemaVersion" to 1,
            "components" to inventory.map { module ->
                mapOf(
                    "coordinate" to module.coordinate,
                    "licenseId" to module.license.id,
                    "licenseName" to module.license.name,
                    "artifacts" to module.artifacts.map { artifact ->
                        mapOf(
                            "file" to artifact.file,
                            "classifier" to artifact.classifier,
                            "sha256" to artifact.sha256
                        )
                    }
                )
            }
        )
        val licenseOutput = runtimeLicenseInventoryFile.get().asFile
        licenseOutput.parentFile.mkdirs()
        licenseOutput.writeText(
            JsonOutput.prettyPrint(JsonOutput.toJson(licenseDocument)) + "\n",
            StandardCharsets.UTF_8
        )
    }
}

tasks.register("verifyRuntimeSupplyChainMetadata") {
    group = "verification"
    description = "Rejects incomplete, unhashed, machine-specific, or unknown-license runtime metadata."
    dependsOn(tasks.named("generateRuntimeSupplyChainMetadata"))
    inputs.files(runtimeSbomFile, runtimeLicenseInventoryFile)

    doLast {
        val sbomFile = runtimeSbomFile.get().asFile
        val licenseFile = runtimeLicenseInventoryFile.get().asFile
        val sbom = JsonSlurper().parse(sbomFile) as Map<*, *>
        val licenses = JsonSlurper().parse(licenseFile) as Map<*, *>
        if (sbom["bomFormat"] != "CycloneDX" ||
            sbom["specVersion"] != "1.6" ||
            sbom["version"] != 1
        ) {
            throw GradleException("Runtime SBOM is not the required CycloneDX 1.6 document")
        }
        val sbomCoordinates = (sbom["components"] as List<*>).map { rawComponent ->
            val component = rawComponent as Map<*, *>
            "${component["group"]}:${component["name"]}:${component["version"]}"
        }.sorted()
        val licenseComponents = licenses["components"] as List<*>
        val licenseCoordinates = licenseComponents.map { rawComponent ->
            (rawComponent as Map<*, *>)["coordinate"].toString()
        }.sorted()
        if (sbomCoordinates != licenseCoordinates || sbomCoordinates.isEmpty()) {
            throw GradleException("Runtime SBOM and license inventories differ")
        }
        val invalidHashes = licenseComponents.flatMap { rawComponent ->
            val component = rawComponent as Map<*, *>
            component["artifacts"] as List<*>
        }.filter { rawArtifact ->
            val artifact = rawArtifact as Map<*, *>
            Regex("[0-9a-f]{64}").matches(artifact["sha256"].toString()) == false
        }
        if (invalidHashes.isNotEmpty()) {
            throw GradleException("Runtime inventory contains invalid hashes: $invalidHashes")
        }
        val machineSpecificTokens = listOfNotNull(
            rootDir.absolutePath,
            System.getProperty("user.home")
        ).filter(String::isNotBlank)
        val combined = sbomFile.readText(StandardCharsets.UTF_8) +
            licenseFile.readText(StandardCharsets.UTF_8)
        val leaked = machineSpecificTokens.filter(combined::contains)
        if (leaked.isNotEmpty()) {
            throw GradleException("Runtime supply-chain metadata contains a machine-specific path")
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("rendererApiContractSelfTest"))
    dependsOn(tasks.named("verifyRendererApiBoundary"))
    dependsOn(tasks.named("verifyRendererApiAbi"))
    dependsOn(tasks.named("verifyRuntimeSupplyChainMetadata"))
}

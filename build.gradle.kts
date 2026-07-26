import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.w3c.dom.Element

plugins {
    base
}

group = providers.gradleProperty("project_group").get()
version = providers.gradleProperty("project_version").get()

subprojects {
    group = rootProject.group
    version = rootProject.version

    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    dependencyLocking {
        lockAllConfigurations()
    }

    plugins.withId("java-library") {
        tasks.withType<AbstractArchiveTask>().configureEach {
            // Release hashes are meaningful only when identical inputs produce byte-identical
            // archives. ZIP entry timestamps and filesystem traversal order are environmental,
            // so neither may enter a published JAR, sources JAR, or Javadoc JAR.
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
        }

        tasks.named<Jar>("jar") {
            from(rootProject.file("LICENSE")) {
                into("META-INF")
            }
        }

        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("mavenJava") {
                    from(components["java"])
                    artifactId = project.name

                    pom {
                        name.set(
                            if (project.name == "renderer-api") {
                                "RTRendererAPI"
                            } else {
                                "RTRendererAPI Vulkan Backend"
                            }
                        )
                        description.set(
                            if (project.name == "renderer-api") {
                                "Public ray tracing contracts with the transitive Windows NVIDIA Vulkan runtime."
                            } else {
                                "Vulkan ray tracing renderer implementation."
                            }
                        )
                        url.set("https://github.com/CeroxeAnivie/RTRendererAPI")

                        licenses {
                            license {
                                name.set("MIT License")
                                url.set("https://opensource.org/licenses/MIT")
                                distribution.set("repo")
                            }
                        }

                        developers {
                            developer {
                                id.set("CeroxeAnivie")
                                name.set("Ceroxe")
                                email.set("1591117599@qq.com")
                                organization.set("Ceroxe")
                                url.set("https://github.com/CeroxeAnivie")
                            }
                        }

                        scm {
                            connection.set("scm:git:git://github.com/CeroxeAnivie/RTRendererAPI.git")
                            developerConnection.set("scm:git:ssh://github.com/CeroxeAnivie/RTRendererAPI.git")
                            url.set("https://github.com/CeroxeAnivie/RTRendererAPI")
                        }
                    }
                }
            }

            repositories {
                maven {
                    name = "localStaging"
                    url = rootProject.layout.buildDirectory.dir("repository").get().asFile.toURI()
                }
            }
        }

        extensions.configure<SigningExtension> {
            // Local verification remains credential-free. A Central release opts in explicitly,
            // at which point every publication artifact must receive an OpenPGP signature. Do
            // not even wire Sign tasks for ordinary checks/local staging: Gradle may otherwise
            // execute an optional sign task as a publication dependency and invoke GPG anyway.
            val centralRelease = providers.gradleProperty("centralRelease")
                .map(String::toBoolean)
                .getOrElse(false)
            setRequired(centralRelease)
            if (centralRelease) {
                useGpgCmd()
                sign(project.extensions.getByType<PublishingExtension>().publications.named("mavenJava").get())
            }
        }
    }
}

tasks.named("assemble") {
    dependsOn(":renderer-api:assemble", ":renderer-core:assemble")
}

tasks.named("check") {
    dependsOn(":renderer-api:check", ":renderer-core:check")
}

tasks.register("publishAllToLocalStagingRepository") {
    group = "publishing"
    description = "Publishes every RTRendererAPI module into build/repository."
    dependsOn(":renderer-api:publishMavenJavaPublicationToLocalStagingRepository")
    dependsOn(":renderer-core:publishMavenJavaPublicationToLocalStagingRepository")
}

val publishedRepository = layout.buildDirectory.dir("repository")
val publishedGroupId = group.toString()
val publishedVersion = version.toString()
val centralPortalBundleFile = layout.buildDirectory.file(
    "release/rtrenderer-api-$publishedVersion-central-bundle.zip"
)

tasks.register<Zip>("centralPortalBundle") {
    group = "publishing"
    description = "Builds the signed Maven repository bundle accepted by the Central Portal API."
    dependsOn(tasks.named("publishAllToLocalStagingRepository"))

    from(publishedRepository) {
        include("top/ceroxe/rt/renderer-api/$publishedVersion/**")
        include("top/ceroxe/rt/renderer-core/$publishedVersion/**")
    }
    archiveFileName.set("rtrenderer-api-$publishedVersion-central-bundle.zip")
    destinationDirectory.set(layout.buildDirectory.dir("release"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    doFirst {
        val centralRelease = providers.gradleProperty("centralRelease")
            .map(String::toBoolean)
            .getOrElse(false)
        if (!centralRelease) {
            throw GradleException(
                "Central Portal bundles require -PcentralRelease=true so every artifact is signed"
            )
        }
    }
}

tasks.register("verifyCentralPortalBundle") {
    group = "verification"
    description = "Rejects a Central Portal bundle with missing artifacts or detached signatures."
    dependsOn(tasks.named("centralPortalBundle"))
    inputs.file(centralPortalBundleFile)

    doLast {
        val requiredArtifacts = listOf("renderer-api", "renderer-core").flatMap { module ->
            val prefix = "top/ceroxe/rt/$module/$publishedVersion/$module-$publishedVersion"
            listOf(
                "$prefix.pom",
                "$prefix.module",
                "$prefix.jar",
                "$prefix-sources.jar",
                "$prefix-javadoc.jar"
            )
        }
        ZipFile(centralPortalBundleFile.get().asFile).use { archive ->
            val entries = archive.entries().asSequence().map { it.name }.toSet()
            val missingArtifacts = requiredArtifacts.filterNot(entries::contains)
            val missingSignatures = requiredArtifacts
                .map { "$it.asc" }
                .filterNot(entries::contains)
            if (missingArtifacts.isNotEmpty() || missingSignatures.isNotEmpty()) {
                throw GradleException(
                    "Central Portal bundle is incomplete: " +
                        "missingArtifacts=$missingArtifacts, missingSignatures=$missingSignatures"
                )
            }
        }
    }
}

tasks.register("verifyPublishedMavenTopology") {
    group = "verification"
    description = "Proves the single-coordinate API/runtime POM graph is exact and acyclic."
    dependsOn(tasks.named("publishAllToLocalStagingRepository"))

    doLast {
        fun readDependencies(pomFile: File): List<Map<String, String>> {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val document = factory.newDocumentBuilder().parse(pomFile)
            return (0 until document.getElementsByTagName("dependency").length).map { index ->
                val dependency = document.getElementsByTagName("dependency").item(index) as Element
                fun textOf(tag: String): String =
                    dependency.getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()
                mapOf(
                    "groupId" to textOf("groupId"),
                    "artifactId" to textOf("artifactId"),
                    "version" to textOf("version"),
                    "scope" to textOf("scope")
                )
            }
        }

        val repository = publishedRepository.get().asFile
        val apiDependencies = readDependencies(
            File(
                repository,
                "top/ceroxe/rt/renderer-api/$publishedVersion/renderer-api-$publishedVersion.pom"
            )
        )
        val coreDependencies = readDependencies(
            File(
                repository,
                "top/ceroxe/rt/renderer-core/$publishedVersion/renderer-core-$publishedVersion.pom"
            )
        )
        val apiRuntime = apiDependencies.filter {
            it["groupId"] == publishedGroupId &&
                it["artifactId"] == "renderer-core" &&
                it["version"] == publishedVersion &&
                it["scope"] == "runtime"
        }
        if (apiRuntime.size != 1) {
            throw GradleException(
                "renderer-api POM must contain exactly one runtime renderer-core dependency"
            )
        }
        val reverseEdges = coreDependencies.filter {
            it["groupId"] == publishedGroupId && it["artifactId"] == "renderer-api"
        }
        if (reverseEdges.isNotEmpty()) {
            throw GradleException(
                "renderer-core POM must not depend on renderer-api; that would create a cycle"
            )
        }
    }
}

tasks.register("verifyReproducibleArchiveConfiguration") {
    group = "verification"
    description = "Rejects any published archive task that can encode timestamps or filesystem order."

    doLast {
        val violations = subprojects.flatMap { child ->
            child.tasks.withType<AbstractArchiveTask>().filter { archive ->
                archive.isPreserveFileTimestamps || !archive.isReproducibleFileOrder
            }.map { archive -> "${child.path}:${archive.name}" }
        }.sorted()
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Non-reproducible archive configuration: ${violations.joinToString(", ")}"
            )
        }
    }
}

val releaseChecksumsFile = layout.buildDirectory.file("release/SHA256SUMS")

tasks.register("generateReleaseChecksums") {
    group = "publishing"
    description = "Generates deterministic SHA-256 checksums for every locally staged Maven artifact."
    dependsOn(tasks.named("publishAllToLocalStagingRepository"))

    val stagedArtifacts = fileTree(publishedRepository) {
        include("**/*.jar", "**/*.module", "**/*.pom")
    }
    inputs.files(stagedArtifacts)
    outputs.file(releaseChecksumsFile)

    doLast {
        val repository = publishedRepository.get().asFile.toPath()
        val artifacts = stagedArtifacts.files.sortedBy { artifact ->
            repository.relativize(artifact.toPath()).toString()
        }
        if (artifacts.isEmpty()) {
            throw GradleException("No staged Maven artifacts exist for checksum generation")
        }
        val lines = artifacts.map { artifact ->
            val digest = MessageDigest.getInstance("SHA-256")
            artifact.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            val relative = repository.relativize(artifact.toPath()).toString().replace('\\', '/')
            "${HexFormat.of().formatHex(digest.digest())}  $relative"
        }
        val output = releaseChecksumsFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(lines.joinToString("\n", postfix = "\n"), StandardCharsets.UTF_8)
    }
}

tasks.register("verifyReleaseChecksums") {
    group = "verification"
    description = "Recomputes every staged artifact digest and rejects missing, duplicate, or stale checksums."
    dependsOn(tasks.named("generateReleaseChecksums"))
    inputs.dir(publishedRepository)
    inputs.file(releaseChecksumsFile)

    doLast {
        val repository = publishedRepository.get().asFile.toPath()
        val entries = linkedMapOf<String, String>()
        val pattern = Regex("^([0-9a-f]{64})  (.+)$")
        releaseChecksumsFile.get().asFile.readLines(StandardCharsets.UTF_8)
            .forEachIndexed { index, line ->
                val match = pattern.matchEntire(line)
                    ?: throw GradleException("Invalid SHA256SUMS line ${index + 1}: $line")
                val previous = entries.put(match.groupValues[2], match.groupValues[1])
                if (previous != null) {
                    throw GradleException("Duplicate SHA256SUMS path: ${match.groupValues[2]}")
                }
            }
        val staged = fileTree(publishedRepository) {
            include("**/*.jar", "**/*.module", "**/*.pom")
        }.files.map {
            repository.relativize(it.toPath()).toString().replace('\\', '/')
        }.sorted()
        if (entries.keys.toList() != staged) {
            throw GradleException(
                "SHA256SUMS artifact inventory differs: manifest=${entries.keys}, staged=$staged"
            )
        }
        entries.forEach { (relative, expected) ->
            val digest = MessageDigest.getInstance("SHA-256")
            repository.resolve(relative).toFile().inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            val actual = HexFormat.of().formatHex(digest.digest())
            if (actual != expected) {
                throw GradleException(
                    "SHA-256 mismatch for $relative: expected=$expected, actual=$actual"
                )
            }
        }
    }
}

val publishedConsumerDirectory = layout.buildDirectory.dir("published-consumer-smoke")

tasks.register<Sync>("preparePublishedMavenConsumer") {
    group = "verification"
    description = "Prepares an isolated consumer that resolves only published Maven metadata."
    from(layout.projectDirectory.dir("gradle/published-consumer-smoke"))
    into(publishedConsumerDirectory)
}

tasks.register<Exec>("verifyPublishedMavenConsumer") {
    group = "verification"
    description = "Publishes both modules and compiles an isolated consumer through their Maven POMs."
    dependsOn(tasks.named("publishAllToLocalStagingRepository"))
    dependsOn(tasks.named("verifyPublishedMavenTopology"))
    dependsOn(tasks.named("preparePublishedMavenConsumer"))

    val stagingRepository = layout.buildDirectory.dir("repository")
    val javaInstallationPaths = providers.gradleProperty("org.gradle.java.installations.paths")
    inputs.dir(stagingRepository)
    inputs.dir(layout.projectDirectory.dir("gradle/published-consumer-smoke"))
    inputs.property("javaInstallationPaths", javaInstallationPaths.orElse(""))
    outputs.dir(publishedConsumerDirectory.map { it.dir("build") })

    workingDir(publishedConsumerDirectory)
    executable(rootProject.file("gradlew.bat"))
    // Do not rely on the inherited working directory to select the nested build. An explicit
    // project directory makes it impossible for its `clean` task to target this repository's
    // root build when Gradle executes verification tasks concurrently.
    args(
        "--no-daemon",
        "--console=plain",
        "--project-dir",
        publishedConsumerDirectory.get().asFile.absolutePath,
        "clean",
        "compileJava",
        "verifyPublishedRuntimeClasspath",
        "-PstagingRepository=${stagingRepository.get().asFile.toURI()}",
        "-PrendererVersion=${project.version}"
    )
    // The consumer is an isolated Gradle invocation and therefore cannot see project properties
    // passed to this build. Forward only the opt-in toolchain discovery path so a Java 25 daemon
    // can still compile the consumer with the same explicitly selected Java 21 installation.
    doFirst {
        javaInstallationPaths.orNull?.let { paths ->
            args("-Porg.gradle.java.installations.paths=$paths")
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("verifyPublishedMavenConsumer"))
    dependsOn(tasks.named("verifyReproducibleArchiveConfiguration"))
    dependsOn(tasks.named("verifyReleaseChecksums"))
}

val repositoryTextFiles = fileTree(layout.projectDirectory) {
    include(
        "**/*.bat",
        "**/*.gradle.kts",
        "**/*.java",
        "**/*.json",
        "**/*.md",
        "**/*.properties",
        "**/*.ps1",
        "**/*.txt",
        "**/*.xml",
        "**/*.yaml",
        "**/*.yml",
        "**/*.glsl",
        "**/*.rchit",
        "**/*.rgen",
        "**/*.rmiss"
    )
    exclude(".git/**", ".gradle/**", ".idea/**", "**/build/**")
}

tasks.register("verifyRepositoryNeutrality") {
    group = "verification"
    description = "Rejects legacy engine identities and project-specific terminology."
    inputs.files(repositoryTextFiles)

    doLast {
        val forbidden = listOf(
            "legacy package" to Regex("(?i)\\b${"mc" + "vulkanrt"}\\b"),
            "legacy product" to Regex("(?i)\\b${"mine" + "craft"}\\b"),
            "legacy abbreviation" to Regex("(?<![A-Za-z0-9_])${"M" + "C"}(?![A-Za-z0-9_])"),
            "external engine" to Regex("(?i)\\b${"un" + "real"}(?:\\s+engine)?\\b"),
            "external engine version" to Regex("(?i)\\b${"ue" + "5"}\\b")
        )
        val violations = mutableListOf<String>()
        repositoryTextFiles.files.sorted().forEach { file ->
            val relativePath = rootDir.toPath().relativize(file.toPath()).toString().replace('\\', '/')
            forbidden.forEach { (label, pattern) ->
                if (pattern.containsMatchIn(relativePath)) {
                    violations += "$relativePath: path contains $label"
                }
            }
            file.readLines(StandardCharsets.UTF_8).forEachIndexed { index, line ->
                forbidden.forEach { (label, pattern) ->
                    if (pattern.containsMatchIn(line)) {
                        violations += "$relativePath:${index + 1}: contains $label"
                    }
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Repository neutrality verification failed:\n${violations.joinToString("\n")}"
            )
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("verifyRepositoryNeutrality"))
}

/*
 * Deliberately opt-in: this single entry point combines every CPU/publication check with the
 * bounded GPUScene hardware gate. It remains short enough for release iteration while still
 * proving device creation, acceleration structures, rendering, readback, and throughput.
 */
tasks.register("strictAcceptanceTest") {
    group = "verification"
    description = "Runs all CPU/publication checks and the bounded RTX Vulkan native acceptance gate."
    dependsOn(tasks.named("check"))
    dependsOn(":renderer-core:rendererCoreGpuSceneNativeGate")
}

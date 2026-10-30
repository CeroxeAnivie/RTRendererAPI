import java.nio.charset.StandardCharsets
import groovy.json.JsonOutput
import java.time.Instant
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.compile.JavaCompile
import org.w3c.dom.Element
import nmcp.NmcpExtension
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

plugins {
    base
}

abstract class RendererHardwareGateLock : BuildService<BuildServiceParameters.None>

// Native graphics gates share the physical adapter even when they belong to different modules.
val rendererHardwareGateLock = gradle.sharedServices.registerIfAbsent(
    "rendererHardwareGateLock", RendererHardwareGateLock::class
) { maxParallelUsages.set(1) }

subprojects {
    tasks.withType<JavaExec>().configureEach {
        if (name.contains("Native")) usesService(rendererHardwareGateLock)
    }
}

group = providers.gradleProperty("project_group").get()
version = providers.gradleProperty("project_version").get()

subprojects {
    group = rootProject.group
    version = rootProject.version

    tasks.withType<JavaCompile>().configureEach {
        // JDK 25 runs the compiler, while the repository's immutable runtime contract remains
        // Java 25. Keeping this literal at the root prevents -P properties from changing the
        // emitted class-file version for any production module, test source set, or demo.
        options.release = 25
    }

    dependencyLocking {
        lockAllConfigurations()
    }

    plugins.withId("java-library") {
        // Only published renderer modules participate in Maven publication. Demo applications
        // intentionally have no publication and must not be handed to NMCP as empty projects.
        apply(plugin = "maven-publish")
        apply(plugin = "signing")

        plugins.withId("com.gradleup.nmcp") {
            extensions.configure<NmcpExtension> {
                publishAllPublicationsToCentralPortal {
                    username.set(
                        providers.gradleProperty("centralUsername")
                            .orElse(providers.environmentVariable("CENTRAL_USERNAME"))
                    )
                    password.set(
                        providers.gradleProperty("centralPassword")
                            .orElse(providers.environmentVariable("CENTRAL_PASSWORD"))
                    )
                publishingType.set("AUTOMATIC")
                publicationName.set("RTRendererAPI-${project.version}")
                // Upload is the release boundary; do not block on Central's asynchronous
                // validation/publication state after the deployment is accepted.
                validationTimeout.set(java.time.Duration.ZERO)
                publishingTimeout.set(java.time.Duration.ZERO)
            }
        }
        }

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
                        name.set(when (project.name) {
                            "renderer-api" -> "RTRendererAPI"
                            "renderer-core" -> "RTRendererAPI Vulkan Backend"
                            "renderer-nvidia" -> "RTRendererAPI NVIDIA Features"
                            else -> project.name
                        })
                        description.set(when (project.name) {
                            "renderer-api" ->
                                "Public ray tracing contracts with the transitive Windows Vulkan runtime."
                            "renderer-core" -> "Vulkan ray tracing renderer implementation."
                            "renderer-nvidia" ->
                                "Optional NVIDIA DLSS, NIS, NRD, SER, and RTXMU integration."
                            else -> project.description ?: project.name
                        })
                        url.set("https://github.com/CeroxeAnivie/RTRendererAPI")

                        licenses {
                            license {
                                name.set("Apache License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
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
            val centralPublishRequested = gradle.startParameter.taskNames.any {
                it.contains("CentralPortal", ignoreCase = true)
            }
            val centralRelease = providers.gradleProperty("centralRelease")
                .map(String::toBoolean)
                .getOrElse(centralPublishRequested)
            setRequired(centralRelease)
            if (centralRelease) {
                useGpgCmd()
                sign(project.extensions.getByType<PublishingExtension>().publications.named("mavenJava").get())
            }
        }
    }
}

val projectLicenseName = "Apache License, Version 2.0"
val projectLicenseUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt"
val publishedModuleNames = listOf("renderer-api", "renderer-core", "renderer-nvidia")

val verifyProjectLicenseConsistency = tasks.register("verifyProjectLicenseConsistency") {
    group = "verification"
    description = "Verifies the repository, generated POMs, and published JARs use Apache License 2.0."
    publishedModuleNames.forEach { moduleName ->
        dependsOn(":$moduleName:generatePomFileForMavenJavaPublication")
        dependsOn(":$moduleName:jar")
    }
    inputs.file(layout.projectDirectory.file("LICENSE"))

    doLast {
        val licenseFile = layout.projectDirectory.file("LICENSE").asFile
        val licenseBytes = licenseFile.readBytes()
        val licenseText = licenseBytes.toString(StandardCharsets.UTF_8)
        if (!licenseText.startsWith("                                 Apache License\n")
            || !licenseText.contains("Version 2.0, January 2004")) {
            throw GradleException("Root LICENSE is not the canonical Apache License 2.0 text")
        }

        fun secureDocument(file: File) = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }.newDocumentBuilder().parse(file)

        publishedModuleNames.forEach { moduleName ->
            val moduleDirectory = project(moduleName).layout.projectDirectory.asFile
            val pomFile = File(moduleDirectory, "build/publications/mavenJava/pom-default.xml")
            val document = secureDocument(pomFile)
            val licenses = document.getElementsByTagName("license")
            if (licenses.length != 1) {
                throw GradleException("$moduleName POM must declare exactly one project license")
            }
            val license = licenses.item(0) as Element
            fun licenseValue(tag: String): String =
                license.getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()
            val actual = listOf(licenseValue("name"), licenseValue("url"), licenseValue("distribution"))
            val expected = listOf(projectLicenseName, projectLicenseUrl, "repo")
            if (actual != expected) {
                throw GradleException("$moduleName POM license differs: expected=$expected, actual=$actual")
            }

            val jarFile = File(moduleDirectory, "build/libs/$moduleName-$version.jar")
            ZipFile(jarFile).use { archive ->
                val entry = archive.getEntry("META-INF/LICENSE")
                    ?: throw GradleException("$moduleName JAR does not contain META-INF/LICENSE")
                val embedded = archive.getInputStream(entry).use { it.readAllBytes() }
                if (!licenseBytes.contentEquals(embedded)) {
                    throw GradleException("$moduleName JAR embeds a LICENSE different from the repository root")
                }
            }
        }
    }
}

tasks.named("assemble") {
    dependsOn(":renderer-api:assemble", ":renderer-core:assemble", ":renderer-nvidia:assemble")
}

tasks.named("check") {
    dependsOn(":renderer-api:check", ":renderer-core:check", ":renderer-nvidia:check")
    dependsOn(verifyProjectLicenseConsistency)
}

val releaseVersionDocuments = files(
    // README is maintained by the calling project and is intentionally outside the
    // published-version gate. Release-owned docs and metadata remain authoritative.
    fileTree("docs") {
        include("**/*.md")
        // Versioned quality reports are immutable evidence for their historical candidate;
        // they must not be rewritten or treated as the current release's version facts.
        exclude("quality/**")
    },
    fileTree("demos/hex-ball") {
        include("**/*.md", "src/**/*.java", "*.gradle.kts")
        exclude("build/**")
    }
)

val verifyReleaseVersionConsistency = tasks.register("verifyReleaseVersionConsistency") {
    group = "verification"
    description = "Rejects divergent Maven coordinates, Demo artifact names, and version-tag facts."
    inputs.files(releaseVersionDocuments)
    inputs.property("projectVersion", project.version.toString())

    doLast {
        val expected = project.version.toString()
        val canonicalVersion = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
        if (!canonicalVersion.matches(expected)) {
            throw GradleException("project_version must be canonical MAJOR.MINOR.PATCH: $expected")
        }
        val staleFacts = mutableListOf<String>()
        val publishedVersionPatterns = listOf(
            Regex("top\\.ceroxe\\.rt:renderer-api:([0-9]+\\.[0-9]+\\.[0-9]+)"),
            Regex("<version>([0-9]+\\.[0-9]+\\.[0-9]+)</version>"),
            Regex("RTRendererAPI-HexBallDemo-([0-9]+\\.[0-9]+\\.[0-9]+)\\.jar")
        )
        releaseVersionDocuments.files.sorted().forEach { file ->
            val text = file.readText(StandardCharsets.UTF_8)
            publishedVersionPatterns.forEach { pattern ->
                pattern.findAll(text).forEach { match ->
                    val actual = match.groupValues[1]
                    if (actual != expected) {
                        staleFacts += "${rootDir.toPath().relativize(file.toPath())}: $actual"
                    }
                }
            }
        }
        if (staleFacts.isNotEmpty()) {
            throw GradleException(
                "Published documentation versions differ from project_version=$expected:\n" +
                    staleFacts.joinToString("\n")
            )
        }

        val githubRefType = System.getenv("GITHUB_REF_TYPE")
        val githubRefName = System.getenv("GITHUB_REF_NAME")
        if (githubRefType == "tag" && githubRefName != "v$expected") {
            throw GradleException(
                "Release tag must match project_version exactly: tag=$githubRefName, expected=v$expected"
            )
        }
    }
}

val verifyMajorReleaseContract = tasks.register("verifyMajorReleaseContract") {
    group = "verification"
    description = "Enforces the 7.x major-release contract and current documentation entrypoint."
    inputs.property("projectVersion", project.version.toString())
    inputs.files(
        file("docs/${project.version}.md"),
        file("docs/COMPATIBILITY.md"),
        file("README.md"),
        file("gradle.properties")
    )
    doLast {
        val expected = project.version.toString()
        if (!expected.startsWith("7.")) {
            throw GradleException("The current release line must remain on the deliberate 7.x major line: $expected")
        }
        val currentDocs = listOf(
            file("README.md"),
            file("docs/Home.md"),
            file("docs/Java.md"),
            file("docs/COMPATIBILITY.md"),
            file("docs/Procedural-Geometry.md"),
            file("demos/hex-ball/README.md")
        )
        val stale = currentDocs.filter { it.readText(StandardCharsets.UTF_8).contains("5.2.2") }
        if (stale.isNotEmpty()) {
            throw GradleException(
                "Current release documentation still names 5.2.2: " +
                    stale.joinToString(", ") { rootDir.toPath().relativize(it.toPath()).toString() }
            )
        }
        if (!file("docs/$expected.md").isFile) {
            throw GradleException("docs/$expected.md is required for the current release")
        }
    }
}

tasks.named("check") {
    dependsOn(verifyReleaseVersionConsistency)
    dependsOn(verifyMajorReleaseContract)
}

val publishAllToLocalStagingRepository = tasks.register("publishAllToLocalStagingRepository") {
    group = "publishing"
    description = "Publishes every RTRendererAPI module into build/repository."
    dependsOn(":renderer-api:publishMavenJavaPublicationToLocalStagingRepository")
    dependsOn(":renderer-core:publishMavenJavaPublicationToLocalStagingRepository")
    dependsOn(":renderer-nvidia:publishMavenJavaPublicationToLocalStagingRepository")
}

val verifyPublishedNvidiaRuntimeClosure = tasks.register("verifyPublishedNvidiaRuntimeClosure") {
    group = "verification"
    description = "Verifies the staged renderer-nvidia artifact with the shared runtime closure contract."
    dependsOn(tasks.named("publishAllToLocalStagingRepository"))
    doLast {
        val artifact = layout.buildDirectory.file(
            "repository/top/ceroxe/rt/renderer-nvidia/$version/renderer-nvidia-$version.jar"
        ).get().asFile
        NvidiaRuntimeClosure.verify(artifact, "staged renderer-nvidia runtime JAR")
    }
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
        include("top/ceroxe/rt/renderer-nvidia/$publishedVersion/**")
    }
    archiveFileName.set("rtrenderer-api-$publishedVersion-central-bundle.zip")
    destinationDirectory.set(layout.buildDirectory.dir("release"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    doFirst {
        val centralPublishRequested = gradle.startParameter.taskNames.any {
            it.contains("CentralPortal", ignoreCase = true)
        }
        val centralRelease = providers.gradleProperty("centralRelease")
            .map(String::toBoolean)
            .getOrElse(centralPublishRequested)
        if (!centralRelease) {
            throw GradleException(
                "Central Portal bundles require centralRelease signing to be enabled"
            )
        }
    }
}

tasks.register("verifyCentralPortalBundle") {
    group = "verification"
    description = "Rejects a Central Portal bundle with missing artifacts or detached signatures."
    dependsOn(tasks.named("centralPortalBundle"))
    dependsOn(verifyReleaseVersionConsistency)
    inputs.file(centralPortalBundleFile)

    doLast {
        val requiredArtifacts = listOf("renderer-api", "renderer-core", "renderer-nvidia").flatMap { module ->
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
        val nvidiaDependencies = readDependencies(
            File(
                repository,
                "top/ceroxe/rt/renderer-nvidia/$publishedVersion/renderer-nvidia-$publishedVersion.pom"
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
        val apiNvidiaRuntime = apiDependencies.filter {
            it["groupId"] == publishedGroupId &&
                it["artifactId"] == "renderer-nvidia" &&
                it["version"] == publishedVersion &&
                it["scope"] == "runtime"
        }
        if (apiNvidiaRuntime.size != 1) {
            throw GradleException(
                "renderer-api POM must contain exactly one runtime renderer-nvidia dependency"
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
        val nvidiaApiEdges = nvidiaDependencies.filter {
            it["groupId"] == publishedGroupId && it["artifactId"] == "renderer-api"
        }
        if (nvidiaApiEdges.isNotEmpty()) {
            throw GradleException(
                "renderer-nvidia POM must not publish a renderer-api dependency; the API owns the runtime edge"
            )
        }
        val nvidiaCoreEdges = nvidiaDependencies.filter {
            it["groupId"] == publishedGroupId && it["artifactId"] == "renderer-core"
        }
        if (nvidiaCoreEdges.isNotEmpty()) {
            throw GradleException(
                "renderer-nvidia POM must reach renderer-core through renderer-api runtime metadata"
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

// The portable ZIP includes the resolved runtime graph, Maven metadata and its source/docs.
// It is a candidate distribution; feature coverage is documented explicitly in docs/7.0.0.md.
val runtimeDistributionContents = tasks.register<Sync>("prepareRuntimeDistribution") {
    group = "distribution"
    description = "Stages the complete Windows runtime dependency graph, source artifacts, documentation and hashes."
    dependsOn(publishAllToLocalStagingRepository, tasks.named("verifyReleaseChecksums"))
    dependsOn(":renderer-api:verifyRuntimeSupplyChainMetadata")
    into(layout.buildDirectory.dir("distribution/rtrenderer-api-$publishedVersion-windows-x86_64"))
    from(provider { project(":renderer-api").configurations.getByName("runtimeClasspath") }) { into("lib") }
    from(project(":renderer-api").layout.buildDirectory.file("libs/renderer-api-$publishedVersion.jar")) { into("lib") }
    from(publishedRepository) {
        include("top/ceroxe/rt/renderer-api/$publishedVersion/**", "top/ceroxe/rt/renderer-core/$publishedVersion/**",
            "top/ceroxe/rt/renderer-nvidia/$publishedVersion/**")
        into("maven")
    }
    from(layout.projectDirectory.dir("docs")) { into("docs") }
    from(layout.projectDirectory) {
        include("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
            "LICENSE", "README.md", "docs/**", "gradle/wrapper/**", "gradle/verification-metadata.xml",
            "gradle/published-consumer-smoke/**", "buildSrc/build.gradle.kts", "buildSrc/src/**",
            "renderer-api/build.gradle.kts", "renderer-api/abi/**", "renderer-api/src/**",
            "renderer-core/build.gradle.kts", "renderer-core/gradle.lockfile", "renderer-core/src/**",
            "renderer-nvidia/build.gradle.kts", "renderer-nvidia/src/**", "tools/**",
            "demos/hex-ball/build.gradle.kts", "demos/hex-ball/src/**")
        into("source")
    }
    from(layout.projectDirectory.file("LICENSE"))
    from(layout.projectDirectory.file("docs/runtime-distribution.md")) { rename { "README.md" } }
    from(layout.buildDirectory.file("release/renderer-runtime.cdx.json"))
    from(layout.buildDirectory.file("release/runtime-licenses.json"))
    from(releaseChecksumsFile) { into("maven") }
    doLast {
        val directory = destinationDir.toPath()
        val mavenDirectory = destinationDir.resolve("maven")
        val mavenRoot = mavenDirectory.toPath()
        val mavenArtifacts = fileTree(mavenDirectory) {
            include("**/*.jar", "**/*.module", "**/*.pom")
        }.files.sortedBy { mavenRoot.relativize(it.toPath()).toString().replace('\\', '/') }
        if (mavenArtifacts.isEmpty()) throw GradleException("Runtime distribution has no current-version Maven artifacts")
        val mavenSums = mavenArtifacts.map { artifact ->
            val digest = MessageDigest.getInstance("SHA-256")
            artifact.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            val relative = mavenRoot.relativize(artifact.toPath()).toString().replace('\\', '/')
            "${HexFormat.of().formatHex(digest.digest())}  $relative"
        }
        mavenDirectory.resolve("SHA256SUMS").writeText(
            mavenSums.joinToString("\n", postfix = "\n"), StandardCharsets.UTF_8
        )
        val sums = fileTree(destinationDir).files.filter { it.toPath() != directory.resolve("SHA256SUMS") }
            .sortedBy { directory.relativize(it.toPath()).toString() }.map { artifact ->
                val digest = MessageDigest.getInstance("SHA-256")
                artifact.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                HexFormat.of().formatHex(digest.digest()) + "  " + directory.relativize(artifact.toPath()).toString().replace('\\', '/')
            }
        destinationDir.resolve("SHA256SUMS").writeText(sums.joinToString("\n", postfix = "\n"), StandardCharsets.UTF_8)
    }
}
tasks.register<Zip>("runtimeDistribution") {
    group = "distribution"
    description = "Builds the Windows runtime candidate ZIP with all resolved dependencies."
    dependsOn(runtimeDistributionContents)
    from(runtimeDistributionContents.map { it.destinationDir })
    archiveFileName.set("rtrenderer-api-$publishedVersion-windows-x86_64-candidate.zip")
    destinationDirectory.set(layout.buildDirectory.dir("release"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.register("verifyRuntimeDistribution") {
    group = "verification"
    description = "Verifies ZIP completeness and every embedded SHA-256, then writes the archive checksum."
    val archive = tasks.named<Zip>("runtimeDistribution").flatMap { it.archiveFile }
    dependsOn(archive)
    inputs.file(archive)
    val resolvedRuntime = provider {
        project(":renderer-api").configurations.getByName("runtimeClasspath").files +
            project(":renderer-api").layout.buildDirectory.file("libs/renderer-api-$publishedVersion.jar").get().asFile
    }
    inputs.files(resolvedRuntime)
    val checksum = layout.buildDirectory.file("release/rtrenderer-api-$publishedVersion-windows-x86_64-candidate.zip.sha256")
    outputs.file(checksum)
    doLast {
        val artifact = archive.get().asFile
        ZipFile(artifact).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
            val names = entries.map { it.name }
            check(names.size == names.toSet().size) { "Duplicate distribution entries" }
            check(names.all { name -> !name.startsWith('/') && !name.contains('\\')
                    && name.split('/').none { it == ".." || it == "." || it.isEmpty() } }) {
                "Unsafe distribution entry path"
            }
            val sums = zip.getEntry("SHA256SUMS") ?: error("Missing distribution SHA256SUMS")
            val expected = linkedMapOf<String, String>()
            zip.getInputStream(sums).bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { line ->
                    val match = Regex("([a-f0-9]{64})  (.+)").matchEntire(line)
                        ?: error("Malformed distribution checksum")
                    check(expected.put(match.groupValues[2], match.groupValues[1]) == null) { "Duplicate distribution checksum" }
                }
            }
            check(expected.keys == names.filter { it != "SHA256SUMS" }.toSet()) { "Distribution checksum coverage is incomplete" }
            val runtimeFiles = resolvedRuntime.get().toList()
            check(runtimeFiles.map { it.name }.distinct().size == runtimeFiles.size) {
                "Resolved runtime has duplicate filenames and cannot be flattened safely into lib/"
            }
            val runtimePaths = runtimeFiles.map { "lib/" + it.name }.toSet()
            check(names.filter { it.startsWith("lib/") }.toSet() == runtimePaths) {
                "Distribution lib/ differs from the complete resolved API runtime graph"
            }
            runtimeFiles.forEach { dependency ->
                val digest = MessageDigest.getInstance("SHA-256")
                dependency.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
                }
                check(HexFormat.of().formatHex(digest.digest()) == expected["lib/" + dependency.name]) {
                    "Distribution runtime differs from its resolved artifact: ${dependency.name}"
                }
            }
            for (entry in entries) {
                if (entry.name == "SHA256SUMS") continue
                val digest = MessageDigest.getInstance("SHA-256")
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(65536)
                    while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
                }
                check(HexFormat.of().formatHex(digest.digest()) == expected[entry.name]) { "Distribution hash mismatch: ${entry.name}" }
            }
            val required = listOf("LICENSE", "README.md", "renderer-runtime.cdx.json", "runtime-licenses.json",
                "maven/SHA256SUMS", "source/LICENSE", "source/settings.gradle.kts", "source/gradle/wrapper/gradle-wrapper.jar",
                "source/gradle/verification-metadata.xml", "source/renderer-core/gradle.lockfile",
                "source/gradle/published-consumer-smoke/build.gradle.kts",
                "source/gradle/published-consumer-smoke/src/main/java/consumer/PublishedApiRuntimeSelfTest.java",
                "source/demos/hex-ball/build.gradle.kts")
            check(names.containsAll(required)) { "Distribution lacks required runtime/source metadata: ${required - names.toSet()}" }
            val mavenSums = zip.getEntry("maven/SHA256SUMS") ?: error("Missing Maven SHA256SUMS")
            val mavenExpected = linkedMapOf<String, String>()
            zip.getInputStream(mavenSums).bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { line ->
                    val match = Regex("([a-f0-9]{64})  (.+)").matchEntire(line)
                        ?: error("Malformed Maven checksum")
                    val relative = match.groupValues[2]
                    check(!relative.startsWith('/') && !relative.contains('\\')
                            && relative.split('/').none { it == ".." || it == "." || it.isEmpty() }) {
                        "Unsafe Maven checksum path: $relative"
                    }
                    check(mavenExpected.put(relative, match.groupValues[1]) == null) { "Duplicate Maven checksum: $relative" }
                }
            }
            val mavenArtifacts = names.filter { name ->
                name.startsWith("maven/") && name.substringAfterLast('.').let { it == "jar" || it == "module" || it == "pom" }
            }.map { it.removePrefix("maven/") }.sorted()
            check(mavenExpected.keys.toList() == mavenArtifacts) {
                "Maven SHA256SUMS artifact inventory differs: manifest=${mavenExpected.keys}, archive=$mavenArtifacts"
            }
            mavenExpected.forEach { (relative, expectedHash) ->
                val entry = zip.getEntry("maven/$relative") ?: error("Missing Maven artifact: $relative")
                val digest = MessageDigest.getInstance("SHA-256")
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(65536)
                    while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
                }
                check(HexFormat.of().formatHex(digest.digest()) == expectedHash) { "Maven hash mismatch: $relative" }
            }
            for (module in listOf("renderer-api", "renderer-core", "renderer-nvidia")) {
                check(names.contains("lib/$module-$publishedVersion.jar")) { "Missing runtime module: $module" }
                val prefix = "maven/top/ceroxe/rt/$module/$publishedVersion/$module-$publishedVersion"
                check(names.containsAll(listOf("$prefix.jar", "$prefix-sources.jar", "$prefix-javadoc.jar", "$prefix.pom", "$prefix.module"))) {
                    "Missing published artifacts: $module"
                }
            }
            logger.lifecycle("Distribution verified: {} files, every SHA-256 and runtime/source publication closure", entries.size)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        checksum.get().asFile.writeText(HexFormat.of().formatHex(digest.digest()) + "  " + artifact.name + "\n", StandardCharsets.UTF_8)
    }
}

tasks.register<Sync>("preparePublishedMavenConsumer") {
    group = "verification"
    description = "Prepares an isolated consumer that resolves only published Maven metadata."
    from(layout.projectDirectory.dir("gradle/published-consumer-smoke"))
    into(publishedConsumerDirectory)
}

tasks.register<Exec>("verifyPublishedMavenConsumer") {
    group = "verification"
    description = "Publishes all three modules and executes isolated API/runtime contracts through their Maven POMs."
    dependsOn(tasks.named("publishAllToLocalStagingRepository"))
    dependsOn(tasks.named("verifyPublishedMavenTopology"))
    dependsOn(verifyPublishedNvidiaRuntimeClosure)
    dependsOn(tasks.named("preparePublishedMavenConsumer"))

    val stagingRepository = layout.buildDirectory.dir("repository")
    val javaInstallationPaths = providers.gradleProperty("org.gradle.java.installations.paths")
    val toolchainJavaVersion = providers.gradleProperty("java_toolchain_version")
        .orElse(JavaVersion.current().majorVersion)
    inputs.dir(stagingRepository)
    inputs.dir(layout.projectDirectory.dir("gradle/published-consumer-smoke"))
    inputs.property("javaInstallationPaths", javaInstallationPaths.orElse(""))
    inputs.property("toolchainJavaVersion", toolchainJavaVersion)
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
        "verifyPublishedApiRuntime",
        "-PstagingRepository=${stagingRepository.get().asFile.toURI()}",
        "-PrendererVersion=${project.version}"
    )
    // The consumer is an isolated Gradle invocation and therefore cannot see root properties.
    // Forward only the compiler toolchain; its own build fixes --release to Java 25.
    doFirst {
        if (gradle.startParameter.isOffline) args("--offline")
        javaInstallationPaths.orNull?.let { paths ->
            args("-Porg.gradle.java.installations.paths=$paths")
        }
        args("-Pjava_toolchain_version=${toolchainJavaVersion.get()}")
    }
}

tasks.named("check") {
    dependsOn(tasks.named("verifyReproducibleArchiveConfiguration"))
}

val repositoryNeutralityFiles = files(
    // These files define the published coordinates, module graph, and release metadata.
    file("build.gradle.kts"),
    file("settings.gradle.kts"),
    file("gradle.properties"),
    publishedModuleNames.map { moduleName ->
        fileTree(moduleName) {
            include(
                "build.gradle.kts",
                "src/main/**/*.comp",
                "src/main/**/*.cpp",
                "src/main/**/*.glsl",
                "src/main/**/*.hpp",
                "src/main/**/*.java",
                "src/main/**/*.rahit",
                "src/main/**/*.rchit",
                "src/main/**/*.rgen",
                "src/main/**/*.rmiss",
                "src/main/**/*.txt",
                "src/main/resources/META-INF/services/**"
            )
        }
    }
)

tasks.register("verifyRepositoryNeutrality") {
    group = "verification"
    description = "Rejects host-specific identities from published modules and release metadata."
    inputs.files(repositoryNeutralityFiles)

    doLast {
        val forbidden = listOf(
            "legacy package" to Regex("(?i)\\b${"mc" + "vulkanrt"}\\b"),
            "legacy product" to Regex("(?i)\\b${"mine" + "craft"}\\b"),
            "legacy abbreviation" to Regex("(?<![A-Za-z0-9_])${"M" + "C"}(?![A-Za-z0-9_])"),
            "external engine" to Regex("(?i)\\b${"un" + "real"}(?:\\s+engine)?\\b"),
            "external engine version" to Regex("(?i)\\b${"ue" + "5"}\\b")
        )
        val violations = mutableListOf<String>()
        repositoryNeutralityFiles.files.sorted().forEach { file ->
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
    dependsOn(tasks.named("verifyPublishedMavenConsumer"))
    dependsOn(tasks.named("verifyReleaseChecksums"))
    dependsOn(":renderer-core:rendererCoreGpuSceneNativeGate")
    dependsOn(":renderer-nvidia:nvidiaNativeAcceptanceGate")
}

// A green bounded GPUScene gate does not establish the medium, generic-command,
// gallery or packaged-distribution contracts. Keep its fast entry point and make
// the complete release scope explicit in a separate, opt-in invocation.
val apiClosureRequiredTasks = listOf(
    ":check", ":renderer-api:check", ":renderer-core:check", ":renderer-nvidia:check", ":demos:hex-ball:check",
    ":renderer-core:rendererCoreGpuSceneNativeGate",
    ":renderer-core:rendererCoreNativeGate",
    ":renderer-core:rendererCoreMediumNativeGate",
    ":renderer-core:rendererCoreUnifiedApiNativeGate",
    ":renderer-nvidia:nvidiaNativeAcceptanceGate",
    ":demos:hex-ball:mediumGalleryNativeSelfTest",
    ":verifyPublishedMavenConsumer", ":verifyReleaseChecksums", ":verifyRuntimeDistribution"
)
tasks.register("verifyApiClosure") {
    group = "verification"
    description = "Runs every current-platform API, medium, vendor, gallery and packaged-consumer release gate."
    dependsOn(apiClosureRequiredTasks)
    doLast {
        val required = apiClosureRequiredTasks.map { project.tasks.getByPath(it) }
        val graph = gradle.taskGraph.allTasks
        val omitted = required.filter { !it.state.executed || it.state.failure != null }
        check(omitted.isEmpty()) { "API closure has incomplete required tasks: " + omitted.map { it.path } }
        val skippedNative = graph.filter { it is JavaExec && it.name.contains("Native")
                && (!it.state.executed || it.state.skipMessage == "SKIPPED" || it.state.skipMessage == "NO-SOURCE") }
        check(skippedNative.isEmpty()) { "API closure cannot skip native evidence: " + skippedNative.map { it.path } }
        val report = layout.buildDirectory.file("reports/api-closure/result.json").get().asFile
        report.parentFile.mkdirs()
        report.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(linkedMapOf(
            "passed" to true,
            "completedAtUtc" to Instant.now().toString(),
            "version" to project.version.toString(),
            "scope" to "documented Windows x86_64 Vulkan RT and NVIDIA runtime contracts",
            "requiredTasks" to required.map { task -> linkedMapOf("path" to task.path,
                "executed" to task.state.executed, "upToDate" to task.state.upToDate) },
            "nativeTasks" to graph.filter { it is JavaExec && it.name.contains("Native") }.map { task ->
                linkedMapOf("path" to task.path, "executed" to task.state.executed,
                    "upToDate" to task.state.upToDate) },
            "artifacts" to listOf(
                project(":renderer-api").tasks.named<Jar>("jar").get().archiveFile.get().asFile,
                project(":renderer-core").tasks.named<Jar>("jar").get().archiveFile.get().asFile,
                project(":renderer-nvidia").tasks.named<Jar>("jar").get().archiveFile.get().asFile,
                tasks.named<Zip>("runtimeDistribution").get().archiveFile.get().asFile
            ).map { artifact ->
                val digest = MessageDigest.getInstance("SHA-256")
                artifact.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        digest.update(buffer, 0, size)
                    }
                }
                linkedMapOf("file" to artifact.name, "bytes" to artifact.length(),
                    "sha256" to HexFormat.of().formatHex(digest.digest()))
            }
        ))) + "\n", StandardCharsets.UTF_8)
        logger.lifecycle("Complete current-platform API closure evidence: {}", report)
    }
}

// Reject subset selectors before executing any part of the complete gate. These
// properties remain useful for focused development, but cannot certify a release.
gradle.taskGraph.whenReady {
    if (allTasks.any { it.path == ":verifyApiClosure" }) {
        // A later failed invocation must not leave a previous green result looking current.
        layout.buildDirectory.file("reports/api-closure/result.json").get().asFile.delete()
        val selectors = listOf("externalSemaphoreParamsOnly", "shaderContractsOnly", "opticalBenchmark",
            "opticalCaustics", "opticalVolumeCaustics", "opticalOnlyThinCase", "precompiledSceneOnly",
            "productionBenchmark", "productionControl", "productionControlJar", "galleryTestScene", "galleryControlJar")
        val active = selectors.filter { project.providers.gradleProperty(it).isPresent }
        check(active.isEmpty()) { "verifyApiClosure requires full current-code tests; remove selectors: $active" }
        check(gradle.startParameter.excludedTaskNames.isEmpty()) {
            "verifyApiClosure does not allow excluded tasks: " + gradle.startParameter.excludedTaskNames
        }
    }
}

// The NVIDIA gate owns a real Vulkan device and Streamline's process-global pacer. Keep it
// isolated from the other strict-acceptance branches; dependsOn alone permits Gradle to run these
// GPU workloads concurrently and can starve FG before its asynchronous output is published.
listOf(
    "check",
    "verifyPublishedMavenConsumer",
    "verifyReleaseChecksums"
).forEach { taskName ->
    tasks.named(taskName).configure {
        mustRunAfter(":renderer-nvidia:nvidiaNativeAcceptanceGate")
    }
}
gradle.projectsEvaluated {
    project(":renderer-core").tasks.named("rendererCoreGpuSceneNativeGate").configure {
        mustRunAfter(":renderer-nvidia:nvidiaNativeAcceptanceGate")
    }
}

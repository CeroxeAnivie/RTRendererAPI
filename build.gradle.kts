import java.nio.charset.StandardCharsets
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

plugins {
    base
}

group = providers.gradleProperty("project_group").get()
version = providers.gradleProperty("project_version").get()

subprojects {
    group = rootProject.group
    version = rootProject.version

    tasks.withType<JavaCompile>().configureEach {
        // JDK 25 runs the compiler, while the repository's immutable runtime contract remains
        // Java 21. Keeping this literal at the root prevents -P properties from changing the
        // emitted class-file version for any production module, test source set, or demo.
        options.release = 21
    }

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
    fileTree("docs") { include("**/*.md") },
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

val previousApiVersion = providers.gradleProperty("previous_api_version")
val previousApiConsumerDirectory = layout.projectDirectory.dir("gradle/previous-api-consumer")
val previousApiConsumerClasses = previousApiConsumerDirectory.dir("build/classes/java/main")
val previousApiCentralAbi = previousApiVersion.map { version ->
    previousApiConsumerDirectory.file("build/abi/renderer-api-$version.abi")
}
val previousConsumerJavaInstallationPaths = providers.gradleProperty(
    "org.gradle.java.installations.paths"
)
val previousConsumerToolchainVersion = providers.gradleProperty("java_toolchain_version")
    .orElse(JavaVersion.current().majorVersion)

val compilePreviousApiConsumer = tasks.register<Exec>("compilePreviousApiConsumer") {
    group = "verification"
    description = "Compiles a consumer and derives exhaustive ABI evidence from the previous Maven Central API artifact."
    workingDir(previousApiConsumerDirectory)
    executable(rootProject.file("gradlew.bat"))
    args(
        "--no-daemon",
        "--console=plain",
        "--project-dir",
        previousApiConsumerDirectory.asFile.absolutePath,
        "clean",
        "compileJava",
        "generatePreviousRendererApiAbi",
        "-PrendererVersion=${previousApiVersion.get()}",
        "-Pjava_toolchain_version=${previousConsumerToolchainVersion.get()}"
    )
    inputs.files(fileTree(previousApiConsumerDirectory) {
        include("build.gradle.kts", "settings.gradle.kts", "src/**/*.java")
    })
    inputs.property("previousApiVersion", previousApiVersion)
    inputs.property("javaInstallationPaths", previousConsumerJavaInstallationPaths.orElse(""))
    inputs.property("toolchainJavaVersion", previousConsumerToolchainVersion)
    outputs.dir(previousApiConsumerClasses)
    outputs.file(previousApiCentralAbi)
    doFirst {
        previousConsumerJavaInstallationPaths.orNull?.let { paths ->
            args("-Porg.gradle.java.installations.paths=$paths")
        }
    }
}

val verifyPreviousApiConsumerRuntime = tasks.register<JavaExec>("verifyPreviousApiConsumerRuntime") {
    group = "verification"
    description = "Runs a previous-release client using only the current renderer-api classes."
    dependsOn(compilePreviousApiConsumer, ":renderer-api:classes")
    classpath = files(
        previousApiConsumerClasses,
        project(":renderer-api").layout.buildDirectory.dir("classes/java/main")
    )
    mainClass.set("compatibility.PreviousApiConsumer")
}

tasks.named("check") {
    dependsOn(verifyReleaseVersionConsistency)
    dependsOn(verifyPreviousApiConsumerRuntime)
}

tasks.register("publishAllToLocalStagingRepository") {
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
        "-PstagingRepository=${stagingRepository.get().asFile.toURI()}",
        "-PrendererVersion=${project.version}"
    )
    // The consumer is an isolated Gradle invocation and therefore cannot see root properties.
    // Forward only the compiler toolchain; its own build fixes --release to Java 21.
    doFirst {
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

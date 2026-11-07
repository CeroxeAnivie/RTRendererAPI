import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile

plugins { base }
group = providers.gradleProperty("project_group").get()
version = providers.gradleProperty("project_version").get()
val publishedModules = listOf("renderer-api", "renderer-rtxpt")
abstract class RendererHardwareGateLock : BuildService<BuildServiceParameters.None>
val hardwareGate = gradle.sharedServices.registerIfAbsent("rendererHardwareGateLock", RendererHardwareGateLock::class) {
    maxParallelUsages.set(1)
}
subprojects {
    group = rootProject.group
    version = rootProject.version
    dependencyLocking { lockAllConfigurations() }
    tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8"; options.release = 25 }
    tasks.withType<JavaExec>().configureEach {
        jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
        if (name.contains("Native") || name.contains("Benchmark") || name.contains("Composition")) usesService(hardwareGate)
    }
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    plugins.withId("java-library") {
        apply(plugin = "maven-publish")
        tasks.named<Jar>("jar") { from(rootProject.file("LICENSE")) { into("META-INF") } }
        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("mavenJava") {
                    from(components["java"])
                    artifactId = project.name
                    pom {
                        name.set(if (project.name == "renderer-api") "RTRendererAPI" else "RTRendererAPI RTXPT Bridge")
                        description.set(if (project.name == "renderer-api") "Host-independent rendering contracts." else "External consumer bridge to unmodified NVIDIA RTXPT.")
                        url.set("https://github.com/CeroxeAnivie/RTRendererAPI")
                        licenses { license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        } }
                        developers { developer { id.set("CeroxeAnivie"); name.set("Ceroxe") } }
                        scm {
                            connection.set("scm:git:git://github.com/CeroxeAnivie/RTRendererAPI.git")
                            developerConnection.set("scm:git:ssh://github.com/CeroxeAnivie/RTRendererAPI.git")
                            url.set("https://github.com/CeroxeAnivie/RTRendererAPI")
                        }
                    }
                }
            }
            repositories { maven {
                name = "localStaging"
                url = rootProject.layout.buildDirectory.dir("repository").get().asFile.toURI()
            } }
        }
    }
}
tasks.named("assemble") { dependsOn(publishedModules.map { ":$it:assemble" }) }
tasks.named("check") { dependsOn(publishedModules.map { ":$it:check" }) }
tasks.register("verifyMajorReleaseContract") {
    group = "verification"
    inputs.file("gradle.properties")
    doLast {
        check(project.version.toString().matches(Regex("(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)")))
        check(rootProject.subprojects.map { it.name }.toSet() == publishedModules.toSet()) { "Only renderer-api and renderer-rtxpt belong to this runtime" }
    }
}
tasks.register("publishAllToLocalStagingRepository") {
    group = "publishing"
    dependsOn(publishedModules.map { ":$it:publishMavenJavaPublicationToLocalStagingRepository" })
}
tasks.register("verifyPublishedMavenTopology") {
    group = "verification"
    dependsOn(publishedModules.map { ":$it:generatePomFileForMavenJavaPublication" })
    doLast {
        val documents = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isExpandEntityReferences = false
        }.newDocumentBuilder()
        publishedModules.forEach { module ->
            val pom = project(module).file("build/publications/mavenJava/pom-default.xml")
            val dependencies = documents.parse(pom).getElementsByTagName("dependency")
            var rendererEdges = 0
            for (i in 0 until dependencies.length) {
                val dependency = dependencies.item(i) as org.w3c.dom.Element
                fun field(name: String) = dependency.getElementsByTagName(name).item(0)?.textContent
                if (field("groupId") == project.group.toString()) {
                    check(module == "renderer-rtxpt" && field("artifactId") == "renderer-api" && field("version") == project.version.toString() && field("scope") == "compile") { "Unexpected renderer dependency in $module" }
                    rendererEdges++
                }
            }
            check(rendererEdges == if (module == "renderer-rtxpt") 1 else 0)
        }
    }
}
tasks.register("verifyProjectLicenseConsistency") {
    group = "verification"
    dependsOn(publishedModules.map { ":$it:jar" })
    inputs.file("LICENSE")
    doLast {
        val expected = file("LICENSE").readBytes()
        publishedModules.forEach { module ->
            val jar = project(module).tasks.named<Jar>("jar").get().archiveFile.get().asFile
            ZipFile(jar).use { zip ->
                val entry = zip.getEntry("META-INF/LICENSE") ?: error("Missing project license: $module")
                check(zip.getInputStream(entry).use { it.readBytes() }.contentEquals(expected))
            }
        }
    }
}
tasks.register("generateReleaseChecksums") {
    group = "publishing"
    dependsOn("publishAllToLocalStagingRepository")
    val output = layout.buildDirectory.file("release/SHA256SUMS")
    outputs.file(output)
    doLast {
        val repository = layout.buildDirectory.dir("repository").get().asFile
        val files = publishedModules.flatMap { module ->
            repository.resolve("top/ceroxe/rt/" + module + "/" + project.version).listFiles().orEmpty().filter { it.extension in setOf("jar", "pom", "module") }
        }.sortedBy { it.relativeTo(repository).invariantSeparatorsPath }
        check(files.isNotEmpty())
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText(files.joinToString("\n", postfix = "\n") {
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(it.readBytes())) + "  " + it.relativeTo(repository).invariantSeparatorsPath
            }, Charsets.UTF_8)
        }
    }
}
tasks.named("check") { dependsOn("verifyMajorReleaseContract", "verifyPublishedMavenTopology") }

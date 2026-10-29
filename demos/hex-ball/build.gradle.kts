import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import groovy.json.JsonSlurper

plugins {
    application
}

description = "Interactive hex-ball sample and GPU smoke workload for RTRendererAPI."

dependencies {
    implementation(project(":renderer-api"))
    runtimeOnly(project(":renderer-nvidia"))
}

val toolchainJavaVersion = 25
val unsafeAccessJvmArg = if (toolchainJavaVersion >= 24) {
    "--sun-misc-unsafe-memory-access=allow"
} else {
    null
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(toolchainJavaVersion)
    }
}

application {
    mainClass.set("demo.HexBallDemo")
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "--enable-native-access=ALL-UNNAMED"
    ) + listOfNotNull(unsafeAccessJvmArg)
}

// The in-repository demo uses the current modules. verifyPublishedMavenConsumer independently
// proves the staged Maven graph without making the demo depend on its own publication.

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.processResources {
    // `gradle run` executes class directories, which have no JAR manifest. Emit the same build
    // identity into every runtime layout so the HUD never mislabels a released API as a dev build.
    inputs.property("apiVersion", project.version.toString())
    filesMatching("META-INF/rtrenderer-api-demo.properties") {
        expand("apiVersion" to project.version.toString())
    }
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs(
        "-Dfile.encoding=UTF-8",
        "--enable-native-access=ALL-UNNAMED"
    )
    unsafeAccessJvmArg?.let { jvmArgs(it) }
    // Process isolation does not inherit Gradle JVM system properties. Forward only the three
    // reviewed demo controls so :run and the executable JAR expose the same feature policy.
    listOf("demo.feature-profile", "demo.disable-fg", "demo.fg-multiplier").forEach { propertyName ->
        providers.systemProperty(propertyName).orNull?.let { propertyValue ->
            systemProperty(propertyName, propertyValue)
        }
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.withType<Jar>().configureEach {
    manifest.attributes("Implementation-Version" to project.version.toString())
}

tasks.test {
    // This module uses executable contract tests and intentionally has no test-engine dependency.
    failOnNoDiscoveredTests = false
}

tasks.jar {
    archiveClassifier.set("thin")
}

val shadowJar = tasks.register<Jar>("shadowJar") {
    group = "build"
    description = "Builds the self-contained executable hex-ball demo from the released runtime."
    dependsOn(tasks.classes, configurations.runtimeClasspath)
    archiveBaseName.set("RTRendererAPI-HexBallDemo")
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isZip64 = true
    manifest {
        attributes(
            "Main-Class" to "demo.DemoLauncher",
            "Enable-Native-Access" to "ALL-UNNAMED",
            "Implementation-Version" to project.version.toString()
        )
    }
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get().map { dependency ->
            if (dependency.isDirectory) dependency else zipTree(dependency)
        }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

val physicsSelfTest = tasks.register<JavaExec>("physicsSelfTest") {
    group = "verification"
    description = "Verifies elastic collision invariants and hexagonal containment."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("demo.HexPhysicsSelfTest")
}

tasks.register<JavaExec>("demoPerformanceNativeBenchmark") {
    group = "verification"
    description = "Measures identical frozen demo work, separating startup, warmup and GPU time."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("demo.DemoPerformanceBenchmark")
}

tasks.register<JavaExec>("surfacePathNativeSelfTest") {
    group = "verification"
    description = "Compares demo SDR/HDR pixels and dynamic transport transitions with the full shader."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("demo.DemoSurfaceNativeSelfTest")
    val validationLog = layout.buildDirectory.file("reports/surface-path-validation.jsonl")
    systemProperty("top.ceroxe.rt.validation.logPath", validationLog.get().asFile.absolutePath)
    doFirst {
        val log = validationLog.get().asFile
        log.parentFile.mkdirs()
        log.writeText("", Charsets.UTF_8)
    }
    doLast {
        val events = validationLog.get().asFile.readLines(Charsets.UTF_8)
                .filter(String::isNotBlank).map { JsonSlurper().parseText(it) as Map<*, *> }
        check(events.any { it["event"] == "validation" }) { "No Vulkan validation evidence was produced" }
        check(events.none { it["severity"] == "ERROR" || it["severity"] == "WARNING"
                || it["event"] == "dropSummary" }) { "Vulkan validation reported warnings, errors or dropped evidence" }
    }
}

tasks.register<JavaExec>("programmableSceneDemo") {
    group = "application"
    description = "Runs the public programmable-scene, application-buffer and native HDR example."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("demo.ProgrammableSceneDemo")
    args("--verify")
}

val featureIntegrationSelfTest = tasks.register<JavaExec>("featureIntegrationSelfTest") {
    group = "verification"
    description = "Verifies the production feature profile, projection contract, and HUD mapping."
    dependsOn(tasks.testClasses, tasks.processResources)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("demo.DemoFeatureIntegrationSelfTest")
}

val verifyHexBallRuntimeClosure = tasks.register("verifyHexBallRuntimeClosure") {
    group = "verification"
    description = "Verifies the executable demo JAR with the shared NVIDIA runtime closure contract."
    dependsOn(shadowJar)
    inputs.file(shadowJar.flatMap { it.archiveFile })
    doLast {
        NvidiaRuntimeClosure.verify(
            shadowJar.get().archiveFile.get().asFile,
            "HexBall executable JAR"
        )
    }
}

tasks.assemble {
    dependsOn(verifyHexBallRuntimeClosure)
}

tasks.check {
    dependsOn(physicsSelfTest, featureIntegrationSelfTest, verifyHexBallRuntimeClosure)
}

// Deliberate GPU launch only: never a dependency of assemble or check.
tasks.register<JavaExec>("mediumGalleryDemo") {
    group = "application"
    description = "Runs the 2K/1-spp/2x-FG optical gallery (executes GPU work)."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("demo.MediumGalleryDemo")
    providers.gradleProperty("galleryArgs").orNull?.let { args(it.split(" ").filter(String::isNotBlank)) }
}

tasks.register<JavaExec>("mediumCertificationDemo") {
    group = "application"
    description = "Runs the selectable 7.0.0 medium route certification lab (executes GPU work)."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("demo.MediumCertificationDemo")
    providers.gradleProperty("certificationArgs").orNull?.let { args(it.split(" ").filter(String::isNotBlank)) }
}

val mediumCertificationSceneSelfTest = tasks.register<JavaExec>("mediumCertificationSceneSelfTest") {
    group = "verification"
    description = "Validates all isolated medium certification fixtures and hotkey mappings without GPU work."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("demo.MediumCertificationSceneSelfTest")
}
tasks.check { dependsOn(mediumCertificationSceneSelfTest) }

val mediumGallerySceneSelfTest = tasks.register<JavaExec>("mediumGallerySceneSelfTest") {
    group = "verification"
    description = "Validates the optical gallery fixture and defaults without executing GPU work."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("demo.MediumGallerySceneSelfTest")
}
tasks.check { dependsOn(mediumGallerySceneSelfTest) }

// Requires explicit approval to execute; kept outside check/assemble.
tasks.register<JavaExec>("mediumGalleryNativeSelfTest") {
    group = "verification"
    description = "Executes the GPU gallery feature/image and representation parity gate."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("demo.MediumGalleryNativeSelfTest")
    val validationLog = layout.buildDirectory.file("reports/medium-gallery/validation.jsonl")
    systemProperty("top.ceroxe.rt.validation.logPath", validationLog.get().asFile.absolutePath)
    doFirst {
        val log = validationLog.get().asFile
        log.parentFile.mkdirs()
        log.writeText("", Charsets.UTF_8)
    }
    doLast {
        val events = validationLog.get().asFile.readLines(Charsets.UTF_8)
            .filter(String::isNotBlank).map { JsonSlurper().parseText(it) as Map<*, *> }
        check(events.any { it["event"] == "validation" }) { "No Vulkan validation evidence was produced" }
        check(events.none { it["severity"] == "ERROR" || it["severity"] == "WARNING" || it["event"] == "dropSummary" }) {
            "Vulkan validation reported warnings, errors or dropped evidence"
        }
    }
}

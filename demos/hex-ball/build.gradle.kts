import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    application
}

description = "Interactive hex-ball sample and GPU smoke workload for RTRendererAPI."

dependencies {
    // Resolve exactly as an external consumer does from Maven Central.
    implementation("${rootProject.group}:renderer-api:${rootProject.version}")
}

val toolchainJavaVersion = rootProject.providers.gradleProperty("java_toolchain_version")
    .orElse(JavaVersion.current().majorVersion)
    .get()
    .toInt()
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

/*
 * Dependency resolution remains Central-first in settings.gradle.kts. When the current
 * version is not published there yet, these task edges materialize the exact same coordinates
 * in build/repository before Gradle resolves the demo runtime. The localBuildFallback repository
 * then supplies only the missing coordinate; it is a release bootstrap path, not a second API
 * or a replacement for Central.
 */
tasks.named("run") {
    dependsOn(rootProject.tasks.named("publishAllToLocalStagingRepository"))
}

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
    // Keep the same Central-first/local-build-fallback contract as the interactive run task.
    dependsOn(rootProject.tasks.named("publishAllToLocalStagingRepository"))
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

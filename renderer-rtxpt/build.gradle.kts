import java.security.MessageDigest
import java.util.HexFormat
import java.nio.file.Files
import java.util.zip.ZipFile
import org.gradle.api.tasks.Sync
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins { `java-library` }
description = "Thin in-process JNI host for NVIDIA RTXPT managed rendering."
dependencies { compileOnly(project(":renderer-api")); testImplementation(project(":renderer-api")) }
val lightingExampleLwjglVersion = rootProject.providers.gradleProperty("lwjgl_version").getOrElse("3.4.3")
dependencies {
    testImplementation(project(":renderer-core"))
    testImplementation("org.lwjgl:lwjgl:$lightingExampleLwjglVersion")
    testImplementation("org.lwjgl:lwjgl-vulkan:$lightingExampleLwjglVersion")
    testImplementation("org.lwjgl:lwjgl-shaderc:$lightingExampleLwjglVersion")
    testImplementation("org.lwjgl:lwjgl-glfw:$lightingExampleLwjglVersion")
    testImplementation("org.lwjgl:lwjgl-opengl:$lightingExampleLwjglVersion")
    testRuntimeOnly("org.lwjgl:lwjgl:$lightingExampleLwjglVersion:natives-windows")
    testRuntimeOnly("org.lwjgl:lwjgl-glfw:$lightingExampleLwjglVersion:natives-windows")
    testRuntimeOnly("org.lwjgl:lwjgl-opengl:$lightingExampleLwjglVersion:natives-windows")
    testRuntimeOnly("org.lwjgl:lwjgl-shaderc:$lightingExampleLwjglVersion:natives-windows")
}
sourceSets.named("test") {
    java.srcDir(rootProject.file("examples/native-lighting-extension"))
    java.srcDir(rootProject.file("examples/native-lighting-opengl"))
    java.srcDir(rootProject.file("examples/host-surface-opengl"))
}
configurations.named("testRuntimeClasspath") {
    // Tests of an unreleased patch version must use the sibling provider, not Maven's old version.
    resolutionStrategy.dependencySubstitution {
        substitute(module("${rootProject.group}:renderer-nvidia:${rootProject.version}"))
            .using(project(":renderer-nvidia"))
    }
}
java { toolchain { languageVersion = JavaLanguageVersion.of(25) }; withSourcesJar(); withJavadocJar() }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.withType<Test>().configureEach { failOnNoDiscoveredTests = false }
tasks.withType<JavaExec>().configureEach {
    workingDir(rootProject.projectDir)
    jvmArgs("-Xmx512m", "-XX:-UseJVMCICompiler", "--enable-native-access=ALL-UNNAMED", "-Dfile.encoding=UTF-8")
    systemProperties(System.getProperties().stringPropertyNames().filter { it.startsWith("top.ceroxe.rt.rtxpt.") }.associateWith(System::getProperty))
    if (project.hasProperty("rtxptTestPackaged")) {
        dependsOn(tasks.named("jar"))
        environment("RTR_RTXPT_REQUIRE_PRECOMPILED", "1")
        environment("RTR_RTXPT_SHADER_MANIFEST_DIR", "")
        doFirst {
            check(!systemProperties.containsKey("top.ceroxe.rt.rtxpt.runtime") && !systemProperties.containsKey("top.ceroxe.rt.rtxpt.bridge"))
            classpath = sourceSets.test.get().output + configurations.testRuntimeClasspath.get() + files(tasks.named("jar"))
        }
    }
}
tasks.register<Exec>("buildRtxptBridge") {
    group = "build"
    workingDir(rootProject.projectDir)
    commandLine("pwsh", "-NoProfile", "-File", "tools/Build-RtxptBridge.ps1")
}
tasks.register<JavaExec>("rtxptAdapterSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest")
}
tasks.register<JavaExec>("rtxptBudgetContractSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptBudgetContractSelfTest")
}
tasks.register<JavaExec>("rtxptLightMapSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptLightMapSelfTest")
}
tasks.register<JavaExec>("nativeLightingStyleSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.NativeLightingStyleSelfTest")
}
tasks.register<JavaExec>("rtxptNativeLightingNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingNativeSelfTest")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
}
tasks.register<JavaExec>("rtxptRetainedLightingRepairNativeSelfTest") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptRetainedLightingRepairNativeSelfTest")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
    systemProperty("top.ceroxe.rt.rtxpt.testOutput",rootProject.file("build/reports/retained-lighting-repairs").absolutePath)
}

listOf("RtxptTexturePatchNativeSelfTest", "RtxptDynamicResourcesNativeSelfTest", "RtxptDynamicResourcesSelfTest", "RtxptLocalPreparationSelfTest", "RtxptLocalResourceNativeSelfTest", "RtxptMixedResourceNativeSelfTest", "RtxptLightingAttributeNativeSelfTest", "RtxptRetainedTransactionBenchmark", "RtxptRetainedDependencySelfTest", "GlGuidedLightingComposerSelfTest",
    "RtxptLightingGroupSpecializationNativeSelfTest", "RtxptWindowResponsivenessNativeSelfTest", "RtxptLightingResourceMatrixNativeSelfTest",
    "RtxptLightingPressureNativeSelfTest", "RtxptLightingDiagnosticsCostBenchmark", "RtxptLightingInstrumentationNativeSelfTest",
    "RtxptLightingLifecycleNativeSelfTest").forEach { name ->
    tasks.register<JavaExec>(name.replaceFirstChar { it.lowercase() }) {
        group = "verification"; dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("top.ceroxe.rt.renderer.rtxpt.$name")
        if (name == "RtxptLightingAttributeNativeSelfTest" && project.hasProperty("lightingAttributeCases")) args(project.property("lightingAttributeCases").toString().split(","))
        environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
        systemProperty("top.ceroxe.rt.rtxpt.testOutput",rootProject.file("build/reports/lighting-attributes").absolutePath)
        doFirst { rootProject.file("build/renderer-validation-classpath.txt").writeText(classpath.asPath, Charsets.UTF_8) }
    }
}
tasks.register<JavaExec>("rtxptNativeLightingExtensionNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingExtensionNativeSelfTest")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
}
tasks.register<JavaExec>("rtxptNativeLightingGlSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingGlSelfTest")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
    doFirst { rootProject.file("build/native-lighting-gl-classpath.txt").writeText(classpath.asPath, Charsets.UTF_8) }
}
tasks.register<JavaExec>("rtxptDiagnosticMonitorNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptDiagnosticMonitorNativeSelfTest")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
}
tasks.register<JavaExec>("hostSurfaceWriterSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.examples.GlHostSurfaceWriterSelfTest")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
}

tasks.register<JavaExec>("rtxptStructuralLightingNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptStructuralLightingNativeSelfTest")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
    environment("RTR_RTXPT_DIAGNOSTIC_FREEZE_NOISE", "1")
    if (project.hasProperty("lightingSizes")) args(project.property("lightingSizes").toString().split(","))
}

tasks.register<JavaExec>("rtxptSparseLightingNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptSparseLightingNativeSelfTest")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
    environment("RTR_RTXPT_DIAGNOSTIC_FREEZE_NOISE", "1")
}

tasks.register<JavaExec>("rtxptFramePreparationNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptFramePreparationNativeSelfTest")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
    environment("RTR_RTXPT_DIAGNOSTIC_FREEZE_NOISE", "1")
    environment("RTR_RTXPT_SHADER_MANIFEST_DIR", rootProject.file("build/rtxpt-shader-manifests").absolutePath)
    if (project.hasProperty("preparationCases")) args(project.property("preparationCases").toString().split(","))
}

tasks.register<JavaExec>("rtxptPackagedFramePreparationNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses, tasks.jar)
    classpath = sourceSets.test.get().output + configurations.testRuntimeClasspath.get() + files(tasks.jar)
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptFramePreparationNativeSelfTest")
    environment("RTR_RTXPT_REQUIRE_PRECOMPILED", "1")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
    environment("RTR_RTXPT_DIAGNOSTIC_FREEZE_NOISE", "1")
    environment("RTR_RTXPT_SHADER_MANIFEST_DIR", "")
    systemProperty("top.ceroxe.rt.rtxpt.testOutput",rootProject.file("build/reports/rtxpt-frame-packaged").absolutePath)
    doFirst {
        check(!systemProperties.containsKey("top.ceroxe.rt.rtxpt.runtime") && !systemProperties.containsKey("top.ceroxe.rt.rtxpt.bridge"))
        workingDir(Files.createTempDirectory("rf-").toFile())
        systemProperty("top.ceroxe.rt.rtxpt.cache",workingDir.absolutePath)
    }
    doLast { workingDir.deleteRecursively() }
}

tasks.register<JavaExec>("rtxptFrameBenchmark") {
    group = "verification"; dependsOn(tasks.testClasses)
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptFrameBenchmark")
    val baseline = project.findProperty("benchmarkBaseline")?.toString()
    classpath = if (baseline == null) sourceSets.test.get().runtimeClasspath else
        files("${System.getProperty("user.home")}/.m2/repository/top/ceroxe/rt/renderer-api/$baseline/renderer-api-$baseline.jar",
            "${System.getProperty("user.home")}/.m2/repository/top/ceroxe/rt/renderer-rtxpt/$baseline/renderer-rtxpt-$baseline.jar") + sourceSets.test.get().runtimeClasspath
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
    if (project.hasProperty("benchmarkInstances")) args(project.property("benchmarkInstances").toString())
    doFirst { rootProject.file("build/frame-benchmark-classpath.txt").writeText(classpath.asPath,Charsets.UTF_8) }
}

tasks.register("rtxptReleaseValidationClasspath") {
    group = "verification"; dependsOn(tasks.testClasses,tasks.jar)
    doLast {
        rootProject.file("build/release-validation-classpath.txt").writeText(
            (sourceSets.test.get().output + configurations.testRuntimeClasspath.get() + files(tasks.jar)).asPath,Charsets.UTF_8)
    }
}
tasks.register<JavaExec>("rtxptLightingNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    description = "Short background native lighting updates, actual counters, fresh-load reference, and lifecycle checks."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptLightingNativeSelfTest")
    environment("RTR_RTXPT_DIAGNOSTIC_FREEZE_NOISE", "1")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
    if (project.hasProperty("lightingCases")) args(project.property("lightingCases").toString().split(","))
}
tasks.register<JavaExec>("rtxptRetainedNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptRetainedNativeSelfTest")
    environment("RTR_RTXPT_DIAGNOSTIC_FREEZE_NOISE", "0")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
    if (project.hasProperty("retainedCases")) args(project.property("retainedCases").toString().split(","))
}

tasks.register<JavaExec>("rtxptScalabilityBenchmark") {
    group = "verification"; dependsOn(tasks.testClasses)
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptScalabilityBenchmark")
    val baseline = project.findProperty("benchmarkBaseline")?.toString()
    classpath = if (baseline == null) sourceSets.test.get().runtimeClasspath else
        files("${System.getProperty("user.home")}/.m2/repository/top/ceroxe/rt/renderer-api/$baseline/renderer-api-$baseline.jar",
            "${System.getProperty("user.home")}/.m2/repository/top/ceroxe/rt/renderer-rtxpt/$baseline/renderer-rtxpt-$baseline.jar") + sourceSets.test.get().runtimeClasspath
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
    if (project.hasProperty("benchmarkCases")) args(project.property("benchmarkCases").toString().split(","))
}
tasks.register<JavaExec>("rtxptMixedBenchmark") {
    group = "verification"; dependsOn(tasks.testClasses)
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptMixedBenchmark")
    val baseline = project.findProperty("benchmarkBaseline")?.toString()
    classpath = if (baseline == null) sourceSets.test.get().runtimeClasspath else
        files("${System.getProperty("user.home")}/.m2/repository/top/ceroxe/rt/renderer-api/$baseline/renderer-api-$baseline.jar",
            "${System.getProperty("user.home")}/.m2/repository/top/ceroxe/rt/renderer-rtxpt/$baseline/renderer-rtxpt-$baseline.jar") + sourceSets.test.get().runtimeClasspath
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
    if (project.hasProperty("mixedCases")) args(project.property("mixedCases").toString().split(","))
    usesService(rootProject.gradle.sharedServices.registrations.named("rendererHardwareGateLock").get().service)
}
tasks.register<JavaExec>("rtxptContinuityNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptContinuityNativeSelfTest")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
    environment("RTR_RTXPT_DIAGNOSTIC_FREEZE_NOISE", "0")
}
tasks.register<JavaExec>("rtxptBackgroundNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptBackgroundNativeSelfTest")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
    environment("RTR_RTXPT_DIAGNOSTIC_FREEZE_NOISE", "1")
}
val transparentCompositionRuntime = configurations.create("transparentCompositionRuntime") {
    extendsFrom(configurations.testRuntimeClasspath.get())
    resolutionStrategy.dependencySubstitution {
        substitute(module("${rootProject.group}:renderer-nvidia:${rootProject.version}")).using(project(":renderer-nvidia"))
    }
}
dependencies { add(transparentCompositionRuntime.name, project(":renderer-core")) }

tasks.register<JavaExec>("rtxptTransparentCompositionSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses, ":renderer-core:classes")
    classpath = sourceSets.test.get().output + sourceSets.main.get().output + transparentCompositionRuntime
    doFirst { rootProject.file("build/transparent-composition-classpath.txt").writeText(classpath.asPath, Charsets.UTF_8) }
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptTransparentCompositionSelfTest")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
    environment("RTR_RTXPT_DIAGNOSTIC_FREEZE_NOISE", "1")
    args(project.findProperty("transparentPresentation")?.toString() ?: "native", project.findProperty("transparentRoute")?.toString() ?: "rr")
}

tasks.register<JavaExec>("rtxptTransparentNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptTransparentNativeSelfTest")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
    environment("RTR_RTXPT_DIAGNOSTIC_FREEZE_NOISE", "1")
    if (project.hasProperty("transparentCases")) args(project.property("transparentCases").toString().split(","))
}

tasks.register<JavaExec>("rtxptCoverageNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptCoverageNativeSelfTest")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
}


tasks.register<JavaExec>("rtxptLightTreeNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptLightTreeNativeSelfTest")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
    environment("RTR_RTXPT_LIGHT_STATS", "1")
    if (project.hasProperty("treeCase")) args(project.property("treeCase").toString())
}

tasks.register<JavaExec>("rtxptNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptNativeSelfTest")
}
tasks.register<JavaExec>("rtxptBudgetNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptBudgetNativeSelfTest")
}
tasks.register<JavaExec>("rtxptPackagedBudgetNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses, tasks.jar)
    description = "Tests real budget settings and all six optical budgets from a fresh foreign JAR install, with compilation forbidden."
    classpath = sourceSets.test.get().output + configurations.testRuntimeClasspath.get() + files(tasks.jar)
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptShaderBundleNativeSelfTest")
    environment("RTR_RTXPT_REQUIRE_PRECOMPILED", "1")
    environment("RTR_RTXPT_SHADER_MANIFEST_DIR", "")
    systemProperty("top.ceroxe.rt.rtxpt.testOutput", rootProject.layout.buildDirectory.dir("reports/rtxpt-packaged-budgets").get().asFile.absolutePath)
    doFirst {
        check(!systemProperties.containsKey("top.ceroxe.rt.rtxpt.runtime") && !systemProperties.containsKey("top.ceroxe.rt.rtxpt.bridge")) {
            "Packaged acceptance must use the JAR payload, without native overrides"
        }
        workingDir(Files.createTempDirectory("rb-").toFile())
        systemProperty("top.ceroxe.rt.rtxpt.cache", workingDir.absolutePath)
    }
    doLast { workingDir.deleteRecursively() }
}
tasks.register<JavaExec>("rtxptAdvancedNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses, tasks.jar)
    classpath = sourceSets.test.get().output + configurations.testRuntimeClasspath.get() + files(tasks.jar)
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptAdvancedNativeSelfTest")
    doFirst { workingDir(Files.createTempDirectory("rtxpt-advanced-consumer-").toFile()) }
    systemProperty("top.ceroxe.rt.rtxpt.testOutput", rootProject.layout.buildDirectory.dir("reports/rtxpt-advanced").get().asFile.absolutePath)
    doLast { workingDir.deleteRecursively() }
}

tasks.register<JavaExec>("rtxptPackagedNativeSelfTest") {
    group = "verification"
    description = "Runs the native contract from the bundled JAR outside the source checkout."
    dependsOn(tasks.testClasses, tasks.jar)
    classpath = sourceSets.test.get().output + configurations.testRuntimeClasspath.get() + files(tasks.jar)
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptNativeSelfTest")
    systemProperty("top.ceroxe.rt.rtxpt.testOutput", layout.buildDirectory.dir("reports/packaged-native").get().asFile.absolutePath)
    doFirst {
        check(!systemProperties.containsKey("top.ceroxe.rt.rtxpt.runtime") && !systemProperties.containsKey("top.ceroxe.rt.rtxpt.bridge")) {
            "Packaged acceptance must use the JAR payload, without native overrides"
        }
        workingDir(Files.createTempDirectory("rtxpt-packaged-consumer-").toFile())
    }
    doLast { workingDir.deleteRecursively() }
}
tasks.named("check") { dependsOn("rtxptAdapterSelfTest", "rtxptBudgetContractSelfTest") }

val nativeRuntime = rootProject.layout.buildDirectory.dir("rtxpt-sdk/bin")
val nativeSymbols = tasks.register<Zip>("rtxptNativeSymbols") {
    group = "distribution"
    archiveClassifier.set("native-symbols")
    from(nativeRuntime) { include("rtrenderer_rtxpt.pdb") }
    from("NOTICE-RTXPT.txt")
    doFirst { check(nativeRuntime.get().file("rtrenderer_rtxpt.pdb").asFile.isFile) { "Build the matching RTXPT PDB first" } }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
extensions.configure<PublishingExtension> {
    publications.named<MavenPublication>("mavenJava") { artifact(nativeSymbols) }
}
val rtxptShaderSource = rootProject.layout.buildDirectory.dir("rtxpt-sdk/Rtxpt/Shaders")
val syncRtxptRuntimeShaders = tasks.register<Sync>("syncRtxptRuntimeShaders") {
    from(rtxptShaderSource)
    into(nativeRuntime.map { it.dir("ShaderDynamic/Source/Rtxpt") })
    doFirst {
        val nee = rtxptShaderSource.get().file("PathTracer/PathTracerNEE.hlsli").asFile
        check(nee.isFile && nee.readText(Charsets.UTF_8).contains("RtrSmallPointNee.hlsli")) {
            "Build the patched RTXPT shader source first: tools/Build-RtxptBridge.ps1"
        }
    }
}
val sixBudgetShaders = rootProject.layout.buildDirectory.dir("rtxpt-six-budget-bundle")
val verifyRtxptShaderBudgets = tasks.register<Exec>("verifyRtxptShaderBudgets") {
    group = "verification"
    description = "Checks all six precompiled optical budgets, upstream permutations and source hashes."
    dependsOn(syncRtxptRuntimeShaders)
    inputs.dir(sixBudgetShaders)
    inputs.dir(nativeRuntime.map { it.dir("ShaderDynamic/Source") })
    workingDir(rootProject.projectDir)
    commandLine("pwsh", "-NoProfile", "-File", "tools/Build-RtxptShaderBudgets.ps1", "-VerifyOnly", "-RequireReleaseCoverage")
}
val nativeLightingSdk = rootProject.layout.buildDirectory.dir("rtxpt-native-lighting-sdk")
val stageNativeLightingSdk = tasks.register<Exec>("stageNativeLightingSdk") {
    group = "distribution"
    workingDir(rootProject.projectDir)
    commandLine("pwsh", "-NoProfile", "-File", "tools/Stage-NativeLightingSdk.ps1", "-Version", project.version.toString())
    inputs.files(rootProject.file("tools/Stage-NativeLightingSdk.ps1"), rootProject.file("tools/Build-NativeLightingExtension.ps1"), rootProject.file("tools/Invoke-BoundedBuild.ps1"))
    inputs.dir(rootProject.file("examples")); inputs.dir(rootProject.file("docs"))
    inputs.files(rootProject.file("renderer-rtxpt/src/main/cpp/RtrNativeLighting.hlsl"), rootProject.file("renderer-rtxpt/src/main/cpp/RtrNativeLightingStyle.hlsli"))
    inputs.dir(rootProject.file("build/rtxpt-sdk/External/Donut/include/donut/shaders"))
    outputs.dir(nativeLightingSdk)
}
val runtimeArchive = tasks.register<Zip>("packageRtxptRuntime") {
    group = "distribution"
    description = "Packages the pinned RTXPT native renderer, runtime shaders and upstream licenses."
    dependsOn(verifyRtxptShaderBudgets, stageNativeLightingSdk)
    from(nativeLightingSdk)
    archiveFileName.set("rtxpt-runtime.zip")
    destinationDirectory.set(layout.buildDirectory.dir("native-bundle"))
    from(nativeRuntime) {
        // Windows resolves DLL names without case; the archive filter must do the same.
        isCaseSensitive = false
        include("*.dll", "sl.*.json", "D3D12/D3D12Core.dll", "Assets/**", "ThirdPartyLicenses/**",
            "ShaderPrecompiled/**", "ShaderDynamic/Source/**", "ShaderDynamic/Tools/d3d12/x64/*.dll",
            "ShaderDynamic/Tools/d3d12/x64/dxc.exe")
        exclude("**/*.pdb", "**/.gitkeep")
        rename { if (it.equals("rtrenderer_rtxpt.dll", ignoreCase = true)) "rtrenderer_rtxpt.dll" else it }
    }
    from(sixBudgetShaders) {
        include("**/*.bin", "bundle.json")
        into("ShaderDynamic/Bin/dxil")
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    doFirst {
        listOf("rtrenderer_rtxpt.dll", "ThirdPartyLicenses/RTXPT.txt").forEach {
            check(nativeRuntime.get().file(it).asFile.isFile) { "Build the native runtime first: tools/Build-RtxptBridge.ps1 (missing $it)" }
        }
    }
    doLast {
        ZipFile(archiveFile.get().asFile).use { zip ->
            listOf("rtrenderer_rtxpt.dll", "sl.interposer.dll", "NRD.dll", "omm-lib.dll",
                "D3D12/D3D12Core.dll", "ShaderDynamic/Tools/d3d12/x64/dxc.exe",
                "ThirdPartyLicenses/RTXPT.txt", "ShaderDynamic/Bin/dxil/bundle.json", "native-lighting-sdk.json",
                "tools/Build-NativeLightingExtension.ps1", "tools/Invoke-BoundedBuild.ps1",
                "sdk/backend/RtrNativeLighting.hlsl", "examples/native-lighting-extension/ReceiverTint.hlsli").forEach {
                check(zip.getEntry(it) != null) { "RTXPT archive omitted required runtime entry: $it" }
            }
        }
    }
}
val runtimeDigest = tasks.register("hashRtxptRuntime") {
    dependsOn(runtimeArchive)
    val archive = runtimeArchive.flatMap { it.archiveFile }
    val output = layout.buildDirectory.file("native-bundle/rtxpt-runtime.sha256")
    inputs.file(archive); outputs.file(output)
    doLast {
        val hash = MessageDigest.getInstance("SHA-256")
        archive.get().asFile.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n=input.read(buffer); if (n<0) break; hash.update(buffer,0,n) }
        }
        output.get().asFile.writeText(HexFormat.of().formatHex(hash.digest()) + "\n", Charsets.US_ASCII)
    }
}
tasks.named<Jar>("jar") {
    dependsOn(runtimeDigest)
    // The runtime is already compressed. Storing the outer JAR avoids a
    // second expensive compression pass over its large ZIP payload.
    entryCompression = org.gradle.api.tasks.bundling.ZipEntryCompression.STORED
    from("NOTICE-RTXPT.txt") { into("META-INF") }
    from(runtimeArchive.flatMap { it.archiveFile }) { into("META-INF/native/windows-x86_64") }
    from(layout.buildDirectory.file("native-bundle/rtxpt-runtime.sha256")) { into("META-INF/native/windows-x86_64") }
}

tasks.register<JavaExec>("rtxptHostSurfaceNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses, syncRtxptRuntimeShaders)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptHostSurfaceNativeSelfTest")
    environment("RTR_RTXPT_SHADER_MANIFEST_DIR", rootProject.file("build/rtxpt-shader-manifests").absolutePath)
    args(providers.gradleProperty("hostSurfaceRoute").getOrElse("raw"))
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
}
tasks.register<JavaExec>("rtxptNativeLightingApplicationPatchNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingApplicationPatchNativeSelfTest")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
}
tasks.register<JavaExec>("rtxptNativeLightingCompactNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingCompactNativeSelfTest")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
}

tasks.register<JavaExec>("rtxptHostSurfaceInputContractSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptHostSurfaceInputContractSelfTest")
}

tasks.register<JavaExec>("rtxptHostAugmentationNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses, syncRtxptRuntimeShaders)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptHostAugmentationNativeSelfTest")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
}

tasks.register<JavaExec>("rtxptHostSurfaceTransportNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses, syncRtxptRuntimeShaders)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptHostSurfaceTransportNativeSelfTest")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
    environment("RTR_RTXPT_SHADER_MANIFEST_DIR", rootProject.file("build/rtxpt-shader-manifests").absolutePath)
}

tasks.register<JavaExec>("rtxptNativeLightingMatrixNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
}

tasks.register<JavaExec>("rtxptNativeLightingStressNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingStressNativeSelfTest")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
}

tasks.register<JavaExec>("rtxptHostSurfaceRrNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses, syncRtxptRuntimeShaders)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptHostSurfaceNativeSelfTest")
    args("rr")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
}

listOf("RtxptHostSurfaceFrameContractSelfTest", "RtxptNativeLightingCompactContractSelfTest", "RtxptDiagnosticsSelfTest", "RtxptModeUsabilitySelfTest").forEach { name ->
    val contract = tasks.register<JavaExec>(name.replaceFirstChar { it.lowercase() }) {
        group = "verification"; dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("top.ceroxe.rt.renderer.rtxpt.$name")
    }
    tasks.named("check") { dependsOn(contract) }
}
tasks.named("check") { dependsOn("nativeLightingStyleSelfTest", "rtxptHostSurfaceInputContractSelfTest", "rtxptLightMapSelfTest") }
tasks.register<JavaExec>("rtxptNativeLightingReconstructionNativeSelfTest") {
    group = "verification"; dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingReconstructionNativeSelfTest")
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
}

listOf("RtxptHighFramerateResourcesSelfTest", "RtxptHighFramerateResourcesNativeSelfTest", "RtxptHighFramerateCompositionNativeSelfTest").forEach { name ->
    tasks.register<JavaExec>(name.replaceFirstChar { it.lowercase() }) {
        group = "verification"; dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("top.ceroxe.rt.renderer.rtxpt.$name")
        if(name=="RtxptHighFramerateCompositionNativeSelfTest" && project.hasProperty("highFramerateCompositionCases"))
            args(project.property("highFramerateCompositionCases").toString().split(","))
        doFirst { rootProject.file("build/high-framerate-classpath.txt").writeText(classpath.asPath,Charsets.UTF_8) }
        environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
    }
}

tasks.register<JavaExec>("rtxptHighFramerateBenchmark") {
    group = "verification"; dependsOn(tasks.testClasses)
    val baseline = providers.systemProperty("highFramerate.baseline").orNull
    classpath = if (baseline == null) sourceSets.test.get().runtimeClasspath else
        files(listOf("renderer-api", "renderer-core", "renderer-nvidia", "renderer-rtxpt").map {
            "${System.getProperty("user.home")}/.m2/repository/top/ceroxe/rt/$it/$baseline/$it-$baseline.jar"
        }) + sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.RtxptHighFramerateBenchmark")
    jvmArgs("-Xmx2g")
    systemProperties(System.getProperties().stringPropertyNames().filter { it.startsWith("highFramerate.") }.associateWith(System::getProperty))
    environment("RTR_RTXPT_BACKGROUND_WINDOW", "1")
}

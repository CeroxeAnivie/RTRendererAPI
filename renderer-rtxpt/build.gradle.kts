import java.security.MessageDigest
import java.util.HexFormat
import java.nio.file.Files
import java.util.zip.ZipFile
import org.gradle.api.tasks.Sync

plugins { `java-library` }
description = "Thin in-process JNI host for NVIDIA RTXPT managed rendering."
dependencies { compileOnly(project(":renderer-api")); testImplementation(project(":renderer-api")) }
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
    commandLine("pwsh", "-NoProfile", "-File", "tools/Build-RtxptShaderBudgets.ps1", "-VerifyOnly")
}
val runtimeArchive = tasks.register<Zip>("packageRtxptRuntime") {
    group = "distribution"
    description = "Packages the pinned RTXPT native renderer, runtime shaders and upstream licenses."
    dependsOn(verifyRtxptShaderBudgets)
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
                "ThirdPartyLicenses/RTXPT.txt", "ShaderDynamic/Bin/dxil/bundle.json").forEach {
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
    from("NOTICE-RTXPT.txt") { into("META-INF") }
    from(runtimeArchive.flatMap { it.archiveFile }) { into("META-INF/native/windows-x86_64") }
    from(layout.buildDirectory.file("native-bundle/rtxpt-runtime.sha256")) { into("META-INF/native/windows-x86_64") }
}

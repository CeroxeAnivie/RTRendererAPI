import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile
import org.gradle.api.tasks.bundling.Zip

plugins { `java-library` }
description = "External consumer bridge to unmodified NVIDIA RTXPT."
dependencies {
    api(project(":renderer-api"))
    val lwjgl = rootProject.providers.gradleProperty("lwjgl_version").getOrElse("3.4.3")
    listOf("lwjgl", "lwjgl-vulkan", "lwjgl-shaderc", "lwjgl-glfw", "lwjgl-opengl").forEach {
        testImplementation("org.lwjgl:$it:$lwjgl")
    }
    listOf("lwjgl", "lwjgl-shaderc", "lwjgl-glfw", "lwjgl-opengl").forEach {
        testRuntimeOnly("org.lwjgl:$it:$lwjgl:natives-windows")
    }
}
java { toolchain { languageVersion = JavaLanguageVersion.of(25) }; withSourcesJar(); withJavadocJar() }
sourceSets.named("test") {
    java.srcDir(rootProject.file("examples/native-lighting-opengl"))
    java.srcDir(rootProject.file("examples/native-scenes/src/main/java"))
}
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.withType<Test>().configureEach { failOnNoDiscoveredTests = false }
tasks.withType<JavaExec>().configureEach {
    workingDir(rootProject.projectDir)
    jvmArgs("-Xmx512m", "-XX:-UseJVMCICompiler", "--enable-native-access=ALL-UNNAMED")
    systemProperties(System.getProperties().stringPropertyNames().filter {
        it.startsWith("top.ceroxe.rt.rtxpt.")
    }.associateWith(System::getProperty))
}
val cpuTests = listOf(
    "RtxptAdapterSelfTest", "RtxptPacketPoolSelfTest", "RtxptLightMapSelfTest",
    "NativeLightingStyleSelfTest", "RtxptRuntimeBundleSelfTest", "RtxptApplicationDataSelfTest",
    "RtxptBoundaryDiagnosticsSelfTest", "RtxptBudgetContractSelfTest", "RtxptDynamicResourcesSelfTest",
    "RtxptHighFramerateResourcesSelfTest", "RtxptGeometryInheritanceSelfTest", "RtxptLocalPreparationSelfTest",
    "RtxptNativeLightingCompactContractSelfTest", "RtxptNativeLightingProjectionSelfTest",
    "RtxptRetainedDependencySelfTest", "RtxptAnalyticTransportSelfTest", "RtxptMaterialLifetimeSelfTest",
    "RtxptMeshDefinitionLifetimeSelfTest")
cpuTests.forEach { name ->
    tasks.register<JavaExec>(name.replaceFirstChar { it.lowercase() }) {
        group = "verification"
        dependsOn(tasks.testClasses)
        classpath = files(sourceSets.test.get().output, sourceSets.main.get().output, configurations.testCompileClasspath)
        mainClass.set("top.ceroxe.rt.renderer.rtxpt.$name")
    }
}
tasks.named("check") { dependsOn(cpuTests.map { it.replaceFirstChar { c -> c.lowercase() } }) }
val nativeSceneDefaults = tasks.register<JavaExec>("nativeSceneDefaultsSelfTest") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = files(sourceSets.test.get().output, sourceSets.main.get().output, configurations.testCompileClasspath)
    mainClass.set("top.ceroxe.rt.renderer.rtxpt.examples.NativeSceneDefaultsSelfTest")
}
tasks.named("check") { dependsOn(nativeSceneDefaults) }
val runtimeRoot = rootProject.layout.buildDirectory.dir("upstream-rtxpt/runtime/rtxpt_v.1.9.0_binaries")
val buildBridge = tasks.register<Exec>("buildRtxptBridge") {
    group = "build"
    workingDir(rootProject.projectDir)
    inputs.files(rootProject.fileTree("renderer-rtxpt/src/main/cpp") { include("**/*.cpp", "**/*.h", "**/*.manifest", "CMakeLists.txt") }, rootProject.file("tools/Build-RtxptBridge.ps1"), rootProject.file("tools/Build-RtxptUpstream.ps1"), rootProject.file("tools/RtxptBridgeProject.cmake"), rootProject.file("tools/RtxptProgressPatch.cmake"), rootProject.file("tools/patches/progress-noninteractive.patch"), rootProject.file("tools/RtxptOfflinePatch.cmake"), rootProject.file("tools/patches/streamline-offline-preferences.patch"), rootProject.file("tools/Assert-RtxptAuthorizedPatches.ps1"))
    outputs.files(runtimeRoot.map { it.file("bin/rtrenderer_rtxpt.dll") }, runtimeRoot.map { it.file("bin/rtrenderer_jvm_host.exe") })
    inputs.file(rootProject.file("tools/patches/streamline-offline-preferences.json"))
    inputs.files(rootProject.file("tools/patches/streamline-offline-device.patch"), rootProject.file("tools/patches/streamline-offline-device.json"))
    val arguments = mutableListOf("pwsh", "-NoProfile", "-File", "tools/Build-RtxptBridge.ps1")
    providers.gradleProperty("cmakeExecutable").orNull?.let { arguments += listOf("-CMakeExecutable", it) }
    commandLine(arguments)
}
val buildAdapterShaders = tasks.register<Exec>("buildAdapterShaders") {
    group = "build"
    dependsOn(buildBridge)
    workingDir(rootProject.projectDir)
    inputs.files(rootProject.fileTree("renderer-rtxpt/src/main/cpp") {
        include("**/*.hlsl", "**/*.hlsli", "AdapterShaders.cfg")
    }, rootProject.file("tools/Build-AdapterShaders.ps1"))
    inputs.file(rootProject.layout.buildDirectory.file("upstream-rtxpt/source-manifest.json"))
    outputs.dir(runtimeRoot.map { it.dir("bin/AdapterShaders") })
    commandLine("pwsh", "-NoProfile", "-File", "tools/Build-AdapterShaders.ps1")
}
val buildPrecompiledRuntime = tasks.register<Exec>("buildPrecompiledRuntime") {
    group = "build"
    dependsOn(buildAdapterShaders)
    workingDir(rootProject.projectDir)
    inputs.file(rootProject.file("tools/Build-PrecompiledRuntime.py"))
    inputs.file(rootProject.file("tools/rtxpt-precompiled-commands.json"))
    inputs.file(rootProject.layout.buildDirectory.file("upstream-rtxpt/source-manifest.json"))
    outputs.dir(runtimeRoot.map { it.dir("bin/PrecompiledShaders") })
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python"),
        "tools/Build-PrecompiledRuntime.py", "--runtime", runtimeRoot.get().dir("bin").asFile.absolutePath,
        "--sdk", rootProject.file("build/upstream-rtxpt/source").absolutePath,
        "--compiler", rootProject.file("build/upstream-rtxpt/source/External/dxc/build/native/bin/x64/dxc.exe").absolutePath,
        "--output", rootProject.file("build/reports/precompiled-runtime").absolutePath)
}
// These are runtime support assets, not the upstream demonstration scenes.
val proceduralSkyAssets = listOf(
    "Assets/StandaloneTextures/RGBANoiseMedium.png",
    "Assets/StandaloneTextures/q2rtx_env/clouds.dds",
    "Assets/StandaloneTextures/q2rtx_env/inscatter_earth.dds",
    "Assets/StandaloneTextures/q2rtx_env/irradiance_earth.dds",
    "Assets/StandaloneTextures/q2rtx_env/transmittance_earth.dds"
)
val coreRuntimeAssets = listOf(
    "Assets/Fonts/**",
    "Assets/loading_splash.png", "Assets/ArtLicenses.txt"
) + proceduralSkyAssets
val coreRuntimeFiles = listOf(
    "bin/rtrenderer_jvm_host.exe", "bin/rtrenderer_rtxpt.dll",
    "bin/NRD.dll", "bin/omm-lib.dll",
    "bin/nvngx_dlss.dll", "bin/nvngx_dlssd.dll", "bin/nvngx_dlssg.dll",
    "bin/sl.common.dll", "bin/sl.interposer.dll", "bin/sl.pcl.dll",
    "bin/sl.reflex.dll", "bin/sl.dlss.dll", "bin/sl.dlss_d.dll", "bin/sl.dlss_g.dll",
    "bin/D3D12/**", "bin/ShaderPrecompiled/**", "bin/AdapterShaders/**",
    "bin/PrecompiledShaders/**", "bin/ShaderDynamic/Source/**",
    // The unmodified ShaderCompilerConfig::Initialize checks this real path.
    // Compilation remains prohibited; compiler DLLs are build-time inputs only.
    "bin/ShaderDynamic/Tools/d3d12/x64/dxc.exe"
)
val coreRuntimeLayout = layout.buildDirectory.dir("core-runtime-layout")
val prepareCoreRuntimeLayout = tasks.register("prepareCoreRuntimeLayout") {
    outputs.dir(coreRuntimeLayout)
    doLast {
        // The unchanged sample enumerates this directory during initialization.
        // Preserve the empty directory, without any demonstration HDR textures.
        coreRuntimeLayout.get().dir("Assets/EnvironmentMaps").asFile.mkdirs()
    }
}
val runtimeArchive = tasks.register<Zip>("packageRtxptRuntime") {
    group = "build"
    // GPU binaries and textures are already encoded. Avoid serial deflation
    // of the complete runtime on every bridge release; contents stay identical.
    entryCompression = org.gradle.api.tasks.bundling.ZipEntryCompression.STORED
    dependsOn(buildPrecompiledRuntime, prepareCoreRuntimeLayout)
    archiveFileName.set("rtxpt-runtime.zip")
    destinationDirectory.set(layout.buildDirectory.dir("native-package"))
    from(runtimeRoot) {
        (coreRuntimeFiles + coreRuntimeAssets).forEach { include(it) }
        exclude("**/*.pdb", "bin/Rtxpt.exe", "bin/IntroSample.exe", "bin/ShaderDynamic/Bin/**")
    }
    from(coreRuntimeLayout) { includeEmptyDirs = true }
    doFirst {
        val bin = runtimeRoot.get().dir("bin").asFile
        (coreRuntimeFiles + coreRuntimeAssets).filterNot { it.contains('*') }.forEach {
            check(runtimeRoot.get().file(it).asFile.isFile) { "Missing core runtime input: $it" }
        }
        listOf("rtrenderer_rtxpt.dll", "rtrenderer_jvm_host.exe", "NRD.dll", "omm-lib.dll", "sl.interposer.dll", "D3D12/D3D12Core.dll", "ShaderDynamic/Tools/d3d12/x64/dxc.exe", "ShaderDynamic/Source/Rtxpt/PathTracer/PathTracer.hlsli").forEach {
            check(bin.resolve(it).isFile) { "Missing upstream runtime input: $it" }
        }
        check(bin.resolve("ShaderPrecompiled/Rtxpt").isDirectory)
        check(bin.resolve("PrecompiledShaders/index.tsv").isFile)
        check(bin.resolve("PrecompiledShaders/globals.txt").isFile)
        check(bin.resolve("PrecompiledShaders/sources.tsv").isFile)
        rootProject.file("renderer-rtxpt/src/main/cpp/AdapterShaders.cfg").readLines().forEach { line ->
            Regex("^\\s*(Rtr\\w+)\\.hlsl\\s").find(line)?.let { shader ->
                check(bin.resolve("AdapterShaders/app/ProcessingPasses/" + shader.groupValues[1] + ".bin").let { it.isFile && it.length() > 0 }) {
                    "Missing compiled adapter shader: ${shader.groupValues[1]}"
                }
            }
        }
        check(runtimeRoot.get().dir("Assets").asFile.isDirectory)
    }
    doLast {
        ZipFile(archiveFile.get().asFile).use { zip ->
            check(zip.getEntry("Assets/EnvironmentMaps/")?.isDirectory == true) {
                "The original runtime requires an empty EnvironmentMaps directory"
            }
            val entries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
            check(entries.none { it.name.startsWith("Assets/Models/")
                || it.name.startsWith("Assets/Materials/")
                || it.name.startsWith("Assets/EnvironmentMaps/")
                || (it.name.startsWith("Assets/StandaloneTextures/") && it.name !in proceduralSkyAssets)
                || it.name.startsWith("Assets/SampleGame/")
                || it.name.endsWith(".scene.json")
                || it.name.endsWith(".pdb")
                || it.name.endsWith("/dxcompiler.dll")
                || it.name.endsWith("/dxil.dll") }) {
                "Demonstration assets must not enter the core renderer runtime"
            }
            check(entries.sumOf { it.size } <= 512L * 1024 * 1024) {
                "Core runtime exceeds 512 MiB; review the package manifest instead of bundling sample assets"
            }
        }
    }
}
// Explicit opt-in only: neither jar nor publication depends on this archive.
tasks.register<Zip>("packageRtxptExampleAssets") {
    group = "distribution"
    description = "Package optional upstream demonstration scenes and their assets"
    entryCompression = org.gradle.api.tasks.bundling.ZipEntryCompression.STORED
    archiveFileName.set("rtxpt-example-assets.zip")
    destinationDirectory.set(layout.buildDirectory.dir("example-package"))
    from(runtimeRoot) {
        include("Assets/**")
        coreRuntimeAssets.forEach { exclude(it) }
    }
    from(runtimeRoot) { include("Assets/ArtLicenses.txt") }
}
val runtimeDigest = tasks.register("hashRtxptRuntime") {
    dependsOn(runtimeArchive)
    val archive = runtimeArchive.flatMap { it.archiveFile }
    val output = layout.buildDirectory.file("native-package/rtxpt-runtime.sha256")
    inputs.file(archive); outputs.file(output)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
        archive.get().asFile.inputStream().use { stream ->
            val bytes = ByteArray(65536)
            while (true) { val count = stream.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
        }
        output.get().asFile.writeText(HexFormat.of().formatHex(digest.digest()) + "\n", Charsets.US_ASCII)
    }
}
tasks.named<Jar>("jar") {
    // Store the nested runtime without a multi-gigabyte deflate pass.
    entryCompression = org.gradle.api.tasks.bundling.ZipEntryCompression.STORED
    dependsOn(runtimeDigest)
    from(layout.buildDirectory.dir("native-package")) {
        include("rtxpt-runtime.zip", "rtxpt-runtime.sha256")
        into("META-INF/native/windows-x86_64")
    }
}
tasks.register("rtxptReleaseValidationClasspath") {
    dependsOn(tasks.testClasses)
    doLast {
        rootProject.file("build/renderer-validation-classpath.txt").writeText(
            files(sourceSets.test.get().output, sourceSets.main.get().output, configurations.testCompileClasspath).asPath,
            Charsets.UTF_8)
    }
}

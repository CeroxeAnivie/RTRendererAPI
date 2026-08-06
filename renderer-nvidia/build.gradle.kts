import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    `java-library`
}

abstract class NvidiaStreamlineNativeGateLock : BuildService<BuildServiceParameters.None>

group = rootProject.group
version = rootProject.version

val toolchainJavaVersion = rootProject.providers.gradleProperty("java_toolchain_version")
    .orElse(JavaVersion.current().majorVersion)
    .get()
    .toInt()
val unsafeAccessJvmArg = if (toolchainJavaVersion >= 24) {
    "--sun-misc-unsafe-memory-access=allow"
} else {
    null
}

fun discoveredPath(
    propertyName: String,
    environmentName: String,
    candidates: List<String>,
    accepts: (File) -> Boolean
) = providers.gradleProperty(propertyName)
    .orElse(providers.environmentVariable(environmentName))
    .orElse(providers.provider {
        candidates.asSequence().map(::file).firstOrNull(accepts)?.absolutePath
    })

dependencies {
    // The API publication owns this provider as a runtime coordinate. Compile against the API
    // contract locally, but do not publish a reverse edge that would make renderer-api and this
    // provider cyclic in Maven/Gradle metadata.
    compileOnly(project(":renderer-api"))
    compileOnly(project(":renderer-core"))
    testImplementation(project(":renderer-api"))
    testImplementation(project(":renderer-core"))
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(toolchainJavaVersion)
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
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
    // renderer-api publishes this module as a runtime coordinate. Gradle therefore places the
    // provider JAR on this module's test runtime classpath; declare the producer explicitly so
    // Gradle's task validation cannot observe an implicit jar read.
    dependsOn(tasks.named("jar"))
}

tasks.withType<JavaExec>().configureEach {
    // The test runtime graph reaches this module through renderer-api metadata. Make the
    // self-artifact producer explicit so Gradle never observes an undeclared JAR dependency.
    dependsOn(tasks.named("jar"))
    unsafeAccessJvmArg?.let { jvmArgs(it) }
}

val nativeBridgeOutput = layout.buildDirectory.file("native/rtrenderer_nvidia.dll")
val nativeBridgeBuildDirectory = layout.buildDirectory.dir("cmake/nvidia")
val cmakeExecutableProperty = discoveredPath(
    "cmakeExecutable", "CMAKE_EXECUTABLE", listOf("D:/CMake/bin/cmake.exe")
) { it.isFile && it.name.equals("cmake.exe", ignoreCase = true) }
val nrdSdkRootProperty = discoveredPath(
    "nrdSdkRoot", "NRD_ROOT", listOf("D:/NRD")
) { File(it, "CMakeLists.txt").isFile }
val nriSdkRootProperty = discoveredPath(
    "nriSdkRoot", "NRI_ROOT", listOf("D:/NRI")
) { File(it, "CMakeLists.txt").isFile }
val rtxmuSdkRoot = discoveredPath(
    "rtxmuSdkRoot", "RTXMU_ROOT", listOf("D:/RTXMU")
) { File(it, "include/rtxmu/VkAccelStructManager.h").isFile }
val rtxmuCommit = "0c9ce1177000d5923e2cc6a35ae9cb7ff03748d2"
val streamlineSdkRootProperty = discoveredPath(
    "streamlineSdkRoot", "STREAMLINE_ROOT", listOf("D:/StreamlineSdk")
) { File(it, "bin/x64/sl.interposer.dll").isFile }
val jdkHomeProperty = discoveredPath(
    "jdkHome", "JAVA_HOME", listOf(
        System.getProperty("java.home")
    )
) { File(it, "include/jni.h").isFile }

val configureNvidiaBridge = tasks.register<Exec>("configureNvidiaBridge") {
    group = "build"
    description = "Configures the external NRD/NRI/RTXMU JNI bridge build with CMake."

    val cmake = cmakeExecutableProperty
    val nrdSdkRoot = nrdSdkRootProperty
    val nriSdkRoot = nriSdkRootProperty
    val jdkHome = jdkHomeProperty
    inputs.files(fileTree("src/main/cpp") { include("**/*") })
    inputs.property("nrdSdkRoot", nrdSdkRoot)
    inputs.property("nriSdkRoot", nriSdkRoot)
    inputs.property("streamlineSdkRoot", streamlineSdkRootProperty)
    inputs.property("rtxmuSdkRoot", rtxmuSdkRoot)
    inputs.property("rtxmuCommit", rtxmuCommit)
    inputs.property("jdkHome", jdkHome)
    inputs.property("rendererVersion", project.version.toString())
    outputs.dir(nativeBridgeBuildDirectory)
    // The external SDK trees are commit-pinned inputs.  CMake owns their dependency graph and is
    // deliberately re-run so generator/cache state cannot be hidden by Gradle snapshots.
    outputs.upToDateWhen { false }
    onlyIf {
        if (!cmake.isPresent || !nrdSdkRoot.isPresent || !nriSdkRoot.isPresent
                || !streamlineSdkRootProperty.isPresent || !rtxmuSdkRoot.isPresent || !jdkHome.isPresent) {
            logger.lifecycle(
                    "configureNvidiaBridge skipped: pass -PcmakeExecutable=<absolute cmake.exe> " +
                    "-PnrdSdkRoot=<absolute NRD directory> -PnriSdkRoot=<absolute NRI directory> " +
                    "-PstreamlineSdkRoot=<official Streamline SDK directory> " +
                    "-PrtxmuSdkRoot=<absolute RTXMU v1.4 directory> " +
                    "-PjdkHome=<absolute JDK directory>"
            )
            false
        } else {
            true
        }
    }
    doFirst {
        val executableFile = file(cmake.get())
        val nrdRoot = file(nrdSdkRoot.get())
        val nriRoot = file(nriSdkRoot.get())
        val streamlineRoot = file(streamlineSdkRootProperty.get())
        val rtxmuRoot = file(rtxmuSdkRoot.get())
        val jdk = file(jdkHome.get())
        if (!executableFile.isFile || executableFile.name.lowercase() != "cmake.exe") {
            throw GradleException("cmakeExecutable must identify an existing cmake.exe: $executableFile")
        }
        if (!nrdRoot.isDirectory || !File(nrdRoot, "CMakeLists.txt").isFile) {
            throw GradleException("nrdSdkRoot must identify an NRD SDK source root: $nrdRoot")
        }
        if (!nriRoot.isDirectory || !File(nriRoot, "CMakeLists.txt").isFile) {
            throw GradleException("nriSdkRoot must identify an NRI SDK source root: $nriRoot")
        }
        if (!File(streamlineRoot, "include/sl_core_api.h").isFile
                || !File(streamlineRoot, "bin/x64/sl.interposer.dll").isFile) {
            throw GradleException("streamlineSdkRoot must identify a Streamline SDK root: $streamlineRoot")
        }
        if (!File(rtxmuRoot, "include/rtxmu/VkAccelStructManager.h").isFile
                || !File(rtxmuRoot, "src/VkAccelStructManager.cpp").isFile) {
            throw GradleException("rtxmuSdkRoot must identify the NVIDIA RTXMU source root: $rtxmuRoot")
        }
        val rtxmuRevisionProcess = ProcessBuilder(
            "git", "-C", rtxmuRoot.absolutePath, "rev-parse", "HEAD"
        ).redirectErrorStream(true).start()
        val rtxmuRevisionOutput = rtxmuRevisionProcess.inputStream
            .bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
        if (rtxmuRevisionProcess.waitFor() != 0 || rtxmuRevisionOutput != rtxmuCommit) {
            throw GradleException(
                "rtxmuSdkRoot must be a Git checkout pinned to RTXMU v1.4 commit " +
                    "$rtxmuCommit: $rtxmuRoot (resolved '$rtxmuRevisionOutput')"
            )
        }
        if (!File(jdk, "include/jni.h").isFile || !File(jdk, "include/win32/jni_md.h").isFile) {
            throw GradleException("jdkHome must identify a JDK with Windows JNI headers: $jdk")
        }
        val buildDirectory = nativeBridgeBuildDirectory.get().asFile
        executable(executableFile.absolutePath)
        args(
            "-S", project.file("src/main/cpp").absolutePath,
            "-B", buildDirectory.absolutePath,
            "-G", "Visual Studio 17 2022",
            "-A", "x64",
            "-DNRD_ROOT=${nrdRoot.absolutePath}",
            "-DNRI_ROOT=${nriRoot.absolutePath}",
            "-DSTREAMLINE_ROOT=${streamlineRoot.absolutePath}",
            "-DRTXMU_ROOT=${rtxmuRoot.absolutePath}",
            "-DRTXMU_COMMIT=$rtxmuCommit",
            "-DRTRENDERER_VERSION=${project.version}",
            "-DJNI_INCLUDE_DIR=${File(jdk, "include").absolutePath}",
            "-DJNI_PLATFORM_INCLUDE_DIR=${File(jdk, "include/win32").absolutePath}"
        )
    }
}

tasks.register<Exec>("compileNvidiaBridge") {
    group = "build"
    description = "Builds the ABI-safe NVIDIA JNI bridge with external NRD/NRI/RTXMU SDK roots via CMake."
    dependsOn(configureNvidiaBridge)

    val cmake = cmakeExecutableProperty
    val nrdSdkRoot = nrdSdkRootProperty
    val nriSdkRoot = nriSdkRootProperty
    val jdkHome = jdkHomeProperty
    inputs.files(fileTree("src/main/cpp") { include("**/*") })
    inputs.property("nrdSdkRoot", nrdSdkRoot)
    inputs.property("nriSdkRoot", nriSdkRoot)
    inputs.property("streamlineSdkRoot", streamlineSdkRootProperty)
    inputs.property("rtxmuSdkRoot", rtxmuSdkRoot)
    inputs.property("jdkHome", jdkHome)
    outputs.file(nativeBridgeOutput)
    // CMake/MSBuild provide the actual incremental build decision after configuration.
    outputs.upToDateWhen { false }
    onlyIf {
        cmake.isPresent && nrdSdkRoot.isPresent && nriSdkRoot.isPresent && streamlineSdkRootProperty.isPresent
                && rtxmuSdkRoot.isPresent && jdkHome.isPresent
    }
    doFirst {
        val executableFile = file(cmake.get())
        val buildDirectory = nativeBridgeBuildDirectory.get().asFile
        val buildPath = buildDirectory.toPath().toAbsolutePath().normalize()
        val generatedShaderPath = buildPath.resolve("nrd-shaders").normalize()
        val output = nativeBridgeOutput.get().asFile
        if (!executableFile.isFile || executableFile.name.lowercase() != "cmake.exe") {
            throw GradleException("cmakeExecutable must identify an existing cmake.exe: $executableFile")
        }
        if (!generatedShaderPath.startsWith(buildPath)) {
            throw GradleException("NRD shader output escaped the native build directory: $generatedShaderPath")
        }
        // ShaderMake uses its output directory for both transient compiler products and final
        // embedded headers. Reusing that mutable state across forced native builds can leave a
        // later header-packaging pass observing an already-retired .spirv file. Give each build
        // exclusive ownership of a fresh generated directory; SDK sources remain read-only.
        project.delete(generatedShaderPath.toFile())
        if (!generatedShaderPath.toFile().mkdirs() && !generatedShaderPath.toFile().isDirectory) {
            throw GradleException("could not create NRD shader output directory: $generatedShaderPath")
        }
        output.parentFile.mkdirs()
        executable(executableFile.absolutePath)
        // NRD's ShaderMake custom rule invokes Visual Studio batch labels. Parallel MSBuild can
        // interleave those command files and produce the transient "VCEnd" failure; one worker
        // keeps shader generation deterministic and preserves the original diagnostics.
        args("--build", buildDirectory.absolutePath, "--config", "Release", "--target", "rtrenderer_nvidia", "--parallel", "1")
    }
    doLast {
        val buildDirectory = nativeBridgeBuildDirectory.get().asFile
        val produced = File(buildDirectory, "output/rtrenderer_nvidia.dll")
        if (!produced.isFile) {
            throw GradleException("CMake completed without producing the NVIDIA JNI bridge: $produced")
        }
        produced.copyTo(nativeBridgeOutput.get().asFile, overwrite = true)
    }
}

val packagedNvidiaRuntime = layout.buildDirectory.dir("generated/nvidia-runtime-resources")
val nativePackagingConfigured = cmakeExecutableProperty.isPresent
        && nrdSdkRootProperty.isPresent
        && nriSdkRootProperty.isPresent
        && streamlineSdkRootProperty.isPresent
        && rtxmuSdkRoot.isPresent
        && jdkHomeProperty.isPresent

val clearUnconfiguredPackagedNvidiaRuntime = tasks.register<Delete>(
    "clearUnconfiguredPackagedNvidiaRuntime"
) {
    delete(packagedNvidiaRuntime)
    onlyIf { !nativePackagingConfigured }
    doLast {
        logger.lifecycle(
            "NVIDIA native SDKs unavailable; removed native staging for the Java-only test artifact"
        )
    }
}

val preparePackagedNvidiaRuntime = tasks.register<Sync>("preparePackagedNvidiaRuntime") {
    group = "build"
    description = "Stages the JNI bridge and redistributable Streamline sidecars when native SDKs are available."
    dependsOn(tasks.named("compileNvidiaBridge"), clearUnconfiguredPackagedNvidiaRuntime)
    val destination = packagedNvidiaRuntime.map {
        it.dir("META-INF/native/windows-x86_64")
    }
    into(destination)

    if (nativePackagingConfigured) {
        from(nativeBridgeOutput)
        from(streamlineSdkRootProperty.map { file(it).resolve("bin/x64") }) {
            include("*.dll", "*.txt", "*.md")
        }
        doLast {
            val directory = destination.get().asFile
            val runtimeFiles = directory.listFiles()
                ?.filter { it.isFile && it.name != "runtime-files.sha256" }
                ?.sortedBy { it.name }
                ?: emptyList()
            if (runtimeFiles.none { it.name == "rtrenderer_nvidia.dll" }
                    || runtimeFiles.none { it.name == "sl.interposer.dll" }) {
                throw GradleException("packaged NVIDIA runtime is missing the JNI bridge or Streamline interposer")
            }
            val manifest = runtimeFiles.joinToString(separator = "\n", postfix = "\n") { runtime ->
                val digest = MessageDigest.getInstance("SHA-256").digest(runtime.readBytes())
                HexFormat.of().formatHex(digest) + "\t" + runtime.name
            }
            File(directory, "runtime-files.sha256").writeText(manifest, Charsets.UTF_8)
        }
    }
}

tasks.named<Jar>("jar") {
    dependsOn(preparePackagedNvidiaRuntime)
    from(packagedNvidiaRuntime)
}

tasks.register("verifyPackagedNvidiaRuntime") {
    group = "verification"
    description = "Rejects a release JAR that omits classpath-loadable NVIDIA native runtime files."
    dependsOn(tasks.named("jar"))
    doFirst {
        if (!nativePackagingConfigured) {
            throw GradleException(
                "NVIDIA runtime verification requires CMake, JDK, NRD, NRI, Streamline, and RTXMU"
            )
        }
    }
    doLast {
        val artifact = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        NvidiaRuntimeClosure.verify(artifact, "renderer-nvidia runtime JAR")
    }
}

tasks.matching { it.name.startsWith("publishMavenJavaPublication") }.configureEach {
    dependsOn(tasks.named("verifyPackagedNvidiaRuntime"))
    doFirst {
        if (!nativePackagingConfigured) {
            throw GradleException(
                "renderer-nvidia publication requires the fixed native SDK/toolchain properties"
            )
        }
    }
}

tasks.register<JavaExec>("nvidiaNativeBridgeSelfTest") {
    group = "verification"
    description = "Loads the compiled NVIDIA JNI bridge and verifies its advertised capability mask."
    dependsOn(tasks.named("compileNvidiaBridge"), tasks.named("testClasses"))

    val cmake = cmakeExecutableProperty
    val nrdSdkRoot = nrdSdkRootProperty
    val nriSdkRoot = nriSdkRootProperty
    val streamlineSdkRoot = streamlineSdkRootProperty
    val jdkHome = jdkHomeProperty
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.nvidia.NvidiaNativeBridgeSelfTest")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    jvmArgs("-Djava.library.path=${nativeBridgeOutput.get().asFile.parentFile.absolutePath}")
    doFirst {
        environment("RTRENDERER_STREAMLINE_PLUGIN_DIR", File(streamlineSdkRoot.get(), "bin/x64").absolutePath)
    }
    onlyIf {
        cmake.isPresent && nrdSdkRoot.isPresent && nriSdkRoot.isPresent && streamlineSdkRoot.isPresent && jdkHome.isPresent
    }
}

val nvidiaStreamlinePreflightSelfTest = tasks.register<JavaExec>("nvidiaStreamlinePreflightSelfTest") {
    group = "verification"
    description = "Rejects malformed, partial, and failed Streamline preflight capability reports."
    dependsOn(tasks.named("testClasses"), tasks.named("jar"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.nvidia.NvidiaStreamlinePreflightSelfTest")
}

tasks.register<JavaExec>("nvidiaStreamlinePlanLeaseNativeSelfTest") {
    group = "verification"
    description = "Abandons repeated Streamline plans and verifies balanced process preflight leases."
    dependsOn(tasks.named("compileNvidiaBridge"), tasks.named("testClasses"))
    val cmake = cmakeExecutableProperty
    val nrdSdkRoot = nrdSdkRootProperty
    val nriSdkRoot = nriSdkRootProperty
    val streamlineSdkRoot = streamlineSdkRootProperty
    val jdkHome = jdkHomeProperty
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.nvidia.NvidiaStreamlinePlanLeaseNativeSelfTest")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    jvmArgs("-Djava.library.path=${nativeBridgeOutput.get().asFile.parentFile.absolutePath}")
    doFirst {
        environment("RTRENDERER_STREAMLINE_PLUGIN_DIR", File(streamlineSdkRoot.get(), "bin/x64").absolutePath)
    }
    onlyIf {
        cmake.isPresent && nrdSdkRoot.isPresent && nriSdkRoot.isPresent
                && streamlineSdkRoot.isPresent && jdkHome.isPresent
    }
}

val nvidiaStreamlineAdaptiveFrameGenerationSelfTest =
    tasks.register<JavaExec>("nvidiaStreamlineAdaptiveFrameGenerationSelfTest") {
        group = "verification"
        description = "Verifies explicit and capability-adaptive Streamline FG/MFG cadence requests."
        dependsOn(tasks.named("testClasses"), tasks.named("jar"))
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("top.ceroxe.rt.renderer.nvidia.NvidiaStreamlineAdaptiveFrameGenerationSelfTest")
    }

val nvidiaTechnologyCapabilitiesSelfTest =
    tasks.register<JavaExec>("nvidiaTechnologyCapabilitiesSelfTest") {
        group = "verification"
        description = "Verifies capability-driven NVIDIA defaults and per-technology status projection."
        dependsOn(tasks.named("testClasses"), tasks.named("jar"))
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("top.ceroxe.rt.renderer.nvidia.NvidiaTechnologyCapabilitiesSelfTest")
    }

val nvidiaFeatureFailurePolicySelfTest = tasks.register<JavaExec>("nvidiaFeatureFailurePolicySelfTest") {
    group = "verification"
    description = "Verifies REQUIRED and preferred NVIDIA runtime failure decisions."
    dependsOn(tasks.named("testClasses"), tasks.named("jar"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.nvidia.NvidiaFeatureFailurePolicySelfTest")
}

val nvidiaFeatureExecutionEvidenceSelfTest =
    tasks.register<JavaExec>("nvidiaFeatureExecutionEvidenceSelfTest") {
        group = "verification"
        description = "Verifies queue-accepted and successfully presented NVIDIA execution evidence."
        dependsOn(tasks.named("testClasses"), tasks.named("jar"))
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("top.ceroxe.rt.renderer.nvidia.NvidiaFeatureExecutionEvidenceSelfTest")
    }

val nvidiaStreamlinePresentCircuitBreakerSelfTest =
    tasks.register<JavaExec>("nvidiaStreamlinePresentCircuitBreakerSelfTest") {
        group = "verification"
        description = "Injects Streamline present/recreate failures and verifies one-way native fallback."
        dependsOn(tasks.named("testClasses"), tasks.named("jar"))
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("top.ceroxe.rt.renderer.nvidia.NvidiaStreamlinePresentCircuitBreakerSelfTest")
    }

tasks.register<JavaExec>("nvidiaStreamlineFrameConstantsSelfTest") {
    group = "verification"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.nvidia.StreamlineFrameConstantsSelfTest")
}

tasks.register<JavaExec>("nvidiaNrdFrameConstantsSelfTest") {
    group = "verification"
    description = "Verifies NRD camera matrices, jitter, motion scale, and history reset semantics."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.nvidia.NrdFrameConstantsSelfTest")
}

tasks.register<JavaExec>("nvidiaStreamlineDeviceHandoffNativeSelfTest") {
    group = "verification"
    description = "Creates the Streamline-negotiated Vulkan device and verifies the native handoff."
    dependsOn(tasks.named("compileNvidiaBridge"), tasks.named("testClasses"))
    val cmake = cmakeExecutableProperty
    val nrdSdkRoot = nrdSdkRootProperty
    val nriSdkRoot = nriSdkRootProperty
    val streamlineSdkRoot = streamlineSdkRootProperty
    val jdkHome = jdkHomeProperty
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.nvidia.NvidiaStreamlineDeviceHandoffNativeSelfTest")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    jvmArgs("-Djava.library.path=${nativeBridgeOutput.get().asFile.parentFile.absolutePath}")
    doFirst {
        environment("RTRENDERER_STREAMLINE_PLUGIN_DIR", File(streamlineSdkRoot.get(), "bin/x64").absolutePath)
    }
    onlyIf {
        cmake.isPresent && nrdSdkRoot.isPresent && nriSdkRoot.isPresent && streamlineSdkRoot.isPresent && jdkHome.isPresent
    }
}

tasks.register<JavaExec>("nvidiaStreamlineGpuSceneNativeSelfTest") {
    group = "verification"
    description = "Runs a real GPUScene frame through Streamline DLSS and verifies capability activation."
    dependsOn(tasks.named("compileNvidiaBridge"), tasks.named("testClasses"))
    val cmake = cmakeExecutableProperty
    val nrdSdkRoot = nrdSdkRootProperty
    val nriSdkRoot = nriSdkRootProperty
    val streamlineSdkRoot = streamlineSdkRootProperty
    val jdkHome = jdkHomeProperty
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.backend.vulkan.NvidiaStreamlineGpuSceneNativeSelfTest")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    jvmArgs("-Djava.library.path=${nativeBridgeOutput.get().asFile.parentFile.absolutePath}")
    doFirst {
        environment("RTRENDERER_STREAMLINE_PLUGIN_DIR", File(streamlineSdkRoot.get(), "bin/x64").absolutePath)
    }
    onlyIf {
        cmake.isPresent && nrdSdkRoot.isPresent && nriSdkRoot.isPresent && streamlineSdkRoot.isPresent && jdkHome.isPresent
    }
}

tasks.register<JavaExec>("nvidiaStreamlineDlaaGpuSceneNativeSelfTest") {
    group = "verification"
    description = "Runs a real native-resolution GPUScene frame through Streamline DLAA and verifies visual output."
    dependsOn(tasks.named("compileNvidiaBridge"), tasks.named("testClasses"))
    val cmake = cmakeExecutableProperty
    val nrdSdkRoot = nrdSdkRootProperty
    val nriSdkRoot = nriSdkRootProperty
    val streamlineSdkRoot = streamlineSdkRootProperty
    val jdkHome = jdkHomeProperty
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.backend.vulkan.NvidiaStreamlineGpuSceneNativeSelfTest")
    args("dlaa")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    jvmArgs("-Djava.library.path=${nativeBridgeOutput.get().asFile.parentFile.absolutePath}")
    doFirst {
        environment("RTRENDERER_STREAMLINE_PLUGIN_DIR", File(streamlineSdkRoot.get(), "bin/x64").absolutePath)
    }
    onlyIf {
        cmake.isPresent && nrdSdkRoot.isPresent && nriSdkRoot.isPresent && streamlineSdkRoot.isPresent && jdkHome.isPresent
    }
}

tasks.register<JavaExec>("nvidiaStreamlineNisGpuSceneNativeSelfTest") {
    group = "verification"
    description = "Runs a truly scaled GPUScene frame through the production Streamline NIS fallback."
    dependsOn(tasks.named("compileNvidiaBridge"), tasks.named("testClasses"))
    val cmake = cmakeExecutableProperty
    val nrdSdkRoot = nrdSdkRootProperty
    val nriSdkRoot = nriSdkRootProperty
    val streamlineSdkRoot = streamlineSdkRootProperty
    val jdkHome = jdkHomeProperty
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.backend.vulkan.NvidiaStreamlineGpuSceneNativeSelfTest")
    args("nis")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    jvmArgs("-Djava.library.path=${nativeBridgeOutput.get().asFile.parentFile.absolutePath}")
    doFirst {
        environment("RTRENDERER_STREAMLINE_PLUGIN_DIR", File(streamlineSdkRoot.get(), "bin/x64").absolutePath)
    }
    onlyIf {
        cmake.isPresent && nrdSdkRoot.isPresent && nriSdkRoot.isPresent && streamlineSdkRoot.isPresent && jdkHome.isPresent
    }
}

tasks.register<JavaExec>("nvidiaStreamlineFrameGenerationNativeSelfTest") {
    group = "verification"
    description = "Presents real GPUScene frames through Streamline DLSS-G and verifies 2x cadence."
    dependsOn(tasks.named("compileNvidiaBridge"), tasks.named("testClasses"))
    val cmake = cmakeExecutableProperty
    val nrdSdkRoot = nrdSdkRootProperty
    val nriSdkRoot = nriSdkRootProperty
    val streamlineSdkRoot = streamlineSdkRootProperty
    val jdkHome = jdkHomeProperty
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.backend.vulkan.NvidiaStreamlineFrameGenerationNativeSelfTest")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    jvmArgs("-Djava.library.path=${nativeBridgeOutput.get().asFile.parentFile.absolutePath}")
    doFirst {
        environment("RTRENDERER_STREAMLINE_PLUGIN_DIR", File(streamlineSdkRoot.get(), "bin/x64").absolutePath)
    }
    onlyIf {
        cmake.isPresent && nrdSdkRoot.isPresent && nriSdkRoot.isPresent && streamlineSdkRoot.isPresent && jdkHome.isPresent
    }
}

tasks.register<JavaExec>("nvidiaStreamlineMultiFrameGenerationNativeSelfTest") {
    group = "verification"
    description = "Presents real GPUScene frames through Streamline MFG and verifies a true 3x configuration."
    dependsOn(tasks.named("compileNvidiaBridge"), tasks.named("testClasses"))
    val cmake = cmakeExecutableProperty
    val nrdSdkRoot = nrdSdkRootProperty
    val nriSdkRoot = nriSdkRootProperty
    val streamlineSdkRoot = streamlineSdkRootProperty
    val jdkHome = jdkHomeProperty
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.backend.vulkan.NvidiaStreamlineFrameGenerationNativeSelfTest")
    args("mfg")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    jvmArgs("-Djava.library.path=${nativeBridgeOutput.get().asFile.parentFile.absolutePath}")
    doFirst {
        environment("RTRENDERER_STREAMLINE_PLUGIN_DIR", File(streamlineSdkRoot.get(), "bin/x64").absolutePath)
    }
    onlyIf {
        cmake.isPresent && nrdSdkRoot.isPresent && nriSdkRoot.isPresent && streamlineSdkRoot.isPresent && jdkHome.isPresent
    }
}

tasks.register<JavaExec>("nvidiaStreamlineAdaptiveFrameGenerationNativeSelfTest") {
    group = "verification"
    description = "Presents real frames at the highest SDK-supported DLSS-G/MFG cadence up to 4x."
    dependsOn(tasks.named("compileNvidiaBridge"), tasks.named("testClasses"))
    val cmake = cmakeExecutableProperty
    val nrdSdkRoot = nrdSdkRootProperty
    val nriSdkRoot = nriSdkRootProperty
    val streamlineSdkRoot = streamlineSdkRootProperty
    val jdkHome = jdkHomeProperty
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.backend.vulkan.NvidiaStreamlineFrameGenerationNativeSelfTest")
    args("adaptive")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    jvmArgs("-Djava.library.path=${nativeBridgeOutput.get().asFile.parentFile.absolutePath}")
    doFirst {
        environment("RTRENDERER_STREAMLINE_PLUGIN_DIR", File(streamlineSdkRoot.get(), "bin/x64").absolutePath)
    }
    onlyIf {
        cmake.isPresent && nrdSdkRoot.isPresent && nriSdkRoot.isPresent && streamlineSdkRoot.isPresent && jdkHome.isPresent
    }
}

tasks.register<JavaExec>("nvidiaNrdGpuSceneNativeSelfTest") {
    group = "verification"
    description = "Runs a real GPUScene frame through NRD REBLUR and verifies the composed visual output."
    dependsOn(tasks.named("compileNvidiaBridge"), tasks.named("testClasses"))
    val cmake = cmakeExecutableProperty
    val nrdSdkRoot = nrdSdkRootProperty
    val nriSdkRoot = nriSdkRootProperty
    val streamlineSdkRoot = streamlineSdkRootProperty
    val jdkHome = jdkHomeProperty
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.backend.vulkan.NvidiaNrdGpuSceneNativeSelfTest")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    jvmArgs("-Djava.library.path=${nativeBridgeOutput.get().asFile.parentFile.absolutePath}")
    doFirst {
        environment("RTRENDERER_STREAMLINE_PLUGIN_DIR", File(streamlineSdkRoot.get(), "bin/x64").absolutePath)
    }
    onlyIf {
        cmake.isPresent && nrdSdkRoot.isPresent && nriSdkRoot.isPresent && streamlineSdkRoot.isPresent && jdkHome.isPresent
    }
}

tasks.register<JavaExec>("nvidiaRtxmuGpuSceneNativeSelfTest") {
    group = "verification"
    description = "Builds and compacts a real GPUScene BLAS through pinned RTXMU v1.4."
    dependsOn(tasks.named("compileNvidiaBridge"), tasks.named("testClasses"))
    val cmake = cmakeExecutableProperty
    val nrdSdkRoot = nrdSdkRootProperty
    val nriSdkRoot = nriSdkRootProperty
    val streamlineSdkRoot = streamlineSdkRootProperty
    val jdkHome = jdkHomeProperty
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.backend.vulkan.NvidiaRtxmuGpuSceneNativeSelfTest")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    jvmArgs("-Djava.library.path=${nativeBridgeOutput.get().asFile.parentFile.absolutePath}")
    onlyIf {
        cmake.isPresent && nrdSdkRoot.isPresent && nriSdkRoot.isPresent
                && streamlineSdkRoot.isPresent && rtxmuSdkRoot.isPresent && jdkHome.isPresent
    }
}

tasks.register<JavaExec>("nvidiaNrdDlaaGpuSceneNativeSelfTest") {
    group = "verification"
    description = "Runs the ordered NRD compose then DLAA reconstruction path on the selected GPU."
    dependsOn(tasks.named("compileNvidiaBridge"), tasks.named("testClasses"))
    val cmake = cmakeExecutableProperty
    val nrdSdkRoot = nrdSdkRootProperty
    val nriSdkRoot = nriSdkRootProperty
    val streamlineSdkRoot = streamlineSdkRootProperty
    val jdkHome = jdkHomeProperty
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.backend.vulkan.NvidiaNrdGpuSceneNativeSelfTest")
    args("combo")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    jvmArgs("-Djava.library.path=${nativeBridgeOutput.get().asFile.parentFile.absolutePath}")
    doFirst { environment("RTRENDERER_STREAMLINE_PLUGIN_DIR", File(streamlineSdkRoot.get(), "bin/x64").absolutePath) }
    onlyIf { cmake.isPresent && nrdSdkRoot.isPresent && nriSdkRoot.isPresent && streamlineSdkRoot.isPresent && jdkHome.isPresent }
}

tasks.register<JavaExec>("nvidiaNrdDlssGpuSceneNativeSelfTest") {
    group = "verification"
    description = "Runs the ordered low-resolution NRD compose then DLSS SR path on the selected GPU."
    dependsOn(tasks.named("compileNvidiaBridge"), tasks.named("testClasses"))
    val cmake = cmakeExecutableProperty
    val nrdSdkRoot = nrdSdkRootProperty
    val nriSdkRoot = nriSdkRootProperty
    val streamlineSdkRoot = streamlineSdkRootProperty
    val jdkHome = jdkHomeProperty
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.backend.vulkan.NvidiaNrdGpuSceneNativeSelfTest")
    args("combo-dlss")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    jvmArgs("-Djava.library.path=${nativeBridgeOutput.get().asFile.parentFile.absolutePath}")
    doFirst { environment("RTRENDERER_STREAMLINE_PLUGIN_DIR", File(streamlineSdkRoot.get(), "bin/x64").absolutePath) }
    onlyIf { cmake.isPresent && nrdSdkRoot.isPresent && nriSdkRoot.isPresent && streamlineSdkRoot.isPresent && jdkHome.isPresent }
}

tasks.register<JavaExec>("nvidiaNrdDlssFrameGenerationGpuSceneNativeSelfTest") {
    group = "verification"
    description = "Runs ordered NRD, DLSS SR, and adaptive DLSS-G/MFG through a real swapchain."
    dependsOn(tasks.named("compileNvidiaBridge"), tasks.named("testClasses"))
    val cmake = cmakeExecutableProperty
    val nrdSdkRoot = nrdSdkRootProperty
    val nriSdkRoot = nriSdkRootProperty
    val streamlineSdkRoot = streamlineSdkRootProperty
    val jdkHome = jdkHomeProperty
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.backend.vulkan.NvidiaStreamlineFrameGenerationNativeSelfTest")
    args("composed")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    jvmArgs("-Djava.library.path=${nativeBridgeOutput.get().asFile.parentFile.absolutePath}")
    doFirst { environment("RTRENDERER_STREAMLINE_PLUGIN_DIR", File(streamlineSdkRoot.get(), "bin/x64").absolutePath) }
    onlyIf { cmake.isPresent && nrdSdkRoot.isPresent && nriSdkRoot.isPresent && streamlineSdkRoot.isPresent && jdkHome.isPresent }
}

/*
 * Production Streamline starts process-external NGX updater work from slInit. Keep only tasks
 * that own a Streamline lifecycle on one lane so concurrent Gradle workers cannot create an OTA
 * process storm or race the driver's shared cache. NRD-only, RTXMU, and renderer-core tasks retain
 * normal Gradle parallelism.
 */
val nvidiaStreamlineNativeGateLock = gradle.sharedServices.registerIfAbsent(
    "nvidiaStreamlineNativeGateLock",
    NvidiaStreamlineNativeGateLock::class
) {
    maxParallelUsages.set(1)
}

listOf(
    "nvidiaStreamlinePlanLeaseNativeSelfTest",
    "nvidiaStreamlineDeviceHandoffNativeSelfTest",
    "nvidiaStreamlineGpuSceneNativeSelfTest",
    "nvidiaStreamlineDlaaGpuSceneNativeSelfTest",
    "nvidiaStreamlineNisGpuSceneNativeSelfTest",
    "nvidiaStreamlineFrameGenerationNativeSelfTest",
    "nvidiaStreamlineMultiFrameGenerationNativeSelfTest",
    "nvidiaStreamlineAdaptiveFrameGenerationNativeSelfTest",
    "nvidiaNrdDlaaGpuSceneNativeSelfTest",
    "nvidiaNrdDlssGpuSceneNativeSelfTest",
    "nvidiaNrdDlssFrameGenerationGpuSceneNativeSelfTest"
).forEach { taskName ->
    tasks.named(taskName).configure {
        usesService(nvidiaStreamlineNativeGateLock)
    }
}

/**
 * Single release-facing gate for the NVIDIA native surface. Each child task remains independently
 * runnable for diagnosis, while this aggregate makes the published claim mechanically depend on
 * every real-device capability that this module exposes.
 */
tasks.register("nvidiaNativeAcceptanceGate") {
    group = "verification"
    description = "Runs the complete NVIDIA native handoff, Streamline, NRD, and RTXMU acceptance matrix."
    val requiredPaths = listOf(
        "cmakeExecutable" to cmakeExecutableProperty,
        "nrdSdkRoot" to nrdSdkRootProperty,
        "nriSdkRoot" to nriSdkRootProperty,
        "streamlineSdkRoot" to streamlineSdkRootProperty,
        "rtxmuSdkRoot" to rtxmuSdkRoot,
        "jdkHome" to jdkHomeProperty
    )
    doFirst {
        val missing = requiredPaths.filterNot { (_, path) -> path.isPresent }.map { it.first }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "NVIDIA native acceptance requires discoverable SDK/toolchain paths: "
                        + missing.joinToString(", ")
            )
        }
    }
    val acceptanceTaskNames = listOf(
        "verifyPackagedNvidiaRuntime",
        "nvidiaNativeBridgeSelfTest",
        "nvidiaStreamlineFrameConstantsSelfTest",
        "nvidiaNrdFrameConstantsSelfTest",
        "nvidiaStreamlinePlanLeaseNativeSelfTest",
        "nvidiaStreamlineDeviceHandoffNativeSelfTest",
        "nvidiaStreamlineGpuSceneNativeSelfTest",
        "nvidiaStreamlineDlaaGpuSceneNativeSelfTest",
        "nvidiaStreamlineNisGpuSceneNativeSelfTest",
        "nvidiaStreamlineFrameGenerationNativeSelfTest",
        "nvidiaStreamlineMultiFrameGenerationNativeSelfTest",
        "nvidiaNrdGpuSceneNativeSelfTest",
        "nvidiaNrdDlaaGpuSceneNativeSelfTest",
        "nvidiaNrdDlssGpuSceneNativeSelfTest",
        "nvidiaRtxmuGpuSceneNativeSelfTest"
    )
    dependsOn(
        acceptanceTaskNames
    )
    doLast {
        val report = layout.buildDirectory.file("reports/nvidia-native-acceptance/summary.txt").get().asFile
        report.parentFile.mkdirs()
        report.writeText(
            "NVIDIA native acceptance dependencies completed successfully\n"
                    + acceptanceTaskNames.joinToString(separator = "\n") { "- $it" }
                    + "\n",
            Charsets.UTF_8
        )
        logger.lifecycle("NVIDIA native acceptance report: $report")
    }
}

tasks.register("verifyNvidiaModuleBoundary") {
    group = "verification"
    description = "Rejects bundled proprietary SDK binaries and accidental direct core ownership."
    val productionFiles = fileTree("src/main")
    inputs.files(productionFiles)
    doLast {
        val forbidden = productionFiles.files.filter {
            it.extension.lowercase() in setOf("dll", "lib", "pdb")
        }
        if (forbidden.isNotEmpty()) {
            throw GradleException("Proprietary/native binaries must not be committed: $forbidden")
        }
    }
}

tasks.named("check") {
    dependsOn(
        tasks.named("verifyNvidiaModuleBoundary"),
        nvidiaStreamlinePreflightSelfTest,
        nvidiaStreamlineAdaptiveFrameGenerationSelfTest,
        nvidiaTechnologyCapabilitiesSelfTest,
        nvidiaFeatureFailurePolicySelfTest,
        nvidiaFeatureExecutionEvidenceSelfTest,
        nvidiaStreamlinePresentCircuitBreakerSelfTest
    )
}

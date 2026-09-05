import java.nio.charset.StandardCharsets
import groovy.json.JsonSlurper
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    `java-library`
}

data class Native1080pScenario(
    val mainClass: String,
    val widthProperty: String,
    val heightProperty: String
)

abstract class RendererCoreHardwareGateLock : BuildService<BuildServiceParameters.None>

group = rootProject.group
version = rootProject.version

val toolchainJavaVersion = rootProject.providers.gradleProperty("java_toolchain_version")
    .orElse(JavaVersion.current().majorVersion)
    .get()
    .toInt()
val fastutilVersion = rootProject.providers.gradleProperty("fastutil_version").getOrElse("8.5.19")
val jomlVersion = rootProject.providers.gradleProperty("joml_version").getOrElse("1.10.8")
val lwjglVersion = rootProject.providers.gradleProperty("lwjgl_version").getOrElse("3.4.3")

dependencies {
    // Core implements the public contracts but must not publish a reverse dependency back to
    // renderer-api: renderer-api owns the single-coordinate runtime edge to this module.
    compileOnly(project(":renderer-api"))
    testImplementation(project(":renderer-api"))
    // renderer-api publishes NVIDIA as an external runtime coordinate. The composite build must
    // resolve that coordinate to its sibling implementation for core test classpaths; individual
    // provider-free JavaExec tasks filter the resulting JAR before launching their test process.
    testRuntimeOnly(project(":renderer-nvidia"))
    implementation("it.unimi.dsi:fastutil:$fastutilVersion")
    api("org.joml:joml:$jomlVersion")
    api("org.lwjgl:lwjgl:$lwjglVersion")
    api("org.lwjgl:lwjgl-glfw:$lwjglVersion")
    api("org.lwjgl:lwjgl-vulkan:$lwjglVersion")
    api("org.lwjgl:lwjgl-vma:$lwjglVersion")
    implementation("org.lwjgl:lwjgl-shaderc:$lwjglVersion")

    // renderer-core is published as a ready-to-run Windows backend. Keeping natives test-only
    // makes the published provider discoverable but impossible to initialize in a clean host.
    runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-glfw:$lwjglVersion:natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-vma:$lwjglVersion:natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-shaderc:$lwjglVersion:natives-windows")
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

tasks.withType<ProcessResources>().configureEach {
    filteringCharset = "UTF-8"
}

/*
 * Core contracts are deterministic JavaExec programs registered below, not JUnit tests. Gradle 9.5
 * otherwise treats the intentionally empty JUnit discovery result as a configuration failure before
 * those real gates can run.
 */
tasks.withType<Test>().configureEach {
    failOnNoDiscoveredTests = false
}

tasks.withType<JavaExec>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(toolchainJavaVersion)
    })
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
        "-Duser.language=en",
        "-Duser.country=US"
    )
    if (toolchainJavaVersion >= 24) {
        jvmArgs("--sun-misc-unsafe-memory-access=allow")
    }
    systemProperties(
        System.getProperties().stringPropertyNames()
            .filter { it.startsWith("top.ceroxe.rt.") }
            .associateWith(System::getProperty)
    )
}

/*
 * Match dependency namespaces instead of prose references to a host game.
 * Renderer documentation must be able to explain the host boundary without
 * turning a comment into a false-positive dependency violation.
 */
val forbiddenCoreReferences = listOf(
    "net." + "mine" + "craft.",
    "com.mojang.",
    "net.fabricmc.",
    "org.lwjgl.opengl.",
    "top.ceroxe.rt." + "M" + "C" + "VulkanRT",
    "top.ceroxe.rt.bridge.",
    "top.ceroxe.rt.mixin."
)

val coreProductionSources = fileTree("src/main") {
    include("**/*.java")
    include("**/*.rgen")
    include("**/*.rmiss")
    include("**/*.rchit")
}

tasks.register("verifyNoHostGameDependencies") {
    group = "verification"
    description = "Rejects host-game, Mojang, Fabric, bridge, mixin, and OpenGL dependencies in renderer-core production code."
    inputs.files(coreProductionSources)

    doLast {
        val violations = mutableListOf<String>()
        coreProductionSources.files.sorted().forEach { file ->
            file.readLines(StandardCharsets.UTF_8).forEachIndexed { index, line ->
                forbiddenCoreReferences.forEach { forbidden ->
                    if (line.contains(forbidden)) {
                        val relative = projectDir.toPath().relativize(file.toPath())
                        violations += "$relative:${index + 1}: $forbidden"
                    }
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "renderer-core must remain host-game-independent:\n" +
                    violations.joinToString("\n")
            )
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("verifyNoHostGameDependencies"))
}

val registerCoreSelfTest =
    { taskName: String, mainClassName: String, taskDescription: String ->
        tasks.register<JavaExec>(taskName) {
            group = "verification"
            description = taskDescription
            dependsOn(tasks.named("testClasses"))
            // Core gates validate the host-independent Vulkan backend. The API test graph also
            // resolves renderer-nvidia to prove the published single-coordinate dependency, but
            // putting that ServiceLoader provider in every forked core JVM would silently turn
            // dozens of core tests into duplicate Streamline initializations. NVIDIA behavior has
            // its own real-device acceptance gate, so keep this execution boundary provider-free.
            classpath = sourceSets.test.get().runtimeClasspath.filter { file ->
                !file.name.startsWith("renderer-nvidia-")
            }
            mainClass.set(mainClassName)
        }
    }

val contractSelfTests = linkedMapOf(
    "vulkanCommandEvidenceHistorySelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanCommandEvidenceHistorySelfTest",
    "vulkanGenericAccelerationStructuresSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanGenericAccelerationStructuresSelfTest",
    "vulkanGenericTextureLayoutUpdatesSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanGenericTextureLayoutUpdatesSelfTest",
    "vulkanFrameCompositionNativeSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanFrameCompositionNativeSelfTest",
    "coreVulkanBlasBuildCoordinatorSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanBlasBuildCoordinatorSelfTest",
    "coreVulkanRendererRuntimeLifecycleSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanRendererRuntimeLifecycleSelfTest",
    "coreRtActiveSectionContentStateSelfTest" to "top.ceroxe.rt.renderer.rt.acceleration.RtActiveSectionContentStateSelfTest",
    "coreRtCommittedFrontPolicySelfTest" to "top.ceroxe.rt.renderer.rt.device.RtCommittedFrontPolicySelfTest",
    "coreRtDeferredEmptySectionStateSelfTest" to "top.ceroxe.rt.renderer.rt.acceleration.RtDeferredEmptySectionStateSelfTest",
    "coreRtDeviceFrameAdmissionSelfTest" to "top.ceroxe.rt.renderer.rt.device.RtDeviceFrameAdmissionSelfTest",
    "coreRtDynamicInstanceFlightRecorderSelfTest" to "top.ceroxe.rt.renderer.rt.acceleration.RtDynamicInstanceFlightRecorderSelfTest",
    "coreRtFirstFrontBlasFlightRecorderSelfTest" to "top.ceroxe.rt.renderer.rt.acceleration.RtFirstFrontBlasFlightRecorderSelfTest",
    "coreRtFirstFrontBlasProgressTrackerSelfTest" to "top.ceroxe.rt.renderer.rt.acceleration.RtFirstFrontBlasProgressTrackerSelfTest",
    "coreRtFrameDispatchAdmissionSelfTest" to "top.ceroxe.rt.renderer.rt.pipeline.RtFrameDispatchAdmissionSelfTest",
    "coreRtSharedFramePublicationLedgerSelfTest" to "top.ceroxe.rt.renderer.rt.pipeline.RtSharedFramePublicationLedgerSelfTest",
    "coreRtDynamicSceneUploadPlannerSelfTest" to "top.ceroxe.rt.renderer.rt.pipeline.RtDynamicSceneUploadPlannerSelfTest",
    "coreRtDynamicSceneShaderAbiSelfTest" to "top.ceroxe.rt.renderer.rt.pipeline.RtDynamicSceneShaderAbiSelfTest",
    "coreRtPipelineFrameLifecycleSelfTest" to "top.ceroxe.rt.renderer.rt.pipeline.RtPipelineFrameLifecycleSelfTest",
    "coreRtShaderBindingTableSelfTest" to "top.ceroxe.rt.renderer.rt.pipeline.RtShaderBindingTableSelfTest",
    "coreRtFrameDispatchFlightRecorderSelfTest" to "top.ceroxe.rt.renderer.rt.pipeline.RtFrameDispatchFlightRecorderSelfTest",
    "coreRtMaterialStateSelfTest" to "top.ceroxe.rt.renderer.rt.material.RtMaterialStateSelfTest",
    "coreRtMaterialUploadStatisticsSelfTest" to "top.ceroxe.rt.renderer.rt.material.RtMaterialUploadStatisticsSelfTest",
    "coreMaterialSlotAllocatorSelfTest" to "top.ceroxe.rt.renderer.rt.material.MaterialSlotAllocatorSelfTest",
    "coreRtResourceScopeSelfTest" to "top.ceroxe.rt.renderer.rt.device.RtResourceScopeSelfTest",
    "coreVulkanFailuresSelfTest" to "top.ceroxe.rt.renderer.rt.device.VulkanFailuresSelfTest",
    "coreRtVulkanDeviceCapabilitiesSelfTest" to "top.ceroxe.rt.renderer.rt.device.RtVulkanDeviceCapabilitiesSelfTest",
    "coreRtDeferredWorldSceneBindSchedulerSelfTest" to "top.ceroxe.rt.renderer.rt.device.RtDeferredWorldSceneBindSchedulerSelfTest",
    "coreRtDynamicSceneDispatchPolicySelfTest" to "top.ceroxe.rt.renderer.rt.device.RtDynamicSceneDispatchPolicySelfTest",
    "coreRtWorldSceneMaterialUploadLaneSelfTest" to "top.ceroxe.rt.renderer.rt.device.RtWorldSceneMaterialUploadLaneSelfTest",
    "coreRtWorldSceneConvergencePolicySelfTest" to "top.ceroxe.rt.renderer.rt.device.RtWorldSceneConvergencePolicySelfTest",
    "coreRtDynamicMeshContractsSelfTest" to "top.ceroxe.rt.renderer.rt.acceleration.RtDynamicMeshContractsSelfTest",
    "coreRtSectionSourceStoreSelfTest" to "top.ceroxe.rt.renderer.rt.acceleration.RtSectionSourceStoreSelfTest",
    "coreRtSectionActiveViewCacheSelfTest" to "top.ceroxe.rt.renderer.rt.acceleration.RtSectionActiveViewCacheSelfTest",
    "coreRtSectionActiveViewTelemetrySelfTest" to "top.ceroxe.rt.renderer.rt.acceleration.RtSectionActiveViewTelemetrySelfTest",
    "coreRtSectionBlasConfigurationSelfTest" to "top.ceroxe.rt.renderer.rt.acceleration.RtSectionBlasConfigurationSelfTest",
    "coreRtSectionAsyncBuildInventorySelfTest" to "top.ceroxe.rt.renderer.rt.acceleration.RtSectionAsyncBuildInventorySelfTest",
    "coreRtSectionSceneRevisionStateSelfTest" to "top.ceroxe.rt.renderer.rt.acceleration.RtSectionSceneRevisionStateSelfTest",
    "coreRtSectionBlasBuildBatchSelfTest" to "top.ceroxe.rt.renderer.rt.acceleration.RtSectionBlasBuildBatchSelfTest",
    "coreRtSectionBlasLifecycleFlightRecorderSelfTest" to "top.ceroxe.rt.renderer.rt.acceleration.RtSectionBlasLifecycleFlightRecorderSelfTest",
    "coreRtSectionBlasRetirementQueueSelfTest" to "top.ceroxe.rt.renderer.rt.acceleration.RtSectionBlasRetirementQueueSelfTest",
    "coreRtSectionBlasStatisticsSelfTest" to "top.ceroxe.rt.renderer.rt.acceleration.RtSectionBlasStatisticsSelfTest",
    "coreRtSectionBuildIntentStateSelfTest" to "top.ceroxe.rt.renderer.rt.acceleration.RtSectionBuildIntentStateSelfTest",
    "coreRtSectionBuildPriorityStateSelfTest" to "top.ceroxe.rt.renderer.rt.acceleration.RtSectionBuildPriorityStateSelfTest",
    "coreRtSectionForegroundBuildLedgerSelfTest" to "top.ceroxe.rt.renderer.rt.acceleration.RtSectionForegroundBuildLedgerSelfTest",
    "coreRtSectionForegroundStateSelfTest" to "top.ceroxe.rt.renderer.rt.acceleration.RtSectionForegroundStateSelfTest",
    "coreRtSectionLifecycleMembershipStateSelfTest" to "top.ceroxe.rt.renderer.rt.acceleration.RtSectionLifecycleMembershipStateSelfTest",
    "coreRtSectionMaterialPublicationStateSelfTest" to "top.ceroxe.rt.renderer.rt.acceleration.RtSectionMaterialPublicationStateSelfTest",
    "coreRtSectionTerrainOwnershipPublisherSelfTest" to "top.ceroxe.rt.renderer.rt.acceleration.RtSectionTerrainOwnershipPublisherSelfTest",
    "coreRtSectionTlasBuildInputCacheSelfTest" to "top.ceroxe.rt.renderer.rt.acceleration.RtSectionTlasBuildInputCacheSelfTest",
    "coreVulkanQueueHostSyncSelfTest" to "top.ceroxe.rt.renderer.rt.device.VulkanQueueHostSyncSelfTest",
    "coreVulkanQueueTimelineSelfTest" to "top.ceroxe.rt.renderer.rt.device.VulkanQueueTimelineSelfTest",
    "coreVulkanProviderQueueAllocatorSelfTest" to "top.ceroxe.rt.renderer.rt.device.VulkanProviderQueueAllocatorSelfTest",
    "coreVulkanRtDeviceCapabilitySelfTest" to "top.ceroxe.rt.renderer.rt.device.VulkanRtDeviceCapabilitySelfTest",
    "corePersistentSceneRegistrySelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.PersistentSceneRegistrySelfTest",
    "coreVulkanSceneResidencySelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanSceneResidencySelfTest",
    "coreVulkanSceneResidencyFlightRecorderSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanSceneResidencyFlightRecorderSelfTest",
    "coreVulkanGpuSceneAbiSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanGpuSceneAbiSelfTest",
    "coreVulkanGpuSceneAbiPropertySelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanGpuSceneAbiPropertySelfTest",
    "coreVulkanFrameUniformPackerSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanFrameUniformPackerSelfTest",
    "coreTemporalHistoryTrackerSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.TemporalHistoryTrackerSelfTest",
    "coreVulkanFrameExtentsSelfTest" to "top.ceroxe.rt.renderer.rt.pipeline.VulkanFrameExtentsSelfTest",
    "coreVulkanFrameFlightRecorderSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanFrameFlightRecorderSelfTest",
    "coreGpuSceneDescriptorResourcesSelfTest" to "top.ceroxe.rt.renderer.rt.pipeline.GpuSceneDescriptorResourcesSelfTest",
    "coreVulkanDenoisingResourceContractSelfTest" to "top.ceroxe.rt.renderer.feature.VulkanDenoisingResourceContractSelfTest",
    "coreVulkanFrameReconstructionResourceContractSelfTest" to "top.ceroxe.rt.renderer.feature.VulkanFrameReconstructionResourceContractSelfTest",
    "coreVulkanStreamlineFrameResourceContractSelfTest" to "top.ceroxe.rt.renderer.feature.VulkanStreamlineFrameResourceContractSelfTest",
    "coreVulkanFeatureRequirementsSelfTest" to "top.ceroxe.rt.renderer.feature.VulkanFeatureRequirementsSelfTest",
    "coreVulkanFeatureRuntimeStateSelfTest" to "top.ceroxe.rt.renderer.feature.VulkanFeatureRuntimeStateSelfTest",
    "coreVulkanGpuSceneFeatureCompositionSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanGpuSceneFeatureCompositionSelfTest",
    "coreVulkanFeatureSubmissionTransactionSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanFeatureSubmissionTransactionSelfTest",
    "coreVulkanDenoisingFrameResourcesSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanDenoisingFrameResourcesSelfTest",
    "coreVulkanFrameReconstructionResourcesSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanFrameReconstructionResourcesSelfTest",
    "coreVulkanGpuSceneShaderSelfTest" to "top.ceroxe.rt.renderer.rt.pipeline.VulkanGpuSceneShaderSelfTest",
    "coreRtPrecompiledShaderSelfTest" to "top.ceroxe.rt.renderer.rt.pipeline.RtPrecompiledShaderSelfTest",
    "coreVulkanFramePixelCodecSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanFrameDiagnosticReadbackSelfTest",
    "coreVulkanRangeArenaSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanRangeArenaSelfTest",
    "coreVulkanGpuSceneMemorySelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanGpuSceneMemorySelfTest",
    "coreVulkanGpuSceneIdentityIndexSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanGpuSceneIdentityIndexSelfTest",
    "coreVulkanGpuSceneUploadPlannerSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanGpuSceneUploadPlannerSelfTest",
    "coreVulkanGpuSceneTransferPlanSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanGpuSceneTransferPlanSelfTest",
    "coreVulkanGpuSceneSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanGpuSceneSelfTest",
    "coreVulkanGpuSceneHeavySceneBenchmark" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanGpuSceneHeavySceneBenchmark",
    "coreVulkanRendererHostSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanRendererHostSelfTest",
    "coreVulkanGpuFrameLeaseSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanGpuFrameLeaseSelfTest",
    "coreVulkanExternalFrameConsumerAdapterSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanExternalFrameConsumerAdapterSelfTest",
    "coreVulkanTextOverlayRasterizerSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanTextOverlayRasterizerSelfTest",
    "coreVulkanSpirvBindingValidatorSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanSpirvBindingValidatorSelfTest",
    "coreVulkanRendererBackendProviderSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanRendererBackendProviderSelfTest"
)

contractSelfTests.forEach { (taskName, mainClassName) ->
    registerCoreSelfTest(
        taskName,
        mainClassName,
        "Runs the independent renderer-core contract check $mainClassName."
    )
}

/*
 * Pinned SPIR-V is a production resource.  Generation deliberately invokes the same Java
 * compiler implementation as verification, so a source edit cannot silently produce assets
 * compiled with a different shaderc configuration.
 */
tasks.register<JavaExec>("generateRtPrecompiledShaders") {
    group = "build"
    description = "Regenerates pinned RT SPIR-V resources from the current GLSL sources."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("top.ceroxe.rt.renderer.rt.pipeline.RtPrecompiledShaderSelfTest")
    args(layout.projectDirectory.dir("src/main/resources/assets/rtrenderer/shaders/spv").asFile.absolutePath)
}

tasks.named<JavaExec>("coreVulkanGpuSceneHeavySceneBenchmark").configure {
    providers.gradleProperty("gpuSceneHeavySceneJfr").orNull?.let { recordingPath ->
        jvmArgs("-XX:StartFlightRecording=filename=$recordingPath,settings=profile,dumponexit=true")
    }
}

tasks.register("rendererCoreContractGate") {
    group = "verification"
    description = "Runs renderer-core ownership, lifecycle, material, pipeline, and acceleration contracts."
    dependsOn(contractSelfTests.keys)
    dependsOn(":renderer-api:check")
}

/*
 * A standalone API project must make `check` authoritative. Leaving the core
 * contract gate opt-in would allow broken ownership or lifecycle contracts to
 * publish successfully while Gradle still reports a green default build.
 */
tasks.named("check") {
    dependsOn(tasks.named("rendererCoreContractGate"))
}

val nativeSelfTests = linkedMapOf(
    "vulkanEvidenceRetentionNativeSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanEvidenceRetentionNativeSelfTest",
    "vulkanGenericCommandNativeSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanGenericCommandNativeSelfTest",
    "vulkanGenericRayTracingNativeSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanGenericRayTracingNativeSelfTest",
    "vulkanGpuSceneNativeSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanGpuSceneNativeSelfTest",
    "vulkanGenericAccelerationNativeSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanGenericBlasNativeSelfTest",
    "vulkanSceneAccelerationNativeSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanSceneAccelerationNativeSelfTest",
    "vulkanSceneRuntimeNativeSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanSceneRuntimeNativeSelfTest",
    "gpuSceneRayTracingPipelineNativeSelfTest" to "top.ceroxe.rt.renderer.rt.pipeline.GpuSceneRayTracingPipelineNativeSelfTest",
    "vulkanGpuSceneRenderingSessionNativeSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanGpuSceneRenderingSessionNativeSelfTest",
    "vulkanFrameSlotExternalCompletionNativeSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanFrameSlotExternalCompletionNativeSelfTest",
    "vulkanGpuSceneThroughputNativeSelfTest" to "top.ceroxe.rt.renderer.backend.vulkan.VulkanGpuSceneThroughputNativeSelfTest",
    "rtNativeMicroSceneSelfTest" to "top.ceroxe.rt.renderer.rt.RtNativeMicroSceneSelfTest",
    "rtNativeStressSceneSelfTest" to "top.ceroxe.rt.renderer.rt.RtNativeStressSceneSelfTest",
    "rtNativeMaskedMaterialStressSelfTest" to "top.ceroxe.rt.renderer.rt.RtNativeMaskedMaterialStressSelfTest",
    "rtNativeFluidSceneSelfTest" to "top.ceroxe.rt.renderer.rt.RtNativeFluidSceneSelfTest",
    "rtNativeDynamicSkySceneSelfTest" to "top.ceroxe.rt.renderer.rt.RtNativeDynamicSkySceneSelfTest",
    "rtNativeDynamicObjectSceneSelfTest" to "top.ceroxe.rt.renderer.rt.RtNativeDynamicObjectSceneSelfTest",
    "rtNativeDynamicBlasTlasStressSelfTest" to "top.ceroxe.rt.renderer.rt.RtNativeDynamicBlasTlasStressSelfTest",
    "rtNativeDynamicMeshInstanceStressSelfTest" to "top.ceroxe.rt.renderer.rt.RtNativeDynamicMeshInstanceStressSelfTest",
    "rtNativeVanillaDynamicLightingSceneSelfTest" to "top.ceroxe.rt.renderer.rt.RtNativeVanillaDynamicLightingSceneSelfTest",
    "rtNativeGpuThroughputSelfTest" to "top.ceroxe.rt.renderer.rt.RtNativeGpuThroughputSelfTest"
)

nativeSelfTests.forEach { (taskName, mainClassName) ->
    registerCoreSelfTest(
        taskName,
        mainClassName,
        "Runs the independent hardware Vulkan RT check $mainClassName."
    )
}

tasks.named<JavaExec>("vulkanEvidenceRetentionNativeSelfTest").configure {
    minHeapSize = "256m"
    maxHeapSize = "256m"
}

listOf("vulkanEvidenceRetentionNativeSelfTest", "vulkanGenericCommandNativeSelfTest",
    "vulkanGenericRayTracingNativeSelfTest", "vulkanGenericAccelerationNativeSelfTest",
    "vulkanFrameCompositionNativeSelfTest").forEach { taskName ->
    tasks.named<JavaExec>(taskName).configure {
        val validationLog = layout.buildDirectory.file("reports/vulkan-validation/$taskName.jsonl")
        systemProperty("top.ceroxe.rt.validation.enabled", "true")
        systemProperty("top.ceroxe.rt.validation.logPath", validationLog.get().asFile.absolutePath)
        doFirst {
            val log = validationLog.get().asFile
            log.parentFile.mkdirs()
            log.writeText("", StandardCharsets.UTF_8)
        }
        doLast {
            val events = validationLog.get().asFile.readLines(StandardCharsets.UTF_8)
                .filter(String::isNotBlank).map { JsonSlurper().parseText(it) as Map<*, *> }
            check(events.any { it["event"] == "validation" }) { "Native gate produced no validation diagnostics" }
            val failures = events.filter { it["severity"] == "ERROR" || it["event"] == "dropSummary" }
            check(failures.isEmpty()) { "Native Vulkan validation failed: $failures" }
        }
    }
}

registerCoreSelfTest(
    "vulkanSerNativeSelfTest",
    "top.ceroxe.rt.renderer.backend.vulkan.VulkanGpuSceneRenderingSessionNativeSelfTest",
    "Runs the required NVIDIA SER device-feature, shader-permutation, and queue-submission gate."
)
tasks.named<JavaExec>("vulkanSerNativeSelfTest").configure {
    systemProperty("top.ceroxe.rt.ser.requiredGate", "true")
}

val rendererCoreHardwareGateLock = gradle.sharedServices.registerIfAbsent(
    "rendererCoreHardwareGateLock",
    RendererCoreHardwareGateLock::class
) {
    maxParallelUsages.set(1)
}

tasks.named("vulkanSerNativeSelfTest").configure {
    usesService(rendererCoreHardwareGateLock)
}

nativeSelfTests.keys.forEach { taskName ->
    tasks.named(taskName).configure {
        usesService(rendererCoreHardwareGateLock)
    }
}

val throughputTask = tasks.named("rtNativeGpuThroughputSelfTest")
nativeSelfTests.keys.filter { it != "rtNativeGpuThroughputSelfTest" }.forEach { taskName ->
    tasks.named(taskName).configure {
        mustRunAfter(throughputTask)
    }
}

/*
 * The largest 768-section convergence scene runs immediately after throughput.
 * Smaller visual/dynamic scenes must not thermally precondition the GPU or leave
 * driver retirement work ahead of the test with the largest initial BLAS front.
 */
val stressTask = tasks.named("rtNativeStressSceneSelfTest")
nativeSelfTests.keys.filter {
    it != "rtNativeGpuThroughputSelfTest" && it != "rtNativeStressSceneSelfTest"
}.forEach { taskName ->
    tasks.named(taskName).configure {
        mustRunAfter(stressTask)
    }
}

tasks.register("rendererCoreNativeGate") {
    group = "verification"
    description = "Runs the complete host-independent hardware Vulkan RT correctness and throughput gate."
    dependsOn(nativeSelfTests.keys)
}

val gpuSceneNativeSelfTests = listOf(
    "vulkanGpuSceneNativeSelfTest",
    "vulkanGenericAccelerationNativeSelfTest",
    "vulkanSceneAccelerationNativeSelfTest",
    "vulkanSceneRuntimeNativeSelfTest",
    "gpuSceneRayTracingPipelineNativeSelfTest",
    "vulkanGpuSceneRenderingSessionNativeSelfTest",
    "vulkanSerNativeSelfTest",
    "vulkanFrameSlotExternalCompletionNativeSelfTest",
    "vulkanGpuSceneThroughputNativeSelfTest"
)

tasks.register("rendererCoreGpuSceneNativeGate") {
    group = "verification"
    description = "Runs the standalone GPUScene Vulkan RT correctness, visual, provider, and throughput gates."
    dependsOn(gpuSceneNativeSelfTests)
}

tasks.register("rendererCoreGpuSceneGate") {
    group = "verification"
    description = "Runs all deterministic and native gates required before the standalone GPUScene checkpoint."
    dependsOn(tasks.named("rendererCoreContractGate"))
    dependsOn(tasks.named("rendererCoreGpuSceneNativeGate"))
    dependsOn(tasks.named("verifyNoHostGameDependencies"))
}

/*
 * Standalone renderer laboratories. Each lane uses only renderer-core payloads
 * and Vulkan hardware; no source-game runtime, bridge, or oracle participates.
 * Keep them separate so a regression report names the resource lifetime under
 * test instead of presenting one opaque native-gate failure.
 */
tasks.register("rendererCoreStaticDenseSceneLab") {
    group = "verification"
    description = "Runs the GPU-resident dense static scene laboratory."
    dependsOn(tasks.named("rtNativeGpuThroughputSelfTest"))
}

tasks.register("rendererCoreDynamicGeometrySceneLab") {
    group = "verification"
    description = "Runs dynamic BLAS/TLAS and persistent-instance scene laboratories."
    dependsOn(tasks.named("rtNativeDynamicBlasTlasStressSelfTest"))
    dependsOn(tasks.named("rtNativeDynamicMeshInstanceStressSelfTest"))
}

tasks.register("rendererCoreDynamicMaterialMediumSceneLab") {
    group = "verification"
    description = "Runs incremental masked-material and dynamic-medium scene laboratories."
    dependsOn(tasks.named("rtNativeMaskedMaterialStressSelfTest"))
    dependsOn(tasks.named("rtNativeFluidSceneSelfTest"))
}

tasks.register("rendererCoreSceneLabGate") {
    group = "verification"
    description = "Runs the standalone static, dynamic geometry, and dynamic material/medium renderer laboratories."
    dependsOn(tasks.named("rendererCoreStaticDenseSceneLab"))
    dependsOn(tasks.named("rendererCoreDynamicGeometrySceneLab"))
    dependsOn(tasks.named("rendererCoreDynamicMaterialMediumSceneLab"))
}

/*
 * A fixed-resolution benchmark matrix reuses the native correctness scenes
 * instead of forking their scene construction. Dedicated JavaExec tasks keep
 * the 1080p contract local to this matrix; ordinary native gates retain their
 * faster default extents. The shared hardware service and explicit ordering
 * make runs reproducible even when Gradle parallel execution is enabled.
 */
val native1080pMatrixScenarios = linkedMapOf(
    "rtNative1080pStaticDenseBenchmark" to Native1080pScenario(
        "top.ceroxe.rt.renderer.rt.RtNativeGpuThroughputSelfTest",
        "top.ceroxe.rt.rt.gpuThroughput.width",
        "top.ceroxe.rt.rt.gpuThroughput.height"
    ),
    "rtNative1080pMaskedMaterialBenchmark" to Native1080pScenario(
        "top.ceroxe.rt.renderer.rt.RtNativeMaskedMaterialStressSelfTest",
        "top.ceroxe.rt.rt.maskedStress.width",
        "top.ceroxe.rt.rt.maskedStress.height"
    ),
    "rtNative1080pFluidMediumBenchmark" to Native1080pScenario(
        "top.ceroxe.rt.renderer.rt.RtNativeFluidSceneSelfTest",
        "top.ceroxe.rt.rt.fluidStress.width",
        "top.ceroxe.rt.rt.fluidStress.height"
    ),
    "rtNative1080pDynamicSkyBenchmark" to Native1080pScenario(
        "top.ceroxe.rt.renderer.rt.RtNativeDynamicSkySceneSelfTest",
        "top.ceroxe.rt.rt.dynamicSky.width",
        "top.ceroxe.rt.rt.dynamicSky.height"
    ),
    "rtNative1080pDynamicLightingBenchmark" to Native1080pScenario(
        "top.ceroxe.rt.renderer.rt.RtNativeVanillaDynamicLightingSceneSelfTest",
        "top.ceroxe.rt.rt.vanillaLighting.width",
        "top.ceroxe.rt.rt.vanillaLighting.height"
    ),
    "rtNative1080pDynamicBlasTlasBenchmark" to Native1080pScenario(
        "top.ceroxe.rt.renderer.rt.RtNativeDynamicBlasTlasStressSelfTest",
        "top.ceroxe.rt.rt.dynamicBlasStress.width",
        "top.ceroxe.rt.rt.dynamicBlasStress.height"
    )
)

native1080pMatrixScenarios.forEach { (taskName, scenario) ->
    registerCoreSelfTest(
        taskName,
        scenario.mainClass,
        "Runs the fixed 1920x1080 Vulkan RT matrix lane ${scenario.mainClass}."
    )
    tasks.named<JavaExec>(taskName).configure {
        usesService(rendererCoreHardwareGateLock)
        systemProperty(scenario.widthProperty, "1920")
        systemProperty(scenario.heightProperty, "1080")
    }
}

var previous1080pMatrixTask: TaskProvider<*>? = null
native1080pMatrixScenarios.keys.forEach { taskName ->
    val currentTask = tasks.named(taskName)
    previous1080pMatrixTask?.let { predecessorTask ->
        currentTask.configure {
            mustRunAfter(predecessorTask)
        }
    }
    previous1080pMatrixTask = currentTask
}

tasks.register("rendererCore1080pBenchmarkMatrix") {
    group = "verification"
    description = "Runs the ordered host-independent 1080p Vulkan RT correctness and GPU-timing matrix."
    dependsOn(native1080pMatrixScenarios.keys)
}

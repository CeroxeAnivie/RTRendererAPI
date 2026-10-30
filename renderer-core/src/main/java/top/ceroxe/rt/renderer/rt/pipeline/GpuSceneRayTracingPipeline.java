package top.ceroxe.rt.renderer.rt.pipeline;

import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.backend.vulkan.VulkanSpirvBindingValidator;
import java.util.List;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.RtEdgeSink;
import top.ceroxe.rt.renderer.feature.VulkanDenoisingResourceContract;
import top.ceroxe.rt.renderer.feature.VulkanFrameReconstructionResourceContract;
import top.ceroxe.rt.renderer.feature.VulkanFrameGenerationResourceContract;
import top.ceroxe.rt.renderer.feature.VulkanTemporalFrameInput;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.RtGpuImage;
import top.ceroxe.rt.renderer.feature.VulkanFeatureFrameContext;
import top.ceroxe.rt.renderer.feature.VulkanFeatureSession;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable generic GPUScene RT pipeline and descriptor-set bank.
 *
 * <p>This owner contains no scene, frame, or host-runtime state. Shader modules are temporary
 * construction inputs, the SBT owns its uploaded device buffer, and all Vulkan handles are torn
 * down in reverse dependency order after frame submissions have completed.</p>
 */
public final class GpuSceneRayTracingPipeline implements AutoCloseable {
    public enum Program {
        SURFACE("gpuscene_surface.rgen"), COMPACT("gpuscene_compact.rgen"), GENERAL("gpuscene.rgen"),
        COMPACT_PASSIVE("gpuscene_compact_passive.rgen"), GENERAL_PASSIVE("gpuscene_passive.rgen"),
        COMPACT_PASSIVE_NO_DIELECTRIC("gpuscene_compact_passive_no_dielectric.rgen"),
        GENERAL_PASSIVE_NO_DIELECTRIC("gpuscene_passive_no_dielectric.rgen"),
        PRIMARY_TRACE("gpuscene_primary_trace.rgen"),
        NATIVE_MEDIUM_TRACE("gpuscene_native_medium_trace.rgen"),
        NATIVE_BOUNDARY_PACKET("gpuscene_native_boundary_packet.rgen"),
        NATIVE_PBR_MEDIUM_TRACE("gpuscene_native_boundary_packet.rgen"),
        NATIVE_PBR_MEDIUM_CACHED("gpuscene_native_pbr_primary_cached.rgen"),
        NATIVE_PBR_MEDIUM_LIGHTS("gpuscene_native_pbr_medium_lights.rgen"),
        NATIVE_BOUNDARY_SCATTERING("gpuscene_native_boundary_scattering.rgen"),
        NATIVE_SCATTERING_LIGHTS("gpuscene_native_scattering_lights.rgen"),
        NATIVE_SCATTERING_LIGHTS_DIAGNOSTIC("gpuscene_native_scattering_lights.rgen"),
        NATIVE_MULTIPLE_SCATTERING("gpuscene_native_multiple_scattering.rgen"),
        NATIVE_DENSITY_SCATTERING_LIGHTS("gpuscene_native_density_scattering_lights.rgen"),
        NATIVE_OPTICAL_TRACE("gpuscene_native_optical_trace.rgen"),
        SURFACE_PRIMARY_SHADE("gpuscene_surface_primary_shade.rgen"),
        COMPACT_PRIMARY_SHADE("gpuscene_compact_primary_shade.rgen"),
        GENERAL_PRIMARY_SHADE("gpuscene_primary_shade.rgen"),
        COMPACT_VOLUME("gpuscene_compact_volume.rgen"), GENERAL_VOLUME("gpuscene_volume.rgen"),
        COMPACT_CAUSTICS("gpuscene_compact_caustics.rgen"), GENERAL_CAUSTICS("gpuscene_caustics.rgen"),
        PHOTONS("gpuscene_photons.rgen");
        private final String source;
        Program(String source) { this.source = source; }
        public boolean primaryQueue() { return this == PRIMARY_TRACE || this == NATIVE_MEDIUM_TRACE || this == NATIVE_BOUNDARY_PACKET
                || this == NATIVE_PBR_MEDIUM_TRACE || this == NATIVE_PBR_MEDIUM_CACHED || this == NATIVE_PBR_MEDIUM_LIGHTS
                || this == NATIVE_BOUNDARY_SCATTERING
                || this == NATIVE_SCATTERING_LIGHTS || this == NATIVE_SCATTERING_LIGHTS_DIAGNOSTIC || this == NATIVE_MULTIPLE_SCATTERING
                || this == NATIVE_DENSITY_SCATTERING_LIGHTS || this == NATIVE_OPTICAL_TRACE || this == SURFACE_PRIMARY_SHADE
                || this == COMPACT_PRIMARY_SHADE || this == GENERAL_PRIMARY_SHADE; }
    }
    private static final String SHADER_ROOT = "assets/rtrenderer/shaders/gpuscene/";

    private final SceneShaderProgram sceneShaders;
    private final GpuSceneShaderDataBuffers applicationData;
    private final VkDevice device;
    private final GpuSceneDescriptorLayout descriptors;
    private final long pipelineLayout;
    private final long pipeline;
    private final RtShaderBindingTable shaderBindingTable;
    private final RtRayTracingPipelineProperties properties;
    private final long maxStorageBufferRangeBytes;
    private final boolean shaderExecutionReorderingEnabled;
    private boolean closed;
    private boolean closing;
    private boolean pipelineDestroyed;
    private boolean layoutDestroyed;

    private GpuSceneRayTracingPipeline(
            VkDevice device,
            GpuSceneDescriptorLayout descriptors,
            long pipelineLayout,
            long pipeline,
            RtShaderBindingTable shaderBindingTable,
            RtRayTracingPipelineProperties properties,
            long maxStorageBufferRangeBytes,
            boolean shaderExecutionReorderingEnabled,
            SceneShaderProgram sceneShaders,
            GpuSceneShaderDataBuffers applicationData
    ) {
        this.sceneShaders = sceneShaders;
        this.applicationData = applicationData;
        this.device = Objects.requireNonNull(device, "device");
        this.descriptors = Objects.requireNonNull(descriptors, "descriptors");
        if (pipelineLayout == 0L || pipeline == 0L) {
            throw new IllegalArgumentException("pipeline handles must not be null");
        }
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
        this.shaderBindingTable = Objects.requireNonNull(shaderBindingTable, "shaderBindingTable");
        this.properties = Objects.requireNonNull(properties, "properties");
        if (maxStorageBufferRangeBytes <= 0L) {
            throw new IllegalArgumentException("maxStorageBufferRangeBytes must be positive");
        }
        this.maxStorageBufferRangeBytes = maxStorageBufferRangeBytes;
        this.shaderExecutionReorderingEnabled = shaderExecutionReorderingEnabled;
    }

    /**
     * Creates the GPUScene pipeline for one immutable native output encoding.
     *
     * @param runtime            initialized Vulkan device runtime
     * @param descriptorSetCount positive descriptor-set count
     * @param linearHdrOutput    whether ray generation writes linear RGBA16F instead of display RGBA8
     * @return owned pipeline and descriptor bank
     */
    public static GpuSceneRayTracingPipeline open(
            VulkanDeviceRuntime runtime,
            int descriptorSetCount,
            boolean linearHdrOutput
    ) {
        return open(runtime, descriptorSetCount, linearHdrOutput, SceneShaderProgram.builtIn());
    }

    /**
     * Creates a retained-scene pipeline with explicit shader replacements.
     * @param runtime initialized Vulkan runtime
     * @param descriptorSetCount positive frame-slot count
     * @param linearHdrOutput whether the public output is RGBA16F
     * @param sceneShaders shader sources and application data layout
     * @return owned pipeline
     */
    public static GpuSceneRayTracingPipeline open(VulkanDeviceRuntime runtime, int descriptorSetCount,
            boolean linearHdrOutput, SceneShaderProgram sceneShaders) {
        return open(runtime, descriptorSetCount, linearHdrOutput, sceneShaders, Program.GENERAL_VOLUME);
    }

    /** Built-in specialization is internal policy; expert shader programs never enter this selector. */
    public static GpuSceneRayTracingPipeline openBuiltIn(VulkanDeviceRuntime runtime,
            int descriptorSetCount, boolean linearHdrOutput, Program program) {
        return open(runtime, descriptorSetCount, linearHdrOutput, SceneShaderProgram.builtIn(), program);
    }

    private static GpuSceneRayTracingPipeline open(VulkanDeviceRuntime runtime, int descriptorSetCount,
            boolean linearHdrOutput, SceneShaderProgram sceneShaders, Program program) {
        Objects.requireNonNull(sceneShaders, "sceneShaders");
        VulkanDeviceRuntime checkedRuntime = Objects.requireNonNull(runtime, "runtime");
        boolean shaderExecutionReorderingEnabled = checkedRuntime.shaderExecutionReorderingEnabled()
                && !sceneShaders.sources().containsKey(ShaderStage.RAY_GENERATION)
                && !sceneShaders.hasSpirv(ShaderStage.RAY_GENERATION);
        if (descriptorSetCount <= 0) throw new IllegalArgumentException("descriptorSetCount must be positive");
        VkDevice device = checkedRuntime.device();
        GpuSceneDescriptorLayout descriptors = null;
        long pipelineLayout = 0L;
        long pipeline = 0L;
        RtShaderBindingTable shaderBindingTable = null;
        long raygenModule = 0L;
        long missModule = 0L;
        long closestHitModule = 0L;
        long anyHitModule = 0L;
        long visibilityMissModule = 0L, visibilityClosestModule = 0L, visibilityAnyModule = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            RtRayTracingPipelineProperties properties = RtRayTracingPipelineProperties.query(
                    stack, checkedRuntime.physicalDevice()
            );
            VkPhysicalDeviceProperties physicalDeviceProperties = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(checkedRuntime.physicalDevice(), physicalDeviceProperties);
            long maxStorageBufferRangeBytes = Integer.toUnsignedLong(
                    physicalDeviceProperties.limits().maxStorageBufferRange()
            );
            int storageBindings = SceneShaderBindings.STORAGE_BUFFER_COUNT + sceneShaders.dataBindingCount();
            if (storageBindings > Integer.toUnsignedLong(physicalDeviceProperties.limits().maxPerStageDescriptorStorageBuffers())
                    || storageBindings > Integer.toUnsignedLong(physicalDeviceProperties.limits().maxDescriptorSetStorageBuffers())
                    || storageBindings + SceneShaderBindings.STORAGE_IMAGE_COUNT + 1
                        > Integer.toUnsignedLong(physicalDeviceProperties.limits().maxPerStageResources())) {
                throw new IllegalArgumentException("scene shader interface exceeds device descriptor limits");
            }
            descriptors = GpuSceneDescriptorLayout.create(stack, device, descriptorSetCount, sceneShaders.dataBindingCount());
            pipelineLayout = RtRayTracingPipelineFactory.createPipelineLayout(stack, device, descriptors.layout(),
                    program.primaryQueue() ? GpuScenePrimaryQueue.PUSH_BYTES : 0);
            boolean opticalDiagnostic=program==Program.NATIVE_OPTICAL_TRACE&&top.ceroxe.rt.renderer.rt.diagnostics.OpticalDetailedProfile.SHADERS;
            boolean scatteringDiagnostic=program==Program.NATIVE_SCATTERING_LIGHTS_DIAGNOSTIC
                    &&top.ceroxe.rt.renderer.rt.diagnostics.OpticalDetailedProfile.CAMERA_SCATTERING_SHADERS;
            boolean inlineScatteringQuery=checkedRuntime.enabledExtensions().contains(KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME)
                    &&(program==Program.NATIVE_SCATTERING_LIGHTS||program==Program.NATIVE_SCATTERING_LIGHTS_DIAGNOSTIC);
            boolean inlinePbrLightQuery=checkedRuntime.enabledExtensions().contains(KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME)
                    &&program==Program.NATIVE_PBR_MEDIUM_LIGHTS;
            String raygenSource=inlineScatteringQuery?"gpuscene_native_scattering_query.rgen"
                    :inlinePbrLightQuery?"gpuscene_native_pbr_medium_query.rgen":program.source;
            byte[] raygenCode = scatteringDiagnostic?RtShaderModuleCompiler.compileCameraScatteringDiagnostic(SHADER_ROOT+raygenSource,
                    Shaderc.shaderc_raygen_shader,linearHdrOutput,shaderExecutionReorderingEnabled):opticalDiagnostic
                    ?RtShaderModuleCompiler.compileOpticalDiagnostic(SHADER_ROOT+program.source,Shaderc.shaderc_raygen_shader,
                            linearHdrOutput,shaderExecutionReorderingEnabled,top.ceroxe.rt.renderer.rt.diagnostics.OpticalDetailedProfile.GROUP):loadStageCode(
                    raygenSource, Shaderc.shaderc_raygen_shader,
                    linearHdrOutput, shaderExecutionReorderingEnabled, sceneShaders
            );
            byte[] missCode = opticalDiagnostic?RtShaderModuleCompiler.compileOpticalDiagnostic(SHADER_ROOT+"gpuscene.rmiss",Shaderc.shaderc_miss_shader,false,false,top.ceroxe.rt.renderer.rt.diagnostics.OpticalDetailedProfile.GROUP):loadStageCode("gpuscene.rmiss", Shaderc.shaderc_miss_shader, false, false, sceneShaders);
            byte[] closestCode = opticalDiagnostic?RtShaderModuleCompiler.compileOpticalDiagnostic(SHADER_ROOT+"gpuscene.rchit",Shaderc.shaderc_closesthit_shader,false,false,top.ceroxe.rt.renderer.rt.diagnostics.OpticalDetailedProfile.GROUP)
                    :scatteringDiagnostic?RtShaderModuleCompiler.compileCameraScatteringDiagnostic(SHADER_ROOT+"gpuscene.rchit",
                            Shaderc.shaderc_closesthit_shader,false,false):loadStageCode(
                    "gpuscene.rchit", Shaderc.shaderc_closesthit_shader, false, false, sceneShaders
            );
            byte[] anyHitCode = opticalDiagnostic?RtShaderModuleCompiler.compileOpticalDiagnostic(SHADER_ROOT+"gpuscene.rahit",Shaderc.shaderc_anyhit_shader,false,false,top.ceroxe.rt.renderer.rt.diagnostics.OpticalDetailedProfile.GROUP):loadStageCode(program == Program.NATIVE_BOUNDARY_PACKET
                    || program == Program.NATIVE_PBR_MEDIUM_TRACE || program == Program.NATIVE_PBR_MEDIUM_CACHED || program == Program.NATIVE_PBR_MEDIUM_LIGHTS
                    || program == Program.NATIVE_BOUNDARY_SCATTERING
                    ? program==Program.NATIVE_BOUNDARY_SCATTERING?"gpuscene_native_boundary_scattering.rahit":"gpuscene_native_boundary_packet.rahit" : "gpuscene.rahit",
                    Shaderc.shaderc_anyhit_shader, false, false, sceneShaders);
            boolean visibility = program != Program.SURFACE && program != Program.SURFACE_PRIMARY_SHADE
                    && program != Program.PRIMARY_TRACE && program != Program.NATIVE_MEDIUM_TRACE
                    && program != Program.NATIVE_BOUNDARY_PACKET && program != Program.NATIVE_PBR_MEDIUM_TRACE && program != Program.NATIVE_PBR_MEDIUM_CACHED
                    && program != Program.NATIVE_PBR_MEDIUM_LIGHTS && program != Program.NATIVE_BOUNDARY_SCATTERING;
            int recursionDepth = GpuScenePipelineRequirements.recursionDepth(missCode, closestCode, anyHitCode);
            if (properties.maxRayRecursionDepth() < recursionDepth)
                throw new IllegalStateException("device cannot execute the declared shader recursion depth");
            raygenModule = RtShaderModuleCompiler.createModule(stack, device, raygenCode);
            missModule = RtShaderModuleCompiler.createModule(stack, device, missCode);
            closestHitModule = RtShaderModuleCompiler.createModule(stack, device, closestCode);
            anyHitModule = RtShaderModuleCompiler.createModule(stack, device, anyHitCode);
            byte[] visibilityMissCode = visibility ? RtShaderModuleCompiler.loadProduction(SHADER_ROOT + "gpuscene_visibility.rmiss",
                    Shaderc.shaderc_miss_shader, false, false, RtEdgeSink.NOOP) : new byte[0];
            byte[] visibilityClosestCode = visibility ? RtShaderModuleCompiler.loadProduction(SHADER_ROOT + "gpuscene_visibility.rchit",
                    Shaderc.shaderc_closesthit_shader, false, false, RtEdgeSink.NOOP) : new byte[0];
            byte[] visibilityAnyCode = visibility ? scatteringDiagnostic
                    ? RtShaderModuleCompiler.compileCameraScatteringDiagnostic(SHADER_ROOT + "gpuscene_visibility.rahit",
                            Shaderc.shaderc_anyhit_shader, false, false)
                    : RtShaderModuleCompiler.loadProduction(SHADER_ROOT + "gpuscene_visibility.rahit",
                            Shaderc.shaderc_anyhit_shader, false, false, RtEdgeSink.NOOP) : new byte[0];
            if (visibility) {
                visibilityMissModule = RtShaderModuleCompiler.createModule(stack, device, visibilityMissCode);
                visibilityClosestModule = RtShaderModuleCompiler.createModule(stack, device, visibilityClosestCode);
                visibilityAnyModule = RtShaderModuleCompiler.createModule(stack, device, visibilityAnyCode);
            }
            String programKey = RtPipelineCacheStore.programKey("gpuscene-7/program="+program+"/data="+sceneShaders.dataBindingCount()+"/depth="+recursionDepth,
                    raygenCode,missCode,closestCode,anyHitCode,visibilityMissCode,visibilityClosestCode,visibilityAnyCode);
            try (RtPipelineCache cache = RtPipelineCache.open(
                    device, checkedRuntime.physicalDevice(), "gpuscene",programKey)) {
                pipeline = RtRayTracingPipelineFactory.createPipeline(
                        stack, device, pipelineLayout,
                        raygenModule, missModule, closestHitModule, anyHitModule,
                        cache.handle(), visibilityMissModule, visibilityClosestModule, visibilityAnyModule, recursionDepth
                );
            }
            if (Boolean.getBoolean("top.ceroxe.rt.pipeline.handleDiagnostics"))
                System.out.printf(java.util.Locale.ROOT,
                        "GPUScene pipeline handles program=%s pipeline=0x%x device=0x%x visibility=%s%n",
                        program, pipeline, device.address(), visibility);
            byte[] groupHandles = RtRayTracingPipelineFactory.queryShaderGroupHandles(properties, device, pipeline,
                    RtRayTracingPipelineFactory.GROUP_COUNT + (visibility ? 2 : 0));
            RtRayTracingPipelineProperties.ShaderBindingTableData packed = properties.packShaderGroupHandles(
                    groupHandles,
                    RtRayTracingPipelineFactory.RAYGEN_GROUPS,
                    RtRayTracingPipelineFactory.MISS_GROUPS + (visibility ? 1 : 0),
                    RtRayTracingPipelineFactory.HIT_GROUPS + (visibility ? 1 : 0),
                    RtRayTracingPipelineFactory.CALLABLE_GROUPS
            );
            shaderBindingTable = RtShaderBindingTable.create(
                    device,
                    checkedRuntime.allocator(),
                    checkedRuntime.buildCommands(),
                    packed.bytes(),
                    packed.layout()
            );
            GpuSceneRayTracingPipeline result = new GpuSceneRayTracingPipeline(
                    device, descriptors, pipelineLayout, pipeline, shaderBindingTable, properties,
                    maxStorageBufferRangeBytes, shaderExecutionReorderingEnabled, sceneShaders,
                    new GpuSceneShaderDataBuffers(checkedRuntime, descriptorSetCount,
                            sceneShaders.dataBindingCount(), maxStorageBufferRangeBytes)
            );
            descriptors = null;
            pipelineLayout = 0L;
            pipeline = 0L;
            shaderBindingTable = null;
            return result;
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            closeSuppressing(failure, shaderBindingTable);
            if (pipeline != 0L) VK10.vkDestroyPipeline(device, pipeline, null);
            if (pipelineLayout != 0L) VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
            closeSuppressing(failure, descriptors);
            throw failure;
        } finally {
            RtShaderModuleCompiler.destroyModule(device, visibilityAnyModule);
            RtShaderModuleCompiler.destroyModule(device, visibilityClosestModule);
            RtShaderModuleCompiler.destroyModule(device, visibilityMissModule);
            RtShaderModuleCompiler.destroyModule(device, anyHitModule);
            RtShaderModuleCompiler.destroyModule(device, closestHitModule);
            RtShaderModuleCompiler.destroyModule(device, missModule);
            RtShaderModuleCompiler.destroyModule(device, raygenModule);
        }
    }

    private static void transitionTemporalImage(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            RtGpuImage image,
            boolean initialized,
            int initializedSourceAccess,
            int destinationAccess
    ) {
        int rayTracingStage = org.lwjgl.vulkan.KHRRayTracingPipeline
                .VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
        RtFrameDispatchCommands.recordImageLayoutTransition(
                commandBuffer,
                stack,
                image.image(),
                initialized ? VK10.VK_IMAGE_LAYOUT_GENERAL : VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                VK10.VK_IMAGE_LAYOUT_GENERAL,
                initialized ? initializedSourceAccess : 0,
                destinationAccess,
                initialized ? rayTracingStage : VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                rayTracingStage,
                VK10.VK_QUEUE_FAMILY_IGNORED,
                VK10.VK_QUEUE_FAMILY_IGNORED
        );
    }

    private static void transitionDenoisingImage(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanDenoisingResourceContract.Image image,
            boolean initialized,
            int destinationAccess,
            int destinationStage
    ) {
        int rayTracingStage = org.lwjgl.vulkan.KHRRayTracingPipeline
                .VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
        RtFrameDispatchCommands.recordImageLayoutTransition(
                commandBuffer,
                stack,
                image.handle(),
                initialized ? VK10.VK_IMAGE_LAYOUT_GENERAL : VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                VK10.VK_IMAGE_LAYOUT_GENERAL,
                initialized ? VK10.VK_ACCESS_MEMORY_WRITE_BIT : 0,
                destinationAccess,
                initialized ? VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT : VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                destinationStage,
                VK10.VK_QUEUE_FAMILY_IGNORED,
                VK10.VK_QUEUE_FAMILY_IGNORED
        );
    }

    private static void prepareDenoisingResources(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanDenoisingResourceContract resources,
            boolean layoutsInitialized
    ) {
        int rayTracingStage = org.lwjgl.vulkan.KHRRayTracingPipeline
                .VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
        transitionDenoisingImage(
                commandBuffer, stack, resources.normalRoughness(), layoutsInitialized,
                VK10.VK_ACCESS_SHADER_WRITE_BIT, rayTracingStage
        );
        transitionDenoisingImage(
                commandBuffer, stack, resources.viewZ(), layoutsInitialized,
                VK10.VK_ACCESS_SHADER_WRITE_BIT, rayTracingStage
        );
        transitionDenoisingImage(
                commandBuffer, stack, resources.motionVectors(), layoutsInitialized,
                VK10.VK_ACCESS_SHADER_WRITE_BIT, rayTracingStage
        );
        transitionDenoisingImage(
                commandBuffer, stack, resources.diffuseRadianceHitDistance(), layoutsInitialized,
                VK10.VK_ACCESS_SHADER_WRITE_BIT, rayTracingStage
        );
        transitionDenoisingImage(
                commandBuffer, stack, resources.specularRadianceHitDistance(), layoutsInitialized,
                VK10.VK_ACCESS_SHADER_WRITE_BIT, rayTracingStage
        );
        transitionDenoisingImage(
                commandBuffer, stack, resources.diffuseMaterialFactor(), layoutsInitialized,
                VK10.VK_ACCESS_SHADER_WRITE_BIT, rayTracingStage
        );
        transitionDenoisingImage(
                commandBuffer, stack, resources.specularMaterialFactor(), layoutsInitialized,
                VK10.VK_ACCESS_SHADER_WRITE_BIT, rayTracingStage
        );
        transitionDenoisingImage(
                commandBuffer, stack, resources.denoisedDiffuseRadianceHitDistance(), layoutsInitialized,
                VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT,
                VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT
        );
        transitionDenoisingImage(
                commandBuffer, stack, resources.denoisedSpecularRadianceHitDistance(), layoutsInitialized,
                VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT,
                VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT
        );
    }

    private static void makeDenoisingSignalsAvailable(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanDenoisingResourceContract resources
    ) {
        int rayTracingStage = org.lwjgl.vulkan.KHRRayTracingPipeline
                .VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
        int allCommands = VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        int nativeAccess = VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT;
        for (VulkanDenoisingResourceContract.Image image : java.util.List.of(
                resources.normalRoughness(),
                resources.viewZ(),
                resources.motionVectors(),
                resources.diffuseRadianceHitDistance(),
                resources.specularRadianceHitDistance()
        )) {
            RtFrameDispatchCommands.recordImageLayoutTransition(
                    commandBuffer, stack, image.handle(),
                    VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    VK10.VK_ACCESS_SHADER_WRITE_BIT, nativeAccess,
                    rayTracingStage, allCommands,
                    VK10.VK_QUEUE_FAMILY_IGNORED, VK10.VK_QUEUE_FAMILY_IGNORED
            );
        }
    }

    private static void prepareReconstructionResources(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanFrameReconstructionResourceContract resources,
            boolean layoutsInitialized
    ) {
        int rayTracingStage = org.lwjgl.vulkan.KHRRayTracingPipeline
                .VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
        /* inputColor is the trace target and is transitioned by the regular trace-output path. */
        transitionReconstructionImage(
                commandBuffer, stack, resources.depth(), layoutsInitialized,
                VK10.VK_ACCESS_SHADER_WRITE_BIT, rayTracingStage
        );
        transitionReconstructionImage(
                commandBuffer, stack, resources.motionVectors(), layoutsInitialized,
                VK10.VK_ACCESS_SHADER_WRITE_BIT, rayTracingStage
        );
        transitionReconstructionImage(
                commandBuffer, stack, resources.exposure(), layoutsInitialized,
                VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT,
                VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT
        );
    }

    private static void transitionReconstructionImage(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanFrameReconstructionResourceContract.Image image,
            boolean initialized,
            int destinationAccess,
            int destinationStage
    ) {
        RtFrameDispatchCommands.recordImageLayoutTransition(
                commandBuffer,
                stack,
                image.handle(),
                initialized ? VK10.VK_IMAGE_LAYOUT_GENERAL : VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                VK10.VK_IMAGE_LAYOUT_GENERAL,
                initialized ? VK10.VK_ACCESS_MEMORY_WRITE_BIT : 0,
                destinationAccess,
                initialized ? VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT : VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                destinationStage,
                VK10.VK_QUEUE_FAMILY_IGNORED,
                VK10.VK_QUEUE_FAMILY_IGNORED
        );
    }

    private static void makeReconstructionInputsAvailable(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanFrameReconstructionResourceContract resources
    ) {
        int rayTracingStage = org.lwjgl.vulkan.KHRRayTracingPipeline
                .VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
        int allCommands = VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        int nativeAccess = VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT;
        for (VulkanFrameReconstructionResourceContract.Image image : java.util.List.of(
                resources.inputColor(), resources.depth(), resources.motionVectors(), resources.exposure()
        )) {
            RtFrameDispatchCommands.recordImageLayoutTransition(
                    commandBuffer, stack, image.handle(),
                    VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    image == resources.exposure() ? nativeAccess : VK10.VK_ACCESS_SHADER_WRITE_BIT,
                    nativeAccess,
                    image == resources.exposure() ? allCommands : rayTracingStage,
                    allCommands,
                    VK10.VK_QUEUE_FAMILY_IGNORED, VK10.VK_QUEUE_FAMILY_IGNORED
            );
        }
    }

    private static void prepareFrameGenerationResources(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanFrameGenerationResourceContract resources,
            boolean layoutsInitialized
    ) {
        int rayTracingStage = org.lwjgl.vulkan.KHRRayTracingPipeline
                .VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
        transitionReconstructionImage(
                commandBuffer, stack, resources.depth(), layoutsInitialized,
                VK10.VK_ACCESS_SHADER_WRITE_BIT, rayTracingStage
        );
        transitionReconstructionImage(
                commandBuffer, stack, resources.motionVectors(), layoutsInitialized,
                VK10.VK_ACCESS_SHADER_WRITE_BIT, rayTracingStage
        );
    }

    private static void makeFrameGenerationInputsAvailable(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanFrameGenerationResourceContract resources
    ) {
        int rayTracingStage = org.lwjgl.vulkan.KHRRayTracingPipeline
                .VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
        for (VulkanFrameReconstructionResourceContract.Image image
                : java.util.List.of(resources.depth(), resources.motionVectors(), resources.exposure())) {
            RtFrameDispatchCommands.recordImageLayoutTransition(
                    commandBuffer, stack, image.handle(),
                    VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    image == resources.exposure()
                            ? VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT
                            : VK10.VK_ACCESS_SHADER_WRITE_BIT,
                    VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT,
                    image == resources.exposure() ? VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT : rayTracingStage,
                    VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK10.VK_QUEUE_FAMILY_IGNORED, VK10.VK_QUEUE_FAMILY_IGNORED
            );
        }
    }

    private static void makeTraceOutputAvailableForCompose(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            RtGpuImage traceImage
    ) {
        RtFrameDispatchCommands.recordImageLayoutTransition(
                commandBuffer, stack, traceImage.image(),
                VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_GENERAL,
                VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT,
                org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_QUEUE_FAMILY_IGNORED, VK10.VK_QUEUE_FAMILY_IGNORED
        );
    }

    private static void makeDenoisingOutputsAvailableForCompose(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanDenoisingResourceContract resources
    ) {
        for (VulkanDenoisingResourceContract.Image image : java.util.List.of(
                resources.diffuseMaterialFactor(),
                resources.specularMaterialFactor()
        )) {
            RtFrameDispatchCommands.recordImageLayoutTransition(
                    commandBuffer, stack, image.handle(),
                    VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT,
                    org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_QUEUE_FAMILY_IGNORED, VK10.VK_QUEUE_FAMILY_IGNORED
            );
        }
        for (VulkanDenoisingResourceContract.Image image : java.util.List.of(
                resources.diffuseRadianceHitDistance(),
                resources.specularRadianceHitDistance()
        )) {
            /* NRD restores borrowed resources to their declared GENERAL state. Preserve that
             * layout while making its reads visible to the compose pass that reads them again. */
            RtFrameDispatchCommands.recordImageLayoutTransition(
                    commandBuffer, stack, image.handle(),
                    VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    VK10.VK_ACCESS_SHADER_READ_BIT,
                    VK10.VK_ACCESS_SHADER_READ_BIT,
                    VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_QUEUE_FAMILY_IGNORED, VK10.VK_QUEUE_FAMILY_IGNORED
            );
        }
        for (VulkanDenoisingResourceContract.Image image : java.util.List.of(
                resources.denoisedDiffuseRadianceHitDistance(),
                resources.denoisedSpecularRadianceHitDistance()
        )) {
            /* NRD keeps outputs in GENERAL; only a memory dependency is needed before compose. */
            RtFrameDispatchCommands.recordImageLayoutTransition(
                    commandBuffer, stack, image.handle(),
                    VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    VK10.VK_ACCESS_MEMORY_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT,
                    VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_QUEUE_FAMILY_IGNORED, VK10.VK_QUEUE_FAMILY_IGNORED
            );
        }
    }

    static byte[] loadStageCode(String file, int kind,
            boolean linearHdrOutput, boolean ser, SceneShaderProgram program) {
        ShaderStage stage = switch (kind) {
            case Shaderc.shaderc_raygen_shader -> ShaderStage.RAY_GENERATION;
            case Shaderc.shaderc_miss_shader -> ShaderStage.RAY_MISS;
            case Shaderc.shaderc_closesthit_shader -> ShaderStage.RAY_CLOSEST_HIT;
            case Shaderc.shaderc_anyhit_shader -> ShaderStage.RAY_ANY_HIT;
            default -> throw new IllegalArgumentException("unsupported scene shader stage");
        };
        String source = program.sources().get(stage);
        /*
         * A stable application build can ship optimized SPIR-V and avoid shaderc's expensive
         * first-run optimization pass. Select the exact output encoding; GLSL may supply a
         * missing binary or an explicitly requested diagnostic compilation variant.
         */
        byte[] suppliedBinary = program.spirv(stage,
                linearHdrOutput ? FrameOutputFormat.LINEAR_HDR_RGBA16F : FrameOutputFormat.SDR_RGBA8);
        boolean useSuppliedBinary = suppliedBinary != null
                && !ser
                && !Boolean.getBoolean("top.ceroxe.rt.shader.surfaceOnly");
        if (!useSuppliedBinary && source == null && program.hasSpirv(stage)) {
            throw new IllegalArgumentException("missing precompiled scene shader variant for " + stage
                    + ": hdr=" + linearHdrOutput + ", ser=" + ser
                    + "; supply the requested binary or GLSL source");
        }
        byte[] spirv = useSuppliedBinary
                ? suppliedBinary
                : source == null
                ? RtShaderModuleCompiler.loadProduction(SHADER_ROOT + file, kind, linearHdrOutput, ser, RtEdgeSink.NOOP)
                : RtShaderModuleCompiler.compileSceneSource(source, file, kind, linearHdrOutput, ser);
        if (useSuppliedBinary || source != null) {
            VulkanSpirvBindingValidator.requireSceneInterface(
                    spirv, stage, program.dataBindingCount(), linearHdrOutput
            );
        }
        return spirv;
    }

    /**
     * Validates data before frame admission.
     *
     * @param values immutable per-binding payloads
     */
    public synchronized void validateShaderData(List<ResourceData> values) {
        requireOpen();
        if (values.size() != sceneShaders.dataBindingCount()) {
            throw new FrameValidationException(FrameValidationException.Reason.INVALID_SHADER_DATA,
                    "frame shader data count must equal declared dataBindingCount=" + sceneShaders.dataBindingCount());
        }
        for (ResourceData value : values) {
            if (value.byteSize() <= 0 || (value.byteSize() & 3) != 0 || value.byteSize() > maxStorageBufferRangeBytes) {
                throw new FrameValidationException(FrameValidationException.Reason.INVALID_SHADER_DATA,
                        "shader data must have a positive, 4-byte-aligned size no greater than device maxStorageBufferRange="
                                + maxStorageBufferRangeBytes);
            }
        }
    }

    /**
     * Computes peak additional allocation bytes.
     * @param slot available frame slot
     * @param values immutable per-binding payloads
     * @return additional bytes including temporary overlap
     */
    public synchronized long requiredShaderDataGrowth(int slot, List<ResourceData> values) {
        validateShaderData(values);
        return applicationData.requiredGrowth(slot, values);
    }

    /**
     * Uploads application data and updates the available slot's descriptors.
     * @param slot available frame slot
     * @param values immutable per-binding payloads
     */
    public synchronized void updateShaderData(int slot, List<ResourceData> values) {
        validateShaderData(values);
        applicationData.update(slot, descriptors.set(slot), values);
    }

    /**
     * Returns whether this pipeline contains the executable SER ray-generation permutation.
     *
     * @return {@code true} when the SER shader permutation was created
     */
    public synchronized boolean shaderExecutionReorderingEnabled() {
        requireOpen();
        return shaderExecutionReorderingEnabled;
    }

    private static void closeSuppressing(Throwable failure, AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    /**
     * 返回描述符集合数量。
     *
     * @return 正集合数量
     */
    public synchronized int descriptorSetCount() {
        requireOpen();
        return descriptors.setCount();
    }

    /**
     * 返回指定描述符槽的 Vulkan 句柄。
     *
     * @param index 描述符槽索引
     * @return 非零描述符集合句柄
     */
    public synchronized long descriptorSet(int index) {
        requireOpen();
        return descriptors.set(index);
    }

    /**
     * 返回共享描述符集合布局。
     *
     * @return 非零布局句柄
     */
    public synchronized long descriptorSetLayout() {
        requireOpen();
        return descriptors.layout();
    }

    public synchronized long maxStorageBufferRangeBytes() {
        requireOpen();
        return maxStorageBufferRangeBytes;
    }

    /**
     * Publishes one complete scene/frame resource generation into a bounded descriptor slot.
     *
     * @param index     bounded descriptor slot index
     * @param resources complete immutable descriptor resource generation
     */
    public synchronized void updateDescriptorSet(int index, GpuSceneDescriptorResources resources) {
        requireOpen();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GpuSceneDescriptorWriter.update(
                    stack, device, descriptors.set(index), maxStorageBufferRangeBytes, resources
            );
        }
    }

    /**
     * 在已满足图像依赖的命令缓冲区中记录一次光线派发。
     *
     * @param commandBuffer      记录目标命令缓冲区
     * @param stack              调用方拥有的临时栈
     * @param descriptorSetIndex 已发布描述符槽索引
     * @param width              正派发宽度
     * @param height             正派发高度
     */
    public synchronized void recordTrace(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            int descriptorSetIndex,
            int width,
            int height
    ) {
        requireOpen();
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        Objects.requireNonNull(stack, "stack");
        RtFrameDispatchCommands.recordTraceRays(
                commandBuffer,
                stack,
                pipelineLayout,
                pipeline,
                descriptors.set(descriptorSetIndex),
                shaderBindingTable.buffer(),
                shaderBindingTable.baseOffsetBytes(),
                shaderBindingTable.layout(),
                width,
                height
        );
    }

    /** Internal direct dispatch over one bounded tile, with original pixel identity in push data. */
    public synchronized void recordPrimaryQueueTrace(VkCommandBuffer commandBuffer, MemoryStack stack, int slot,
            long address, int firstPixel, int count) {
        recordPrimaryQueueTrace(commandBuffer, stack, slot, address, firstPixel, count, 0);
    }

    /** Records a tile whose primary hits and optional native attenuation already belong to Vulkan. */
    public synchronized void recordPrimaryQueueTrace(VkCommandBuffer commandBuffer, MemoryStack stack, int slot,
            long address, int firstPixel, int count, long transmittanceAddress) {
        recordPrimaryQueueTrace(commandBuffer, stack, slot, address, firstPixel, count, transmittanceAddress, 0);
    }

    /** Records native direct-scattering work with an optional sampled shader-clock buffer. */
    public synchronized void recordNativeScatteringLights(VkCommandBuffer commandBuffer,MemoryStack stack,int slot,
            long address,int firstPixel,int count,long offsets,long diagnosticAddress) {
        requireOpen();
        if(address==0||(address&15)!=0||(diagnosticAddress!=0&&(diagnosticAddress&15)!=0)
                ||firstPixel<0||count<1||(long)firstPixel+count>Integer.MAX_VALUE)
            throw new IllegalArgumentException("invalid native scattering tile or aligned device address");
        var push=stack.malloc(GpuScenePrimaryQueue.PUSH_BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        push.putLong(address).putInt(firstPixel).putInt(count).putLong(offsets).putLong(diagnosticAddress).flip();
        VK10.vkCmdPushConstants(commandBuffer,pipelineLayout,
                KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR|KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR,0,push);
        recordTrace(commandBuffer,stack,slot,count,1);
    }

    /** Records native transmittance and separately integrated camera-medium emission for this tile. */
    public synchronized void recordPrimaryQueueTrace(VkCommandBuffer commandBuffer, MemoryStack stack, int slot,
            long address, int firstPixel, int count, long transmittanceAddress, long emissionAddress) {
        requireOpen();
        // The low four attenuation bits are internal layout/publication tags. Actual
        // storage addresses and the independent emission address remain 16-byte aligned.
        if (address == 0 || (address & 15) != 0 || (emissionAddress & 15) != 0
                || firstPixel < 0 || count < 1 || (long) firstPixel + count > Integer.MAX_VALUE)
            throw new IllegalArgumentException("invalid primary tile extent or aligned device address");
        var push = stack.malloc(GpuScenePrimaryQueue.PUSH_BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        push.putLong(address).putInt(firstPixel).putInt(count).putLong(transmittanceAddress).putLong(emissionAddress).flip();
        VK10.vkCmdPushConstants(commandBuffer, pipelineLayout,
                KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                        | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR, 0, push);
        recordTrace(commandBuffer, stack, slot, count, 1);
    }

    /** Records the legacy shared-primary optical tile. */
    public synchronized void recordNativeOpticalTrace(VkCommandBuffer commandBuffer, MemoryStack stack, int slot,
            long storageAddress, int firstPixel, int count, boolean initialize) {
        recordNativeOpticalTrace(commandBuffer,stack,slot,storageAddress,0L,firstPixel,count,initialize);
    }

    /** Records an optical tile with an optional Vulkan-owned full primary and a tile-local initial-hit cache. */
    public synchronized void recordNativeOpticalTrace(VkCommandBuffer commandBuffer,MemoryStack stack,int slot,
            long storageAddress,long primaryAddress,int firstPixel,int count,boolean initialize) {
        requireOpen();
        if (storageAddress == 0 || (storageAddress & 15) != 0
                || (primaryAddress & 15) != 0
                || firstPixel < 0 || count < 1 || (long) firstPixel + count > Integer.MAX_VALUE)
            throw new IllegalArgumentException("invalid native optical tile extent or aligned storage address");
        var push = stack.malloc(GpuScenePrimaryQueue.PUSH_BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        push.putLong(storageAddress).putInt(firstPixel).putInt(count).putLong(initialize ? 1L : 0L).putLong(primaryAddress).flip();
        VK10.vkCmdPushConstants(commandBuffer, pipelineLayout,
                KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                        | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR, 0, push);
        recordTrace(commandBuffer, stack, slot, count, 1);
    }

    /** Native light paths retain the full geometric hit and share camera medium transport. */
    public synchronized void recordNativeOpticalPhotons(VkCommandBuffer commandBuffer,MemoryStack stack,int slot,
            long sceneAddress,long photonAddress,int count,boolean initialize) {
        requireOpen();
        if(sceneAddress==0||(sceneAddress&15)!=0||photonAddress==0||(photonAddress&15)!=0||count<1)
            throw new IllegalArgumentException("invalid native optical photon dispatch");
        var push=stack.malloc(GpuScenePrimaryQueue.PUSH_BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        push.putLong(sceneAddress).putInt(0).putInt(count).putLong(initialize?2L:3L).putLong(photonAddress).flip();
        VK10.vkCmdPushConstants(commandBuffer,pipelineLayout,
                KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR|KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR,0,push);
        recordTrace(commandBuffer,stack,slot,Math.min(count,1024),(count+1023)/1024);
    }

    /** Packet boundary producer writes compact continuation state to shared memory and full hits to Vulkan storage. */
    public synchronized void recordBoundaryPacketTrace(VkCommandBuffer commandBuffer,MemoryStack stack,int slot,
            long sharedAddress,long primaryAddress,int firstPixel,int count,long offsets) {
        requireOpen();
        if(sharedAddress==0||(sharedAddress&15)!=0||primaryAddress==0||(primaryAddress&15)!=0
                ||firstPixel<0||count<1||(long)firstPixel+count>Integer.MAX_VALUE||offsets<0||(offsets&15)!=0)
            throw new IllegalArgumentException("invalid native boundary packet dispatch");
        var push=stack.malloc(GpuScenePrimaryQueue.PUSH_BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        push.putLong(sharedAddress).putInt(firstPixel).putInt(count).putLong(primaryAddress).putLong(offsets).flip();
        VK10.vkCmdPushConstants(commandBuffer,pipelineLayout,
                KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                        | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR,0,push);
        recordTrace(commandBuffer,stack,slot,count,1);
    }

    /** Arbitrary-origin scatter rays write ordered volume packets consumed entirely by native CUDA. */
    public synchronized void recordNativeBoundaryScattering(VkCommandBuffer commandBuffer,MemoryStack stack,int slot,
            long storageAddress,int firstQuery,int count) {
        requireOpen();
        if(storageAddress==0||(storageAddress&15)!=0||firstQuery<0||count<1||(long)firstQuery+count>Integer.MAX_VALUE)
            throw new IllegalArgumentException("invalid native boundary scattering query extent or storage address");
        var push=stack.malloc(GpuScenePrimaryQueue.PUSH_BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        push.putLong(storageAddress).putInt(firstQuery).putInt(count).putLong(0).putLong(0).flip();
        VK10.vkCmdPushConstants(commandBuffer,pipelineLayout,
                KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR|KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR,0,push);
        recordTrace(commandBuffer,stack,slot,count,1);
    }

    /**
     * Records the output-image dependency and the ray dispatch as one auditable command unit.
     *
     * @param commandBuffer            recording target
     * @param stack                    caller-owned temporary stack
     * @param descriptorSetIndex       published descriptor slot index
     * @param outputImage              dispatch output image
     * @param publishedOutputImage     externally visible output destination
     * @param cpuReadback              optional slot-owned asynchronous readback destination
     * @param temporalImages           complete temporal images and their committed layout state
     * @param previousImageLayout      layout before this frame
     * @param acquireExternalOwnership whether to acquire from the external queue family
     * @param releaseExternalOwnership whether to release to the external queue family after trace
     * @param producerQueueFamilyIndex Vulkan producer queue family
     * @param width                    positive dispatch width
     * @param height                   positive dispatch height
     */
    public synchronized void recordFrame(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            int descriptorSetIndex,
            RtGpuImage outputImage,
            RtGpuImage publishedOutputImage,
            RtGpuBuffer cpuReadback,
            GpuSceneTemporalFrameResources temporalImages,
            int previousImageLayout,
            boolean acquireExternalOwnership,
            boolean releaseExternalOwnership,
            int producerQueueFamilyIndex,
            int width,
            int height
    ) {
        recordFrame(
                commandBuffer,
                stack,
                descriptorSetIndex,
                outputImage,
                publishedOutputImage,
                publishedOutputImage,
                cpuReadback,
                temporalImages,
                previousImageLayout,
                false,
                acquireExternalOwnership,
                releaseExternalOwnership,
                producerQueueFamilyIndex,
                VulkanFrameExtents.identity(width, height),
                VulkanFeatureSession.disabled(),
                Optional.empty(),
                false,
                Optional.empty(),
                false,
                false,
                Optional.empty(),
                false,
                Optional.empty(),
                0L,
                0L,
                false,
                () -> { },
                () -> { }
        );
    }

    /**
     * Records RT work and invokes the optional feature boundary before publication.
     *
     * @param commandBuffer command recording target
     * @param stack caller-owned temporary allocation stack
     * @param descriptorSetIndex descriptor generation index
     * @param outputImage internal ray-tracing output image
     * @param publishedOutputImage externally visible output destination
     * @param cpuReadback optional CPU readback destination
     * @param temporalImages temporal history resources
     * @param previousImageLayout previous output layout
     * @param traceLayoutInitialized whether the trace output has an established layout
     * @param acquireExternalOwnership whether external ownership is acquired
     * @param releaseExternalOwnership whether external ownership is released
     * @param producerQueueFamilyIndex producer queue family
     * @param width dispatch width
     * @param height dispatch height
     * @param featureSession composite optional feature session
     * @param denoisingResources optional validated denoising resource contract
     * @param denoisingLayoutsInitialized whether denoising images have established layouts
     * @param frameSequence non-negative frame identity
     * @param sceneRevision non-negative scene identity
     * @param historyReset whether temporal history must be discarded
     * @param postTraceComposition renderer-owned composition recorded after feature work
     */
    public synchronized void recordFrame(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            int descriptorSetIndex,
            RtGpuImage outputImage,
            RtGpuImage publishedOutputImage,
            RtGpuBuffer cpuReadback,
            GpuSceneTemporalFrameResources temporalImages,
            int previousImageLayout,
            boolean traceLayoutInitialized,
            boolean acquireExternalOwnership,
            boolean releaseExternalOwnership,
            int producerQueueFamilyIndex,
            int width,
            int height,
            VulkanFeatureSession featureSession,
            Optional<VulkanDenoisingResourceContract> denoisingResources,
            boolean denoisingLayoutsInitialized,
            long frameSequence,
            long sceneRevision,
            boolean historyReset,
            Runnable postTraceComposition
    ) {
        recordFrame(
                commandBuffer,
                stack,
                descriptorSetIndex,
                outputImage,
                publishedOutputImage,
                publishedOutputImage,
                cpuReadback,
                temporalImages,
                previousImageLayout,
                traceLayoutInitialized,
                acquireExternalOwnership,
                releaseExternalOwnership,
                producerQueueFamilyIndex,
                VulkanFrameExtents.identity(width, height),
                featureSession,
                denoisingResources,
                denoisingLayoutsInitialized,
                Optional.empty(),
                false,
                false,
                Optional.empty(),
                false,
                Optional.empty(),
                frameSequence,
                sceneRevision,
                historyReset,
                postTraceComposition,
                () -> { }
        );
    }

    /**
     * Records RT work over an internal render extent, then publishes at the requested output extent.
     *
     * <p>The separate dimensions are intentional: a reconstruction feature observes an internal
     * ray-traced image and writes the externally visible output image. Existing callers retain the
     * identity-extent overload above until they negotiate reconstruction settings.</p>
     *
     * @param commandBuffer command recording target
     * @param stack caller-owned temporary allocation stack
     * @param descriptorSetIndex descriptor generation index
     * @param outputImage internal ray-tracing output image
     * @param reconstructionOutputImage reconstruction destination; aliases the public output for
     *        direct HDR publication and is private RGBA16F for SDR publication
     * @param publishedOutputImage externally visible output destination
     * @param cpuReadback optional CPU readback destination
     * @param temporalImages temporal history resources
     * @param previousImageLayout prior published-output layout
     * @param traceLayoutInitialized whether the trace output has an established layout
     * @param acquireExternalOwnership whether external ownership is acquired
     * @param releaseExternalOwnership whether external ownership is released after publication
     * @param producerQueueFamilyIndex producer queue family
     * @param extents paired internal render and published output dimensions
     * @param featureSession composite optional feature session
     * @param denoisingResources optional validated denoising resource contract
     * @param denoisingLayoutsInitialized whether denoising images have established layouts
     * @param reconstructionResources optional validated reconstruction resource contract
     * @param reconstructionLayoutsInitialized whether reconstruction images have established layouts
     * @param reconstructionOutputLayoutInitialized whether a private reconstruction destination has
     *        an established layout
     * @param frameGenerationResources optional validated presentation-time generation inputs
     * @param frameGenerationLayoutsInitialized whether frame-generation images have established layouts
     * @param temporalInput optional exact camera facts shared by native temporal integrations
     * @param frameSequence non-negative frame identity
     * @param sceneRevision non-negative scene identity
     * @param historyReset whether temporal history must be discarded
     * @param postTraceComposition renderer-owned composition recorded after feature work
     * @param outputPublication renderer-owned conversion from a private reconstruction destination
     *        into the requested public output format
     */
    public synchronized void recordFrame(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            int descriptorSetIndex,
            RtGpuImage outputImage,
            RtGpuImage reconstructionOutputImage,
            RtGpuImage publishedOutputImage,
            RtGpuBuffer cpuReadback,
            GpuSceneTemporalFrameResources temporalImages,
            int previousImageLayout,
            boolean traceLayoutInitialized,
            boolean acquireExternalOwnership,
            boolean releaseExternalOwnership,
            int producerQueueFamilyIndex,
            VulkanFrameExtents extents,
            VulkanFeatureSession featureSession,
            Optional<VulkanDenoisingResourceContract> denoisingResources,
            boolean denoisingLayoutsInitialized,
            Optional<VulkanFrameReconstructionResourceContract> reconstructionResources,
            boolean reconstructionLayoutsInitialized,
            boolean reconstructionOutputLayoutInitialized,
            Optional<VulkanFrameGenerationResourceContract> frameGenerationResources,
            boolean frameGenerationLayoutsInitialized,
            Optional<VulkanTemporalFrameInput> temporalInput,
            long frameSequence,
            long sceneRevision,
            boolean historyReset,
            Runnable postTraceComposition,
            Runnable outputPublication
    ) {
        recordFrame(commandBuffer, stack, descriptorSetIndex, outputImage, reconstructionOutputImage,
                publishedOutputImage, cpuReadback, temporalImages, previousImageLayout, traceLayoutInitialized,
                acquireExternalOwnership, releaseExternalOwnership, producerQueueFamilyIndex, extents, featureSession,
                denoisingResources, denoisingLayoutsInitialized, reconstructionResources, reconstructionLayoutsInitialized,
                reconstructionOutputLayoutInitialized, frameGenerationResources, frameGenerationLayoutsInitialized,
                temporalInput, frameSequence, sceneRevision, historyReset, postTraceComposition, outputPublication, null);
    }

    /** Same frame ownership/layout transaction with an internal multi-stage trace implementation. */
    public synchronized void recordFrame(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            int descriptorSetIndex,
            RtGpuImage outputImage,
            RtGpuImage reconstructionOutputImage,
            RtGpuImage publishedOutputImage,
            RtGpuBuffer cpuReadback,
            GpuSceneTemporalFrameResources temporalImages,
            int previousImageLayout,
            boolean traceLayoutInitialized,
            boolean acquireExternalOwnership,
            boolean releaseExternalOwnership,
            int producerQueueFamilyIndex,
            VulkanFrameExtents extents,
            VulkanFeatureSession featureSession,
            Optional<VulkanDenoisingResourceContract> denoisingResources,
            boolean denoisingLayoutsInitialized,
            Optional<VulkanFrameReconstructionResourceContract> reconstructionResources,
            boolean reconstructionLayoutsInitialized,
            boolean reconstructionOutputLayoutInitialized,
            Optional<VulkanFrameGenerationResourceContract> frameGenerationResources,
            boolean frameGenerationLayoutsInitialized,
            Optional<VulkanTemporalFrameInput> temporalInput,
            long frameSequence,
            long sceneRevision,
            boolean historyReset,
            Runnable postTraceComposition,
            Runnable outputPublication,
            Runnable traceCommands
    ) {
        requireOpen();
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        Objects.requireNonNull(stack, "stack");
        RtGpuImage image = Objects.requireNonNull(outputImage, "outputImage");
        RtGpuImage reconstructionImage = Objects.requireNonNull(
                reconstructionOutputImage, "reconstructionOutputImage"
        );
        RtGpuImage publishedImage = Objects.requireNonNull(publishedOutputImage, "publishedOutputImage");
        GpuSceneTemporalFrameResources temporal = Objects.requireNonNull(
                temporalImages, "temporalImages"
        );
        VulkanFeatureSession checkedFeatureSession = Objects.requireNonNull(featureSession, "featureSession");
        Runnable checkedPostTraceComposition = Objects.requireNonNull(postTraceComposition, "postTraceComposition");
        Runnable checkedOutputPublication = Objects.requireNonNull(outputPublication, "outputPublication");
        Optional<VulkanDenoisingResourceContract> checkedDenoisingResources = Objects.requireNonNull(
                denoisingResources, "denoisingResources"
        );
        Optional<VulkanFrameReconstructionResourceContract> checkedReconstructionResources = Objects.requireNonNull(
                reconstructionResources, "reconstructionResources"
        );
        Optional<VulkanFrameGenerationResourceContract> checkedFrameGenerationResources = Objects.requireNonNull(
                frameGenerationResources, "frameGenerationResources"
        );
        Optional<VulkanTemporalFrameInput> checkedTemporalInput = Objects.requireNonNull(
                temporalInput, "temporalInput"
        );
        VulkanFrameExtents checkedExtents = Objects.requireNonNull(extents, "extents");
        if (denoisingLayoutsInitialized && checkedDenoisingResources.isEmpty()) {
            throw new IllegalArgumentException("initialized denoising layouts require denoising resources");
        }
        if (reconstructionLayoutsInitialized && checkedReconstructionResources.isEmpty()) {
            throw new IllegalArgumentException("initialized reconstruction layouts require reconstruction resources");
        }
        boolean privateReconstructionOutput = reconstructionImage.image() != publishedImage.image();
        if (privateReconstructionOutput && checkedReconstructionResources.isEmpty()) {
            throw new IllegalArgumentException(
                    "private reconstruction output requires active reconstruction resources"
            );
        }
        if (frameGenerationLayoutsInitialized && checkedFrameGenerationResources.isEmpty()) {
            throw new IllegalArgumentException("initialized frame-generation layouts require resources");
        }
        boolean separateTraceOutput = image.image() != publishedImage.image();
        if (separateTraceOutput != (checkedDenoisingResources.isPresent() || checkedReconstructionResources.isPresent())) {
            throw new IllegalArgumentException(
                    "separate trace output requires at least one active denoising or reconstruction resource contract"
            );
        }
        int renderWidth = checkedExtents.renderWidth();
        int renderHeight = checkedExtents.renderHeight();
        int outputWidth = checkedExtents.outputWidth();
        int outputHeight = checkedExtents.outputHeight();
        if (image.width() != renderWidth || image.height() != renderHeight
                || reconstructionImage.width() != outputWidth
                || reconstructionImage.height() != outputHeight
                || publishedImage.width() != outputWidth || publishedImage.height() != outputHeight) {
            throw new IllegalArgumentException("trace and published output images do not match their frame extents");
        }
        if (producerQueueFamilyIndex < 0) {
            throw new IllegalArgumentException("producerQueueFamilyIndex must not be negative");
        }
        if (frameSequence < 0L || sceneRevision < 0L) {
            throw new IllegalArgumentException("feature frame identity must not be negative");
        }
        int sourceAccess;
        int sourceStage;
        int sourceQueueFamilyIndex;
        int destinationQueueFamilyIndex;
        if (previousImageLayout == VK10.VK_IMAGE_LAYOUT_UNDEFINED) {
            if (acquireExternalOwnership) {
                throw new IllegalArgumentException("an undefined image cannot be externally owned");
            }
            sourceAccess = 0;
            sourceStage = VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            sourceQueueFamilyIndex = VK10.VK_QUEUE_FAMILY_IGNORED;
            destinationQueueFamilyIndex = VK10.VK_QUEUE_FAMILY_IGNORED;
        } else if (previousImageLayout == VK10.VK_IMAGE_LAYOUT_GENERAL) {
            sourceAccess = acquireExternalOwnership ? 0 : VK10.VK_ACCESS_SHADER_WRITE_BIT;
            sourceStage = acquireExternalOwnership
                    ? VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
                    : org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
            sourceQueueFamilyIndex = acquireExternalOwnership
                    ? VK11.VK_QUEUE_FAMILY_EXTERNAL
                    : VK10.VK_QUEUE_FAMILY_IGNORED;
            destinationQueueFamilyIndex = acquireExternalOwnership
                    ? producerQueueFamilyIndex
                    : VK10.VK_QUEUE_FAMILY_IGNORED;
        } else {
            throw new IllegalArgumentException("unsupported GPUScene output layout " + previousImageLayout);
        }
        int rayTracingStage = org.lwjgl.vulkan.KHRRayTracingPipeline
                .VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
        int composeStage = VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        if (separateTraceOutput) {
            /* The trace image never crosses the external ownership boundary. */
            RtFrameDispatchCommands.recordImageLayoutTransition(
                    commandBuffer,
                    stack,
                    image.image(),
                    traceLayoutInitialized ? VK10.VK_IMAGE_LAYOUT_GENERAL : VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                    VK10.VK_IMAGE_LAYOUT_GENERAL,
                    traceLayoutInitialized ? VK10.VK_ACCESS_SHADER_READ_BIT : 0,
                    VK10.VK_ACCESS_SHADER_WRITE_BIT,
                    traceLayoutInitialized ? composeStage : VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    rayTracingStage,
                    VK10.VK_QUEUE_FAMILY_IGNORED,
                    VK10.VK_QUEUE_FAMILY_IGNORED
            );
        }
        RtFrameDispatchCommands.recordImageLayoutTransition(
                commandBuffer,
                stack,
                publishedImage.image(),
                previousImageLayout,
                VK10.VK_IMAGE_LAYOUT_GENERAL,
                sourceAccess,
                checkedReconstructionResources.isPresent()
                        ? VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT
                        : VK10.VK_ACCESS_SHADER_WRITE_BIT,
                sourceStage,
                checkedReconstructionResources.isPresent()
                        ? VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT
                        : separateTraceOutput ? composeStage : rayTracingStage,
                sourceQueueFamilyIndex,
                destinationQueueFamilyIndex
        );
        if (privateReconstructionOutput) {
            RtFrameDispatchCommands.recordImageLayoutTransition(
                    commandBuffer,
                    stack,
                    reconstructionImage.image(),
                    reconstructionOutputLayoutInitialized
                            ? VK10.VK_IMAGE_LAYOUT_GENERAL : VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                    VK10.VK_IMAGE_LAYOUT_GENERAL,
                    reconstructionOutputLayoutInitialized ? VK10.VK_ACCESS_MEMORY_WRITE_BIT : 0,
                    VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT,
                    reconstructionOutputLayoutInitialized
                            ? VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT
                            : VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK10.VK_QUEUE_FAMILY_IGNORED,
                    VK10.VK_QUEUE_FAMILY_IGNORED
            );
        }
        transitionTemporalImage(
                commandBuffer, stack, temporal.colorInput(), temporal.inputLayoutInitialized(),
                VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT
        );
        transitionTemporalImage(
                commandBuffer, stack, temporal.geometryInput(), temporal.inputLayoutInitialized(),
                VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT
        );
        transitionTemporalImage(
                commandBuffer, stack, temporal.colorOutput(), temporal.outputLayoutInitialized(),
                VK10.VK_ACCESS_SHADER_READ_BIT, VK10.VK_ACCESS_SHADER_WRITE_BIT
        );
        transitionTemporalImage(
                commandBuffer, stack, temporal.geometryOutput(), temporal.outputLayoutInitialized(),
                VK10.VK_ACCESS_SHADER_READ_BIT, VK10.VK_ACCESS_SHADER_WRITE_BIT
        );
        transitionTemporalImage(
                commandBuffer, stack, temporal.motionOutput(), temporal.motionLayoutInitialized(),
                VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_SHADER_WRITE_BIT
        );
        checkedDenoisingResources.ifPresent(resources -> prepareDenoisingResources(
                commandBuffer, stack, resources, denoisingLayoutsInitialized
        ));
        checkedReconstructionResources.ifPresent(resources -> prepareReconstructionResources(
                commandBuffer, stack, resources, reconstructionLayoutsInitialized
        ));
        if (checkedDenoisingResources.isPresent() && checkedReconstructionResources.isPresent()) {
            // NRD writes the reconstruction input after ray tracing. It has its own layout
            // transition because the reconstruction contract is no longer the trace target.
            transitionReconstructionImage(
                    commandBuffer, stack, checkedReconstructionResources.orElseThrow().inputColor(),
                    reconstructionLayoutsInitialized,
                    VK10.VK_ACCESS_SHADER_WRITE_BIT,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
            );
        }
        checkedFrameGenerationResources.ifPresent(resources -> prepareFrameGenerationResources(
                commandBuffer, stack, resources, frameGenerationLayoutsInitialized
        ));
        if (traceCommands == null) recordTrace(commandBuffer, stack, descriptorSetIndex, renderWidth, renderHeight);
        else traceCommands.run();
        checkedDenoisingResources.ifPresent(resources -> makeDenoisingSignalsAvailable(
                commandBuffer, stack, resources
        ));
        checkedReconstructionResources.ifPresent(resources -> makeReconstructionInputsAvailable(
                commandBuffer, stack, resources
        ));
        checkedFrameGenerationResources.ifPresent(resources -> makeFrameGenerationInputsAvailable(
                commandBuffer, stack, resources
        ));
        VulkanFeatureFrameContext featureContext = new VulkanFeatureFrameContext(
                commandBuffer,
                stack,
                image,
                reconstructionImage,
                publishedImage,
                temporal,
                checkedDenoisingResources,
                checkedReconstructionResources,
                checkedFrameGenerationResources,
                checkedTemporalInput,
                frameSequence,
                sceneRevision,
                historyReset,
                checkedExtents
        );
        if (checkedDenoisingResources.isPresent()) {
            makeTraceOutputAvailableForCompose(commandBuffer, stack, image);
        }
        // Keep the complete post-trace sequence inside one session boundary. If any optional
        // provider fails, RtCommandContext discards this unsubmitted recording and the output
        // image cannot become visible as a half-written frame.
        checkedFeatureSession.recordPostTrace(featureContext, () -> {
            checkedDenoisingResources.ifPresent(resources ->
                    makeDenoisingOutputsAvailableForCompose(commandBuffer, stack, resources));
            checkedPostTraceComposition.run();
        });
        checkedOutputPublication.run();
        checkedFeatureSession.recordFrameGeneration(featureContext);
        int publicationSourceStage = checkedReconstructionResources.isPresent()
                ? privateReconstructionOutput
                ? VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                : VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT
                : separateTraceOutput ? composeStage : rayTracingStage;
        int publicationSourceAccess = checkedReconstructionResources.isPresent()
                ? VK10.VK_ACCESS_MEMORY_WRITE_BIT
                : VK10.VK_ACCESS_SHADER_WRITE_BIT;
        if (cpuReadback != null) {
            recordCpuReadback(
                    commandBuffer, stack, publishedImage, cpuReadback, outputWidth, outputHeight,
                    publicationSourceAccess, publicationSourceStage
            );
        }
        if (releaseExternalOwnership) {
            /* Expert external interop requires a real ownership release, not fence-only ordering. */
            RtFrameDispatchCommands.recordImageLayoutTransition(
                    commandBuffer,
                    stack,
                    publishedImage.image(),
                    cpuReadback == null
                            ? VK10.VK_IMAGE_LAYOUT_GENERAL
                            : VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK10.VK_IMAGE_LAYOUT_GENERAL,
                    cpuReadback == null
                            ? publicationSourceAccess
                            : VK10.VK_ACCESS_TRANSFER_READ_BIT,
                    0,
                    cpuReadback == null
                            ? publicationSourceStage
                            : VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                    producerQueueFamilyIndex,
                    VK11.VK_QUEUE_FAMILY_EXTERNAL
            );
        } else if (cpuReadback != null) {
            /* Readback changes layout; managed presentation still consumes a GENERAL image. */
            RtFrameDispatchCommands.recordImageLayoutTransition(
                    commandBuffer, stack, publishedImage.image(),
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    VK10.VK_ACCESS_TRANSFER_READ_BIT, VK10.VK_ACCESS_TRANSFER_READ_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_QUEUE_FAMILY_IGNORED, VK10.VK_QUEUE_FAMILY_IGNORED
            );
        }
    }

    private static void recordCpuReadback(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            RtGpuImage outputImage,
            RtGpuBuffer readback,
            int width,
            int height,
            int sourceAccess,
            int sourceStage
    ) {
        RtFrameDispatchCommands.recordImageLayoutTransition(
                commandBuffer,
                stack,
                outputImage.image(),
                VK10.VK_IMAGE_LAYOUT_GENERAL,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                sourceAccess,
                VK10.VK_ACCESS_TRANSFER_READ_BIT,
                sourceStage,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK10.VK_QUEUE_FAMILY_IGNORED,
                VK10.VK_QUEUE_FAMILY_IGNORED
        );

        VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
        copy.get(0)
                .bufferOffset(0L)
                .bufferRowLength(0)
                .bufferImageHeight(0);
        copy.get(0).imageSubresource()
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);
        copy.get(0).imageOffset().set(0, 0, 0);
        copy.get(0).imageExtent().set(width, height, 1);
        VK10.vkCmdCopyImageToBuffer(
                commandBuffer,
                outputImage.image(),
                VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                readback.buffer(),
                copy
        );

        VkBufferMemoryBarrier.Buffer hostRead = VkBufferMemoryBarrier.calloc(1, stack);
        hostRead.get(0)
                .sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .buffer(readback.buffer())
                .offset(0L)
                .size(readback.sizeBytes());
        VK10.vkCmdPipelineBarrier(
                commandBuffer,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK10.VK_PIPELINE_STAGE_HOST_BIT,
                0,
                null,
                hostRead,
                null
        );
    }

    /**
     * 返回不持有额外原生资源的管线诊断快照。
     *
     * @return 当前管线形状快照
     */
    public synchronized Snapshot snapshot() {
        requireOpen();
        return new Snapshot(
                descriptors.setCount(),
                pipelineLayout,
                pipeline,
                properties.maxRayRecursionDepth(),
                maxStorageBufferRangeBytes,
                shaderBindingTable.layout().strideBytes(),
                shaderBindingTable.layout().totalBytes()
        );
    }

    /**
     * Releases shader binding, pipeline, and layout resources in dependency order.
     *
     * @throws RuntimeException if an owned native resource cannot be released
     */
    @Override
    public synchronized void close() {
        if (closed) return;
        closing = true;
        RuntimeException failure = null;
        try {
            shaderBindingTable.close();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        }
        try { applicationData.close(); }
        catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure; else failure.addSuppressed(closeFailure);
        }
        if (!pipelineDestroyed) {
            VK10.vkDestroyPipeline(device, pipeline, null);
            pipelineDestroyed = true;
        }
        if (!layoutDestroyed) {
            VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
            layoutDestroyed = true;
        }
        try {
            descriptors.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        if (failure != null) throw failure;
        closed = true;
    }

    private void requireOpen() {
        if (closed || closing) throw new IllegalStateException("GPUScene ray tracing pipeline is closed");
    }

    /**
     * GPUScene 光追管线的不可变原生形状快照。
     *
     * @param descriptorSetCount            描述符集合数量
     * @param pipelineLayout                管线布局句柄
     * @param pipeline                      光追管线句柄
     * @param maxRayRecursionDepth          设备最大递归深度
     * @param maxStorageBufferRangeBytes    最大存储缓冲区范围
     * @param shaderBindingTableStrideBytes SBT 记录步幅
     * @param shaderBindingTableBytes       SBT 总字节数
     */
    public record Snapshot(
            int descriptorSetCount,
            long pipelineLayout,
            long pipeline,
            int maxRayRecursionDepth,
            long maxStorageBufferRangeBytes,
            int shaderBindingTableStrideBytes,
            int shaderBindingTableBytes
    ) {
        /**
         * 校验所有快照维度与句柄有效。
         */
        public Snapshot {
            if (descriptorSetCount <= 0 || pipelineLayout == 0L || pipeline == 0L
                    || maxRayRecursionDepth <= 0 || maxStorageBufferRangeBytes <= 0L
                    || shaderBindingTableStrideBytes <= 0
                    || shaderBindingTableBytes <= 0) {
                throw new IllegalArgumentException("GPUScene pipeline snapshot is invalid");
            }
        }
    }
}

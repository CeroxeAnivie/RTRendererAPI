package top.ceroxe.rt.renderer.rt.pipeline;

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
    private static final String SHADER_ROOT = "assets/rtrenderer/shaders/gpuscene/";

    private final VkDevice device;
    private final GpuSceneDescriptorLayout descriptors;
    private final long pipelineLayout;
    private final long pipeline;
    private final RtShaderBindingTable shaderBindingTable;
    private final RtRayTracingPipelineProperties properties;
    private final long maxStorageBufferRangeBytes;
    private final boolean shaderExecutionReorderingEnabled;
    private boolean closed;

    private GpuSceneRayTracingPipeline(
            VkDevice device,
            GpuSceneDescriptorLayout descriptors,
            long pipelineLayout,
            long pipeline,
            RtShaderBindingTable shaderBindingTable,
            RtRayTracingPipelineProperties properties,
            long maxStorageBufferRangeBytes,
            boolean shaderExecutionReorderingEnabled
    ) {
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
        VulkanDeviceRuntime checkedRuntime = Objects.requireNonNull(runtime, "runtime");
        boolean shaderExecutionReorderingEnabled = checkedRuntime.shaderExecutionReorderingEnabled();
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
        try (MemoryStack stack = MemoryStack.stackPush()) {
            RtRayTracingPipelineProperties properties = RtRayTracingPipelineProperties.query(
                    stack, checkedRuntime.physicalDevice()
            );
            VkPhysicalDeviceProperties physicalDeviceProperties = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(checkedRuntime.physicalDevice(), physicalDeviceProperties);
            long maxStorageBufferRangeBytes = Integer.toUnsignedLong(
                    physicalDeviceProperties.limits().maxStorageBufferRange()
            );
            if (properties.maxRayRecursionDepth() < 1) {
                throw new IllegalStateException("device cannot execute the depth-one GPUScene pipeline");
            }
            descriptors = GpuSceneDescriptorLayout.create(stack, device, descriptorSetCount);
            pipelineLayout = RtRayTracingPipelineFactory.createPipelineLayout(stack, device, descriptors.layout());
            raygenModule = compileModule(
                    stack, device, "gpuscene.rgen", Shaderc.shaderc_raygen_shader,
                    linearHdrOutput, shaderExecutionReorderingEnabled
            );
            missModule = compileModule(stack, device, "gpuscene.rmiss", Shaderc.shaderc_miss_shader);
            closestHitModule = compileModule(
                    stack, device, "gpuscene.rchit", Shaderc.shaderc_closesthit_shader
            );
            anyHitModule = compileModule(stack, device, "gpuscene.rahit", Shaderc.shaderc_anyhit_shader);
            try (RtPipelineCache cache = RtPipelineCache.open(
                    device, checkedRuntime.physicalDevice(), "gpuscene")) {
                pipeline = RtRayTracingPipelineFactory.createPipeline(
                        stack, device, pipelineLayout,
                        raygenModule, missModule, closestHitModule, anyHitModule,
                        cache.handle()
                );
            }
            byte[] groupHandles = RtRayTracingPipelineFactory.queryShaderGroupHandles(properties, device, pipeline);
            RtRayTracingPipelineProperties.ShaderBindingTableData packed = properties.packShaderGroupHandles(
                    groupHandles,
                    RtRayTracingPipelineFactory.RAYGEN_GROUPS,
                    RtRayTracingPipelineFactory.MISS_GROUPS,
                    RtRayTracingPipelineFactory.HIT_GROUPS,
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
                    maxStorageBufferRangeBytes, shaderExecutionReorderingEnabled
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

    private static long compileModule(MemoryStack stack, VkDevice device, String file, int kind) {
        return compileModule(stack, device, file, kind, false);
    }

    private static long compileModule(
            MemoryStack stack,
            VkDevice device,
            String file,
            int kind,
            boolean linearHdrOutput
    ) {
        return compileModule(stack, device, file, kind, linearHdrOutput, false);
    }

    private static long compileModule(
            MemoryStack stack,
            VkDevice device,
            String file,
            int kind,
            boolean linearHdrOutput,
            boolean shaderExecutionReorderingEnabled
    ) {
        return RtShaderModuleCompiler.createModule(
                stack,
                device,
                RtShaderModuleCompiler.loadProduction(
                        SHADER_ROOT + file, kind, linearHdrOutput,
                        shaderExecutionReorderingEnabled, RtEdgeSink.NOOP
                )
        );
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
        recordTrace(commandBuffer, stack, descriptorSetIndex, renderWidth, renderHeight);
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
        closed = true;
        RuntimeException failure = null;
        try {
            shaderBindingTable.close();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        }
        VK10.vkDestroyPipeline(device, pipeline, null);
        VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
        try {
            descriptors.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        if (failure != null) throw failure;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("GPUScene ray tracing pipeline is closed");
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

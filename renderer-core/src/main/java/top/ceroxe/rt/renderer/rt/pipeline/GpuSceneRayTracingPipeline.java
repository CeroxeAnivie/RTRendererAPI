package top.ceroxe.rt.renderer.rt.pipeline;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.RtEdgeSink;
import top.ceroxe.rt.renderer.rt.device.RtGpuImage;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

import java.util.Objects;

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
    private boolean closed;

    private GpuSceneRayTracingPipeline(
            VkDevice device,
            GpuSceneDescriptorLayout descriptors,
            long pipelineLayout,
            long pipeline,
            RtShaderBindingTable shaderBindingTable,
            RtRayTracingPipelineProperties properties,
            long maxStorageBufferRangeBytes
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
                    stack, device, "gpuscene.rgen", Shaderc.shaderc_raygen_shader, linearHdrOutput
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
                    maxStorageBufferRangeBytes
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
        return RtShaderModuleCompiler.createModule(
                stack,
                device,
                RtShaderModuleCompiler.loadProduction(
                        SHADER_ROOT + file, kind, linearHdrOutput, RtEdgeSink.NOOP
                )
        );
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
     * @param temporalImages           complete temporal images and their committed layout state
     * @param previousImageLayout      layout before this frame
     * @param acquireExternalOwnership whether to acquire from the external queue family
     * @param producerQueueFamilyIndex Vulkan producer queue family
     * @param width                    positive dispatch width
     * @param height                   positive dispatch height
     */
    public synchronized void recordFrame(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            int descriptorSetIndex,
            RtGpuImage outputImage,
            GpuSceneTemporalFrameResources temporalImages,
            int previousImageLayout,
            boolean acquireExternalOwnership,
            int producerQueueFamilyIndex,
            int width,
            int height
    ) {
        requireOpen();
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        Objects.requireNonNull(stack, "stack");
        RtGpuImage image = Objects.requireNonNull(outputImage, "outputImage");
        GpuSceneTemporalFrameResources temporal = Objects.requireNonNull(
                temporalImages, "temporalImages"
        );
        if (image.width() != width || image.height() != height) {
            throw new IllegalArgumentException("dispatch extent must equal the output-image extent");
        }
        if (producerQueueFamilyIndex < 0) {
            throw new IllegalArgumentException("producerQueueFamilyIndex must not be negative");
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
        RtFrameDispatchCommands.recordImageLayoutTransition(
                commandBuffer,
                stack,
                image.image(),
                previousImageLayout,
                VK10.VK_IMAGE_LAYOUT_GENERAL,
                sourceAccess,
                VK10.VK_ACCESS_SHADER_WRITE_BIT,
                sourceStage,
                org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                sourceQueueFamilyIndex,
                destinationQueueFamilyIndex
        );
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
        recordTrace(commandBuffer, stack, descriptorSetIndex, width, height);
        /*
         * The exported image uses EXCLUSIVE sharing. A producer fence proves execution
         * completion, but it does not transfer queue-family ownership to another device
         * or graphics API. Release every published frame to the external family; the
         * next reuse performs the matching acquire before the first shader write.
         */
        RtFrameDispatchCommands.recordImageLayoutTransition(
                commandBuffer,
                stack,
                image.image(),
                VK10.VK_IMAGE_LAYOUT_GENERAL,
                VK10.VK_IMAGE_LAYOUT_GENERAL,
                VK10.VK_ACCESS_SHADER_WRITE_BIT,
                0,
                org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                producerQueueFamilyIndex,
                VK11.VK_QUEUE_FAMILY_EXTERNAL
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

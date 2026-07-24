package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import top.ceroxe.mcvulkanrt.renderer.RtEdgeSink;
import top.ceroxe.mcvulkanrt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtGpuImage;

import java.util.Objects;

/**
 * Immutable generic GPUScene RT pipeline and descriptor-set bank.
 *
 * <p>This owner contains no scene, frame, or source-engine state. Shader modules are temporary
 * construction inputs, the SBT owns its uploaded device buffer, and all Vulkan handles are torn
 * down in reverse dependency order after frame submissions have completed.</p>
 */
public final class GpuSceneRayTracingPipeline implements AutoCloseable {
    private static final String SHADER_ROOT = "assets/mcvulkanrt/shaders/gpuscene/";

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

    public static GpuSceneRayTracingPipeline open(VulkanDeviceRuntime runtime, int descriptorSetCount) {
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
            raygenModule = compileModule(stack, device, "gpuscene.rgen", Shaderc.shaderc_raygen_shader);
            missModule = compileModule(stack, device, "gpuscene.rmiss", Shaderc.shaderc_miss_shader);
            closestHitModule = compileModule(
                    stack, device, "gpuscene.rchit", Shaderc.shaderc_closesthit_shader
            );
            anyHitModule = compileModule(stack, device, "gpuscene.rahit", Shaderc.shaderc_anyhit_shader);
            pipeline = RtRayTracingPipelineFactory.createPipeline(
                    stack, device, pipelineLayout, raygenModule, missModule, closestHitModule, anyHitModule
            );
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

    public synchronized int descriptorSetCount() {
        requireOpen();
        return descriptors.setCount();
    }

    public synchronized long descriptorSet(int index) {
        requireOpen();
        return descriptors.set(index);
    }

    public synchronized long descriptorSetLayout() {
        requireOpen();
        return descriptors.layout();
    }

    /** Publishes one complete scene/frame resource generation into a bounded descriptor slot. */
    public synchronized void updateDescriptorSet(int index, GpuSceneDescriptorResources resources) {
        requireOpen();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GpuSceneDescriptorWriter.update(
                    stack, device, descriptors.set(index), maxStorageBufferRangeBytes, resources
            );
        }
    }

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

    /** Records the output-image dependency and the ray dispatch as one auditable command unit. */
    public synchronized void recordFrame(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            int descriptorSetIndex,
            RtGpuImage outputImage,
            int previousImageLayout,
            int width,
            int height
    ) {
        requireOpen();
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        Objects.requireNonNull(stack, "stack");
        RtGpuImage image = Objects.requireNonNull(outputImage, "outputImage");
        if (image.width() != width || image.height() != height) {
            throw new IllegalArgumentException("dispatch extent must equal the output-image extent");
        }
        int sourceAccess;
        int sourceStage;
        if (previousImageLayout == VK10.VK_IMAGE_LAYOUT_UNDEFINED) {
            sourceAccess = 0;
            sourceStage = VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        } else if (previousImageLayout == VK10.VK_IMAGE_LAYOUT_GENERAL) {
            sourceAccess = VK10.VK_ACCESS_SHADER_WRITE_BIT;
            sourceStage = org.lwjgl.vulkan.KHRRayTracingPipeline
                    .VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
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
                org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
        );
        recordTrace(commandBuffer, stack, descriptorSetIndex, width, height);
    }

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

    private static long compileModule(MemoryStack stack, VkDevice device, String file, int kind) {
        return RtShaderModuleCompiler.createModule(
                stack,
                device,
                RtShaderModuleCompiler.compile(SHADER_ROOT + file, kind, RtEdgeSink.NOOP)
        );
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("GPUScene ray tracing pipeline is closed");
    }

    private static void closeSuppressing(Throwable failure, AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    public record Snapshot(
            int descriptorSetCount,
            long pipelineLayout,
            long pipeline,
            int maxRayRecursionDepth,
            long maxStorageBufferRangeBytes,
            int shaderBindingTableStrideBytes,
            int shaderBindingTableBytes
    ) {
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

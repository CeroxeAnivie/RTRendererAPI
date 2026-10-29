package top.ceroxe.rt.renderer.rt.pipeline;

import java.nio.LongBuffer;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaSharedRayFrame;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.MediumRayQueryAbi;
import top.ceroxe.rt.renderer.rt.device.*;
import static org.lwjgl.vulkan.VK10.*;

/** Opaque geometry closest-hit/occlusion service for the native medium continuation, not surface shading. */
public final class MediumRayQueryPipeline implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final VkDevice device;
    private final CudaSharedRayFrame frame;
    private long setLayout, descriptorPool, descriptorSet, layout, pipeline;
    private RtShaderBindingTable sbt;
    private boolean closed;
    public MediumRayQueryPipeline(VulkanDeviceRuntime runtime, long tlas, CudaSharedRayFrame frame) {
        this.device = Objects.requireNonNull(runtime, "runtime").device();
        this.frame = Objects.requireNonNull(frame, "frame");
        if (tlas == 0 || frame.device().address() != device.address()) throw new IllegalArgumentException("invalid ray query device or TLAS");
        try (MemoryStack s = MemoryStack.stackPush()) {
            var bindings = VkDescriptorSetLayoutBinding.calloc(2, s);
            bindings.get(0).binding(0).descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            LongBuffer out = s.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(bindings), null, out));
            setLayout = out.get(0);
            var sizes = VkDescriptorPoolSize.calloc(2, s);
            sizes.get(0).type(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(1).pPoolSizes(sizes), null, out));
            descriptorPool = out.get(0);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(s).sType$Default()
                    .descriptorPool(descriptorPool).pSetLayouts(s.longs(setLayout)), out));
            descriptorSet = out.get(0);
            var acceleration = VkWriteDescriptorSetAccelerationStructureKHR.calloc(s).sType$Default().pAccelerationStructures(s.longs(tlas));
            var writes = VkWriteDescriptorSet.calloc(2, s);
            writes.get(0).sType$Default().dstSet(descriptorSet).dstBinding(0)
                    .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1).pNext(acceleration.address());
            writes.get(1).sType$Default().dstSet(descriptorSet).dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).pBufferInfo(VkDescriptorBufferInfo.calloc(1, s).buffer(frame.buffer()).offset(0).range(frame.byteSize()));
            vkUpdateDescriptorSets(device, writes, null);
            layout = RtRayTracingPipelineFactory.createPipelineLayout(s, device, setLayout);
            String[] suffixes = {"rgen", "rmiss", "rchit", "rahit"};
            long[] modules = new long[4];
            try {
                for (int i = 0; i < modules.length; i++) modules[i] = RtShaderModuleCompiler.createModule(s, device,
                        RtShaderModuleCompiler.loadPrecompiled("assets/rtrenderer/shaders/medium/native_query." + suffixes[i], false, false));
                pipeline = RtRayTracingPipelineFactory.createPipeline(s, device, layout, modules[0], modules[1], modules[2], modules[3], 0);
            } finally { for (long module : modules) if (module != 0) vkDestroyShaderModule(device, module, null); }
            var properties = RtRayTracingPipelineProperties.query(s, runtime.physicalDevice());
            var table = properties.packShaderGroupHandles(RtRayTracingPipelineFactory.queryShaderGroupHandles(properties, device, pipeline), 1, 1, 1, 0);
            sbt = RtShaderBindingTable.create(device, runtime.allocator(), runtime.buildCommands(), table.bytes(), table.layout());
        } catch (RuntimeException | Error failure) {
            try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    /** Caller owns TLAS/slot retirement and must wait for this recorded work before closing the pipeline. */
    public void record(VkCommandBuffer command, int count) {
        checkOpen(); MediumRayQueryAbi.requireCount(count, frame.capacity());
        try (MemoryStack s = MemoryStack.stackPush()) {
            vkCmdBindPipeline(command, KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline);
            vkCmdBindDescriptorSets(command, KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                    layout, 0, s.longs(descriptorSet), null);
            var table = sbt.layout();
            KHRRayTracingPipeline.vkCmdTraceRaysKHR(command, region(table.raygen(), s), region(table.miss(), s),
                    region(table.hit(), s), VkStridedDeviceAddressRegionKHR.calloc(s), count, 1, 1);
        }
    }
    private VkStridedDeviceAddressRegionKHR region(RtRayTracingPipelineProperties.Region region, MemoryStack s) {
        return VkStridedDeviceAddressRegionKHR.calloc(s).deviceAddress(sbt.buffer().deviceAddress() + sbt.baseOffsetBytes() + region.offsetBytes())
                .stride(region.strideBytes()).size(region.sizeBytes());
    }
    private static void check(int result) { if (result != VK_SUCCESS) throw new IllegalStateException("native ray query VkResult=" + result); }
    private void checkOpen() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("ray pipeline belongs to another thread");
        if (closed) throw new IllegalStateException("ray pipeline is closed");
    }
    @Override public void close() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("ray pipeline belongs to another thread");
        if (closed) return;
        if (sbt != null) sbt.close();
        if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
        if (layout != 0) vkDestroyPipelineLayout(device, layout, null);
        if (descriptorPool != 0) vkDestroyDescriptorPool(device, descriptorPool, null);
        if (setLayout != 0) vkDestroyDescriptorSetLayout(device, setLayout, null);
        closed = true;
    }
}

package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.rt.acceleration.RtAccelerationStructure;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneDescriptorResources;

import java.util.ArrayList;
import java.util.Objects;

/** Assembles one immutable GPUScene descriptor snapshot from session-owned resources. */
final class VulkanGpuSceneDescriptorAssembler {
    private final VulkanSceneRuntime scene;
    private final VulkanGpuSceneFeatureComposition features;

    VulkanGpuSceneDescriptorAssembler(
            VulkanSceneRuntime scene,
            VulkanGpuSceneFeatureComposition features
    ) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.features = Objects.requireNonNull(features, "features");
    }

    GpuSceneDescriptorResources assemble(
            VulkanFrameSlot slot,
            long sceneRevision,
            VulkanTemporalHistory.PreparedFrame temporal,
            VulkanFramePrimitiveResources.Prepared framePrimitives,
            VulkanGpuSceneFeatureComposition.Selection frameSelection
    ) {
        VulkanFrameSlot checkedSlot = Objects.requireNonNull(slot, "slot");
        VulkanTemporalHistory.PreparedFrame checkedTemporal = Objects.requireNonNull(temporal, "temporal");
        VulkanFramePrimitiveResources.Prepared primitives = Objects.requireNonNull(
                framePrimitives, "framePrimitives"
        );
        VulkanGpuSceneFeatureComposition.Selection selected = Objects.requireNonNull(
                frameSelection, "frameSelection"
        );
        RtAccelerationStructure tlas = primitives.tlas();
        ArrayList<GpuSceneDescriptorResources.StorageBinding> sceneBuffers = new ArrayList<>(
                VulkanGpuSceneUploadPlanner.Target.values().length
        );
        for (VulkanGpuSceneUploadPlanner.Target target : VulkanGpuSceneUploadPlanner.Target.values()) {
            VulkanGpuSceneTransferQueue.BufferBinding binding = scene.requireBuffer(target, sceneRevision);
            sceneBuffers.add(new GpuSceneDescriptorResources.StorageBinding(
                    VulkanGpuSceneAbi.descriptorBinding(target),
                    GpuSceneDescriptorResources.BufferRange.whole(binding.buffer(), binding.capacityBytes())
            ));
        }
        RtGpuBuffer uniforms = checkedSlot.frameUniforms();
        return new GpuSceneDescriptorResources(
                tlas.handle(),
                features.traceOutput(checkedSlot, selected).imageView(),
                checkedTemporal.colorInput().imageView(),
                checkedTemporal.colorOutput().imageView(),
                checkedTemporal.geometryInput().imageView(),
                checkedTemporal.geometryOutput().imageView(),
                checkedTemporal.motionOutput().imageView(),
                features.denoisingDescriptorViews(checkedSlot, selected),
                features.reconstructionDescriptorViews(checkedSlot, selected),
                GpuSceneDescriptorResources.BufferRange.whole(uniforms.buffer(), uniforms.sizeBytes()),
                GpuSceneDescriptorResources.BufferRange.whole(
                        primitives.instanceBuffer().buffer(),
                        primitives.instanceBuffer().sizeBytes()
                ),
                sceneBuffers
        );
    }
}

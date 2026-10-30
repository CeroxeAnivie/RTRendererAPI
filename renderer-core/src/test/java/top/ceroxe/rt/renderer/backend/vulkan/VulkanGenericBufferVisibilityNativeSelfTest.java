package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Set;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

final class VulkanGenericBufferVisibilityNativeSelfTest {
    static void verifyPlans(VulkanDeviceRuntime device) {
        try (var resources = new VulkanGenericResourceRegistry(device);
             var compute = new VulkanGenericComputePipelines(device, resources);
             var graphics = new VulkanGenericGraphicsPipelines(device.device(), resources, device.maxBoundDescriptorSets(),
                     device.logicOpEnabled(), device.geometryShaderEnabled(), device.vertexPipelineStoresAndAtomicsEnabled(),
                     device.drawIndirectCountEnabled(), device.multiDrawIndirectEnabled(), device.maxDrawIndirectCount());
             var rayTracing = new VulkanGenericRayTracingPipelines(device, resources);
             var structures = new VulkanGenericAccelerationStructures(device)) {
            var buffer = new BufferResource(new RenderResourceId(91001), ResourceVersion.initial(), 64,
                    Set.of(BufferUsage.COPY_DESTINATION, BufferUsage.COPY_SOURCE, BufferUsage.STORAGE_READ,
                            BufferUsage.ACCELERATION_STRUCTURE_BUILD_INPUT));
            var destination = new BufferResource(new RenderResourceId(91002), ResourceVersion.initial(), 64,
                    Set.of(BufferUsage.COPY_DESTINATION));
            require(resources.apply(new RenderResourceTransaction(0, List.of(buffer, destination), List.of(), List.of()), -1)
                    .outcome() == ResourceTransactionEvidence.Outcome.ACCEPTED, "resource admission failed");
            var slice = new ResourceSlice.BufferSlice(buffer, new ByteRange(0, 64));
            var target = new AccelerationStructureResource(new RenderResourceId(91003), ResourceVersion.initial(),
                    AccelerationStructureKind.BOTTOM_LEVEL, true);
            var triangles = new AccelerationStructureTriangleGeometry(slice, 12, 3,
                    new ResourceSlice.BufferSlice(buffer, new ByteRange(48, 12)), AccelerationStructureIndexFormat.UINT32, 3);
            var aabbs = new AccelerationStructureAabbGeometry(slice, 32, 2, false);
            var payload = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
            payload.putFloat(-1).putFloat(-1).putFloat(0).putFloat(1).putFloat(-1).putFloat(0)
                    .putFloat(0).putFloat(1).putFloat(0);
            payload.putInt(48, 0).putInt(52, 1).putInt(56, 2).position(0).limit(64);
            var write = new WriteBufferCommand(slice, new ResourceData(payload));
            for (boolean procedural : List.of(false, true)) {
                for (long barrierBytes : List.of(16L, 64L)) {
                    var shaderBarrier = new ResourceBarrierCommand(List.of(new BufferBarrier(
                            new ResourceSlice.BufferSlice(buffer, new ByteRange(0, barrierBytes)),
                            Set.of(RenderPipelineStage.COPY), Set.of(RenderResourceAccess.COPY_WRITE),
                            Set.of(RenderPipelineStage.RAY_TRACING_SHADER), Set.of(RenderResourceAccess.SHADER_READ))), List.of());
                    var build = build(procedural, target, triangles, aabbs, AccelerationStructureBuildMode.BUILD);
                    var secondTarget = new AccelerationStructureResource(new RenderResourceId(91004), ResourceVersion.initial(),
                            AccelerationStructureKind.BOTTOM_LEVEL, true);
                    var secondBuild = build(procedural, secondTarget, triangles, aabbs, AccelerationStructureBuildMode.BUILD);
                    assertPlan(resources, compute, graphics, rayTracing, structures,
                            List.of(write, shaderBarrier, build, secondBuild), 1, 0);
                    assertPlan(resources, compute, graphics, rayTracing, structures,
                            List.of(write, shaderBarrier, build, write, shaderBarrier, secondBuild), 2, 0);
                    var copy = new CopyBufferCommand(slice, new ResourceSlice.BufferSlice(destination, new ByteRange(0, 64)));
                    assertPlan(resources, compute, graphics, rayTracing, structures,
                            List.of(write, shaderBarrier, build, copy), 1, 1);
                    assertPlan(resources, compute, graphics, rayTracing, structures,
                            List.of(write, copy, build), 0, 1);
                    assertPlan(resources, compute, graphics, rayTracing, structures,
                            List.of(write, copy, build, write, secondBuild), 1, 1);
                }
            }
        }
        System.out.println("VulkanGenericBufferVisibilityNativeSelfTest passed: 20 command plans, partial/full barriers, repeated writes, indexed/AABB builds");
    }

    private static RenderCommand build(boolean procedural, AccelerationStructureResource target,
            AccelerationStructureTriangleGeometry triangles, AccelerationStructureAabbGeometry aabbs,
            AccelerationStructureBuildMode mode) {
        return procedural ? new BuildProceduralBottomLevelAccelerationStructureCommand(target, mode, List.of(aabbs))
                : new BuildBottomLevelAccelerationStructureCommand(target, mode, List.of(triangles));
    }

    private static void assertPlan(VulkanGenericResourceRegistry resources, VulkanGenericComputePipelines compute,
            VulkanGenericGraphicsPipelines graphics, VulkanGenericRayTracingPipelines rayTracing,
            VulkanGenericAccelerationStructures structures, List<RenderCommand> commands,
            long expectedAsBarriers, long expectedGeneralBarriers) {
        var plan = VulkanGenericCommandPlan.compile(resources, compute, graphics, rayTracing, structures,
                new RenderCommandTransaction(0, commands));
        try {
            long asBarriers = plan.actions().stream().filter(VulkanGenericCommandPlan.AutoAccelerationStructureInputVisibility.class::isInstance).count();
            long generalBarriers = plan.actions().stream().filter(VulkanGenericCommandPlan.AutoBufferVisibility.class::isInstance).count();
            require(asBarriers == expectedAsBarriers, "AS barriers expected=" + expectedAsBarriers + " actual=" + asBarriers);
            require(generalBarriers == expectedGeneralBarriers, "general barriers expected=" + expectedGeneralBarriers + " actual=" + generalBarriers);
            var dirty = new java.util.HashSet<ResourceGenerationKey>();
            for (var action : plan.actions()) {
                if (action instanceof VulkanGenericCommandPlan.Write write) dirty.add(write.destination().generation());
                else if (action instanceof VulkanGenericCommandPlan.AutoAccelerationStructureInputVisibility visible) dirty.remove(visible.resource().generation());
                else if (action instanceof VulkanGenericCommandPlan.AutoBufferVisibility visible) dirty.remove(visible.resource().generation());
                else if (action instanceof VulkanGenericCommandPlan.BuildAccelerationStructure) require(dirty.isEmpty(), "build precedes input visibility");
            }
        } finally {
            plan.accelerationStructures().close();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

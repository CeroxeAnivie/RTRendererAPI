package top.ceroxe.rt.renderer.rt.acceleration;

import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Vulkan ABI encoder and immutable-boundary validation for TLAS instance tables.
 */
final class RtTlasInstanceEncoder {
    private static final int TRANSFORM_FLOATS = 12;

    private RtTlasInstanceEncoder() {
    }

    static void validateDirtySlots(int[] dirtySlots, int instanceCount) {
        Objects.requireNonNull(dirtySlots, "dirtySlots");
        if (instanceCount <= 0) {
            throw new IllegalArgumentException("TLAS instance count must be positive");
        }
        int previous = -1;
        for (int slot : dirtySlots) {
            if (slot <= previous || slot >= instanceCount) {
                throw new IllegalArgumentException("TLAS dirty instance slots must be sorted and in range");
            }
            previous = slot;
        }
    }

    static List<RtAccelerationStructure.TlasInstance> freeze(
            Collection<RtAccelerationStructure.TlasInstance> instances
    ) {
        Objects.requireNonNull(instances, "instances");
        if (instances instanceof RtImmutableTlasInstances immutable) {
            return immutable;
        }
        return List.copyOf(instances);
    }

    static void write(
            VkAccelerationStructureInstanceKHR target,
            RtAccelerationStructure.TlasInstance instance
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(instance, "instance");
        for (int index = 0; index < TRANSFORM_FLOATS; index++) {
            target.transform().matrix(index, instance.transformValue(index));
        }
        target.instanceCustomIndex(instance.customIndex())
                .mask(instance.visibilityMask())
                .instanceShaderBindingTableRecordOffset(0)
                .flags(KHRAccelerationStructure.VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                .accelerationStructureReference(instance.blasDeviceAddress());
    }
}

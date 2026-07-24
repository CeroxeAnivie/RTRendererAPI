package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.DynamicRenderScene;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameCausality;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameSubmission;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtSceneMaterialTable;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, publication-safe view of the persistent dynamic-instance table.
 *
 * <p>Every physical slot is represented. An asset that is not resident yet is
 * encoded as an inactive TLAS instance paired with a tombstone material. This
 * makes the snapshot independently buildable: residency backlog is telemetry,
 * never a global build barrier for already-resident animated instances. The
 * sorted dirty-slot publication describes the exact 64-byte Vulkan instance
 * records changed since the previous immutable table generation.</p>
 */
public record RtDynamicInstanceSnapshot(
        long revision,
        long topologyRevision,
        long transformRevision,
        long geometryRevision,
        long materialRevision,
        long latestSceneRevision,
        RendererFrameCausality causality,
        DynamicRenderScene dynamicScene,
        List<RtAccelerationStructure.TlasInstance> instances,
        int[] instanceDirtySlots,
        List<RtSceneMaterialTable.SectionMaterial> materials,
        int[] materialDirtySlots,
        int activeInstanceCount,
        long primitiveCount,
        long faceCount,
        long triangleCount
) {
    public RtDynamicInstanceSnapshot(
            long revision,
            long topologyRevision,
            long transformRevision,
            long geometryRevision,
            long materialRevision,
            long latestSceneRevision,
            DynamicRenderScene dynamicScene,
            List<RtAccelerationStructure.TlasInstance> instances,
            List<RtSceneMaterialTable.SectionMaterial> materials,
            int[] materialDirtySlots,
            int activeInstanceCount,
            long primitiveCount,
            long faceCount,
            long triangleCount
    ) {
        this(
                revision,
                topologyRevision,
                transformRevision,
                geometryRevision,
                materialRevision,
                latestSceneRevision,
                RendererFrameCausality.untraced(0L),
                dynamicScene,
                instances,
                allSlots(Objects.requireNonNull(instances, "instances").size()),
                materials,
                materialDirtySlots,
                activeInstanceCount,
                primitiveCount,
                faceCount,
                triangleCount
        );
    }

    public RtDynamicInstanceSnapshot {
        dynamicScene = Objects.requireNonNull(dynamicScene, "dynamicScene");
        causality = Objects.requireNonNull(causality, "causality");
        instances = freezeInstances(Objects.requireNonNull(instances, "instances"));
        instanceDirtySlots = copyAndValidateDirtySlots(
                instanceDirtySlots,
                instances.size(),
                "dynamic instance"
        );
        materials = List.copyOf(Objects.requireNonNull(materials, "materials"));
        materialDirtySlots = copyAndValidateDirtySlots(
                materialDirtySlots,
                materials.size(),
                "dynamic material"
        );
        if (revision < 0L || topologyRevision < 0L || transformRevision < 0L
                || geometryRevision < 0L || materialRevision < 0L) {
            throw new IllegalArgumentException("dynamic instance revisions must not be negative");
        }
        if (latestSceneRevision < 0L) {
            throw new IllegalArgumentException("dynamic scene revision must not be negative");
        }
        if (instances.size() != materials.size()) {
            throw new IllegalArgumentException("dynamic instances and materials must match physical slots");
        }
        if (activeInstanceCount < 0 || activeInstanceCount > instances.size()) {
            throw new IllegalArgumentException("active dynamic instances must fit physical slots");
        }
        if (primitiveCount < 0L || faceCount < 0L || triangleCount < 0L) {
            throw new IllegalArgumentException("dynamic instance counts must not be negative");
        }
    }

    @Override
    public int[] instanceDirtySlots() {
        return Arrays.copyOf(instanceDirtySlots, instanceDirtySlots.length);
    }

    @Override
    public int[] materialDirtySlots() {
        return Arrays.copyOf(materialDirtySlots, materialDirtySlots.length);
    }

    private static List<RtAccelerationStructure.TlasInstance> freezeInstances(
            List<RtAccelerationStructure.TlasInstance> instances
    ) {
        return instances instanceof RtImmutableTlasInstances ? instances : List.copyOf(instances);
    }

    static RtDynamicInstanceSnapshot empty(
            long revision,
            long topologyRevision,
            long transformRevision,
            long geometryRevision,
            long materialRevision,
            long latestSceneRevision,
            RendererFrameCausality causality,
            DynamicRenderScene dynamicScene
    ) {
        return new RtDynamicInstanceSnapshot(
                revision,
                topologyRevision,
                transformRevision,
                geometryRevision,
                materialRevision,
                latestSceneRevision,
                causality,
                dynamicScene,
                List.of(),
                new int[0],
                List.of(),
                new int[0],
                0,
                0L,
                0L,
                0L
        );
    }

    private static int[] copyAndValidateDirtySlots(int[] dirtySlots, int size, String description) {
        int[] copy = Arrays.copyOf(
                Objects.requireNonNull(dirtySlots, description + " dirty slots"),
                dirtySlots.length
        );
        int previous = -1;
        for (int dirtySlot : copy) {
            if (dirtySlot <= previous || dirtySlot >= size) {
                throw new IllegalArgumentException(description + " dirty slots must be sorted and in range");
            }
            previous = dirtySlot;
        }
        return copy;
    }

    private static int[] allSlots(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("dynamic instance slot count must not be negative");
        }
        int[] slots = new int[size];
        for (int index = 0; index < size; index++) {
            slots[index] = index;
        }
        return slots;
    }

}

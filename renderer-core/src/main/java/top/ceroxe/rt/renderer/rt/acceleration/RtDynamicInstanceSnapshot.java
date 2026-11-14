package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.DynamicRenderScene;
import top.ceroxe.rt.renderer.RendererFrameCausality;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable;

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
 *
 * @param revision            immutable table revision
 * @param topologyRevision    physical-slot topology revision
 * @param transformRevision   instance-transform revision
 * @param geometryRevision    resident geometry revision
 * @param materialRevision    material-table revision
 * @param latestSceneRevision latest incorporated dynamic-scene revision
 * @param causality           immutable frame-causality identity
 * @param dynamicScene        immutable source dynamic scene
 * @param instances           immutable physical TLAS instance slots
 * @param instanceDirtySlots  sorted changed instance slots
 * @param materials           immutable physical material slots
 * @param materialDirtySlots  sorted changed material slots
 * @param activeInstanceCount number of active physical instances
 * @param primitiveCount      incorporated source primitive count
 * @param faceCount           incorporated source face count
 * @param triangleCount       incorporated triangle count
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
    /**
     * Creates a backward-compatible snapshot in which every physical instance slot is dirty.
     *
     * @param revision            immutable table revision
     * @param topologyRevision    physical-slot topology revision
     * @param transformRevision   instance-transform revision
     * @param geometryRevision    resident geometry revision
     * @param materialRevision    material-table revision
     * @param latestSceneRevision latest incorporated scene revision
     * @param dynamicScene        immutable source scene
     * @param instances           immutable physical instance slots
     * @param materials           immutable physical material slots
     * @param materialDirtySlots  sorted changed material slots
     * @param activeInstanceCount active physical instance count
     * @param primitiveCount      source primitive count
     * @param faceCount           source face count
     * @param triangleCount       indexed triangle count
     */
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

    /**
     * Defensively freezes collections and validates revision, cardinality, and dirty-slot invariants.
     */
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

    /**
     * Returns a defensive copy of the sorted instance-dirty set.
     *
     * @return copied dirty slot indices
     */
    @Override
    public int[] instanceDirtySlots() {
        return Arrays.copyOf(instanceDirtySlots, instanceDirtySlots.length);
    }

    /**
     * Returns a defensive copy of the sorted material-dirty set.
     *
     * @return copied dirty slot indices
     */
    @Override
    public int[] materialDirtySlots() {
        return Arrays.copyOf(materialDirtySlots, materialDirtySlots.length);
    }

}

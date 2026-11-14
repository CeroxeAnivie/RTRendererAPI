package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.function.ToLongFunction;

/**
 * Immutable buffer-growth and staging-copy schedule derived from one sparse upload plan.
 */
final class VulkanGpuSceneTransferPlan {
    private static final long MIN_BUFFER_BYTES = 4_096L;

    private VulkanGpuSceneTransferPlan() {
    }

    static Plan build(
            VulkanGpuSceneUploadPlanner.Plan uploads,
            ToLongFunction<VulkanGpuSceneUploadPlanner.Target> currentCapacity
    ) {
        VulkanGpuSceneUploadPlanner.Plan source = Objects.requireNonNull(uploads, "uploads");
        ToLongFunction<VulkanGpuSceneUploadPlanner.Target> capacities =
                Objects.requireNonNull(currentCapacity, "currentCapacity");
        EnumMap<VulkanGpuSceneUploadPlanner.Target, Long> requiredByTarget =
                new EnumMap<>(VulkanGpuSceneUploadPlanner.Target.class);
        for (VulkanGpuSceneUploadPlanner.Chunk chunk : source.chunks()) {
            requiredByTarget.merge(chunk.target(), chunk.endOffsetBytes(), Math::max);
        }

        ArrayList<TargetCapacity> targets = new ArrayList<>(VulkanGpuSceneUploadPlanner.Target.values().length);
        for (VulkanGpuSceneUploadPlanner.Target target : VulkanGpuSceneUploadPlanner.Target.values()) {
            long current = capacities.applyAsLong(target);
            if (current < 0L) throw new IllegalArgumentException("current target capacity must not be negative");
            long required = requiredByTarget.getOrDefault(target, 0L);
            if (required == 0L && current > 0L) continue;
            /*
             * Vulkan storage-buffer descriptors must always reference a valid resource, including
             * empty scene domains. Bootstrap one word for every previously absent target; capacity
             * growth raises it to the same bounded 4 KiB generation used by populated domains.
             */
            if (required == 0L) required = Integer.BYTES;
            boolean grows = required > current;
            targets.add(new TargetCapacity(
                    target, required, grows ? growthCapacity(required) : current,
                    grows ? current : 0L, grows
            ));
        }

        if (source.uploadBytes() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("one GPUScene staging batch exceeds Java array address space");
        }
        byte[] staging = new byte[(int) source.uploadBytes()];
        ArrayList<StagedCopy> copies = new ArrayList<>(source.chunks().size());
        int stagingOffset = 0;
        for (VulkanGpuSceneUploadPlanner.Chunk chunk : source.chunks()) {
            chunk.copyPayloadTo(staging, stagingOffset);
            copies.add(new StagedCopy(
                    chunk.target(), stagingOffset, chunk.targetOffsetBytes(), chunk.byteCount()
            ));
            stagingOffset = Math.addExact(stagingOffset, chunk.byteCount());
        }
        return Plan.owned(source.revision(), targets, copies, staging);
    }

    private static long growthCapacity(long required) {
        if (required <= 0L) throw new IllegalArgumentException("required buffer capacity must be positive");
        long capacity = MIN_BUFFER_BYTES;
        while (capacity < required) {
            if (capacity > Long.MAX_VALUE / 2L) {
                return required;
            }
            capacity *= 2L;
        }
        return capacity;
    }

    record TargetCapacity(
            VulkanGpuSceneUploadPlanner.Target target,
            long requiredBytes,
            long capacityBytes,
            long copyPreviousBytes,
            boolean grows
    ) {
        TargetCapacity {
            target = Objects.requireNonNull(target, "target");
            if (requiredBytes <= 0L || capacityBytes < requiredBytes || copyPreviousBytes < 0L
                    || copyPreviousBytes > capacityBytes) {
                throw new IllegalArgumentException("GPUScene target capacity decision is invalid");
            }
            if (!grows && copyPreviousBytes != 0L) {
                throw new IllegalArgumentException("in-place target must not copy a previous generation");
            }
        }
    }

    record StagedCopy(
            VulkanGpuSceneUploadPlanner.Target target,
            long sourceOffsetBytes,
            long targetOffsetBytes,
            long byteCount
    ) {
        StagedCopy {
            target = Objects.requireNonNull(target, "target");
            if (sourceOffsetBytes < 0L || targetOffsetBytes < 0L || byteCount <= 0L
                    || (sourceOffsetBytes & 3L) != 0L || (targetOffsetBytes & 3L) != 0L
                    || (byteCount & 3L) != 0L) {
                throw new IllegalArgumentException("GPUScene staging copy must be positive and four-byte aligned");
            }
        }
    }

    static final class Plan {
        private final long revision;
        private final List<TargetCapacity> targets;
        private final List<StagedCopy> copies;
        private final byte[] stagingBytes;

        Plan(long revision, List<TargetCapacity> targets, List<StagedCopy> copies, byte[] stagingBytes) {
            this(revision, targets, copies, Objects.requireNonNull(stagingBytes, "stagingBytes").clone(), true);
        }

        private Plan(
                long revision,
                List<TargetCapacity> targets,
                List<StagedCopy> copies,
                byte[] stagingBytes,
                boolean owned
        ) {
            if (revision < 0L) throw new IllegalArgumentException("transfer revision must not be negative");
            this.revision = revision;
            this.targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
            this.copies = List.copyOf(Objects.requireNonNull(copies, "copies"));
            this.stagingBytes = Objects.requireNonNull(stagingBytes, "stagingBytes");
            long expectedBytes = 0L;
            for (StagedCopy copy : this.copies) expectedBytes = Math.addExact(expectedBytes, copy.byteCount());
            if (expectedBytes != this.stagingBytes.length) {
                throw new IllegalArgumentException("staging payload length does not match copy schedule");
            }
        }

        private static Plan owned(
                long revision,
                List<TargetCapacity> targets,
                List<StagedCopy> copies,
                byte[] stagingBytes
        ) {
            return new Plan(revision, targets, copies, stagingBytes, true);
        }

        long revision() {
            return revision;
        }

        List<TargetCapacity> targets() {
            return targets;
        }

        List<StagedCopy> copies() {
            return copies;
        }

        byte[] stagingBytes() {
            return stagingBytes.clone();
        }

        byte[] stagingBytesForSubmit() {
            return stagingBytes;
        }

        boolean isEmpty() {
            return targets.isEmpty() && copies.isEmpty();
        }

        long allocationGrowthBytes() {
            return allocationGrowthBytes(0L);
        }

        long allocationGrowthBytes(long reusableStagingCapacity) {
            if (reusableStagingCapacity < 0L) {
                throw new IllegalArgumentException("reusable staging capacity must not be negative");
            }
            long growth = Math.max(0L, stagingBytes.length - reusableStagingCapacity);
            for (TargetCapacity target : targets) {
                if (target.grows()) {
                    /*
                     * The predecessor remains descriptor-visible until its retirement epoch, so
                     * budget admission must charge the successor's full allocation, not merely
                     * the difference between old and new capacities.
                     */
                    growth = Math.addExact(growth, target.capacityBytes());
                }
            }
            return growth;
        }
    }
}

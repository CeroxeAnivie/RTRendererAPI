package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToLongFunction;

/** Immutable buffer-growth and staging-copy schedule derived from one sparse upload plan. */
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
            byte[] payload = chunk.payload();
            System.arraycopy(payload, 0, staging, stagingOffset, payload.length);
            copies.add(new StagedCopy(
                    chunk.target(), stagingOffset, chunk.targetOffsetBytes(), payload.length
            ));
            stagingOffset = Math.addExact(stagingOffset, payload.length);
        }
        return new Plan(source.revision(), List.copyOf(targets), List.copyOf(copies), staging);
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

    record Plan(long revision, List<TargetCapacity> targets, List<StagedCopy> copies, byte[] stagingBytes) {
        Plan {
            if (revision < 0L) throw new IllegalArgumentException("transfer revision must not be negative");
            targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
            copies = List.copyOf(Objects.requireNonNull(copies, "copies"));
            stagingBytes = Objects.requireNonNull(stagingBytes, "stagingBytes").clone();
            long expectedBytes = 0L;
            for (StagedCopy copy : copies) expectedBytes = Math.addExact(expectedBytes, copy.byteCount());
            if (expectedBytes != stagingBytes.length) {
                throw new IllegalArgumentException("staging payload length does not match copy schedule");
            }
        }

        @Override
        public byte[] stagingBytes() {
            return stagingBytes.clone();
        }

        boolean isEmpty() { return targets.isEmpty() && copies.isEmpty(); }
    }
}

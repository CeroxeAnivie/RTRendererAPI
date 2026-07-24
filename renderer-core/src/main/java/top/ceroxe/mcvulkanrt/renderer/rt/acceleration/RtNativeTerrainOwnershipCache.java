package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.scene.PackedSectionMembership;

import java.util.Objects;

/** Owns immutable terrain-ownership publications and their revision-keyed frozen memberships. */
class RtNativeTerrainOwnershipCache {
    private NativeTerrainOwnership snapshot;
    private long sourceRevision = -1L;
    private long queuedRevision = -1L;
    private long recordingRevision = -1L;
    private long gpuRevision = -1L;
    private long activeRevision = -1L;
    private long boundRevision = Long.MIN_VALUE;
    private long authorityRevision = -1L;
    private long frozenSourceRevision = Long.MIN_VALUE;
    private PackedSectionMembership frozenSourceKeys = PackedSectionMembership.empty();
    private long frozenActiveRevision = Long.MIN_VALUE;
    private PackedSectionMembership frozenActiveKeys = PackedSectionMembership.empty();
    private long frozenBoundRevision = Long.MIN_VALUE;
    private PackedSectionMembership frozenBoundKeys = PackedSectionMembership.empty();
    private long frozenAuthorityRevision = Long.MIN_VALUE;
    private PackedSectionMembership frozenAuthorityKeys = PackedSectionMembership.empty();
    private long ownershipGeneration = -1L;
    private long generationSourceRevision = Long.MIN_VALUE;
    private long generationQueuedRevision = Long.MIN_VALUE;
    private long generationRecordingRevision = Long.MIN_VALUE;
    private long generationGpuRevision = Long.MIN_VALUE;
    private long generationActiveRevision = Long.MIN_VALUE;
    private long generationBoundRevision = Long.MIN_VALUE;
    private long generationAuthorityRevision = Long.MIN_VALUE;

    long observeGeneration(long source, long queued, long recording, long gpu, long active, long bound, long authority) {
        if (generationSourceRevision == source
                && generationQueuedRevision == queued
                && generationRecordingRevision == recording
                && generationGpuRevision == gpu
                && generationActiveRevision == active
                && generationBoundRevision == bound
                && generationAuthorityRevision == authority) {
            return ownershipGeneration;
        }
        ownershipGeneration = ownershipGeneration < 0L ? 0L : Math.incrementExact(ownershipGeneration);
        generationSourceRevision = source;
        generationQueuedRevision = queued;
        generationRecordingRevision = recording;
        generationGpuRevision = gpu;
        generationActiveRevision = active;
        generationBoundRevision = bound;
        generationAuthorityRevision = authority;
        return ownershipGeneration;
    }

    PackedSectionMembership freezeSource(PackedSectionMembership keys, long revision) {
        if (frozenSourceRevision != revision) {
            frozenSourceKeys = Objects.requireNonNull(keys, "keys");
            frozenSourceRevision = revision;
        }
        return frozenSourceKeys;
    }

    PackedSectionMembership freezeActive(PackedSectionMembership keys, long revision) {
        if (frozenActiveRevision != revision) {
            frozenActiveKeys = Objects.requireNonNull(keys, "keys");
            frozenActiveRevision = revision;
        }
        return frozenActiveKeys;
    }

    PackedSectionMembership freezeBound(PackedSectionMembership keys, long revision) {
        if (frozenBoundRevision != revision) {
            frozenBoundKeys = Objects.requireNonNull(keys, "keys");
            frozenBoundRevision = revision;
        }
        return frozenBoundKeys;
    }

    PackedSectionMembership freezeAuthority(PackedSectionMembership keys, long revision) {
        if (frozenAuthorityRevision != revision) {
            frozenAuthorityKeys = Objects.requireNonNull(keys, "keys");
            frozenAuthorityRevision = revision;
        }
        return frozenAuthorityKeys;
    }

    boolean isCurrent(long source, long queued, long recording, long gpu, long active, long bound, long authority) {
        return snapshot != null
                && sourceRevision == source
                && queuedRevision == queued
                && recordingRevision == recording
                && gpuRevision == gpu
                && activeRevision == active
                && boundRevision == bound
                && authorityRevision == authority;
    }

    void publish(
            NativeTerrainOwnership nextSnapshot,
            long source,
            long queued,
            long recording,
            long gpu,
            long active,
            long bound,
            long authority
    ) {
        snapshot = Objects.requireNonNull(nextSnapshot, "nextSnapshot");
        sourceRevision = source;
        queuedRevision = queued;
        recordingRevision = recording;
        gpuRevision = gpu;
        activeRevision = active;
        boundRevision = bound;
        authorityRevision = authority;
    }

    NativeTerrainOwnership snapshot() {
        return Objects.requireNonNull(snapshot, "snapshot");
    }
}

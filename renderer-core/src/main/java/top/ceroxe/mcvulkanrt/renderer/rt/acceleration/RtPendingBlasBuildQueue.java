package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;
import top.ceroxe.mcvulkanrt.renderer.scene.PackedSectionMembership;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionTriangleMesh;
import top.ceroxe.mcvulkanrt.renderer.orchestration.work.SectionWorkLane;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Byte- and section-bounded admission queue for section BLAS builds.
 *
 * <p>Stable per-lane insertion order defines scheduling, while camera authority may reclassify a
 * queued identity without rebuilding its payload. The queue owns accounting, lane membership and
 * priority-safe eviction only; worker execution and Vulkan resource lifetime remain outside this
 * class.</p>
 */
class RtPendingBlasBuildQueue<T> {
    private final LinkedHashMap<SectionKey, Work<T>> workBySection = new LinkedHashMap<>();
    private final EnumMap<SectionWorkLane, LinkedHashSet<SectionKey>> keysByLane =
            new EnumMap<>(SectionWorkLane.class);
    private final int sectionCapacity;
    private final long maxBytes;
    private long pendingTriangles;
    private long pendingBytes;
    private long enqueuedSections;
    private long replacedSections;
    private long cancelledSections;
    private long evictedSections;
    private long membershipRevision;
    private final PackedSectionMembership.Builder membershipBuilder = PackedSectionMembership.builder(0);
    private PackedSectionMembership publishedMembership = PackedSectionMembership.empty();
    private long publishedMembershipRevision = -1L;

    RtPendingBlasBuildQueue() {
        this(Integer.MAX_VALUE, Long.MAX_VALUE);
    }

    RtPendingBlasBuildQueue(int maxSections, long maxBytes) {
        if (maxSections <= 0) {
            throw new IllegalArgumentException("maxSections must be positive");
        }
        if (maxBytes <= 0L) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        sectionCapacity = maxSections;
        this.maxBytes = maxBytes;
        for (SectionWorkLane lane : SectionWorkLane.values()) {
            keysByLane.put(lane, new LinkedHashSet<>());
        }
    }

    public int sectionCapacity() {
        return sectionCapacity;
    }

    public int initialSectionCapacity() {
        return sectionCapacity;
    }

    int availableSectionCapacity() {
        return Math.max(0, sectionCapacity - workBySection.size());
    }

    public void enqueue(SectionTriangleMesh mesh) {
        enqueue(mesh, null, SectionWorkLane.BACKGROUND);
    }

    public void enqueue(
            SectionTriangleMesh mesh,
            SectionWorkLane lane
    ) {
        enqueue(mesh, null, lane);
    }

    public void enqueue(
            SectionTriangleMesh mesh,
            T payload,
            SectionWorkLane lane
    ) {
        Objects.requireNonNull(mesh, "mesh");
        Objects.requireNonNull(lane, "lane");
        if (mesh.triangleCount() <= 0) {
            throw new IllegalArgumentException("pending BLAS build mesh must contain triangles");
        }
        Work<T> previous = workBySection.put(mesh.key(), new Work<>(mesh, payload, lane));
        if (previous == null) {
            membershipRevision = Math.incrementExact(membershipRevision);
            keysByLane.get(lane).add(mesh.key());
        } else if (previous.lane() != lane) {
            keysByLane.get(previous.lane()).remove(mesh.key());
            keysByLane.get(lane).add(mesh.key());
        }
        enqueuedSections++;
        pendingTriangles += mesh.triangleCount();
        pendingBytes += mesh.estimatedBytes();
        if (previous != null) {
            replacedSections++;
            pendingTriangles -= previous.mesh().triangleCount();
            pendingBytes -= previous.mesh().estimatedBytes();
        }
        evictLowestPriorityUntilWithinBudget(mesh.key());
    }

    /** Reclassifies queued identities after a new camera authority publication. */
    void classify(Set<SectionKey> interactiveSectionKeys, Set<SectionKey> foregroundSectionKeys) {
        Objects.requireNonNull(interactiveSectionKeys, "interactiveSectionKeys");
        Objects.requireNonNull(foregroundSectionKeys, "foregroundSectionKeys");
        for (Map.Entry<SectionKey, Work<T>> entry : workBySection.entrySet()) {
            SectionKey key = entry.getKey();
            Work<T> work = entry.getValue();
            SectionWorkLane lane = interactiveSectionKeys.contains(key)
                    ? SectionWorkLane.INTERACTIVE
                    : foregroundSectionKeys.contains(key)
                    ? SectionWorkLane.FOREGROUND
                    : work.lane() == SectionWorkLane.INTERACTIVE || work.lane() == SectionWorkLane.FOREGROUND
                    ? SectionWorkLane.STREAMING
                    : work.lane();
            if (lane == work.lane()) {
                continue;
            }
            keysByLane.get(work.lane()).remove(key);
            keysByLane.get(lane).add(key);
            entry.setValue(work.withLane(lane));
        }
    }

    public boolean owns(SectionTriangleMesh mesh) {
        Objects.requireNonNull(mesh, "mesh");
        Work<T> work = workBySection.get(mesh.key());
        return work != null && work.mesh() == mesh;
    }

    public boolean contains(SectionKey key) {
        return workBySection.containsKey(Objects.requireNonNull(key, "key"));
    }

    public SectionTriangleMesh pollNext(
            int builtInPass,
            long trianglesInPass,
            int maxBuildsPerFrame,
            long maxTrianglesPerFrame
    ) {
        return pollNext(
                builtInPass, trianglesInPass, maxBuildsPerFrame, maxTrianglesPerFrame,
                0L, Long.MAX_VALUE
        );
    }

    public SectionTriangleMesh pollNext(
            int builtInPass,
            long trianglesInPass,
            int maxBuildsPerFrame,
            long maxTrianglesPerFrame,
            long bytesInPass,
            long maxBytesPerBatch
    ) {
        return pollNextInternal(
                builtInPass, trianglesInPass, maxBuildsPerFrame, maxTrianglesPerFrame,
                bytesInPass, maxBytesPerBatch
        );
    }

    private SectionTriangleMesh pollNextInternal(
            int builtInPass,
            long trianglesInPass,
            int maxBuildsPerFrame,
            long maxTrianglesPerFrame,
            long bytesInPass,
            long maxBytesPerBatch
    ) {
        Work<T> work = pollNextWork(
                builtInPass, trianglesInPass, maxBuildsPerFrame, maxTrianglesPerFrame,
                bytesInPass, maxBytesPerBatch
        );
        return work == null ? null : work.mesh();
    }

    Work<T> pollNextWork(
            int builtInPass,
            long trianglesInPass,
            int maxBuildsPerFrame,
            long maxTrianglesPerFrame,
            long bytesInPass,
            long maxBytesPerBatch
    ) {
        if (builtInPass >= maxBuildsPerFrame || workBySection.isEmpty()) {
            return null;
        }
        if (bytesInPass < 0L || maxBytesPerBatch <= 0L) {
            throw new IllegalArgumentException("pending BLAS byte budget must be positive");
        }
        Work<T> work = nextScheduledWork();
        SectionTriangleMesh mesh = work.mesh();
        if (builtInPass > 0 && trianglesInPass + mesh.triangleCount() > maxTrianglesPerFrame) {
            return null;
        }
        if (builtInPass > 0 && bytesInPass + mesh.estimatedBytes() > maxBytesPerBatch) {
            return null;
        }
        workBySection.remove(mesh.key());
        keysByLane.get(work.lane()).remove(mesh.key());
        membershipRevision = Math.incrementExact(membershipRevision);
        pendingTriangles -= mesh.triangleCount();
        pendingBytes -= mesh.estimatedBytes();
        return work;
    }

    private Work<T> nextScheduledWork() {
        for (SectionWorkLane lane : SectionWorkLane.values()) {
            Iterator<SectionKey> iterator = keysByLane.get(lane).iterator();
            if (iterator.hasNext()) {
                return workBySection.get(iterator.next());
            }
        }
        throw new IllegalStateException("pending BLAS work has no scheduling lane");
    }

    boolean hasPreferred(Set<SectionKey> preferredSectionKeys) {
        return preferredCount(preferredSectionKeys) > 0;
    }

    int preferredCount(Set<SectionKey> preferredSectionKeys) {
        Objects.requireNonNull(preferredSectionKeys, "preferredSectionKeys");
        int preferred = 0;
        for (SectionKey key : preferredSectionKeys) {
            if (workBySection.containsKey(key)) {
                preferred++;
            }
        }
        return preferred;
    }

    int preferredCount(PackedSectionMembership preferredSectionKeys) {
        Objects.requireNonNull(preferredSectionKeys, "preferredSectionKeys");
        int preferred = 0;
        for (SectionKey key : workBySection.keySet()) {
            if (preferredSectionKeys.containsPacked(key.packed())) {
                preferred++;
            }
        }
        return preferred;
    }

    public boolean cancel(SectionKey key) {
        Objects.requireNonNull(key, "key");
        Work<T> removed = workBySection.remove(key);
        if (removed == null) {
            return false;
        }
        keysByLane.get(removed.lane()).remove(key);
        cancelledSections++;
        membershipRevision = Math.incrementExact(membershipRevision);
        pendingTriangles -= removed.mesh().triangleCount();
        pendingBytes -= removed.mesh().estimatedBytes();
        return true;
    }

    void clear() {
        cancelledSections += workBySection.size();
        if (!workBySection.isEmpty()) {
            membershipRevision = Math.incrementExact(membershipRevision);
        }
        workBySection.clear();
        keysByLane.values().forEach(Set::clear);
        pendingTriangles = 0L;
        pendingBytes = 0L;
    }

    public boolean isEmpty() {
        return workBySection.isEmpty();
    }

    public int size() {
        return workBySection.size();
    }

    Set<SectionKey> snapshotKeys() {
        return Set.copyOf(workBySection.keySet());
    }

    PackedSectionMembership snapshotMembership() {
        if (publishedMembershipRevision != membershipRevision) {
            membershipBuilder.reset(workBySection.size());
            for (SectionKey key : workBySection.keySet()) {
                membershipBuilder.addPacked(key.packed());
            }
            publishedMembership = membershipBuilder.buildCanonical(publishedMembership);
            publishedMembershipRevision = membershipRevision;
        }
        return publishedMembership;
    }

    long membershipRevision() {
        return membershipRevision;
    }

    public long pendingTriangles() {
        return pendingTriangles;
    }

    public long pendingBytes() {
        return pendingBytes;
    }

    public long enqueuedSections() {
        return enqueuedSections;
    }

    public long replacedSections() {
        return replacedSections;
    }

    public long cancelledSections() {
        return cancelledSections;
    }

    public long evictedSections() {
        return evictedSections;
    }

    private void evictLowestPriorityUntilWithinBudget(SectionKey newestKey) {
        while ((workBySection.size() > sectionCapacity || pendingBytes > maxBytes)
                && workBySection.size() > 1) {
            SectionKey victimKey = lowestPriorityOldestKey(newestKey);
            Work<T> victim = workBySection.remove(victimKey);
            keysByLane.get(victim.lane()).remove(victimKey);
            membershipRevision = Math.incrementExact(membershipRevision);
            pendingTriangles -= victim.mesh().triangleCount();
            pendingBytes -= victim.mesh().estimatedBytes();
            evictedSections++;
        }
    }

    private SectionKey lowestPriorityOldestKey(SectionKey newestKey) {
        SectionWorkLane[] lanes = SectionWorkLane.values();
        Work<T> newest = Objects.requireNonNull(workBySection.get(newestKey), "newest queued BLAS work");
        for (int laneIndex = lanes.length - 1; laneIndex >= newest.lane().rank(); laneIndex--) {
            for (SectionKey key : keysByLane.get(lanes[laneIndex])) {
                if (!key.equals(newestKey)) {
                    return key;
                }
            }
        }
        if (workBySection.containsKey(newestKey)) {
            return newestKey;
        }
        throw new IllegalStateException("over-budget BLAS queue has no eviction candidate");
    }

    record Work<T>(SectionTriangleMesh mesh, T payload, SectionWorkLane lane) {
        Work {
            mesh = Objects.requireNonNull(mesh, "mesh");
            lane = Objects.requireNonNull(lane, "lane");
        }

        Work<T> withLane(SectionWorkLane replacement) {
            if (lane == replacement) {
                return this;
            }
            return new Work<>(mesh, payload, replacement);
        }
    }

}

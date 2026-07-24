package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import org.lwjgl.vulkan.VkDevice;
import top.ceroxe.mcvulkanrt.renderer.DynamicRenderScene;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameCausality;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtCommandContext;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;

/**
 * Owns the high-churn animated-instance TLAS separately from terrain.
 *
 * <p>host ModelPart animation changes many cube transforms every rendered
 * frame.  Terrain instance slots are persistent and must not be copied into
 * that update stream.  This cache therefore has its own bounded physical slot
 * table and descriptor-generation retirement queue.</p>
 */
public final class RtDynamicTlasCache implements AutoCloseable {
    /*
     * Transform-only host animation can advance far faster than a human
     * can observe a new acceleration structure.  UE keeps primitive data
     * dirty independently and coalesces BLAS/TLAS work to a bounded cadence;
     * retain that split here while topology and geometry changes remain
     * immediate correctness barriers.
     */
    private static final long TRANSFORM_ONLY_UPDATE_INTERVAL_NANOS = 8_000_000L;
    private static final int MAX_REUSABLE_DESTINATION_TLAS = 2;
    private final VkDevice device;
    private final long allocator;
    private final RtCommandContext commandContext;
    private final int scratchAlignmentBytes;
    private final long placeholderBlasDeviceAddress;
    private final RtAccelerationStructure.TlasInstance inactiveInstance;
    private final RtAccelerationStructure.PersistentTlasBuildInputs persistentBuildInputs;
    /** Descriptor-visible TLAS. Only {@link #commitBound(Update, long)} may replace it. */
    private RtAccelerationStructure active;
    private Pending pending;
    /** GPU-complete TLAS candidate retained until a descriptor generation publishes it. */
    private Update candidate;
    private final List<Retired> retired = new ArrayList<>();
    private final List<RtAccelerationStructure> reusableDestinations = new ArrayList<>();
    private int capacity;
    private long builtRevision = -1L;
    private long builtTopologyRevision = -1L;
    private long builtGeometryRevision = -1L;
    private long builtTransformRevision = -1L;
    private int builtInstanceLayoutHash;
    private long submissions;
    private long updates;
    private long completions;
    private long destinationReuses;
    private long destinationRecycles;
    private long inputInstances;
    private long physicalInstances;
    private long nextTransformOnlyUpdateNanos;
    private long cachedInputTopologyRevision = -1L;
    private long cachedInputGeometryRevision = -1L;
    private int cachedInputLayoutHash;
    private boolean closed;

    public RtDynamicTlasCache(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            long placeholderBlasDeviceAddress
    ) {
        this.device = Objects.requireNonNull(device, "device");
        if (allocator == 0L || placeholderBlasDeviceAddress == 0L || scratchAlignmentBytes <= 0) {
            throw new IllegalArgumentException("invalid dynamic TLAS device resources");
        }
        this.allocator = allocator;
        this.commandContext = Objects.requireNonNull(commandContext, "commandContext");
        this.scratchAlignmentBytes = scratchAlignmentBytes;
        this.placeholderBlasDeviceAddress = placeholderBlasDeviceAddress;
        this.inactiveInstance = RtAccelerationStructure.TlasInstance.inactive(placeholderBlasDeviceAddress);
        this.persistentBuildInputs = new RtAccelerationStructure.PersistentTlasBuildInputs(
                device,
                allocator,
                commandContext.stallTelemetry()
        );
    }

    public synchronized Update process(RtDynamicInstanceSnapshot input) {
        return process(
                input,
                TRANSFORM_ONLY_UPDATE_INTERVAL_NANOS,
                RendererFrameCausality.untraced(0L)
        );
    }

    public synchronized Update process(
            RtDynamicInstanceSnapshot input,
            RendererFrameCausality causality
    ) {
        return process(input, TRANSFORM_ONLY_UPDATE_INTERVAL_NANOS, causality);
    }

    /**
     * Polls GPU completion without constructing the heavyweight physical-slot snapshot.
     *
     * <p>A completed generation must advance monotonically even when capture has already produced
     * a successor. Discarding every topology-stale completion starves binding under ordinary entity
     * churn: the producer can advance faster than the GPU indefinitely. Each candidate retains its
     * matching immutable instance/material snapshot, so publishing it briefly is coherent and lets
     * the next generation build from a newer descriptor-visible predecessor.</p>
     */
    public synchronized Update pollCompletedCandidate(RtDynamicInstanceStats input) {
        Objects.requireNonNull(input, "input");
        if (closed) {
            throw new IllegalStateException("dynamic TLAS cache is closed");
        }
        pollCompletedCandidate();
        if (candidate != null && !shouldPublishCompletedGeneration(
                candidate.revision(), builtRevision, input.revision()
        )) {
            throw new IllegalStateException("completed dynamic TLAS generation is not monotonic");
        }
        return candidate;
    }

    /** Returns whether the caller must materialize the full persistent instance table. */
    public synchronized boolean snapshotRequired(RtDynamicInstanceStats input) {
        return snapshotRequired(input, TRANSFORM_ONLY_UPDATE_INTERVAL_NANOS);
    }

    /** Returns whether the caller must materialize the full persistent instance table. */
    public synchronized boolean snapshotRequired(
            RtDynamicInstanceStats input,
            long transformOnlyUpdateIntervalNanos
    ) {
        Objects.requireNonNull(input, "input");
        if (transformOnlyUpdateIntervalNanos <= 0L) {
            throw new IllegalArgumentException("transform-only update interval must be positive");
        }
        if (closed) {
            throw new IllegalStateException("dynamic TLAS cache is closed");
        }
        return shouldMaterializeSnapshot(
                pending != null,
                candidate != null,
                active != null,
                input.revision(),
                builtRevision,
                input.topologyRevision(),
                builtTopologyRevision,
                input.geometryRevision(),
                builtGeometryRevision,
                input.transformRevision(),
                builtTransformRevision,
                System.nanoTime(),
                nextTransformOnlyUpdateNanos
        );
    }

    /**
     * Advances completed work on every call while coalescing only transform-only successors.
     * Topology and geometry changes remain immediate correctness barriers.
     */
    public synchronized Update process(
            RtDynamicInstanceSnapshot input,
            long transformOnlyUpdateIntervalNanos
    ) {
        return process(
                input,
                transformOnlyUpdateIntervalNanos,
                RendererFrameCausality.untraced(0L)
        );
    }

    public synchronized Update process(
            RtDynamicInstanceSnapshot input,
            long transformOnlyUpdateIntervalNanos,
            RendererFrameCausality causality
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(causality, "causality");
        if (transformOnlyUpdateIntervalNanos <= 0L) {
            throw new IllegalArgumentException("transform-only update interval must be positive");
        }
        if (closed) {
            throw new IllegalStateException("dynamic TLAS cache is closed");
        }
        int candidateLayoutHash;
        if (cachedInputTopologyRevision == input.topologyRevision()
                && cachedInputGeometryRevision == input.geometryRevision()) {
            candidateLayoutHash = cachedInputLayoutHash;
        } else {
            candidateLayoutHash = instanceLayoutHash(input.instances());
            cachedInputTopologyRevision = input.topologyRevision();
            cachedInputGeometryRevision = input.geometryRevision();
            cachedInputLayoutHash = candidateLayoutHash;
        }
        pollCompletedCandidate();
        if (candidate != null) {
            if (!shouldPublishCompletedGeneration(candidate.revision(), builtRevision, input.revision())) {
                throw new IllegalStateException("completed dynamic TLAS generation is not monotonic");
            }
            return candidate;
        }
        boolean transformOnlySuccessor = isTransformOnlySuccessor(input, candidateLayoutHash);
        long nowNanos = System.nanoTime();
        if (shouldDeferTransformOnlyUpdate(
                transformOnlySuccessor,
                nowNanos,
                nextTransformOnlyUpdateNanos
        )) {
            return null;
        }
        if (!shouldSubmitSnapshot(
                pending != null,
                input.revision(),
                builtRevision,
                candidateLayoutHash,
                builtInstanceLayoutHash
        )) {
            return null;
        }
        List<RtAccelerationStructure.TlasInstance> instances = input.instances();
        int activeInstances = input.activeInstanceCount();
        int logicalInstanceCount = instances.size();
        /* The input already owns stable physical slots, including inactive holes. */
        int nextCapacity = RtWorldTlasCache.persistentInstanceCapacity(Math.max(1, instances.size()), capacity);
        if (instances.size() < nextCapacity) {
            instances = new PaddedTlasInstances(instances, nextCapacity, inactiveInstance);
        }
        boolean update = active != null && nextCapacity == capacity;
        int[] dirtyInstanceSlots = submissionDirtySlots(
                input,
                logicalInstanceCount,
                nextCapacity,
                update
        );
        RtAccelerationStructure reusableDestination = update ? takeReusableDestination() : null;
        RtAccelerationStructure.WorldTlasBuildSubmission submission = update
                ? RtAccelerationStructure.submitPersistentWorldTlasUpdateAsync(
                        device,
                        allocator,
                        commandContext,
                        scratchAlignmentBytes,
                        active,
                        reusableDestination,
                        instances,
                        dirtyInstanceSlots,
                        persistentBuildInputs
                )
                : RtAccelerationStructure.submitPersistentWorldTlasAsync(
                        device,
                        allocator,
                        commandContext,
                        scratchAlignmentBytes,
                        instances,
                        dirtyInstanceSlots,
                        persistentBuildInputs
                );
        pending = new Pending(
                submission,
                input,
                input.causality(),
                candidateLayoutHash,
                activeInstances,
                nextCapacity,
                update
        );
        submissions++;
        if (update) {
            updates++;
        }
        inputInstances += activeInstances;
        physicalInstances += nextCapacity;
        if (transformOnlySuccessor) {
            nextTransformOnlyUpdateNanos = nowNanos + transformOnlyUpdateIntervalNanos;
        } else {
            nextTransformOnlyUpdateNanos = 0L;
        }
        return null;
    }

    synchronized RtAccelerationStructure current() {
        return active;
    }

    synchronized boolean ready() {
        return active != null;
    }

    synchronized boolean structureCurrent(RtDynamicBlasCache dynamic) {
        Objects.requireNonNull(dynamic, "dynamic");
        return active != null && builtTopologyRevision == dynamic.topologyRevision()
                && builtGeometryRevision == dynamic.geometryRevision();
    }

    synchronized boolean transformCurrent(RtDynamicBlasCache dynamic) {
        Objects.requireNonNull(dynamic, "dynamic");
        return structureCurrent(dynamic) && builtTransformRevision == dynamic.transformRevision();
    }

    public synchronized void commitBound(Update update, long retiredDescriptorGeneration) {
        validateCommitBound(update, retiredDescriptorGeneration);
        active = update.topLevelAccelerationStructure();
        capacity = update.capacity();
        builtRevision = update.revision();
        builtTopologyRevision = update.topologyRevision();
        builtGeometryRevision = update.geometryRevision();
        builtTransformRevision = update.transformRevision();
        builtInstanceLayoutHash = update.instanceLayoutHash();
        candidate = null;
        if (update.previous() != null) {
            retired.add(new Retired(update.previous(), retiredDescriptorGeneration));
        }
    }

    public synchronized void validateCommitBound(Update update, long retiredDescriptorGeneration) {
        Objects.requireNonNull(update, "update");
        if (candidate != update) {
            throw new IllegalStateException("cannot bind dynamic TLAS update that is not awaiting descriptor ownership");
        }
        if (update.previous() != active) {
            throw new IllegalStateException("cannot bind dynamic TLAS candidate built from a stale active generation");
        }
        if (retiredDescriptorGeneration < 0L) {
            throw new IllegalArgumentException("retired descriptor generation must not be negative");
        }
    }

    /**
     * Releases a GPU-complete TLAS generation whose descriptor transaction can no longer publish.
     *
     * <p>The identity check is intentional. A stale material/publication transaction may only
     * release the exact candidate it owns; accepting a revision or blindly clearing the slot could
     * destroy a newer candidate that has already taken its place. The descriptor-visible
     * {@link #active} TLAS is never touched by this path.</p>
     */
    public synchronized void discardUnbound(Update update) {
        Objects.requireNonNull(update, "update");
        if (candidate != update) {
            throw new IllegalStateException("cannot discard a dynamic TLAS update not owned by the pending bind");
        }
        candidate = null;
        recycleDestination(update.topLevelAccelerationStructure());
    }

    public synchronized void releaseRetiredThrough(long completedDescriptorGeneration) {
        for (int index = retired.size() - 1; index >= 0; index--) {
            Retired candidate = retired.get(index);
            if (candidate.descriptorGeneration() <= completedDescriptorGeneration) {
                recycleDestination(retired.remove(index).accelerationStructure());
            }
        }
    }

    public synchronized String summary() {
        return "dynamicTlas{ready=" + (active != null) + ", pending=" + (pending != null)
                + ", candidate=" + (candidate == null ? "none" : candidate.revision())
                + ", capacity=" + capacity + ", builtRevision=" + builtRevision
                + ", transformRevision=" + builtTransformRevision + ", submissions=" + submissions
                + ", updates=" + updates + ", completions=" + completions
                + ", inputInstances=" + inputInstances + ", physicalInstances=" + physicalInstances
                + ", destinationReuses=" + destinationReuses
                + ", destinationRecycles=" + destinationRecycles
                + ", reusableDestinations=" + reusableDestinations.size()
                + ", " + persistentBuildInputs.summary() + "}";
    }

    private void pollCompletedCandidate() {
        if (pending == null || candidate != null) {
            return;
        }
        RtAccelerationStructure.CompletedWorldTlasBuild completed = pending.submission().completeIfReady();
        if (completed == null) {
            return;
        }
        Pending completedPending = pending;
        pending = null;
        completions++;
        candidate = new Update(
                completed.accelerationStructure(),
                active,
                completedPending.snapshot().revision(),
                completedPending.snapshot().topologyRevision(),
                completedPending.snapshot().geometryRevision(),
                completedPending.snapshot().transformRevision(),
                completedPending.instanceLayoutHash(),
                completedPending.snapshot().dynamicScene(),
                completedPending.activeInstances(),
                completedPending.capacity(),
                completedPending.snapshot(),
                completedPending.snapshot().causality()
        );
    }

    private int[] submissionDirtySlots(
            RtDynamicInstanceSnapshot input,
            int logicalInstanceCount,
            int nextCapacity,
            boolean update
    ) {
        if (!update) {
            return allSlots(nextCapacity);
        }
        int[] source = input.instanceDirtySlots();
        boolean topologyChanged = input.topologyRevision() != builtTopologyRevision
                || input.geometryRevision() != builtGeometryRevision;
        if (logicalInstanceCount >= nextCapacity || !topologyChanged) {
            return source;
        }
        int paddingCount = nextCapacity - logicalInstanceCount;
        int[] merged = Arrays.copyOf(source, source.length + paddingCount);
        for (int index = 0; index < paddingCount; index++) {
            merged[source.length + index] = logicalInstanceCount + index;
        }
        return merged;
    }

    private static int[] allSlots(int size) {
        int[] slots = new int[size];
        for (int index = 0; index < size; index++) {
            slots[index] = index;
        }
        return slots;
    }

    /** Immutable capacity view; inactive tail slots do not allocate a duplicate full table. */
    private static final class PaddedTlasInstances
            extends AbstractList<RtAccelerationStructure.TlasInstance>
            implements RandomAccess, RtImmutableTlasInstances {
        private final List<RtAccelerationStructure.TlasInstance> base;
        private final int capacity;
        private final RtAccelerationStructure.TlasInstance inactive;

        private PaddedTlasInstances(
                List<RtAccelerationStructure.TlasInstance> base,
                int capacity,
                RtAccelerationStructure.TlasInstance inactive
        ) {
            this.base = Objects.requireNonNull(base, "base");
            if (capacity <= 0 || capacity < base.size()) {
                throw new IllegalArgumentException("padded TLAS capacity must cover its immutable base");
            }
            this.capacity = capacity;
            this.inactive = Objects.requireNonNull(inactive, "inactive");
        }

        @Override
        public RtAccelerationStructure.TlasInstance get(int index) {
            Objects.checkIndex(index, capacity);
            return index < base.size() ? base.get(index) : inactive;
        }

        @Override
        public int size() {
            return capacity;
        }
    }

    private RtAccelerationStructure takeReusableDestination() {
        if (reusableDestinations.isEmpty()) {
            return null;
        }
        int largestIndex = 0;
        long largestBytes = reusableDestinations.get(0).storageBytes();
        for (int index = 1; index < reusableDestinations.size(); index++) {
            long candidateBytes = reusableDestinations.get(index).storageBytes();
            if (candidateBytes > largestBytes) {
                largestIndex = index;
                largestBytes = candidateBytes;
            }
        }
        destinationReuses++;
        return reusableDestinations.remove(largestIndex);
    }

    private void recycleDestination(RtAccelerationStructure destination) {
        if (destination == null) {
            return;
        }
        if (reusableDestinations.size() >= MAX_REUSABLE_DESTINATION_TLAS) {
            destination.close();
            return;
        }
        reusableDestinations.add(destination);
        destinationRecycles++;
    }

    static int instanceLayoutHash(List<RtAccelerationStructure.TlasInstance> instances) {
        Objects.requireNonNull(instances, "instances");
        int result = 1;
        for (RtAccelerationStructure.TlasInstance instance : instances) {
            result = 31 * result + instance.customIndex();
            result = 31 * result + Long.hashCode(instance.blasDeviceAddress());
        }
        return result;
    }

    static boolean shouldSubmitSnapshot(
            boolean submissionPending,
            long snapshotRevision,
            long builtRevision,
            int snapshotLayoutHash,
            int builtLayoutHash
    ) {
        if (snapshotRevision < 0L || builtRevision < -1L) {
            throw new IllegalArgumentException("dynamic TLAS revisions are invalid");
        }
        return !submissionPending
                && (snapshotRevision != builtRevision || snapshotLayoutHash != builtLayoutHash);
    }

    static boolean shouldPublishCompletedGeneration(
            long candidateRevision,
            long builtRevision,
            long latestObservedRevision
    ) {
        if (candidateRevision < 0L || builtRevision < -1L || latestObservedRevision < 0L) {
            throw new IllegalArgumentException("dynamic TLAS publication revisions are invalid");
        }
        return candidateRevision > builtRevision && candidateRevision <= latestObservedRevision;
    }

    static boolean shouldDeferTransformOnlyUpdate(
            boolean transformOnlySuccessor,
            long nowNanos,
            long nextUpdateNanos
    ) {
        /* nanoTime deadlines are compared by subtraction so wraparound remains well-defined. */
        return transformOnlySuccessor && nowNanos - nextUpdateNanos < 0L;
    }

    static boolean shouldMaterializeSnapshot(
            boolean submissionPending,
            boolean candidatePresent,
            boolean activePresent,
            long inputRevision,
            long builtRevision,
            long inputTopologyRevision,
            long builtTopologyRevision,
            long inputGeometryRevision,
            long builtGeometryRevision,
            long inputTransformRevision,
            long builtTransformRevision,
            long nowNanos,
            long nextTransformUpdateNanos
    ) {
        if (inputRevision < 0L || builtRevision < -1L
                || inputTopologyRevision < 0L || builtTopologyRevision < -1L
                || inputGeometryRevision < 0L || builtGeometryRevision < -1L
                || inputTransformRevision < 0L || builtTransformRevision < -1L) {
            throw new IllegalArgumentException("dynamic TLAS scheduler revisions are invalid");
        }
        if (submissionPending || candidatePresent || inputRevision == builtRevision) {
            return false;
        }
        boolean transformOnlySuccessor = activePresent
                && inputTopologyRevision == builtTopologyRevision
                && inputGeometryRevision == builtGeometryRevision
                && inputTransformRevision != builtTransformRevision;
        return !shouldDeferTransformOnlyUpdate(
                transformOnlySuccessor,
                nowNanos,
                nextTransformUpdateNanos
        );
    }

    private boolean isTransformOnlySuccessor(
            RtDynamicInstanceSnapshot input,
            int candidateLayoutHash
    ) {
        return active != null
                && input.topologyRevision() == builtTopologyRevision
                && input.geometryRevision() == builtGeometryRevision
                && candidateLayoutHash == builtInstanceLayoutHash
                && input.transformRevision() != builtTransformRevision;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        if (pending != null) {
            try {
                pending.submission().close();
            } catch (RuntimeException ex) {
                failure = ex;
            }
            pending = null;
        }
        Update discarded = candidate;
        candidate = null;
        if (discarded != null) {
            failure = closeCollecting(failure, discarded.topLevelAccelerationStructure());
        }
        if (active != null) {
            failure = closeCollecting(failure, active);
            active = null;
        }
        for (Retired candidate : retired) {
            failure = closeCollecting(failure, candidate.accelerationStructure());
        }
        retired.clear();
        for (RtAccelerationStructure destination : reusableDestinations) {
            failure = closeCollecting(failure, destination);
        }
        reusableDestinations.clear();
        failure = closeCollecting(failure, persistentBuildInputs);
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException closeCollecting(RuntimeException failure, AutoCloseable resource) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
        } catch (Exception ex) {
            if (failure == null) {
                return ex instanceof RuntimeException runtime ? runtime : new RuntimeException(ex);
            }
            failure.addSuppressed(ex);
        }
        return failure;
    }

    public record Update(
            RtAccelerationStructure topLevelAccelerationStructure,
            RtAccelerationStructure previous,
            long revision,
            long topologyRevision,
            long geometryRevision,
            long transformRevision,
            int instanceLayoutHash,
            DynamicRenderScene dynamicScene,
            int activeInstances,
            int capacity,
            RtDynamicInstanceSnapshot instanceSnapshot,
            RendererFrameCausality causality
    ) {
        public Update {
            topLevelAccelerationStructure = Objects.requireNonNull(
                    topLevelAccelerationStructure,
                    "topLevelAccelerationStructure"
            );
            dynamicScene = Objects.requireNonNull(dynamicScene, "dynamicScene");
            instanceSnapshot = Objects.requireNonNull(instanceSnapshot, "instanceSnapshot");
            causality = Objects.requireNonNull(causality, "causality");
            if (topLevelAccelerationStructure == previous) {
                throw new IllegalArgumentException("dynamic TLAS candidate must not alias its active predecessor");
            }
            if (revision < 0L || topologyRevision < 0L || geometryRevision < 0L || transformRevision < 0L) {
                throw new IllegalArgumentException("dynamic TLAS candidate revisions must not be negative");
            }
            if (activeInstances < 0 || capacity <= 0 || activeInstances > capacity) {
                throw new IllegalArgumentException("dynamic TLAS candidate instance counts are invalid");
            }
            if (instanceSnapshot.revision() != revision
                    || instanceSnapshot.topologyRevision() != topologyRevision
                    || instanceSnapshot.geometryRevision() != geometryRevision
                    || instanceSnapshot.transformRevision() != transformRevision
                    || instanceSnapshot.dynamicScene() != dynamicScene) {
                throw new IllegalArgumentException("dynamic TLAS candidate snapshot identity is inconsistent");
            }
        }
    }

    private record Pending(
            RtAccelerationStructure.WorldTlasBuildSubmission submission,
            RtDynamicInstanceSnapshot snapshot,
            RendererFrameCausality causality,
            int instanceLayoutHash,
            int activeInstances,
            int capacity,
            boolean update
    ) {
        private Pending {
            submission = Objects.requireNonNull(submission, "submission");
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            causality = Objects.requireNonNull(causality, "causality");
            if (activeInstances < 0 || capacity <= 0 || activeInstances > capacity) {
                throw new IllegalArgumentException("pending dynamic TLAS instance counts are invalid");
            }
            if (snapshot.activeInstanceCount() != activeInstances) {
                throw new IllegalArgumentException("pending dynamic TLAS snapshot count is inconsistent");
            }
            if (snapshot.causality() != causality) {
                throw new IllegalArgumentException("pending dynamic TLAS causality must retain snapshot identity");
            }
        }
    }

    private record Retired(RtAccelerationStructure accelerationStructure, long descriptorGeneration) { }
}

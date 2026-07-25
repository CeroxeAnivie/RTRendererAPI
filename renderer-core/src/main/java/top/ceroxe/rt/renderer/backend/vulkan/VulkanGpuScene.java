package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.MeshAsset;
import top.ceroxe.rt.renderer.api.SceneInstance;

import java.util.Objects;

/**
 * Transaction and lifetime authority for one persistent GPUScene.
 *
 * <p>A scene generation has two deliberately separate milestones. Acceptance means the complete
 * CPU placement/index generation and its immutable Vulkan transfer were admitted atomically.
 * Activation means the transfer fence completed and descriptors may expose that generation to
 * BLAS/TLAS construction or ray dispatch. Consumers must never infer activation from acceptance.</p>
 */
final class VulkanGpuScene implements AutoCloseable {
    private final VulkanGpuSceneMemory memory;
    private final VulkanGpuSceneIdentityIndex identities;
    private final VulkanGpuSceneTransferQueue transfers;

    private Lifecycle lifecycle = Lifecycle.READY;
    private PendingGeneration pending;
    private long acceptedRevision = -1L;
    private long activeRevision = -1L;
    private long completedDescriptorEpoch = -1L;
    private Throwable terminalFailure;

    VulkanGpuScene(VulkanGpuSceneTransferQueue transfers) {
        this(new VulkanGpuSceneMemory(), new VulkanGpuSceneIdentityIndex(), transfers);
    }

    VulkanGpuScene(
            VulkanGpuSceneMemory memory,
            VulkanGpuSceneIdentityIndex identities,
            VulkanGpuSceneTransferQueue transfers
    ) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.transfers = Objects.requireNonNull(transfers, "transfers");
    }

    private static void requireRange(
            VulkanGpuSceneTransferQueue.BufferBinding binding,
            long offset,
            long bytes,
            String label
    ) {
        if (offset < 0L || bytes <= 0L || offset > binding.capacityBytes()
                || bytes > binding.capacityBytes() - offset) {
            throw new IllegalStateException("GPUScene " + label + " placement exceeds active buffer capacity");
        }
    }

    synchronized Admission submit(
            VulkanSceneResidency.SceneChangeSet changeSet,
            long retireAfterDescriptorEpoch
    ) throws BusyException {
        requireReady("submit scene generation");
        VulkanSceneResidency.SceneChangeSet changes = Objects.requireNonNull(changeSet, "changeSet");
        if (retireAfterDescriptorEpoch < 0L) {
            throw new IllegalArgumentException("retire descriptor epoch must not be negative");
        }
        pollPending();
        if (pending != null) {
            throw new BusyException(
                    "GPUScene generation " + pending.revision + " is still transferring"
            );
        }
        if (changes.baseRevision() != acceptedRevision) {
            throw new IllegalStateException(
                    "GPUScene accepted revision diverged from resident changes: accepted="
                            + acceptedRevision + ", base=" + changes.baseRevision()
            );
        }

        VulkanGpuSceneMemory.Prepared preparedMemory;
        VulkanGpuSceneIdentityIndex.Prepared preparedIdentities;
        VulkanGpuSceneUploadPlanner.Plan uploadPlan;
        try {
            preparedMemory = memory.prepare(changes);
            preparedIdentities = identities.prepare(changes);
            uploadPlan = VulkanGpuSceneUploadPlanner.plan(changes, preparedMemory, preparedIdentities);
            // Validate every CPU owner before native submission. Commits below cannot reject after
            // the queue owns the transfer ticket, preventing a recoverable half-generation.
            memory.validate(preparedMemory, retireAfterDescriptorEpoch);
            identities.validate(preparedIdentities);
        } catch (RuntimeException preparationFailure) {
            throw fail("prepare GPUScene generation " + changes.revision(), preparationFailure);
        }

        VulkanGpuSceneTransferQueue.TransferTicket ticket;
        try {
            ticket = Objects.requireNonNull(transfers.submit(uploadPlan), "GPUScene transfer ticket");
        } catch (VulkanMemoryBudgetRejectedException budgetRejection) {
            throw new BusyException(budgetRejection.getMessage(), budgetRejection);
        } catch (RuntimeException nativeFailure) {
            throw fail("submit GPUScene generation " + changes.revision(), nativeFailure);
        }

        PendingGeneration generation = new PendingGeneration(
                changes.revision(), retireAfterDescriptorEpoch, ticket
        );
        pending = generation;
        try {
            if (ticket.revision() != changes.revision()) {
                throw new IllegalStateException(
                        "GPUScene transfer accepted a different revision: expected=" + changes.revision()
                                + ", actual=" + ticket.revision()
                );
            }
            memory.commitValidated(preparedMemory, retireAfterDescriptorEpoch);
            identities.commitValidated(preparedIdentities);
            acceptedRevision = changes.revision();
            pollPending();
            return new Admission(
                    acceptedRevision,
                    activeRevision >= acceptedRevision,
                    uploadPlan.uploadBytes(),
                    uploadPlan.logicalRecords()
            );
        } catch (RuntimeException publicationFailure) {
            throw fail("publish GPUScene generation " + changes.revision(), publicationFailure);
        }
    }

    /**
     * Polls transfer completion and releases resources whose descriptor users have completed.
     */
    synchronized Snapshot poll(long latestCompletedDescriptorEpoch) {
        requireReady("poll GPUScene");
        if (latestCompletedDescriptorEpoch < completedDescriptorEpoch) {
            throw new IllegalArgumentException(
                    "completed descriptor epoch regressed: current=" + completedDescriptorEpoch
                            + ", supplied=" + latestCompletedDescriptorEpoch
            );
        }
        try {
            pollPending();
            if (latestCompletedDescriptorEpoch >= 0L) {
                memory.releaseThrough(latestCompletedDescriptorEpoch);
                transfers.releaseRetiredThrough(latestCompletedDescriptorEpoch);
                completedDescriptorEpoch = latestCompletedDescriptorEpoch;
            }
            return snapshot();
        } catch (RuntimeException nativeFailure) {
            throw fail("poll or retire GPUScene resources", nativeFailure);
        }
    }

    /**
     * Advances native fences without making any descriptor-retirement claim.
     */
    synchronized Snapshot pollCompletion() {
        requireReady("poll GPUScene completion");
        try {
            pollPending();
            return snapshot();
        } catch (RuntimeException nativeFailure) {
            throw fail("poll GPUScene completion", nativeFailure);
        }
    }

    synchronized VulkanGpuSceneTransferQueue.BufferBinding requireBuffer(
            VulkanGpuSceneUploadPlanner.Target target,
            long requiredSceneRevision
    ) {
        requireReady("resolve GPUScene buffer");
        if (requiredSceneRevision < 0L || activeRevision < requiredSceneRevision) {
            throw new IllegalStateException(
                    "GPUScene revision is not active: required=" + requiredSceneRevision
                            + ", active=" + activeRevision
            );
        }
        VulkanGpuSceneTransferQueue.BufferBinding binding = transfers.buffer(
                Objects.requireNonNull(target, "target")
        );
        if (binding == null) {
            throw new IllegalStateException("active GPUScene has no buffer for " + target);
        }
        return binding;
    }

    synchronized MeshGeometry resolveMesh(MeshAsset mesh, long requiredSceneRevision) {
        MeshAsset checked = Objects.requireNonNull(mesh, "mesh");
        requireExactActiveRevision(requiredSceneRevision);
        int slot = identities.meshSlot(checked.id());
        VulkanGpuSceneAbi.GeometryPlacement placement = memory.geometryPlacement(checked.id());
        if (slot < 0 || placement == null) {
            throw new IllegalStateException("mesh is not resident in active GPUScene: " + checked.id());
        }
        VulkanGpuSceneTransferQueue.BufferBinding positions = transfers.buffer(
                VulkanGpuSceneUploadPlanner.Target.POSITIONS
        );
        VulkanGpuSceneTransferQueue.BufferBinding indices = transfers.buffer(
                VulkanGpuSceneUploadPlanner.Target.INDICES
        );
        if (positions == null || indices == null) {
            throw new IllegalStateException("active GPUScene geometry buffers are unavailable");
        }
        long positionBytes = Math.multiplyExact((long) checked.vertexCount(), 3L * Float.BYTES);
        long indexBytes = Math.multiplyExact((long) checked.triangleCount(), 3L * Integer.BYTES);
        requireRange(positions, placement.positionBytes(), positionBytes, "positions");
        requireRange(indices, placement.indexBytes(), indexBytes, "indices");
        return new MeshGeometry(
                checked.id(),
                slot,
                Math.addExact(positions.deviceAddress(), placement.positionBytes()),
                Math.addExact(indices.deviceAddress(), placement.indexBytes()),
                checked.vertexCount(),
                checked.triangleCount()
        );
    }

    synchronized InstanceGeometry resolveInstance(SceneInstance instance, long requiredSceneRevision) {
        SceneInstance checked = Objects.requireNonNull(instance, "instance");
        requireExactActiveRevision(requiredSceneRevision);
        int instanceSlot = identities.instanceSlot(checked.id());
        int meshSlot = identities.meshSlot(checked.meshAssetId());
        if (instanceSlot < 0 || meshSlot < 0) {
            throw new IllegalStateException("instance or referenced mesh is not resident: " + checked.id());
        }
        return new InstanceGeometry(
                checked.id(),
                instanceSlot,
                meshSlot,
                checked.transform(),
                checked.visibilityMask()
        );
    }

    synchronized Snapshot snapshot() {
        VulkanGpuSceneTransferQueue.TransferState transferState = transfers.state();
        if (transferState.activeRevision() != activeRevision || transferState.pending() != (pending != null)) {
            throw fail(
                    "verify GPUScene transfer authority",
                    new IllegalStateException(
                            "coordinator and transfer queue state diverged: coordinator=" + activeRevision
                                    + "/" + (pending != null) + ", queue="
                                    + transferState.activeRevision() + "/" + transferState.pending()
                    )
            );
        }
        return new Snapshot(
                lifecycle,
                acceptedRevision,
                activeRevision,
                pending == null ? -1L : pending.revision,
                completedDescriptorEpoch,
                transferState,
                terminalFailure
        );
    }

    private void pollPending() {
        if (pending == null) return;
        if (transfers.pollAndActivate(pending.ticket, pending.retireAfterDescriptorEpoch)) {
            if (!pending.ticket.activated()) {
                throw new IllegalStateException("completed GPUScene transfer ticket was not activated");
            }
            activeRevision = pending.revision;
            pending = null;
        }
    }

    private void requireExactActiveRevision(long requiredSceneRevision) {
        requireReady("resolve active GPUScene generation");
        if (requiredSceneRevision < 0L || activeRevision != requiredSceneRevision) {
            throw new IllegalStateException(
                    "GPUScene revision mismatch: required=" + requiredSceneRevision
                            + ", active=" + activeRevision
            );
        }
    }

    private void requireReady(String operation) {
        if (lifecycle != Lifecycle.READY) {
            throw new IllegalStateException(
                    "cannot " + operation + " while GPUScene is " + lifecycle,
                    terminalFailure
            );
        }
    }

    private IllegalStateException fail(String operation, RuntimeException cause) {
        lifecycle = Lifecycle.FAILED;
        if (terminalFailure == null) terminalFailure = cause;
        else if (terminalFailure != cause) terminalFailure.addSuppressed(cause);
        return new IllegalStateException(operation + " failed", cause);
    }

    @Override
    public synchronized void close() {
        if (lifecycle == Lifecycle.CLOSED) return;
        lifecycle = Lifecycle.CLOSED;
        try {
            transfers.close();
        } catch (RuntimeException closeFailure) {
            if (terminalFailure != null) terminalFailure.addSuppressed(closeFailure);
            else throw closeFailure;
        } finally {
            pending = null;
        }
    }

    enum Lifecycle {
        READY,
        FAILED,
        CLOSED
    }

    record Admission(long acceptedRevision, boolean active, long uploadBytes, int logicalRecords) {
        Admission {
            if (acceptedRevision < 0L || uploadBytes < 0L || logicalRecords < 0) {
                throw new IllegalArgumentException("GPUScene admission counters are invalid");
            }
        }
    }

    record MeshGeometry(
            long meshIdentity,
            int meshSlot,
            long positionDeviceAddress,
            long indexDeviceAddress,
            int vertexCount,
            int primitiveCount
    ) {
        MeshGeometry {
            if (meshIdentity < 0L || meshSlot < 0 || positionDeviceAddress == 0L
                    || indexDeviceAddress == 0L || vertexCount <= 0 || primitiveCount <= 0) {
                throw new IllegalArgumentException("resolved GPUScene mesh geometry is invalid");
            }
        }
    }

    record InstanceGeometry(
            long instanceIdentity,
            int instanceSlot,
            int meshSlot,
            top.ceroxe.rt.renderer.api.AffineTransform transform,
            int visibilityMask
    ) {
        InstanceGeometry {
            if (instanceIdentity < 0L || instanceSlot < 0 || meshSlot < 0
                    || visibilityMask <= 0 || visibilityMask > 0xff) {
                throw new IllegalArgumentException("resolved GPUScene instance geometry is invalid");
            }
            transform = Objects.requireNonNull(transform, "transform");
        }
    }

    record Snapshot(
            Lifecycle lifecycle,
            long acceptedRevision,
            long activeRevision,
            long pendingRevision,
            long completedDescriptorEpoch,
            VulkanGpuSceneTransferQueue.TransferState transfers,
            Throwable terminalFailure
    ) {
        Snapshot {
            lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
            transfers = Objects.requireNonNull(transfers, "transfers");
            if (acceptedRevision < -1L || activeRevision < -1L || pendingRevision < -1L
                    || completedDescriptorEpoch < -1L || activeRevision > acceptedRevision) {
                throw new IllegalArgumentException("GPUScene snapshot contains invalid revisions");
            }
        }
    }

    static final class BusyException extends Exception {
        private static final long serialVersionUID = 1L;

        BusyException(String message) {
            super(Objects.requireNonNull(message, "message"));
        }

        BusyException(String message, Throwable cause) {
            super(Objects.requireNonNull(message, "message"), Objects.requireNonNull(cause, "cause"));
        }
    }

    private record PendingGeneration(
            long revision,
            long retireAfterDescriptorEpoch,
            VulkanGpuSceneTransferQueue.TransferTicket ticket
    ) {
        PendingGeneration {
            if (revision < 0L || retireAfterDescriptorEpoch < 0L) {
                throw new IllegalArgumentException("pending GPUScene generation counters are invalid");
            }
            ticket = Objects.requireNonNull(ticket, "ticket");
        }
    }
}

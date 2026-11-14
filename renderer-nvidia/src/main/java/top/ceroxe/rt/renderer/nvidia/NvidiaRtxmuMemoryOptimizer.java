package top.ceroxe.rt.renderer.nvidia;

import org.lwjgl.vulkan.VkDevice;
import top.ceroxe.rt.renderer.api.RendererDeviceException;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;
import top.ceroxe.rt.renderer.feature.VulkanAccelerationStructureMemoryOptimizer;
import top.ceroxe.rt.renderer.feature.VulkanFeatureRuntimeState;
import top.ceroxe.rt.renderer.rt.acceleration.RtAccelerationStructure;
import top.ceroxe.rt.renderer.rt.acceleration.RtBottomLevelBuild;
import top.ceroxe.rt.renderer.rt.acceleration.RtDeviceTriangleBlasBuilder;
import top.ceroxe.rt.renderer.rt.device.RtCommandContext;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Device-session RTXMU owner for suballocated, compacted GPUScene BLAS resources. */
final class NvidiaRtxmuMemoryOptimizer implements VulkanAccelerationStructureMemoryOptimizer {
    private static final String BUILD_TIMING_LABEL = "gpuSceneRtxmuTriangleBlas";
    private static final String COMPACTION_TIMING_LABEL = "gpuSceneRtxmuBlasCompaction";

    private final long nativeSession;
    private final VkDevice device;
    private final int scratchAlignmentBytes;
    private final RendererFeaturePreference preference;
    private final VulkanFeatureRuntimeState runtimeState;
    private volatile long completedBuilds;
    private boolean disabled;
    private FailureRecovery pendingRecovery;

    NvidiaRtxmuMemoryOptimizer(
            long nativeSession,
            VkDevice device,
            int scratchAlignmentBytes,
            RendererFeaturePreference preference,
            VulkanFeatureRuntimeState runtimeState
    ) {
        if (nativeSession == 0L) throw new IllegalArgumentException("nativeSession must not be null");
        if (scratchAlignmentBytes <= 0
                || (scratchAlignmentBytes & (scratchAlignmentBytes - 1)) != 0) {
            throw new IllegalArgumentException("scratch alignment must be a positive power of two");
        }
        this.nativeSession = nativeSession;
        this.device = Objects.requireNonNull(device, "device");
        this.scratchAlignmentBytes = scratchAlignmentBytes;
        this.preference = Objects.requireNonNull(preference, "preference");
        if (!preference.requested()) {
            throw new IllegalArgumentException("RTXMU optimizer requires a requested preference");
        }
        this.runtimeState = Objects.requireNonNull(runtimeState, "runtimeState");
    }

    @Override
    public synchronized RtBottomLevelBuild submitTriangleBlas(
            RtCommandContext commands,
            List<RtDeviceTriangleBlasBuilder.Geometry> geometries
    ) {
        if (disabled) throw new IllegalStateException("RTXMU is permanently disabled for this session");
        return new PendingBuild(Objects.requireNonNull(commands, "commands"), geometries);
    }

    @Override
    public synchronized Optional<FailureRecovery> beginFailureRecovery(Throwable failure) {
        Throwable checked = Objects.requireNonNull(failure, "failure");
        if (preference == RendererFeaturePreference.REQUIRED
                || checked instanceof RendererDeviceException
                || checked instanceof OutOfMemoryError) {
            return Optional.empty();
        }
        if (pendingRecovery != null) return Optional.of(pendingRecovery);
        disabled = true;
        String reason = describeFailure(checked);
        runtimeState.recovering(
                "core.vulkan.blas",
                reason + "; complete core BLAS generation submission pending"
        );
        pendingRecovery = new FailureRecovery() {
            private boolean committed;

            @Override
            public synchronized void commitCoreSubmission() {
                if (committed) return;
                committed = true;
                runtimeState.fallback(
                        "core.vulkan.blas",
                        reason + "; complete replacement core BLAS generation accepted by the Vulkan queue"
                );
            }
        };
        return Optional.of(pendingRecovery);
    }

    boolean executed() {
        return completedBuilds > 0L;
    }

    long completedBuilds() {
        return completedBuilds;
    }

    private synchronized void markExecuted() {
        completedBuilds = Math.incrementExact(completedBuilds);
        runtimeState.active(
                "nvidia.rtx-memory-utility",
                "RTXMU completed BLAS build, compaction, and transient-resource collection"
        );
    }

    boolean disabled() {
        synchronized (this) {
            return disabled;
        }
    }

    VulkanFeatureRuntimeState.Snapshot runtimeState() {
        return runtimeState.snapshot();
    }

    private static String describeFailure(Throwable failure) {
        String message = failure.getMessage();
        return "RTXMU failed: " + failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private final class PendingBuild implements RtBottomLevelBuild {
        private final RtCommandContext commands;
        private final int geometryCount;
        private final long primitiveCount;
        private final long sourceStorageBytes;
        private final long buildScratchBytes;
        private final long id;
        private RtCommandContext.AsyncSubmission submission;
        private NvidiaRtxmuRuntime.Build result;
        private long completedStorageBytes = -1L;
        private boolean copyPending;
        private boolean compacted;
        private boolean closed;

        private PendingBuild(
                RtCommandContext commands,
                List<RtDeviceTriangleBlasBuilder.Geometry> geometries
        ) {
            this.commands = commands;
            List<RtDeviceTriangleBlasBuilder.Geometry> checked =
                    List.copyOf(Objects.requireNonNull(geometries, "geometries"));
            if (checked.isEmpty()) throw new IllegalArgumentException("RTXMU BLAS requires geometry");
            geometryCount = checked.size();
            primitiveCount = checked.stream().mapToLong(RtDeviceTriangleBlasBuilder.Geometry::primitiveCount).sum();
            long[] positions = new long[geometryCount];
            long[] indices = new long[geometryCount];
            int[] vertices = new int[geometryCount];
            int[] primitives = new int[geometryCount];
            boolean[] opaque = new boolean[geometryCount];
            for (int index = 0; index < geometryCount; index++) {
                RtDeviceTriangleBlasBuilder.Geometry geometry = checked.get(index);
                positions[index] = geometry.positionDeviceAddress();
                indices[index] = geometry.indexDeviceAddress();
                vertices[index] = geometry.vertexCount();
                primitives[index] = geometry.primitiveCount();
                opaque[index] = geometry.opaque();
            }

            NvidiaRtxmuRuntime.Build[] recorded = new NvidiaRtxmuRuntime.Build[1];
            RtCommandContext.AsyncSubmission submitted = null;
            try {
                submitted = commands.submitTimedOneTimeAsync(
                        BUILD_TIMING_LABEL,
                        (commandBuffer, stack) -> recorded[0] = NvidiaRtxmuRuntime.recordBuild(
                                nativeSession,
                                commandBuffer.address(),
                                positions,
                                indices,
                                vertices,
                                primitives,
                                opaque
                        )
                );
                result = Objects.requireNonNull(recorded[0], "RTXMU build recorder result");
                submission = submitted;
                id = result.id();
                sourceStorageBytes = result.storageBytes();
                buildScratchBytes = result.scratchBytes();
            } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
                closeSuppressing(submitted, failure);
                if (recorded[0] != null) removeSuppressing(recorded[0].id(), failure);
                throw failure;
            }
        }

        @Override
        public synchronized RtAccelerationStructure completeIfReady() {
            requireOpen();
            if (!submission.pollComplete()) return null;
            return copyPending ? finishCompaction() : beginCompaction();
        }

        @Override
        public synchronized RtAccelerationStructure waitAndComplete() {
            requireOpen();
            submission.close();
            if (!copyPending) beginCompaction();
            submission.close();
            return finishCompaction();
        }

        private RtAccelerationStructure beginCompaction() {
            submission.close();
            NvidiaRtxmuRuntime.Build[] recorded = new NvidiaRtxmuRuntime.Build[1];
            RtCommandContext.AsyncSubmission submitted = null;
            try {
                submitted = commands.submitTimedOneTimeAsync(
                        COMPACTION_TIMING_LABEL,
                        (commandBuffer, stack) -> recorded[0] =
                                NvidiaRtxmuRuntime.recordCompaction(
                                        nativeSession, commandBuffer.address(), id
                                )
                );
                NvidiaRtxmuRuntime.Build compactedResult =
                        Objects.requireNonNull(recorded[0], "RTXMU compaction recorder result");
                if (compactedResult.id() != id) {
                    throw new IllegalStateException("RTXMU changed acceleration structure identity");
                }
                result = compactedResult;
                submission = submitted;
                completedStorageBytes = result.storageBytes();
                copyPending = true;
                compacted = true;
                return null;
            } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
                closeSuppressing(submitted, failure);
                removeSuppressing(id, failure);
                closed = true;
                throw failure;
            }
        }

        private RtAccelerationStructure finishCompaction() {
            submission.close();
            NvidiaRtxmuRuntime.garbageCollect(nativeSession, id);
            markExecuted();
            RtAccelerationStructure completed = RtAccelerationStructure.wrapExternalBottomLevel(
                    device,
                    result.accelerationStructure(),
                    result.deviceAddress(),
                    result.storageBytes(),
                    buildScratchBytes,
                    scratchAlignmentBytes,
                    () -> NvidiaRtxmuRuntime.remove(nativeSession, id)
            );
            closed = true;
            return completed;
        }

        @Override
        public int geometryCount() {
            return geometryCount;
        }

        @Override
        public long primitiveCount() {
            return primitiveCount;
        }

        @Override
        public long sourceStorageBytes() {
            return sourceStorageBytes;
        }

        @Override
        public long completedStorageBytes() {
            return completedStorageBytes;
        }

        @Override
        public boolean compacted() {
            return compacted;
        }

        private void requireOpen() {
            if (closed) throw new IllegalStateException("RTXMU BLAS build is already completed or closed");
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            RuntimeException failure = null;
            try {
                submission.close();
            } catch (RuntimeException closeFailure) {
                failure = closeFailure;
            }
            try {
                NvidiaRtxmuRuntime.remove(nativeSession, id);
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
            if (failure != null) throw failure;
        }
    }

    private static void closeSuppressing(AutoCloseable resource, Throwable failure) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private void removeSuppressing(long id, Throwable failure) {
        try {
            NvidiaRtxmuRuntime.remove(nativeSession, id);
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}

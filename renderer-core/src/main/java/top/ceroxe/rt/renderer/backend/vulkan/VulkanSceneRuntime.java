package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.RayTracingRendererConfig;
import top.ceroxe.rt.renderer.api.RendererPreset;
import top.ceroxe.rt.renderer.api.FramePrimitiveBatch;
import top.ceroxe.rt.renderer.rt.acceleration.RtAccelerationStructure;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

import java.util.Objects;

/**
 * Production scene engine coordinating GPUScene transfer and acceleration generations.
 *
 * <p>Admission occurs only after the immutable GPUScene transfer enters the real Vulkan queue.
 * BLAS/TLAS activation remains asynchronous and is explicitly observable. A second scene update is
 * rejected while either stage owns the prior generation, so no caller can overwrite the immutable
 * change set required to finish acceleration construction.</p>
 */
final class VulkanSceneRuntime implements AutoCloseable {
    private final VulkanDeviceRuntime device;
    private final VulkanGpuScene gpuScene;
    private final VulkanSceneAcceleration acceleration;

    private Lifecycle lifecycle = Lifecycle.READY;
    private VulkanSceneResidency.SceneChangeSet pendingAccelerationChanges;
    private long pendingRetireEpoch = -1L;
    private long acceptedRevision = -1L;
    private long completedDescriptorEpoch = -1L;
    private Throwable terminalFailure;

    static VulkanSceneRuntime open(
            VulkanRtCapabilityProbe.Result capability,
            RendererRtDiagnostics diagnostics
    ) {
        return open(capability, diagnostics, false);
    }

    static VulkanSceneRuntime open(
            VulkanRtCapabilityProbe.Result capability,
            RendererRtDiagnostics diagnostics,
            boolean validationEnabled
    ) {
        return open(capability, diagnostics, validationEnabled, true);
    }

    static VulkanSceneRuntime open(
            VulkanRtCapabilityProbe.Result capability,
            RendererRtDiagnostics diagnostics,
            boolean validationEnabled,
            boolean gpuTimingsEnabled
    ) {
        return open(
                capability,
                diagnostics,
                validationEnabled,
                gpuTimingsEnabled,
                RendererPreset.CPU_READBACK.configuration()
        );
    }

    static VulkanSceneRuntime open(
            VulkanRtCapabilityProbe.Result capability,
            RendererRtDiagnostics diagnostics,
            boolean validationEnabled,
            boolean gpuTimingsEnabled,
            RayTracingRendererConfig configuration
    ) {
        VulkanDeviceRuntime device = null;
        VulkanGpuScene gpuScene = null;
        VulkanSceneAcceleration acceleration = null;
        try {
            device = VulkanDeviceRuntime.open(
                    Objects.requireNonNull(capability, "capability"),
                    Objects.requireNonNull(diagnostics, "diagnostics"),
                    validationEnabled,
                    gpuTimingsEnabled,
                    Objects.requireNonNull(configuration, "configuration")
            );
            gpuScene = new VulkanGpuScene(new VulkanGpuSceneBuffers(
                    device.device(), device.allocator(), device.buildCommands(), device::memoryBudget
            ));
            acceleration = new VulkanSceneAcceleration(device, gpuScene);
            VulkanSceneRuntime runtime = new VulkanSceneRuntime(device, gpuScene, acceleration);
            device = null;
            gpuScene = null;
            acceleration = null;
            return runtime;
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            closeSuppressing(failure, acceleration);
            closeSuppressing(failure, gpuScene);
            closeSuppressing(failure, device);
            throw failure;
        }
    }

    VulkanSceneRuntime(
            VulkanDeviceRuntime device,
            VulkanGpuScene gpuScene,
            VulkanSceneAcceleration acceleration
    ) {
        this.device = Objects.requireNonNull(device, "device");
        this.gpuScene = Objects.requireNonNull(gpuScene, "gpuScene");
        this.acceleration = Objects.requireNonNull(acceleration, "acceleration");
    }

    synchronized Admission apply(
            VulkanSceneResidency.SceneChangeSet changeSet,
            long retireAfterDescriptorEpoch
    ) throws BusyException {
        requireReady("apply scene generation");
        VulkanSceneResidency.SceneChangeSet changes = Objects.requireNonNull(changeSet, "changeSet");
        if (retireAfterDescriptorEpoch < 0L) {
            throw new IllegalArgumentException("retire descriptor epoch must not be negative");
        }
        pumpInternal();
        if (pendingAccelerationChanges != null
                || gpuScene.snapshot().acceptedRevision() != gpuScene.snapshot().activeRevision()
                || acceleration.snapshot().pendingRevision() >= 0L) {
            throw new BusyException("previous scene generation is still converging");
        }
        if (changes.baseRevision() != acceptedRevision) {
            throw fail(
                    "validate scene runtime revision",
                    new IllegalStateException(
                            "scene runtime revision diverged: accepted=" + acceptedRevision
                                    + ", base=" + changes.baseRevision()
                    )
            );
        }
        try {
            // The owning rendering session admits mutation only after all renderer producer fences
            // have completed. VulkanSceneRuntime has no presentation ownership and must never widen
            // that precise dependency into a whole-queue idle operation.
            VulkanGpuScene.Admission admission = gpuScene.submit(changes, retireAfterDescriptorEpoch);
            pendingAccelerationChanges = changes;
            pendingRetireEpoch = retireAfterDescriptorEpoch;
            acceptedRevision = changes.revision();
            pumpInternal();
            return new Admission(
                    acceptedRevision,
                    admission.uploadBytes(),
                    admission.logicalRecords(),
                    activeRevision() == acceptedRevision
            );
        } catch (VulkanGpuScene.BusyException busy) {
            throw new BusyException(busy.getMessage(), busy.getCause());
        } catch (RuntimeException failure) {
            throw fail("apply scene generation " + changes.revision(), failure);
        }
    }

    synchronized Snapshot poll(long latestCompletedDescriptorEpoch) {
        requireReady("poll scene runtime");
        if (latestCompletedDescriptorEpoch < completedDescriptorEpoch) {
            throw new IllegalArgumentException(
                    "completed descriptor epoch regressed: current=" + completedDescriptorEpoch
                            + ", supplied=" + latestCompletedDescriptorEpoch
            );
        }
        try {
            completedDescriptorEpoch = latestCompletedDescriptorEpoch;
            gpuScene.poll(latestCompletedDescriptorEpoch);
            acceleration.poll(latestCompletedDescriptorEpoch);
            pumpInternal();
            return snapshot();
        } catch (RuntimeException failure) {
            throw fail("poll scene runtime", failure);
        }
    }

    /** Advances transfer and AS fences without claiming any descriptor generation completed. */
    synchronized Snapshot pollCompletion() {
        requireReady("poll scene completion");
        try {
            pumpInternal();
            return snapshot();
        } catch (RuntimeException failure) {
            throw fail("poll scene completion", failure);
        }
    }

    synchronized RtAccelerationStructure requireActiveTlas(long requiredSceneRevision) {
        requireReady("resolve active scene TLAS");
        pumpInternal();
        return acceleration.requireActiveTlas(requiredSceneRevision);
    }

    synchronized VulkanSceneAcceleration.FrameInstances frameInstances(
            FramePrimitiveBatch batch,
            long requiredSceneRevision
    ) {
        requireReady("resolve frame primitives");
        pumpInternal();
        return acceleration.frameInstances(batch, requiredSceneRevision);
    }

    synchronized VulkanGpuSceneTransferQueue.BufferBinding requireBuffer(
            VulkanGpuSceneUploadPlanner.Target target,
            long requiredSceneRevision
    ) {
        requireReady("resolve active GPUScene buffer");
        pumpInternal();
        return gpuScene.requireBuffer(target, requiredSceneRevision);
    }

    synchronized VulkanDeviceRuntime device() {
        requireReady("resolve Vulkan device runtime");
        return device;
    }

    synchronized Snapshot snapshot() {
        VulkanGpuScene.Snapshot gpu = gpuScene.snapshot();
        VulkanSceneAcceleration.Snapshot as = acceleration.snapshot();
        return new Snapshot(
                lifecycle,
                acceptedRevision,
                Math.min(gpu.activeRevision(), as.activeRevision()),
                pendingAccelerationChanges == null ? -1L : pendingAccelerationChanges.revision(),
                gpu,
                as,
                completedDescriptorEpoch,
                terminalFailure
        );
    }

    private void pumpInternal() {
        if (completedDescriptorEpoch >= 0L) {
            gpuScene.poll(completedDescriptorEpoch);
            acceleration.poll(completedDescriptorEpoch);
        } else {
            gpuScene.pollCompletion();
            acceleration.pollCompletion();
        }
        VulkanSceneResidency.SceneChangeSet changes = pendingAccelerationChanges;
        if (changes == null || gpuScene.snapshot().activeRevision() != changes.revision()) return;
        try {
            acceleration.submit(changes, pendingRetireEpoch);
            pendingAccelerationChanges = null;
            pendingRetireEpoch = -1L;
        } catch (VulkanSceneAcceleration.BusyException ignored) {
            // GPUScene or the previous AS generation still owns bounded native work. Poll retries.
        }
    }

    private long activeRevision() {
        return Math.min(gpuScene.snapshot().activeRevision(), acceleration.snapshot().activeRevision());
    }

    private void requireReady(String operation) {
        if (lifecycle != Lifecycle.READY) {
            throw new IllegalStateException(
                    "cannot " + operation + " while scene runtime is " + lifecycle,
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
        RuntimeException failure = null;
        failure = closeCollecting(failure, acceleration);
        failure = closeCollecting(failure, gpuScene);
        failure = closeCollecting(failure, device);
        pendingAccelerationChanges = null;
        if (failure != null) throw failure;
    }

    enum Lifecycle {
        READY,
        FAILED,
        CLOSED
    }

    record Admission(long revision, long uploadBytes, int logicalRecords, boolean active) {
        Admission {
            if (revision < 0L || uploadBytes < 0L || logicalRecords < 0) {
                throw new IllegalArgumentException("scene runtime admission counters are invalid");
            }
        }
    }

    record Snapshot(
            Lifecycle lifecycle,
            long acceptedRevision,
            long activeRevision,
            long pendingAccelerationRevision,
            VulkanGpuScene.Snapshot gpuScene,
            VulkanSceneAcceleration.Snapshot acceleration,
            long completedDescriptorEpoch,
            Throwable terminalFailure
    ) {
        Snapshot {
            lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
            gpuScene = Objects.requireNonNull(gpuScene, "gpuScene");
            acceleration = Objects.requireNonNull(acceleration, "acceleration");
            if (acceptedRevision < -1L || activeRevision < -1L
                    || pendingAccelerationRevision < -1L || completedDescriptorEpoch < -1L
                    || activeRevision > acceptedRevision) {
                throw new IllegalArgumentException("scene runtime snapshot contains invalid revisions");
            }
        }
    }

    static final class BusyException extends Exception {
        private static final long serialVersionUID = 1L;

        BusyException(String message) {
            super(Objects.requireNonNull(message, "message"));
        }


        BusyException(String message, Throwable cause) {
            super(Objects.requireNonNull(message, "message"), cause);
        }
    }

    private static RuntimeException closeCollecting(RuntimeException failure, AutoCloseable resource) {
        if (resource == null) return failure;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            RuntimeException wrapped = closeFailure instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("failed to close scene runtime resource", closeFailure);
            if (failure == null) return wrapped;
            failure.addSuppressed(wrapped);
        }
        return failure;
    }

    private static void closeSuppressing(Throwable failure, AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}

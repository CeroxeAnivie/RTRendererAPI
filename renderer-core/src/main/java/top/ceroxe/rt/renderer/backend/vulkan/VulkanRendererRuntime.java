package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.*;
import top.ceroxe.rt.renderer.rt.RtSceneReadiness;
import top.ceroxe.rt.renderer.rt.pipeline.RtFrameSnapshot;
import top.ceroxe.rt.renderer.rt.pipeline.RtGBufferSnapshot;
import top.ceroxe.rt.renderer.rt.runtime.GuardedRtCore;
import top.ceroxe.rt.renderer.rt.runtime.RtCore;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Backend lifecycle owner for the renderer-owned Vulkan ray tracing engine.
 *
 * <p>The backend provider supplies immutable scene/frame transactions and consumes exported
 * GPU images. This runtime owns the Vulkan instance, device, queues, allocator, acceleration
 * structures, pipelines, and frame resources for its entire lifetime. It deliberately exposes no
 * host callbacks and never borrows a host command encoder or presentation target.</p>
 *
 * <p>Frame submission is designed for one renderer submission thread. Observation methods may be
 * called from diagnostics threads, but {@link #close()} must be serialized with submission by the
 * embedding application. This avoids an implicit lock on the hot path while keeping ownership
 * unambiguous.</p>
 */
final class VulkanRendererRuntime implements AutoCloseable {
    private final VulkanRtCapabilityProbe.Result capability;
    private final GuardedRtCore core;
    private final AtomicBoolean closed = new AtomicBoolean();

    private VulkanRendererRuntime(VulkanRtCapabilityProbe.Result capability, GuardedRtCore core) {
        this.capability = Objects.requireNonNull(capability, "capability");
        this.core = Objects.requireNonNull(core, "core");
    }

    /**
     * Probes the machine and opens an independently owned production renderer.
     */
    public static VulkanRendererRuntime open() {
        return open(RendererRtDiagnostics.noop());
    }

    /**
     * Probes the machine and opens a renderer with immutable diagnostic sinks.
     */
    public static VulkanRendererRuntime open(RendererRtDiagnostics diagnostics) {
        return open(VulkanRtCapabilityProbe.capture(), diagnostics);
    }

    /**
     * Opens from a previously captured probe result, avoiding a duplicate Vulkan capability probe.
     *
     * @throws InitializationException when no compatible device exists or native initialization fails
     */
    public static VulkanRendererRuntime open(VulkanRtCapabilityProbe.Result capability) {
        return open(capability, RendererRtDiagnostics.noop());
    }

    /**
     * Opens from a previously captured probe result with explicit diagnostic sinks.
     */
    public static VulkanRendererRuntime open(
            VulkanRtCapabilityProbe.Result capability,
            RendererRtDiagnostics diagnostics
    ) {
        return open(capability, diagnostics, (acceptedCapability, scope) ->
                top.ceroxe.rt.renderer.rt.device.VulkanRtDeviceContext.open(
                        acceptedCapability,
                        scope,
                        diagnostics
                ));
    }

    static VulkanRendererRuntime open(
            VulkanRtCapabilityProbe.Result capability,
            RendererRtDiagnostics diagnostics,
            GuardedRtCore.NativeBackendFactory backendFactory
    ) {
        VulkanRtCapabilityProbe.Result immutableCapability = Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(diagnostics, "diagnostics");
        Objects.requireNonNull(backendFactory, "backendFactory");

        if (!immutableCapability.hardwareRayTracingReady()) {
            throw new InitializationException(
                    "no Vulkan device satisfies the renderer ray tracing contract",
                    immutableCapability,
                    RtCore.State.DISABLED_UNSUPPORTED,
                    null
            );
        }

        GuardedRtCore openedCore = new GuardedRtCore(backendFactory);
        openedCore.acceptCapability(immutableCapability);
        if (openedCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES) {
            return new VulkanRendererRuntime(immutableCapability, openedCore);
        }

        RtCore.State failedState = openedCore.state();
        RtCore.Summary failedSummary = openedCore.summary();
        try {
            openedCore.close();
        } catch (RuntimeException closeFailure) {
            InitializationException failure = new InitializationException(
                    "native renderer initialization failed and cleanup also failed",
                    immutableCapability,
                    failedState,
                    failedSummary
            );
            failure.addSuppressed(closeFailure);
            throw failure;
        }
        throw new InitializationException(
                "native renderer initialization failed: " + failedSummary.asLogFragment(),
                immutableCapability,
                failedState,
                failedSummary
        );
    }

    private static GpuFrame wrap(RtCore.SharedFrameImage image) {
        return image == null ? null : new GpuFrame(image);
    }

    public VulkanRtCapabilityProbe.Result capability() {
        return capability;
    }

    public Status status() {
        if (closed.get()) {
            return Status.CLOSED;
        }
        return core.state() == RtCore.State.READY_FOR_SCENE_UPDATES ? Status.READY : Status.FAILED;
    }

    /**
     * Publishes the current renderer-owned view and residency policy.
     */
    public void updateView(RendererViewState viewState) {
        requireReady("updateView");
        core.acceptViewState(Objects.requireNonNull(viewState, "viewState"));
        requireReady("updateView");
    }

    /**
     * Publishes a view plus explicit foreground recovery ownership.
     */
    public void updateForegroundWork(RendererForegroundWork work) {
        requireReady("updateForegroundWork");
        core.acceptForegroundWork(Objects.requireNonNull(work, "work"));
        requireReady("updateForegroundWork");
    }

    /**
     * Submits one immutable scene/frame transaction.
     */
    public void submit(RendererFrameUpdate update) {
        submit(RendererFrameSubmission.untraced(Objects.requireNonNull(update, "update")));
    }

    /**
     * Submits one immutable transaction while preserving its causality envelope.
     */
    public void submit(RendererFrameSubmission submission) {
        requireReady("submit");
        core.acceptFrameSubmission(Objects.requireNonNull(submission, "submission"));
        requireReady("submit");
    }

    /**
     * Latest asynchronous CPU diagnostic snapshot, or {@code null} when readback is disabled.
     */
    public RtFrameSnapshot latestDiagnosticFrame() {
        requireReady("latestDiagnosticFrame");
        return core.latestFrameSnapshot();
    }

    public boolean requestGBufferCapture() {
        requireReady("requestGBufferCapture");
        return core.requestGBufferCapture();
    }

    public RtGBufferSnapshot latestGBufferSnapshot() {
        requireReady("latestGBufferSnapshot");
        return core.latestGBufferSnapshot();
    }

    /**
     * Exports the latest completed renderer-owned GPU image, or {@code null} if none is ready.
     */
    public GpuFrame exportLatestGpuFrame() {
        requireReady("exportLatestGpuFrame");
        return wrap(core.exportLatestSharedFrameImage());
    }

    /**
     * Exports exactly the requested completed frame, or {@code null} if it is no longer available.
     */
    public GpuFrame exportGpuFrame(long requiredFrameSequence) {
        if (requiredFrameSequence < 0L) {
            throw new IllegalArgumentException("requiredFrameSequence must not be negative");
        }
        requireReady("exportGpuFrame");
        return wrap(core.exportSharedFrameImage(requiredFrameSequence));
    }

    /**
     * Acknowledges that the host has consumed an exported image. The frame must remain open until
     * the host has imported/waited on its optional synchronization handle.
     */
    public boolean acknowledgePresented(GpuFrame frame) {
        Objects.requireNonNull(frame, "frame");
        requireReady("acknowledgePresented");
        RtCore.SharedFrameImage image = frame.openImage();
        return core.acknowledgeSharedFramePresented(image.frameStateSequence(), image.vulkanImage());
    }

    public RtSceneReadiness sceneReadiness() {
        requireReady("sceneReadiness");
        return core.sceneReadiness();
    }

    public RtCore.RuntimeActivity runtimeActivity() {
        requireReady("runtimeActivity");
        return core.runtimeActivity();
    }

    /**
     * Expensive human-readable snapshot intended for diagnostics boundaries, not every frame.
     */
    public RtCore.Summary diagnosticSummary() {
        requireReady("diagnosticSummary");
        core.refreshDiagnosticSummary();
        return core.summary();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            core.close();
        }
    }

    private void requireReady(String operation) {
        if (closed.get()) {
            throw new IllegalStateException("renderer is closed: " + operation);
        }
        RtCore.State coreState = core.state();
        if (coreState != RtCore.State.READY_FOR_SCENE_UPDATES) {
            throw new IllegalStateException(
                    "renderer is unavailable during " + operation
                            + ": state=" + coreState
                            + ", summary=" + core.summary().asLogFragment()
            );
        }
    }

    public enum Status {
        READY,
        FAILED,
        CLOSED
    }

    /**
     * One exported GPU image view. Closing it releases only per-export synchronization ownership;
     * the renderer retains the persistent Vulkan image and memory until renderer shutdown.
     */
    public static final class GpuFrame implements AutoCloseable {
        private final RtCore.SharedFrameImage image;
        private final AtomicBoolean closed = new AtomicBoolean();

        private GpuFrame(RtCore.SharedFrameImage image) {
            this.image = Objects.requireNonNull(image, "image");
        }

        public long frameSequence() {
            return image.frameStateSequence();
        }

        public int width() {
            return image.width();
        }

        public int height() {
            return image.height();
        }

        public int vulkanFormat() {
            return image.vulkanFormat();
        }

        public long vulkanImage() {
            return image.vulkanImage();
        }

        public long vulkanMemory() {
            return image.vulkanMemory();
        }

        public long allocationSize() {
            return image.allocationSize();
        }

        public int memoryTypeIndex() {
            return image.memoryTypeIndex();
        }

        public boolean dedicatedAllocation() {
            return image.dedicatedAllocation();
        }

        public long win32MemoryHandle() {
            return image.win32Handle();
        }

        public long win32SynchronizationHandle() {
            return image.syncWin32Handle();
        }

        public int synchronizationHandleType() {
            return image.syncHandleType();
        }

        public boolean closed() {
            return closed.get();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                image.close();
            }
        }

        private RtCore.SharedFrameImage openImage() {
            if (closed.get()) {
                throw new IllegalStateException("GPU frame export is already closed");
            }
            return image;
        }
    }

    /**
     * Initialization failure with machine capability and guarded backend evidence attached.
     */
    @SuppressWarnings("serial") // Diagnostic snapshots intentionally remain typed and process-local.
    public static final class InitializationException extends IllegalStateException {
        private final VulkanRtCapabilityProbe.Result capability;
        private final RtCore.State terminalState;
        private final RtCore.Summary summary;

        private InitializationException(
                String message,
                VulkanRtCapabilityProbe.Result capability,
                RtCore.State terminalState,
                RtCore.Summary summary
        ) {
            super(message);
            this.capability = Objects.requireNonNull(capability, "capability");
            this.terminalState = Objects.requireNonNull(terminalState, "terminalState");
            this.summary = summary;
        }

        public VulkanRtCapabilityProbe.Result capability() {
            return capability;
        }

        public RtCore.State terminalState() {
            return terminalState;
        }

        public RtCore.Summary summary() {
            return summary;
        }
    }
}

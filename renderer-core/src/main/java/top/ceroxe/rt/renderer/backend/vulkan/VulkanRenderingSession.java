package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.CpuFrame;
import top.ceroxe.rt.renderer.api.HistoryInvalidationReason;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.RendererDiagnostics;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease;

import java.util.Objects;
import java.util.Set;

/**
 * Internal submission seam between the public renderer lifecycle and the native Vulkan engine.
 *
 * <p>The implementation must either accept a submission completely or throw
 * {@link SubmissionRejectedException} without retaining any part of it. Native failures are
 * reported as unchecked exceptions and permanently fail the owning renderer. This distinction is
 * what lets a host retry capacity-limited work without concealing device loss or partial GPU state.</p>
 */
interface VulkanRenderingSession extends AutoCloseable {
    State state();

    /** Stable UUID-derived identity of the physical GPU that owns exported frame memory. */
    String gpuStableId();

    SceneAdmission apply(SceneSubmission submission) throws SubmissionRejectedException;

    FrameAdmission submit(FrameSubmission submission) throws SubmissionRejectedException;

    /**
     * Returns a real completed external image lease, or {@code null} when none is newer.
     */
    GpuFrameLease acquireLatestFrame();

    /**
     * Returns a managed-presenter lease as soon as GPU timeline synchronization can describe it.
     * Expert consumers keep using {@link #acquireLatestFrame()} and therefore never observe a
     * producer-pending image without an exported acquire semaphore.
     */
    default GpuFrameLease acquireLatestManagedFrame() {
        return acquireLatestFrame();
    }

    /**
     * Returns an owned CPU snapshot newer than the supplied sequence, or {@code null}.
     */
    CpuFrame captureLatestCpuFrame(long afterFrameSequence);

    Telemetry telemetry();

    @Override
    void close();

    enum State {
        READY,
        FAILED,
        CLOSED
    }

    record SceneSubmission(
            VulkanSceneResidency.SceneChangeSet residentChanges,
            PersistentSceneRegistry.SceneState resultingState
    ) {
        public SceneSubmission {
            residentChanges = Objects.requireNonNull(residentChanges, "residentChanges");
            resultingState = Objects.requireNonNull(resultingState, "resultingState");
            if (residentChanges.revision() != resultingState.revision()) {
                throw new IllegalArgumentException("resident scene change set revision does not match resulting state");
            }
        }
    }

    /**
     * Backend evidence that scene work entered its real renderer-owned submission lane.
     */
    record SceneAdmission(long acceptedSceneRevision) {
        public SceneAdmission {
            if (acceptedSceneRevision < 0L) {
                throw new IllegalArgumentException("acceptedSceneRevision must not be negative");
            }
        }
    }

    record FrameSubmission(RenderFrameRequest request, long acceptedSceneRevision) {
        public FrameSubmission {
            request = Objects.requireNonNull(request, "request");
            if (acceptedSceneRevision < request.minimumSceneRevision()) {
                throw new IllegalArgumentException("accepted scene revision does not satisfy frame requirement");
            }
        }
    }

    /**
     * Backend evidence that the exact frame entered its real dispatch lane.
     */
    record FrameAdmission(
            long frameSequence,
            long sceneRevision,
            Set<HistoryInvalidationReason> historyInvalidations
    ) {
        public FrameAdmission {
            if (frameSequence < 0L || sceneRevision < 0L) {
                throw new IllegalArgumentException("frame admission counters must not be negative");
            }
            historyInvalidations = Set.copyOf(Objects.requireNonNull(
                    historyInvalidations, "historyInvalidations"
            ));
        }
    }

    record Telemetry(
            long latestCompletedFrameSequence,
            RendererDiagnostics.FrameGpuTiming frameGpuTiming
    ) {
        public Telemetry {
            if (latestCompletedFrameSequence < -1L) {
                throw new IllegalArgumentException("latestCompletedFrameSequence must be at least -1");
            }
            frameGpuTiming = Objects.requireNonNull(frameGpuTiming, "frameGpuTiming");
        }

        static Telemetry unavailable() {
            return new Telemetry(-1L, RendererDiagnostics.FrameGpuTiming.unavailable());
        }
    }

    /**
     * Recoverable admission refusal. The session contract forbids retaining partial submission
     * state before throwing this exception.
     */
    final class SubmissionRejectedException extends Exception {
        private static final long serialVersionUID = 1L;

        SubmissionRejectedException(String message) {
            super(Objects.requireNonNull(message, "message"));
        }
    }
}

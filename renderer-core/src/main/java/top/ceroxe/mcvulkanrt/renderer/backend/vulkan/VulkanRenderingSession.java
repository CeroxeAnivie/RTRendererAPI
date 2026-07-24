package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import top.ceroxe.mcvulkanrt.renderer.api.GpuFrameLease;
import top.ceroxe.mcvulkanrt.renderer.api.RenderFrameRequest;
import top.ceroxe.mcvulkanrt.renderer.api.RendererDiagnostics;

import java.util.Objects;

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

    SceneAdmission apply(SceneSubmission submission) throws SubmissionRejectedException;

    FrameAdmission submit(FrameSubmission submission) throws SubmissionRejectedException;

    /** Returns a real completed external image lease, or {@code null} when none is newer. */
    GpuFrameLease acquireLatestFrame();

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

    /** Backend evidence that scene work entered its real renderer-owned submission lane. */
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

    /** Backend evidence that the exact frame entered its real dispatch lane. */
    record FrameAdmission(long frameSequence, long sceneRevision) {
        public FrameAdmission {
            if (frameSequence < 0L || sceneRevision < 0L) {
                throw new IllegalArgumentException("frame admission counters must not be negative");
            }
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
        SubmissionRejectedException(String message) {
            super(Objects.requireNonNull(message, "message"));
        }
    }
}

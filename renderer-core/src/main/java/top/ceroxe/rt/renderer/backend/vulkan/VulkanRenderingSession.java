package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.CpuFrame;
import top.ceroxe.rt.renderer.api.HistoryInvalidationReason;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.RendererDiagnostics;
import top.ceroxe.rt.renderer.api.FrameGenerationEvidence;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities;
import top.ceroxe.rt.renderer.api.SubmissionDeferralReason;
import top.ceroxe.rt.renderer.api.TechnologyExecutionEvidence;
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

    /** Current immutable snapshot of device-bound optional feature execution state. */
    default RenderingFeatureCapabilities featureCapabilities() {
        return RenderingFeatureCapabilities.builder().build();
    }

    /**
     * Maximum accepted producer lead while a managed presenter owns frame retirement.
     *
     * <p>Most backends impose no tighter limit than the presenter configuration. A backend whose
     * native presentation feature exposes only frame-local inputs may lower this value so the host
     * applies recoverable admission backpressure before those inputs can be superseded.</p>
     */
    default int managedPresentationProducerLeadLimit() {
        return Integer.MAX_VALUE;
    }

    SceneAdmission apply(SceneSubmission submission) throws SubmissionRejectedException;

    FrameAdmission submit(FrameSubmission submission) throws SubmissionRejectedException;

    /**
     * Attempts frame admission without representing expected capacity backpressure as an
     * exception. Implementations should override this on hot paths; the default preserves the
     * transactional contract for test doubles and alternate backends.
     */
    default FrameAdmissionAttempt trySubmit(FrameSubmission submission) {
        try {
            return new FrameAdmitted(submit(submission));
        } catch (SubmissionRejectedException rejection) {
            return new FrameDeferred(rejection.deferralReason(), rejection.detail());
        }
    }

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

    sealed interface FrameAdmissionAttempt permits FrameAdmitted, FrameDeferred {
    }

    record FrameAdmitted(FrameAdmission admission) implements FrameAdmissionAttempt {
        public FrameAdmitted {
            admission = Objects.requireNonNull(admission, "admission");
        }
    }

    record FrameDeferred(
            SubmissionDeferralReason deferralReason,
            String detail
    ) implements FrameAdmissionAttempt {
        public FrameDeferred {
            deferralReason = Objects.requireNonNull(deferralReason, "deferralReason");
            detail = Objects.requireNonNull(detail, "detail");
            if (detail.isBlank()) throw new IllegalArgumentException("detail must not be blank");
        }
    }

    record Telemetry(
            long latestCompletedFrameSequence,
            RendererDiagnostics.FrameGpuTiming frameGpuTiming,
            FrameGenerationEvidence frameGenerationEvidence,
            TechnologyExecutionEvidence technologyExecutionEvidence
    ) {
        public Telemetry {
            if (latestCompletedFrameSequence < -1L) {
                throw new IllegalArgumentException("latestCompletedFrameSequence must be at least -1");
            }
            frameGpuTiming = Objects.requireNonNull(frameGpuTiming, "frameGpuTiming");
            frameGenerationEvidence = Objects.requireNonNull(
                    frameGenerationEvidence, "frameGenerationEvidence"
            );
            technologyExecutionEvidence = Objects.requireNonNull(
                    technologyExecutionEvidence, "technologyExecutionEvidence"
            );
        }

        Telemetry(long latestCompletedFrameSequence, RendererDiagnostics.FrameGpuTiming frameGpuTiming) {
            this(
                    latestCompletedFrameSequence,
                    frameGpuTiming,
                    FrameGenerationEvidence.unavailable(),
                    TechnologyExecutionEvidence.disabled()
            );
        }

        Telemetry(
                long latestCompletedFrameSequence,
                RendererDiagnostics.FrameGpuTiming frameGpuTiming,
                FrameGenerationEvidence frameGenerationEvidence
        ) {
            this(
                    latestCompletedFrameSequence,
                    frameGpuTiming,
                    frameGenerationEvidence,
                    TechnologyExecutionEvidence.disabled()
            );
        }

        static Telemetry unavailable() {
            return new Telemetry(
                    -1L,
                    RendererDiagnostics.FrameGpuTiming.unavailable(),
                    FrameGenerationEvidence.unavailable(),
                    TechnologyExecutionEvidence.disabled()
            );
        }
    }

    /**
     * Recoverable admission refusal. The session contract forbids retaining partial submission
     * state before throwing this exception.
     */
    final class SubmissionRejectedException extends Exception {
        private static final long serialVersionUID = 1L;
        /** Typed reason that lets callers distinguish retryable capacity from lifecycle failure. */
        private final SubmissionDeferralReason deferralReason;

        SubmissionRejectedException(String message) {
            this(SubmissionDeferralReason.PROVIDER_CAPACITY, message);
        }

        SubmissionRejectedException(SubmissionDeferralReason deferralReason, String detail) {
            super(Objects.requireNonNull(detail, "detail"));
            if (detail.isBlank()) throw new IllegalArgumentException("detail must not be blank");
            this.deferralReason = Objects.requireNonNull(deferralReason, "deferralReason");
        }

        SubmissionDeferralReason deferralReason() {
            return deferralReason;
        }

        String detail() {
            return getMessage();
        }
    }
}

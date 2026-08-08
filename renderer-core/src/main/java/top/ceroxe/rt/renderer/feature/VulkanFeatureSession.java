package top.ceroxe.rt.renderer.feature;

import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities;
import top.ceroxe.rt.renderer.api.FrameGenerationEvidence;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.RendererFeaturePlan;
import top.ceroxe.rt.renderer.api.RendererFeatureProfile;
import top.ceroxe.rt.renderer.api.TechnologyExecutionEvidence;
import top.ceroxe.rt.renderer.rt.pipeline.VulkanFrameExtents;

import java.util.Optional;

/**
 * Lifetime owner for one negotiated set of optional Vulkan rendering features.
 *
 * <p>Sessions are created after the logical device exists and are closed before that device. The
 * post-trace callback is intentionally small: it gives DLSS/NRD-style implementations a precise
 * synchronization boundary without making {@code RtRayTracingPipeline} aware of vendor SDKs.</p>
 */
public interface VulkanFeatureSession extends AutoCloseable {
    /**
     * Returns a stateless no-op session used by compatibility callers that do not negotiate
     * optional features.
     *
     * @return canonical disabled session
     */
    static VulkanFeatureSession disabled() {
        return DisabledHolder.INSTANCE;
    }

    /**
     * Returns the immutable capabilities owned by this session.
     *
     * @return complete non-null capability result
     */
    RenderingFeatureCapabilities capabilities();

    /**
     * Returns point-in-time presentation evidence owned by this feature session.
     *
     * <p>The default is deliberately unavailable. Implementations must report backend-observed
     * delivery counters and must not infer output from a configured capability or present call.</p>
     *
     * @return immutable vendor-neutral frame-generation evidence
     */
    default FrameGenerationEvidence frameGenerationEvidence() {
        return FrameGenerationEvidence.unavailable();
    }

    /**
     * Returns structured execution evidence for every concrete technology owned by this session.
     * Implementations return immutable snapshots and keep mutable counters in narrow provider
     * ledgers rather than in this lifecycle coordinator.
     *
     * @return complete immutable technology evidence map
     */
    default TechnologyExecutionEvidence technologyExecutionEvidence() {
        return TechnologyExecutionEvidence.disabled();
    }

    /**
     * Negotiates a frame's internal render extent before frame-slot resources are allocated.
     *
     * <p>The default preserves identity rendering. Temporal upscalers override this boundary so
     * DLSS optimal settings cannot be applied after images, descriptors, and dispatch dimensions
     * have already been fixed.</p>
     *
     * @param request immutable frame request whose output extent is being negotiated
     * @param requested renderer-requested identity extent
     * @return validated internal render and published output extents
     */
    default VulkanFrameExtents negotiateFrameExtents(RenderFrameRequest request, VulkanFrameExtents requested) {
        return java.util.Objects.requireNonNull(requested, "requested");
    }

    /**
     * Whether extent negotiation may mutate the capability state observed by the next frame.
     * Providers that can fall back while negotiating dimensions must return {@code true}; a
     * stateless or frame-generation-only provider can keep the admission path to one capability
     * snapshot instead of rebuilding dynamic telemetry twice.
     *
     * @return {@code true} when negotiation can change the next capability snapshot
     */
    default boolean extentNegotiationMayChangeCapabilities() {
        return false;
    }

    /**
     * Assesses whether this already-open provider session can honor a target profile without
     * inventing resources that were not reserved during device negotiation.
     *
     * @param source currently effective profile
     * @param target requested profile
     * @return typed transition assessment
     */
    default ReconfigurationAssessment assessReconfiguration(
            RendererFeatureProfile source,
            RendererFeatureProfile target
    ) {
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(target, "target");
        return ReconfigurationAssessment.rendererRebuild(
                "feature provider does not support runtime reconfiguration"
        );
    }

    /**
     * Commits one already-assessed profile after core proves the frame ring is drained.
     *
     * @param source profile used for the assessment
     * @param target profile to commit
     */
    default void applyReconfiguration(
            RendererFeatureProfile source,
            RendererFeatureProfile target
    ) {
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(target, "target");
        throw new UnsupportedOperationException("feature provider does not support runtime reconfiguration");
    }

    /**
     * Records optional post-trace work before readback or external queue ownership release.
     *
     * @param context borrowed command recording context
     */
    default void recordPostTrace(VulkanFeatureFrameContext context) {
        recordDenoising(context);
        recordReconstruction(context);
    }

    /**
     * Records the complete post-trace transaction with the renderer-owned composition stage in
     * the middle. The caller submits the command buffer only after this method returns, so an
     * optional feature failure releases the unsubmitted recording instead of publishing a
     * partially-written output image.
     *
     * @param context borrowed command recording context
     * @param composition renderer-owned composition between denoising and reconstruction
     */
    default void recordPostTrace(VulkanFeatureFrameContext context, Runnable composition) {
        recordDenoising(context);
        java.util.Objects.requireNonNull(composition, "composition").run();
        recordReconstruction(context);
    }

    /**
     * Records denoising work before renderer-owned post-NRD composition.
     * @param context borrowed command recording context
     */
    default void recordDenoising(VulkanFeatureFrameContext context) {
        // A no-op is the safe fallback: the RT output remains the published output image.
    }

    /**
     * Records reconstruction and frame-generation work after renderer-owned composition.
     * @param context borrowed command recording context
     */
    default void recordReconstruction(VulkanFeatureFrameContext context) {
        // A no-op is the safe fallback: the RT output remains the published output image.
    }

    /**
     * Records presentation-time frame-generation tags after renderer-owned output publication.
     *
     * <p>This boundary is deliberately separate from reconstruction. A renderer may reconstruct
     * into a private HDR target and then convert to the caller's public format; generated-frame
     * integrations must observe only that complete, externally visible result.</p>
     *
     * @param context borrowed command recording context
     */
    default void recordFrameGeneration(VulkanFeatureFrameContext context) {
        // A no-op preserves native one-frame-per-submission presentation.
    }

    /**
     * Waits until optional presentation work has released inputs owned by the next writable slot.
     *
     * <p>This boundary intentionally precedes feature reconfiguration and resource preparation.
     * A generated-frame implementation may continue reading tagged images after present returns;
     * waiting only at queue submission is too late if preparation first replaces those images.</p>
     *
     * @param frameSequence non-negative identity of the frame that will reuse resources
     * @return same-device timeline dependency, or {@link InputCompletion#none()}
     */
    default InputCompletion awaitFrameInputReuse(long frameSequence) {
        return InputCompletion.none();
    }

    /**
     * Commits the completion dependency after the renderer queue accepted the matching wait.
     * Failed recordings must not call this method, so a retry cannot lose its vendor dependency.
     *
     * @param frameSequence frame identity passed to {@link #awaitFrameInputReuse(long)}
     */
    default void commitFrameInputReuse(long frameSequence) {
    }

    /**
     * Marks the start of CPU preparation for one renderer frame.
     *
     * <p>Latency integrations use this as the real frame-start boundary: pacing occurs before
     * renderer work begins, and the matching simulation interval remains open until
     * {@link #beginFrameSubmission(long)}. Implementations must treat
     * {@link #cancelFramePreparation(long)} as an idempotent rollback.</p>
     *
     * @param frameSequence non-negative renderer frame identity
     */
    default void beginFramePreparation(long frameSequence) {
    }

    /**
     * Cancels CPU preparation if the frame is rejected before queue submission begins.
     * @param frameSequence identity passed to the matching begin operation
     */
    default void cancelFramePreparation(long frameSequence) {
    }

    /**
     * Marks the transition from CPU frame preparation to command recording and queue submission.
     * @param frameSequence non-negative renderer frame identity
     */
    default void beginFrameSubmission(long frameSequence) {
    }

    /**
     * Marks completion of command recording and the queue-submission marker interval.
     * @param frameSequence identity passed to the matching begin operation
     */
    default void endFrameSubmission(long frameSequence) {
    }

    /**
     * Commits CPU-only execution evidence after Vulkan accepted the complete command buffer.
     * This method must not perform JNI, block, or infer fence/present completion.
     * @param frameSequence non-negative identity of the accepted submission
     */
    default void commitFrameSubmission(long frameSequence) {
    }

    /**
     * Observes that the producer fence for an accepted frame has completed.
     *
     * <p>This is the first boundary that proves recorded optional work executed on the GPU.
     * Implementations must keep this callback CPU-only and retry-safe: the frame slot retains a
     * pending notification and withholds reuse until this method returns normally.</p>
     *
     * @param frameSequence non-negative identity previously passed to
     *                      {@link #commitFrameSubmission(long)}
     */
    default void observeFrameCompletion(long frameSequence) {
    }

    /**
     * Idempotently discards unsubmitted frame-local execution evidence.
     * This method must not roll back evidence from earlier accepted submissions.
     * @param frameSequence identity of the abandoned recording
     */
    default void discardFrameSubmission(long frameSequence) {
    }

    /**
     * Observes the terminal WSI result for one submitted renderer frame.
     *
     * <p>This notification is deliberately independent from {@link #swapchainInterceptor()}.
     * Reconstruction and latency integrations may use direct Vulkan WSI while still requiring
     * truthful evidence that a replacement or marker-bearing frame reached presentation.</p>
     *
     * @param frameSequence non-negative renderer frame identity
     * @param succeeded whether WSI accepted the present, including a suboptimal swapchain result
     */
    default void observePresentation(long frameSequence, boolean succeeded) {
    }

    /**
     * Same-device timeline dependency required before a renderer submission accesses tagged inputs.
     *
     * @param semaphore timeline semaphore handle, or zero when disabled
     * @param value positive timeline value, or zero when disabled
     */
    record InputCompletion(long semaphore, long value) {
        /** Validates the all-or-none semaphore/value contract. */
        public InputCompletion {
            if ((semaphore == 0L) != (value == 0L) || value < 0L) {
                throw new IllegalArgumentException("input completion semaphore and value are inconsistent");
            }
        }

        /**
         * Reports whether this dependency carries a timeline wait.
         *
         * @return {@code true} when semaphore and value are both present
         */
        public boolean enabled() {
            return semaphore != 0L;
        }

        /**
         * Returns the canonical disabled dependency.
         *
         * @return a dependency with zero semaphore and value
         */
        public static InputCompletion none() {
            return new InputCompletion(0L, 0L);
        }
    }

    /**
     * Provider-local transition assessment aggregated by the feature registry.
     *
     * @param disposition transition classification
     * @param boundary earliest safe transition boundary
     * @param reason bounded assessment explanation
     */
    record ReconfigurationAssessment(
            RendererFeaturePlan.Disposition disposition,
            RendererFeaturePlan.Boundary boundary,
            String reason
    ) {
        /** Validates a complete provider assessment. */
        public ReconfigurationAssessment {
            disposition = java.util.Objects.requireNonNull(disposition, "disposition");
            boundary = java.util.Objects.requireNonNull(boundary, "boundary");
            reason = java.util.Objects.requireNonNull(reason, "reason").trim();
            if (reason.isEmpty()) throw new IllegalArgumentException("reason must not be blank");
        }

        /**
         * Returns the normal in-session transition boundary.
         *
         * @param reason bounded transition explanation
         * @return frame-drain assessment
         */
        public static ReconfigurationAssessment frameDrain(String reason) {
            return new ReconfigurationAssessment(
                    RendererFeaturePlan.Disposition.APPLICABLE,
                    RendererFeaturePlan.Boundary.FRAME_DRAIN,
                    reason
            );
        }

        /**
         * Returns the fail-closed boundary for an unreserved provider resource.
         *
         * @param reason bounded transition explanation
         * @return renderer-rebuild assessment
         */
        public static ReconfigurationAssessment rendererRebuild(String reason) {
            return new ReconfigurationAssessment(
                    RendererFeaturePlan.Disposition.REQUIRES_RENDERER_REBUILD,
                    RendererFeaturePlan.Boundary.RENDERER_REBUILD,
                    reason
            );
        }
    }

    /**
     * Returns a WSI proxy when a manual-hooking feature must observe swapchain presentation.
     *
     * @return empty for direct Vulkan WSI, otherwise the session-owned interceptor
     */
    default Optional<VulkanSwapchainInterceptor> swapchainInterceptor() {
        return Optional.empty();
    }

    /**
     * Returns the negotiated vendor AS-memory path, when one owns this session's BLAS storage.
     * @return optional session-owned acceleration-structure memory optimizer
     */
    default Optional<VulkanAccelerationStructureMemoryOptimizer> accelerationStructureMemoryOptimizer() {
        return Optional.empty();
    }

    /** Closes feature-owned native resources. */
    @Override
    default void close() {
    }

    /** Lazy holder that avoids constructing the disabled capability value during interface loading. */
    final class DisabledHolder {
        private static final VulkanFeatureSession INSTANCE = new VulkanFeatureSession() {
            private final RenderingFeatureCapabilities capabilities =
                    RenderingFeatureCapabilities.builder().build();

            @Override
            public RenderingFeatureCapabilities capabilities() {
                return capabilities;
            }
        };

        private DisabledHolder() {
        }
    }
}

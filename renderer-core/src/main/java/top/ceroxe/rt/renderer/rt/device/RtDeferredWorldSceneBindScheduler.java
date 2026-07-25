package top.ceroxe.rt.renderer.rt.device;

import top.ceroxe.rt.renderer.RendererFrameUpdate;

import java.util.Objects;

/**
 * Decides how a deferred world-scene bind may re-enter the descriptor-visible scene.
 *
 * <p>This is deliberately a pure scheduler: it neither clears a deferred transaction nor submits
 * Vulkan work. The device context remains the sole resource owner and executes the returned
 * action. Keeping the ordering here makes descriptor contention, streaming cadence, and the
 * transform-refit fast path independently inspectable and contract-testable.</p>
 */
final class RtDeferredWorldSceneBindScheduler {
    private RtDeferredWorldSceneBindScheduler() {
    }

    static Decision decide(
            DeferredWorldSceneBind deferred,
            RtScenePublication publication,
            boolean descriptorsCanBeUpdated,
            boolean descriptorTransactionPresent,
            RendererFrameUpdate update,
            boolean forceCurrentWorldTlas,
            long immediateStreamingBindMaxFaces,
            long nowNanos,
            long nextStreamingSceneBindNanos,
            long maxStreamingSceneBindDeferrals
    ) {
        Objects.requireNonNull(deferred, "deferred");
        Objects.requireNonNull(publication, "publication");
        Objects.requireNonNull(update, "update");

        boolean transformOnly = RtWorldSceneConvergencePolicy.isTransformOnlyWorldTlasUpdate(
                deferred.worldTlasUpdate(),
                publication.worldTlas(),
                publication.worldSectionKeys(),
                publication.materialSnapshot()
        );
        if (transformOnly) {
            return new Decision(selectAction(true, descriptorsCanBeUpdated, descriptorTransactionPresent, false), false);
        }

        String bindReason = deferred.bindReason(update);
        boolean streaming = RtWorldSceneConvergencePolicy.isStreamingWorldSceneUpdate(
                deferred.worldTlasUpdate(),
                bindReason,
                immediateStreamingBindMaxFaces
        );
        boolean deferMaterialUpload = RtWorldSceneConvergencePolicy.shouldDeferWorldSceneMaterialUpload(
                forceCurrentWorldTlas || RtWorldSceneConvergencePolicy.shouldBindStreamingImmediately(
                        deferred.worldTlasUpdate(), immediateStreamingBindMaxFaces
                ),
                streaming,
                update.backlogSnapshot(),
                false,
                nowNanos,
                nextStreamingSceneBindNanos,
                deferred.deferrals(),
                maxStreamingSceneBindDeferrals
        );
        return new Decision(
                selectAction(false, descriptorsCanBeUpdated, descriptorTransactionPresent, deferMaterialUpload),
                streaming
        );
    }

    /**
     * Preserves the transaction ordering used by the device context before extraction.
     *
     * <p>A transform-only refit is material-compatible and therefore needs no material upload;
     * only another descriptor transaction may delay it. Full world updates first need a writable
     * descriptor generation, then may be cadence- or backlog-deferred.</p>
     */
    static Action selectAction(
            boolean transformOnly,
            boolean descriptorsCanBeUpdated,
            boolean descriptorTransactionPresent,
            boolean deferMaterialUpload
    ) {
        if (transformOnly) {
            return descriptorTransactionPresent ? Action.DESCRIPTOR_DEFERRED : Action.BIND_TRANSFORM_ONLY;
        }
        if (!descriptorsCanBeUpdated || descriptorTransactionPresent) {
            return Action.DESCRIPTOR_DEFERRED;
        }
        return deferMaterialUpload ? Action.RETAIN_DEFERRED : Action.SUBMIT_MATERIAL_UPLOAD;
    }

    enum Action {
        DESCRIPTOR_DEFERRED,
        BIND_TRANSFORM_ONLY,
        RETAIN_DEFERRED,
        SUBMIT_MATERIAL_UPLOAD
    }

    record Decision(Action action, boolean streamingWorldSceneUpdate) {
        Decision {
            action = Objects.requireNonNull(action, "action");
            if (action == Action.BIND_TRANSFORM_ONLY && streamingWorldSceneUpdate) {
                throw new IllegalArgumentException("transform-only world updates cannot be streaming material uploads");
            }
        }
    }
}

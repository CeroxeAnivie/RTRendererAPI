package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Reusable CPU classification of one complete scene for ordinary or composed backend selection.
 * No renderer, native handle or window is opened. Scene dependencies are retained as immutable
 * assets; selecting another frame does not scan triangle materials again. This is a classification
 * snapshot, not a scene store or a replacement for the backend's scene-revision validation.
 *
 * <p>{@link #updated(SceneTransaction)} prepares another snapshot without modifying this one.
 * Publish it only after the corresponding backend scene update succeeds. Transform, lighting
 * and optical-coefficient changes that preserve routing class reuse the existing dependency
 * cache. Frame-local primitive validation/classification is cached by immutable batch identity.
 * Concurrent selection is safe; candidate snapshots never change another snapshot's result.</p>
 */
public final class RendererSceneRouting {
    private final SceneRoutingState state;

    /**
     * Classifies a complete reset transaction with all referenced mesh/material dependencies.
     *
     * @param initialScene complete reset scene; a delta is not sufficient for initialization
     * @throws IllegalArgumentException if the scene is incomplete or contains unresolved dependencies
     */
    public RendererSceneRouting(SceneTransaction initialScene) {
        state = SceneRoutingState.initial(initialScene);
    }

    private RendererSceneRouting(SceneRoutingState state) {
        this.state = state;
    }

    /**
     * Prepares a classification candidate for a delta or replacement reset. This snapshot remains
     * unchanged even if classification or the caller's later backend publication fails. A change
     * that preserves all routing dependencies may return this same instance.
     *
     * @param transaction scene change to classify before publishing it to the renderer
     * @return independent candidate, or this snapshot when its routing cache can be reused
     * @throws IllegalArgumentException if the resulting dependencies cannot be resolved
     */
    public RendererSceneRouting updated(SceneTransaction transaction) {
        var next = state.updated(Objects.requireNonNull(transaction, "transaction"));
        return next == state ? this : new RendererSceneRouting(next);
    }

    /**
     * Selects the exact provider for the retained scene and one frame's local primitive batch.
     * Does not rescan persistent triangle materials or perform any GPU work.
     *
     * @param policy exact caller policy
     * @param frame frame whose local primitives participate in the optical classification
     * @return immutable provider decision and its reason
     * @throws IllegalArgumentException if a frame-local primitive refers to an unknown mesh
     */
    public RendererBackendPolicy.Selection select(RendererBackendPolicy policy, RenderFrameRequest frame) {
        return Objects.requireNonNull(policy, "policy").select(state, Objects.requireNonNull(frame, "frame"));
    }

    boolean hasMedia(FramePrimitiveBatch primitives) {
        return state.hasMedia(primitives);
    }
}

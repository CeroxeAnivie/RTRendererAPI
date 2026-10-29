package top.ceroxe.rt.renderer.api;

/** Retained-scene publication and frame-admission boundary. */
public interface RendererSceneAccess {
    /** Publishes a scene revision. Revisions must advance after any admitted scene generation. */
    Renderer.SceneUpdateResult apply(SceneTransaction transaction);

    /**
     * Admits a frame. If no scene has been published, the renderer first admits empty revision
     * zero; subsequent scene transactions must advance beyond zero, even if frame admission defers.
     */
    Renderer.FrameSubmissionResult submit(RenderFrameRequest request);

    /** Capacity-aware form of {@link #submit(RenderFrameRequest)} with the same initial scene semantics. */
    Renderer.FrameSubmissionAttempt trySubmit(RenderFrameRequest request);
}

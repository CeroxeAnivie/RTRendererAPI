package top.ceroxe.rt.renderer.api;

/** Retained-scene publication and frame-admission boundary. */
public interface RendererSceneAccess {
    Renderer.SceneUpdateResult apply(SceneTransaction transaction);

    Renderer.FrameSubmissionResult submit(RenderFrameRequest request);

    Renderer.FrameSubmissionAttempt trySubmit(RenderFrameRequest request);
}

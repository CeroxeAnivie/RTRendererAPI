package consumer;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rtxpt.*;
/** Compile-only use of the current retained-scene and GPU output entry points. */
public final class PublishedRendererConsumer {
    private PublishedRendererConsumer() { }
    public static Renderer open() { return Renderer.open(RendererConfig.pathTracedPresentation()); }
    public static void frame(Renderer renderer) {
        renderer.apply(SceneTransaction.builder(1).resetScene().build());
        renderer.submit(RenderFrameRequest.builder(1,1280,800,CameraState.lookAt(0,0,3,0,0,0).build())
                .minimumSceneRevision(1).build());
    }
    public static Renderer lighting(NativeLightingPlan plan) {
        return RtxptRenderers.openNativeLighting(RtxptRenderers.nativeLightingConfiguration(),plan);
    }
}

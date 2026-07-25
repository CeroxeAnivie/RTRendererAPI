package consumer;

import java.time.Duration;

import top.ceroxe.rt.renderer.api.CameraState;
import top.ceroxe.rt.renderer.api.CpuFrame;
import top.ceroxe.rt.renderer.api.RayTracingRenderer;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.RendererBootstrap;
import top.ceroxe.rt.renderer.api.SceneTransaction;

/**
 * Compile-checked source of the README beginner quick start.
 */
public final class ReadmeQuickStart {
    private ReadmeQuickStart() {
    }

    /**
     * Opens the published backend, renders one managed frame, and closes every owned resource.
     *
     * @param args ignored command-line arguments
     * @throws Exception if initialization, submission, waiting, or shutdown fails
     */
    public static void main(String[] args) throws Exception {
        try (RayTracingRenderer renderer = RendererBootstrap.open()) {
            long sceneRevision = renderer.apply(SceneTransaction.empty(0L))
                    .acceptedSceneRevision();

            CameraState camera = CameraState.lookAt(
                    0.0, 1.0, 5.0,
                    0.0, 1.0, 0.0
            ).aspectRatio(16.0 / 9.0).build();

            renderer.submit(RenderFrameRequest.builder(0L, 1280, 720, camera)
                    .minimumSceneRevision(sceneRevision)
                    .build());

            CpuFrame frame = renderer.awaitLatestCpuFrame(Duration.ofSeconds(5))
                    .orElseThrow(() -> new IllegalStateException("frame timed out"));
            System.out.println(frame.width() + "x" + frame.height());
        }
    }
}

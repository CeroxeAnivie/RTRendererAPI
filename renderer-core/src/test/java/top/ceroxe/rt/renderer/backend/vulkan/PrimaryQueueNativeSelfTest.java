package top.ceroxe.rt.renderer.backend.vulkan;
import java.time.Duration;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
/** Ordinary Vulkan primary queues remain byte-identical after medium delegation. */
public final class PrimaryQueueNativeSelfTest {
    public static void main(String[] args) throws Exception {
        String key="top.ceroxe.rt.medium.primaryQueue",previous=System.getProperty(key);
        String diagnosticKey="top.ceroxe.rt.medium.queueDiagnostics",previousDiagnostics=System.getProperty(diagnosticKey);
        try {
            System.setProperty(diagnosticKey,"true");
            System.setProperty(key,"false");byte[] baseline=render();
            long before=VulkanGpuSceneFrameSubmitter.diagnosticQueuedSubmissions.sum();
            System.setProperty(key,"true");byte[] queued=render();
            if(!Arrays.equals(baseline,queued))throw new AssertionError("surface queue changed pixels");
            if(VulkanGpuSceneFrameSubmitter.diagnosticQueuedSubmissions.sum()<=before)throw new AssertionError("primary queue did not execute");
            System.out.println("PrimaryQueueNativeSelfTest PASS (surface-only Vulkan)");
        } finally {
            if(previous==null)System.clearProperty(key);else System.setProperty(key,previous);
            if(previousDiagnostics==null)System.clearProperty(diagnosticKey);else System.setProperty(diagnosticKey,previousDiagnostics);
        }
    }
    static byte[] render() throws Exception {
        // Compare raw queue output, independently of the presentation reconstruction policy.
        var config=RendererConfig.recommended().copyBuilder()
            .temporalRendering(TemporalRenderingOptions.disabled())
            .frameReconstruction(FrameReconstructionOptions.disabled()).build();
        try(var renderer=Renderer.open("vulkan-rt",config)) {
            renderer.apply(SceneTransaction.builder(1).resetScene().upsert(MaterialAsset.opaque(1,0xff80a0c0))
                .upsert(MeshAsset.triangles(1,new float[]{-3,-2,0,3,-2,0,0,3,0},new int[]{0,1,2},1))
                .upsert(SceneInstance.builder(1,1).build()).build());
            var request=RenderFrameRequest.builder(0,160,90,CameraState.lookAt(0,0,5,0,0,0).aspectRatio(160.0/90).build())
                .minimumSceneRevision(1).build();
            long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
            while(renderer.trySubmit(request) instanceof Renderer.FrameSubmissionDeferred deferred) {
                if(System.nanoTime()>=deadline)throw new AssertionError("surface scene did not converge: "+deferred);
                renderer.diagnostics();
                Thread.sleep(1L);
            }
            var frame=renderer.awaitFrame(Duration.ofSeconds(30)).orElseThrow();
            if(frame.frameSequence()!=0||frame.renderedSceneRevision()!=1)throw new AssertionError("surface queue rendered the wrong frame or scene revision");
            var pixels=frame.pixels();byte[] output=new byte[pixels.remaining()];pixels.get(output);return output;
        }
    }
}

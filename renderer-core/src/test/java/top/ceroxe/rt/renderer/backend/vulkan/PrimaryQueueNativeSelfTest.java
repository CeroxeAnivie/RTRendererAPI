package top.ceroxe.rt.renderer.backend.vulkan;
import java.time.Duration;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
/** Ordinary Vulkan primary queues remain byte-identical after medium delegation. */
public final class PrimaryQueueNativeSelfTest {
    public static void main(String[] args) throws Exception {
        String key="top.ceroxe.rt.medium.primaryQueue",previous=System.getProperty(key);
        try {
            System.setProperty(key,"false");byte[] baseline=render();
            long before=VulkanGpuSceneFrameSubmitter.diagnosticQueuedSubmissions.sum();
            System.setProperty(key,"true");byte[] queued=render();
            if(!Arrays.equals(baseline,queued))throw new AssertionError("surface queue changed pixels");
            if(VulkanGpuSceneFrameSubmitter.diagnosticQueuedSubmissions.sum()<=before)throw new AssertionError("primary queue did not execute");
            System.out.println("PrimaryQueueNativeSelfTest PASS (surface-only Vulkan)");
        } finally {if(previous==null)System.clearProperty(key);else System.setProperty(key,previous);}
    }
    static byte[] render() throws Exception {
        var config=RendererConfig.recommended().copyBuilder().temporalRendering(TemporalRenderingOptions.disabled()).build();
        try(var renderer=Renderer.open("vulkan-rt",config)) {
            renderer.apply(SceneTransaction.builder(1).resetScene().upsert(MaterialAsset.opaque(1,0xff80a0c0))
                .upsert(MeshAsset.triangles(1,new float[]{-3,-2,0,3,-2,0,0,3,0},new int[]{0,1,2},1))
                .upsert(SceneInstance.builder(1,1).build()).build());
            renderer.submit(RenderFrameRequest.builder(0,160,90,CameraState.lookAt(0,0,5,0,0,0).aspectRatio(160.0/90).build()).build());
            var frame=renderer.awaitFrame(Duration.ofSeconds(30)).orElseThrow();var pixels=frame.pixels();byte[] output=new byte[pixels.remaining()];pixels.get(output);return output;
        }
    }
}

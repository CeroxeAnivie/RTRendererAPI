package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.Files;
import java.nio.file.Path;
import top.ceroxe.rt.renderer.api.*;

/** Public consumer regression for cached analytic bindings after resize, with preferred 2x FG. */
public final class RtxptAnalyticResizeNativeSelfTest {
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]);Files.createDirectories(output);
        var quality=PathTracingQualityOptions.defaults().withBeautyTransport(PathTracingQualityOptions.BeautyTransport.ANALYTIC_DIRECT);
        var config=RendererConfig.presentation().copyBuilder().denoising(DenoisingOptions.disabled())
                .pathTracing(PathTracingOptions.defaults().withQuality(quality)).build();
        try(var renderer=RtxptRenderers.openHiddenWindow(config)) {
            renderer.apply(SceneTransaction.builder(1).resetScene().upsert(MaterialAsset.builder(901).build())
                    .upsert(MeshAsset.builder(902,new float[]{-2,-2,0,2,-2,0,0,2,0},new int[]{0,1,2},new long[]{901}).build())
                    .upsert(SceneInstance.builder(903,902).build())
                    .upsert(SceneLight.point(904,0,0,2).intensity(100).range(10).castsShadow(true).build()).build());
            var access=renderer.extension(RtxptAccess.class).orElseThrow();
            int[][] sizes={{640,480},{960,540},{640,480}};long sequence=0;
            for(int phase=0;phase<sizes.length;phase++) {
                int width=sizes[phase][0],height=sizes[phase][1];
                var camera=CameraState.lookAt(0,0,4,0,0,0).aspectRatio(width/(double)height).build();
                for(int frame=0;frame<12;frame++)renderer.submit(RenderFrameRequest.builder(++sequence,width,height,camera)
                        .minimumSceneRevision(1).build());
                access.awaitIdle();access.capture(output.resolve("resize-"+phase+".png"));
                if(!access.renderingState().analyticBeauty())throw new AssertionError("Analytic path not executed");
                System.out.println("RESIZE_PHASE phase="+phase+" width="+width+" height="+height+" stats="+access.frameStatistics());
            }
            System.out.println("ANALYTIC_RESIZE_NATIVE PASS publicConsumer=true hidden=true phases=3 frames=36 fgRequested=2x");
        }
    }
}

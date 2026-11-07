package top.ceroxe.rt.renderer.rtxpt;

import top.ceroxe.rt.renderer.api.*;

/** Independent public-API consumer; no demo assets, identifiers or renderer internals. */
public final class RtxptAnalyticLightsNativeSelfTest {
    public static void main(String[] args) {
        var quality = PathTracingQualityOptions.defaults()
                .withBeautyTransport(PathTracingQualityOptions.BeautyTransport.ANALYTIC_DIRECT);
        var config = RendererConfig.builder().frameGeneration(FrameGenerationOptions.disabled())
                .frameReconstruction(FrameReconstructionOptions.disabled()).denoising(DenoisingOptions.disabled())
                .lowLatency(LowLatencyOptions.disabled()).rayTracingOptimizations(RayTracingOptimizationOptions.disabled())
                .pathTracing(new PathTracingOptions(1, 4, 0, RendererFeaturePreference.DISABLED,
                        RendererFeaturePreference.DISABLED, RendererFeaturePreference.DISABLED, quality)).build();
        try (var renderer = RtxptRenderers.openHiddenWindow(config)) {
            renderer.apply(SceneTransaction.builder(1).resetScene()
                    .upsert(MaterialAsset.builder(901).build())
                    .upsert(MeshAsset.builder(902, new float[]{-1,-1,0, 1,-1,0, 0,1,0},
                            new int[]{0,1,2}, new long[]{901}).build())
                    .upsert(SceneInstance.builder(903,902).build()).build());
            var camera = CameraState.lookAt(0,0,4,0,0,0).build();
            for (int frame = 0; frame < 3; frame++) {
                long revision = frame + 2;
                renderer.apply(SceneTransaction.builder(revision)
                        .upsert(SceneLight.point(904,0,0,2).intensity(4).range(10).castsShadow(frame == 1).build())
                        .build());
                renderer.submit(RenderFrameRequest.builder(frame,64,64,camera).minimumSceneRevision(revision).build());
            }
            renderer.extension(RtxptAccess.class).orElseThrow().awaitIdle();
        }
        System.out.println("ANALYTIC_LIGHTS_NATIVE PASS publicConsumer=true hidden=true frames=3 shadowOffOnOff=true");
    }
}

package top.ceroxe.rt.renderer.rtxpt;

import java.util.List;
import java.util.Set;
import top.ceroxe.rt.renderer.api.*;

/** Mixed opaque/cutout geometry shares one mesh, as permitted by the public contract. */
public final class RtxptMixedCoverageNativeSelfTest {
    public static void main(String[] args) {
        var plan = new NativeLightingPlan(64, 64, Set.of(NativeLightingPlan.Signal.DIRECT_GAIN),
                0, 6, 0, NativeLightingStyle.defaults());
        try (var renderer = RtxptRenderers.openNativeLighting(RtxptRenderers.nativeLightingConfiguration(), plan)) {
            var access = renderer.extension(RtxptAccess.class).orElseThrow();
            var lighting = renderer.extension(NativeLightingControl.class).orElseThrow();
            lighting.lightingReconstruction(NativeLightingReconstruction.NONE);
            var texture = TextureAsset.color(7, 2, 1,
                    new byte[]{-1, -1, -1, 0, -1, -1, -1, -1});
            renderer.apply(SceneTransaction.builder(1).resetScene().upsert(texture)
                    .upsert(MaterialAsset.builder(1).doubleSided(true).build())
                    .upsert(MaterialAsset.builder(2).blendMode(MaterialAsset.BlendMode.MASKED)
                            .baseColorTextureId(7).doubleSided(true).build())
                    .upsert(mesh(0)).upsert(SceneInstance.builder(1, 1).build()).build());
            lighting.publishLightingAttributes(new NativeLightingPublication(1,
                    List.of(NativeLightingPublication.Instance.receiver(1)), List.of(), null, null));
            var camera = CameraState.lookAt(0, 0, 3, 0, 0, 0).build();
            for (long sequence = 1; sequence <= 6; sequence++) {
                long revision = sequence >= 3 ? 2 : 1;
                if (sequence == 3) renderer.apply(SceneTransaction.builder(2).upsert(mesh(.1f)).build());
                renderer.submit(RenderFrameRequest.builder(sequence, 64, 64, camera)
                        .minimumSceneRevision(revision).build());
                access.awaitIdle();
                try (var lease = lighting.acquireLightingFrame().orElseThrow()) {
                    var frame = lease.frame();
                    if (frame.sequence() != sequence || frame.sceneRevision() != revision
                            || frame.planes().size() != 1 || frame.width() != 64 || frame.height() != 64)
                        throw new AssertionError("Mixed-coverage frame identity was not preserved");
                }
            }
            if (lighting.nativeLightingExecution().fullBeautyFrames() != 0)
                throw new AssertionError("Direct consumer unexpectedly executed full beauty");
        }
        System.out.println("MIXED_COVERAGE_NATIVE PASS frames=6 opaqueAndCutout=true topologyUpdate=true");
    }

    private static MeshAsset mesh(float z) {
        return MeshAsset.builder(1, new float[]{-1,-1,z, 1,-1,z, 1,1,z, -1,1,z},
                new int[]{0,1,2, 0,2,3}, new long[]{1,2})
                .textureCoordinates(new float[]{0,0, 1,0, 1,1, 0,1}).build();
    }
}

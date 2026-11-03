import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rtxpt.RtxptRenderers;

/** Uses published APIs and an offline extension bundle; no project-private JNI entry points. */
public final class NativeLightingExtensionExample {
    private static RendererConfig configuration() {
        var base=RendererConfig.builder().cpuFrameReadbackEnabled(false)
            .frameGeneration(FrameGenerationOptions.disabled()).build();
        var quality=base.pathTracing().quality();
        quality=quality.withLighting(quality.lighting().withInput(PathTracingQualityOptions.LightingInput.SCENE_ANALYTIC));
        return base.copyBuilder().pathTracing(new PathTracingOptions(1,1,0,RendererFeaturePreference.DISABLED,
            RendererFeaturePreference.DISABLED,RendererFeaturePreference.DISABLED,4,quality)).build();
    }
    private static SceneTransaction scene() {
        var receiver=MeshAsset.builder(1,new float[]{-1,-1,0,1,-1,0,1,1,0,-1,1,0},
            new int[]{0,1,2,0,2,3},new long[]{1,1})
            .lightmapCoordinates(new float[]{.5f,0,.5f,0,.5f,0,.5f,0}).build();
        var bounce=MeshAsset.builder(2,new float[]{-2,1,0,-2,1,4,2,1,4,2,1,0},
            new int[]{0,1,2,0,2,3},new long[]{2,2}).build();
        return SceneTransaction.builder(1).resetScene()
            .upsert(MaterialAsset.builder(1).baseColorRgba8(0xff808080).roughness(.8f).build())
            .upsert(MaterialAsset.builder(2).baseColorRgba8(0xff5070ff).roughness(.8f).build())
            .upsert(receiver).upsert(bounce).upsert(SceneInstance.builder(1,1).build())
            .upsert(SceneInstance.builder(2,2).build())
            .upsert(SceneLight.point(1,0,0,1.5).color(1,1,1).intensity(4).range(5).build()).build();
    }
    private static NativeLightingPublication parameters(long generation,float red,float green,float blue,float directStrength) {
        var data=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        data.putFloat(red).putFloat(green).putFloat(blue).putFloat(directStrength).flip();
        return new NativeLightingPublication(generation,List.of(NativeLightingPublication.Instance.receiver(1),
            new NativeLightingPublication.Instance(2,6,1,0,1,1,0,0)),List.of(),null,data);
    }
    private static RenderFrameRequest frame(long sequence,long nanos) {
        var camera=CameraState.lookAt(0,0,3,0,0,0).verticalFieldOfViewDegrees(52).aspectRatio(1.5).build();
        return RenderFrameRequest.builder(sequence,960,640,camera).minimumSceneRevision(1)
            .environment(EnvironmentState.neutral()).clock(FrameClock.of(nanos,sequence==0?0:500_000_000)).build();
    }
    private static void packet(NativeLightingControl control) {
        try(var lease=control.acquireLightingFrame().orElseThrow()) {
            var frame=lease.frame();
            if(frame.width()!=96||frame.height()!=64||frame.planes().size()!=2)
                throw new AssertionError("Expected two independent HDR lighting planes");
            System.out.println("sequence="+frame.sequence()+" scene="+frame.sceneRevision()+" attributes="
                +frame.attributeGeneration()+" clock="+frame.clock()+" planes="+frame.planes());
            // This metadata example does not import or read pixels. A real GPU consumer
            // imports on frame.adapterLuid(), enqueues readyValue, marks imported, reads,
            // signals completionValue, then calls completionSignalEnqueued() and closes.
            // Closing this unimported packet simply abandons its consumer slot.
        }
    }
    public static void main(String[] args) {
        if(args.length!=1)throw new IllegalArgumentException("Pass the extension manifest.json path");
        try(var renderer=RtxptRenderers.openNativeLighting(configuration(),NativeLightingPlan.defaults(96,64))) {
            var control=renderer.extension(NativeLightingControl.class).orElseThrow();
            renderer.apply(scene());control.publishLightingAttributes(parameters(1,1,.3f,.15f,1));
            control.activateLightingExtension(Path.of(args[0]));
            renderer.submit(frame(0,0));packet(control);
            // Data-only change: same pipeline, retained scene, textures, meshes and TLAS.
            control.publishLightingAttributes(parameters(2,.15f,.3f,1,.5f));
            renderer.submit(frame(1,500_000_000));packet(control);
            control.resetLightingExtension();renderer.submit(frame(2,1_000_000_000));packet(control);
            System.out.println("Native extension example completed: "+control.nativeLightingExecution());
        }
    }
}

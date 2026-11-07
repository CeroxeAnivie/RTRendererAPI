package top.ceroxe.rt.renderer.rtxpt;

import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** CPU-only data adaptation contracts; no GPU, native loading or private runtime probes. */
public final class RtxptAdapterSelfTest {
    static SceneTransaction scene(long revision) {
        return scene(revision,new Medium(new Rgb(.2f,.6f,.8f),1.5f));
    }
    static SceneTransaction scene(long revision,Medium medium) {
        var material=MaterialAsset.builder(1).blendMode(MaterialAsset.BlendMode.TRANSLUCENT).transmission(1).roughness(.04f).indexOfRefraction(1.5f).build();
        float[] p={-1,-1,-1,1,-1,-1,1,1,-1,-1,1,-1,-1,-1,1,1,-1,1,1,1,1,-1,1,1};
        int[] idx={4,5,6,4,6,7,0,2,1,0,3,2,0,4,7,0,7,3,1,2,6,1,6,5,3,7,6,3,6,2,0,1,5,0,5,4};
        long[] materials=new long[12];Arrays.fill(materials,1);
        return SceneTransaction.builder(revision).resetScene().upsert(material).upsert(MeshAsset.builder(1,p,idx,materials).build())
            .upsert(SceneInstance.builder(1,1).mediumBoundary(MediumBoundary.surface(medium).withPriority(4)).build()).build();
    }
    static CameraState camera(){return CameraState.lookAt(0,1,6,0,0,0).verticalFieldOfViewDegrees(55).aspectRatio(16d/9).build();}
    static RenderFrameRequest frame(long sequence){return RenderFrameRequest.builder(sequence,1920,1080,camera()).minimumSceneRevision(1)
        .environment(EnvironmentState.builder().skyRadiance(.4f,.6f,.9f).ambientIntensity(1).sunDirection(0,-1,0).sunIntensity(4).build()).build();}
    public static void main(String[] args) {
        inputContractRejectsIgnoredControls();
        RtxptLightingSelfTest.run();
        var source=new RtxptScene();source.retain(scene(1));
        var frame=frame(0);var items=source.items(frame);
        var mesh=source.meshes.get(1L);var expanded=RtxptScene.ExportGeometry.from(mesh);
        require(expanded.vertexCount()==36&&expanded.normals().remaining()==108,"Missing normals retain flat triangle-corner geometry");
        for(int corner=0;corner<36;corner++) {
            int original=mesh.triangleIndices().get(corner);
            require(expanded.indices().get(corner)==corner,"Triangle order survives conversion");
            for(int axis=0;axis<3;axis++)require(expanded.positions().get(corner*3+axis)==mesh.positions().get(original*3+axis),"Positions survive conversion");
            float x=expanded.normals().get(corner*3),y=expanded.normals().get(corner*3+1),z=expanded.normals().get(corner*3+2);
            require(Math.abs(x*x+y*y+z*z-1)<1e-6,"Fallback normals are finite unit vectors");
        }
        float[] authored=new float[mesh.vertexCount()*3];Arrays.fill(authored,1);
        require(RtxptScene.ExportGeometry.from(mesh.toBuilder().normals(authored).build()).vertexCount()==mesh.vertexCount(),"Authored normals preserve indexing");
        var moved=new RtxptScene(source);
        moved.retain(SceneTransaction.builder(2).upsert(SceneInstance.builder(1,1)
                .mediumBoundary(source.instances.get(1L).mediumBoundary().orElseThrow())
                .transform(new AffineTransform(new float[]{1,0,0,2,0,1,0,0,0,0,1,0})).build()).build());
        require(RtxptScene.sameStructure(items,moved.items(frame)),"Pose preserves resource structure");
        var changes=RtxptScene.changedItems(source,moved,items,moved.items(frame));
        require(changes.upserts().size()==1&&changes.removals().isEmpty(),"One pose edit publishes one instance update");
        require(source.instances.get(1L)!=moved.instances.get(1L),"Retained snapshots remain independent");
        require(RtxptOptions.resolve(RendererFeatureProfile.disabled()).reconstruction()==RtxptAccess.Reconstruction.NONE,"Disabled reconstruction stays disabled");
        System.out.println("RTXPT_ADAPTER_CPU PASS geometry=true retainedIdentity=true inputRejection=true lighting=true noNativeLoading=true");
    }
    private static void inputContractRejectsIgnoredControls() {
        RtxptInputContract.validateFrame(frame(0));
        rejected(()->RtxptInputContract.validateFrame(frame(0).toBuilder().antiAliasing(AntiAliasingState.multisampled(4)).build()),
            "Frame-local deterministic multisampling cannot silently become one sample");
        rejected(()->RtxptInputContract.validateFrame(frame(0).toBuilder().lightmap(new LightmapState(1,new int[LightmapState.ENTRY_COUNT])).build()),
            "Authored lightmap cannot silently disappear");
        rejected(()->RtxptInputContract.validateFrame(frame(0).toBuilder().textureSampling(TextureSamplingState.anisotropic(8)).build()),
            "Frame-local texture sampling cannot silently disappear");
        RtxptInputContract.validateFrame(frame(0).toBuilder().resetTemporalHistory(HistoryResetReason.EXPLICIT_RESET).build());
        RtxptInputContract.requireAdvancingRevision(0,0,false);
        RtxptInputContract.requireAdvancingRevision(1,0,true);
        try { RtxptInputContract.requireAdvancingRevision(1,1,true);throw new AssertionError("Duplicate scene revision accepted"); }
        catch(SceneRevisionException expected) { }
        try { RtxptInputContract.requireAdvancingRevision(0,1,true);throw new AssertionError("Stale scene revision accepted"); }
        catch(SceneRevisionException expected) { }
    }
    static void rejected(Runnable action,String message) {
        try { action.run();throw new AssertionError(message); }
        catch(IllegalArgumentException expected) { }
    }
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}

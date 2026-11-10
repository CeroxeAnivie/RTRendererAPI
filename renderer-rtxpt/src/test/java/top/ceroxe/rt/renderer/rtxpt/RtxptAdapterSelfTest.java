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
        nativeLightingRoles();
        nativeLightingPlanAndFog();
        nativeLightingPackedLight();
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
    private static void nativeLightingPackedLight() {
        var scene=new RtxptScene();scene.retain(scene(1));var mesh=scene.meshes.get(1L);
        int packed=120|(60<<16);
        var value=RtxptLightingRoles.defaults(1,packed,.4f,mesh,false);
        require(value.localScale()==0&&value.skyScale()==0&&value.localBias()==.5f&&value.skyBias()==.25f,
            "Absent mesh lightmap coordinates use both packed-light halves divided by 240");
        var row=new float[8];RtxptLightingRoles.pack(row,0,value,2,true);
        require(row[0]==18&&row[1]==.4f&&row[4]==0&&row[5]==0&&row[6]==.5f&&row[7]==.25f,
            "Packed-light fallback preserves ray roles and visibility");
        var vertex=mesh.toBuilder().lightmapCoordinates(new float[mesh.vertexCount()*2]).build();
        for(var authored:new MeshAsset[]{mesh,vertex}) {
            var result=RtxptLightingRoles.defaults(1,packed,1,authored,true);
            require(result.localScale()==1&&result.skyScale()==1&&result.localBias()==0&&result.skyBias()==0,
                "Explicit baseline publication overrides packed-light fallback");
        }
        var result=RtxptLightingRoles.defaults(1,packed,1,vertex,false);
        require(result.localScale()==1&&result.skyScale()==1&&result.localBias()==0&&result.skyBias()==0,
            "Authored mesh coordinates take precedence even when zero");
        var full=RtxptLightingRoles.defaults(1,SceneInstance.FULL_BRIGHT_PACKED_LIGHT,1,mesh,false);
        require(full.localBias()==1&&full.skyBias()==1,"Full-bright default does not turn into darkness");
        var a=PrimitiveInstance.builder(1).packedLight(packed).build();
        require(!RtxptLightingRoles.same(a,a.toBuilder().packedLight(0).build()),"Light-only primitive edits publish attributes");
        require(!RtxptLightingRoles.same(a,PrimitiveInstance.builder(2).packedLight(packed).build()),"Mesh changes re-evaluate baseline precedence");
        var transform=new AffineTransform(new float[]{1,0,0,2,0,1,0,3,0,0,1,4});
        var moved=a.toBuilder().previousTransform(transform).build();
        require(!RtxptLightingRoles.same(a,moved),"An explicit previous-pose-only change publishes motion attributes");
        var history=new float[20];RtxptLightingRoles.packHistory(history,0,moved);
        require(history[3]==3&&history[11]==2&&history[15]==3&&history[19]==4,
            "Previous object-to-world rows preserve translation and dynamic coverage flag");
    }
    private static void nativeLightingPlanAndFog() {
        var original=new NativeLightingPlan(16,12,java.util.Set.of(NativeLightingPlan.Signal.DIRECT_GAIN),0,6,0,
            NativeLightingStyle.defaults(),new NativeLightingPlan.Directional(0,1,0,1,1,1,1),null);
        require(original.directAlphaMode()==NativeLightingPlan.DirectAlphaMode.OPACITY,"Existing plans retain opacity alpha");
        var coverage=original.withDirectAlphaMode(NativeLightingPlan.DirectAlphaMode.RECONSTRUCTION_COVERAGE_V1)
            .withEncoding(NativeLightingPlan.Encoding.COMPACT_UNORM8);
        require(coverage.directAlphaMode()==NativeLightingPlan.DirectAlphaMode.RECONSTRUCTION_COVERAGE_V1,
            "Encoding changes preserve reconstruction metadata contract");
        var fog=DistanceFogState.builder().opacity(.75f).sphericalRange(5,10).cylindricalRange(8,20).build();
        var frame=frame(0).toBuilder().fog(fog).build();
        rejected(()->RtxptInputContract.validateFrame(frame),"Beauty cannot silently accept frame-local signal fog");
        RtxptInputContract.validateFrame(frame,true);
        var words=RtxptNativeLightingParameters.pack(coverage,frame);
        require(words.length==88&&words[71]==2&&words[80]==5&&words[81]==10&&words[82]==8&&words[83]==20&&words[84]==.75f,
            "Coverage flags and both distance-fog ranges reach the extended shader ABI");
        require(RtxptNativeLightingParameters.pack(original,frame(0))[71]==0,"Default plan retains its original fast-path flag");
    }
    private static void nativeLightingRoles() {
        var source=new RtxptScene();source.retain(scene(1));
        var shadow=SceneInstance.builder(1,1).visibilityMask(2).build();
        rejected(()->new RtxptScene(source).retain(SceneTransaction.builder(2).upsert(shadow).build()),"Beauty cannot silently accept custom ray roles");
        source.nativeLighting=true;
        source.retain(SceneTransaction.builder(2).upsert(shadow).build());
        var primitive=PrimitiveInstance.builder(1).visibilityMask(2).build();
        var frame=frame(0).toBuilder().primitiveBatch(FramePrimitiveBatch.of(List.of(primitive))).build();
        var items=source.items(frame);
        require(items==source.items(frame),"Unchanged frame primitives retain their converted input snapshot");
        require(RtxptLightingRoles.restrict(23,2,true)==18,"Shadow-only primitives never become primary receivers");
        require(RtxptLightingRoles.restrict(23,1,true)==17,"Receiver-only role excludes shadow and bounce");
        require(RtxptLightingRoles.restrict(23,3,true)==19,"Combined receiver/shadow retains both roles");
        require(RtxptLightingRoles.restrict(23,255,false)==21,"No-shadow excludes only shadow participation");
        require(RtxptLightingRoles.restrict(0,3,true)==0,"Explicit dormant roles remain dormant");
        var row=new float[8];var value=new NativeLightingPublication.Instance(1,23,.4f,.3f,1,1,0,0);
        RtxptLightingRoles.pack(row,0,value,2,true);
        require(row[0]==18&&row[1]==.4f&&row[2]==.3f,"Ray mask restriction preserves visibility and fog");
        rejected(()->source.items(frame.toBuilder().primitiveBatch(FramePrimitiveBatch.of(List.of(primitive.toBuilder().visibilityMask(8).build()))).build()),"Unsupported finite-signal masks fail explicitly");
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

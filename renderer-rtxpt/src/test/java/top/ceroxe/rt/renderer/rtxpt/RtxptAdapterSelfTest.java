package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
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
        geometryWireAndBuilderOwnership(source.meshes.get(1L));
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
    private static void geometryWireAndBuilderOwnership(MeshAsset original) {
        float[] authored=new float[original.vertexCount()*3];Arrays.fill(authored,1);
        float[] uvs=new float[original.vertexCount()*2],tangents=new float[original.vertexCount()*4];
        for(int i=0;i<uvs.length;i++)uvs[i]=i*.125f;
        for(int i=0;i<tangents.length;i++)tangents[i]=i*.0625f;
        var builder=original.toBuilder().textureCoordinates(uvs).tangents(tangents);
        var flat=builder.build();var mesh=builder.normals(authored).build();
        authored[0]=99;uvs[1]=99;builder.normals(new float[0]).textureCoordinates(new float[0]);
        require(mesh.normals().get(0)==1&&mesh.textureCoordinates().get(1)==.125f
            &&!flat.normals().hasRemaining()&&flat.textureCoordinates().get(1)==.125f,"Builder snapshots survive caller and builder changes");
        require(flat.positions().isReadOnly()&&mesh.triangleIndices().isReadOnly(),"Shared builder snapshots are read-only");
        for(var value:List.of(flat,mesh)){
            var size=new RtxptMemoryResources.Writer(null);size.geometry(value);
            var bytes=ByteBuffer.allocateDirect(Math.toIntExact(size.offset)).order(ByteOrder.LITTLE_ENDIAN);
            var write=new RtxptMemoryResources.Writer(bytes);write.geometry(value);
            require(size.offset==bytes.position()&&size.offset==write.offset,"Final geometry byte count matches reservation");
            bytes.flip();boolean missingNormals=value==flat;
            var positions=channel(bytes);var normals=channel(bytes);var packedTangents=channel(bytes);var packedUv=channel(bytes);
            int vertices=original.vertexCount();
            require(positions.remaining()==vertices*3&&normals.remaining()==(missingNormals?0:vertices*3)
                &&packedTangents.remaining()==vertices*4&&packedUv.remaining()==vertices*2,
                "Indexed canonical channels retain authored data; missing normals stay absent until GPU packing");
            require(bytes.getInt()==36,"Canonical index count");
            for(int corner=0;corner<36;corner++)require(bytes.getInt()==original.triangleIndices().get(corner),"Preserve winding and triangle order");
            require(!bytes.hasRemaining(),"No trailing geometry bytes");
            for(int vertex=0;vertex<vertices;vertex++){
                int input=vertex;
                for(int axis=0;axis<3;axis++)require(positions.get(vertex*3+axis)==original.positions().get(input*3+axis),"Original positions");
                for(int axis=0;axis<4;axis++)require(packedTangents.get(vertex*4+axis)==tangents[input*4+axis],"Original tangents");
                require(packedUv.get(vertex*2)==input*.25f&&packedUv.get(vertex*2+1)==input*.25f+.125f,"Original UVs");
                if(!missingNormals)require(normals.get(vertex*3)==1&&normals.get(vertex*3+1)==1
                        &&normals.get(vertex*3+2)==1,"Authored normal components are preserved");
            }
            require(missingNormals?normals.remaining()==0:normals.get(2)==1,"No generated CPU normal channel");
        }
    }
    private static FloatBuffer channel(ByteBuffer packet){
        int count=packet.getInt();var channel=packet.asFloatBuffer().limit(count);packet.position(packet.position()+count*4);return channel;
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

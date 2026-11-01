package top.ceroxe.rt.renderer.rtxpt;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** CPU-only adapter contract: no GPU and no native loading. */
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
    public static void main(String[] args) throws Exception {
        var s=new RtxptScene();s.retain(scene(1));var frame=frame(0);var items=s.items(frame);
        var mesh=s.meshes.get(1L);var expanded=RtxptScene.ExportGeometry.from(mesh);
        require(expanded.vertexCount()==36&&expanded.normals().remaining()==108,"Missing normals become flat triangle-corner normals");
        for(int corner=0;corner<36;corner++) {
            int original=mesh.triangleIndices().get(corner);
            require(expanded.indices().get(corner)==corner,"Expanded triangle order is preserved");
            for(int axis=0;axis<3;axis++)require(expanded.positions().get(corner*3+axis)==mesh.positions().get(original*3+axis),"Expanded positions are unchanged");
            float nx=expanded.normals().get(corner*3),ny=expanded.normals().get(corner*3+1),nz=expanded.normals().get(corner*3+2);
            require(Math.abs(nx*nx+ny*ny+nz*nz-1)<1e-6,"Every fallback normal is a unit vector");
        }
        require(expanded.normals().get(2)==1&&expanded.normals().get(20)==-1,"Flat normals follow outward triangle winding");
        float[] authoredNormals=new float[mesh.vertexCount()*3];java.util.Arrays.fill(authoredNormals,1);
        require(RtxptScene.ExportGeometry.from(mesh.toBuilder().normals(authoredNormals).build()).vertexCount()==mesh.vertexCount(),"Authored normals keep indexed geometry");
        Path dir=Files.createTempDirectory("rtxpt-adapter-test-");try {
            Path file=s.export(dir,frame,items);String json=Files.readString(file),gltf=Files.readString(dir.resolve("content.gltf"));
            require(json.contains("EnvironmentLight")&&json.contains("DirectionalLight"),"Native light graph");
            require(gltf.contains("KHR_materials_transmission")&&gltf.contains("KHR_materials_ior"),"Native transmission and IOR");
            require(gltf.contains("\"name\":\"i1\"")&&s.interiors.size()==1,"Interior assigned to imported material");
            require(Files.size(dir.resolve("content.bin"))>0,"Geometry is exported");
            require(gltf.contains("\"NORMAL\""),"Donut always receives explicit normals");
            require(RtxptScene.sameStructure(items,items),"Static scene does not reload");
            var moved=List.of(new RtxptScene.Item(items.getFirst().name(),1,new AffineTransform(new float[]{1,0,0,2,0,1,0,0,0,0,1,0}),items.getFirst().boundary(),1));
            require(RtxptScene.sameStructure(items,moved),"Transform edits do not reload assets");
            var profile=RtxptOptions.profile(RendererConfig.pathTracedPresentation());
            require(RtxptOptions.resolve(profile).reconstruction()==RtxptAccess.Reconstruction.DLSS_RR,"Ordinary API selects native ray reconstruction");
            require(RtxptOptions.resolve(RendererFeatureProfile.disabled()).reconstruction()==RtxptAccess.Reconstruction.NONE,"Disabled common features stay disabled");
            var live=new boolean[1];var applies=new int[1];
            var control=new RtxptFeatureController(new RtxptFeatureController.Host(){public boolean initialized(){return live[0];}public void ready(){}public void apply(RendererFeatureProfile p){applies[0]++;}},profile);
            var off=control.plan(RendererFeatureProfile.disabled());require(control.apply(off).outcome()==RendererFeatureApplyResult.Outcome.APPLIED,"Expert API applies the same policy before initialization");
            require(control.apply(off).outcome()==RendererFeatureApplyResult.Outcome.STALE_PLAN,"Expert plans are single-use");
            live[0]=true;var rebuild=control.plan(profile);require(control.apply(rebuild).outcome()==RendererFeatureApplyResult.Outcome.REQUIRES_RENDERER_REBUILD,"No hidden upstream pipeline rebuild");
            var latency=control.plan(control.effectiveProfile().toBuilder().lowLatency(LowLatencyOptions.recommended()).build());
            require(control.apply(latency).outcome()==RendererFeatureApplyResult.Outcome.APPLIED&&applies[0]==2,"Live expert latency control uses the common protocol");
            require(Medium.class.getRecordComponents().length==2,"No unsupported medium knobs");
            require(MediumBoundary.class.getRecordComponents().length==2,"No invisible/inward compatibility knobs");
        } finally {try(var walk=Files.walk(dir)){for(var p:walk.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
        System.out.println("RTXPT adapter contracts PASS");
    }
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}

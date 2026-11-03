package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Independent resources at two scene sizes, with actual new GPU frames and preparation evidence. */
public final class RtxptLocalResourceNativeSelfTest {
    private static final List<Object> ROWS=new ArrayList<>();
    private static long sequence;
    private static TextureAsset texture(long id,int alpha){return TextureAsset.color(id,1,1,new byte[]{-1,-1,-1,(byte)alpha});}
    private static MaterialAsset material(long id){var builder=MaterialAsset.builder(id).baseColorTextureId(id).baseColorRgba8(rgba(128,128,128));
        if(id==2)builder.blendMode(MaterialAsset.BlendMode.MASKED).alphaCutoff(.5f);return builder.build();}
    private static void check(RtxptRenderer renderer,int meshes,long revision,String name,double expected,
                              RtxptAccess.PreparationStatistics before,boolean local)throws Exception{
        var nativeBefore=renderer.nativePreparationStatistics();
        renderer.submit(frame(sequence,revision));near(capture(renderer,"local-"+meshes+"-"+name,0)[CENTER],expected,.002,name);
        packet(renderer,sequence++,revision,FrameClock.of(0,0));var after=renderer.preparationStatistics();
        var nativeAfter=renderer.nativePreparationStatistics();
        if(local){require(after.itemReads()==before.itemReads(),"No unrelated frame-list reads: "+name);
            require(after.attributeInstanceVisits()-before.attributeInstanceVisits()<=1,"At most one dependent attribute instance: "+name);
            require(nativeAfter.indexBuilds()==nativeBefore.indexBuilds(),"Local update keeps native indexes: "+name);
            require(nativeAfter.materialVisits()-nativeBefore.materialVisits()<=1&&nativeAfter.meshVisits()-nativeBefore.meshVisits()<=1,
                "Native resource visits cover at most one independent dependency: "+name);}
        ROWS.add(RtxptJson.object("meshes",meshes,"case",name,"sequence",sequence-1,"revision",revision,"itemReads",after.itemReads()-before.itemReads(),
            "dependencyVisits",after.dependencyVisits()-before.dependencyVisits(),"mapNodes",after.inspectedMapNodes()-before.inspectedMapNodes(),
            "exportedModels",after.modelExports()-before.modelExports(),"transformItems",after.transformItems()-before.transformItems(),
            "attributeInstanceVisits",after.attributeInstanceVisits()-before.attributeInstanceVisits(),"submitJavaAllocatedBytes",after.submitJavaAllocatedBytes()-before.submitJavaAllocatedBytes(),
            "nativeMaterialVisits",nativeAfter.materialVisits()-nativeBefore.materialVisits(),"nativeMeshVisits",nativeAfter.meshVisits()-nativeBefore.meshVisits(),
            "nativeIndexBuilds",nativeAfter.indexBuilds()-nativeBefore.indexBuilds(),
            "attributes",renderer.lightingAttributeUpdateStatistics().toString(),"retained",renderer.retainedUpdateTimings().toString()));
        Files.writeString(OUT.resolve("local-resource-progress.json"),RtxptJson.encode(ROWS));
    }
    private static void run(int count)throws Exception{
        sequence=0;
        try(var renderer=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4).copyBuilder().resources(RendererResourceOptions.high()).build(),true)){
            renderer.nativeLightingPlan(directPlan(SUN_ON));var tx=SceneTransaction.builder(1).resetScene();
            for(long id=1;id<=count;id++){
                tx.upsert(texture(id,255)).upsert(material(id));
                tx.upsert(id==1?plane(id,id,2,0,0,1,false):id==2?plane(id,id,4,1,0,0,false):plane(id,id,.1f,-10,0,0,false));
                var instance=SceneInstance.builder(id,id);if(id>2)instance.transform(new AffineTransform(new float[]{1,0,0,100+id,0,1,0,0,0,0,1,0}));tx.upsert(instance.build());
            }
            renderer.apply(tx.build());renderer.publishLightingAttributes(new NativeLightingPublication(1,List.of(NativeLightingPublication.Instance.shadowOnly(2)),List.of(),null,ByteBuffer.allocate(4096)));
            check(renderer,count,1,"initial",.45,renderer.preparationStatistics(),false);
            var before=renderer.preparationStatistics();check(renderer,count,1,"steady",.45,before,true);
            require(renderer.preparationStatistics().transformItems()==before.transformItems(),"Steady frame submits zero poses");
            renderer.apply(SceneTransaction.builder(2).upsert(material(1).toBuilder().roughness(.73f).build()).build());
            check(renderer,count,2,"material",.45,renderer.preparationStatistics(),true);
            renderer.apply(SceneTransaction.builder(3).upsert(TextureAsset.color(1,1,1,new byte[]{32,64,96,-1})).build());
            check(renderer,count,3,"texture-rgb",.45,renderer.preparationStatistics(),true);
            renderer.apply(SceneTransaction.builder(4).upsert(texture(2,0)).build());check(renderer,count,4,"texture-alpha",1,renderer.preparationStatistics(),true);
            renderer.apply(SceneTransaction.builder(5).upsert(texture(2,255)).build());check(renderer,count,5,"alpha-restored",.45,renderer.preparationStatistics(),true);
            long blases=renderer.retainedUpdateTimings().builtBlases();
            var tint=plane(1,1,2,0,0,1,false).toBuilder().vertexColorsRgba8(new int[]{0xff0000ff,0xff0000ff,0xff0000ff,0xff0000ff}).build();
            renderer.apply(SceneTransaction.builder(6).upsert(tint).build());before=renderer.preparationStatistics();
            check(renderer,count,6,"vertex-color",.45,before,true);require(renderer.preparationStatistics().modelExports()==before.modelExports(),"Tint exports no model");
            renderer.publishLightingAttributes(new NativeLightingPublication(2,List.of(),List.of(new NativeLightingPublication.MeshBaseline(1,new float[]{.5f,.75f,.5f,.75f,.5f,.75f,.5f,.75f})),null,null));
            before=renderer.preparationStatistics();check(renderer,count,6,"baseline",.41875,before,true);
            require(renderer.preparationStatistics().modelExports()==before.modelExports(),"Baseline exports no model");
            renderer.apply(SceneTransaction.builder(7).upsert(SceneInstance.builder(2,2).transform(new AffineTransform(new float[]{1,0,0,30,0,1,0,0,0,0,1,0})).build()).build());
            before=renderer.preparationStatistics();check(renderer,count,7,"pose",.625,before,true);
            require(renderer.preparationStatistics().transformItems()-before.transformItems()==1&&renderer.preparationStatistics().modelExports()==before.modelExports(),"One pose submitted directly");
            renderer.apply(SceneTransaction.builder(8).upsert(SceneLight.point(1,10000,10000,10000).range(1).build()).build());
            before=renderer.preparationStatistics();check(renderer,count,8,"light",.625,before,true);
            require(renderer.preparationStatistics().transformItems()==before.transformItems()&&renderer.preparationStatistics().modelExports()==before.modelExports(),"Pure light avoids transforms and export");
            renderer.patchLightingApplicationData(new NativeLightingApplicationPatch(3,12,ByteBuffer.allocate(4).putInt(7).flip()));
            var uploads=renderer.nativeLightingApplicationTransfers();check(renderer,count,8,"application-four-bytes",.625,renderer.preparationStatistics(),true);
            require(renderer.nativeLightingApplicationTransfers()[1]-uploads[1]==4,"Four-byte application upload");
            require(renderer.retainedUpdateTimings().builtBlases()==blases,"Tint, baseline, pose, light and application preserve BLAS");
            renderer.apply(SceneTransaction.builder(9).upsert(plane(count-1,1,.2f,-10,0,0,false))
                .upsert(SceneInstance.builder(count,count-1).transform(new AffineTransform(new float[]{1,0,0,999,0,1,0,0,0,0,1,0})).build()).build());
            check(renderer,count,9,"mixed-material-group-mapping",.625,renderer.preparationStatistics(),false);
            renderer.apply(SceneTransaction.builder(10).removeInstance(count).build());check(renderer,count,10,"remove",.625,renderer.preparationStatistics(),false);
            renderer.apply(SceneTransaction.builder(11).upsert(SceneInstance.builder(count,count).transform(new AffineTransform(new float[]{1,0,0,999,0,1,0,0,0,0,1,0})).build()).build());
            check(renderer,count,11,"restore",.625,renderer.preparationStatistics(),false);
            var frozen=renderer.preparationStatistics();var nativeFrozen=renderer.nativePreparationStatistics();renderer.diagnosticLevel(RtxptDiagnostics.Level.OFF);
            renderer.submit(frame(sequence,11));packet(renderer,sequence++,11,FrameClock.of(0,0));var off=renderer.preparationStatistics();
            require(!off.collecting()&&off.submissions()==frozen.submissions()&&off.submitJavaAllocatedBytes()==frozen.submitJavaAllocatedBytes(),"OFF freezes preparation instrumentation");
            var nativeOff=renderer.nativePreparationStatistics();require(!nativeOff.collecting()&&nativeOff.materialVisits()==nativeFrozen.materialVisits()
                &&nativeOff.meshVisits()==nativeFrozen.meshVisits()&&nativeOff.indexBuilds()==nativeFrozen.indexBuilds(),"OFF freezes native preparation counters");
            require(renderer.nativeLightingExecution().fullBeautyFrames()==0,"No beauty frames");
        }
    }
    public static void main(String[] args)throws Exception{
        Files.createDirectories(OUT);for(int count:new int[]{256,4096})run(count);
        Files.writeString(OUT.resolve("local-resource-matrix.json"),RtxptJson.encode(RtxptJson.object("passed",true,"rows",ROWS)));
        System.out.println("LOCAL_RESOURCE_NATIVE_ALL PASS sceneSizes=256,4096 cases="+ROWS.size()+" offFrozen=true");
    }
}

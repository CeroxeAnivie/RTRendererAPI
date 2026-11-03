package top.ceroxe.rt.renderer.rtxpt;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Large resident scene: local texture, baseline, pose, role, application and layout updates. */
public final class RtxptLightingResourceMatrixNativeSelfTest {
    static final List<Object> ROWS=new ArrayList<>();
    static long sequence;
    static void check(RtxptRenderer r,long revision,String name,double expected)throws Exception{
        r.submit(frame(sequence,revision));near(capture(r,"resource-"+name,0)[CENTER],expected,.002,name);
        packet(r,sequence++,revision,FrameClock.of(0,0));
        ROWS.add(RtxptJson.object("case",name,"attributes",r.lightingAttributeUpdateStatistics().toString(),
            "retained",r.retainedUpdateTimings().toString(),"centerGain",expected));
        Files.writeString(OUT.resolve("resource-matrix-progress.json"),RtxptJson.encode(ROWS));
    }
    static void sameAttributes(NativeLightingControl.AttributeUpdateStatistics before,RtxptRenderer r,String name){
        var after=r.lightingAttributeUpdateStatistics();require(after.baselineCorners()==before.baselineCorners()
            &&after.colorCorners()==before.colorCorners()&&after.layoutBuilds()==before.layoutBuilds(),name+" preserves resident attribute conversion and mapping");
    }
    public static void main(String[] args)throws Exception{
        Files.createDirectories(OUT);
        if(args.length>0&&args[0].equals("tail")){tail();return;}
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(directPlan(SUN_ON));var tx=SceneTransaction.builder(1).resetScene()
                .upsert(material(1,128,128,128)).upsert(alpha(255))
                .upsert(MaterialAsset.builder(2).baseColorTextureId(7).blendMode(MaterialAsset.BlendMode.MASKED).alphaCutoff(.5f).build())
                .upsert(plane(1,1,2,0,0,1,false)).upsert(plane(2,2,4,1,0,0,false))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build());
            for(long id=3;id<=256;id++)tx.upsert(plane(id,1,.1f,-10,0,0,false)).upsert(SceneInstance.builder(id,id)
                .transform(new AffineTransform(new float[]{1,0,0,100+id,0,1,0,0,0,0,1,0})).build());
            r.apply(tx.build());publication(r,1,NativeLightingPublication.Instance.shadowOnly(2));check(r,1,"initial",.45);
            var before=r.lightingAttributeUpdateStatistics();long blases=r.retainedUpdateTimings().builtBlases();
            r.apply(SceneTransaction.builder(2).upsert(alpha(0)).build());check(r,2,"alpha-texel-open",1);sameAttributes(before,r,"Texture-only update");
            r.apply(SceneTransaction.builder(3).upsert(alpha(255)).build());check(r,3,"alpha-texel-restored",.45);sameAttributes(before,r,"Texture restore");
            // Alpha coverage changes legitimately invalidate only their dependent BLAS.
            require(r.retainedUpdateTimings().builtBlases()-blases<=2,"At most one dependent BLAS per alpha edit");
            blases=r.retainedUpdateTimings().builtBlases();
            r.publishLightingAttributes(new NativeLightingPublication(2,List.of(),List.of(new NativeLightingPublication.MeshBaseline(1,
                new float[]{.5f,.75f,.5f,.75f,.5f,.75f,.5f,.75f})),null,ByteBuffer.allocate(4096)));
            check(r,3,"baseline",.41875);var baseline=r.lightingAttributeUpdateStatistics();
            require(baseline.baselineCorners()-before.baselineCorners()==6&&baseline.layoutBuilds()==before.layoutBuilds(),"One six-corner baseline patch in 256 meshes");
            r.apply(SceneTransaction.builder(4).upsert(SceneInstance.builder(2,2).transform(new AffineTransform(new float[]{1,0,0,30,0,1,0,0,0,0,1,0})).build()).build());
            check(r,4,"pose",.625);sameAttributes(baseline,r,"Pose-only update");
            publication(r,3,new NativeLightingPublication.Instance(1,0,1,0,1,1,0,0));check(r,4,"receiver-role-off",1);
            publication(r,4,NativeLightingPublication.Instance.receiver(1));check(r,4,"receiver-role-restored",.625);
            require(r.retainedUpdateTimings().builtBlases()==blases,"Texture, baseline, pose and roles preserve all resident BLAS");
            var appBefore=r.nativeLightingApplicationTransfers();r.patchLightingApplicationData(new NativeLightingApplicationPatch(5,12,ByteBuffer.allocate(4).putInt(7).flip()));
            check(r,4,"four-byte-application",.625);var appAfter=r.nativeLightingApplicationTransfers();
            require(appAfter[1]-appBefore[1]==4&&appAfter[2]-appBefore[2]==1,"Exactly one four-byte upload in a 256-mesh scene");
            r.apply(SceneTransaction.builder(5).upsert(material(1,64,128,255)).upsert(plane(255,1,.2f,-10,0,0,false))
                .upsert(SceneInstance.builder(254,254).transform(new AffineTransform(new float[]{1,0,0,999,0,1,0,0,0,0,1,0})).build()).build());
            check(r,5,"mixed-local-geometry",.625);
            require(r.retainedUpdateTimings().builtBlases()-blases==1,"Mixed transaction rebuilds only the edited geometry BLAS");
            r.apply(SceneTransaction.builder(6).removeInstance(256).build());check(r,6,"remove",.625);
            r.apply(SceneTransaction.builder(7).upsert(SceneInstance.builder(256,256).transform(new AffineTransform(new float[]{1,0,0,356,0,1,0,0,0,0,1,0})).build()).build());
            check(r,7,"restore",.625);require(r.nativeLightingExecution().fullBeautyFrames()==0,"No beauty work");
        }
        Files.writeString(OUT.resolve("resource-matrix.json"),RtxptJson.encode(RtxptJson.object("passed",true,"meshes",256,"rows",ROWS)));
        System.out.println("LIGHTING_RESOURCE_MATRIX PASS meshes=256 cases="+ROWS.size()+" applicationPatchBytes=4 unrelatedBlasPreserved=true");
    }
    static void tail()throws Exception{
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(directPlan(SUN_ON));var tx=SceneTransaction.builder(1).resetScene()
                .upsert(material(1,128,128,128)).upsert(alpha(255))
                .upsert(MaterialAsset.builder(2).baseColorTextureId(7).blendMode(MaterialAsset.BlendMode.MASKED).alphaCutoff(.5f).build())
                .upsert(plane(1,1,2,0,0,1,false)).upsert(plane(2,2,4,1,0,0,false)).upsert(SceneInstance.builder(1,1).build())
                .upsert(SceneInstance.builder(2,2).transform(new AffineTransform(new float[]{1,0,0,30,0,1,0,0,0,0,1,0})).build());
            for(long id=3;id<=256;id++)tx.upsert(plane(id,1,.1f,-10,0,0,false)).upsert(SceneInstance.builder(id,id)
                .transform(new AffineTransform(new float[]{1,0,0,100+id,0,1,0,0,0,0,1,0})).build());
            r.apply(tx.build());r.publishLightingAttributes(new NativeLightingPublication(1,List.of(NativeLightingPublication.Instance.shadowOnly(2)),
                List.of(new NativeLightingPublication.MeshBaseline(1,new float[]{.5f,.75f,.5f,.75f,.5f,.75f,.5f,.75f})),null,ByteBuffer.allocate(4096)));
            r.submit(frame(sequence,1));packet(r,sequence++,1,FrameClock.of(0,0));long blases=r.retainedUpdateTimings().builtBlases();
            r.apply(SceneTransaction.builder(2).upsert(SceneInstance.builder(2,2).transform(new AffineTransform(new float[]{1,0,0,35,0,1,0,0,0,0,1,0})).build()).build());
            check(r,2,"tail-pose",.625);require(r.retainedUpdateTimings().builtBlases()==blases,"Pose preserves all resident BLAS");
            var before=r.nativeLightingApplicationTransfers();r.patchLightingApplicationData(new NativeLightingApplicationPatch(2,12,ByteBuffer.allocate(4).putInt(7).flip()));
            check(r,2,"tail-application",.625);var after=r.nativeLightingApplicationTransfers();
            require(after[1]-before[1]==4&&after[2]-before[2]==1,"Four-byte patch emits only four bytes");
            r.apply(SceneTransaction.builder(3).upsert(material(1,64,128,255)).upsert(plane(255,1,.2f,-10,0,0,false))
                .upsert(SceneInstance.builder(254,254).transform(new AffineTransform(new float[]{1,0,0,999,0,1,0,0,0,0,1,0})).build()).build());
            check(r,3,"tail-mixed-local-geometry",.625);require(r.retainedUpdateTimings().builtBlases()-blases==1,"Only edited geometry rebuilds its BLAS");
            r.apply(SceneTransaction.builder(4).removeInstance(256).build());check(r,4,"tail-remove",.625);
            r.apply(SceneTransaction.builder(5).upsert(SceneInstance.builder(256,256).transform(new AffineTransform(new float[]{1,0,0,356,0,1,0,0,0,0,1,0})).build()).build());check(r,5,"tail-restore",.625);
        }
        Files.writeString(OUT.resolve("resource-matrix-tail.json"),RtxptJson.encode(RtxptJson.object("passed",true,"meshes",256,"rows",ROWS,"supplementOnly",true)));
        System.out.println("LIGHTING_RESOURCE_MATRIX_TAIL PASS meshes=256 patchBytes=4 localBlasOnly=true cases="+ROWS.size());
    }
}

package top.ceroxe.rt.renderer.rtxpt;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Large resident scene: local texture, baseline, pose, role, application and layout updates. */
public final class RtxptLightingResourceMatrixNativeSelfTest {
    private static final class Progress {
        final List<Object> rows=new ArrayList<>();
        long sequence;
    }
    static void check(RtxptLightingTestSession.Case test,Progress progress,long revision,String name,double expected)throws Exception{
        var r=test.renderer;long sequence=progress.sequence++;
        r.submit(test.frame(sequence,revision));near(capture(r,"resource-"+name,0)[CENTER],expected,.002,name);
        test.packet(sequence,revision,FrameClock.of(0,0));
        progress.rows.add(RtxptJson.object("case",name,"attributes",r.lightingAttributeUpdateStatistics().toString(),
            "retained",r.retainedUpdateTimings().toString(),"maintenance",r.resourceMaintenance().toString(),"centerGain",expected));
        Files.writeString(OUT.resolve("resource-matrix-progress.json"),RtxptJson.encode(progress.rows));
    }
    static void sameAttributes(NativeLightingControl.AttributeUpdateStatistics before,RtxptRenderer r,String name){
        var after=r.lightingAttributeUpdateStatistics();require(after.baselineCorners()==before.baselineCorners()
            &&after.colorCorners()==before.colorCorners()&&after.layoutBuilds()==before.layoutBuilds(),name+" preserves resident attribute conversion and mapping");
    }
    public static void main(String[] args)throws Exception{
        Files.createDirectories(OUT);
        var cases=args.length==0?List.of("main"):List.of(args);
        for(var value:cases)if(!List.of("main","tail").contains(value))throw new IllegalArgumentException(value);
        try(var session=new RtxptLightingTestSession()){
            for(var value:cases)run(session.begin("resources/"+value),value.equals("tail"));
        }
    }
    static void run(RtxptLightingTestSession.Case test,boolean tail)throws Exception {
        var progress=new Progress();
        if(tail){tail(test,progress);return;}
        var r=test.renderer;
        {
            r.nativeLightingPlan(directPlan(SUN_ON));var tx=test.scene(1).resetScene()
                .upsert(material(1,128,128,128)).upsert(alpha(255))
                .upsert(MaterialAsset.builder(2).baseColorTextureId(7).blendMode(MaterialAsset.BlendMode.MASKED).alphaCutoff(.5f).build())
                .upsert(plane(1,1,2,0,0,1,false)).upsert(plane(2,2,4,1,0,0,false))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build());
            for(long id=3;id<=256;id++)tx.upsert(plane(id,1,.1f,-10,0,0,false)).upsert(SceneInstance.builder(id,id)
                .transform(new AffineTransform(new float[]{1,0,0,100+id,0,1,0,0,0,0,1,0})).build());
            r.apply(tx.build());test.publication(1,NativeLightingPublication.Instance.shadowOnly(2));check(test,progress,1,"initial",.45);
            var before=r.lightingAttributeUpdateStatistics();long blases=r.retainedUpdateTimings().builtBlases();
            r.apply(test.scene(2).upsert(alpha(0)).build());check(test,progress,2,"alpha-texel-open",1);sameAttributes(before,r,"Texture-only update");
            r.apply(test.scene(3).upsert(alpha(255)).build());check(test,progress,3,"alpha-texel-restored",.45);sameAttributes(before,r,"Texture restore");
            // Alpha coverage changes legitimately invalidate only their dependent BLAS.
            require(r.retainedUpdateTimings().builtBlases()-blases<=2,"At most one dependent BLAS per alpha edit");
            blases=r.retainedUpdateTimings().builtBlases();
            test.publish(new NativeLightingPublication(2,List.of(),List.of(new NativeLightingPublication.MeshBaseline(1,
                new float[]{.5f,.75f,.5f,.75f,.5f,.75f,.5f,.75f})),null,ByteBuffer.allocate(4096)));
            check(test,progress,3,"baseline",.41875);var baseline=r.lightingAttributeUpdateStatistics();
            require(baseline.baselineCorners()-before.baselineCorners()==6&&baseline.layoutBuilds()==before.layoutBuilds(),"One six-corner baseline patch in 256 meshes");
            r.apply(test.scene(4).upsert(SceneInstance.builder(2,2).transform(new AffineTransform(new float[]{1,0,0,30,0,1,0,0,0,0,1,0})).build()).build());
            check(test,progress,4,"pose",.625);sameAttributes(baseline,r,"Pose-only update");
            test.publication(3,new NativeLightingPublication.Instance(1,0,1,0,1,1,0,0));check(test,progress,4,"receiver-role-off",1);
            test.publication(4,NativeLightingPublication.Instance.receiver(1));check(test,progress,4,"receiver-role-restored",.625);
            require(r.retainedUpdateTimings().builtBlases()==blases,"Texture, baseline, pose and roles preserve all resident BLAS");
            var appBefore=r.nativeLightingApplicationTransfers();test.patch(new NativeLightingApplicationPatch(5,12,ByteBuffer.allocate(4).putInt(7).flip()));
            check(test,progress,4,"four-byte-application",.625);var appAfter=r.nativeLightingApplicationTransfers();
            require(appAfter[1]-appBefore[1]==4&&appAfter[2]-appBefore[2]==1,"Exactly one four-byte upload in a 256-mesh scene");
            var mixedBefore=r.resourceMaintenance();long retainedBefore=r.retainedUpdateTimings().retainedBlases();
            r.apply(test.scene(5).upsert(material(1,64,128,255)).upsert(plane(255,1,.2f,-10,0,0,false))
                .upsert(SceneInstance.builder(254,254).transform(new AffineTransform(new float[]{1,0,0,999,0,1,0,0,0,0,1,0})).build()).build());
            check(test,progress,5,"mixed-local-geometry",.625);
            requireOneLocalBlasUpdate(r,mixedBefore,retainedBefore);
            r.apply(test.scene(6).removeInstance(256).build());check(test,progress,6,"remove",.625);
            r.apply(test.scene(7).upsert(SceneInstance.builder(256,256).transform(new AffineTransform(new float[]{1,0,0,356,0,1,0,0,0,0,1,0})).build()).build());
            check(test,progress,7,"restore",.625);require(r.nativeLightingExecution().fullBeautyFrames()==0,"No beauty work");
        }
        Files.writeString(OUT.resolve("resource-matrix.json"),RtxptJson.encode(RtxptJson.object("passed",true,"meshes",256,"rows",progress.rows)));
        System.out.println("LIGHTING_RESOURCE_MATRIX PASS meshes=256 cases="+progress.rows.size()+" applicationPatchBytes=4 unrelatedBlasPreserved=true");
    }
    static void requireOneLocalBlasUpdate(RtxptRenderer r,RtxptAccess.ResourceMaintenance before,long retainedBefore){
        var after=r.resourceMaintenance();
        require(after.blasBuilds()-before.blasBuilds()+after.blasRefits()-before.blasRefits()==1,
            "Mixed transaction builds or refits exactly one edited geometry BLAS: before="+before+" after="+after);
        require(r.retainedUpdateTimings().retainedBlases()-retainedBefore==255,
            "Mixed transaction preserves all 255 unrelated resident BLAS");
    }
    static void tail(RtxptLightingTestSession.Case test,Progress progress)throws Exception{
        var r=test.renderer;
        {
            r.nativeLightingPlan(directPlan(SUN_ON));var tx=test.scene(1).resetScene()
                .upsert(material(1,128,128,128)).upsert(alpha(255))
                .upsert(MaterialAsset.builder(2).baseColorTextureId(7).blendMode(MaterialAsset.BlendMode.MASKED).alphaCutoff(.5f).build())
                .upsert(plane(1,1,2,0,0,1,false)).upsert(plane(2,2,4,1,0,0,false)).upsert(SceneInstance.builder(1,1).build())
                .upsert(SceneInstance.builder(2,2).transform(new AffineTransform(new float[]{1,0,0,30,0,1,0,0,0,0,1,0})).build());
            for(long id=3;id<=256;id++)tx.upsert(plane(id,1,.1f,-10,0,0,false)).upsert(SceneInstance.builder(id,id)
                .transform(new AffineTransform(new float[]{1,0,0,100+id,0,1,0,0,0,0,1,0})).build());
            r.apply(tx.build());test.publish(new NativeLightingPublication(1,List.of(NativeLightingPublication.Instance.shadowOnly(2)),
                List.of(new NativeLightingPublication.MeshBaseline(1,new float[]{.5f,.75f,.5f,.75f,.5f,.75f,.5f,.75f})),null,ByteBuffer.allocate(4096)));
            r.submit(test.frame(progress.sequence,1));test.packet(progress.sequence++,1,FrameClock.of(0,0));long blases=r.retainedUpdateTimings().builtBlases();
            r.apply(test.scene(2).upsert(SceneInstance.builder(2,2).transform(new AffineTransform(new float[]{1,0,0,35,0,1,0,0,0,0,1,0})).build()).build());
            check(test,progress,2,"tail-pose",.625);require(r.retainedUpdateTimings().builtBlases()==blases,"Pose preserves all resident BLAS");
            var before=r.nativeLightingApplicationTransfers();test.patch(new NativeLightingApplicationPatch(2,12,ByteBuffer.allocate(4).putInt(7).flip()));
            check(test,progress,2,"tail-application",.625);var after=r.nativeLightingApplicationTransfers();
            require(after[1]-before[1]==4&&after[2]-before[2]==1,"Four-byte patch emits only four bytes");
            var mixedBefore=r.resourceMaintenance();long retainedBefore=r.retainedUpdateTimings().retainedBlases();
            r.apply(test.scene(3).upsert(material(1,64,128,255)).upsert(plane(255,1,.2f,-10,0,0,false))
                .upsert(SceneInstance.builder(254,254).transform(new AffineTransform(new float[]{1,0,0,999,0,1,0,0,0,0,1,0})).build()).build());
            check(test,progress,3,"tail-mixed-local-geometry",.625);requireOneLocalBlasUpdate(r,mixedBefore,retainedBefore);
            r.apply(test.scene(4).removeInstance(256).build());check(test,progress,4,"tail-remove",.625);
            r.apply(test.scene(5).upsert(SceneInstance.builder(256,256).transform(new AffineTransform(new float[]{1,0,0,356,0,1,0,0,0,0,1,0})).build()).build());check(test,progress,5,"tail-restore",.625);
        }
        Files.writeString(OUT.resolve("resource-matrix-tail.json"),RtxptJson.encode(RtxptJson.object("passed",true,"meshes",256,"rows",progress.rows,"supplementOnly",true)));
        System.out.println("LIGHTING_RESOURCE_MATRIX_TAIL PASS meshes=256 patchBytes=4 localBlasOnly=true cases="+progress.rows.size());
    }
}

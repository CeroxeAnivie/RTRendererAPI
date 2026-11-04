package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** New composition coverage for small-group shaders, mixed patch/refit and ring pressure. */
public final class RtxptHighFramerateCompositionNativeSelfTest {
    static List<SparseLightSource> sources(int groupOffset){
        var values=new ArrayList<SparseLightSource>();
        for(int i=0;i<48;i++)values.add(new SparseLightSource(i,SparseLightSource.Shape.BOX,(i%8)*.15-.6,(i/8)*.15-.45,.6,
            .04f,.04f,.04f,0,-1,0,i%3==0?1:.3f,i%3==1?1:.2f,i%3==2?1:.1f,.15f,4,2,1,-1,
            SparseLightSource.Composition.values()[i%3],groupOffset+i%4,i%2==0,i%2==0?SparseLightSource.Layer.DYNAMIC:SparseLightSource.Layer.STATIC));
        return values;
    }
    static void frame(RtxptRenderer r,long sequence,long revision){RtxptHighFramerateResourcesNativeSelfTest.render(r,sequence,revision);}
    public static void main(String[] args)throws Exception{
        Files.createDirectories(OUT);double maxError=Double.NaN;
        if(args.length>0&&args[0].equals("single-group")){singleGroup();return;}
        if(args.length>0&&args[0].equals("layers")){layers();return;}
        if(args.length>0&&args[0].equals("pair-groups")){pairGroups();return;}
        if(args.length>0&&args[0].startsWith("history-")){history(args[0]);return;}
        if(args.length>0&&args[0].startsWith("signal-reuse-")){signalReuse(args[0]);return;}
        if(args.length>0&&args[0].startsWith("bounds")){bounds(args[0]);return;}
        if(args.length>0&&args[0].equals("groups")){maxError=0;try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(plan(Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.INDIRECT_DIFFUSE),2,SUN_OFF,true));
            r.apply(SceneTransaction.builder(1).resetScene().upsert(material(1,128,128,128)).upsert(material(2,180,120,60))
                .upsert(plane(1,1,2,0,0,0,false)).upsert(plane(2,2,4,1,0,0,false))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build(),sources(0),LongBuffer.allocate(0));
            publication(r,1,new NativeLightingPublication.Instance(2,4,1,0,1,1,0,0));frame(r,0,1);
            var gain=capture(r,"small-groups-gain",0);var gi=capture(r,"small-groups-gi",1);
            r.apply(SceneTransaction.builder(2).build(),sources(8),LongBuffer.allocate(0));frame(r,1,2);
            var wideGain=capture(r,"wide-groups-gain",0);var wideGi=capture(r,"wide-groups-gi",1);
            for(int i=0;i<gain.length;i++){maxError=Math.max(maxError,Math.abs(gain[i]-wideGain[i]));maxError=Math.max(maxError,Math.abs(gi[i]-wideGi[i]));}
            require(maxError<=.0001,"Small-group shader preserves every gain/GI pixel: "+maxError);r.awaitIdle();
        }System.out.println("HIGH_FRAMERATE_GROUPS PASS maxError="+maxError);return;}
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            byte[] pixels=new byte[64*64*4];Arrays.fill(pixels,(byte)255);var texture=TextureAsset.color(2,64,64,pixels);
            r.nativeLightingPlan(directPlan(SUN_ON));
            r.apply(SceneTransaction.builder(1).resetScene().upsert(texture).upsert(material(1,128,128,128))
                .upsert(MaterialAsset.builder(2).baseColorTextureId(2).blendMode(MaterialAsset.BlendMode.MASKED).alphaCutoff(.5f).build())
                .upsert(plane(1,1,2,0,0,1,false)).upsert(plane(2,2,4,1,0,0,false))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).upsert(SceneInstance.builder(3,2).build()).build());
            publication(r,1,NativeLightingPublication.Instance.shadowOnly(2),NativeLightingPublication.Instance.shadowOnly(3));
            frame(r,0,1);frame(r,1,1);r.awaitIdle();var before=r.resourceMaintenance();
            near(capture(r,"mixed-patch-before",0)[CENTER],.45,.002,"Shared opaque blockers visible");
            byte[] patch=new byte[4*4*4];for(int i=0;i<patch.length;i+=4)patch[i]=patch[i+1]=patch[i+2]=(byte)255;
            var next=texture.patched(new TexturePatch(2,0,30,30,4,4,16,ByteBuffer.wrap(patch)));
            r.apply(SceneTransaction.builder(2).upsert(next).upsert(plane(2,2,4,1.01f,0,0,false))
                .upsert(MaterialAsset.builder(2).baseColorTextureId(2).blendMode(MaterialAsset.BlendMode.MASKED).alphaCutoff(.6f).build()).build());
            frame(r,2,2);var result=capture(r,"mixed-patch-after",0);near(result[CENTER],1,.002,"Patch, material and shared refit use the same revision");
            near(result[((H/2)*W+W/4)*3],.45,.002,"Unchanged coverage around rectangle remains opaque");
            var after=r.resourceMaintenance();require(after.blasRefits()==before.blasRefits()+1&&after.blasBuilds()==before.blasBuilds(),"One shared mesh refits once in mixed transaction: before="+before+" after="+after+" decisions="+r.protocolMonitor().snapshot().events().stream().filter(e->e.kind()==6||e.kind()==7).toList());
            var held=new ArrayList<LightingFrameLease>();
            for(long seq=3;seq<6;seq++){r.submit(RtxptNativeLightingMatrixNativeSelfTest.frame(seq,2));held.add(r.acquireLightingFrame().orElseThrow());}
            var retry=RtxptNativeLightingMatrixNativeSelfTest.frame(6,2);require(r.trySubmit(retry) instanceof Renderer.FrameSubmissionDeferred,"Full output ring defers before mutation");
            held.get(2).close();held.get(0).close();held.get(1).close();long deadline=System.nanoTime()+2_000_000_000L;
            while(r.trySubmit(retry) instanceof Renderer.FrameSubmissionDeferred){require(System.nanoTime()<deadline,"Out-of-order returns recover");Thread.sleep(1);}
            try(var lease=r.acquireLightingFrame().orElseThrow()){require(lease.frame().sequence()==6&&lease.frame().sceneRevision()==2,"Retry preserves immutable frame identity");}r.awaitIdle();
            require(r.dynamicResourceStatistics().deviceDrains()==0,"Mixed update and ring pressure perform no device drain");
        }
        System.out.println("HIGH_FRAMERATE_COMPOSITION PASS groupPixelMaxError="+maxError+" mixedSharedRefit=1 rectangle=true ringDefer=true returnOrder=true");
    }
    static void bounds(String label)throws Exception{
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(plan(Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.INDIRECT_DIFFUSE),2,SUN_OFF,true));
            var values=sources(0).stream().map(s->s.withComposition(SparseLightSource.Composition.VISIBLE_GROUP_MAX,s.group())).toList();
            r.apply(SceneTransaction.builder(1).resetScene().upsert(material(1,128,128,128)).upsert(material(2,180,120,60))
                .upsert(plane(1,1,2,0,0,0,false)).upsert(plane(2,2,4,1,0,0,false))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build(),values,LongBuffer.allocate(0));
            publication(r,1,new NativeLightingPublication.Instance(2,4,1,0,1,1,0,0));frame(r,0,1);
            capture(r,label+"-gain",0);capture(r,label+"-gi",1);
            var edits=values.stream().filter(s->s.id()%5==0).map(s->s.withAttenuation(SparseLightSource.Attenuation.MANHATTAN_LINEAR)).toList();
            r.apply(SceneTransaction.builder(2).build(),edits,LongBuffer.wrap(new long[]{1,2,3}));frame(r,1,2);
            capture(r,label+"-edited-gain",0);capture(r,label+"-edited-gi",1);r.awaitIdle();
        }
        System.out.println("SPARSE_NODE_BOUNDS_CAPTURE "+label);
    }
    static void singleGroup()throws Exception{
        double error=0;
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(plan(Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.INDIRECT_DIFFUSE),2,SUN_OFF,true));
            var values=sources(0).stream().map(s->s.withComposition(SparseLightSource.Composition.VISIBLE_GROUP_MAX,31)).toList();
            r.apply(SceneTransaction.builder(1).resetScene().upsert(material(1,128,128,128)).upsert(material(2,180,120,60))
                .upsert(plane(1,1,2,0,0,0,false)).upsert(plane(2,2,4,1,0,0,false))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build(),values,LongBuffer.allocate(0));
            publication(r,1,new NativeLightingPublication.Instance(2,4,1,0,1,1,0,0));frame(r,0,1);
            var gain=capture(r,"bounded-single-high-gain",0);var gi=capture(r,"bounded-single-high-gi",1);
            r.apply(SceneTransaction.builder(2).build(),values.stream().map(s->s.withComposition(s.composition(),0)).toList(),LongBuffer.allocate(0));frame(r,1,2);
            var nextGain=capture(r,"bounded-single-low-gain",0);var nextGi=capture(r,"bounded-single-low-gi",1);
            for(int i=0;i<gain.length;i++){error=Math.max(error,Math.abs(gain[i]-nextGain[i]));error=Math.max(error,Math.abs(gi[i]-nextGi[i]));}
            require(error==0,"Single-group node masks remap high IDs exactly: "+error);r.awaitIdle();
        }
        System.out.println("BOUNDED_SINGLE_GROUP PASS maxPixelError="+error);
    }
    static void layers()throws Exception{
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(plan(Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.INDIRECT_DIFFUSE),2,SUN_OFF,true));
            var values=sources(0).stream().map(s->s.withComposition(SparseLightSource.Composition.VISIBLE_GROUP_MAX,0)
                .withLayer(SparseLightSource.Layer.DYNAMIC)).toList();
            r.apply(SceneTransaction.builder(1).resetScene().upsert(material(1,128,128,128)).upsert(material(2,180,120,60))
                .upsert(plane(1,1,2,0,0,0,false)).upsert(plane(2,2,4,1,0,0,false))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build(),values,LongBuffer.allocate(0));
            publication(r,1,new NativeLightingPublication.Instance(2,4,1,0,1,1,0,0));frame(r,0,1);
            var gain=capture(r,"all-dynamic-gain",0);var gi=capture(r,"all-dynamic-gi",1);
            r.apply(SceneTransaction.builder(2).build(),values.stream().map(s->s.withLayer(SparseLightSource.Layer.STATIC)).toList(),LongBuffer.allocate(0));frame(r,1,2);
            var staticGain=capture(r,"all-static-gain",0);var staticGi=capture(r,"all-static-gi",1);
            double giError=0,boost=0;for(int i=0;i<gain.length;i++){giError=Math.max(giError,Math.abs(gi[i]-staticGi[i]));boost=Math.max(boost,gain[i]-staticGain[i]);}
            require(giError==0,"Layer reuse preserves every GI pixel: "+giError);require(boost>.001,"All-dynamic sources retain their direct boost: "+boost);r.awaitIdle();
            System.out.println("SPARSE_LAYER_REUSE PASS giMaxError="+giError+" dynamicBoost="+boost);
        }
    }
    static void pairGroups()throws Exception{
        double error=0;
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(plan(Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.INDIRECT_DIFFUSE),2,SUN_OFF,true));
            for(int variant=0;variant<2;variant++){
                final boolean maximum=variant==0;
                var values=sources(0).stream().map(s->s.withComposition(maximum?SparseLightSource.Composition.VISIBLE_GROUP_MAX:s.composition(),(int)s.id()%2)).toList();
                long revision=variant*2+1;
                var tx=SceneTransaction.builder(revision);
                if(variant==0)tx.resetScene().upsert(material(1,128,128,128)).upsert(material(2,180,120,60))
                    .upsert(plane(1,1,2,0,0,0,false)).upsert(plane(2,2,4,1,0,0,false))
                    .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build());
                r.apply(tx.build(),values,LongBuffer.allocate(0));
                if(variant==0)publication(r,1,new NativeLightingPublication.Instance(2,4,1,0,1,1,0,0));
                frame(r,revision-1,revision);var gain=capture(r,"pair-"+variant+"-gain",0);var gi=capture(r,"pair-"+variant+"-gi",1);
                r.apply(SceneTransaction.builder(revision+1).build(),values.stream().map(s->s.withComposition(s.composition(),s.group()+8)).toList(),LongBuffer.allocate(0));
                frame(r,revision,revision+1);var wideGain=capture(r,"wide-pair-"+variant+"-gain",0);var wideGi=capture(r,"wide-pair-"+variant+"-gi",1);
                for(int i=0;i<gain.length;i++){error=Math.max(error,Math.abs(gain[i]-wideGain[i]));error=Math.max(error,Math.abs(gi[i]-wideGi[i]));}
            }
            require(error==0,"Two-group max and mixed pipelines match full groups exactly: "+error);r.awaitIdle();
        }
        System.out.println("PAIR_GROUPS PASS maxPixelError="+error+" maximum=true mixed=true");
    }
    static void signalReuse(String label)throws Exception{
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(plan(Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.INDIRECT_DIFFUSE),2,SUN_ON,true));
            var values=sources(0).stream().map(s->s.withComposition(SparseLightSource.Composition.VISIBLE_GROUP_MAX,(int)s.id()%2)).toList();
            var texture=RtxptHighFramerateResourcesNativeSelfTest.texture(255);
            r.apply(SceneTransaction.builder(1).resetScene().upsert(texture).upsert(material(1,128,128,128))
                .upsert(MaterialAsset.builder(2).baseColorTextureId(2).blendMode(MaterialAsset.BlendMode.MASKED).alphaCutoff(.5f).build())
                .upsert(plane(1,1,2,0,0,0,false)).upsert(plane(2,2,.4f,1,0,0,false))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build(),values,LongBuffer.allocate(0));
            long sequence=0,revision=1;
            var camera=RtxptNativeLightingMatrixNativeSelfTest.frame(0,1).camera();
            for(int mode=0;mode<2;mode++){
                final var layer=mode==0?SparseLightSource.Layer.STATIC:SparseLightSource.Layer.DYNAMIC;
                if(mode==1){
                    r.nativeLightingPlan(new NativeLightingPlan(128,96,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.INDIRECT_DIFFUSE),2,6,.35f,NativeLightingStyle.defaults(),SUN_OFF,null,true));
                    r.lightingReconstruction(NativeLightingReconstruction.DLSS_RR);
                }
                for(int stage=0;stage<7;stage++){
                    if(stage==1)camera=CameraState.lookAt(.2*(mode+1),0,3,0,0,0).verticalFieldOfViewDegrees(52).aspectRatio(1.5).build();
                    if(stage==2)r.apply(SceneTransaction.builder(++revision).upsert(RtxptHighFramerateResourcesNativeSelfTest.texture(mode==0?0:255)).build());
                    if(stage==3)r.apply(SceneTransaction.builder(++revision).upsert(plane(1,1,2,.15f*(mode+1),0,0,false)).build());
                    if(stage==4)r.apply(SceneTransaction.builder(++revision).build(),values.stream().map(s->s.withLayer(layer)).toList(),LongBuffer.allocate(0));
                    if(stage==5)publication(r,mode+1,new NativeLightingPublication.Instance(1,23,.6f+mode*.2f,0,1,1,0,0));
                    if(stage==6)r.nativeLightingPlan(new NativeLightingPlan(mode==0?W:128,mode==0?H:96,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.INDIRECT_DIFFUSE),2,6,.35f,NativeLightingStyle.defaults(),mode==0?SUN_OFF:SUN_ON,null,true));
                    for(int repeat=0;repeat<3;repeat++){
                        var request=RtxptNativeLightingMatrixNativeSelfTest.frame(sequence,revision,camera).toBuilder().clock(FrameClock.of(sequence*50_000_000,50_000_000)).build();
                        long deadline=System.nanoTime()+10_000_000_000L;
                        while(r.trySubmit(request) instanceof Renderer.FrameSubmissionDeferred){require(System.nanoTime()<deadline,"Reuse admission recovers");Thread.sleep(1);}
                        try(var lease=r.acquireLightingFrame().orElseThrow()){require(lease.frame().sequence()==sequence&&lease.frame().sceneRevision()==revision,"Reuse publishes current frame identity");}
                        String name=label+"-"+mode+"-"+stage+"-"+repeat;
                        for(int signal=0;signal<2;signal++){
                            Path file=OUT.resolve(name+(signal==0?"-gain":"-gi")+".pfm");r.captureNativeLighting(file,signal);
                            var pixels=RtxptLightTreeNativeSelfTest.linear(file);
                            require(pixels.length==(mode==0?W*H:128*96)*3,"Reuse captures the complete lighting grid");
                            for(var value:pixels)require(Double.isFinite(value),"Finite reuse signal");
                        }
                        sequence++;
                    }
                }
                if(mode==1)require(r.lightingReconstruction().evaluatedFrames()==21,"RR evaluates every repeated and changed frame");
            }
            require(r.nativeLightingExecution().fullBeautyFrames()==0,"Reuse never invokes beauty");r.awaitIdle();
        }
        System.out.println("SIGNAL_REUSE_CAPTURE "+label+" camera=true coverage=true geometry=true sources=true attributes=true plan=true raw=true rr=true");
    }
    static void history(String label)throws Exception{
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(plan(Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.INDIRECT_DIFFUSE),2,SUN_OFF,true));
            var values=sources(0).stream().map(s->s.withComposition(SparseLightSource.Composition.VISIBLE_GROUP_MAX,(int)s.id()%2)).toList();
            r.apply(SceneTransaction.builder(1).resetScene().upsert(material(1,128,128,128)).upsert(material(2,180,120,60))
                .upsert(plane(1,1,2,0,0,0,false)).upsert(plane(2,2,4,1,0,0,false))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build(),values,LongBuffer.allocate(0));
            publication(r,1,new NativeLightingPublication.Instance(2,4,1,0,1,1,0,0));
            frame(r,0,1);capture(r,label+"-initial-gain",0);capture(r,label+"-initial-gi",1);
            frame(r,1,1);capture(r,label+"-reuse-gain",0);capture(r,label+"-reuse-gi",1);
            r.apply(SceneTransaction.builder(2).upsert(plane(2,2,4,1.5f,0,0,false)).upsert(material(2,120,180,60)).build(),
                values.stream().filter(s->s.id()%5==0).map(s->s.withAttenuation(SparseLightSource.Attenuation.MANHATTAN_LINEAR)).toList(),LongBuffer.wrap(new long[]{1,2,3}));
            frame(r,2,2);capture(r,label+"-edited-gain",0);capture(r,label+"-edited-gi",1);
            r.apply(SceneTransaction.builder(3).build(),values.stream().map(s->s.withComposition(s.composition(),31)).toList(),LongBuffer.allocate(0));
            frame(r,3,3);capture(r,label+"-single-gain",0);capture(r,label+"-single-gi",1);
            r.apply(SceneTransaction.builder(4).build(),values.stream().map(s->s.withComposition(s.composition(),(int)s.id()%4)).toList(),LongBuffer.allocate(0));
            frame(r,4,4);capture(r,label+"-growth-gain",0);capture(r,label+"-growth-gi",1);r.awaitIdle();
        }
        System.out.println("SOURCE_HISTORY_CAPTURE "+label+" reuse=true currentVisibility=true removal=true highGroup=true growth=true");
    }
}

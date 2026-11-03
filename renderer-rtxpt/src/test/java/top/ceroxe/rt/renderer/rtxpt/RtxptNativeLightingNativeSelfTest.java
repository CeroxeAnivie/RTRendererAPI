package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Independent GPU plan integration; this does not stand in for the full E01-E12 matrix. */
public final class RtxptNativeLightingNativeSelfTest {
    static final Path OUT=Path.of("build/reports/rtxpt-native-lighting").toAbsolutePath();
    static SceneTransaction scene(){return SceneTransaction.builder(1).resetScene()
        .upsert(MaterialAsset.builder(1).baseColorRgba8(0xff808080).roughness(.8f).build())
        .upsert(RtxptFramePreparationNativeSelfTest.card(1).toBuilder().lightmapCoordinates(new float[]{.5f,0,.5f,0,.5f,0,.5f,0}).build())
        .upsert(SceneInstance.builder(1,1).build()).build();}
    static RenderFrameRequest frame(long sequence,long revision){return RtxptFramePreparationNativeSelfTest.frame(sequence,revision,96,64).toBuilder()
        .environment(RtxptLightingNativeSelfTest.environment().toBuilder().sunIntensity(1).build()).build();}
    public static void main(String[] args)throws Exception {
        Files.createDirectories(OUT);
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)) {
            r.nativeLightingPlan(NativeLightingPlan.defaults(48,32));r.lightingWorkStatisticsEnabled(true);r.apply(scene());r.submit(frame(0,1));
            var direct=OUT.resolve("baseline-direct.pfm");var indirect=OUT.resolve("baseline-indirect.pfm");
            r.captureNativeLighting(direct,0);r.captureNativeLighting(indirect,1);
            var a=RtxptLightTreeNativeSelfTest.linear(direct);var b=RtxptLightTreeNativeSelfTest.linear(indirect);
            require(a.length==48*32*3&&b.length==a.length,"Native grid is independent from logical output extent");
            for(int y=12;y<20;y++)for(int x=20;x<28;x++)for(int c=0;c<3;c++)
                require(Math.abs(a[(y*48+x)*3+c]-.625)<.0005,"GPU baseline formula matches CPU reference at receiver");
            for(double value:b)require(Math.abs(value)<1e-6,"Unlit one-bounce GI is zero");
            var status=r.nativeLightingTelemetry();
            require(status[0]==1&&status[1]==48&&status[2]==32&&status[3]==3&&status[4]==1&&status[5]==0,"Independent plan, exactly two outputs, no beauty");
            var work=r.lightingWorkStatistics();require(work.available()&&work.sequence()==0&&work.sceneRevision()==1&&work.primaryRays()==48*32&&work.receivers()>0&&work.bounceRays()==work.receivers()*2,"Asynchronous work counters identify actual receiver and bounce execution");
            r.lightingWorkStatisticsEnabled(false);
            System.out.println("RTXPT_710_NATIVE_BASELINE PASS "+Arrays.toString(status));
            // Identical live inputs reproduce the result without temporal history or frozen rendering.
            r.submit(frame(1,1));r.captureNativeLighting(OUT.resolve("repeat-direct.pfm"),0);
            require(Arrays.equals(a,RtxptLightTreeNativeSelfTest.linear(OUT.resolve("repeat-direct.pfm"))),"Deterministic live native-lighting output");
            // Static sources can restore a baseline; they must not apply the dynamic boost.
            var source=SparseLightSource.point(1,0,0,2,1,1,1,16,8)
                .withComposition(SparseLightSource.Composition.VISIBLE_GROUP_CHANNEL_MAX,0);
            r.apply(SceneTransaction.builder(2).build(),List.of(source),java.nio.LongBuffer.allocate(0));r.submit(frame(2,2));
            r.captureNativeLighting(OUT.resolve("static-direct.pfm"),0);var restored=RtxptLightTreeNativeSelfTest.linear(OUT.resolve("static-direct.pfm"));
            for(int y=12;y<20;y++)for(int x=20;x<28;x++)for(int c=0;c<3;c++)
                require(Math.abs(restored[(y*48+x)*3+c]-1)<.0005,"Static source restores but never amplifies baked baseline");
            r.apply(SceneTransaction.builder(3).build(),List.of(source.withLayer(SparseLightSource.Layer.DYNAMIC)),java.nio.LongBuffer.allocate(0));r.submit(frame(3,3));
            r.captureNativeLighting(OUT.resolve("dynamic-direct.pfm"),0);var boosted=RtxptLightTreeNativeSelfTest.linear(OUT.resolve("dynamic-direct.pfm"));
            require(boosted[(16*48+24)*3]>2,"Dynamic source applies the declared additive gain");
            require(r.nativeLightingTelemetry()[5]==0,"No hidden full beauty during source changes");
            System.out.println("RTXPT_710_NATIVE_STYLE_SOURCES PASS static=1 dynamic="+boosted[(16*48+24)*3]);
            r.apply(SceneTransaction.builder(4).build(),List.of(),java.nio.LongBuffer.wrap(new long[]{1}));
            var before=r.retainedUpdateTimings();
            r.publishLightingAttributes(new NativeLightingPublication(1,List.of(),List.of(new NativeLightingPublication.MeshBaseline(1,new float[]{.25f,0,.25f,0,.25f,0,.25f,0})),null,null));
            r.submit(frame(4,4));r.captureNativeLighting(OUT.resolve("updated-baseline.pfm"),0);
            require(Math.abs(RtxptLightTreeNativeSelfTest.linear(OUT.resolve("updated-baseline.pfm"))[(16*48+24)*3]-.8125)<.0005,"Independent interpolated baseline update");
            r.publishLightingAttributes(new NativeLightingPublication(2,List.of(new NativeLightingPublication.Instance(1,23,.5f,.5f,1,1,0,0)),List.of(),null,null));
            r.submit(frame(5,4));r.captureNativeLighting(OUT.resolve("updated-visibility.pfm"),0);
            require(Math.abs(RtxptLightTreeNativeSelfTest.linear(OUT.resolve("updated-visibility.pfm"))[(16*48+24)*3]-.953125)<.0005,"Visibility and fog each applied exactly once");
            r.publishLightingAttributes(new NativeLightingPublication(3,List.of(NativeLightingPublication.Instance.shadowOnly(1)),List.of(),null,null));
            r.submit(frame(6,4));r.captureNativeLighting(OUT.resolve("shadow-only.pfm"),0);
            require(RtxptLightTreeNativeSelfTest.linear(OUT.resolve("shadow-only.pfm"))[(16*48+24)*3]==1,"Shadow-only geometry cannot become a receiver");
            var after=r.retainedUpdateTimings();require(before.builtBlases()==after.builtBlases()&&before.exportedMeshVariants()==after.exportedMeshVariants(),"Attribute-only updates never rebuild or export geometry");
            require(r.nativeLightingExecution().attributeGeneration()==3,"Actual publication generation");
            System.out.println("RTXPT_710_NATIVE_ATTRIBUTES PASS generation=3 noGeometryRebuild=true");
        }
        signalLeases();
        sourceSemantics();
        System.out.println("RTXPT_710_NATIVE_LIGHTING_CORE PASS");
    }
    static void sourceSemantics()throws Exception {
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)) {
            r.nativeLightingPlan(NativeLightingPlan.defaults(48,32));r.apply(scene());
            long revision=1,sequence=0;
            for(var composition:SparseLightSource.Composition.values())for(var attenuation:SparseLightSource.Attenuation.values()) {
                var red=SparseLightSource.point(1,0,0,2,1,0,0,6,8).withLayer(SparseLightSource.Layer.DYNAMIC).withComposition(composition,0).withAttenuation(attenuation);
                var green=SparseLightSource.point(2,0,0,2,0,1,0,6,8).withLayer(SparseLightSource.Layer.DYNAMIC).withComposition(composition,0).withAttenuation(attenuation);
                r.apply(SceneTransaction.builder(++revision).build(),List.of(red,green),java.nio.LongBuffer.allocate(0));r.submit(frame(sequence++,revision));
                var path=OUT.resolve("source-"+composition+"-"+attenuation+".pfm");r.captureNativeLighting(path,0);var pixels=RtxptLightTreeNativeSelfTest.linear(path);
                var camera=frame(0,0).camera();double x=(24.5/48*2-1)*3*camera.tanHalfFovX(),y=(1-16.5/32*2)*3*camera.tanHalfFovY();
                double distance=Math.sqrt(x*x+y*y+4);double falloff=attenuation==SparseLightSource.Attenuation.MANHATTAN_LINEAR?Math.max(0,1-(Math.abs(x)+Math.abs(y)+2)/8):Math.pow(1-distance/8,2)/Math.max(distance*distance,1);
                float contribution=(float)(6*falloff);
                for(int channel=0;channel<3;channel++) {
                    boolean present=channel<2&&(composition!=SparseLightSource.Composition.VISIBLE_GROUP_MAX||channel==1);
                    float expected=NativeLightingStyle.defaults().directGain(channel,.5f,0,1,0,present?contribution:0,present?contribution:0,0,1,0,1);
                    require(Math.abs(pixels[(16*48+24)*3+channel]-expected)<.008,"GPU source composition and attenuation match exhaustive CPU oracle: "+composition+" "+attenuation+" channel="+channel);
                }
            }
            r.apply(SceneTransaction.builder(++revision).upsert(SceneLight.point(4,0,0,2).color(0,0,1).intensity(6).range(8).build()).build(),List.of(),java.nio.LongBuffer.wrap(new long[]{1,2}));
            r.submit(frame(sequence++,revision));var path=OUT.resolve("analytic-source.pfm");r.captureNativeLighting(path,0);
            require(RtxptLightTreeNativeSelfTest.linear(path)[(16*48+24)*3+2]>2,"Ordinary analytic point light is actually evaluated in native mode");
            r.apply(SceneTransaction.builder(++revision).resetScene().build());r.submit(frame(sequence++,revision));
            path=OUT.resolve("empty-world.pfm");r.captureNativeLighting(path,0);for(var value:RtxptLightTreeNativeSelfTest.linear(path))require(value==1,"Empty world publishes neutral gain, never an old lit frame");
            try(var lease=r.acquireLightingFrame().orElseThrow()){require(lease.frame().sceneRevision()==revision,"Empty world output carries its own revision");}
            System.out.println("RTXPT_710_NATIVE_SOURCE_SEMANTICS PASS compositions=3 attenuation=2 analytic=true");
        }
    }
    static void signalLeases() {
        var held=new ArrayList<LightingFrameLease>();
        try(var renderer=RtxptRenderers.openNativeLighting(RtxptLightTreeNativeSelfTest.config(4),NativeLightingPlan.defaults(49,33))) {
            var control=renderer.extension(NativeLightingControl.class).orElseThrow();
            require(control.acquireLightingFrame().isEmpty(),"No packet before the first submit");renderer.apply(scene());
            try {
                for(int i=0;i<3;i++) {
                    renderer.submit(frame(i,1));var lease=control.acquireLightingFrame().orElseThrow();held.add(lease);var packet=lease.frame();
                    require(packet.width()==49&&packet.height()==33&&packet.planes().size()==2,"Exact odd-grid HDR packet");
                    require(packet.sequence()==i&&packet.sceneRevision()==1,"Packet frame and revision identity");
                    require(packet.generation()>0&&packet.readyValue()<packet.completionValue()&&packet.fenceHandle()!=0,"Shared-fence packet contract");
                    require(packet.planes().stream().allMatch(p->p.memoryHandle()!=0&&p.allocationBytes()>=49*33*8&&p.format()==LightingFrameLease.Format.RGBA16_FLOAT),"Independent linear HDR planes");
                    require(control.acquireLightingFrame().isEmpty(),"Single acquisition per packet");
                }
                require(renderer.trySubmit(frame(3,1)) instanceof Renderer.FrameSubmissionDeferred d&&d.deferralReason()==SubmissionDeferralReason.FRAME_RING_FULL,"Three retained packets defer without overwriting");
                // Resize while old generations are leased: only a returned slot can be replaced.
                long oldGeneration=held.get(0).frame().generation();held.remove(0).close();
                control.nativeLightingPlan(NativeLightingPlan.defaults(51,35));
                // This test cancellation queues a producer completion; wait only in the diagnostic test.
                long deadline=System.nanoTime()+5_000_000_000L;
                while(renderer.trySubmit(frame(3,1)) instanceof Renderer.FrameSubmissionDeferred) {
                    if(System.nanoTime()>deadline)throw new AssertionError("Cancelled lease did not retire");Thread.onSpinWait();
                }
                var resized=control.acquireLightingFrame().orElseThrow();held.add(resized);
                require(resized.frame().width()==51&&resized.frame().height()==35&&resized.frame().generation()!=oldGeneration,"Resized packet has a new allocation generation");
                require(held.get(0).frame().width()==49&&held.get(1).frame().width()==49,"Old leased generations remain intact across resize");
                System.out.println("RTXPT_710_NATIVE_SIGNAL_LEASES PASS slots=3 resize=49x33->51x35");
            } finally {for(var lease:held)lease.close();}
        }
    }
}

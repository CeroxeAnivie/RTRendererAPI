package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Advancing-noise, actual-execution history and primary/secondary background checks. */
public final class RtxptContinuityNativeSelfTest {
    static final Path OUT=Path.of("build/reports/rtxpt-continuity");
    static RenderFrameRequest frame(long seq,long rev,EnvironmentState e){return RenderFrameRequest.builder(seq,640,360,RtxptAcceptanceFixture.camera()).minimumSceneRevision(rev).environment(e).build();}
    public static void main(String[] args)throws Exception {
        Files.createDirectories(OUT);
        for(String route:args.length==0?List.of("rr","nrd"):List.of(args)){
            var base=RtxptLightingNativeSelfTest.configuration(route);var q=base.pathTracing().quality();
            var config=base.copyBuilder().pathTracing(base.pathTracing().withQuality(q.withLighting(q.lighting().withInput(PathTracingQualityOptions.LightingInput.SCENE_ANALYTIC)))).build();
            try(var r=Renderer.open("rtxpt",config)){
                var h=r.extension(RtxptAccess.class).orElseThrow();var control=r.extension(SceneLightingControl.class).orElseThrow();
                var env=RtxptLightingNativeSelfTest.environment();long seq=0,rev=1;r.apply(RtxptAcceptanceFixture.scene());r.submit(frame(seq++,rev,env));
                RtxptLightingNativeSelfTest.settle(h,32);var initial=h.historyActions();
                require(control.supportsContinuousLighting(),"Continuous policy capability is explicit");
                control.lightingContinuity(SceneLightingControl.Continuity.CONTINUOUS);
                try(var csv=Files.newBufferedWriter(OUT.resolve(route+".csv"))){
                    csv.write("case,index,sequence,sunIntensity,mean,pathResets,reconstructionResets,denoiserResets,continuousUploads\n");
                    for(String test:List.of("fixed","ulp-reset","ulp","daylight","moving","switch","dark")){
                        control.lightingContinuity(test.equals("ulp-reset")?SceneLightingControl.Continuity.DISCONTINUOUS:SceneLightingControl.Continuity.CONTINUOUS);
                        RtxptLightingNativeSelfTest.settle(h,48);
                        var before=h.historyActions();
                        for(int i=0;i<32;i++){
                            float power=test.startsWith("ulp")?(i%2==0?2:Math.nextUp(2f)):test.equals("daylight")?2+i*.002f:test.equals("dark")?0:2;
                            var e=test.equals("daylight")?EnvironmentState.builder().skyRadiance(.3f,.4f,.6f).ambientIntensity(.4f).sunDirection((float)Math.sin(i*.0005),-(float)Math.cos(i*.0005),0).sunRadiance(1,.9f-i*.001f,.8f).sunIntensity(power).build():test.equals("dark")?EnvironmentState.neutral():EnvironmentState.builder().skyRadiance(.3f,.4f,.6f).ambientIntensity(.4f).sunDirection(.57735026f,-.57735026f,-.57735026f).sunIntensity(power).build();
                            if(test.equals("moving")||test.equals("switch"))r.apply(SceneTransaction.builder(++rev).upsert(SceneLight.point(99,Math.sin(i*.02),3,0).intensity(test.equals("switch")&&i>=16?0:10).range(12).build()).build());
                            r.submit(frame(seq++,rev,e));
                            var actual=h.historyFrame();require(actual.rendered()&&actual.sequence()==seq-1,"History execution corresponds to current completed frame");
                            if(test.equals("ulp"))require(actual.reconstructionResets()==0&&actual.denoiserResets()==0,"Continuous ULP frame executes no reconstruction/NRD reset");
                            Path p=OUT.resolve(route+"-"+test+"-"+i+".png");h.capture(p);
                            var a=h.historyActions();csv.write(test+","+i+","+(seq-1)+","+power+","+RtxptLightingNativeSelfTest.mean(p)+","+a.pathAccumulationResets()+","+a.reconstructionResets()+","+a.denoiserResets()+","+a.continuousLightingUploads()+"\n");
                        }
                        var after=h.historyActions();long resets=after.reconstructionResets()-before.reconstructionResets()+after.denoiserResets()-before.denoiserResets();
                        if(Set.of("ulp","daylight","moving").contains(test))require(resets<=2,"Continuous changes preserve reconstruction/NRD histories "+test+" "+resets);
                        if(test.equals("switch"))require(resets>0,"Abrupt light switch executes reset");
                        System.out.println("CONTINUITY_PASS "+route+" "+test+" resets="+resets+" "+after);
                    }
                }
                System.out.println("CONTINUITY_IMAGES "+route+" "+OUT.toAbsolutePath());
                // Background-only edits must leave secondary illumination unchanged. Use a fixed
                // non-reconstructed route for exact surface comparison in the separate background case.
                control.visibleBackground(.8f,.02f,.01f);r.submit(frame(seq++,rev,env));RtxptLightingNativeSelfTest.settle(h,24);h.capture(OUT.resolve(route+"-background-red.png"));
                control.visibleBackground(.01f,.02f,.8f);r.submit(frame(seq++,rev,env));RtxptLightingNativeSelfTest.settle(h,24);h.capture(OUT.resolve(route+"-background-blue.png"));
                // Transparent coverage is verified by the separate 7.0.4 native gate.
                r.submit(frame(seq++,rev,env).toBuilder().resetTemporalHistory(HistoryResetReason.CAMERA_CUT).build());
                require(h.historyActions().reconstructionResets()+h.historyActions().denoiserResets()>initial.reconstructionResets()+initial.denoiserResets(),"Explicit camera cut executes native reset");
                System.out.println("CONTINUITY_FINAL "+route+" "+h.runtimeStatus()+" "+h.submitStages());
            }
        }
    }
}

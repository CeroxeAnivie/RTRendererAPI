package demo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rtxpt.RtxptAccess;

/** Foreground-only SDK delivery check. Counts actual frames without benchmarking a busy GPU. */
public final class RtxptHexBallGenerationSelfTest {
    private static final int WARMUP_FRAMES = 24;
    private static final int MAX_ACTIVATION_FRAMES = 120;
    private static final int DELIVERY_FRAMES = 48;
    private static long sequence;
    private static long revision;
    private static FramePrimitiveBatch previous=FramePrimitiveBatch.empty();
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output);
        System.setProperty(DemoRendererProfile.DISABLE_FRAME_GENERATION_PROPERTY,"true");
        var scene=new DemoScene(true);
        var physics=new HexPhysics();
        var config=DemoRendererProfile.rtxptInteractive();
        var csv=new StringBuilder("stage,width,height,native_frames,actual_presented,actual_generated,ratio,query_failures,status\n");
        try(var renderer=Renderer.open("rtxpt",config)) {
            revision=renderer.apply(scene.initialTransaction()).acceptedSceneRevision();
            var host=renderer.extension(RtxptAccess.class).orElseThrow();
            host.setExposureCompensation(DemoScene.GLASS_EXPOSURE_COMPENSATION);
            var controller=renderer.extension(RendererFeatureController.class).orElseThrow();
            for(int multiplier:new int[]{1,2,3,4,1}) {
                var fg=multiplier==1?FrameGenerationOptions.disabled():FrameGenerationOptions.builder()
                    .preference(RendererFeaturePreference.REQUIRED)
                    .mode(multiplier==2?FrameGenerationOptions.Mode.FRAME_GENERATION:FrameGenerationOptions.Mode.MULTI_FRAME_GENERATION)
                    .multiplier(FrameGenerationOptions.Multiplier.values()[multiplier-2]).build();
                var plan=controller.plan(controller.effectiveProfile().toBuilder().frameGeneration(fg).build());
                var applied=controller.apply(plan);
                require(applied.outcome()==RendererFeatureApplyResult.Outcome.APPLIED
                    || (sequence==0&&multiplier==1&&applied.outcome()==RendererFeatureApplyResult.Outcome.UNCHANGED),
                    "Live generation policy: "+applied);
                measure(renderer,host,scene,physics,multiplier,1920,1080,csv,output);
                if(multiplier==4) {
                    measure(renderer,host,scene,physics,multiplier,1280,720,csv,output);
                    measure(renderer,host,scene,physics,multiplier,1920,1080,csv,output);
                }
                Files.writeString(output.resolve("generation.csv"),csv);
            }
            require(host.bridgeTimings().sceneExports()==1,"No scene rebuild for generation changes or resize");
            require(renderer.health().activeFailure().isEmpty(),"Renderer healthy");
        }
        System.out.println("GENERATION_DELIVERY_AND_LIFECYCLE PASS");
    }
    private static void measure(Renderer renderer,RtxptAccess host,DemoScene scene,HexPhysics physics,
            int multiplier,int width,int height,StringBuilder csv,Path output) throws Exception {
        System.out.println("GENERATION_STAGE_START "+multiplier+"x "+width+"x"+height);
        int warmup=0;
        do {
            frame(renderer,host,scene,physics,width,height);
            warmup++;
        } while(warmup<WARMUP_FRAMES || (warmup<MAX_ACTIVATION_FRAMES
            &&renderer.diagnostics().frameGenerationEvidence().lastFramesActuallyPresented()<multiplier));
        System.out.println("GENERATION_WARMUP frames="+warmup+" "+host.runtimeStatus());
        host.awaitIdle();
        var before=renderer.diagnostics().frameGenerationEvidence();
        for(int i=0;i<DELIVERY_FRAMES;i++)frame(renderer,host,scene,physics,width,height);
        host.awaitIdle();
        var after=renderer.diagnostics().frameGenerationEvidence();
        var status=host.runtimeStatus();
        long nativeFrames=after.proxyPresentCalls()-before.proxyPresentCalls();
        long presented=after.totalFramesActuallyPresented()-before.totalFramesActuallyPresented();
        long generated=Math.max(0,presented-nativeFrames);
        long failures=after.stateQueryFailures()-before.stateQueryFailures();
        double ratio=(double)presented/nativeFrames;
        String row=String.format(Locale.ROOT,"%dx,%d,%d,%d,%d,%d,%.6f,%d,%d%n",
            multiplier,width,height,nativeFrames,presented,generated,ratio,
            failures,after.latestNativeStatus().orElse(-1));
        csv.append(row);
        Files.writeString(output.resolve("generation.csv"),csv);
        System.out.print("GENERATION_DELIVERY "+row);
        require(status.reconstruction()==RtxptAccess.Reconstruction.DLSS&&!status.denoising()&&!status.sharc()
            &&status.dlssMode()==1&&status.sceneLighting()
            &&!status.restirDi()&&!status.restirGi(),"Default SR Performance remains active while generation settings change");
        require(host.pathQualityStatus().allocatedCacheEntries()==0,"Disabled SHaRC stays unallocated");
        require(status.frameGeneration()==(multiplier>1),"Actual FG activation");
        require(nativeFrames==DELIVERY_FRAMES,"Native presentation count");
        require(failures==0&&after.latestQuerySucceeded(),"SDK query succeeded");
        require(after.latestNativeStatus().orElse(-1)==0,"SDK generation status");
        require(Math.abs(ratio-multiplier)<.15,"Actual delivery multiplier: "+ratio+", expected "+multiplier);
        System.out.println("GENERATION_STAGE PASS "+status);
    }
    private static void frame(Renderer renderer,RtxptAccess host,DemoScene scene,HexPhysics physics,int width,int height) {
        for(int step=0;step<4;step++)physics.stepForTesting();
        var batch=scene.primitiveBatch(physics,previous);
        long before=host.bridgeTimings().frames();
        renderer.submit(DemoRendererProfile.frame(sequence++,width,height,DemoRendererProfile.camera(width,height,15.4))
            .minimumSceneRevision(revision).primitiveBatch(batch).environment(DemoRendererProfile.environment())
            .antiAliasing(DemoRendererProfile.antiAliasing(1)).build());
        previous=batch;
        long deadline=System.nanoTime()+90_000_000_000L;
        while(host.bridgeTimings().frames()==before&&System.nanoTime()<deadline)
            require(host.renderFrame(),"Generation window stays open");
        require(host.bridgeTimings().frames()==before+1,"One native frame per delivery input");
    }
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}

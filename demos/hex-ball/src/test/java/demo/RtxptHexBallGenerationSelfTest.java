package demo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rtxpt.RtxptAccess;

/** Measures SDK delivery counters, never infers generated frames from requested settings. */
public final class RtxptHexBallGenerationSelfTest {
    private static long sequence;
    private static long revision;
    private static FramePrimitiveBatch previous=FramePrimitiveBatch.empty();
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output);
        System.setProperty(DemoRendererProfile.DISABLE_FRAME_GENERATION_PROPERTY,"true");
        var scene=new DemoScene(false);
        var physics=new HexPhysics();
        var config=DemoRendererProfile.rtxptInteractive();
        var csv=new StringBuilder("stage,width,height,native_frames,actual_presented,actual_generated,ratio,wall_ms,native_fps,actual_presented_fps,query_failures,status\n");
        try(var renderer=Renderer.open("rtxpt",config)) {
            revision=renderer.apply(scene.initialTransaction()).acceptedSceneRevision();
            var host=renderer.extension(RtxptAccess.class).orElseThrow();
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
                measure(renderer,host,scene,physics,multiplier,2560,1440,csv,output);
                if(multiplier==4) {
                    measure(renderer,host,scene,physics,multiplier,1920,1080,csv,output);
                    measure(renderer,host,scene,physics,multiplier,2560,1440,csv,output);
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
        for(int i=0;i<120;i++)frame(renderer,scene,physics,width,height);
        host.awaitIdle();
        var before=renderer.diagnostics().frameGenerationEvidence();
        long begin=System.nanoTime();
        for(int i=0;i<360;i++)frame(renderer,scene,physics,width,height);
        host.awaitIdle();
        double ms=(System.nanoTime()-begin)/1e6;
        var after=renderer.diagnostics().frameGenerationEvidence();
        var status=host.runtimeStatus();
        long nativeFrames=after.proxyPresentCalls()-before.proxyPresentCalls();
        long presented=after.totalFramesActuallyPresented()-before.totalFramesActuallyPresented();
        long generated=Math.max(0,presented-nativeFrames);
        long failures=after.stateQueryFailures()-before.stateQueryFailures();
        double ratio=(double)presented/nativeFrames;
        String row=String.format(Locale.ROOT,"%dx,%d,%d,%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%d,%d%n",
            multiplier,width,height,nativeFrames,presented,generated,ratio,ms,nativeFrames*1000/ms,presented*1000/ms,
            failures,after.latestNativeStatus().orElse(-1));
        csv.append(row);
        Files.writeString(output.resolve("generation.csv"),csv);
        System.out.print("GENERATION_DELIVERY "+row);
        require(status.reconstruction()==RtxptAccess.Reconstruction.DLSS&&!status.denoising()&&!status.sharc()
            &&!status.restirDi()&&!status.restirGi(),"Unchanged raw SR rendering policy");
        require(status.frameGeneration()==(multiplier>1),"Actual FG activation");
        require(nativeFrames==360,"Native presentation count");
        require(failures==0&&after.latestQuerySucceeded(),"SDK query succeeded");
        require(after.latestNativeStatus().orElse(-1)==0,"SDK generation status");
        require(Math.abs(ratio-multiplier)<.15,"Actual delivery multiplier: "+ratio+", expected "+multiplier);
        System.out.println("GENERATION_STAGE PASS "+status);
    }
    private static void frame(Renderer renderer,DemoScene scene,HexPhysics physics,int width,int height) {
        for(int step=0;step<4;step++)physics.stepForTesting();
        var batch=scene.primitiveBatch(physics,previous);
        renderer.submit(DemoRendererProfile.frame(sequence++,width,height,DemoRendererProfile.camera(width,height,15.4))
            .minimumSceneRevision(revision).primitiveBatch(batch).environment(DemoRendererProfile.environment())
            .antiAliasing(DemoRendererProfile.antiAliasing(1)).build());
        previous=batch;
    }
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}

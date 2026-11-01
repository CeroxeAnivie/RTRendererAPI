package demo;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rtxpt.RtxptAccess;

/** Measures presented frames for the glass HexBall scene with RTXPT's native SR path. */
public final class RtxptHexBallBenchmark {
    private RtxptHexBallBenchmark() { }

    public static void main(String[] args) throws Exception {
        int width=args.length>0?Integer.parseInt(args[0]):1920;
        int height=args.length>1?Integer.parseInt(args[1]):1080;
        int seconds=args.length>2?Integer.parseInt(args[2]):8;
        if(width<1||height<1||seconds<1)throw new IllegalArgumentException("Invalid benchmark dimensions or duration");
        System.setProperty(DemoRendererProfile.DISABLE_FRAME_GENERATION_PROPERTY,"true");
        var config=DemoRendererProfile.rtxptInteractive();
        var scene=new DemoScene(true);
        var physics=new HexPhysics();
        var batch=scene.primitiveBatch(physics,FramePrimitiveBatch.empty());
        var camera=DemoRendererProfile.camera(width,height,15.4);
        try(Renderer renderer=Renderer.open("rtxpt",config)) {
            long revision=renderer.apply(scene.initialTransaction()).acceptedSceneRevision();
            var host=renderer.extension(RtxptAccess.class).orElseThrow();
            host.setExposureCompensation(DemoScene.GLASS_EXPOSURE_COMPENSATION);
            renderer.submit(DemoRendererProfile.frame(0,width,height,camera).minimumSceneRevision(revision)
                .primitiveBatch(batch).environment(DemoRendererProfile.environment())
                .antiAliasing(DemoRendererProfile.antiAliasing(1)).build());
            for(int i=0;i<120;i++)if(!host.renderFrame())throw new IllegalStateException("Window closed during warmup");
            host.awaitIdle();
            var actual=host.runtimeStatus();
            if(actual.reconstruction()!=RtxptAccess.Reconstruction.DLSS || actual.denoising()
                || actual.restirDi() || actual.restirGi() || actual.sharc() || actual.frameGeneration())
                throw new IllegalStateException("Unexpected benchmark feature state: "+actual);
            System.out.println("RTXPT_HEXBALL_ACTUAL "+actual);
            for(int round=1;round<=3;round++) {
                long first=host.bridgeTimings().frames();
                long start=System.nanoTime(),deadline=start+Duration.ofSeconds(seconds).toNanos();
                long calls=0;
                do {
                    if(!host.renderFrame())throw new IllegalStateException("Window closed during benchmark");
                    calls++;
                    if(calls>seconds*2000L)throw new IllegalStateException("Unexpected render loop rate");
                } while(System.nanoTime()<deadline);
                host.awaitIdle();
                long finish=System.nanoTime(),frames=host.bridgeTimings().frames()-first;
                double elapsed=(finish-start)/1e9;
                if(frames==0)throw new IllegalStateException("No native presentations counted");
                System.out.printf(Locale.ROOT,
                    "RTXPT_HEXBALL round=%d output=%dx%d input=%dx%d spp=%d SR=DLSS FG=off frames=%d "
                        +"elapsedWithGPUDrain=%.3fs fps=%.2f wallMsPerFrame=%.3f renderFrameCalls=%d%n",
                    round,width,height,actual.renderWidth(),actual.renderHeight(),config.pathTracing().samplesPerPixel(),
                    frames,elapsed,frames/elapsed,elapsed*1000/frames,calls);
            }
            if(renderer.health().status()!=Renderer.Status.READY||renderer.health().activeFailure().isPresent())
                throw new AssertionError("Renderer unhealthy after benchmark: "+renderer.health());
        }
    }
}

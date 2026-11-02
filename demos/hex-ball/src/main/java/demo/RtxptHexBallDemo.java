package demo;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;
import top.ceroxe.rt.renderer.api.FramePrimitiveBatch;
import top.ceroxe.rt.renderer.api.Renderer;
import top.ceroxe.rt.renderer.rtxpt.RtxptAccess;

/** Original hex-ball assets and physics, presented directly by the resident upstream RTXPT. */
public final class RtxptHexBallDemo {
    private RtxptHexBallDemo() { }

    public static void main(String[] args) {
        Path capture=Arrays.stream(args).filter(a->a.startsWith("--capture="))
            .map(a->Path.of(a.substring("--capture=".length()))).findFirst().orElse(null);
        boolean originalBalls=Arrays.asList(args).contains("--original-balls");
        boolean originalSettings=Arrays.asList(args).contains("--original-settings");
        var demoArgs=DemoApiControls.parse(Arrays.stream(args).filter(a->!a.startsWith("--capture=")&&!a.equals("--original-balls")&&!a.equals("--original-settings")).toArray(String[]::new));
        var config=DemoConfig.parse(demoArgs);
        if(config.windowed() && Arrays.stream(demoArgs).noneMatch(a->a.startsWith("--width="))) {
            config=new DemoConfig(Math.min(1920,Math.max(1,config.width()-160)),Math.min(1080,Math.max(1,config.height()-160)),
                config.samplesPerPixel(),true,config.benchmark(),config.cpuPresentation(),config.targetFps(),config.frameLimit(),config.duration());
        }
        if(config.samplesPerPixel()!=1)throw new IllegalArgumentException("This RTXPT comparison uses 1 SPP");
        var scene=new DemoScene(!originalBalls);
        var camera=DemoRendererProfile.camera(config.width(),config.height(),15.4);
        var environment=DemoRendererProfile.environment();
        var stats=new RenderStats();
        var policy=originalSettings?DemoRendererProfile.interactive(config):DemoRendererProfile.rtxptInteractive();
        try(var renderer=Renderer.open("rtxpt",policy)) {
            var host=renderer.extension(RtxptAccess.class).orElseThrow();
            if(!originalBalls)host.setExposureCompensation(DemoScene.GLASS_EXPOSURE_COMPENSATION);
            long revision=renderer.apply(scene.initialTransaction()).acceptedSceneRevision();
            var physics=new HexPhysics();
            FramePrimitiveBatch previous=FramePrimitiveBatch.empty();
            long start=0,report=0,reportFrames=0,sequence=0,hudUpdate=0;
            while(true) {
                long now=System.nanoTime();
                if(start!=0 && config.targetFps()!=0) {
                    long deadline=start+(long)(sequence*1_000_000_000d/config.targetFps());
                    if(now<deadline){LockSupport.parkNanos(deadline-now);now=System.nanoTime();}
                }
                physics.advanceTo(now);
                var batch=scene.primitiveBatch(physics,previous);
                renderer.submit(DemoRendererProfile.frame(sequence,config.width(),config.height(),camera)
                    .minimumSceneRevision(revision).primitiveBatch(batch).environment(environment)
                    .antiAliasing(DemoRendererProfile.antiAliasing(1)).build());
                previous=batch;sequence++;
                stats.submittedFrames.incrementAndGet();stats.framePresented();
                if(start==0) {
                    host.setWindowTitle("RTRendererAPI "+DemoBuildInfo.version()+" - RTXPT Hex Ball");
                    start=report=System.nanoTime();reportFrames=host.bridgeTimings().frames();
                    System.out.println("RTXPT HEX BALL READY: same DemoScene / HexPhysics / camera / environment; output="
                        +config.width()+"x"+config.height()+", 1 SPP; "+host.runtimeStatus());
                }
                now=System.nanoTime();
                if(now-hudUpdate>=500_000_000L) {
                    var actual=host.runtimeStatus();var display=host.presentationStatus();
                    var displayedConfig=new DemoConfig(display.outputWidth(),display.outputHeight(),1,true,false,false,0,0,java.time.Duration.ZERO);
                    stats.observeGpuTiming(renderer.diagnostics().frameGpuTiming());
                    String text=DemoTechnologyHud.capture(renderer,displayedConfig,stats).text()
                        +String.format(Locale.ROOT,"%nBACKEND RTXPT 1.9.0 | D3D12%nINPUT %dx%d -> OUTPUT %dx%d%nSHaRC %s | ReSTIR DI/GI %s/%s%nACES | exposure offset %.2f EV | bloom %s%nGLASS | F0 20%% | IOR %.3f",
                            actual.renderWidth(),actual.renderHeight(),display.outputWidth(),display.outputHeight(),
                            actual.sharc()?"ACTIVE":"DISABLED",actual.restirDi()?"ACTIVE":"DISABLED",actual.restirGi()?"ACTIVE":"DISABLED",
                            display.exposureCompensation(),display.bloom()?"ON":"OFF",DemoScene.BALL_IOR);
                    if(originalBalls)text=text.substring(0,text.lastIndexOf("GLASS |"))+"ORIGINAL METAL BALLS";
                    host.setOverlayText(text);hudUpdate=now;
                    if(sequence==1)System.out.println("RTXPT actual presentation: "+display);
                }
                if(now-report>=1_000_000_000L) {
                    long frames=host.bridgeTimings().frames();double seconds=(now-report)/1e9;
                    System.out.printf(Locale.ROOT,"RTXPT hex-ball: %.2f native FPS, %.3f ms/frame; exports=%d, transformBatches=%d%n",
                        (frames-reportFrames)/seconds,seconds*1000/(frames-reportFrames),host.bridgeTimings().sceneExports(),host.bridgeTimings().transformBatches());
                    report=now;reportFrames=frames;
                }
                if(capture!=null && sequence==120){
                    System.out.println("RTXPT acceptance actual settings: "+host.runtimeStatus());
                    System.out.println("RTXPT acceptance FG delivery: "+renderer.diagnostics().frameGenerationEvidence());
                    host.capture(capture);System.out.println("RTXPT hex-ball capture: "+capture);
                }
                if(config.frameLimit()>0 && sequence>=config.frameLimit())break;
                if(!config.duration().isZero() && now-start>=config.duration().toNanos())break;
            }
            host.awaitIdle();
            System.out.printf(Locale.ROOT,"RTXPT 30s comparison: seconds=%.6f, presented=%d, meanNativeFPS=%.6f, meanOutputFPS=%.6f, final=%s, bridge=%s%n",
                (System.nanoTime()-start)/1e9,stats.presentedFrames.get(),stats.totalPresentationFramesPerSecond(),
                DemoTechnologyHud.presentTotalFps(renderer.diagnostics().frameGenerationEvidence(),stats.totalPresentationFramesPerSecond()),
                renderer.diagnostics().frameGenerationEvidence(),host.bridgeTimings());
        } catch(IllegalStateException failure) {
            // Closing the GLFW window is normal user control, not a renderer failure.
            if(!"RTXPT window closed".equals(failure.getMessage()))throw failure;
        }
    }
}

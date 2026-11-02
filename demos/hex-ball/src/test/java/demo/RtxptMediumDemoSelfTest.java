package demo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;
import top.ceroxe.rt.renderer.api.Renderer;
import top.ceroxe.rt.renderer.rtxpt.RtxptAccess;
import top.ceroxe.rt.renderer.rtxpt.RtxptComposedRenderer;

/** Packaged demo HUD, supported scene transitions and real SDK FG delivery. */
public final class RtxptMediumDemoSelfTest {
    private RtxptMediumDemoSelfTest() { }

    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output);
        var csv=new StringBuilder("scene,input_width,input_height,output_width,output_height,native_frames,presented_frames,generated_frames,native_ms,native_fps,output_fps,query_failures\n");
        try(var renderer=Renderer.open("rtxpt",DemoRendererProfile.rtxptMediumInteractive(false))) {
            renderer.apply(RtxptMediumScene.create(1,"mixed"));
            renderer.submit(RtxptMediumDemo.frameRequest(0,1,1920,1080));
            require(renderer.extension(RtxptComposedRenderer.class).isEmpty(),"Default medium demo never opens a composition host");
            var host=renderer.extension(RtxptAccess.class).orElseThrow();
            long revision=1,sequence=1;
            for(String scene:RtxptMediumScene.PRESETS) {
                var stats=new RenderStats();
                if(sequence>1) {
                    renderer.apply(RtxptMediumScene.create(++revision,scene));
                    RtxptMediumDemo.submit(renderer,sequence++,revision,1920,1080);
                } else sequence++;
                host.setOverlayChoices(RtxptMediumScene.LABELS,RtxptMediumScene.PRESETS.indexOf(scene));
                require(host.overlaySelection()==RtxptMediumScene.PRESETS.indexOf(scene),"Native scene selector state");
                var keys=new DemoWindowKeys(host.windowHandle());
                keys.pollCommand();
                for(int i=0;i<180;i++) {
                    frame(host,stats);
                    if(i%60==0)host.setOverlayText(RtxptMediumDemo.overlay(renderer,host,stats,scene));
                }
                host.awaitIdle();
                var before=renderer.diagnostics().frameGenerationEvidence();
                long start=System.nanoTime(),first=host.bridgeTimings().frames();
                while(host.bridgeTimings().frames()-first<360) {
                    frame(host,stats);
                    if(host.bridgeTimings().frames()%90==0)host.setOverlayText(RtxptMediumDemo.overlay(renderer,host,stats,scene));
                }
                host.awaitIdle();
                double elapsedMs=(System.nanoTime()-start)/1e6;
                var after=renderer.diagnostics().frameGenerationEvidence();
                var actual=host.runtimeStatus();
                var display=host.presentationStatus();
                long nativeFrames=after.proxyPresentCalls()-before.proxyPresentCalls();
                long presented=after.totalFramesActuallyPresented()-before.totalFramesActuallyPresented();
                long failures=after.stateQueryFailures()-before.stateQueryFailures();
                require(nativeFrames==360,"360 actual native presents");
                require(actual.frameGeneration()&&Math.abs((double)presented/nativeFrames-2)<.05,
                    "Actual 2x FG delivery: native="+nativeFrames+", delivered="+presented+", status="+actual+", evidence="+after);
                require(actual.reconstruction()==RtxptAccess.Reconstruction.DLSS_RR&&actual.sharc(),"Recommended RR + SHaRC active");
                require(failures==0&&after.latestQuerySucceeded()&&after.latestNativeStatus().orElse(-1)==0,"SDK success");
                String hud=RtxptMediumDemo.overlay(renderer,host,stats,scene);
                require(hud.contains("RTRendererAPI 7.0.0")&&hud.contains("DLSS FG: ACTIVE")
                    &&hud.contains("BACKEND RTXPT ONLY")&&hud.contains("SCENE "+scene.toUpperCase(Locale.ROOT))&&hud.contains("[1] Mixed"),"Full active HUD");
                host.setOverlayText(hud);
                host.setOverlayChoices(RtxptMediumScene.LABELS,RtxptMediumScene.PRESETS.indexOf(scene));
                frame(host,stats);
                String name=revision+"-"+scene;
                host.capture(output.resolve(name+".png"));
                Files.writeString(output.resolve(name+"-hud.txt"),hud);
                var image=ImageIO.read(output.resolve(name+".png").toFile());
                RtxptCompositionSelfTest.requireVisibleHud(output.resolve(name+".png"));
                require(image!=null&&image.getWidth()==display.outputWidth()&&image.getHeight()==display.outputHeight(),"Capture matches actual window extent");
                int low=255,high=0;
                for(int y=image.getHeight()/2;y<image.getHeight()*9/10;y+=7)
                    for(int x=image.getWidth()/6;x<image.getWidth()*9/10;x+=7) {
                    int c=image.getRGB(x,y),l=(((c>>16)&255)+((c>>8)&255)+(c&255))/3;
                    low=Math.min(low,l);high=Math.max(high,l);
                }
                require(high-low>40,"Rendered scene has visible content outside HUD");
                csv.append(String.format(Locale.ROOT,"%s,%d,%d,%d,%d,%d,%d,%d,%.6f,%.6f,%.6f,%d%n",
                    name,actual.renderWidth(),actual.renderHeight(),display.outputWidth(),display.outputHeight(),
                    nativeFrames,presented,presented-nativeFrames,elapsedMs/nativeFrames,nativeFrames*1000/elapsedMs,presented*1000/elapsedMs,failures));
                Files.writeString(output.resolve("acceptance.csv"),csv);
                System.out.println("MEDIUM_SCENE PASS "+name+" "+actual);
            }
            require(host.bridgeTimings().sceneExports()==6,"Exactly one scene export per switch");
            host.setOverlayChoices(java.util.List.of(),-1);
            require(host.overlaySelection()==-1,"Selector can be hidden");
            require(renderer.health().activeFailure().isEmpty(),"Healthy lifecycle");
        }
        System.out.println("MEDIUM_DEMO_HUD_FG_AND_SCENES PASS");
    }
    private static void frame(RtxptAccess host,RenderStats stats) {
        long before=host.bridgeTimings().frames();
        require(host.renderFrame(),"Window stays open");
        RtxptMediumDemo.recordPresented(host,stats,before);
    }
    private static void require(boolean value,String message) { if(!value)throw new AssertionError(message); }
}

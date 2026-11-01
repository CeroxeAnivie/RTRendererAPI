package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** JAR side of native/JNI parity; shares exported assets with the native control. */
public final class RtxptParityBenchmark {
    public static void main(String[] args) throws Exception {
        var directory=Path.of(args[1]).toAbsolutePath();
        var frame=RtxptAcceptanceFixture.frame();
        var config=RendererConfig.pathTracedPresentation();
        if(args[0].equals("prepare")) {
            var scene=new RtxptScene();scene.retain(RtxptAcceptanceFixture.scene());
            var path=scene.export(directory,frame,scene.items(frame));
            try(var renderer=Renderer.open("rtxpt",config)) {
                renderer.apply(RtxptAcceptanceFixture.scene());renderer.submit(frame);
                var host=renderer.extension(RtxptAccess.class).orElseThrow();
                for(int i=0;i<32;i++)require(host.renderFrame(),"Fixture warmup");
                ((RtxptRenderer)renderer).snapshotMaterials(directory.resolve("Materials"));
                host.capture(directory.resolve("fixture-jar.png"));
            }
            var nativeArgs=new ArrayList<String>(List.of("--scene",path.toString(),"--nonInteractive","--width","1920","--height","1080","--overrideToRealtimeMode"));
            nativeArgs.addAll(Arrays.asList(RtxptOptions.resolve(RtxptOptions.profile(config)).arguments()));
            nativeArgs.addAll(List.of("--cameraPosDirUp",RtxptAcceptanceFixture.cameraPose()));
            Files.writeString(directory.resolve("native-arguments.json"),RtxptJson.encode(nativeArgs));
            return;
        }
        try(var renderer=Renderer.open("rtxpt",config)) {
            var host=renderer.extension(RtxptAccess.class).orElseThrow();
            host.loadNativeScene(directory.resolve("managed.scene.json"));
            renderer.submit(RenderFrameRequest.builder(0,1920,1080,RtxptAcceptanceFixture.camera()).build());
            for(int i=0;i<180;i++)require(host.renderFrame(),"JAR warmup frame");
            host.awaitIdle();long first=host.bridgeTimings().frames();
            long begin=System.nanoTime();
            do { require(host.renderFrame(),"JAR benchmark frame"); }while(System.nanoTime()-begin<4_000_000_000L);
            host.awaitIdle();double seconds=(System.nanoTime()-begin)/1e9;
            long frames=host.bridgeTimings().frames()-first;var status=host.runtimeStatus();
            require(status.reconstruction()==RtxptAccess.Reconstruction.DLSS_RR&&!status.frameGeneration(),"Benchmark settings: "+status);
            System.out.printf(Locale.ROOT,"RTXPT_PARITY jar frames=%d seconds=%.9f fps=%.6f ms=%.9f input=%dx%d aa=%d sharc=%d fg=0%n",
                frames,seconds,frames/seconds,seconds*1000/frames,status.renderWidth(),status.renderHeight(),status.reconstruction().ordinal(),status.sharc()?1:0);
        }
    }
}

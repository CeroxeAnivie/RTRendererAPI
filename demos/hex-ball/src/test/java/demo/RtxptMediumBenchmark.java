package demo;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rtxpt.*;

/** End-to-end resident native presentation. Frame generation stays off. */
public final class RtxptMediumBenchmark {
    public static void main(String[] args) throws Exception {
        boolean raw=Arrays.asList(args).contains("--native");
        var config=raw?RendererConfig.builder().cpuFrameReadbackEnabled(false).build():RendererConfig.pathTracedPresentation();
        try(Renderer r=Renderer.open("rtxpt",config)) {
            var host=r.extension(RtxptAccess.class).orElseThrow();
            r.apply(RtxptMediumScene.create(1,"mixed"));r.submit(RenderFrameRequest.builder(0,1920,1080,RtxptMediumScene.camera(1920,1080)).minimumSceneRevision(1).environment(RtxptMediumScene.environment()).build());
            for(int i=0;i<120;i++)if(!host.renderFrame())throw new IllegalStateException("Window closed during warmup");
            host.awaitIdle();
            long firstPresent=host.bridgeTimings().frames();
            long start=System.nanoTime(),deadline=start+5_000_000_000L;var callTimes=new ArrayList<Double>();
            do {long begin=System.nanoTime();if(!host.renderFrame())throw new IllegalStateException("Window closed during benchmark");callTimes.add((System.nanoTime()-begin)/1e6);}while(System.nanoTime()<deadline);
            host.awaitIdle();
            long finish=System.nanoTime(),frames=host.bridgeTimings().frames()-firstPresent;
            if(frames==0)throw new IllegalStateException("No native presentations were counted");
            double elapsed=(finish-start)/1e9;callTimes.sort(Comparator.naturalOrder());
            var actual=host.runtimeStatus();
            if(actual.reconstruction()!=(raw?RtxptAccess.Reconstruction.NONE:RtxptAccess.Reconstruction.DLSS_RR) || actual.frameGeneration())
                throw new IllegalStateException("Benchmark mode was not applied: "+actual);
            if(raw && (actual.renderWidth()!=1920 || actual.renderHeight()!=1080))
                throw new IllegalStateException("Native benchmark must render at output resolution: "+actual);
            System.out.printf(Locale.ROOT,"RTXPT JNI mixed: output=1920x1080, internal=%dx%d, spp=%d, reconstruction=%s, requestedDLSSQuality=%s, standaloneDenoiser=%s, ReSTIR(DI/GI)=%s/%s, SHaRC=%s, FG=%s, elapsedWithGPUDrain=%.3fs, presents=%d, fps=%.2f, wallMsPerPresentedFrame=%.3f, renderFrameCallP50=%.3fms, renderFrameCallP95=%.3fms%n",
                actual.renderWidth(),actual.renderHeight(),config.pathTracing().samplesPerPixel(),actual.reconstruction(),config.frameReconstruction().quality(),actual.standaloneDenoiser(),actual.restirDi(),actual.restirGi(),actual.sharc(),actual.frameGeneration(),elapsed,frames,frames/elapsed,elapsed*1000/frames,callTimes.get(callTimes.size()/2),callTimes.get(Math.min(callTimes.size()-1,(int)(callTimes.size()*.95))));
            System.out.println("Bridge: "+host.bridgeTimings());
            for(String argument:args)if(argument.startsWith("--capture="))host.capture(java.nio.file.Path.of(argument.substring("--capture=".length())));
        }
    }
}

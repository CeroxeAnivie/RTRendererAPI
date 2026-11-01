package demo;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rtxpt.*;

/** End-to-end resident native presentation. Frame generation stays off. */
public final class RtxptMediumBenchmark {
    public static void main(String[] args) throws Exception {
        boolean raw=Arrays.asList(args).contains("--native");
        try(Renderer r=Renderer.open("rtxpt",RendererConfig.builder().cpuFrameReadbackEnabled(false).build())) {
            var host=r.extension(RtxptAccess.class).orElseThrow();host.configure(raw?RtxptOptions.nativeOneSample():RtxptOptions.recommended());
            r.apply(RtxptMediumScene.create(1,"mixed"));r.submit(RenderFrameRequest.builder(0,1920,1080,RtxptMediumScene.camera(1920,1080)).minimumSceneRevision(1).environment(RtxptMediumScene.environment()).build());
            for(int i=0;i<120;i++)if(!host.renderFrame())throw new IllegalStateException("Window closed during warmup");
            long start=System.nanoTime(),end=start+5_000_000_000L;int frames=0;var times=new ArrayList<Double>();
            do {long begin=System.nanoTime();if(!host.renderFrame())throw new IllegalStateException("Window closed during benchmark");times.add((System.nanoTime()-begin)/1e6);frames++;}while(System.nanoTime()<end);
            double elapsed=(System.nanoTime()-start)/1e9;times.sort(Comparator.naturalOrder());
            System.out.printf(Locale.ROOT,"RTXPT JNI mixed: output=1920x1080, spp=1, FG=off, mode=%s, frames=%d, fps=%.2f, mean=%.3f ms, p50=%.3f ms, p95=%.3f ms%n",raw?"native":"DLSS-RR Balanced",frames,frames/elapsed,elapsed*1000/frames,times.get(times.size()/2),times.get((int)(times.size()*.95)));
            System.out.println("Bridge: "+host.bridgeTimings());
        }
    }
}

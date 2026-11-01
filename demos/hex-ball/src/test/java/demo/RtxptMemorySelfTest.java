package demo;

import java.lang.management.ManagementFactory;
import java.lang.management.BufferPoolMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rtxpt.RtxptAccess;
import top.ceroxe.rt.renderer.rtxpt.RtxptComposedRenderer;
import top.ceroxe.rt.renderer.rt.diagnostics.NativeMemoryDiagnostics;

/** Diagnostic lifecycle run, deliberately separate from throughput acceptance. */
public final class RtxptMemorySelfTest {
    private RtxptMemorySelfTest() { }
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]).toAbsolutePath();Files.createDirectories(output);
        var csv=new StringBuilder("epoch_ms,phase,private_commit_bytes,heap_used_bytes,heap_committed_bytes,nonheap_committed_bytes,direct_bytes,frames\n");
        sample(output,csv,"before-open",0);
        var config=RendererConfig.pathTracedPresentation().copyBuilder().frameGeneration(FrameGenerationOptions.disabled()).build();
        try(var renderer=RtxptComposedRenderer.openRtxptPresentation(
                RendererConfig.builder().cpuFrameReadbackEnabled(false).build(),config,"RTRendererAPI - Memory validation")) {
            sample(output,csv,"devices-open",0);
            renderer.apply(RtxptMediumScene.create(1,"mixed"));
            renderer.submit(RtxptMediumDemo.frameRequest(0,1,1920,1080));
            var host=renderer.extension(RtxptAccess.class).orElseThrow();
            advance(host,120);sample(output,csv,"startup-warm",host.bridgeTimings().frames());
            var layer=RtxptCompositionSelfTest.createOverlay(renderer);
            sample(output,csv,"legacy-first-use",host.bridgeTimings().frames());
            long revision=1,sequence=1;
            for(int cycle=0;cycle<3;cycle++) {
                renderer.overlays(List.of(layer));advance(host,90);host.awaitIdle();
                sample(output,csv,"cycle-"+cycle+"-overlay-on",host.bridgeTimings().frames());
                for(String scene:RtxptMediumScene.PRESETS) {
                    renderer.apply(RtxptMediumScene.create(++revision,scene));
                    renderer.submit(RtxptMediumDemo.frameRequest(sequence++,revision,1920,1080));
                    advance(host,60);host.awaitIdle();
                    sample(output,csv,"cycle-"+cycle+"-scene-"+scene,host.bridgeTimings().frames());
                }
                renderer.overlays(List.of());advance(host,90);host.awaitIdle();
                sample(output,csv,"cycle-"+cycle+"-overlay-off",host.bridgeTimings().frames());
                renderer.submit(RtxptMediumDemo.frameRequest(sequence++,revision,960,540));advance(host,12);
                renderer.submit(RtxptMediumDemo.frameRequest(sequence++,revision,1920,1080));advance(host,60);host.awaitIdle();
                // Explicit full GC belongs only to this memory diagnostic, never FPS acceptance.
                System.gc();Thread.sleep(250);
                sample(output,csv,"cycle-"+cycle+"-post-gc",host.bridgeTimings().frames());
                if(renderer.workSnapshot().rtxptFrames()!=host.bridgeTimings().frames())
                    throw new AssertionError("Work count must include direct resident renderFrame");
            }
        }
        System.gc();Thread.sleep(500);sample(output,csv,"after-close-gc",0);
        System.out.println("RTXPT_MEMORY_LIFECYCLE PASS; diagnostic run, not performance acceptance");
    }
    private static void advance(RtxptAccess host,int frames) {
        for(int i=0;i<frames;i++)if(!host.renderFrame())throw new AssertionError("Memory validation window closed");
    }
    private static void sample(Path output,StringBuilder csv,String phase,long frames) throws Exception {
        NativeMemoryDiagnostics.checkpoint(phase,frames,0);
        var os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
        var memory=ManagementFactory.getMemoryMXBean();var heap=memory.getHeapMemoryUsage();
        long direct=ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
            .filter(pool->pool.getName().equals("direct")).mapToLong(BufferPoolMXBean::getMemoryUsed).sum();
        String row=String.format(Locale.ROOT,"%d,%s,%d,%d,%d,%d,%d,%d%n",System.currentTimeMillis(),phase,
            os.getCommittedVirtualMemorySize(),heap.getUsed(),heap.getCommitted(),memory.getNonHeapMemoryUsage().getCommitted(),direct,frames);
        csv.append(row);Files.writeString(output.resolve("phases.csv"),csv);System.out.print("MEMORY_PHASE "+row);
    }
}

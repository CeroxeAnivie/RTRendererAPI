package demo;

import java.nio.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.luxcore.LuxCoreAccess;

/** Full-resolution native-film throughput. Refreshes are never counted as rendered frames. */
public final class LuxCoreMediumBenchmark {
    public static void main(String[] args) throws Exception {
        Map<String,String> options=new HashMap<>();for(String a:args){var p=a.replaceFirst("^--","").split("=",2);if(p.length==2)options.put(p[0],p[1]);}
        String selected=options.getOrDefault("scene","micro");
        var workload=selected.equals("focused")?MediumPerformanceCases.focused():MediumPerformanceCases.micro();
        int width=Integer.parseInt(options.getOrDefault("width","1920")),height=Integer.parseInt(options.getOrDefault("height","1080"));
        int rounds=Integer.parseInt(options.getOrDefault("rounds","3"));double seconds=Double.parseDouble(options.getOrDefault("seconds","3"));
        Path output=Path.of(options.getOrDefault("output","build/reports/luxcore-medium/"+selected));Files.createDirectories(output);
        var fixture=NativeMediumScenes.scene(workload.transaction());
        var frame=NativeMediumScenes.frame(workload.frame(0,width,height));
        long start=System.nanoTime();
        try(var renderer=Renderer.open("luxcore",RendererConfig.recommended())) {
            var access=renderer.extension(LuxCoreAccess.class).orElseThrow();long opened=System.nanoTime();
            renderer.apply(fixture);long uploaded=System.nanoTime();renderer.submit(frame);long started=System.nanoTime();
            waitSamples(access,0,30);LockSupport.parkNanos(1_000_000_000L);
            System.out.println("LuxCorePerf native-semantics scene="+selected+" width="+width+" height="+height+" tileAA=1 filter=NONE resolutionReduction=1 FG=false denoiser=false");
            System.out.printf(Locale.ROOT,"LuxCorePerf openMs=%.3f uploadMs=%.3f sessionStartMs=%.3f%n",(opened-start)/1e6,(uploaded-opened)/1e6,(started-uploaded)/1e6);
            var raw=ByteBuffer.allocateDirect(Math.multiplyExact(width*height,16)).order(ByteOrder.nativeOrder());
            var records=new ArrayList<String>();
            for(int round=1;round<=rounds;round++) {
                double before=access.totalSamples();long begin=System.nanoTime();
                while(System.nanoTime()-begin<seconds*1e9) {
                    access.totalSamples();
                    LockSupport.parkNanos(1_000_000L);
                }
                double after=access.totalSamples();long end=System.nanoTime();double spp=(after-before)/(width*(double)height),rate=spp*1e9/(end-begin);
                long readStart=System.nanoTime();double readSamples=access.readLinearRgba(raw);long readNs=System.nanoTime()-readStart;
                long invalid=0;double radiance=0;for(int i=0;i<width*height;i++){float v=raw.getFloat(i*16);if(!Float.isFinite(v)||v<0)invalid++;else radiance+=v;}
                if(invalid!=0||radiance<=0)throw new AssertionError("Invalid film invalid="+invalid+" radiance="+radiance);
                String result=String.format(Locale.ROOT,"{\"round\":%d,\"wallMs\":%.6f,\"completedSampleDelta\":%.0f,\"sppDelta\":%.6f,\"sppPerSecond\":%.6f,\"msPerSpp\":%.6f,\"readFilmMs\":%.6f,\"filmSamples\":%.0f}",round,(end-begin)/1e6,after-before,spp,rate,1000/rate,readNs/1e6,readSamples);
                records.add(result);System.out.println("LuxCorePerf "+result);
            }
            String stats=access.nativeStatistics();Files.writeString(output.resolve("native-stats.txt"),stats,StandardCharsets.UTF_8);
            Files.writeString(output.resolve("result.json"),"{\"scene\":\""+selected+"-native\",\"width\":"+width+",\"height\":"+height+",\"metric\":\"native progressive completed SPP/s, not independent no-history FPS\",\"nativeSemanticChanges\":"+NativeMediumScenes.JSON_CHANGES+",\"rounds\":["+String.join(",",records)+"]}\n",StandardCharsets.UTF_8);
            System.out.println("LuxCorePerf bridge="+access.bridgeTimings()+" report="+output.toAbsolutePath());
        }
    }
    static void waitSamples(LuxCoreAccess access,double previous,int seconds) {
        long end=System.nanoTime()+seconds*1_000_000_000L;while(access.totalSamples()<=previous){if(System.nanoTime()>end)throw new IllegalStateException("No upstream completed samples");LockSupport.parkNanos(1_000_000);}
    }
}

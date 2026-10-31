package demo;

import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import top.ceroxe.rt.renderer.api.Renderer;
import top.ceroxe.rt.renderer.api.TextureFormat;

/** Captures the actual matrix workloads for frozen-core HDR comparison, outside all FPS windows. */
public final class MediumPerformanceHdrCapture {
    private MediumPerformanceHdrCapture() { }

    public static void main(String[] args) throws Exception {
        if(args.length!=5)throw new IllegalArgumentException("cases|all outputDirectory width height frames");
        var workloads=MediumPerformanceCases.all();
        if(!args[0].equals("all")) {
            var selected=Set.of(args[0].split(","));
            workloads=workloads.stream().filter(w->selected.contains(w.id())).toList();
            require(workloads.size()==selected.size(),"unknown or duplicate HDR workloads");
        }
        Path output=Path.of(args[1]).toAbsolutePath();
        int width=Integer.parseInt(args[2]),height=Integer.parseInt(args[3]),frames=Integer.parseInt(args[4]);
        require(width>0&&height>0&&frames>0,"positive HDR extent and frames required");
        Files.createDirectories(output);
        for(var workload:workloads) {
            try(var renderer=Renderer.open(workload.config().copyBuilder().cpuFrameReadbackEnabled(true).build())) {
                renderer.apply(workload.transaction());
                for(int frame=0;frame<frames;frame++) {
                    var request=workload.frame(frame,width,height);
                    long deadline=System.nanoTime()+Duration.ofSeconds(120).toNanos();
                    while(!(renderer.trySubmit(request) instanceof Renderer.FrameSubmitted)) {
                        require(System.nanoTime()<deadline,"HDR frame admission timed out: "+workload.id());
                        LockSupport.parkNanos(100_000);
                    }
                    var snapshot=renderer.awaitFrame(Duration.ofSeconds(120)).orElseThrow();
                    require(snapshot.format()==TextureFormat.RGBA16_FLOAT,"linear half-float HDR required");
                    var pixels=snapshot.pixels().duplicate().order(ByteOrder.LITTLE_ENDIAN);
                    byte[] packed=new byte[Math.multiplyExact(Math.multiplyExact(width,height),8)];
                    for(int y=0;y<height;y++) {
                        for(int x=0;x<width;x++)for(int c=0;c<4;c++) {
                            float value=Float.float16ToFloat(pixels.getShort(y*snapshot.rowBytes()+x*8+c*2));
                            require(Float.isFinite(value)&&value>=0,"invalid HDR pixel: "+workload.id());
                        }
                        pixels.position(y*snapshot.rowBytes());pixels.get(packed,y*width*8,width*8);
                    }
                    Files.write(output.resolve(workload.id()+"-"+frame+".rgba16f"),packed);
                }
                require(renderer.health().activeFailure().isEmpty(),"HDR session failed: "+workload.id());
            }
            System.out.println("MatrixHDR case="+workload.id()+" frames="+frames+" extent="+width+"x"+height);
        }
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}

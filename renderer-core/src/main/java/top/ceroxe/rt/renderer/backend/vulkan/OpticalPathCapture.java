package top.ceroxe.rt.renderer.backend.vulkan;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import top.ceroxe.rt.renderer.api.RendererException;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumRuntime;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaOpticalMediumAbi;

/** Opt-in correctness capture: GPU programs, arithmetic and device allocation stay unchanged. */
final class OpticalPathCapture {
    private static final String DIRECTORY=System.getProperty("top.ceroxe.rt.medium.opticalCaptureDirectory");
    private static final int PATH=Integer.getInteger("top.ceroxe.rt.medium.opticalCapturePath",-1);
    private static final long SEQUENCE=Long.getLong("top.ceroxe.rt.medium.opticalCaptureSequence",1L);
    private final CudaMediumRuntime cuda;
    private final CudaOpticalMediumAbi.Plan plan;
    private final int paths,item;
    private final Path directory;
    private int record;

    private OpticalPathCapture(CudaMediumRuntime cuda,CudaOpticalMediumAbi.Plan plan,int paths,int item,Path directory) {
        this.cuda=cuda;this.plan=plan;this.paths=paths;this.item=item;this.directory=directory;
    }
    static boolean enabled(){return DIRECTORY!=null;}
    static OpticalPathCapture open(CudaMediumRuntime cuda,CudaOpticalMediumAbi.Plan plan,long sequence,
            int firstPixel,int paths,int sample,int branch) {
        if(PATH<0)throw new IllegalArgumentException("opticalCaptureDirectory requires a nonnegative opticalCapturePath");
        long local=PATH-(long)firstPixel*plan.samples();
        if(sequence!=SEQUENCE||local<0||local>=paths)return null;
        Path directory=Path.of(DIRECTORY).resolve("sequence-"+sequence+"-sample-"+sample+"-branch-"+branch);
        try{Files.createDirectories(directory);}catch(IOException failure){throw new RendererException("create optical path capture",failure);}
        return new OpticalPathCapture(cuda,plan,paths,(int)local,directory);
    }
    void capture(CudaMediumRuntime.Slice input,String phase) {
        // Bounded to one path per selected frame; no whole-frame temporary arrays.
        int bytes=CudaOpticalMediumAbi.HEADER_BYTES+plan.stateBytes()+plan.hitBytes()
                +(plan.connectionTransport()?CudaOpticalMediumAbi.CONNECTION_BYTES:0)+48;
        try(Arena arena=Arena.ofConfined()) {
            var data=arena.allocate(bytes,16);int offset=0;
            cuda.download(input.slice(0,CudaOpticalMediumAbi.HEADER_BYTES),data.asSlice(offset,CudaOpticalMediumAbi.HEADER_BYTES));
            offset+=CudaOpticalMediumAbi.HEADER_BYTES;
            record(input,plan.stateOffset(),plan.stateBytes(),4,plan.transposedStates(),data.asSlice(offset,plan.stateBytes()));
            offset+=plan.stateBytes();
            record(input,plan.hitOffset(),plan.hitBytes(),16,plan.transposedHits(),data.asSlice(offset,plan.hitBytes()));
            offset+=plan.hitBytes();
            if(plan.connectionTransport()){
                cuda.download(input.slice(plan.connectionOffset()+(long)item*CudaOpticalMediumAbi.CONNECTION_BYTES,
                        CudaOpticalMediumAbi.CONNECTION_BYTES),data.asSlice(offset,CudaOpticalMediumAbi.CONNECTION_BYTES));
                offset+=CudaOpticalMediumAbi.CONNECTION_BYTES;
            }
            for(int base:new int[]{plan.evaluationOffset(),plan.transmittanceOffset(),plan.emissionOffset()}) {
                cuda.download(input.slice(base+(long)item*16,16),data.asSlice(offset,16));offset+=16;
            }
            Files.write(directory.resolve(String.format(java.util.Locale.ROOT,"%04d-%s.bin",record++,phase)),
                    data.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
        }catch(IOException failure){throw new RendererException("write optical path capture",failure);}
    }
    private void record(CudaMediumRuntime.Slice input,long base,int bytes,int laneBytes,boolean transposed,MemorySegment output) {
        if(!transposed){cuda.download(input.slice(base+(long)item*bytes,bytes),output);return;}
        for(int lane=0;lane<bytes/laneBytes;lane++)cuda.download(
                input.slice(base+((long)lane*paths+item)*laneBytes,laneBytes),output.asSlice((long)lane*laneBytes,laneBytes));
    }
}

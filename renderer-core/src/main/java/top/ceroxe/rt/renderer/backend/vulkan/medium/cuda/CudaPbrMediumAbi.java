package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import top.ceroxe.rt.renderer.api.Medium;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;

/** Bounded, GPU-only camera and direct-light packets. No per-light host exchanges are required. */
public final class CudaPbrMediumAbi {
    public static final int HEADER_BYTES=288, COLOR_BYTES=16, PRIMARY_BYTES=208;
    public static final int STATISTICS_OFFSET=112, STATISTICS_BYTES=16;
    public static final long ALLOCATION_ALLOWANCE=65_536, DEFAULT_RESIDENT_BYTES=512L*1024*1024;
    public static final long NATIVE_LIGHTING_FLAG=4;
    private CudaPbrMediumAbi() { }

    public record Plan(int pixels,int samples,int tilePixels,int boundaryCount,int connections,int capacity,
            int sceneBytes,int stateOffset,int colorsOffset,int transmittanceOffset,int emissionOffset,long bytes) {
        public int paths(){return Math.multiplyExact(pixels,samples);}
        public int packetBytes(){return 32+16*((capacity+1)/2);}
        public int tilePaths(){return Math.multiplyExact(tilePixels,samples);}
        public long primaryBytes(){return Math.multiplyExact((long)paths(),PRIMARY_BYTES);}
    }

    public static long minimumBytes(int width,int height,int samples,int boundaries,int lightUpperBound,int capacity) {
        dimensions(width,height,samples,boundaries,lightUpperBound,capacity);
        long paths=Math.multiplyExact(Math.multiplyExact((long)width,height),samples);
        long scene=align16(Math.toIntExact(HEADER_BYTES+(long)boundaries*68));
        long scratch=Math.multiplyExact((long)samples,Math.multiplyExact((long)lightUpperBound+2,32+16*((capacity+1)/2)+32));
        return Math.addExact(Math.addExact(scene,Math.multiplyExact(paths,32)),scratch);
    }

    public static Plan plan(int width,int height,int samples,int boundaries,int lightUpperBound,int capacity,
            CudaMediumDensityAbi.Scene density,long storageLimit) {
        dimensions(width,height,samples,boundaries,lightUpperBound,capacity);
        int pixels=Math.toIntExact(Math.multiplyExact((long)width,height));
        int paths=Math.multiplyExact(pixels,samples),connections=Math.addExact(lightUpperBound,1);
        int scene=Math.addExact(align16(Math.toIntExact(HEADER_BYTES+(long)boundaries*68)),density.bytes());
        int trans=scene,emission=Math.toIntExact(trans+(long)paths*16),state=Math.toIntExact(emission+(long)paths*16);
        int packetBytes=32+16*((capacity+1)/2);
        long perPixel=Math.multiplyExact((long)samples,Math.multiplyExact((long)connections+1,packetBytes+32));
        long available=storageLimit-state;
        if(available<perPixel)throw new IllegalArgumentException("complete native PBR output and one pixel's light packets exceed residency");
        // Dispatch width is bounded independently of the storage arithmetic.
        int tile=(int)Math.min(Math.min((long)pixels,available/perPixel),Integer.MAX_VALUE/(long)connections);
        if(tile<1)throw new IllegalArgumentException("native PBR light dispatch exceeds uint indexing");
        int colors=Math.toIntExact(state+Math.multiplyExact(Math.multiplyExact((long)tile*samples,connections+1L),packetBytes));
        long bytes=Math.addExact(colors,Math.multiplyExact(Math.multiplyExact((long)tile*samples,connections+1L),32));
        return new Plan(pixels,samples,tile,boundaries,connections,capacity,scene,state,colors,trans,emission,bytes);
    }

    public static byte[] scenePrefix(Plan plan,RenderFrameRequest frame,List<CudaBoundaryMediumAbi.Boundary> source,
            int[] containing,CudaMediumDensityAbi.Scene density) {
        if(source.size()!=plan.boundaryCount()||containing.length>32)throw new IllegalArgumentException("invalid native PBR boundaries");
        int priority=HEADER_BYTES+plan.boundaryCount()*64,densityOffset=align16(priority+plan.boundaryCount()*4);
        if(densityOffset+density.bytes()!=plan.sceneBytes())throw new IllegalArgumentException("native PBR density allocation mismatch");
        var entries=new ArrayList<>(source);entries.sort((a,b)->Integer.compareUnsigned(a.slot(),b.slot()));
        ByteBuffer b=ByteBuffer.allocate(densityOffset).order(ByteOrder.nativeOrder());
        updateTile(b,plan,0,Math.min(plan.tilePixels(),plan.pixels()));
        b.putInt(24,frame.mediumTransport().maxEvents()).putInt(28,plan.boundaryCount()).putInt(32,containing.length);
        b.putInt(52,HEADER_BYTES).putInt(56,priority).putInt(60,128);
        putMedium(b,64,frame.environment().cameraMedium());
        b.putFloat(76,frame.mediumTransport().maxDistance()).putFloat(92,frame.mediumTransport().transmittanceCutoff());
        b.putInt(96,descriptor(density.environmentOffset(),densityOffset));
        b.putInt(100,frame.mediumTransport().scatteringSteps()).putInt(104,plan.sceneBytes());
        var compact=new HashMap<Integer,Integer>();
        for(int i=0;i<entries.size();i++) {
            var entry=entries.get(i);var boundary=entry.boundary();
            if(boundary.surfaceInteraction())throw new IllegalArgumentException("PBR packets require volume-only boundaries");
            if(compact.put(entry.slot(),i)!=null)throw new IllegalArgumentException("duplicate native PBR boundary slot");
            int base=HEADER_BYTES+i*64;
            b.putInt(base,entry.slot()).putInt(base+4,boundary.priority()).putInt(base+8,boundary.orientation().ordinal()).putInt(base+12,3);
            putMedium(b,base+16,boundary.medium());
            b.putInt(base+48,descriptor(density.boundaryOffset(entry.slot()),densityOffset));
            b.putInt(priority+i*4,boundary.priority());
        }
        var unique=new HashSet<Integer>();
        for(int i=0;i<containing.length;i++) {
            Integer index=compact.get(containing[i]);
            if(index==null||!unique.add(index))throw new IllegalArgumentException("invalid native PBR initial volume set");
            b.putInt(128+i*4,index);
        }
        return b.array();
    }

    public static void updateTile(ByteBuffer b,Plan p,int first,int count) {
        if(first<0||count<1||count>p.tilePixels()||(long)first+count>p.pixels())throw new IllegalArgumentException("invalid PBR tile");
        int paths=Math.multiplyExact(count,p.samples());
        b.putInt(0,CudaBoundaryMediumAbi.MAGIC).putInt(4,1).putInt(8,paths).putInt(12,p.samples()).putInt(16,first).putInt(20,count);
        // Header byte 40 is the packet base consumed by the light query shader; both
        // camera and connection packets share one tile-local state array.
        b.putInt(36,p.stateOffset()).putInt(40,p.stateOffset()).putInt(44,Math.toIntExact(p.transmittanceOffset()+(long)first*p.samples()*16))
                .putInt(48,Math.toIntExact(p.emissionOffset()+(long)first*p.samples()*16));
        b.putInt(108,p.capacity()).putInt(112,paths).putInt(116,0).putInt(120,0).putInt(124,0);
        b.putInt(256,p.connections()).putInt(260,p.colorsOffset()).putInt(264,paths).putInt(268,0);
    }
    private static int descriptor(int offset,int base){return offset<0?-1:Math.addExact(offset,base);}
    private static void putMedium(ByteBuffer b,int base,Medium medium) {
        CudaBoundaryMediumAbi.requireMedium(Objects.requireNonNull(medium,"medium"));
        var a=medium.absorption().multiply(medium.density());var e=medium.emission().multiply(medium.density());
        b.putFloat(base,a.red()).putFloat(base+4,a.green()).putFloat(base+8,a.blue()).putFloat(base+12,1);
        b.putFloat(base+16,e.red()).putFloat(base+20,e.green()).putFloat(base+24,e.blue());
    }
    private static int align16(int size){return Math.toIntExact((size+15L)&~15L);}
    private static void dimensions(int w,int h,int samples,int boundaries,int lights,int capacity) {
        if(w<1||h<1||samples<1||samples>8||boundaries<0||lights<0||capacity<1||capacity>32)
            throw new IllegalArgumentException("invalid native PBR frame extent or packet capacity");
    }
}

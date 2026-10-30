package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import top.ceroxe.rt.renderer.api.Caustics;

/** Bounded light-path storage. Vulkan supplies geometric hits; CUDA owns all medium events. */
public final class CudaOpticalCausticAbi {
    public static final int MAGIC=0x524f4331,VERSION=1,HEADER_BYTES=128;
    public static final int STATE_BYTES=272,HIT_BYTES=208,PHOTON_BYTES=64,GATHER_BYTES=80;
    public static final int ACTIVE=1,SURFACE=0,VOLUME=1;
    private CudaOpticalCausticAbi() { }
    public record Plan(int paths,int depositsPerPath,int stateOffset,int hitOffset,int photonOffset,
            int headsOffset,int hashCapacity,long bytes) {
        public int capacity(){return Math.multiplyExact(paths,depositsPerPath);}
    }
    /** Reserve every possible deposition: no overflow truncation or silent photon loss. */
    public static Plan plan(int paths,int maxSurfaceEvents,int maxScatterEvents,long residentLimit) {
        if(paths<1||paths>Caustics.MAX_PHOTONS||maxSurfaceEvents<1||maxSurfaceEvents>32
                ||maxScatterEvents<0||maxScatterEvents>32||residentLimit<1)
            throw new IllegalArgumentException("invalid optical photon budget");
        return planForMap(paths,Math.addExact(maxSurfaceEvents,maxScatterEvents),residentLimit);
    }
    /** Production maps reserve one terminal surface record plus every admitted scattering event. */
    public static Plan planForMap(int paths,int deposits,long residentLimit) {
        if(paths<1||paths>Caustics.MAX_PHOTONS||deposits<1||deposits>64||residentLimit<1)
            throw new IllegalArgumentException("invalid optical photon map budget");
        int capacity=Math.multiplyExact(paths,deposits);
        long desired=Math.max(2L,(long)capacity*2),hash=Long.highestOneBit(desired-1)<<1;
        if(hash>Integer.MAX_VALUE)throw new IllegalArgumentException("optical photon hash exceeds addressable storage");
        int state=HEADER_BYTES,hit=Math.toIntExact(state+(long)paths*STATE_BYTES);
        int photons=Math.toIntExact(hit+(long)paths*HIT_BYTES),heads=Math.toIntExact(photons+(long)capacity*PHOTON_BYTES);
        long bytes=Math.addExact(heads,hash*4);
        if(bytes+65536>residentLimit||bytes>Integer.MAX_VALUE)
            throw new IllegalArgumentException("complete optical photon map exceeds the resident budget");
        return new Plan(paths,deposits,state,hit,photons,heads,(int)hash,bytes);
    }
    public static byte[] header(Plan p,Caustics caustics,int maxScatterEvents,int sequence) {
        if(!caustics.enabled()||p.paths()!=caustics.photons()||maxScatterEvents<0||maxScatterEvents>32
                ||p.depositsPerPath()!=caustics.maxEvents()+maxScatterEvents)
            throw new IllegalArgumentException("inconsistent optical photon scene");
        return mapHeader(p,caustics,maxScatterEvents,sequence);
    }
    public static byte[] mapHeader(Plan p,Caustics caustics,int maxScatterEvents,int sequence) {
        if(!caustics.enabled()||p.paths()!=caustics.photons()||maxScatterEvents<0||maxScatterEvents>32)
            throw new IllegalArgumentException("inconsistent production optical photon scene");
        var b=ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.nativeOrder());
        b.putInt(0,MAGIC).putInt(4,VERSION).putInt(8,p.paths()).putInt(12,p.depositsPerPath())
                .putInt(16,p.stateOffset()).putInt(20,p.hitOffset()).putInt(24,p.photonOffset())
                .putInt(28,p.headsOffset()).putInt(32,p.hashCapacity()-1).putInt(36,sequence)
                .putInt(40,caustics.maxEvents()).putInt(44,maxScatterEvents);
        b.putFloat(48,caustics.gatherRadius()).putFloat(52,caustics.radius())
                .putFloat(56,caustics.normalThreshold()).putFloat(64,(float)caustics.centerX())
                .putFloat(68,(float)caustics.centerY()).putFloat(72,(float)caustics.centerZ());
        return b.array();
    }
    /** CUDA allocation addresses only; Vulkan buffer device addresses are not interchangeable. */
    public static void bindCudaScene(ByteBuffer header,long sceneAddress) {
        if(sceneAddress==0||(sceneAddress&15)!=0)throw new IllegalArgumentException("invalid CUDA optical scene address");
        header.duplicate().order(ByteOrder.nativeOrder()).putLong(96,sceneAddress);
    }
    public static void bindCudaGather(ByteBuffer header,long queriesAddress) {
        if(queriesAddress==0||(queriesAddress&15)!=0)throw new IllegalArgumentException("invalid CUDA gather query address");
        header.duplicate().order(ByteOrder.nativeOrder()).putLong(104,queriesAddress);
    }
    /** Photon state starts with the existing 256-byte strict optical state, then deposition metadata. */
    public static void initialize(ByteBuffer bytes,Plan p,int path,float ox,float oy,float oz,
            float dx,float dy,float dz,float red,float green,float blue,float maximum,int[] compactMedia) {
        bytes=bytes.duplicate().order(ByteOrder.nativeOrder());
        if(path<0||path>=p.paths()||compactMedia.length>32||!(maximum>0)||!Float.isFinite(maximum))
            throw new IllegalArgumentException("invalid optical photon initialization");
        float norm=(float)Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(!(norm>0)||!Float.isFinite(norm)||!Float.isFinite(ox)||!Float.isFinite(oy)||!Float.isFinite(oz)
                ||red<0||green<0||blue<0||!Float.isFinite(red)||!Float.isFinite(green)||!Float.isFinite(blue))
            throw new IllegalArgumentException("nonfinite optical photon");
        int at=p.stateOffset()+path*STATE_BYTES;
        for(int i=0;i<STATE_BYTES;i+=4)bytes.putInt(at+i,0);
        bytes.putFloat(at,ox).putFloat(at+4,oy).putFloat(at+8,oz)
                .putFloat(at+16,dx/norm).putFloat(at+20,dy/norm).putFloat(at+24,dz/norm)
                .putInt(at+32,ACTIVE).putInt(at+40,path).putInt(at+44,1)
                .putFloat(at+48,red).putFloat(at+52,green).putFloat(at+56,blue).putFloat(at+60,maximum)
                .putFloat(at+96,1).putFloat(at+100,1).putFloat(at+104,1).putFloat(at+108,1);
        // The initialization kernel resolves priorities and detects duplicate/out-of-range entries.
        bytes.putInt(at+112,-1).putInt(at+244,compactMedia.length);
        for(int i=0;i<compactMedia.length;i++)bytes.putInt(at+116+i*4,compactMedia[i]);
    }
}

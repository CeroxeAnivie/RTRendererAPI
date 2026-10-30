package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.LongFunction;
import top.ceroxe.rt.renderer.api.Medium;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.TextureAsset;

/** Private packet ABI for scattering paths whose active medium changes at explicit volume boundaries. */
public final class CudaBoundaryScatteringAbi {
    public static final int MAGIC=0x52534231,VERSION=1,HEADER_BYTES=512,BOUNDARY_BYTES=64;
    public static final int STATE_BYTES=144,VOLUME_BYTES=144,CONNECTION_BYTES=32,RAY_RECORD_BYTES=64;
    /** Maximum packet size, retained for callers that need a conservative upper bound. */
    public static final int PACKET_BYTES=288,MAX_BOUNDARIES=32,MAX_CONNECTIONS=9;
    public static final int STATISTICS_OFFSET=112,STATISTICS_BYTES=16;
    public static final int QUERY_CAMERA=0,QUERY_GEOMETRY=16,QUERY_LIGHTS=32;
    public static final long RESIDENT_CAP=512L*1024*1024,ALLOCATION_ALLOWANCE=65_536;
    private CudaBoundaryScatteringAbi() { }

    public record Plan(int pixels,int samples,int steps,int tilePixels,int packetCapacity,int connections,
            int sceneBytes,int primaryOffset,int transmittanceOffset,int emissionOffset,int stateOffset,
            int volumeOffset,int geometryPacketOffset,int shadowPacketOffset,int geometryRayOffset,
            int shadowRayOffset,int connectionOffset,int connectionRayOffset,long bytes) {
        public int paths(){return Math.multiplyExact(pixels,samples);}
        public int tilePaths(){return Math.multiplyExact(tilePixels,samples);}
        public int states(){return tilePaths();}
        public int packetBytes(){return CudaBoundaryScatteringAbi.packetBytes(packetCapacity);}
    }

    public record FrameScene(RenderFrameRequest frame,Plan plan,CudaMediumDensityAbi.Scene density,
            List<CudaBoundaryMediumAbi.Boundary> boundaries,int[] containingSlots,int lightUpperBound) {
        public FrameScene {boundaries=List.copyOf(boundaries);containingSlots=containingSlots.clone();}
        @Override public int[] containingSlots(){return containingSlots.clone();}
        public int chunks(){long count=frame.mediumTransport().lightSamples()==0?lightUpperBound
                :Math.min(lightUpperBound,frame.mediumTransport().lightSamples());
            return Math.toIntExact((count+1+plan.connections()-1)/plan.connections());}
        public byte[] metadata(int firstPixel,int pixelCount,int mode,int chunk) {
            return pack(firstPixel,pixelCount,mode,chunk,true);
        }
        public byte[] metadataPrefix(int firstPixel,int pixelCount,int mode,int chunk) {
            return pack(firstPixel,pixelCount,mode,chunk,false);
        }
        private byte[] pack(int firstPixel,int pixelCount,int mode,int chunk,boolean includeDensity) {
            if(firstPixel<0||pixelCount<1||pixelCount>plan.tilePixels()||(long)firstPixel+pixelCount>plan.pixels()
                    ||mode!=QUERY_CAMERA&&mode!=QUERY_GEOMETRY&&mode!=QUERY_LIGHTS||chunk<0||chunk>=chunks())
                throw new IllegalArgumentException("invalid boundary-scattering query tile");
            var medium=frame.environment().cameraMedium();var transport=frame.mediumTransport();
            int tilePaths=Math.multiplyExact(pixelCount,plan.samples());
            int densityBase=plan.sceneBytes()-density.bytes();
            ByteBuffer b=ByteBuffer.allocate(includeDensity?plan.sceneBytes():densityBase).order(ByteOrder.nativeOrder());
            int packetOffset=mode==QUERY_CAMERA?plan.geometryPacketOffset():mode==QUERY_GEOMETRY
                    ?Math.toIntExact(plan.geometryPacketOffset()+(long)tilePaths*plan.packetBytes()):plan.shadowPacketOffset();
            int rayRecords=mode==QUERY_LIGHTS?plan.connectionRayOffset():plan.geometryRayOffset();
            int queries=mode==QUERY_CAMERA?tilePaths:mode==QUERY_GEOMETRY?plan.states()
                    :Math.multiplyExact(plan.states(),plan.connections());
            b.putInt(0,MAGIC).putInt(4,VERSION).putInt(8,tilePaths).putInt(12,plan.samples())
                    .putInt(16,firstPixel).putInt(20,pixelCount).putInt(24,transport.maxEvents()).putInt(28,boundaries.size())
                    .putInt(32,containingSlots.length).putInt(36,packetOffset)
                    .putInt(40,plan.primaryOffset()).putInt(44,plan.transmittanceOffset()).putInt(48,plan.emissionOffset())
                    .putInt(52,HEADER_BYTES).putInt(56,HEADER_BYTES+boundaries.size()*BOUNDARY_BYTES)
                    .putInt(60,384);
            var sigma=medium.extinction().multiply(medium.density());var scatter=medium.scattering().multiply(medium.density());
            var emission=medium.emission().multiply(medium.density());
            b.putFloat(64,sigma.red()).putFloat(68,sigma.green()).putFloat(72,sigma.blue()).putFloat(76,transport.maxDistance())
                    .putFloat(80,emission.red()).putFloat(84,emission.green()).putFloat(88,emission.blue())
                    .putFloat(92,transport.transmittanceCutoff()).putInt(96,descriptor(plan.sceneBytes()-density.bytes(),density.environmentOffset()))
                    .putInt(100,plan.steps()).putInt(104,plan.stateOffset()).putInt(108,plan.packetCapacity());
            if(mode==QUERY_CAMERA)b.putInt(STATISTICS_OFFSET,0).putInt(STATISTICS_OFFSET+4,0)
                    .putInt(STATISTICS_OFFSET+8,0).putInt(STATISTICS_OFFSET+12,0);
            b.putFloat(128,scatter.red()).putFloat(132,scatter.green()).putFloat(136,scatter.blue())
                    .putFloat(140,medium.anisotropy()).putInt(156,transport.scatteringBounces())
                    .putInt(160,(int)frame.sequence()).putInt(164,plan.states()).putInt(168,plan.connections())
                    .putInt(172,chunk*plan.connections()).putInt(176,lightUpperBound).putInt(180,transport.lightSamples())
                    .putInt(184,STATE_BYTES).putInt(188,plan.volumeOffset()).putInt(192,plan.geometryPacketOffset())
                    .putInt(196,plan.shadowPacketOffset()).putInt(200,plan.geometryRayOffset()).putInt(204,plan.shadowRayOffset())
                    .putInt(208,tilePaths).putInt(212,mode).putInt(216,plan.packetBytes())
                    .putInt(220,plan.connectionOffset()).putInt(224,plan.connectionRayOffset())
                    .putInt(256,rayRecords).putInt(260,queries);
            // Sorted compact boundary table. Density descriptors relocate against this resident prefix.
            var ordered=new ArrayList<>(boundaries);ordered.sort((x,y)->Integer.compareUnsigned(x.slot(),y.slot()));
            var compact=new HashMap<Integer,Integer>();
            int tableBase=HEADER_BYTES;
            for(int index=0;index<ordered.size();index++) {
                var entry=ordered.get(index);var boundary=entry.boundary();
                if(boundary.surfaceInteraction())
                    throw new IllegalArgumentException("boundary scattering requires volume-only boundaries");
                if(compact.put(entry.slot(),index)!=null)throw new IllegalArgumentException("duplicate boundary slot");
                int at=tableBase+index*BOUNDARY_BYTES;var m=boundary.medium();
                var st=m.extinction().multiply(m.density());var ss=m.scattering().multiply(m.density());var e=m.emission().multiply(m.density());
                b.putInt(at,entry.slot()).putInt(at+4,boundary.priority()).putInt(at+8,boundary.orientation().ordinal()).putInt(at+12,3)
                        .putFloat(at+16,st.red()).putFloat(at+20,st.green()).putFloat(at+24,st.blue()).putFloat(at+28,m.anisotropy())
                        .putFloat(at+32,ss.red()).putFloat(at+36,ss.green()).putFloat(at+40,ss.blue())
                        .putInt(at+44,descriptor(plan.sceneBytes()-density.bytes(),density.boundaryOffset(entry.slot())))
                        .putFloat(at+48,e.red()).putFloat(at+52,e.green()).putFloat(at+56,e.blue()).putFloat(at+60,0);
            }
            int priority=HEADER_BYTES+boundaries.size()*BOUNDARY_BYTES;
            for(int index=0;index<ordered.size();index++)b.putInt(priority+index*4,ordered.get(index).boundary().priority());
            var unique=new HashSet<Integer>();int init=(int)b.getInt(60);
            for(int i=0;i<containingSlots.length;i++) {
                Integer index=compact.get(containingSlots[i]);
                if(index==null||!unique.add(index))throw new IllegalArgumentException("invalid initial containing volume set");
                b.putInt(init+i*4,index);
            }
            if(includeDensity)density.write(b,densityBase);
            return b.array();
        }
    }

    public static FrameScene scene(RenderFrameRequest frame,int width,int height,int lightUpperBound,
            List<CudaBoundaryMediumAbi.Boundary> boundaries,int[] containingSlots,
            LongFunction<TextureAsset> textures,CudaMediumDensityAbi.Cache cache,long residentLimit,int packetCapacity) {
        Objects.requireNonNull(frame);Objects.requireNonNull(boundaries);Objects.requireNonNull(containingSlots);
        if(width<1||height<1||frame.antiAliasing().samplesPerPixel()<1||frame.antiAliasing().samplesPerPixel()>8
                ||lightUpperBound<0||packetCapacity<1||packetCapacity>32||boundaries.size()>MAX_BOUNDARIES
                ||containingSlots.length>MAX_BOUNDARIES
                ||frame.mediumTransport().scatteringSteps()<1)
            throw new IllegalArgumentException("unsupported boundary-scattering frame dimensions or event limits");
        boolean scattering=!frame.environment().cameraMedium().scattering().isZero();
        for(var boundary:boundaries) {
            if(boundary.boundary().surfaceInteraction())
                throw new IllegalArgumentException("boundary scattering requires volume-only boundaries");
            scattering|=!boundary.boundary().medium().scattering().isZero();
        }
        if(!scattering)throw new IllegalArgumentException("at least one active medium must scatter");
        int pixels=Math.toIntExact(Math.multiplyExact((long)width,height)),samples=frame.antiAliasing().samplesPerPixel();
        int connections=(int)Math.min(MAX_CONNECTIONS,1L+(frame.mediumTransport().lightSamples()==0?lightUpperBound
                :Math.min(lightUpperBound,frame.mediumTransport().lightSamples())));
        int initialOffset=align16(HEADER_BYTES+boundaries.size()*(BOUNDARY_BYTES+Integer.BYTES));
        long fixed=Math.addExact(initialOffset,Math.multiplyExact((long)pixels*samples,240L));
        int packetBytes=packetBytes(packetCapacity);
        long perPath=packetBytes;
        long perState=STATE_BYTES+VOLUME_BYTES+packetBytes+RAY_RECORD_BYTES
                +(long)connections*(CONNECTION_BYTES+packetBytes+RAY_RECORD_BYTES);
        long perPixel=Math.addExact((long)samples*perPath,Math.multiplyExact((long)samples,perState));
        long densityLimit=Math.min(Math.min(residentLimit,RESIDENT_CAP),RESIDENT_CAP)-ALLOCATION_ALLOWANCE-fixed-perPixel;
        Medium environment=frame.environment().cameraMedium();
        var density=cache==null?CudaMediumDensityAbi.prepare(environment,boundaries,textures,densityLimit)
                :cache.prepare(environment,boundaries,textures,densityLimit);
        long cap=Math.min(Math.min(residentLimit,RESIDENT_CAP),RESIDENT_CAP)-ALLOCATION_ALLOWANCE;
        long withDensity=Math.addExact(fixed,density.bytes());long available=cap-withDensity;
        int tile=Math.toIntExact(Math.min(pixels,Math.max(0,available)/perPixel));
        if(tile<1)throw new IllegalArgumentException("complete boundary-scattering outputs and one state tile exceed CUDA residency");
        int paths=Math.multiplyExact(pixels,samples),tilePaths=Math.multiplyExact(tile,samples),states=tilePaths;
        int prefix=initialOffset+density.bytes();
        int primary=prefix;
        int tr=Math.toIntExact(primary+(long)paths*208),em=Math.toIntExact(tr+(long)paths*16);
        int state=Math.toIntExact(em+(long)paths*16),volumes=Math.toIntExact(state+(long)states*STATE_BYTES);
        int geoPackets=Math.toIntExact(volumes+(long)states*VOLUME_BYTES);
        int shadowPackets=Math.toIntExact(geoPackets+(long)(tilePaths+states)*packetBytes);
        int geoRays=Math.toIntExact(shadowPackets+(long)states*connections*packetBytes);
        int connectionsOffset=Math.toIntExact(geoRays+(long)states*RAY_RECORD_BYTES);
        int connectionRays=Math.toIntExact(connectionsOffset+(long)states*connections*CONNECTION_BYTES);
        int scratchEnd=Math.toIntExact(connectionRays+(long)states*connections*RAY_RECORD_BYTES);
        long bytes=scratchEnd;
        return new FrameScene(frame,new Plan(pixels,samples,frame.mediumTransport().scatteringSteps(),tile,packetCapacity,connections,
                prefix,primary,tr,em,state,volumes,geoPackets,shadowPackets,geoRays,connectionRays,connectionsOffset,connectionRays,bytes),
                density,boundaries,containingSlots,lightUpperBound);
    }
    private static int descriptor(int densityBase,int relative){return relative<0?-1:Math.addExact(densityBase,relative);}
    public static int packetBytes(int capacity) {
        if(capacity<1||capacity>32)throw new IllegalArgumentException("packet capacity must be within [1,32]");
        return Math.addExact(32,Math.multiplyExact(16,(capacity+1)/2));
    }
    private static int align16(int value){return Math.toIntExact((Math.addExact((long)value,15))&~15L);}
}

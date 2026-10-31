package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import top.ceroxe.rt.renderer.api.Medium;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;

/** Dielectric continuation with homogeneous or density-grid camera scattering; primary samples remain intact. */
public final class CudaOpticalMediumAbi {
    public static final int MAGIC=0x524f5031,VERSION=7,HEADER_BYTES=384,BOUNDARY_BYTES=64;
    public static final int STATE_BYTES=256,HIT_BYTES=208,COLOR_BYTES=16,STATISTICS_OFFSET=112,STATISTICS_BYTES=16;
    public static final int COMPACT_HIT_BYTES=96;
    public static final int COMPACT_STATE_BYTES=112;
    public static final int CONNECTION_BYTES=352;
    public static final long DEFAULT_RESIDENT_BYTES=512L*1024*1024,ALLOCATION_ALLOWANCE=65536;
    /** Byte offsets in this ABI are signed Java ints; leave room for allocation alignment. */
    public static final long MAXIMUM_RESIDENT_BYTES=Integer.MAX_VALUE-ALLOCATION_ALLOWANCE;
    public static final String EXTERNAL_PRIMARY_PROPERTY="top.ceroxe.rt.medium.cuda.opticalExternalPrimary";
    public static final String DIRECT_PRIMARY_PROPERTY="top.ceroxe.rt.medium.cuda.opticalDirectPrimary";
    public static final String REDUCED_COUNTERS_PROPERTY="top.ceroxe.rt.medium.cuda.opticalReducedCounters";
    public static final String COMPACT_HITS_PROPERTY="top.ceroxe.rt.medium.cuda.opticalCompactHits";
    public static final String COMPACT_STATES_PROPERTY="top.ceroxe.rt.medium.cuda.opticalCompactStates";
    public static final String TRANSPOSED_PRIMARY_PROPERTY="top.ceroxe.rt.medium.cuda.opticalTransposedPrimary";
    public static final String TRANSPOSED_STATES_PROPERTY="top.ceroxe.rt.medium.cuda.opticalTransposedStates";
    public static final String HYBRID_VOLUME_STATES_PROPERTY="top.ceroxe.rt.medium.cuda.opticalHybridVolumeStates";
    public static final String TRANSPOSED_HITS_PROPERTY="top.ceroxe.rt.medium.cuda.opticalTransposedHits";
    public static final String MINIMAL_STATE_WRITES_PROPERTY="top.ceroxe.rt.medium.cuda.opticalMinimalStateWrites";
    public static final String FUSED_INITIALIZATION_PROPERTY="top.ceroxe.rt.medium.cuda.opticalFusedInitialization";
    public static final String RESOLVED_PUBLICATION_PROPERTY="top.ceroxe.rt.medium.cuda.opticalResolvedPublication";
    public static final String ACTIVE_QUEUES_PROPERTY="top.ceroxe.rt.medium.cuda.opticalActiveQueues";
    public static final String SKIP_EMPTY_CONNECTIONS_PROPERTY="top.ceroxe.rt.medium.cuda.opticalSkipEmptyConnections";
    public static final String BOUND_CONNECTION_RAYS_PROPERTY="top.ceroxe.rt.medium.cuda.opticalBoundConnectionRays";
    public static final String REUSE_DUPLICATE_BRANCHES_PROPERTY="top.ceroxe.rt.medium.cuda.opticalReuseDuplicateBranches";
    public static final String BATCH_CONNECTION_HITS_PROPERTY="top.ceroxe.rt.medium.cuda.opticalBatchConnectionHits";
    public static final long RESOLVED_RADIANCE_FLAG=1L;
    private CudaOpticalMediumAbi() { }
    public record Plan(int pixels,int samples,int tilePixels,int boundaryCount,int stateOffset,int hitOffset,
            int evaluationOffset,int primaryOffset,int transmittanceOffset,int emissionOffset,int priorityOffset,
            int sceneBytes,long bytes,boolean externalPrimary,boolean directPrimary,boolean compactHits,boolean compactStates,boolean transposedHits,boolean transposedStates,boolean transposedPrimary,
            int connectionOffset,boolean connectionTransport,boolean activeQueues,int connectionBatchSize) {
        public int paths(){return Math.multiplyExact(pixels,samples);}
        public int tilePaths(){return Math.multiplyExact(tilePixels,samples);}
        public long primaryBytes(){return Math.multiplyExact((long)paths(),HIT_BYTES);}
        public int cachedPrimaryPaths(){return externalPrimary?(directPrimary?0:tilePaths()):paths();}
        public int hitBytes(){return compactHits?COMPACT_HIT_BYTES:HIT_BYTES;}
        public int stateBytes(){return compactStates?COMPACT_STATE_BYTES:STATE_BYTES;}
        public int secondConnectionHitOffset(){return connectionBatchSize==2?Math.toIntExact(hitOffset+(long)tilePaths()*hitBytes()):0;}
        public long queueStrideBytes(){return activeQueues?((tilePaths()*4L+15L)&~15L):0L;}
        public long firstQueueOffset(){return activeQueues?emissionOffset+paths()*(long)COLOR_BYTES:0L;}
        public long secondQueueOffset(){return activeQueues?firstQueueOffset()+queueStrideBytes():0L;}
    }
    public static Plan plan(int width,int height,int samples,int boundaries,long residentLimit) {
        return plan(width,height,samples,boundaries,CudaMediumDensityAbi.Scene.empty(),residentLimit);
    }
    public static Plan plan(int width,int height,int samples,int boundaries,CudaMediumDensityAbi.Scene density,long residentLimit) {
        return plan(width,height,samples,boundaries,density,residentLimit,
                Boolean.parseBoolean(System.getProperty(EXTERNAL_PRIMARY_PROPERTY,"true")));
    }
    public static Plan plan(int width,int height,int samples,int boundaries,CudaMediumDensityAbi.Scene density,long residentLimit,
            boolean externalPrimary) {
        return plan(width,height,samples,boundaries,density,residentLimit,externalPrimary,
                externalPrimary&&Boolean.parseBoolean(System.getProperty(DIRECT_PRIMARY_PROPERTY,"true")));
    }
    public static Plan plan(int width,int height,int samples,int boundaries,CudaMediumDensityAbi.Scene density,long residentLimit,
            boolean externalPrimary,boolean directPrimary) {
        return plan(width,height,samples,boundaries,density,residentLimit,externalPrimary,directPrimary,
                Boolean.parseBoolean(System.getProperty(COMPACT_HITS_PROPERTY,"true")));
    }
    public static Plan plan(int width,int height,int samples,int boundaries,CudaMediumDensityAbi.Scene density,long residentLimit,
            boolean externalPrimary,boolean directPrimary,boolean compactHits) {
        return plan(width,height,samples,boundaries,density,residentLimit,externalPrimary,directPrimary,compactHits,
                Boolean.parseBoolean(System.getProperty(COMPACT_STATES_PROPERTY,"true")));
    }
    public static Plan plan(int width,int height,int samples,int boundaries,CudaMediumDensityAbi.Scene density,long residentLimit,
            boolean externalPrimary,boolean directPrimary,boolean compactHits,boolean compactStates) {
        return plan(width,height,samples,boundaries,density,residentLimit,externalPrimary,directPrimary,compactHits,compactStates,
                Boolean.parseBoolean(System.getProperty(TRANSPOSED_HITS_PROPERTY,"true")));
    }
    public static Plan plan(int width,int height,int samples,int boundaries,CudaMediumDensityAbi.Scene density,long residentLimit,
            boolean externalPrimary,boolean directPrimary,boolean compactHits,boolean compactStates,boolean transposedHits) {
        return plan(width,height,samples,boundaries,density,residentLimit,externalPrimary,directPrimary,compactHits,compactStates,transposedHits,
                Boolean.parseBoolean(System.getProperty(TRANSPOSED_STATES_PROPERTY,"true")));
    }
    public static Plan plan(int width,int height,int samples,int boundaries,CudaMediumDensityAbi.Scene density,long residentLimit,
            boolean externalPrimary,boolean directPrimary,boolean compactHits,boolean compactStates,boolean transposedHits,boolean transposedStates) {
        return plan(width,height,samples,boundaries,density,residentLimit,externalPrimary,directPrimary,compactHits,compactStates,transposedHits,transposedStates,
                Boolean.parseBoolean(System.getProperty(TRANSPOSED_PRIMARY_PROPERTY,"true")));
    }
    public static Plan plan(int width,int height,int samples,int boundaries,CudaMediumDensityAbi.Scene density,long residentLimit,
            boolean externalPrimary,boolean directPrimary,boolean compactHits,boolean compactStates,boolean transposedHits,boolean transposedStates,boolean transposedPrimary) {
        return plan(width,height,samples,boundaries,density,residentLimit,externalPrimary,directPrimary,compactHits,compactStates,
                transposedHits,transposedStates,transposedPrimary,false);
    }
    public static Plan plan(int width,int height,int samples,int boundaries,CudaMediumDensityAbi.Scene density,long residentLimit,
            boolean externalPrimary,boolean directPrimary,boolean compactHits,boolean compactStates,boolean transposedHits,boolean transposedStates,boolean transposedPrimary,
            boolean connectionTransport) {
        transposedHits &= compactHits;
        directPrimary &= externalPrimary;
        transposedPrimary &= directPrimary;
        compactStates &= boundaries==0;
        transposedStates &= compactStates||Boolean.parseBoolean(System.getProperty(HYBRID_VOLUME_STATES_PROPERTY,"true"));
        if(width<1||height<1||(samples!=1&&samples!=2&&samples!=4&&samples!=8)||boundaries<0)
            throw new IllegalArgumentException("invalid native optical dimensions or boundary count");
        int pixels=Math.toIntExact((long)width*height),paths=Math.multiplyExact(pixels,samples);
        int priority=Math.toIntExact(HEADER_BYTES+(long)boundaries*BOUNDARY_BYTES);
        int scene=Math.addExact(Math.toIntExact((priority+(long)boundaries*4+15)&~15L),density.bytes());
        long finalBytes=Math.multiplyExact((long)paths,(externalPrimary?0:HIT_BYTES)+2L*COLOR_BYTES);
        if(residentLimit<1)throw new IllegalArgumentException("CUDA resident byte limit must be positive");
        long available=Math.min(MAXIMUM_RESIDENT_BYTES,residentLimit)-ALLOCATION_ALLOWANCE-scene-finalBytes;
        int hitBytes=compactHits?COMPACT_HIT_BYTES:HIT_BYTES;
        int stateBytes=compactStates?COMPACT_STATE_BYTES:STATE_BYTES;
        boolean activeQueues=useActiveQueues(boundaries,connectionTransport);
        int connectionBatchSize=connectionTransport&&!OpticalFrameTimings.KERNEL_PHASES
                &&Boolean.parseBoolean(System.getProperty(BATCH_CONNECTION_HITS_PROPERTY,"true"))?2:1;
        int tilePixels=Math.toIntExact(Math.min(pixels,(available-(activeQueues?32L:0L))/((long)samples*(stateBytes+(long)hitBytes*connectionBatchSize+COLOR_BYTES
                +(externalPrimary&&!directPrimary?HIT_BYTES:0)+(connectionTransport?CONNECTION_BYTES:0)+(activeQueues?8:0)))));
        if(tilePixels<1)throw new IllegalArgumentException("complete native optical frame exceeds the CUDA resident cap");
        int hit=Math.toIntExact(scene+(long)tilePixels*samples*stateBytes);
        int evaluation=Math.toIntExact(hit+(long)tilePixels*samples*hitBytes*connectionBatchSize);
        int connection=Math.toIntExact(evaluation+(long)tilePixels*samples*COLOR_BYTES);
        int primary=Math.toIntExact(connection+(connectionTransport?(long)tilePixels*samples*CONNECTION_BYTES:0));
        int attenuation=Math.toIntExact(primary+(long)(externalPrimary?(directPrimary?0:tilePixels*samples):paths)*HIT_BYTES);
        int emission=Math.toIntExact(attenuation+(long)paths*COLOR_BYTES);
        long bytes=Math.addExact(emission,(long)paths*COLOR_BYTES);
        if(activeQueues)bytes=Math.addExact(bytes,2L*((tilePixels*(long)samples*4L+15L)&~15L));
        return new Plan(pixels,samples,tilePixels,boundaries,scene,hit,evaluation,primary,attenuation,emission,priority,scene,bytes,externalPrimary,directPrimary,compactHits,compactStates,transposedHits,transposedStates,transposedPrimary,connection,connectionTransport,activeQueues,connectionBatchSize);
    }
    public static byte[] scene(Plan plan,RenderFrameRequest frame,List<CudaBoundaryMediumAbi.Boundary> source,int[] containing) {
        return scene(plan,frame,source,containing,CudaMediumDensityAbi.Scene.empty());
    }
    public static byte[] scene(Plan plan,RenderFrameRequest frame,List<CudaBoundaryMediumAbi.Boundary> source,int[] containing,
            CudaMediumDensityAbi.Scene density) {
        byte[] prefix=scenePrefix(plan,frame,source,containing,density);
        int densityBase=prefix.length;
        var packed=ByteBuffer.allocate(plan.sceneBytes()).order(ByteOrder.nativeOrder());
        packed.put(prefix);
        density.write(packed,densityBase);
        return packed.array();
    }
    /** Small per-frame header and boundary table; density descriptors and texels remain immutable assets. */
    public static byte[] scenePrefix(Plan plan,RenderFrameRequest frame,List<CudaBoundaryMediumAbi.Boundary> source,
            int[] containing,CudaMediumDensityAbi.Scene density) {
        if(source.size()!=plan.boundaryCount()||containing.length>32||(plan.compactStates()&&containing.length!=0)||frame.antiAliasing().samplesPerPixel()!=plan.samples())
            throw new IllegalArgumentException("inconsistent native optical frame scene");
        int densityBase=Math.toIntExact((plan.priorityOffset()+(long)source.size()*4+15)&~15L);
        if(Math.addExact(densityBase,density.bytes())!=plan.sceneBytes())
            throw new IllegalArgumentException("optical density scene does not match the storage plan");
        var packed=ByteBuffer.allocate(densityBase).order(ByteOrder.nativeOrder());
        packed.putInt(0,MAGIC).putInt(4,VERSION).putInt(12,plan.samples());
        packed.putInt(24,frame.mediumTransport().maxEvents()).putInt(28,plan.boundaryCount()).putInt(32,containing.length);
        packed.putInt(36,plan.stateOffset()).putInt(40,plan.hitOffset()).putInt(44,plan.evaluationOffset());
        packed.putInt(48,plan.primaryOffset()).putInt(52,plan.transmittanceOffset()).putInt(56,plan.emissionOffset())
                .putInt(60,HEADER_BYTES).putInt(64,plan.priorityOffset());
        packed.putInt(72,plan.connectionOffset()).putInt(76,plan.connectionTransport()?1:0);
        putMedium(packed,80,frame.environment().cameraMedium());
        packed.putFloat(108,frame.mediumTransport().maxDistance()).putFloat(128,frame.mediumTransport().transmittanceCutoff());
        packed.putInt(132,frame.mediumTransport().opticalSamples()).putInt(144,(int)frame.sequence())
                .putInt(148,frame.mediumTransport().firstInterfaceSampling().ordinal());
        packed.putInt(152,descriptor(densityBase,density.environmentOffset()))
                .putInt(156,frame.mediumTransport().scatteringSteps());
        var environmentScatter=frame.environment().cameraMedium().scattering().multiply(frame.environment().cameraMedium().density());
        packed.putFloat(160,environmentScatter.red()).putFloat(164,environmentScatter.green()).putFloat(168,environmentScatter.blue())
                .putFloat(172,frame.environment().cameraMedium().anisotropy()).putInt(176,frame.mediumTransport().scatteringBounces())
                .putInt(180,plan.directPrimary()?1:0)
                .putInt(184,reducedCounters()?1:0).putInt(188,(plan.compactHits()?1:0)|(plan.compactStates()?2:0)|(minimalStateWrites()?4:0)|(plan.transposedHits()?8:0)|(plan.transposedStates()?16:0)|(plan.transposedPrimary()?32:0)|(fusedInitialization()?128:0)|(plan.activeQueues()?256:0)
                        |(Boolean.parseBoolean(System.getProperty(SKIP_EMPTY_CONNECTIONS_PROPERTY,"true"))?512:0)
                        |(Boolean.parseBoolean(System.getProperty(BOUND_CONNECTION_RAYS_PROPERTY,"true"))?2048:0)
                        |(Boolean.parseBoolean(System.getProperty(REUSE_DUPLICATE_BRANCHES_PROPERTY,"true"))?4096:0)
                        |(plan.connectionBatchSize()==2?8192:0));
        packed.putInt(200,Math.toIntExact(plan.firstQueueOffset())).putInt(204,Math.toIntExact(plan.secondQueueOffset()));
        // These words share the opt-in clock sampling area, which disables batching.
        packed.putInt(228,plan.secondConnectionHitOffset()).putInt(232,plan.connectionBatchSize());
        var boundaries=new ArrayList<>(source);boundaries.sort((a,b)->Integer.compareUnsigned(a.slot(),b.slot()));
        var compact=new HashMap<Integer,Integer>();
        for(int index=0;index<boundaries.size();index++) {
            var entry=boundaries.get(index);var boundary=entry.boundary();
            if(!boundary.surfaceInteraction()&&boundary.medium().indexOfRefraction()!=1)
                throw new IllegalArgumentException("IOR-changing boundaries must enable surface interaction");
            if(compact.put(entry.slot(),index)!=null)throw new IllegalArgumentException("duplicate native optical boundary");
            int base=HEADER_BYTES+index*BOUNDARY_BYTES;
            packed.putInt(base,entry.slot()).putInt(base+4,boundary.priority()).putInt(base+8,boundary.orientation().ordinal())
                    .putInt(base+12,boundary.surfaceInteraction()?1:3);
            putMedium(packed,base+16,boundary.medium());packed.putInt(plan.priorityOffset()+index*4,boundary.priority());
            var scatter=boundary.medium().scattering().multiply(boundary.medium().density());
            packed.putFloat(base+44,boundary.medium().anisotropy()).putFloat(base+48,scatter.red())
                    .putFloat(base+52,scatter.green()).putFloat(base+56,scatter.blue())
                    .putInt(base+60,descriptor(densityBase,density.boundaryOffset(entry.slot())));
        }
        var unique=new HashSet<Integer>();
        for(int index=0;index<containing.length;index++) {
            Integer entry=compact.get(containing[index]);
            if(entry==null||!unique.add(entry))throw new IllegalArgumentException("invalid initial native optical medium set");
            packed.putInt(256+index*4,entry);
        }
        updateTile(packed,plan,0,Math.min(plan.pixels(),plan.tilePixels()),0,0);return packed.array();
    }
    public static void updateTile(ByteBuffer data,Plan plan,int firstPixel,int pixels,int opticalSample,int branch) {
        if(firstPixel<0||pixels<1||pixels>plan.tilePixels()||(long)firstPixel+pixels>plan.pixels()
                ||opticalSample<0||opticalSample>=data.getInt(132)||branch<0||branch>1)
            throw new IllegalArgumentException("invalid native optical tile/sample/branch");
        data.putInt(8,pixels*plan.samples()).putInt(16,firstPixel).putInt(20,pixels)
                .putInt(136,opticalSample).putInt(140,branch);
        for(int offset=STATISTICS_OFFSET;offset<STATISTICS_OFFSET+STATISTICS_BYTES;offset+=4)data.putInt(offset,0);
    }
    public static boolean minimalStateWrites(){return Boolean.parseBoolean(System.getProperty(MINIMAL_STATE_WRITES_PROPERTY,"true"));}
    public static boolean fusedInitialization(){return Boolean.parseBoolean(System.getProperty(FUSED_INITIALIZATION_PROPERTY,"true"));}
    public static boolean resolvedPublication(){return Boolean.parseBoolean(System.getProperty(RESOLVED_PUBLICATION_PROPERTY,"true"));}
    public static boolean useActiveQueues(int boundaries,boolean connections){
        // Keep the proven single-tile boundary-free layout within the same cap.
        return (boundaries>0||connections)&&Boolean.parseBoolean(System.getProperty(ACTIVE_QUEUES_PROPERTY,"true"))
                &&reducedCounters()&&!OpticalFrameTimings.KERNEL_PHASES;
    }
    public static boolean reducedCounters() {
        return Boolean.parseBoolean(System.getProperty(REDUCED_COUNTERS_PROPERTY,"true"));
    }
    public static boolean requiresConnections(RenderFrameRequest frame,List<CudaBoundaryMediumAbi.Boundary> boundaries) {
        boolean spatial=!frame.environment().cameraMedium().densityField().isUniform()||!boundaries.isEmpty();
        for(var boundary:boundaries) {
            spatial|=!boundary.boundary().medium().densityField().isUniform();
        }
        return spatial;
    }
    /** Every direct connection can consume the full public boundary-event budget. */
    public static int maximumRounds(RenderFrameRequest frame,int lightUpperBound,boolean connectionTransport) {
        if(lightUpperBound<0)throw new IllegalArgumentException("negative light upper bound");
        long collisions=(long)frame.mediumTransport().scatteringBounces()+1;
        long connections=frame.mediumTransport().lightSamples()==0?lightUpperBound:frame.mediumTransport().lightSamples();
        long rounds=frame.mediumTransport().maxEvents()+2*collisions;
        if(connectionTransport)rounds=Math.addExact(rounds,Math.multiplyExact(collisions+frame.mediumTransport().maxEvents(),
                Math.multiplyExact(connections+1,(long)frame.mediumTransport().maxEvents()+1)));
        return Math.toIntExact(rounds);
    }
    private static void putMedium(ByteBuffer target,int base,Medium medium) {
        var a=medium.absorption().multiply(medium.density());var e=medium.emission().multiply(medium.density());
        target.putFloat(base,a.red()).putFloat(base+4,a.green()).putFloat(base+8,a.blue()).putFloat(base+12,medium.indexOfRefraction());
        target.putFloat(base+16,e.red()).putFloat(base+20,e.green()).putFloat(base+24,e.blue());
    }
    private static int descriptor(int base,int offset) {return offset<0?-1:Math.addExact(base,offset);}
}

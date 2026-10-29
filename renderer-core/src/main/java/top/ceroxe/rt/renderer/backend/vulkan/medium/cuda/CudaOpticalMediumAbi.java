package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import top.ceroxe.rt.renderer.api.Medium;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;

/** Independent non-scattering dielectric continuation; original primary samples remain intact. */
public final class CudaOpticalMediumAbi {
    public static final int MAGIC=0x524f5031,VERSION=2,HEADER_BYTES=384,BOUNDARY_BYTES=64;
    public static final int STATE_BYTES=256,HIT_BYTES=208,COLOR_BYTES=16,STATISTICS_OFFSET=112,STATISTICS_BYTES=16;
    public static final long DEFAULT_RESIDENT_BYTES=512L*1024*1024,ALLOCATION_ALLOWANCE=65536;
    public static final String EXTERNAL_PRIMARY_PROPERTY="top.ceroxe.rt.medium.cuda.opticalExternalPrimary";
    public static final String DIRECT_PRIMARY_PROPERTY="top.ceroxe.rt.medium.cuda.opticalDirectPrimary";
    private CudaOpticalMediumAbi() { }
    public record Plan(int pixels,int samples,int tilePixels,int boundaryCount,int stateOffset,int hitOffset,
            int evaluationOffset,int primaryOffset,int transmittanceOffset,int emissionOffset,int priorityOffset,
            int sceneBytes,long bytes,boolean externalPrimary,boolean directPrimary) {
        public int paths(){return Math.multiplyExact(pixels,samples);}
        public int tilePaths(){return Math.multiplyExact(tilePixels,samples);}
        public long primaryBytes(){return Math.multiplyExact((long)paths(),HIT_BYTES);}
        public int cachedPrimaryPaths(){return externalPrimary?(directPrimary?0:tilePaths()):paths();}
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
        directPrimary &= externalPrimary;
        if(width<1||height<1||(samples!=1&&samples!=2&&samples!=4&&samples!=8)||boundaries<0)
            throw new IllegalArgumentException("invalid native optical dimensions or boundary count");
        int pixels=Math.toIntExact((long)width*height),paths=Math.multiplyExact(pixels,samples);
        int priority=Math.toIntExact(HEADER_BYTES+(long)boundaries*BOUNDARY_BYTES);
        int scene=Math.addExact(Math.toIntExact((priority+(long)boundaries*4+15)&~15L),density.bytes());
        long finalBytes=Math.multiplyExact((long)paths,(externalPrimary?0:HIT_BYTES)+2L*COLOR_BYTES);
        long available=Math.min(DEFAULT_RESIDENT_BYTES,residentLimit)-ALLOCATION_ALLOWANCE-scene-finalBytes;
        int tilePixels=Math.toIntExact(Math.min(pixels,available/((long)samples*(STATE_BYTES+HIT_BYTES+COLOR_BYTES+(externalPrimary&&!directPrimary?HIT_BYTES:0)))));
        if(tilePixels<1)throw new IllegalArgumentException("complete native optical frame exceeds the CUDA resident cap");
        int hit=Math.toIntExact(scene+(long)tilePixels*samples*STATE_BYTES);
        int evaluation=Math.toIntExact(hit+(long)tilePixels*samples*HIT_BYTES);
        int primary=Math.toIntExact(evaluation+(long)tilePixels*samples*COLOR_BYTES);
        int attenuation=Math.toIntExact(primary+(long)(externalPrimary?(directPrimary?0:tilePixels*samples):paths)*HIT_BYTES);
        int emission=Math.toIntExact(attenuation+(long)paths*COLOR_BYTES);
        long bytes=Math.addExact(emission,(long)paths*COLOR_BYTES);
        return new Plan(pixels,samples,tilePixels,boundaries,scene,hit,evaluation,primary,attenuation,emission,priority,scene,bytes,externalPrimary,directPrimary);
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
        if(source.size()!=plan.boundaryCount()||containing.length>32||frame.antiAliasing().samplesPerPixel()!=plan.samples())
            throw new IllegalArgumentException("inconsistent native optical frame scene");
        requireMedium(frame.environment().cameraMedium());
        int densityBase=Math.toIntExact((plan.priorityOffset()+(long)source.size()*4+15)&~15L);
        if(Math.addExact(densityBase,density.bytes())!=plan.sceneBytes())
            throw new IllegalArgumentException("optical density scene does not match the storage plan");
        var packed=ByteBuffer.allocate(densityBase).order(ByteOrder.nativeOrder());
        packed.putInt(0,MAGIC).putInt(4,VERSION).putInt(12,plan.samples());
        packed.putInt(24,frame.mediumTransport().maxEvents()).putInt(28,plan.boundaryCount()).putInt(32,containing.length);
        packed.putInt(36,plan.stateOffset()).putInt(40,plan.hitOffset()).putInt(44,plan.evaluationOffset());
        packed.putInt(48,plan.primaryOffset()).putInt(52,plan.transmittanceOffset()).putInt(56,plan.emissionOffset())
                .putInt(60,HEADER_BYTES).putInt(64,plan.priorityOffset());
        putMedium(packed,80,frame.environment().cameraMedium());
        packed.putFloat(108,frame.mediumTransport().maxDistance()).putFloat(128,frame.mediumTransport().transmittanceCutoff());
        packed.putInt(132,frame.mediumTransport().opticalSamples()).putInt(144,(int)frame.sequence())
                .putInt(148,frame.mediumTransport().firstInterfaceSampling().ordinal());
        packed.putInt(152,descriptor(densityBase,density.environmentOffset()))
                .putInt(156,frame.mediumTransport().scatteringSteps());
        var environmentScatter=frame.environment().cameraMedium().scattering().multiply(frame.environment().cameraMedium().density());
        packed.putFloat(160,environmentScatter.red()).putFloat(164,environmentScatter.green()).putFloat(168,environmentScatter.blue())
                .putFloat(172,frame.environment().cameraMedium().anisotropy()).putInt(176,frame.mediumTransport().scatteringBounces())
                .putInt(180,plan.directPrimary()?1:0);
        var boundaries=new ArrayList<>(source);boundaries.sort((a,b)->Integer.compareUnsigned(a.slot(),b.slot()));
        var compact=new HashMap<Integer,Integer>();
        for(int index=0;index<boundaries.size();index++) {
            var entry=boundaries.get(index);var boundary=entry.boundary();requireMedium(boundary.medium());
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
    private static void putMedium(ByteBuffer target,int base,Medium medium) {
        var a=medium.absorption().multiply(medium.density());var e=medium.emission().multiply(medium.density());
        target.putFloat(base,a.red()).putFloat(base+4,a.green()).putFloat(base+8,a.blue()).putFloat(base+12,medium.indexOfRefraction());
        target.putFloat(base+16,e.red()).putFloat(base+20,e.green()).putFloat(base+24,e.blue());
    }
    private static int descriptor(int base,int offset) {return offset<0?-1:Math.addExact(base,offset);}
    public static void requireMedium(Medium medium) {
        if(medium.participating()&&!medium.scattering().isZero()&&!medium.densityField().isUniform())
            throw new IllegalArgumentException("native optical scattering requires a homogeneous camera medium");
    }
}

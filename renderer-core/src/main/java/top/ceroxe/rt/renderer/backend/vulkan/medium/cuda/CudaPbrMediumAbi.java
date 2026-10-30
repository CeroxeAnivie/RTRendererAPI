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
import top.ceroxe.rt.renderer.rt.diagnostics.OpticalDetailedProfile;

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
    public record CombinedPlan(Plan pbr,CudaCameraScatteringAbi.Plan scattering) { }
    public enum ScatteringKind { DIRECT_UNIFORM, DIRECT_DENSITY, MULTIPLE_UNIFORM, MULTIPLE_DENSITY }
    /** A single resident budget governs both existing integrators, including both density atlases. */
    public record TransportPlan(Plan pbr,ScatteringKind kind,CudaCameraScatteringAbi.Plan direct,
            CudaDensityScatteringAbi.FrameScene densityDirect,CudaMultipleScatteringAbi.Plan multiple,
            CudaHeterogeneousMultipleScatteringAbi.FrameScene densityMultiple,long scatteringBytes) {
        public TransportPlan {
            Objects.requireNonNull(pbr,"pbr");Objects.requireNonNull(kind,"kind");
            int present=(direct==null?0:1)+(densityDirect==null?0:1)+(multiple==null?0:1)+(densityMultiple==null?0:1);
            if(present!=1)throw new IllegalArgumentException("one native scattering integrator must own a PBR contribution");
            int pixels,samples,tile;long bytes;
            switch(kind) {
                case DIRECT_UNIFORM -> {Objects.requireNonNull(direct,"direct");pixels=direct.pixels();samples=direct.samples();tile=direct.tilePixels();bytes=direct.bytes();}
                case DIRECT_DENSITY -> {Objects.requireNonNull(densityDirect,"densityDirect");var p=densityDirect.plan();pixels=p.pixels();samples=p.samples();tile=p.tilePixels();bytes=p.bytes();}
                case MULTIPLE_UNIFORM -> {Objects.requireNonNull(multiple,"multiple");pixels=multiple.pixels();samples=multiple.samples();tile=multiple.tilePixels();bytes=multiple.bytes();}
                case MULTIPLE_DENSITY -> {Objects.requireNonNull(densityMultiple,"densityMultiple");var p=densityMultiple.plan();pixels=p.pixels();samples=p.samples();tile=p.tilePixels();bytes=p.bytes();}
                default -> throw new IllegalArgumentException("unrecognized PBR scattering integrator");
            }
            long diagnostic=kind==ScatteringKind.DIRECT_UNIFORM
                    ?OpticalDetailedProfile.cameraScatteringShaderBytes(Math.multiplyExact(pixels,samples)):0;
            if(pbr.pixels()!=pixels||pbr.samples()!=samples||pbr.tilePixels()!=tile
                    ||scatteringBytes!=Math.addExact(Math.addExact(bytes,diagnostic),ALLOCATION_ALLOWANCE))
                throw new IllegalArgumentException("PBR/scattering contribution plan must share complete outputs, tile and residency");
        }
        public int scatteringTilePixels() {return switch(kind) {
            case DIRECT_UNIFORM -> direct.tilePixels();
            case DIRECT_DENSITY -> densityDirect.plan().tilePixels();
            case MULTIPLE_UNIFORM -> multiple.tilePixels();
            case MULTIPLE_DENSITY -> densityMultiple.plan().tilePixels();
        };}
        public long residentBytes(){return Math.addExact(pbr.bytes()+ALLOCATION_ALLOWANCE,scatteringBytes);}
        public boolean tileLocalPrimary(){return kind!=ScatteringKind.DIRECT_UNIFORM;}
        public int primaryOffset(){return switch(kind) {
            case DIRECT_UNIFORM -> direct.primaryOffset();
            case DIRECT_DENSITY -> densityDirect.plan().primaryOffset();
            case MULTIPLE_UNIFORM -> multiple.primaryOffset();
            case MULTIPLE_DENSITY -> densityMultiple.plan().primaryOffset();
        };}
        public long primaryTileBytes(){return tileLocalPrimary()?Math.multiplyExact((long)pbr.tilePaths(),PRIMARY_BYTES):0;}
    }
    /** Density is resident in both native allocations; reserve all complete outputs before conversion. */
    public static long scatteringDensityBudget(RenderFrameRequest frame,int width,int height,int lights,int capacity,long residentLimit) {
        int samples=frame.antiAliasing().samplesPerPixel(),steps=frame.mediumTransport().scatteringSteps();
        dimensions(width,height,samples,0,lights,capacity);
        if(steps<1||steps>64)throw new IllegalArgumentException("scattering steps must be within [1, 64]");
        boolean multiple=frame.mediumTransport().scatteringBounces()!=0;
        int connections=(int)Math.min(CudaDensityScatteringAbi.CONNECTIONS,1L+(frame.mediumTransport().lightSamples()==0
                ?lights:Math.min(lights,frame.mediumTransport().lightSamples())));
        long paths=Math.multiplyExact(Math.multiplyExact((long)width,height),samples);
        long scatteringFixed=Math.addExact(multiple?CudaHeterogeneousMultipleScatteringAbi.HEADER_BYTES:CudaDensityScatteringAbi.HEADER_BYTES,
                Math.multiplyExact(paths,32));
        long scratch=Math.addExact(Math.multiplyExact((long)samples,PRIMARY_BYTES),
                multiple?Math.multiplyExact((long)samples*steps,144L+connections*32L)
                :Math.multiplyExact((long)samples,32L+steps*(32L+connections*32L)));
        long minimum=Math.addExact(2*ALLOCATION_ALLOWANCE,Math.addExact(minimumBytes(width,height,samples,0,lights,capacity),
                Math.addExact(scatteringFixed,scratch)));
        if(residentLimit<minimum)throw new IllegalArgumentException("complete PBR/scattering outputs exceed residency before density conversion");
        return (residentLimit-minimum)/2;
    }
    public static TransportPlan planWithScattering(RenderFrameRequest frame,int width,int height,int lights,int capacity,
            CudaMediumDensityAbi.Scene density,long residentLimit) {
        int samples=frame.antiAliasing().samplesPerPixel(),steps=frame.mediumTransport().scatteringSteps();
        boolean uniform=frame.environment().cameraMedium().densityField().isUniform();
        boolean multiple=frame.mediumTransport().scatteringBounces()!=0;
        Objects.requireNonNull(density,"density");
        if(uniform?density.bytes()!=0:density.environmentOffset()<0)
            throw new IllegalArgumentException("PBR scattering density descriptors do not match the camera medium");
        if(uniform&&!multiple) {
            var combined=planWithScattering(width,height,samples,lights,capacity,steps,residentLimit);
            return new TransportPlan(combined.pbr(),ScatteringKind.DIRECT_UNIFORM,combined.scattering(),null,null,null,
                    Math.addExact(Math.addExact(combined.scattering().bytes(),
                            OpticalDetailedProfile.cameraScatteringShaderBytes(combined.scattering().paths())),ALLOCATION_ALLOWANCE));
        }
        dimensions(width,height,samples,0,lights,capacity);
        if(steps<1||steps>64)throw new IllegalArgumentException("scattering steps must be within [1, 64]");
        long paths=Math.multiplyExact(Math.multiplyExact((long)width,height),samples);
        long pbrFixed=Math.addExact(HEADER_BYTES+(long)density.bytes(),Math.multiplyExact(paths,32));
        long pbrScratch=minimumBytes(width,height,samples,0,lights,capacity)-HEADER_BYTES-paths*32;
        int connections=(int)Math.min(CudaDensityScatteringAbi.CONNECTIONS,1L+(frame.mediumTransport().lightSamples()==0
                ?lights:Math.min(lights,frame.mediumTransport().lightSamples())));
        int scatteringHeader=uniform?CudaMultipleScatteringAbi.HEADER_BYTES:multiple
                ?CudaHeterogeneousMultipleScatteringAbi.HEADER_BYTES:CudaDensityScatteringAbi.HEADER_BYTES;
        long scatteringFixed=Math.addExact(scatteringHeader+(long)density.bytes(),Math.multiplyExact(paths,32));
        long scatteringScratch=Math.addExact(Math.multiplyExact((long)samples,PRIMARY_BYTES),uniform?Math.multiplyExact((long)samples*steps,160)
                :multiple?Math.multiplyExact((long)samples*steps,144L+connections*32L)
                :Math.multiplyExact((long)samples,32L+steps*(32L+connections*32L)));
        long fixed=Math.addExact(2*ALLOCATION_ALLOWANCE,Math.addExact(pbrFixed,scatteringFixed));
        long tile=Math.min(Math.min((long)width*height,(residentLimit-fixed)/Math.addExact(pbrScratch,scatteringScratch)),
                Integer.MAX_VALUE/((long)lights+1));
        if(residentLimit<fixed||tile<1)throw new IllegalArgumentException("complete PBR/scattering outputs and one pixel scratch exceed residency");
        var pbr=plan(width,height,samples,0,lights,capacity,density,pbrFixed+tile*pbrScratch);
        // Complete primary geometry belongs to Vulkan/PBR. Contributions retain only
        // the current wavefront tile, after the two complete-frame RGB outputs.
        int trans=Math.addExact(scatteringHeader,density.bytes());
        int emission=Math.toIntExact(trans+paths*COLOR_BYTES),primary=Math.toIntExact(emission+paths*COLOR_BYTES);
        int scratchBase=Math.toIntExact(primary+tile*samples*PRIMARY_BYTES);
        if(uniform) {
            int state=Math.toIntExact(scratchBase+tile*samples*steps*CudaMultipleScatteringAbi.SOURCE_BYTES);
            long bytes=Math.addExact(state,tile*samples*steps*CudaMultipleScatteringAbi.STATE_BYTES);
            var plan=new CudaMultipleScatteringAbi.Plan(Math.multiplyExact(width,height),samples,steps,Math.toIntExact(tile),
                    primary,trans,emission,scratchBase,state,bytes);
            return new TransportPlan(pbr,ScatteringKind.MULTIPLE_UNIFORM,null,null,plan,null,plan.bytes()+ALLOCATION_ALLOWANCE);
        }
        if(!multiple) {
            int sample=Math.toIntExact(scratchBase+tile*samples*CudaDensityScatteringAbi.RAY_BYTES);
            int connection=Math.toIntExact(sample+tile*samples*steps*CudaDensityScatteringAbi.SAMPLE_BYTES);
            long bytes=Math.addExact(connection,tile*samples*steps*connections*CudaDensityScatteringAbi.CONNECTION_BYTES);
            var plan=new CudaDensityScatteringAbi.Plan(Math.multiplyExact(width,height),samples,steps,Math.toIntExact(tile),
                    CudaDensityScatteringAbi.HEADER_BYTES,primary,trans,emission,scratchBase,sample,connection,connections,bytes);
            var scene=new CudaDensityScatteringAbi.FrameScene(frame,plan,density,lights);
            return new TransportPlan(pbr,ScatteringKind.DIRECT_DENSITY,null,scene,null,null,plan.bytes()+ALLOCATION_ALLOWANCE);
        }
        int state=scratchBase;
        long secondary=tile*samples*steps;int connection=Math.toIntExact(state+secondary*144);
        long bytes=Math.addExact(connection,secondary*connections*32);
        var plan=new CudaHeterogeneousMultipleScatteringAbi.Plan(Math.multiplyExact(width,height),samples,steps,Math.toIntExact(tile),
                primary,trans,emission,state,connection,connections,bytes);
        var scene=new CudaHeterogeneousMultipleScatteringAbi.FrameScene(frame,plan,density,lights);
        return new TransportPlan(pbr,ScatteringKind.MULTIPLE_DENSITY,null,null,null,scene,bytes+ALLOCATION_ALLOWANCE);
    }
    /** Both native allocations use the same tile size and share one complete-frame budget. */
    public static CombinedPlan planWithScattering(int width,int height,int samples,int lights,int capacity,int steps,long residentLimit) {
        dimensions(width,height,samples,0,lights,capacity);
        if(steps<1||steps>64)throw new IllegalArgumentException("scattering steps must be within [1, 64]");
        long paths=Math.multiplyExact(Math.multiplyExact((long)width,height),samples);
        long pbrFixed=Math.addExact(HEADER_BYTES,Math.multiplyExact(paths,32));
        long scatteringFixed=Math.addExact(CudaCameraScatteringAbi.HEADER_BYTES,Math.multiplyExact(paths,32));
        long pbrScratch=minimumBytes(width,height,samples,0,lights,capacity)-pbrFixed;
        long scatteringScratch=Math.multiplyExact((long)samples,Math.multiplyExact((long)steps+1,16));
        long fixed=Math.addExact(2*ALLOCATION_ALLOWANCE,Math.addExact(pbrFixed,scatteringFixed));
        if(residentLimit<fixed)throw new IllegalArgumentException("complete PBR scattering output exceeds residency");
        long available=residentLimit-fixed;
        long tile=Math.min(Math.min((long)width*height,available/Math.addExact(pbrScratch,scatteringScratch)),
                Integer.MAX_VALUE/((long)lights+1));
        long diagnostic=OpticalDetailedProfile.cameraScatteringShaderBytes(Math.toIntExact(paths));
        for(int attempt=0;diagnostic>0&&attempt<8;attempt++){
            long adjusted=(available-diagnostic)/Math.addExact(pbrScratch,scatteringScratch);
            tile=Math.min(tile,adjusted);
            long next=OpticalDetailedProfile.cameraScatteringShaderBytes(Math.toIntExact(paths));
            if(next==diagnostic)break;
            diagnostic=next;
        }
        diagnostic=OpticalDetailedProfile.cameraScatteringShaderBytes(Math.toIntExact(paths));
        if(Math.addExact(Math.multiplyExact(tile,Math.addExact(pbrScratch,scatteringScratch)),diagnostic)>available)
            throw new IllegalArgumentException("camera-scattering shader diagnostics exceed combined residency");
        if(tile<1)throw new IllegalArgumentException("complete PBR scattering outputs and one pixel's scratch exceed residency");
        var pbr=plan(width,height,samples,0,lights,capacity,CudaMediumDensityAbi.Scene.empty(),
                Math.addExact(pbrFixed,Math.multiplyExact(tile,pbrScratch)));
        var scattering=CudaCameraScatteringAbi.contributionPlan(width,height,samples,steps,
                Math.addExact(ALLOCATION_ALLOWANCE,Math.addExact(scatteringFixed,Math.multiplyExact(tile,scatteringScratch))));
        return new CombinedPlan(pbr,scattering);
    }

    public static long minimumBytes(int width,int height,int samples,int boundaries,int lightUpperBound,int capacity) {
        dimensions(width,height,samples,boundaries,lightUpperBound,capacity);
        long paths=Math.multiplyExact(Math.multiplyExact((long)width,height),samples);
        long scene=align16(Math.toIntExact(HEADER_BYTES+(long)boundaries*68));
        int eventCapacity=boundaries==0?0:capacity;
        long scratch=Math.multiplyExact((long)samples,Math.multiplyExact((long)lightUpperBound+2,32+16*((eventCapacity+1)/2)+32));
        return Math.addExact(Math.addExact(scene,Math.multiplyExact(paths,32)),scratch);
    }

    public static Plan plan(int width,int height,int samples,int boundaries,int lightUpperBound,int capacity,
            CudaMediumDensityAbi.Scene density,long storageLimit) {
        dimensions(width,height,samples,boundaries,lightUpperBound,capacity);
        int pixels=Math.toIntExact(Math.multiplyExact((long)width,height));
        int paths=Math.multiplyExact(pixels,samples),connections=Math.addExact(lightUpperBound,1);
        int scene=Math.addExact(align16(Math.toIntExact(HEADER_BYTES+(long)boundaries*68)),density.bytes());
        int trans=scene,emission=Math.toIntExact(trans+(long)paths*16),state=Math.toIntExact(emission+(long)paths*16);
        // No interface can produce an event in a scene with no boundaries.
        // Keep the public transport budget; remove only unused wire storage.
        int eventCapacity=boundaries==0?0:capacity;
        int packetBytes=32+16*((eventCapacity+1)/2);
        long perPixel=Math.multiplyExact((long)samples,Math.multiplyExact((long)connections+1,packetBytes+32));
        long available=storageLimit-state;
        if(available<perPixel)throw new IllegalArgumentException("complete native PBR output and one pixel's light packets exceed residency");
        // Dispatch width is bounded independently of the storage arithmetic.
        int tile=(int)Math.min(Math.min((long)pixels,available/perPixel),Integer.MAX_VALUE/(long)connections);
        if(tile<1)throw new IllegalArgumentException("native PBR light dispatch exceeds uint indexing");
        int colors=Math.toIntExact(state+Math.multiplyExact(Math.multiplyExact((long)tile*samples,connections+1L),packetBytes));
        long bytes=Math.addExact(colors,Math.multiplyExact(Math.multiplyExact((long)tile*samples,connections+1L),32));
        return new Plan(pixels,samples,tile,boundaries,connections,eventCapacity,scene,state,colors,trans,emission,bytes);
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
        var cameraMedium=frame.environment().cameraMedium();
        putMedium(b,64,cameraMedium);
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
        updateTile(b,p,first,count,0L);
    }
    public static void updateTile(ByteBuffer b,Plan p,int first,int count,long additionalRadianceOffset) {
        updateTile(b,p,first,count,additionalRadianceOffset,false);
    }
    public static void updateTile(ByteBuffer b,Plan p,int first,int count,long additionalRadianceOffset,boolean includesCameraEmission) {
        if(first<0||count<1||count>p.tilePixels()||(long)first+count>p.pixels())throw new IllegalArgumentException("invalid PBR tile");
        int paths=Math.multiplyExact(count,p.samples());
        b.putInt(0,CudaBoundaryMediumAbi.MAGIC).putInt(4,1).putInt(8,paths).putInt(12,p.samples()).putInt(16,first).putInt(20,count);
        // Header byte 40 is the packet base consumed by the light query shader; both
        // camera and connection packets share one tile-local state array.
        b.putInt(36,p.stateOffset()).putInt(40,p.stateOffset()).putInt(44,Math.toIntExact(p.transmittanceOffset()+(long)first*p.samples()*16))
                .putInt(48,Math.toIntExact(p.emissionOffset()+(long)first*p.samples()*16));
        b.putInt(108,p.capacity()).putInt(112,paths).putInt(116,0).putInt(120,0).putInt(124,0);
        b.putInt(256,p.connections()).putInt(260,p.colorsOffset()).putInt(264,paths).putInt(268,0);
        b.putLong(272,additionalRadianceOffset);
        b.putInt(280,includesCameraEmission?1:0);
    }
    private static int descriptor(int offset,int base){return offset<0?-1:Math.addExact(offset,base);}
    private static void putMedium(ByteBuffer b,int base,Medium medium) {
        Objects.requireNonNull(medium,"medium");
        var a=medium.extinction().multiply(medium.density());var e=medium.emission().multiply(medium.density());
        b.putFloat(base,a.red()).putFloat(base+4,a.green()).putFloat(base+8,a.blue()).putFloat(base+12,1);
        b.putFloat(base+16,e.red()).putFloat(base+20,e.green()).putFloat(base+24,e.blue());
    }
    private static int align16(int size){return Math.toIntExact((size+15L)&~15L);}
    private static void dimensions(int w,int h,int samples,int boundaries,int lights,int capacity) {
        if(w<1||h<1||samples<1||samples>8||boundaries<0||lights<0||capacity<1||capacity>32)
            throw new IllegalArgumentException("invalid native PBR frame extent or packet capacity");
    }
}

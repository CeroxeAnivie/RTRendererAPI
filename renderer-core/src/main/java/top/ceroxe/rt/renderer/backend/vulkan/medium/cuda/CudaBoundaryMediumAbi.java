package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import top.ceroxe.rt.renderer.api.Medium;
import top.ceroxe.rt.renderer.api.MediumBoundary;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.AffineTransform;

/** One exported allocation holds bounded continuation state and the complete final primary output. */
public final class CudaBoundaryMediumAbi {
    public static final int MAGIC = 0x52564d31, VERSION = 1;
    public static final int HEADER_BYTES = 256, BOUNDARY_BYTES = 64, PATH_BYTES = 272;
    public static final int PRIMARY_BYTES = 208, COLOR_BYTES = 16, MAX_TILE_PATHS = 65_536;
    public static final int PACKET_PATH_BYTES = 288;
    public static final int PACKET_LAYOUT_OFFSET = 108, PACKET_TERMINAL_RADIANCE = 512;
    public static final int INITIAL_VOLUMES_OFFSET = 128, STACK_OFFSET = 128;
    public static final int STATISTICS_OFFSET = 112, STATISTICS_BYTES = 16;
    public static final int ACTIVE = 0, FAILED = 4, CROSSED = 8, COMPLETED = 12;
    public static final int STATUS_OK = 0, STATUS_EPOCH = 100, STATUS_BAD_HIT = 101,
            STATUS_UNKNOWN_BOUNDARY = 102, STATUS_STACK = 103, STATUS_DUPLICATE_ENTRY = 104,
            STATUS_UNSUPPORTED_SURFACE = 105, STATUS_BAD_HEADER = 106;

    private CudaBoundaryMediumAbi() { }

    public record Boundary(int slot, MediumBoundary boundary, AffineTransform objectToWorld) {
        public Boundary(int slot, MediumBoundary boundary) { this(slot,boundary,AffineTransform.identity()); }
        public Boundary { Objects.requireNonNull(boundary, "boundary"); Objects.requireNonNull(objectToWorld,"objectToWorld"); }
    }

    /** All offsets fit the shader's uint byte offsets; total storage remains separately budgeted. */
    public record Plan(int pixels, int samples, int tilePixels, int boundaryCount,
                       int stateOffset, int primaryOffset, int transmittanceOffset,
                       int emissionOffset, int priorityOffset, int sceneBytes, long bytes, int packetCapacity) {
        public Plan(int pixels,int samples,int tilePixels,int boundaryCount,int stateOffset,int primaryOffset,
                int transmittanceOffset,int emissionOffset,int priorityOffset,int sceneBytes,long bytes) {
            this(pixels,samples,tilePixels,boundaryCount,stateOffset,primaryOffset,transmittanceOffset,
                    emissionOffset,priorityOffset,sceneBytes,bytes,0);
        }
        public int packetPathBytes() { return packetCapacity==0?0:32+16*((packetCapacity+1)/2); }
        public int paths() { return Math.multiplyExact(pixels, samples); }
        public int tilePaths() { return Math.multiplyExact(tilePixels, samples); }
        public int tilePrimaryOffset(int firstPixel) { return offset(primaryOffset, firstPixel, PRIMARY_BYTES); }
        public boolean packet() {
            return packetCapacity>0;
        }
        public int tilePrimaryScratchOffset(int firstPixel) {
            if (firstPixel < 0 || firstPixel >= pixels) throw new IllegalArgumentException("tile pixel is outside the frame");
            return packet()?stateOffset:offset(primaryOffset,firstPixel,PRIMARY_BYTES);
        }
        public int tileTransmittanceOffset(int firstPixel) { return offset(transmittanceOffset, firstPixel, COLOR_BYTES); }
        public int tileEmissionOffset(int firstPixel) { return offset(emissionOffset, firstPixel, COLOR_BYTES); }
        private int offset(int base, int firstPixel, int stride) {
            if (firstPixel < 0 || firstPixel >= pixels) throw new IllegalArgumentException("tile pixel is outside the frame");
            return Math.toIntExact(base + Math.multiplyExact((long) firstPixel * samples, stride));
        }
    }

    public static Plan plan(int width, int height, int samples, int boundaryCount) {
        return plan(width,height,samples,boundaryCount,CudaMediumDensityAbi.Scene.empty());
    }

    public static Plan plan(int width, int height, int samples, int boundaryCount, CudaMediumDensityAbi.Scene density) {
        requireDimensions(width,height,samples,boundaryCount);
        return planTiles(width,height,samples,boundaryCount,density,MAX_TILE_PATHS/samples);
    }

    /**
     * Fit as many continuation states as possible beside the complete final-frame arrays.
     * The owner has already removed allocation alignment/residency overhead from this limit.
     */
    public static Plan plan(int width, int height, int samples, int boundaryCount,
                            CudaMediumDensityAbi.Scene density, long storageByteLimit) {
        long fixed=minimumBytes(width,height,samples,boundaryCount)-((long)samples*PATH_BYTES);
        fixed=Math.addExact(fixed,Objects.requireNonNull(density,"density").bytes());
        long available=storageByteLimit-fixed;
        if(available<(long)samples*PATH_BYTES)
            throw new IllegalArgumentException("complete native boundary output and one pixel state exceed residency");
        int tilePixels=(int)Math.min((long)width*height,available/((long)samples*PATH_BYTES));
        return planTiles(width,height,samples,boundaryCount,density,tilePixels);
    }

    /** Packet path: the full primary payload is Vulkan-owned; CUDA keeps only one tile scratch. */
    public static Plan planPacket(int width,int height,int samples,int boundaryCount,
                                  CudaMediumDensityAbi.Scene density,long storageByteLimit) {
        return planPacket(width,height,samples,boundaryCount,density,storageByteLimit,32);
    }

    public static Plan planPacket(int width,int height,int samples,int boundaryCount,
            CudaMediumDensityAbi.Scene density,long storageByteLimit,int capacity) {
        requireDimensions(width,height,samples,boundaryCount);
        if(capacity<1||capacity>32)throw new IllegalArgumentException("packet capacity must be within [1,32]");
        int packetBytes=32+16*((capacity+1)/2);
        Objects.requireNonNull(density,"density");
        int pixels=Math.toIntExact(Math.multiplyExact((long)width,height));
        long paths=Math.multiplyExact((long)pixels,samples);
        long scene=align16(Math.toIntExact(HEADER_BYTES+(long)boundaryCount*(BOUNDARY_BYTES+Integer.BYTES)))+density.bytes();
        long output=Math.addExact(scene,Math.multiplyExact(paths,2L*COLOR_BYTES));
        long available=storageByteLimit-output;
        if(available<(long)samples*packetBytes) throw new IllegalArgumentException("packet boundary state exceeds residency");
        int tilePixels=(int)Math.min((long)pixels,available/((long)samples*packetBytes));
        int primary=Math.toIntExact(scene+Math.multiplyExact((long)tilePixels*samples,PATH_BYTES));
        int trans=Math.toIntExact(scene+Math.multiplyExact((long)tilePixels*samples,packetBytes));
        int emission=Math.toIntExact(trans+Math.multiplyExact(paths,COLOR_BYTES));
        long bytes=Math.addExact(emission,Math.multiplyExact(paths,COLOR_BYTES));
        int priority=HEADER_BYTES+boundaryCount*BOUNDARY_BYTES;
        return new Plan(pixels,samples,tilePixels,boundaryCount,Math.toIntExact(scene),primary,trans,emission,priority,
                Math.toIntExact(scene),bytes,capacity);
    }

    public static long minimumPacketBytes(int width,int height,int samples,int boundaryCount) {
        return minimumPacketBytes(width,height,samples,boundaryCount,32);
    }

    public static long minimumPacketBytes(int width,int height,int samples,int boundaryCount,int capacity) {
        if(capacity<1||capacity>32)throw new IllegalArgumentException("packet capacity must be within [1,32]");
        int packetBytes=32+16*((capacity+1)/2);
        requireDimensions(width,height,samples,boundaryCount);
        long paths=Math.multiplyExact(Math.multiplyExact((long)width,height),samples);
        long scene=align16(Math.toIntExact(HEADER_BYTES+(long)boundaryCount*(BOUNDARY_BYTES+Integer.BYTES)));
        return Math.addExact(scene,Math.addExact((long)samples*packetBytes,
                Math.multiplyExact(paths,2L*COLOR_BYTES)));
    }

    /** Minimum complete-frame storage, before optional density data and allocation overhead. */
    public static long minimumBytes(int width,int height,int samples,int boundaryCount) {
        requireDimensions(width,height,samples,boundaryCount);
        long paths=Math.multiplyExact(Math.multiplyExact((long)width,height),samples);
        long scene=align16(Math.toIntExact(HEADER_BYTES+(long)boundaryCount*(BOUNDARY_BYTES+Integer.BYTES)));
        return Math.addExact(scene,Math.addExact((long)samples*PATH_BYTES,
                Math.multiplyExact(paths,PRIMARY_BYTES+2L*COLOR_BYTES)));
    }

    private static void requireDimensions(int width,int height,int samples,int boundaryCount) {
        if (width < 1 || height < 1 || samples < 1 || samples > 8 || boundaryCount < 0)
            throw new IllegalArgumentException("positive extent, 1..8 samples and nonnegative boundary count required");
    }

    private static Plan planTiles(int width,int height,int samples,int boundaryCount,
                                   CudaMediumDensityAbi.Scene density,int maximumTilePixels) {
        requireDimensions(width,height,samples,boundaryCount);
        int pixels = Math.toIntExact(Math.multiplyExact((long) width, height));
        int paths = Math.multiplyExact(pixels, samples), tilePixels = Math.min(pixels, maximumTilePixels);
        if(tilePixels<1) throw new IllegalArgumentException("a continuation tile must retain every sample of one pixel");
        int priority = Math.toIntExact(HEADER_BYTES + (long) boundaryCount * BOUNDARY_BYTES);
        int densityOffset = align16(Math.addExact(priority, Math.multiplyExact(boundaryCount, Integer.BYTES)));
        int scene = Math.addExact(densityOffset,Objects.requireNonNull(density,"density").bytes());
        int primary = Math.toIntExact(scene + (long) tilePixels * samples * PATH_BYTES);
        int transmittance = Math.toIntExact(primary + (long) paths * PRIMARY_BYTES);
        int emission = Math.toIntExact(transmittance + (long) paths * COLOR_BYTES);
        long bytes = Math.addExact(emission, (long) paths * COLOR_BYTES);
        return new Plan(pixels, samples, tilePixels, boundaryCount, scene, primary, transmittance,
                emission, priority, scene, bytes);
    }

    /** Header/table upload only: no ray, hit or per-path state crosses the CPU. */
    public static byte[] scene(Plan plan, RenderFrameRequest frame, List<Boundary> source,
                               int[] containingSlots, int firstPixel, int pixelCount) {
        return scene(plan,frame,source,containingSlots,firstPixel,pixelCount,CudaMediumDensityAbi.Scene.empty());
    }

    public static byte[] scene(Plan plan, RenderFrameRequest frame, List<Boundary> source,
                               int[] containingSlots, int firstPixel, int pixelCount, CudaMediumDensityAbi.Scene density) {
        return packScene(plan,frame,source,containingSlots,firstPixel,pixelCount,density,true);
    }

    /** Small frame metadata; immutable descriptor/atlas bytes are copied only on an asset change. */
    public static byte[] scenePrefix(Plan plan,RenderFrameRequest frame,List<Boundary> source,
            int[] containingSlots,int firstPixel,int pixelCount,CudaMediumDensityAbi.Scene density) {
        return packScene(plan,frame,source,containingSlots,firstPixel,pixelCount,density,false);
    }

    private static byte[] packScene(Plan plan,RenderFrameRequest frame,List<Boundary> source,
            int[] containingSlots,int firstPixel,int pixelCount,CudaMediumDensityAbi.Scene density,boolean includeDensity) {
        Objects.requireNonNull(plan, "plan"); Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(source, "source"); Objects.requireNonNull(containingSlots, "containingSlots");
        if (source.size() != plan.boundaryCount() || containingSlots.length > CudaMediumTransportAbi.MAX_BOUNDARIES
                || firstPixel < 0 || pixelCount < 1 || pixelCount > plan.tilePixels()
                || (long) firstPixel + pixelCount > plan.pixels())
            throw new IllegalArgumentException("invalid native boundary scene/tile");
        requireMedium(frame.environment().cameraMedium());
        int densityOffset=align16(Math.addExact(plan.priorityOffset(),Math.multiplyExact(plan.boundaryCount(),Integer.BYTES)));
        if(Math.addExact(densityOffset,density.bytes())!=plan.sceneBytes())
            throw new IllegalArgumentException("native density scene does not match the planned allocation");
        List<Boundary> boundaries = new ArrayList<>(source);
        boundaries.sort((a,b) -> Integer.compareUnsigned(a.slot(), b.slot()));
        var compact = new HashMap<Integer,Integer>();
        ByteBuffer packed = ByteBuffer.allocate(includeDensity?plan.sceneBytes():densityOffset).order(ByteOrder.nativeOrder());
        updateTile(packed,plan,firstPixel,pixelCount);
        packed.putInt(24,frame.mediumTransport().maxEvents())
                .putInt(28,plan.boundaryCount()).putInt(32,containingSlots.length);
        packed.putInt(52,HEADER_BYTES).putInt(56,plan.priorityOffset()).putInt(60,INITIAL_VOLUMES_OFFSET);
        var environment = frame.environment().cameraMedium();
        putMedium(packed,64,environment);
        packed.putFloat(76,frame.mediumTransport().maxDistance());
        packed.putFloat(92,frame.mediumTransport().transmittanceCutoff());
        if(environment.participating() && !environment.densityField().isUniform() && density.environmentOffset()<0)
            throw new IllegalArgumentException("native environment density has no uploaded descriptor");
        packed.putInt(96,density.environmentOffset()<0?-1:Math.addExact(densityOffset,density.environmentOffset()));
        packed.putInt(100,frame.mediumTransport().scatteringSteps()).putInt(104,plan.sceneBytes());
        if(includeDensity)density.write(packed,densityOffset);
        for (int i=0; i<boundaries.size(); i++) {
            Boundary entry = boundaries.get(i); var boundary = entry.boundary();
            requireMedium(boundary.medium());
            if (boundary.surfaceInteraction()) throw new IllegalArgumentException("native boundary continuation requires volume-only boundaries");
            if (compact.put(entry.slot(),i) != null) throw new IllegalArgumentException("duplicate native boundary wire slot");
            int base = HEADER_BYTES+i*BOUNDARY_BYTES;
            packed.putInt(base,entry.slot()).putInt(base+4,boundary.priority())
                    .putInt(base+8,boundary.orientation().ordinal()).putInt(base+12,3);
            putMedium(packed,base+16,boundary.medium());
            int descriptor=density.boundaryOffset(entry.slot());
            if(boundary.medium().participating() && !boundary.medium().densityField().isUniform() && descriptor<0)
                throw new IllegalArgumentException("native boundary density has no uploaded descriptor");
            packed.putInt(base+48,descriptor<0?-1:Math.addExact(densityOffset,descriptor));
            packed.putInt(plan.priorityOffset()+i*4,boundary.priority());
        }
        var initial = new java.util.HashSet<Integer>();
        for (int i=0; i<containingSlots.length; i++) {
            Integer index = compact.get(containingSlots[i]);
            if (index == null || !initial.add(index)) throw new IllegalArgumentException("invalid initial native medium set");
            packed.putInt(INITIAL_VOLUMES_OFFSET+i*4,index);
        }
        return packed.array();
    }

    /** Only this header changes between tiles; boundary tables and density assets stay resident. */
    public static void updateTile(ByteBuffer packed,Plan plan,int firstPixel,int pixelCount) {
        if(firstPixel<0 || pixelCount<1 || pixelCount>plan.tilePixels()
                || (long)firstPixel+pixelCount>plan.pixels() || packed.capacity()<HEADER_BYTES)
            throw new IllegalArgumentException("invalid native boundary tile header");
        int paths=Math.multiplyExact(pixelCount,plan.samples());
        packed.putInt(0,MAGIC).putInt(4,VERSION).putInt(8,paths).putInt(12,plan.samples());
        packed.putInt(16,firstPixel).putInt(20,pixelCount);
        packed.putInt(36,plan.stateOffset()).putInt(40,plan.packet()?plan.tilePrimaryScratchOffset(firstPixel):plan.tilePrimaryOffset(firstPixel))
                .putInt(44,plan.tileTransmittanceOffset(firstPixel)).putInt(48,plan.tileEmissionOffset(firstPixel));
        packed.putInt(PACKET_LAYOUT_OFFSET,plan.packetCapacity());
        packed.putInt(STATISTICS_OFFSET,paths).putInt(STATISTICS_OFFSET+4,0)
                .putInt(STATISTICS_OFFSET+8,0).putInt(STATISTICS_OFFSET+12,0);
    }

    private static void putMedium(ByteBuffer target, int base, Medium medium) {
        var absorption=medium.absorption().multiply(medium.density());
        var emission=medium.emission().multiply(medium.density());
        target.putFloat(base,absorption.red()).putFloat(base+4,absorption.green()).putFloat(base+8,absorption.blue())
                .putFloat(base+12,medium.indexOfRefraction());
        target.putFloat(base+16,emission.red()).putFloat(base+20,emission.green()).putFloat(base+24,emission.blue());
    }

    public static void requireMedium(Medium medium) {
        if (!medium.scattering().isZero() || medium.indexOfRefraction()!=1)
            throw new IllegalArgumentException("native boundary continuation requires non-scattering IOR-one media");
    }

    private static int align16(int bytes) { return Math.toIntExact((Math.addExact((long)bytes,15)) & ~15L); }
}

package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongFunction;
import top.ceroxe.rt.renderer.api.AffineTransform;
import top.ceroxe.rt.renderer.api.Medium;
import top.ceroxe.rt.renderer.api.MediumDensityField;
import top.ceroxe.rt.renderer.api.TextureAsset;

/** Immutable asset-upload preparation. No renderer path or hit is read back for density evaluation. */
public final class CudaMediumDensityAbi {
    public static final int DESCRIPTOR_BYTES = 64;
    private CudaMediumDensityAbi() { }

    /** Relative descriptors/atlas are relocated once when writing the shared scene prefix. */
    public static final class Scene {
        private static final Scene EMPTY = new Scene(new byte[0], -1, Map.of(), 0);
        private final byte[] data;
        private final int environmentOffset;
        private final Map<Integer,Integer> boundaryOffsets;
        private final int descriptorCount;
        private Scene(byte[] data, int environmentOffset, Map<Integer,Integer> boundaryOffsets, int descriptorCount) {
            this.data=data; this.environmentOffset=environmentOffset;
            this.boundaryOffsets=Map.copyOf(boundaryOffsets); this.descriptorCount=descriptorCount;
        }
        public static Scene empty() { return EMPTY; }
        public int bytes() { return data.length; }
        public int environmentOffset() { return environmentOffset; }
        public int boundaryOffset(int slot) { return boundaryOffsets.getOrDefault(slot,-1); }
        public void write(ByteBuffer target, int base) {
            Objects.requireNonNull(target,"target");
            if(base<0 || (long)base+data.length>target.limit() || (data.length!=0 && (base&15)!=0))
                throw new IllegalArgumentException("native density upload needs an aligned range within the target limit");
            ByteBuffer destination=target.duplicate().order(ByteOrder.nativeOrder());
            destination.position(base); destination.put(data);
            for(int i=0;i<descriptorCount;i++) {
                int descriptor=base+i*DESCRIPTOR_BYTES;
                destination.putInt(descriptor,Math.addExact(base,destination.getInt(descriptor)));
            }
        }
    }

    private record Grid(int slot, boolean environment, MediumDensityField field, TextureAsset texture, float[] worldToUvw) { }
    private record Input(int slot, boolean environment, MediumDensityField field, TextureAsset texture, AffineTransform objectToWorld) { }

    /** Session-owned cache retains only the current immutable asset set, never historical texture generations. */
    public static final class Cache {
        private List<Input> previous=List.of();
        private Scene scene=Scene.empty();
        public synchronized Scene prepare(Medium environment,List<CudaBoundaryMediumAbi.Boundary> boundaries,
                                          LongFunction<TextureAsset> textures) {
            return prepare(environment,boundaries,textures,Integer.MAX_VALUE);
        }
        /** Enforces the current frame's remaining residency budget even on a cache hit. */
        public synchronized Scene prepare(Medium environment,List<CudaBoundaryMediumAbi.Boundary> boundaries,
                                          LongFunction<TextureAsset> textures,long densityBytesLimit) {
            List<Input> current=inputs(environment,boundaries,textures);
            requireBudget(requiredBytes(current),densityBytesLimit);
            if(current.equals(previous)) return scene;
            Scene prepared=pack(current);
            previous=List.copyOf(current);scene=prepared;
            return scene;
        }
    }

    public static Scene prepare(Medium environment, List<CudaBoundaryMediumAbi.Boundary> boundaries,
                                LongFunction<TextureAsset> textures) {
        return prepare(environment,boundaries,textures,Integer.MAX_VALUE);
    }

    /** Rejects an oversized atlas before reading texels or allocating its converted upload array. */
    public static Scene prepare(Medium environment,List<CudaBoundaryMediumAbi.Boundary> boundaries,
                                LongFunction<TextureAsset> textures,long densityBytesLimit) {
        List<Input> current=inputs(environment,boundaries,textures);
        requireBudget(requiredBytes(current),densityBytesLimit);
        return pack(current);
    }

    /** Metadata-only footprint, including shared-atlas deduplication and final alignment. */
    public static long requiredBytes(Medium environment,List<CudaBoundaryMediumAbi.Boundary> boundaries,
                                     LongFunction<TextureAsset> textures) {
        return requiredBytes(inputs(environment,boundaries,textures));
    }

    private static long requiredBytes(List<Input> inputs) {
        long bytes=Math.multiplyExact((long)inputs.size(),DESCRIPTOR_BYTES);
        var seen=new java.util.HashSet<Long>();
        for(var input:inputs) if(seen.add(input.texture().id()))
            bytes=Math.addExact(bytes,Math.multiplyExact(Math.multiplyExact((long)input.texture().width(),input.texture().height()),Float.BYTES));
        return Math.addExact(bytes,15)&~15L;
    }

    private static void requireBudget(long bytes,long limit) {
        if(limit<0 || bytes>limit || bytes>Integer.MAX_VALUE)
            throw new IllegalArgumentException("native density upload exceeds the remaining resident budget: required="+bytes+", limit="+limit);
    }

    private static List<Input> inputs(Medium environment,List<CudaBoundaryMediumAbi.Boundary> boundaries,
                                       LongFunction<TextureAsset> textures) {
        Objects.requireNonNull(environment,"environment"); Objects.requireNonNull(boundaries,"boundaries");
        Objects.requireNonNull(textures,"textures");
        List<Input> inputs=new ArrayList<>();
        Map<Long,TextureAsset> assets=new HashMap<>();
        LongFunction<TextureAsset> snapshot=id->assets.computeIfAbsent(id,textures::apply);
        if(environment.participating())
            input(inputs,0,true,environment.densityField(),AffineTransform.identity(),snapshot);
        for(var boundary:boundaries)
            if(boundary.boundary().medium().participating())
                input(inputs,boundary.slot(),false,boundary.boundary().medium().densityField(),boundary.objectToWorld(),snapshot);
        return inputs;
    }

    private static Scene pack(List<Input> inputs) {
        List<Grid> grids=new ArrayList<>();
        for(var input:inputs) grids.add(new Grid(input.slot(),input.environment(),input.field(),input.texture(),
                worldToUvw(input.field(),input.objectToWorld())));
        if(grids.isEmpty()) return Scene.empty();
        int bytes=Math.multiplyExact(grids.size(),DESCRIPTOR_BYTES);
        Map<Long,Integer> atlasOffsets=new LinkedHashMap<>();
        for(var grid:grids) if(!atlasOffsets.containsKey(grid.texture().id())) {
            atlasOffsets.put(grid.texture().id(),bytes);
            bytes=Math.addExact(bytes,Math.multiplyExact(Math.multiplyExact(grid.texture().width(),grid.texture().height()),Float.BYTES));
        }
        bytes=align16(bytes);
        ByteBuffer output=ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder());
        int environmentOffset=-1;
        Map<Integer,Integer> boundaryOffsets=new HashMap<>();
        for(int index=0;index<grids.size();index++) {
            var grid=grids.get(index); int descriptor=index*DESCRIPTOR_BYTES;
            output.putInt(descriptor,atlasOffsets.get(grid.texture().id())).putInt(descriptor+4,grid.texture().width())
                    .putInt(descriptor+8,grid.texture().height()/grid.field().slices()).putInt(descriptor+12,grid.field().slices());
            for(int lane=0;lane<12;lane++) output.putFloat(descriptor+16+lane*4,grid.worldToUvw()[lane]);
            if(grid.environment()) environmentOffset=descriptor;
            else if(boundaryOffsets.put(grid.slot(),descriptor)!=null)
                throw new IllegalArgumentException("duplicate density boundary slot");
        }
        // Base-level red only; material sampling state and lower mip levels do not participate.
        Map<Long,TextureAsset> assets=new LinkedHashMap<>();
        for(var grid:grids) assets.putIfAbsent(grid.texture().id(),grid.texture());
        for(var entry:assets.entrySet()) {
            var texture=entry.getValue(); ByteBuffer rgba=texture.rgba8(); int at=atlasOffsets.get(entry.getKey());
            int texels=Math.multiplyExact(texture.width(),texture.height());
            for(int index=0;index<texels;index++) output.putFloat(at+index*4,(rgba.get(index*4)&255)/255f);
        }
        return new Scene(output.array(),environmentOffset,boundaryOffsets,grids.size());
    }

    private static void input(List<Input> inputs,int slot,boolean environment,MediumDensityField field,
                            AffineTransform objectToWorld,LongFunction<TextureAsset> textures) {
        if(field.isUniform()) return;
        TextureAsset texture=textures.apply(field.textureAssetId());
        if(texture==null || texture.id()!=field.textureAssetId())
            throw new IllegalArgumentException("missing native density texture "+field.textureAssetId());
        if(texture.colorSpace()!=TextureAsset.ColorSpace.LINEAR)
            throw new IllegalArgumentException("native density texture must use LINEAR color space");
        if(texture.height()%field.slices()!=0 || texture.height()<field.slices())
            throw new IllegalArgumentException("native density atlas height must be a positive multiple of slices");
        inputs.add(new Input(slot,environment,field,texture,objectToWorld));
    }

    /** Same affine composition as the public scene density metadata, evaluated in double precision. */
    public static float[] worldToUvw(MediumDensityField field,AffineTransform objectToWorld) {
        var t=objectToWorld; double a=t.element(0),b=t.element(1),c=t.element(2),d=t.element(4),e=t.element(5),f=t.element(6);
        double g=t.element(8),h=t.element(9),i=t.element(10);
        double det=a*(e*i-f*h)-b*(d*i-f*g)+c*(d*h-e*g);
        double[] inverse={(e*i-f*h)/det,(c*h-b*i)/det,(b*f-c*e)/det,0,
                (f*g-d*i)/det,(a*i-c*g)/det,(c*d-a*f)/det,0,
                (d*h-e*g)/det,(b*g-a*h)/det,(a*e-b*d)/det,0};
        for(int row=0;row<3;row++) inverse[row*4+3]=-inverse[row*4]*t.element(3)
                -inverse[row*4+1]*t.element(7)-inverse[row*4+2]*t.element(11);
        float[] result=new float[12]; var local=field.transform();
        for(int row=0;row<3;row++) for(int column=0;column<4;column++) {
            double value=column==3?local.element(row*4+3):0;
            for(int axis=0;axis<3;axis++) value+=local.element(row*4+axis)*inverse[axis*4+column];
            result[row*4+column]=(float)value;
            if(!Float.isFinite(result[row*4+column])) throw new IllegalArgumentException("native density transform exceeds finite float range");
        }
        return result;
    }
    private static int align16(int bytes) { return Math.toIntExact((Math.addExact((long)bytes,15))&~15L); }
}

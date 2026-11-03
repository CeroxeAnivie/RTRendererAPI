package top.ceroxe.rt.renderer.api;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;

/** Immutable attribute delta. Generation increases independently from geometry and scene revisions. */
public record NativeLightingPublication(long generation,List<Instance> instances,List<MeshBaseline> baselines,
        float[] baselineLookup,ByteBuffer applicationData) {
    /** Roles: receiver=1, shadow=2, bounce=4, cull receiver backfaces=8, double-sided shadow=16. */
    public record Instance(long id,int roles,float visibility,float fog,float localScale,float skyScale,float localBias,float skyBias) {
        public Instance {
            if(id<0||roles<0||roles>31)throw new IllegalArgumentException("Invalid instance identity or lighting roles");
            for(float v:new float[]{visibility,fog})if(!Float.isFinite(v)||v<0||v>1)throw new IllegalArgumentException("Visibility and fog must be in [0,1]");
            for(float v:new float[]{localScale,skyScale,localBias,skyBias})if(!Float.isFinite(v))throw new IllegalArgumentException("Nonfinite baseline transform");
        }
        public static Instance receiver(long id){return new Instance(id,23,1,0,1,1,0,0);}
        public static Instance shadowOnly(long id){return new Instance(id,22,1,0,1,1,0,0);}
    }
    /** Two normalized coordinates per mesh vertex; interpolation occurs before the lookup. */
    public record MeshBaseline(long meshId,float[] coordinates) {
        public MeshBaseline {
            if(meshId<0)throw new IllegalArgumentException("Negative mesh ID");coordinates=Objects.requireNonNull(coordinates).clone();
            if(coordinates.length%2!=0)throw new IllegalArgumentException("Baseline requires float2 per vertex");
            for(float v:coordinates)if(!Float.isFinite(v)||v<0||v>1)throw new IllegalArgumentException("Baseline coordinates must be in [0,1]");
        }
        @Override public float[] coordinates(){return coordinates.clone();}
    }
    public NativeLightingPublication {
        if(generation<=0)throw new IllegalArgumentException("Publication generation must be positive");
        instances=List.copyOf(instances);baselines=List.copyOf(baselines);
        if(baselineLookup!=null){baselineLookup=baselineLookup.clone();if(baselineLookup.length!=512)throw new IllegalArgumentException("Lookup requires 16x16 float2 values");
            for(float v:baselineLookup)if(!Float.isFinite(v)||v<0||v>1)throw new IllegalArgumentException("Baseline lookup values must be in [0,1]");}
        if(applicationData!=null){var source=applicationData.asReadOnlyBuffer();int bytes=source.remaining();
            if(bytes%4!=0||bytes>16*1024*1024)throw new IllegalArgumentException("Application data requires 4-byte alignment and at most 16 MiB");
            var copy=ByteBuffer.allocate(bytes);copy.put(source).flip();applicationData=copy.asReadOnlyBuffer();}
    }
    @Override public float[] baselineLookup(){return baselineLookup==null?null:baselineLookup.clone();}
    @Override public ByteBuffer applicationData(){return applicationData==null?null:applicationData.asReadOnlyBuffer();}
}

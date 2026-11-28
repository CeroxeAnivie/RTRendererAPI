package top.ceroxe.rt.renderer.api;

import java.util.List;

/**
 * One atomic GPU-only signal packet. Handles are borrowed Win32 D3D12-resource
 * and shared-fence handles: never CloseHandle them. Import them on the same
 * adapter LUID and destroy imported GL/Vulkan objects before closing the renderer.
 * Fence readiness covers every plane. Before reading, enqueue the advertised
 * ready wait; after every read enqueue completionValue on that same fence.
 * Half-float lighting is clamped to [0,65504]; nonfinite extension output becomes
 * neutral gain or zero indirect. NORMAL_DISTANCE uses RGBA32_FLOAT, with world
 * normal in xyz and ray distance in w. Identity is only valid when its w is 1.
 * DIELECTRIC_RADIANCE is RGBA32_FLOAT regardless of the frame encoding. Its
 * Positive alpha is the first dielectric interface's camera-ray distance in
 * world units; RGB is then scene-linear radiance. Negative alpha encodes the
 * negated distance to a straight-ray opaque receiver in the camera medium;
 * RGB is then linear transmittance for that segment, to multiply only the
 * matching receiver's radiance. Zero alpha means no valid result.
 * DIELECTRIC_REFINEMENT, when published, is a row-major RGBA32_FLOAT atlas at the
 * packet extent, keyed by the submitted exact projection's viewport pixels. The
 * first ceil(gridWidth*gridHeight/4) texels store one numeric pointer per grid
 * cell in RGBA order. Zero means no refinement; otherwise subtract one to get
 * the payload index. For each axis, the grid cell containing viewport pixel p
 * is floor((p+0.5)*grid/viewport), and its first pixel is
 * ceil(cell*viewport/grid-0.5). Each payload has ceil(viewportWidth/gridWidth)
 * by ceil(viewportHeight/gridHeight) texels, in row-major order, starting after
 * the pointer table. Valid pixels carry the same RGBA as DIELECTRIC_RADIANCE;
 * skip padding outside the cell. Zero alpha is unresolved. Use the coarse
 * signal when no usable refined sample matches the receiver. The adapter may
 * omit this plane without an exact viewport or outside its supersampling budget.
 */
public interface LightingFrameLease extends AutoCloseable {
    enum Format { RGBA16_FLOAT, RGBA32_UINT, RGBA32_FLOAT,RGBA8_UNORM }
    record Plane(NativeLightingPlan.Signal signal,Format format,long allocationBytes,long memoryHandle) { }
    record Frame(long generation,long sequence,long sceneRevision,long adapterLuid,int width,int height,
            long fenceHandle,long readyValue,long completionValue,long attributeGeneration,FrameClock clock,List<Plane> planes,
            NativeLightingPlan.Encoding encoding,float maximumGi) {
        public Frame {planes=List.copyOf(planes);java.util.Objects.requireNonNull(encoding);
            if(!Float.isFinite(maximumGi)||maximumGi<=0)throw new IllegalArgumentException("Invalid compact GI decode scale");}
        public Frame(long generation,long sequence,long sceneRevision,long adapterLuid,int width,int height,
                long fenceHandle,long readyValue,long completionValue,long attributeGeneration,FrameClock clock,List<Plane> planes){
            this(generation,sequence,sceneRevision,adapterLuid,width,height,fenceHandle,readyValue,completionValue,attributeGeneration,
                clock,planes,NativeLightingPlan.Encoding.HDR_FLOAT16,1);
        }
    }
    Frame frame();
    /**
     * Transfer an active, not-yet-imported lease to another consumer thread.
     * The current owner must call this before publishing the lease to that thread;
     * afterward only the new owner may inspect, import, complete or close it.
     * This changes CPU ownership only and never replaces the required GPU fences.
     */
    void transferTo(Thread nextOwner);
    /** Call after successful resource import, before enqueueing any consumer GPU reads. */
    void markImported();
    /**
     * Acknowledge that the consumer queued its completion signal after all reads.
     * The producer still observes the actual shared fence before reusing the slot.
     */
    void completionSignalEnqueued();
    /** An unimported lease can be abandoned; an imported active lease must first enqueue completion. */
    @Override void close();
}

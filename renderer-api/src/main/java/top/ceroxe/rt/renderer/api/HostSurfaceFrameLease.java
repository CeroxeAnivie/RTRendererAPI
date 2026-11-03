package top.ceroxe.rt.renderer.api;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * One atomic scene-linear GPU output packet produced from host primary surfaces.
 * Handles are borrowed D3D12 resources and fence handles on the advertised adapter.
 * Wait readyValue on the consumer GPU before reading; signal completionValue after
 * all reads. The combined surface replaces its included lighting contributions;
 * it must not be added a second time as an independent diffuse/specular AOV.
 */
public interface HostSurfaceFrameLease extends AutoCloseable {
    /** Radiance AOVs include BSDF weights and primary albedo exactly once. */
    enum Signal { COMBINED_SURFACE,DIRECT_MODULATION,DIFFUSE_DIRECT,DIFFUSE_INDIRECT,SPECULAR,TRANSMISSION,EMISSION,COVERAGE }
    enum Format {
        RGBA16_FLOAT(8),RGBA32_UINT(16),RGBA32_FLOAT(16);
        private final int bytes;
        Format(int bytes){this.bytes=bytes;}
        public int bytesPerPixel(){return bytes;}
    }
    /** Actual processing used for this output, rather than the requested preference. */
    enum Reconstruction { NONE,SR,RR }
    enum ColorDomain { SCENE_LINEAR,DISPLAY_LINEAR }
    record Plane(Signal signal,Format format,long allocationBytes,long memoryHandle){
        public Plane {
            Objects.requireNonNull(signal);Objects.requireNonNull(format);
            if(allocationBytes<1||memoryHandle==0||signal==Signal.COMBINED_SURFACE&&format==Format.RGBA32_UINT)
                throw new IllegalArgumentException("Invalid host-surface output plane descriptor");
        }
    }
    record Frame(long generation,long sequence,long sceneRevision,long adapterLuid,int width,int height,
            long fenceHandle,long readyValue,long completionValue,long inputGeneration,long resourceGeneration,
            FrameClock clock,Reconstruction reconstruction,List<Plane> planes,ColorDomain colorDomain){
        public Frame {
            Objects.requireNonNull(clock);Objects.requireNonNull(reconstruction);Objects.requireNonNull(colorDomain);planes=List.copyOf(planes);
            if(generation<=0||sequence<0||sceneRevision<0||inputGeneration<=0||resourceGeneration<0
                ||width<1||height<1||width>16384||height>16384||fenceHandle==0
                ||readyValue<1||readyValue==Long.MAX_VALUE||completionValue!=readyValue+1)
                throw new IllegalArgumentException("Invalid host-surface output frame/fence identity");
            var signals=EnumSet.noneOf(Signal.class);
            for(var plane:planes)if(!signals.add(plane.signal())
                ||plane.allocationBytes()<(long)width*height*plane.format().bytesPerPixel())
                throw new IllegalArgumentException("Duplicate or undersized host-surface output plane");
            if(planes.isEmpty())throw new IllegalArgumentException("A host-surface output packet requires a signal");
        }
        public Frame(long generation,long sequence,long sceneRevision,long adapterLuid,int width,int height,
                long fenceHandle,long readyValue,long completionValue,long inputGeneration,long resourceGeneration,
                FrameClock clock,Reconstruction reconstruction,List<Plane> planes){
            this(generation,sequence,sceneRevision,adapterLuid,width,height,fenceHandle,readyValue,completionValue,inputGeneration,resourceGeneration,clock,reconstruction,planes,ColorDomain.SCENE_LINEAR);
        }
        /** RR/SR processes the combined signal; separately requested transport AOVs are raw estimates. */
        public Reconstruction reconstruction(Signal signal){return signal==Signal.COMBINED_SURFACE?reconstruction:Reconstruction.NONE;}
    }
    Frame frame();
    /** Call after import, before enqueueing any consumer GPU reads. */
    void markImported();
    /** Acknowledge a queued/flushed completion signal after all consumer reads. */
    void completionSignalEnqueued();
    /** Unimported packets may be abandoned; imported packets need their completion signal first. */
    @Override void close();
}

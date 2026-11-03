package top.ceroxe.rt.renderer.api;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * One owner-thread host-write reservation. D3D12 resources/fence handles are borrowed;
 * import on the advertised adapter, never CloseHandle them. This is the input direction.
 */
public interface HostSurfaceInputLease extends AutoCloseable {
    record Plane(HostSurfacePlan.Plane kind,HostSurfacePlan.Format format,long allocationBytes,long memoryHandle) {
        public Plane {
            Objects.requireNonNull(kind);Objects.requireNonNull(format);
            if(format!=kind.format()||allocationBytes<1||memoryHandle==0)throw new IllegalArgumentException("Invalid host-surface plane descriptor");
        }
    }
    record Frame(long generation,long sequence,long sceneRevision,long resourceGeneration,long adapterLuid,
            int width,int height,long fenceHandle,long previousDoneValue,long readyValue,long doneValue,
            FrameClock clock,List<Plane> planes) {
        public Frame {
            Objects.requireNonNull(clock);planes=List.copyOf(planes);
            if(generation<=0||sequence<0||sceneRevision<0||resourceGeneration<0||width<1||height<1||width>16384||height>16384||fenceHandle==0
                    ||previousDoneValue<0||previousDoneValue>Long.MAX_VALUE-2||readyValue!=previousDoneValue+1||doneValue!=previousDoneValue+2)
                throw new IllegalArgumentException("Invalid host-surface frame/fence identity");
            var kinds=EnumSet.noneOf(HostSurfacePlan.Plane.class);
            for(var plane:planes){
                if(!kinds.add(plane.kind())||plane.allocationBytes()<(long)width*height*plane.format().bytesPerPixel())
                    throw new IllegalArgumentException("Duplicate or undersized host-surface plane");
            }
            int count=planes.size();
            if(count!=8&&count!=10)throw new IllegalArgumentException("A host-surface frame requires eight split or ten augmentation ABI planes");
            for(int i=0;i<count;i++)if(planes.get(i).kind().ordinal()!=i)
                throw new IllegalArgumentException("Host-surface planes must contain the complete composition-specific ABI order");
        }
        /** Input shape identifies the declared composition; split inputs allocate no augmentation planes. */
        public HostSurfacePlan.Composition composition(){return planes.size()==10
            ?HostSurfacePlan.Composition.HOST_LIT_AUGMENTATION:HostSurfacePlan.Composition.SPLIT_SURFACE_LIGHTING;}
    }
    Frame frame();
    /** Call after successful import and the previous-done GPU wait, before writing any plane. */
    void markImported();
    /** Call only after all host writes and readyValue signal have been queued and flushed. */
    void publish();
    /**
     * Cancel an imported, unpublished reservation after queuing/flushing readyValue.
     * The renderer queues a ready wait and done signal without sampling the surfaces.
     */
    void abandonSignalEnqueued();
    /** Unimported reservations cancel immediately. Imported reservations need publish/abandon first. */
    @Override void close();
}

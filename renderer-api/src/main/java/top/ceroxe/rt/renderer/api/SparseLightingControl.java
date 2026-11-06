package top.ceroxe.rt.renderer.api;

import java.nio.LongBuffer;
import java.util.List;

/** Owner-thread scene extension. All resources and sources share the transaction revision. */
public interface SparseLightingControl {
    /** Cumulative regional-index maintenance and current resident sizes. A
     * backend without regional indexing returns zeroes. No GPU readback. */
    record SpatialIndexStatistics(long builds,long coverageChanges,long treelets,long cells,long links,
            long globalTreelets,long packedBytes,long hashProbeOverflows,long uploadedBytes) { }
    default SpatialIndexStatistics sparseSpatialIndexStatistics(){return new SpatialIndexStatistics(0,0,0,0,0,0,0,0,0);}
    record Capacity(int maximumSources,int maximumGroups,int recordBytes) { }
    record UpdateStatus(long representedSources,long indexNodes,long patches,long upserts,long removals,
            long updatedIndexNodes,long uploadedRecordBytes,long uploadedIndexBytes,long capacityGrowths) { }
    Capacity sparseLightingCapacity();
    UpdateStatus sparseLightingStatus();
    /** Validate the entire transaction before accepting any of its changes. Reset clears sources too. */
    Renderer.SceneUpdateResult apply(SceneTransaction transaction,List<SparseLightSource> sources,LongBuffer removals);
}

package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.RendererViewState;

/** Verifies scalar-key active-view refresh, publication, material promotion, and clear semantics. */
public final class RtSectionActiveViewCacheSelfTest {
    private RtSectionActiveViewCacheSelfTest() {
    }

    public static void main(String[] arguments) {
        RtSectionActiveViewCache cache = new RtSectionActiveViewCache();
        RendererViewState view = RendererViewState.allResident();
        require(cache.refresh(0L, 0L, 0L, 0L, 0L, 0L, false, view)
                        == RtSectionActiveViewCache.Refresh.TOPOLOGY,
                "cold active-view cache must require topology publication");
        RtSectionActiveViewAssembler.Snapshot snapshot = RtSectionActiveViewAssembler.Snapshot.empty();
        cache.publishTopology(snapshot, 0L, 0L, 0L, 0L, 0L, 0L, false, view);
        require(cache.available() && cache.snapshot() == snapshot,
                "topology publication did not commit its immutable view");
        require(cache.refresh(0L, 0L, 0L, 0L, 0L, 0L, false, view)
                        == RtSectionActiveViewCache.Refresh.HIT,
                "identical scalar keys must hit the active-view cache");
        require(cache.refresh(0L, 0L, 0L, 0L, 0L, 1L, false, view)
                        == RtSectionActiveViewCache.Refresh.MATERIAL_ONLY,
                "source-material publication must invalidate even when the global material token is stable");
        cache.publishMaterial(0L, 1L);
        require(cache.refresh(0L, 1L, 0L, 0L, 0L, 1L, false, view)
                        == RtSectionActiveViewCache.Refresh.MATERIAL_ONLY,
                "global material-only revision must not force topology assembly");
        cache.publishMaterial(1L, 1L);
        require(cache.refresh(0L, 1L, 0L, 0L, 0L, 1L, false, view)
                        == RtSectionActiveViewCache.Refresh.HIT,
                "material publication did not advance both material keys");
        require(cache.refresh(0L, 1L, 0L, 1L, 0L, 1L, false, view)
                        == RtSectionActiveViewCache.Refresh.TOPOLOGY,
                "active BLAS membership revision must invalidate topology");

        RendererViewState movedView = new RendererViewState(1L, true, true, 4, 5, 6, java.util.List.of());
        require(cache.refresh(0L, 1L, 0L, 0L, 0L, 1L, true, movedView)
                        == RtSectionActiveViewCache.Refresh.TOPOLOGY,
                "view-dependent admission change must invalidate topology");
        cache.clear();
        require(!cache.available() && cache.snapshot().baseEntries().isEmpty(),
                "active-view clear retained a stale publication");
        System.out.println("RtSectionActiveViewCacheSelfTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

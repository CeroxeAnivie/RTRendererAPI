package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.rt.material.RtSceneMaterialTable;

import java.util.List;

/** Verifies identity keys, miss accounting, stale-publish rejection, and invalidation. */
public final class RtSectionTlasBuildInputCacheSelfTest {
    private RtSectionTlasBuildInputCacheSelfTest() {
    }

    public static void main(String[] arguments) {
        RtSectionTlasBuildInputCache cache = new RtSectionTlasBuildInputCache();
        RtSectionActiveViewAssembler.Snapshot view = RtSectionActiveViewAssembler.Snapshot.empty();
        RtSectionTlasBuildInputCache.Key initial = key(view, 0L, 0L, 0L);
        RtSectionTlasBuildInputCache.Lookup cold = cache.probe(initial);
        require(!cold.hit() && cold.missMask() == RtSectionTlasBuildInputCache.MISS_COLD,
                "first TLAS input probe must be a cold-only miss");

        RtSectionTlasBuildInput input = emptyInput();
        cache.publish(cold, input);
        RtSectionTlasBuildInputCache.Lookup hit = cache.probe(initial);
        require(hit.hit() && hit.input() == input, "identical scalar key must reuse the cached input");

        RtSectionTlasBuildInputCache.Key changed = key(view, 1L, 2L, 3L);
        RtSectionTlasBuildInputCache.Lookup changedLookup = cache.probe(changed);
        require((changedLookup.missMask() & RtSectionTlasBuildInputCache.MISS_TEXTURE) != 0,
                "texture revision change was not audited");
        require((changedLookup.missMask() & RtSectionTlasBuildInputCache.MISS_ACTIVE_CONTENT) != 0,
                "active-content generation change was not audited");

        RtSectionTlasBuildInputCache.Key successor = key(view, 2L, 3L, 4L);
        cache.probe(successor);
        expectFailure(() -> cache.publish(changedLookup, input));
        RtSectionTlasBuildInputCache.Lookup successorLookup = cache.probe(successor);
        cache.publish(successorLookup, input);

        RtSectionActiveViewAssembler.Snapshot equivalentButDistinctView =
                RtSectionActiveViewAssembler.Snapshot.empty();
        RtSectionTlasBuildInputCache.Lookup viewMiss = cache.probe(key(equivalentButDistinctView, 2L, 3L, 4L));
        require((viewMiss.missMask() & RtSectionTlasBuildInputCache.MISS_ACTIVE_VIEW) != 0,
                "active-view cache key must use identity rather than structural equality");

        RtSectionTlasBuildInputCache.Stats stats = cache.stats();
        require(stats.hits() == 1L && stats.misses() == 5L,
                "TLAS input hit/miss totals are inconsistent");
        require(stats.activeContentMisses() >= 2L && stats.textureMisses() >= 2L,
                "TLAS input miss-reason counters were not retained");
        cache.invalidate();
        require(!cache.stats().cached() && cache.stats().hits() == stats.hits(),
                "invalidation must release input ownership without erasing diagnostics");
        System.out.println("RtSectionTlasBuildInputCacheSelfTest passed");
    }

    private static RtSectionTlasBuildInputCache.Key key(
            RtSectionActiveViewAssembler.Snapshot view,
            long textureRevision,
            long activeContentGeneration,
            long pendingTriangles
    ) {
        return new RtSectionTlasBuildInputCache.Key(
                view,
                0L,
                0L,
                0L,
                textureRevision,
                activeContentGeneration,
                0,
                pendingTriangles,
                0L
        );
    }

    private static RtSectionTlasBuildInput emptyInput() {
        return new RtSectionTlasBuildInput(
                0L,
                0L,
                List.of(),
                List.of(),
                RtSceneMaterialTable.Snapshot.empty(),
                0,
                0L,
                0L
        );
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("expected operation to fail");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import java.util.Objects;

/**
 * Persistent scalar-key cache for immutable world-TLAS build input.
 *
 * <p>The section cache gathers renderer state and constructs the public input DTO; this owner only
 * decides whether that DTO is current and records why it was not.  Probe and publish are separate
 * so expensive material composition happens only after a miss, while a publish token prevents a
 * stale probe from being committed if this owner is later used outside the current outer lock.</p>
 */
final class RtSectionTlasBuildInputCache {
    static final int MISS_COLD = 1;
    static final int MISS_ACTIVE_VIEW = 1 << 1;
    static final int MISS_SCENE = 1 << 2;
    static final int MISS_GEOMETRY = 1 << 3;
    static final int MISS_MATERIAL = 1 << 4;
    static final int MISS_TEXTURE = 1 << 5;
    static final int MISS_PENDING_BUILDS = 1 << 6;
    static final int MISS_PENDING_TRIANGLES = 1 << 7;
    static final int MISS_CACHED_TRIANGLES = 1 << 8;
    static final int MISS_ACTIVE_CONTENT = 1 << 9;

    private Key cachedKey;
    private RtSectionTlasBuildInput cachedInput;
    /**
     * Identity token for the only miss which may publish next.
     *
     * <p>Key equality is intentionally insufficient here: two probes may observe the same scalar
     * state while their callers build different candidate DTOs.  Retaining the exact lookup makes
     * the probe/publish transaction linearizable if the outer cache lock is ever relaxed.</p>
     */
    private Lookup latestMiss;
    private long hits;
    private long misses;
    private long coldMisses;
    private long activeViewMisses;
    private long sceneMisses;
    private long geometryMisses;
    private long materialMisses;
    private long textureMisses;
    private long pendingBuildMisses;
    private long pendingTriangleMisses;
    private long cachedTriangleMisses;
    private long activeContentMisses;

    Lookup probe(Key key) {
        Objects.requireNonNull(key, "key");
        int mask = cachedInput == null ? MISS_COLD : missMask(cachedKey, key);
        if (mask == 0) {
            hits++;
            latestMiss = null;
            return new Lookup(key, cachedInput, 0);
        }
        misses++;
        recordMiss(mask);
        Lookup lookup = new Lookup(key, null, mask);
        latestMiss = lookup;
        return lookup;
    }

    void publish(Lookup lookup, RtSectionTlasBuildInput input) {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(input, "input");
        if (lookup.hit()) {
            throw new IllegalArgumentException("a TLAS cache hit must not be republished");
        }
        if (latestMiss != lookup) {
            throw new IllegalStateException("TLAS build input publish does not match the latest cache miss");
        }
        cachedKey = lookup.key();
        cachedInput = input;
        latestMiss = null;
    }

    void invalidate() {
        cachedKey = null;
        cachedInput = null;
        latestMiss = null;
    }

    Stats stats() {
        return new Stats(
                hits,
                misses,
                coldMisses,
                activeViewMisses,
                sceneMisses,
                geometryMisses,
                materialMisses,
                textureMisses,
                pendingBuildMisses,
                pendingTriangleMisses,
                cachedTriangleMisses,
                activeContentMisses,
                cachedInput != null,
                cachedKey == null ? -1L : cachedKey.textureRevision()
        );
    }

    static boolean current(
            RtSectionTlasBuildInput cachedInput,
            Object cachedView,
            Object currentView,
            long cachedSceneRevision,
            long sceneRevision,
            long cachedGeometryRevision,
            long geometryRevision,
            long cachedMaterialRevision,
            long materialRevision,
            long cachedTextureRevision,
            long textureRevision,
            int cachedPendingBuilds,
            int pendingBuilds,
            long cachedPendingTriangles,
            long pendingTriangles,
            long cachedTriangles,
            long currentCachedTriangles
    ) {
        return compatibilityMissMask(
                cachedInput,
                cachedView,
                currentView,
                cachedSceneRevision,
                sceneRevision,
                cachedGeometryRevision,
                geometryRevision,
                cachedMaterialRevision,
                materialRevision,
                cachedTextureRevision,
                textureRevision,
                cachedPendingBuilds,
                pendingBuilds,
                cachedPendingTriangles,
                pendingTriangles,
                cachedTriangles,
                currentCachedTriangles
        ) == 0;
    }

    static int compatibilityMissMask(
            RtSectionTlasBuildInput cachedInput,
            Object cachedView,
            Object currentView,
            long cachedSceneRevision,
            long sceneRevision,
            long cachedGeometryRevision,
            long geometryRevision,
            long cachedMaterialRevision,
            long materialRevision,
            long cachedTextureRevision,
            long textureRevision,
            int cachedPendingBuilds,
            int pendingBuilds,
            long cachedPendingTriangles,
            long pendingTriangles,
            long cachedTriangles,
            long currentCachedTriangles
    ) {
        if (cachedInput == null) {
            return MISS_COLD;
        }
        int mask = 0;
        if (cachedView != currentView) {
            mask |= MISS_ACTIVE_VIEW;
        }
        if (cachedSceneRevision != sceneRevision) {
            mask |= MISS_SCENE;
        }
        if (cachedGeometryRevision != geometryRevision) {
            mask |= MISS_GEOMETRY;
        }
        if (cachedMaterialRevision != materialRevision) {
            mask |= MISS_MATERIAL;
        }
        if (cachedTextureRevision != textureRevision) {
            mask |= MISS_TEXTURE;
        }
        if (cachedPendingBuilds != pendingBuilds) {
            mask |= MISS_PENDING_BUILDS;
        }
        if (cachedPendingTriangles != pendingTriangles) {
            mask |= MISS_PENDING_TRIANGLES;
        }
        if (cachedTriangles != currentCachedTriangles) {
            mask |= MISS_CACHED_TRIANGLES;
        }
        return mask;
    }

    private static int missMask(Key cached, Key current) {
        int mask = 0;
        if (cached.activeView() != current.activeView()) {
            mask |= MISS_ACTIVE_VIEW;
        }
        if (cached.sceneRevision() != current.sceneRevision()) {
            mask |= MISS_SCENE;
        }
        if (cached.geometryRevision() != current.geometryRevision()) {
            mask |= MISS_GEOMETRY;
        }
        if (cached.materialRevision() != current.materialRevision()) {
            mask |= MISS_MATERIAL;
        }
        if (cached.textureRevision() != current.textureRevision()) {
            mask |= MISS_TEXTURE;
        }
        if (cached.pendingBuilds() != current.pendingBuilds()) {
            mask |= MISS_PENDING_BUILDS;
        }
        if (cached.pendingTriangles() != current.pendingTriangles()) {
            mask |= MISS_PENDING_TRIANGLES;
        }
        if (cached.cachedTriangles() != current.cachedTriangles()) {
            mask |= MISS_CACHED_TRIANGLES;
        }
        if (cached.activeContentGeneration() != current.activeContentGeneration()) {
            mask |= MISS_ACTIVE_CONTENT;
        }
        return mask;
    }

    private void recordMiss(int mask) {
        if ((mask & MISS_COLD) != 0) {
            coldMisses++;
        }
        if ((mask & MISS_ACTIVE_VIEW) != 0) {
            activeViewMisses++;
        }
        if ((mask & MISS_SCENE) != 0) {
            sceneMisses++;
        }
        if ((mask & MISS_GEOMETRY) != 0) {
            geometryMisses++;
        }
        if ((mask & MISS_MATERIAL) != 0) {
            materialMisses++;
        }
        if ((mask & MISS_TEXTURE) != 0) {
            textureMisses++;
        }
        if ((mask & MISS_PENDING_BUILDS) != 0) {
            pendingBuildMisses++;
        }
        if ((mask & MISS_PENDING_TRIANGLES) != 0) {
            pendingTriangleMisses++;
        }
        if ((mask & MISS_CACHED_TRIANGLES) != 0) {
            cachedTriangleMisses++;
        }
        if ((mask & MISS_ACTIVE_CONTENT) != 0) {
            activeContentMisses++;
        }
    }

    record Key(
            RtSectionActiveViewAssembler.Snapshot activeView,
            long sceneRevision,
            long geometryRevision,
            long materialRevision,
            long textureRevision,
            long activeContentGeneration,
            int pendingBuilds,
            long pendingTriangles,
            long cachedTriangles
    ) {
        Key {
            Objects.requireNonNull(activeView, "activeView");
            if (sceneRevision < 0L || geometryRevision < 0L || materialRevision < 0L
                    || textureRevision < 0L || activeContentGeneration < 0L
                    || pendingBuilds < 0 || pendingTriangles < 0L || cachedTriangles < 0L) {
                throw new IllegalArgumentException("TLAS build input cache key values must not be negative");
            }
        }
    }

    record Lookup(Key key, RtSectionTlasBuildInput input, int missMask) {
        Lookup {
            Objects.requireNonNull(key, "key");
            if (missMask < 0) {
                throw new IllegalArgumentException("TLAS build input miss mask must not be negative");
            }
            if ((missMask == 0) != (input != null)) {
                throw new IllegalArgumentException("TLAS build input lookup hit/input state is inconsistent");
            }
        }

        boolean hit() {
            return missMask == 0;
        }
    }

    record Stats(
            long hits,
            long misses,
            long coldMisses,
            long activeViewMisses,
            long sceneMisses,
            long geometryMisses,
            long materialMisses,
            long textureMisses,
            long pendingBuildMisses,
            long pendingTriangleMisses,
            long cachedTriangleMisses,
            long activeContentMisses,
            boolean cached,
            long cachedTextureRevision
    ) {
        Stats {
            if (hits < 0L || misses < 0L || coldMisses < 0L || activeViewMisses < 0L
                    || sceneMisses < 0L || geometryMisses < 0L || materialMisses < 0L
                    || textureMisses < 0L || pendingBuildMisses < 0L
                    || pendingTriangleMisses < 0L || cachedTriangleMisses < 0L
                    || activeContentMisses < 0L || cachedTextureRevision < -1L) {
                throw new IllegalArgumentException("TLAS build input cache statistics must not be negative");
            }
        }

        static Stats empty() {
            return new Stats(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, false, -1L);
        }

        long calls() {
            return Math.addExact(hits, misses);
        }
    }
}

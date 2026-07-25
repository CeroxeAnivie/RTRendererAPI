package top.ceroxe.rt.renderer.scene;

import java.util.Objects;

/**
 * A renderer-neutral terrain invalidation carried by {@link RendererWorldPublication}.
 *
 * @param kind    invalidation category
 * @param minX    inclusive minimum x coordinate
 * @param minY    inclusive minimum y coordinate
 * @param minZ    inclusive minimum z coordinate
 * @param maxX    inclusive maximum x coordinate
 * @param maxY    inclusive maximum y coordinate
 * @param maxZ    inclusive maximum z coordinate
 * @param changed whether the observed mutation changed renderable state
 */
public record RendererWorldInvalidation(
        Kind kind,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ,
        boolean changed
) {
    /**
     * Validates the invalidation category and inclusive coordinate bounds.
     */
    public RendererWorldInvalidation {
        kind = Objects.requireNonNull(kind, "kind");
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("invalidation range is inverted");
        }
    }

    /**
     * Creates a section invalidation that also requires neighbor refresh.
     *
     * @param x section X coordinate
     * @param y section Y coordinate
     * @param z section Z coordinate
     * @return section-and-neighbors invalidation
     */
    public static RendererWorldInvalidation sectionDirtyWithNeighbors(int x, int y, int z) {
        return new RendererWorldInvalidation(Kind.SECTION_DIRTY_WITH_NEIGHBORS, x, y, z, x, y, z, true);
    }

    /**
     * Creates an inclusive section-range invalidation.
     *
     * @param minX minimum section X
     * @param minY minimum section Y
     * @param minZ minimum section Z
     * @param maxX maximum section X
     * @param maxY maximum section Y
     * @param maxZ maximum section Z
     * @return section-range invalidation
     */
    public static RendererWorldInvalidation sectionRangeDirty(
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ
    ) {
        return new RendererWorldInvalidation(Kind.SECTION_RANGE_DIRTY, minX, minY, minZ, maxX, maxY, maxZ, true);
    }

    /**
     * Creates an invalidation for one observed block mutation.
     *
     * @param blockX  block X coordinate
     * @param blockY  block Y coordinate
     * @param blockZ  block Z coordinate
     * @param changed whether renderable state changed
     * @return block-mutation invalidation
     */
    public static RendererWorldInvalidation blockMutation(int blockX, int blockY, int blockZ, boolean changed) {
        return new RendererWorldInvalidation(Kind.BLOCK_MUTATION, blockX, blockY, blockZ, blockX, blockY, blockZ, changed);
    }

    /**
     * Creates an invalidation for complete chunk payload replacement.
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return chunk-replacement invalidation
     */
    public static RendererWorldInvalidation chunkPacketReplacement(int chunkX, int chunkZ) {
        return new RendererWorldInvalidation(Kind.CHUNK_PACKET_REPLACEMENT, chunkX, 0, chunkZ, chunkX, 0, chunkZ, true);
    }

    /**
     * Renderer-neutral invalidation category.
     */
    public enum Kind {
        /**
         * One section and its dependency neighbors became dirty.
         */
        SECTION_DIRTY_WITH_NEIGHBORS,
        /**
         * An inclusive section range became dirty.
         */
        SECTION_RANGE_DIRTY,
        /**
         * One block mutation was observed.
         */
        BLOCK_MUTATION,
        /**
         * A complete chunk payload was replaced.
         */
        CHUNK_PACKET_REPLACEMENT
    }
}

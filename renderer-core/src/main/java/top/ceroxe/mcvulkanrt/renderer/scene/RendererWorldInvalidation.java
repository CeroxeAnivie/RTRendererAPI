package top.ceroxe.mcvulkanrt.renderer.scene;

import java.util.Objects;

/** A renderer-neutral terrain invalidation carried by {@link RendererWorldPublication}. */
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
    public RendererWorldInvalidation {
        kind = Objects.requireNonNull(kind, "kind");
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("invalidation range is inverted");
        }
    }

    public static RendererWorldInvalidation sectionDirtyWithNeighbors(int x, int y, int z) {
        return new RendererWorldInvalidation(Kind.SECTION_DIRTY_WITH_NEIGHBORS, x, y, z, x, y, z, true);
    }

    public static RendererWorldInvalidation sectionRangeDirty(
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ
    ) {
        return new RendererWorldInvalidation(Kind.SECTION_RANGE_DIRTY, minX, minY, minZ, maxX, maxY, maxZ, true);
    }

    public static RendererWorldInvalidation blockMutation(int blockX, int blockY, int blockZ, boolean changed) {
        return new RendererWorldInvalidation(Kind.BLOCK_MUTATION, blockX, blockY, blockZ, blockX, blockY, blockZ, changed);
    }

    public static RendererWorldInvalidation chunkPacketReplacement(int chunkX, int chunkZ) {
        return new RendererWorldInvalidation(Kind.CHUNK_PACKET_REPLACEMENT, chunkX, 0, chunkZ, chunkX, 0, chunkZ, true);
    }

    public enum Kind {
        SECTION_DIRTY_WITH_NEIGHBORS,
        SECTION_RANGE_DIRTY,
        BLOCK_MUTATION,
        CHUNK_PACKET_REPLACEMENT
    }
}

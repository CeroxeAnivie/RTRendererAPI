package top.ceroxe.mcvulkanrt.renderer.scene;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One host-neutral, ordered publication of a renderable world.
 *
 * <p>The host may observe cache membership, asynchronous section compilation,
 * and block mutations on different callbacks. Those callbacks are not allowed
 * to mutate renderer state independently. They first become facts of this
 * publication, which has one world identity and one monotonic sequence. The
 * complete terrain residency remains authoritative; payloads for a retired
 * section are rejected instead of accidentally reviving it downstream.</p>
 */
public record RendererWorldPublication(
        long worldEpoch,
        long publicationSequence,
        TerrainResidencySnapshot terrainResidency,
        List<ChunkSnapshot> foregroundChunkSnapshots,
        List<SectionVoxelSnapshot> blockMutationSnapshots,
        List<RendererWorldInvalidation> invalidations
) {
    public RendererWorldPublication {
        if (worldEpoch <= 0L) {
            throw new IllegalArgumentException("world epoch must be positive");
        }
        if (publicationSequence < 0L) {
            throw new IllegalArgumentException("publication sequence must not be negative");
        }
        terrainResidency = Objects.requireNonNull(terrainResidency, "terrainResidency");
        foregroundChunkSnapshots = immutableChunks(foregroundChunkSnapshots, terrainResidency);
        blockMutationSnapshots = immutableSections(blockMutationSnapshots, terrainResidency);
        invalidations = immutableInvalidations(invalidations, terrainResidency);
    }

    public static RendererWorldPublication empty(long worldEpoch) {
        return new RendererWorldPublication(worldEpoch, 0L, TerrainResidencySnapshot.empty(), List.of(), List.of(), List.of());
    }

    private static List<ChunkSnapshot> immutableChunks(
            List<ChunkSnapshot> snapshots,
            TerrainResidencySnapshot residency
    ) {
        Objects.requireNonNull(snapshots, "foregroundChunkSnapshots");
        Map<ChunkKey, ChunkSnapshot> distinct = new LinkedHashMap<>();
        for (ChunkSnapshot snapshot : snapshots) {
            ChunkSnapshot checked = Objects.requireNonNull(snapshot, "foreground chunk snapshot");
            if (!residency.residentChunks().contains(checked.chunkKey())) {
                throw new IllegalArgumentException("foreground chunk is outside published residency: " + checked.chunkKey());
            }
            if (distinct.putIfAbsent(checked.chunkKey(), checked) != null) {
                throw new IllegalArgumentException("duplicate foreground chunk snapshot: " + checked.chunkKey());
            }
            for (SectionVoxelSnapshot section : checked.sections()) {
                if (!section.key().chunkKey().equals(checked.chunkKey())) {
                    throw new IllegalArgumentException("section belongs to a different chunk: " + section.key());
                }
                requireLiveSection(section, residency);
            }
        }
        return List.copyOf(distinct.values());
    }

    private static List<SectionVoxelSnapshot> immutableSections(
            List<SectionVoxelSnapshot> snapshots,
            TerrainResidencySnapshot residency
    ) {
        Objects.requireNonNull(snapshots, "blockMutationSnapshots");
        Map<SectionKey, SectionVoxelSnapshot> distinct = new LinkedHashMap<>();
        for (SectionVoxelSnapshot snapshot : snapshots) {
            SectionVoxelSnapshot checked = Objects.requireNonNull(snapshot, "block mutation snapshot");
            requireLiveSection(checked, residency);
            if (distinct.putIfAbsent(checked.key(), checked) != null) {
                throw new IllegalArgumentException("duplicate block mutation snapshot: " + checked.key());
            }
        }
        return List.copyOf(distinct.values());
    }

    /**
     * Invalidations are causal facts, not an independent lifetime channel.
     * Keeping this validation beside snapshot admission prevents a host from
     * reviving a retired chunk by placing only a dirty marker in a newer
     * publication. Range invalidations may overlap the resident boundary; the
     * renderer ingress clips their exact section work against geometry.
     */
    private static List<RendererWorldInvalidation> immutableInvalidations(
            List<RendererWorldInvalidation> invalidations,
            TerrainResidencySnapshot residency
    ) {
        Objects.requireNonNull(invalidations, "invalidations");
        for (RendererWorldInvalidation invalidation : invalidations) {
            RendererWorldInvalidation checked = Objects.requireNonNull(invalidation, "world invalidation");
            if (!touchesResidentTerrain(checked, residency)) {
                throw new IllegalArgumentException(
                        "world invalidation is outside published residency: " + checked
                );
            }
        }
        return List.copyOf(invalidations);
    }

    private static boolean touchesResidentTerrain(
            RendererWorldInvalidation invalidation,
            TerrainResidencySnapshot residency
    ) {
        return switch (invalidation.kind()) {
            case BLOCK_MUTATION -> residency.containsResidentChunk(
                    Math.floorDiv(invalidation.minX(), SectionVoxelSnapshot.SECTION_SIZE),
                    Math.floorDiv(invalidation.minZ(), SectionVoxelSnapshot.SECTION_SIZE)
            );
            case SECTION_DIRTY_WITH_NEIGHBORS, CHUNK_PACKET_REPLACEMENT -> residency.containsResidentChunk(
                    invalidation.minX(), invalidation.minZ()
            );
            case SECTION_RANGE_DIRTY -> residency.residentChunks().stream().anyMatch(chunk ->
                    chunk.x() >= invalidation.minX() && chunk.x() <= invalidation.maxX()
                            && chunk.z() >= invalidation.minZ() && chunk.z() <= invalidation.maxZ()
            );
        };
    }

    private static void requireLiveSection(SectionVoxelSnapshot snapshot, TerrainResidencySnapshot residency) {
        SectionKey key = snapshot.key();
        if (!residency.residentChunks().contains(key.chunkKey())) {
            throw new IllegalArgumentException("section snapshot is outside published residency: " + key);
        }
    }
}

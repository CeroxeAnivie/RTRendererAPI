package top.ceroxe.rt.renderer.scene;

import top.ceroxe.rt.renderer.rt.material.RtTextureCatalog;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable sparse model facts resolved while classifying one section.
 */
public final class SectionModelFacts {
    private static final SectionModelFacts UNAVAILABLE = new SectionModelFacts(false, new int[0], new RtTextureCatalog.ModelQuads[0]);
    private static final SectionModelFacts COMPLETE_EMPTY = new SectionModelFacts(true, new int[0], new RtTextureCatalog.ModelQuads[0]);

    private final boolean complete;
    private final int[] blockIndices;
    private final RtTextureCatalog.ModelQuads[] models;

    private SectionModelFacts(
            boolean complete,
            int[] blockIndices,
            RtTextureCatalog.ModelQuads[] models
    ) {
        this.complete = complete;
        this.blockIndices = blockIndices;
        this.models = models;
    }

    /**
     * Returns the singleton indicating unavailable model resolution.
     *
     * @return singleton indicating that model resolution was not completed
     */
    public static SectionModelFacts unavailable() {
        return UNAVAILABLE;
    }

    static SectionModelFacts complete(
            int[] blockIndices,
            RtTextureCatalog.ModelQuads[] models,
            int count
    ) {
        Objects.requireNonNull(blockIndices, "blockIndices");
        Objects.requireNonNull(models, "models");
        if (count < 0 || count > blockIndices.length || count > models.length) {
            throw new IllegalArgumentException("model count exceeds source arrays");
        }
        if (count == 0) {
            return COMPLETE_EMPTY;
        }

        int[] ownedIndices = Arrays.copyOf(blockIndices, count);
        RtTextureCatalog.ModelQuads[] ownedModels = Arrays.copyOf(models, count);
        int previousIndex = -1;
        for (int index = 0; index < count; index++) {
            int blockIndex = ownedIndices[index];
            if (blockIndex <= previousIndex || blockIndex >= SectionVoxelSnapshot.BLOCKS_PER_SECTION) {
                throw new IllegalArgumentException("model block indices must be sorted and section-local");
            }
            RtTextureCatalog.ModelQuads model = Objects.requireNonNull(
                    ownedModels[index],
                    "model"
            );
            previousIndex = blockIndex;
        }
        return new SectionModelFacts(true, ownedIndices, ownedModels);
    }

    /**
     * Reports whether model resolution completed authoritatively.
     *
     * @return whether this object is an authoritative, complete resolution
     */
    public boolean complete() {
        return complete;
    }

    /**
     * Resolves the sparse model captured for one section-local block.
     *
     * @param blockIndex section-local block index
     * @return resolved model, or {@code null} when the block has no sparse model entry
     * @throws IndexOutOfBoundsException if the index is outside the section
     */
    public RtTextureCatalog.ModelQuads modelAt(int blockIndex) {
        if (blockIndex < 0 || blockIndex >= SectionVoxelSnapshot.BLOCKS_PER_SECTION) {
            throw new IndexOutOfBoundsException("blockIndex=" + blockIndex);
        }
        int index = Arrays.binarySearch(blockIndices, blockIndex);
        return index < 0 ? null : models[index];
    }

    /**
     * Counts resolved sparse model entries.
     *
     * @return number of resolved sparse model entries
     */
    public int modelCount() {
        return blockIndices.length;
    }

    /**
     * Estimates sparse index and reference storage.
     *
     * @return conservative heap-footprint estimate for index/reference storage
     */
    public long estimatedBytes() {
        return (long) blockIndices.length * (Integer.BYTES + Long.BYTES);
    }
}

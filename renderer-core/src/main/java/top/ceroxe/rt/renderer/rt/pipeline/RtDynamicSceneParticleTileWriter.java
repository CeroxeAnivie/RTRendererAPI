package top.ceroxe.rt.renderer.rt.pipeline;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Writes the compact particle-tile indirection table after visibility planning has completed.
 */
final class RtDynamicSceneParticleTileWriter {
    private static final int RECORD_BYTES = 16;

    private RtDynamicSceneParticleTileWriter() {
    }

    static void write(
            ByteBuffer target,
            RtParticleTilePlanner.UploadIndex tiles,
            int columns,
            int rows,
            int tileCount,
            int maxReferences,
            int infoRecord,
            int rangesRecord,
            int indicesRecord
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(tiles, "tiles");
        if (columns <= 0 || rows <= 0 || tileCount != columns * rows || maxReferences < 0
                || infoRecord < 0 || rangesRecord < 0 || indicesRecord < 0
                || tiles.offsets().length != tileCount || tiles.counts().length != tileCount
                || tiles.referenceCount() > maxReferences) {
            throw new IllegalArgumentException("particle tile writer arguments do not describe the fixed tile ABI");
        }
        target.position(infoRecord * RECORD_BYTES);
        putUvec4(target, columns, rows, tiles.referenceCount(), tiles.fallbackToFullScan() ? 1 : 0);
        target.position(rangesRecord * RECORD_BYTES);
        int[] offsets = tiles.offsets();
        int[] counts = tiles.counts();
        for (int tile = 0; tile < tileCount; tile++) {
            putUvec4(target, offsets[tile], counts[tile], 0, 0);
        }
        target.position(indicesRecord * RECORD_BYTES);
        int[] references = tiles.references();
        int referenceCount = tiles.referenceCount();
        for (int record = 0; record < maxReferences / 4; record++) {
            int base = record * 4;
            putUvec4(target,
                    base < referenceCount ? references[base] : 0,
                    base + 1 < referenceCount ? references[base + 1] : 0,
                    base + 2 < referenceCount ? references[base + 2] : 0,
                    base + 3 < referenceCount ? references[base + 3] : 0);
        }
    }

    private static void putUvec4(ByteBuffer target, int x, int y, int z, int w) {
        target.putInt(x);
        target.putInt(y);
        target.putInt(z);
        target.putInt(w);
    }
}

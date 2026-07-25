package top.ceroxe.rt.renderer.rt.pipeline;

import top.ceroxe.rt.renderer.DynamicRenderScene;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Maintains the bounded open-addressed block-decal lookup table used by the hit shader.
 */
final class RtDynamicSceneDecalWriter {
    private static final int RECORD_BYTES = 16;

    private RtDynamicSceneDecalWriter() {
    }

    static void write(
            ByteBuffer target,
            DynamicRenderScene scene,
            int decalCount,
            int tableSlots,
            int infoRecord,
            int boundsMinRecord,
            int boundsMaxRecord,
            int decalRecord,
            int decalOffsetRecord
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(scene, "scene");
        if (decalCount < 0 || decalCount > scene.blockDecals().size() || decalCount > tableSlots
                || Integer.bitCount(tableSlots) != 1 || infoRecord < 0 || boundsMinRecord < 0
                || boundsMaxRecord < 0 || decalRecord < 0 || decalOffsetRecord < 0) {
            throw new IllegalArgumentException("decal writer arguments do not describe the fixed decal-table ABI");
        }
        target.position(infoRecord * RECORD_BYTES);
        putUvec4(target, decalCount, tableSlots - 1, 0, 0);
        int minimumBlockX = Integer.MAX_VALUE;
        int minimumBlockY = Integer.MAX_VALUE;
        int minimumBlockZ = Integer.MAX_VALUE;
        int maximumBlockX = Integer.MIN_VALUE;
        int maximumBlockY = Integer.MIN_VALUE;
        int maximumBlockZ = Integer.MIN_VALUE;
        for (int index = 0; index < decalCount; index++) {
            DynamicRenderScene.BlockDecal decal = scene.blockDecals().get(index);
            minimumBlockX = Math.min(minimumBlockX, decal.blockX());
            minimumBlockY = Math.min(minimumBlockY, decal.blockY());
            minimumBlockZ = Math.min(minimumBlockZ, decal.blockZ());
            maximumBlockX = Math.max(maximumBlockX, decal.blockX());
            maximumBlockY = Math.max(maximumBlockY, decal.blockY());
            maximumBlockZ = Math.max(maximumBlockZ, decal.blockZ());
        }
        target.position(boundsMinRecord * RECORD_BYTES);
        putUvec4(target, decalCount == 0 ? 0 : minimumBlockX - 1, decalCount == 0 ? 0 : minimumBlockY - 1,
                decalCount == 0 ? 0 : minimumBlockZ - 1, 0);
        target.position(boundsMaxRecord * RECORD_BYTES);
        putUvec4(target, decalCount == 0 ? 0 : maximumBlockX + 1, decalCount == 0 ? 0 : maximumBlockY + 1,
                decalCount == 0 ? 0 : maximumBlockZ + 1, 0);
        boolean[] occupied = new boolean[tableSlots];
        for (int index = 0; index < decalCount; index++) {
            DynamicRenderScene.BlockDecal decal = scene.blockDecals().get(index);
            int slot = tableSlot(decal.blockX(), decal.blockY(), decal.blockZ(), occupied);
            target.position((decalRecord + slot) * RECORD_BYTES);
            putUvec4(target, decal.blockX(), decal.blockY(), decal.blockZ(), decal.textureId());
            target.position((decalOffsetRecord + slot) * RECORD_BYTES);
            putVec4(target, decal.offsetX(), decal.offsetY(), decal.offsetZ(), decal.progress());
        }
    }

    static int tableSlot(int blockX, int blockY, int blockZ, boolean[] occupiedSlots) {
        Objects.requireNonNull(occupiedSlots, "occupiedSlots");
        if (occupiedSlots.length == 0 || Integer.bitCount(occupiedSlots.length) != 1) {
            throw new IllegalArgumentException("decal table occupancy must have a power-of-two capacity");
        }
        int hash = blockX * 0x8DA6_B343 ^ blockY * 0xD816_3841 ^ blockZ * 0xCB1A_B31F;
        hash ^= hash >>> 16;
        int mask = occupiedSlots.length - 1;
        for (int probe = 0; probe < occupiedSlots.length; probe++) {
            int slot = (hash + probe) & mask;
            if (!occupiedSlots[slot]) {
                occupiedSlots[slot] = true;
                return slot;
            }
        }
        throw new IllegalStateException("decal hash table is full");
    }

    private static void putUvec4(ByteBuffer target, int x, int y, int z, int w) {
        target.putInt(x);
        target.putInt(y);
        target.putInt(z);
        target.putInt(w);
    }

    private static void putVec4(ByteBuffer target, float x, float y, float z, float w) {
        target.putFloat(x);
        target.putFloat(y);
        target.putFloat(z);
        target.putFloat(w);
    }
}

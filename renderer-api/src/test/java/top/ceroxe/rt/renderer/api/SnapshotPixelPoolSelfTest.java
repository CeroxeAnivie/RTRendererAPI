package top.ceroxe.rt.renderer.api;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;

/** Retained derived views must keep pooled pixels immutable, including under GC pressure. */
public final class SnapshotPixelPoolSelfTest {
    public static void main(String[] args) {
        SnapshotPixelPool pool = new SnapshotPixelPool(4096);
        ByteBuffer retained = retainSlice(pool);
        var typed = retained.asIntBuffer();
        for (int round = 0; round < 256; round++) {
            byte[] input = new byte[4096];
            java.util.Arrays.fill(input, (byte) round);
            ByteBuffer copied = pool.copy(ByteBuffer.wrap(input), input.length);
            input[0] ^= 1;
            require(copied.get(0) == (byte) round, "source alias");
            if (round % 64 == 0) System.gc();
            require(retained.get(0) == 0x35 && retained.get(2047) == 0x35
                    && typed.get(0) == 0x35353535, "live slice storage was recycled");
        }
        require(pool.allocatedBytes() == (SnapshotPixelPool.class.getModule().isNativeAccessEnabled() ? 4096 : 0),
                "native pool cap or native-access fallback");
        try { retained.put(0, (byte) 0); throw new AssertionError("writable snapshot"); }
        catch (ReadOnlyBufferException expected) { }
        byte[] rgba = {1, 2, 3, 4};
        FrameSnapshot original = FrameSnapshot.builder().frameSequence(1).renderedSceneRevision(0)
                .extent(1, 1).pixels(rgba).build();
        FrameSnapshot copied = FrameSnapshot.copyOf(original);
        rgba[0] = 99;
        require(copied.pixels().get(0) == 1 && copied.byteCount() == 4, "snapshot pixels or metadata changed");
        require(copied.pixels().position() == 0, "pixel cursor leaked");
        System.out.println("SnapshotPixelPoolSelfTest passed: source independence, retained slice/typed view, cap, fallback and read-only cursors");
    }
    private static ByteBuffer retainSlice(SnapshotPixelPool pool) {
        byte[] input = new byte[4096];
        java.util.Arrays.fill(input, (byte) 0x35);
        ByteBuffer root = pool.copy(ByteBuffer.wrap(input), input.length);
        return root.duplicate().position(1024).limit(3072).slice().asReadOnlyBuffer();
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

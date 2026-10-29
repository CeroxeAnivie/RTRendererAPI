package top.ceroxe.rt.renderer.api;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/** Bounded storage for immutable snapshots; a lease follows every derived pixel view. */
final class SnapshotPixelPool {
    static final SnapshotPixelPool SHARED = new SnapshotPixelPool(256L << 20, 4 << 20);
    private final long limit;
    private final int minimumBytes;
    private long allocated;
    private final ArrayDeque<Block> available = new ArrayDeque<>();

    SnapshotPixelPool(long limit) { this(limit, 0); }
    SnapshotPixelPool(long limit, int minimumBytes) { this.limit = limit; this.minimumBytes = minimumBytes; }

    ByteBuffer copy(ByteBuffer source, int bytes) {
        Block block = SnapshotPixelPool.class.getModule().isNativeAccessEnabled() ? acquire(bytes) : null;
        if (block == null) {
            byte[] copy = new byte[bytes];
            source.get(copy);
            return ByteBuffer.wrap(copy).asReadOnlyBuffer();
        }
        boolean leaseRegistered = false;
        try {
            ByteBuffer destination = block.memory.asByteBuffer();
            destination.limit(bytes).put(source);
            // The automatic arena is retained by the segment-backed buffer and ALL of its
            // duplicates/slices. Watching the snapshot object alone would recycle too early.
            Arena lease = Arena.ofAuto();
            MemorySegment pixels = MemorySegment.ofAddress(block.memory.address()).reinterpret(bytes, lease,
                    ignored -> release(block));
            leaseRegistered = true;
            return pixels.asReadOnly().asByteBuffer();
        } catch (RuntimeException | Error failure) {
            if (!leaseRegistered) release(block);
            throw failure;
        }
    }

    private synchronized Block acquire(int bytes) {
        if (bytes < minimumBytes) return null;
        for (var it = available.iterator(); it.hasNext();) {
            Block block = it.next();
            if (block.memory.byteSize() == bytes) { it.remove(); return block; }
        }
        if (bytes > limit - allocated) return null;
        Arena owner = Arena.ofShared();
        try {
            Block block = new Block(owner, owner.allocate(bytes, 16));
            allocated += bytes;
            return block;
        } catch (RuntimeException | Error failure) { owner.close(); throw failure; }
    }
    private synchronized void release(Block block) { available.addLast(block); }
    synchronized long allocatedBytes() { return allocated; }
    private record Block(Arena owner, MemorySegment memory) { }
}

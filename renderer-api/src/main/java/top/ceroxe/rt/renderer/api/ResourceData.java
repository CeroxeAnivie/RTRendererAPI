package top.ceroxe.rt.renderer.api;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/** Immutable resource snapshot with bounded page sharing and defensive ownership. */
public final class ResourceData {
    private static final int PAGE_BYTES = 16 * 1024;
    private final ByteBuffer[] pages;
    private final int size;
    private final ByteOrder order;
    private volatile ByteBuffer contiguous;

    /** Copies remaining bytes and retains their declared byte order. */
    public ResourceData(ByteBuffer bytes) {
        ByteBuffer source = Objects.requireNonNull(bytes, "bytes").slice().order(bytes.order());
        size = source.remaining(); order = source.order();
        ByteBuffer copy = ByteBuffer.allocateDirect(size).order(order);
        copy.put(source).flip();
        contiguous = copy.asReadOnlyBuffer().order(order);
        pages = new ByteBuffer[(int) ((size + (long) PAGE_BYTES - 1) / PAGE_BYTES)];
        for (int i = 0; i < pages.length; i++) {
            int offset = i * PAGE_BYTES;
            pages[i] = contiguous.slice(offset, Math.min(PAGE_BYTES, size - offset)).order(order);
        }
    }

    private ResourceData(ByteBuffer[] pages, int size, ByteOrder order) {
        this.pages = pages; this.size = size; this.order = order;
    }

    /** An immutable replacement range; later updates win when ranges overlap. */
    public record Update(int byteOffset, ResourceData data) {
        public Update {
            if (byteOffset < 0) throw new IllegalArgumentException("negative update offset");
            Objects.requireNonNull(data, "data");
            if (data.byteSize() == 0) throw new IllegalArgumentException("empty update");
        }
    }

    /**
     * Creates an independent immutable snapshot, copying only touched 16 KiB pages. All ranges
     * are validated before copying. Snapshots keep no parent chain, are safe across threads and
     * renderers, and remain valid after callers discard every earlier snapshot. Size is fixed;
     * construct a new ResourceData to resize. Byte order describes interpretation, never conversion.
     */
    public ResourceData withUpdates(List<Update> updates) {
        List<Update> checked = List.copyOf(updates);
        for (Update update : checked) {
            if (update.byteOffset > size - update.data.size) throw new IllegalArgumentException("update exceeds snapshot");
        }
        if (checked.isEmpty()) return this;
        ByteBuffer[] next = pages.clone();
        boolean[] copied = new boolean[pages.length];
        for (Update update : checked) {
            ByteBuffer source = update.data.bytes();
            int destination = update.byteOffset;
            while (source.hasRemaining()) {
                int page = destination / PAGE_BYTES, local = destination % PAGE_BYTES;
                if (!copied[page]) {
                    ByteBuffer replacement = ByteBuffer.allocateDirect(pages[page].remaining()).order(order);
                    replacement.put(pages[page].duplicate()).flip();
                    next[page] = replacement; copied[page] = true;
                }
                int count = Math.min(source.remaining(), next[page].capacity() - local);
                next[page].duplicate().position(local).put(source.slice(source.position(), count));
                source.position(source.position() + count); destination += count;
            }
        }
        for (int i = 0; i < next.length; i++) if (copied[i]) next[i] = next[i].asReadOnlyBuffer().order(order);
        return new ResourceData(next, size, order);
    }

    /**
     * Visits changed pages at their exact byte offsets. A null or differently sized previous
     * snapshot requests a complete upload. Views are independently positioned and readonly. The
     * caller must serialize writes with GPU ownership and invalidate its prior snapshot on failure.
     */
    public void forEachChangedRange(ResourceData previous, BiConsumer<Integer, ByteBuffer> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        if (previous == this) return;
        if (size == 0) return;
        if (previous == null || previous.size != size) {
            consumer.accept(0, bytes());
            return;
        }
        for (int i = 0; i < pages.length; i++) {
            if (previous.pages[i] != pages[i]) {
                consumer.accept(i * PAGE_BYTES, pages[i].duplicate().order(order));
            }
        }
    }

    /** Returns an independent readonly contiguous view positioned at zero. */
    public ByteBuffer bytes() {
        ByteBuffer value = contiguous;
        if (value == null) {
            synchronized (this) {
                value = contiguous;
                if (value == null) {
                    ByteBuffer copy = ByteBuffer.allocateDirect(size).order(order);
                    for (ByteBuffer page : pages) copy.put(page.duplicate());
                    copy.flip();
                    contiguous = value = copy.asReadOnlyBuffer().order(order);
                }
            }
        }
        return value.duplicate().order(order);
    }

    /** Returns the exact byte count. */
    public int byteSize() { return size; }
    /** Returns byte order retained from the original buffer. */
    public ByteOrder byteOrder() { return order; }
}

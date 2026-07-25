package top.ceroxe.rt.renderer.rt.acceleration;

import java.util.*;

/**
 * Immutable paged TLAS instance table with sparse copy-on-write replacement.
 *
 * <p>Each update retains untouched pages by identity. This preserves the
 * previous instance generation for in-flight Vulkan work without turning a
 * single animated model transform into a full-capacity allocation.</p>
 */
final class RtPersistentTlasInstanceTable
        extends AbstractList<RtAccelerationStructure.TlasInstance>
        implements RandomAccess, RtImmutableTlasInstances {
    private static final int PAGE_SHIFT = 6;
    private static final int PAGE_SIZE = 1 << PAGE_SHIFT;
    private static final int PAGE_MASK = PAGE_SIZE - 1;

    private final RtAccelerationStructure.TlasInstance[][] pages;
    private final int size;

    private RtPersistentTlasInstanceTable(RtAccelerationStructure.TlasInstance[][] pages, int size) {
        this.pages = Objects.requireNonNull(pages, "pages");
        if (size <= 0 || pages.length != pageCount(size)) {
            throw new IllegalArgumentException("invalid persistent TLAS instance table size");
        }
        this.size = size;
    }

    static List<RtAccelerationStructure.TlasInstance> update(
            List<RtAccelerationStructure.TlasInstance> previous,
            List<RtAccelerationStructure.TlasInstance> slots,
            BitSet dirtyTableSlots
    ) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(slots, "slots");
        Objects.requireNonNull(dirtyTableSlots, "dirtyTableSlots");
        if (slots.isEmpty()) {
            throw new IllegalArgumentException("dynamic TLAS slot table must retain its legacy slot");
        }
        if (!(previous instanceof RtPersistentTlasInstanceTable persistent) || persistent.size != slots.size()) {
            return copyOf(slots);
        }

        RtAccelerationStructure.TlasInstance[][] nextPages = persistent.pages.clone();
        BitSet copiedPages = new BitSet(nextPages.length);
        for (int tableSlot = dirtyTableSlots.nextSetBit(0);
             tableSlot >= 0;
             tableSlot = dirtyTableSlots.nextSetBit(tableSlot + 1)) {
            if (tableSlot >= slots.size()) {
                break;
            }
            replace(nextPages, copiedPages, tableSlot, slots.get(tableSlot));
        }
        return copiedPages.isEmpty() ? persistent : new RtPersistentTlasInstanceTable(nextPages, persistent.size);
    }

    private static RtPersistentTlasInstanceTable copyOf(List<RtAccelerationStructure.TlasInstance> slots) {
        int size = slots.size();
        RtAccelerationStructure.TlasInstance[][] pages =
                new RtAccelerationStructure.TlasInstance[pageCount(size)][PAGE_SIZE];
        for (int index = 0; index < size; index++) {
            pages[index >>> PAGE_SHIFT][index & PAGE_MASK] = Objects.requireNonNull(slots.get(index), "TLAS instance");
        }
        return new RtPersistentTlasInstanceTable(pages, size);
    }

    private static void replace(
            RtAccelerationStructure.TlasInstance[][] pages,
            BitSet copiedPages,
            int index,
            RtAccelerationStructure.TlasInstance instance
    ) {
        int pageIndex = index >>> PAGE_SHIFT;
        if (!copiedPages.get(pageIndex)) {
            pages[pageIndex] = pages[pageIndex].clone();
            copiedPages.set(pageIndex);
        }
        pages[pageIndex][index & PAGE_MASK] = Objects.requireNonNull(instance, "TLAS instance");
    }

    private static int pageCount(int size) {
        return Math.addExact(size, PAGE_MASK) >>> PAGE_SHIFT;
    }

    @Override
    public RtAccelerationStructure.TlasInstance get(int index) {
        Objects.checkIndex(index, size);
        return pages[index >>> PAGE_SHIFT][index & PAGE_MASK];
    }

    @Override
    public int size() {
        return size;
    }
}

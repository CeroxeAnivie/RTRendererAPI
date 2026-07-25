package top.ceroxe.rt.renderer;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;

/**
 * Immutable authoritative publication of the dynamic-model transform lane.
 *
 * <p>Membership/topology and object-to-world transforms have different update frequencies and
 * different consumers. Keeping transforms inside the membership publication made a late native
 * consumer observe the transform from the last membership change, not the latest animation
 * frame. This value is the transform lane's sole cold-rebase authority. Fixed-size copy-on-write
 * pages preserve older generations held by in-flight work while copying only pages touched by
 * the current scatter delta.</p>
 */
public final class DynamicModelTransformSnapshot {
    /**
     * 每个对象到世界空间 3x4 变换包含的浮点分量数。
     */
    public static final int COMPONENTS = 12;

    private static final int PAGE_SLOT_SHIFT = 6;
    private static final int PAGE_SLOT_COUNT = 1 << PAGE_SLOT_SHIFT;
    private static final int PAGE_SLOT_MASK = PAGE_SLOT_COUNT - 1;
    private static final int PAGE_FLOAT_COUNT = PAGE_SLOT_COUNT * COMPONENTS;
    private static final DynamicModelTransformSnapshot EMPTY =
            new DynamicModelTransformSnapshot(0L, 0, new float[0][]);

    private final long revision;
    private final int physicalSlotCount;
    private final float[][] pages;

    private DynamicModelTransformSnapshot(long revision, int physicalSlotCount, float[][] pages) {
        if (revision < 0L || physicalSlotCount < 0) {
            throw new IllegalArgumentException("dynamic transform identity must not be negative");
        }
        this.pages = Objects.requireNonNull(pages, "pages");
        if (pages.length != pageCount(physicalSlotCount)) {
            throw new IllegalArgumentException("dynamic transform page count does not match slot capacity");
        }
        for (float[] page : pages) {
            if (page == null || page.length != PAGE_FLOAT_COUNT) {
                throw new IllegalArgumentException("dynamic transform pages must have a fixed shape");
            }
        }
        this.revision = revision;
        this.physicalSlotCount = physicalSlotCount;
    }

    /**
     * 返回修订号与物理容量均为零的共享初始快照。
     *
     * @return 空变换快照
     */
    public static DynamicModelTransformSnapshot empty() {
        return EMPTY;
    }

    /**
     * Compatibility bootstrap for complete legacy/test membership publications.
     */
    static DynamicModelTransformSnapshot fromMembership(
            long revision,
            DynamicRenderScene.DynamicModelSlotSnapshot membership
    ) {
        Objects.requireNonNull(membership, "membership");
        if (membership.physicalSlotCount() == 0) {
            return revision == 0L ? EMPTY : new DynamicModelTransformSnapshot(revision, 0, new float[0][]);
        }
        float[][] pages = allocatePages(membership.physicalSlotCount());
        for (int index = 0; index < membership.activeSlotCount(); index++) {
            copyObservation(pages, membership.slotAt(index), membership.instanceAt(index));
        }
        return new DynamicModelTransformSnapshot(revision, membership.physicalSlotCount(), pages);
    }

    static DynamicModelTransformSnapshot fromFrame(
            long revision,
            DynamicRenderScene.DynamicModelSlotSnapshot membership,
            int[] slots,
            byte[] dirtyMasks,
            float[] packedTransforms
    ) {
        DynamicModelTransformSnapshot snapshot = fromMembership(revision, membership);
        if (slots.length != dirtyMasks.length || packedTransforms.length != slots.length * COMPONENTS) {
            throw new IllegalArgumentException("invalid dynamic transform frame publication");
        }
        if (snapshot.pages.length == 0) {
            return snapshot;
        }
        float[][] pages = snapshot.pages.clone();
        BitSet copiedPages = new BitSet(pages.length);
        for (int update = 0; update < slots.length; update++) {
            if ((Byte.toUnsignedInt(dirtyMasks[update])
                    & DynamicRenderScene.DynamicModelFrameDelta.TRANSFORM) == 0) {
                continue;
            }
            int slot = Objects.checkIndex(slots[update], membership.physicalSlotCount());
            int pageIndex = slot >>> PAGE_SLOT_SHIFT;
            if (!copiedPages.get(pageIndex)) {
                pages[pageIndex] = pages[pageIndex].clone();
                copiedPages.set(pageIndex);
            }
            int destination = (slot & PAGE_SLOT_MASK) * COMPONENTS;
            int source = update * COMPONENTS;
            for (int component = 0; component < COMPONENTS; component++) {
                float value = packedTransforms[source + component];
                requireFinite(value);
                pages[pageIndex][destination + component] = value;
            }
        }
        return copiedPages.isEmpty()
                ? snapshot
                : new DynamicModelTransformSnapshot(revision, membership.physicalSlotCount(), pages);
    }

    private static void copyObservation(
            float[][] pages,
            int slot,
            DynamicRenderScene.DynamicModelObservation observation
    ) {
        int pageIndex = slot >>> PAGE_SLOT_SHIFT;
        int destination = (slot & PAGE_SLOT_MASK) * COMPONENTS;
        for (int component = 0; component < COMPONENTS; component++) {
            float value = observation.transformValue(component);
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("dynamic transform snapshot contains a non-finite value");
            }
            pages[pageIndex][destination + component] = value;
        }
    }

    private static float[][] allocatePages(int physicalSlotCount) {
        float[][] pages = new float[pageCount(physicalSlotCount)][];
        Arrays.setAll(pages, ignored -> new float[PAGE_FLOAT_COUNT]);
        return pages;
    }

    private static int pageCount(int physicalSlotCount) {
        return Math.addExact(physicalSlotCount, PAGE_SLOT_MASK) >>> PAGE_SLOT_SHIFT;
    }

    private static void requireFinite(float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("dynamic transform component must be finite");
        }
    }

    /**
     * Applies a sorted frame scatter publication. Only pages containing transform-dirty slots are
     * copied; capacity-only changes preserve all overlapping page identities.
     *
     * @param nextRevision          新发布的变换修订号
     * @param nextPhysicalSlotCount 新发布的物理槽容量
     * @param slots                 严格递增的待更新物理槽
     * @param dirtyMasks            与槽一一对应的更新标志
     * @param packedTransforms      每个槽连续包含 {@link #COMPONENTS} 个分量的变换负载
     * @return 结构共享后的下一份不可变快照；没有变化时返回当前对象
     */
    public DynamicModelTransformSnapshot withUpdates(
            long nextRevision,
            int nextPhysicalSlotCount,
            int[] slots,
            byte[] dirtyMasks,
            float[] packedTransforms
    ) {
        Objects.requireNonNull(slots, "slots");
        Objects.requireNonNull(dirtyMasks, "dirtyMasks");
        Objects.requireNonNull(packedTransforms, "packedTransforms");
        if (nextRevision < revision || nextPhysicalSlotCount < 0
                || slots.length != dirtyMasks.length
                || packedTransforms.length != slots.length * COMPONENTS) {
            throw new IllegalArgumentException("invalid dynamic transform scatter publication");
        }

        int nextPageCount = pageCount(nextPhysicalSlotCount);
        float[][] nextPages = new float[nextPageCount][];
        int sharedPages = Math.min(pages.length, nextPageCount);
        System.arraycopy(pages, 0, nextPages, 0, sharedPages);
        for (int page = sharedPages; page < nextPageCount; page++) {
            nextPages[page] = new float[PAGE_FLOAT_COUNT];
        }

        BitSet copiedPages = new BitSet(nextPageCount);
        int previousSlot = -1;
        boolean transformChanged = false;
        for (int update = 0; update < slots.length; update++) {
            int slot = slots[update];
            if (slot <= previousSlot || slot < 0 || slot >= nextPhysicalSlotCount) {
                throw new IllegalArgumentException("dynamic transform scatter slots must be sorted and valid");
            }
            previousSlot = slot;
            if ((Byte.toUnsignedInt(dirtyMasks[update])
                    & DynamicRenderScene.DynamicModelFrameDelta.TRANSFORM) == 0) {
                continue;
            }
            transformChanged = true;
            int pageIndex = slot >>> PAGE_SLOT_SHIFT;
            if (!copiedPages.get(pageIndex)) {
                nextPages[pageIndex] = nextPages[pageIndex].clone();
                copiedPages.set(pageIndex);
            }
            int destination = (slot & PAGE_SLOT_MASK) * COMPONENTS;
            int source = update * COMPONENTS;
            for (int component = 0; component < COMPONENTS; component++) {
                float value = packedTransforms[source + component];
                requireFinite(value);
                nextPages[pageIndex][destination + component] = value;
            }
        }
        if (transformChanged && nextRevision <= revision) {
            throw new IllegalArgumentException("transform changes must advance the transform revision");
        }
        if (!transformChanged && nextRevision != revision) {
            throw new IllegalArgumentException("transform revision advanced without a transform change");
        }
        if (nextPhysicalSlotCount == physicalSlotCount && copiedPages.isEmpty()) {
            return this;
        }
        return new DynamicModelTransformSnapshot(nextRevision, nextPhysicalSlotCount, nextPages);
    }

    /**
     * 返回变换发布修订号。
     *
     * @return 非负修订号
     */
    public long revision() {
        return revision;
    }

    /**
     * 返回快照可寻址的物理槽数量。
     *
     * @return 非负物理槽容量
     */
    public int physicalSlotCount() {
        return physicalSlotCount;
    }

    /**
     * 返回指定物理槽的一个 3x4 变换分量。
     *
     * @param slot      物理槽索引
     * @param component 0..11 行主序分量索引
     * @return 对应有限浮点分量
     */
    public float value(int slot, int component) {
        Objects.checkIndex(slot, physicalSlotCount);
        Objects.checkIndex(component, COMPONENTS);
        return pages[slot >>> PAGE_SLOT_SHIFT][(slot & PAGE_SLOT_MASK) * COMPONENTS + component];
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DynamicModelTransformSnapshot that
                && revision == that.revision
                && physicalSlotCount == that.physicalSlotCount
                && Arrays.deepEquals(pages, that.pages);
    }

    @Override
    public int hashCode() {
        int hash = Objects.hash(revision, physicalSlotCount);
        return 31 * hash + Arrays.deepHashCode(pages);
    }
}

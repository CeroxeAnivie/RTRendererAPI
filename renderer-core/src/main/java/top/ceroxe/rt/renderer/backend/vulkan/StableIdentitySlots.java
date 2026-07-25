package top.ceroxe.rt.renderer.backend.vulkan;

import it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.nio.LongBuffer;
import java.util.*;
import java.util.function.ToLongFunction;

/**
 * Stable sparse slot ownership for one GPUScene resource domain.
 *
 * <p>Preparation computes deterministic slot writes without mutating resident state. This permits
 * native admission to fail before publication. Existing identities retain their slots, and new
 * identities consume the lowest free slot before extending the high-water mark. The resulting
 * change set is directly suitable for scatter upload and never requires a dense world rewrite.</p>
 */
final class StableIdentitySlots<T> {
    private final ToLongFunction<T> idFunction;
    private final Long2IntOpenHashMap slotsById = new Long2IntOpenHashMap();
    private final ArrayList<T> valuesBySlot = new ArrayList<>();
    private final IntAVLTreeSet freeSlots = new IntAVLTreeSet();

    private long revision = -1L;
    private int liveCount;

    StableIdentitySlots(ToLongFunction<T> idFunction) {
        this.idFunction = Objects.requireNonNull(idFunction, "idFunction");
        slotsById.defaultReturnValue(-1);
    }

    Prepared<T> prepare(long nextRevision, boolean reset, Collection<T> upserts, LongBuffer removals) {
        if (nextRevision <= revision) {
            throw new IllegalArgumentException(
                    "resident slot revision must advance: current=" + revision + ", submitted=" + nextRevision
            );
        }
        List<T> orderedUpserts = new ArrayList<>(Objects.requireNonNull(upserts, "upserts"));
        for (T value : orderedUpserts) {
            Objects.requireNonNull(value, "upsert value");
        }
        orderedUpserts.sort(Comparator.comparingLong(idFunction));
        LongOpenHashSet upsertIds = new LongOpenHashSet(orderedUpserts.size());
        for (T value : orderedUpserts) {
            long id = idFunction.applyAsLong(value);
            if (id < 0L) {
                throw new IllegalArgumentException("resident upsert id must not be negative: " + id);
            }
            if (!upsertIds.add(id)) {
                throw new IllegalArgumentException("duplicate resident upsert id " + id);
            }
        }

        LongArrayList removedIds = reset
                ? resetRemovals(upsertIds)
                : explicitRemovals(Objects.requireNonNull(removals, "removals"));
        LongOpenHashSet removedIdSet = new LongOpenHashSet(removedIds);
        if (!reset) {
            for (long id : upsertIds) {
                if (removedIdSet.contains(id)) {
                    throw new IllegalArgumentException("resident identity cannot be upserted and removed: " + id);
                }
            }
        }
        IntAVLTreeSet releasedSlots = new IntAVLTreeSet();
        for (long id : removedIds) {
            int slot = slotsById.get(id);
            if (slot < 0) {
                throw new IllegalArgumentException("resident removal references missing id " + id);
            }
            releasedSlots.add(slot);
        }

        AvailableSlotCursor available = new AvailableSlotCursor(freeSlots.iterator(), releasedSlots.iterator());
        int nextSlot = valuesBySlot.size();
        int newIdentityCount = 0;
        ArrayList<SlotWrite<T>> writes = new ArrayList<>(orderedUpserts.size());
        IntOpenHashSet writtenSlots = new IntOpenHashSet(orderedUpserts.size());
        for (T value : orderedUpserts) {
            long id = idFunction.applyAsLong(value);
            int slot = slotsById.get(id);
            if (slot < 0) {
                newIdentityCount++;
                slot = available.poll();
                if (slot < 0) {
                    slot = nextSlot++;
                }
            }
            writtenSlots.add(slot);
            writes.add(new SlotWrite<>(slot, id, value));
        }

        IntAVLTreeSet cleared = new IntAVLTreeSet();
        for (long id : removedIds) {
            int slot = slotsById.get(id);
            if (!writtenSlots.contains(slot)) {
                cleared.add(slot);
            }
        }
        int nextLiveCount = liveCount - removedIds.size() + newIdentityCount;
        return new Prepared<>(
                this,
                revision,
                nextRevision,
                removedIds.toLongArray(),
                List.copyOf(writes),
                cleared.toIntArray(),
                nextLiveCount,
                nextSlot
        );
    }

    void validate(Prepared<T> prepared) {
        Prepared<T> checked = Objects.requireNonNull(prepared, "prepared");
        if (checked.owner() != this) {
            throw new IllegalArgumentException("prepared slots belong to another resident domain");
        }
        if (checked.committed()) {
            throw new IllegalStateException("prepared resident slots were already committed");
        }
        if (revision != checked.baseRevision()) {
            throw new IllegalStateException(
                    "resident slots advanced after preparation: expected=" + checked.baseRevision()
                            + ", actual=" + revision
            );
        }
    }

    void commitValidated(Prepared<T> prepared) {
        for (long id : prepared.removedIds) {
            int slot = slotsById.remove(id);
            valuesBySlot.set(slot, null);
            freeSlots.add(slot);
        }
        ensureCapacity(prepared.slotUpperBound());
        for (SlotWrite<T> write : prepared.writes()) {
            slotsById.put(write.id(), write.slot());
            valuesBySlot.set(write.slot(), write.value());
            freeSlots.remove(write.slot());
        }
        revision = prepared.revision();
        liveCount = prepared.liveCount();
        prepared.markCommitted();
    }

    int slot(long id) {
        return slotsById.get(id);
    }

    T valueAt(int slot) {
        return slot < 0 || slot >= valuesBySlot.size() ? null : valuesBySlot.get(slot);
    }

    int liveCount() {
        return liveCount;
    }

    int slotUpperBound() {
        return valuesBySlot.size();
    }

    private LongArrayList resetRemovals(LongOpenHashSet upsertIds) {
        LongArrayList removed = new LongArrayList();
        for (long id : slotsById.keySet()) {
            if (!upsertIds.contains(id)) {
                removed.add(id);
            }
        }
        removed.sort(Long::compare);
        return removed;
    }

    private LongArrayList explicitRemovals(LongBuffer removals) {
        LongArrayList removed = new LongArrayList(removals.remaining());
        LongOpenHashSet unique = new LongOpenHashSet(removals.remaining());
        while (removals.hasRemaining()) {
            long id = removals.get();
            if (id < 0L) {
                throw new IllegalArgumentException("resident removal id must not be negative: " + id);
            }
            if (!unique.add(id)) {
                throw new IllegalArgumentException("duplicate resident removal id " + id);
            }
            removed.add(id);
        }
        removed.sort(Long::compare);
        return removed;
    }

    private void ensureCapacity(int capacity) {
        while (valuesBySlot.size() < capacity) {
            valuesBySlot.add(null);
        }
    }

    /**
     * Merges persistent free slots with slots released by this transaction without copying either set.
     */
    private static final class AvailableSlotCursor {
        private final IntIterator free;
        private final IntIterator released;
        private int nextFree;
        private int nextReleased;
        private boolean hasFree;
        private boolean hasReleased;

        private AvailableSlotCursor(IntIterator free, IntIterator released) {
            this.free = free;
            this.released = released;
            advanceFree();
            advanceReleased();
        }

        private int poll() {
            if (!hasFree && !hasReleased) {
                return -1;
            }
            if (!hasReleased || hasFree && nextFree < nextReleased) {
                int result = nextFree;
                advanceFree();
                return result;
            }
            int result = nextReleased;
            advanceReleased();
            return result;
        }

        private void advanceFree() {
            hasFree = free.hasNext();
            if (hasFree) {
                nextFree = free.nextInt();
            }
        }

        private void advanceReleased() {
            hasReleased = released.hasNext();
            if (hasReleased) {
                nextReleased = released.nextInt();
            }
        }
    }

    record SlotWrite<T>(int slot, long id, T value) {
        SlotWrite {
            if (slot < 0 || id < 0L) {
                throw new IllegalArgumentException("resident slot write identity is invalid");
            }
            value = Objects.requireNonNull(value, "value");
        }
    }

    static final class Prepared<T> {
        private final StableIdentitySlots<T> owner;
        private final long baseRevision;
        private final long revision;
        private final long[] removedIds;
        private final List<SlotWrite<T>> writes;
        private final int[] clearedSlots;
        private final int liveCount;
        private final int slotUpperBound;
        private boolean committed;

        private Prepared(
                StableIdentitySlots<T> owner,
                long baseRevision,
                long revision,
                long[] removedIds,
                List<SlotWrite<T>> writes,
                int[] clearedSlots,
                int liveCount,
                int slotUpperBound
        ) {
            this.owner = owner;
            this.baseRevision = baseRevision;
            this.revision = revision;
            this.removedIds = removedIds;
            this.writes = writes;
            this.clearedSlots = clearedSlots;
            this.liveCount = liveCount;
            this.slotUpperBound = slotUpperBound;
        }

        StableIdentitySlots<T> owner() {
            return owner;
        }

        long baseRevision() {
            return baseRevision;
        }

        long revision() {
            return revision;
        }

        long[] removedIds() {
            return removedIds.clone();
        }

        List<SlotWrite<T>> writes() {
            return writes;
        }

        int[] clearedSlots() {
            return clearedSlots.clone();
        }

        int liveCount() {
            return liveCount;
        }

        int slotUpperBound() {
            return slotUpperBound;
        }

        boolean committed() {
            return committed;
        }

        void markCommitted() {
            committed = true;
        }
    }
}

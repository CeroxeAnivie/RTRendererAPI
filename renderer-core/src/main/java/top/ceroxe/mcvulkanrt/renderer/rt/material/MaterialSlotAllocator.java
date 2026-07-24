package top.ceroxe.mcvulkanrt.renderer.rt.material;

import top.ceroxe.mcvulkanrt.renderer.RtMaterialTelemetrySink;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Stable section-material allocator for TLAS custom indices and sparse face records.
 *
 * <p>The important invariant is that section slot identity and face-buffer
 * capacity have different lifetimes. host can churn a section's mesh
 * every frame through fluid spread, fire animation, block digging, or chunk
 * edge updates; turning that churn into slot reshuffles or face-buffer growth
 * makes the RT path stall even when the actual changed material bytes are tiny.</p>
 *
 * <p>This allocator mirrors the UE-style persistent GPUScene discipline used by
 * the RT backend: active sections keep stable custom indices, removed sections
 * leave explicit tombstone records that never alias live face ranges, free face
 * ranges are coalesced and reused, and hot slot capacity is remembered even when
 * a tail slot is trimmed and recreated later.</p>
 */
final class MaterialSlotAllocator<K> {
    static final int DEFAULT_FACE_CAPACITY = 128;
    static final int FACE_ALIGNMENT = 64;

    private final Map<K, Integer> sectionSlots = new HashMap<>();
    private final List<RtSceneMaterialTable.SectionMaterial> sectionMaterials = new ArrayList<>();
    private final List<Integer> firstFaces = new ArrayList<>();
    private final List<Integer> faceCapacities = new ArrayList<>();
    private final List<Integer> preferredFaceCapacities = new ArrayList<>();
    private final List<Boolean> freeSlots = new ArrayList<>();
    private final List<Integer> freeSlotStack = new ArrayList<>();
    private final List<Integer> retiredSlotPreferredCapacities = new ArrayList<>();
    private final FaceRangeAllocator faceRanges = new FaceRangeAllocator();
    private final BitSet dirtySlots = new BitSet();
    private long reusedSlotAllocations;
    private final RtMaterialTelemetrySink materialTelemetry;

    MaterialSlotAllocator() {
        this(RtMaterialTelemetrySink.NOOP);
    }

    MaterialSlotAllocator(RtMaterialTelemetrySink materialTelemetry) {
        this.materialTelemetry = Objects.requireNonNull(materialTelemetry, "materialTelemetry");
    }

    int update(K key, RtSceneMaterialTable.SectionMaterial material) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(material, "material");
        int requiredFaces = material.faceCount();
        if (requiredFaces <= 0) {
            throw new IllegalArgumentException("required material slot faces must be positive");
        }

        Integer existingSlot = sectionSlots.get(key);
        int slot = existingSlot == null ? allocateSlot(key, requiredFaces) : existingSlot;
        if (slot < 0 || slot >= sectionMaterials.size() || freeSlots.get(slot)) {
            throw new IllegalStateException("material slot is not active for " + key + ": " + slot);
        }

        FaceRange currentRange = range(slot);
        boolean changed = !material.equals(sectionMaterials.get(slot));
        String rangeDecision = "unchanged";
        if (requiredFaces > currentRange.capacity()) {
            FaceRange grownRange = faceRanges.grow(
                    currentRange,
                    grownFaceCapacity(currentRange.capacity(), requiredFaces)
            );
            setRange(slot, grownRange);
            preferredFaceCapacities.set(slot, grownRange.capacity());
            changed = true;
            rangeDecision = grownRange.firstFace() == currentRange.firstFace()
                    ? "grownInPlace"
                    : "grownMoved";
        }
        sectionMaterials.set(slot, material);
        if (changed) {
            dirtySlots.set(slot);
        }
        FaceRange updatedRange = range(slot);
        materialTelemetry.materialSlotUpdated(
                slot,
                existingSlot == null,
                changed,
                rangeDecision,
                requiredFaces,
                currentRange.firstFace(),
                currentRange.capacity(),
                updatedRange.firstFace(),
                updatedRange.capacity(),
                sectionSlots.size(),
                sectionMaterials.size(),
                freeSlotStack.size(),
                faceRanges.faceCapacity(),
                faceRanges.freeRangeCount()
        );
        return slot;
    }

    boolean release(K key) {
        Integer slot = sectionSlots.remove(Objects.requireNonNull(key, "key"));
        if (slot == null) {
            return false;
        }
        if (slot < 0 || slot >= sectionMaterials.size() || freeSlots.get(slot)) {
            throw new IllegalStateException("material slot is already free for " + key + ": " + slot);
        }

        FaceRange releasedRange = range(slot);
        faceRanges.release(releasedRange);
        preferredFaceCapacities.set(slot, Math.max(preferredFaceCapacities.get(slot), releasedRange.capacity()));
        FaceRange tombstoneRange = faceRanges.allocateTombstone();
        sectionMaterials.set(slot, RtSceneMaterialTable.tombstoneSectionMaterial());
        setRange(slot, tombstoneRange);
        dirtySlots.set(slot);
        freeSlots.set(slot, true);
        freeSlotStack.add(slot);
        trimTrailingFreeSlots();
        materialTelemetry.materialSlotReleased(
                slot,
                releasedRange.firstFace(),
                releasedRange.capacity(),
                sectionSlots.size(),
                sectionMaterials.size(),
                freeSlotStack.size(),
                faceRanges.faceCapacity(),
                faceRanges.freeRangeCount()
        );
        return true;
    }

    void clear() {
        int clearedActiveSlots = sectionSlots.size();
        int clearedSlotCount = sectionMaterials.size();
        int clearedFaceCapacity = faceRanges.faceCapacity();
        int clearedFreeRanges = faceRanges.freeRangeCount();
        sectionSlots.clear();
        sectionMaterials.clear();
        firstFaces.clear();
        faceCapacities.clear();
        preferredFaceCapacities.clear();
        freeSlots.clear();
        freeSlotStack.clear();
        retiredSlotPreferredCapacities.clear();
        faceRanges.clear();
        dirtySlots.clear();
        materialTelemetry.materialSlotsCleared(
                clearedActiveSlots,
                clearedSlotCount,
                clearedFaceCapacity,
                clearedFreeRanges
        );
    }

    Integer slotFor(K key) {
        return sectionSlots.get(Objects.requireNonNull(key, "key"));
    }

    List<RtSceneMaterialTable.SectionMaterial> sectionMaterials() {
        return sectionMaterials;
    }

    int[] firstFacesArray() {
        int[] result = new int[firstFaces.size()];
        for (int index = 0; index < firstFaces.size(); index++) {
            result[index] = firstFaces.get(index);
        }
        return result;
    }

    int firstFace(int slot) {
        if (slot < 0 || slot >= firstFaces.size()) {
            throw new IllegalArgumentException("material slot outside allocator range: " + slot);
        }
        return firstFaces.get(slot);
    }

    int[] consumeDirtySlots() {
        int[] result = new int[dirtySlots.cardinality()];
        int output = 0;
        for (int slot = dirtySlots.nextSetBit(0); slot >= 0; slot = dirtySlots.nextSetBit(slot + 1)) {
            result[output++] = slot;
        }
        dirtySlots.clear();
        materialTelemetry.materialDirtySlotsConsumed(
                result.length,
                sectionSlots.size(),
                sectionMaterials.size()
        );
        return result;
    }

    int faceCapacity() {
        return faceRanges.faceCapacity();
    }

    int slotCount() {
        return sectionMaterials.size();
    }

    int activeSlotCount() {
        return sectionSlots.size();
    }

    int freeSlotCount() {
        return freeSlotStack.size();
    }

    long reusedSlotAllocations() {
        return reusedSlotAllocations;
    }

    int freeFaceRangeCount() {
        return faceRanges.freeRangeCount();
    }

    int freeFaceCapacity() {
        return faceRanges.freeFaceCapacity();
    }

    long reusedFaceRangeAllocations() {
        return faceRanges.reusedRangeAllocations();
    }

    long movedFaceRangeAllocations() {
        return faceRanges.movedRangeAllocations();
    }

    long tailExtendedFaceRangeAllocations() {
        return faceRanges.tailExtendedRangeAllocations();
    }

    void assertInvariants() {
        int slotCount = sectionMaterials.size();
        if (firstFaces.size() != slotCount
                || faceCapacities.size() != slotCount
                || preferredFaceCapacities.size() != slotCount
                || freeSlots.size() != slotCount) {
            throw new IllegalStateException("material slot parallel arrays have diverged");
        }
        if (sectionSlots.size() + freeSlotStack.size() != slotCount) {
            throw new IllegalStateException("active and free material slot counts do not cover the slot table");
        }

        Set<Integer> mappedSlots = new HashSet<>();
        for (Map.Entry<K, Integer> entry : sectionSlots.entrySet()) {
            int slot = entry.getValue();
            if (slot < 0 || slot >= slotCount || freeSlots.get(slot) || !mappedSlots.add(slot)) {
                throw new IllegalStateException("invalid or multiply mapped material slot for " + entry.getKey()
                        + ": " + slot);
            }
        }

        Set<Integer> stackedFreeSlots = new HashSet<>();
        for (int slot : freeSlotStack) {
            if (slot < 0 || slot >= slotCount || !freeSlots.get(slot) || !stackedFreeSlots.add(slot)) {
                throw new IllegalStateException("invalid or duplicate material free-slot entry: " + slot);
            }
        }

        ArrayList<FaceRange> allocatedRanges = new ArrayList<>(slotCount);
        for (int slot = 0; slot < slotCount; slot++) {
            boolean free = freeSlots.get(slot);
            if (free != stackedFreeSlots.contains(slot) || free == mappedSlots.contains(slot)) {
                throw new IllegalStateException("material slot ownership mismatch at slot " + slot);
            }
            if (preferredFaceCapacities.get(slot) < DEFAULT_FACE_CAPACITY) {
                throw new IllegalStateException("material slot preferred capacity fell below the default at slot "
                        + slot);
            }
            allocatedRanges.add(range(slot));
        }
        faceRanges.assertInvariants(allocatedRanges);
    }

    static int grownFaceCapacity(int currentCapacity, int requiredFaces) {
        if (currentCapacity <= 0) {
            throw new IllegalArgumentException("current material slot capacity must be positive");
        }
        if (requiredFaces <= 0) {
            throw new IllegalArgumentException("required material slot faces must be positive");
        }
        if (requiredFaces <= currentCapacity) {
            return currentCapacity;
        }
        long growth = Math.max((long) requiredFaces, currentCapacity + Math.max(1L, currentCapacity / 2L));
        growth = Math.max(growth, DEFAULT_FACE_CAPACITY);
        long aligned = alignUp(growth, FACE_ALIGNMENT);
        if (aligned > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("material slot face capacity overflow");
        }
        return (int) aligned;
    }

    private int allocateSlot(K key, int requiredFaces) {
        int slot;
        FaceRange allocatedRange;
        boolean reusedSlot;
        boolean restoredRetiredCapacity = false;
        if (freeSlotStack.isEmpty()) {
            int minimumCapacity = DEFAULT_FACE_CAPACITY;
            restoredRetiredCapacity = !retiredSlotPreferredCapacities.isEmpty();
            if (restoredRetiredCapacity) {
                minimumCapacity = Math.max(
                        minimumCapacity,
                        retiredSlotPreferredCapacities.get(retiredSlotPreferredCapacities.size() - 1)
                );
            }
            int initialCapacity = grownFaceCapacity(minimumCapacity, requiredFaces);
            allocatedRange = faceRanges.allocate(initialCapacity);
            if (restoredRetiredCapacity) {
                retiredSlotPreferredCapacities.remove(retiredSlotPreferredCapacities.size() - 1);
            }
            slot = sectionMaterials.size();
            sectionMaterials.add(RtSceneMaterialTable.tombstoneSectionMaterial());
            firstFaces.add(allocatedRange.firstFace());
            faceCapacities.add(allocatedRange.capacity());
            preferredFaceCapacities.add(allocatedRange.capacity());
            freeSlots.add(false);
            reusedSlot = false;
        } else {
            int freeStackIndex = freeSlotStack.size() - 1;
            slot = freeSlotStack.get(freeStackIndex);
            if (!freeSlots.get(slot)) {
                throw new IllegalStateException("material free-slot stack referenced an active slot: " + slot);
            }
            int minimumCapacity = Math.max(DEFAULT_FACE_CAPACITY, preferredFaceCapacities.get(slot));
            int initialCapacity = grownFaceCapacity(minimumCapacity, requiredFaces);
            FaceRange tombstoneRange = range(slot);
            faceRanges.release(tombstoneRange);
            try {
                allocatedRange = faceRanges.allocate(initialCapacity);
            } catch (RuntimeException failure) {
                FaceRange restoredTombstone = faceRanges.allocateTombstone();
                setRange(slot, restoredTombstone);
                throw failure;
            }
            freeSlotStack.remove(freeStackIndex);
            sectionMaterials.set(slot, RtSceneMaterialTable.tombstoneSectionMaterial());
            setRange(slot, allocatedRange);
            preferredFaceCapacities.set(slot, allocatedRange.capacity());
            freeSlots.set(slot, false);
            reusedSlotAllocations++;
            reusedSlot = true;
        }
        sectionSlots.put(key, slot);
        materialTelemetry.materialSlotAllocated(
                slot,
                reusedSlot,
                restoredRetiredCapacity,
                requiredFaces,
                allocatedRange.firstFace(),
                allocatedRange.capacity(),
                sectionSlots.size(),
                sectionMaterials.size(),
                freeSlotStack.size(),
                faceRanges.faceCapacity(),
                faceRanges.freeRangeCount()
        );
        return slot;
    }

    private FaceRange range(int slot) {
        return new FaceRange(firstFaces.get(slot), faceCapacities.get(slot));
    }

    private void setRange(int slot, FaceRange range) {
        firstFaces.set(slot, range.firstFace());
        faceCapacities.set(slot, range.capacity());
    }

    private void trimTrailingFreeSlots() {
        int trimmedSlots = 0;
        int retainedPreferredCapacity = 0;
        while (!freeSlots.isEmpty()) {
            int lastSlot = freeSlots.size() - 1;
            if (!freeSlots.get(lastSlot)) {
                break;
            }
            int preferredCapacity = preferredFaceCapacities.get(lastSlot);
            faceRanges.release(range(lastSlot));
            sectionMaterials.remove(lastSlot);
            firstFaces.remove(lastSlot);
            faceCapacities.remove(lastSlot);
            preferredFaceCapacities.remove(lastSlot);
            freeSlots.remove(lastSlot);
            freeSlotStack.remove(Integer.valueOf(lastSlot));
            retiredSlotPreferredCapacities.add(preferredCapacity);
            retainedPreferredCapacity = Math.max(retainedPreferredCapacity, preferredCapacity);
            trimmedSlots++;
        }
        if (trimmedSlots > 0) {
            materialTelemetry.materialTrailingSlotsTrimmed(
                    trimmedSlots,
                    retainedPreferredCapacity,
                    sectionSlots.size(),
                    sectionMaterials.size(),
                    faceRanges.faceCapacity(),
                    faceRanges.freeRangeCount()
            );
        }
    }

    private static long alignUp(long value, long alignment) {
        if (value <= 0L) {
            throw new IllegalArgumentException("value must be positive");
        }
        if (alignment <= 0L) {
            throw new IllegalArgumentException("alignment must be positive");
        }
        long remainder = value % alignment;
        if (remainder == 0L) {
            return value;
        }
        return checkedLongAdd(value, alignment - remainder);
    }

    private static int checkedIntAdd(int left, int right) {
        int result = left + right;
        if (((left ^ result) & (right ^ result)) < 0) {
            throw new IllegalArgumentException("size overflow");
        }
        return result;
    }

    private static long checkedLongAdd(long left, long right) {
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0) {
            throw new IllegalArgumentException("size overflow");
        }
        return result;
    }

    record FaceRange(int firstFace, int capacity) {
        FaceRange {
            if (firstFace < 0) {
                throw new IllegalArgumentException("material face range first face must not be negative");
            }
            if (capacity <= 0) {
                throw new IllegalArgumentException("material face range capacity must be positive");
            }
            checkedIntAdd(firstFace, capacity);
        }

        int endExclusive() {
            return checkedIntAdd(firstFace, capacity);
        }
    }

    static final class FaceRangeAllocator {
        private final List<FaceRange> freeRanges = new ArrayList<>();
        private final Map<Integer, Integer> allocatedRanges = new HashMap<>();
        private int faceCapacity;
        private long reusedRangeAllocations;
        private long movedRangeAllocations;
        private long tailExtendedRangeAllocations;

        FaceRange allocate(int capacity) {
            validateCapacity(capacity);
            int freeIndex = bestFitFreeRangeIndex(capacity);
            if (freeIndex >= 0) {
                reusedRangeAllocations++;
                return allocateFromFreeRange(freeIndex, capacity);
            }
            return appendRange(capacity);
        }

        FaceRange allocateTombstone() {
            return allocate(1);
        }

        FaceRange grow(FaceRange current, int requiredCapacity) {
            Objects.requireNonNull(current, "current");
            validateAllocated(current);
            validateCapacity(requiredCapacity);
            if (requiredCapacity <= current.capacity()) {
                return current;
            }

            int extraCapacity = requiredCapacity - current.capacity();
            if (current.endExclusive() == faceCapacity) {
                faceCapacity = checkedIntAdd(faceCapacity, extraCapacity);
                FaceRange grown = new FaceRange(current.firstFace(), requiredCapacity);
                allocatedRanges.put(grown.firstFace(), grown.capacity());
                tailExtendedRangeAllocations++;
                return grown;
            }

            int adjacentIndex = freeRangeIndexAt(current.endExclusive());
            if (adjacentIndex >= 0) {
                FaceRange adjacent = freeRanges.get(adjacentIndex);
                if (adjacent.capacity() >= extraCapacity) {
                    consumeFreeRangePrefix(adjacentIndex, extraCapacity);
                    FaceRange grown = new FaceRange(current.firstFace(), requiredCapacity);
                    allocatedRanges.put(grown.firstFace(), grown.capacity());
                    reusedRangeAllocations++;
                    return grown;
                }
                if (adjacent.endExclusive() == faceCapacity) {
                    int grownFaceCapacity = checkedIntAdd(faceCapacity, extraCapacity - adjacent.capacity());
                    freeRanges.remove(adjacentIndex);
                    faceCapacity = grownFaceCapacity;
                    FaceRange grown = new FaceRange(current.firstFace(), requiredCapacity);
                    allocatedRanges.put(grown.firstFace(), grown.capacity());
                    reusedRangeAllocations++;
                    tailExtendedRangeAllocations++;
                    return grown;
                }
            }

            FaceRange replacement = allocate(requiredCapacity);
            release(current);
            movedRangeAllocations++;
            return replacement;
        }

        void release(FaceRange range) {
            Objects.requireNonNull(range, "range");
            Integer allocatedCapacity = allocatedRanges.remove(range.firstFace());
            if (allocatedCapacity == null || allocatedCapacity != range.capacity()) {
                throw new IllegalStateException("material face range was not allocated: " + range);
            }
            insertFreeRange(range);
        }

        void clear() {
            freeRanges.clear();
            allocatedRanges.clear();
            faceCapacity = 0;
        }

        void assertInvariants(List<FaceRange> expectedAllocatedRanges) {
            Objects.requireNonNull(expectedAllocatedRanges, "expectedAllocatedRanges");
            Map<Integer, Integer> expected = new HashMap<>();
            for (FaceRange range : expectedAllocatedRanges) {
                Integer previous = expected.put(range.firstFace(), range.capacity());
                if (previous != null) {
                    throw new IllegalStateException("material face ranges overlap at " + range.firstFace());
                }
            }
            if (!allocatedRanges.equals(expected)) {
                throw new IllegalStateException("material face allocation registry diverged from slot ranges");
            }

            ArrayList<FaceRange> allRanges = new ArrayList<>(expectedAllocatedRanges.size() + freeRanges.size());
            allRanges.addAll(expectedAllocatedRanges);
            int previousFreeEnd = -1;
            for (FaceRange freeRange : freeRanges) {
                if (previousFreeEnd >= freeRange.firstFace()) {
                    throw new IllegalStateException("material free ranges are unsorted, overlapping, or uncoalesced");
                }
                previousFreeEnd = freeRange.endExclusive();
                allRanges.add(freeRange);
            }
            allRanges.sort(java.util.Comparator.comparingInt(FaceRange::firstFace));
            int cursor = 0;
            for (FaceRange range : allRanges) {
                if (range.firstFace() != cursor) {
                    throw new IllegalStateException("material face address space contains a gap or overlap at "
                            + cursor);
                }
                cursor = range.endExclusive();
            }
            if (cursor != faceCapacity) {
                throw new IllegalStateException("material face address space does not cover face capacity");
            }
        }

        int faceCapacity() {
            return faceCapacity;
        }

        int freeRangeCount() {
            return freeRanges.size();
        }

        int freeFaceCapacity() {
            int total = 0;
            for (FaceRange range : freeRanges) {
                total = checkedIntAdd(total, range.capacity());
            }
            return total;
        }

        long reusedRangeAllocations() {
            return reusedRangeAllocations;
        }

        long movedRangeAllocations() {
            return movedRangeAllocations;
        }

        long tailExtendedRangeAllocations() {
            return tailExtendedRangeAllocations;
        }

        private FaceRange allocateFromFreeRange(int index, int capacity) {
            FaceRange freeRange = freeRanges.get(index);
            if (capacity > freeRange.capacity()) {
                throw new IllegalArgumentException("free range is too small");
            }
            FaceRange allocated = new FaceRange(freeRange.firstFace(), capacity);
            rememberAllocated(allocated);
            consumeFreeRangePrefix(index, capacity);
            return allocated;
        }

        private FaceRange appendRange(int capacity) {
            FaceRange range = new FaceRange(faceCapacity, capacity);
            int grownFaceCapacity = checkedIntAdd(faceCapacity, capacity);
            rememberAllocated(range);
            faceCapacity = grownFaceCapacity;
            return range;
        }

        private void consumeFreeRangePrefix(int index, int capacity) {
            FaceRange freeRange = freeRanges.get(index);
            if (capacity == freeRange.capacity()) {
                freeRanges.remove(index);
                return;
            }
            freeRanges.set(index, new FaceRange(
                    checkedIntAdd(freeRange.firstFace(), capacity),
                    freeRange.capacity() - capacity
            ));
        }

        private void rememberAllocated(FaceRange range) {
            Integer previous = allocatedRanges.put(range.firstFace(), range.capacity());
            if (previous != null) {
                throw new IllegalStateException("material face range overlaps an allocated range at "
                        + range.firstFace());
            }
        }

        private void validateAllocated(FaceRange range) {
            Integer allocatedCapacity = allocatedRanges.get(range.firstFace());
            if (allocatedCapacity == null || allocatedCapacity != range.capacity()) {
                throw new IllegalStateException("material face range was not allocated: " + range);
            }
        }

        private int bestFitFreeRangeIndex(int capacity) {
            int bestIndex = -1;
            int bestCapacity = Integer.MAX_VALUE;
            for (int index = 0; index < freeRanges.size(); index++) {
                FaceRange range = freeRanges.get(index);
                if (range.capacity() >= capacity && range.capacity() < bestCapacity) {
                    bestIndex = index;
                    bestCapacity = range.capacity();
                }
            }
            return bestIndex;
        }

        private int freeRangeIndexAt(int firstFace) {
            for (int index = 0; index < freeRanges.size(); index++) {
                if (freeRanges.get(index).firstFace() == firstFace) {
                    return index;
                }
            }
            return -1;
        }

        private void insertFreeRange(FaceRange range) {
            int index = 0;
            while (index < freeRanges.size() && freeRanges.get(index).firstFace() < range.firstFace()) {
                index++;
            }

            int firstFace = range.firstFace();
            int capacity = range.capacity();
            if (index > 0) {
                FaceRange previous = freeRanges.get(index - 1);
                if (previous.endExclusive() > firstFace) {
                    throw new IllegalStateException("released material face range overlaps a free range");
                }
                if (previous.endExclusive() == firstFace) {
                    firstFace = previous.firstFace();
                    capacity = checkedIntAdd(previous.capacity(), capacity);
                    freeRanges.remove(--index);
                }
            }
            if (index < freeRanges.size()) {
                FaceRange next = freeRanges.get(index);
                int endExclusive = checkedIntAdd(firstFace, capacity);
                if (endExclusive > next.firstFace()) {
                    throw new IllegalStateException("released material face range overlaps a free range");
                }
                if (endExclusive == next.firstFace()) {
                    capacity = checkedIntAdd(capacity, next.capacity());
                    freeRanges.remove(index);
                }
            }
            freeRanges.add(index, new FaceRange(firstFace, capacity));
        }

        private static void validateCapacity(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("material face range capacity must be positive");
            }
        }
    }
}

package top.ceroxe.rt.renderer.backend.vulkan;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.nio.LongBuffer;
import java.util.*;
import java.util.function.ToLongFunction;

/**
 * Transactional suballocator for variable-sized GPUScene streams.
 *
 * <p>The arena owns offsets, not Vulkan buffers. Preparation works on a private interval map and
 * therefore cannot consume capacity when native admission is rejected. A replaced or removed
 * range is deliberately kept out of the reusable free set until the caller advances the GPU
 * completion epoch. This is the critical distinction between CPU scene revision and actual GPU
 * resource lifetime.</p>
 */
final class VulkanRangeArena<T> {
    private final ToLongFunction<T> identityFunction;
    private final Long2ObjectOpenHashMap<Allocation> allocations = new Long2ObjectOpenHashMap<>();
    private NavigableMap<Long, Long> freeRanges = new TreeMap<>();
    private final ArrayList<RetiredRange> retiredRanges = new ArrayList<>();

    private long revision = -1L;
    private long stateVersion;
    private long highWater;
    private long liveBytes;

    VulkanRangeArena(ToLongFunction<T> identityFunction) {
        this.identityFunction = Objects.requireNonNull(identityFunction, "identityFunction");
    }

    private static long[] readRemovals(boolean reset, LongBuffer removals) {
        if (reset) {
            if (removals != null && removals.hasRemaining()) {
                throw new IllegalArgumentException("reset arena update cannot contain explicit removals");
            }
            return new long[0];
        }
        LongBuffer source = Objects.requireNonNull(removals, "removals");
        long[] result = new long[source.remaining()];
        LongOpenHashSet unique = new LongOpenHashSet(result.length);
        for (int index = 0; index < result.length; index++) {
            long id = source.get();
            if (id < 0L) {
                throw new IllegalArgumentException("arena removal id must not be negative");
            }
            if (!unique.add(id)) {
                throw new IllegalArgumentException("duplicate arena removal id " + id);
            }
            result[index] = id;
        }
        return result;
    }

    private static Allocation allocate(NavigableMap<Long, Long> free, long highWater,
                                       long capacity, long alignment) {
        long selectedOffset = -1L;
        long selectedRangeOffset = -1L;
        long selectedRangeBytes = 0L;
        for (Map.Entry<Long, Long> entry : free.entrySet()) {
            long start = alignUp(entry.getKey(), alignment);
            long end = Math.addExact(start, capacity);
            long rangeEnd = Math.addExact(entry.getKey(), entry.getValue());
            if (end <= rangeEnd) {
                selectedOffset = start;
                selectedRangeOffset = entry.getKey();
                selectedRangeBytes = entry.getValue();
                break;
            }
        }
        if (selectedOffset >= 0L) {
            long selectedEnd = Math.addExact(selectedOffset, capacity);
            long rangeEnd = Math.addExact(selectedRangeOffset, selectedRangeBytes);
            free.remove(selectedRangeOffset);
            addFreeRange(free, selectedRangeOffset, selectedOffset - selectedRangeOffset);
            addFreeRange(free, selectedEnd, rangeEnd - selectedEnd);
            return new Allocation(selectedOffset, capacity);
        }
        long start = alignUp(highWater, alignment);
        addFreeRange(free, highWater, start - highWater);
        return new Allocation(start, capacity);
    }

    private static void addFreeRange(NavigableMap<Long, Long> free, long offset, long bytes) {
        if (bytes <= 0L) return;
        long start = offset;
        long end = Math.addExact(offset, bytes);
        Map.Entry<Long, Long> lower = free.floorEntry(start);
        if (lower != null && Math.addExact(lower.getKey(), lower.getValue()) >= start) {
            start = lower.getKey();
            end = Math.max(end, Math.addExact(lower.getKey(), lower.getValue()));
            free.remove(lower.getKey());
        }
        Map.Entry<Long, Long> next;
        while ((next = free.ceilingEntry(start)) != null && next.getKey() <= end) {
            end = Math.max(end, Math.addExact(next.getKey(), next.getValue()));
            free.remove(next.getKey());
        }
        free.put(start, end - start);
    }

    private static long alignedCapacity(long bytes, long alignment) {
        if (bytes <= 0L) throw new IllegalArgumentException("arena byte count must be positive");
        return alignUp(bytes, alignment);
    }

    private static long alignUp(long value, long alignment) {
        if (alignment <= 0L || (alignment & (alignment - 1L)) != 0L) {
            throw new IllegalArgumentException("arena alignment must be a positive power of two");
        }
        long mask = alignment - 1L;
        return Math.addExact(value, mask) & ~mask;
    }

    synchronized Prepared<T> prepare(
            long nextRevision,
            boolean reset,
            Collection<RangeRequest<T>> upserts,
            LongBuffer removals
    ) {
        if (nextRevision <= revision) {
            throw new IllegalArgumentException("arena revision must advance: current=" + revision
                    + ", submitted=" + nextRevision);
        }
        List<RangeRequest<T>> ordered = new ArrayList<>(Objects.requireNonNull(upserts, "upserts"));
        ordered.sort(Comparator.comparingLong(request -> identityFunction.applyAsLong(request.value())));
        long[] explicitRemovedIds = readRemovals(reset, removals);
        Long2ObjectOpenHashMap<RangeRequest<T>> requestsById = new Long2ObjectOpenHashMap<>();
        for (RangeRequest<T> request : ordered) {
            RangeRequest<T> checked = Objects.requireNonNull(request, "upsert request");
            long id = identityFunction.applyAsLong(checked.value());
            if (id < 0L || requestsById.putIfAbsent(id, checked) != null) {
                throw new IllegalArgumentException("duplicate or invalid arena upsert id " + id);
            }
        }
        long[] removedIds = reset ? allocations.keySet().toLongArray() : explicitRemovedIds;
        java.util.Arrays.sort(removedIds);
        for (long id : removedIds) {
            if (!allocations.containsKey(id)) {
                throw new IllegalArgumentException("arena removal references missing id " + id);
            }
            if (!reset && requestsById.containsKey(id)) {
                throw new IllegalArgumentException("arena identity cannot be upserted and removed: " + id);
            }
        }

        if (!reset && ordered.isEmpty() && removedIds.length == 0) {
            return new Prepared<>(
                    this, revision, stateVersion, nextRevision,
                    new Long2ObjectOpenHashMap<>(), List.of(), removedIds,
                    null, highWater, liveBytes
            );
        }

        NavigableMap<Long, Long> workingFree = new TreeMap<>(freeRanges);
        Long2ObjectOpenHashMap<Allocation> preparedAllocations = new Long2ObjectOpenHashMap<>(ordered.size());
        ArrayList<PlacementWrite<T>> writes = new ArrayList<>(ordered.size());
        long nextHighWater = highWater;
        long nextLiveBytes = reset ? 0L : liveBytes;
        if (!reset) {
            nextLiveBytes -= removedBytes(removedIds);
        }

        for (RangeRequest<T> request : ordered) {
            long id = identityFunction.applyAsLong(request.value());
            Allocation previous = reset ? null : allocations.get(id);
            long capacity = alignedCapacity(request.byteCount(), request.alignment());
            Allocation selected = previous != null && previous.capacityBytes() >= capacity
                    && previous.offsetBytes() % request.alignment() == 0L
                    ? previous
                    : allocate(workingFree, nextHighWater, capacity, request.alignment());
            nextHighWater = Math.max(nextHighWater,
                    Math.addExact(selected.offsetBytes(), selected.capacityBytes()));
            Allocation retiredPrevious = previous != null && !previous.equals(selected) ? previous : null;
            writes.add(new PlacementWrite<>(id, request.value(), selected, retiredPrevious));
            if (previous == null) {
                nextLiveBytes = Math.addExact(nextLiveBytes, selected.capacityBytes());
            } else if (retiredPrevious != null) {
                nextLiveBytes = Math.subtractExact(nextLiveBytes, previous.capacityBytes());
                nextLiveBytes = Math.addExact(nextLiveBytes, selected.capacityBytes());
            }
            preparedAllocations.put(id, selected);
        }
        return new Prepared<>(
                this, revision, stateVersion, nextRevision,
                preparedAllocations, List.copyOf(writes), removedIds,
                workingFree, nextHighWater, nextLiveBytes
        );
    }

    synchronized State commit(Prepared<T> prepared, long retireAfterEpoch) {
        validate(prepared, retireAfterEpoch);
        return commitValidated(prepared, retireAfterEpoch);
    }

    synchronized void validate(Prepared<T> prepared, long retireAfterEpoch) {
        Prepared<T> checked = Objects.requireNonNull(prepared, "prepared");
        if (retireAfterEpoch < 0L) {
            throw new IllegalArgumentException("retireAfterEpoch must not be negative");
        }
        if (checked.owner() != this || checked.committed()) {
            throw new IllegalStateException("arena prepared change set is invalid or already committed");
        }
        if (checked.baseRevision() != revision || checked.baseStateVersion() != stateVersion) {
            throw new IllegalStateException("arena advanced after preparation");
        }
    }

    synchronized State commitValidated(Prepared<T> prepared, long retireAfterEpoch) {
        Prepared<T> checked = Objects.requireNonNull(prepared, "prepared");
        checked.markCommitted();
        for (long id : checked.removedIds()) {
            Allocation previous = allocations.remove(id);
            retire(previous, retireAfterEpoch);
        }
        for (PlacementWrite<T> write : checked.writes()) {
            if (write.previous() != null) {
                retire(write.previous(), retireAfterEpoch);
            }
            allocations.put(write.identity(), write.allocation());
        }
        NavigableMap<Long, Long> preparedFreeRanges = checked.takeFreeRanges();
        if (preparedFreeRanges != null) freeRanges = preparedFreeRanges;
        highWater = checked.highWater();
        liveBytes = checked.liveBytes();
        revision = checked.revision();
        stateVersion++;
        return state();
    }

    synchronized State releaseThrough(long completedEpoch) {
        if (completedEpoch < 0L) {
            throw new IllegalArgumentException("completedEpoch must not be negative");
        }
        boolean changed = false;
        for (int index = retiredRanges.size() - 1; index >= 0; index--) {
            RetiredRange retired = retiredRanges.get(index);
            if (retired.safeAfterEpoch() <= completedEpoch) {
                addFreeRange(freeRanges, retired.range().offsetBytes(), retired.range().capacityBytes());
                retiredRanges.remove(index);
                changed = true;
            }
        }
        if (changed) {
            stateVersion++;
        }
        return state();
    }

    synchronized State state() {
        long freeBytes = 0L;
        for (long bytes : freeRanges.values()) {
            freeBytes = Math.addExact(freeBytes, bytes);
        }
        long retiredBytes = 0L;
        for (RetiredRange retired : retiredRanges) {
            retiredBytes = Math.addExact(retiredBytes, retired.range().capacityBytes());
        }
        return new State(revision, highWater, liveBytes, freeBytes, retiredBytes,
                allocations.size(), freeRanges.size(), retiredRanges.size());
    }

    synchronized Allocation allocation(long identity) {
        return allocations.get(identity);
    }

    private void retire(Allocation allocation, long safeAfterEpoch) {
        if (allocation != null) {
            retiredRanges.add(new RetiredRange(safeAfterEpoch, allocation));
        }
    }

    private long removedBytes(long[] removedIds) {
        long bytes = 0L;
        for (long id : removedIds) {
            bytes = Math.addExact(bytes, allocations.get(id).capacityBytes());
        }
        return bytes;
    }

    record RangeRequest<T>(T value, long byteCount, long alignment) {
        RangeRequest {
            value = Objects.requireNonNull(value, "value");
            if (byteCount <= 0L || alignment <= 0L || (alignment & (alignment - 1L)) != 0L) {
                throw new IllegalArgumentException("arena request must have positive power-of-two alignment");
            }
        }
    }

    record Allocation(long offsetBytes, long capacityBytes) {
        Allocation {
            if (offsetBytes < 0L || capacityBytes <= 0L) {
                throw new IllegalArgumentException("arena allocation is invalid");
            }
        }
    }

    record PlacementWrite<T>(long identity, T value, Allocation allocation, Allocation previous) {
        PlacementWrite {
            if (identity < 0L) throw new IllegalArgumentException("arena identity must not be negative");
            value = Objects.requireNonNull(value, "value");
            allocation = Objects.requireNonNull(allocation, "allocation");
        }
    }

    record State(long revision, long highWaterBytes, long liveBytes, long freeBytes,
                 long pendingRetiredBytes, int liveAllocations, int freeRangeCount, int retiredRangeCount) {
        State {
            if (revision < -1L || highWaterBytes < 0L || liveBytes < 0L || freeBytes < 0L
                    || pendingRetiredBytes < 0L || liveAllocations < 0 || freeRangeCount < 0 || retiredRangeCount < 0) {
                throw new IllegalArgumentException("arena state contains a negative counter");
            }
        }
    }

    private record RetiredRange(long safeAfterEpoch, Allocation range) {
        RetiredRange {
            if (safeAfterEpoch < 0L) throw new IllegalArgumentException("retirement epoch must not be negative");
            range = Objects.requireNonNull(range, "range");
        }
    }

    static final class Prepared<T> {
        private final VulkanRangeArena<T> owner;
        private final long baseRevision;
        private final long baseStateVersion;
        private final long revision;
        private final Long2ObjectOpenHashMap<Allocation> preparedAllocations;
        private final List<PlacementWrite<T>> writes;
        private final long[] removedIds;
        private NavigableMap<Long, Long> freeRanges;
        private final long highWater;
        private final long liveBytes;
        private boolean committed;

        private Prepared(VulkanRangeArena<T> owner, long baseRevision, long baseStateVersion, long revision,
                         Long2ObjectOpenHashMap<Allocation> preparedAllocations, List<PlacementWrite<T>> writes,
                         long[] removedIds, NavigableMap<Long, Long> freeRanges, long highWater, long liveBytes) {
            this.owner = owner;
            this.baseRevision = baseRevision;
            this.baseStateVersion = baseStateVersion;
            this.revision = revision;
            this.preparedAllocations = preparedAllocations;
            this.writes = writes;
            this.removedIds = removedIds.clone();
            this.freeRanges = freeRanges;
            this.highWater = highWater;
            this.liveBytes = liveBytes;
        }

        VulkanRangeArena<T> owner() {
            return owner;
        }

        long baseRevision() {
            return baseRevision;
        }

        long baseStateVersion() {
            return baseStateVersion;
        }

        long revision() {
            return revision;
        }

        Map<Long, Allocation> nextAllocations() {
            return Collections.unmodifiableMap(preparedAllocations);
        }

        Allocation prospectiveAllocation(long identity) {
            Allocation prepared = preparedAllocations.get(identity);
            if (prepared != null) return prepared;
            for (long removedId : removedIds) {
                if (removedId == identity) return null;
            }
            return owner.allocation(identity);
        }

        List<PlacementWrite<T>> writes() {
            return writes;
        }

        long[] removedIds() {
            return removedIds.clone();
        }

        private NavigableMap<Long, Long> takeFreeRanges() {
            NavigableMap<Long, Long> transferred = freeRanges;
            freeRanges = null;
            return transferred;
        }

        long highWater() {
            return highWater;
        }

        long liveBytes() {
            return liveBytes;
        }

        void markCommitted() {
            committed = true;
        }

        boolean committed() {
            return committed;
        }
    }
}

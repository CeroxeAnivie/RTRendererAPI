package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.LongBuffer;
import java.util.List;

public final class VulkanRangeArenaSelfTest {
   private VulkanRangeArenaSelfTest() {
   }

   public static void main(String[] arguments) {
      preparationDoesNotConsumeCapacityAndAlignmentPaddingIsReusable();
      inPlaceUpdateWritesWithoutRetiringAndGrowthDefersReuse();
      completionReleaseInvalidatesPreparedPlans();
      resetAllocatesASeparateGenerationAndRetiresEveryOldRange();
      unchangedReadsAndRejectedPlansRetainTheirSnapshot();
      randomizedSnapshotsMatchAllocationAndRetirementAccounting();
      System.out.println("VulkanRangeArenaSelfTest passed");
   }

   private static void preparationDoesNotConsumeCapacityAndAlignmentPaddingIsReusable() {
      VulkanRangeArena<Item> arena = new VulkanRangeArena<>(Item::id);
      VulkanRangeArena.Prepared<Item> prepared = arena.prepare(0L, false, List.of(request(1L, 12L, 8L), request(2L, 4L, 64L)), LongBuffer.allocate(0));
      require(arena.state().highWaterBytes() == 0L && arena.allocation(1L) == null, "prepare consumed arena capacity before native admission");
      require(((VulkanRangeArena.Allocation)prepared.nextAllocations().get(1L)).equals(new VulkanRangeArena.Allocation(0L, 16L)), "first aligned range changed");
      require(((VulkanRangeArena.Allocation)prepared.nextAllocations().get(2L)).equals(new VulkanRangeArena.Allocation(64L, 64L)), "second range did not honor 64-byte alignment");
      VulkanRangeArena.State committed = arena.commit(prepared, 0L);
      require(committed.highWaterBytes() == 128L && committed.liveBytes() == 80L && committed.freeBytes() == 48L && committed.freeRangeCount() == 1, "alignment padding was leaked instead of becoming reusable capacity");
   }

   private static void inPlaceUpdateWritesWithoutRetiringAndGrowthDefersReuse() {
      VulkanRangeArena<Item> arena = populatedArena();
      VulkanRangeArena.Prepared<Item> inPlace = arena.prepare(1L, false, List.of(request(1L, 8L, 8L)), LongBuffer.allocate(0));
      require(inPlace.writes().size() == 1 && ((VulkanRangeArena.PlacementWrite)inPlace.writes().get(0)).previous() == null && ((VulkanRangeArena.PlacementWrite)inPlace.writes().get(0)).allocation().offsetBytes() == 0L, "in-place content update was omitted or treated as a relocation");
      require(arena.commit(inPlace, 3L).retiredRangeCount() == 0, "in-place content update retired its still-valid range");
      VulkanRangeArena.Prepared<Item> growth = arena.prepare(2L, false, List.of(request(1L, 128L, 16L)), LongBuffer.allocate(0));
      VulkanRangeArena.Allocation grown = (VulkanRangeArena.Allocation)growth.nextAllocations().get(1L);
      require(grown.offsetBytes() == 128L && grown.capacityBytes() == 128L, "grown allocation did not move to independent capacity");
      VulkanRangeArena.State grownState = arena.commit(growth, 5L);
      require(grownState.pendingRetiredBytes() == 16L && grownState.retiredRangeCount() == 1, "replaced range was not protected by a completion epoch");
      require(arena.releaseThrough(4L).pendingRetiredBytes() == 16L, "range became reusable before its protecting GPU epoch completed");
      VulkanRangeArena.State released = arena.releaseThrough(5L);
      require(released.pendingRetiredBytes() == 0L && released.freeBytes() == 64L, "completed retirement did not merge with adjacent alignment padding");
   }

   private static void completionReleaseInvalidatesPreparedPlans() {
      VulkanRangeArena<Item> arena = populatedArena();
      arena.commit(arena.prepare(1L, false, List.of(), LongBuffer.wrap(new long[]{1L})), 7L);
      VulkanRangeArena.Prepared<Item> stale = arena.prepare(2L, false, List.of(request(3L, 16L, 8L)), LongBuffer.allocate(0));
      arena.releaseThrough(7L);
      expect(IllegalStateException.class, () -> arena.commit(stale, 8L));
      require(arena.allocation(3L) == null, "stale prepared plan published after the free-range generation changed");
   }

   private static void resetAllocatesASeparateGenerationAndRetiresEveryOldRange() {
      VulkanRangeArena<Item> arena = populatedArena();
      VulkanRangeArena.Allocation oldFirst = arena.allocation(1L);
      VulkanRangeArena.Allocation oldSecond = arena.allocation(2L);
      VulkanRangeArena.Prepared<Item> reset = arena.prepare(1L, true, List.of(request(1L, 16L, 8L)), LongBuffer.allocate(0));
      require(reset.removedIds().length == 2, "authoritative reset did not retire every old allocation");
      VulkanRangeArena.Allocation replacement = (VulkanRangeArena.Allocation)reset.nextAllocations().get(1L);
      require(!overlaps(replacement, oldFirst) && !overlaps(replacement, oldSecond), "reset overlapped a range still visible to the previous GPU generation");
      VulkanRangeArena.State state = arena.commit(reset, 9L);
      require(state.liveAllocations() == 1 && state.retiredRangeCount() == 2 && state.pendingRetiredBytes() == 80L, "reset did not separate live and retired generations");
   }

   private static void unchangedReadsAndRejectedPlansRetainTheirSnapshot() {
      VulkanRangeArena<Item> arena = populatedArena();
      var original = arena.state();
      for (int i = 0; i < 10000; i++) require(arena.state() == original, "unchanged diagnostic reads allocated a new snapshot");
      var prepared = arena.prepare(1L, false, List.of(request(3L, 24L, 16L)), LongBuffer.allocate(0));
      require(arena.state() == original, "uncommitted preparation invalidated observable state");
      expect(IllegalArgumentException.class, () -> arena.commit(prepared, -1L));
      require(arena.state() == original, "rejected admission invalidated observable state");
      var committed = arena.commit(prepared, 1L);
      require(committed != original && committed.liveAllocations() == 3 && original.liveAllocations() == 2,
              "commit failed to publish a new independent diagnostic snapshot");
      require(arena.releaseThrough(0L) == committed, "completion without retired ranges invalidated unchanged state");
      var removed = arena.commit(arena.prepare(2L, false, List.of(), LongBuffer.wrap(new long[]{1L})), 5L);
      require(removed.pendingRetiredBytes() == 16L && arena.releaseThrough(4L) == removed,
              "premature completion changed protected retirement statistics");
      var released = arena.releaseThrough(5L);
      require(released != removed && released.pendingRetiredBytes() == 0L && removed.pendingRetiredBytes() == 16L,
              "retirement completion reused stale statistics or mutated an earlier snapshot");
      var stale = arena.prepare(3L, false, List.of(), LongBuffer.allocate(0));
      arena.commit(arena.prepare(3L, false, List.of(), LongBuffer.allocate(0)), 6L);
      var advanced = arena.state();
      expect(IllegalStateException.class, () -> arena.commit(stale, 6L));
      require(arena.state() == advanced, "stale plan rejection changed diagnostic state");
   }

   private static void randomizedSnapshotsMatchAllocationAndRetirementAccounting() {
      var random = new java.util.Random(731921);
      VulkanRangeArena<Item> arena = new VulkanRangeArena<>(Item::id);
      var live = new java.util.HashMap<Long,VulkanRangeArena.Allocation>();
      record Pending(VulkanRangeArena.Allocation range, long epoch) {}
      var pending = new java.util.ArrayList<Pending>();
      for (long revision = 0; revision < 800; revision++) {
         boolean reset = revision % 43 == 0;
         long id = random.nextInt(40), epoch = revision + 7;
         boolean remove = !reset && live.containsKey(id) && random.nextBoolean();
         var previous = live.get(id);
         var requests = remove ? List.<VulkanRangeArena.RangeRequest<Item>>of()
                 : List.of(request(id, 1 + random.nextInt(1024), 1L << random.nextInt(7)));
         var old = arena.state();
         var plan = arena.prepare(revision, reset, requests,
                 remove ? LongBuffer.wrap(new long[]{id}) : LongBuffer.allocate(0));
         require(arena.state() == old, "random preparation changed published state");
         arena.commit(plan, epoch);
         if (reset) {
            for (var allocation : live.values()) pending.add(new Pending(allocation, epoch));
            live.clear();
         } else if (remove) {
            pending.add(new Pending(live.remove(id), epoch));
         }
         if (!remove) {
            var current = arena.allocation(id);
            if (!reset && previous != null && !previous.equals(current)) pending.add(new Pending(previous, epoch));
            live.put(id, current);
         }
         long completed = revision;
         arena.releaseThrough(completed);
         pending.removeIf(entry -> entry.epoch() <= completed);
         var state = arena.state();
         long liveBytes = live.values().stream().mapToLong(VulkanRangeArena.Allocation::capacityBytes).sum();
         long retiredBytes = pending.stream().mapToLong(entry -> entry.range().capacityBytes()).sum();
         require(state.revision() == revision && state.liveAllocations() == live.size()
                 && state.liveBytes() == liveBytes && state.pendingRetiredBytes() == retiredBytes
                 && state.retiredRangeCount() == pending.size(), "randomized diagnostic counters diverged from owned ranges");
         require(state.highWaterBytes() == state.liveBytes() + state.freeBytes() + state.pendingRetiredBytes(),
                 "arena capacity partition lost bytes during reset, alignment or coalescing");
         require(arena.state() == state, "randomized stable read discarded its snapshot");
      }
   }

   private static VulkanRangeArena<Item> populatedArena() {
      VulkanRangeArena<Item> arena = new VulkanRangeArena<>(Item::id);
      arena.commit(arena.prepare(0L, false, List.of(request(1L, 12L, 8L), request(2L, 4L, 64L)), LongBuffer.allocate(0)), 0L);
      return arena;
   }

   private static VulkanRangeArena.RangeRequest<Item> request(long id, long bytes, long alignment) {
      return new VulkanRangeArena.RangeRequest<>(new Item(id), bytes, alignment);
   }

   private static boolean overlaps(VulkanRangeArena.Allocation left, VulkanRangeArena.Allocation right) {
      long leftEnd = left.offsetBytes() + left.capacityBytes();
      long rightEnd = right.offsetBytes() + right.capacityBytes();
      return left.offsetBytes() < rightEnd && right.offsetBytes() < leftEnd;
   }

   private static <T extends Throwable> void expect(Class<T> type, ThrowingRunnable action) {
      try {
         action.run();
      } catch (Throwable failure) {
         if (type.isInstance(failure)) {
            return;
         }

         throw new AssertionError("expected " + type.getName() + " but caught " + String.valueOf(failure), failure);
      }

      throw new AssertionError("expected " + type.getName() + " but no exception was thrown");
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }

   private static record Item(long id) {
   }

   @FunctionalInterface
   private interface ThrowingRunnable {
      void run() throws Throwable;
   }
}

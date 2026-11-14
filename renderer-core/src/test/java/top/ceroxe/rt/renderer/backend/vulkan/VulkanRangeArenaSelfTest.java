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

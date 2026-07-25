package top.ceroxe.rt.renderer.rt.device;

import java.util.List;

public final class VulkanMemoryBudgetPolicySelfTest {
   private static final long MIB = 1048576L;

   private VulkanMemoryBudgetPolicySelfTest() {
   }

   public static void main(String[] args) {
      require(VulkanMemoryBudgetPolicy.evaluate(snapshot(false, 990L, 1000L), 500L).admitted(), "estimated budgets must not fabricate authoritative backpressure");
      require(!VulkanMemoryBudgetPolicy.evaluate(snapshot(true, 960L, 1000L), 0L).admitted(), "high-watermark utilization was admitted");
      require(!VulkanMemoryBudgetPolicy.evaluate(snapshot(true, 838860800L, 943718400L), 52428800L).admitted(), "allocation consumed the safety reserve");
      require(VulkanMemoryBudgetPolicy.evaluate(snapshot(true, 419430400L, 1048576000L), 104857600L).admitted(), "healthy allocation headroom was rejected");
      expect(IllegalArgumentException.class, () -> VulkanMemoryBudgetPolicy.evaluate(snapshot(true, 1L, 2L), -1L));
      System.out.println("VulkanMemoryBudgetPolicySelfTest passed");
   }

   private static VulkanMemoryBudgetSnapshot snapshot(boolean exact, long usage, long budget) {
      return new VulkanMemoryBudgetSnapshot(exact, usage, budget, Math.max(0L, budget - usage), List.of(new VulkanMemoryBudgetSnapshot.HeapBudget(0, true, usage, budget)));
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }

   private static <T extends Throwable> void expect(Class<T> type, Runnable action) {
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
}

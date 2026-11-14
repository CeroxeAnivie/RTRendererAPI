package top.ceroxe.rt.renderer.rt.device;

import java.util.List;

/**
 * Immutable driver/VMA memory-budget snapshot for one physical device.
 *
 * @param driverBudgetAvailable     whether driver budget reporting is available
 * @param deviceLocalUsageBytes     aggregate device-local usage
 * @param deviceLocalBudgetBytes    aggregate device-local budget
 * @param deviceLocalAvailableBytes aggregate available device-local bytes
 * @param heaps                     immutable per-heap budgets
 */
public record VulkanMemoryBudgetSnapshot(
        boolean driverBudgetAvailable,
        long deviceLocalUsageBytes,
        long deviceLocalBudgetBytes,
        long deviceLocalAvailableBytes,
        List<HeapBudget> heaps
) {
    /**
     * Defensively snapshots the heap list and validates aggregate byte accounting.
     */
    public VulkanMemoryBudgetSnapshot {
        if (deviceLocalUsageBytes < 0L || deviceLocalBudgetBytes < 0L || deviceLocalAvailableBytes < 0L) {
            throw new IllegalArgumentException("memory budget values must not be negative");
        }
        if (deviceLocalAvailableBytes != Math.max(0L, deviceLocalBudgetBytes - deviceLocalUsageBytes)) {
            throw new IllegalArgumentException("aggregate available memory does not match usage and budget");
        }
        heaps = List.copyOf(heaps);
    }

    /**
     * Returns aggregate device-local utilization clamped to {@code [0, 1]}.
     *
     * @return zero when the driver reports no budget, otherwise usage divided by budget
     */
    public double deviceLocalUtilization() {
        if (deviceLocalBudgetBytes == 0L) return 0.0D;
        return Math.min(1.0D, (double) deviceLocalUsageBytes / (double) deviceLocalBudgetBytes);
    }

    /**
     * Immutable budget for one Vulkan memory heap.
     *
     * @param index       Vulkan memory heap index
     * @param deviceLocal whether the heap is device local
     * @param usageBytes  current heap usage
     * @param budgetBytes current heap budget
     */
    public record HeapBudget(int index, boolean deviceLocal, long usageBytes, long budgetBytes) {
        /**
         * Validates the heap index and non-negative byte counters.
         */
        public HeapBudget {
            if (index < 0 || usageBytes < 0L || budgetBytes < 0L) {
                throw new IllegalArgumentException("heap budget values are invalid");
            }
        }

        /**
         * Returns the remaining reported budget without underflow.
         *
         * @return {@code max(0, budgetBytes - usageBytes)}
         */
        public long availableBytes() {
            return Math.max(0L, budgetBytes - usageBytes);
        }
    }
}

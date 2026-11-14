package top.ceroxe.rt.renderer.rt.device;

/**
 * Pure admission policy kept separate from native budget sampling.
 */
public final class VulkanMemoryBudgetPolicy {
    private static final long MINIMUM_RESERVE_BYTES = 64L * 1024L * 1024L;
    private static final double HIGH_WATERMARK = 0.95D;

    private VulkanMemoryBudgetPolicy() {
    }

    /**
     * Evaluates whether an allocation may grow without crossing the device-local safety reserve.
     *
     * @param snapshot             immutable budget sample used for the decision
     * @param requestedGrowthBytes additional device-local bytes requested, never negative
     * @return an immutable decision and a diagnostic reason
     */
    public static Admission evaluate(VulkanMemoryBudgetSnapshot snapshot, long requestedGrowthBytes) {
        if (snapshot == null) throw new NullPointerException("snapshot");
        if (requestedGrowthBytes < 0L) {
            throw new IllegalArgumentException("requestedGrowthBytes must not be negative");
        }
        if (!snapshot.driverBudgetAvailable() || snapshot.deviceLocalBudgetBytes() == 0L) {
            return Admission.admitted("driver memory budget unavailable; VMA allocation checks remain active");
        }
        long required = saturatedAdd(requestedGrowthBytes, MINIMUM_RESERVE_BYTES);
        if (snapshot.deviceLocalUtilization() >= HIGH_WATERMARK) {
            return Admission.rejected("device-local memory is above the 95% high watermark");
        }
        if (snapshot.deviceLocalAvailableBytes() < required) {
            return Admission.rejected("device-local memory cannot preserve the 64 MiB safety reserve");
        }
        return Admission.admitted("device-local memory headroom is sufficient");
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    /**
     * Memory-growth admission result.
     *
     * @param admitted whether allocation may proceed
     * @param reason   non-blank human-readable rationale suitable for diagnostics
     */
    public record Admission(boolean admitted, String reason) {
        /**
         * Validates that every decision retains an actionable diagnostic reason.
         */
        public Admission {
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
        }

        private static Admission admitted(String reason) {
            return new Admission(true, reason);
        }

        private static Admission rejected(String reason) {
            return new Admission(false, reason);
        }
    }
}

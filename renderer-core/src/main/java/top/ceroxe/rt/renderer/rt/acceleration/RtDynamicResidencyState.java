package top.ceroxe.rt.renderer.rt.acceleration;

/**
 * Read-only progress of asynchronous dynamic-asset residency.
 *
 * <p>This state is intentionally separate from {@link RtDynamicInstanceSnapshot}.
 * It may drive diagnostics and build budgeting, but it must never decide whether
 * an already-isolated instance snapshot can update or dispatch its TLAS.</p>
 *
 * @param pendingAssetBuilds    admitted asset builds not yet resident
 * @param queuedAssetBuilds     asset builds waiting for admission
 * @param inactiveAssetSlots    inactive physical slots awaiting residency
 * @param replacementAssetSlots slots awaiting replacement residency
 */
public record RtDynamicResidencyState(
        int pendingAssetBuilds,
        int queuedAssetBuilds,
        int inactiveAssetSlots,
        int replacementAssetSlots
) {
    /**
     * Validates that every residency population is non-negative.
     */
    public RtDynamicResidencyState {
        if (pendingAssetBuilds < 0 || queuedAssetBuilds < 0
                || inactiveAssetSlots < 0 || replacementAssetSlots < 0) {
            throw new IllegalArgumentException("dynamic residency counts must not be negative");
        }
    }

    /**
     * Reports whether asset admission or replacement work remains.
     *
     * @return whether residency has backlog
     */
    public boolean hasBacklog() {
        return pendingAssetBuilds > 0 || queuedAssetBuilds > 0
                || inactiveAssetSlots > 0 || replacementAssetSlots > 0;
    }
}

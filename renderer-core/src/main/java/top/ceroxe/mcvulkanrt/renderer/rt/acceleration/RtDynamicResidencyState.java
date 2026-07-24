package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

/**
 * Read-only progress of asynchronous dynamic-asset residency.
 *
 * <p>This state is intentionally separate from {@link RtDynamicInstanceSnapshot}.
 * It may drive diagnostics and build budgeting, but it must never decide whether
 * an already-isolated instance snapshot can update or dispatch its TLAS.</p>
 */
public record RtDynamicResidencyState(
        int pendingAssetBuilds,
        int queuedAssetBuilds,
        int inactiveAssetSlots,
        int replacementAssetSlots
) {
    public RtDynamicResidencyState {
        if (pendingAssetBuilds < 0 || queuedAssetBuilds < 0
                || inactiveAssetSlots < 0 || replacementAssetSlots < 0) {
            throw new IllegalArgumentException("dynamic residency counts must not be negative");
        }
    }

    public boolean hasBacklog() {
        return pendingAssetBuilds > 0 || queuedAssetBuilds > 0
                || inactiveAssetSlots > 0 || replacementAssetSlots > 0;
    }
}

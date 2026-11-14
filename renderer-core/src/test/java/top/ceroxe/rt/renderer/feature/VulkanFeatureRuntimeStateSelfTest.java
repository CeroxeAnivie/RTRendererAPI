package top.ceroxe.rt.renderer.feature;

/** Deterministic contract test for frame-boundary fallback state transitions. */
public final class VulkanFeatureRuntimeStateSelfTest {
    private VulkanFeatureRuntimeStateSelfTest() {}

    public static void main(String[] args) {
        VulkanFeatureRuntimeState state = new VulkanFeatureRuntimeState(
                VulkanFeatureRuntimeState.Status.AVAILABLE, "vendor.feature", "preflight"
        );
        require(state.snapshot().status() == VulkanFeatureRuntimeState.Status.AVAILABLE);
        state.active("vendor.feature", "evaluate succeeded");
        require(state.active());
        state.fallback("builtin.temporal", "evaluate failed; next frame");
        require(state.snapshot().status() == VulkanFeatureRuntimeState.Status.FALLBACK);
        require(!state.active());
        state.unavailable("resource allocation failed");
        require(state.snapshot().status() == VulkanFeatureRuntimeState.Status.UNAVAILABLE);
        state.close();
        require(state.snapshot().status() == VulkanFeatureRuntimeState.Status.CLOSED);
    }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("runtime state contract failed");
    }
}

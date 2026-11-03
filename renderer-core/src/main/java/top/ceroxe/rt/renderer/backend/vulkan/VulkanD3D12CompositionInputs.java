package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.List;
import top.ceroxe.rt.renderer.api.FrameCompositionPlan;

/** Snapshot and pin ownership obtained under the existing renderer lifecycle lock. */
final class VulkanD3D12CompositionInputs implements AutoCloseable {
    @FunctionalInterface interface Resolver {
        VulkanD3D12CompositionInputs resolve(List<FrameCompositionPlan.Layer> layers,int width,int height);
    }
    final VulkanGenericCompositionSource[] sources;
    final int[] layouts,operations;
    int externalLayer;
    boolean linearSrgb;
    private final Runnable commit,release;
    private boolean closed;

    VulkanD3D12CompositionInputs(VulkanGenericCompositionSource[] sources,int[] layouts,int[] operations,
            Runnable commit,Runnable release) {
        this.sources=sources;this.layouts=layouts;this.operations=operations;this.commit=commit;this.release=release;
    }
    void submitted() { commit.run(); }
    @Override public void close() { if(!closed) { release.run();closed=true; } }
}

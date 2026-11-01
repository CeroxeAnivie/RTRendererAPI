package top.ceroxe.rt.renderer.api;

import java.util.List;
import java.util.Optional;
import top.ceroxe.rt.renderer.api.interop.vulkan.D3D12SharedFrame;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenter;

/** CPU-only checks of borrowed-resource identity, extents and odd/even slot handoff. */
public final class D3D12SharedFrameContractSelfTest {
    private D3D12SharedFrameContractSelfTest() { }

    public static void main(String[] args) {
        var first = frame(1, 0, 1920, 1080, 1920L * 1080 * 4, 123, 456, 1, 2);
        var next = frame(1, 3, 1920, 1080, first.allocationSize(), 123, 456, 3, 4);
        require(first.memoryHandle() == next.memoryHandle() && first.fenceHandle() == next.fenceHandle(),
                "Advancing a slot retains borrowed resource and fence handles");
        require(first.adapterLuid() == 0x0123456789abcdefL && first.resourceId() == 1,
                "Packed adapter LUID and allocation generation are preserved");
        require(first.equals(frame(1, 0, 1920, 1080, first.allocationSize(), 123, 456, 1, 2)),
                "Descriptors retain immutable value semantics");
        frame(1, 0, 1, 1, 4, Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE - 2, Long.MAX_VALUE - 1);
        frame(1, 0, 16384, 16384, 16384L * 16384 * 4, 123, 456, 1, 2);
        reject(() -> frame(0, 0, 1, 1, 4, 123, 456, 1, 2));
        reject(() -> frame(1, -1, 1, 1, 4, 123, 456, 1, 2));
        reject(() -> frame(1, 0, 0, 1, 4, 123, 456, 1, 2));
        reject(() -> frame(1, 0, 1, 0, 4, 123, 456, 1, 2));
        reject(() -> frame(1, 0, 16385, 1, 65540, 123, 456, 1, 2));
        reject(() -> frame(1, 0, 1, 16385, 65540, 123, 456, 1, 2));
        reject(() -> frame(1, 0, 1920, 1080, first.allocationSize() - 1, 123, 456, 1, 2));
        for (long invalid : new long[]{0, -1}) {
            reject(() -> frame(1, 0, 1, 1, 4, invalid, 456, 1, 2));
            reject(() -> frame(1, 0, 1, 1, 4, 123, invalid, 1, 2));
        }
        for (long invalid : new long[]{-1, 0, 2, Long.MAX_VALUE}) {
            reject(() -> frame(1, 0, 1, 1, 4, 123, 456, invalid, invalid + 1));
        }
        reject(() -> frame(1, 0, 1, 1, 4, 123, 456, 1, 1));
        reject(() -> frame(1, 0, 1, 1, 4, 123, 456, 1, 4));
        defaultPresenterNeverDropsLayers(first);
        System.out.println("D3D12SharedFrameContractSelfTest passed");
    }

    private static void defaultPresenterNeverDropsLayers(D3D12SharedFrame frame) {
        var presenter=new LegacyOnlyPresenter();
        require(presenter.presentLatestFrame(List.of()).isEmpty(),"Empty layers preserve existing presenter implementations");
        require(presenter.latestCalls==1,"Empty-layer overload forwards to the original method");
        var mutation=new ResourceMutationKey(new ResourceGenerationKey(new RenderResourceId(7),ResourceVersion.initial()),1);
        var layers=List.of(new FrameCompositionPlan.Layer(mutation,FrameCompositionPlan.Operation.ALPHA_OVER));
        try { presenter.presentLatestFrame(layers);throw new AssertionError("Managed overlays were silently dropped"); }
        catch(UnsupportedOperationException expected) { }
        require(presenter.latestCalls==1,"Unsupported overlays never render an incomplete scene");
        try { presenter.presentD3D12SharedFrame(frame,layers);throw new AssertionError("External overlays were silently dropped"); }
        catch(UnsupportedOperationException expected) { }
    }

    private static D3D12SharedFrame frame(long id, long sequence, int width, int height,
            long allocation, long memory, long fence, long ready, long completion) {
        return new D3D12SharedFrame(id, sequence, 0x0123456789abcdefL, width, height,
                allocation, memory, fence, ready, completion);
    }

    private static void reject(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid shared-frame descriptor was accepted");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class LegacyOnlyPresenter implements VulkanFramePresenter {
        int latestCalls;
        @Override public void pollEvents() { }
        @Override public WindowState windowState() { return new WindowState(false,32,32); }
        @Override public SwapchainPresentMode activePresentMode() { return SwapchainPresentMode.FIFO; }
        @Override public PerformanceSnapshot performanceSnapshot() { return new PerformanceSnapshot(0,0,0,0,0,0,0,0); }
        @Override public void setTitle(String title) { }
        @Override public void setOverlayText(String text) { }
        @Override public Optional<PresentationResult> presentLatestFrame() { latestCalls++;return Optional.empty(); }
        @Override public PresentationResult presentAndRelease(GpuFrameLease lease) { throw new UnsupportedOperationException(); }
        @Override public void close() { }
    }
}

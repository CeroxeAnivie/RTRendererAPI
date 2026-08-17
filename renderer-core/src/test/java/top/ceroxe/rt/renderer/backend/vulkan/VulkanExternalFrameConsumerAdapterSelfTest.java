package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import top.ceroxe.rt.renderer.api.FrameOutputFormat;
import top.ceroxe.rt.renderer.api.interop.ExternalFrameCompletionEvidence;
import top.ceroxe.rt.renderer.api.interop.ExternalFrameConsumerCapabilities;
import top.ceroxe.rt.renderer.api.interop.ExternalFrameConsumerSession;
import top.ceroxe.rt.renderer.api.interop.ExternalFrameInterop;
import top.ceroxe.rt.renderer.api.interop.ExternalFrameLease;
import top.ceroxe.rt.renderer.api.interop.ExternalFrameNegotiation;
import top.ceroxe.rt.renderer.api.interop.ExternalFrameOffer;
import top.ceroxe.rt.renderer.api.interop.ExternalFrameTransport;
import top.ceroxe.rt.renderer.api.interop.ExternalHandleState;
import top.ceroxe.rt.renderer.api.interop.ExternalMemoryHandleType;
import top.ceroxe.rt.renderer.api.interop.ExternalSynchronizationContract;
import top.ceroxe.rt.renderer.api.interop.OwnedExternalHandle;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFrameInterop;

import java.util.List;
import java.util.Optional;

/** Contract-only test for the old Vulkan ABI to generic external-frame adapter. */
public final class VulkanExternalFrameConsumerAdapterSelfTest {
    private VulkanExternalFrameConsumerAdapterSelfTest() {
    }

    public static void main(String[] args) {
        TrackingHandle memory = new TrackingHandle();
        VulkanGpuFrameLease lease = new VulkanGpuFrameLease(descriptor(), memory, () -> { });
        ExternalFrameInterop adapter = new VulkanExternalFrameConsumerAdapter(
                new SingleFrameInterop(lease), FrameOutputFormat.SDR_RGBA8
        );
        ExternalFrameOffer offer = adapter.offer();
        ExternalFrameTransport transport = offer.transports().get(0);
        ExternalFrameConsumerSession session = ((ExternalFrameNegotiation.Accepted) adapter.negotiate(
                new ExternalFrameConsumerCapabilities(List.of(transport))
        )).session();
        ExternalFrameConsumerSession.PollResult result = session.pollLatestFrame();
        ExternalFrameLease generic = ((ExternalFrameConsumerSession.FrameAvailable) result).lease();
        require(generic.descriptor().format() == FrameOutputFormat.SDR_RGBA8, "format mapping failed");
        require(generic.memoryHandle().handleType().equals(transport.memoryHandleType()), "memory contract mismatch");
        generic.memoryHandle().markImported();
        generic.release(new ExternalFrameCompletionEvidence.CpuObserved(generic.descriptor().frameSequence()));
        generic.close();
        require(generic.state() == ExternalFrameLease.LeaseState.CLOSED, "generic lease did not close");
        session.close();
        System.out.println("VulkanExternalFrameConsumerAdapterSelfTest passed");
    }

    private static GpuFrameLease.FrameDescriptor descriptor() {
        return GpuFrameLease.FrameDescriptor.builder()
                .resourceId(1L).frameSequence(2L).renderedSceneRevision(3L)
                .extent(4, 4).format(new GpuFrameLease.VulkanFormat(VK10.VK_FORMAT_R8G8B8A8_UNORM))
                .imageType(new GpuFrameLease.VulkanImageType(VK10.VK_IMAGE_TYPE_2D))
                .imageTiling(new GpuFrameLease.VulkanImageTiling(VK10.VK_IMAGE_TILING_OPTIMAL))
                .imageUsage(new GpuFrameLease.VulkanImageUsage(VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT))
                .imageCreateFlags(new GpuFrameLease.VulkanImageCreateFlags(0))
                .imageLayout(new GpuFrameLease.VulkanImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL))
                .mipLevels(1).arrayLayers(1)
                .sampleCount(new GpuFrameLease.VulkanSampleCount(VK10.VK_SAMPLE_COUNT_1_BIT))
                .sharingMode(new GpuFrameLease.VulkanSharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE))
                .producerQueueFamily(new GpuFrameLease.VulkanQueueFamily(0))
                .memoryTypeIndex(0).allocationSize(64L).allocationOffset(0L).dedicatedAllocation(true)
                .build();
    }

    private static final class SingleFrameInterop implements VulkanFrameInterop {
        private GpuFrameLease lease;

        private SingleFrameInterop(GpuFrameLease lease) {
            this.lease = lease;
        }

        @Override
        public FramePollResult pollLatestFrame() {
            if (lease == null) return FrameNotReady.INSTANCE;
            GpuFrameLease value = lease;
            lease = null;
            return new FrameAvailable(value);
        }
    }

    private static final class TrackingHandle implements GpuFrameLease.ExportedNativeHandle<GpuFrameLease.VulkanMemoryHandleType> {
        private GpuFrameLease.HandleState state = GpuFrameLease.HandleState.EXPORTED;

        @Override public long value() { return 1L; }
        @Override public GpuFrameLease.VulkanMemoryHandleType handleType() {
            return new GpuFrameLease.VulkanMemoryHandleType(VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);
        }
        @Override public GpuFrameLease.ImportDisposition importDisposition() {
            return GpuFrameLease.ImportDisposition.CALLER_RETAINS_HANDLE;
        }
        @Override public GpuFrameLease.HandleState state() { return state; }
        @Override public boolean markImported() {
            if (state != GpuFrameLease.HandleState.EXPORTED) return false;
            state = GpuFrameLease.HandleState.IMPORTED;
            return true;
        }
        @Override public void close() { state = GpuFrameLease.HandleState.CLOSED; }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

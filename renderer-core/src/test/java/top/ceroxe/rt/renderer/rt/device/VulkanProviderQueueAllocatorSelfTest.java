package top.ceroxe.rt.renderer.rt.device;

import top.ceroxe.rt.renderer.feature.VulkanFeatureQueueAllocation;
import top.ceroxe.rt.renderer.feature.VulkanQueueRequirements;

import java.util.Map;

/** Regression gate for provider-owned Vulkan queue identity and optional allocation. */
public final class VulkanProviderQueueAllocatorSelfTest {
    private VulkanProviderQueueAllocatorSelfTest() {
    }

    public static void main(String[] arguments) {
        publishesAcceptedPreferredQueues();
        rejectsUnavailablePreferredQueuesWithoutAliasingQueueZero();
        alwaysPublishesRequiredQueues();
        fallsBackOnlyUnavailableOpticalFlowToGraphicsInterop();
        rejectsTopologyAccountingMismatch();
    }

    private static void publishesAcceptedPreferredQueues() {
        Map<String, VulkanFeatureQueueAllocation> allocations = VulkanProviderQueueAllocator.allocate(
                3, 3, 5, 7, 1, true, true,
                Map.of("nvidia", VulkanQueueRequirements.NONE),
                Map.of("nvidia", new VulkanQueueRequirements(1, 1, 1))
        );
        require(allocations.get("nvidia").equals(
                new VulkanFeatureQueueAllocation(3, 3, 4, 3, 0, 7)
        ));
        expect(UnsupportedOperationException.class, allocations::clear);
    }

    private static void rejectsUnavailablePreferredQueuesWithoutAliasingQueueZero() {
        VulkanFeatureQueueAllocation allocation = VulkanProviderQueueAllocator.allocate(
                3, 3, 3, -1, 0, false, false,
                Map.of("nvidia", VulkanQueueRequirements.NONE),
                Map.of("nvidia", new VulkanQueueRequirements(1, 1, 1))
        ).get("nvidia");
        require(allocation.equals(VulkanFeatureQueueAllocation.NONE));
    }

    private static void alwaysPublishesRequiredQueues() {
        VulkanFeatureQueueAllocation allocation = VulkanProviderQueueAllocator.allocate(
                2, 2, 4, -1, 0, false, false,
                Map.of("required", new VulkanQueueRequirements(1, 1, 0)),
                Map.of("required", VulkanQueueRequirements.NONE)
        ).get("required");
        require(allocation.equals(new VulkanFeatureQueueAllocation(2, 2, 3, 2, 2, 2)));
    }

    private static void fallsBackOnlyUnavailableOpticalFlowToGraphicsInterop() {
        VulkanFeatureQueueAllocation allocation = VulkanProviderQueueAllocator.allocate(
                2, 2, 4, -1, 0, false, false,
                Map.of("nvidia", new VulkanQueueRequirements(1, 1, 0)),
                Map.of("nvidia", new VulkanQueueRequirements(0, 0, 1))
        ).get("nvidia");
        require(allocation.equals(new VulkanFeatureQueueAllocation(2, 2, 3, 2, 2, 2)));
    }

    private static void rejectsTopologyAccountingMismatch() {
        expect(IllegalStateException.class, () -> VulkanProviderQueueAllocator.allocate(
                0, 3, 4, -1, 0, false, false,
                Map.of(), Map.of()
        ));
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected " + type.getSimpleName());
        } catch (Throwable failure) {
            if (!type.isInstance(failure)) {
                throw new AssertionError("unexpected failure", failure);
            }
        }
    }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("provider queue allocation contract failed");
    }
}

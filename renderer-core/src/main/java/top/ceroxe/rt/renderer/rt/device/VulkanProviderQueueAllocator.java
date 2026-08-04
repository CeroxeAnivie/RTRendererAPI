package top.ceroxe.rt.renderer.rt.device;

import top.ceroxe.rt.renderer.feature.VulkanFeatureQueueAllocation;
import top.ceroxe.rt.renderer.feature.VulkanQueueRequirements;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;

/** Resolves provider queue roles to only the queues actually created on the logical device. */
final class VulkanProviderQueueAllocator {
    private VulkanProviderQueueAllocator() {
    }

    static Map<String, VulkanFeatureQueueAllocation> allocate(
            int primaryFamilyIndex,
            int firstProviderPrimaryQueue,
            int primaryQueueCount,
            int opticalFlowFamilyIndex,
            int opticalFlowQueueCount,
            boolean preferredPrimaryAllocated,
            boolean preferredOpticalAllocated,
            Map<String, VulkanQueueRequirements> requiredByProvider,
            Map<String, VulkanQueueRequirements> preferredByProvider
    ) {
        if (primaryFamilyIndex < 0 || firstProviderPrimaryQueue < 0
                || primaryQueueCount < firstProviderPrimaryQueue
                || opticalFlowQueueCount < 0
                || (opticalFlowQueueCount > 0) != (opticalFlowFamilyIndex >= 0)) {
            throw new IllegalArgumentException("invalid logical-device queue topology");
        }
        Map<String, VulkanQueueRequirements> required = Map.copyOf(
                Objects.requireNonNull(requiredByProvider, "requiredByProvider")
        );
        Map<String, VulkanQueueRequirements> preferred = Map.copyOf(
                Objects.requireNonNull(preferredByProvider, "preferredByProvider")
        );
        LinkedHashSet<String> providerIds = new LinkedHashSet<>();
        providerIds.addAll(requiredByProvider.keySet());
        providerIds.addAll(preferredByProvider.keySet());

        LinkedHashMap<String, VulkanFeatureQueueAllocation> result = new LinkedHashMap<>();
        int nextPrimaryQueue = firstProviderPrimaryQueue;
        int nextOpticalQueue = 0;
        for (String providerId : providerIds) {
            String checkedId = Objects.requireNonNull(providerId, "providerId");
            VulkanQueueRequirements requiredQueues = required.getOrDefault(
                    checkedId, VulkanQueueRequirements.NONE
            );
            VulkanQueueRequirements preferredQueues = preferred.getOrDefault(
                    checkedId, VulkanQueueRequirements.NONE
            );
            VulkanQueueRequirements allocated = requiredQueues;
            if (preferredPrimaryAllocated) {
                allocated = allocated.plus(new VulkanQueueRequirements(
                        preferredQueues.additionalGraphicsQueues(),
                        preferredQueues.additionalComputeQueues(),
                        0
                ));
            }
            if (preferredOpticalAllocated) {
                allocated = allocated.plus(new VulkanQueueRequirements(
                        0, 0, preferredQueues.additionalOpticalFlowQueues()
                ));
            }

            boolean graphicsRequested = Math.addExact(
                    requiredQueues.additionalGraphicsQueues(),
                    preferredQueues.additionalGraphicsQueues()
            ) > 0;
            boolean computeRequested = Math.addExact(
                    requiredQueues.additionalComputeQueues(),
                    preferredQueues.additionalComputeQueues()
            ) > 0;
            int graphicsIndex = allocated.additionalGraphicsQueues() > 0
                    ? nextPrimaryQueue : graphicsRequested ? -1 : 0;
            int graphicsFamily = graphicsIndex < 0 ? -1 : primaryFamilyIndex;
            nextPrimaryQueue = Math.addExact(
                    nextPrimaryQueue, allocated.additionalGraphicsQueues()
            );
            int computeIndex = allocated.additionalComputeQueues() > 0
                    ? nextPrimaryQueue : computeRequested ? -1 : 0;
            int computeFamily = computeIndex < 0 ? -1 : primaryFamilyIndex;
            nextPrimaryQueue = Math.addExact(
                    nextPrimaryQueue, allocated.additionalComputeQueues()
            );
            /*
             * Streamline explicitly supports optical-flow interop on its graphics queue when no
             * exclusive NV optical-flow family exists. This is the one role where fallback is a
             * documented execution mode; graphics/compute requests above remain fail-closed.
             */
            int opticalIndex = allocated.additionalOpticalFlowQueues() > 0
                    ? nextOpticalQueue : graphicsIndex;
            int opticalFamily = allocated.additionalOpticalFlowQueues() > 0
                    ? opticalFlowFamilyIndex : opticalIndex < 0 ? -1 : graphicsFamily;
            nextOpticalQueue = Math.addExact(
                    nextOpticalQueue, allocated.additionalOpticalFlowQueues()
            );
            result.put(checkedId, new VulkanFeatureQueueAllocation(
                    graphicsIndex, graphicsFamily,
                    computeIndex, computeFamily,
                    opticalIndex, opticalFamily
            ));
        }
        if (nextPrimaryQueue != primaryQueueCount) {
            throw new IllegalStateException(
                    "provider primary queue allocation does not match logical-device queue count"
            );
        }
        if (nextOpticalQueue != opticalFlowQueueCount) {
            throw new IllegalStateException(
                    "provider optical-flow allocation does not match logical-device queue count"
            );
        }
        return Collections.unmodifiableMap(result);
    }
}

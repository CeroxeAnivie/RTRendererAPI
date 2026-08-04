package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.renderer.feature.VulkanFeatureOpenContext;
import top.ceroxe.rt.renderer.feature.VulkanFeatureQueueAllocation;

import java.util.Objects;
import java.util.Set;

/** Explicit owner of a claimed Streamline preflight lease and its bound Vulkan device state. */
final class NvidiaStreamlineDeviceSession implements AutoCloseable {
    private final Set<NvidiaStreamlineRuntime.Feature> features;
    private boolean closed;

    private NvidiaStreamlineDeviceSession(Set<NvidiaStreamlineRuntime.Feature> features) {
        this.features = Set.copyOf(features);
    }

    static NvidiaStreamlineDeviceSession bind(
            VulkanFeatureOpenContext context,
            Set<NvidiaStreamlineRuntime.Feature> prepared,
            Set<NvidiaStreamlineRuntime.Feature> required
    ) {
        VulkanFeatureOpenContext checked = Objects.requireNonNull(context, "context");
        Set<NvidiaStreamlineRuntime.Feature> checkedPrepared = Set.copyOf(prepared);
        if (checkedPrepared.isEmpty()) {
            throw new IllegalArgumentException("Streamline device session requires a prepared feature");
        }
        try {
            VulkanFeatureQueueAllocation queues = checked.device().featureQueueAllocation("nvidia");
            Set<NvidiaStreamlineRuntime.Feature> executable = NvidiaStreamlineRuntime.bindDevice(
                    checked.device().instance().address(),
                    checked.device().physicalDevice().address(),
                    checked.device().device().address(),
                    new NvidiaStreamlineRuntime.QueueRanges(
                            queues.computeQueueIndex(),
                            queues.computeQueueFamily(),
                            queues.graphicsQueueIndex(),
                            queues.graphicsQueueFamily(),
                            queues.opticalFlowQueueIndex(),
                            queues.opticalFlowQueueFamily(),
                            queues.opticalFlowQueueFamily() != queues.graphicsQueueFamily()
                    ),
                    required
            );
            return new NvidiaStreamlineDeviceSession(executable);
        } catch (RuntimeException | Error failure) {
            closeLeaseSuppressing(failure);
            throw failure;
        }
    }

    Set<NvidiaStreamlineRuntime.Feature> features() {
        if (closed) return Set.of();
        return features;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        NvidiaStreamlineRuntime.closePreflight();
    }

    private static void closeLeaseSuppressing(Throwable failure) {
        try {
            NvidiaStreamlineRuntime.closePreflight();
        } catch (RuntimeException | Error closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}

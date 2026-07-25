package consumer;

import java.time.Duration;
import java.util.Objects;

import top.ceroxe.rt.renderer.api.RayTracingRenderer;
import top.ceroxe.rt.renderer.api.RendererDeviceException;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFrameInterop;

/**
 * Compile-checked expert consumer for the published Vulkan extension.
 *
 * <p>The application-specific {@link NativeFrameConsumer} is the only place that may contain raw
 * LWJGL/Vulkan calls. The surrounding control flow is backend-neutral, exhaustive, bounded, and
 * guarantees that a successfully consumed lease is released before it is closed.</p>
 */
public final class PublishedVulkanInteropConsumer {
    private PublishedVulkanInteropConsumer() {
    }

    /**
     * Imports and consumes at most one completed external image.
     *
     * @param renderer open renderer
     * @param timeout non-negative maximum wait
     * @param consumer application-owned native Vulkan consumer
     * @return {@code true} when a frame was consumed, otherwise {@code false} on timeout
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public static boolean consumeLatest(
            RayTracingRenderer renderer,
            Duration timeout,
            NativeFrameConsumer consumer
    ) throws InterruptedException {
        Objects.requireNonNull(renderer, "renderer");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(consumer, "consumer");
        VulkanFrameInterop interop = renderer.extension(VulkanFrameInterop.class)
                .orElseThrow(() -> new IllegalStateException("Vulkan frame interop is unavailable"));
        VulkanFrameInterop.FramePollResult result = interop.awaitLatestFrame(timeout);
        if (result == VulkanFrameInterop.FrameNotReady.INSTANCE) return false;

        GpuFrameLease lease = ((VulkanFrameInterop.FrameAvailable) result).lease();
        try (lease) {
            GpuFrameLease.ConsumerCompletion completion = Objects.requireNonNull(
                    consumer.importSubmitAndComplete(lease), "consumer completion"
            );
            lease.release(completion);
            if (lease.state() != GpuFrameLease.LeaseState.RELEASED) {
                throw new IllegalStateException("lease did not publish RELEASED after completion");
            }
        }
        return true;
    }

    /**
     * Retries exactly once when the backend explicitly reports that it already recreated the
     * device and the rejected operation is safe to repeat unchanged.
     *
     * @param operation one retry-safe renderer operation
     * @param <T> result type
     * @return operation result
     */
    public static <T> T retryAfterAutomaticRecovery(RendererOperation<T> operation) {
        RendererOperation<T> checked = Objects.requireNonNull(operation, "operation");
        try {
            return checked.execute();
        } catch (RendererDeviceException failure) {
            if (failure.recoveryAction() != RendererDeviceException.RecoveryAction.RETRY_OPERATION) {
                throw failure;
            }
            return checked.execute();
        }
    }

    /**
     * Maps a device exception to an exhaustive caller policy without parsing its message.
     *
     * @param failure typed device failure
     * @return caller policy
     */
    public static CallerRecovery callerRecovery(RendererDeviceException failure) {
        return switch (Objects.requireNonNull(failure, "failure").recoveryAction()) {
            case RETRY_OPERATION -> CallerRecovery.RETRY_SAME_OPERATION;
            case RECREATE_RENDERER -> CallerRecovery.REPLAY_AUTHORITY_ON_NEW_RENDERER;
            case REDUCE_MEMORY_AND_RECREATE -> CallerRecovery.REDUCE_MEMORY_THEN_REPLAY;
            case ABORT -> CallerRecovery.ABORT;
        };
    }

    /**
     * Application Vulkan boundary. Implementations must import each exported handle and call its
     * {@code markImported()} immediately after the matching Vulkan import succeeds; wait for the
     * acquire signal; perform required external queue-family ownership transfers; and return only
     * a completion advertised by {@link GpuFrameLease#consumerCompletionCapabilities()}.
     */
    @FunctionalInterface
    public interface NativeFrameConsumer {
        /**
         * Imports, submits, and publishes completion for one active lease.
         *
         * @param lease exclusively owned active lease
         * @return negotiated consumer completion
         */
        GpuFrameLease.ConsumerCompletion importSubmitAndComplete(GpuFrameLease lease);
    }

    /** One operation explicitly safe to repeat after {@code RETRY_OPERATION}. */
    @FunctionalInterface
    public interface RendererOperation<T> {
        /** @return operation result */
        T execute();
    }

    /** Exhaustive caller-side recovery policy. */
    public enum CallerRecovery {
        /** Retry the rejected operation unchanged on the automatically recovered renderer. */
        RETRY_SAME_OPERATION,
        /** Close the failed renderer, open a replacement, and replay caller-owned authority. */
        REPLAY_AUTHORITY_ON_NEW_RENDERER,
        /** Reduce memory pressure before opening and replaying a replacement renderer. */
        REDUCE_MEMORY_THEN_REPLAY,
        /** Stop rendering because automated recovery is unsafe. */
        ABORT
    }
}

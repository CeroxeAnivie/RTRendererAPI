package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One exclusive consumer claim on a producer-complete Vulkan frame slot.
 */
final class VulkanGpuFrameLease implements GpuFrameLease {
    private final FrameDescriptor descriptor;
    private final ExportedNativeHandle<VulkanMemoryHandleType> memoryHandle;
    private final ConsumerCompletionCapabilities completionCapabilities;
    private final Consumer<ConsumerCompletion> completionObserver;

    private LeaseState state = LeaseState.ACTIVE;

    VulkanGpuFrameLease(
            FrameDescriptor descriptor,
            ExportedNativeHandle<VulkanMemoryHandleType> memoryHandle,
            Runnable completionObserver
    ) {
        this(descriptor, memoryHandle, ConsumerCompletionCapabilities.cpuOnly(), adapt(completionObserver));
    }

    VulkanGpuFrameLease(
            FrameDescriptor descriptor,
            ExportedNativeHandle<VulkanMemoryHandleType> memoryHandle,
            ConsumerCompletionCapabilities completionCapabilities,
            Consumer<ConsumerCompletion> completionObserver
    ) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.memoryHandle = Objects.requireNonNull(memoryHandle, "memoryHandle");
        this.completionCapabilities = Objects.requireNonNull(completionCapabilities, "completionCapabilities");
        this.completionObserver = Objects.requireNonNull(completionObserver, "completionObserver");
    }

    private static Consumer<ConsumerCompletion> adapt(Runnable observer) {
        Runnable checked = Objects.requireNonNull(observer, "completionObserver");
        return ignored -> checked.run();
    }

    @Override
    public FrameDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ExportedNativeHandle<VulkanMemoryHandleType> memoryHandle() {
        return memoryHandle;
    }

    @Override
    public Optional<AcquireSignal> acquireSignal() {
        // The lease is created only after the producer fence has completed on the CPU.
        return Optional.empty();
    }

    @Override
    public ConsumerCompletionCapabilities consumerCompletionCapabilities() {
        return completionCapabilities;
    }

    @Override
    public synchronized void release(ConsumerCompletion completion) {
        Objects.requireNonNull(completion, "completion");
        if (state == LeaseState.CLOSED) throw new IllegalStateException("frame lease is closed");
        if (state == LeaseState.RELEASED) return;
        if (completion instanceof CpuCompleted && !completionCapabilities.cpuCompleted()) {
            throw new UnsupportedOperationException(
                    "CPU completion is not enabled for this frame lease"
            );
        }
        if (completion instanceof ExternalSemaphoreSignal signal
                && !completionCapabilities.supports(signal.kind())) {
            throw new UnsupportedOperationException(
                    "external " + signal.kind() + " semaphore completion is not enabled"
            );
        }
        completionObserver.accept(completion);
        state = LeaseState.RELEASED;
    }

    @Override
    public synchronized LeaseState state() {
        return state;
    }

    @Override
    public synchronized void close() {
        if (state == LeaseState.CLOSED) return;
        if (state == LeaseState.ACTIVE && memoryHandle.state() == HandleState.IMPORTED) {
            throw new IllegalStateException(
                    "an imported frame lease must publish consumer completion before close"
            );
        }
        RuntimeException failure = null;
        try {
            memoryHandle.close();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        }
        if (state == LeaseState.ACTIVE) {
            try {
                if (!completionCapabilities.cpuCompleted()) {
                    throw new IllegalStateException("lease requires an explicit GPU completion signal before close");
                }
                completionObserver.accept(new CpuCompleted());
                state = LeaseState.RELEASED;
            } catch (RuntimeException observerFailure) {
                if (failure == null) failure = observerFailure;
                else failure.addSuppressed(observerFailure);
            }
        }
        if (failure != null) throw failure;
        state = LeaseState.CLOSED;
    }
}

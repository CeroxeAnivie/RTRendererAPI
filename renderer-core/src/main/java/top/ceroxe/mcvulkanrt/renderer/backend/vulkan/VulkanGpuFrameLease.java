package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import top.ceroxe.mcvulkanrt.renderer.api.GpuFrameLease;

import java.util.Objects;
import java.util.Optional;

/** One exclusive consumer claim on a producer-complete Vulkan frame slot. */
final class VulkanGpuFrameLease implements GpuFrameLease {
    private final FrameDescriptor descriptor;
    private final VulkanExportedMemoryHandle memoryHandle;
    private final Runnable completionObserver;

    private boolean released;
    private boolean closed;

    VulkanGpuFrameLease(
            FrameDescriptor descriptor,
            VulkanExportedMemoryHandle memoryHandle,
            Runnable completionObserver
    ) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.memoryHandle = Objects.requireNonNull(memoryHandle, "memoryHandle");
        this.completionObserver = Objects.requireNonNull(completionObserver, "completionObserver");
    }

    @Override
    public FrameDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ExportedNativeHandle memoryHandle() {
        return memoryHandle;
    }

    @Override
    public Optional<AcquireSignal> acquireSignal() {
        // The lease is created only after the producer fence has completed on the CPU.
        return Optional.empty();
    }

    @Override
    public synchronized void release(ConsumerCompletion completion) {
        Objects.requireNonNull(completion, "completion");
        if (closed) throw new IllegalStateException("frame lease is closed");
        if (released) return;
        if (!(completion instanceof CpuCompleted)) {
            throw new UnsupportedOperationException(
                    "external consumer semaphore import is not enabled; supply CpuCompleted after waiting"
            );
        }
        released = true;
        completionObserver.run();
    }

    @Override
    public synchronized boolean released() {
        return released;
    }

    @Override
    public synchronized boolean closed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        if (!released && memoryHandle.state() == HandleState.IMPORTED) {
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
        if (!released) {
            released = true;
            try {
                completionObserver.run();
            } catch (RuntimeException observerFailure) {
                if (failure == null) failure = observerFailure;
                else failure.addSuppressed(observerFailure);
            }
        }
        closed = true;
        if (failure != null) throw failure;
    }
}

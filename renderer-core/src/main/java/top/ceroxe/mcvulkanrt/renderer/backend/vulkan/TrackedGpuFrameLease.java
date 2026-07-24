package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import top.ceroxe.mcvulkanrt.renderer.api.GpuFrameLease;

import java.util.Objects;
import java.util.Optional;

/**
 * Makes lease relinquishment observable without weakening the public ownership contract.
 *
 * <p>The host must keep the Vulkan session alive while a consumer can still submit work against
 * an exported image. A lease leaves that set only after its backend lease reports a successful
 * close. In particular, a failed close of an imported, unreleased lease does not notify the host:
 * the consumer still owes an explicit completion signal.</p>
 */
final class TrackedGpuFrameLease implements GpuFrameLease {
    private final GpuFrameLease delegate;
    private final Runnable closeObserver;

    private boolean closeObserved;

    TrackedGpuFrameLease(GpuFrameLease delegate, Runnable closeObserver) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.closeObserver = Objects.requireNonNull(closeObserver, "closeObserver");
    }

    @Override
    public synchronized FrameDescriptor descriptor() {
        return delegate.descriptor();
    }

    @Override
    public synchronized ExportedNativeHandle memoryHandle() {
        return delegate.memoryHandle();
    }

    @Override
    public synchronized Optional<AcquireSignal> acquireSignal() {
        return delegate.acquireSignal();
    }

    @Override
    public synchronized void release(ConsumerCompletion completion) {
        delegate.release(Objects.requireNonNull(completion, "completion"));
        if (!delegate.released()) {
            throw new IllegalStateException("backend lease accepted release without publishing released state");
        }
    }

    @Override
    public synchronized boolean released() {
        return delegate.released();
    }

    @Override
    public synchronized boolean closed() {
        return delegate.closed();
    }

    @Override
    public synchronized void close() {
        if (closeObserved) {
            return;
        }
        delegate.close();
        if (!delegate.closed()) {
            throw new IllegalStateException("backend lease close returned without publishing closed state");
        }
        closeObserved = true;
        closeObserver.run();
    }
}

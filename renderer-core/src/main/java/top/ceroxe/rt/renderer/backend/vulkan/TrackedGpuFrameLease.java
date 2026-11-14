package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease;

import java.util.Objects;
import java.util.Optional;
import java.util.function.LongConsumer;

/**
 * Makes lease relinquishment observable without weakening the public ownership contract.
 *
 * <p>The host must keep the Vulkan session alive while a consumer can still submit work against
 * an exported image. A lease leaves that set only after its backend lease reports a successful
 * close. In particular, a failed close of an imported, unreleased lease does not notify the host:
 * the consumer still owes an explicit completion signal.</p>
 */
final class TrackedGpuFrameLease implements GpuFrameLease, VulkanManagedFrameLease {
    private final GpuFrameLease delegate;
    private final Runnable closeObserver;
    private final LongConsumer consumerAcceptanceObserver;

    private boolean closeObserved;

    TrackedGpuFrameLease(GpuFrameLease delegate, Runnable closeObserver) {
        this(delegate, closeObserver, ignored -> { });
    }

    TrackedGpuFrameLease(
            GpuFrameLease delegate, Runnable closeObserver, LongConsumer consumerAcceptanceObserver
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.closeObserver = Objects.requireNonNull(closeObserver, "closeObserver");
        this.consumerAcceptanceObserver = Objects.requireNonNull(
                consumerAcceptanceObserver, "consumerAcceptanceObserver"
        );
    }

    @Override
    public synchronized FrameDescriptor descriptor() {
        return delegate.descriptor();
    }

    @Override
    public synchronized ExportedNativeHandle<VulkanMemoryHandleType> memoryHandle() {
        return delegate.memoryHandle();
    }

    @Override
    public synchronized Optional<AcquireSignal> acquireSignal() {
        return delegate.acquireSignal();
    }

    @Override
    public synchronized ConsumerCompletionCapabilities consumerCompletionCapabilities() {
        return delegate.consumerCompletionCapabilities();
    }

    @Override
    public synchronized void release(ConsumerCompletion completion) {
        delegate.release(Objects.requireNonNull(completion, "completion"));
        if (delegate.state() != LeaseState.RELEASED) {
            throw new IllegalStateException("backend lease accepted release without publishing released state");
        }
        consumerAcceptanceObserver.accept(descriptor().frameSequence());
    }

    @Override
    public synchronized LeaseState state() {
        return delegate.state();
    }

    @Override
    public synchronized NativeFrame managedNativeFrame() {
        return managedDelegate().managedNativeFrame();
    }

    @Override
    public synchronized void releaseAfterManagedQueueSubmission() {
        managedDelegate().releaseAfterManagedQueueSubmission();
        if (delegate.state() != LeaseState.RELEASED) {
            throw new IllegalStateException("managed lease release did not publish released state");
        }
    }

    @Override
    public synchronized void releaseAfterPresenterAccessComplete() {
        managedDelegate().releaseAfterPresenterAccessComplete();
        if (delegate.state() != LeaseState.RELEASED) {
            throw new IllegalStateException("presenter completion did not publish released state");
        }
    }

    private VulkanManagedFrameLease managedDelegate() {
        if (delegate instanceof VulkanManagedFrameLease managed) return managed;
        throw new UnsupportedOperationException("lease has no managed native frame capability");
    }

    @Override
    public synchronized void close() {
        if (closeObserved) {
            return;
        }
        delegate.close();
        if (delegate.state() != LeaseState.CLOSED) {
            throw new IllegalStateException("backend lease close returned without publishing closed state");
        }
        closeObserver.run();
        // Publish the terminal wrapper state only after the host accepted the relinquishment.
        // If the observer fails, the already-closed delegate remains safe and a subsequent close
        // retries only the missing host notification instead of leaking an outstanding lease.
        closeObserved = true;
    }
}

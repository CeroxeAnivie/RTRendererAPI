package top.ceroxe.rt.renderer.api.interop.vulkan;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Opt-in Vulkan expert extension for zero-copy external-image consumption.
 *
 * <p>Ordinary applications should use the managed CPU-frame methods on
 * {@link top.ceroxe.rt.renderer.api.RayTracingRenderer}. Experts obtain this extension
 * through {@code renderer.extension(VulkanFrameInterop.class)}; doing so makes every Vulkan
 * handle, layout, queue-family and explicit synchronization obligation locally visible.</p>
 */
public interface VulkanFrameInterop {
    /**
     * Polls for the newest completed external GPU image without nullable control flow.
     *
     * <p>An available result exclusively owns its lease; the caller must release and close that
     * lease according to its advertised completion capabilities.</p>
     *
     * @return an owned available-frame result, or the shared not-ready result
     */
    FramePollResult pollLatestFrame();

    /**
     * Waits for a newly completed GPU frame for at most {@code timeout}.
     *
     * @param timeout non-negative maximum wait duration
     * @return an available frame, or {@link FrameNotReady#INSTANCE} after the timeout
     * @throws InterruptedException     if the waiting thread is interrupted
     * @throws IllegalArgumentException if the timeout is negative or cannot fit in nanoseconds
     */
    default FramePollResult awaitLatestFrame(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
        final long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("timeout is too large", overflow);
        }
        long started = System.nanoTime();
        while (true) {
            FramePollResult result = pollLatestFrame();
            if (result instanceof FrameAvailable || timeoutNanos == 0L) return result;
            long elapsed = System.nanoTime() - started;
            if (elapsed >= timeoutNanos) return FrameNotReady.INSTANCE;
            if (Thread.interrupted()) {
                throw new InterruptedException("interrupted while awaiting a completed Vulkan frame");
            }
            LockSupport.parkNanos(Math.min(timeoutNanos - elapsed, 250_000L));
        }
    }

    /**
     * Runs the bounded wait on a caller-owned executor.
     *
     * <p>Cancellation succeeds only while the task is still queued. Once polling begins it may
     * acquire an exclusive GPU-frame lease, so cancellation is rejected and the future remains
     * responsible for delivering that owned result to the caller.</p>
     *
     * @param timeout  non-negative maximum wait duration
     * @param executor caller-owned executor
     * @return future completed with an available or timed-out result
     */
    default CompletableFuture<FramePollResult> awaitLatestFrameAsync(
            Duration timeout,
            Executor executor
    ) {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(executor, "executor");
        final int queued = 0;
        final int running = 1;
        final int cancelled = 2;
        final int terminal = 3;
        AtomicInteger state = new AtomicInteger(queued);
        CompletableFuture<FramePollResult> result = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                if (!state.compareAndSet(queued, cancelled)) return false;
                return super.cancel(false);
            }
        };
        try {
            executor.execute(() -> {
                if (!state.compareAndSet(queued, running)) return;
                try {
                    result.complete(awaitLatestFrame(timeout));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    result.completeExceptionally(interrupted);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                } finally {
                    state.set(terminal);
                }
            });
        } catch (RuntimeException rejected) {
            if (state.compareAndSet(queued, terminal)) result.completeExceptionally(rejected);
        }
        return result;
    }

    /**
     * Shared result indicating that no newer completed GPU frame is currently available.
     */
    enum FrameNotReady implements FramePollResult {
        /**
         * Canonical allocation-free not-ready result.
         */
        INSTANCE
    }

    /**
     * Exhaustive, non-null result of one non-blocking GPU-frame poll.
     */
    sealed interface FramePollResult permits FrameAvailable, FrameNotReady {
    }

    /**
     * Transfers exclusive ownership of a newly completed frame lease.
     *
     * @param lease non-null open and unreleased lease
     */
    record FrameAvailable(GpuFrameLease lease) implements FramePollResult {
        /**
         * Validates an available-frame result.
         *
         * @param lease non-null open and unreleased lease
         */
        public FrameAvailable {
            lease = Objects.requireNonNull(lease, "lease");
            if (lease.state() != GpuFrameLease.LeaseState.ACTIVE) {
                throw new IllegalArgumentException("available frame lease must be active");
            }
        }
    }
}

package top.ceroxe.rt.renderer.api;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Owns completed-frame publication and bounded consumer waiting. */
public interface RendererFrameAccess {
    /** Consumes the newest completed native-format frame without an ownership copy. */
    boolean readFrame(FrameConsumer consumer);
    Optional<FrameSnapshot> pollFrame();

    Optional<FrameSnapshot> awaitFrame(Duration timeout) throws InterruptedException;

    default CompletableFuture<Optional<FrameSnapshot>> awaitFrameAsync(
            Duration timeout, Executor executor) {
        java.util.Objects.requireNonNull(timeout, "timeout");
        java.util.Objects.requireNonNull(executor, "executor");
        timeoutNanos(timeout);
        /* Delegate to the provider's synchronous wait so one implementation owns timeout and
           completion semantics. The task remains cancellable and executor rejection is reported
           through the returned future, matching the non-blocking contract. */
        class CancellableFrameFuture extends CompletableFuture<Optional<FrameSnapshot>> {
            private volatile Thread runner;

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                if (cancelled && mayInterruptIfRunning) {
                    Thread waiting = runner;
                    if (waiting != null) waiting.interrupt();
                }
                return cancelled;
            }
        }
        CancellableFrameFuture result = new CancellableFrameFuture();
        try {
            executor.execute(() -> {
                if (result.isDone()) return;
                result.runner = Thread.currentThread();
                try {
                    result.complete(awaitFrame(timeout));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    result.completeExceptionally(interrupted);
                } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                } finally {
                    result.runner = null;
                }
            });
        } catch (RuntimeException rejected) {
            result.completeExceptionally(rejected);
        }
        return result;
    }

    private static long timeoutNanos(Duration timeout) {
        java.util.Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
        try {
            return timeout.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("timeout is too large", overflow);
        }
    }

}

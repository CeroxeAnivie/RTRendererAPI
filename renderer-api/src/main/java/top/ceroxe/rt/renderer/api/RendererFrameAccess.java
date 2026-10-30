package top.ceroxe.rt.renderer.api;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

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
            private FutureTask<Optional<FrameSnapshot>> task;

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                if (isDone()) return isCancelled();
                return task.cancel(mayInterruptIfRunning);
            }

            void taskCancelled() { super.cancel(false); }
        }
        CancellableFrameFuture result = new CancellableFrameFuture();
        // FutureTask owns the runner/interrupt handshake, including cancellation before run and
        // delayed interrupts while a shared executor thread is finishing this task.
        FutureTask<Optional<FrameSnapshot>> task = new FutureTask<>(() -> {
            try { return awaitFrame(timeout); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        }) {
            @Override protected void done() {
                if (isCancelled()) {
                    result.taskCancelled();
                    return;
                }
                try { result.complete(get()); }
                catch (ExecutionException failure) { result.completeExceptionally(failure.getCause()); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    result.completeExceptionally(interrupted);
                }
            }
        };
        result.task = task;
        try {
            executor.execute(task);
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

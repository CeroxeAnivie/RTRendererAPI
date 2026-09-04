package top.ceroxe.rt.renderer.api;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns completed-frame publication and bounded consumer waiting. */
public interface RendererFrameAccess {
    Optional<CpuFrame> pollLatestCpuFrame();

    Optional<CpuFrame> awaitLatestCpuFrame(Duration timeout) throws InterruptedException;

    default CompletableFuture<Optional<CpuFrame>> awaitLatestCpuFrameAsync(
            Duration timeout, Executor executor) {
        java.util.Objects.requireNonNull(timeout, "timeout");
        java.util.Objects.requireNonNull(executor, "executor");
        long timeoutNanos = timeoutNanos(timeout);
        AtomicBoolean cancelled = new AtomicBoolean();
        CompletableFuture<Optional<CpuFrame>> result = new CompletableFuture<>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                cancelled.set(true);
                return super.cancel(false);
            }
        };
        try {
            executor.execute(() -> {
                if (result.isDone()) return;
                try {
                    result.complete(awaitLatestCpuFrame(timeout));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    result.completeExceptionally(failure);
                } catch (CancellationException ignored) {
                    // Cancellation already completed the future.
                } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
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

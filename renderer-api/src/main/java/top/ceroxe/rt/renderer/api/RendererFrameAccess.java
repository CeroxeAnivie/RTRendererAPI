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
        long timeoutNanos = timeoutNanos(timeout);
        CompletableFuture<Optional<FrameSnapshot>> result = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                if (result.isDone()) return;
                try {
                    long started = System.nanoTime();
                    long backoff = 250_000;
                    while (!result.isDone()) {
                        if (Thread.interrupted()) throw new InterruptedException("frame wait interrupted");
                        Optional<FrameSnapshot> frame = pollFrame();
                        if (frame.isPresent() || timeoutNanos == 0) { result.complete(frame); break; }
                        long remaining = timeoutNanos - (System.nanoTime() - started);
                        if (remaining <= 0) { result.complete(Optional.empty()); break; }
                        java.util.concurrent.locks.LockSupport.parkNanos(Math.min(remaining, backoff));
                        backoff = Math.min(4_000_000, backoff * 2);
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    result.completeExceptionally(failure);
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

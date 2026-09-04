package top.ceroxe.rt.renderer.api;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/** Lifecycle, health and diagnostics boundary for one renderer instance. */
public interface RendererLifecycle extends AutoCloseable {
    Renderer.Status status();

    RendererHealth health();

    RendererDiagnostics diagnostics();

    @Override
    void close();

    default CompletionStage<Void> closeAsync() {
        close();
        if (status() == Renderer.Status.CLOSED) return java.util.concurrent.CompletableFuture.completedFuture(null);
        return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException(
                "Provider with deferred teardown must override closeAsync()"));
    }

    default boolean awaitClosed(Duration timeout) throws InterruptedException {
        java.util.Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
        final long nanos;
        try { nanos = timeout.toNanos(); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException("timeout is too large", overflow); }
        try {
            java.util.Objects.requireNonNull(closeAsync(), "close completion stage")
                    .toCompletableFuture().get(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException timeoutFailure) { return false; }
        catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("renderer close failed", cause);
        }
    }
}

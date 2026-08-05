package top.ceroxe.rt.renderer.api;

/**
 * Complete host boundary for one independently owned renderer instance.
 *
 * <p>Scene and frame submission are single-writer operations. Observation may
 * occur from another thread, but {@link #close()} must be serialized with all
 * submissions. Implementations may process work asynchronously; accepted input
 * ownership never implies immediate GPU completion.</p>
 */
public interface RayTracingRenderer extends AutoCloseable {
    /**
     * Returns the current lifecycle state without waiting for GPU work.
     *
     * @return current renderer status
     */
    Status status();

    /**
     * Returns typed operational failure and resource-debt evidence without waiting for GPU work.
     *
     * @return immutable bounded health snapshot
     */
    RendererHealth health();

    /**
     * Atomically publishes one ordered set of persistent scene mutations.
     *
     * @param transaction immutable transaction whose revision must advance monotonically
     * @return evidence of the accepted scene revision
     */
    SceneUpdateResult apply(SceneTransaction transaction);

    /**
     * Schedules one frame from an accepted scene revision satisfying the request minimum.
     *
     * @param request immutable render request whose sequence must advance monotonically
     * @return evidence that the request entered the backend dispatch lane
     */
    FrameSubmissionResult submit(RenderFrameRequest request);

    /**
     * Attempts one frame admission without using an exception for ordinary bounded backpressure.
     *
     * <p>Ordering, lifecycle, revision and device failures still throw their existing typed
     * exceptions. Only a recoverable capacity refusal becomes {@link FrameSubmissionDeferred},
     * avoiding exception construction and stack capture in uncapped producer loops.</p>
     *
     * @param request immutable render request whose sequence must advance when accepted
     * @return exhaustive submitted-or-deferred result
     */
    default FrameSubmissionAttempt trySubmit(RenderFrameRequest request) {
        try {
            return new FrameSubmitted(submit(request));
        } catch (SubmissionRejectedException rejection) {
            return FrameSubmissionDeferred.because(rejection.deferralReason(), rejection.detail());
        }
    }

    /**
     * Copies the newest completed frame into an immutable CPU-readable value when one is available.
     *
     * <p>This is the default frame-consumption path for applications that do not need native GPU
     * interop. It exposes no Vulkan handles or synchronization protocol. The returned frame owns
     * its pixels and remains valid independently of renderer progress or shutdown. Implementations
     * enqueue readback into renderer-owned frame slots. This call only copies a slot whose producer
     * fence has completed; it never submits a readback command or waits for the GPU. Pixel copying
     * can still be proportional to frame size, so UI integrations should use a presentation thread
     * or {@link #awaitLatestCpuFrameAsync}.</p>
     *
     * @return newest frame not previously returned by this method, or empty when none is ready
     */
    java.util.Optional<CpuFrame> pollLatestCpuFrame();

    /**
     * Waits for a new CPU-readable frame for at most {@code timeout}.
     *
     * @param timeout non-negative maximum wait duration
     * @return a newly copied frame, or empty after the timeout
     * @throws InterruptedException     if the waiting thread is interrupted
     * @throws IllegalArgumentException if {@code timeout} is negative or cannot be represented in nanoseconds
     */
    default java.util.Optional<CpuFrame> awaitLatestCpuFrame(java.time.Duration timeout)
            throws InterruptedException {
        java.util.Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
        final long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("timeout is too large", overflow);
        }
        return awaitLatestCpuFrameUntil(timeoutNanos, () -> false);
    }

    /**
     * Polls until a frame, timeout, interruption, or caller-owned asynchronous cancellation.
     *
     * <p>The cancellation signal is checked before every poll and before every bounded park. It
     * never closes the renderer or releases a frame because a managed poll owns neither resource;
     * this makes cancelling one waiter independent of all other consumers.</p>
     *
     * @param timeoutNanos non-negative, representable timeout in nanoseconds
     * @param cancelled    non-null cancellation observation
     * @return newly copied frame, or empty after the timeout
     * @throws InterruptedException                       if the waiting thread is interrupted
     * @throws java.util.concurrent.CancellationException if the asynchronous caller cancelled
     */
    private java.util.Optional<CpuFrame> awaitLatestCpuFrameUntil(
            long timeoutNanos,
            java.util.function.BooleanSupplier cancelled
    ) throws InterruptedException {
        long started = System.nanoTime();
        long backoffNanos = 250_000L;
        while (true) {
            if (cancelled.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException("CPU frame wait was cancelled");
            }
            java.util.Optional<CpuFrame> frame = java.util.Objects.requireNonNull(
                    pollLatestCpuFrame(), "CPU frame poll result"
            );
            if (frame.isPresent() || timeoutNanos == 0L) return frame;
            long elapsed = System.nanoTime() - started;
            if (elapsed >= timeoutNanos) return java.util.Optional.empty();
            if (Thread.interrupted()) {
                throw new InterruptedException("interrupted while awaiting a CPU-readable renderer frame");
            }
            if (cancelled.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException("CPU frame wait was cancelled");
            }
            java.util.concurrent.locks.LockSupport.parkNanos(Math.min(timeoutNanos - elapsed, backoffNanos));
            backoffNanos = Math.min(4_000_000L, backoffNanos << 1);
        }
    }

    /**
     * Runs {@link #awaitLatestCpuFrame(java.time.Duration)} on a caller-owned executor.
     *
     * @param timeout  non-negative maximum wait duration
     * @param executor caller-owned executor used for polling and any required readback
     * @return cancellable future completed with a frame or an empty timeout result
     */
    default java.util.concurrent.CompletableFuture<java.util.Optional<CpuFrame>> awaitLatestCpuFrameAsync(
            java.time.Duration timeout,
            java.util.concurrent.Executor executor
    ) {
        java.util.Objects.requireNonNull(timeout, "timeout");
        java.util.Objects.requireNonNull(executor, "executor");
        final long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("timeout is too large", overflow);
        }
        if (timeoutNanos < 0L) throw new IllegalArgumentException("timeout must not be negative");
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.CompletableFuture<java.util.Optional<CpuFrame>> result =
                new java.util.concurrent.CompletableFuture<>() {
                    @Override
                    public boolean cancel(boolean mayInterruptIfRunning) {
                        cancelled.set(true);
                        return super.cancel(false);
                    }
                };
        try {
            executor.execute(() -> {
                if (result.isDone()) return;
                try {
                    result.complete(awaitLatestCpuFrameUntil(timeoutNanos, cancelled::get));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    result.completeExceptionally(interrupted);
                } catch (java.util.concurrent.CancellationException ignored) {
                    // cancel() already completed the future; managed polling owns no renderer resource.
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException rejected) {
            result.completeExceptionally(rejected);
        }
        return result;
    }

    /**
     * Discovers an optional, explicitly named expert extension without adding its native concepts
     * to the ordinary renderer lifecycle.
     *
     * <p>The default recognizes interfaces implemented directly by the renderer. Providers may
     * override this method for delegated extension objects. Stateful service extensions must keep
     * stable identity for the renderer lifetime; explicitly documented immutable snapshot value
     * types may return a newer value on each query. Neither form may fabricate support.</p>
     *
     * @param extensionType non-null extension interface
     * @param <T>           extension interface type
     * @return supported extension instance, or empty when unavailable
     */
    default <T> java.util.Optional<T> extension(Class<T> extensionType) {
        java.util.Objects.requireNonNull(extensionType, "extensionType");
        return extensionType.isInstance(this)
                ? java.util.Optional.of(extensionType.cast(this))
                : java.util.Optional.empty();
    }

    /**
     * Returns typed immutable diagnostics without waiting for GPU completion.
     *
     * @return a point-in-time diagnostics snapshot
     */
    RendererDiagnostics diagnostics();

    /**
     * Stops accepting work and releases native resources after every acquired GPU frame lease is
     * closed. Teardown may therefore be deferred when a consumer still owes GPU completion.
     */
    @Override
    void close();

    /**
     * Requests closure and completes only after all renderer-owned native resources are released.
     *
     * <p>The default preserves binary compatibility for providers whose {@link #close()} is fully
     * synchronous. Providers that permit deferred cleanup must override this method and complete
     * the returned stage only after outstanding external ownership has retired.</p>
     *
     * @return non-null close-completion stage
     */
    default java.util.concurrent.CompletionStage<Void> closeAsync() {
        try {
            close();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        } catch (Throwable failure) {
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * Requests closure and waits for native resource release for at most {@code timeout}.
     *
     * @param timeout non-negative maximum wait duration
     * @return {@code true} when cleanup completed, or {@code false} on timeout
     * @throws InterruptedException     if the waiting thread is interrupted
     * @throws IllegalArgumentException if the timeout is negative or cannot be represented
     */
    default boolean awaitClosed(java.time.Duration timeout) throws InterruptedException {
        java.util.Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
        final long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("timeout is too large", overflow);
        }
        try {
            java.util.Objects.requireNonNull(closeAsync(), "close completion stage")
                    .toCompletableFuture()
                    .get(timeoutNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException timedOut) {
            return false;
        } catch (java.util.concurrent.ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("renderer close failed", cause);
        }
    }

    /**
     * Lifecycle state of one renderer instance.
     */
    enum Status {
        /**
         * The renderer accepts scene and frame submissions.
         */
        READY,
        /**
         * Device recreation is waiting for outstanding external frame ownership to retire.
         */
        RECOVERING,
        /**
         * A terminal backend failure prevents further submissions.
         */
        FAILED,
        /**
         * The renderer was closed and owns no reusable public instance state.
         */
        CLOSED
    }

    /**
     * Logical scene publication result; GPU work coalescing is intentionally not observable.
     *
     * @param acceptedSceneRevision exact revision atomically accepted by the renderer
     */
    record SceneUpdateResult(long acceptedSceneRevision) {
        /**
         * Validates and creates a scene publication result.
         *
         * @param acceptedSceneRevision exact non-negative revision accepted by the renderer
         * @throws IllegalArgumentException if the revision is negative
         */
        public SceneUpdateResult {
            if (acceptedSceneRevision < 0L) {
                throw new IllegalArgumentException("acceptedSceneRevision must not be negative");
            }
        }
    }

    /**
     * Evidence that a frame entered the backend dispatch lane against one exact temporal state.
     */
    final class FrameSubmissionResult {
        private final long frameSequence;
        private final long scheduledSceneRevision;
        private final java.util.Set<HistoryInvalidationReason> historyInvalidations;

        private FrameSubmissionResult(
                long frameSequence,
                long scheduledSceneRevision,
                java.util.Set<HistoryInvalidationReason> historyInvalidations
        ) {
            if (frameSequence < 0L || scheduledSceneRevision < 0L) {
                throw new IllegalArgumentException("frame submission revisions must not be negative");
            }
            this.frameSequence = frameSequence;
            this.scheduledSceneRevision = scheduledSceneRevision;
            this.historyInvalidations = java.util.Set.copyOf(java.util.Objects.requireNonNull(
                    historyInvalidations, "historyInvalidations"
            ));
        }

        /**
         * Creates validated admission evidence for a renderer provider.
         *
         * @param frameSequence          exact non-negative admitted frame sequence
         * @param scheduledSceneRevision exact non-negative scene revision selected for rendering
         * @param historyInvalidations   immutable effective temporal invalidation reasons
         * @return validated immutable admission evidence
         */
        public static FrameSubmissionResult accepted(
                long frameSequence,
                long scheduledSceneRevision,
                java.util.Set<HistoryInvalidationReason> historyInvalidations
        ) {
            return new FrameSubmissionResult(
                    frameSequence, scheduledSceneRevision, historyInvalidations
            );
        }

        /**
         * Returns the exact admitted frame sequence.
         *
         * @return non-negative frame sequence
         */
        public long frameSequence() {
            return frameSequence;
        }

        /**
         * Returns the scene revision selected for the admitted frame.
         *
         * @return non-negative scheduled scene revision
         */
        public long scheduledSceneRevision() {
            return scheduledSceneRevision;
        }

        /**
         * Returns immutable effective reasons why this frame started a fresh temporal generation.
         *
         * @return immutable, possibly empty invalidation set
         */
        public java.util.Set<HistoryInvalidationReason> historyInvalidations() {
            return historyInvalidations;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof FrameSubmissionResult result)) return false;
            return frameSequence == result.frameSequence
                    && scheduledSceneRevision == result.scheduledSceneRevision
                    && historyInvalidations.equals(result.historyInvalidations);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(
                    frameSequence, scheduledSceneRevision, historyInvalidations
            );
        }

        @Override
        public String toString() {
            return "FrameSubmissionResult[frameSequence=" + frameSequence
                    + ", scheduledSceneRevision=" + scheduledSceneRevision
                    + ", historyInvalidations=" + historyInvalidations + ']';
        }
    }

    /** Exhaustive, non-null result of a non-throwing capacity-aware frame attempt. */
    sealed interface FrameSubmissionAttempt permits FrameSubmitted, FrameSubmissionDeferred {
    }

    /**
     * Successful frame admission.
     *
     * @param submission exact immutable backend admission evidence
     */
    record FrameSubmitted(FrameSubmissionResult submission) implements FrameSubmissionAttempt {
        /** Validates the successful attempt. */
        public FrameSubmitted {
            submission = java.util.Objects.requireNonNull(submission, "submission");
        }
    }

    /**
     * Recoverable capacity refusal that retained no logical or native submission state.
     *
     * @param reason non-blank diagnostic reason suitable for telemetry
     */
    record FrameSubmissionDeferred(String reason) implements FrameSubmissionAttempt {
        /** Validates the deferred attempt. */
        public FrameSubmissionDeferred {
            reason = java.util.Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
        }

        /**
         * Creates a refusal with a stable category and human-readable diagnostic detail.
         *
         * @param deferralReason stable capacity classification
         * @param detail         non-blank provider diagnostic detail
         * @return immutable deferred attempt preserving the original one-string binary shape
         */
        public static FrameSubmissionDeferred because(
                SubmissionDeferralReason deferralReason,
                String detail
        ) {
            return new FrameSubmissionDeferred(SubmissionDeferralReason.encode(deferralReason, detail));
        }

        /**
         * Returns a stable category without requiring callers to parse {@link #reason()}.
         *
         * @return typed category, or {@link SubmissionDeferralReason#UNSPECIFIED} for legacy values
         */
        public SubmissionDeferralReason deferralReason() {
            return SubmissionDeferralReason.decode(reason);
        }

        /**
         * Returns provider diagnostic detail without the internal stable-category marker.
         *
         * @return non-blank diagnostic detail
         */
        public String detail() {
            return SubmissionDeferralReason.detail(reason);
        }
    }

}

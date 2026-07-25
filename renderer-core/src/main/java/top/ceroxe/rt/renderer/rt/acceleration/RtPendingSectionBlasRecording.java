package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.RtEdgeSink;
import top.ceroxe.rt.renderer.scene.PackedSectionMembership;
import top.ceroxe.rt.renderer.scene.SectionKey;
import top.ceroxe.rt.renderer.scene.SectionTriangleMesh;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns one CPU-recording transaction before it becomes a submitted BLAS build.
 *
 * <p>The owner keeps the immutable mesh generation, its priority task, invalidation
 * state, and cancellation/close behavior together. It never calls back into the
 * cache; the cache consumes a completed {@link SubmittedBuild} and remains the sole
 * authority for GPU submission admission and scene publication.</p>
 */
final class RtPendingSectionBlasRecording implements AutoCloseable {
    /**
     * Native BLAS preparation ultimately converges on one command-pool/queue ownership domain.
     * Two producers are enough to overlap CPU-side allocation with serialized command recording;
     * additional producers only contend on that domain and starve foreground frame publication.
     */
    private static final int MAX_RECORDING_WORKERS = 2;
    private static final AtomicInteger NEXT_WORKER_ID = new AtomicInteger();
    private final RtSectionBlasBuildBatch batch;
    private final RtEdgeSink edges;
    private final boolean backgroundAdmission;
    private final long sequence;
    private final Set<SectionKey> invalidatedKeys = new HashSet<>();
    private boolean foregroundSubmission;
    private Future<SubmittedBuild> future;
    private long lastClassificationViewRevision = Long.MIN_VALUE;
    private long lastDecisionSignature = Long.MIN_VALUE;
    private boolean invalidatedAll;
    private boolean closed;

    RtPendingSectionBlasRecording(
            RtSectionBlasBuildBatch batch,
            boolean foregroundSubmission,
            long sequence,
            RtEdgeSink edges
    ) {
        this.batch = Objects.requireNonNull(batch, "batch");
        this.edges = Objects.requireNonNull(edges, "edges");
        this.backgroundAdmission = !foregroundSubmission;
        this.foregroundSubmission = foregroundSubmission;
        if (sequence < 0L) {
            throw new IllegalArgumentException("pending BLAS recording sequence must not be negative");
        }
        this.sequence = sequence;
    }

    static ThreadPoolExecutor createExecutor() {
        int workerCount = recordingWorkerCount(Runtime.getRuntime().availableProcessors());
        return new ThreadPoolExecutor(
                workerCount, workerCount, 0L, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "RTRenderer-SectionBlasSubmit-" + NEXT_WORKER_ID.getAndIncrement()
                    );
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                }
        );
    }

    static int recordingWorkerCount(int availableProcessors) {
        if (availableProcessors <= 0) {
            throw new IllegalArgumentException("availableProcessors must be positive");
        }
        return Math.max(1, Math.min(MAX_RECORDING_WORKERS, availableProcessors / 4));
    }

    static PrioritizedTask task(boolean foreground, long sequence, Callable<SubmittedBuild> callable) {
        return new PrioritizedTask(foreground, sequence, callable);
    }

    static int comparePriority(boolean leftForeground, long leftSequence, boolean rightForeground, long rightSequence) {
        if (leftSequence < 0L || rightSequence < 0L) {
            throw new IllegalArgumentException("build submission sequences must not be negative");
        }
        int priorityOrder = Boolean.compare(rightForeground, leftForeground);
        return priorityOrder != 0 ? priorityOrder : Long.compare(leftSequence, rightSequence);
    }

    private static long keyFingerprint(Set<SectionKey> keys) {
        long fingerprint = 0L;
        for (SectionKey key : keys) {
            fingerprint ^= fingerprint(key);
        }
        return fingerprint;
    }

    private static long fingerprint(SectionKey key) {
        long value = Integer.toUnsignedLong(key.x()) * 0x9E3779B185EBCA87L;
        value ^= Integer.toUnsignedLong(key.y()) * 0xC2B2AE3D27D4EB4FL;
        value ^= Integer.toUnsignedLong(key.z()) * 0x165667B19E3779F9L;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    RtSectionBlasBuildBatch batch() {
        return batch;
    }

    void start(Future<SubmittedBuild> future) {
        requireOpen();
        if (this.future != null) {
            throw new IllegalStateException("pending async BLAS recording is already started");
        }
        this.future = Objects.requireNonNull(future, "future");
    }

    List<SectionTriangleMesh> meshes() {
        return batch.meshes();
    }

    long sequence() {
        return sequence;
    }

    boolean containsSection(SectionKey key) {
        Objects.requireNonNull(key, "key");
        for (SectionTriangleMesh mesh : meshes()) {
            if (mesh.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    boolean foregroundSubmission() {
        return foregroundSubmission;
    }

    boolean backgroundAdmission() {
        return backgroundAdmission;
    }

    void recordCreated(long viewRevision, Set<SectionKey> preferredSectionKeys) {
        if (!edges.verboseIoEnabled()) {
            return;
        }
        top.ceroxe.rt.renderer.RendererLog.info(
                "rt section BLAS recording created: T={}ms, sequence={}, sections={}, activeSections={}, "
                        + "sectionKeyFingerprint=0x{}, activeKeys={}, foregroundAtAdmission={}, viewRevision={}, "
                        + "frozenKeys={}, frozenKeyFingerprint=0x{}, matchedFrozenSections={}",
                edges.elapsedMillis(), sequence, meshes().size(), activeSectionCount(),
                Long.toUnsignedString(activeKeyFingerprint(), 16), activeDiagnosticKeys(), foregroundSubmission,
                viewRevision, preferredSectionKeys.size(),
                Long.toUnsignedString(keyFingerprint(preferredSectionKeys), 16),
                activePreferredSectionCount(preferredSectionKeys)
        );
    }

    void recordCpuCompleted(long elapsedNanos, Set<SectionKey> preferredSectionKeys) {
        if (!edges.verboseIoEnabled()) {
            return;
        }
        int foregroundSections = activePreferredSectionCount(preferredSectionKeys);
        if (!foregroundSubmission && foregroundSections == 0) {
            return;
        }
        top.ceroxe.rt.renderer.RendererLog.info(
                "rt section BLAS lifecycle: T={}ms, sequence={}, edge=cpuRecorded, activeSections={}, "
                        + "foregroundSections={}, elapsedMs={}",
                edges.elapsedMillis(), sequence, activeSectionCount(), foregroundSections,
                elapsedNanos / 1_000_000L
        );
    }

    void recordViewClassification(long viewRevision, Set<SectionKey> preferredSectionKeys, int matchingSections) {
        if (!edges.verboseIoEnabled() || lastClassificationViewRevision == viewRevision) {
            return;
        }
        lastClassificationViewRevision = viewRevision;
        top.ceroxe.rt.renderer.RendererLog.info(
                "rt section BLAS recording classified: sequence={}, viewRevision={}, sections={}, activeSections={}, "
                        + "invalidatedSections={}, sectionKeyFingerprint=0x{}, frozenKeys={}, "
                        + "frozenKeyFingerprint=0x{}, matchedFrozenSections={}, foregroundAtAdmission={}, foregroundNow={}",
                sequence, viewRevision, meshes().size(), activeSectionCount(),
                meshes().size() - activeSectionCount(),
                Long.toUnsignedString(activeKeyFingerprint(), 16), preferredSectionKeys.size(),
                Long.toUnsignedString(keyFingerprint(preferredSectionKeys), 16), matchingSections,
                !backgroundAdmission, foregroundSubmission || matchingSections > 0
        );
    }

    void recordSubmissionDecision(
            long viewRevision,
            Set<SectionKey> preferredSectionKeys,
            boolean authoritativeViewEstablished,
            boolean foregroundCoverageIncomplete,
            boolean firstWorldFrontPending,
            int matchingSections,
            boolean submit
    ) {
        if (!edges.verboseIoEnabled()) {
            return;
        }
        long signature = viewRevision << 4;
        signature ^= authoritativeViewEstablished ? 1L : 0L;
        signature ^= foregroundCoverageIncomplete ? 2L : 0L;
        signature ^= firstWorldFrontPending ? 4L : 0L;
        signature ^= submit ? 8L : 0L;
        if (!submit && lastDecisionSignature == signature) {
            return;
        }
        lastDecisionSignature = signature;
        top.ceroxe.rt.renderer.RendererLog.info(
                "rt section BLAS recording decision: sequence={}, decision={}, viewRevision={}, authoritativeView={}, "
                        + "foregroundCoverageIncomplete={}, firstWorldFrontPending={}, sections={}, activeSections={}, "
                        + "invalidatedSections={}, sectionKeyFingerprint=0x{}, frozenKeys={}, frozenKeyFingerprint=0x{}, "
                        + "matchedFrozenSections={}, foregroundAtAdmission={}, foregroundNow={}",
                sequence, submit ? "submit" : "defer", viewRevision, authoritativeViewEstablished,
                foregroundCoverageIncomplete, firstWorldFrontPending, meshes().size(), activeSectionCount(),
                meshes().size() - activeSectionCount(), Long.toUnsignedString(activeKeyFingerprint(), 16),
                preferredSectionKeys.size(), Long.toUnsignedString(keyFingerprint(preferredSectionKeys), 16),
                matchingSections, !backgroundAdmission, foregroundSubmission
        );
    }

    void boostPriority(ThreadPoolExecutor executor) {
        Objects.requireNonNull(executor, "executor");
        requireOpen();
        if (foregroundSubmission) {
            return;
        }
        foregroundSubmission = true;
        if (future instanceof PrioritizedTask task && executor.getQueue().remove(task)) {
            task.boostPriority();
            executor.execute(task);
        } else if (future instanceof PrioritizedTask task) {
            task.boostPriority();
        }
    }

    boolean cancelIfQueued(ThreadPoolExecutor executor) {
        Objects.requireNonNull(executor, "executor");
        requireOpen();
        if (!(future instanceof PrioritizedTask task) || !executor.getQueue().remove(task)) {
            return false;
        }
        if (!task.cancel(false)) {
            throw new IllegalStateException("removed BLAS recording task could not be cancelled");
        }
        return true;
    }

    boolean isDone() {
        return future != null && future.isDone();
    }

    SubmittedBuild complete() {
        requireOpen();
        if (future == null) {
            throw new IllegalStateException("pending async BLAS recording was not started");
        }
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while completing async BLAS recording", ex);
        } catch (CancellationException ex) {
            throw new IllegalStateException("async BLAS recording was cancelled", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("async BLAS recording failed", cause);
        }
    }

    boolean invalidate(SectionKey key) {
        requireOpen();
        Objects.requireNonNull(key, "key");
        return !invalidatedAll && containsSection(key) && invalidatedKeys.add(key);
    }

    boolean invalidateAll() {
        requireOpen();
        if (activeSectionCount() == 0) {
            return false;
        }
        invalidatedAll = true;
        invalidatedKeys.clear();
        return true;
    }

    void applyInvalidations(RtPendingSectionBlasBuild pending) {
        Objects.requireNonNull(pending, "pending");
        if (invalidatedAll) {
            pending.invalidateAll();
            return;
        }
        for (SectionKey key : invalidatedKeys) {
            pending.invalidate(key);
        }
    }

    int activeSectionCount() {
        if (invalidatedAll) {
            return 0;
        }
        int active = 0;
        for (SectionTriangleMesh mesh : meshes()) {
            if (!invalidatedKeys.contains(mesh.key())) {
                active++;
            }
        }
        return active;
    }

    int activePreferredSectionCount(Set<SectionKey> preferredSectionKeys) {
        Objects.requireNonNull(preferredSectionKeys, "preferredSectionKeys");
        if (invalidatedAll) {
            return 0;
        }
        int active = 0;
        for (SectionTriangleMesh mesh : meshes()) {
            if (!invalidatedKeys.contains(mesh.key()) && preferredSectionKeys.contains(mesh.key())) {
                active++;
            }
        }
        return active;
    }

    void addActiveKeysTo(Set<SectionKey> target) {
        Objects.requireNonNull(target, "target");
        if (invalidatedAll) {
            return;
        }
        for (SectionTriangleMesh mesh : meshes()) {
            if (!invalidatedKeys.contains(mesh.key())) {
                target.add(mesh.key());
            }
        }
    }

    void addActiveKeysTo(PackedSectionMembership.Builder target) {
        Objects.requireNonNull(target, "target");
        if (invalidatedAll) {
            return;
        }
        for (SectionTriangleMesh mesh : meshes()) {
            SectionKey key = mesh.key();
            if (!invalidatedKeys.contains(key)) {
                target.addPacked(key.packed());
            }
        }
    }

    long activeTriangleCount() {
        if (invalidatedAll) {
            return 0L;
        }
        long triangles = 0L;
        for (SectionTriangleMesh mesh : meshes()) {
            if (!invalidatedKeys.contains(mesh.key())) {
                triangles = Math.addExact(triangles, mesh.triangleCount());
            }
        }
        return triangles;
    }

    long activeEstimatedBytes() {
        if (invalidatedAll) {
            return 0L;
        }
        long bytes = 0L;
        for (SectionTriangleMesh mesh : meshes()) {
            if (!invalidatedKeys.contains(mesh.key())) {
                bytes = Math.addExact(bytes, mesh.estimatedBytes());
            }
        }
        return bytes;
    }

    int retainedSectionCount() {
        return meshes().size();
    }

    long retainedEstimatedBytes() {
        return batch.retainedEstimatedBytes();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (future == null) {
            return;
        }
        if (future instanceof PrioritizedTask task && task.cancelBeforeRun()) {
            return;
        }
        SubmittedBuild submitted = completeAfterClose();
        submitted.recorded().close();
    }

    private List<SectionKey> activeDiagnosticKeys() {
        List<SectionKey> keys = new ArrayList<>();
        if (invalidatedAll) {
            return List.of();
        }
        for (SectionTriangleMesh mesh : meshes()) {
            if (!invalidatedKeys.contains(mesh.key())) {
                keys.add(mesh.key());
            }
            if (keys.size() >= 32) {
                break;
            }
        }
        return List.copyOf(keys);
    }

    private long activeKeyFingerprint() {
        long fingerprint = 0L;
        if (!invalidatedAll) {
            for (SectionTriangleMesh mesh : meshes()) {
                if (!invalidatedKeys.contains(mesh.key())) {
                    fingerprint ^= fingerprint(mesh.key());
                }
            }
        }
        return fingerprint;
    }

    private SubmittedBuild completeAfterClose() {
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while closing async BLAS recording", ex);
        } catch (CancellationException ex) {
            throw new IllegalStateException("async BLAS recording was cancelled during close", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("async BLAS recording failed during close", cause);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("pending section BLAS recording is closed");
        }
    }

    record SubmittedBuild(RtAccelerationStructure.RecordedSectionBlasBuild recorded, long submitElapsedNanos) {
        SubmittedBuild {
            recorded = Objects.requireNonNull(recorded, "recorded");
            if (submitElapsedNanos < 0L) {
                throw new IllegalArgumentException("async BLAS submit elapsed time must not be negative");
            }
        }
    }

    static final class PrioritizedTask extends FutureTask<SubmittedBuild> implements Comparable<PrioritizedTask> {
        private final long sequence;
        private volatile boolean foreground;
        private boolean started;

        private PrioritizedTask(boolean foreground, long sequence, Callable<SubmittedBuild> callable) {
            super(Objects.requireNonNull(callable, "callable"));
            if (sequence < 0L) {
                throw new IllegalArgumentException("build submission sequence must not be negative");
            }
            this.foreground = foreground;
            this.sequence = sequence;
        }

        @Override
        public int compareTo(PrioritizedTask other) {
            Objects.requireNonNull(other, "other");
            return comparePriority(foreground, sequence, other.foreground, other.sequence);
        }

        private void boostPriority() {
            foreground = true;
        }

        @Override
        public void run() {
            synchronized (this) {
                if (isCancelled()) {
                    return;
                }
                started = true;
            }
            super.run();
        }

        /**
         * Cancels only work that cannot yet own native resources.
         *
         * <p>{@link FutureTask#cancel(boolean)} with {@code false} may still mark a running task
         * cancelled while its callable continues. Its Vulkan recording would then be discarded
         * instead of closed. Serializing this transition with {@link #run()} leaves running work
         * observable so the recording owner can await and explicitly release its result.</p>
         */
        private synchronized boolean cancelBeforeRun() {
            return !started && cancel(false);
        }
    }
}

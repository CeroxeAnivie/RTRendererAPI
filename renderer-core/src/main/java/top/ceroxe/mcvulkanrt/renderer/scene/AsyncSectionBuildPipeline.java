package top.ceroxe.mcvulkanrt.renderer.scene;

import top.ceroxe.mcvulkanrt.renderer.diagnostics.RtFirstFrontCausalityRecorder;
import top.ceroxe.mcvulkanrt.renderer.diagnostics.RtSceneCausalityRecorder;
import top.ceroxe.mcvulkanrt.renderer.diagnostics.RtTakeoverTimeline;
import top.ceroxe.mcvulkanrt.renderer.SectionLifecycleFlightRecorder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Builds section CPU payloads away from the render frame.
 *
 * <p>The renderer thread owns cache mutation and Vulkan submission order. This
 * worker only transforms immutable {@link SectionVoxelSnapshot} instances into
 * immutable encoded/geometry/mesh payloads, then hands the result back for the
 * renderer thread to validate against the latest section generation. Keeping
 * that ownership split prevents old chunk work from resurrecting after unloads
 * or newer packets.</p>
 */
public final class AsyncSectionBuildPipeline implements AutoCloseable {
    private static final int CLOSE_JOIN_MILLIS = 100;
    private static final int DEFAULT_WORKER_PRIORITY = Thread.NORM_PRIORITY;
    private static final String WORKER_PRIORITY_PROPERTY = "mcvulkanrt.renderer.sectionBuildThreadPriority";

    private final LinkedBlockingDeque<SectionKey> queuedTasks = new LinkedBlockingDeque<>();
    private final Set<SectionKey> preferredKeys = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<SectionKey, Task> latestTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SectionKey, Long> latestRequestedGenerations = new ConcurrentHashMap<>();
    private final Set<SectionKey> scheduledKeys = ConcurrentHashMap.newKeySet();
    private final Object completedBuildLock = new Object();
    private final LinkedHashMap<SectionKey, CompletedSectionBuild> completedBuilds = new LinkedHashMap<>();
    private final List<Thread> workers;
    private final int workerPriority;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger activeWorkerTasks = new AtomicInteger();
    private final AtomicLong submittedSections = new AtomicLong();
    private final AtomicLong supersededSections = new AtomicLong();
    private final AtomicLong cancelledSections = new AtomicLong();
    private final AtomicLong startedSections = new AtomicLong();
    private final AtomicLong completedSections = new AtomicLong();
    private final AtomicLong fastEmptySections = new AtomicLong();
    private final AtomicLong discardedSections = new AtomicLong();
    private final AtomicLong failedSections = new AtomicLong();
    private final AtomicReference<String> lastFailure = new AtomicReference<>("none");
    private final SectionBuildFlightRecorder flightRecorder = new SectionBuildFlightRecorder();

    public AsyncSectionBuildPipeline(int workerCount) {
        this(workerCount, intProperty(WORKER_PRIORITY_PROPERTY, DEFAULT_WORKER_PRIORITY));
    }

    public AsyncSectionBuildPipeline(int workerCount, int workerPriority) {
        if (workerCount <= 0) {
            throw new IllegalArgumentException("workerCount must be positive");
        }
        if (workerPriority < Thread.MIN_PRIORITY || workerPriority > Thread.MAX_PRIORITY) {
            throw new IllegalArgumentException(
                    "workerPriority must be in range "
                            + Thread.MIN_PRIORITY
                            + ".."
                            + Thread.MAX_PRIORITY
            );
        }
        this.workerPriority = workerPriority;
        this.workers = new ArrayList<>(workerCount);
        for (int index = 0; index < workerCount; index++) {
            Thread worker = new Thread(this::runWorker, "MCVulkanRT-SectionBuild-" + (index + 1));
            worker.setDaemon(true);
            worker.setPriority(workerPriority);
            workers.add(worker);
            worker.start();
        }
    }

    public void submit(SectionVoxelSnapshot snapshot, long generation) {
        Objects.requireNonNull(snapshot, "snapshot");
        submit(snapshot, generation, SectionNeighborhood.empty(snapshot.key()));
    }

    public void submit(
            SectionVoxelSnapshot snapshot,
            long generation,
            Map<FaceDirection, SectionVoxelSnapshot> neighborSections
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(neighborSections, "neighborSections");
        submit(snapshot, generation, SectionNeighborhood.fromFaceNeighbors(snapshot.key(), neighborSections));
    }

    public void submit(
            SectionVoxelSnapshot snapshot,
            long generation,
            SectionNeighborhood neighborhood
    ) {
        submit(snapshot, generation, neighborhood, false);
    }

    public void submit(
            SectionVoxelSnapshot snapshot,
            long generation,
            SectionNeighborhood neighborhood,
            boolean preferred
    ) {
        submit(snapshot, generation, neighborhood, preferred, -1L);
    }

    /**
     * Enqueues an immutable section task together with its renderer-owned content revision.
     *
     * <p>The revision is not used by scheduling. It only preserves causality across the
     * render-thread ticket, worker queue, CPU meshing and render-thread commit, so the
     * smoke recorder cannot accidentally join a superseded generation to a newer result.</p>
     */
    public void submit(
            SectionVoxelSnapshot snapshot,
            long generation,
            SectionNeighborhood neighborhood,
            boolean preferred,
            long contentRevision
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(neighborhood, "neighborhood");
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
        if (closed.get()) {
            throw new IllegalStateException("section build pipeline is closed");
        }

        Task task = new Task(snapshot, generation, neighborhood, System.nanoTime(), contentRevision);
        latestRequestedGenerations.put(snapshot.key(), generation);
        discardCompleted(snapshot.key());
        Task previous = latestTasks.put(snapshot.key(), task);
        SectionLifecycleFlightRecorder.record(
                previous == null
                        ? SectionLifecycleFlightRecorder.STAGE_CPU_QUEUE_SUBMIT
                        : SectionLifecycleFlightRecorder.STAGE_CPU_QUEUE_SUPERSEDE,
                SectionLifecycleFlightRecorder.SOURCE_UNKNOWN,
                previous == null
                        ? SectionLifecycleFlightRecorder.OUTCOME_ACCEPTED
                        : SectionLifecycleFlightRecorder.OUTCOME_REPLACED,
                snapshot,
                generation,
                contentRevision,
                previous == null ? -1L : previous.generation(),
                0,
                latestTasks.size(),
                0L
        );
        flightRecorder.record(
                previous == null ? "queueSubmit" : "queueSupersede",
                snapshot.key(),
                generation,
                previous == null ? -1L : previous.generation(),
                0,
                0L,
                preferred,
                false
        );
        submittedSections.incrementAndGet();
        if (previous != null) {
            supersededSections.incrementAndGet();
        }
        if (preferred) {
            preferredKeys.add(snapshot.key());
        }
        if (scheduledKeys.add(snapshot.key())) {
            if (preferred) {
                queuedTasks.offerFirst(snapshot.key());
            } else {
                queuedTasks.offerLast(snapshot.key());
            }
        } else if (preferred && queuedTasks.remove(snapshot.key())) {
            queuedTasks.offerFirst(snapshot.key());
        }
        recordCausality("cpuQueueSubmitted", task, "preferred=" + preferred);
        RtSceneCausalityRecorder.recordSection(
                RtSceneCausalityRecorder.CPU_QUEUE,
                snapshot.key(),
                contentRevision,
                generation,
                preferred ? 1L : 0L,
                0
        );
    }

    public void prioritize(Set<SectionKey> sectionKeys) {
        Objects.requireNonNull(sectionKeys, "sectionKeys");
        for (SectionKey key : sectionKeys) {
            if (!latestTasks.containsKey(key)) {
                continue;
            }
            preferredKeys.add(key);
            if (queuedTasks.remove(key)) {
                queuedTasks.offerFirst(key);
            }
        }
    }

    public void recordTicket(
            SectionKey key,
            long generation,
            long contentRevision,
            int sourceFlags,
            boolean preferred
    ) {
        flightRecorder.record(
                "buildTicket",
                key,
                generation,
                contentRevision,
                sourceFlags,
                0L,
                preferred,
                false
        );
    }

    public void recordDrained(SectionKey key, long generation, long contentRevision, int sourceFlags, boolean stale) {
        flightRecorder.record(
                stale ? "renderDrainStale" : "renderDrainCommit",
                key,
                generation,
                contentRevision,
                sourceFlags,
                0L,
                false,
                stale
        );
    }

    public void dumpFirstFrontFlightRecorder(Set<SectionKey> foregroundKeys) {
        flightRecorder.dumpOnce(foregroundKeys);
    }

    public void cancel(SectionKey key) {
        Objects.requireNonNull(key, "key");
        if (latestTasks.remove(key) != null) {
            cancelledSections.incrementAndGet();
        }
        latestRequestedGenerations.remove(key);
        preferredKeys.remove(key);
        discardCompleted(key);
    }

    public void cancelAll() {
        int pending = latestTasks.size();
        if (pending > 0) {
            cancelledSections.addAndGet(pending);
        }
        latestTasks.clear();
        latestRequestedGenerations.clear();
        scheduledKeys.clear();
        preferredKeys.clear();
        queuedTasks.clear();
        clearCompleted();
        flightRecorder.reset();
    }

    public List<CompletedSectionBuild> drainCompleted(int maxResults) {
        return drainCompleted(maxResults, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    }

    public List<CompletedSectionBuild> drainCompleted(
            int maxResults,
            long maxMeshBytes,
            long maxTriangles,
            long deadlineNanos
    ) {
        if (maxResults <= 0) {
            throw new IllegalArgumentException("maxResults must be positive");
        }
        if (maxMeshBytes <= 0L) {
            throw new IllegalArgumentException("maxMeshBytes must be positive");
        }
        if (maxTriangles <= 0L) {
            throw new IllegalArgumentException("maxTriangles must be positive");
        }
        List<CompletedSectionBuild> results;
        synchronized (completedBuildLock) {
            results = new ArrayList<>(Math.min(maxResults, completedBuilds.size()));
            long drainedMeshBytes = 0L;
            long drainedTriangles = 0L;
            var iterator = completedBuilds.entrySet().iterator();
            while (iterator.hasNext() && results.size() < maxResults) {
                CompletedSectionBuild result = iterator.next().getValue();
                long resultMeshBytes = result.mesh().estimatedBytes();
                long resultTriangles = result.mesh().triangleCount();
                if (!results.isEmpty()
                        && (drainedMeshBytes + resultMeshBytes > maxMeshBytes
                        || drainedTriangles + resultTriangles > maxTriangles
                        || System.nanoTime() >= deadlineNanos)) {
                    break;
                }
                iterator.remove();
                results.add(result);
                drainedMeshBytes += resultMeshBytes;
                drainedTriangles += resultTriangles;
            }
        }
        for (CompletedSectionBuild result : results) {
            latestRequestedGenerations.remove(result.key(), result.generation());
        }
        return results;
    }

    public boolean hasPendingWork() {
        return !latestTasks.isEmpty() || !queuedTasks.isEmpty() || completedSectionBuildsWaiting() > 0;
    }

    public int pendingSectionBuilds() {
        return latestTasks.size();
    }

    public int completedSectionBuildsWaiting() {
        synchronized (completedBuildLock) {
            return completedBuilds.size();
        }
    }

    public boolean acceptsMoreWork(int maxInFlightTasks, int maxCompletedResultsWaiting) {
        return availableSubmissionSlots(maxInFlightTasks, maxCompletedResultsWaiting) > 0;
    }

    public int availableSubmissionSlots(int maxInFlightTasks, int maxCompletedResultsWaiting) {
        return availableSubmissionSlotsAfterDrainingCompleted(maxInFlightTasks, maxCompletedResultsWaiting, 0);
    }

    public int availableSubmissionSlotsAfterDrainingCompleted(
            int maxInFlightTasks,
            int maxCompletedResultsWaiting,
            int completedResultsToDrain
    ) {
        if (maxInFlightTasks <= 0) {
            throw new IllegalArgumentException("maxInFlightTasks must be positive");
        }
        if (maxCompletedResultsWaiting <= 0) {
            throw new IllegalArgumentException("maxCompletedResultsWaiting must be positive");
        }
        if (completedResultsToDrain < 0) {
            throw new IllegalArgumentException("completedResultsToDrain must not be negative");
        }
        int completedWaitingAfterDrain = Math.max(0, completedSectionBuildsWaiting() - completedResultsToDrain);
        if (completedWaitingAfterDrain >= maxCompletedResultsWaiting) {
            return 0;
        }
        return Math.max(0, maxInFlightTasks - inFlightTaskCount());
    }

    public String summary(String name) {
        return name
                + "{workers=" + workers.size()
                + ", workerPriority=" + workerPriority
                + ", queuedTasks=" + queuedTasks.size()
                + ", preferredTasks=" + preferredKeys.size()
                + ", latestTasks=" + latestTasks.size()
                + ", scheduledKeys=" + scheduledKeys.size()
                + ", activeWorkerTasks=" + activeWorkerTasks.get()
                + ", inFlightTasks=" + inFlightTaskCount()
                + ", completedWaiting=" + completedSectionBuildsWaiting()
                + ", submittedSections=" + submittedSections.get()
                + ", supersededSections=" + supersededSections.get()
                + ", cancelledSections=" + cancelledSections.get()
                + ", startedSections=" + startedSections.get()
                + ", completedSections=" + completedSections.get()
                + ", fastEmptySections=" + fastEmptySections.get()
                + ", discardedSections=" + discardedSections.get()
                + ", failedSections=" + failedSections.get()
                + ", lastFailure=" + lastFailure.get()
                + "}";
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        latestTasks.clear();
        latestRequestedGenerations.clear();
        scheduledKeys.clear();
        preferredKeys.clear();
        queuedTasks.clear();
        clearCompleted();
        for (Thread worker : workers) {
            worker.interrupt();
        }
        for (Thread worker : workers) {
            try {
                worker.join(CLOSE_JOIN_MILLIS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void runWorker() {
        SectionMesher mesher = new SectionMesher();
        SectionMeshBuilder meshBuilder = new SectionMeshBuilder();
        while (!closed.get()) {
            SectionKey key;
            try {
                key = queuedTasks.take();
            } catch (InterruptedException ex) {
                if (closed.get()) {
                    return;
                }
                Thread.currentThread().interrupt();
                return;
            }

            Task task = latestTasks.get(key);
            if (task == null) {
                discardedSections.incrementAndGet();
                rescheduleIfLatestTaskRemains(key);
                continue;
            }

            startedSections.incrementAndGet();
            boolean preferred = preferredKeys.remove(key);
            activeWorkerTasks.incrementAndGet();
            long buildStartNanos = System.nanoTime();
            SectionLifecycleFlightRecorder.record(
                    SectionLifecycleFlightRecorder.STAGE_CPU_WORK_START,
                    SectionLifecycleFlightRecorder.SOURCE_UNKNOWN,
                    SectionLifecycleFlightRecorder.OUTCOME_ACCEPTED,
                    task.snapshot(), task.generation(), task.contentRevision(), -1L,
                    0, queuedTasks.size(), buildStartNanos - task.submittedNanos()
            );
            recordCausality(
                    "cpuWorkerStarted",
                    task,
                    "queueWaitMs=" + Math.max(0L, buildStartNanos - task.submittedNanos()) / 1_000_000L
            );
            RtSceneCausalityRecorder.recordSection(
                    RtSceneCausalityRecorder.CPU_BUILD_BEGIN,
                    key,
                    task.contentRevision(),
                    task.generation(),
                    buildStartNanos - task.submittedNanos(),
                    preferred ? 1 : 0
            );
            flightRecorder.record(
                    "workerStart",
                    key,
                    task.generation(),
                    0L,
                    0,
                    buildStartNanos - task.submittedNanos(),
                    preferred,
                    false
            );
            try {
                CompletedSectionPayload payload = buildPayload(
                        task.snapshot(),
                        task.neighborhood(),
                        mesher,
                        meshBuilder,
                        task.contentRevision()
                );
                recordCausality(
                        "cpuWorkerCompleted",
                        task,
                        "meshMs=" + Math.max(0L, System.nanoTime() - buildStartNanos) / 1_000_000L
                                + ", triangles=" + payload.mesh().triangleCount()
                );
                RtSceneCausalityRecorder.recordSection(
                        RtSceneCausalityRecorder.CPU_BUILD_COMPLETE,
                        key,
                        task.contentRevision(),
                        task.generation(),
                        payload.mesh().triangleCount(),
                        0
                );
                if (latestTasks.remove(task.snapshot().key(), task)) {
                    SectionLifecycleFlightRecorder.record(
                            SectionLifecycleFlightRecorder.STAGE_CPU_WORK_COMPLETE,
                            SectionLifecycleFlightRecorder.SOURCE_UNKNOWN,
                            SectionLifecycleFlightRecorder.OUTCOME_ACCEPTED,
                            task.snapshot(), task.generation(), task.contentRevision(), -1L,
                            0, completedSectionBuildsWaiting(), payload.mesh().triangleCount()
                    );
                    flightRecorder.record(
                            "workerComplete",
                            key,
                            task.generation(),
                            payload.mesh().triangleCount(),
                            0,
                            System.nanoTime() - buildStartNanos,
                            false,
                            false
                    );
                    publishCompleted(task, new CompletedSectionBuild(
                            task.snapshot(),
                            task.generation(),
                            payload.materialFacts(),
                            payload.faceCount(),
                            payload.mesh()
                    ));
                    completedSections.incrementAndGet();
                } else {
                    SectionLifecycleFlightRecorder.record(
                            SectionLifecycleFlightRecorder.STAGE_CPU_WORK_SUPERSEDED,
                            SectionLifecycleFlightRecorder.SOURCE_UNKNOWN,
                            SectionLifecycleFlightRecorder.OUTCOME_STALE,
                            task.snapshot(), task.generation(), task.contentRevision(),
                            latestRequestedGenerations.getOrDefault(key, -1L),
                            0, queuedTasks.size(), payload.mesh().triangleCount()
                    );
                    flightRecorder.record(
                            "workerSuperseded",
                            key,
                            task.generation(),
                            0L,
                            0,
                            System.nanoTime() - buildStartNanos,
                            false,
                            true
                    );
                    RtSceneCausalityRecorder.recordSection(
                            RtSceneCausalityRecorder.CPU_SUPERSEDED,
                            key,
                            task.contentRevision(),
                            task.generation(),
                            payload.mesh().triangleCount(),
                            0
                    );
                    discardedSections.incrementAndGet();
                }
            } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
                RtSceneCausalityRecorder.recordSection(
                        RtSceneCausalityRecorder.CPU_FAILED,
                        key,
                        task.contentRevision(),
                        task.generation(),
                        0L,
                        RtSceneCausalityRecorder.failureCode(ex)
                );
                failedSections.incrementAndGet();
                lastFailure.set(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                latestTasks.remove(key, task);
                top.ceroxe.mcvulkanrt.renderer.RendererLog.warn(
                        "section build worker discarded failed section {}, generation={}",
                        task.snapshot().key(),
                        task.generation(),
                        ex
                );
            } finally {
                activeWorkerTasks.decrementAndGet();
                rescheduleIfLatestTaskRemains(key);
            }
        }
    }

    private static void recordCausality(String stage, Task task, String details) {
        if (task.contentRevision() < 0L) {
            return;
        }
        RtFirstFrontCausalityRecorder.recordSection(
                stage,
                task.snapshot().key(),
                task.contentRevision(),
                "generation=" + task.generation() + ", " + details
        );
    }

    private void publishCompleted(Task task, CompletedSectionBuild completed) {
        synchronized (completedBuildLock) {
            Long requestedGeneration = latestRequestedGenerations.get(task.snapshot().key());
            if (requestedGeneration != null && requestedGeneration == task.generation()) {
                completedBuilds.put(task.snapshot().key(), completed);
                flightRecorder.record(
                        "publishCompleted",
                        task.snapshot().key(),
                        task.generation(),
                        completed.mesh().triangleCount(),
                        0,
                        0,
                        false,
                        false
                );
            } else {
                flightRecorder.record(
                        "publishRejected",
                        task.snapshot().key(),
                        task.generation(),
                        requestedGeneration == null ? -1L : requestedGeneration,
                        0,
                        0L,
                        false,
                        true
                );
                discardedSections.incrementAndGet();
            }
        }
    }

    private void discardCompleted(SectionKey key) {
        synchronized (completedBuildLock) {
            if (completedBuilds.remove(key) != null) {
                discardedSections.incrementAndGet();
            }
        }
    }

    private void clearCompleted() {
        synchronized (completedBuildLock) {
            if (!completedBuilds.isEmpty()) {
                discardedSections.addAndGet(completedBuilds.size());
                completedBuilds.clear();
            }
        }
    }

    private void rescheduleIfLatestTaskRemains(SectionKey key) {
        scheduledKeys.remove(key);
        if (!closed.get() && latestTasks.containsKey(key) && scheduledKeys.add(key)) {
            if (preferredKeys.contains(key)) {
                queuedTasks.offerFirst(key);
            } else {
                queuedTasks.offerLast(key);
            }
        }
    }

    private int inFlightTaskCount() {
        return currentTaskPressure(latestTasks.size(), queuedTasks.size(), activeWorkerTasks.get());
    }

    static int currentTaskPressure(int latestTaskCount, int queuedTaskCount, int activeWorkerTaskCount) {
        if (latestTaskCount < 0) {
            throw new IllegalArgumentException("latestTaskCount must not be negative");
        }
        if (queuedTaskCount < 0) {
            throw new IllegalArgumentException("queuedTaskCount must not be negative");
        }
        if (activeWorkerTaskCount < 0) {
            throw new IllegalArgumentException("activeWorkerTaskCount must not be negative");
        }

        /*
         * queuedTasks intentionally is not part of this pressure value. Replaced section snapshots
         * leave stale queue entries behind until a worker observes and discards them; counting those
         * stale objects as in-flight work makes chunk streaming self-throttle long after the latest
         * renderer state has moved on.
         */
        return Math.max(latestTaskCount, activeWorkerTaskCount);
    }

    private CompletedSectionPayload buildPayload(
            SectionVoxelSnapshot snapshot,
            SectionNeighborhood neighborhood,
            SectionMesher mesher,
            SectionMeshBuilder meshBuilder,
            long contentRevision
    ) {
        if (snapshot.hasOnlyAir() && !snapshot.hasFluid()) {
            fastEmptySections.incrementAndGet();
            SectionTriangleMesh mesh = new SectionTriangleMesh(
                    snapshot.key(),
                    new short[0],
                    new int[0],
                    new int[0],
                    new byte[0],
                    new byte[0],
                    new int[0],
                    new byte[0],
                    new byte[0]
            );
            return new CompletedSectionPayload(
                    SectionMaterialCache.MaterialFacts.fromSnapshot(snapshot),
                    0,
                    mesh
            );
        }

        SectionFaceStaging faces = mesher.stage(snapshot, neighborhood);
        SectionTriangleMesh mesh = meshBuilder.build(faces);
        return new CompletedSectionPayload(
                SectionMaterialCache.MaterialFacts.fromSnapshot(snapshot),
                faces.faceCount(),
                mesh
        );
    }

    /** Fixed-capacity CPU build trace; one log write after the first front is complete. */
    private static final class SectionBuildFlightRecorder {
        private static final int CAPACITY = 4096;

        private final boolean enabled = Boolean.getBoolean("mcvulkanrt.takeoverFlightRecorder.enabled");
        private final Entry[] entries = enabled ? entries() : new Entry[0];
        private long nextSequence;
        private int nextSlot;
        private boolean dumped;

        private synchronized void record(
                String edge,
                SectionKey key,
                long generation,
                long value,
                int sourceFlags,
                long durationNanos,
                boolean preferred,
                boolean discarded
        ) {
            if (!enabled || dumped) {
                return;
            }
            long sequence = ++nextSequence;
            Entry entry = entries[nextSlot];
            nextSlot = (nextSlot + 1) % entries.length;
            entry.set(
                    sequence,
                    RtTakeoverTimeline.elapsedMillis(),
                    edge,
                    key,
                    generation,
                    value,
                    sourceFlags,
                    durationNanos,
                    preferred,
                    discarded
            );
        }

        private synchronized void reset() {
            if (!enabled) {
                return;
            }
            nextSequence = 0L;
            nextSlot = 0;
            dumped = false;
            for (Entry entry : entries) {
                entry.sequence = 0L;
            }
        }

        private synchronized void dumpOnce(Set<SectionKey> foregroundKeys) {
            Objects.requireNonNull(foregroundKeys, "foregroundKeys");
            if (!enabled || dumped || foregroundKeys.isEmpty()) {
                return;
            }
            dumped = true;
            long firstSequence = Math.max(1L, nextSequence - entries.length + 1L);
            StringBuilder trace = new StringBuilder(16_384);
            int retained = 0;
            for (long sequence = firstSequence; sequence <= nextSequence; sequence++) {
                Entry entry = entries[(int) ((sequence - 1L) % entries.length)];
                if (entry.sequence != sequence || !foregroundKeys.contains(entry.key)) {
                    continue;
                }
                if (retained++ > 0) {
                    trace.append(';');
                }
                entry.appendTo(trace);
            }
            top.ceroxe.mcvulkanrt.renderer.RendererLog.info(
                    "rt first-front CPU flight recorder: events={}, retained={}, overwritten={}, trace={}",
                    nextSequence,
                    retained,
                    Math.max(0L, nextSequence - entries.length),
                    trace
            );
        }

        private static Entry[] entries() {
            Entry[] result = new Entry[CAPACITY];
            for (int index = 0; index < result.length; index++) {
                result[index] = new Entry();
            }
            return result;
        }

        private static final class Entry {
            private long sequence;
            private long elapsedMillis;
            private String edge;
            private SectionKey key;
            private long generation;
            private long value;
            private int sourceFlags;
            private long durationNanos;
            private boolean preferred;
            private boolean discarded;

            private void set(
                    long sequence,
                    long elapsedMillis,
                    String edge,
                    SectionKey key,
                    long generation,
                    long value,
                    int sourceFlags,
                    long durationNanos,
                    boolean preferred,
                    boolean discarded
            ) {
                this.sequence = sequence;
                this.elapsedMillis = elapsedMillis;
                this.edge = Objects.requireNonNull(edge, "edge");
                this.key = Objects.requireNonNull(key, "key");
                this.generation = generation;
                this.value = value;
                this.sourceFlags = sourceFlags;
                this.durationNanos = durationNanos;
                this.preferred = preferred;
                this.discarded = discarded;
            }

            private void appendTo(StringBuilder output) {
                output.append('{').append(sequence).append('@').append(elapsedMillis).append("ms:")
                        .append(edge)
                        .append(",key=").append(key)
                        .append(",generation=").append(generation)
                        .append(",value=").append(value)
                        .append(",flags=0x").append(Integer.toHexString(sourceFlags))
                        .append(",durationUs=").append(durationNanos / 1_000L)
                        .append(",preferred=").append(preferred)
                        .append(",discarded=").append(discarded)
                        .append('}');
            }
        }
    }

    private record Task(
            SectionVoxelSnapshot snapshot,
            long generation,
            SectionNeighborhood neighborhood,
            long submittedNanos,
            long contentRevision
    ) {
        private Task {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            neighborhood = Objects.requireNonNull(neighborhood, "neighborhood");
            if (!snapshot.key().equals(neighborhood.centerKey())) {
                throw new IllegalArgumentException("task neighborhood center must match snapshot key");
            }
            if (submittedNanos <= 0L) {
                throw new IllegalArgumentException("task submission time must be positive");
            }
            if (contentRevision < -1L) {
                throw new IllegalArgumentException("task content revision must be at least -1");
            }
        }
    }

    private record CompletedSectionPayload(
            SectionMaterialCache.MaterialFacts materialFacts,
            int faceCount,
            SectionTriangleMesh mesh
    ) {
        private CompletedSectionPayload {
            materialFacts = Objects.requireNonNull(materialFacts, "materialFacts");
            mesh = Objects.requireNonNull(mesh, "mesh");
            if (faceCount < 0) {
                throw new IllegalArgumentException("completed section face count must not be negative");
            }
        }
    }

    public record CompletedSectionBuild(
            SectionVoxelSnapshot snapshot,
            long generation,
            SectionMaterialCache.MaterialFacts materialFacts,
            int faceCount,
            SectionTriangleMesh mesh
    ) {
        public CompletedSectionBuild {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            materialFacts = Objects.requireNonNull(materialFacts, "materialFacts");
            mesh = Objects.requireNonNull(mesh, "mesh");
            if (generation <= 0L) {
                throw new IllegalArgumentException("generation must be positive");
            }
            if (faceCount < 0) {
                throw new IllegalArgumentException("completed section face count must not be negative");
            }
            if (!snapshot.key().equals(mesh.key())) {
                throw new IllegalArgumentException("completed section build keys must match");
            }
        }

        public SectionKey key() {
            return snapshot.key();
        }
    }

    private static int intProperty(String name, int defaultValue) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= Thread.MIN_PRIORITY && parsed <= Thread.MAX_PRIORITY ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}

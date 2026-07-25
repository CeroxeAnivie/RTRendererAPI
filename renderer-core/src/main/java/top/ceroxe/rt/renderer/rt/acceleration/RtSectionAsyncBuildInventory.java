package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.scene.PackedSectionMembership;
import top.ceroxe.rt.renderer.scene.SectionKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Owns the complete CPU-recording to GPU-build inventory for section BLAS work.
 *
 * <p>The two stages form one native-resource ownership chain. Keeping their mutable lists,
 * immutable membership publications, revision counters, capacity metrics, and executor lifetime
 * in one owner prevents a scheduler call site from changing a list without advancing the matching
 * generation. The outer cache remains responsible for admission policy and Vulkan result
 * publication; this class owns only the work already admitted to the asynchronous chain.</p>
 */
final class RtSectionAsyncBuildInventory {
    private final ArrayList<RtPendingSectionBlasRecording> recordings = new ArrayList<>();
    private final ArrayList<RtPendingSectionBlasBuild> gpuBuilds = new ArrayList<>();
    private final ThreadPoolExecutor recordingExecutor;
    private final PackedSectionMembership.Builder recordingMembershipBuilder = PackedSectionMembership.builder(0);
    private final PackedSectionMembership.Builder gpuMembershipBuilder = PackedSectionMembership.builder(0);

    private PackedSectionMembership recordingMembership = PackedSectionMembership.empty();
    private PackedSectionMembership gpuMembership = PackedSectionMembership.empty();
    private long recordingRevision;
    private long gpuRevision;
    private long nextSequence;
    private long publishedRecordingRevision = -1L;
    private long publishedGpuRevision = -1L;
    private boolean closed;

    RtSectionAsyncBuildInventory() {
        this(RtPendingSectionBlasRecording.createExecutor());
    }

    RtSectionAsyncBuildInventory(ThreadPoolExecutor recordingExecutor) {
        this.recordingExecutor = Objects.requireNonNull(recordingExecutor, "recordingExecutor");
    }

    private static RuntimeException appendFailure(RuntimeException failure, RuntimeException next) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }

    /**
     * Allocates the stable CPU-recording/GPU-build/JFR join identity.
     */
    long allocateSequence() {
        requireOpen();
        long allocated = nextSequence;
        nextSequence = Math.incrementExact(nextSequence);
        return allocated;
    }

    /**
     * Starts and publishes one CPU recording as a single ownership operation.
     *
     * <p>Capacity is reserved before the task becomes executable. Therefore an allocation failure
     * cannot leave a worker running without a discoverable owner in {@link #recordings}.</p>
     */
    void startRecording(
            RtPendingSectionBlasRecording recording,
            RtPendingSectionBlasRecording.PrioritizedTask task
    ) {
        requireOpen();
        Objects.requireNonNull(recording, "recording");
        Objects.requireNonNull(task, "task");
        recordings.ensureCapacity(Math.addExact(recordings.size(), 1));
        long nextRecordingRevision = Math.incrementExact(recordingRevision);
        recording.start(task);
        try {
            recordingExecutor.execute(task);
            recordings.add(recording);
            recordingRevision = nextRecordingRevision;
        } catch (RuntimeException | Error failure) {
            try {
                recording.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /**
     * Atomically moves one completed recording into GPU-submission ownership.
     */
    void promoteRecording(
            int recordingIndex,
            RtPendingSectionBlasRecording expectedRecording,
            RtPendingSectionBlasBuild gpuBuild
    ) {
        requireOpen();
        Objects.requireNonNull(expectedRecording, "expectedRecording");
        Objects.requireNonNull(gpuBuild, "gpuBuild");
        if (recordingAt(recordingIndex) != expectedRecording) {
            throw new IllegalStateException("async BLAS recording inventory changed during promotion");
        }
        if (expectedRecording.activeSectionCount() != gpuBuild.activeSectionCount()) {
            throw new IllegalStateException("recording and GPU BLAS membership diverged during promotion");
        }
        gpuBuilds.ensureCapacity(Math.addExact(gpuBuilds.size(), 1));
        long nextRecordingRevision = Math.incrementExact(recordingRevision);
        long nextGpuRevision = Math.incrementExact(gpuRevision);
        recordings.remove(recordingIndex);
        gpuBuilds.add(gpuBuild);
        recordingRevision = nextRecordingRevision;
        gpuRevision = nextGpuRevision;
    }

    RtPendingSectionBlasRecording removeRecordingAt(int index) {
        requireOpen();
        RtPendingSectionBlasRecording removed = recordings.get(index);
        long nextRevision = removed.activeSectionCount() == 0
                ? recordingRevision
                : Math.incrementExact(recordingRevision);
        recordings.remove(index);
        recordingRevision = nextRevision;
        return removed;
    }

    RtPendingSectionBlasBuild removeGpuBuildAt(int index) {
        requireOpen();
        RtPendingSectionBlasBuild removed = gpuBuilds.get(index);
        long nextRevision = removed.activeSectionCount() == 0
                ? gpuRevision
                : Math.incrementExact(gpuRevision);
        gpuBuilds.remove(index);
        gpuRevision = nextRevision;
        return removed;
    }

    /**
     * Removes a fully consumed build. Every active result must already have advanced the GPU
     * membership revision through {@link #markGpuResultApplied(RtPendingSectionBlasBuild, SectionKey)}.
     */
    void removeConsumedGpuBuildAt(int index, RtPendingSectionBlasBuild expectedBuild) {
        requireOpen();
        if (gpuBuildAt(index) != Objects.requireNonNull(expectedBuild, "expectedBuild")) {
            throw new IllegalStateException("async GPU BLAS inventory changed during result application");
        }
        if (expectedBuild.activeSectionCount() != 0 || expectedBuild.hasCompletedResults()) {
            throw new IllegalStateException("cannot remove an unconsumed async GPU BLAS build");
        }
        gpuBuilds.remove(index);
    }

    void markGpuResultApplied(RtPendingSectionBlasBuild build, SectionKey key) {
        requireOpen();
        Objects.requireNonNull(build, "build");
        Objects.requireNonNull(key, "key");
        requireOwnedGpuBuild(build);
        boolean membershipChanged = !build.resultWasInvalidated(key);
        long nextRevision = membershipChanged ? Math.incrementExact(gpuRevision) : gpuRevision;
        build.markCompletedResultApplied(key);
        gpuRevision = nextRevision;
    }

    Invalidation invalidate(SectionKey key) {
        requireOpen();
        Objects.requireNonNull(key, "key");
        List<Long> recordingSequences = new ArrayList<>();
        List<Long> gpuSequences = new ArrayList<>();
        boolean recordingChanged = false;
        boolean gpuChanged = false;
        for (RtPendingSectionBlasRecording recording : recordings) {
            if (recording.containsSection(key)) {
                recordingSequences.add(recording.sequence());
            }
            recordingChanged |= recording.invalidate(key);
        }
        for (RtPendingSectionBlasBuild build : gpuBuilds) {
            if (build.containsSection(key)) {
                gpuSequences.add(build.sequence());
            }
            gpuChanged |= build.invalidate(key);
        }
        if (recordingChanged) {
            recordingRevision = Math.incrementExact(recordingRevision);
        }
        if (gpuChanged) {
            gpuRevision = Math.incrementExact(gpuRevision);
        }
        return new Invalidation(List.copyOf(recordingSequences), List.copyOf(gpuSequences));
    }

    void invalidateAll() {
        requireOpen();
        boolean recordingChanged = false;
        boolean gpuChanged = false;
        for (RtPendingSectionBlasRecording recording : recordings) {
            recordingChanged |= recording.invalidateAll();
        }
        for (RtPendingSectionBlasBuild build : gpuBuilds) {
            gpuChanged |= build.invalidateAll();
        }
        if (recordingChanged) {
            recordingRevision = Math.incrementExact(recordingRevision);
        }
        if (gpuChanged) {
            gpuRevision = Math.incrementExact(gpuRevision);
        }
    }

    /**
     * Applies a new view classification and priority boost without exposing the executor.
     */
    void prioritizeRecordings(long viewRevision, Set<SectionKey> preferredSectionKeys) {
        requireOpen();
        Objects.requireNonNull(preferredSectionKeys, "preferredSectionKeys");
        for (RtPendingSectionBlasRecording recording : recordings) {
            int matchingSections = recording.activePreferredSectionCount(preferredSectionKeys);
            recording.recordViewClassification(viewRevision, preferredSectionKeys, matchingSections);
            if (matchingSections > 0) {
                recording.boostPriority(recordingExecutor);
            }
        }
    }

    void prioritizeGpuBuilds(Set<SectionKey> preferredSectionKeys) {
        requireOpen();
        Objects.requireNonNull(preferredSectionKeys, "preferredSectionKeys");
        for (RtPendingSectionBlasBuild build : gpuBuilds) {
            if (build.activePreferredSectionCount(preferredSectionKeys) > 0) {
                build.boostPriority();
            }
        }
    }

    /**
     * Returns whether a still-owned CPU recording can advance the current preferred front.
     *
     * <p>A recording leaves {@code pendingBuilds} before its worker finishes. Treating only the
     * pre-recording queue as pending creates a false idle interval: completed older pages are
     * then held by the strict foreground gate while the actual preferred dependency is already
     * recording. This query preserves the ownership boundary and exposes that dependency without
     * publishing worker internals.</p>
     */
    boolean hasPendingPreferredRecording(Set<SectionKey> preferredSectionKeys) {
        requireOpen();
        Objects.requireNonNull(preferredSectionKeys, "preferredSectionKeys");
        for (RtPendingSectionBlasRecording recording : recordings) {
            if (recording.activePreferredSectionCount(preferredSectionKeys) > 0) {
                return true;
            }
        }
        return false;
    }

    boolean cancelRecordingIfQueued(int index, RtPendingSectionBlasRecording expectedRecording) {
        requireOpen();
        if (recordingAt(index) != Objects.requireNonNull(expectedRecording, "expectedRecording")) {
            throw new IllegalStateException("async BLAS recording inventory changed during cancellation");
        }
        if (!expectedRecording.cancelIfQueued(recordingExecutor)) {
            return false;
        }
        long nextRevision = expectedRecording.activeSectionCount() == 0
                ? recordingRevision
                : Math.incrementExact(recordingRevision);
        recordings.remove(index);
        recordingRevision = nextRevision;
        return true;
    }

    PackedSectionMembership recordingMembership() {
        if (publishedRecordingRevision != recordingRevision) {
            int expectedSections = 0;
            for (RtPendingSectionBlasRecording recording : recordings) {
                expectedSections = Math.addExact(expectedSections, recording.activeSectionCount());
            }
            recordingMembershipBuilder.reset(expectedSections);
            for (RtPendingSectionBlasRecording recording : recordings) {
                recording.addActiveKeysTo(recordingMembershipBuilder);
            }
            recordingMembership = recordingMembershipBuilder.buildCanonical(recordingMembership);
            publishedRecordingRevision = recordingRevision;
        }
        return recordingMembership;
    }

    PackedSectionMembership gpuMembership() {
        if (publishedGpuRevision != gpuRevision) {
            int expectedSections = 0;
            for (RtPendingSectionBlasBuild build : gpuBuilds) {
                expectedSections = Math.addExact(expectedSections, build.activeSectionCount());
            }
            gpuMembershipBuilder.reset(expectedSections);
            for (RtPendingSectionBlasBuild build : gpuBuilds) {
                build.addActiveKeysTo(gpuMembershipBuilder);
            }
            gpuMembership = gpuMembershipBuilder.buildCanonical(gpuMembership);
            publishedGpuRevision = gpuRevision;
        }
        return gpuMembership;
    }

    long recordingRevision() {
        return recordingRevision;
    }

    long gpuRevision() {
        return gpuRevision;
    }

    int recordingBatchCount() {
        return recordings.size();
    }

    int gpuBatchCount() {
        return gpuBuilds.size();
    }

    /**
     * Number of native submissions which still occupy the ordered Vulkan queue.
     */
    int incompleteGpuBatchCount() {
        int count = 0;
        for (RtPendingSectionBlasBuild build : gpuBuilds) {
            if (!build.hasCompletedResults()) {
                count = Math.incrementExact(count);
            }
        }
        return count;
    }

    int totalBatchCount() {
        return Math.addExact(recordings.size(), gpuBuilds.size());
    }

    boolean hasRecordings() {
        return !recordings.isEmpty();
    }

    /**
     * Returns whether selection was blocked by policy rather than unfinished CPU recording.
     */
    boolean hasCompletedRecording() {
        for (RtPendingSectionBlasRecording recording : recordings) {
            if (recording.isDone()) {
                return true;
            }
        }
        return false;
    }

    boolean hasGpuBuilds() {
        return !gpuBuilds.isEmpty();
    }

    boolean hasActiveSections() {
        for (RtPendingSectionBlasRecording recording : recordings) {
            if (recording.activeSectionCount() != 0) {
                return true;
            }
        }
        return hasActiveGpuBuilds();
    }

    int activeSectionCount() {
        int count = 0;
        for (RtPendingSectionBlasRecording recording : recordings) {
            count = Math.addExact(count, recording.activeSectionCount());
        }
        for (RtPendingSectionBlasBuild build : gpuBuilds) {
            count = Math.addExact(count, build.activeSectionCount());
        }
        return count;
    }

    long activeTriangleCount() {
        long count = 0L;
        for (RtPendingSectionBlasRecording recording : recordings) {
            count = Math.addExact(count, recording.activeTriangleCount());
        }
        for (RtPendingSectionBlasBuild build : gpuBuilds) {
            count = Math.addExact(count, build.activeTriangleCount());
        }
        return count;
    }

    long activeEstimatedBytes() {
        long bytes = 0L;
        for (RtPendingSectionBlasRecording recording : recordings) {
            bytes = Math.addExact(bytes, recording.activeEstimatedBytes());
        }
        for (RtPendingSectionBlasBuild build : gpuBuilds) {
            bytes = Math.addExact(bytes, build.activeEstimatedBytes());
        }
        return bytes;
    }

    RtPendingSectionBlasRecording recordingAt(int index) {
        return recordings.get(index);
    }

    RtPendingSectionBlasBuild gpuBuildAt(int index) {
        return gpuBuilds.get(index);
    }

    boolean hasCompletedDeferredBackgroundRecording(Set<SectionKey> preferredSectionKeys) {
        Objects.requireNonNull(preferredSectionKeys, "preferredSectionKeys");
        for (RtPendingSectionBlasRecording recording : recordings) {
            if (recording.isDone() && recording.activePreferredSectionCount(preferredSectionKeys) == 0) {
                return true;
            }
        }
        return false;
    }

    DebugState debugState(SectionKey key) {
        Objects.requireNonNull(key, "key");
        boolean recording = false;
        boolean gpu = false;
        long sequence = -1L;
        for (RtPendingSectionBlasRecording candidate : recordings) {
            if (candidate.containsSection(key)) {
                recording = true;
                sequence = Math.max(sequence, candidate.sequence());
            }
        }
        for (RtPendingSectionBlasBuild candidate : gpuBuilds) {
            if (candidate.containsSection(key)) {
                gpu = true;
                sequence = Math.max(sequence, candidate.sequence());
            }
        }
        return new DebugState(recording, gpu, sequence);
    }

    Metrics metrics(Set<SectionKey> preferredSectionKeys) {
        Objects.requireNonNull(preferredSectionKeys, "preferredSectionKeys");
        int activeSections = 0;
        long activeTriangles = 0L;
        long activeBytes = 0L;
        int retainedSections = 0;
        long retainedBytes = 0L;
        int preferredSections = 0;
        int backgroundBatches = 0;
        int backgroundRetainedSections = 0;
        long backgroundRetainedBytes = 0L;
        for (RtPendingSectionBlasRecording recording : recordings) {
            activeSections = Math.addExact(activeSections, recording.activeSectionCount());
            activeTriangles = Math.addExact(activeTriangles, recording.activeTriangleCount());
            activeBytes = Math.addExact(activeBytes, recording.activeEstimatedBytes());
            retainedSections = Math.addExact(retainedSections, recording.retainedSectionCount());
            retainedBytes = Math.addExact(retainedBytes, recording.retainedEstimatedBytes());
            preferredSections = Math.addExact(
                    preferredSections,
                    recording.activePreferredSectionCount(preferredSectionKeys)
            );
            if (recording.backgroundAdmission()) {
                backgroundBatches = Math.incrementExact(backgroundBatches);
                backgroundRetainedSections = Math.addExact(
                        backgroundRetainedSections,
                        recording.retainedSectionCount()
                );
                backgroundRetainedBytes = Math.addExact(
                        backgroundRetainedBytes,
                        recording.retainedEstimatedBytes()
                );
            }
        }
        for (RtPendingSectionBlasBuild build : gpuBuilds) {
            activeSections = Math.addExact(activeSections, build.activeSectionCount());
            activeTriangles = Math.addExact(activeTriangles, build.activeTriangleCount());
            activeBytes = Math.addExact(activeBytes, build.activeEstimatedBytes());
            retainedSections = Math.addExact(retainedSections, build.retainedSectionCount());
            retainedBytes = Math.addExact(retainedBytes, build.retainedEstimatedBytes());
            preferredSections = Math.addExact(
                    preferredSections,
                    build.activePreferredSectionCount(preferredSectionKeys)
            );
            if (build.backgroundAdmission()) {
                backgroundBatches = Math.incrementExact(backgroundBatches);
                backgroundRetainedSections = Math.addExact(
                        backgroundRetainedSections,
                        build.retainedSectionCount()
                );
                backgroundRetainedBytes = Math.addExact(
                        backgroundRetainedBytes,
                        build.retainedEstimatedBytes()
                );
            }
        }
        return new Metrics(
                recordings.size(),
                gpuBuilds.size(),
                activeSections,
                activeTriangles,
                activeBytes,
                retainedSections,
                retainedBytes,
                preferredSections,
                backgroundBatches,
                backgroundRetainedSections,
                backgroundRetainedBytes
        );
    }

    void addActiveKeysTo(Set<SectionKey> target) {
        Objects.requireNonNull(target, "target");
        for (RtPendingSectionBlasRecording recording : recordings) {
            recording.addActiveKeysTo(target);
        }
        for (RtPendingSectionBlasBuild build : gpuBuilds) {
            build.addActiveKeysTo(target);
        }
    }

    RuntimeException closeCollecting(RuntimeException failure) {
        if (closed) {
            return failure;
        }
        boolean recordingMembershipChanged = hasActiveRecordings();
        boolean gpuMembershipChanged = hasActiveGpuBuilds();
        long nextRecordingRevision = recordingMembershipChanged
                ? Math.incrementExact(recordingRevision)
                : recordingRevision;
        long nextGpuRevision = gpuMembershipChanged ? Math.incrementExact(gpuRevision) : gpuRevision;
        closed = true;
        for (RtPendingSectionBlasRecording recording : recordings) {
            try {
                recording.close();
            } catch (RuntimeException closeFailure) {
                failure = appendFailure(failure, closeFailure);
            }
        }
        recordings.clear();
        for (RtPendingSectionBlasBuild build : gpuBuilds) {
            try {
                build.close();
            } catch (RuntimeException closeFailure) {
                failure = appendFailure(failure, closeFailure);
            }
        }
        gpuBuilds.clear();
        recordingRevision = nextRecordingRevision;
        gpuRevision = nextGpuRevision;
        recordingExecutor.shutdown();
        return failure;
    }

    private boolean hasActiveRecordings() {
        for (RtPendingSectionBlasRecording recording : recordings) {
            if (recording.activeSectionCount() != 0) {
                return true;
            }
        }
        return false;
    }

    private boolean hasActiveGpuBuilds() {
        for (RtPendingSectionBlasBuild build : gpuBuilds) {
            if (build.activeSectionCount() != 0) {
                return true;
            }
        }
        return false;
    }

    private void requireOwnedGpuBuild(RtPendingSectionBlasBuild expected) {
        for (RtPendingSectionBlasBuild candidate : gpuBuilds) {
            if (candidate == expected) {
                return;
            }
        }
        throw new IllegalStateException("async GPU BLAS build is not owned by this inventory");
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("section async BLAS inventory is closed");
        }
    }

    record Invalidation(List<Long> recordingSequences, List<Long> gpuSequences) {
        Invalidation {
            recordingSequences = List.copyOf(recordingSequences);
            gpuSequences = List.copyOf(gpuSequences);
        }
    }

    record DebugState(boolean recording, boolean gpu, long latestSequence) {
        DebugState {
            if (latestSequence < -1L) {
                throw new IllegalArgumentException("latest async BLAS sequence must be -1 or non-negative");
            }
        }
    }

    record Metrics(
            int recordingBatches,
            int gpuBatches,
            int activeSections,
            long activeTriangles,
            long activeBytes,
            int retainedSections,
            long retainedBytes,
            int preferredSections,
            int backgroundBatches,
            int backgroundRetainedSections,
            long backgroundRetainedBytes
    ) {
        Metrics {
            if (recordingBatches < 0 || gpuBatches < 0 || activeSections < 0 || activeTriangles < 0L
                    || activeBytes < 0L || retainedSections < 0 || retainedBytes < 0L
                    || preferredSections < 0 || backgroundBatches < 0 || backgroundRetainedSections < 0
                    || backgroundRetainedBytes < 0L) {
                throw new IllegalArgumentException("async BLAS inventory metrics must not be negative");
            }
        }

        int totalBatches() {
            return Math.addExact(recordingBatches, gpuBatches);
        }
    }
}

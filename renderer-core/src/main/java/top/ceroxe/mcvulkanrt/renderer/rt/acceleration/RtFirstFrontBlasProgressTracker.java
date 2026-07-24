package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.RendererRtDiagnostics;
import top.ceroxe.mcvulkanrt.renderer.rt.runtime.RtCore;
import top.ceroxe.mcvulkanrt.renderer.scene.PackedSectionMembership;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

import java.util.Objects;

/**
 * Owns first-authoritative-front coverage proofs, progress throttling, and the bounded flight trace.
 *
 * <p>BLAS scheduling publishes immutable lifecycle memberships into this observer.  It never owns
 * build queues, Vulkan resources, or renderer revisions, so diagnostics can be enabled, reset, or
 * inspected without mutating the production state machine.</p>
 */
final class RtFirstFrontBlasProgressTracker {
    private static final long PROGRESS_LOG_INTERVAL_NANOS = 1_000_000_000L;

    private final RendererRtDiagnostics diagnostics;
    private final RtFirstFrontBlasFlightRecorder flightRecorder;
    private final RtSectionCoverageProof sourceCoverage = new RtSectionCoverageProof();
    private final RtSectionCoverageProof queuedCoverage = new RtSectionCoverageProof();
    private final RtSectionCoverageProof recordingCoverage = new RtSectionCoverageProof();
    private final RtSectionCoverageProof gpuCoverage = new RtSectionCoverageProof();
    private final RtSectionCoverageProof activeCoverage = new RtSectionCoverageProof();
    private final RtSectionCoverageProof boundCoverage = new RtSectionCoverageProof();

    private long latestViewRevision = -1L;
    private int latestRequired;
    private int latestSource;
    private int latestQueued;
    private int latestRecording;
    private int latestGpu;
    private int latestActive;
    private int latestBound;
    private long lastLoggedSignature = Long.MIN_VALUE;
    private boolean lastLoggedComplete;
    private long nextProgressLogNanos;

    RtFirstFrontBlasProgressTracker(RendererRtDiagnostics diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.flightRecorder = new RtFirstFrontBlasFlightRecorder(diagnostics.edges()::elapsedMillis);
    }

    void reset() {
        flightRecorder.reset();
        sourceCoverage.clear();
        queuedCoverage.clear();
        recordingCoverage.clear();
        gpuCoverage.clear();
        activeCoverage.clear();
        boundCoverage.clear();
        latestViewRevision = -1L;
        latestRequired = latestSource = latestQueued = latestRecording = 0;
        latestGpu = latestActive = latestBound = 0;
        lastLoggedSignature = Long.MIN_VALUE;
        lastLoggedComplete = false;
        nextProgressLogNanos = 0L;
    }

    void recordLifecycle(
            String edge,
            SectionKey key,
            long buildSequence,
            long desiredRevision,
            long activeRevision,
            int sourceFlags,
            boolean activeExists,
            boolean baseMatches,
            boolean invalidated
    ) {
        flightRecorder.record(
                edge,
                key,
                buildSequence,
                desiredRevision,
                activeRevision,
                sourceFlags,
                activeExists,
                baseMatches,
                invalidated
        );
    }

    boolean activeCoverageIncomplete(
            PackedSectionMembership authoritative,
            long authorityRevision,
            PackedSectionMembership active,
            long activeRevision
    ) {
        Objects.requireNonNull(authoritative, "authoritative");
        Objects.requireNonNull(active, "active");
        return !authoritative.isEmpty()
                && activeCoverage.matchedCount(
                        authoritative,
                        authorityRevision,
                        active,
                        activeRevision
                ) != authoritative.size();
    }

    boolean boundCovers(
            PackedSectionMembership authoritative,
            long authorityRevision,
            PackedSectionMembership active,
            long activeRevision,
            PackedSectionMembership bound
    ) {
        Objects.requireNonNull(authoritative, "authoritative");
        Objects.requireNonNull(active, "active");
        Objects.requireNonNull(bound, "bound");
        int required = authoritative.size();
        return required > 0
                && !activeCoverageIncomplete(authoritative, authorityRevision, active, activeRevision)
                && boundCoverage.matchedCount(
                        authoritative,
                        authorityRevision,
                        bound,
                        Long.MIN_VALUE
                ) == required;
    }

    /**
     * Samples the six lifecycle publications without allocating a per-frame aggregate object.
     * Membership and revision parameters stay adjacent so a call site cannot obscure provenance.
     */
    void recordProgress(
            long viewRevision,
            PackedSectionMembership authoritative,
            long authorityRevision,
            PackedSectionMembership source,
            long sourceRevision,
            PackedSectionMembership queued,
            long queuedRevision,
            PackedSectionMembership recording,
            long recordingRevision,
            PackedSectionMembership gpu,
            long gpuRevision,
            PackedSectionMembership active,
            long activeRevision,
            PackedSectionMembership bound,
            long boundRevision,
            RtCore.RuntimeActivity runtimeActivity
    ) {
        Objects.requireNonNull(authoritative, "authoritative");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(queued, "queued");
        Objects.requireNonNull(recording, "recording");
        Objects.requireNonNull(gpu, "gpu");
        Objects.requireNonNull(active, "active");
        Objects.requireNonNull(bound, "bound");
        Objects.requireNonNull(runtimeActivity, "runtimeActivity");
        if (authoritative.isEmpty()) {
            return;
        }
        int required = authoritative.size();
        int sourceCount = sourceCoverage.matchedCount(
                authoritative, authorityRevision, source, sourceRevision
        );
        int queuedCount = queuedCoverage.matchedCount(
                authoritative, authorityRevision, queued, queuedRevision
        );
        int recordingCount = recordingCoverage.matchedCount(
                authoritative, authorityRevision, recording, recordingRevision
        );
        int gpuCount = gpuCoverage.matchedCount(
                authoritative, authorityRevision, gpu, gpuRevision
        );
        int activeCount = activeCoverage.matchedCount(
                authoritative, authorityRevision, active, activeRevision
        );
        int boundCount = boundCoverage.matchedCount(
                authoritative, authorityRevision, bound, boundRevision
        );
        latestViewRevision = viewRevision;
        latestRequired = required;
        latestSource = sourceCount;
        latestQueued = queuedCount;
        latestRecording = recordingCount;
        latestGpu = gpuCount;
        latestActive = activeCount;
        latestBound = boundCount;
        flightRecorder.recordProgress(
                viewRevision,
                required,
                sourceCount,
                queuedCount,
                recordingCount,
                gpuCount,
                activeCount,
                boundCount
        );
        boolean complete = activeCount == required && boundCount == required;
        publishTerminalTrace(authoritative, complete);
        publishProgressEdge(
                viewRevision,
                required,
                sourceCount,
                queuedCount,
                recordingCount,
                gpuCount,
                activeCount,
                boundCount,
                complete,
                runtimeActivity
        );
    }

    Progress latestProgress() {
        return new Progress(
                latestViewRevision,
                latestRequired,
                latestSource,
                latestQueued,
                latestRecording,
                latestGpu,
                latestActive,
                latestBound
        );
    }

    private void publishTerminalTrace(PackedSectionMembership authoritative, boolean complete) {
        if (!complete) {
            return;
        }
        RtFirstFrontBlasFlightRecorder.Dump dump = flightRecorder.dumpOnce(authoritative);
        if (dump != null) {
            top.ceroxe.mcvulkanrt.renderer.RendererLog.info(
                    "rt first-front BLAS flight recorder: events={}, retained={}, overwritten={}, trace={}",
                    dump.events(),
                    dump.retained(),
                    dump.overwritten(),
                    dump.trace()
            );
        }
    }

    private void publishProgressEdge(
            long viewRevision,
            int required,
            int source,
            int queued,
            int recording,
            int gpu,
            int active,
            int bound,
            boolean complete,
            RtCore.RuntimeActivity runtimeActivity
    ) {
        long signature = signature(source, queued, recording, gpu, active, bound);
        if (signature == lastLoggedSignature) {
            return;
        }
        lastLoggedSignature = signature;
        /*
         * Every vector remains available through latestProgress and the bounded flight trace.  Text
         * I/O is rate-limited independently so a many-section successor cannot turn convergence
         * diagnostics into the dominant render-thread workload.
         */
        if (!diagnostics.edges().enabled()) {
            return;
        }
        long now = System.nanoTime();
        boolean completionChanged = complete != lastLoggedComplete;
        if (!completionChanged && now < nextProgressLogNanos) {
            return;
        }
        lastLoggedComplete = complete;
        nextProgressLogNanos = now + PROGRESS_LOG_INTERVAL_NANOS;
        top.ceroxe.mcvulkanrt.renderer.RendererLog.info(
                "rt first-front progress: T={}ms, viewRevision={}, required={}, source={}, queued={}, recording={}, gpuInFlight={}, active={}, bound={}, complete={}, frameSubmitted={}, frameCompleted={}, framePending={}, framePendingAgeMs={}",
                diagnostics.edges().elapsedMillis(),
                viewRevision,
                required,
                source,
                queued,
                recording,
                gpu,
                active,
                bound,
                complete,
                runtimeActivity.frameDispatches(),
                runtimeActivity.latestCompletedFrameDispatch(),
                runtimeActivity.pendingFrame(),
                runtimeActivity.pendingFrameAgeMillis()
        );
    }

    private static long signature(int source, int queued, int recording, int gpu, int active, int bound) {
        long signature = source;
        signature = signature * 31L + queued;
        signature = signature * 31L + recording;
        signature = signature * 31L + gpu;
        signature = signature * 31L + active;
        return signature * 31L + bound;
    }

    record Progress(
            long viewRevision,
            int required,
            int source,
            int queued,
            int recording,
            int gpu,
            int active,
            int bound
    ) {
        Progress {
            if (required < 0 || source < 0 || queued < 0 || recording < 0
                    || gpu < 0 || active < 0 || bound < 0
                    || source > required || queued > required || recording > required
                    || gpu > required || active > required || bound > required) {
                throw new IllegalArgumentException("first-front progress counts are inconsistent");
            }
        }
        boolean complete() {
            return required > 0 && active == required && bound == required;
        }
    }
}

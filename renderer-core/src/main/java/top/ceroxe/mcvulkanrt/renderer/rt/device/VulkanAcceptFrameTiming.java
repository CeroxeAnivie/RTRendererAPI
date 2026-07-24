package top.ceroxe.mcvulkanrt.renderer.rt.device;

import top.ceroxe.mcvulkanrt.renderer.RtBuildTelemetrySink;
import top.ceroxe.mcvulkanrt.renderer.rt.runtime.RtCore;

import java.util.Objects;

/**
 * Bounded timing and decision telemetry for the native accept-frame transaction.
 *
 * <p>The collector owns no Vulkan handle and never influences scheduling. Keeping
 * it separate prevents observability growth from increasing device-context
 * lifecycle coupling while preserving stage-level debug evidence.</p>
 */
final class VulkanAcceptFrameTiming {
    private final RtBuildTelemetrySink buildTelemetry;
    final VulkanAcceptNanoTiming total = new VulkanAcceptNanoTiming("total");
    final VulkanAcceptNanoTiming ingest = new VulkanAcceptNanoTiming("ingest");
    final VulkanAcceptNanoTiming preBuild = new VulkanAcceptNanoTiming("preBuild");
    final VulkanAcceptNanoTiming buildBudget = new VulkanAcceptNanoTiming("buildBudget");
    final VulkanAcceptNanoTiming postBuild = new VulkanAcceptNanoTiming("postBuild");
    final VulkanAcceptNanoTiming worldTlas = new VulkanAcceptNanoTiming("worldTlas");
    final VulkanAcceptNanoTiming worldTlasScheduler = new VulkanAcceptNanoTiming("worldTlasScheduler");
    final VulkanAcceptNanoTiming worldTlasBind = new VulkanAcceptNanoTiming("worldTlasBind");
    final VulkanAcceptNanoTiming dispatch = new VulkanAcceptNanoTiming("dispatch");
    final VulkanAcceptNanoTiming framePoll = new VulkanAcceptNanoTiming("framePoll");
    final VulkanAcceptNanoTiming dynamicPipelineIngest = new VulkanAcceptNanoTiming("dynamicPipelineIngest");
    final VulkanAcceptNanoTiming dynamicBlasIngest = new VulkanAcceptNanoTiming("dynamicBlasIngest");
    final VulkanAcceptNanoTiming sectionEnqueue = new VulkanAcceptNanoTiming("sectionEnqueue");
    final VulkanAcceptNanoTiming descriptorPoll = new VulkanAcceptNanoTiming("descriptorPoll");
    final VulkanAcceptNanoTiming preBuildRetirement = new VulkanAcceptNanoTiming("preBuildRetirement");
    final VulkanAcceptNanoTiming buildSectionCompletionPump = new VulkanAcceptNanoTiming("buildSectionCompletionPump");
    final VulkanAcceptNanoTiming pendingBindCompletion = new VulkanAcceptNanoTiming("pendingBindCompletion");
    final VulkanAcceptNanoTiming deferredBindSubmit = new VulkanAcceptNanoTiming("deferredBindSubmit");
    final VulkanAcceptNanoTiming textureCatchUp = new VulkanAcceptNanoTiming("textureCatchUp");
    final VulkanAcceptNanoTiming stableBackpressure = new VulkanAcceptNanoTiming("stableBackpressure");
    final VulkanAcceptNanoTiming stableFastPath = new VulkanAcceptNanoTiming("stableFastPath");
    final VulkanAcceptNanoTiming preBuildPolicy = new VulkanAcceptNanoTiming("preBuildPolicy");
    final VulkanAcceptNanoTiming preBuildDispatch = new VulkanAcceptNanoTiming("preBuildDispatch");
    final VulkanAcceptNanoTiming preBuildRetry = new VulkanAcceptNanoTiming("preBuildRetry");
    final VulkanAcceptNanoTiming buildDeferPolicy = new VulkanAcceptNanoTiming("buildDeferPolicy");
    final VulkanAcceptNanoTiming buildRemainingBudget = new VulkanAcceptNanoTiming("buildRemainingBudget");
    final VulkanAcceptNanoTiming buildFirstFront = new VulkanAcceptNanoTiming("buildFirstFront");
    final VulkanAcceptNanoTiming buildCoverage = new VulkanAcceptNanoTiming("buildCoverage");
    final VulkanAcceptNanoTiming buildSection = new VulkanAcceptNanoTiming("buildSection");
    final VulkanAcceptNanoTiming buildPostSectionPolicy = new VulkanAcceptNanoTiming("buildPostSectionPolicy");
    final VulkanAcceptNanoTiming buildDynamic = new VulkanAcceptNanoTiming("buildDynamic");
    final VulkanAcceptNanoTiming buildPostDynamicPolicy = new VulkanAcceptNanoTiming("buildPostDynamicPolicy");
    final VulkanAcceptNanoTiming dispatchReadiness = new VulkanAcceptNanoTiming("dispatchReadiness");
    final VulkanAcceptNanoTiming dispatchCapacity = new VulkanAcceptNanoTiming("dispatchCapacity");
    final VulkanAcceptNanoTiming dispatchDescriptor = new VulkanAcceptNanoTiming("dispatchDescriptor");
    final VulkanAcceptNanoTiming dispatchReadinessPolicy = new VulkanAcceptNanoTiming("dispatchReadinessPolicy");
    final VulkanAcceptNanoTiming dispatchDynamicPolicy = new VulkanAcceptNanoTiming("dispatchDynamicPolicy");
    final VulkanAcceptNanoTiming dispatchInteractivePolicy = new VulkanAcceptNanoTiming("dispatchInteractivePolicy");
    final VulkanAcceptNanoTiming dispatchPipeline = new VulkanAcceptNanoTiming("dispatchPipeline");
    final VulkanAcceptNanoTiming dispatchRecorders = new VulkanAcceptNanoTiming("dispatchRecorders");
    long smokeWindowStartNanos;
    long smokeLastSamples;
    long smokeLastTotalMicros;
    long smokeLastIngestMicros;
    long smokeLastPreBuildMicros;
    long smokeLastBuildBudgetMicros;
    long smokeLastPostBuildMicros;
    long smokeLastWorldTlasMicros;
    long smokeLastWorldTlasSchedulerMicros;
    long smokeLastWorldTlasBindMicros;
    long smokeLastDispatchMicros;
    long smokeLastFramePollMicros;
    long smokeLastDynamicPipelineIngestMicros;
    long smokeLastDynamicBlasIngestMicros;
    long smokeLastSectionEnqueueMicros;
    long smokeLastDescriptorPollMicros;
    long smokeLastPreBuildRetirementMicros;
    long smokeLastBuildSectionCompletionPumpMicros;
    long smokeLastPendingBindCompletionMicros;
    long smokeLastDeferredBindSubmitMicros;
    long smokeLastTextureCatchUpMicros;
    long smokeLastStableBackpressureMicros;
    long smokeLastStableFastPathMicros;
    long smokeLastPreBuildPolicyMicros;
    long smokeLastPreBuildDispatchMicros;
    long smokeLastPreBuildRetryMicros;
    long smokeLastBuildDeferPolicyMicros;
    long smokeLastBuildRemainingBudgetMicros;
    long smokeLastBuildFirstFrontMicros;
    long smokeLastBuildCoverageMicros;
    long smokeLastBuildSectionMicros;
    long smokeLastBuildPostSectionPolicyMicros;
    long smokeLastBuildDynamicMicros;
    long smokeLastBuildPostDynamicPolicyMicros;
    long smokeLastDispatchReadinessMicros;
    long smokeLastDispatchCapacityMicros;
    long smokeLastDispatchDescriptorMicros;
    long smokeLastDispatchReadinessPolicyMicros;
    long smokeLastDispatchDynamicPolicyMicros;
    long smokeLastDispatchInteractivePolicyMicros;
    long smokeLastDispatchPipelineMicros;
    long smokeLastDispatchRecordersMicros;
    long dispatchAttempts;
    long dispatchCapacityRejects;
    long dispatchDescriptorRejects;
    long dispatchReadinessRejects;
    long dispatchDynamicRejects;
    long dispatchInteractiveRejects;
    long dispatchPipelineNoSubmits;
    long dispatchSubmissions;
    long dynamicShadowCurrent;
    long dynamicShadowTransformLagEligible;
    long dynamicShadowTopologyBlocked;
    long dynamicShadowGeometryBlocked;
    long dynamicShadowNoBoundWorld;
    long dynamicShadowMaxRevisionLag;
    private final long[] committedFrontDecisions =
            new long[RtCommittedFrontPolicy.Decision.values().length];
    private final long[] smokeLastCommittedFrontDecisions =
            new long[RtCommittedFrontPolicy.Decision.values().length];
    private final VulkanAcceptNanoTiming[] frameTimings = {
            total, ingest, preBuild, buildBudget, postBuild, worldTlas, worldTlasScheduler, worldTlasBind,
            dispatch, framePoll, dynamicPipelineIngest, dynamicBlasIngest, sectionEnqueue, descriptorPoll,
            preBuildRetirement, buildSectionCompletionPump, pendingBindCompletion, deferredBindSubmit,
            textureCatchUp, stableBackpressure, stableFastPath, preBuildPolicy, preBuildDispatch, preBuildRetry,
            buildDeferPolicy, buildRemainingBudget, buildFirstFront, buildCoverage, buildSection,
            buildPostSectionPolicy, buildDynamic, buildPostDynamicPolicy, dispatchReadiness, dispatchCapacity,
            dispatchDescriptor, dispatchReadinessPolicy, dispatchDynamicPolicy, dispatchInteractivePolicy,
            dispatchPipeline, dispatchRecorders
    };
    long committedFrontDispatches;
    long smokeLastCommittedFrontDispatches;
    long smokeLastDispatchAttempts;
    long smokeLastDispatchCapacityRejects;
    long smokeLastDispatchDescriptorRejects;
    long smokeLastDispatchReadinessRejects;
    long smokeLastDispatchDynamicRejects;
    long smokeLastDispatchInteractiveRejects;
    long smokeLastDispatchPipelineNoSubmits;
    long smokeLastDispatchSubmissions;

    VulkanAcceptFrameTiming(RtBuildTelemetrySink buildTelemetry) {
        this.buildTelemetry = Objects.requireNonNull(buildTelemetry, "buildTelemetry");
    }

    /**
     * Starts an observation-only per-frame ledger. Lifetime aggregates remain intact; this ledger
     * prevents a branch that skips a stage from being reported as the preceding frame's duration.
     */
    void beginFrame() {
        for (VulkanAcceptNanoTiming timing : frameTimings) {
            timing.resetFrameMicros();
        }
    }

    FrameBreakdown frameBreakdown() {
        return new FrameBreakdown(
                ingest.frameMicros(),
                preBuild.frameMicros(),
                buildSectionCompletionPump.frameMicros(),
                buildBudget.frameMicros(),
                buildSection.frameMicros(),
                postBuild.frameMicros(),
                worldTlas.frameMicros(),
                worldTlasScheduler.frameMicros(),
                worldTlasBind.frameMicros(),
                dispatch.frameMicros()
        );
    }

    record FrameBreakdown(
            long ingestMicros,
            long preBuildMicros,
            long sectionCompletionPumpMicros,
            long buildBudgetMicros,
            long buildSectionMicros,
            long postBuildMicros,
            long worldTlasMicros,
            long worldTlasSchedulerMicros,
            long worldTlasBindMicros,
            long dispatchMicros
    ) {
    }

    String summary() {
        return "acceptFrameTiming{" + total.summary()
                + ", " + ingest.summary()
                + ", " + preBuild.summary()
                + ", " + buildBudget.summary()
                + ", " + postBuild.summary()
                + ", " + worldTlas.summary()
                + ", " + dispatch.summary()
                + "}";
    }

    RtCore.NativeFrameTiming snapshot() {
        return new RtCore.NativeFrameTiming(
                total.lastMicros,
                ingest.lastMicros,
                preBuild.lastMicros,
                buildBudget.lastMicros,
                postBuild.lastMicros,
                worldTlas.lastMicros,
                dispatch.lastMicros
        );
    }

    void maybeLogSmokeWindow() {
        if (!buildTelemetry.enabled()) {
            return;
        }
        long now = System.nanoTime();
        if (smokeWindowStartNanos == 0L) {
            smokeWindowStartNanos = now;
            rememberSmokeTotals();
            return;
        }
        if (now - smokeWindowStartNanos < 1_000_000_000L) {
            return;
        }
        buildTelemetry.aggregate(
                "nativeAcceptStages",
                "windowMs=" + (now - smokeWindowStartNanos) / 1_000_000L
                        + ", frames=" + Math.max(0L, total.samples - smokeLastSamples)
                        + ", totalMicros=" + delta(total.totalMicros, smokeLastTotalMicros)
                        + ", ingestMicros=" + delta(ingest.totalMicros, smokeLastIngestMicros)
                        + ", preBuildMicros=" + delta(preBuild.totalMicros, smokeLastPreBuildMicros)
                        + ", buildBudgetMicros=" + delta(buildBudget.totalMicros, smokeLastBuildBudgetMicros)
                        + ", postBuildMicros=" + delta(postBuild.totalMicros, smokeLastPostBuildMicros)
                        + ", worldTlasMicros=" + delta(worldTlas.totalMicros, smokeLastWorldTlasMicros)
                        + ", worldTlasDetail={scheduler="
                        + delta(worldTlasScheduler.totalMicros, smokeLastWorldTlasSchedulerMicros)
                        + ", bind=" + delta(worldTlasBind.totalMicros, smokeLastWorldTlasBindMicros) + "}"
                        + ", dispatchMicros=" + delta(dispatch.totalMicros, smokeLastDispatchMicros)
                        + ", detail={framePoll=" + delta(framePoll.totalMicros, smokeLastFramePollMicros)
                        + ", dynamicPipelineIngest=" + delta(dynamicPipelineIngest.totalMicros, smokeLastDynamicPipelineIngestMicros)
                        + ", dynamicBlasIngest=" + delta(dynamicBlasIngest.totalMicros, smokeLastDynamicBlasIngestMicros)
                        + ", sectionEnqueue=" + delta(sectionEnqueue.totalMicros, smokeLastSectionEnqueueMicros)
                        + ", descriptorPoll=" + delta(descriptorPoll.totalMicros, smokeLastDescriptorPollMicros)
                        + ", preBuildRetirement=" + delta(preBuildRetirement.totalMicros, smokeLastPreBuildRetirementMicros)
                        + ", sectionCompletionPump=" + delta(buildSectionCompletionPump.totalMicros, smokeLastBuildSectionCompletionPumpMicros)
                        + ", pendingBindCompletion=" + delta(pendingBindCompletion.totalMicros, smokeLastPendingBindCompletionMicros)
                        + ", deferredBindSubmit=" + delta(deferredBindSubmit.totalMicros, smokeLastDeferredBindSubmitMicros)
                        + ", textureCatchUp=" + delta(textureCatchUp.totalMicros, smokeLastTextureCatchUpMicros) + "}"
                        + ", preBuildDetail={stableBackpressure="
                        + delta(stableBackpressure.totalMicros, smokeLastStableBackpressureMicros)
                        + ", stableFastPath=" + delta(stableFastPath.totalMicros, smokeLastStableFastPathMicros)
                        + ", policy=" + delta(preBuildPolicy.totalMicros, smokeLastPreBuildPolicyMicros)
                        + ", dispatch=" + delta(preBuildDispatch.totalMicros, smokeLastPreBuildDispatchMicros)
                        + ", retry=" + delta(preBuildRetry.totalMicros, smokeLastPreBuildRetryMicros) + "}"
                        + ", buildBudgetDetail={deferPolicy=" + delta(buildDeferPolicy.totalMicros, smokeLastBuildDeferPolicyMicros)
                        + ", remainingBudget=" + delta(buildRemainingBudget.totalMicros, smokeLastBuildRemainingBudgetMicros)
                        + ", firstFront=" + delta(buildFirstFront.totalMicros, smokeLastBuildFirstFrontMicros)
                        + ", coverage=" + delta(buildCoverage.totalMicros, smokeLastBuildCoverageMicros)
                        + ", section=" + delta(buildSection.totalMicros, smokeLastBuildSectionMicros)
                        + ", postSectionPolicy=" + delta(buildPostSectionPolicy.totalMicros, smokeLastBuildPostSectionPolicyMicros)
                        + ", dynamic=" + delta(buildDynamic.totalMicros, smokeLastBuildDynamicMicros)
                        + ", postDynamicPolicy=" + delta(buildPostDynamicPolicy.totalMicros, smokeLastBuildPostDynamicPolicyMicros) + "}"
                        + ", dispatchDetail={readiness="
                        + delta(dispatchReadiness.totalMicros, smokeLastDispatchReadinessMicros)
                        + ", capacity=" + delta(dispatchCapacity.totalMicros, smokeLastDispatchCapacityMicros)
                        + ", descriptor=" + delta(dispatchDescriptor.totalMicros, smokeLastDispatchDescriptorMicros)
                        + ", readinessPolicy=" + delta(dispatchReadinessPolicy.totalMicros, smokeLastDispatchReadinessPolicyMicros)
                        + ", dynamicPolicy=" + delta(dispatchDynamicPolicy.totalMicros, smokeLastDispatchDynamicPolicyMicros)
                        + ", interactivePolicy=" + delta(dispatchInteractivePolicy.totalMicros, smokeLastDispatchInteractivePolicyMicros)
                        + ", pipeline=" + delta(dispatchPipeline.totalMicros, smokeLastDispatchPipelineMicros)
                        + ", recorders=" + delta(dispatchRecorders.totalMicros, smokeLastDispatchRecordersMicros) + "}"
                        + ", dispatchResults={attempts=" + delta(dispatchAttempts, smokeLastDispatchAttempts)
                        + ", capacityRejects=" + delta(dispatchCapacityRejects, smokeLastDispatchCapacityRejects)
                        + ", descriptorRejects=" + delta(dispatchDescriptorRejects, smokeLastDispatchDescriptorRejects)
                        + ", readinessRejects=" + delta(dispatchReadinessRejects, smokeLastDispatchReadinessRejects)
                        + ", dynamicRejects=" + delta(dispatchDynamicRejects, smokeLastDispatchDynamicRejects)
                        + ", interactiveRejects=" + delta(dispatchInteractiveRejects, smokeLastDispatchInteractiveRejects)
                        + ", pipelineNoSubmits=" + delta(dispatchPipelineNoSubmits, smokeLastDispatchPipelineNoSubmits)
                        + ", submissions=" + delta(dispatchSubmissions, smokeLastDispatchSubmissions) + "}"
                        + ", dynamicLaneShadow={current=" + dynamicShadowCurrent
                        + ", transformLagEligible=" + dynamicShadowTransformLagEligible
                        + ", topologyBlocked=" + dynamicShadowTopologyBlocked
                        + ", geometryBlocked=" + dynamicShadowGeometryBlocked
                        + ", noBoundWorld=" + dynamicShadowNoBoundWorld
                        + ", maxRevisionLag=" + dynamicShadowMaxRevisionLag + "}"
                        + ", committedFront={dispatches="
                        + delta(committedFrontDispatches, smokeLastCommittedFrontDispatches)
                        + ", decisions=" + committedFrontDecisionWindowSummary() + "}"
        );
        smokeWindowStartNanos = now;
        rememberSmokeTotals();
        dynamicShadowCurrent = 0L;
        dynamicShadowTransformLagEligible = 0L;
        dynamicShadowTopologyBlocked = 0L;
        dynamicShadowGeometryBlocked = 0L;
        dynamicShadowNoBoundWorld = 0L;
        dynamicShadowMaxRevisionLag = 0L;
    }

    void recordCommittedFrontDecision(RtCommittedFrontPolicy.Decision decision) {
        committedFrontDecisions[Objects.requireNonNull(decision, "decision").ordinal()]++;
    }

    void recordCommittedFrontDispatch() {
        committedFrontDispatches++;
    }

    private String committedFrontDecisionWindowSummary() {
        StringBuilder summary = new StringBuilder("{");
        RtCommittedFrontPolicy.Decision[] decisions = RtCommittedFrontPolicy.Decision.values();
        for (int index = 0; index < decisions.length; index++) {
            if (index > 0) {
                summary.append(',');
            }
            summary.append(decisions[index].name().toLowerCase())
                    .append('=')
                    .append(delta(committedFrontDecisions[index], smokeLastCommittedFrontDecisions[index]));
        }
        return summary.append('}').toString();
    }

    void recordDynamicLaneShadow(
            boolean hasBoundWorld,
            long boundRevision,
            long boundTopologyRevision,
            long boundGeometryRevision,
            long currentRevision,
            long currentTopologyRevision,
            long currentGeometryRevision
    ) {
        if (!hasBoundWorld || boundRevision < 0L) {
            dynamicShadowNoBoundWorld++;
            return;
        }
        if (boundGeometryRevision != currentGeometryRevision) {
            dynamicShadowGeometryBlocked++;
            return;
        }
        if (boundTopologyRevision != currentTopologyRevision) {
            dynamicShadowTopologyBlocked++;
            return;
        }
        long lag = Math.max(0L, currentRevision - boundRevision);
        dynamicShadowMaxRevisionLag = Math.max(dynamicShadowMaxRevisionLag, lag);
        if (lag == 0L) {
            dynamicShadowCurrent++;
        } else {
            dynamicShadowTransformLagEligible++;
        }
    }

    private void rememberSmokeTotals() {
        smokeLastSamples = total.samples;
        smokeLastTotalMicros = total.totalMicros;
        smokeLastIngestMicros = ingest.totalMicros;
        smokeLastPreBuildMicros = preBuild.totalMicros;
        smokeLastBuildBudgetMicros = buildBudget.totalMicros;
        smokeLastPostBuildMicros = postBuild.totalMicros;
        smokeLastWorldTlasMicros = worldTlas.totalMicros;
        smokeLastWorldTlasSchedulerMicros = worldTlasScheduler.totalMicros;
        smokeLastWorldTlasBindMicros = worldTlasBind.totalMicros;
        smokeLastDispatchMicros = dispatch.totalMicros;
        smokeLastFramePollMicros = framePoll.totalMicros;
        smokeLastDynamicPipelineIngestMicros = dynamicPipelineIngest.totalMicros;
        smokeLastDynamicBlasIngestMicros = dynamicBlasIngest.totalMicros;
        smokeLastSectionEnqueueMicros = sectionEnqueue.totalMicros;
        smokeLastDescriptorPollMicros = descriptorPoll.totalMicros;
        smokeLastPreBuildRetirementMicros = preBuildRetirement.totalMicros;
        smokeLastBuildSectionCompletionPumpMicros = buildSectionCompletionPump.totalMicros;
        smokeLastPendingBindCompletionMicros = pendingBindCompletion.totalMicros;
        smokeLastDeferredBindSubmitMicros = deferredBindSubmit.totalMicros;
        smokeLastTextureCatchUpMicros = textureCatchUp.totalMicros;
        smokeLastStableBackpressureMicros = stableBackpressure.totalMicros;
        smokeLastStableFastPathMicros = stableFastPath.totalMicros;
        smokeLastPreBuildPolicyMicros = preBuildPolicy.totalMicros;
        smokeLastPreBuildDispatchMicros = preBuildDispatch.totalMicros;
        smokeLastPreBuildRetryMicros = preBuildRetry.totalMicros;
        smokeLastBuildDeferPolicyMicros = buildDeferPolicy.totalMicros;
        smokeLastBuildRemainingBudgetMicros = buildRemainingBudget.totalMicros;
        smokeLastBuildFirstFrontMicros = buildFirstFront.totalMicros;
        smokeLastBuildCoverageMicros = buildCoverage.totalMicros;
        smokeLastBuildSectionMicros = buildSection.totalMicros;
        smokeLastBuildPostSectionPolicyMicros = buildPostSectionPolicy.totalMicros;
        smokeLastBuildDynamicMicros = buildDynamic.totalMicros;
        smokeLastBuildPostDynamicPolicyMicros = buildPostDynamicPolicy.totalMicros;
        smokeLastDispatchReadinessMicros = dispatchReadiness.totalMicros;
        smokeLastDispatchCapacityMicros = dispatchCapacity.totalMicros;
        smokeLastDispatchDescriptorMicros = dispatchDescriptor.totalMicros;
        smokeLastDispatchReadinessPolicyMicros = dispatchReadinessPolicy.totalMicros;
        smokeLastDispatchDynamicPolicyMicros = dispatchDynamicPolicy.totalMicros;
        smokeLastDispatchInteractivePolicyMicros = dispatchInteractivePolicy.totalMicros;
        smokeLastDispatchPipelineMicros = dispatchPipeline.totalMicros;
        smokeLastDispatchRecordersMicros = dispatchRecorders.totalMicros;
        smokeLastCommittedFrontDispatches = committedFrontDispatches;
        System.arraycopy(
                committedFrontDecisions,
                0,
                smokeLastCommittedFrontDecisions,
                0,
                committedFrontDecisions.length
        );
        smokeLastDispatchAttempts = dispatchAttempts;
        smokeLastDispatchCapacityRejects = dispatchCapacityRejects;
        smokeLastDispatchDescriptorRejects = dispatchDescriptorRejects;
        smokeLastDispatchReadinessRejects = dispatchReadinessRejects;
        smokeLastDispatchDynamicRejects = dispatchDynamicRejects;
        smokeLastDispatchInteractiveRejects = dispatchInteractiveRejects;
        smokeLastDispatchPipelineNoSubmits = dispatchPipelineNoSubmits;
        smokeLastDispatchSubmissions = dispatchSubmissions;
    }

    private static long delta(long current, long previous) {
        return Math.max(0L, current - previous);
    }
}

final class VulkanAcceptNanoTiming {
    private final String name;
    long samples;
    long lastMicros;
    long maxMicros;
    long totalMicros;
    private long frameMicros;

    String name() {
        return name;
    }

    VulkanAcceptNanoTiming(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    void record(long elapsedNanos) {
        long elapsedMicros = Math.max(0L, elapsedNanos / 1_000L);
        samples++;
        lastMicros = elapsedMicros;
        maxMicros = Math.max(maxMicros, elapsedMicros);
        totalMicros += elapsedMicros;
        frameMicros += elapsedMicros;
    }

    void resetFrameMicros() {
        frameMicros = 0L;
    }

    long frameMicros() {
        return frameMicros;
    }

    String summary() {
        return name + "{samples=" + samples
                + ", lastMicros=" + lastMicros
                + ", maxMicros=" + maxMicros
                + ", avgMicros=" + (samples == 0L ? 0L : totalMicros / samples)
                + "}";
    }
}

package top.ceroxe.rt.renderer.rt.device;

/**
 * Monotonic diagnostics for asynchronous world/material descriptor-bind lanes.
 */
final class RtWorldSceneBindStatistics {
    private long worldSubmissions;
    private long worldCompletions;
    private long worldPollsNotReady;
    private long worldDescriptorDeferrals;
    private long worldReplacements;
    private long materialSubmissions;
    private long materialCompletions;
    private long materialPollsNotReady;
    private long materialDescriptorDeferrals;
    private long materialSkippedNoBoundWorld;
    private long materialSkippedPendingWorld;
    private long materialSkippedUnchanged;
    private long deferredWorldBinds;
    private long deferredWorldDeferrals;
    private long submittedDeferredWorldBinds;
    private long discardedDeferredWorldBinds;
    private long coverageProtectedDiscards;
    private long streamingIntervalDeferrals;

    void worldSubmitted() {
        worldSubmissions++;
    }

    void worldCompleted() {
        worldCompletions++;
    }

    void worldPollNotReady() {
        worldPollsNotReady++;
    }

    void worldDescriptorDeferred() {
        worldDescriptorDeferrals++;
    }

    void worldReplaced() {
        worldReplacements++;
    }

    void materialSubmitted() {
        materialSubmissions++;
    }

    void materialCompleted() {
        materialCompletions++;
    }

    void materialPollNotReady() {
        materialPollsNotReady++;
    }

    void materialDescriptorDeferred() {
        materialDescriptorDeferrals++;
    }

    void materialSkippedNoBoundWorld() {
        materialSkippedNoBoundWorld++;
    }

    void materialSkippedPendingWorld() {
        materialSkippedPendingWorld++;
    }

    void materialSkippedUnchanged() {
        materialSkippedUnchanged++;
    }

    void deferredWorldCreated() {
        deferredWorldBinds++;
    }

    void deferredWorldDeferred() {
        deferredWorldDeferrals++;
    }

    void deferredWorldSubmitted() {
        submittedDeferredWorldBinds++;
    }

    void deferredWorldDiscarded() {
        discardedDeferredWorldBinds++;
    }

    void coverageProtectedDiscarded() {
        coverageProtectedDiscards++;
    }

    void streamingIntervalDeferred() {
        streamingIntervalDeferrals++;
    }

    long nextWorldPollOrdinal() {
        return Math.incrementExact(worldPollsNotReady);
    }

    String summary() {
        return "asyncSceneBindSubmissions=" + worldSubmissions
                + ", asyncSceneBindCompletions=" + worldCompletions
                + ", asyncSceneBindPollsNotReady=" + worldPollsNotReady
                + ", asyncSceneBindDescriptorDeferrals=" + worldDescriptorDeferrals
                + ", asyncSceneBindReplacements=" + worldReplacements
                + ", asyncMaterialOnlySubmissions=" + materialSubmissions
                + ", asyncMaterialOnlyCompletions=" + materialCompletions
                + ", asyncMaterialOnlyPollsNotReady=" + materialPollsNotReady
                + ", asyncMaterialOnlyDescriptorDeferrals=" + materialDescriptorDeferrals
                + ", asyncMaterialOnlySkippedNoBoundWorld=" + materialSkippedNoBoundWorld
                + ", asyncMaterialOnlySkippedPendingWorldBind=" + materialSkippedPendingWorld
                + ", asyncMaterialOnlySkippedUnchanged=" + materialSkippedUnchanged
                + ", deferredWorldSceneBinds=" + deferredWorldBinds
                + ", deferredWorldSceneBindDeferrals=" + deferredWorldDeferrals
                + ", submittedDeferredWorldSceneBinds=" + submittedDeferredWorldBinds
                + ", discardedDeferredWorldSceneBinds=" + discardedDeferredWorldBinds
                + ", coverageProtectedWorldSceneDiscards=" + coverageProtectedDiscards
                + ", streamingSceneBindIntervalDeferrals=" + streamingIntervalDeferrals;
    }
}

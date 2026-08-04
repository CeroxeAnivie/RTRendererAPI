package top.ceroxe.rt.renderer.nvidia;

/** Read-only operational evidence exported by the optional NVIDIA Streamline integration. */
public final class NvidiaStreamlineDiagnostics {
    private NvidiaStreamlineDiagnostics() {
    }

    /**
     * Returns cumulative DLSS-G presentation evidence for the currently open Streamline session.
     *
     * <p>A proxy present is one application-rendered frame routed through the manual-hooking
     * entry point. Generated-frame counts come only from {@code slDLSSGGetState}; they are never
     * inferred from a successful {@code vkQueuePresentKHR} return code.</p>
     *
     * @return immutable cumulative evidence from the currently open native Streamline runtime
     */
    public static FrameGenerationSnapshot frameGenerationSnapshot() {
        NvidiaNativeBridge.Probe probe = NvidiaNativeBridge.probe();
        if (!probe.loaded()) {
            throw new IllegalStateException("NVIDIA native bridge is unavailable: " + probe.reason());
        }
        NvidiaStreamlineFrameGenerationRuntime.Stats stats =
                NvidiaStreamlineFrameGenerationRuntime.stats();
        return new FrameGenerationSnapshot(
                stats.proxyPresentCalls(), stats.stateSamples(), stats.framesActuallyPresented(),
                stats.generatedFramesActuallyPresented(), stats.lastFramesActuallyPresented(),
                stats.maxFramesToGenerate(), stats.status(), stats.stateQueryFailures(),
                stats.maxGeneratedFramesInSample(), stats.lastRequestedGeneratedFrames(),
                stats.configuredGeneratedFrames(), stats.generationRequestMisses(),
                stats.stateQueryCalls(), stats.firstProxyPresentSequence(),
                stats.lastProxyPresentSequence(), stats.lastGeneratedObservationSequence(),
                stats.resetEpoch(), stats.latestQuerySucceeded()
        );
    }

    /**
     * Immutable cumulative DLSS-G cadence and runtime-state evidence.
     *
     * @param proxyPresentCalls number of application presents routed through the proxy
     * @param stateSamples number of non-empty authoritative DLSS-G delivery samples
     * @param framesActuallyPresented total frames Streamline reports as actually presented
     * @param generatedFramesActuallyPresented presented frames beyond the application-rendered frames
     * @param lastFramesActuallyPresented frames reported by the latest non-empty state sample
     * @param maxFramesToGenerate current maximum generated-frame count advertised by Streamline
     * @param status native DLSS-G runtime status value from the most recent sample
     * @param stateQueryFailures cumulative failed authoritative state queries
     * @param maxGeneratedFramesInSample largest generated-frame count observed in one sample
     * @param lastRequestedGeneratedFrames generated-frame count passed by the last application present
     * @param configuredGeneratedFrames generated-frame count currently enabled in Streamline;
     *                                 zero after generation is configured off
     * @param generationRequestMisses generation requests converted to native presents by a contract miss
     * @param stateQueryCalls all authoritative DLSS-G state-query attempts
     * @param firstProxyPresentSequence first application sequence presented in the current reset epoch
     * @param lastProxyPresentSequence latest application sequence presented in the current reset epoch
     * @param lastGeneratedObservationSequence application sequence whose state query most recently
     *                                         observed nonzero generated delivery
     * @param resetEpoch native tracker reset generation
     * @param latestQuerySucceeded whether the most recent authoritative state query succeeded
     */
    public record FrameGenerationSnapshot(
            long proxyPresentCalls,
            long stateSamples,
            long framesActuallyPresented,
            long generatedFramesActuallyPresented,
            int lastFramesActuallyPresented,
            int maxFramesToGenerate,
            int status,
            long stateQueryFailures,
            int maxGeneratedFramesInSample,
            int lastRequestedGeneratedFrames,
            int configuredGeneratedFrames,
            long generationRequestMisses,
            long stateQueryCalls,
            long firstProxyPresentSequence,
            long lastProxyPresentSequence,
            long lastGeneratedObservationSequence,
            long resetEpoch,
            boolean latestQuerySucceeded
    ) {
        /**
         * Reports whether the latest authoritative state query succeeded.
         *
         * @return {@code true} when the latest query produced valid state
         */
        public boolean stateQueriesHealthy() {
            return latestQuerySucceeded;
        }

        /**
         * Reports runtime activation proven by at least one actually presented generated frame.
         *
         * @return {@code true} when the runtime is healthy and generated output reached presentation
         */
        public boolean active() {
            return latestQuerySucceeded && status == 0 && generatedFramesActuallyPresented > 0L;
        }
    }
}

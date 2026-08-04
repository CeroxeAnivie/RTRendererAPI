package top.ceroxe.rt.renderer.nvidia;

import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VK10;

import java.util.Set;

/** Deterministic present/recreate failure injection without invoking a native swapchain. */
public final class NvidiaStreamlinePresentCircuitBreakerSelfTest {
    private NvidiaStreamlinePresentCircuitBreakerSelfTest() {
    }

    public static void main(String[] args) {
        successfulPresentKeepsGenerationArmed();
        recreateResultsKeepGenerationArmed();
        failedPresentPermanentlyFallsBackToNativePresentation();
        thrownProxyFailurePermanentlyFallsBackToNativePresentation();
        disabledConfigurationNeverArmsGeneration();
        standaloneLowLatencyOwnsThePresentProxy();
        System.out.println("NvidiaStreamlinePresentCircuitBreakerSelfTest passed");
    }

    private static void recreateResultsKeepGenerationArmed() {
        NvidiaStreamlinePresentCircuitBreaker breaker = new NvidiaStreamlinePresentCircuitBreaker(2);
        require(!breaker.observeResult(KHRSwapchain.VK_SUBOPTIMAL_KHR, 2, 1L),
                "suboptimal WSI result incorrectly requested permanent fallback");
        require(!breaker.observeResult(KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR, 2, 2L),
                "out-of-date WSI result incorrectly requested permanent fallback");
        require(breaker.enabled() && breaker.generatedFramesForPresent() == 2,
                "ordinary swapchain recreation permanently disabled generation");
    }

    private static void successfulPresentKeepsGenerationArmed() {
        NvidiaStreamlinePresentCircuitBreaker breaker = new NvidiaStreamlinePresentCircuitBreaker(-3);
        int attempted = breaker.generatedFramesForPresent();
        require(!breaker.observeResult(VK10.VK_SUCCESS, attempted, 3L),
                "successful present incorrectly requested a proxy rebuild");
        require(breaker.enabled() && breaker.generatedFramesForPresent() == -3,
                "successful adaptive MFG present unexpectedly tripped the circuit breaker");
    }

    private static void failedPresentPermanentlyFallsBackToNativePresentation() {
        NvidiaStreamlinePresentCircuitBreaker breaker = new NvidiaStreamlinePresentCircuitBreaker(1);
        int attempted = breaker.generatedFramesForPresent();
        require(breaker.observeResult(KHRSurface.VK_ERROR_SURFACE_LOST_KHR, attempted, 4L),
                "first failed present did not request a proxy rebuild");
        require(!breaker.enabled() && breaker.generatedFramesForPresent() == 0,
                "fatal proxy present did not disable generated frames before recreation");
        NvidiaStreamlinePresentCircuitBreaker.FailureSnapshot failure =
                breaker.failureSnapshot().orElseThrow();
        require(failure.kind() == NvidiaStreamlinePresentCircuitBreaker.FailureKind.WSI_RESULT
                        && failure.frameSequence() == 4L
                        && failure.code().equals("VK_RESULT_" + KHRSurface.VK_ERROR_SURFACE_LOST_KHR),
                "fatal WSI failure did not retain typed immutable evidence");
        require(!breaker.observeResult(VK10.VK_SUCCESS, breaker.generatedFramesForPresent(), 5L),
                "disabled generation repeatedly requested proxy rebuilds");
        require(!breaker.enabled() && breaker.generatedFramesForPresent() == 0,
                "swapchain recreation incorrectly resurrected generation in the failed session");
        require(breaker.failureSnapshot().orElseThrow().equals(failure),
                "later observations replaced the first terminal presentation failure");
    }

    private static void thrownProxyFailurePermanentlyFallsBackToNativePresentation() {
        NvidiaStreamlinePresentCircuitBreaker breaker = new NvidiaStreamlinePresentCircuitBreaker(3);
        require(breaker.observeFailure(
                        breaker.generatedFramesForPresent(), 6L,
                        new IllegalStateException("injected proxy failure")
                ),
                "first proxy exception did not request a proxy rebuild");
        require(!breaker.enabled() && breaker.generatedFramesForPresent() == 0,
                "proxy exception did not atomically select native presentation");
        NvidiaStreamlinePresentCircuitBreaker.FailureSnapshot failure =
                breaker.failureSnapshot().orElseThrow();
        require(failure.kind() == NvidiaStreamlinePresentCircuitBreaker.FailureKind.THROWN_FAILURE
                        && failure.frameSequence() == 6L
                        && failure.code().equals("IllegalStateException")
                        && failure.reason().contains("injected proxy failure"),
                "proxy exception did not retain bounded typed evidence");
    }

    private static void disabledConfigurationNeverArmsGeneration() {
        NvidiaStreamlinePresentCircuitBreaker breaker = new NvidiaStreamlinePresentCircuitBreaker(0);
        require(!breaker.observeResult(
                        KHRSwapchain.VK_SUBOPTIMAL_KHR, breaker.generatedFramesForPresent(), 7L
                ), "disabled configuration requested a proxy rebuild");
        require(!breaker.enabled() && breaker.generatedFramesForPresent() == 0,
                "disabled frame generation advertised an executable present request");
        require(breaker.failureSnapshot().isEmpty(),
                "disabled frame generation fabricated a presentation failure");
    }

    private static void standaloneLowLatencyOwnsThePresentProxy() {
        require(NvidiaFeatureSession.streamlinePresentProxyRequired(Set.of(
                        NvidiaStreamlineRuntime.Feature.REFLEX,
                        NvidiaStreamlineRuntime.Feature.PCL
                )), "standalone Reflex/PCL did not claim the Streamline present proxy");
        require(NvidiaFeatureSession.streamlinePresentProxyRequired(Set.of(
                        NvidiaStreamlineRuntime.Feature.DLSS_FRAME_GENERATION
                )), "DLSS-G no longer claimed its required Streamline present proxy");
        require(!NvidiaFeatureSession.streamlinePresentProxyRequired(Set.of(
                        NvidiaStreamlineRuntime.Feature.REFLEX
                )), "incomplete Reflex/PCL binding unexpectedly claimed presentation ownership");
        require(!NvidiaFeatureSession.streamlinePresentProxyRequired(Set.of(
                        NvidiaStreamlineRuntime.Feature.DLSS
                )), "reconstruction-only Streamline unexpectedly claimed presentation ownership");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

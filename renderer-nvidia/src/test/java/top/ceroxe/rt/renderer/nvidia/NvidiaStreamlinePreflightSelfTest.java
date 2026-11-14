package top.ceroxe.rt.renderer.nvidia;

import java.util.Map;
import java.util.Set;

/** Adversarial parser contract for untrusted Streamline SDK preflight reports. */
public final class NvidiaStreamlinePreflightSelfTest {
    private NvidiaStreamlinePreflightSelfTest() {
    }

    public static void main(String[] arguments) {
        acceptsCompleteRequirements();
        preservesSdkFailureWithoutPartialCapabilities();
        rejectsMalformedOrPartialReports();
        rejectsInvalidValueObjects();
        preservesDeviceHandoffFailures();
        roundTripsExecutionFeatureMasks();
        System.out.println("NvidiaStreamlinePreflightSelfTest passed");
    }

    private static void acceptsCompleteRequirements() {
        NvidiaStreamlineRuntime.Preflight preflight = NvidiaStreamlineRuntime.Preflight.parse(
                "ready\nslInit=0\n"
                        + "DLSS\t0\t1\t2\t0\tVK_KHR_surface\tVK_KHR_swapchain\t"
                        + "descriptorIndexing\tdynamicRendering\t2\t12\t0\n"
        );
        require(preflight.ready(), "valid preflight must remain ready");
        NvidiaStreamlineRuntime.Requirements requirements = preflight.requirements()
                .get(NvidiaStreamlineRuntime.Feature.DLSS);
        require(requirements != null, "valid preflight lost its feature requirements");
        require(requirements.queues().additionalGraphicsQueues() == 1
                        && requirements.queues().additionalComputeQueues() == 2
                        && requirements.queues().additionalOpticalFlowQueues() == 0,
                "queue requirements were decoded in the wrong order");
        require(requirements.instanceExtensions().contains("VK_KHR_surface")
                        && requirements.deviceExtensions().contains("VK_KHR_swapchain"),
                "extension requirements were not preserved");
        require(requirements.streamlineVersion().equals(new NvidiaStreamlineRuntime.Version(2, 12, 0)),
                "Streamline plugin version was not preserved");
        expect(UnsupportedOperationException.class,
                () -> preflight.requirements().put(NvidiaStreamlineRuntime.Feature.NIS, requirements));
        expect(UnsupportedOperationException.class,
                () -> requirements.deviceExtensions().add("VK_EXT_mutation"));
    }

    private static void preservesSdkFailureWithoutPartialCapabilities() {
        for (String reason : new String[]{
                "failed to load sl.interposer.dll",
                "slInit=eErrorDriverOutOfDate",
                "slIsFeatureSupported=eErrorAdapterNotSupported",
                "slSetVulkanInfo=eErrorMissingOrInvalidAPI"
        }) {
            NvidiaStreamlineRuntime.Preflight failure =
                    NvidiaStreamlineRuntime.Preflight.parse("failed\n" + reason + "\n");
            require(!failure.ready(), "SDK failure was promoted to ready: " + reason);
            require(failure.requirements().isEmpty(), "SDK failure leaked partial requirements: " + reason);
            require(failure.reason().equals(reason), "SDK failure reason was not preserved: " + reason);
        }
    }

    private static void rejectsMalformedOrPartialReports() {
        expect(NullPointerException.class, () -> NvidiaStreamlineRuntime.Preflight.parse(null));
        expect(IllegalStateException.class, () -> NvidiaStreamlineRuntime.Preflight.parse("ready"));
        expect(IllegalStateException.class,
                () -> NvidiaStreamlineRuntime.Preflight.parse("unknown\nreason\n"));
        expect(IllegalStateException.class,
                () -> NvidiaStreamlineRuntime.Preflight.parse("ready\nslInit=0\nDLSS\t0\t1\n"));
        expect(IllegalStateException.class,
                () -> NvidiaStreamlineRuntime.Preflight.parse(
                        "ready\nslInit=0\nDLSS\t0\t-1\t0\t0\t\t\t\t\t2\t12\t0\n"));
        expect(IllegalStateException.class,
                () -> NvidiaStreamlineRuntime.Preflight.parse(
                        "ready\nslInit=0\nUNKNOWN\t0\t0\t0\t0\t\t\t\t\t2\t12\t0\n"));
        expect(IllegalStateException.class,
                () -> NvidiaStreamlineRuntime.Preflight.parse(
                        "ready\nslInit=0\nDLSS\t7\t0\t0\t0\t\t\t\t\t2\t12\t0\n"));
        expect(IllegalStateException.class,
                () -> NvidiaStreamlineRuntime.Preflight.parse(
                        "ready\nslInit=0\nDLSS\t0\t0\t0\t0\t\tVK_A,,VK_B\t\t2\t12\t0\n"));
        expect(IllegalStateException.class,
                () -> NvidiaStreamlineRuntime.Preflight.parse(
                        "ready\nslInit=0\nDLSS\t0\t0\t0\t0\t\t\t\t\t2\t12\t0\n"
                                + "DLSS\t0\t0\t0\t0\t\t\t\t\t2\t12\t0\n"));
        expect(IllegalStateException.class,
                () -> NvidiaStreamlineRuntime.Preflight.parse(
                        "ready\nslInit=0\nDLSS\t0\t0\t0\t0\t\t\t\t\t-1\t12\t0\n"));
        expect(IllegalStateException.class,
                () -> NvidiaStreamlineRuntime.Preflight.parse(
                        "ready\nslInit=0\nDLSS\t0\t0\t0\t0\t\t\t\t\t2\t12\t-1\n"));
        expect(IllegalArgumentException.class,
                () -> NvidiaStreamlineRuntime.Preflight.parse(
                        "failed\nslInit=eErrorDriverOutOfDate\n"
                                + "DLSS\t0\t0\t0\t0\t\t\t\t\t2\t12\t0\n"));
    }

    private static void rejectsInvalidValueObjects() {
        expect(IllegalArgumentException.class,
                () -> new NvidiaNativeBridge.Probe(false, NvidiaNativeBridge.NRD, "unloaded"));
        expect(IllegalArgumentException.class,
                () -> new NvidiaStreamlineRuntime.Preflight(false, "failed", Map.of(
                        NvidiaStreamlineRuntime.Feature.DLSS,
                        new NvidiaStreamlineRuntime.Requirements(
                                java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), java.util.Set.of(),
                                top.ceroxe.rt.renderer.feature.VulkanQueueRequirements.NONE,
                                new NvidiaStreamlineRuntime.Version(2, 12, 0)
                        )
                )));
        expect(IllegalArgumentException.class,
                () -> new NvidiaStreamlineRuntime.Version(-1, 0, 0));
        expect(IllegalArgumentException.class,
                () -> new NvidiaStreamlineRuntime.QueueRanges(-1, 0, 0, 0, 0, 0, false));
    }

    private static void preservesDeviceHandoffFailures() {
        for (int result : new int[]{-1, -4, -5, -8, -10}) {
            IllegalStateException failure = NvidiaStreamlineRuntime.handoffFailure(
                    result, "synthetic SDK failure " + result
            );
            require(failure.getMessage().contains("result=" + result)
                            && failure.getMessage().contains("synthetic SDK failure " + result),
                    "handoff failure lost SDK result or diagnostic");
        }
        require(NvidiaStreamlineRuntime.handoffFailure(-1, null).getMessage().contains("no diagnostic"),
                "missing handoff diagnostic was not normalized");
        expect(IllegalArgumentException.class,
                () -> NvidiaStreamlineRuntime.handoffFailure(0, "success"));
    }

    private static void roundTripsExecutionFeatureMasks() {
        Set<NvidiaStreamlineRuntime.Feature> features = Set.of(
                NvidiaStreamlineRuntime.Feature.DLSS,
                NvidiaStreamlineRuntime.Feature.DLSS_FRAME_GENERATION,
                NvidiaStreamlineRuntime.Feature.REFLEX,
                NvidiaStreamlineRuntime.Feature.PCL
        );
        int mask = NvidiaStreamlineRuntime.Feature.mask(features);
        require(NvidiaStreamlineRuntime.Feature.fromMask(mask).equals(features),
                "device-selected Streamline feature mask did not round-trip");
        require(NvidiaStreamlineRuntime.Feature.fromMask(0).isEmpty(),
                "empty device-selected mask must stay empty");
        expect(IllegalStateException.class,
                () -> NvidiaStreamlineRuntime.Feature.fromMask(1 << 12));
    }

    private static <T extends Throwable> void expect(Class<T> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + failure, failure);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.LowLatencyOptions;
import top.ceroxe.rt.renderer.api.RayTracingOptimizationOptions;
import top.ceroxe.rt.renderer.api.RendererConfig;
import top.ceroxe.rt.renderer.api.RendererDeviceException;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;
import top.ceroxe.rt.renderer.api.RendererPreset;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Entry;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Feature;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Status;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/** Deterministic provider owner-isolation tests; no Vulkan device or native SDK is required. */
public final class NvidiaProviderFaultIsolationSelfTest {
    private static final int ALL_NATIVE =
            NvidiaNativeBridge.NRD | NvidiaNativeBridge.RTX_MEMORY_UTILITY;
    private static final NvidiaNativeBridge.Probe FULL_PROBE =
            new NvidiaNativeBridge.Probe(true, ALL_NATIVE, "test bridge supports all native owners");

    private NvidiaProviderFaultIsolationSelfTest() {
    }

    public static void main(String[] arguments) {
        runAll();
        System.out.println("NvidiaProviderFaultIsolationSelfTest passed");
    }

    static void runAll() {
        preferredNativeFailureDoesNotBreakRequiredSibling();
        preferredStreamlineFailureDoesNotOverwriteNativeSiblings();
        requiredAndDeviceFailuresRemainStrict();
        nullNativeHandleNeverBecomesAnOwner();
        providerRollbackPreservesPrimaryFailure();
        closeAggregatesFailuresAndIsIdempotent();
        fallbackOnlySnapshotContainsNoPhantomOwner();
        capabilitiesFollowActuallyOpenedOwners();
    }

    private static void preferredNativeFailureDoesNotBreakRequiredSibling() {
        FakeOperations nrdFailure = new FakeOperations();
        nrdFailure.nrdOpenFailure = new IllegalStateException("NRD unavailable");
        nrdFailure.rtxmuHandle = 22L;
        NvidiaNativeFeatureSessions rtxmuOnly = NvidiaNativeFeatureSessions.open(
                FULL_PROBE,
                RendererFeaturePreference.PREFERRED,
                RendererFeaturePreference.REQUIRED,
                nrdFailure
        );
        require(!rtxmuOnly.nrdAvailable(), "failed preferred NRD must not own a handle");
        require(rtxmuOnly.nrdOpenFailure() == nrdFailure.nrdOpenFailure,
                "preferred NRD failure must remain available for diagnostics");
        require(rtxmuOnly.rtxmuAvailable() && rtxmuOnly.rtxmuHandle() == 22L,
                "required RTXMU must still open after preferred NRD fails");
        rtxmuOnly.close();
        require(nrdFailure.closeOrder.equals(List.of("RTXMU:22")),
                "only the actually opened RTXMU owner may close");

        FakeOperations rtxmuFailure = new FakeOperations();
        rtxmuFailure.nrdHandle = 11L;
        rtxmuFailure.rtxmuOpenFailure = new IllegalStateException("RTXMU unavailable");
        NvidiaNativeFeatureSessions nrdOnly = NvidiaNativeFeatureSessions.open(
                FULL_PROBE,
                RendererFeaturePreference.REQUIRED,
                RendererFeaturePreference.PREFERRED,
                rtxmuFailure
        );
        require(nrdOnly.nrdAvailable() && nrdOnly.nrdHandle() == 11L,
                "required NRD must remain owned after preferred RTXMU fails");
        require(!nrdOnly.rtxmuAvailable()
                        && nrdOnly.rtxmuOpenFailure() == rtxmuFailure.rtxmuOpenFailure,
                "preferred RTXMU failure must not fabricate an owner");
        nrdOnly.close();
        require(rtxmuFailure.closeOrder.equals(List.of("NRD:11")),
                "only the actually opened NRD owner may close");
    }

    private static void preferredStreamlineFailureDoesNotOverwriteNativeSiblings() {
        RendererConfig configuration = configuration(
                RendererFeaturePreference.REQUIRED,
                RendererFeaturePreference.REQUIRED,
                RendererFeaturePreference.PREFERRED,
                RendererFeaturePreference.PREFERRED,
                RendererFeaturePreference.PREFERRED
        );
        EnumMap<Feature, Entry> active = optimisticVendorCapabilities();

        NvidiaVulkanFeatureProvider.handleStreamlineOpenFailure(
                active, configuration, new LinkageError("Streamline plugin missing")
        );
        FakeOperations operations = new FakeOperations();
        operations.nrdHandle = 71L;
        operations.rtxmuHandle = 72L;
        NvidiaNativeFeatureSessions nativeSiblings = NvidiaNativeFeatureSessions.open(
                FULL_PROBE,
                RendererFeaturePreference.REQUIRED,
                RendererFeaturePreference.REQUIRED,
                operations
        );
        NvidiaVulkanFeatureProvider.applyNativeOpenOutcomes(
                active, configuration, nativeSiblings
        );

        requireStatus(active, Feature.DENOISING, Status.AVAILABLE);
        requireStatus(active, Feature.MEMORY_OPTIMIZATION, Status.AVAILABLE);
        requireStatus(active, Feature.FRAME_RECONSTRUCTION, Status.FALLBACK_PENDING);
        requireStatus(active, Feature.FRAME_GENERATION, Status.FALLBACK_PENDING);
        requireStatus(active, Feature.LOW_LATENCY, Status.FALLBACK_PENDING);
        nativeSiblings.close();
        require(operations.closeOrder.equals(List.of("RTXMU:72", "NRD:71")),
                "required native siblings must retain independent ownership after SL fallback");
    }

    private static void requiredAndDeviceFailuresRemainStrict() {
        FakeOperations requiredNrd = new FakeOperations();
        RuntimeException nrdFailure = new IllegalStateException("required NRD failed");
        requiredNrd.nrdOpenFailure = nrdFailure;
        Throwable observedNrd = expectFailure(() -> NvidiaNativeFeatureSessions.open(
                FULL_PROBE,
                RendererFeaturePreference.REQUIRED,
                RendererFeaturePreference.PREFERRED,
                requiredNrd
        ));
        require(observedNrd == nrdFailure, "required NRD failure must escape unchanged");
        require(requiredNrd.rtxmuOpenCalls == 0,
                "later siblings must not open after a required owner fails");

        FakeOperations requiredRtxmu = new FakeOperations();
        requiredRtxmu.nrdHandle = 31L;
        LinkageError rtxmuFailure = new LinkageError("required RTXMU linkage failed");
        requiredRtxmu.rtxmuOpenFailure = rtxmuFailure;
        Throwable observedRtxmu = expectFailure(() -> NvidiaNativeFeatureSessions.open(
                FULL_PROBE,
                RendererFeaturePreference.PREFERRED,
                RendererFeaturePreference.REQUIRED,
                requiredRtxmu
        ));
        require(observedRtxmu == rtxmuFailure, "required RTXMU failure must escape unchanged");
        require(requiredRtxmu.closeOrder.equals(List.of("NRD:31")),
                "required RTXMU failure must roll back the earlier NRD owner");

        FakeOperations deviceFailure = new FakeOperations();
        deviceFailure.nrdHandle = 41L;
        RendererDeviceException terminal = deviceFailure("preferred RTXMU lost the device");
        deviceFailure.rtxmuOpenFailure = terminal;
        Throwable observedDevice = expectFailure(() -> NvidiaNativeFeatureSessions.open(
                FULL_PROBE,
                RendererFeaturePreference.PREFERRED,
                RendererFeaturePreference.PREFERRED,
                deviceFailure
        ));
        require(observedDevice == terminal,
                "device failures must remain strict even for preferred owners");
        require(deviceFailure.closeOrder.equals(List.of("NRD:41")),
                "device failure must roll back every earlier owner");

        RendererConfig requiredStreamline = configuration(
                RendererFeaturePreference.DISABLED,
                RendererFeaturePreference.DISABLED,
                RendererFeaturePreference.REQUIRED,
                RendererFeaturePreference.DISABLED,
                RendererFeaturePreference.DISABLED
        );
        RuntimeException streamlineFailure = new IllegalStateException("required DLSS failed");
        Throwable observedStreamline = expectFailure(() ->
                NvidiaVulkanFeatureProvider.handleStreamlineOpenFailure(
                        new EnumMap<>(Feature.class), requiredStreamline, streamlineFailure
                )
        );
        require(observedStreamline == streamlineFailure,
                "required Streamline failure must escape unchanged");

        RendererConfig preferredStreamline = configuration(
                RendererFeaturePreference.DISABLED,
                RendererFeaturePreference.DISABLED,
                RendererFeaturePreference.PREFERRED,
                RendererFeaturePreference.DISABLED,
                RendererFeaturePreference.DISABLED
        );
        RendererDeviceException streamlineDeviceFailure = deviceFailure("Streamline lost device");
        Throwable observedStreamlineDevice = expectFailure(() ->
                NvidiaVulkanFeatureProvider.handleStreamlineOpenFailure(
                        new EnumMap<>(Feature.class),
                        preferredStreamline,
                        streamlineDeviceFailure
                )
        );
        require(observedStreamlineDevice == streamlineDeviceFailure,
                "preferred Streamline must never swallow a device failure");
    }

    private static void closeAggregatesFailuresAndIsIdempotent() {
        FakeOperations operations = new FakeOperations();
        operations.nrdHandle = 51L;
        operations.rtxmuHandle = 52L;
        RuntimeException rtxmuCloseFailure = new IllegalStateException("RTXMU close failed");
        LinkageError nrdCloseFailure = new LinkageError("NRD close failed");
        operations.rtxmuCloseFailure = rtxmuCloseFailure;
        operations.nrdCloseFailure = nrdCloseFailure;
        NvidiaNativeFeatureSessions sessions = NvidiaNativeFeatureSessions.open(
                FULL_PROBE,
                RendererFeaturePreference.PREFERRED,
                RendererFeaturePreference.PREFERRED,
                operations
        );

        Throwable observed = expectFailure(sessions::close);
        require(observed == rtxmuCloseFailure,
                "reverse-owner close failure must remain the primary exception");
        require(observed.getSuppressed().length == 1
                        && observed.getSuppressed()[0] == nrdCloseFailure,
                "the second close failure must be retained as suppressed evidence");
        require(operations.closeOrder.equals(List.of("RTXMU:52", "NRD:51")),
                "owners must close in reverse construction order");

        sessions.close();
        require(operations.closeOrder.equals(List.of("RTXMU:52", "NRD:51")),
                "repeated close after a failure must not release either handle twice");
        require(!sessions.nrdAvailable() && !sessions.rtxmuAvailable(),
                "closed owners must never remain observable as available");
    }

    private static void nullNativeHandleNeverBecomesAnOwner() {
        FakeOperations preferred = new FakeOperations();
        NvidiaNativeFeatureSessions fallback = NvidiaNativeFeatureSessions.open(
                FULL_PROBE,
                RendererFeaturePreference.PREFERRED,
                RendererFeaturePreference.DISABLED,
                preferred
        );
        require(!fallback.nrdAvailable() && fallback.nrdOpenFailure() != null,
                "a preferred null native handle must become a diagnosed open failure");
        fallback.close();
        require(preferred.closeOrder.isEmpty(),
                "a null native handle must never be passed to close");

        FakeOperations required = new FakeOperations();
        Throwable failure = expectFailure(() -> NvidiaNativeFeatureSessions.open(
                FULL_PROBE,
                RendererFeaturePreference.REQUIRED,
                RendererFeaturePreference.DISABLED,
                required
        ));
        require(failure instanceof IllegalStateException
                        && failure.getMessage().contains("null handle"),
                "a required null native handle must reject provider initialization");
    }

    private static void providerRollbackPreservesPrimaryFailure() {
        RuntimeException openFailure = new IllegalStateException("provider open failed");
        OutOfMemoryError closeFailure = new OutOfMemoryError("owner close exhausted host memory");
        NvidiaVulkanFeatureProvider.closeSuppressing(
                () -> {
                    throw closeFailure;
                },
                openFailure
        );
        require(openFailure.getSuppressed().length == 1
                        && openFailure.getSuppressed()[0] == closeFailure,
                "provider rollback must preserve open failure as primary and close OOM as evidence");
    }

    private static void fallbackOnlySnapshotContainsNoPhantomOwner() {
        RendererConfig configuration = configuration(
                RendererFeaturePreference.PREFERRED,
                RendererFeaturePreference.PREFERRED,
                RendererFeaturePreference.PREFERRED,
                RendererFeaturePreference.PREFERRED,
                RendererFeaturePreference.PREFERRED
        );
        FakeOperations operations = new FakeOperations();
        operations.nrdOpenFailure = new IllegalStateException("NRD unavailable");
        operations.rtxmuOpenFailure = new IllegalStateException("RTXMU unavailable");
        NvidiaNativeFeatureSessions sessions = NvidiaNativeFeatureSessions.open(
                FULL_PROBE,
                RendererFeaturePreference.PREFERRED,
                RendererFeaturePreference.PREFERRED,
                operations
        );
        EnumMap<Feature, Entry> active = optimisticVendorCapabilities();
        NvidiaVulkanFeatureProvider.applyNativeOpenOutcomes(active, configuration, sessions);
        NvidiaVulkanFeatureProvider.handleStreamlineOpenFailure(
                active, configuration, new IllegalStateException("Streamline unavailable")
        );

        require(sessions.empty(), "all failed preferred opens must produce an ownerless session");
        requireStatus(active, Feature.DENOISING, Status.FALLBACK_PENDING);
        requireStatus(active, Feature.MEMORY_OPTIMIZATION, Status.BLOCKED);
        requireStatus(active, Feature.FRAME_RECONSTRUCTION, Status.FALLBACK_PENDING);
        requireStatus(active, Feature.FRAME_GENERATION, Status.FALLBACK_PENDING);
        requireStatus(active, Feature.LOW_LATENCY, Status.FALLBACK_PENDING);
        for (Entry entry : active.values()) {
            require(entry.status() != Status.AVAILABLE && entry.status() != Status.ACTIVE,
                    "fallback-only capability snapshot must not retain a phantom vendor owner: "
                            + entry);
        }
        sessions.close();
        require(operations.closeOrder.isEmpty(),
                "fallback-only session must not call native close with null handles");
    }

    private static void capabilitiesFollowActuallyOpenedOwners() {
        RendererConfig configuration = configuration(
                RendererFeaturePreference.PREFERRED,
                RendererFeaturePreference.PREFERRED,
                RendererFeaturePreference.DISABLED,
                RendererFeaturePreference.DISABLED,
                RendererFeaturePreference.DISABLED
        );
        FakeOperations operations = new FakeOperations();
        operations.nrdHandle = 61L;
        operations.rtxmuOpenFailure = new IllegalStateException("RTXMU open failed");
        NvidiaNativeFeatureSessions sessions = NvidiaNativeFeatureSessions.open(
                FULL_PROBE,
                RendererFeaturePreference.PREFERRED,
                RendererFeaturePreference.PREFERRED,
                operations
        );
        EnumMap<Feature, Entry> active = optimisticVendorCapabilities();
        NvidiaVulkanFeatureProvider.applyNativeOpenOutcomes(active, configuration, sessions);

        requireStatus(active, Feature.DENOISING, Status.AVAILABLE);
        requireStatus(active, Feature.MEMORY_OPTIMIZATION, Status.BLOCKED);
        require(active.get(Feature.DENOISING).reason().contains("initialized"),
                "opened NRD owner must report the device-session evidence boundary");
        require(active.get(Feature.MEMORY_OPTIMIZATION).reason().contains("RTXMU open failed"),
                "failed RTXMU owner must retain its actual open failure");
        sessions.close();
    }

    private static EnumMap<Feature, Entry> optimisticVendorCapabilities() {
        EnumMap<Feature, Entry> active = new EnumMap<>(Feature.class);
        active.put(Feature.DENOISING, Entry.of(
                Status.AVAILABLE, "nvidia.nrd", "optimistic pre-open NRD capability"
        ));
        active.put(Feature.MEMORY_OPTIMIZATION, Entry.of(
                Status.AVAILABLE,
                "nvidia.rtx-memory-utility",
                "optimistic pre-open RTXMU capability"
        ));
        return active;
    }

    private static RendererConfig configuration(
            RendererFeaturePreference nrd,
            RendererFeaturePreference rtxmu,
            RendererFeaturePreference reconstruction,
            RendererFeaturePreference generation,
            RendererFeaturePreference lowLatency
    ) {
        DenoisingOptions denoising = nrd == RendererFeaturePreference.DISABLED
                ? DenoisingOptions.disabled()
                : DenoisingOptions.builder()
                .preference(nrd)
                .builtInTemporalFallback(nrd == RendererFeaturePreference.PREFERRED)
                .build();
        FrameReconstructionOptions reconstructionOptions =
                reconstruction == RendererFeaturePreference.DISABLED
                        ? FrameReconstructionOptions.disabled()
                        : FrameReconstructionOptions.builder()
                        .preference(reconstruction)
                        .mode(FrameReconstructionOptions.Mode.SUPER_RESOLUTION)
                        .fallback(reconstruction == RendererFeaturePreference.PREFERRED
                                ? FrameReconstructionOptions.Fallback.BUILT_IN_TEMPORAL
                                : FrameReconstructionOptions.Fallback.NONE)
                        .build();
        FrameGenerationOptions generationOptions =
                generation == RendererFeaturePreference.DISABLED
                        ? FrameGenerationOptions.disabled()
                        : FrameGenerationOptions.builder()
                        .preference(generation)
                        .mode(FrameGenerationOptions.Mode.FRAME_GENERATION)
                        .multiplier(FrameGenerationOptions.Multiplier.TWO_X)
                        .fallback(generation == RendererFeaturePreference.PREFERRED
                                ? FrameGenerationOptions.Fallback.PRESENT_NATIVE_FRAMES
                                : FrameGenerationOptions.Fallback.NONE)
                        .build();
        LowLatencyOptions latencyOptions = lowLatency == RendererFeaturePreference.DISABLED
                ? LowLatencyOptions.disabled()
                : LowLatencyOptions.builder().preference(lowLatency).build();
        return RendererPreset.CPU_READBACK.configuration().copyBuilder()
                .denoising(denoising)
                .rayTracingOptimizations(RayTracingOptimizationOptions.builder()
                        .memoryOptimization(rtxmu)
                        .build())
                .frameReconstruction(reconstructionOptions)
                .frameGeneration(generationOptions)
                .lowLatency(latencyOptions)
                .build();
    }

    private static RendererDeviceException deviceFailure(String message) {
        return new RendererDeviceException(
                message,
                RendererDeviceException.Reason.DEVICE_LOST,
                RendererDeviceException.RecoveryAction.RECREATE_RENDERER,
                "test owner open",
                -4
        );
    }

    private static Throwable expectFailure(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected operation to fail");
        } catch (AssertionError assertion) {
            throw assertion;
        } catch (RuntimeException | LinkageError failure) {
            return failure;
        }
    }

    private static void requireStatus(
            EnumMap<Feature, Entry> entries,
            Feature feature,
            Status expected
    ) {
        Entry entry = entries.get(feature);
        require(entry != null, "missing feature capability: " + feature);
        require(entry.status() == expected,
                feature + " expected " + expected + " but was " + entry);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeOperations implements NvidiaNativeFeatureSessions.Operations {
        private long nrdHandle;
        private long rtxmuHandle;
        private Throwable nrdOpenFailure;
        private Throwable rtxmuOpenFailure;
        private Throwable nrdCloseFailure;
        private Throwable rtxmuCloseFailure;
        private int nrdOpenCalls;
        private int rtxmuOpenCalls;
        private final List<String> closeOrder = new ArrayList<>();

        @Override
        public long openNrd() {
            nrdOpenCalls++;
            throwIfPresent(nrdOpenFailure);
            return nrdHandle;
        }

        @Override
        public long openRtxmu() {
            rtxmuOpenCalls++;
            throwIfPresent(rtxmuOpenFailure);
            return rtxmuHandle;
        }

        @Override
        public void closeNrd(long handle) {
            closeOrder.add("NRD:" + handle);
            throwIfPresent(nrdCloseFailure);
        }

        @Override
        public void closeRtxmu(long handle) {
            closeOrder.add("RTXMU:" + handle);
            throwIfPresent(rtxmuCloseFailure);
        }

        private static void throwIfPresent(Throwable failure) {
            if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            if (failure instanceof Error error) throw error;
        }
    }
}

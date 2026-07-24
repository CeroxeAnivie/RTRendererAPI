package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import top.ceroxe.mcvulkanrt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameUpdate;
import top.ceroxe.mcvulkanrt.renderer.RendererLog;
import top.ceroxe.mcvulkanrt.renderer.RendererRtDiagnostics;
import top.ceroxe.mcvulkanrt.renderer.RendererViewState;
import top.ceroxe.mcvulkanrt.renderer.rt.runtime.GuardedRtCore;
import top.ceroxe.mcvulkanrt.renderer.rt.runtime.RtCore;
import top.ceroxe.mcvulkanrt.renderer.scene.SceneUpdateBatch;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic ownership and failure-state contract for the public standalone facade. */
public final class VulkanRendererRuntimeLifecycleSelfTest {
    private VulkanRendererRuntimeLifecycleSelfTest() {
    }

    public static void main(String[] arguments) {
        RendererLog.installSink(new RendererLog.Sink() {
            @Override
            public boolean enabled(System.Logger.Level level) {
                return false;
            }

            @Override
            public void log(System.Logger.Level level, String message, Throwable failure) {
            }
        });
        try {
            rejectsUnsupportedHardwareBeforeBackendCreation();
            delegatesAndClosesExactlyOnce();
            closesPartialInitializationInReverseOwnershipScope();
            surfacesRuntimeBackendFailure();
            System.out.println("VulkanRendererRuntimeLifecycleSelfTest passed");
        } finally {
            RendererLog.restoreSystemSink();
        }
    }

    private static void rejectsUnsupportedHardwareBeforeBackendCreation() {
        AtomicInteger factoryCalls = new AtomicInteger();
        VulkanRtCapabilityProbe.Result unsupported = VulkanRtCapabilityProbe.Result.failed(
                VK13.VK_API_VERSION_1_3,
                "test",
                VK10.VK_ERROR_FEATURE_NOT_PRESENT,
                "missing RT features"
        );

        VulkanRendererRuntime.InitializationException failure = expect(
                VulkanRendererRuntime.InitializationException.class,
                () -> VulkanRendererRuntime.open(
                        unsupported,
                        RendererRtDiagnostics.noop(),
                        (capability, scope) -> {
                            factoryCalls.incrementAndGet();
                            return new TrackingBackend(false);
                        }
                )
        );
        require(factoryCalls.get() == 0, "unsupported hardware must not enter native backend creation");
        require(
                failure.terminalState() == RtCore.State.DISABLED_UNSUPPORTED,
                "unsupported open must retain its exact terminal state"
        );
        require(failure.summary() == null, "pre-backend capability rejection must not invent a backend summary");
    }

    private static void delegatesAndClosesExactlyOnce() {
        AtomicInteger closes = new AtomicInteger();
        TrackingBackend backend = new TrackingBackend(false);
        VulkanRendererRuntime renderer = VulkanRendererRuntime.open(
                supportedCapability(),
                RendererRtDiagnostics.noop(),
                (capability, scope) -> {
                    scope.retain("lifecycle test backend", () -> closes.incrementAndGet());
                    return backend;
                }
        );

        require(renderer.status() == VulkanRendererRuntime.Status.READY, "successful open must publish READY");
        require(backend.foregroundUpdates.get() == 1, "open must seed the backend's initial view admission");
        renderer.updateView(RendererViewState.allResident());
        require(backend.foregroundUpdates.get() == 2, "view update was not delegated exactly once");
        renderer.submit(emptyFrameUpdate());
        require(backend.frameUpdates.get() == 1, "frame update was not delegated exactly once");

        renderer.close();
        renderer.close();
        require(renderer.status() == VulkanRendererRuntime.Status.CLOSED, "close must publish CLOSED");
        require(closes.get() == 1, "renderer-owned resources must close exactly once");
        expect(IllegalStateException.class, () -> renderer.submit(emptyFrameUpdate()));
    }

    private static void closesPartialInitializationInReverseOwnershipScope() {
        AtomicInteger closes = new AtomicInteger();
        VulkanRendererRuntime.InitializationException failure = expect(
                VulkanRendererRuntime.InitializationException.class,
                () -> VulkanRendererRuntime.open(
                        supportedCapability(),
                        RendererRtDiagnostics.noop(),
                        (capability, scope) -> {
                            scope.retain("partial native resource", () -> closes.incrementAndGet());
                            throw new IllegalStateException("synthetic initialization failure");
                        }
                )
        );

        require(closes.get() == 1, "partial initialization must release every retained resource exactly once");
        require(
                failure.terminalState() == RtCore.State.DISABLED_BACKEND_FAILURE,
                "backend initialization failure must retain DISABLED_BACKEND_FAILURE"
        );
        require(failure.summary() != null, "backend initialization failure must retain diagnostic evidence");
    }

    private static void surfacesRuntimeBackendFailure() {
        AtomicInteger closes = new AtomicInteger();
        TrackingBackend backend = new TrackingBackend(true);
        VulkanRendererRuntime renderer = VulkanRendererRuntime.open(
                supportedCapability(),
                RendererRtDiagnostics.noop(),
                (capability, scope) -> {
                    scope.retain("failing backend", () -> closes.incrementAndGet());
                    return backend;
                }
        );

        IllegalStateException failure = expect(
                IllegalStateException.class,
                () -> renderer.submit(emptyFrameUpdate())
        );
        require(
                failure.getMessage().contains("DISABLED_BACKEND_FAILURE"),
                "runtime failure must expose the guarded terminal state"
        );
        require(renderer.status() == VulkanRendererRuntime.Status.FAILED, "runtime backend failure must publish FAILED");
        require(closes.get() == 1, "runtime backend failure must close renderer-owned resources immediately");
        renderer.close();
        require(closes.get() == 1, "close after failure must remain idempotent");
    }

    private static VulkanRtCapabilityProbe.Result supportedCapability() {
        VulkanRtCapabilityProbe.DeviceReport device = new VulkanRtCapabilityProbe.DeviceReport(
                "contract GPU",
                0x10de,
                1,
                VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU,
                VK13.VK_API_VERSION_1_3,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true
        );
        return new VulkanRtCapabilityProbe.Result(
                VK13.VK_API_VERSION_1_3,
                true,
                false,
                "ok",
                VK10.VK_SUCCESS,
                "",
                List.of(device)
        );
    }

    private static RendererFrameUpdate emptyFrameUpdate() {
        SceneUpdateBatch batch = new SceneUpdateBatch(
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Map.of(),
                false,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L
        );
        return RendererFrameUpdate.empty(batch);
    }

    private static <T extends Throwable> T expect(Class<T> type, ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) {
                return type.cast(failure);
            }
            throw new AssertionError("expected " + type.getName() + " but caught " + failure, failure);
        }
        throw new AssertionError("expected " + type.getName() + " but no exception was thrown");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private static final class TrackingBackend implements GuardedRtCore.NativeBackend {
        private final AtomicInteger foregroundUpdates = new AtomicInteger();
        private final AtomicInteger frameUpdates = new AtomicInteger();
        private final boolean failFrameUpdates;

        private TrackingBackend(boolean failFrameUpdates) {
            this.failFrameUpdates = failFrameUpdates;
        }

        @Override
        public void acceptForegroundWork(top.ceroxe.mcvulkanrt.renderer.RendererForegroundWork work) {
            foregroundUpdates.incrementAndGet();
        }

        @Override
        public void acceptFrameUpdate(RendererFrameUpdate update) {
            frameUpdates.incrementAndGet();
            if (failFrameUpdates) {
                throw new IllegalStateException("synthetic frame failure");
            }
        }

        @Override
        public String summary() {
            return "trackingBackend{frames=" + frameUpdates.get()
                    + ", foreground=" + foregroundUpdates.get() + "}";
        }
    }
}

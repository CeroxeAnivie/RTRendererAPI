package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.lwjgl.vulkan.VK13;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe.Result;
import top.ceroxe.rt.renderer.RendererForegroundWork;
import top.ceroxe.rt.renderer.RendererFrameUpdate;
import top.ceroxe.rt.renderer.RendererLog;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.RendererViewState;
import top.ceroxe.rt.renderer.backend.vulkan.VulkanRendererRuntime.Status;
import top.ceroxe.rt.renderer.rt.runtime.GuardedRtCore;
import top.ceroxe.rt.renderer.rt.runtime.RtCore.State;
import top.ceroxe.rt.renderer.scene.SceneUpdateBatch;

public final class VulkanRendererRuntimeLifecycleSelfTest {
   private VulkanRendererRuntimeLifecycleSelfTest() {
   }

   public static void main(String[] arguments) {
      RendererLog.installSink(new RendererLog.Sink() {
         public boolean enabled(System.Logger.Level level) {
            return false;
         }

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
      VulkanRtCapabilityProbe.Result unsupported = Result.failed(VK13.VK_API_VERSION_1_3, "test", -8, "missing RT features");
      VulkanRendererRuntime.InitializationException failure = (VulkanRendererRuntime.InitializationException)expect(VulkanRendererRuntime.InitializationException.class, () -> VulkanRendererRuntime.open(unsupported, RendererRtDiagnostics.noop(), (capability, scope) -> {
            factoryCalls.incrementAndGet();
            return new TrackingBackend(false);
         }));
      require(factoryCalls.get() == 0, "unsupported hardware must not enter native backend creation");
      require(failure.terminalState() == State.DISABLED_UNSUPPORTED, "unsupported open must retain its exact terminal state");
      require(failure.summary() == null, "pre-backend capability rejection must not invent a backend summary");
   }

   private static void delegatesAndClosesExactlyOnce() {
      AtomicInteger closes = new AtomicInteger();
      TrackingBackend backend = new TrackingBackend(false);
      VulkanRendererRuntime renderer = VulkanRendererRuntime.open(supportedCapability(), RendererRtDiagnostics.noop(), (capability, scope) -> {
         scope.retain("lifecycle test backend", () -> closes.incrementAndGet());
         return backend;
      });
      require(renderer.status() == Status.READY, "successful open must publish READY");
      require(backend.foregroundUpdates.get() == 1, "open must seed the backend's initial view admission");
      renderer.updateView(RendererViewState.allResident());
      require(backend.foregroundUpdates.get() == 2, "view update was not delegated exactly once");
      renderer.submit(emptyFrameUpdate());
      require(backend.frameUpdates.get() == 1, "frame update was not delegated exactly once");
      renderer.close();
      renderer.close();
      require(renderer.status() == Status.CLOSED, "close must publish CLOSED");
      require(closes.get() == 1, "renderer-owned resources must close exactly once");
      expect(IllegalStateException.class, () -> renderer.submit(emptyFrameUpdate()));
   }

   private static void closesPartialInitializationInReverseOwnershipScope() {
      AtomicInteger closes = new AtomicInteger();
      VulkanRendererRuntime.InitializationException failure = (VulkanRendererRuntime.InitializationException)expect(VulkanRendererRuntime.InitializationException.class, () -> VulkanRendererRuntime.open(supportedCapability(), RendererRtDiagnostics.noop(), (capability, scope) -> {
            scope.retain("partial native resource", () -> closes.incrementAndGet());
            throw new IllegalStateException("synthetic initialization failure");
         }));
      require(closes.get() == 1, "partial initialization must release every retained resource exactly once");
      require(failure.terminalState() == State.DISABLED_BACKEND_FAILURE, "backend initialization failure must retain DISABLED_BACKEND_FAILURE");
      require(failure.summary() != null, "backend initialization failure must retain diagnostic evidence");
   }

   private static void surfacesRuntimeBackendFailure() {
      AtomicInteger closes = new AtomicInteger();
      TrackingBackend backend = new TrackingBackend(true);
      VulkanRendererRuntime renderer = VulkanRendererRuntime.open(supportedCapability(), RendererRtDiagnostics.noop(), (capability, scope) -> {
         scope.retain("failing backend", () -> closes.incrementAndGet());
         return backend;
      });
      IllegalStateException failure = (IllegalStateException)expect(IllegalStateException.class, () -> renderer.submit(emptyFrameUpdate()));
      require(failure.getMessage().contains("DISABLED_BACKEND_FAILURE"), "runtime failure must expose the guarded terminal state");
      require(renderer.status() == Status.FAILED, "runtime backend failure must publish FAILED");
      require(closes.get() == 1, "runtime backend failure must close renderer-owned resources immediately");
      renderer.close();
      require(closes.get() == 1, "close after failure must remain idempotent");
   }

   private static VulkanRtCapabilityProbe.Result supportedCapability() {
      VulkanRtCapabilityProbe.DeviceReport device = new VulkanRtCapabilityProbe.DeviceReport("contract GPU", 4318, 1, 2, VK13.VK_API_VERSION_1_3, true, true, true, true, true, true, true, true, true, true);
      return new VulkanRtCapabilityProbe.Result(VK13.VK_API_VERSION_1_3, true, false, "ok", 0, "", List.of(device));
   }

   private static RendererFrameUpdate emptyFrameUpdate() {
      SceneUpdateBatch batch = new SceneUpdateBatch(Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), false, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
      return RendererFrameUpdate.empty(batch);
   }

   private static <T extends Throwable> T expect(Class<T> type, ThrowingRunnable action) {
      try {
         action.run();
      } catch (Throwable failure) {
         if (type.isInstance(failure)) {
            return (T)(type.cast(failure));
         }

         throw new AssertionError("expected " + type.getName() + " but caught " + String.valueOf(failure), failure);
      }

      throw new AssertionError("expected " + type.getName() + " but no exception was thrown");
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }

   private static final class TrackingBackend implements GuardedRtCore.NativeBackend {
      private final AtomicInteger foregroundUpdates = new AtomicInteger();
      private final AtomicInteger frameUpdates = new AtomicInteger();
      private final boolean failFrameUpdates;

      private TrackingBackend(boolean failFrameUpdates) {
         this.failFrameUpdates = failFrameUpdates;
      }

      public void acceptForegroundWork(RendererForegroundWork work) {
         this.foregroundUpdates.incrementAndGet();
      }

      public void acceptFrameUpdate(RendererFrameUpdate update) {
         this.frameUpdates.incrementAndGet();
         if (this.failFrameUpdates) {
            throw new IllegalStateException("synthetic frame failure");
         }
      }

      public String summary() {
         int value10000 = this.frameUpdates.get();
         return "trackingBackend{frames=" + value10000 + ", foreground=" + this.foregroundUpdates.get() + "}";
      }
   }

   @FunctionalInterface
   private interface ThrowingRunnable {
      void run() throws Throwable;
   }
}

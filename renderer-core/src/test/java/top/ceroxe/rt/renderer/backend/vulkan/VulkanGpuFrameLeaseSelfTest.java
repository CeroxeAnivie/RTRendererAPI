package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease.ConsumerCompletionCapabilities;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease.FrameDescriptor;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease.HandleState;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease.ImportDisposition;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease.LeaseState;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease.SemaphoreKind;

public final class VulkanGpuFrameLeaseSelfTest {
   private VulkanGpuFrameLeaseSelfTest() {
   }

   public static void main(String[] arguments) {
      assertCompletionObserverFailureIsRetryable();
      assertHandleCloseFailureIsRetryable();
      assertUnsupportedCompletionHasNoSideEffects();
      assertAdvertisedBinaryCompletionIsForwardedExactlyOnce();
      assertLifecycleStateIsMutuallyExclusive();
      assertTrackingObserverFailureIsRetryable();
      assertUnusedLazyMemoryHandleDoesNotExport();
      System.out.println("VulkanGpuFrameLeaseSelfTest passed");
   }

   private static void assertCompletionObserverFailureIsRetryable() {
      TrackingHandle handle = new TrackingHandle();
      AtomicInteger attempts = new AtomicInteger();
      VulkanGpuFrameLease lease = new VulkanGpuFrameLease(descriptor(), handle, () -> {
         if (attempts.getAndIncrement() == 0) {
            throw new IllegalStateException("synthetic observer failure");
         }
      });
      expect(IllegalStateException.class, () -> lease.release(new GpuFrameLease.CpuCompleted()));
      require(lease.state() == LeaseState.ACTIVE, "failed completion observer changed the active lease state");
      lease.release(new GpuFrameLease.CpuCompleted());
      require(lease.state() == LeaseState.RELEASED && attempts.get() == 2, "completion observer retry did not release the lease");
      lease.close();
      require(lease.state() == LeaseState.CLOSED && handle.closeAttempts == 1, "released lease did not close its native handle");
   }

   private static void assertHandleCloseFailureIsRetryable() {
      TrackingHandle handle = new TrackingHandle();
      handle.closeFailuresRemaining = 1;
      AtomicInteger completions = new AtomicInteger();
      GpuFrameLease.FrameDescriptor frameDescriptor10002 = descriptor();
      Objects.requireNonNull(completions);
      VulkanGpuFrameLease lease = new VulkanGpuFrameLease(frameDescriptor10002, handle, completions::incrementAndGet);
      Objects.requireNonNull(lease);
      expect(IllegalStateException.class, lease::close);
      require(lease.state() == LeaseState.RELEASED, "close failure lost the successfully published consumer completion");
      lease.close();
      require(lease.state() == LeaseState.CLOSED && handle.state == HandleState.CLOSED, "retry did not close the native handle and lease");
      require(completions.get() == 1, "close retry published consumer completion more than once");
   }

   private static void assertUnsupportedCompletionHasNoSideEffects() {
      TrackingHandle handle = new TrackingHandle();
      AtomicInteger completions = new AtomicInteger();
      GpuFrameLease.FrameDescriptor frameDescriptor10002 = descriptor();
      Objects.requireNonNull(completions);
      VulkanGpuFrameLease lease = new VulkanGpuFrameLease(frameDescriptor10002, handle, completions::incrementAndGet);
      GpuFrameLease.ExternalSemaphoreSignal signal = new GpuFrameLease.ExternalSemaphoreSignal(1L, new GpuFrameLease.VulkanSemaphoreHandleType(1), SemaphoreKind.BINARY, 0L, ImportDisposition.CALLER_RETAINS_HANDLE);
      expect(UnsupportedOperationException.class, () -> lease.release(signal));
      require(lease.state() == LeaseState.ACTIVE && completions.get() == 0, "unsupported completion mutated the lease or frame slot");
      lease.close();
   }

   private static void assertAdvertisedBinaryCompletionIsForwardedExactlyOnce() {
      TrackingHandle handle = new TrackingHandle();
      AtomicReference<GpuFrameLease.ConsumerCompletion> observed = new AtomicReference<>();
      VulkanGpuFrameLease lease = new VulkanGpuFrameLease(descriptor(), handle, ConsumerCompletionCapabilities.cpuAndBinarySemaphore(), (completion) -> {
         if (!observed.compareAndSet(null, completion)) {
            throw new AssertionError("completion was published more than once");
         }
      });
      GpuFrameLease.ExternalSemaphoreSignal binary = new GpuFrameLease.ExternalSemaphoreSignal(2L, new GpuFrameLease.VulkanSemaphoreHandleType(1), SemaphoreKind.BINARY, 0L, ImportDisposition.CALLER_RETAINS_HANDLE);
      require(lease.consumerCompletionCapabilities().supports(SemaphoreKind.BINARY), "binary completion capability was hidden");
      require(!lease.consumerCompletionCapabilities().supports(SemaphoreKind.TIMELINE), "timeline completion was advertised without implementation");
      lease.release(binary);
      require(observed.get() == binary && lease.state() == LeaseState.RELEASED, "binary completion was not forwarded exactly");
      lease.close();
   }

   private static void assertLifecycleStateIsMutuallyExclusive() {
      TrackingHandle handle = new TrackingHandle();
      VulkanGpuFrameLease lease = new VulkanGpuFrameLease(descriptor(), handle, () -> {
      });
      require(lease.state() == LeaseState.ACTIVE, "new lease is not active");
      lease.release(new GpuFrameLease.CpuCompleted());
      require(lease.state() == LeaseState.RELEASED, "released lease state was not published");
      lease.close();
      require(lease.state() == LeaseState.CLOSED, "closed lease state was not published");
   }

   private static void assertTrackingObserverFailureIsRetryable() {
      TrackingHandle handle = new TrackingHandle();
      VulkanGpuFrameLease delegate = new VulkanGpuFrameLease(descriptor(), handle, () -> {
      });
      AtomicInteger observations = new AtomicInteger();
      TrackedGpuFrameLease lease = new TrackedGpuFrameLease(delegate, () -> {
         if (observations.getAndIncrement() == 0) {
            throw new IllegalStateException("synthetic tracking observer failure");
         }
      });
      Objects.requireNonNull(lease);
      expect(IllegalStateException.class, lease::close);
      require(delegate.state() == LeaseState.CLOSED, "tracking failure lost the successful backend close");
      lease.close();
      require(observations.get() == 2 && lease.state() == LeaseState.CLOSED, "tracking notification was not retried exactly once");
   }

   private static void assertUnusedLazyMemoryHandleDoesNotExport() {
      AtomicInteger exports = new AtomicInteger();
      VulkanExportedMemoryHandle handle = new VulkanExportedMemoryHandle(() -> {
         exports.incrementAndGet();
         return 1L;
      });
      handle.close();
      require(exports.get() == 0, "closing an unused managed handle performed a Win32 export");
      require(handle.state() == HandleState.CLOSED, "unused lazy handle did not close cleanly");
   }

   private static GpuFrameLease.FrameDescriptor descriptor() {
      return FrameDescriptor.builder().resourceId(1L).frameSequence(1L).renderedSceneRevision(1L).extent(1, 1).format(new GpuFrameLease.VulkanFormat(37)).imageType(new GpuFrameLease.VulkanImageType(1)).imageTiling(new GpuFrameLease.VulkanImageTiling(1)).imageUsage(new GpuFrameLease.VulkanImageUsage(1)).imageCreateFlags(new GpuFrameLease.VulkanImageCreateFlags(0)).imageLayout(new GpuFrameLease.VulkanImageLayout(1)).mipLevels(1).arrayLayers(1).sampleCount(new GpuFrameLease.VulkanSampleCount(1)).sharingMode(new GpuFrameLease.VulkanSharingMode(0)).producerQueueFamily(new GpuFrameLease.VulkanQueueFamily(0)).memoryTypeIndex(0).allocationSize(4L).allocationOffset(0L).dedicatedAllocation(true).build();
   }

   private static <T extends Throwable> void expect(Class<T> type, ThrowingRunnable action) {
      try {
         action.run();
      } catch (Throwable failure) {
         if (type.isInstance(failure)) {
            return;
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

   private static final class TrackingHandle implements GpuFrameLease.ExportedNativeHandle<GpuFrameLease.VulkanMemoryHandleType> {
      private GpuFrameLease.HandleState state;
      private int closeFailuresRemaining;
      private int closeAttempts;

      private TrackingHandle() {
         this.state = HandleState.EXPORTED;
      }

      public long value() {
         return 1L;
      }

      public GpuFrameLease.VulkanMemoryHandleType handleType() {
         return new GpuFrameLease.VulkanMemoryHandleType(1);
      }

      public GpuFrameLease.ImportDisposition importDisposition() {
         return ImportDisposition.CALLER_RETAINS_HANDLE;
      }

      public GpuFrameLease.HandleState state() {
         return this.state;
      }

      public boolean markImported() {
         if (this.state != HandleState.EXPORTED) {
            return false;
         } else {
            this.state = HandleState.IMPORTED;
            return true;
         }
      }

      public void close() {
         ++this.closeAttempts;
         if (this.closeFailuresRemaining > 0) {
            --this.closeFailuresRemaining;
            throw new IllegalStateException("synthetic handle close failure");
         } else {
            this.state = HandleState.CLOSED;
         }
      }
   }

   @FunctionalInterface
   private interface ThrowingRunnable {
      void run();
   }
}

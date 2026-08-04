package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.FrameOutputFormat;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease.ImportDisposition;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease.LeaseState;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease.SemaphoreKind;
import top.ceroxe.rt.renderer.rt.device.RtCommandContext;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.device.interop.VulkanWin32ExternalSemaphoreProbe;
import top.ceroxe.rt.renderer.rt.device.interop.Win32HandleSupport;

public final class VulkanFrameSlotExternalCompletionNativeSelfTest {
   private static final long TIMEOUT_NANOS = 15000000000L;

   private VulkanFrameSlotExternalCompletionNativeSelfTest() {
   }

   public static void main(String[] arguments) throws Exception {
      VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
      require(capability.hardwareRayTracingReady(), "external completion gate requires hardware RT: " + capability.summary());
      VulkanDeviceRuntime producerDevice = VulkanDeviceRuntime.open(capability, RendererRtDiagnostics.noop(), true, false);

      try {
         VulkanDeviceRuntime.ExternalFrameInterop interop = producerDevice.externalFrameInterop();
         require(interop.memoryExportReady() && interop.semaphoreImportReady(), "external frame completion is unavailable: " + interop.reason());
         VulkanFrameSlot slot = new VulkanFrameSlot(0, producerDevice, VulkanFrameOutput.from(FrameOutputFormat.SDR_RGBA8), interop.dedicatedAllocationRequired(), true, true, RendererRtDiagnostics.noop().stalls(), false);

         try {
            publish(slot, producerDevice, 0L);
            VulkanDeviceRuntime consumerDevice = VulkanDeviceRuntime.open(capability, RendererRtDiagnostics.noop(), false, false);
            long stableResourceId;

            try {
               GpuFrameLease lease = slot.acquire();
               stableResourceId = lease.descriptor().resourceId();
               require(stableResourceId > 0L, "frame slot published an invalid external resource identity");

               try {
                  releaseAfterSubmittedExternalSignal(slot, lease, consumerDevice);
               } catch (Throwable value13) {
                  if (lease != null) {
                     try {
                        lease.close();
                     } catch (Throwable value12) {
                        value13.addSuppressed(value12);
                     }
                  }

                  throw value13;
               }

               if (lease != null) {
                  lease.close();
               }
            } catch (Throwable value14) {
               if (consumerDevice != null) {
                  try {
                     consumerDevice.close();
                  } catch (Throwable value11) {
                     value14.addSuppressed(value11);
                  }
               }

               throw value14;
            }

            if (consumerDevice != null) {
               consumerDevice.close();
            }

            require(slot.writable(), "frame slot did not become writable after consumer completion");
            publish(slot, producerDevice, 1L);
            rejectInvalidExternalHandle(slot, stableResourceId);
            require(slot.writable(), "failed semaphore import stranded the frame slot");
            verifyReplacementChangesResourceId(slot, producerDevice, stableResourceId);
         } catch (Throwable value15) {
            try {
               slot.close();
            } catch (Throwable value10) {
               value15.addSuppressed(value10);
            }

            throw value15;
         }

         slot.close();
      } catch (Throwable value16) {
         if (producerDevice != null) {
            try {
               producerDevice.close();
            } catch (Throwable value9) {
               value16.addSuppressed(value9);
            }
         }

         throw value16;
      }

      if (producerDevice != null) {
         producerDevice.close();
      }

      System.out.println("VulkanFrameSlotExternalCompletionNativeSelfTest passed");
   }

   private static void publish(VulkanFrameSlot slot, VulkanDeviceRuntime producerDevice, long sequence) throws InterruptedException {
      publish(slot, producerDevice, sequence, 64, 64);
   }

   private static void publish(VulkanFrameSlot slot, VulkanDeviceRuntime producerDevice, long sequence, int width, int height) throws InterruptedException {
      slot.prepare(width, height, new byte[VulkanFrameUniformPacker.BYTE_COUNT]);
      slot.submitted(producerDevice.frameCommands().submitOneTimeAsync((commandBuffer, stack) -> {
      }), sequence, sequence, sequence, true,
              VulkanDeviceRuntime.ManagedPresentationSignal.disabled(), false);
      awaitProducer(slot);
   }

   private static void rejectInvalidExternalHandle(VulkanFrameSlot slot, long expectedResourceId) {
      GpuFrameLease lease = slot.acquire();

      try {
         require(lease.descriptor().resourceId() == expectedResourceId,
                 "reused frame allocation changed external resource identity");
         require(Win32HandleSupport.valid(lease.memoryHandle().value()), "freshly exported frame memory handle is invalid");
         GpuFrameLease.ExternalSemaphoreSignal invalid = new GpuFrameLease.ExternalSemaphoreSignal(1L, new GpuFrameLease.VulkanSemaphoreHandleType(1), SemaphoreKind.BINARY, 0L, ImportDisposition.CALLER_RETAINS_HANDLE);
         expect(IllegalArgumentException.class, () -> lease.release(invalid));
         require(lease.state() == LeaseState.ACTIVE, "failed external semaphore import released the lease");
         require(!slot.writable(), "failed external semaphore import released the frame slot");
         require(Win32HandleSupport.valid(lease.memoryHandle().value()), "rejected semaphore completion invalidated the frame memory handle");
      } catch (Throwable value5) {
         if (lease != null) {
            try {
               lease.close();
            } catch (Throwable value4) {
               value5.addSuppressed(value4);
            }
         }

         throw value5;
      }

      if (lease != null) {
         lease.close();
      }

   }

   private static void verifyReplacementChangesResourceId(
           VulkanFrameSlot slot,
           VulkanDeviceRuntime producerDevice,
           long previousResourceId
   ) throws InterruptedException {
      publish(slot, producerDevice, 2L, 96, 64);
      GpuFrameLease lease = slot.acquire();
      try {
         require(lease.descriptor().resourceId() != previousResourceId,
                 "replaced frame allocation retained a stale external resource identity");
      } finally {
         lease.close();
      }
      require(slot.writable(), "replacement resource verification stranded the frame slot");
   }

   private static void releaseAfterSubmittedExternalSignal(VulkanFrameSlot slot, GpuFrameLease lease, VulkanDeviceRuntime consumerDevice) throws InterruptedException {
      long consumerHandle = 0L;

      try {
         VulkanWin32ExternalSemaphoreProbe.ExportedSemaphore signal = VulkanWin32ExternalSemaphoreProbe.exportSemaphore(consumerDevice.device());

         try {
            consumerHandle = signal.detachWin32Handle();
            try (RtCommandContext.AsyncSubmission signalSubmission = consumerDevice.frameCommands().submitOneTimeAsync((commandBuffer, stack) -> {
            }, signal)) {
               lease.release(new GpuFrameLease.ExternalSemaphoreSignal(consumerHandle, new GpuFrameLease.VulkanSemaphoreHandleType(signal.handleType()), SemaphoreKind.BINARY, 0L, ImportDisposition.CALLER_RETAINS_HANDLE));
               require(lease.state() == LeaseState.RELEASED, "lease did not accept external GPU completion");
            }
            awaitConsumer(slot);
         } catch (Throwable value13) {
            if (signal != null) {
               try {
                  signal.close();
               } catch (Throwable value12) {
                  value13.addSuppressed(value12);
               }
            }

            throw value13;
         }

         if (signal != null) {
            signal.close();
         }
      } finally {
         if (consumerHandle != 0L && !Win32HandleSupport.close(consumerHandle)) {
            throw new IllegalStateException("failed to close external consumer semaphore handle, error=" + Win32HandleSupport.lastError());
         }

      }

   }

   private static void awaitProducer(VulkanFrameSlot slot) throws InterruptedException {
      long deadline = System.nanoTime() + 15000000000L;

      while(!slot.pollProducer()) {
         if (System.nanoTime() >= deadline) {
            throw new AssertionError("producer submission did not complete before timeout");
         }

         Thread.sleep(1L);
      }

      require(slot.completed(), "completed producer did not publish a leasable frame");
   }

   private static void awaitConsumer(VulkanFrameSlot slot) throws InterruptedException {
      long deadline = System.nanoTime() + 15000000000L;

      while(!slot.writable()) {
         slot.pollProducer();
         if (System.nanoTime() >= deadline) {
            throw new AssertionError("external consumer completion did not release the frame slot");
         }

         Thread.sleep(1L);
      }

   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
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

   @FunctionalInterface
   private interface ThrowingRunnable {
      void run() throws Exception;
   }
}

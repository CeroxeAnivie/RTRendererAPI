package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.EnumMap;
import java.util.Objects;
import top.ceroxe.rt.renderer.api.SceneTransaction;
import top.ceroxe.rt.renderer.api.TextureAsset;
import top.ceroxe.rt.renderer.api.TextureAsset.AddressMode;
import top.ceroxe.rt.renderer.api.TextureAsset.ColorSpace;
import top.ceroxe.rt.renderer.api.TextureAsset.Filter;
import top.ceroxe.rt.renderer.backend.vulkan.VulkanGpuScene.Lifecycle;
import top.ceroxe.rt.renderer.backend.vulkan.VulkanGpuSceneUploadPlanner.Target;

public final class VulkanGpuSceneSelfTest {
   private VulkanGpuSceneSelfTest() {
   }

   public static void main(String[] arguments) throws Exception {
      acceptedGenerationRemainsInactiveUntilFenceCompletion();
      inFlightGenerationRejectsASecondGenerationWithoutPublishingIt();
      memoryBudgetRejectionRemainsRetryable();
      System.out.println("VulkanGpuSceneSelfTest passed");
   }

   private static void acceptedGenerationRemainsInactiveUntilFenceCompletion() throws Exception {
      FakeTransferQueue transfers = new FakeTransferQueue();
      VulkanSceneResidency residency = new VulkanSceneResidency();
      VulkanGpuScene scene = new VulkanGpuScene(transfers);

      try {
         VulkanSceneResidency.PreparedUpdate initial = residency.prepare(transaction(0L, true, 10L));
         VulkanGpuScene.Admission admission = scene.submit(initial.changeSet(), 0L);
         require(admission.acceptedRevision() == 0L && !admission.active(), "submission fence was confused with scene acceptance");
         residency.commit(initial);
         VulkanGpuScene.Snapshot accepted = scene.snapshot();
         require(accepted.acceptedRevision() == 0L && accepted.activeRevision() == -1L && accepted.pendingRevision() == 0L, "accepted generation was exposed before native completion");
         transfers.completePending();
         VulkanGpuScene.Snapshot active = scene.poll(0L);
         require(active.activeRevision() == 0L && active.pendingRevision() == -1L, "completed native generation was not activated");
         require(scene.requireBuffer(Target.TEXTURE_RECORDS, 0L).capacityBytes() == 4096L, "active buffer binding was unavailable");
         require(transfers.latestReleasedEpoch == 0L, "descriptor completion epoch did not reach native retirement");
      } catch (Throwable value8) {
         try {
            scene.close();
         } catch (Throwable value7) {
            value8.addSuppressed(value7);
         }

         throw value8;
      }

      scene.close();
   }

   private static void inFlightGenerationRejectsASecondGenerationWithoutPublishingIt() throws Exception {
      FakeTransferQueue transfers = new FakeTransferQueue();
      VulkanSceneResidency residency = new VulkanSceneResidency();
      VulkanGpuScene scene = new VulkanGpuScene(transfers);

      try {
         VulkanSceneResidency.PreparedUpdate initial = residency.prepare(transaction(0L, true, 10L));
         scene.submit(initial.changeSet(), 0L);
         residency.commit(initial);
         VulkanSceneResidency.PreparedUpdate successor = residency.prepare(transaction(1L, false, 10L));
         boolean rejected = false;

         try {
            scene.submit(successor.changeSet(), 1L);
         } catch (VulkanGpuScene.BusyException value8) {
            rejected = true;
         }

         require(rejected, "in-flight GPUScene generation did not apply backpressure");
         require(scene.snapshot().acceptedRevision() == 0L && transfers.submissions == 1, "rejected generation changed CPU or native authority");
         transfers.completePending();
         scene.poll(0L);
         VulkanGpuScene.Admission accepted = scene.submit(successor.changeSet(), 1L);
         require(accepted.acceptedRevision() == 1L && transfers.submissions == 2, "same prepared generation could not be retried after backpressure cleared");
      } catch (Throwable value9) {
         try {
            scene.close();
         } catch (Throwable value7) {
            value9.addSuppressed(value7);
         }

         throw value9;
      }

      scene.close();
   }

   private static void memoryBudgetRejectionRemainsRetryable() throws Exception {
      FakeTransferQueue transfers = new FakeTransferQueue();
      transfers.rejectNextMemoryBudget = true;
      VulkanSceneResidency residency = new VulkanSceneResidency();
      VulkanGpuScene scene = new VulkanGpuScene(transfers);

      try {
         VulkanSceneResidency.PreparedUpdate initial = residency.prepare(transaction(0L, true, 10L));
         boolean rejected = false;

         try {
            scene.submit(initial.changeSet(), 0L);
         } catch (VulkanGpuScene.BusyException expected) {
            require(expected.getCause() instanceof VulkanMemoryBudgetRejectedException, "memory budget rejection lost its typed retry cause");
            rejected = true;
         }

         require(rejected, "memory budget refusal did not surface as retryable backpressure");
         require(scene.snapshot().lifecycle() == Lifecycle.READY && scene.snapshot().acceptedRevision() == -1L && transfers.submissions == 0, "memory budget refusal poisoned or partially published GPUScene state");
         VulkanGpuScene.Admission retry = scene.submit(initial.changeSet(), 0L);
         require(retry.acceptedRevision() == 0L && transfers.submissions == 1, "budget-rejected scene generation could not retry unchanged");
      } catch (Throwable value8) {
         try {
            scene.close();
         } catch (Throwable value6) {
            value8.addSuppressed(value6);
         }

         throw value8;
      }

      scene.close();
   }

   private static SceneTransaction transaction(long revision, boolean reset, long textureId) {
      TextureAsset texture = TextureAsset.builder(textureId, 1, 1).colorSpace(ColorSpace.SRGB).addressModes(AddressMode.REPEAT, AddressMode.REPEAT).filter(Filter.NEAREST).pixelsRgba8(new byte[]{1, 2, 3, 4}).build();
      SceneTransaction.Builder builder = SceneTransaction.builder(revision).upsert(texture);
      if (reset) {
         builder.resetScene();
      }

      return builder.build();
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }

   private static final class FakeTransferQueue implements VulkanGpuSceneTransferQueue {
      private final EnumMap<VulkanGpuSceneUploadPlanner.Target, VulkanGpuSceneTransferQueue.BufferBinding> buffers = new EnumMap<>(VulkanGpuSceneUploadPlanner.Target.class);
      private FakeTicket pending;
      private long activeRevision = -1L;
      private long latestReleasedEpoch = -1L;
      private int submissions;
      private boolean closed;
      private boolean rejectNextMemoryBudget;

      public VulkanGpuSceneTransferQueue.TransferTicket submit(VulkanGpuSceneUploadPlanner.Plan uploadPlan) {
         VulkanGpuSceneSelfTest.require(!this.closed && this.pending == null, "fake transfer queue received an invalid submission");
         VulkanGpuSceneUploadPlanner.Plan plan = (VulkanGpuSceneUploadPlanner.Plan)Objects.requireNonNull(uploadPlan, "uploadPlan");
         if (this.rejectNextMemoryBudget) {
            this.rejectNextMemoryBudget = false;
            throw new VulkanMemoryBudgetRejectedException("synthetic GPU memory budget rejection");
         } else {
            for(VulkanGpuSceneUploadPlanner.Chunk chunk : plan.chunks()) {
               this.buffers.putIfAbsent(chunk.target(), new VulkanGpuSceneTransferQueue.BufferBinding((long)chunk.target().ordinal() + 1L, 4096L + (long)chunk.target().ordinal() * 4096L, 4096L));
            }

            this.pending = new FakeTicket(plan.revision());
            ++this.submissions;
            return this.pending;
         }
      }

      public boolean pollAndActivate(VulkanGpuSceneTransferQueue.TransferTicket transfer, long retireAfterEpoch) {
         FakeTicket ticket = this.requireTicket(transfer);
         if (!ticket.complete) {
            return false;
         } else {
            ticket.activated = true;
            this.activeRevision = ticket.revision;
            this.pending = null;
            return true;
         }
      }

      public void waitAndActivate(VulkanGpuSceneTransferQueue.TransferTicket transfer, long retireAfterEpoch) {
         FakeTicket ticket = this.requireTicket(transfer);
         ticket.complete = true;
         this.pollAndActivate(ticket, retireAfterEpoch);
      }

      public void releaseRetiredThrough(long completedDescriptorEpoch) {
         this.latestReleasedEpoch = completedDescriptorEpoch;
      }

      public VulkanGpuSceneTransferQueue.BufferBinding buffer(VulkanGpuSceneUploadPlanner.Target target) {
         return (VulkanGpuSceneTransferQueue.BufferBinding)this.buffers.get(target);
      }

      public VulkanGpuSceneTransferQueue.TransferState state() {
         return new VulkanGpuSceneTransferQueue.TransferState(this.activeRevision, this.buffers.size(), (long)this.buffers.size() * 4096L, this.pending != null, 0, 0L);
      }

      public void close() {
         this.closed = true;
         this.pending = null;
         this.buffers.clear();
      }

      private void completePending() {
         VulkanGpuSceneSelfTest.require(this.pending != null, "fake transfer queue had no pending generation");
         this.pending.complete = true;
      }

      private FakeTicket requireTicket(VulkanGpuSceneTransferQueue.TransferTicket candidate) {
         if (candidate instanceof FakeTicket ticket) {
            if (ticket == this.pending && !ticket.activated) {
               return ticket;
            }
         }

         throw new IllegalStateException("fake transfer ticket is stale");
      }
   }

   private static final class FakeTicket implements VulkanGpuSceneTransferQueue.TransferTicket {
      private final long revision;
      private boolean complete;
      private boolean activated;

      private FakeTicket(long revision) {
         this.revision = revision;
      }

      public long revision() {
         return this.revision;
      }

      public boolean activated() {
         return this.activated;
      }
   }
}

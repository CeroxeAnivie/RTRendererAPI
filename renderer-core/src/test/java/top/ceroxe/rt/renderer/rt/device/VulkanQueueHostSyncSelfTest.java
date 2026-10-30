package top.ceroxe.rt.renderer.rt.device;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VulkanQueueHostSyncSelfTest {
   private VulkanQueueHostSyncSelfTest() {
   }

   public static void main(String[] args) throws InterruptedException {
      canonicalizesMonitorByQueueHandle();
      isolatesDifferentQueueHandles();
      serializesCompetingOwnersOfOneQueue();
      validatesLegacyWaitCountWithoutGpu();
      System.out.println("VulkanQueueHostSyncSelfTest passed");
   }

   private static void canonicalizesMonitorByQueueHandle() {
      Object first = VulkanQueueHostSync.monitor(256L);
      Object second = VulkanQueueHostSync.monitor(256L);
      require(first == second, "one VkQueue handle must have one host-synchronization monitor");
   }

   private static void isolatesDifferentQueueHandles() {
      require(VulkanQueueHostSync.monitor(512L) != VulkanQueueHostSync.monitor(513L), "different VkQueue handles must not share an operation monitor");
   }

   private static void serializesCompetingOwnersOfOneQueue() throws InterruptedException {
      Object monitor = VulkanQueueHostSync.monitor(768L);
      CountDownLatch contenderStarted = new CountDownLatch(1);
      CountDownLatch contenderEntered = new CountDownLatch(1);
      AtomicBoolean interrupted = new AtomicBoolean();
      Thread contender;
      synchronized(monitor) {
         contender = new Thread(() -> {
            contenderStarted.countDown();
            synchronized(VulkanQueueHostSync.monitor(768L)) {
               contenderEntered.countDown();
            }
         }, "queue-host-sync-contender");
         contender.start();
         require(contenderStarted.await(1L, TimeUnit.SECONDS), "contender did not start");
         require(!contenderEntered.await(50L, TimeUnit.MILLISECONDS), "same-queue operation entered concurrently");
      }

      contender.join(1000L);
      if (contender.isAlive()) {
         contender.interrupt();
         interrupted.set(true);
      }

      require(!interrupted.get(), "contender did not leave after the queue monitor was released");
      require(contenderEntered.getCount() == 0L, "contender never acquired the released queue monitor");
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }

   private static void validatesLegacyWaitCountWithoutGpu() {
      try (var stack = MemoryStack.stackPush()) {
         var submit = VkSubmitInfo.calloc(1, stack).sType$Default();
         VulkanQueueHostSync.validateLegacyWaits(submit);
         submit.pWaitSemaphores(stack.longs(17L))
                 .pWaitDstStageMask(stack.ints(VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT));
         require(submit.waitSemaphoreCount() == 0, "LWJGL wait-array setter contract changed; review explicit count handling");
         expectMalformedWaits(submit, "wait pointers silently lost because count remained zero");
         submit.waitSemaphoreCount(1);
         VulkanQueueHostSync.validateLegacyWaits(submit);

         var missingStages = VkSubmitInfo.calloc(1, stack).sType$Default().waitSemaphoreCount(1)
                 .pWaitSemaphores(stack.longs(18L));
         expectMalformedWaits(missingStages, "positive wait count needs stage masks");
         var missingSemaphores = VkSubmitInfo.calloc(1, stack).sType$Default().waitSemaphoreCount(1)
                 .pWaitDstStageMask(stack.ints(VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT));
         expectMalformedWaits(missingSemaphores, "positive wait count needs semaphore handles");
         var orphanStages = VkSubmitInfo.calloc(1, stack).sType$Default()
                 .pWaitDstStageMask(stack.ints(VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT));
         expectMalformedWaits(orphanStages, "orphan stage masks hide a missing wait dependency");

         var batch = VkSubmitInfo.calloc(2, stack);
         batch.get(0).sType$Default();
         batch.get(1).sType$Default().pWaitSemaphores(stack.longs(19L))
                 .pWaitDstStageMask(stack.ints(VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT));
         expectMalformedWaits(batch, "all submissions in a batch must be checked");
         batch.get(1).waitSemaphoreCount(1);
         VulkanQueueHostSync.validateLegacyWaits(batch);
         batch.get(0).waitSemaphoreCount(1);
         batch.position(1);
         VulkanQueueHostSync.validateLegacyWaits(batch);
      }
   }

   private static void expectMalformedWaits(VkSubmitInfo.Buffer submit, String message) {
      try {
         VulkanQueueHostSync.validateLegacyWaits(submit);
      } catch (IllegalArgumentException expected) {
         require(expected.getMessage().contains("waitSemaphoreCount"), "missing contract diagnostic");
         return;
      }
      throw new AssertionError(message);
   }
}

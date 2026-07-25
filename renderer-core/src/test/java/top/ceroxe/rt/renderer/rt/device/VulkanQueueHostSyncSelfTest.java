package top.ceroxe.rt.renderer.rt.device;

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
}

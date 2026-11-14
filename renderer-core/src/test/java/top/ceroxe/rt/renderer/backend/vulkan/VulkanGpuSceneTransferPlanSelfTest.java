package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.Arrays;
import java.util.List;
import top.ceroxe.rt.renderer.backend.vulkan.VulkanGpuSceneUploadPlanner.Target;

public final class VulkanGpuSceneTransferPlanSelfTest {
   private VulkanGpuSceneTransferPlanSelfTest() {
   }

   public static void main(String[] arguments) {
      distinguishesInPlaceGrowthAndFirstAllocation();
      preservesPackedStagingOrderAndOffsets();
      bootstrapsDescriptorTargetsForAnEmptyInitialScene();
      acceptsAnEmptySceneGenerationAfterBootstrap();
      System.out.println("VulkanGpuSceneTransferPlanSelfTest passed");
   }

   private static void distinguishesInPlaceGrowthAndFirstAllocation() {
      VulkanGpuSceneTransferPlan.Plan transfer = transfer();
      VulkanGpuSceneTransferPlan.TargetCapacity material = target(transfer, Target.MATERIAL_RECORDS);
      require(!material.grows() && material.capacityBytes() == 4096L && material.copyPreviousBytes() == 0L, "in-place descriptor update was incorrectly reallocated");
      VulkanGpuSceneTransferPlan.TargetCapacity instance = target(transfer, Target.INSTANCE_RECORDS);
      require(instance.grows() && instance.requiredBytes() == 5064L && instance.capacityBytes() == 8192L && instance.copyPreviousBytes() == 4096L, "grown target did not preserve the previous buffer generation");
      VulkanGpuSceneTransferPlan.TargetCapacity texture = target(transfer, Target.TEXTURE_PIXELS);
      require(texture.grows() && texture.capacityBytes() == 4096L && texture.copyPreviousBytes() == 0L, "first target allocation did not use bounded minimum capacity");
      require(transfer.allocationGrowthBytes() == 57476L, "allocation budget omitted successor or staging allocations");
   }

   private static void preservesPackedStagingOrderAndOffsets() {
      VulkanGpuSceneTransferPlan.Plan transfer = transfer();
      require(((VulkanGpuSceneTransferPlan.StagedCopy)transfer.copies().get(0)).sourceOffsetBytes() == 0L && ((VulkanGpuSceneTransferPlan.StagedCopy)transfer.copies().get(1)).sourceOffsetBytes() == 64L && ((VulkanGpuSceneTransferPlan.StagedCopy)transfer.copies().get(2)).sourceOffsetBytes() == 128L, "staging source offsets do not match packed payload order");
      byte[] staging = transfer.stagingBytes();
      require(staging.length == 132 && staging[0] == 1 && staging[64] == 2 && staging[128] == 3, "staging payload changed while building copy schedule");
   }

   private static void bootstrapsDescriptorTargetsForAnEmptyInitialScene() {
      VulkanGpuSceneUploadPlanner.Plan empty = new VulkanGpuSceneUploadPlanner.Plan(1L, List.of(), 0L, 0);
      VulkanGpuSceneTransferPlan.Plan transfer = VulkanGpuSceneTransferPlan.build(empty, (ignored) -> 0L);
      require(!transfer.isEmpty() && transfer.targets().size() == Target.values().length && transfer.targets().stream().allMatch((target) -> target.grows() && target.requiredBytes() == 4L && target.capacityBytes() == 4096L) && transfer.copies().isEmpty() && transfer.stagingBytes().length == 0, "empty initial scene did not bootstrap every descriptor target");
      require(transfer.allocationGrowthBytes() == 4096L * (long)Target.values().length, "empty descriptor bootstrap growth was not charged to the memory budget");
   }

   private static void acceptsAnEmptySceneGenerationAfterBootstrap() {
      VulkanGpuSceneUploadPlanner.Plan empty = new VulkanGpuSceneUploadPlanner.Plan(2L, List.of(), 0L, 0);
      VulkanGpuSceneTransferPlan.Plan transfer = VulkanGpuSceneTransferPlan.build(empty, (ignored) -> 4096L);
      require(transfer.isEmpty() && transfer.targets().isEmpty() && transfer.stagingBytes().length == 0, "empty incremental generation fabricated native transfer work");
   }

   private static VulkanGpuSceneTransferPlan.Plan transfer() {
      VulkanGpuSceneUploadPlanner.Chunk material = new VulkanGpuSceneUploadPlanner.Chunk(Target.MATERIAL_RECORDS, 0L, filled(64, (byte)1), 1);
      VulkanGpuSceneUploadPlanner.Chunk instance = new VulkanGpuSceneUploadPlanner.Chunk(Target.INSTANCE_RECORDS, 5000L, filled(64, (byte)2), 1);
      VulkanGpuSceneUploadPlanner.Chunk texture = new VulkanGpuSceneUploadPlanner.Chunk(Target.TEXTURE_PIXELS, 0L, filled(4, (byte)3), 1);
      VulkanGpuSceneUploadPlanner.Plan uploads = new VulkanGpuSceneUploadPlanner.Plan(0L, List.of(material, instance, texture), 132L, 3);
      return VulkanGpuSceneTransferPlan.build(uploads, (target) -> {
         long value10000;
         switch (target) {
            case MATERIAL_RECORDS:
            case INSTANCE_RECORDS:
               value10000 = 4096L;
               break;
            default:
               value10000 = 0L;
         }

         return value10000;
      });
   }

   private static VulkanGpuSceneTransferPlan.TargetCapacity target(VulkanGpuSceneTransferPlan.Plan plan, VulkanGpuSceneUploadPlanner.Target target) {
      return (VulkanGpuSceneTransferPlan.TargetCapacity)plan.targets().stream().filter((candidate) -> candidate.target() == target).findFirst().orElseThrow();
   }

   private static byte[] filled(int length, byte value) {
      byte[] bytes = new byte[length];
      Arrays.fill(bytes, value);
      return bytes;
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}

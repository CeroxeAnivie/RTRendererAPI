package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import java.util.List;

/** Buffer growth, previous-generation copy, and staging offset gate. */
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
        VulkanGpuSceneTransferPlan.TargetCapacity material = target(
                transfer, VulkanGpuSceneUploadPlanner.Target.MATERIAL_RECORDS
        );
        require(!material.grows() && material.capacityBytes() == 4_096L
                        && material.copyPreviousBytes() == 0L,
                "in-place descriptor update was incorrectly reallocated");
        VulkanGpuSceneTransferPlan.TargetCapacity instance = target(
                transfer, VulkanGpuSceneUploadPlanner.Target.INSTANCE_RECORDS
        );
        require(instance.grows() && instance.requiredBytes() == 5_064L
                        && instance.capacityBytes() == 8_192L && instance.copyPreviousBytes() == 4_096L,
                "grown target did not preserve the previous buffer generation");
        VulkanGpuSceneTransferPlan.TargetCapacity texture = target(
                transfer, VulkanGpuSceneUploadPlanner.Target.TEXTURE_PIXELS
        );
        require(texture.grows() && texture.capacityBytes() == 4_096L
                        && texture.copyPreviousBytes() == 0L,
                "first target allocation did not use bounded minimum capacity");
    }

    private static void preservesPackedStagingOrderAndOffsets() {
        VulkanGpuSceneTransferPlan.Plan transfer = transfer();
        require(transfer.copies().get(0).sourceOffsetBytes() == 0L
                        && transfer.copies().get(1).sourceOffsetBytes() == 64L
                        && transfer.copies().get(2).sourceOffsetBytes() == 128L,
                "staging source offsets do not match packed payload order");
        byte[] staging = transfer.stagingBytes();
        require(staging.length == 132 && staging[0] == 1 && staging[64] == 2 && staging[128] == 3,
                "staging payload changed while building copy schedule");
    }

    private static void bootstrapsDescriptorTargetsForAnEmptyInitialScene() {
        VulkanGpuSceneUploadPlanner.Plan empty = new VulkanGpuSceneUploadPlanner.Plan(
                1L, List.of(), 0L, 0
        );
        VulkanGpuSceneTransferPlan.Plan transfer = VulkanGpuSceneTransferPlan.build(empty, ignored -> 0L);
        require(!transfer.isEmpty()
                        && transfer.targets().size() == VulkanGpuSceneUploadPlanner.Target.values().length
                        && transfer.targets().stream().allMatch(target -> target.grows()
                        && target.requiredBytes() == Integer.BYTES
                        && target.capacityBytes() == 4_096L)
                        && transfer.copies().isEmpty() && transfer.stagingBytes().length == 0,
                "empty initial scene did not bootstrap every descriptor target");
    }

    private static void acceptsAnEmptySceneGenerationAfterBootstrap() {
        VulkanGpuSceneUploadPlanner.Plan empty = new VulkanGpuSceneUploadPlanner.Plan(
                2L, List.of(), 0L, 0
        );
        VulkanGpuSceneTransferPlan.Plan transfer = VulkanGpuSceneTransferPlan.build(empty, ignored -> 4_096L);
        require(transfer.isEmpty() && transfer.targets().isEmpty() && transfer.stagingBytes().length == 0,
                "empty incremental generation fabricated native transfer work");
    }

    private static VulkanGpuSceneTransferPlan.Plan transfer() {
        VulkanGpuSceneUploadPlanner.Chunk material = new VulkanGpuSceneUploadPlanner.Chunk(
                VulkanGpuSceneUploadPlanner.Target.MATERIAL_RECORDS, 0L, filled(64, (byte) 1), 1
        );
        VulkanGpuSceneUploadPlanner.Chunk instance = new VulkanGpuSceneUploadPlanner.Chunk(
                VulkanGpuSceneUploadPlanner.Target.INSTANCE_RECORDS, 5_000L, filled(64, (byte) 2), 1
        );
        VulkanGpuSceneUploadPlanner.Chunk texture = new VulkanGpuSceneUploadPlanner.Chunk(
                VulkanGpuSceneUploadPlanner.Target.TEXTURE_PIXELS, 0L, filled(4, (byte) 3), 1
        );
        VulkanGpuSceneUploadPlanner.Plan uploads = new VulkanGpuSceneUploadPlanner.Plan(
                0L, List.of(material, instance, texture), 132L, 3
        );
        return VulkanGpuSceneTransferPlan.build(uploads, target -> switch (target) {
            case MATERIAL_RECORDS, INSTANCE_RECORDS -> 4_096L;
            default -> 0L;
        });
    }

    private static VulkanGpuSceneTransferPlan.TargetCapacity target(
            VulkanGpuSceneTransferPlan.Plan plan,
            VulkanGpuSceneUploadPlanner.Target target
    ) {
        return plan.targets().stream().filter(candidate -> candidate.target() == target)
                .findFirst().orElseThrow();
    }

    private static byte[] filled(int length, byte value) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

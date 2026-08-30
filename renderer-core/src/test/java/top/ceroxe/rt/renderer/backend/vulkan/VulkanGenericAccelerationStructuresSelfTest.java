package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.List;
import top.ceroxe.rt.renderer.api.AccelerationStructureKind;

/** Pure contract checks for the immutable generic Vulkan AS build shape. */
public final class VulkanGenericAccelerationStructuresSelfTest {
    private VulkanGenericAccelerationStructuresSelfTest() { }

    public static void main(String[] args) {
        testResidentTlasDependencies();
        VulkanGenericAccelerationStructures.BuildShape blas =
                new VulkanGenericAccelerationStructures.BuildShape(
                        AccelerationStructureKind.BOTTOM_LEVEL, List.of(1, 4)
                );
        VulkanGenericAccelerationStructures.BuildShape sameBlas =
                new VulkanGenericAccelerationStructures.BuildShape(
                        AccelerationStructureKind.BOTTOM_LEVEL, List.of(1, 4)
                );
        VulkanGenericAccelerationStructures.BuildShape changedPrimitiveCount =
                new VulkanGenericAccelerationStructures.BuildShape(
                        AccelerationStructureKind.BOTTOM_LEVEL, List.of(1, 5)
                );
        VulkanGenericAccelerationStructures.BuildShape changedGeometryCount =
                new VulkanGenericAccelerationStructures.BuildShape(
                        AccelerationStructureKind.BOTTOM_LEVEL, List.of(1, 4, 2)
                );
        VulkanGenericAccelerationStructures.BuildShape tlasOneInstance =
                new VulkanGenericAccelerationStructures.BuildShape(
                        AccelerationStructureKind.TOP_LEVEL, List.of(1)
                );
        VulkanGenericAccelerationStructures.BuildShape tlasTwoInstances =
                new VulkanGenericAccelerationStructures.BuildShape(
                        AccelerationStructureKind.TOP_LEVEL, List.of(2)
                );

        require(blas.compatibleWith(sameBlas), "identical BLAS shape was rejected");
        require(!blas.compatibleWith(changedPrimitiveCount), "primitive capacity change was accepted");
        require(!blas.compatibleWith(changedGeometryCount), "geometry count change was accepted");
        require(!tlasOneInstance.compatibleWith(tlasTwoInstances), "TLAS instance count change was accepted");
        expectIllegalArgument(() -> new VulkanGenericAccelerationStructures.BuildShape(
                AccelerationStructureKind.BOTTOM_LEVEL, List.of()
        ));
        expectIllegalArgument(() -> new VulkanGenericAccelerationStructures.BuildShape(
                AccelerationStructureKind.TOP_LEVEL, List.of(0)
        ));
        System.out.println("VulkanGenericAccelerationStructuresSelfTest passed");
    }

    private static void testResidentTlasDependencies() {
        VulkanGenericAccelerationStructures.DependencyGraph<String> graph =
                new VulkanGenericAccelerationStructures.DependencyGraph<>();
        graph.replace("tlas", java.util.Set.of("blas-a", "blas-b"));
        require(graph.referencing("blas-a").equals(java.util.Set.of("tlas")),
                "resident TLAS reverse dependency was not published");
        graph.replace("tlas", java.util.Set.of("blas-b"));
        require(graph.referencing("blas-a").isEmpty(),
                "TLAS replacement retained a stale BLAS dependency");
        require(graph.referencing("blas-b").equals(java.util.Set.of("tlas")),
                "TLAS replacement lost its current BLAS dependency");
        graph.remove("tlas");
        graph.remove("tlas");
        require(graph.referencing("blas-b").isEmpty(),
                "TLAS retirement did not release its BLAS dependency");
        graph.replace("tlas-rollback", java.util.Set.of("blas-a"));
        graph.clear();
        require(graph.referencing("blas-a").isEmpty(),
                "dependency rollback/clear left stale reverse references");
    }

    private static void expectIllegalArgument(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected IllegalArgumentException");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

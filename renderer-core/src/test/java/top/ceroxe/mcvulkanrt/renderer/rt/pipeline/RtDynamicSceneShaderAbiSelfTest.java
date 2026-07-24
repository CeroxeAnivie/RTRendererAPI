package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import top.ceroxe.mcvulkanrt.renderer.DynamicRenderScene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Verifies the CPU-authored scene flags that gate shader-side TLAS traversal. */
public final class RtDynamicSceneShaderAbiSelfTest {
    private static final int INFO_FLAGS_BYTE_OFFSET = 3 * Integer.BYTES;

    private RtDynamicSceneShaderAbiSelfTest() {
    }

    public static void main(String[] args) {
        require(flags(DynamicRenderScene.empty()) == 0, "empty scene must publish no GPU scene flags");

        DynamicRenderScene activeWithoutTlas = sceneWithPrimitives(List.of());
        require(
                flags(activeWithoutTlas) == RtDynamicSceneHeaderWriter.ACTIVE_FLAG,
                "active scene without triangle geometry must not request dynamic TLAS traversal"
        );

        DynamicRenderScene analyticScene = sceneWithPrimitives(List.of(primitive(
                DynamicRenderScene.PrimitiveGeometryKind.CROSS_PLANE
        )));
        require(
                flags(analyticScene) == RtDynamicSceneHeaderWriter.ACTIVE_FLAG,
                "analytic primitives must remain on the shader analytic lane"
        );

        DynamicRenderScene tlasScene = sceneWithPrimitives(List.of(primitive(
                DynamicRenderScene.PrimitiveGeometryKind.MODEL
        )));
        require(
                flags(tlasScene) == (RtDynamicSceneHeaderWriter.ACTIVE_FLAG
                        | RtDynamicSceneHeaderWriter.TLAS_GEOMETRY_FLAG),
                "triangle model geometry must enable dynamic TLAS traversal"
        );

        System.out.println("RtDynamicSceneShaderAbiSelfTest passed");
    }

    private static DynamicRenderScene sceneWithPrimitives(
            List<DynamicRenderScene.DynamicPrimitive> primitives
    ) {
        return new DynamicRenderScene(
                1L,
                primitives,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static DynamicRenderScene.DynamicPrimitive primitive(
            DynamicRenderScene.PrimitiveGeometryKind geometryKind
    ) {
        return new DynamicRenderScene.DynamicPrimitive(
                1L,
                DynamicRenderScene.PrimitiveKind.ENTITY,
                geometryKind,
                0.0D,
                0.0D,
                0.0D,
                0.0F,
                0.0F,
                0.0F,
                1.0F,
                0,
                0,
                0,
                true,
                "shader-abi-self-test"
        );
    }

    private static int flags(DynamicRenderScene scene) {
        byte[] encoded = RtRayTracingPipeline.packDynamicScene(scene);
        return ByteBuffer.wrap(encoded)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt(INFO_FLAGS_BYTE_OFFSET);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

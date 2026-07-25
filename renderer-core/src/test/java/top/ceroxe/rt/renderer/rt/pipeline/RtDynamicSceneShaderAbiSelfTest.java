package top.ceroxe.rt.renderer.rt.pipeline;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import top.ceroxe.rt.renderer.DynamicRenderScene;
import top.ceroxe.rt.renderer.DynamicRenderScene.PrimitiveGeometryKind;
import top.ceroxe.rt.renderer.DynamicRenderScene.PrimitiveKind;

public final class RtDynamicSceneShaderAbiSelfTest {
   private static final int INFO_FLAGS_BYTE_OFFSET = 12;

   private RtDynamicSceneShaderAbiSelfTest() {
   }

   public static void main(String[] args) {
      require(flags(DynamicRenderScene.empty()) == 0, "empty scene must publish no GPU scene flags");
      DynamicRenderScene activeWithoutTlas = sceneWithPrimitives(List.of());
      require(flags(activeWithoutTlas) == 1, "active scene without triangle geometry must not request dynamic TLAS traversal");
      DynamicRenderScene analyticScene = sceneWithPrimitives(List.of(primitive(PrimitiveGeometryKind.CROSS_PLANE)));
      require(flags(analyticScene) == 1, "analytic primitives must remain on the shader analytic lane");
      DynamicRenderScene tlasScene = sceneWithPrimitives(List.of(primitive(PrimitiveGeometryKind.MODEL)));
      require(flags(tlasScene) == 3, "triangle model geometry must enable dynamic TLAS traversal");
      System.out.println("RtDynamicSceneShaderAbiSelfTest passed");
   }

   private static DynamicRenderScene sceneWithPrimitives(List<DynamicRenderScene.DynamicPrimitive> primitives) {
      return new DynamicRenderScene(1L, primitives, List.of(), List.of(), List.of(), List.of());
   }

   private static DynamicRenderScene.DynamicPrimitive primitive(DynamicRenderScene.PrimitiveGeometryKind geometryKind) {
      return new DynamicRenderScene.DynamicPrimitive(1L, PrimitiveKind.ENTITY, geometryKind, 0.0, 0.0, 0.0, 0.0F, 0.0F, 0.0F, 1.0F, 0, 0, 0, true, "shader-abi-self-test");
   }

   private static int flags(DynamicRenderScene scene) {
      byte[] encoded = RtRayTracingPipeline.packDynamicScene(scene);
      return ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getInt(12);
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}

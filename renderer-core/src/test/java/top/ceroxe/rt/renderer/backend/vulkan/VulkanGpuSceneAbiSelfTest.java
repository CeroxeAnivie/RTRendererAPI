package top.ceroxe.rt.renderer.backend.vulkan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import top.ceroxe.rt.renderer.api.AffineTransform;
import top.ceroxe.rt.renderer.api.CardinalLightingState;
import top.ceroxe.rt.renderer.api.DirectionalDiffuseState;
import top.ceroxe.rt.renderer.api.MaterialAsset;
import top.ceroxe.rt.renderer.api.MeshAsset;
import top.ceroxe.rt.renderer.api.InstanceRenderState;
import top.ceroxe.rt.renderer.api.OutlineStyle;
import top.ceroxe.rt.renderer.api.PrimitiveInstance;
import top.ceroxe.rt.renderer.api.SceneInstance;
import top.ceroxe.rt.renderer.api.SceneLight;
import top.ceroxe.rt.renderer.api.TextureAsset;
import top.ceroxe.rt.renderer.api.UvTransform;
import top.ceroxe.rt.renderer.api.SurfaceOverlayState;
import top.ceroxe.rt.renderer.api.MaterialAsset.BlendMode;
import top.ceroxe.rt.renderer.api.MaterialAsset.ShadingModel;
import top.ceroxe.rt.renderer.api.SceneInstance.Mobility;
import top.ceroxe.rt.renderer.api.TextureAsset.AddressMode;
import top.ceroxe.rt.renderer.api.TextureAsset.ColorSpace;
import top.ceroxe.rt.renderer.api.TextureAsset.Filter;
import top.ceroxe.rt.renderer.backend.vulkan.VulkanGpuSceneUploadPlanner.Target;

public final class VulkanGpuSceneAbiSelfTest {
   private static final String SHADER_ABI_RESOURCE = "assets/rtrenderer/shaders/gpuscene/gpuscene_abi.glsl";
   private static final Pattern INTEGER_DEFINE = Pattern.compile("(?m)^#define\\s+(GPU_SCENE_[A-Z0-9_]+)\\s+([0-9]+)u?\\s*$");

   private VulkanGpuSceneAbiSelfTest() {
   }

   public static void main(String[] arguments) {
      packsTextureAndMaterialReferences();
      packsGeometryAndInstanceReferences();
      verifiesDirectionalDiffuseNumericalOracle();
      preservesDoublePrecisionLightPositions();
      rejectsUnresolvedAndMismatchedResources();
      matchesShaderContractExactly();
      System.out.println("VulkanGpuSceneAbiSelfTest passed");
   }

   private static void packsTextureAndMaterialReferences() {
      TextureAsset texture = TextureAsset.builder(10L, 2, 1).colorSpace(ColorSpace.SRGB).addressModes(AddressMode.REPEAT, AddressMode.CLAMP_TO_EDGE).filter(Filter.LINEAR).pixelsRgba8(new byte[8]).build();
      int[] textureRecord = VulkanGpuSceneAbi.packTexture(texture, new VulkanGpuSceneAbi.TexturePlacement(4294967296L, 8L));
      require(textureRecord.length == 14, "texture descriptor stride changed");
      require(textureRecord[0] != 0 && textureRecord[1] == 0 && textureRecord[2] == 1, "texture descriptor lost its active flag or 64-bit pixel offset");
      require(textureRecord[3] == 2 && textureRecord[4] == 1 && textureRecord[5] == 8, "texture descriptor lost extent or row stride");
      require(textureRecord[12] == 1, "legacy texture descriptor must expose one mip level");
      MaterialAsset material = MaterialAsset.builder(20L).blendMode(BlendMode.MASKED).baseColorRgba8(-12307678).baseColorTextureId(10L).metallicRoughnessTextureId(11L).emissive(-16711165, 2.0F).alphaCutoff(0.25F).roughness(0.75F).metallic(0.5F).transmission(0.0F).indexOfRefraction(1.45F).doubleSided(true).shadingModel(ShadingModel.LIGHTMAP_MODULATED).build();
      int[] materialRecord = VulkanGpuSceneAbi.packMaterial(material, (id) -> {
         byte value10000;
         switch ((int)id) {
            case 10 -> value10000 = 3;
            case 11 -> value10000 = 7;
            default -> value10000 = -1;
         }

         return value10000;
      });
      require(materialRecord.length == 16, "material descriptor stride changed");
      require(materialRecord[2] == 3 && materialRecord[3] == -1 && materialRecord[4] == 7 && materialRecord[5] == -1, "material texture identities were not resolved to stable slots");
      require(Float.intBitsToFloat(materialRecord[9]) == 0.75F && Float.intBitsToFloat(materialRecord[12]) == 1.45F, "material PBR scalars changed during packing");
      require((materialRecord[0] >> 7 & 3) == ShadingModel.LIGHTMAP_MODULATED.ordinal(), "material shading model was not encoded into GPU flags");
      MaterialAsset multiplyOverlay = MaterialAsset.builder(21L)
            .blendMode(BlendMode.TRANSLUCENT)
            .surfaceOverlay(SurfaceOverlayState.depthEqual(
                  0.002F, SurfaceOverlayState.CompositionMode.MULTIPLY
            ))
            .build();
      int[] multiplyRecord = VulkanGpuSceneAbi.packMaterial(multiplyOverlay, ignored -> -1);
      require((multiplyRecord[0] >> VulkanGpuSceneAbi.OVERLAY_COMPOSITION_MODE_SHIFT
                  & VulkanGpuSceneAbi.OVERLAY_COMPOSITION_MODE_MASK)
                  == SurfaceOverlayState.CompositionMode.MULTIPLY.ordinal(),
            "multiply overlay composition was not encoded into GPU flags");
   }

   private static void packsGeometryAndInstanceReferences() {
      MeshAsset mesh = triangle();
      VulkanGpuSceneAbi.GeometryPlacement placement = new VulkanGpuSceneAbi.GeometryPlacement(0L, -1L, -1L, -1L, -1L, -1L, 36L, 48L);
      int[] meshRecord = VulkanGpuSceneAbi.packMesh(mesh, placement);
      require(meshRecord.length == 18 && meshRecord[16] == 3 && meshRecord[17] == 1, "mesh descriptor lost geometry counts");
      require(meshRecord[2] == -1 && meshRecord[3] == -1, "absent geometry stream did not retain the canonical 64-bit sentinel");
      SceneInstance instance = SceneInstance.builder(40L, 30L).mobility(Mobility.DYNAMIC).visibilityMask(127).surfaceVisibility(0.375F).lightmapCoordinates(32, 176).build();
      int[] instanceRecord = VulkanGpuSceneAbi.packInstance(instance, (id) -> id == 30L ? 9 : -1);
      require(instanceRecord.length == 57 && instanceRecord[0] == 9 && instanceRecord[2] == 127, "instance descriptor lost mesh slot or visibility mask");
      require(Float.intBitsToFloat(instanceRecord[3]) == 1.0F && Float.intBitsToFloat(instanceRecord[8]) == 1.0F, "instance affine transform changed during packing");
      require(Float.intBitsToFloat(instanceRecord[15]) == 0.375F, "instance surface visibility did not occupy the reserved ABI word");
      require(instanceRecord[16] == 0x00b0_0020, "instance lightmap coordinates did not occupy the final ABI word");
      require(Float.intBitsToFloat(instanceRecord[17]) == 1.0F
            && Float.intBitsToFloat(instanceRecord[21]) == 1.0F,
            "identity UV transform was not encoded into instance state");
      require(instanceRecord[23] == -1 && instanceRecord[24] == 0
            && instanceRecord[25] == 0 && instanceRecord[26] == 0,
            "default receiver/object masks changed during instance packing");
      for (int element = 0; element < 12; ++element) {
         require(instanceRecord[28 + element] == instanceRecord[3 + element],
               "default previous transform diverged at element " + element);
      }
      require(instanceRecord[40] == 0 && instanceRecord[41] == 0,
            "default instance motion revision must be zero");
      for (int direction = 0; direction < 6; ++direction) {
         require(Float.intBitsToFloat(instanceRecord[42 + direction]) == 1.0F,
               "default cardinal lighting must remain a no-op at direction " + direction);
      }
      for (int element = 48; element < 57; ++element) {
         require(instanceRecord[element] == 0,
               "disabled directional diffuse payload must remain zero at word " + element);
      }

      AffineTransform previousTransform = new AffineTransform(new float[]{
            1.0F, 0.0F, 0.0F, -3.0F,
            0.0F, 1.0F, 0.0F, 2.0F,
            0.0F, 0.0F, 1.0F, 7.0F
      });
      int[] movingRecord = VulkanGpuSceneAbi.packInstance(
            instance, previousTransform, 0x0000_0002_0000_0001L, id -> id == 30L ? 9 : -1
      );
      for (int element = 0; element < 12; ++element) {
         require(movingRecord[28 + element]
                     == Float.floatToRawIntBits(previousTransform.elements().get(element)),
               "explicit previous transform changed at element " + element);
      }
      require(movingRecord[40] == 1 && movingRecord[41] == 2,
            "instance motion revision lost its exact 64-bit value");

      InstanceRenderState frameState = InstanceRenderState.builder()
            .uvTransform(UvTransform.scaleAndOffset(2.0F, 3.0F, 0.25F, -0.5F))
            .surfaceMask(0x12)
            .overlayReceiverMask(0x34)
            .objectMask(0x56)
            .outline(OutlineStyle.of(0xff20_40ff, 2.0F))
            .cardinalLighting(CardinalLightingState.worldSpace(
                  0.4F, 0.5F, 0.6F, 0.7F, 0.8F, 0.9F
            ))
            .build();
      PrimitiveInstance primitive = PrimitiveInstance.builder(30L)
            .transform(instance.transform())
            .previousTransform(previousTransform)
            .renderState(frameState)
            .surfaceVisibility(0.5F)
            .build();
      int[] primitiveRecord = VulkanGpuSceneAbi.packPrimitive(primitive, id -> id == 30L ? 9 : -1);
      require(primitiveRecord.length == 57 && primitiveRecord[0] == 9
                  && primitiveRecord[1] == (VulkanGpuSceneAbi.FLAG_ACTIVE
                  | VulkanGpuSceneAbi.FLAG_CASTS_SHADOW | VulkanGpuSceneAbi.FLAG_DYNAMIC
                  | VulkanGpuSceneAbi.FLAG_FRAME_LOCAL
                  | VulkanGpuSceneAbi.INSTANCE_CARDINAL_LIGHTING_ENABLED
                  | VulkanGpuSceneAbi.INSTANCE_CARDINAL_LIGHTING_WORLD_SPACE),
            "frame primitive did not use the compact dynamic instance ABI");
      require(Float.intBitsToFloat(primitiveRecord[17]) == 2.0F
                  && Float.intBitsToFloat(primitiveRecord[21]) == 3.0F
                  && primitiveRecord[23] == 0x12 && primitiveRecord[24] == 0x34
                  && primitiveRecord[25] == 0x56 && primitiveRecord[26] == 0xff20_40ff
                  && Float.intBitsToFloat(primitiveRecord[27]) == 2.0F,
            "frame primitive render state drifted while packing");
      for (int element = 0; element < 12; ++element) {
         require(primitiveRecord[28 + element]
                     == Float.floatToRawIntBits(previousTransform.elements().get(element)),
               "transient primitive previous transform changed at element " + element);
      }
      require(primitiveRecord[40] == 0 && primitiveRecord[41] == 0,
            "transient primitive motion revision must remain zero without stable identity");
      float[] cardinalMultipliers = {0.4F, 0.5F, 0.6F, 0.7F, 0.8F, 0.9F};
      for (int direction = 0; direction < cardinalMultipliers.length; ++direction) {
         require(primitiveRecord[42 + direction]
                     == Float.floatToRawIntBits(cardinalMultipliers[direction]),
               "transient primitive cardinal multiplier changed at direction " + direction);
      }

      DirectionalDiffuseState directionalDiffuse = DirectionalDiffuseState.builder()
            .coordinateSpace(DirectionalDiffuseState.CoordinateSpace.WORLD)
            .firstDirection(0.0F, 0.6F, 0.8F)
            .firstIntensity(0.6F)
            .secondDirection(-0.8F, 0.0F, 0.6F)
            .secondIntensity(0.5F)
            .ambient(0.4F)
            .backFacePolicy(DirectionalDiffuseState.BackFacePolicy.FLIP_ON_BACK_FACE)
            .build();
      SceneInstance directionalInstance = SceneInstance.builder(41L, 30L)
            .renderState(InstanceRenderState.builder()
                  .directionalDiffuse(directionalDiffuse)
                  .build())
            .build();
      int[] directionalRecord = VulkanGpuSceneAbi.packInstance(
            directionalInstance, id -> id == 30L ? 9 : -1
      );
      require((directionalRecord[1] & VulkanGpuSceneAbi.INSTANCE_DIRECTIONAL_DIFFUSE_ENABLED) != 0
                  && (directionalRecord[1]
                  & VulkanGpuSceneAbi.INSTANCE_DIRECTIONAL_DIFFUSE_WORLD_SPACE) != 0
                  && (directionalRecord[1]
                  & VulkanGpuSceneAbi.INSTANCE_DIRECTIONAL_DIFFUSE_FLIP_BACK_FACE) != 0
                  && (directionalRecord[1]
                  & VulkanGpuSceneAbi.INSTANCE_CARDINAL_LIGHTING_ENABLED) == 0,
            "directional diffuse flags lost enabled, coordinate-space, or back-face semantics");
      float[] directionalPayload = {
            0.0F, 0.6F, 0.8F, -0.8F, 0.0F, 0.6F, 0.4F, 0.6F, 0.5F
      };
      for (int element = 0; element < directionalPayload.length; ++element) {
         require(directionalRecord[48 + element]
                     == Float.floatToRawIntBits(directionalPayload[element]),
               "directional diffuse payload changed at element " + element);
      }
      require(VulkanGpuSceneAbi.TRANSIENT_INSTANCE_BIT == 0x0080_0000
                  && VulkanGpuSceneAbi.TRANSIENT_INSTANCE_BIT == top.ceroxe.rt.renderer.api.FramePrimitiveBatch.MAX_PRIMITIVES,
            "transient custom-index namespace no longer matches the public batch limit");
   }

   private static void preservesDoublePrecisionLightPositions() {
      SceneLight light = SceneLight.point(50L, 3.0000000125E7, -2000000.5, 0.25).color(1.0F, 0.5F, 0.25F).intensity(100.0F).range(16.0F).build();
      int[] record = VulkanGpuSceneAbi.packLight(light);
      require(record.length == 24, "light descriptor stride changed");
      require(Double.longBitsToDouble(join(record[1], record[2])) == light.x() && Double.longBitsToDouble(join(record[3], record[4])) == light.y(), "persistent light position was truncated to float precision");
   }

   private static void verifiesDirectionalDiffuseNumericalOracle() {
      DirectionalDiffuseState objectLighting = DirectionalDiffuseState.builder()
            .coordinateSpace(DirectionalDiffuseState.CoordinateSpace.OBJECT)
            .firstDirection(0.2F, 1.0F, -0.7F)
            .firstIntensity(0.6F)
            .secondDirection(-0.2F, 1.0F, 0.7F)
            .secondIntensity(0.6F)
            .ambient(0.4F)
            .backFacePolicy(DirectionalDiffuseState.BackFacePolicy.FLIP_ON_BACK_FACE)
            .build();
      InstanceRenderState renderState = InstanceRenderState.builder()
            .directionalDiffuse(objectLighting)
            .build();
      SceneInstance persistent = SceneInstance.builder(60L, 30L)
            .renderState(renderState)
            .build();
      PrimitiveInstance frameLocal = PrimitiveInstance.builder(30L)
            .renderState(renderState)
            .build();
      int[] persistentWords = VulkanGpuSceneAbi.packInstance(persistent, ignored -> 9);
      int[] frameWords = VulkanGpuSceneAbi.packPrimitive(frameLocal, ignored -> 9);
      int directionalFlags = VulkanGpuSceneAbi.INSTANCE_DIRECTIONAL_DIFFUSE_ENABLED
            | VulkanGpuSceneAbi.INSTANCE_DIRECTIONAL_DIFFUSE_WORLD_SPACE
            | VulkanGpuSceneAbi.INSTANCE_DIRECTIONAL_DIFFUSE_FLIP_BACK_FACE;
      require((persistentWords[1] & directionalFlags) ==
                  (VulkanGpuSceneAbi.INSTANCE_DIRECTIONAL_DIFFUSE_ENABLED
                  | VulkanGpuSceneAbi.INSTANCE_DIRECTIONAL_DIFFUSE_FLIP_BACK_FACE),
            "object-space directional diffuse flags changed in the persistent lane");
      require((persistentWords[1] & directionalFlags) == (frameWords[1] & directionalFlags)
                  && Arrays.equals(
                  Arrays.copyOfRange(persistentWords, 48, 57),
                  Arrays.copyOfRange(frameWords, 48, 57)),
            "persistent and frame-local lanes serialized different directional diffuse payloads");

      float[] oblique = {0.8F, 0.6F, 0.0F};
      float[] unlit = {0.0F, 0.0F, -1.0F};
      requireNear(evaluateDirectionalDiffuse(persistentWords, oblique, unlit, false),
            0.9820855F, 1.0E-6F,
            "reference oblique object normal changed its numerical result");
      requireNear(evaluateDirectionalDiffuse(persistentWords, oblique, unlit, true),
            0.4F, 1.0E-6F,
            "back-face flip failed to return the reference ambient floor");
      requireNear(evaluateDirectionalDiffuse(persistentWords, new float[]{0.0F, 1.0F, 0.0F},
                  unlit, false),
            1.0F, 0.0F,
            "directional diffuse failed to clamp a saturated two-light contribution");

      DirectionalDiffuseState worldLighting = objectLighting.toBuilder()
            .coordinateSpace(DirectionalDiffuseState.CoordinateSpace.WORLD)
            .build();
      SceneInstance worldInstance = SceneInstance.builder(61L, 30L)
            .renderState(InstanceRenderState.builder()
                  .directionalDiffuse(worldLighting)
                  .build())
            .build();
      int[] worldWords = VulkanGpuSceneAbi.packInstance(worldInstance, ignored -> 9);
      requireNear(evaluateDirectionalDiffuse(worldWords, unlit, oblique, false),
            0.9820855F, 1.0E-6F,
            "world-space directional diffuse selected the object-space normal");
      requireNear(evaluateDirectionalDiffuse(persistentWords, oblique, oblique, false),
            0.9820855F, 1.0E-6F,
            "geometric-normal fallback changed the oblique Lambert result");
   }

   private static float evaluateDirectionalDiffuse(
         int[] words,
         float[] objectNormal,
         float[] worldNormal,
         boolean backFace
   ) {
      int flags = words[VulkanGpuSceneAbi.INSTANCE_FLAGS_WORD];
      if ((flags & VulkanGpuSceneAbi.INSTANCE_DIRECTIONAL_DIFFUSE_ENABLED) == 0) return 1.0F;
      float[] normal = (flags & VulkanGpuSceneAbi.INSTANCE_DIRECTIONAL_DIFFUSE_WORLD_SPACE) != 0
            ? worldNormal.clone() : objectNormal.clone();
      if (backFace
            && (flags & VulkanGpuSceneAbi.INSTANCE_DIRECTIONAL_DIFFUSE_FLIP_BACK_FACE) != 0) {
         normal[0] = -normal[0];
         normal[1] = -normal[1];
         normal[2] = -normal[2];
      }
      float first = Math.max(dot(words,
            VulkanGpuSceneAbi.INSTANCE_DIRECTIONAL_DIFFUSE_FIRST_DIRECTION_WORD, normal), 0.0F);
      float second = Math.max(dot(words,
            VulkanGpuSceneAbi.INSTANCE_DIRECTIONAL_DIFFUSE_SECOND_DIRECTION_WORD, normal), 0.0F);
      float value = real(words, VulkanGpuSceneAbi.INSTANCE_DIRECTIONAL_DIFFUSE_AMBIENT_WORD)
            + real(words, VulkanGpuSceneAbi.INSTANCE_DIRECTIONAL_DIFFUSE_FIRST_INTENSITY_WORD)
            * first
            + real(words, VulkanGpuSceneAbi.INSTANCE_DIRECTIONAL_DIFFUSE_SECOND_INTENSITY_WORD)
            * second;
      return Math.min(Math.max(value, 0.0F), 1.0F);
   }

   private static float dot(int[] words, int directionWord, float[] normal) {
      return real(words, directionWord) * normal[0]
            + real(words, directionWord + 1) * normal[1]
            + real(words, directionWord + 2) * normal[2];
   }

   private static float real(int[] words, int index) {
      return Float.intBitsToFloat(words[index]);
   }

   private static void requireNear(float actual, float expected, float tolerance, String message) {
      require(Math.abs(actual - expected) <= tolerance,
            message + ": expected=" + expected + ", actual=" + actual);
   }

   private static void rejectsUnresolvedAndMismatchedResources() {
      MaterialAsset unresolved = MaterialAsset.builder(60L).blendMode(BlendMode.OPAQUE).baseColorRgba8(-1).baseColorTextureId(999L).emissive(0, 0.0F).alphaCutoff(0.5F).roughness(1.0F).metallic(0.0F).transmission(0.0F).indexOfRefraction(1.5F).doubleSided(false).shadingModel(ShadingModel.PHYSICALLY_BASED).build();
      expect(IllegalArgumentException.class, () -> VulkanGpuSceneAbi.packMaterial(unresolved, (ignored) -> -1));
      expect(IllegalArgumentException.class, () -> VulkanGpuSceneAbi.packMesh(triangle(), new VulkanGpuSceneAbi.GeometryPlacement(0L, 64L, -1L, -1L, -1L, -1L, 36L, 48L)));
      require(VulkanGpuSceneAbi.recordByteOffset(16384, 16) == 1048576L, "record byte offset overflowed or changed stride");
   }

   private static void matchesShaderContractExactly() {
      String source = readUtf8Resource("assets/rtrenderer/shaders/gpuscene/gpuscene_abi.glsl");
      Matcher matcher = INTEGER_DEFINE.matcher(source);
      Map<String, Integer> actual = new HashMap<>();

      while(matcher.find()) {
         Integer previous = (Integer)actual.put(matcher.group(1), Integer.parseInt(matcher.group(2)));
         require(previous == null, "shader ABI contains duplicate define " + matcher.group(1));
      }

      Map<String, Integer> expected = VulkanGpuSceneAbi.shaderDefines();
      boolean condition10000 = actual.equals(expected);
      String details10001 = String.valueOf(expected);
      require(condition10000, "Java/GLSL GPUScene ABI drift: expected=" + details10001 + ", actual=" + String.valueOf(actual));
      boolean[] occupied = new boolean[31];
      occupied[0] = true;
      occupied[1] = true;
      occupied[2] = true;
      occupied[17] = true;
      occupied[18] = true;
      occupied[19] = true;
      occupied[20] = true;
      occupied[21] = true;
      occupied[22] = true;
      occupied[23] = true;
      occupied[24] = true;
      occupied[25] = true;
      occupied[26] = true;
      occupied[27] = true;
      occupied[28] = true;
      occupied[29] = true;
      occupied[30] = true;

      for(VulkanGpuSceneUploadPlanner.Target target : Target.values()) {
         int binding = VulkanGpuSceneAbi.descriptorBinding(target);
         require(binding >= 0 && binding < occupied.length, "GPUScene target binding is outside the layout");
         require(!occupied[binding], "GPUScene descriptor binding aliases another resource: " + binding);
         occupied[binding] = true;
      }

      for(int binding = 0; binding < occupied.length; ++binding) {
         require(occupied[binding], "GPUScene descriptor layout contains an unassigned binding " + binding);
      }

   }

   private static String readUtf8Resource(String path) {
      try {
         InputStream stream = VulkanGpuSceneAbiSelfTest.class.getClassLoader().getResourceAsStream(path);

         String details2;
         try {
            if (stream == null) {
               throw new AssertionError("missing shader ABI resource " + path);
            }

            details2 = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
         } catch (Throwable value5) {
            if (stream != null) {
               try {
                  stream.close();
               } catch (Throwable value4) {
                  value5.addSuppressed(value4);
               }
            }

            throw value5;
         }

         if (stream != null) {
            stream.close();
         }

         return details2;
      } catch (IOException failure) {
         throw new AssertionError("failed to read shader ABI resource " + path, failure);
      }
   }

   private static MeshAsset triangle() {
      return MeshAsset.triangles(30L, new float[]{0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F}, new int[]{0, 1, 2}, 20L);
   }

   private static long join(int low, int high) {
      return Integer.toUnsignedLong(low) | (long)high << 32;
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

   @FunctionalInterface
   private interface ThrowingRunnable {
      void run() throws Throwable;
   }
}

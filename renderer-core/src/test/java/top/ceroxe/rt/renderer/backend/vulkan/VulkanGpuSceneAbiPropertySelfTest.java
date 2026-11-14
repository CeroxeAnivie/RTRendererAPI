package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.SplittableRandom;
import top.ceroxe.rt.renderer.api.AffineTransform;
import top.ceroxe.rt.renderer.api.CardinalLightingState;
import top.ceroxe.rt.renderer.api.DirectionalDiffuseState;
import top.ceroxe.rt.renderer.api.InstanceRenderState;
import top.ceroxe.rt.renderer.api.OutlineStyle;
import top.ceroxe.rt.renderer.api.SceneInstance;
import top.ceroxe.rt.renderer.api.SceneLight;
import top.ceroxe.rt.renderer.api.TextureAsset;
import top.ceroxe.rt.renderer.api.UvTransform;
import top.ceroxe.rt.renderer.api.SceneInstance.Mobility;

public final class VulkanGpuSceneAbiPropertySelfTest {
   private static final long SEED = 5932457066773103954L;
   private static final int TRIALS = 4096;

   private VulkanGpuSceneAbiPropertySelfTest() {
   }

   public static void main(String[] arguments) {
      SplittableRandom random = new SplittableRandom(5932457066773103954L);
      verifiesRecordOffsets(random.split());
      verifiesTextureSerialization(random.split());
      verifiesLightSerialization(random.split());
      verifiesInstanceSerialization(random.split());
      fuzzesMalformedBoundaries(random.split());
      System.out.println("VulkanGpuSceneAbiPropertySelfTest passed: seed=" + Long.toUnsignedString(5932457066773103954L) + ", trials=4096");
   }

   private static void verifiesRecordOffsets(SplittableRandom random) {
      for(int trial = 0; trial < 4096; ++trial) {
         int slot = random.nextInt(2147483647);
         int words = random.nextInt(1, 1025);
         long expected = (long)slot * (long)words * 4L;
         long actual = VulkanGpuSceneAbi.recordByteOffset(slot, words);
         require(actual == expected, trial, "record byte offset lost 64-bit precision");
         if (slot < 2147483647) {
            long next = VulkanGpuSceneAbi.recordByteOffset(slot + 1, words);
            require(next - actual == (long)words * 4L, trial, "adjacent records overlap or contain a gap");
         }
      }

   }

   private static void verifiesTextureSerialization(SplittableRandom random) {
      for(int trial = 0; trial < 4096; ++trial) {
         int width = random.nextInt(1, 65);
         int height = random.nextInt(1, 65);
         int levels = random.nextInt(1, TextureAsset.maximumMipLevelCount(width, height) + 1);
         int byteCount = Math.toIntExact(TextureAsset.requiredByteCount(width, height, levels));
         byte[] pixels = new byte[byteCount];
         random.nextBytes(pixels);
         TextureAsset texture = TextureAsset.colorMipChain((long)trial, width, height, levels, pixels);
         long byteOffset = (long)random.nextInt(2147483647) * 4L;
         int[] words = VulkanGpuSceneAbi.packTexture(texture, new VulkanGpuSceneAbi.TexturePlacement(byteOffset, (long)byteCount));
         require(words.length == 14, trial, "texture record stride changed");
         require(join(words[1], words[2]) == byteOffset, trial, "texture byte offset was truncated");
         require(words[3] == width && words[4] == height, trial, "texture extent changed during serialization");
         require(join(words[10], words[11]) == (long)byteCount, trial, "texture byte count changed during serialization");
         require(words[12] == levels, trial, "texture mip count changed during serialization");
      }

   }

   private static void verifiesLightSerialization(SplittableRandom random) {
      for(int trial = 0; trial < 4096; ++trial) {
         double x = random.nextDouble(-1.0E12, 1.0E12);
         double y = random.nextDouble(-1.0E12, 1.0E12);
         double z = random.nextDouble(-1.0E12, 1.0E12);
         float red = randomPositiveFloat(random, 1000.0F);
         float green = randomPositiveFloat(random, 1000.0F);
         float blue = randomPositiveFloat(random, 1000.0F);
         float intensity = randomPositiveFloat(random, 1000000.0F);
         float range = Math.max(1.1754944E-38F, randomPositiveFloat(random, 100000.0F));
         SceneLight light = SceneLight.point((long)trial, x, y, z).color(red, green, blue).intensity(intensity).range(range).castsShadow(random.nextBoolean()).build();
         int[] words = VulkanGpuSceneAbi.packLight(light);
         require(words.length == 24, trial, "light record stride changed");
         require(join(words[1], words[2]) == Double.doubleToRawLongBits(x) && join(words[3], words[4]) == Double.doubleToRawLongBits(y) && join(words[5], words[6]) == Double.doubleToRawLongBits(z), trial, "light position lost exact double bits");
         require(words[10] == Float.floatToRawIntBits(red) && words[11] == Float.floatToRawIntBits(green) && words[12] == Float.floatToRawIntBits(blue) && words[13] == Float.floatToRawIntBits(intensity) && words[14] == Float.floatToRawIntBits(range), trial, "light radiance fields lost exact float bits");
      }

   }

   private static void verifiesInstanceSerialization(SplittableRandom random) {
      for(int trial = 0; trial < 4096; ++trial) {
         float scaleX = randomNonZeroScale(random);
         float scaleY = randomNonZeroScale(random);
         float scaleZ = randomNonZeroScale(random);
         float[] transform = new float[]{scaleX, 0.0F, 0.0F, randomFiniteFloat(random), 0.0F, scaleY, 0.0F, randomFiniteFloat(random), 0.0F, 0.0F, scaleZ, randomFiniteFloat(random)};
         int meshSlot = random.nextInt(2147483647);
         float visibility = random.nextFloat();
         int firstLightCoordinate = random.nextInt(SceneInstance.MAX_LIGHT_COORDINATE + 1);
         int secondLightCoordinate = random.nextInt(SceneInstance.MAX_LIGHT_COORDINATE + 1);
         int packedLight = firstLightCoordinate | secondLightCoordinate << 16;
         UvTransform uvTransform = UvTransform.of(
                 randomFiniteFloat(random), randomFiniteFloat(random), randomFiniteFloat(random),
                 randomFiniteFloat(random), randomFiniteFloat(random), randomFiniteFloat(random)
         );
         int surfaceMask = random.nextInt();
         int receiverMask = random.nextInt();
         int objectMask = random.nextInt();
         if (objectMask == 0) objectMask = 1;
         int outlineColor = random.nextInt() | 0xff000000;
         float outlineWidth = 0.25F + random.nextFloat() * OutlineStyle.MAX_WIDTH_PIXELS;
         if (outlineWidth > OutlineStyle.MAX_WIDTH_PIXELS) outlineWidth = OutlineStyle.MAX_WIDTH_PIXELS;
         float[] cardinalMultipliers = new float[6];
         for (int direction = 0; direction < cardinalMultipliers.length; ++direction) {
            cardinalMultipliers[direction] = random.nextFloat();
         }
         boolean worldSpace = random.nextBoolean();
         boolean useDirectionalDiffuse = random.nextBoolean();
         CardinalLightingState cardinalLighting = worldSpace
               ? CardinalLightingState.worldSpace(
                       cardinalMultipliers[0], cardinalMultipliers[1], cardinalMultipliers[2],
                       cardinalMultipliers[3], cardinalMultipliers[4], cardinalMultipliers[5]
               )
               : CardinalLightingState.objectSpace(
                       cardinalMultipliers[0], cardinalMultipliers[1], cardinalMultipliers[2],
                       cardinalMultipliers[3], cardinalMultipliers[4], cardinalMultipliers[5]
               );
         boolean flipBackFace = random.nextBoolean();
         DirectionalDiffuseState directionalDiffuse = DirectionalDiffuseState.builder()
               .coordinateSpace(worldSpace
                       ? DirectionalDiffuseState.CoordinateSpace.WORLD
                       : DirectionalDiffuseState.CoordinateSpace.OBJECT)
               .firstDirection(randomNonZeroScale(random), randomFiniteFloat(random),
                       randomFiniteFloat(random))
               .firstIntensity(random.nextFloat())
               .secondDirection(randomNonZeroScale(random), randomFiniteFloat(random),
                       randomFiniteFloat(random))
               .secondIntensity(random.nextFloat())
               .ambient(random.nextFloat())
               .backFacePolicy(flipBackFace
                       ? DirectionalDiffuseState.BackFacePolicy.FLIP_ON_BACK_FACE
                       : DirectionalDiffuseState.BackFacePolicy.KEEP_AUTHORED)
               .build();
         InstanceRenderState.Builder stateBuilder = InstanceRenderState.builder()
                 .uvTransform(uvTransform)
                 .surfaceMask(surfaceMask)
                 .overlayReceiverMask(receiverMask)
                 .objectMask(objectMask)
                 .outline(OutlineStyle.of(outlineColor, outlineWidth));
         InstanceRenderState state = (useDirectionalDiffuse
               ? stateBuilder.directionalDiffuse(directionalDiffuse)
               : stateBuilder.cardinalLighting(cardinalLighting)).build();
         SceneInstance instance = SceneInstance.builder((long)trial, (long)trial + 1L).transform(new AffineTransform(transform)).mobility(random.nextBoolean() ? Mobility.STATIC : Mobility.DYNAMIC).visibilityMask(random.nextInt(1, 256)).castsShadow(random.nextBoolean()).surfaceVisibility(visibility).lightmapCoordinates(firstLightCoordinate, secondLightCoordinate).renderState(state).build();
         int[] words = VulkanGpuSceneAbi.packInstance(instance, (ignored) -> meshSlot);
         require(words.length == 57 && words[0] == meshSlot, trial, "instance slot or record stride changed");

         for(int element = 0; element < transform.length; ++element) {
            require(words[3 + element] == Float.floatToRawIntBits(transform[element]), trial, "instance transform lost exact float bits at element " + element);
            require(words[28 + element] == Float.floatToRawIntBits(transform[element]), trial, "default previous transform lost exact float bits at element " + element);
         }

         require(words[15] == Float.floatToRawIntBits(visibility), trial, "instance visibility changed during serialization");
         require(words[16] == packedLight, trial, "instance lightmap coordinates changed during serialization");
         for (int element = 0; element < 6; ++element) {
            require(words[17 + element] == Float.floatToRawIntBits(uvTransform.element(element)), trial,
                    "instance UV transform lost exact float bits at element " + element);
         }
         require(words[23] == surfaceMask && words[24] == receiverMask && words[25] == objectMask
                        && words[26] == outlineColor && words[27] == Float.floatToRawIntBits(outlineWidth),
                 trial, "instance render state lost exact ABI bits");
         require(words[40] == 0 && words[41] == 0, trial,
                 "default instance motion revision changed");
         if (useDirectionalDiffuse) {
            require((words[1] & VulkanGpuSceneAbi.INSTANCE_DIRECTIONAL_DIFFUSE_ENABLED) != 0
                           && (words[1] & VulkanGpuSceneAbi.INSTANCE_CARDINAL_LIGHTING_ENABLED) == 0,
                    trial, "directional diffuse enablement aliased cardinal lighting");
            require(((words[1] & VulkanGpuSceneAbi.INSTANCE_DIRECTIONAL_DIFFUSE_WORLD_SPACE) != 0)
                           == worldSpace,
                    trial, "directional diffuse coordinate space changed during serialization");
            require(((words[1]
                           & VulkanGpuSceneAbi.INSTANCE_DIRECTIONAL_DIFFUSE_FLIP_BACK_FACE) != 0)
                           == flipBackFace,
                    trial, "directional diffuse back-face policy changed during serialization");
            float[] expectedDirectional = {
                  directionalDiffuse.firstDirectionX(), directionalDiffuse.firstDirectionY(),
                  directionalDiffuse.firstDirectionZ(), directionalDiffuse.secondDirectionX(),
                  directionalDiffuse.secondDirectionY(), directionalDiffuse.secondDirectionZ(),
                  directionalDiffuse.ambient(), directionalDiffuse.firstIntensity(),
                  directionalDiffuse.secondIntensity()
            };
            for (int element = 0; element < expectedDirectional.length; ++element) {
               require(words[48 + element]
                              == Float.floatToRawIntBits(expectedDirectional[element]),
                       trial, "directional diffuse value lost exact float bits at element " + element);
            }
         } else {
            require((words[1] & VulkanGpuSceneAbi.INSTANCE_CARDINAL_LIGHTING_ENABLED) != 0
                           && (words[1] & VulkanGpuSceneAbi.INSTANCE_DIRECTIONAL_DIFFUSE_ENABLED) == 0,
                    trial, "cardinal-lighting enablement aliased directional diffuse");
            require(((words[1] & VulkanGpuSceneAbi.INSTANCE_CARDINAL_LIGHTING_WORLD_SPACE) != 0)
                           == worldSpace,
                    trial, "cardinal-lighting coordinate space changed during serialization");
            for (int direction = 0; direction < cardinalMultipliers.length; ++direction) {
               require(words[42 + direction]
                              == Float.floatToRawIntBits(cardinalMultipliers[direction]),
                       trial, "cardinal multiplier lost exact float bits at direction " + direction);
            }
         }

         long motionRevision = random.nextLong(Long.MAX_VALUE);
         int[] temporalWords = VulkanGpuSceneAbi.packInstance(
                 instance, instance.transform(), motionRevision, ignored -> meshSlot
         );
         require(join(temporalWords[40], temporalWords[41]) == motionRevision, trial,
                 "instance motion revision lost exact 64-bit serialization");
      }

      SceneInstance instance = SceneInstance.builder(1L, 2L).build();
      expect(IllegalArgumentException.class,
              () -> VulkanGpuSceneAbi.packInstance(
                      instance, instance.transform(), -1L, ignored -> 0
              ),
              -1, "negative instance motion revision was accepted");

   }

   private static void fuzzesMalformedBoundaries(SplittableRandom random) {
      TextureAsset onePixel = TextureAsset.color(1L, 1, 1, new byte[4]);

      for(int trial = 0; trial < 4096; ++trial) {
         int invalidStride = -random.nextInt(0, 2147483647) - 1;
         expect(IllegalArgumentException.class, () -> VulkanGpuSceneAbi.recordByteOffset(random.nextInt(2147483647), invalidStride), trial, "negative record stride was accepted");
         long misalignedOffset = (long)random.nextInt(2147483647) * 4L + (long)random.nextInt(1, 4);
         expect(IllegalArgumentException.class, () -> new VulkanGpuSceneAbi.TexturePlacement(misalignedOffset, 4L), trial, "misaligned texture offset was accepted");
         long wrongByteCount = random.nextBoolean() ? 1L : 8L;
         VulkanGpuSceneAbi.TexturePlacement wrongPlacement = new VulkanGpuSceneAbi.TexturePlacement(0L, wrongByteCount);
         expect(IllegalArgumentException.class, () -> VulkanGpuSceneAbi.packTexture(onePixel, wrongPlacement), trial, "mismatched texture byte count was accepted");
      }

   }

   private static float randomPositiveFloat(SplittableRandom random, float upperBound) {
      return random.nextFloat() * upperBound;
   }

   private static float randomNonZeroScale(SplittableRandom random) {
      float magnitude = 0.01F + random.nextFloat() * 100.0F;
      return random.nextBoolean() ? magnitude : -magnitude;
   }

   private static float randomFiniteFloat(SplittableRandom random) {
      return (random.nextFloat() - 0.5F) * 2.0E7F;
   }

   private static long join(int low, int high) {
      return Integer.toUnsignedLong(low) | (long)high << 32;
   }

   private static <T extends Throwable> void expect(Class<T> type, ThrowingRunnable action, int trial, String message) {
      try {
         action.run();
      } catch (Throwable failure) {
         if (type.isInstance(failure)) {
            return;
         }

         throw new AssertionError(message + " at trial " + trial + ": expected " + type.getName() + " but caught " + String.valueOf(failure), failure);
      }

      throw new AssertionError(message + " at trial " + trial + ": no exception was thrown");
   }

   private static void require(boolean condition, int trial, String message) {
      if (!condition) {
         throw new AssertionError(message + " at trial " + trial);
      }
   }

   @FunctionalInterface
   private interface ThrowingRunnable {
      void run() throws Throwable;
   }
}

package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import top.ceroxe.rt.renderer.api.AntiAliasingState;
import top.ceroxe.rt.renderer.api.CameraState;
import top.ceroxe.rt.renderer.api.DistanceFogState;
import top.ceroxe.rt.renderer.api.DepthProjectionState;
import top.ceroxe.rt.renderer.api.EnvironmentState;
import top.ceroxe.rt.renderer.api.HistoryInvalidationReason;
import top.ceroxe.rt.renderer.api.LightmapState;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.TemporalRenderingOptions;
import top.ceroxe.rt.renderer.api.TextureSamplingState;
import top.ceroxe.rt.renderer.api.EnvironmentState.Medium;
import top.ceroxe.rt.renderer.api.TextureSamplingState.MinificationMode;

public final class VulkanFrameUniformPackerSelfTest {
   private VulkanFrameUniformPackerSelfTest() {
   }

   public static void main(String[] arguments) {
      RenderFrameRequest frame = fixture();
      long sceneRevision = 4294967301L;
      TemporalHistoryTracker tracker = new TemporalHistoryTracker(TemporalRenderingOptions.balanced());
      TemporalHistoryTracker.PreparedFrame temporal = tracker.prepare(frame, sceneRevision);
      byte[] encoded = VulkanFrameUniformPacker.pack(frame, 37, sceneRevision, temporal, TemporalRenderingOptions.balanced(), false);
      require(encoded.length == 1408, "frame ABI byte count changed");
      ByteBuffer words = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
      require(integer(words, 0) == 1920 && integer(words, 1) == 1080, "frame extent was not encoded exactly");
      require(longInteger(words, 2) == 8589934595L, "64-bit frame sequence was truncated");
      require(real64(words, 4) == 3.0000000125E7 && real64(words, 6) == -2000000.5, "camera world position lost double precision");
      require(real32(words, 12) == -1.0F && real32(words, 19) == 1.25F, "camera projection basis changed during packing");
      require(real32(words, 38) == 0.4F && real32(words, 39) == 1.333F, "camera medium changed during packing");
      require(integer(words, 40) == 37, "sparse light high-water mark changed during packing");
      require(longInteger(words, 41) == 4294967301L, "64-bit scene revision was truncated");

      for(int word = 43; word < 44; ++word) {
         require(integer(words, word) == 0, "pre-fog padding was not deterministically zero: " + word);
      }

      require(real32(words, 44) == 0.2F && real32(words, 47) == 0.75F && real32(words, 48) == -8.0F && real32(words, 51) == 96.0F, "distance fog changed during frame packing");
      require(integer(words, 52) == MinificationMode.ROTATED_GRID_SUPERSAMPLING.ordinal() && integer(words, 53) == 1, "texture minification policy changed during frame packing");
      require(integer(words, 54) == 8, "deterministic anti-aliasing sample count changed during frame packing");

      for(int word = 55; word < 56; ++word) {
         require(integer(words, word) == 0, "reserved frame word was not deterministically zero: " + word);
      }

      for(int word = 56; word < 312; ++word) {
         require(integer(words, word) == -1, "default frame lightmap must remain full intensity: " + word);
      }

      require(real64(words, 312) == frame.camera().x() && real32(words, 320) == frame.camera().forwardZ(), "first frame did not initialize previous camera deterministically");
      require(longInteger(words, 329) == frame.sequence(), "first frame previous sequence was not self-initialized");
      require(integer(words, 331) == 1, "invalid first-frame history was advertised as reusable");
      require(integer(words, 332) == 8, "temporal history bound was not packed");
      require(real32(words, 333) >= -0.5F && real32(words, 333) < 0.5F, "temporal jitter escaped its pixel-centered range");
      float[] canonicalJitter = VulkanFrameUniformPacker.temporalJitter(frame.sequence(), true);
      require(real32(words, 333) == canonicalJitter[0] && real32(words, 334) == canonicalJitter[1],
              "frame ABI did not use the canonical temporal jitter sequence");
      float[] disabledJitter = VulkanFrameUniformPacker.temporalJitter(frame.sequence(), false);
      require(disabledJitter[0] == 0.0F && disabledJitter[1] == 0.0F,
              "disabled temporal rendering must use zero jitter");
      require(longInteger(words, 337) == 0L, "first temporal generation did not start at zero");
      require(integer(words, 339) == 1 << HistoryInvalidationReason.FIRST_FRAME.ordinal(), "first-frame invalidation mask changed");
      require(longInteger(words, 340) == sceneRevision, "first frame previous scene revision was not self-initialized");
      require(real64(words, 342) == 0.0, "first frame fabricated a camera delta");
      require(integer(words, 348) == 0, "inactive denoising was advertised to the shader");
      require(real32(words, 349) == 0.0F && real32(words, 350) == 0.0F && integer(words, 351) == 0,
              "unknown depth projection must remain explicit and inert");
      ByteBuffer denoisingWords = ByteBuffer.wrap(
              VulkanFrameUniformPacker.pack(
                      frame, 37, sceneRevision, temporal, TemporalRenderingOptions.balanced(), true
              )
      ).order(ByteOrder.LITTLE_ENDIAN);
      require(integer(denoisingWords, 348) == 1, "active denoising flag was not encoded exactly");
      tracker.commit(temporal);
      CameraState movedCamera = CameraState.explicitBasis(frame.camera().x() + 0.25, frame.camera().y(), frame.camera().z()).forward(frame.camera().forwardX(), frame.camera().forwardY(), frame.camera().forwardZ()).right(frame.camera().rightX(), frame.camera().rightY(), frame.camera().rightZ()).up(frame.camera().upX(), frame.camera().upY(), frame.camera().upZ()).projectionTangents(frame.camera().tanHalfFovX(), frame.camera().tanHalfFovY()).build();
      RenderFrameRequest movedFrame = RenderFrameRequest.builder(frame.sequence() + 1L, frame.width(), frame.height(), movedCamera).minimumSceneRevision(frame.minimumSceneRevision()).environment(frame.environment()).lightmap(frame.lightmap()).fog(frame.fog()).textureSampling(frame.textureSampling()).antiAliasing(frame.antiAliasing()).build();
      TemporalHistoryTracker.PreparedFrame movedTemporal = tracker.prepare(movedFrame, sceneRevision);
      ByteBuffer movedWords = ByteBuffer.wrap(VulkanFrameUniformPacker.pack(movedFrame, 37, sceneRevision, movedTemporal, TemporalRenderingOptions.balanced(), false)).order(ByteOrder.LITTLE_ENDIAN);
      require(real64(movedWords, 342) == 0.25, "high-coordinate camera delta lost sub-unit precision");
      RenderFrameRequest projectedFrame = movedFrame.toBuilder()
              .depthProjection(DepthProjectionState.vulkanPerspective(0.125F, 4096.0F))
              .build();
      TemporalHistoryTracker.PreparedFrame projectedTemporal = tracker.prepare(projectedFrame, sceneRevision);
      ByteBuffer projectedWords = ByteBuffer.wrap(
              VulkanFrameUniformPacker.pack(
                      projectedFrame, 37, sceneRevision, projectedTemporal, TemporalRenderingOptions.balanced(), false
              )
      ).order(ByteOrder.LITTLE_ENDIAN);
      require(real32(projectedWords, 349) == 0.125F && real32(projectedWords, 350) == 4096.0F
                      && integer(projectedWords, 351) == 1,
              "known Vulkan depth projection was not packed exactly");
      expect(IllegalArgumentException.class, () -> VulkanFrameUniformPacker.pack(frame, -1, sceneRevision, temporal, TemporalRenderingOptions.balanced(), false));
      expect(IllegalArgumentException.class, () -> VulkanFrameUniformPacker.pack(frame, 0, 6L, temporal, TemporalRenderingOptions.balanced(), false));
      expect(IllegalArgumentException.class, () -> VulkanFrameUniformPacker.temporalJitter(-1L, true));
      verifiesVendorProvenanceWithoutBuiltInHistory();
      System.out.println("VulkanFrameUniformPackerSelfTest passed");
   }

   private static void verifiesVendorProvenanceWithoutBuiltInHistory() {
      TemporalRenderingOptions disabled = TemporalRenderingOptions.disabled();
      TemporalHistoryTracker tracker = new TemporalHistoryTracker(disabled, true);
      RenderFrameRequest firstFrame = fixture();
      long sceneRevision = firstFrame.minimumSceneRevision();
      TemporalHistoryTracker.PreparedFrame first = tracker.prepare(firstFrame, sceneRevision);
      require(first.invalidations().contains(HistoryInvalidationReason.FIRST_FRAME),
            "vendor provenance did not reset while built-in history was disabled");
      tracker.commit(first);

      RenderFrameRequest secondFrame = RenderFrameRequest.builder(
                  firstFrame.sequence() + 1L,
                  firstFrame.width(),
                  firstFrame.height(),
                  firstFrame.camera()
            )
            .minimumSceneRevision(firstFrame.minimumSceneRevision())
            .environment(firstFrame.environment())
            .lightmap(firstFrame.lightmap())
            .fog(firstFrame.fog())
            .textureSampling(firstFrame.textureSampling())
            .antiAliasing(firstFrame.antiAliasing())
            .depthProjection(firstFrame.depthProjection())
            .primitiveBatch(firstFrame.primitiveBatch())
            .build();
      TemporalHistoryTracker.PreparedFrame second = tracker.prepare(secondFrame, sceneRevision);
      require(second.historyValid(),
            "vendor provenance did not become valid after a committed source frame");

      ByteBuffer dlssWords = ByteBuffer.wrap(VulkanFrameUniformPacker.pack(
            secondFrame,
            top.ceroxe.rt.renderer.rt.pipeline.VulkanFrameExtents.identity(
                  secondFrame.width(), secondFrame.height()
            ),
            0,
            sceneRevision,
            second,
            disabled,
            false,
            true,
            true
      )).order(ByteOrder.LITTLE_ENDIAN);
      require(integer(dlssWords, 331) == VulkanGpuSceneAbi.TEMPORAL_FLAG_HISTORY_VALID,
            "vendor history must not enable the renderer's built-in temporal resolve");
      require(real32(dlssWords, 333) != 0.0F || real32(dlssWords, 334) != 0.0F,
            "temporal reconstruction lost its required vendor jitter");

      ByteBuffer nisWords = ByteBuffer.wrap(VulkanFrameUniformPacker.pack(
            secondFrame,
            top.ceroxe.rt.renderer.rt.pipeline.VulkanFrameExtents.identity(
                  secondFrame.width(), secondFrame.height()
            ),
            0,
            sceneRevision,
            second,
            disabled,
            false,
            true,
            false
      )).order(ByteOrder.LITTLE_ENDIAN);
      require(real32(nisWords, 333) == 0.0F && real32(nisWords, 334) == 0.0F,
            "spatial reconstruction received temporal jitter");

      ByteBuffer inactiveVendorWords = ByteBuffer.wrap(VulkanFrameUniformPacker.pack(
            secondFrame,
            top.ceroxe.rt.renderer.rt.pipeline.VulkanFrameExtents.identity(
                  secondFrame.width(), secondFrame.height()
            ),
            0,
            sceneRevision,
            second,
            disabled,
            false,
            false,
            false
      )).order(ByteOrder.LITTLE_ENDIAN);
      require(integer(inactiveVendorWords, 331) == VulkanGpuSceneAbi.TEMPORAL_FLAG_HISTORY_VALID,
            "a temporarily inactive vendor feature must not invalidate requested provenance");
   }

   private static RenderFrameRequest fixture() {
      CameraState camera = CameraState.explicitBasis(3.0000000125E7, -2000000.5, 0.25).forward(0.0F, 0.0F, -1.0F).right(1.0F, 0.0F, 0.0F).up(0.0F, 1.0F, 0.0F).projectionTangents(1.25F, 0.75F).build();
      EnvironmentState.Medium medium = Medium.builder().extinction(0.01F, 0.02F, 0.03F).scattering(0.04F, 0.05F, 0.06F).density(0.4F).indexOfRefraction(1.333F).build();
      EnvironmentState environment = EnvironmentState.builder().skyRadiance(0.1F, 0.2F, 0.3F).ambientIntensity(0.4F).sunDirection(0.0F, 1.0F, 0.0F).sunRadiance(0.9F, 0.8F, 0.7F).sunIntensity(12.0F).cameraMedium(medium).build();
      return RenderFrameRequest.builder(8589934595L, 1920, 1080, camera).minimumSceneRevision(7L).environment(environment).lightmap(LightmapState.fullIntensity()).fog(DistanceFogState.builder().color(0.2F, 0.3F, 0.4F).opacity(0.75F).sphericalRange(-8.0F, 16.0F).cylindricalRange(64.0F, 96.0F).build()).textureSampling(TextureSamplingState.rotatedGridSupersampling()).antiAliasing(AntiAliasingState.multisampled(8)).build();
   }

   private static int integer(ByteBuffer bytes, int word) {
      return bytes.getInt(word * 4);
   }

   private static long longInteger(ByteBuffer bytes, int word) {
      return bytes.getLong(word * 4);
   }

   private static float real32(ByteBuffer bytes, int word) {
      return Float.intBitsToFloat(integer(bytes, word));
   }

   private static double real64(ByteBuffer bytes, int word) {
      return Double.longBitsToDouble(longInteger(bytes, word));
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

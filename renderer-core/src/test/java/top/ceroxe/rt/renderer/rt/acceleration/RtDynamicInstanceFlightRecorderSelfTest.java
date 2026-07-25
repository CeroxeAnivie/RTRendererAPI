package top.ceroxe.rt.renderer.rt.acceleration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import top.ceroxe.rt.renderer.DynamicMeshAsset;
import top.ceroxe.rt.renderer.DynamicMeshInstance;
import top.ceroxe.rt.renderer.DynamicRenderScene;
import top.ceroxe.rt.renderer.DynamicMeshInstance.AffineTransform;
import top.ceroxe.rt.renderer.DynamicRenderScene.PrimitiveKind;
import top.ceroxe.rt.renderer.rt.material.RtBlendMode;

public final class RtDynamicInstanceFlightRecorderSelfTest {
   private static final String EVENT_NAME = "top.ceroxe.rt.DynamicTlasInstance";

   private RtDynamicInstanceFlightRecorderSelfTest() {
   }

   public static void main(String[] arguments) throws Exception {
      System.setProperty("top.ceroxe.rt.takeoverFlightRecorder.enabled", "true");
      DynamicMeshAsset asset = asset();
      DynamicRenderScene.DynamicModelInstance instance = instance(asset);
      RtDynamicTransformSlots transforms = new RtDynamicTransformSlots();
      transforms.resize(18);
      transforms.set(17, instance);
      Path recordingPath = Files.createTempFile("rtrenderer-dynamic-instance-", ".jfr");
      Recording recording = new Recording();

      try {
         recording.enable("top.ceroxe.rt.DynamicTlasInstance").withoutThreshold();
         recording.start();
         RtDynamicInstanceFlightRecorder.record("ready", 64L, 4L, 63L, 2L, 8L, 60L, 17, 8388626, 127, 305419896L, instance, transforms, asset, false);
         recording.stop();
         recording.dump(recordingPath);
      } catch (Throwable value14) {
         try {
            recording.close();
         } catch (Throwable value12) {
            value14.addSuppressed(value12);
         }

         throw value14;
      }

      recording.close();

      try {
         RecordedEvent event = (RecordedEvent)RecordingFile.readAllEvents(recordingPath).stream().filter((candidate) -> candidate.getEventType().getName().equals("top.ceroxe.rt.DynamicTlasInstance")).findFirst().orElseThrow(() -> new AssertionError("dynamic TLAS instance event was not recorded"));
         require(event.getString("stage").equals("ready"), "TLAS admission stage was not preserved");
         require(event.getInt("physicalSlot") == 17, "physical slot was not preserved");
         require(event.getInt("customIndex") == 8388626, "custom index was not preserved");
         require(event.getInt("visibilityMask") == 127, "visibility mask was not preserved");
         require(event.getLong("blasDeviceAddress") == 305419896L, "BLAS device address was not preserved");
         require(event.getString("debugName").equals("sample:dynamic-entity"), "pig instance identity was not preserved");
         require(event.getLong("assetRevision") == event.getLong("residentAssetRevision"), "resident asset revision diverged in ready evidence");
      } finally {
         Files.deleteIfExists(recordingPath);
      }

      System.out.println("RtDynamicInstanceFlightRecorderSelfTest passed");
   }

   private static DynamicMeshAsset asset() {
      return new DynamicMeshAsset(1537L, 3L, new float[]{-0.5F, 0.0F, -0.5F, 0.5F, 0.0F, -0.5F, 0.5F, 0.0F, 0.5F, -0.5F, 0.0F, 0.5F}, new int[]{0, 1, 2, 0, 2, 3}, List.of(new DynamicMeshAsset.Face(2, true)));
   }

   private static DynamicRenderScene.DynamicModelInstance instance(DynamicMeshAsset asset) {
      DynamicMeshInstance.FaceMaterial material = new DynamicMeshInstance.FaceMaterial(1, 0, 0, 0, 0, -1, false, false, RtBlendMode.OPAQUE, 0, 0, 0, false, false, 655360);
      return new DynamicRenderScene.DynamicModelInstance(72057924750606345L, PrimitiveKind.ENTITY, asset, AffineTransform.identity(), List.of(material), 15728880, "sample:dynamic-entity");
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}

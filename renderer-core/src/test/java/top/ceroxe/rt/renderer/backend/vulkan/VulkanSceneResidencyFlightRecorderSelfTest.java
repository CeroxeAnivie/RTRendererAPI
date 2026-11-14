package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import top.ceroxe.rt.renderer.api.MaterialAsset;
import top.ceroxe.rt.renderer.api.MeshAsset;
import top.ceroxe.rt.renderer.api.SceneInstance;
import top.ceroxe.rt.renderer.api.SceneTransaction;
import top.ceroxe.rt.renderer.api.TextureAsset;
import top.ceroxe.rt.renderer.api.MaterialAsset.BlendMode;
import top.ceroxe.rt.renderer.api.MaterialAsset.ShadingModel;
import top.ceroxe.rt.renderer.api.TextureAsset.AddressMode;
import top.ceroxe.rt.renderer.api.TextureAsset.ColorSpace;
import top.ceroxe.rt.renderer.api.TextureAsset.Filter;

public final class VulkanSceneResidencyFlightRecorderSelfTest {
   private static final String EVENT_NAME = "top.ceroxe.rt.VulkanSceneResidency";
   private static final String CAPTURE_LOSS_EVENT_NAME = "top.ceroxe.rt.VulkanSceneResidencyCaptureLoss";

   private VulkanSceneResidencyFlightRecorderSelfTest() {
   }

   public static void main(String[] arguments) throws Exception {
      System.setProperty("top.ceroxe.rt.takeoverFlightRecorder.enabled", "true");
      System.setProperty("top.ceroxe.rt.takeoverFlightRecorder.sceneResidencyMaxEvents", "1");
      assertProductionWiring();
      Path recordingPath = Files.createTempFile("rtrenderer-scene-residency-", ".jfr");
      Recording recording = new Recording();

      try {
         recording.enable("top.ceroxe.rt.VulkanSceneResidency").withoutThreshold().withoutStackTrace();
         recording.enable("top.ceroxe.rt.VulkanSceneResidencyCaptureLoss").withoutThreshold().withoutStackTrace();
         recording.start();
         VulkanSceneResidency residency = new VulkanSceneResidency();
         residency.commit(residency.prepare(initialScene(0L)));
         residency.commit(residency.prepare(SceneTransaction.empty(1L)));
         recording.stop();
         recording.dump(recordingPath);
      } catch (Throwable value10) {
         try {
            recording.close();
         } catch (Throwable value9) {
            value10.addSuppressed(value9);
         }

         throw value10;
      }

      recording.close();

      try {
         List<RecordedEvent> events = RecordingFile.readAllEvents(recordingPath);
         RecordedEvent residency = (RecordedEvent)events.stream().filter((event) -> "top.ceroxe.rt.VulkanSceneResidency".equals(event.getEventType().getName())).findFirst().orElseThrow(() -> new AssertionError("scene residency publication was not recorded"));
         require(residency.getLong("baseRevision") == -1L && residency.getLong("revision") == 0L, "JFR event lost scene generation boundaries");
         require(residency.getBoolean("reset"), "JFR event lost reset mode");
         require(residency.getLong("totalWrites") == 4L && residency.getLong("totalRemovals") == 0L && residency.getLong("totalClears") == 0L, "JFR event lost aggregate sparse update counts");
         require(residency.getLong("textureWrites") == 1L && residency.getLong("textureLiveSlots") == 1L && residency.getLong("textureSlotUpperBound") == 1L, "JFR event lost texture domain statistics");
         require(residency.getLong("materialWrites") == 1L && residency.getLong("meshWrites") == 1L && residency.getLong("instanceWrites") == 1L, "JFR event lost dependent domain writes");
         require(residency.getLong("lightWrites") == 0L && residency.getLong("lightClears") == 0L && residency.getLong("lightSlotUpperBound") == 0L, "JFR event lost empty-domain high-water evidence");
         RecordedEvent loss = (RecordedEvent)events.stream().filter((event) -> "top.ceroxe.rt.VulkanSceneResidencyCaptureLoss".equals(event.getEventType().getName())).findFirst().orElseThrow(() -> new AssertionError("residency capture overflow was not observable"));
         require(loss.getLong("maxEvents") == 1L && loss.getLong("droppedEventsLowerBound") == 1L, "residency capture-loss event did not preserve its configured bound");
      } finally {
         Files.deleteIfExists(recordingPath);
      }

      System.out.println("VulkanSceneResidencyFlightRecorderSelfTest passed");
   }

   private static void assertProductionWiring() throws Exception {
      Path source = Path.of(System.getProperty("user.dir")).resolve("src/main/java/top/ceroxe/rt/renderer/backend/vulkan/VulkanSceneResidency.java");
      String contents = Files.readString(source, StandardCharsets.UTF_8);
      require(contents.contains("VulkanSceneResidencyFlightRecorder.recordCommitted("), "VulkanSceneResidency is missing the committed-publication JFR edge");
   }

   private static SceneTransaction initialScene(long revision) {
      TextureAsset texture = TextureAsset.builder(10L, 1, 1).colorSpace(ColorSpace.SRGB).addressModes(AddressMode.REPEAT, AddressMode.REPEAT).filter(Filter.NEAREST).pixelsRgba8(new byte[]{1, 2, 3, 4}).build();
      MaterialAsset material = MaterialAsset.builder(20L).blendMode(BlendMode.OPAQUE).baseColorRgba8(-1).baseColorTextureId(10L).emissive(255, 0.0F).alphaCutoff(0.5F).roughness(1.0F).metallic(0.0F).transmission(0.0F).indexOfRefraction(1.5F).doubleSided(false).shadingModel(ShadingModel.PHYSICALLY_BASED).build();
      MeshAsset mesh = MeshAsset.triangles(30L, new float[]{0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F}, new int[]{0, 1, 2}, 20L);
      SceneInstance instance = SceneInstance.builder(40L, 30L).build();
      return SceneTransaction.builder(revision).resetScene().upsert(texture).upsert(material).upsert(mesh).upsert(instance).build();
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}

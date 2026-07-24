package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import top.ceroxe.mcvulkanrt.renderer.api.AffineTransform;
import top.ceroxe.mcvulkanrt.renderer.api.MaterialAsset;
import top.ceroxe.mcvulkanrt.renderer.api.MeshAsset;
import top.ceroxe.mcvulkanrt.renderer.api.SceneInstance;
import top.ceroxe.mcvulkanrt.renderer.api.SceneTransaction;
import top.ceroxe.mcvulkanrt.renderer.api.TextureAsset;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Verifies sparse GPUScene JFR fields and explicit bounded-capture loss evidence. */
public final class VulkanSceneResidencyFlightRecorderSelfTest {
    private static final String EVENT_NAME = "top.ceroxe.mcvulkanrt.VulkanSceneResidency";
    private static final String CAPTURE_LOSS_EVENT_NAME =
            "top.ceroxe.mcvulkanrt.VulkanSceneResidencyCaptureLoss";

    private VulkanSceneResidencyFlightRecorderSelfTest() {
    }

    public static void main(String[] arguments) throws Exception {
        System.setProperty("mcvulkanrt.takeoverFlightRecorder.enabled", "true");
        System.setProperty("mcvulkanrt.takeoverFlightRecorder.sceneResidencyMaxEvents", "1");
        assertProductionWiring();
        Path recordingPath = Files.createTempFile("mcvulkanrt-scene-residency-", ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable(EVENT_NAME).withoutThreshold().withoutStackTrace();
            recording.enable(CAPTURE_LOSS_EVENT_NAME).withoutThreshold().withoutStackTrace();
            recording.start();

            VulkanSceneResidency residency = new VulkanSceneResidency();
            residency.commit(residency.prepare(initialScene(0L)));
            residency.commit(residency.prepare(SceneTransaction.empty(1L)));

            recording.stop();
            recording.dump(recordingPath);
        }

        try {
            List<RecordedEvent> events = RecordingFile.readAllEvents(recordingPath);
            RecordedEvent residency = events.stream()
                    .filter(event -> EVENT_NAME.equals(event.getEventType().getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("scene residency publication was not recorded"));
            require(residency.getLong("baseRevision") == -1L && residency.getLong("revision") == 0L,
                    "JFR event lost scene generation boundaries");
            require(residency.getBoolean("reset"), "JFR event lost reset mode");
            require(residency.getLong("totalWrites") == 4L
                            && residency.getLong("totalRemovals") == 0L
                            && residency.getLong("totalClears") == 0L,
                    "JFR event lost aggregate sparse update counts");
            require(residency.getLong("textureWrites") == 1L
                            && residency.getLong("textureLiveSlots") == 1L
                            && residency.getLong("textureSlotUpperBound") == 1L,
                    "JFR event lost texture domain statistics");
            require(residency.getLong("materialWrites") == 1L
                            && residency.getLong("meshWrites") == 1L
                            && residency.getLong("instanceWrites") == 1L,
                    "JFR event lost dependent domain writes");
            require(residency.getLong("lightWrites") == 0L
                            && residency.getLong("lightClears") == 0L
                            && residency.getLong("lightSlotUpperBound") == 0L,
                    "JFR event lost empty-domain high-water evidence");

            RecordedEvent loss = events.stream()
                    .filter(event -> CAPTURE_LOSS_EVENT_NAME.equals(event.getEventType().getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("residency capture overflow was not observable"));
            require(loss.getLong("maxEvents") == 1L && loss.getLong("droppedEventsLowerBound") == 1L,
                    "residency capture-loss event did not preserve its configured bound");
        } finally {
            Files.deleteIfExists(recordingPath);
        }
        System.out.println("VulkanSceneResidencyFlightRecorderSelfTest passed");
    }

    private static void assertProductionWiring() throws Exception {
        Path source = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/java/top/ceroxe/mcvulkanrt/renderer/backend/vulkan/VulkanSceneResidency.java");
        String contents = Files.readString(source, StandardCharsets.UTF_8);
        require(contents.contains("VulkanSceneResidencyFlightRecorder.recordCommitted("),
                "VulkanSceneResidency is missing the committed-publication JFR edge");
    }

    private static SceneTransaction initialScene(long revision) {
        TextureAsset texture = new TextureAsset(
                10L, 1, 1, TextureAsset.ColorSpace.SRGB,
                TextureAsset.AddressMode.REPEAT, TextureAsset.AddressMode.REPEAT,
                TextureAsset.Filter.NEAREST, new byte[]{1, 2, 3, 4}
        );
        MaterialAsset material = new MaterialAsset(
                20L, MaterialAsset.BlendMode.OPAQUE, 0xffffffff,
                10L, -1L, -1L, -1L, 0x000000ff,
                0.0F, 0.5F, 1.0F, 0.0F, 0.0F, 1.5F, false
        );
        MeshAsset mesh = new MeshAsset(
                30L,
                new float[]{0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F},
                new float[0], new float[0], new float[0], new int[0],
                new int[]{0, 1, 2}, new long[]{20L}
        );
        SceneInstance instance = new SceneInstance(
                40L, 30L, AffineTransform.identity(), SceneInstance.Mobility.STATIC, 0xff, true
        );
        return new SceneTransaction(
                revision,
                true,
                new SceneTransaction.Upserts(List.of(texture), List.of(material), List.of(mesh), List.of(instance), List.of()),
                SceneTransaction.Removals.empty()
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

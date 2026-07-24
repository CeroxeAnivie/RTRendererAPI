package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import top.ceroxe.mcvulkanrt.renderer.DynamicMeshAsset;
import top.ceroxe.mcvulkanrt.renderer.DynamicMeshInstance;
import top.ceroxe.mcvulkanrt.renderer.DynamicRenderScene;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtBlendMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Anchors the final dynamic BLAS-to-TLAS slot evidence used for entity disappearance triage. */
public final class RtDynamicInstanceFlightRecorderSelfTest {
    private static final String EVENT_NAME = "top.ceroxe.mcvulkanrt.DynamicTlasInstance";

    private RtDynamicInstanceFlightRecorderSelfTest() {
    }

    public static void main(String[] arguments) throws Exception {
        System.setProperty("mcvulkanrt.takeoverFlightRecorder.enabled", "true");
        DynamicMeshAsset asset = asset();
        DynamicRenderScene.DynamicModelInstance instance = instance(asset);
        RtDynamicTransformSlots transforms = new RtDynamicTransformSlots();
        transforms.resize(18);
        transforms.set(17, instance);
        Path recordingPath = Files.createTempFile("mcvulkanrt-dynamic-instance-", ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable(EVENT_NAME).withoutThreshold();
            recording.start();
            RtDynamicInstanceFlightRecorder.record(
                    "ready", 64L, 4L, 63L, 2L, 8L, 60L,
                    17, 0x0080_0012, 0x7F, 0x1234_5678L, instance, transforms, asset, false
            );
            recording.stop();
            recording.dump(recordingPath);
        }
        try {
            RecordedEvent event = RecordingFile.readAllEvents(recordingPath).stream()
                    .filter(candidate -> candidate.getEventType().getName().equals(EVENT_NAME))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("dynamic TLAS instance event was not recorded"));
            require(event.getString("stage").equals("ready"), "TLAS admission stage was not preserved");
            require(event.getInt("physicalSlot") == 17, "physical slot was not preserved");
            require(event.getInt("customIndex") == 0x0080_0012, "custom index was not preserved");
            require(event.getInt("visibilityMask") == 0x7F, "visibility mask was not preserved");
            require(event.getLong("blasDeviceAddress") == 0x1234_5678L,
                    "BLAS device address was not preserved");
            require(event.getString("debugName").equals("minecraft:pig"),
                    "pig instance identity was not preserved");
            require(event.getLong("assetRevision") == event.getLong("residentAssetRevision"),
                    "resident asset revision diverged in ready evidence");
        } finally {
            Files.deleteIfExists(recordingPath);
        }
        System.out.println("RtDynamicInstanceFlightRecorderSelfTest passed");
    }

    private static DynamicMeshAsset asset() {
        return new DynamicMeshAsset(
                0x601L,
                3L,
                new float[]{-0.5F, 0.0F, -0.5F, 0.5F, 0.0F, -0.5F, 0.5F, 0.0F, 0.5F, -0.5F, 0.0F, 0.5F},
                new int[]{0, 1, 2, 0, 2, 3},
                List.of(new DynamicMeshAsset.Face(2, true))
        );
    }

    private static DynamicRenderScene.DynamicModelInstance instance(DynamicMeshAsset asset) {
        DynamicMeshInstance.FaceMaterial material = new DynamicMeshInstance.FaceMaterial(
                1, 0, 0, 0, 0, 0xFFFF_FFFF, false, false, RtBlendMode.OPAQUE,
                0, 0, 0, false, false, DynamicMeshInstance.FaceMaterial.NO_OVERLAY_COORDS
        );
        return new DynamicRenderScene.DynamicModelInstance(
                0x0100_004D_0003_0009L,
                DynamicRenderScene.PrimitiveKind.ENTITY,
                asset,
                DynamicMeshInstance.AffineTransform.identity(),
                List.of(material),
                0x00F0_00F0,
                "minecraft:pig"
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

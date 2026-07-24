package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import top.ceroxe.mcvulkanrt.renderer.DynamicRenderScene;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Serializes bounded beam and local-light lanes from one frozen dynamic-scene payload. */
final class RtDynamicSceneBeamLightWriter {
    private static final int RECORD_BYTES = 16;

    private RtDynamicSceneBeamLightWriter() {
    }

    static int localLightCount(DynamicRenderScene scene, int maximum) {
        Objects.requireNonNull(scene, "scene");
        if (maximum < 0) {
            throw new IllegalArgumentException("maximum must not be negative");
        }
        int count = 0;
        for (DynamicRenderScene.SceneLight light : scene.lights()) {
            if (isLocal(light) && ++count >= maximum) {
                return maximum;
            }
        }
        return count;
    }

    static void writeBeams(
            ByteBuffer target,
            DynamicRenderScene scene,
            int beamCount,
            int startRecord,
            int endRecord,
            int colorRecord
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(scene, "scene");
        if (beamCount < 0 || beamCount > scene.beams().size() || startRecord < 0 || endRecord < 0 || colorRecord < 0) {
            throw new IllegalArgumentException("beam writer arguments do not describe the frozen beam payload");
        }
        target.position(startRecord * RECORD_BYTES);
        for (int index = 0; index < beamCount; index++) {
            DynamicRenderScene.Beam beam = scene.beams().get(index);
            putVec4(target, (float) beam.startX(), (float) beam.startY(), (float) beam.startZ(), beam.radius());
        }
        target.position(endRecord * RECORD_BYTES);
        for (int index = 0; index < beamCount; index++) {
            DynamicRenderScene.Beam beam = scene.beams().get(index);
            putVec4(target, (float) beam.endX(), (float) beam.endY(), (float) beam.endZ(), beam.additive() ? 1.0F : 0.0F);
        }
        target.position(colorRecord * RECORD_BYTES);
        for (int index = 0; index < beamCount; index++) {
            DynamicRenderScene.Beam beam = scene.beams().get(index);
            putUvec4(target, beam.rgba8(), beam.kind().ordinal(), beam.textureKey(), beam.packedLight());
        }
    }

    static void writeLocalLights(
            ByteBuffer target,
            DynamicRenderScene scene,
            int localLightCount,
            int positionRecord,
            int colorRecord
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(scene, "scene");
        if (localLightCount < 0 || positionRecord < 0 || colorRecord < 0) {
            throw new IllegalArgumentException("local light writer arguments must not be negative");
        }
        target.position(positionRecord * RECORD_BYTES);
        int packed = 0;
        for (DynamicRenderScene.SceneLight light : scene.lights()) {
            if (packed >= localLightCount) break;
            if (!isLocal(light)) continue;
            putVec4(target, (float) light.x(), (float) light.y(), (float) light.z(), clamp(light.radius(), 0.001F, 1024.0F));
            packed++;
        }
        target.position(colorRecord * RECORD_BYTES);
        packed = 0;
        for (DynamicRenderScene.SceneLight light : scene.lights()) {
            if (packed >= localLightCount) break;
            if (!isLocal(light)) continue;
            putUvec4(
                    target,
                    packRgb8(light.rgb8()),
                    light.kind().ordinal(),
                    Float.floatToRawIntBits(clamp(light.intensity(), 0.0F, 4096.0F)),
                    light.castsShadow() ? 1 : 0
            );
            packed++;
        }
    }

    private static boolean isLocal(DynamicRenderScene.SceneLight light) {
        return light.kind() == DynamicRenderScene.LightKind.BLOCK_EMISSION
                || light.kind() == DynamicRenderScene.LightKind.ENTITY_EMISSION
                || light.kind() == DynamicRenderScene.LightKind.BEAM_EMISSION;
    }

    private static int packRgb8(int rgb8) {
        return (rgb8 & 0xff0000) >> 16 | (rgb8 & 0x00ff00) | (rgb8 & 0x0000ff) << 16;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void putUvec4(ByteBuffer target, int x, int y, int z, int w) {
        target.putInt(x); target.putInt(y); target.putInt(z); target.putInt(w);
    }

    private static void putVec4(ByteBuffer target, float x, float y, float z, float w) {
        target.putFloat(x); target.putFloat(y); target.putFloat(z); target.putFloat(w);
    }
}

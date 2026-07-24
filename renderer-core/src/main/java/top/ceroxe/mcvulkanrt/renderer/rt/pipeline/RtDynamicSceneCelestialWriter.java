package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import top.ceroxe.mcvulkanrt.renderer.DynamicRenderScene;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Serializes bounded celestial-body direction and material records. */
final class RtDynamicSceneCelestialWriter {
    private static final int RECORD_BYTES = 16;

    private RtDynamicSceneCelestialWriter() {
    }

    static void write(
            ByteBuffer target,
            DynamicRenderScene scene,
            int celestialCount,
            int directionRecord,
            int colorRecord
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(scene, "scene");
        if (celestialCount < 0 || celestialCount > scene.celestialBodies().size()
                || directionRecord < 0 || colorRecord < 0) {
            throw new IllegalArgumentException("celestial writer arguments do not describe the frozen celestial payload");
        }
        target.position(directionRecord * RECORD_BYTES);
        for (int index = 0; index < celestialCount; index++) {
            DynamicRenderScene.CelestialBody body = scene.celestialBodies().get(index);
            putVec4(target, body.directionX(), body.directionY(), body.directionZ(), body.angularRadius());
        }
        target.position(colorRecord * RECORD_BYTES);
        for (int index = 0; index < celestialCount; index++) {
            DynamicRenderScene.CelestialBody body = scene.celestialBodies().get(index);
            putUvec4(target, body.rgba8(), body.kind().ordinal(), body.textureKey(),
                    Float.floatToRawIntBits(body.brightness()));
        }
    }

    private static void putUvec4(ByteBuffer target, int x, int y, int z, int w) {
        target.putInt(x);
        target.putInt(y);
        target.putInt(z);
        target.putInt(w);
    }

    private static void putVec4(ByteBuffer target, float x, float y, float z, float w) {
        target.putFloat(x);
        target.putFloat(y);
        target.putFloat(z);
        target.putFloat(w);
    }
}

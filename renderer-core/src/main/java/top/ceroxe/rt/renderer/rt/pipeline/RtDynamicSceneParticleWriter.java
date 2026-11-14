package top.ceroxe.rt.renderer.rt.pipeline;

import top.ceroxe.rt.renderer.DynamicRenderScene;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Serializes bounded billboard-particle payloads into the five dynamic-scene ABI lanes.
 */
final class RtDynamicSceneParticleWriter {
    private static final int RECORD_BYTES = 16;

    private RtDynamicSceneParticleWriter() {
    }

    static void write(
            ByteBuffer target,
            DynamicRenderScene scene,
            int particleCount,
            int positionRecord,
            int colorRecord,
            int rotationRecord,
            int uvRecord,
            int lifecycleRecord
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(scene, "scene");
        if (particleCount < 0 || positionRecord < 0 || colorRecord < 0 || rotationRecord < 0
                || uvRecord < 0 || lifecycleRecord < 0 || particleCount > scene.particles().size()) {
            throw new IllegalArgumentException("particle writer arguments do not describe the frozen particle payload");
        }
        target.position(positionRecord * RECORD_BYTES);
        for (int index = 0; index < particleCount; index++) {
            DynamicRenderScene.BillboardParticle particle = scene.particles().get(index);
            putVec4(target, (float) particle.x(), (float) particle.y(), (float) particle.z(), particle.size());
        }
        target.position(colorRecord * RECORD_BYTES);
        for (int index = 0; index < particleCount; index++) {
            DynamicRenderScene.BillboardParticle particle = scene.particles().get(index);
            putUvec4(target, particle.rgba8(), particle.kind().ordinal(), particle.textureId(),
                    Float.floatToRawIntBits(particle.ageFraction()));
        }
        target.position(rotationRecord * RECORD_BYTES);
        for (int index = 0; index < particleCount; index++) {
            DynamicRenderScene.BillboardParticle particle = scene.particles().get(index);
            putVec4(target, particle.rotationX(), particle.rotationY(), particle.rotationZ(), particle.rotationW());
        }
        target.position(uvRecord * RECORD_BYTES);
        for (int index = 0; index < particleCount; index++) {
            DynamicRenderScene.BillboardParticle particle = scene.particles().get(index);
            putVec4(target, particle.u0(), particle.u1(), particle.v0(), particle.v1());
        }
        target.position(lifecycleRecord * RECORD_BYTES);
        for (int index = 0; index < particleCount; index++) {
            DynamicRenderScene.BillboardParticle particle = scene.particles().get(index);
            putVec4(target, particle.ageFraction(), particle.lifecycleAlpha(), particle.packedLight(), 0.0F);
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

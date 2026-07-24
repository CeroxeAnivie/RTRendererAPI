package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import top.ceroxe.mcvulkanrt.renderer.DynamicRenderScene;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Writes the fixed dynamic-scene header: scene identity, lighting, and lightmap payload. */
final class RtDynamicSceneHeaderWriter {
    static final int ACTIVE_FLAG = 1;
    static final int TLAS_GEOMETRY_FLAG = 1 << 1;

    private RtDynamicSceneHeaderWriter() {
    }

    static void write(
            ByteBuffer target,
            DynamicRenderScene scene,
            boolean active,
            int celestialCount,
            int primitiveCount,
            int particleCount,
            int beamCount,
            int localLightCount
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(scene, "scene");
        requireNonNegative(celestialCount, "celestialCount");
        requireNonNegative(primitiveCount, "primitiveCount");
        requireNonNegative(particleCount, "particleCount");
        requireNonNegative(beamCount, "beamCount");
        requireNonNegative(localLightCount, "localLightCount");

        int sceneFlags = active ? ACTIVE_FLAG : 0;
        if (active && scene.hasTlasGeometryContent()) {
            sceneFlags |= TLAS_GEOMETRY_FLAG;
        }
        putUvec4(
                target,
                (int) scene.revision(),
                (int) (scene.revision() >>> Integer.SIZE),
                celestialCount,
                sceneFlags
        );
        putUvec4(target, primitiveCount, particleCount, beamCount, localLightCount);

        RtDynamicSceneLighting.SkyPalette sky = RtDynamicSceneLighting.skyPalette(scene, active);
        putVec4(target, sky.zenithRed(), sky.zenithGreen(), sky.zenithBlue(), active ? 1.0F : 0.0F);
        putVec4(target, sky.horizonRed(), sky.horizonGreen(), sky.horizonBlue(), sky.intensity());

        RtDynamicSceneLighting.DirectionalLight directional = RtDynamicSceneLighting.directionalLight(scene, active);
        putVec4(
                target,
                directional.directionX(), directional.directionY(), directional.directionZ(), directional.intensity()
        );
        putVec4(target, directional.red(), directional.green(), directional.blue(), directional.castsShadow() ? 1.0F : 0.0F);
        scene.lightmapPayload().writePackedUvec4Records((x, y, z, w) -> putUvec4(target, x, y, z, w));
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

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}

package top.ceroxe.rt.renderer.rt.pipeline;

import top.ceroxe.rt.renderer.DynamicRenderScene;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Serializes environment and weather lanes while keeping frame-varying values in frame uniforms.
 */
final class RtDynamicSceneEnvironmentWriter {
    private static final int RECORD_BYTES = 16;

    private RtDynamicSceneEnvironmentWriter() {
    }

    static void write(
            ByteBuffer target,
            DynamicRenderScene scene,
            boolean active,
            int weatherColumnCount,
            int environmentRecord,
            int weatherBoundsRecord,
            int weatherDataRecord,
            int weatherMetaRecord,
            int fogKnownFlag,
            int cloudKnownFlag,
            int skyVisibleFlag
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(scene, "scene");
        if (weatherColumnCount < 0 || weatherColumnCount > scene.weatherColumns().size()
                || environmentRecord < 0 || weatherBoundsRecord < 0 || weatherDataRecord < 0 || weatherMetaRecord < 0) {
            throw new IllegalArgumentException("environment writer arguments do not describe the frozen environment payload");
        }
        DynamicRenderScene.EnvironmentState environment =
                active ? scene.environmentState() : DynamicRenderScene.EnvironmentState.unknown();
        target.position(environmentRecord * RECORD_BYTES);
        putVec4(target, environment.fogRed(), environment.fogGreen(), environment.fogBlue(),
                environment.fogKnown() ? 1.0F : 0.0F);
        putVec4(target, environment.environmentalStart(), environment.environmentalEnd(),
                environment.renderDistanceStart(), environment.renderDistanceEnd());
        putVec4(target, environment.skyEnd(), environment.cloudEnd(), environment.cloudHeight(), environment.cloudRange());
        int flags = 0;
        if (environment.fogKnown()) flags |= fogKnownFlag;
        if (environment.cloudKnown()) flags |= cloudKnownFlag;
        if (environment.skyVisible()) flags |= skyVisibleFlag;
        putUvec4(target, environment.cloudRgba8(), environment.cloudStatus(), flags, weatherColumnCount);
        // Clock and fog alpha are frame uniforms; keeping them out preserves persistent-scene upload stability.
        putVec4(target, 0.0F, 0.0F, 0.0F, 0.0F);

        target.position(weatherBoundsRecord * RECORD_BYTES);
        for (int index = 0; index < weatherColumnCount; index++) {
            DynamicRenderScene.WeatherColumn column = scene.weatherColumns().get(index);
            putVec4(target, (float) column.x(), (float) column.z(), column.bottomY(), column.topY());
        }
        target.position(weatherDataRecord * RECORD_BYTES);
        for (int index = 0; index < weatherColumnCount; index++) {
            DynamicRenderScene.WeatherColumn column = scene.weatherColumns().get(index);
            putVec4(target, column.uOffset(), column.vOffset(), column.alpha(), 0.0F);
        }
        target.position(weatherMetaRecord * RECORD_BYTES);
        for (int index = 0; index < weatherColumnCount; index++) {
            DynamicRenderScene.WeatherColumn column = scene.weatherColumns().get(index);
            putUvec4(target, column.kind().ordinal(), column.lightCoords(), 0, 0);
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

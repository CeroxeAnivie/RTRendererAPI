package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import top.ceroxe.mcvulkanrt.renderer.CameraRayMath;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameEnvironment;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameState;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtDynamicBlasCache;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Owns the fixed shader ABI for per-frame camera and environment uniforms. */
final class RtFrameUniformEncoder {
    static final int BYTES = 112;
    static final int WORLD_MODE = 0;
    static final int BOOTSTRAP_MODE = 1;

    private RtFrameUniformEncoder() {
    }

    static byte[] encode(
            RendererFrameState frameState,
            RendererFrameEnvironment frameEnvironment,
            int outputWidth,
            int outputHeight,
            int renderMode,
            int terrainMaterialCount
    ) {
        Objects.requireNonNull(frameState, "frameState");
        Objects.requireNonNull(frameEnvironment, "frameEnvironment");
        if (!frameState.valid()) {
            throw new IllegalArgumentException("frameState must be valid");
        }
        if (outputWidth <= 0 || outputHeight <= 0) {
            throw new IllegalArgumentException("output dimensions must be positive");
        }
        if (renderMode != WORLD_MODE && renderMode != BOOTSTRAP_MODE) {
            throw new IllegalArgumentException("unknown RT render mode: " + renderMode);
        }
        if (terrainMaterialCount < 0
                || terrainMaterialCount > RtDynamicBlasCache.DYNAMIC_MATERIAL_INDEX_BIT) {
            throw new IllegalArgumentException("terrainMaterialCount overlaps the stable dynamic material namespace");
        }

        float aspect = outputWidth / (float) outputHeight;
        CameraRayMath.RayScale rayScale = CameraRayMath.rayScale(frameState, outputWidth, outputHeight);
        ByteBuffer uniforms = ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN);
        putVec4(uniforms,
                (float) frameState.cameraX(),
                (float) frameState.cameraY(),
                (float) frameState.cameraZ(),
                1.0F);
        putVec4(uniforms,
                frameState.cameraForwardX(),
                frameState.cameraForwardY(),
                frameState.cameraForwardZ(),
                rayScale.horizontalTan());
        putVec4(uniforms,
                frameState.cameraRightX(),
                frameState.cameraRightY(),
                frameState.cameraRightZ(),
                rayScale.verticalTan());
        putVec4(uniforms,
                frameState.cameraUpX(),
                frameState.cameraUpY(),
                frameState.cameraUpZ(),
                aspect);
        uniforms.putInt(outputWidth);
        uniforms.putInt(outputHeight);
        uniforms.putInt(renderMode);
        uniforms.putInt(frameState.cameraFluidMedium().active() ? 1 : 0);
        uniforms.putLong(frameState.sequence());
        uniforms.putInt(frameState.cameraFluidMedium().packedRgba());
        uniforms.putInt(Float.floatToRawIntBits(frameState.cameraFluidMedium().density()));
        putVec4(
                uniforms,
                (float) (frameEnvironment.gameTime() % 2_147_483_647L),
                frameEnvironment.partialTicks(),
                frameEnvironment.fogAlpha(),
                (float) terrainMaterialCount
        );
        if (uniforms.position() != BYTES) {
            throw new IllegalStateException("frame uniform encoder diverged from its shader ABI");
        }
        return uniforms.array();
    }

    private static void putVec4(ByteBuffer target, float x, float y, float z, float w) {
        target.putFloat(x);
        target.putFloat(y);
        target.putFloat(z);
        target.putFloat(w);
    }
}

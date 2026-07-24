package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import top.ceroxe.mcvulkanrt.renderer.api.CameraState;
import top.ceroxe.mcvulkanrt.renderer.api.EnvironmentState;
import top.ceroxe.mcvulkanrt.renderer.api.DistanceFogState;
import top.ceroxe.mcvulkanrt.renderer.api.LightmapState;
import top.ceroxe.mcvulkanrt.renderer.api.RenderFrameRequest;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Encodes immutable public frame facts into the exact GPUScene frame ABI. */
final class VulkanFrameUniformPacker {
    static final int BYTE_COUNT = VulkanGpuSceneAbi.FRAME_UNIFORM_WORDS * Integer.BYTES;

    private VulkanFrameUniformPacker() {
    }

    static byte[] pack(RenderFrameRequest request, int lightSlotUpperBound, long activeSceneRevision) {
        RenderFrameRequest frame = Objects.requireNonNull(request, "request");
        if (lightSlotUpperBound < 0) {
            throw new IllegalArgumentException("lightSlotUpperBound must not be negative");
        }
        if (activeSceneRevision < frame.minimumSceneRevision()) {
            throw new IllegalArgumentException(
                    "active scene revision " + activeSceneRevision
                            + " does not satisfy frame minimum " + frame.minimumSceneRevision()
            );
        }

        ByteBuffer words = ByteBuffer.allocate(BYTE_COUNT).order(ByteOrder.LITTLE_ENDIAN);
        putInt(words, VulkanGpuSceneAbi.FRAME_EXTENT_WORD, frame.width());
        putInt(words, VulkanGpuSceneAbi.FRAME_EXTENT_WORD + 1, frame.height());
        putLong(words, VulkanGpuSceneAbi.FRAME_SEQUENCE_WORD, frame.sequence());

        CameraState camera = frame.camera();
        putDouble(words, VulkanGpuSceneAbi.FRAME_CAMERA_POSITION_WORD, camera.x());
        putDouble(words, VulkanGpuSceneAbi.FRAME_CAMERA_POSITION_WORD + 2, camera.y());
        putDouble(words, VulkanGpuSceneAbi.FRAME_CAMERA_POSITION_WORD + 4, camera.z());
        putVec3(words, VulkanGpuSceneAbi.FRAME_CAMERA_FORWARD_WORD,
                camera.forwardX(), camera.forwardY(), camera.forwardZ());
        putVec3(words, VulkanGpuSceneAbi.FRAME_CAMERA_RIGHT_WORD,
                camera.rightX(), camera.rightY(), camera.rightZ());
        putVec3(words, VulkanGpuSceneAbi.FRAME_CAMERA_UP_WORD,
                camera.upX(), camera.upY(), camera.upZ());
        putFloat(words, VulkanGpuSceneAbi.FRAME_FOV_WORD, camera.tanHalfFovX());
        putFloat(words, VulkanGpuSceneAbi.FRAME_FOV_WORD + 1, camera.tanHalfFovY());

        EnvironmentState environment = frame.environment();
        putVec3(words, VulkanGpuSceneAbi.FRAME_SKY_COLOR_WORD,
                environment.skyRed(), environment.skyGreen(), environment.skyBlue());
        putFloat(words, VulkanGpuSceneAbi.FRAME_AMBIENT_INTENSITY_WORD, environment.ambientIntensity());
        putVec3(words, VulkanGpuSceneAbi.FRAME_SUN_DIRECTION_WORD,
                environment.sunDirectionX(), environment.sunDirectionY(), environment.sunDirectionZ());
        putVec3(words, VulkanGpuSceneAbi.FRAME_SUN_COLOR_WORD,
                environment.sunRed(), environment.sunGreen(), environment.sunBlue());
        putFloat(words, VulkanGpuSceneAbi.FRAME_SUN_INTENSITY_WORD, environment.sunIntensity());

        EnvironmentState.Medium medium = environment.cameraMedium();
        putVec3(words, VulkanGpuSceneAbi.FRAME_MEDIUM_EXTINCTION_WORD,
                medium.extinctionRed(), medium.extinctionGreen(), medium.extinctionBlue());
        putVec3(words, VulkanGpuSceneAbi.FRAME_MEDIUM_SCATTERING_WORD,
                medium.scatteringRed(), medium.scatteringGreen(), medium.scatteringBlue());
        putFloat(words, VulkanGpuSceneAbi.FRAME_MEDIUM_DENSITY_WORD, medium.density());
        putFloat(words, VulkanGpuSceneAbi.FRAME_MEDIUM_IOR_WORD, medium.indexOfRefraction());
        putInt(words, VulkanGpuSceneAbi.FRAME_LIGHT_SLOT_UPPER_BOUND_WORD, lightSlotUpperBound);
        putLong(words, VulkanGpuSceneAbi.FRAME_SCENE_REVISION_WORD, activeSceneRevision);
        DistanceFogState fog = frame.fog();
        putFloat(words, VulkanGpuSceneAbi.FRAME_FOG_COLOR_WORD, fog.red());
        putFloat(words, VulkanGpuSceneAbi.FRAME_FOG_COLOR_WORD + 1, fog.green());
        putFloat(words, VulkanGpuSceneAbi.FRAME_FOG_COLOR_WORD + 2, fog.blue());
        putFloat(words, VulkanGpuSceneAbi.FRAME_FOG_COLOR_WORD + 3, fog.opacity());
        putFloat(words, VulkanGpuSceneAbi.FRAME_FOG_SPHERICAL_START_WORD, fog.sphericalStart());
        putFloat(words, VulkanGpuSceneAbi.FRAME_FOG_SPHERICAL_END_WORD, fog.sphericalEnd());
        putFloat(words, VulkanGpuSceneAbi.FRAME_FOG_CYLINDRICAL_START_WORD, fog.cylindricalStart());
        putFloat(words, VulkanGpuSceneAbi.FRAME_FOG_CYLINDRICAL_END_WORD, fog.cylindricalEnd());
        putInt(words, VulkanGpuSceneAbi.FRAME_TEXTURE_MINIFICATION_MODE_WORD,
                frame.textureSampling().minificationMode().ordinal());
        putInt(words, VulkanGpuSceneAbi.FRAME_MAX_ANISOTROPY_WORD,
                frame.textureSampling().maxAnisotropy());
        java.nio.IntBuffer lightmap = frame.lightmap().texelsRgba8();
        if (lightmap.remaining() != LightmapState.ENTRY_COUNT) {
            throw new IllegalStateException("validated frame lightmap changed entry count");
        }
        for (int index = 0; lightmap.hasRemaining(); index++) {
            putInt(words, VulkanGpuSceneAbi.FRAME_LIGHTMAP_WORD + index, lightmap.get());
        }
        return words.array();
    }

    private static void putVec3(ByteBuffer target, int word, float x, float y, float z) {
        putFloat(target, word, x);
        putFloat(target, word + 1, y);
        putFloat(target, word + 2, z);
    }

    private static void putFloat(ByteBuffer target, int word, float value) {
        putInt(target, word, Float.floatToRawIntBits(value));
    }

    private static void putDouble(ByteBuffer target, int word, double value) {
        putLong(target, word, Double.doubleToRawLongBits(value));
    }

    private static void putLong(ByteBuffer target, int word, long value) {
        target.putLong(Math.multiplyExact(word, Integer.BYTES), value);
    }

    private static void putInt(ByteBuffer target, int word, int value) {
        target.putInt(Math.multiplyExact(word, Integer.BYTES), value);
    }
}

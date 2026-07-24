package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import top.ceroxe.mcvulkanrt.renderer.api.CameraState;
import top.ceroxe.mcvulkanrt.renderer.api.EnvironmentState;
import top.ceroxe.mcvulkanrt.renderer.api.DistanceFogState;
import top.ceroxe.mcvulkanrt.renderer.api.LightmapState;
import top.ceroxe.mcvulkanrt.renderer.api.RenderFrameRequest;
import top.ceroxe.mcvulkanrt.renderer.api.TextureSamplingState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Exact byte-level gate for public frame facts crossing into the GPUScene shader ABI. */
public final class VulkanFrameUniformPackerSelfTest {
    private VulkanFrameUniformPackerSelfTest() {
    }

    public static void main(String[] arguments) {
        RenderFrameRequest frame = fixture();
        byte[] encoded = VulkanFrameUniformPacker.pack(frame, 37, 0x1_0000_0005L);
        require(encoded.length == VulkanFrameUniformPacker.BYTE_COUNT, "frame ABI byte count changed");
        ByteBuffer words = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        require(integer(words, VulkanGpuSceneAbi.FRAME_EXTENT_WORD) == 1920
                        && integer(words, VulkanGpuSceneAbi.FRAME_EXTENT_WORD + 1) == 1080,
                "frame extent was not encoded exactly");
        require(longInteger(words, VulkanGpuSceneAbi.FRAME_SEQUENCE_WORD) == 0x2_0000_0003L,
                "64-bit frame sequence was truncated");
        require(real64(words, VulkanGpuSceneAbi.FRAME_CAMERA_POSITION_WORD) == 30_000_000.125D
                        && real64(words, VulkanGpuSceneAbi.FRAME_CAMERA_POSITION_WORD + 2) == -2_000_000.5D,
                "camera world position lost double precision");
        require(real32(words, VulkanGpuSceneAbi.FRAME_CAMERA_FORWARD_WORD + 2) == -1.0F
                        && real32(words, VulkanGpuSceneAbi.FRAME_FOV_WORD) == 1.25F,
                "camera projection basis changed during packing");
        require(real32(words, VulkanGpuSceneAbi.FRAME_MEDIUM_DENSITY_WORD) == 0.4F
                        && real32(words, VulkanGpuSceneAbi.FRAME_MEDIUM_IOR_WORD) == 1.333F,
                "camera medium changed during packing");
        require(integer(words, VulkanGpuSceneAbi.FRAME_LIGHT_SLOT_UPPER_BOUND_WORD) == 37,
                "sparse light high-water mark changed during packing");
        require(longInteger(words, VulkanGpuSceneAbi.FRAME_SCENE_REVISION_WORD) == 0x1_0000_0005L,
                "64-bit scene revision was truncated");
        for (int word = VulkanGpuSceneAbi.FRAME_SCENE_REVISION_WORD + 2;
             word < VulkanGpuSceneAbi.FRAME_FOG_COLOR_WORD;
             word++) {
            require(integer(words, word) == 0, "pre-fog padding was not deterministically zero: " + word);
        }
        require(real32(words, VulkanGpuSceneAbi.FRAME_FOG_COLOR_WORD) == 0.2F
                        && real32(words, VulkanGpuSceneAbi.FRAME_FOG_COLOR_WORD + 3) == 0.75F
                        && real32(words, VulkanGpuSceneAbi.FRAME_FOG_SPHERICAL_START_WORD) == -8.0F
                        && real32(words, VulkanGpuSceneAbi.FRAME_FOG_CYLINDRICAL_END_WORD) == 96.0F,
                "distance fog changed during frame packing");
        require(integer(words, VulkanGpuSceneAbi.FRAME_TEXTURE_MINIFICATION_MODE_WORD)
                        == TextureSamplingState.MinificationMode.ROTATED_GRID_SUPERSAMPLING.ordinal()
                        && integer(words, VulkanGpuSceneAbi.FRAME_MAX_ANISOTROPY_WORD) == 1,
                "texture minification policy changed during frame packing");
        for (int word = VulkanGpuSceneAbi.FRAME_MAX_ANISOTROPY_WORD + 1;
             word < VulkanGpuSceneAbi.FRAME_LIGHTMAP_WORD;
             word++) {
            require(integer(words, word) == 0, "reserved frame word was not deterministically zero: " + word);
        }
        for (int word = VulkanGpuSceneAbi.FRAME_LIGHTMAP_WORD;
             word < VulkanGpuSceneAbi.FRAME_UNIFORM_WORDS;
             word++) {
            require(integer(words, word) == 0xffff_ffff,
                    "default frame lightmap must remain full intensity: " + word);
        }
        expect(IllegalArgumentException.class, () -> VulkanFrameUniformPacker.pack(frame, -1, 9L));
        expect(IllegalArgumentException.class, () -> VulkanFrameUniformPacker.pack(frame, 0, 6L));
        System.out.println("VulkanFrameUniformPackerSelfTest passed");
    }

    private static RenderFrameRequest fixture() {
        CameraState camera = new CameraState(
                30_000_000.125D, -2_000_000.5D, 0.25D,
                0.0F, 0.0F, -1.0F,
                1.0F, 0.0F, 0.0F,
                0.0F, 1.0F, 0.0F,
                1.25F, 0.75F
        );
        EnvironmentState environment = new EnvironmentState(
                0.1F, 0.2F, 0.3F, 0.4F,
                0.0F, 1.0F, 0.0F,
                0.9F, 0.8F, 0.7F, 12.0F,
                new EnvironmentState.Medium(
                        0.01F, 0.02F, 0.03F,
                        0.04F, 0.05F, 0.06F,
                        0.4F, 1.333F
                )
        );
        return new RenderFrameRequest(
                0x2_0000_0003L, 7L, 1920, 1080, camera, environment,
                LightmapState.fullIntensity(),
                new DistanceFogState(0.2F, 0.3F, 0.4F, 0.75F, -8.0F, 16.0F, 64.0F, 96.0F),
                TextureSamplingState.rotatedGridSupersampling()
        );
    }

    private static int integer(ByteBuffer bytes, int word) {
        return bytes.getInt(word * Integer.BYTES);
    }

    private static long longInteger(ByteBuffer bytes, int word) {
        return bytes.getLong(word * Integer.BYTES);
    }

    private static float real32(ByteBuffer bytes, int word) {
        return Float.intBitsToFloat(integer(bytes, word));
    }

    private static double real64(ByteBuffer bytes, int word) {
        return Double.longBitsToDouble(longInteger(bytes, word));
    }

    private static <T extends Throwable> void expect(Class<T> type, ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("expected " + type.getName() + " but caught " + failure, failure);
        }
        throw new AssertionError("expected " + type.getName() + " but no exception was thrown");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }
}

package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import top.ceroxe.mcvulkanrt.renderer.api.AffineTransform;
import top.ceroxe.mcvulkanrt.renderer.api.MaterialAsset;
import top.ceroxe.mcvulkanrt.renderer.api.MeshAsset;
import top.ceroxe.mcvulkanrt.renderer.api.SceneInstance;
import top.ceroxe.mcvulkanrt.renderer.api.SceneLight;
import top.ceroxe.mcvulkanrt.renderer.api.TextureAsset;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exact word-layout gate shared by future upload code and ray-tracing shaders. */
public final class VulkanGpuSceneAbiSelfTest {
    private static final String SHADER_ABI_RESOURCE =
            "assets/mcvulkanrt/shaders/gpuscene/gpuscene_abi.glsl";
    private static final Pattern INTEGER_DEFINE = Pattern.compile(
            "(?m)^#define\\s+(GPU_SCENE_[A-Z0-9_]+)\\s+([0-9]+)u?\\s*$"
    );
    private VulkanGpuSceneAbiSelfTest() {
    }

    public static void main(String[] arguments) {
        packsTextureAndMaterialReferences();
        packsGeometryAndInstanceReferences();
        preservesDoublePrecisionLightPositions();
        rejectsUnresolvedAndMismatchedResources();
        matchesShaderContractExactly();
        System.out.println("VulkanGpuSceneAbiSelfTest passed");
    }

    private static void packsTextureAndMaterialReferences() {
        TextureAsset texture = new TextureAsset(
                10L, 2, 1, TextureAsset.ColorSpace.SRGB,
                TextureAsset.AddressMode.REPEAT, TextureAsset.AddressMode.CLAMP_TO_EDGE,
                TextureAsset.Filter.LINEAR, new byte[8]
        );
        int[] textureRecord = VulkanGpuSceneAbi.packTexture(
                texture, new VulkanGpuSceneAbi.TexturePlacement(4_294_967_296L, 8L)
        );
        require(textureRecord.length == VulkanGpuSceneAbi.TEXTURE_RECORD_WORDS,
                "texture descriptor stride changed");
        require(textureRecord[0] != 0 && textureRecord[1] == 0 && textureRecord[2] == 1,
                "texture descriptor lost its active flag or 64-bit pixel offset");
        require(textureRecord[3] == 2 && textureRecord[4] == 1 && textureRecord[5] == 8,
                "texture descriptor lost extent or row stride");
        require(textureRecord[VulkanGpuSceneAbi.TEXTURE_MIP_LEVEL_COUNT_WORD] == 1,
                "legacy texture descriptor must expose one mip level");

        MaterialAsset material = new MaterialAsset(
                20L, MaterialAsset.BlendMode.MASKED, 0xff443322,
                10L, -1L, 11L, -1L, 0xff010203,
                2.0F, 0.25F, 0.75F, 0.5F, 0.0F, 1.45F, true,
                MaterialAsset.ShadingModel.LIGHTMAP_MODULATED
        );
        int[] materialRecord = VulkanGpuSceneAbi.packMaterial(material, id -> switch ((int) id) {
            case 10 -> 3;
            case 11 -> 7;
            default -> -1;
        });
        require(materialRecord.length == VulkanGpuSceneAbi.MATERIAL_RECORD_WORDS,
                "material descriptor stride changed");
        require(materialRecord[2] == 3 && materialRecord[3] == -1
                        && materialRecord[4] == 7 && materialRecord[5] == -1,
                "material texture identities were not resolved to stable slots");
        require(Float.intBitsToFloat(materialRecord[9]) == 0.75F
                        && Float.intBitsToFloat(materialRecord[12]) == 1.45F,
                "material PBR scalars changed during packing");
        require(((materialRecord[0] >> VulkanGpuSceneAbi.SHADING_MODEL_SHIFT)
                        & VulkanGpuSceneAbi.SHADING_MODEL_MASK)
                        == MaterialAsset.ShadingModel.LIGHTMAP_MODULATED.ordinal(),
                "material shading model was not encoded into GPU flags");
    }

    private static void packsGeometryAndInstanceReferences() {
        MeshAsset mesh = triangle();
        VulkanGpuSceneAbi.GeometryPlacement placement = new VulkanGpuSceneAbi.GeometryPlacement(
                0L, -1L, -1L, -1L, -1L, -1L, 36L, 48L
        );
        int[] meshRecord = VulkanGpuSceneAbi.packMesh(mesh, placement);
        require(meshRecord.length == VulkanGpuSceneAbi.MESH_RECORD_WORDS
                        && meshRecord[16] == 3 && meshRecord[17] == 1,
                "mesh descriptor lost geometry counts");
        require(meshRecord[2] == -1 && meshRecord[3] == -1,
                "absent geometry stream did not retain the canonical 64-bit sentinel");

        SceneInstance instance = new SceneInstance(
                40L, 30L, AffineTransform.identity(), SceneInstance.Mobility.DYNAMIC,
                0x7f, true, 0.375F
        );
        int[] instanceRecord = VulkanGpuSceneAbi.packInstance(instance, id -> id == 30L ? 9 : -1);
        require(instanceRecord.length == VulkanGpuSceneAbi.INSTANCE_RECORD_WORDS
                        && instanceRecord[0] == 9 && instanceRecord[2] == 0x7f,
                "instance descriptor lost mesh slot or visibility mask");
        require(Float.intBitsToFloat(instanceRecord[3]) == 1.0F
                        && Float.intBitsToFloat(instanceRecord[8]) == 1.0F,
                "instance affine transform changed during packing");
        require(Float.intBitsToFloat(instanceRecord[15]) == 0.375F,
                "instance surface visibility did not occupy the reserved ABI word");
    }

    private static void preservesDoublePrecisionLightPositions() {
        SceneLight light = new SceneLight(
                50L, SceneLight.Type.POINT,
                30_000_000.125D, -2_000_000.5D, 0.25D,
                0.0F, 0.0F, 0.0F,
                1.0F, 0.5F, 0.25F, 100.0F, 16.0F,
                0.0F, 0.0F, true
        );
        int[] record = VulkanGpuSceneAbi.packLight(light);
        require(record.length == VulkanGpuSceneAbi.LIGHT_RECORD_WORDS,
                "light descriptor stride changed");
        require(Double.longBitsToDouble(join(record[1], record[2])) == light.x()
                        && Double.longBitsToDouble(join(record[3], record[4])) == light.y(),
                "persistent light position was truncated to float precision");
    }

    private static void rejectsUnresolvedAndMismatchedResources() {
        MaterialAsset unresolved = new MaterialAsset(
                60L, MaterialAsset.BlendMode.OPAQUE, 0xffffffff,
                999L, -1L, -1L, -1L, 0,
                0.0F, 0.5F, 1.0F, 0.0F, 0.0F, 1.5F, false
        );
        expect(IllegalArgumentException.class, () -> VulkanGpuSceneAbi.packMaterial(unresolved, ignored -> -1));
        expect(IllegalArgumentException.class, () -> VulkanGpuSceneAbi.packMesh(
                triangle(), new VulkanGpuSceneAbi.GeometryPlacement(
                        0L, 64L, -1L, -1L, -1L, -1L, 36L, 48L
                )
        ));
        require(VulkanGpuSceneAbi.recordByteOffset(16_384, VulkanGpuSceneAbi.MATERIAL_RECORD_WORDS)
                        == 1_048_576L,
                "record byte offset overflowed or changed stride");
    }

    private static void matchesShaderContractExactly() {
        String source = readUtf8Resource(SHADER_ABI_RESOURCE);
        Matcher matcher = INTEGER_DEFINE.matcher(source);
        Map<String, Integer> actual = new HashMap<>();
        while (matcher.find()) {
            Integer previous = actual.put(matcher.group(1), Integer.parseInt(matcher.group(2)));
            require(previous == null, "shader ABI contains duplicate define " + matcher.group(1));
        }
        Map<String, Integer> expected = VulkanGpuSceneAbi.shaderDefines();
        require(actual.equals(expected), "Java/GLSL GPUScene ABI drift: expected=" + expected + ", actual=" + actual);

        boolean[] occupied = new boolean[VulkanGpuSceneAbi.DESCRIPTOR_BINDING_COUNT];
        occupied[VulkanGpuSceneAbi.TLAS_BINDING] = true;
        occupied[VulkanGpuSceneAbi.OUTPUT_IMAGE_BINDING] = true;
        occupied[VulkanGpuSceneAbi.FRAME_UNIFORMS_BINDING] = true;
        for (VulkanGpuSceneUploadPlanner.Target target : VulkanGpuSceneUploadPlanner.Target.values()) {
            int binding = VulkanGpuSceneAbi.descriptorBinding(target);
            require(binding >= 0 && binding < occupied.length, "GPUScene target binding is outside the layout");
            require(!occupied[binding], "GPUScene descriptor binding aliases another resource: " + binding);
            occupied[binding] = true;
        }
        for (int binding = 0; binding < occupied.length; binding++) {
            require(occupied[binding], "GPUScene descriptor layout contains an unassigned binding " + binding);
        }
    }

    private static String readUtf8Resource(String path) {
        try (InputStream stream = VulkanGpuSceneAbiSelfTest.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new AssertionError("missing shader ABI resource " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AssertionError("failed to read shader ABI resource " + path, failure);
        }
    }

    private static MeshAsset triangle() {
        return new MeshAsset(
                30L,
                new float[]{0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F},
                new float[0], new float[0], new float[0], new int[0],
                new int[]{0, 1, 2}, new long[]{20L}
        );
    }

    private static long join(int low, int high) {
        return Integer.toUnsignedLong(low) | (long) high << 32;
    }

    private static <T extends Throwable> void expect(Class<T> type, ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) {
                return;
            }
            throw new AssertionError("expected " + type.getName() + " but caught " + failure, failure);
        }
        throw new AssertionError("expected " + type.getName() + " but no exception was thrown");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }
}

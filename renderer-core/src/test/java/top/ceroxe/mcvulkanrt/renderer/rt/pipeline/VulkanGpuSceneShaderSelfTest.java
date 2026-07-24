package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import org.lwjgl.util.shaderc.Shaderc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Compiles every generic GPUScene RT stage without creating Vulkan objects. */
public final class VulkanGpuSceneShaderSelfTest {
    private VulkanGpuSceneShaderSelfTest() {
    }

    public static void main(String[] arguments) {
        Map<String, Integer> stages = new LinkedHashMap<>();
        stages.put("gpuscene.rgen", Shaderc.shaderc_raygen_shader);
        stages.put("gpuscene.rmiss", Shaderc.shaderc_miss_shader);
        stages.put("gpuscene.rchit", Shaderc.shaderc_closesthit_shader);
        stages.put("gpuscene.rahit", Shaderc.shaderc_anyhit_shader);
        long totalBytes = 0L;
        for (Map.Entry<String, Integer> stage : stages.entrySet()) {
            byte[] spirv = RtShaderModuleCompiler.compileForDiagnosticVerification(
                    "assets/mcvulkanrt/shaders/gpuscene/" + stage.getKey(), stage.getValue()
            );
            require(spirv.length > 20 && (spirv.length & 3) == 0,
                    stage.getKey() + " did not produce aligned SPIR-V");
            totalBytes += spirv.length;
        }
        verifyForwardParityContract();
        System.out.println("VulkanGpuSceneShaderSelfTest passed: stages=" + stages.size()
                + ", spirvBytes=" + totalBytes);
    }

    private static void verifyForwardParityContract() {
        String common = read("assets/mcvulkanrt/shaders/gpuscene/gpuscene_common.glsl");
        require(common.contains("gsTriangleLightmapModulatedColor"),
                "forward parity helper must remain explicit");
        require(common.contains("gsTriangleTextureFootprint")
                        && common.contains("gsSampleTextureFootprint")
                        && common.contains("gsSampleTextureLod"),
                "terrain texture sampling must expose one shared footprint/LOD path");
        require(common.contains("GPU_SCENE_FRAME_TEXTURE_MINIFICATION_MODE_WORD"),
                "frame texture filtering policy is not visible to the shader");
        require(common.contains("GPU_SCENE_FRAME_MAX_ANISOTROPY_WORD")
                        && common.contains("major UV derivative"),
                "anisotropic filtering must use the bounded major-axis gather path");
        require(common.contains("gsSampleLightmap(gsLightmapCoordinate(meshBase, indices.x))")
                        && common.contains("gsSampleLightmap(gsLightmapCoordinate(meshBase, indices.y))")
                        && common.contains("gsSampleLightmap(gsLightmapCoordinate(meshBase, indices.z))"),
                "forward parity must sample lightmap independently for all three vertices");
        require(common.contains("return c0 * barycentrics.x + c1 * barycentrics.y + c2 * barycentrics.z"),
                "forward parity must interpolate sampled vertex products");
        require(read("assets/mcvulkanrt/shaders/gpuscene/gpuscene.rchit")
                        .contains("gsTriangleLightmapModulatedColor(meshBase, indices, barycentrics)")
                        && read("assets/mcvulkanrt/shaders/gpuscene/gpuscene.rahit")
                        .contains("gsTriangleLightmapModulatedColor(meshBase, indices, barycentrics)"),
                "closest-hit and any-hit must share the same forward-parity vertex product");
        require(read("assets/mcvulkanrt/shaders/gpuscene/gpuscene.rchit")
                        .contains("gsSampleTextureFootprint")
                        && read("assets/mcvulkanrt/shaders/gpuscene/gpuscene.rahit")
                        .contains("gsSampleTextureFootprint"),
                "closest-hit and any-hit must share the same texture footprint sampler");
        String raygen = read("assets/mcvulkanrt/shaders/gpuscene/gpuscene.rgen");
        require(raygen.contains("else if (lightmapModulated)"),
                "forward parity must have an explicit ray-generation branch");
        require(raygen.contains("gsApplyDistanceFog(")
                        && raygen.indexOf("else if (lightmapModulated)")
                        < raygen.indexOf("vec3 position = payload.worldPositionAndDistance.xyz"),
                "forward parity must bypass the renderer-owned PBR branch");
        require(raygen.contains("lightmapModulated ? gsLinearToSrgb(radiance) : gsToneMap(radiance)"),
                "forward parity must bypass renderer-owned tone mapping");
    }

    private static String read(String path) {
        try (InputStream input = VulkanGpuSceneShaderSelfTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            if (input == null) throw new AssertionError("missing shader resource " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AssertionError("failed to read shader resource " + path, failure);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

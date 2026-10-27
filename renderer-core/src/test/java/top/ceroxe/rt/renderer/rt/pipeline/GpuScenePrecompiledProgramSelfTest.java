package top.ceroxe.rt.renderer.rt.pipeline;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.lwjgl.util.shaderc.Shaderc;
import top.ceroxe.rt.renderer.api.FrameOutputFormat;
import top.ceroxe.rt.renderer.api.SceneShaderProgram;
import top.ceroxe.rt.renderer.api.ShaderStage;
import top.ceroxe.rt.renderer.backend.vulkan.VulkanSpirvBindingValidator;

/** Validates binary ownership, all four stage interfaces, exact variants and explicit failures. */
public final class GpuScenePrecompiledProgramSelfTest {
    private static final String RAYGEN = """
            #version 460
            #extension GL_EXT_ray_tracing : require
            #ifdef RTRENDERER_LINEAR_HDR_OUTPUT
            layout(set=0,binding=1,rgba16f) uniform writeonly image2D outputImage;
            #else
            layout(set=0,binding=1,rgba8) uniform writeonly image2D outputImage;
            #endif
            void main() { imageStore(outputImage,ivec2(gl_LaunchIDEXT.xy),vec4(.25,.5,.75,1)); }
            """;

    public static void main(String[] args) throws Exception {
        byte[] sdr = RtShaderModuleCompiler.compileSceneSource(
                RAYGEN, "binary-test.rgen", Shaderc.shaderc_raygen_shader, false, false);
        byte[] hdr = RtShaderModuleCompiler.compileSceneSource(
                RAYGEN, "binary-test.rgen", Shaderc.shaderc_raygen_shader, true, false);
        var program = SceneShaderProgram.builder().spirv(ShaderStage.RAY_GENERATION, sdr)
                .spirv(ShaderStage.RAY_GENERATION, FrameOutputFormat.LINEAR_HDR_RGBA16F, hdr).build();
        require(Arrays.equals(sdr, load(program, false)), "SDR binary selection changed bytes");
        require(Arrays.equals(hdr, load(program, true)), "HDR binary selection changed bytes");
        byte[] owned = program.spirv(ShaderStage.RAY_GENERATION);
        owned[0] = 0;
        require(Arrays.equals(sdr, load(program, false)), "getter leaked binary ownership");
        var builder = SceneShaderProgram.builder().spirv(ShaderStage.RAY_GENERATION, sdr);
        var independent = builder.build();
        builder.spirv(ShaderStage.RAY_GENERATION, hdr);
        require(Arrays.equals(sdr, load(independent, false)), "builder mutation changed built program");
        require(program.equals(SceneShaderProgram.builder().spirv(ShaderStage.RAY_GENERATION, sdr)
                .spirv(ShaderStage.RAY_GENERATION, FrameOutputFormat.LINEAR_HDR_RGBA16F, hdr).build()),
                "equal binary programs are not equal");
        expectFailure(() -> load(independent, true), "missing HDR must fail instead of using built-in");
        var fallback = SceneShaderProgram.builder().shader(ShaderStage.RAY_GENERATION, RAYGEN)
                .spirv(ShaderStage.RAY_GENERATION, sdr).build();
        require(Arrays.equals(hdr, load(fallback, true)), "GLSL fallback lost HDR variant");
        var invalidSource = SceneShaderProgram.builder().shader(ShaderStage.RAY_GENERATION, "invalid GLSL")
                .spirv(ShaderStage.RAY_GENERATION, sdr).build();
        require(Arrays.equals(sdr, load(invalidSource, false)), "binary path invoked shaderc");
        expectFailure(() -> load(SceneShaderProgram.builder()
                .spirv(ShaderStage.RAY_GENERATION, hdr).build(), false), "SDR must reject HDR image ABI");
        byte[] truncated = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x07230203).array();
        expectFailure(() -> SceneShaderProgram.builder().spirv(ShaderStage.RAY_GENERATION, truncated),
                "truncated binary must fail before Vulkan");
        int[] kinds = {Shaderc.shaderc_miss_shader, Shaderc.shaderc_closesthit_shader, Shaderc.shaderc_anyhit_shader};
        ShaderStage[] stages = {ShaderStage.RAY_MISS, ShaderStage.RAY_CLOSEST_HIT, ShaderStage.RAY_ANY_HIT};
        for (int i = 0; i < kinds.length; i++) {
            byte[] code = RtShaderModuleCompiler.compileSceneSource(
                    "#version 460\n#extension GL_EXT_ray_tracing : require\nvoid main() {}\n",
                    "binary-stage-" + i, kinds[i], false, false);
            var replacement = SceneShaderProgram.builder().spirv(stages[i], code).build();
            require(Arrays.equals(code, GpuSceneRayTracingPipeline.loadStageCode(
                    "binary-stage-" + i, kinds[i], false, false, replacement)), "hit/miss binary changed");
            final int index = i;
            expectFailure(() -> VulkanSpirvBindingValidator.requireSceneInterface(code,
                    index == 0 ? ShaderStage.RAY_ANY_HIT : ShaderStage.RAY_MISS, 0, false),
                    "wrong execution model was accepted");
        }
        if (args.length == 1) {
            var files = new String[]{"terrain_shadow.rgen.spv", "terrain_shadow.rmiss.spv",
                    "terrain_shadow.rchit.spv", "terrain_shadow.rahit.spv"};
            ShaderStage[] all = {ShaderStage.RAY_GENERATION, ShaderStage.RAY_MISS,
                    ShaderStage.RAY_CLOSEST_HIT, ShaderStage.RAY_ANY_HIT};
            for (int i = 0; i < files.length; i++) {
                VulkanSpirvBindingValidator.requireSceneInterface(
                        Files.readAllBytes(Path.of(args[0], files[i])), all[i], 4, false);
            }
        }
        System.out.println("GpuScenePrecompiledProgramSelfTest passed: ownership, SDR/HDR, GLSL fallback, all stages, rejection");
    }

    private static byte[] load(SceneShaderProgram program, boolean hdr) {
        return GpuSceneRayTracingPipeline.loadStageCode(
                "gpuscene.rgen", Shaderc.shaderc_raygen_shader, hdr, false, program);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void expectFailure(Runnable action, String message) {
        try { action.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError(message);
    }
}

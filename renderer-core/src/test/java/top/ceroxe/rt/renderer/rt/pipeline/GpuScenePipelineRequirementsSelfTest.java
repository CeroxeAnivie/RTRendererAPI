package top.ceroxe.rt.renderer.rt.pipeline;

import org.lwjgl.util.shaderc.Shaderc;

public final class GpuScenePipelineRequirementsSelfTest {
    private static final String HEADER = """
            #version 460
            #extension GL_EXT_ray_tracing : require
            layout(set=0,binding=0) uniform accelerationStructureEXT tlas;
            layout(location=0) rayPayloadEXT vec3 color;
            """;

    public static void main(String[] args) {
        byte[] empty = compile("#version 460\n#extension GL_EXT_ray_tracing : require\nvoid main() {}",
                Shaderc.shaderc_miss_shader);
        byte[] primary = raygen("0u"), service = raygen("1u"), dynamic = raygen("gl_LaunchIDEXT.x");
        require(!required(primary,empty,empty,empty), "proven primary-only custom pipeline must omit unused service");
        require(required(service,empty,empty,empty), "service index must keep service");
        require(required(dynamic,empty,empty,empty), "dynamic index must conservatively keep service");
        byte[] nested = compile(HEADER+"void main() {"+trace("0u")+"}", Shaderc.shaderc_miss_shader);
        require(required(primary,nested,empty,empty), "nested miss trace must retain depth two");
        require(required(primary,empty,nested,empty), "nested hit trace must retain depth two");
        require(required(primary,empty,empty,nested), "nested any-hit trace must retain depth two");
        byte[] builtin = RtShaderModuleCompiler.compileForVerification(
                "assets/rtrenderer/shaders/gpuscene/gpuscene.rgen", Shaderc.shaderc_raygen_shader, false);
        require(required(builtin,empty,empty,empty), "full built-in transport must retain service");
        System.out.println("GpuScenePipelineRequirementsSelfTest passed: primary, service, dynamic, nested, built-in");
    }

    private static byte[] raygen(String miss) {
        return compile(HEADER+"void main() {"+trace(miss)+"}", Shaderc.shaderc_raygen_shader);
    }

    private static String trace(String miss) {
        return "traceRayEXT(tlas,0u,255u,0u,0u,"+miss+",vec3(0),.001,vec3(0,0,-1),100.0,0);";
    }

    private static byte[] compile(String source, int kind) {
        return RtShaderModuleCompiler.compileSceneSource(source,"requirements-test",kind,false,false);
    }

    private static boolean required(byte[] generation, byte[] miss, byte[] closest, byte[] any) {
        return GpuScenePipelineRequirements.shadowServiceRequired(generation,miss,closest,any);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

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
        require(depth(empty,empty,empty)==1, "leaf-only pipelines require depth one");
        byte[] nested = compile(HEADER+"void main() {"+trace("0u")+"}", Shaderc.shaderc_miss_shader);
        require(depth(nested,empty,empty)==2, "nested miss trace must retain depth two");
        require(depth(empty,nested,empty)==2, "nested hit trace must retain depth two");
        require(depth(empty,empty,nested)==2, "nested any-hit trace must retain depth two");
        String root="assets/rtrenderer/shaders/gpuscene/";
        byte[] miss=builtin(root+"gpuscene.rmiss",Shaderc.shaderc_miss_shader);
        byte[] closest=builtin(root+"gpuscene.rchit",Shaderc.shaderc_closesthit_shader);
        byte[] any=builtin(root+"gpuscene.rahit",Shaderc.shaderc_anyhit_shader);
        require(depth(miss,closest,any)==1, "built-in surface/compact/general share leaf-only shading stages");
        require(depth(builtin(root+"gpuscene_visibility.rmiss",Shaderc.shaderc_miss_shader),
                builtin(root+"gpuscene_visibility.rchit",Shaderc.shaderc_closesthit_shader),
                builtin(root+"gpuscene_visibility.rahit",Shaderc.shaderc_anyhit_shader))==1,
                "dedicated visibility stages must never dispatch recursive services");
        expectInvalid(new byte[4]);
        byte[] invalid=empty.clone(); invalid[0]=0;
        expectInvalid(invalid);
        invalid=empty.clone();
        for(int i=20;i<24;++i) invalid[i]=0;
        expectInvalid(invalid);
        System.out.println("GpuScenePipelineRequirementsSelfTest passed: leaf stages, custom nested traces, malformed SPIR-V");
    }

    private static byte[] builtin(String path,int stage) {
        return RtShaderModuleCompiler.compileForVerification(path,stage,false);
    }

    private static void expectInvalid(byte[] spirv) {
        try {depth(spirv,spirv,spirv);}
        catch(IllegalArgumentException expected) {return;}
        throw new AssertionError("malformed SPIR-V must be rejected");
    }

    private static String trace(String miss) {
        return "traceRayEXT(tlas,0u,255u,0u,0u,"+miss+",vec3(0),.001,vec3(0,0,-1),100.0,0);";
    }

    private static byte[] compile(String source, int kind) {
        return RtShaderModuleCompiler.compileSceneSource(source,"requirements-test",kind,false,false);
    }

    private static int depth(byte[] miss, byte[] closest, byte[] any) {
        return GpuScenePipelineRequirements.recursionDepth(miss,closest,any);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

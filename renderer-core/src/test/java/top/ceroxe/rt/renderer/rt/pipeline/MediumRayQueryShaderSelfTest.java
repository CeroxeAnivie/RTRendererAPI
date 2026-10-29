package top.ceroxe.rt.renderer.rt.pipeline;

import java.nio.file.*;
import java.util.Arrays;
import org.lwjgl.util.shaderc.Shaderc;

/** Optional first argument writes the pinned base resources; no argument verifies packaged source/binary parity. */
public final class MediumRayQueryShaderSelfTest {
    public static void main(String[] args) throws Exception {
        String[] suffix = {"rgen","rmiss","rchit","rahit"};
        int[] kind = {Shaderc.shaderc_raygen_shader,Shaderc.shaderc_miss_shader,Shaderc.shaderc_closesthit_shader,Shaderc.shaderc_anyhit_shader};
        for (int i=0;i<suffix.length;i++) {
            String name="medium/native_query."+suffix[i];
            byte[] compiled=RtShaderModuleCompiler.compileForVerification("assets/rtrenderer/shaders/"+name,kind[i],false);
            if(args.length==1){Path path=Path.of(args[0]).resolve("base").resolve(name.replace('/','_')+".spv");Files.createDirectories(path.getParent());Files.write(path,compiled);}
            else if(!Arrays.equals(compiled,RtShaderModuleCompiler.loadPrecompiled("assets/rtrenderer/shaders/"+name,false,false)))throw new AssertionError("stale native ray shader "+name);
        }
        System.out.println("MediumRayQueryShaderSelfTest passed: stages=4");
    }
}

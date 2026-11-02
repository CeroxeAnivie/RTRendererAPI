package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Fresh foreign install: all tested permutations must load from the JAR, with runtime compilation forbidden. */
public final class RtxptShaderBundleNativeSelfTest {
    public static void main(String[] args) throws Exception {
        require(RtxptRuntimeBundle.packaged(),"Use the real RTXPT JAR on the runtime classpath");
        require(System.getProperty("top.ceroxe.rt.rtxpt.runtime")==null
            &&System.getProperty("top.ceroxe.rt.rtxpt.bridge")==null,"No source-tree native overrides");
        require("1".equals(System.getenv("RTR_RTXPT_REQUIRE_PRECOMPILED")),"The native compiler fallback must be forbidden for this acceptance");
        var root=RtxptNative.runtime();var bins=root.resolve("ShaderDynamic/Bin/dxil");
        var index=Files.readString(bins.resolve("bundle.json"));
        for(int i=1;i<=6;i++)require(java.util.regex.Pattern.compile("\"exactInterfaces\"\\s*:\\s*"+i+"(?:,|\\s)").matcher(index).find(),"Packaged optical budget "+i);
        var before=inventory(bins);require(before.size()>6,"Package contains complete captured permutations, not six marker files");
        System.out.println("RTXPT_PACKAGED_BUDGETS foreignRuntime="+root+" initialBinaries="+before.size()+" compilerFallback=FORBIDDEN");
        RtxptBudgetNativeSelfTest.main(args);
        require(before.equals(inventory(bins)),"No shader binary is added, overwritten or recompiled in the fresh foreign install");
        System.out.println("RTXPT_PACKAGED_BUDGETS PASS all tested permutations loaded without compilation; binaries="+before.size());
    }
    private static Map<String,String> inventory(Path bins) throws Exception {
        var result=new TreeMap<String,String>();
        try(var files=Files.walk(bins)) {
            for(var file:files.filter(p->p.toString().endsWith(".bin")).toList()) {
                var a=Files.readAttributes(file,BasicFileAttributes.class);
                result.put(bins.relativize(file).toString(),a.size()+":"+a.lastModifiedTime().toMillis());
            }
        }
        return result;
    }
}

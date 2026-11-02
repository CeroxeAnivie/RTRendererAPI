package top.ceroxe.rt.renderer.rtxpt;

import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** CPU contracts for presets, rejection and atomic retained-asset admission. */
public final class RtxptBudgetContractSelfTest {
    public static void main(String[] args) {
        require(RendererConfig.pathTracedPresentation().equals(RendererConfig.pathTracedPresentation(RendererPerformanceTier.MEDIUM)),"No-argument managed preset is exactly MEDIUM");
        require(RendererConfig.presentation().equals(RendererConfig.presentation(RendererPerformanceTier.MEDIUM)),"No-argument presentation preset is exactly MEDIUM");
        require(RendererConfig.recommended().equals(RendererConfig.recommended(RendererPerformanceTier.MEDIUM)),"No-argument general preset is exactly MEDIUM");
        for(var tier:RendererPerformanceTier.values()) {
            var c=RendererConfig.pathTracedPresentation(tier);var p=c.pathTracing();
            require(p.samplesPerPixel()==1&&c.maxFramesInFlight()==2,"One sample, two bounded frames");
            require(c.resources().equals(tier.resources()),"Resources belong to the selected tier");
            require(p.exactOpticalInterfaces()==(tier==RendererPerformanceTier.LOW?2:tier==RendererPerformanceTier.HIGH?6:4),"Independent optical budget");
            require(p.quality().postProcessing().bloom()==(tier!=RendererPerformanceTier.LOW),"Explicit bloom preset");
        }
        for(int i=1;i<=6;i++)require(PathTracingOptions.defaults().withExactOpticalInterfaces(i).exactOpticalInterfaces()==i,"All six budgets are public");
        reject(()->PathTracingOptions.defaults().withExactOpticalInterfaces(0));
        reject(()->PathTracingOptions.defaults().withExactOpticalInterfaces(7));
        reject(()->new PathTracingQualityOptions.RadianceCache(65536,5,8,4,1,3.5f,1,35,10,7,.4f));
        var post=new PathTracingQualityOptions.PostProcessing(false,4,.01f,false);
        var q=PathTracingQualityOptions.defaults().withPostProcessing(post).withHalfPrecision(true).withTextureLodBias(-.5f).withNestedDielectricsQuality(2);
        require(q.postProcessing().equals(post),"Independent with-methods preserve explicit post-processing");
        var explicit=RendererConfig.pathTracedPresentation().copyBuilder().pathTracing(PathTracingOptions.defaults().withQuality(q)).build();
        require(explicit.copyBuilder().build().equals(explicit),"Copy preserves all budgets");
        reject(()->RtxptInputContract.validateConfiguration(explicit.copyBuilder().maxFramesInFlight(4).build()));
        var source=RtxptAdapterSelfTest.scene(1);var mesh=source.upserts().meshes().getFirst();
        long meshBytes=RendererResourceOptions.meshBytes(mesh);
        var resources=new RendererResourceOptions(2,1,meshBytes,4,meshBytes+4);
        resources.validateAssets(List.of(mesh),List.of(TextureAsset.color(1,1,1,new byte[4])));
        reject(()->resources.validateAssets(List.of(mesh,mesh),List.of()));
        reject(()->resources.validateAssets(List.of(mesh),List.of(TextureAsset.color(2,2,1,new byte[8]))));
        System.out.println("RTXPT_BUDGET_CONTRACT PASS presets, ranges, copy, rebuild contract and retained-asset byte limits");
    }
    private static void reject(Runnable action){try{action.run();throw new AssertionError("Expected invalid budget rejection");}catch(IllegalArgumentException expected){ }}
}

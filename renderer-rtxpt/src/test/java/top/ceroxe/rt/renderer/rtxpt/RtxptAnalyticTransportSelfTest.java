package top.ceroxe.rt.renderer.rtxpt;

import top.ceroxe.rt.renderer.api.*;

/** Public transport selection and copy semantics; does not open a graphics device. */
public final class RtxptAnalyticTransportSelfTest {
    public static void main(String[] args) {
        var defaults=PathTracingQualityOptions.defaults();
        check(defaults.beautyTransport()==PathTracingQualityOptions.BeautyTransport.NATIVE,"native default");
        var analytic=defaults.withBeautyTransport(PathTracingQualityOptions.BeautyTransport.ANALYTIC_DIRECT);
        var copied=new PathTracingQualityOptions[]{analytic.withLighting(analytic.lighting()),
            analytic.withDenoisingLayers(2,8),analytic.withNestedDielectricsQuality(2),
            analytic.withSubSampling(PathTracingQualityOptions.SubSampling.FULL),analytic.withTextureLodBias(0),
            analytic.withHalfPrecision(true),analytic.withPostProcessing(analytic.postProcessing())};
        for(var value:copied)check(value.beautyTransport()==analytic.beautyTransport(),"copy retains transport");
        var config=RendererConfig.presentation().copyBuilder()
            .pathTracing(PathTracingOptions.defaults().withQuality(analytic))
            .denoising(DenoisingOptions.disabled()).build();
        var options=RtxptOptions.resolve(RtxptOptions.profile(config));
        check(options.quality().beautyTransport()==analytic.beautyTransport(),"resolved selection");
        check(!options.standaloneDenoiser()&&options.reconstruction()!=RtxptAccess.Reconstruction.DLSS_RR,"no stochastic denoiser");
        boolean rejected=false;
        try {RtxptOptions.resolve(RtxptOptions.profile(config.copyBuilder().denoising(DenoisingOptions.builder()
            .preference(RendererFeaturePreference.REQUIRED).build()).build()));}
        catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"reject incompatible required denoiser");
        rejected=false;
        try {RtxptOptions.resolve(RtxptOptions.profile(config.copyBuilder().rayTracingOptimizations(
            config.rayTracingOptimizations().toBuilder().shaderExecutionReordering(RendererFeaturePreference.REQUIRED).build()).build()));}
        catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"reject required SER on inline compute transport");
        System.out.println("ANALYTIC_TRANSPORT_CPU PASS nativeDefault=true copies=7 resolved=true incompatibleRejected=true requiredSerRejected=true");
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}

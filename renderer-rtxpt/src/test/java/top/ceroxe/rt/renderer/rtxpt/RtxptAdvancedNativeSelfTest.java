package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Hardware assertions on actual upstream combinations, including close/reopen in one JVM. */
public final class RtxptAdvancedNativeSelfTest {
    public static void main(String[] args) throws Exception {
        var output=Path.of(System.getProperty("top.ceroxe.rt.rtxpt.testOutput","build/reports/rtxpt-advanced")).toAbsolutePath();
        Files.createDirectories(output);
        var recommended=RendererConfig.pathTracedPresentation();
        var resampling=new PathTracingOptions(1,20,2,RendererFeaturePreference.REQUIRED,
            RendererFeaturePreference.REQUIRED,RendererFeaturePreference.REQUIRED);
        var configurations=List.of(recommended,
            recommended.copyBuilder().pathTracing(resampling).build(),
            recommended.copyBuilder().frameReconstruction(FrameReconstructionOptions.disabled()).build(),
            RendererConfig.builder().cpuFrameReadbackEnabled(false).build(),
            recommended.copyBuilder().frameReconstruction(FrameReconstructionOptions.builder()
                .preference(RendererFeaturePreference.REQUIRED).mode(FrameReconstructionOptions.Mode.NATIVE_ANTI_ALIASING).build()).build());
        var names=List.of("rr-sharc-ser-reflex","dlss-nrd-restir-di-gi-sharc","native-nrd-sharc","native-raw","dlaa-rr");
        for(int i=0;i<configurations.size();i++) {
            try(var renderer=Renderer.open("rtxpt",configurations.get(i))) {
                var host=renderer.extension(RtxptAccess.class).orElseThrow();
                renderer.apply(RtxptAcceptanceFixture.scene());renderer.submit(RtxptAcceptanceFixture.frame());
                for(int f=0;f<24;f++)require(host.renderFrame(),"Combination frame");
                var status=host.runtimeStatus();
                var expected=RtxptOptions.resolve(RtxptOptions.profile(configurations.get(i)));
                require(status.reconstruction()==expected.reconstruction(),"Actual reconstruction: "+status);
                require(status.standaloneDenoiser()==(expected.standaloneDenoiser()&&expected.reconstruction()!=RtxptAccess.Reconstruction.DLSS_RR),"Actual NRD: "+status);
                require(status.restirDi()==expected.restirDi()&&status.restirGi()==expected.restirGi(),"Actual ReSTIR DI/GI: "+status);
                require(status.sharc()==expected.sharc(),"Actual SHaRC: "+status);
                require(status.shaderExecutionReordering()==expected.ser(),"Actual SER: "+status);
                require(status.lowLatency()==expected.reflex(),"Actual Reflex: "+status);
                require(!status.frameGeneration(),"Starts with FG off");
                if(i>=2)require(status.renderWidth()==1920&&status.renderHeight()==1080,"Native/DLAA full input size");
                var png=output.resolve(names.get(i)+".png");host.capture(png);
                var image=ImageIO.read(png.toFile());int min=765,max=0;
                for(int y=0;y<image.getHeight();y+=8)for(int x=0;x<image.getWidth();x+=8){int c=image.getRGB(x,y);int v=((c>>>16)&255)+((c>>>8)&255)+(c&255);min=Math.min(min,v);max=Math.max(max,v);}
                require(max-min>80,"Combination must produce a non-flat visible image");
                System.out.println("RTXPT_COMBINATION PASS "+names.get(i)+" "+status);
                if(i==0) {
                    var controller=renderer.extension(RendererFeatureController.class).orElseThrow();
                    for(var multiplier:FrameGenerationOptions.Multiplier.values()) {
                        var fg=FrameGenerationOptions.builder().preference(RendererFeaturePreference.REQUIRED)
                            .mode(multiplier==FrameGenerationOptions.Multiplier.TWO_X?FrameGenerationOptions.Mode.FRAME_GENERATION:FrameGenerationOptions.Mode.MULTI_FRAME_GENERATION)
                            .multiplier(multiplier).build();
                        var plan=controller.plan(controller.effectiveProfile().toBuilder().frameGeneration(fg).build());
                        require(controller.apply(plan).outcome()==RendererFeatureApplyResult.Outcome.APPLIED,"Live FG change");
                        for(int f=0;f<12;f++)require(host.renderFrame(),"FG combination frame");
                        status=host.runtimeStatus();
                        require(status.frameGeneration()&&status.generatedFramesPerNativeFrame()==multiplier.presentedFramesPerNativeFrame()-1,"Actual FG multiplier: "+status);
                        require(renderer.extension(RenderingFeatureCapabilities.class).orElseThrow()
                            .feature(RenderingFeatureCapabilities.Feature.FRAME_GENERATION).status()==RenderingFeatureCapabilities.Status.ACTIVE,"Capability feedback follows activation");
                        System.out.println("RTXPT_COMBINATION PASS "+multiplier+" "+status);
                    }
                }
            }
            System.out.println("RTXPT lifecycle close PASS "+names.get(i));
        }
        System.out.println("RTXPT advanced combinations and sequential lifecycle PASS");
    }
}

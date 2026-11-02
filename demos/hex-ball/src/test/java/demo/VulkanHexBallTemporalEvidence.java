package demo;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import javax.imageio.ImageIO;
import top.ceroxe.rt.renderer.api.*;

/** Isolates the old route's pre-SR history using identical static scene and SR settings. */
public final class VulkanHexBallTemporalEvidence {
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("on|off output-directory");
        boolean history=switch(args[0]){case "on"->true;case "off"->false;
            default->throw new IllegalArgumentException("on|off");};
        Path output=Path.of(args[1]).toAbsolutePath();Files.createDirectories(output);
        System.setProperty(DemoRendererProfile.DISABLE_FRAME_GENERATION_PROPERTY,"true");
        var config=DemoRendererProfile.interactive(DemoConfig.parse(new String[]{"--windowed","--width=2560","--height=1440"}))
            .copyBuilder().cpuFrameReadbackEnabled(true).maxFramesInFlight(2)
            .denoising(DenoisingOptions.disabled()).frameGeneration(FrameGenerationOptions.disabled())
            .temporalRendering(history?TemporalRenderingOptions.balanced():TemporalRenderingOptions.disabled())
            .frameReconstruction(FrameReconstructionOptions.recommended().toBuilder()
                .preference(RendererFeaturePreference.REQUIRED).fallback(FrameReconstructionOptions.Fallback.NONE)
                .quality(FrameReconstructionOptions.Quality.BALANCED).build()).build();
        var scene=new DemoScene(true);
        var batch=scene.primitiveBatch(new HexPhysics(),FramePrimitiveBatch.empty());
        try(var renderer=Renderer.open("vulkan",config)) {
            long revision=renderer.apply(scene.initialTransaction()).acceptedSceneRevision();
            for(int frame=0;frame<164;frame++) {
                var request=DemoRendererProfile.frame(frame,2560,1440,DemoRendererProfile.camera(2560,1440,15.4))
                    .minimumSceneRevision(revision).primitiveBatch(batch).environment(DemoRendererProfile.environment())
                    .antiAliasing(DemoRendererProfile.antiAliasing(1)).build();
                renderer.submit(request);
                var completed=renderer.awaitFrame(Duration.ofSeconds(90)).orElseThrow();
                if(completed.frameSequence()!=frame)throw new AssertionError("Unexpected frame sequence");
                if(frame>=160) {
                    var image=new BufferedImage(2560,1440,BufferedImage.TYPE_INT_RGB);
                    var pixels=completed.pixels();
                    if(pixels.remaining()!=2560*1440*4)throw new AssertionError("Expected RGBA8 output");
                    int[] rgb=new int[2560*1440];
                    for(int i=0;i<rgb.length;i++) {
                        rgb[i]=(Byte.toUnsignedInt(pixels.get())<<16)|(Byte.toUnsignedInt(pixels.get())<<8)
                            |Byte.toUnsignedInt(pixels.get());pixels.get();
                    }
                    image.setRGB(0,0,2560,1440,rgb,0,2560);
                    ImageIO.write(image,"png",output.resolve("static-"+frame+".png").toFile());
                }
            }
            var evidence=renderer.diagnostics().technologyExecutionEvidence()
                .technologies().get(RenderingFeatureCapabilities.Technology.TEMPORAL_SUPER_RESOLUTION);
            if(evidence==null || evidence.gpuCompletedCount()<164)throw new AssertionError("SR did not complete all frames: "+evidence);
            if(renderer.health().activeFailure().isPresent())throw new AssertionError(renderer.health());
            Files.writeString(output.resolve("runtime.txt"),config+"\n"+evidence+"\n");
            System.out.println("VULKAN_HISTORY_EVIDENCE PASS history="+history+" "+evidence);
        }
    }
}

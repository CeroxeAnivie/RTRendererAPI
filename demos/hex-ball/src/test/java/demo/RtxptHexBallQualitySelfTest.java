package demo;

import java.nio.file.Files;
import java.nio.file.Path;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rtxpt.RtxptAccess;

/** Deterministic static/moving/stopped image evidence; never part of the production frame loop. */
public final class RtxptHexBallQualitySelfTest {
    public static void main(String[] args) throws Exception {
        String mode=args.length>0?args[0]:"relax";
        boolean glass=args.length>1&&args[1].equals("glass");
        Path output=Path.of(args.length>2?args[2]:"build/reports/hexball-quality/"+mode).toAbsolutePath();
        Files.createDirectories(output);
        System.setProperty(DemoRendererProfile.DISABLE_FRAME_GENERATION_PROPERTY,"true");
        var config=DemoConfig.parse(new String[]{"--windowed","--width=2560","--height=1440","--spp=1"});
        var base=DemoRendererProfile.interactive(config);
        boolean reference=mode.equals("reference");
        boolean denoise=!mode.equals("raw")&&!mode.equals("reuse-raw")&&!reference;
        boolean reuse=mode.startsWith("reuse-");
        var policy=switch(mode) {
            case "raw", "reuse-raw", "reference" -> base.copyBuilder().denoising(DenoisingOptions.disabled()).build();
            case "relax", "reuse-relax" -> base.copyBuilder().denoising(DenoisingOptions.builder()
                .preference(RendererFeaturePreference.REQUIRED).strategy(DenoisingOptions.Strategy.RESPONSIVE)
                .builtInTemporalFallback(false).build()).build();
            case "default" -> DemoRendererProfile.rtxptInteractive();
            default -> throw new IllegalArgumentException("Expected raw, relax, default, reuse-raw, reuse-relax or reference");
        };
        if(reference) {
            var path=policy.pathTracing();
            policy=policy.copyBuilder().pathTracing(new PathTracingOptions(32,path.maxBounces(),path.diffuseBounces(),
                path.radianceCache(),path.directLightResampling(),path.indirectLightResampling())).build();
        }
        if(reuse) {
            var path=policy.pathTracing();
            policy=policy.copyBuilder().pathTracing(new PathTracingOptions(path.samplesPerPixel(),path.maxBounces(),
                path.diffuseBounces(),path.radianceCache(),RendererFeaturePreference.REQUIRED,
                path.indirectLightResampling())).build();
        }
        var scene=new DemoScene(glass);
        var physics=new HexPhysics();
        var camera=DemoRendererProfile.camera(2560,1440,15.4);
        var environment=DemoRendererProfile.environment();
        FramePrimitiveBatch previous=FramePrimitiveBatch.empty();
        long sequence=0;
        try(var renderer=Renderer.open("rtxpt",policy)) {
            long revision=renderer.apply(scene.initialTransaction()).acceptedSceneRevision();
            var host=renderer.extension(RtxptAccess.class).orElseThrow();
            for(int phase=0;phase<3;phase++) {
                int count=phase==0?164:phase==1?120:80;
                for(int f=0;f<count;f++) {
                    if(phase==1)for(int step=0;step<4;step++)physics.stepForTesting();
                    var batch=scene.primitiveBatch(physics,previous);
                    renderer.submit(DemoRendererProfile.frame(sequence++,2560,1440,camera)
                        .minimumSceneRevision(revision).primitiveBatch(batch).environment(environment)
                        .antiAliasing(DemoRendererProfile.antiAliasing(1)).build());
                    previous=batch;
                    if(sequence==1) {
                        var status=host.runtimeStatus();
                        require(status.reconstruction()==RtxptAccess.Reconstruction.DLSS,"Actual SR, not RR/TAA");
                        require(status.standaloneDenoiser()==denoise,"Actual denoiser policy");
                        require(status.relaxDenoiser()==denoise,"Actual native RELAX");
                        require(status.restirDi()==reuse,"Actual upstream direct-light reuse");
                        require(!status.frameGeneration(),"FG off for quality evidence");
                        require(status.renderWidth()==1485&&status.renderHeight()==835,"Balanced input unchanged");
                        Files.writeString(output.resolve("runtime.txt"),"mode="+mode+" glass="+glass+"\n"+status+"\n"+policy+"\n");
                        System.out.println("QUALITY_ACTUAL "+status);
                    }
                    if(phase==0&&f>=160 || phase==1&&f>=116 || phase==2&&(f==0||f==3||f==15||f==79)) {
                        Path png=output.resolve((phase==0?"static":phase==1?"moving":"stopped")+"-"+f+".png");
                        host.capture(png);
                    }
                }
            }
            host.awaitIdle();
            require(host.bridgeTimings().sceneExports()==1,"No per-frame scene rebuild");
            require(renderer.health().activeFailure().isEmpty(),"Healthy renderer");
            System.out.println("QUALITY_CAPTURE PASS "+mode+" glass="+glass+" frames="+sequence+" "+host.bridgeTimings());
        }
        System.out.println("QUALITY_LIFECYCLE PASS "+mode);
    }
    private static void require(boolean condition,String message) {
        if(!condition)throw new AssertionError(message);
    }
}

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
        boolean rr=mode.equals("rr")||(glass&&mode.equals("default"));
        boolean denoise=mode.equals("relax")||mode.equals("reuse-relax")||rr;
        boolean reuse=mode.startsWith("reuse-");
        var policy=switch(mode) {
            case "raw", "reuse-raw", "reference", "direct" -> base.copyBuilder().denoising(DenoisingOptions.disabled()).build();
            case "relax", "reuse-relax" -> base.copyBuilder().denoising(DenoisingOptions.builder()
                .preference(RendererFeaturePreference.REQUIRED).strategy(DenoisingOptions.Strategy.RESPONSIVE)
                .builtInTemporalFallback(false).build()).build();
            case "default" -> glass?DemoRendererProfile.rtxptGlassInteractive():DemoRendererProfile.rtxptInteractive();
            case "rr" -> base.copyBuilder().denoising(DenoisingOptions.recommended()).build();
            default -> throw new IllegalArgumentException("Expected raw, relax, default, reuse-raw, reuse-relax, rr, reference or direct");
        };
        if(reference) {
            var path=policy.pathTracing();
            policy=policy.copyBuilder().pathTracing(new PathTracingOptions(32,path.maxBounces(),path.diffuseBounces(),
                path.radianceCache(),path.directLightResampling(),path.indirectLightResampling())).build();
        }
        if(mode.equals("direct")) {
            policy=policy.copyBuilder().pathTracing(new PathTracingOptions(1,1,0,
                RendererFeaturePreference.DISABLED,RendererFeaturePreference.DISABLED,
                RendererFeaturePreference.DISABLED)).build();
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
                        require(status.reconstruction()==(rr?RtxptAccess.Reconstruction.DLSS_RR:RtxptAccess.Reconstruction.DLSS),"Actual requested SR/RR, not TAA");
                        require(status.standaloneDenoiser()==(denoise&&!rr),"Actual standalone denoiser policy");
                        require(status.relaxDenoiser()==(denoise&&!rr),"Actual native RELAX");
                        require(status.denoising()==denoise,"Actual native denoising, including RR");
                        require(status.restirDi()==reuse,"Actual upstream direct-light reuse");
                        require(status.sharc()==(glass&&mode.equals("default")),"Radiance cache matches the chosen surface/glass policy");
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

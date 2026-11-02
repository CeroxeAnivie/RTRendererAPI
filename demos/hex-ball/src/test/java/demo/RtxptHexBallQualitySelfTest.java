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
        String probe=args.length>3?args[3]:"none";
        String cacheMode=args.length>4?args[4]:"none";
        String reconstructionQuality=args.length>5?args[5]:(mode.equals("default")?"QUALITY":"BALANCED");
        boolean sharc=cacheMode.equals("sharc");
        boolean restirGi=cacheMode.equals("restirgi");
        if(!java.util.Set.of("none","original-lights","quarter-lights","no-points","no-sun","no-sky","no-environment").contains(probe))
            throw new IllegalArgumentException("Unknown lighting probe: "+probe);
        if(!java.util.Set.of("none","sharc","restirgi").contains(cacheMode))
            throw new IllegalArgumentException("Unknown indirect lighting mode: "+cacheMode);
        if(sharc&&restirGi)throw new IllegalArgumentException("Choose SHaRC or ReSTIR GI for isolated testing.");
        Files.createDirectories(output);
        System.setProperty(DemoRendererProfile.DISABLE_FRAME_GENERATION_PROPERTY,"true");
        var config=DemoConfig.parse(new String[]{"--windowed","--width=2560","--height=1440","--spp=1"});
        var base=DemoRendererProfile.interactive(config);
        boolean dlaa=reconstructionQuality.equalsIgnoreCase("DLAA");
        FrameReconstructionOptions.Quality quality=dlaa
                ?FrameReconstructionOptions.Quality.AUTO
                :FrameReconstructionOptions.Quality.valueOf(reconstructionQuality.toUpperCase(java.util.Locale.ROOT));
        var reconstruction=base.frameReconstruction().toBuilder()
                .mode(dlaa?FrameReconstructionOptions.Mode.NATIVE_ANTI_ALIASING:FrameReconstructionOptions.Mode.SUPER_RESOLUTION)
                .quality(quality)
                .fallback(dlaa?FrameReconstructionOptions.Fallback.NONE:base.frameReconstruction().fallback())
                .build();
        base=base.copyBuilder().frameReconstruction(reconstruction).build();
        boolean reference=mode.equals("reference")||mode.equals("direct-reference");
        boolean directOnly=mode.equals("direct")||mode.equals("direct-reference");
        boolean rr=mode.equals("rr")||mode.equals("default");
        boolean denoise=mode.equals("relax")||mode.equals("reuse-relax")||rr;
        boolean reuse=mode.startsWith("reuse-");
        var policy=switch(mode) {
            case "raw", "reuse-raw", "reference", "direct", "direct-reference" -> base.copyBuilder().denoising(DenoisingOptions.disabled()).build();
            case "relax", "reuse-relax" -> base.copyBuilder().denoising(DenoisingOptions.builder()
                .preference(RendererFeaturePreference.REQUIRED).strategy(DenoisingOptions.Strategy.RESPONSIVE)
                .builtInTemporalFallback(false).build()).build();
            case "default" -> DemoRendererProfile.rtxptInteractive();
            case "rr" -> base.copyBuilder().denoising(DenoisingOptions.recommended()).build();
            default -> throw new IllegalArgumentException("Expected raw, relax, default, reuse-raw, reuse-relax, rr, reference, direct or direct-reference");
        };
        policy=policy.copyBuilder().frameReconstruction(reconstruction).build();
        if(sharc) {
            var path=policy.pathTracing();
            policy=policy.copyBuilder().pathTracing(new PathTracingOptions(path.samplesPerPixel(),path.maxBounces(),
                path.diffuseBounces(),RendererFeaturePreference.REQUIRED,path.directLightResampling(),
                path.indirectLightResampling())).build();
        }
        if(restirGi) {
            var path=policy.pathTracing();
            policy=policy.copyBuilder().pathTracing(new PathTracingOptions(path.samplesPerPixel(),path.maxBounces(),
                path.diffuseBounces(),path.radianceCache(),path.directLightResampling(),
                RendererFeaturePreference.REQUIRED)).build();
        }
        if(reference) {
            var path=policy.pathTracing();
            policy=policy.copyBuilder().pathTracing(new PathTracingOptions(32,path.maxBounces(),path.diffuseBounces(),
                path.radianceCache(),path.directLightResampling(),path.indirectLightResampling())).build();
        }
        if(directOnly) {
            policy=policy.copyBuilder().pathTracing(new PathTracingOptions(reference?32:1,1,0,
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
        // Diagnostic isolation only. Keep exposure identical across glass probes.
        environment=lightingProbe(environment,probe);
        verifyLightingProbeMapping(DemoRendererProfile.environment());
        var initial=scene.initialTransaction();
        if(probe.equals("original-lights")||probe.equals("quarter-lights")) {
            float scale=probe.equals("original-lights")?1f:.25f;
            var lights=initial.upserts().lights().stream().map(light->SceneLight.point(light.id(),light.x(),light.y(),light.z())
                .color(light.red(),light.green(),light.blue()).intensity(light.intensity()*scale).range(light.range()).build()).toList();
            initial=SceneTransaction.builder(0).resetScene().upsertMaterials(initial.upserts().materials())
                .upsertMeshes(initial.upserts().meshes()).upsertInstances(initial.upserts().instances()).upsertLights(lights).build();
        }
        if(probe.equals("no-points")) {
            initial=SceneTransaction.builder(0).resetScene().upsertMaterials(initial.upserts().materials())
                .upsertMeshes(initial.upserts().meshes()).upsertInstances(initial.upserts().instances()).build();
        }
        FramePrimitiveBatch previous=FramePrimitiveBatch.empty();
        long sequence=0;
        try(var renderer=Renderer.open("rtxpt",policy)) {
            long revision=renderer.apply(initial).acceptedSceneRevision();
            var host=renderer.extension(RtxptAccess.class).orElseThrow();
            if(glass)host.setExposureCompensation(DemoScene.GLASS_EXPOSURE_COMPENSATION);
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
                        System.out.println("QUALITY_ACTUAL "+status);
                        require(status.reconstruction()==(rr?RtxptAccess.Reconstruction.DLSS_RR:RtxptAccess.Reconstruction.DLSS),"Actual requested SR/RR, not TAA");
                        require(status.standaloneDenoiser()==(denoise&&!rr),"Actual standalone denoiser policy");
                        require(status.relaxDenoiser()==(denoise&&!rr),"Actual native RELAX");
                        require(status.denoising()==denoise,"Actual native denoising, including RR");
                        require(status.restirDi()==reuse,"Actual upstream direct-light reuse");
                        require(status.restirGi()==restirGi,"Actual requested ReSTIR GI state");
                        require(status.sharc()==policy.pathTracing().radianceCache().requested(),"Actual requested SHaRC state");
                        require(!status.frameGeneration(),"FG off for quality evidence");
                        int expectedDlssMode=dlaa?6:switch(quality) {
                            case PERFORMANCE -> 1;
                            case BALANCED -> 2;
                            case QUALITY -> 3;
                            case ULTRA_PERFORMANCE -> 4;
                            case ULTRA_QUALITY -> 5;
                            case AUTO -> status.dlssMode();
                        };
                        require(status.dlssMode()==expectedDlssMode,
                            "RTXPT must report the requested input preset: "+status);
                        require(dlaa
                                ? status.renderWidth()==2560&&status.renderHeight()==1440
                                : status.renderWidth()>0&&status.renderHeight()>0,
                            "RTXPT input extent must match the selected preset: "+status);
                        float expectedExposure=glass?DemoScene.GLASS_EXPOSURE_COMPENSATION:0;
                        require(Math.abs(host.presentationStatus().exposureCompensation()-expectedExposure)<1e-5f,
                            "The upstream tone mapper must apply the requested exposure, including before first submission");
                        Files.writeString(output.resolve("runtime.txt"),"mode="+mode+" glass="+glass+" probe="+probe+"\n"+status+"\n"+policy+"\n");
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

    private static EnvironmentState lightingProbe(EnvironmentState source,String probe) {
        return switch(probe) {
            case "no-sun" -> source.toBuilder().sunIntensity(0).build();
            case "no-sky" -> source.toBuilder().skyRadiance(0,0,0).build();
            case "no-environment" -> source.toBuilder().skyRadiance(0,0,0).ambientIntensity(0).sunIntensity(0).build();
            default -> source;
        };
    }

    private static void verifyLightingProbeMapping(EnvironmentState source) {
        EnvironmentState noSun=lightingProbe(source,"no-sun");
        EnvironmentState noSky=lightingProbe(source,"no-sky");
        EnvironmentState noEnvironment=lightingProbe(source,"no-environment");
        require(noSun.sunIntensity()==0&&noSun.skyRed()==source.skyRed()
            &&noSun.ambientIntensity()==source.ambientIntensity(),"no-sun must remove only the sun");
        require(noSky.skyRed()==0&&noSky.sunIntensity()==source.sunIntensity()
            &&noSky.ambientIntensity()==source.ambientIntensity(),"no-sky must remove only sky radiance");
        require(noEnvironment.skyRed()==0&&noEnvironment.ambientIntensity()==0
            &&noEnvironment.sunIntensity()==0,"no-environment must remove sky, ambient and sun");
    }
}

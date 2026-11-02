package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Explicit hardware gate. Each case checks native settings, real allocations and visible output. */
public final class RtxptBudgetNativeSelfTest {
    public static void main(String[] args) throws Exception {
        var names=args.length==0?List.of("low","medium","high","quality","uniform","strata","point-limit","bloom-off","bloom-on","tone-map-off","asset-quota",
            "exact-1","exact-2","exact-3","exact-4","exact-5","exact-6"):List.of(args);
        var output=Path.of(System.getProperty("top.ceroxe.rt.rtxpt.testOutput","build/reports/rtxpt-budgets")).toAbsolutePath();
        Files.createDirectories(output);
        for(String name:names) run(name,output);
        if(names.contains("bloom-on")&&names.contains("bloom-off"))comparePostProcessing(output,"bloom-on","bloom-off");
        if(names.contains("bloom-on")&&names.contains("tone-map-off"))comparePostProcessing(output,"bloom-on","tone-map-off");
        System.out.println("RTXPT_BUDGET_MATRIX PASS "+names);
    }
    private static RendererConfig configuration(String name) {
        if(Set.of("low","medium","high").contains(name))return RendererConfig.pathTracedPresentation(RendererPerformanceTier.valueOf(name.toUpperCase(Locale.ROOT)));
        var base=RendererConfig.pathTracedPresentation();var quality=PathTracingQualityOptions.defaults();
        if(name.equals("quality")) {
            quality=new PathTracingQualityOptions(new PathTracingQualityOptions.LightSampling(
                PathTracingQualityOptions.LightingInput.BACKEND_DEFAULT,PathTracingQualityOptions.LightSampler.ADAPTIVE,2,7,.4f,2,9,false),
                new PathTracingQualityOptions.RadianceCache(65536,3,6,2,2,1.25f,.5f,24,17,40,.27f),2,6,2,
                PathTracingQualityOptions.SubSampling.HALF,-.5f,true);
            return base.copyBuilder().maxFramesInFlight(3).resources(base.resources().withThreads(3,2))
                .pathTracing(base.pathTracing().withQuality(quality)).build();
        }
        // Keep the accepted raw-SR transport while varying its public estimator budgets.
        var path=PathTracingOptions.defaults();
        if(name.startsWith("exact-")) {
            path=path.withExactOpticalInterfaces(Integer.parseInt(name.substring(6)));
            quality=quality.withLighting(quality.lighting().withInput(PathTracingQualityOptions.LightingInput.SCENE_ANALYTIC));
        } else if(name.equals("uniform")) {
            quality=quality.withLighting(quality.lighting().withSampler(PathTracingQualityOptions.LightSampler.UNIFORM).withSamples(3,9));
        } else if(name.equals("strata")) {
            quality=quality.withLighting(quality.lighting().withDirectionalStrata(true));
        } else if(name.equals("point-limit")) {
            quality=quality.withLighting(quality.lighting().withSampler(PathTracingQualityOptions.LightSampler.POWER).withExactPointLightLimit(9));
        } else if(name.equals("asset-quota")) {
            long bytes=RendererResourceOptions.meshBytes(RtxptAdapterSelfTest.scene(1).upserts().meshes().getFirst());
            base=base.copyBuilder().resources(new RendererResourceOptions(2,1,bytes,4,bytes+4)).build();
        } else if(name.equals("bloom-on")||name.equals("bloom-off")||name.equals("tone-map-off")) {
            quality=quality.withPostProcessing(new PathTracingQualityOptions.PostProcessing(!name.equals("bloom-off"),4,.1f,!name.equals("tone-map-off")));
        } else throw new IllegalArgumentException("Unknown budget case "+name);
        return base.copyBuilder().denoising(DenoisingOptions.disabled()).pathTracing(path.withQuality(quality)).build();
    }
    private static void run(String name,Path output) throws Exception {
        var config=configuration(name);var requested=config.pathTracing().quality();
        try(var renderer=Renderer.open("rtxpt",config)) {
            var host=renderer.extension(RtxptAccess.class).orElseThrow();
            renderer.apply(RtxptAdapterSelfTest.scene(1));
            if(name.equals("asset-quota")) {
                try {renderer.apply(SceneTransaction.builder(2).upsert(TextureAsset.color(2,2,1,new byte[8])).build());throw new AssertionError("Texture over quota must be rejected");}
                catch(IllegalArgumentException expected){require(expected.getMessage().contains("budget exceeded"),"Explicit budget rejection");}
                // The rejected candidate must leave revision one and the complete old scene intact.
                renderer.apply(SceneTransaction.builder(2).upsert(SceneLight.point(200,0,3,2).intensity(1).build()).build());
            }
            boolean postProcessing=name.startsWith("bloom-")||name.equals("tone-map-off");
            if(postProcessing)renderer.apply(SceneTransaction.builder(2)
                .upsert(MaterialAsset.builder(1).baseColorRgba8(0xffffffff).emissive(0xffffcc88,10).roughness(.2f).build())
                .upsert(SceneInstance.builder(1,1).build()).build());
            if(name.equals("point-limit")) {
                var lights=SceneTransaction.builder(2);
                for(int i=0;i<5;i++)lights.upsert(SceneLight.point(100+i,i-2,3,2).intensity(1).build());
                renderer.apply(lights.build());
            }
            var frame=RenderFrameRequest.builder(0,640,360,RtxptAdapterSelfTest.camera()).minimumSceneRevision(name.equals("point-limit")||name.equals("asset-quota")||postProcessing?2:1)
                .environment(RtxptAdapterSelfTest.frame(0).environment()).build();
            renderer.submit(frame);
            long deadline=System.nanoTime()+120_000_000_000L;
            while(host.bridgeTimings().frames()<12 && System.nanoTime()<deadline)require(host.renderFrame(),"Budget window stays open");
            require(host.bridgeTimings().frames()>=12,"Budget case produces twelve completed frames: "+name);
            var actual=host.pathQualityStatus();var runtime=host.runtimeStatus();
            require(actual.quality().equals(requested),"Every native quality field matches: expected="+requested+" actual="+actual);
            require(actual.allocatedCacheEntries()==requested.cache().entries(),"Actual SHaRC allocation follows capacity: "+actual);
            require(actual.workerThreads()==config.resources().workerThreads(),"Actual frame worker pool follows budget: "+actual);
            require(actual.shaderCompilerThreads()==config.resources().shaderCompilerThreads(),"Actual PT compiler pool follows budget: "+actual);
            require(actual.maxFramesInFlight()==config.maxFramesInFlight(),"Native frame ring follows budget: "+actual);
            require(host.presentationStatus().bloom()==requested.postProcessing().bloom()
                &&host.presentationStatus().toneMapping()==requested.postProcessing().toneMapping(),"Native post-processing flags follow the public API");
            if(requested.lighting().sampler()!=PathTracingQualityOptions.LightSampler.AUTO)
                require(actual.actualLightSampler()==requested.lighting().sampler().ordinal()-1,"Explicit native light sampler: "+actual);
            if(name.startsWith("exact-"))require(runtime.sceneLighting()&&runtime.activeExactOpticalInterfaces()==config.pathTracing().exactOpticalInterfaces(),"Selected optical budget is active: "+runtime);
            if(name.equals("strata"))require(actual.directionalStrata(),"Directional stratification is active");
            if(name.equals("point-limit"))require(actual.exactPointLighting(),"Five point lights activate only after the public limit is raised above four");
            var controller=renderer.extension(RendererFeatureController.class).orElseThrow();
            var changed=config.pathTracing().withExactOpticalInterfaces(config.pathTracing().exactOpticalInterfaces()==6?5:6);
            require(controller.plan(controller.effectiveProfile().toBuilder().pathTracing(changed).build()).disposition()
                ==RendererFeaturePlan.Disposition.REQUIRES_RENDERER_REBUILD,"Optical budget changes explicitly require a rebuild");
            Path png=output.resolve(name+".png");host.capture(png);var image=ImageIO.read(png.toFile());
            require(image!=null&&image.getWidth()==640&&image.getHeight()==360,"Full rendered output");
            int min=765,max=0;for(int y=0;y<360;y+=4)for(int x=0;x<640;x+=4){int p=image.getRGB(x,y);int light=((p>>>16)&255)+((p>>>8)&255)+(p&255);min=Math.min(min,light);max=Math.max(max,light);}
            require(max-min>24,"Rendered output has scene contrast, not a blank frame");
            var result="RTXPT_BUDGET PASS "+name+" "+actual+" "+runtime+System.lineSeparator();
            Files.writeString(output.resolve(name+".txt"),result);System.out.print(result);
        }
    }
    private static void comparePostProcessing(Path output,String left,String right) throws Exception {
        var a=ImageIO.read(output.resolve(left+".png").toFile());var b=ImageIO.read(output.resolve(right+".png").toFile());
        int changed=0,max=0;
        for(int y=0;y<a.getHeight();y++)for(int x=0;x<a.getWidth();x++) {
            int aa=a.getRGB(x,y),bb=b.getRGB(x,y);int delta=0;
            for(int shift:new int[]{0,8,16})delta=Math.max(delta,Math.abs(((aa>>>shift)&255)-((bb>>>shift)&255)));
            if(delta>2)changed++;max=Math.max(max,delta);
        }
        require(changed>50&&max>8,"Native post-processing changes actual pixels: "+left+" vs "+right+" changed="+changed+" max="+max);
        String evidence="RTXPT_POSTPROCESS_PIXELS PASS "+left+" vs "+right+" changed="+changed+" maxDelta="+max;
        Files.writeString(output.resolve(left+"-vs-"+right+".txt"),evidence+System.lineSeparator());System.out.println(evidence);
    }
}

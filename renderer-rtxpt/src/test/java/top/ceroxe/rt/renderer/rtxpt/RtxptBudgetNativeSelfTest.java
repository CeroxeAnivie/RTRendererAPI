package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Explicit hardware gate. Each case checks native settings, real allocations and visible output. */
public final class RtxptBudgetNativeSelfTest {
    public static void main(String[] args) throws Exception {
        var names=args.length==0?List.of("low","medium","high","quality","uniform","strata","point-limit",
            "exact-1","exact-2","exact-3","exact-4","exact-5","exact-6"):List.of(args);
        var output=Path.of(System.getProperty("top.ceroxe.rt.rtxpt.testOutput","build/reports/rtxpt-budgets")).toAbsolutePath();
        Files.createDirectories(output);
        for(String name:names) run(name,output);
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
        } else throw new IllegalArgumentException("Unknown budget case "+name);
        return base.copyBuilder().denoising(DenoisingOptions.disabled()).pathTracing(path.withQuality(quality)).build();
    }
    private static void run(String name,Path output) throws Exception {
        var config=configuration(name);var requested=config.pathTracing().quality();
        try(var renderer=Renderer.open("rtxpt",config)) {
            var host=renderer.extension(RtxptAccess.class).orElseThrow();
            renderer.apply(RtxptAdapterSelfTest.scene(1));
            if(name.equals("point-limit")) {
                var lights=SceneTransaction.builder(2);
                for(int i=0;i<5;i++)lights.upsert(SceneLight.point(100+i,i-2,3,2).intensity(1).build());
                renderer.apply(lights.build());
            }
            var frame=RenderFrameRequest.builder(0,640,360,RtxptAdapterSelfTest.camera()).minimumSceneRevision(name.equals("point-limit")?2:1)
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
}

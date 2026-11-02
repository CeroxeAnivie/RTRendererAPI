package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Short background-only native acceptance. Counters and images are evidence; no FPS measurements. */
public final class RtxptLightingNativeSelfTest {
    static final Path OUTPUT=Path.of(System.getProperty("top.ceroxe.rt.rtxpt.testOutput","build/reports/rtxpt-lighting")).toAbsolutePath();
    static final EnvironmentState DARK=EnvironmentState.neutral();
    static EnvironmentState environment(){return EnvironmentState.builder().skyRadiance(.3f,.4f,.6f).ambientIntensity(.4f).sunDirection(.57735026f,-.57735026f,-.57735026f).sunIntensity(2).build();}
    static List<SceneLight> lights() {
        var result=new ArrayList<SceneLight>();
        for(int i=0;i<20;i++)result.add(SceneLight.directional(i,.2f,-1,.1f).color(.9f,.6f,.3f).intensity(.08f).castsShadow(i%2==0).build());
        for(int i=20;i<52;i++)result.add(SceneLight.point(i,(i%8-4)*.45,2.8,(i/8-4)*.4).color(.2f,.5f,1).intensity(.3f).range(8).castsShadow(i%2==0).build());
        result.add(SceneLight.spot(90,0,4,0,0,-1,0).color(1,.1f,.05f).intensity(2).range(7).coneCosines(.95f,.5f).castsShadow(false).build());
        return result;
    }
    static RenderFrameRequest frame(long sequence,long revision,EnvironmentState e){return RenderFrameRequest.builder(sequence,960,540,RtxptAcceptanceFixture.camera()).minimumSceneRevision(revision).environment(e).build();}
    static RendererConfig configuration(String name) {
        var base=name.equals("raw")||name.equals("analytic")?RendererConfig.builder().cpuFrameReadbackEnabled(false).build():RendererConfig.pathTracedPresentation();
        var path=base.pathTracing();var quality=path.quality().withCache(path.quality().cache().withCapacity(65536));
        if(name.equals("analytic"))quality=quality.withLighting(quality.lighting().withInput(PathTracingQualityOptions.LightingInput.SCENE_ANALYTIC));
        var di=name.contains("di")?RendererFeaturePreference.REQUIRED:RendererFeaturePreference.DISABLED;
        var gi=name.contains("gi")?RendererFeaturePreference.REQUIRED:RendererFeaturePreference.DISABLED;
        var cache=name.contains("sharc")?RendererFeaturePreference.REQUIRED:RendererFeaturePreference.DISABLED;
        path=new PathTracingOptions(1,8,2,cache,di,gi,4,quality);
        var builder=base.copyBuilder().pathTracing(path).frameGeneration(FrameGenerationOptions.disabled());
        if(name.equals("sr"))builder.denoising(DenoisingOptions.disabled());
        if(name.equals("nrd"))builder.denoising(DenoisingOptions.builder().preference(RendererFeaturePreference.REQUIRED).strategy(DenoisingOptions.Strategy.RESPONSIVE).build());
        return builder.build();
    }
    static void settle(RtxptAccess host,int frames){for(int i=0;i<frames;i++)require(host.renderFrame(),"Background fixture frame");}
    static void save(RtxptAccess host,Path file){host.awaitIdle();host.capture(file);}
    static double mean(Path file)throws Exception {var image=ImageIO.read(file.toFile());double total=0;for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){int c=image.getRGB(x,y);total+=((c>>>16)&255)+((c>>>8)&255)+(c&255);}return total/(image.getWidth()*image.getHeight()*3.0);}
    static double difference(Path a,Path b)throws Exception {var x=ImageIO.read(a.toFile());var y=ImageIO.read(b.toFile());double total=0;for(int j=0;j<x.getHeight();j++)for(int i=0;i<x.getWidth();i++){int c=x.getRGB(i,j),d=y.getRGB(i,j);for(int shift:new int[]{0,8,16})total+=Math.abs(((c>>>shift)&255)-((d>>>shift)&255));}return total/(x.getWidth()*x.getHeight()*3.0);}
    static final class FieldRunner implements AutoCloseable {
        final Renderer renderer; final RtxptAccess host; final Path folder;
        long sequence=0,revision=1;Set<Long> previousIds=Set.of();
        FieldRunner(String input)throws Exception {
            folder=OUTPUT.resolve("fields-"+input);Files.createDirectories(folder);renderer=Renderer.open("rtxpt",configuration(input).copyBuilder().pathTracing(new PathTracingOptions(1,2,1,RendererFeaturePreference.DISABLED,RendererFeaturePreference.DISABLED,RendererFeaturePreference.DISABLED,4,configuration(input).pathTracing().quality().withLighting(configuration(input).pathTracing().quality().lighting().withSampler(PathTracingQualityOptions.LightSampler.POWER)))).build());host=renderer.extension(RtxptAccess.class).orElseThrow();
            var source=RtxptAcceptanceFixture.scene();var cube=source.upserts().meshes().getFirst();
            renderer.apply(SceneTransaction.builder(1).resetScene().upsert(MaterialAsset.builder(1).baseColorRgba8(0xffcccccc).roughness(.8f).build()).upsert(cube)
                .upsert(SceneInstance.builder(1,1).transform(new AffineTransform(new float[]{7,0,0,0,0,.1f,0,-.05f,0,0,7,0})).build())
                .upsert(SceneInstance.builder(2,1).transform(new AffineTransform(new float[]{1,0,0,0,0,1.5f,0,.75f,0,0,1,0})).build()).build());
        }
        Path shot(String name,List<SceneLight> lights,EnvironmentState environment)throws Exception {
            var tx=SceneTransaction.builder(++revision);var ids=new HashSet<Long>();for(var l:lights){ids.add(l.id());tx.upsert(l);}for(long id:previousIds)if(!ids.contains(id))tx.removeLight(id);
            renderer.apply(tx.build());previousIds=ids;
            renderer.submit(request(environment));settle(host,2);var path=folder.resolve(name+".png");save(host,path);return path;
        }
        RenderFrameRequest request(EnvironmentState environment){return RenderFrameRequest.builder(sequence++,320,180,CameraState.lookAt(5,4,6,0,0,0).verticalFieldOfViewDegrees(52).aspectRatio(16d/9).build()).minimumSceneRevision(revision).environment(environment).build();}
        void changed(String field,Path a,Path b,double minimum)throws Exception {double error=difference(a,b);require(error>minimum,"Actual pixels for "+field+" difference="+error);System.out.println("RTXPT_LIGHTING_FIELD "+folder.getFileName()+" "+field+" meanRgbDifference="+error);}
        public void close(){renderer.close();}
    }
    static void fields(String input)throws Exception {
        try(var f=new FieldRunner(input)) {
            var point=SceneLight.point(100,0,4,0).intensity(35).range(12).build();
            var dark=f.shot("zero",List.of(point.toBuilder().intensity(0).build()),DARK);
            var on=f.shot("point",List.of(point),DARK);f.changed("intensity",dark,on,3);
            var shortRange=f.shot("short-range",List.of(point.toBuilder().range(1).build()),DARK);f.changed("range",shortRange,on,3);
            var noShadow=f.shot("no-shadow",List.of(point.toBuilder().castsShadow(false).build()),DARK);f.changed("castsShadow",on,noShadow,.15);require(mean(noShadow)>mean(on)+.1,"Disabling shadows increases visible light");
            var moved=f.shot("position",List.of(SceneLight.point(100,3,2,0).intensity(35).range(12).build()),DARK);f.changed("position",on,moved,1);
            var red=f.shot("red",List.of(point.toBuilder().color(1,0,0).build()),DARK);
            var blue=f.shot("blue",List.of(point.toBuilder().color(0,0,1).build()),DARK);f.changed("color",red,blue,3);
            var spot=SceneLight.spot(100,0,4,0,0,-1,0).intensity(35).range(12).coneCosines(.99f,.95f).build();
            var narrow=f.shot("narrow-cone",List.of(spot),DARK);
            var wide=f.shot("wide-cone",List.of(spot.toBuilder().coneCosines(.4f,.1f).build()),DARK);f.changed("spot-cones",narrow,wide,1);
            var down=f.shot("direction-down",List.of(SceneLight.directional(100,0,-1,0).intensity(3).build()),DARK);
            var up=f.shot("direction-up",List.of(SceneLight.directional(100,0,1,0).intensity(3).build()),DARK);f.changed("direction",down,up,3);
            var directionals=new ArrayList<SceneLight>();for(int i=0;i<20;i++)directionals.add(SceneLight.directional(i,0,-1,0).intensity(0).build());
            var allOff=f.shot("twenty-directionals-off",directionals,DARK);directionals.set(19,directionals.get(19).toBuilder().intensity(3).build());
            var twentieth=f.shot("twentieth-directional-only",directionals,DARK);f.changed("twentieth-directional",allOff,twentieth,3);
            var sunDown=f.shot("environment-sun-down",List.of(),EnvironmentState.builder().sunDirection(0,-1,0).sunIntensity(3).build());
            var sunUp=f.shot("environment-sun-up",List.of(),EnvironmentState.builder().sunDirection(0,1,0).sunIntensity(3).build());f.changed("EnvironmentState-sun-direction",sunDown,sunUp,3);
            var skyRed=f.shot("environment-sky-red",List.of(),EnvironmentState.builder().skyRadiance(1,0,0).ambientIntensity(.5f).build());
            var skyBlue=f.shot("environment-sky-blue",List.of(),EnvironmentState.builder().skyRadiance(0,0,1).ambientIntensity(.5f).build());f.changed("EnvironmentState-sky-RGB",skyRed,skyBlue,3);
            var before=f.host.lightingTimings();var exports=f.host.bridgeTimings().sceneExports();
            for(int i=0;i<10;i++)f.renderer.submit(f.request(EnvironmentState.builder().skyRadiance(.05f+i*.015f,.1f,.2f).ambientIntensity(.3f+i*.02f)
                .sunDirection(.19518f,-.9759f,.09759f).sunRadiance(1,.9f-i*.02f,.6f).sunIntensity(.3f+i*.03f).build()));
            var after=f.host.lightingTimings();require(exports==1&&f.host.bridgeTimings().sceneExports()==1&&after.nativeSceneLoads()==before.nativeSceneLoads(),"Ten consecutive environment frames preserve geometry");
            require(after.committedBatches()==before.committedBatches()+10&&after.recordedUploads()==before.recordedUploads()+10,"Ten real environment changes execute ten uploads");
            require(f.renderer.diagnostics().latestCompletedFrameSequence()==f.sequence-1,"Consecutive environment frame sequences complete normally");
            System.out.println("RTXPT_LIGHTING_FIELDS PASS "+input+" "+after);
        }
    }
    static void directionalAdvanced(String name)throws Exception {
        var folder=OUTPUT.resolve("directional-"+name);Files.createDirectories(folder);
        try(var r=Renderer.open("rtxpt",configuration(name))) {
            var host=r.extension(RtxptAccess.class).orElseThrow();var cube=RtxptAcceptanceFixture.scene().upserts().meshes().getFirst();
            r.apply(SceneTransaction.builder(1).resetScene().upsert(MaterialAsset.builder(1).baseColorRgba8(0xffcccccc).roughness(.8f).build()).upsert(cube)
                .upsert(SceneInstance.builder(1,1).transform(new AffineTransform(new float[]{7,0,0,0,0,.1f,0,-.05f,0,0,7,0})).build())
                .upsert(SceneInstance.builder(2,1).transform(new AffineTransform(new float[]{1,0,0,0,0,1.5f,0,.75f,0,0,1,0})).build()).build());
            r.submit(frame(0,1,DARK));settle(host,8);save(host,folder.resolve("off.png"));
            var tx=SceneTransaction.builder(2);for(int i=0;i<20;i++)tx.upsert(SceneLight.directional(i,0,-1,0).intensity(i==19?3:0).build());r.apply(tx.build());
            r.submit(frame(1,2,DARK));settle(host,16);save(host,folder.resolve("only-twentieth.png"));
            double increase=mean(folder.resolve("only-twentieth.png"))-mean(folder.resolve("off.png"));
            require(increase>3,"Advanced route actually samples the twentieth directional light, increase="+increase);
            var status=host.runtimeStatus();var expected=RtxptOptions.resolve(RtxptOptions.profile(configuration(name)));
            require(status.restirDi()==expected.restirDi()&&status.restirGi()==expected.restirGi()&&status.sharc()==expected.sharc(),"Requested reuse actually active");
            require(host.bridgeTimings().sceneExports()==1&&host.lightingTimings().nativeSceneLoads()==1,"Advanced directional update never reloads geometry");
            System.out.println("RTXPT_LIGHTING_DIRECTIONAL PASS "+name+" meanRgbIncrease="+increase+" "+host.lightingTimings()+" "+status);
        }
    }
    static double blockDifference(Path a,Path b)throws Exception {
        var x=ImageIO.read(a.toFile());var y=ImageIO.read(b.toFile());double total=0;int blocks=0;
        for(int top=0;top<x.getHeight();top+=16)for(int left=0;left<x.getWidth();left+=16){
            int pixels=0;double[] sum=new double[3];for(int j=top;j<Math.min(top+16,x.getHeight());j++)for(int i=left;i<Math.min(left+16,x.getWidth());i++){
                int c=x.getRGB(i,j),d=y.getRGB(i,j);pixels++;for(int k=0;k<3;k++)sum[k]+=((c>>>(k*8))&255)-((d>>>(k*8))&255);
            }for(double v:sum)total+=Math.abs(v/pixels);blocks++;
        }return total/(blocks*3);
    }
    public static void main(String[] args)throws Exception {
        Files.createDirectories(OUTPUT);
        for(String name:args.length==0?new String[]{"fields-analytic","fields-raw","analytic","raw","sr","rr","nrd","di-gi","di-sharc","gi-sharc","directional-di-gi","directional-di-sharc","directional-gi-sharc"}:args) {
            if(name.startsWith("directional-")){directionalAdvanced(name.substring(12));continue;}
            if(name.startsWith("fields-")){fields(name.substring(7));continue;}
            var folder=OUTPUT.resolve(name);Files.createDirectories(folder);
            try(var r=Renderer.open("rtxpt",configuration(name))) {
                var host=r.extension(RtxptAccess.class).orElseThrow();r.apply(RtxptAcceptanceFixture.scene());r.submit(frame(0,1,DARK));settle(host,3);
                save(host,folder.resolve("dark.png"));var start=host.lightingTimings();
                var batch=SceneTransaction.builder(2);lights().forEach(batch::upsert);r.apply(batch.build());
                r.submit(frame(1,2,environment()));settle(host,12);save(host,folder.resolve("incremental.png"));
                var lit=host.lightingTimings();require(host.bridgeTimings().sceneExports()==1&&lit.nativeSceneLoads()==start.nativeSceneLoads(),"Lights/environment never reload geometry");
                require(lit.committedBatches()==start.committedBatches()+1&&lit.recordedUploads()==start.recordedUploads()+1,"Actual native commit and upload counted");
                r.submit(frame(2,2,environment()));require(host.lightingTimings().committedBatches()==lit.committedBatches(),"Equal generation has no batch");
                require(difference(folder.resolve("dark.png"),folder.resolve("incremental.png"))>1,"New lighting actually changes pixels");
                // Change all fields and type, coalesce multiple transactions, and apply an instance transform in the same frame.
                r.apply(SceneTransaction.builder(3).upsert(SceneLight.point(90,1,2,1).intensity(1).range(2).castsShadow(true).build()).removeLight(0).build());
                r.apply(SceneTransaction.builder(4).upsert(SceneLight.spot(90,-1,3,1,1,-2,-1).color(.3f,1,.2f).intensity(3).range(5).coneCosines(.85f,.6f).castsShadow(false).build())
                    .upsert(SceneInstance.builder(3,3).transform(new AffineTransform(new float[]{1.2f,0,0,1.4f,0,1.3f,0,.65f,0,0,1.2f,.3f})).build()).build());
                r.submit(frame(3,4,environment()));settle(host,12);save(host,folder.resolve("mixed.png"));
                require(host.lightingTimings().committedBatches()==lit.committedBatches()+1&&host.bridgeTimings().sceneExports()==1,"Coalesced mixed transform/light update is incremental");
                var snapshot=((RtxptRenderer)r).sceneSnapshot();
                r.apply(SceneTransaction.builder(5).resetScene().upsertTextures(snapshot.upserts().textures()).upsertMaterials(snapshot.upserts().materials()).upsertMeshes(snapshot.upserts().meshes()).upsertInstances(snapshot.upserts().instances()).upsertLights(snapshot.upserts().lights()).build());r.submit(frame(4,5,environment()));settle(host,12);
                save(host,folder.resolve("fresh.png"));
                double error=difference(folder.resolve("mixed.png"),folder.resolve("fresh.png"));
                double blockError=blockDifference(folder.resolve("mixed.png"),folder.resolve("fresh.png"));
                double meanError=Math.abs(mean(folder.resolve("mixed.png"))-mean(folder.resolve("fresh.png")));
                require(blockError<3&&meanError<1,"Incremental vs fresh LDR reference blockError="+blockError+" meanError="+meanError);
                System.out.println("RTXPT_LIGHTING_REFERENCE "+name+" pixelMeanRgbError="+error+" block16MeanRgbError="+blockError+" globalMeanDifference="+meanError);
                require(host.bridgeTimings().sceneExports()==2&&host.lightingTimings().nativeSceneLoads()==start.nativeSceneLoads()+1,"Structural reset still loads exactly once");
                // Clear every application light and the sun/sky, then resize. The black generation cannot retain previous light energy.
                var clear=SceneTransaction.builder(6);lights().forEach(l->clear.removeLight(l.id()));r.apply(clear.build());r.submit(frame(5,6,DARK));settle(host,12);save(host,folder.resolve("cleared.png"));
                require(mean(folder.resolve("cleared.png"))<mean(folder.resolve("incremental.png")),"Clear removes old illumination");
                require(host.bridgeTimings().sceneExports()==2,"Clear never exports geometry");
                r.submit(RenderFrameRequest.builder(6,1280,720,RtxptAcceptanceFixture.camera()).minimumSceneRevision(6).environment(environment()).build());settle(host,2);
                var status=host.runtimeStatus();var expected=RtxptOptions.resolve(RtxptOptions.profile(configuration(name)));
                boolean standalone=expected.standaloneDenoiser()&&expected.reconstruction()!=RtxptAccess.Reconstruction.DLSS_RR;
                require(status.reconstruction()==expected.reconstruction()&&status.restirDi()==expected.restirDi()&&status.restirGi()==expected.restirGi()&&status.sharc()==expected.sharc()&&status.standaloneDenoiser()==standalone&&status.relaxDenoiser()==(standalone&&expected.relaxDenoiser())&&!status.frameGeneration(),"Lighting preserves requested advanced features");
                System.out.println("RTXPT_LIGHTING PASS "+name+" "+host.lightingTimings()+" "+host.bridgeTimings()+" "+status);
            }
        }
    }
}

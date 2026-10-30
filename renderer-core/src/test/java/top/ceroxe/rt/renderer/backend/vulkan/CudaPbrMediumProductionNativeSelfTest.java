package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import top.ceroxe.rt.renderer.api.*;

/** Final public HDR: independent slab/Beer-Lambert references, actual BRDF/light queries and lifecycle. */
public final class CudaPbrMediumProductionNativeSelfTest {
    private static final Path OUTPUT=Path.of(System.getProperty("top.ceroxe.rt.medium.pbrProductionDirectory","build/luxcore-native-pbr/production"));
    private static final Duration TIMEOUT=Duration.ofSeconds(60);
    private static final double FOV=2, CAMERA_Z=-3, RECEIVER_Z=-4, LIGHT_Z=4;
    private static int width=31,height=23,samples=1;
    private static long sequence,revision;
    private static Renderer renderer;
    private static Set<Long> volumeIds=Set.of();
    private static final List<String> results=new ArrayList<>();
    private static final MediumTransport BUDGET=MediumTransport.balanced().withMaxEvents(32).withMaxDistance(30).withTransmittanceCutoff(0);
    private static final EnvironmentState SUN=EnvironmentState.builder().sunDirection(0,0,-1).sunIntensity(1).build();

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUTPUT);
        String old=System.getProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);
        System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY,"true");
        long before=VulkanGpuSceneFrameSubmitter.diagnosticNativePbrSubmissions.sum();
        FrameSnapshot retained;
        try {
            try(Renderer owned=Renderer.open("vulkan-rt",RendererConfig.builder().maxFramesInFlight(3).validationEnabled(true)
                    .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F).temporalRendering(TemporalRenderingOptions.disabled()).build())) {
                renderer=owned;
                if(Arrays.asList(args).contains("--benchmark")){benchmark();return;}
                if(Arrays.asList(args).contains("--caustics")){caustics();return;}
                if(Arrays.asList(args).contains("--scattering")){
                    long cameraScatterBefore=VulkanGpuSceneFrameSubmitter.diagnosticNativeScatteringSubmissions.sum();
                    long densityScatterBefore=VulkanGpuSceneFrameSubmitter.diagnosticNativeDensityScatteringSubmissions.sum();
                    long multipleScatterBefore=VulkanGpuSceneFrameSubmitter.diagnosticNativeMultipleScatteringSubmissions.sum();
                    long pbrBefore=VulkanGpuSceneFrameSubmitter.diagnosticNativePbrSubmissions.sum();
                    scatteringComposition();scatteringTransportMatrix();scatteringTiledComposition();
                    long cameraScatter=VulkanGpuSceneFrameSubmitter.diagnosticNativeScatteringSubmissions.sum()-cameraScatterBefore;
                    long densityScatter=VulkanGpuSceneFrameSubmitter.diagnosticNativeDensityScatteringSubmissions.sum()-densityScatterBefore;
                    long multipleScatter=VulkanGpuSceneFrameSubmitter.diagnosticNativeMultipleScatteringSubmissions.sum()-multipleScatterBefore;
                    long pbrScatter=VulkanGpuSceneFrameSubmitter.diagnosticNativePbrSubmissions.sum()-pbrBefore;
                    require(cameraScatter==7&&densityScatter==6&&multipleScatter==14&&pbrScatter==27,
                            "PBR contribution integrator routing counters mismatch: direct="+cameraScatter+", density="
                                    +densityScatter+", multiple="+multipleScatter+", PBR="+pbrScatter);
                    require(renderer.health().activeFailure().isEmpty(),"PBR scattering renderer unhealthy");
                    Files.writeString(OUTPUT.resolve("scattering-result.json"),"{\n  \"passed\":true,\n  \"comparisons\":[\n"
                            +String.join(",\n",results)+"\n  ],\n  \"nativeContributionRoutes\":{\"directUniform\":"+cameraScatter
                            +",\"directDensity\":"+densityScatter+",\"multiple\":"+multipleScatter+",\"pbrFrames\":"+pbrScatter+"}\n}\n");
                    System.out.println("PBR scattering production passed: comparisons="+results.size());return;
                }
                Medium a=medium(.4f,.2f,.1f),b=medium(.1f,.5f,.03f);
                setup("sun");var sunBase=capture("sun-control",SUN,BUDGET,FramePrimitiveBatch.empty());
                var one=List.of(new Slab(10,-2,2,a,1,1));
                slabs(one);ratio("sun-boundary",sunBase,capture("sun-boundary",SUN,BUDGET,FramePrimitiveBatch.empty()),one,"sun",Medium.vacuum(),30);
                var dielectricAbsorber=new Medium(a.absorption(),Rgb.ZERO,a.emission(),1,1.3f,0);
                var dielectricBoundary=List.of(new Slab(10,-2,2,dielectricAbsorber,1,1));
                slabs(dielectricBoundary);ratio("sun-boundary-interface-free-IOR-1.3",sunBase,
                        capture("sun-boundary-interface-free-IOR-1.3",SUN,BUDGET,FramePrimitiveBatch.empty()),
                        dielectricBoundary,"sun",Medium.vacuum(),30);
                var nested=List.of(new Slab(10,-2,2,a,1,1),new Slab(11,-1,1,b,8,1));
                slabs(nested);ratio("sun-nested-priority",sunBase,capture("sun-nested-priority",SUN,BUDGET,FramePrimitiveBatch.empty()),nested,"sun",Medium.vacuum(),30);
                slabs(List.of());samples=8;var eightBase=capture("eight-SPP-control",SUN,BUDGET,FramePrimitiveBatch.empty());slabs(one);
                ratio("eight-SPP-light-connections",eightBase,capture("eight-SPP-light-connections",SUN,BUDGET,FramePrimitiveBatch.empty()),one,"sun",Medium.vacuum(),30);samples=1;
                // The receiver lies inside this volume while the camera starts outside it.
                var inside=List.of(new Slab(10,-5,-3.5,a,2,1));slabs(inside);
                ratio("receiver-containing-set",sunBase,capture("receiver-containing-set",SUN,BUDGET,FramePrimitiveBatch.empty()),inside,"sun",Medium.vacuum(),30);
                slabs(List.of());
                var local=FramePrimitiveBatch.builder().add(PrimitiveInstance.from(one.getFirst().instance())).build();
                ratio("frame-local-shadow",sunBase,capture("frame-local-shadow",SUN,BUDGET,local),one,"sun",Medium.vacuum(),30);
                equal("frame-local-removal",sunBase,capture("frame-local-removal",SUN,BUDGET,FramePrimitiveBatch.empty()));
                var field=MediumDensityField.grid(50,2,new AffineTransform(new float[]{.5f,0,0,.5f,0,.5f,0,.5f,0,0,.5f,.5f}));
                var density=List.of(new Slab(10,-2,2,a.withDensityField(field),1,128.0/255));slabs(density);
                ratio("boundary-density",sunBase,capture("boundary-density",SUN,BUDGET,FramePrimitiveBatch.empty()),density,"sun",Medium.vacuum(),30);
                // Same asset identity, a new immutable generation: stale atlas reuse changes the entire image.
                apply(SceneTransaction.builder(++revision).upsert(texture(255)).build());
                var replaced=List.of(new Slab(10,-2,2,a.withDensityField(field),1,1));
                ratio("density-asset-replacement",sunBase,capture("density-asset-replacement",SUN,BUDGET,FramePrimitiveBatch.empty()),replaced,"sun",Medium.vacuum(),30);
                setup("point");var pointBase=capture("point-control",EnvironmentState.neutral(),BUDGET,FramePrimitiveBatch.empty());slabs(one);
                ratio("point-boundary",pointBase,capture("point-boundary",EnvironmentState.neutral(),BUDGET,FramePrimitiveBatch.empty()),one,"point",Medium.vacuum(),30);
                setup("spot");var spotBase=capture("spot-control",EnvironmentState.neutral(),BUDGET,FramePrimitiveBatch.empty());slabs(one);
                ratio("spot-boundary",spotBase,capture("spot-boundary",EnvironmentState.neutral(),BUDGET,FramePrimitiveBatch.empty()),one,"point",Medium.vacuum(),30);
                setup("point");
                var environmentMedium=new Medium(new Rgb(.07f,.04f,.02f),Rgb.ZERO,new Rgb(.01f,.02f,.03f),1,1,0);
                var environment=EnvironmentState.builder().cameraMedium(environmentMedium).build();
                ratio("environment-camera-emission-and-shadow",pointBase,capture("environment-camera-emission-and-shadow",environment,BUDGET,FramePrimitiveBatch.empty()),
                        List.of(),"point",environmentMedium,30);
                singleSidedPbr(one);
                scatteringComposition();
                scatteringTransportMatrix();
                // Distant geometry remains authoritative even when medium integration is short.
                setup("sun");slabs(one);
                apply(SceneTransaction.builder(++revision).upsert(plane(9,4)).upsert(SceneInstance.builder(9,9).build()).build());
                var blocked=capture("distant-blocker-short-medium-budget",SUN,BUDGET.withMaxDistance(.25f),FramePrimitiveBatch.empty());
                allBlack(blocked,"distant blocker was clipped to medium maxDistance");
                apply(SceneTransaction.builder(++revision).removeInstance(9).build());
                ratio("short-medium-budget",sunBase,capture("short-medium-budget",SUN,BUDGET.withMaxDistance(.25f),FramePrimitiveBatch.empty()),one,"sun",Medium.vacuum(),.25);
                allBlack(capture("shadow-event-budget",SUN,BUDGET.withMaxEvents(2),FramePrimitiveBatch.empty()),"exhausted boundary budget leaked terminal lighting");
                retained=capture("lifecycle-retained",SUN,BUDGET,FramePrimitiveBatch.empty());
                var saved=retained.pixels();byte[] bytes=new byte[saved.remaining()];saved.get(bytes);
                width=43;height=17;capture("resize",SUN,BUDGET,FramePrimitiveBatch.empty());width=31;height=23;
                equal("resize-recovery",retained,capture("resize-recovery",SUN,BUDGET,FramePrimitiveBatch.empty()));
                require(Arrays.equals(bytes,toBytes(retained)),"retained snapshot changed after slot reuse/resize");
                require(renderer.health().activeFailure().isEmpty(),"PBR medium renderer unhealthy");
            }
            scan(retained); // Snapshot ownership must survive complete native resource closure.
            long routes=VulkanGpuSceneFrameSubmitter.diagnosticNativePbrSubmissions.sum()-before;
            require(routes>=14,"public PBR frames bypassed native routing: "+routes);
            Files.writeString(OUTPUT.resolve("result.json"),"{\n  \"passed\":true,\n  \"scope\":\"public-linear-HDR-PBR-nonscattering-native-medium\",\n  \"nativeFrames\":"+routes
                    +",\n  \"comparisons\":[\n"+String.join(",\n",results)+"\n  ]\n}\n");
            System.out.println("CudaPbrMediumProductionNativeSelfTest passed: comparisons="+results.size()+" nativeFrames="+routes);
        } finally {renderer=null;if(old==null)System.clearProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);else System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY,old);}
    }

    private static void caustics() throws Exception {
        long nativeBefore=VulkanGpuSceneFrameSubmitter.diagnosticNativePbrSubmissions.sum();
        long photonsBefore=CausticPhotonPlan.diagnosticPhotonDispatches.sum();
        var absorber=new Medium(new Rgb(0,.08f,.16f),Rgb.ZERO,Rgb.ZERO,1,1,0);
        var environment=SUN.toBuilder().cameraMedium(absorber).build();
        var policy=Caustics.photonMap(0,0,-1,6,524288,.18f).withMaxEvents(2);
        var transaction=SceneTransaction.builder(++revision).resetScene()
                .upsert(MaterialAsset.builder(1).roughness(1).metallic(0).doubleSided(true).build())
                .upsert(MaterialAsset.builder(2).roughness(0).metallic(0).doubleSided(true).build())
                .upsert(plane(1,RECEIVER_Z)).upsert(causticMirror())
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build());
        apply(transaction.build());
        var off=captureCaustic("absorber-caustic-off",environment,Caustics.disabled());
        var on=captureCaustic("absorber-caustic-on",environment,policy);
        double maxGreenError=0,maxBlueError=0,energy=0;int visible=0;
        var lhs=off.pixels().order(ByteOrder.LITTLE_ENDIAN);var rhs=on.pixels().order(ByteOrder.LITTLE_ENDIAN);
        // Independent specular geometry: mirror center M=(2,0,0), incoming D=(0,0,-1),
        // target receiver P=(0,0,-4). The reflection direction is (-2,0,-4)/sqrt(20).
        double ox=-1/Math.sqrt(5),oz=-2/Math.sqrt(5);
        double nx=ox, nz=1+oz,normalLength=Math.hypot(nx,nz);nx/=normalLength;nz/=normalLength;
        double denom=nx*ox+nz*oz;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            int at=(y*width+x)*8;
            double red=Float.float16ToFloat(rhs.getShort(at))-Float.float16ToFloat(lhs.getShort(at));
            if(red<=.003)continue;
            double fy=Math.tan(Math.toRadians(FOV)/2),px=((x+.5)/width*2-1)*fy*width/height;
            double py=-((y+.5)/height*2-1)*fy;
            double t=(nx*(px-2)+nz*RECEIVER_Z)/denom;
            double mirrorZ=RECEIVER_Z-oz*t;
            double length=5-mirrorZ+t+Math.sqrt(1+px*px+py*py);
            double green=(Float.float16ToFloat(rhs.getShort(at+2))-Float.float16ToFloat(lhs.getShort(at+2)))/red;
            double blue=(Float.float16ToFloat(rhs.getShort(at+4))-Float.float16ToFloat(lhs.getShort(at+4)))/red;
            double ge=Math.abs(green-Math.exp(-.08*length)),be=Math.abs(blue-Math.exp(-.16*length));
            maxGreenError=Math.max(maxGreenError,ge);maxBlueError=Math.max(maxBlueError,be);
            require(ge<.012 && be<.009,"native caustic spectral Beer-Lambert mismatch pixel="+x+","+y
                    +" pathLength="+length+" green="+green+" blue="+blue);
            energy+=red;visible++;
        }
        require(visible>=width*height*3/4 && energy>.1,"native absorber caustic did not deliver reflected HDR energy");
        apply(SceneTransaction.builder(++revision).removeInstance(2).build());
        equal("caustic-generator-removal",off,captureCaustic("absorber-caustic-generator-removed",environment,policy));
        var scatteringMedium=new Medium(Rgb.ZERO,new Rgb(.2f,0,0),Rgb.ZERO,1,1,0);
        var scatteringEnvironment=SUN.toBuilder().cameraMedium(scatteringMedium).build();
        var scatteringTransport=BUDGET.withScatteringSteps(64).withScatteringBounces(0);
        var scatteringPolicy=Caustics.photonMap(0,0,-1,6,524288,.18f).withMaxEvents(8);
        apply(SceneTransaction.builder(++revision).resetScene()
                .upsert(MaterialAsset.builder(1).roughness(1).metallic(0).doubleSided(true).build())
                .upsert(MaterialAsset.builder(2).roughness(0).metallic(0).doubleSided(true).build())
                .upsert(plane(1,RECEIVER_Z)).upsert(causticMirror())
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build());
        var scatteringOff=captureCaustic("scattering-caustic-off",scatteringEnvironment,Caustics.disabled(),scatteringTransport);
        long scatteringPhotonsBefore=CausticPhotonPlan.diagnosticPhotonDispatches.sum();
        var scatteringOn=captureCaustic("scattering-caustic-on",scatteringEnvironment,scatteringPolicy,scatteringTransport);
        var scatteringLhs=scatteringOff.pixels().order(ByteOrder.LITTLE_ENDIAN);
        var scatteringRhs=scatteringOn.pixels().order(ByteOrder.LITTLE_ENDIAN);
        double scatteringCausticEnergy=0;int scatteringVisible=0;
        for(int pixel=0;pixel<width*height;pixel++) {
            double delta=Float.float16ToFloat(scatteringRhs.getShort(pixel*8))-Float.float16ToFloat(scatteringLhs.getShort(pixel*8));
            if(delta>.001){scatteringCausticEnergy+=delta;scatteringVisible++;}
        }
        require(scatteringVisible>=width*height/8&&scatteringCausticEnergy>.01,
                "single-scattering paths through the specular mirror produced no receiver caustic: visible="
                        +scatteringVisible+" energy="+scatteringCausticEnergy);
        require(CausticPhotonPlan.diagnosticPhotonDispatches.sum()-scatteringPhotonsBefore==1,
                "scattering caustic frame did not dispatch the native photon map exactly once");
        require(VulkanGpuSceneFrameSubmitter.diagnosticNativePbrSubmissions.sum()-nativeBefore==5,
                "caustic frames bypassed native PBR/CUDA resolve");
        require(CausticPhotonPlan.diagnosticPhotonDispatches.sum()-photonsBefore==3,
                "each enabled native PBR caustic frame must dispatch its photon map exactly once");
        String json=String.format(Locale.ROOT,"{\"passed\":true,\"scope\":\"homogeneous-IOR-one-absorption-and-single-scattering-PBR-caustics\","
                +"\"nativeFrames\":5,\"photonDispatches\":3,\"absorberVisiblePixels\":%d,\"absorberCausticRedEnergy\":%.9f,"
                +"\"scatteringVisiblePixels\":%d,\"scatteringCausticRedEnergy\":%.9f,"
                +"\"maxGreenRatioError\":%.9f,\"maxBlueRatioError\":%.9f}%n",visible,energy,
                scatteringVisible,scatteringCausticEnergy,maxGreenError,maxBlueError);
        Files.writeString(OUTPUT.resolve("caustic-result.json"),json);
        require(renderer.health().activeFailure().isEmpty(),"native absorber caustic renderer unhealthy");
        System.out.println("Native absorber PBR caustic gate "+json);
    }
    private static MeshAsset causticMirror() {
        double ox=-1/Math.sqrt(5),oz=-2/Math.sqrt(5),nx=ox,nz=1+oz,l=Math.hypot(nx,nz);nx/=l;nz/=l;
        float[] p=new float[12];int at=0;
        for(double[] corner:new double[][]{{-1,-1.2},{1,-1.2},{1,1.2},{-1,1.2}}) {
            p[at++]=(float)(2+nz*corner[0]);p[at++]=(float)corner[1];p[at++]=(float)(-nx*corner[0]);
        }
        return MeshAsset.triangles(2,p,new int[]{0,1,2,0,2,3},2);
    }
    private static FrameSnapshot captureCaustic(String name,EnvironmentState environment,Caustics caustics) throws Exception {
        return captureCaustic(name,environment,caustics,BUDGET);
    }
    private static FrameSnapshot captureCaustic(String name,EnvironmentState environment,Caustics caustics,
            MediumTransport transport) throws Exception {
        var camera=CameraState.lookAt(0,0,CAMERA_Z,0,0,RECEIVER_Z).verticalFieldOfViewDegrees(FOV).aspectRatio((double)width/height).build();
        var request=RenderFrameRequest.builder(++sequence,width,height,camera).minimumSceneRevision(revision)
                .environment(environment).clock(FrameClock.of(0,0)).mediumTransport(transport).caustics(caustics).build();
        long deadline=System.nanoTime()+TIMEOUT.toNanos();
        while(!(renderer.trySubmit(request) instanceof Renderer.FrameSubmitted)) {
            require(System.nanoTime()<deadline,"caustic frame admission timeout");Thread.onSpinWait();
        }
        var frame=renderer.awaitFrame(TIMEOUT).orElseThrow(()->new AssertionError("caustic frame completion timeout"));
        require(frame.frameSequence()==request.sequence() && frame.renderedSceneRevision()==revision,"caustic HDR provenance mismatch");
        scan(frame);Files.write(OUTPUT.resolve(name+".rgba16f"),toBytes(frame));return frame;
    }

    private static void benchmark() throws Exception {
        width=Integer.getInteger("top.ceroxe.rt.medium.pbrWidth",1920);height=Integer.getInteger("top.ceroxe.rt.medium.pbrHeight",1080);
        int warmup=Integer.getInteger("top.ceroxe.rt.medium.pbrWarmup",12),frames=Integer.getInteger("top.ceroxe.rt.medium.pbrFrames",60);
        int lights=Integer.getInteger("top.ceroxe.rt.medium.pbrLights",8);
        require(width>0&&height>0&&warmup>=1&&frames>=3&&lights>=1,"invalid PBR benchmark extent/budget");
        setup("point");var transaction=SceneTransaction.builder(++revision);
        for(int i=1;i<lights;i++)transaction.upsert(SceneLight.point(i+1,(i%4-1.5)*.3,(i/4)*.2,LIGHT_Z).intensity(20).range(20).build());
        apply(transaction.build());slabs(List.of(new Slab(10,-2,2,medium(.4f,.2f,.1f),1,1)));
        for(int i=0;i<warmup;i++)read(false,EnvironmentState.neutral(),BUDGET,FramePrimitiveBatch.empty());
        long[] nanos=new long[frames];long started=System.nanoTime();
        for(int i=0;i<frames;i++){long at=System.nanoTime();read(false,EnvironmentState.neutral(),BUDGET,FramePrimitiveBatch.empty());nanos[i]=System.nanoTime()-at;}
        long elapsed=System.nanoTime()-started;Arrays.sort(nanos);
        String json=String.format(Locale.ROOT,"{\"width\":%d,\"height\":%d,\"lights\":%d,\"warmup\":%d,\"frames\":%d,\"endToEndFps\":%.4f,\"p50ms\":%.4f,\"p95ms\":%.4f,\"validation\":true,\"cpuSnapshotCopy\":false}",
                width,height,lights,warmup,frames,frames*1e9/elapsed,nanos[frames/2]/1e6,nanos[Math.min(frames-1,(int)Math.ceil(frames*.95)-1)]/1e6);
        Files.writeString(OUTPUT.resolve("benchmark.json"),json+"\n");System.out.println("Native PBR batch packet benchmark "+json);
    }
    private static void singleSidedPbr(List<Slab> volumes) throws Exception {
        long before=VulkanGpuSceneFrameSubmitter.diagnosticNativePbrSubmissions.sum();
        setup("sun");
        var doubleSided=capture("sidedness-double-sided-control",SUN,BUDGET,FramePrimitiveBatch.empty());
        var material=MaterialAsset.builder(1).roughness(1).metallic(0).doubleSided(false).build();
        apply(SceneTransaction.builder(++revision).upsert(material).build());
        var front=capture("single-sided-front-control",SUN,BUDGET,FramePrimitiveBatch.empty());
        equal("single-sided-front-matches-double-sided",doubleSided,front);
        require(Float.float16ToFloat(front.pixels().getShort(((height/2)*width+width/2)*8))>.1f,
                "front-facing sidedness control must be visibly lit");
        slabs(volumes);
        var throughMedium=capture("single-sided-front-boundary",SUN,BUDGET,FramePrimitiveBatch.empty());
        ratio("single-sided-front-boundary",front,throughMedium,volumes,"sun",Medium.vacuum(),30);
        // A +Z-wound blocker is back-facing to the +Z shadow ray, but not to the camera.
        apply(SceneTransaction.builder(++revision).upsert(plane(9,4)).upsert(SceneInstance.builder(9,9).build()).build());
        equal("single-sided-backface-does-not-shadow",throughMedium,
                capture("single-sided-backface-does-not-shadow",SUN,BUDGET,FramePrimitiveBatch.empty()));
        apply(SceneTransaction.builder(++revision).upsert(reversedPlane(9,4)).build());
        allBlack(capture("single-sided-frontface-shadows",SUN,BUDGET,FramePrimitiveBatch.empty()),
                "single-sided frontface blocker failed to occlude native PBR light connection");
        apply(SceneTransaction.builder(++revision).removeInstance(9).upsert(reversedPlane(1,RECEIVER_Z)).build());
        allBlack(capture("single-sided-camera-backface",SUN,BUDGET,FramePrimitiveBatch.empty()),
                "camera packet shaded a rejected single-sided backface");
        apply(SceneTransaction.builder(++revision).upsert(material.toBuilder().doubleSided(true).build()).build());
        equal("double-sided-camera-backface-shades",throughMedium,
                capture("double-sided-camera-backface-shades",SUN,BUDGET,FramePrimitiveBatch.empty()));
        // Reuse the same assets through the public frame-local entry point.
        apply(SceneTransaction.builder(++revision).upsert(material).upsert(plane(1,RECEIVER_Z)).removeInstance(1).build());
        var local=FramePrimitiveBatch.builder().add(PrimitiveInstance.builder(1).build()).build();
        equal("single-sided-frame-local-front",throughMedium,
                capture("single-sided-frame-local-front",SUN,BUDGET,local));
        apply(SceneTransaction.builder(++revision).upsert(reversedPlane(1,RECEIVER_Z)).build());
        allBlack(capture("single-sided-frame-local-backface",SUN,BUDGET,local),
                "frame-local camera packet shaded a rejected single-sided backface");
        require(VulkanGpuSceneFrameSubmitter.diagnosticNativePbrSubmissions.sum()-before>=7,
                "single-sided medium fixtures bypassed the native PBR route");
        results.add("    {\"name\":\"single-sided-camera-and-shadow-front-back-faces\",\"passed\":true}");
    }
    private static void scatteringComposition() throws Exception {
        setup("sun");var base=capture("PBR-scattering-surface-control",SUN,BUDGET,FramePrimitiveBatch.empty());
        float absorption=.08f,scattering=.12f;float[] emission={.01f,.02f,.03f};double sigma=(float)(absorption+scattering);
        var medium=new Medium(new Rgb(absorption,absorption,absorption),new Rgb(scattering,scattering,scattering),
                new Rgb(emission[0],emission[1],emission[2]),1,1,0);
        var env=SUN.toBuilder().cameraMedium(medium).build();
        var transport=BUDGET.withScatteringSteps(64).withMaxDistance(3);
        var actual=capture("PBR-scattering-composed-HDR",env,transport,FramePrimitiveBatch.empty());
        double error=0;var lhs=base.pixels().order(ByteOrder.LITTLE_ENDIAN);var rhs=actual.pixels().order(ByteOrder.LITTLE_ENDIAN);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            double fy=Math.tan(Math.toRadians(FOV)/2),dx=((x+.5)/width*2-1)*fy*width/height,dy=-((y+.5)/height*2-1)*fy;
            double distance=Math.sqrt(1+dx*dx+dy*dy),tr=Math.exp(-sigma*distance),integral=-Math.expm1(-sigma*distance)/sigma;
            double sunTr=Math.exp(-sigma*3),incoming=scattering*sunTr/(4*Math.PI);
            for(int c=0;c<3;c++) {int at=((y*width+x)*4+c)*2;
                double expected=Float.float16ToFloat(lhs.getShort(at))*tr*sunTr+(emission[c]+incoming)*integral;
                double observed=Float.float16ToFloat(rhs.getShort(at)),delta=Math.abs(expected-observed);error=Math.max(error,delta);
                require(delta<.003+Math.abs(expected)*.006,"PBR+scattering independent HDR mismatch pixel="+x+","+y+" channel="+c+" expected="+expected+" actual="+observed);
            }
        }
        var darkEnv=env.toBuilder().sunIntensity(0).build();
        var dark=capture("PBR-scattering-emission-only",darkEnv,transport,FramePrimitiveBatch.empty());
        var data=dark.pixels().order(ByteOrder.LITTLE_ENDIAN);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            double fy=Math.tan(Math.toRadians(FOV)/2),dx=((x+.5)/width*2-1)*fy*width/height,dy=-((y+.5)/height*2-1)*fy;
            double integral=-Math.expm1(-sigma*Math.sqrt(1+dx*dx+dy*dy))/sigma;
            for(int c=0;c<3;c++)require(Math.abs(Float.float16ToFloat(data.getShort(((y*width+x)*4+c)*2))-emission[c]*integral)<.0002,
                    "composed medium emission was duplicated or omitted");
        }
        int oldWidth=width,oldHeight=height;
        try {width=1920;height=1080;read(false,env,transport,FramePrimitiveBatch.empty());}
        finally {width=oldWidth;height=oldHeight;}
        results.add("    {\"name\":\"PBR-single-scattering-composition\",\"maxAbsoluteError\":"+error+",\"fullHdCompleted\":true}");
    }
    private static void scatteringTransportMatrix() throws Exception {
        var field=MediumDensityField.grid(50,2,new AffineTransform(new float[]{
                .025f,0,0,.5f,0,.025f,0,.5f,0,0,.025f,.5f}));
        var medium=new Medium(new Rgb(.08f,.08f,.08f),new Rgb(.12f,.12f,.12f),Rgb.ZERO,1,1.3f,0);
        var dark=EnvironmentState.builder().ambientIntensity(0).sunIntensity(0).build();
        var lit=dark.toBuilder().sunDirection(0,0,-1).sunIntensity(1).build();
        for(int bounces:new int[]{0,1,3}) {
            FrameSnapshot uniform=null;
            for(boolean dense:new boolean[]{false,true}) {
                setup("sun");apply(SceneTransaction.builder(++revision).upsert(texture(255)).build());
                var selected=dense?medium.withDensityField(field):medium;
                var transport=BUDGET.withMaxDistance(3).withScatteringSteps(64).withScatteringBounces(bounces);
                String name="PBR-"+(dense?"density":"uniform")+"-bounces-"+bounces;
                var black=capture(name+"-dark-white-surface",dark.toBuilder().cameraMedium(selected).build(),transport,FramePrimitiveBatch.empty());
                allBlack(black,"unlit PBR albedo became self-emission in "+name);
                var actual=capture(name+"-lit",lit.toBuilder().cameraMedium(selected).build(),transport,FramePrimitiveBatch.empty());
                double energy=meanRgb(actual);require(energy>0,"light failed to reach PBR/scattering output in "+name);
                if(!dense)uniform=actual;
                else if(bounces==0) {
                    var a=uniform.pixels().order(ByteOrder.LITTLE_ENDIAN);var b=actual.pixels().order(ByteOrder.LITTLE_ENDIAN);
                    for(int i=0;i<width*height;i++)for(int c=0;c<3;c++) {
                        double expected=Float.float16ToFloat(a.getShort((i*4+c)*2));
                        double observed=Float.float16ToFloat(b.getShort((i*4+c)*2));
                        require(Math.abs(expected-observed)<.003+Math.abs(expected)*.006,
                                "constant density/direct composition differs from uniform transport");
                    }
                }
                results.add("    {\"name\":\""+name+"\",\"darkBelowTolerance\":0.0001,\"meanLitRadiance\":"+energy+"}");
            }
        }
        setup("sun");apply(SceneTransaction.builder(++revision).upsert(texture(0)).build());
        var zero=medium.withDensityField(field);
        var env=lit.toBuilder().cameraMedium(zero).build();
        var vacuum=capture("PBR-density-zero-vacuum",lit,BUDGET.withMaxDistance(3),FramePrimitiveBatch.empty());
        for(int bounces:new int[]{0,1,3}) {
            var actual=capture("PBR-density-zero-bounces-"+bounces,env,
                    BUDGET.withMaxDistance(3).withScatteringSteps(64).withScatteringBounces(bounces),FramePrimitiveBatch.empty());
            equal("PBR-zero-density-bounces-"+bounces,vacuum,actual);
        }
        // Replacing the immutable texture generation must invalidate both native density owners.
        apply(SceneTransaction.builder(++revision).upsert(texture(255)).build());
        var restored=capture("PBR-density-generation-restored",env,BUDGET.withMaxDistance(3).withScatteringSteps(64),FramePrimitiveBatch.empty());
        require(!Arrays.equals(toBytes(vacuum),toBytes(restored)),"density replacement reused an empty atlas");
        results.add("    {\"name\":\"PBR-zero-density-and-generation-replacement\",\"passed\":true}");
    }
    private static double meanRgb(FrameSnapshot frame) {
        var p=frame.pixels().order(ByteOrder.LITTLE_ENDIAN);double sum=0;
        for(int i=0;i<width*height;i++)for(int c=0;c<3;c++)sum+=Float.float16ToFloat(p.getShort((i*4+c)*2));
        return sum/(width*height*3.0);
    }
    private static void scatteringTiledComposition() throws Exception {
        String property="top.ceroxe.rt.medium.cuda.maxResidentBytes",old=System.getProperty(property);
        var field=MediumDensityField.grid(50,2,new AffineTransform(new float[]{
                .025f,0,0,.5f,0,.025f,0,.5f,0,0,.025f,.5f}));
        var medium=new Medium(new Rgb(.08f,.08f,.08f),new Rgb(.12f,.12f,.12f),Rgb.ZERO,1,1.3f,0);
        Renderer outerRenderer=renderer;long outerSequence=sequence,outerRevision=revision;Set<Long> outerVolumes=volumeIds;
        try {
            for(int bounces:new int[]{0,1})for(boolean dense:new boolean[]{false,true}) {
                var selected=dense?medium.withDensityField(field):medium;
                var environment=SUN.toBuilder().cameraMedium(selected).build();
                var transport=BUDGET.withMaxDistance(3).withScatteringSteps(64).withScatteringBounces(bounces);
                String name="PBR-tiled-"+(dense?"density":"uniform")+"-bounces-"+bounces;
                var reference=renderTiledCase(property,old,name+"-reference",environment,transport);
                // Less than the minimum complete scratch for this 713-pixel frame:
                // every contribution kind must reuse its tile on this budget.
                System.setProperty(property,"524288");
                var tiled=renderTiledCase(property,"524288",name,environment,transport);
                var a=reference.pixels().order(ByteOrder.LITTLE_ENDIAN);
                var b=tiled.pixels().order(ByteOrder.LITTLE_ENDIAN);
                for(int i=0;i<width*height;i++)for(int c=0;c<3;c++) {
                    double expected=Float.float16ToFloat(a.getShort((i*4+c)*2));
                    double observed=Float.float16ToFloat(b.getShort((i*4+c)*2));
                    require(Math.abs(expected-observed)<.003+Math.abs(expected)*.01,
                            name+" tile reuse changed radiance at pixel "+i+" channel "+c);
                }
                results.add("    {\"name\":\""+name+"\",\"residentLimitBytes\":524288,\"passed\":true}");
            }
        } finally {
            if(old==null)System.clearProperty(property);else System.setProperty(property,old);
            renderer=outerRenderer;sequence=outerSequence;revision=outerRevision;volumeIds=outerVolumes;
        }
    }
    private static FrameSnapshot renderTiledCase(String property,String limit,String name,
            EnvironmentState environment,MediumTransport transport) throws Exception {
        String previous=System.getProperty(property);
        try {
            if(limit==null)System.clearProperty(property);else System.setProperty(property,limit);
            sequence=0;revision=0;volumeIds=Set.of();
            try(Renderer owned=Renderer.open("vulkan-rt",RendererConfig.builder().maxFramesInFlight(2).validationEnabled(true)
                    .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F).temporalRendering(TemporalRenderingOptions.disabled()).build())) {
                renderer=owned;setup("sun");apply(SceneTransaction.builder(++revision).upsert(texture(255)).build());
                return capture(name,environment,transport,FramePrimitiveBatch.empty());
            }
        } finally {if(previous==null)System.clearProperty(property);else System.setProperty(property,previous);}
    }
    private static void setup(String light) throws Exception {
        var t=SceneTransaction.builder(++revision).resetScene().upsert(MaterialAsset.builder(1).roughness(1).metallic(0).doubleSided(true).build())
                .upsert(plane(1,RECEIVER_Z)).upsert(MediumTransportSelfTest.cube(2,1,1)).upsert(SceneInstance.builder(1,1).build()).upsert(texture(128));
        if(light.equals("point"))t.upsert(SceneLight.point(1,0,0,LIGHT_Z).intensity(100).range(20).build());
        if(light.equals("spot"))t.upsert(SceneLight.spot(1,0,0,LIGHT_Z,0,0,-1).coneDegrees(30,60).intensity(100).range(20).build());
        apply(t.build());
        volumeIds=Set.of();
    }
    private static MeshAsset plane(long id,double z){return MeshAsset.triangles(id,new float[]{-100,-100,(float)z,100,-100,(float)z,100,100,(float)z,-100,100,(float)z},new int[]{0,1,2,2,3,0},1);}
    private static MeshAsset reversedPlane(long id,double z){return MeshAsset.triangles(id,new float[]{-100,-100,(float)z,100,-100,(float)z,100,100,(float)z,-100,100,(float)z},new int[]{0,2,1,2,0,3},1);}
    private static void slabs(List<Slab> volumes) throws Exception {
        var t=SceneTransaction.builder(++revision);
        Set<Long> next=volumes.stream().map(Slab::identity).collect(Collectors.toUnmodifiableSet());
        require(next.size()==volumes.size(),"duplicate PBR fixture volume identity");
        for(long id:volumeIds)if(!next.contains(id))t.removeInstance(id);
        for(var v:volumes)t.upsert(v.instance());
        apply(t.build());
        volumeIds=next;
    }
    private static TextureAsset texture(int red) {
        byte[] pixels=new byte[32];for(int i=0;i<pixels.length;i+=4){pixels[i]=(byte)red;pixels[i+3]=(byte)255;}
        return TextureAsset.builder(50,2,4).colorSpace(TextureAsset.ColorSpace.LINEAR).pixelsRgba8(pixels).build();
    }
    private static Medium medium(float r,float g,float b){return new Medium(new Rgb(r,g,b),Rgb.ZERO,Rgb.ZERO,1,1,0);}
    private static void apply(SceneTransaction t) throws Exception {
        long deadline=System.nanoTime()+TIMEOUT.toNanos();for(;;)try{renderer.apply(t);return;}catch(SubmissionRejectedException pending){require(System.nanoTime()<deadline,"PBR scene apply timed out");Thread.sleep(1);}
    }
    private static FrameSnapshot capture(String name,EnvironmentState env,MediumTransport budget,FramePrimitiveBatch batch) throws Exception {
        FrameSnapshot frame=read(true,env,budget,batch);scan(frame);Files.write(OUTPUT.resolve(name+".rgba16f"),toBytes(frame));return frame;
    }
    private static FrameSnapshot read(boolean copy,EnvironmentState env,MediumTransport budget,FramePrimitiveBatch batch) throws Exception {
        var camera=CameraState.lookAt(0,0,CAMERA_Z,0,0,RECEIVER_Z).verticalFieldOfViewDegrees(FOV).aspectRatio((double)width/height).build();
        var request=RenderFrameRequest.builder(++sequence,width,height,camera).minimumSceneRevision(revision).clock(FrameClock.of(0,0))
                .environment(env).antiAliasing(samples==1?AntiAliasingState.disabled():AntiAliasingState.multisampled(samples))
                .mediumTransport(budget).primitiveBatch(batch).build();
        long deadline=System.nanoTime()+TIMEOUT.toNanos();while(!(renderer.trySubmit(request) instanceof Renderer.FrameSubmitted)){require(System.nanoTime()<deadline,"PBR frame admission timeout");Thread.onSpinWait();}
        FrameSnapshot[] result={null};
        for(;;){boolean consumed=renderer.readFrame(view->{require(view.frameSequence()==request.sequence()&&view.renderedSceneRevision()==revision,"PBR frame provenance mismatch");
            require(view.format()==TextureFormat.RGBA16_FLOAT,"PBR frame is not final linear HDR");
            if(copy)result[0]=FrameSnapshot.copyOf(view);else {var p=view.pixels().order(ByteOrder.LITTLE_ENDIAN);int center=((height/2)*width+width/2)*8;
                for(int c=0;c<4;c++)require(Float.isFinite(Float.float16ToFloat(p.getShort(center+c*2))),"nonfinite benchmark HDR");}});
            if(consumed)return result[0];require(System.nanoTime()<deadline,"PBR completion timeout");Thread.onSpinWait();}
    }
    private static void ratio(String name,FrameSnapshot base,FrameSnapshot actual,List<Slab> slabs,String light,Medium environment,double maximum) {
        var lhs=base.pixels().order(ByteOrder.LITTLE_ENDIAN);var rhs=actual.pixels().order(ByteOrder.LITTLE_ENDIAN);double error=0;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            double fy=Math.tan(Math.toRadians(FOV)/2),dx=((x+.5)/width*2-1)*fy*width/height,dy=-((y+.5)/height*2-1)*fy;
            double cameraLength=Math.sqrt(1+dx*dx+dy*dy),px=dx,py=dy;
            double lightLength=light.equals("sun")?1e20:Math.sqrt(px*px+py*py+64),zDirection=light.equals("sun")?1:8/lightLength;
            for(int c=0;c<3;c++) {
                double optical=0;
                // Independent exact intervals along the camera and light segments. Fixture boxes span x/y.
                optical+=mass(slabs,environment,c,CAMERA_Z,-1/cameraLength,Math.min(cameraLength,maximum));
                optical+=mass(slabs,environment,c,RECEIVER_Z,zDirection,Math.min(lightLength,maximum));
                double expected=Float.float16ToFloat(lhs.getShort(((y*width+x)*4+c)*2))*Math.exp(-optical);
                if(environment.emission().isZero()==false) {
                    double a=channel(environment.absorption(),c)*environment.density(),e=channel(environment.emission(),c)*environment.density(),distance=Math.min(cameraLength,maximum);
                    expected+=a==0?e*distance:e/a*(-Math.expm1(-a*distance));
                }
                double observed=Float.float16ToFloat(rhs.getShort(((y*width+x)*4+c)*2));
                double delta=Math.abs(observed-expected);error=Math.max(error,delta);
                require(delta<=.0025+Math.abs(expected)*.004,name+" HDR mismatch pixel="+x+","+y+" channel="+c+" actual="+observed+" expected="+expected);
            }
        }
        results.add("    {\"name\":\""+name+"\",\"maxAbsoluteError\":"+error+"}");
    }
    private static double mass(List<Slab> slabs,Medium environment,int channel,double originZ,double directionZ,double distance) {
        var events=new ArrayList<Double>();events.add(0.0);events.add(distance);
        for(var slab:slabs)for(double z:new double[]{slab.minZ,slab.maxZ}){double t=(z-originZ)/directionZ;if(t>0&&t<distance)events.add(t);}
        events.sort(Double::compare);double sum=0;
        for(int i=1;i<events.size();i++){double first=events.get(i-1),last=events.get(i),z=originZ+directionZ*(first+last)/2;Slab selected=null;
            for(var slab:slabs)if(z>slab.minZ&&z<slab.maxZ&&(selected==null||slab.priority>=selected.priority))selected=slab;
            Medium medium=selected==null?environment:selected.medium;double density=selected==null?1:selected.densityScale;
            sum+=(last-first)*channel(medium.absorption(),channel)*medium.density()*density;
        }return sum;
    }
    private static double channel(Rgb rgb,int c){return c==0?rgb.red():c==1?rgb.green():rgb.blue();}
    private static void allBlack(FrameSnapshot frame,String message){var p=frame.pixels();for(int i=0;i<p.remaining()/2;i++)if(i%4!=3)require(Math.abs(Float.float16ToFloat(p.getShort(i*2)))<.0001,message);}
    private static void equal(String name,FrameSnapshot a,FrameSnapshot b){require(a.pixels().equals(b.pixels()),name+" changed completed HDR");results.add("    {\"name\":\""+name+"\",\"exact\":true}");}
    private static void scan(FrameSnapshot f){var p=f.pixels();for(int i=0;i<p.remaining()/2;i++){float value=Float.float16ToFloat(p.getShort(i*2));require(Float.isFinite(value)&&value>=0,"nonfinite/negative HDR");if(i%4==3)require(value==1,"HDR alpha changed");}}
    private static byte[] toBytes(FrameSnapshot f){ByteBuffer p=f.pixels();byte[] bytes=new byte[p.remaining()];p.get(bytes);return bytes;}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private record Slab(long identity,double minZ,double maxZ,Medium medium,int priority,double densityScale) {
        SceneInstance instance(){return SceneInstance.builder(identity,2).transform(new AffineTransform(new float[]{100,0,0,0,0,100,0,0,0,0,(float)((maxZ-minZ)/2),(float)((maxZ+minZ)/2)}))
                .mediumBoundary(MediumBoundary.volume(medium).withPriority(priority)).build();}
    }
}

package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Locale;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.api.Renderer.FrameSubmitted;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;

/** Small linear-HDR hardware scenes test transport, rather than shader-source spelling. */
public final class MediumTransportNativeSelfTest {
    private static long sequence,revision;
    private static VulkanRendererHost renderer;
    private static FramePrimitiveBatch primitives=FramePrimitiveBatch.empty();
    private static long frameNanoseconds,maximumFrameNanoseconds;
    private static long lastFrameNanoseconds;
    private static int width=32,height=32;
    private static final CameraState OUTSIDE=CameraState.lookAt(0,0,6,0,0,0).verticalFieldOfViewDegrees(30).build();
    private static EnvironmentState.Medium absorbing(float r,float g,float b) {
        return EnvironmentState.Medium.builder().absorption(r,g,b).density(1).build();
    }
    private static SceneInstance volume(long id,float radius,EnvironmentState.Medium medium,int priority) {
        return SceneInstance.builder(id,2).transform(new AffineTransform(new float[]{radius,0,0,0,0,radius,0,0,0,0,radius,0}))
            .mediumBoundary(MediumBoundaryState.builder(medium).priority(priority).surfaceInteraction(false).build()).build();
    }
    public static void main(String[] args) throws Exception {
        var capability=VulkanRtCapabilityProbe.capture();
        MediumTransportSelfTest.require(capability.hardwareRayTracingReady(),"hardware RT required");
        var config=RendererConfig.builder().frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
            .temporalRendering(TemporalRenderingOptions.disabled()).validationEnabled(true).build();
        long openStarted=System.nanoTime();
        try(var owned=new VulkanRendererHost(config,VulkanGpuSceneRenderingSession.open(capability,config,RendererRtDiagnostics.noop()))) {
            double openMillis=(System.nanoTime()-openStarted)/1_000_000.0;
            System.out.printf(Locale.ROOT,"Medium transport session opened in %.1f ms%n",openMillis);
            renderer=owned;
            var plane=MeshAsset.triangles(1,new float[]{-100,-100,-4,100,-100,-4,100,100,-4,-100,100,-4},new int[]{0,1,2,2,3,0},1);
            apply(SceneTransaction.builder(++revision).resetScene().upsert(MaterialAsset.builder(1).shadingModel(MaterialAsset.ShadingModel.UNLIT).doubleSided(true).build())
                .upsert(plane).upsert(MediumTransportSelfTest.cube(2,1,1))
                .upsert(SceneInstance.builder(1,1).build()).upsert(volume(2,2,absorbing(.4f,.2f,0),1)).build());
            var white=render(OUTSIDE,EnvironmentState.neutral());
            near(white[0],Math.exp(-1.6),.007,"four-unit red Beer-Lambert segment");
            near(white[1],Math.exp(-.8),.007,"four-unit green Beer-Lambert segment");
            near(white[2],1,.004,"non-absorbed blue");
            apply(SceneTransaction.builder(++revision).upsert(volume(2,2,absorbing(.4f,.2f,0),1).toBuilder()
                .transform(new AffineTransform(new float[]{-2,0,0,0,0,2,0,0,0,0,2,0})).build()).build());
            near(render(OUTSIDE,EnvironmentState.neutral())[0],Math.exp(-1.6),.007,"mirrored boundary crossings");
            var cube=MediumTransportSelfTest.cube(2,1,1);int[] inwardIndices=new int[cube.triangleIndices().remaining()];cube.triangleIndices().get(inwardIndices);
            for(int i=0;i<inwardIndices.length;i+=3){int swap=inwardIndices[i];inwardIndices[i]=inwardIndices[i+1];inwardIndices[i+1]=swap;}
            float[] cubePoints=new float[cube.positions().remaining()];cube.positions().get(cubePoints);
            apply(SceneTransaction.builder(++revision).upsert(MeshAsset.triangles(2,cubePoints,inwardIndices,1))
                .upsert(volume(2,2,absorbing(.4f,.2f,0),1).toBuilder().mediumBoundary(MediumBoundaryState.builder(absorbing(.4f,.2f,0))
                    .surfaceInteraction(false).priority(1).orientation(MediumBoundaryState.Orientation.INWARD).build()).build()).build());
            near(render(OUTSIDE,EnvironmentState.neutral())[0],Math.exp(-1.6),.007,"inward-oriented boundary crossings");
            apply(SceneTransaction.builder(++revision).upsert(cube).upsert(volume(2,2,absorbing(.4f,.2f,0),1)).build());
            var inside=render(CameraState.lookAt(0,0,0,0,0,-1).build(),EnvironmentState.neutral());
            near(inside[0],Math.exp(-.8),.007,"camera starts inside volume");
            apply(SceneTransaction.builder(++revision).upsert(volume(3,1,absorbing(0,.5f,0),5)).build());
            var nested=render(OUTSIDE,EnvironmentState.neutral());
            near(nested[0],Math.exp(-.8),.009,"inner medium replaces outer extinction");
            near(nested[1],Math.exp(-1.4),.009,"exit restores enclosing medium");
            apply(SceneTransaction.builder(++revision).removeInstance(3).upsert(volume(2,2,
                EnvironmentState.Medium.builder().density(1).emission(.2f,0,0).build(),1)).build());
            var emission=render(OUTSIDE,EnvironmentState.neutral());
            near(emission[0],1.8,.015,"unabsorbed volume emission integrates distance");
            near(emission[1],1,.005,"volume emission must not leak channels");
            apply(SceneTransaction.builder(++revision).upsert(volume(2,2,absorbing(.4f,.2f,0),1).toBuilder()
                .transform(new AffineTransform(new float[]{2,0,0,10,0,2,0,0,0,0,2,0})).build()).build());
            var moved=render(OUTSIDE,EnvironmentState.neutral());near(moved[0],1,.005,"moving boundary updates containment and optical paths");
            apply(SceneTransaction.builder(++revision).removeInstance(2).build());
            near(render(OUTSIDE,EnvironmentState.neutral())[0],1,.005,"removed boundary retires immediately");
            primitives=FramePrimitiveBatch.builder().add(PrimitiveInstance.from(volume(2,2,absorbing(.4f,.2f,0),1))).build();
            near(render(OUTSIDE,EnvironmentState.neutral())[0],Math.exp(-1.6),.007,"frame-local boundary absorption");
            near(render(CameraState.lookAt(0,0,0,0,0,-1).build(),EnvironmentState.neutral())[0],Math.exp(-.8),.007,"frame-local interior camera");
            primitives=FramePrimitiveBatch.empty();
            near(render(OUTSIDE,EnvironmentState.neutral())[0],1,.005,"frame-local boundary removal");
            var global=render(OUTSIDE,EnvironmentState.builder().cameraMedium(absorbing(.1f,0,0)).build());
            near(global[0],Math.exp(-1),.007,"global medium transports the complete camera-to-receiver segment");

            // The camera-to-surface ray avoids the volume; only the incoming light crosses it.
            apply(SceneTransaction.builder(++revision).upsert(MaterialAsset.builder(1).roughness(1).doubleSided(true).build()).build());
            var receiverCamera=CameraState.lookAt(0,0,-3,0,0,-4).verticalFieldOfViewDegrees(1).build();
            var sunlight=EnvironmentState.builder().sunDirection(0,0,-1).sunIntensity(1).build();
            var lit=render(receiverCamera,sunlight);
            apply(SceneTransaction.builder(++revision).upsert(volume(2,2,absorbing(.4f,.2f,0),1)).build());
            var shadow=render(receiverCamera,sunlight);
            near(shadow[0]/lit[0],Math.exp(-1.6),.008,"ordered volume shadow red transmittance");
            near(shadow[1]/lit[1],Math.exp(-.8),.008,"ordered volume shadow green transmittance");

            var scatter=EnvironmentState.Medium.builder().absorption(.05f,.05f,.05f).scattering(.25f,.25f,.25f).density(1).build();
            apply(SceneTransaction.builder(++revision).upsert(MaterialAsset.builder(1).shadingModel(MaterialAsset.ShadingModel.UNLIT).baseColorRgba8(0xff000000).doubleSided(true).build())
                .upsert(volume(2,2,scatter,1)).upsert(SceneLight.point(1,0,1,0).intensity(30).range(20).build()).build());
            var point=render(OUTSIDE,EnvironmentState.neutral());
            MediumTransportSelfTest.require(point[0]>.01,"point light must illuminate participating medium");
            apply(SceneTransaction.builder(++revision).removeLight(1).upsert(SceneLight.spot(2,0,0,3,0,0,-1).coneDegrees(30,60).intensity(30).range(20).build()).build());
            var spot=render(OUTSIDE,EnvironmentState.neutral());
            MediumTransportSelfTest.require(spot[0]>.01,"spot light must illuminate participating medium");
            apply(SceneTransaction.builder(++revision).removeLight(2).upsert(SceneLight.directional(3,0,0,-1).intensity(2).build()).build());
            var directional=render(OUTSIDE,EnvironmentState.neutral());
            MediumTransportSelfTest.require(directional[0]>.01,"directional light must illuminate participating medium");
            apply(SceneTransaction.builder(++revision).upsert(volume(2,2,scatter.toBuilder().phaseAnisotropy(.7f).build(),1)).build());
            var anisotropic=render(OUTSIDE,EnvironmentState.neutral());
            MediumTransportSelfTest.require(Math.abs(anisotropic[0]-directional[0])>.005,"phase anisotropy must change scattering");
            apply(SceneTransaction.builder(++revision).removeLight(3).build());
            var sun=render(OUTSIDE,EnvironmentState.builder().sunDirection(0,0,-1).sunIntensity(2).build());
            MediumTransportSelfTest.require(sun[0]>.005,"environment sun must illuminate medium");

            // Inside a broad dielectric slab, sixty-degree incidence must totally reflect.
            var glass=MaterialAsset.builder(3).transmission(1).roughness(0).indexOfRefraction(1.5f).doubleSided(true).build();
            var glassBoundary=MediumBoundaryState.builder(EnvironmentState.Medium.builder().indexOfRefraction(1.5f).build()).build();
            var front=MeshAsset.triangles(3,new float[]{-100,-100,3,100,-100,3,100,100,3,-100,100,3},new int[]{0,2,1,0,3,2},1);
            apply(SceneTransaction.builder(++revision).resetScene().upsert(glass)
                .upsert(MaterialAsset.builder(1).shadingModel(MaterialAsset.ShadingModel.UNLIT).baseColorRgba8(0xff0000ff).doubleSided(true).build())
                .upsert(front).upsert(MediumTransportSelfTest.cube(2,3,1)).upsert(SceneInstance.builder(1,3).build())
                .upsert(SceneInstance.builder(2,2).transform(new AffineTransform(new float[]{100,0,0,0,0,100,0,0,0,0,1,0})).mediumBoundary(glassBoundary).build()).build());
            var tilted=CameraState.lookAt(0,0,0,Math.sqrt(3),0,1).verticalFieldOfViewDegrees(1).build();
            var tir=render(tilted,EnvironmentState.neutral());
            apply(SceneTransaction.builder(++revision).upsert(SceneInstance.builder(2,2)
                .transform(new AffineTransform(new float[]{100,0,0,0,0,100,0,0,0,0,1,0}))
                .mediumBoundary(MediumBoundaryState.builder(EnvironmentState.Medium.vacuum()).build()).build()).build());
            var noTir=render(tilted,EnvironmentState.neutral());
            MediumTransportSelfTest.require(tir[0]<.01 && noTir[0]>.9,"Snell critical angle must distinguish TIR from unit-IOR transmission");
            // Existing two-sided material-only interfaces retain their front/back IOR convention.
            var thin=MeshAsset.triangles(4,new float[]{-100,-100,1,100,-100,1,100,100,1,-100,100,1},new int[]{0,1,2,2,3,0},3);
            apply(SceneTransaction.builder(++revision).removeInstance(2).upsert(thin).upsert(SceneInstance.builder(4,4).build()).build());
            near(render(tilted,EnvironmentState.neutral())[0],0,.01,"legacy material-only back-face TIR");
            apply(SceneTransaction.builder(++revision).upsert(glass.toBuilder().indexOfRefraction(1).build()).build());
            MediumTransportSelfTest.require(render(tilted,EnvironmentState.neutral())[0]>.9,"legacy unit-IOR plane transmission");
            System.out.printf(Locale.ROOT,"MediumTransportNativeSelfTest passed: absorption=%.4f/%.4f/%.4f, nested=%.4f/%.4f, emission=%.4f, scatter point/spot/directional/sun=%.4f/%.4f/%.4f/%.4f, TIR=%.4f vs %.4f%n",
                white[0],white[1],white[2],nested[0],nested[1],emission[0],point[0],spot[0],directional[0],sun[0],tir[0],noTir[0]);
            System.out.printf(Locale.ROOT,"Transport correctness frames=%d, admission-to-readback mean=%.2f ms, max=%.2f ms (32x32 HDR, validation enabled; not a display-FPS benchmark)%n",
                sequence,frameNanoseconds/1_000_000.0/sequence,maximumFrameNanoseconds/1_000_000.0);
            if(java.util.Arrays.asList(args).contains("--profile")) profile();
        }
    }
    private static void apply(SceneTransaction transaction) {renderer.apply(transaction);}
    private static void profile() throws Exception {
        width=640;height=360;
        var plane=MeshAsset.triangles(1,new float[]{-100,-100,-4,100,-100,-4,100,100,-4,-100,100,-4},new int[]{0,1,2,2,3,0},1);
        apply(SceneTransaction.builder(++revision).resetScene().upsert(MaterialAsset.builder(1).roughness(.7f).doubleSided(true).build())
            .upsert(plane).upsert(MediumTransportSelfTest.cube(2,1,1)).upsert(SceneInstance.builder(1,1).build())
            .upsert(SceneLight.point(1,0,3,3).intensity(30).range(20).build()).build());
        var environment=EnvironmentState.builder().skyRadiance(.1f,.12f,.15f).ambientIntensity(.2f)
            .sunDirection(0,0,-1).sunIntensity(1).build();
        profileLane("opaque",environment);
        apply(SceneTransaction.builder(++revision).upsert(volume(2,2,absorbing(.4f,.2f,0),1)).build());
        profileLane("absorption",environment);
        var scattering=EnvironmentState.Medium.builder().absorption(.05f,.05f,.05f).scattering(.15f,.15f,.15f)
            .density(1).phaseAnisotropy(.2f).build();
        apply(SceneTransaction.builder(++revision).upsert(volume(2,2,scattering,1)).build());
        profileLane("single-scattering",environment);
    }
    private static void profileLane(String lane,EnvironmentState environment) throws Exception {
        for(int i=0;i<4;i++)render(OUTSIDE,environment);
        long[] times=new long[24];
        for(int i=0;i<times.length;i++) {
            var pixel=render(OUTSIDE,environment);
            for(float channel:pixel)MediumTransportSelfTest.require(Float.isFinite(channel),"profile output must remain finite");
            times[i]=lastFrameNanoseconds;
        }
        java.util.Arrays.sort(times);
        System.out.printf(Locale.ROOT,"Medium profile %s: 640x360 1 SPP, P50=%.2f ms P95=%.2f ms max=%.2f ms (serial admission/readback, validation enabled)%n",
            lane,times[12]/1e6,times[22]/1e6,times[23]/1e6);
    }
    private static float[] render(CameraState camera,EnvironmentState environment) throws Exception {
        long started=System.nanoTime();
        var frame=RenderFrameRequest.builder(++sequence,width,height,camera).minimumSceneRevision(revision).environment(environment)
            .primitiveBatch(primitives)
            .mediumTransport(new MediumTransportOptions(12,1,8,512)).build();
        long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
        while(!(renderer.trySubmit(frame) instanceof FrameSubmitted)) {
            if(System.nanoTime()>deadline) throw new AssertionError("frame admission timeout");Thread.sleep(1);
        }
        while(System.nanoTime()<deadline) {
            var ready=renderer.pollFrame();
            if(ready.isPresent()) {
                var snapshot=ready.orElseThrow();var bytes=snapshot.pixels().order(ByteOrder.LITTLE_ENDIAN);
                long elapsed=System.nanoTime()-started;frameNanoseconds+=elapsed;maximumFrameNanoseconds=Math.max(maximumFrameNanoseconds,elapsed);
                lastFrameNanoseconds=elapsed;
                int offset=(height/2)*snapshot.rowBytes()+(width/2)*8;
                return new float[]{Float.float16ToFloat(bytes.getShort(offset)),Float.float16ToFloat(bytes.getShort(offset+2)),Float.float16ToFloat(bytes.getShort(offset+4))};
            }
            Thread.sleep(1);
        }
        throw new AssertionError("frame completion timeout");
    }
    private static void near(double value,double expected,double tolerance,String message) {
        MediumTransportSelfTest.require(Math.abs(value-expected)<tolerance,message+": "+value+" vs "+expected);
    }
}

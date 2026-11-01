package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import top.ceroxe.rt.renderer.api.*;

/** Renderer-wide light families plus scene reloads; captures are excluded from FPS evidence. */
public final class RtxptLightingNativeSelfTest {
    private static final List<String> CASES=List.of("points","spots","many","area","environment","sun","mixed");
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output);
        String selected=args.length>1?args[1]:"all";
        int spp=args.length>2?Integer.parseInt(args[2]):1;
        var policy=RendererConfig.presentation().copyBuilder().denoising(DenoisingOptions.disabled())
            .frameGeneration(FrameGenerationOptions.disabled())
            .pathTracing(new PathTracingOptions(spp,20,2,RendererFeaturePreference.DISABLED,
                RendererFeaturePreference.DISABLED,RendererFeaturePreference.DISABLED)).build();
        long sequence=0,revision=0;
        try(var renderer=Renderer.open("rtxpt",policy)) {
            for(String name:CASES) {
                if(!selected.equals("all")&&!selected.equals(name))continue;
                // Translation must not change directional illumination; local lights move with the scene.
                for(int pose=0;pose<2;pose++) {
                    double offset=pose==0?0:8;
                    revision++;
                    renderer.apply(scene(name,offset,revision));
                    var host=renderer.extension(RtxptAccess.class).orElseThrow();
                    var c=camera(offset);
                    var env=environment(name);
                    for(int i=0;i<132;i++) {
                        renderer.submit(RenderFrameRequest.builder(sequence++,1920,1080,c)
                            .minimumSceneRevision(revision).environment(env).build());
                        if(i>=128)host.capture(output.resolve(name+"-pose"+pose+"-static"+i+".png"));
                    }
                    var status=host.runtimeStatus();
                    require(status.reconstruction()==RtxptAccess.Reconstruction.DLSS&&!status.denoising()
                        &&!status.sharc()&&!status.restirDi()&&!status.restirGi()&&!status.frameGeneration(),"Raw SR policy "+status);
                    Files.writeString(output.resolve(name+"-pose"+pose+"-runtime.txt"),status+"\n"+policy+"\n");
                    System.out.println("LIGHT_FAMILY_CAPTURE "+name+" pose="+pose+" spp="+spp+" "+status);
                }
            }
            require(renderer.health().activeFailure().isEmpty(),"Healthy scene transitions");
        }
        System.out.println("LIGHT_FAMILIES_AND_LIFECYCLE PASS spp="+spp);
    }
    private static SceneTransaction scene(String name,double offset,long revision) {
        boolean emitter=name.equals("area")||name.equals("mixed");
        boolean glass=name.equals("mixed");
        var tx=SceneTransaction.builder(revision).resetScene();
        tx.upsert(MaterialAsset.builder(1).baseColorRgba8(0xffb8c8df).roughness(.65f).build());
        tx.upsert(MaterialAsset.builder(2).baseColorRgba8(0xffe0bd75).metallic(1).roughness(.14f).build());
        tx.upsert(MaterialAsset.builder(3).baseColorRgba8(0xffffffff).blendMode(MaterialAsset.BlendMode.TRANSLUCENT)
            .transmission(1).roughness(.02f).indexOfRefraction(1.5f).build());
        tx.upsert(MaterialAsset.builder(4).baseColorRgba8(0xfff0f0f0).emissive(0xffffe7b0,emitter?5:0).roughness(.3f).build());
        for(int i=1;i<=4;i++)tx.upsert(cube(i,i));
        tx.upsert(SceneInstance.builder(1,1).transform(transform(offset,-.15,0,10,.2,8)).build());
        var object=SceneInstance.builder(2,glass?3:1).transform(transform(offset-1.2,.9,0,1.3,1.8,1.3));
        if(glass)object.mediumBoundary(MediumBoundary.surface(new Medium(new Rgb(.07f,.55f,.8f),1.5f)).withPriority(2));
        tx.upsert(object.build());
        tx.upsert(SceneInstance.builder(3,2).transform(transform(offset+1.3,.65,.3,1.2,1.3,1.2)).build());
        if(emitter)tx.upsert(SceneInstance.builder(4,4).transform(transform(offset,2.6,-1,1.8,.12,1)).build());
        if(glass)tx.upsert(SceneInstance.builder(5,3).transform(transform(offset-1.2,1,0,.55,.75,.55))
            .mediumBoundary(MediumBoundary.surface(new Medium(new Rgb(.7f,.08f,.04f),1.33f)).withPriority(6)).build());
        int count=name.equals("many")?12:name.equals("points")?4:name.equals("spots")||name.equals("mixed")?2:0;
        for(int i=0;i<count;i++) {
            double x=offset-2.4+(i%4)*1.6,z=-2+(i/4)*1.8;
            var light=name.equals("spots")?SceneLight.spot(100+i,x,3,z,0,-1,0).coneDegrees(18,40)
                :SceneLight.point(100+i,x,3,z);
            tx.upsert(light.color(i%3==0?1:.3f,i%3==1?1:.3f,i%3==2?1:.3f).intensity(12f/Math.max(1,count)).range(30).build());
        }
        if(name.equals("sun")||name.equals("mixed"))tx.upsert(SceneLight.directional(200,.57735026f,-.57735026f,-.57735026f).intensity(3).build());
        return tx.build();
    }
    private static CameraState camera(double offset){return CameraState.lookAt(offset+4,2.7,6,offset,.8,0)
        .verticalFieldOfViewDegrees(52).aspectRatio(16d/9).build();}
    private static EnvironmentState environment(String name) {
        boolean sky=name.equals("environment")||name.equals("mixed");
        return EnvironmentState.builder().skyRadiance(.3f,.4f,.6f).ambientIntensity(sky?.5f:0).sunIntensity(0).build();
    }
    private static AffineTransform transform(double x,double y,double z,double sx,double sy,double sz){
        return new AffineTransform(new float[]{(float)sx,0,0,(float)x,0,(float)sy,0,(float)y,0,0,(float)sz,(float)z});
    }
    private static MeshAsset cube(long id,long material) {
        float[] p={-.5f,-.5f,-.5f,.5f,-.5f,-.5f,.5f,.5f,-.5f,-.5f,.5f,-.5f,-.5f,-.5f,.5f,.5f,-.5f,.5f,.5f,.5f,.5f,-.5f,.5f,.5f};
        int[] indices={4,5,6,4,6,7,0,2,1,0,3,2,0,4,7,0,7,3,1,2,6,1,6,5,3,7,6,3,6,2,0,1,5,0,5,4};
        long[] materials=new long[12];Arrays.fill(materials,material);
        return MeshAsset.builder(id,p,indices,materials).build();
    }
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}

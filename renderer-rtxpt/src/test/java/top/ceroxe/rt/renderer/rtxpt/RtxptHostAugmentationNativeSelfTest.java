package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rtxpt.examples.GlHostSurfaceWriter;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Real ten-plane GL input -> finite augmentation -> independently captured requested GPU AOVs. */
public final class RtxptHostAugmentationNativeSelfTest {
    private static final int WIDTH=33,HEIGHT=25,X=WIDTH/2,Y=HEIGHT/2;
    private static final int OUTPUTS=HostSurfacePlan.COMBINED|HostSurfacePlan.DIRECT_MODULATION
        |HostSurfacePlan.DIFFUSE_INDIRECT|HostSurfacePlan.COVERAGE;
    private static final Set<HostSurfaceFrameLease.Signal> SIGNALS=EnumSet.of(
        HostSurfaceFrameLease.Signal.COMBINED_SURFACE,HostSurfaceFrameLease.Signal.DIRECT_MODULATION,
        HostSurfaceFrameLease.Signal.DIFFUSE_INDIRECT,HostSurfaceFrameLease.Signal.COVERAGE);
    private static final Path OUT=Path.of(System.getProperty("top.ceroxe.rt.rtxpt.testOutput",
        "build/reports/rtxpt-710-host-augmentation")).toAbsolutePath();
    private record Images(double[] combined,double[] gain,double[] indirect,double[] coverage) { }
    private static int assertions;
    private static void check(boolean value,String message){assertions++;require(value,message);}
    private static void near(double actual,double expected,double tolerance,String message){
        check(Double.isFinite(actual)&&Math.abs(actual-expected)<=tolerance,
            message+" expected="+expected+" actual="+actual+" tolerance="+tolerance);
    }
    private static HostSurfaceAugmentation parameters(HostSurfaceAugmentation.ColorDomain domain,float dimming){
        var defaults=NativeLightingStyle.defaults();
        var style=new NativeLightingStyle(defaults.additionMode(),dimming,defaults.localBoost(),defaults.daylightDimming(),
            defaults.moonlightDimming(),defaults.moonRed(),defaults.moonGreen(),defaults.moonBlue(),defaults.minimumGain(),defaults.maximumGain(),defaults.maximumGi());
        var sunOff=new NativeLightingPlan.Directional(0,0,1,0,1,1,1);
        // No sources, directions or GI samples: gain has a closed-form CPU oracle and GI must be exactly zero.
        var lighting=new NativeLightingPlan(WIDTH,HEIGHT,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,
            NativeLightingPlan.Signal.INDIRECT_DIFFUSE),0,6,.35f,style,sunOff,null,true);
        return new HostSurfaceAugmentation(lighting,domain);
    }
    private static GlHostSurfaceWriter.AugmentationInputs inputs(float host,float baseline,float visibility,float fog){
        return new GlHostSurfaceWriter.AugmentationInputs(baseline,0,visibility,fog,host,host,host,.625f);
    }
    private static RenderFrameRequest request(long sequence,long revision,FrameClock clock){
        return RenderFrameRequest.builder(sequence,WIDTH,HEIGHT,CameraState.lookAt(0,0,3,0,0,0)
            .verticalFieldOfViewDegrees(52).aspectRatio((double)WIDTH/HEIGHT).build())
            .minimumSceneRevision(revision).clock(clock).environment(EnvironmentState.builder().sunIntensity(0).build()).build();
    }
    private static double[] capture(RtxptRenderer renderer,String label,HostSurfaceFrameLease.Signal signal)throws Exception {
        Path file=OUT.resolve(label+"-"+signal.name().toLowerCase(Locale.ROOT)+".pfm");
        renderer.captureHostSurface(signal.ordinal(),file);
        double[] raw=RtxptLightTreeNativeSelfTest.linear(file);
        check(raw.length==WIDTH*HEIGHT*3,"Captured the exact augmentation extent for "+signal);
        // PFM is bottom-up, while the public input and patch coordinates are top-left.
        var image=new double[raw.length];
        for(int y=0;y<HEIGHT;y++)System.arraycopy(raw,(HEIGHT-1-y)*WIDTH*3,image,y*WIDTH*3,WIDTH*3);
        return image;
    }
    private static void pixel(double[] image,int x,int y,double expected,String message){
        int offset=(y*WIDTH+x)*3;for(int channel=0;channel<3;channel++)near(image[offset+channel],expected,.001,message+" rgb="+channel);
    }
    private static void patchInvalid(GlHostSurfaceWriter.Packet packet){
        glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT);
        // A requested primary fallback is deliberately unsupported by finite augmentation.
        glClearTexSubImage(packet.texture(HostSurfacePlan.Plane.WORLD_POSITION_FLAGS),0,X,Y,0,1,1,1,
            GL_RGBA,GL_FLOAT,new float[]{0,0,0,HostSurfacePlan.PRIMARY_FALLBACK});
        // A missing public instance and a degenerate normal must also remain neutral.
        glClearTexSubImage(packet.texture(HostSurfacePlan.Plane.IDENTITY),0,X+1,Y,0,1,1,1,
            GL_RGBA_INTEGER,GL_UNSIGNED_INT,new int[]{0,0,0,0});
        glClearTexSubImage(packet.texture(HostSurfacePlan.Plane.SHADING_NORMAL_ALPHA_CUTOFF),0,X-1,Y,0,1,1,1,
            GL_RGBA,GL_FLOAT,new float[]{0,0,0,.5f});
    }
    private static Images runFrame(RtxptRenderer renderer,GlHostSurfaceWriter writer,String label,long sequence,
            long revision,FrameClock clock,GlHostSurfaceWriter.AugmentationInputs input,boolean invalid)throws Exception {
        var frame=request(sequence,revision,clock);long resource=1000+sequence;
        var lease=renderer.acquireHostSurfaceInput(frame,resource).orElseThrow();var descriptor=lease.frame();
        check(descriptor.composition()==HostSurfacePlan.Composition.HOST_LIT_AUGMENTATION&&descriptor.planes().size()==10,"Ten-plane augmentation reservation");
        check(descriptor.clock().equals(clock)&&descriptor.sceneRevision()==revision&&descriptor.resourceGeneration()==resource,"Input frame carries current clock/revision/resource generation");
        var card=new GlHostSurfaceWriter.Card(1,3,52,(float)WIDTH/HEIGHT,1,1,.75f,.25f,.125f,.8f);
        if(invalid)writer.write(lease,packet->{writer.drawCard(packet,card,input);patchInvalid(packet);});
        else writer.writeCard(lease,card,input);
        renderer.submit(frame);renderer.awaitIdle();
        try(var output=renderer.acquireHostSurfaceFrame().orElseThrow()){
            var packet=output.frame();var actual=EnumSet.noneOf(HostSurfaceFrameLease.Signal.class);
            for(var plane:packet.planes()){
                actual.add(plane.signal());check(plane.format()==HostSurfaceFrameLease.Format.RGBA16_FLOAT,"Requested floating working-domain output");
                check(packet.reconstruction(plane.signal())==HostSurfaceFrameLease.Reconstruction.NONE,"Every augmentation signal is actually unreconstructed");
            }
            check(actual.equals(SIGNALS)&&packet.planes().size()==4,"Publish exactly mask 139, with no unrequested physical AOVs");
            check(packet.reconstruction()==HostSurfaceFrameLease.Reconstruction.NONE,"Combined output reports actual NONE");
            check(packet.colorDomain().name().equals(renderer.hostSurfaceAugmentation().colorDomain().name()),"Packet retains the configured common color domain");
            check(packet.width()==WIDTH&&packet.height()==HEIGHT,"Combined grid and host input dimensions agree");
            check(packet.sequence()==sequence&&packet.sceneRevision()==revision&&packet.clock().equals(clock),"Fresh output sequence/revision/clock, including signed seek and paused time");
            check(packet.inputGeneration()==descriptor.generation()&&packet.resourceGeneration()==resource,"Output correlates the consumed input and host resource generations");
        }
        var work=renderer.hostSurfaceWorkStatistics();
        check(work.available()&&work.sequence()==sequence&&work.sceneRevision()==revision,"Completed host counters belong to this new frame");
        check(work.suppliedPrimary()>0&&work.invalidOrBackground()>0,"Both supplied receivers and explicit neutral pixels execute");
        check(work.suppliedPrimary()+work.invalidOrBackground()==(long)WIDTH*HEIGHT,"Each host pixel is classified exactly once");
        check(work.fallbackPrimary()==0&&work.fallbackFill()==0&&work.reusedFill()==0,"Finite augmentation does no fallback-primary or full-image fill work");
        var rays=renderer.lightingWorkStatistics();
        check(rays.available()&&rays.sequence()==sequence&&rays.sceneRevision()==revision,"Actual native shader counters belong to the current augmentation frame");
        check(rays.primaryRays()==0&&rays.receivers()>0&&rays.receivers()==work.suppliedPrimary(),"Actual shader executes supplied receiver work and traces zero camera primaries");
        check(rays.bounceRays()==0&&rays.shadowRays()==0,"No GI samples or active sources means no shadow/bounce rays");
        check(renderer.nativeLightingExecution().fullBeautyFrames()==0,"Augmentation never executes a full beauty frame");
        var images=new Images(capture(renderer,label,HostSurfaceFrameLease.Signal.COMBINED_SURFACE),
            capture(renderer,label,HostSurfaceFrameLease.Signal.DIRECT_MODULATION),
            capture(renderer,label,HostSurfaceFrameLease.Signal.DIFFUSE_INDIRECT),capture(renderer,label,HostSurfaceFrameLease.Signal.COVERAGE));
        double gain=renderer.hostSurfaceAugmentation().lighting().style().directGain(0,input.localBaseline(),input.skyBaseline(),input.visibility(),input.fog(),0,0,0,1,0,1);
        if(!invalid){pixel(images.gain(),X,Y,gain,label+" CPU style oracle");pixel(images.combined(),X,Y,input.hostRed()*gain,label+" actual H multiplied once");pixel(images.coverage(),X,Y,1,label+" valid coverage");}
        for(double value:images.indirect())near(value,0,0,label+" GI samples=0 has no stale indirect contribution");
        pixel(images.combined(),0,0,input.hostRed(),label+" background preserves H");
        pixel(images.gain(),0,0,1,label+" background gain is exactly neutral");pixel(images.coverage(),0,0,0,label+" background coverage");
        if(invalid)for(int x:new int[]{X-1,X,X+1}){
            pixel(images.combined(),x,Y,input.hostRed(),label+" invalid preserves H");
            pixel(images.gain(),x,Y,1,label+" invalid gain is neutral");pixel(images.coverage(),x,Y,0,label+" invalid coverage");
        }
        check(renderer.hostSurfaceStatus().consumedInputs()==sequence+1,"Exactly one host GPU input consumed per frame");
        System.out.printf(Locale.ROOT,"RTXPT_710_HOST_AUGMENTATION_CASE PASS case=%s sequence=%d revision=%d H=%.3f B=%.3f V=%.3f F=%.3f expectedGain=%.6f domain=%s%n",
            label,sequence,revision,input.hostRed(),input.localBaseline(),input.visibility(),input.fog(),gain,renderer.hostSurfaceAugmentation().colorDomain());
        return images;
    }
    public static void main(String[] arguments)throws Exception {
        Files.createDirectories(OUT);
        try(var errors=GLFWErrorCallback.createPrint(System.err)){
            errors.set();check(glfwInit(),"Initialize hidden augmentation GL context");long window=0;
            try{
                glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,5);
                glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
                window=glfwCreateWindow(WIDTH,HEIGHT,"Hidden finite host augmentation acceptance",0,0);check(window!=0,"Create hidden GL context");
                glfwMakeContextCurrent(window);GL.createCapabilities();
                var plan=HostSurfacePlan.augmentLitBase(WIDTH,HEIGHT,HostSurfacePlan.HOST_DIRECT|HostSurfacePlan.HOST_AMBIENT|HostSurfacePlan.HOST_AO).withOutputSignals(OUTPUTS);
                var initial=parameters(HostSurfaceAugmentation.ColorDomain.SCENE_LINEAR,.75f);
                try(var renderer=(RtxptRenderer)RtxptRenderers.openHostSurface(RtxptHostSurfaceNativeSelfTest.configuration("raw"),plan,initial);
                        var writer=new GlHostSurfaceWriter()){
                    check(renderer.hostSurfaceAugmentation().equals(initial),"Three-argument public factory retains full augmentation configuration");
                    check(renderer.extension(HostSurfaceControl.class).orElseThrow().hostSurfaceAugmentation().equals(initial),"Public extension exposes the same configuration");
                    renderer.hostSurfaceWorkStatisticsEnabled(true);
                    renderer.lightingWorkStatisticsEnabled(true);
                    renderer.apply(SceneTransaction.builder(1).resetScene().upsert(MaterialAsset.builder(1).roughness(.8f).build())
                        .upsert(RtxptFramePreparationNativeSelfTest.card(1)).upsert(SceneInstance.builder(1,1).build()).build());
                    runFrame(renderer,writer,"black-host",0,1,FrameClock.of(-100,0),inputs(0,.5f,1,0),false);
                    var clear=runFrame(renderer,writer,"gray-host",1,1,FrameClock.of(16_666_667,16_666_667),inputs(.4f,.5f,1,0),false);
                    runFrame(renderer,writer,"black-baseline",2,1,FrameClock.of(-1_000_000_000,0),inputs(.4f,0,1,0),false);
                    var fog=runFrame(renderer,writer,"half-fog",3,1,FrameClock.of(2_000_000_000,16_666_667),inputs(.4f,.5f,1,.5f),false);
                    int center=(Y*WIDTH+X)*3;
                    near(fog.gain()[center]-1,(clear.gain()[center]-1)*.5,.001,"Fog attenuates only the gain correction once");
                    runFrame(renderer,writer,"half-visibility",4,1,FrameClock.of(2_000_000_000,0),inputs(.4f,.5f,.5f,0),false);
                    runFrame(renderer,writer,"invalid-and-fallback",5,1,FrameClock.of(2_000_000_001,1),inputs(.4f,.5f,1,0),true);
                    var changed=parameters(HostSurfaceAugmentation.ColorDomain.DISPLAY_LINEAR,.5f);renderer.hostSurfaceAugmentation(changed);
                    check(renderer.hostSurfaceAugmentation().equals(changed),"Public setter accepts a complete independent style/domain change");
                    renderer.apply(SceneTransaction.builder(2).upsert(MaterialAsset.builder(1).roughness(.6f).build()).build());
                    runFrame(renderer,writer,"changed-style-domain",6,2,FrameClock.of(-42,0),inputs(.4f,.5f,1,0),false);
                    check(renderer.hostSurfaceStatus().publishedInputs()==0&&renderer.hostSurfaceStatus().hostReservations()==0,"No published inputs or reservations remain after acceptance");
                    System.out.println("RTXPT_710_HOST_AUGMENTATION_ALL PASS frames=7 inputPlanes=10 outputMask="+OUTPUTS+" outputPlanes=4 domains=2 reconstruction=NONE assertions="+assertions);
                }
            }finally{GL.setCapabilities(null);if(window!=0)glfwDestroyWindow(window);glfwTerminate();glfwSetErrorCallback(null);}
        }
    }
}

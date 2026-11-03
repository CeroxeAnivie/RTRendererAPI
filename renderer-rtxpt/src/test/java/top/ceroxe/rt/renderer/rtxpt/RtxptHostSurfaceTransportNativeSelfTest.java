package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rtxpt.examples.GlHostSurfaceWriter;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Directed raw transport: real secondary reflection/transmission, alpha holes and primary ownership. */
public final class RtxptHostSurfaceTransportNativeSelfTest {
    private static final int W=33,H=25,X=W/2,Y=H/2;
    private static final Path OUT=Path.of(System.getProperty("top.ceroxe.rt.rtxpt.testOutput",
        "build/reports/rtxpt-host-transport")).toAbsolutePath();
    private static final HostSurfaceFrameLease.Signal COMBINED=HostSurfaceFrameLease.Signal.COMBINED_SURFACE,
        DIRECT=HostSurfaceFrameLease.Signal.DIFFUSE_DIRECT,SPECULAR=HostSurfaceFrameLease.Signal.SPECULAR,
        TRANSMISSION=HostSurfaceFrameLease.Signal.TRANSMISSION,EMISSION=HostSurfaceFrameLease.Signal.EMISSION,
        COVERAGE=HostSurfaceFrameLease.Signal.COVERAGE;
    private static int checks;
    private static void check(boolean condition,String message){checks++;require(condition,message);}
    private static void near(double actual,double expected,double tolerance,String message){
        check(Double.isFinite(actual)&&Math.abs(actual-expected)<=tolerance,message+" expected="+expected+" actual="+actual);
    }
    private static double channel(EnumMap<HostSurfaceFrameLease.Signal,double[]> images,HostSurfaceFrameLease.Signal signal,int x,int y,int c){return images.get(signal)[(y*W+x)*3+c];}
    private static double center(EnumMap<HostSurfaceFrameLease.Signal,double[]> images,HostSurfaceFrameLease.Signal signal){return channel(images,signal,X,Y,0);}
    private static void black(EnumMap<HostSurfaceFrameLease.Signal,double[]> images,HostSurfaceFrameLease.Signal signal,int x,int y,String why){
        for(int c=0;c<3;c++)near(channel(images,signal,x,y,c),0,.001,why+" rgb="+c);
    }
    private static MeshAsset panel(long id,long material,float z,boolean reversed){
        return MeshAsset.builder(id,new float[]{-8,-8,z,8,-8,z,8,8,z,-8,8,z},
            reversed?new int[]{0,2,1,0,3,2}:new int[]{0,1,2,0,2,3},new long[]{material,material}).build();
    }
    private static RendererConfig configuration(){
        var base=RtxptHostSurfaceNativeSelfTest.configuration("raw");var path=base.pathTracing();
        return base.copyBuilder().pathTracing(new PathTracingOptions(1,6,0,RendererFeaturePreference.DISABLED,
            RendererFeaturePreference.DISABLED,RendererFeaturePreference.DISABLED,4,path.quality())).build();
    }
    private static final class Run {
        final RtxptRenderer renderer;final GlHostSurfaceWriter writer;long sequence,revision;
        Run(RtxptRenderer renderer,GlHostSurfaceWriter writer){this.renderer=renderer;this.writer=writer;}
        void scene(MaterialAsset front,boolean panels,boolean point){
            var tx=SceneTransaction.builder(++revision).resetScene().upsert(front).upsert(RtxptFramePreparationNativeSelfTest.card(1))
                .upsert(SceneInstance.builder(1,1).build());
            if(panels)tx.upsert(MaterialAsset.builder(2).doubleSided(true).emissive(0xff0000ff,2).build())
                .upsert(MaterialAsset.builder(3).doubleSided(true).emissive(0xffff0000,2).build())
                .upsert(panel(2,2,4,true)).upsert(panel(3,3,-2,false))
                .upsert(SceneInstance.builder(2,2).build()).upsert(SceneInstance.builder(3,3).build());
            if(point)tx.upsert(SceneLight.point(1,0,0,2).intensity(8).range(10).castsShadow(false).build());
            renderer.apply(tx.build());
        }
        EnumMap<HostSurfaceFrameLease.Signal,double[]> frame(String label,int preserved,float roughness,float metal,float ior,
                float transmission,float emission,float albedo,EnvironmentState environment,boolean alpha)throws Exception {
            renderer.hostSurfacePlan(new HostSurfacePlan(W,H,HostSurfacePlan.Composition.SPLIT_SURFACE_LIGHTING,preserved,false,
                HostSurfacePlan.DEFAULT_MAXIMUM_RESIDENT_BYTES,HostSurfacePlan.ALL_SIGNALS));
            long current=sequence++;var clock=FrameClock.of(-100+current*16_666_667,16_666_667);
            var request=RenderFrameRequest.builder(current,W,H,CameraState.lookAt(0,0,3,0,0,0)
                .verticalFieldOfViewDegrees(52).aspectRatio((double)W/H).build()).minimumSceneRevision(revision)
                .clock(clock).environment(environment).build();
            var input=renderer.acquireHostSurfaceInput(request,2000+current).orElseThrow();var descriptor=input.frame();
            writer.write(input,packet->{
                writer.drawCard(packet,new GlHostSurfaceWriter.Card(1,3,52,(float)W/H,1,1,albedo,albedo,albedo,roughness));
                glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT);
                glClearTexImage(packet.texture(HostSurfacePlan.Plane.MATERIAL),0,GL_RGBA,GL_FLOAT,new float[]{roughness,metal,ior,transmission});
                glClearTexImage(packet.texture(HostSurfacePlan.Plane.EMISSION),0,GL_RGBA,GL_FLOAT,new float[]{emission,emission,emission,0});
                if(alpha){
                    // A valid cutout survivor, an explicit raster hole, and a defensively rejected below-cutoff sample.
                    glClearTexSubImage(packet.texture(HostSurfacePlan.Plane.WORLD_POSITION_FLAGS),0,X,Y,0,1,1,1,GL_RGBA,GL_FLOAT,new float[]{0,0,0,HostSurfacePlan.VALID});
                    glClearTexSubImage(packet.texture(HostSurfacePlan.Plane.WORLD_POSITION_FLAGS),0,X-1,Y,0,1,1,1,GL_RGBA,GL_FLOAT,new float[]{0,0,0,HostSurfacePlan.BACKGROUND});
                    glClearTexSubImage(packet.texture(HostSurfacePlan.Plane.WORLD_POSITION_FLAGS),0,X+1,Y,0,1,1,1,GL_RGBA,GL_FLOAT,new float[]{0,0,0,HostSurfacePlan.VALID});
                    glClearTexSubImage(packet.texture(HostSurfacePlan.Plane.BASE_COLOR_OPACITY),0,X+1,Y,0,1,1,1,GL_RGBA,GL_FLOAT,new float[]{albedo,albedo,albedo,.25f});
                }
            });
            renderer.submit(request);renderer.awaitIdle();
            try(var lease=renderer.acquireHostSurfaceFrame().orElseThrow()){
                var packet=lease.frame();var signals=EnumSet.noneOf(HostSurfaceFrameLease.Signal.class);
                for(var plane:packet.planes()){signals.add(plane.signal());check(packet.reconstruction(plane.signal())==HostSurfaceFrameLease.Reconstruction.NONE,"Raw AOV reports NONE");}
                check(signals.equals(EnumSet.allOf(HostSurfaceFrameLease.Signal.class))&&packet.planes().size()==8,"ALL_SIGNALS publishes every AOV exactly once");
                check(packet.sequence()==current&&packet.sceneRevision()==revision&&packet.clock().equals(clock),"Raw transport packet retains fresh frame/revision/clock");
                check(packet.inputGeneration()==descriptor.generation()&&packet.resourceGeneration()==2000+current,"Raw packet retains consumed input identity");
                check(packet.width()==W&&packet.height()==H&&packet.colorDomain()==HostSurfaceFrameLease.ColorDomain.SCENE_LINEAR,"Raw AOV extent and physical working domain");
            }
            var images=new EnumMap<HostSurfaceFrameLease.Signal,double[]>(HostSurfaceFrameLease.Signal.class);
            for(var signal:HostSurfaceFrameLease.Signal.values()){
                var path=OUT.resolve(label+"-"+signal.name().toLowerCase(Locale.ROOT)+".pfm");renderer.captureHostSurface(signal.ordinal(),path);
                double[] raw=RtxptLightTreeNativeSelfTest.linear(path);check(raw.length==W*H*3,"Full-sized requested AOV capture");var top=new double[raw.length];
                for(int y=0;y<H;y++)System.arraycopy(raw,(H-1-y)*W*3,top,y*W*3,W*3);images.put(signal,top);
                for(double value:top)check(Double.isFinite(value)&&value>=-.001,"Raw transport values are finite and nonnegative");
            }
            for(int c=0;c<3;c++){
                double sum=0;for(int signal=2;signal<=6;signal++)sum+=channel(images,HostSurfaceFrameLease.Signal.values()[signal],X,Y,c);
                near(channel(images,COMBINED,X,Y,c),sum,.03,"Five physical BSDF AOVs sum to raw combined for "+label);
            }
            var work=renderer.hostSurfaceWorkStatistics();
            check(work.available()&&work.sequence()==current&&work.sceneRevision()==revision,"Raw host counters identify this exact frame");
            check(work.suppliedPrimary()>0&&work.fallbackPrimary()==0&&work.fallbackFill()==0,"Valid host materials reuse primary without fallback");
            near(center(images,COVERAGE),1,.001,"Valid center coverage");black(images,COVERAGE,0,0,"Outside-card background coverage");
            System.out.println("RTXPT_710_HOST_TRANSPORT_CASE PASS case="+label+" sequence="+current+" revision="+revision+" preserved="+preserved+" allSignals=8");
            return images;
        }
        void optics()throws Exception {
            scene(MaterialAsset.builder(1).metallic(1).roughness(0).build(),true,false);
            var mirror=frame("mirror-secondary-emitter",HostSurfacePlan.HOST_AMBIENT,0,1,1.5f,0,0,.75f,EnvironmentState.neutral(),false);
            check(center(mirror,SPECULAR)>.05&&center(mirror,SPECULAR)>channel(mirror,SPECULAR,X,Y,2)*4,"Actual secondary reflection sees the red panel behind the camera");
            black(mirror,EMISSION,X,Y,"A reflected emitter is specular transport, not primary self emission");
            scene(MaterialAsset.builder(1).transmission(1).indexOfRefraction(1).roughness(0).build(),true,false);
            var glass=frame("transmitted-secondary-emitter",HostSurfacePlan.HOST_EMISSION,0,0,1,1,0,1,EnvironmentState.neutral(),false);
            check(channel(glass,TRANSMISSION,X,Y,2)>.05&&channel(glass,TRANSMISSION,X,Y,2)>center(glass,TRANSMISSION)*4,"Actual BSDF transmission sees the blue panel behind the surface");
            black(glass,EMISSION,X,Y,"HOST_EMISSION suppresses only self emission; secondary transmitted emission remains transport");
        }
        void alpha()throws Exception {
            scene(MaterialAsset.builder(1).blendMode(MaterialAsset.BlendMode.MASKED).alphaCutoff(.5f).roughness(1).build(),false,true);
            var cutout=frame("cutout-and-rejected-alpha",0,1,0,1,0,0,.75f,EnvironmentState.neutral(),true);
            check(center(cutout,DIRECT)>.01,"Accepted alpha-cutout survivor receives actual direct light");
            for(int x:new int[]{X-1,X+1}){
                black(cutout,COMBINED,x,Y,"Cutout hole has no surface radiance");black(cutout,COVERAGE,x,Y,"Cutout hole has zero coverage");
                for(int signal=2;signal<=6;signal++)black(cutout,HostSurfaceFrameLease.Signal.values()[signal],x,Y,"Cutout hole has no leaked neighboring AOV");
            }
        }
        void ownership()throws Exception {
            scene(MaterialAsset.builder(1).baseColorRgba8(0xff000000).indexOfRefraction(1).emissive(0xffffffff,.25f).build(),false,false);
            var self=frame("self-emission",0,1,0,1,0,.25f,0,EnvironmentState.neutral(),false);
            near(center(self,EMISSION),.25,.001,"Primary emission is applied once");near(center(self,COMBINED),.25,.001,"Emission-only combined reference");
            var ownedEmission=frame("host-owned-emission",HostSurfacePlan.HOST_EMISSION,1,0,1,0,.25f,0,EnvironmentState.neutral(),false);
            black(ownedEmission,EMISSION,X,Y,"Host-owned primary self emission is omitted");black(ownedEmission,COMBINED,X,Y,"Host-owned self emission is not added twice");
            scene(MaterialAsset.builder(1).roughness(1).indexOfRefraction(1).build(),false,true);
            var direct=frame("primary-direct",0,1,0,1,0,0,.75f,EnvironmentState.neutral(),false);
            check(center(direct,DIRECT)>.01,"Primary direct ownership has a positive analytic-light reference");
            var ownedDirect=frame("host-owned-direct",HostSurfacePlan.HOST_DIRECT,1,0,1,0,0,.75f,EnvironmentState.neutral(),false);
            black(ownedDirect,DIRECT,X,Y,"Host-owned primary direct is omitted");black(ownedDirect,COMBINED,X,Y,"With no secondary light, owned direct leaves zero new radiance");
            scene(MaterialAsset.builder(1).roughness(1).indexOfRefraction(1).build(),false,false);
            var sky=EnvironmentState.builder().skyRadiance(.3f,.3f,.3f).ambientIntensity(1).sunIntensity(0).build();
            var ambient=frame("primary-environment",0,1,0,1,0,0,.75f,sky,false);
            check(center(ambient,COMBINED)>.005,"Primary environment ownership has a positive environment reference");
            var ownedAmbient=frame("host-owned-environment",HostSurfacePlan.HOST_AMBIENT,1,0,1,0,0,.75f,sky,false);
            black(ownedAmbient,COMBINED,X,Y,"Host-owned primary environment is omitted when no second diffuse bounce is requested");
        }
    }
    public static void main(String[] args)throws Exception {
        Files.createDirectories(OUT);var cases=args.length==0?List.of("optics","alpha","ownership"):List.of(args);
        try(var errors=GLFWErrorCallback.createPrint(System.err)){
            errors.set();check(glfwInit(),"Initialize hidden transport GL context");long window=0;
            try{
                glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,5);
                glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);window=glfwCreateWindow(W,H,"Hidden host transport acceptance",0,0);check(window!=0,"Create hidden GL context");
                glfwMakeContextCurrent(window);GL.createCapabilities();
                try(var renderer=(RtxptRenderer)RtxptRenderers.openHostSurface(configuration(),new HostSurfacePlan(W,H).withOutputSignals(HostSurfacePlan.ALL_SIGNALS));var writer=new GlHostSurfaceWriter()){
                    renderer.hostSurfaceWorkStatisticsEnabled(true);var run=new Run(renderer,writer);
                    for(String value:cases)switch(value){case "optics"->run.optics();case "alpha"->run.alpha();case "ownership"->run.ownership();default->throw new IllegalArgumentException("Unknown host transport case: "+value);}
                    check(renderer.hostSurfaceStatus().hostReservations()==0&&renderer.hostSurfaceStatus().publishedInputs()==0,"All directed host inputs are released");
                    System.out.println("RTXPT_710_HOST_TRANSPORT_ALL PASS cases="+cases+" frames="+run.sequence+" allSignals=8 assertions="+checks);
                }
            }finally{GL.setCapabilities(null);if(window!=0)glfwDestroyWindow(window);glfwTerminate();glfwSetErrorCallback(null);}
        }
    }
}

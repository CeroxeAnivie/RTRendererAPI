package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.*;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rtxpt.examples.GlHostSurfaceWriter;
import static org.lwjgl.glfw.GLFW.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Real GL GPU writes -> D3D12 primary reuse -> existing transport. Diagnostic readbacks are explicit. */
public final class RtxptHostSurfaceNativeSelfTest {
    static final Path OUT=Path.of("build/reports/rtxpt-host-surface").toAbsolutePath();
    private static RenderFrameRequest frame(long sequence,int width,int height){return RtxptFramePreparationNativeSelfTest.frame(sequence,1,width,height).toBuilder()
        .environment(EnvironmentState.builder().sunIntensity(0).build()).clock(FrameClock.of(sequence*16_666_667,16_666_667)).build();}
    static RendererConfig configuration(String route){
        var base=route.equals("rr")?RtxptLightingNativeSelfTest.configuration("rr"):RtxptLightTreeNativeSelfTest.config(4);
        var q=base.pathTracing().quality();q=q.withLighting(q.lighting().withInput(PathTracingQualityOptions.LightingInput.SCENE_ANALYTIC)).withPostProcessing(q.postProcessing().withBloom(false));
        return base.copyBuilder().pathTracing(base.pathTracing().withQuality(q)).lowLatency(LowLatencyOptions.disabled()).frameGeneration(FrameGenerationOptions.disabled()).build();
    }
    public static void main(String[] args)throws Exception {
        Files.createDirectories(OUT);String route=args.length==0?"raw":args[0];
        try(var errors=GLFWErrorCallback.createPrint(System.err)){
            errors.set();require(glfwInit(),"Initialize hidden host-input GL");long window=0;
            try{
                glfwDefaultWindowHints();glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,5);
                glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);window=glfwCreateWindow(96,64,"Hidden host surface acceptance",0,0);
                require(window!=0,"Create GL input context");glfwMakeContextCurrent(window);GL.createCapabilities();
                try(var renderer=(RtxptRenderer)RtxptRenderers.openHostSurface(configuration(route),new HostSurfacePlan(96,64).withOutputSignals(HostSurfacePlan.ALL_SIGNALS));var writer=new GlHostSurfaceWriter()){
                    renderer.hostSurfaceWorkStatisticsEnabled(true);
                    renderer.apply(SceneTransaction.builder(1).resetScene().upsert(MaterialAsset.builder(1).roughness(.8f).build())
                        .upsert(RtxptFramePreparationNativeSelfTest.card(1)).upsert(SceneInstance.builder(1,1).build())
                        .upsert(SceneLight.point(1,0,0,2).intensity(8).range(10).castsShadow(false).build()).build());
                    var request=frame(0,96,64);var lease=renderer.acquireHostSurfaceInput(request,7).orElseThrow();
                    require(renderer.diagnostics().latestCompletedFrameSequence()<0,"Preparing input performs no render or fake frame completion");
                    require(lease.frame().resourceGeneration()==7&&lease.frame().sceneRevision()==1&&lease.frame().planes().size()==8,"Input packet identity and all planes");
                    writer.writeCard(lease,new GlHostSurfaceWriter.Card(1,3,52,1.5f,1,1,1,.25f,.125f,.8f));
                    boolean rejected=false;try{renderer.submit(request.toBuilder().build());}catch(IllegalArgumentException expected){rejected=true;}
                    require(rejected,"A different request cannot consume a reserved camera/clock input");
                    renderer.submit(request);renderer.awaitIdle();
                    try(var output=renderer.acquireHostSurfaceFrame().orElseThrow()){
                        require(output.frame().planes().size()==8,"All requested AOVs are published in one atomic packet");
                        require(output.frame().reconstruction(HostSurfaceFrameLease.Signal.DIFFUSE_DIRECT)==HostSurfaceFrameLease.Reconstruction.NONE,"Separate transport estimates do not claim combined RR filtering");
                        if(route.equals("rr"))require(output.frame().reconstruction()==HostSurfaceFrameLease.Reconstruction.RR,"HDR packet reports executed RR");
                        require(output.frame().sequence()==0&&output.frame().resourceGeneration()==7&&output.frame().inputGeneration()==lease.frame().generation(),"Independent HDR packet matches consumed input facts");
                    }
                    var actual=renderer.runtimeStatus();if(route.equals("rr"))require(actual.reconstruction()==RtxptAccess.Reconstruction.DLSS_RR,"Actual RR execution");
                    var work=renderer.hostSurfaceWorkStatistics();
                    require(work.available()&&work.sequence()==0&&work.sceneRevision()==1,"Asynchronous counters carry actual input frame identity");
                    require(work.suppliedPrimary()>0&&work.fallbackPrimary()==0&&work.fallbackFill()==0,"Supplied opaque surfaces bypass primary traversal in build and fill");
                    require(work.suppliedPrimary()+work.invalidOrBackground()==(long)actual.renderWidth()*actual.renderHeight(),"Every build primary is supplied or explicitly background");
                    require(renderer.hostSurfaceStatus().consumedInputs()==1,"Exactly one GPU input read");
                    renderer.captureLinear(OUT.resolve("host-color.pfm"));
                    var image=RtxptLightTreeNativeSelfTest.linear(OUT.resolve("host-color.pfm"));int center=((actual.renderHeight()/2)*actual.renderWidth()+actual.renderWidth()/2)*3;
                    require(image[center]>.01&&image[center]>image[center+1]*2,"Actual transport consumes host sampled base color");
                    double[] sum=new double[3];
                    for(int signal=2;signal<=6;signal++){
                        var path=OUT.resolve("host-aov-"+signal+".pfm");renderer.captureHostSurface(signal,path);
                        var aov=RtxptLightTreeNativeSelfTest.linear(path);int sample=((64/2)*96+96/2)*3;
                        for(int c=0;c<3;c++){require(Double.isFinite(aov[sample+c])&&aov[sample+c]>=0,"Finite nonnegative physical AOV");sum[c]+=aov[sample+c];}
                    }
                    for(int c=0;c<3;c++)require(Math.abs(sum[c]-image[center+c])<.03,"Actual BSDF contributions sum to the unfiltered transport reference");
                    System.out.println("RTXPT_HOST_AOV_SUM PASS route="+route+" rgb="+java.util.Arrays.toString(sum));
                    System.out.println("RTXPT_HOST_PRIMARY_REUSE PASS route="+route+" "+work+" center="+image[center]+","+image[center+1]+","+image[center+2]);
                    // Return an unimported reservation, then resize with a new generation.
                    try(var cancelled=renderer.acquireHostSurfaceInput(frame(1,96,64),8).orElseThrow()){}
                    renderer.hostSurfacePlan(new HostSurfacePlan(97,65));
                    var resized=frame(2,97,65);writer.writeCard(renderer.acquireHostSurfaceInput(resized,9).orElseThrow(),GlHostSurfaceWriter.Card.unit(1,97f/65));
                    renderer.submit(resized);renderer.awaitIdle();
                    try(var output=renderer.acquireHostSurfaceFrame().orElseThrow()){require(output.frame().width()==97&&output.frame().height()==65,"Odd host HDR output extent");}
                    require(renderer.hostSurfaceStatus().consumedInputs()==2&&renderer.hostSurfaceStatus().abandonedInputs()==1,"Cancellation and odd resize produce fresh input reads");
                    System.out.println("RTXPT_HOST_INPUT_ALL PASS frames=2 planes=8 resize=96x64->97x65");
                }
            }finally{GL.setCapabilities(null);if(window!=0)glfwDestroyWindow(window);glfwTerminate();}
        }
    }
}

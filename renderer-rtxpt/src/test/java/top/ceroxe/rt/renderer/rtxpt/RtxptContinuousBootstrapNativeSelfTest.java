package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** First-submit continuity is configuration eligibility, not an existing light publication. */
public final class RtxptContinuousBootstrapNativeSelfTest {
    private static RendererConfig configuration(){
        return RtxptRenderers.nativeLightingConfiguration().copyBuilder().maxFramesInFlight(2)
            .resources(RendererResourceOptions.medium().withAssetBytes(1024L<<20,256L<<20,1280L<<20))
            .cpuFrameReadbackEnabled(false).gpuTimingsEnabled(true).build();
    }
    private static RenderFrameRequest frame(long sequence,long revision){
        return RtxptNativeLightingNativeSelfTest.frame(sequence,revision);
    }
    private static void submit(RtxptRenderer r,long sequence,long revision)throws Exception{
        var request=frame(sequence,revision);long deadline=System.nanoTime()+20_000_000_000L;
        while(r.trySubmit(request) instanceof Renderer.FrameSubmissionDeferred){
            require(System.nanoTime()<deadline,"Fresh continuous submission completes admission");Thread.sleep(1);
        }
        try(var lease=r.acquireLightingFrame().orElseThrow()){
            var packet=lease.frame();
            require(packet.sequence()==sequence&&packet.sceneRevision()==revision,"First/reset output has current identity");
            require(packet.encoding()==NativeLightingPlan.Encoding.COMPACT_RESPONSE_UNORM8
                &&packet.planes().size()==1&&packet.planes().getFirst().format()==LightingFrameLease.Format.RGBA8_UNORM,"MC-equivalent direct-only compact GPU output");
        }
        r.awaitIdle();
    }
    private static long handle(RtxptRenderer r)throws Exception{
        var field=RtxptRenderer.class.getDeclaredField("handle");field.setAccessible(true);return field.getLong(r);
    }
    private static void reject(long h,String label){
        try{RtxptNative.lightingPolicy(h,1);throw new AssertionError(label+" accepted CONTINUOUS");}
        catch(IllegalStateException expected){require(expected.getMessage().contains("Continuous lighting requires"),label+" keeps native policy guard");}
    }
    public static void main(String[] args)throws Exception{
        var output=Path.of(System.getProperty("top.ceroxe.rt.rtxpt.testOutput","build/reports/continuous-bootstrap"));Files.createDirectories(output);
        var config=configuration();var core=RtxptNativeLightingNativeSelfTest.scene();
        var style=new NativeLightingStyle(NativeLightingStyle.AdditionMode.HOST_RESPONSE_RATIO_V1,0,0,0,0,1,1,1,0,255,1);
        var plan=new NativeLightingPlan(48,32,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN),0,6,0,style,null,null)
            .withEncoding(NativeLightingPlan.Encoding.COMPACT_RESPONSE_UNORM8);
        try(var r=(RtxptRenderer)RtxptRenderers.openNativeLighting(config,plan)){
            r.diagnosticLevel(RtxptDiagnostics.Level.DEEP);
            require(r.supportsContinuousLighting(),"MC-equivalent policy advertises continuity before native initialization");
            r.lightingContinuity(SceneLightingControl.Continuity.CONTINUOUS);
            float[] response=new float[NativeLightingHostResponse.SAMPLES];Arrays.fill(response,1);
            r.publishLightingAttributes(new NativeLightingPublication(1,List.of(),List.of(),null,null,new NativeLightingHostResponse(response)));
            r.apply(core);submit(r,0,1);
            System.out.println("CONTINUOUS_FIRST_FRAME_STATE "+r.pathQualityStatus()+" "+r.runtimeStatus());
            r.apply(SceneTransaction.builder(2).resetScene().build());submit(r,1,2);
            var repopulate=SceneTransaction.builder(3).resetScene();
            core.upserts().materials().forEach(repopulate::upsert);core.upserts().meshes().forEach(repopulate::upsert);core.upserts().instances().forEach(repopulate::upsert);
            r.apply(repopulate.build());submit(r,2,3);
            r.apply(SceneTransaction.builder(4).resetScene().build());submit(r,3,4);
            var policy=RtxptRenderer.class.getDeclaredField("continuity");policy.setAccessible(true);
            require(policy.get(r)==SceneLightingControl.Continuity.CONTINUOUS,"No reset-policy fallback during initialization or resets");
            System.out.println("CONTINUOUS_BOOTSTRAP_GPU PASS firstSubmit=true emptyReset=true populatedReset=true repeatedEmptyReset=true exactCurrentLease=true compactDirect=true unchangedContinuous=true");
        }
        var oracle=new RtxptScene();oracle.retain(core);
        var path=new RtxptSceneExport(oracle).export(output.resolve("file-oracle"),frame(0,1),oracle.items(frame(0,1)));
        var encoded=Files.readString(path);int marker=encoded.indexOf(",\"rtrSceneLighting\":");
        require(marker>0,"Test exporter writes managed metadata as final member");
        var ordinary=path.resolveSibling("ordinary.scene.json");Files.writeString(ordinary,encoded.substring(0,marker)+"}");
        try(var r=new RtxptRenderer(config,false)){
            r.apply(SceneTransaction.builder(1).resetScene().build());r.submit(frame(0,1));long h=handle(r);
            r.loadNativeScene(path);RtxptNative.lightingPolicy(h,1);
            r.loadNativeScene(ordinary);reject(h,"Unmanaged native file");RtxptNative.lightingPolicy(h,0);
            RtxptNative.loadScene(h,null);RtxptNative.lightingPolicy(h,1);
            var options=RtxptOptions.resolve(RtxptOptions.profile(config));var backend=options.qualityInts();backend[0]=0;
            RtxptNative.pathQuality(h,backend,options.qualityFloats());reject(h,"Backend lighting input");
            RtxptNative.pathQuality(h,options.qualityInts(),options.qualityFloats());RtxptNative.lightingPolicy(h,1);
            System.out.println("CONTINUOUS_NATIVE_FILE_GUARDS PASS managedFile=true ordinaryFileRejected=true memoryReloadEmpty=true backendInputRejected=true restoredAnalytic=true");
        }
        System.out.println("RTXPT_CONTINUOUS_BOOTSTRAP PASS configuration=MC-equivalent DEEP=true cases=9 noPolicyFallback=true");
    }
}

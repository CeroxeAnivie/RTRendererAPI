package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenterConfig;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Exercises canonical materials, CPU batches, real output extent and recording lifecycle. */
public final class RtxptFramePreparationNativeSelfTest {
    static final Path OUT=Path.of(System.getProperty("top.ceroxe.rt.rtxpt.testOutput","build/reports/rtxpt-frame-preparation")).toAbsolutePath();
    static MeshAsset card(long material){return MeshAsset.builder(1,new float[]{-1,-1,0,1,-1,0,1,1,0,-1,1,0},new int[]{0,1,2,0,2,3},new long[]{material,material}).textureCoordinates(new float[]{0,1,1,1,1,0,0,0}).build();}
    static RenderFrameRequest frame(long sequence,long revision,int width,int height){return RenderFrameRequest.builder(sequence,width,height,CameraState.lookAt(0,0,3,0,0,0).verticalFieldOfViewDegrees(52).aspectRatio((double)width/height).build()).minimumSceneRevision(revision).environment(RtxptLightingNativeSelfTest.environment()).build();}
    static void submit(RtxptComposedRenderer r,RenderFrameRequest frame){
        long deadline=System.nanoTime()+10_000_000_000L;
        while(r.trySubmit(frame) instanceof Renderer.FrameSubmissionDeferred){
            require(System.nanoTime()<deadline,"Shared frame makes progress");r.awaitCompositionConsumers();
        }
        r.awaitCompositionConsumers();
    }
    public static void main(String[] args)throws Exception {
        Files.createDirectories(OUT);
        if("1".equals(System.getenv("RTR_RTXPT_REQUIRE_PRECOMPILED"))){
            require(RtxptRuntimeBundle.packaged(),"Strict release test uses the real JAR");
            var root=RtxptRuntimeBundle.extract();
            Path binary;
            try(var files=Files.list(root.resolve("ShaderDynamic/Bin/dxil"))){binary=files.filter(p->p.toString().endsWith(".bin")).findFirst().orElseThrow();}
            byte[] original=Files.readAllBytes(binary);Files.write(binary,new byte[]{0,1,2});
            RtxptRuntimeBundle.extract();
            require(Arrays.equals(original,Files.readAllBytes(binary)),"Damaged cache shader must be repaired from the JAR before native loading");
            System.out.println("RTXPT_705_CACHE_REPAIR PASS");
        }
        for(String route:args.length==0?new String[]{"rr","nrd","analytic"}:args){
            var config=RtxptLightingNativeSelfTest.configuration(route);
            var quality=config.pathTracing().quality();
            quality=quality.withLighting(quality.lighting().withInput(PathTracingQualityOptions.LightingInput.SCENE_ANALYTIC));
            config=config.copyBuilder().pathTracing(config.pathTracing().withQuality(quality)).lowLatency(LowLatencyOptions.disabled()).build();
            try(var r=RtxptComposedRenderer.openVulkanPresentation(RendererBackendPolicy.RTXPT_ONLY,
                    RendererConfig.builder().cpuFrameReadbackEnabled(false).build(),config,
                    VulkanFramePresenterConfig.builder().visible(false).initialExtent(640,360).build())){
                long revision=1,sequence=0,material=1;
                var tx=SceneTransaction.builder(revision).resetScene().upsert(MaterialAsset.builder(material).baseColorRgba8(0xff4040ff).roughness(.6f).build()).upsert(card(material));
                for(int i=0;i<256;i++)tx.upsert(SceneInstance.builder(i+1,1).transform(new AffineTransform(new float[]{.035f,0,0,(i%16-8)*.08f,0,.035f,0,(i/16-8)*.08f,0,0,1,0})).build());
                r.apply(tx.build());submit(r,frame(sequence++,revision,640,360));
                var h=r.extension(RtxptDiagnosticAccess.class).orElseThrow();h.diagnosticLevel(RtxptDiagnostics.Level.DEEP);
                // Force one fresh generation while deep recording is active.
                r.apply(SceneTransaction.builder(++revision).upsert(card(material)).build());
                for(int i=0;i<8;i++)submit(r,frame(sequence++,revision,640,360));
                long pipelines=h.submitStages().pipelineBuilds();
                for(int i=0;i<4;i++){
                    long next=1000+i;
                    var mode=i%2==0?MaterialAsset.BlendMode.MASKED:MaterialAsset.BlendMode.TRANSLUCENT;
                    byte[] pixels={(byte)255,(byte)255,(byte)255,0,(byte)255,(byte)255,(byte)255,(byte)255};
                    r.apply(SceneTransaction.builder(++revision).upsert(TextureAsset.color(7,2,1,pixels))
                        .upsert(MaterialAsset.builder(next).baseColorRgba8(i%2==0?0x40ff40ff:0x4040ffff).baseColorTextureId(7).blendMode(mode).transmission(mode==MaterialAsset.BlendMode.TRANSLUCENT?.8f:0).roughness(.25f).build())
                        .upsert(card(next)).removeMaterial(material).build());material=next;
                    long begin=System.nanoTime();submit(r,frame(sequence++,revision,640,360));
                    double millis=(System.nanoTime()-begin)/1e6;
                    require(h.submitStages().pipelineBuilds()==pipelines,"Material identity/features must not create a new PSO");
                    require(millis<1000,"Warm material delta must not stall for a second: "+millis);
                    long materialSequence=sequence-1;
                    require(h.diagnosticSnapshot().events().stream().noneMatch(e->e.sequence()==materialSequence&&e.stage()==RtxptDiagnostics.Stage.SHADER_COMPILE),"Material delta compiled a shader");
                    System.out.printf(Locale.ROOT,"RTXPT_705_MATERIAL route=%s edit=%d submitMs=%.6f extraPsos=0%n",route,i,millis);
                }
                long steadyBegin=sequence;
                for(int i=0;i<16;i++)submit(r,frame(sequence++,revision,640,360));
                require(h.diagnosticSnapshot().events().stream().noneMatch(e->e.sequence()>=steadyBegin&&e.stage()==RtxptDiagnostics.Stage.DISPATCH),"Unchanged scene must reuse its derived cache");
                for(int[] size:new int[][]{{960,540},{640,360},{2560,1600},{641,361},{3001,1703},{960,540}}){
                    submit(r,frame(sequence++,revision,size[0],size[1]));
                    var actual=h.presentationStatus();require(actual.outputWidth()==size[0]&&actual.outputHeight()==size[1],"Requested extent "+Arrays.toString(size)+" actual="+actual);
                    require(r.diagnostics().latestCompletedFrameSequence()>=0,"Real completed frame after resize");
                    System.out.println("RTXPT_705_SIZE route="+route+" request="+size[0]+"x"+size[1]+" actual="+actual.outputWidth()+"x"+actual.outputHeight());
                }
                r.captureRtxptSource(OUT.resolve(route+"-materials.png"));
                var snapshot=h.diagnosticSnapshot();
                require(snapshot.events().stream().anyMatch(e->e.stage()==RtxptDiagnostics.Stage.INSTANCE_CACHE),"Instance-cache scope");
                require(snapshot.events().stream().anyMatch(e->e.stage()==RtxptDiagnostics.Stage.SUB_INSTANCES),"Sub-instance scope");
                require(snapshot.events().stream().anyMatch(e->e.stage()==RtxptDiagnostics.Stage.WORKER),"Worker scope");
                require(snapshot.events().stream().allMatch(e->e.selfNanos()<=e.inclusiveNanos()),"Valid self/inclusive relation");
                require(!h.diagnosticGpuSnapshot().scopes().isEmpty(),"Actual command-list GPU scopes");
                Files.writeString(OUT.resolve(route+"-cpu.txt"),snapshot.toString());Files.writeString(OUT.resolve(route+"-gpu.txt"),h.diagnosticGpuSnapshot().toString());
                h.diagnosticLevel(RtxptDiagnostics.Level.OFF);int count=h.diagnosticSnapshot().events().size();
                for(int i=0;i<16;i++)submit(r,frame(sequence++,revision,960,540));
                require(h.diagnosticSnapshot().events().size()==count,"OFF stops CPU recording");
                require(!h.diagnosticGpuSnapshot().enabled(),"OFF stops new GPU queries");
                System.out.println("RTXPT_FRAME_PREPARATION PASS route="+route+" pipelineBuilds="+pipelines+" "+snapshot.frames());
            }
        }
    }
}

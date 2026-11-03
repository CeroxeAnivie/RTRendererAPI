package top.ceroxe.rt.renderer.rtxpt;
import top.ceroxe.rt.renderer.api.*;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Explicit hardware gate: one JVM, one resident upstream D3D12 renderer. */
public final class RtxptNativeSelfTest {
    public static void main(String[] args) throws Exception {
        var output=Path.of(System.getProperty("top.ceroxe.rt.rtxpt.testOutput","build/reports/rtxpt-native")).toAbsolutePath();
        Files.createDirectories(output);
        // Keep the exact adapter assets for a standalone upstream A/B run.
        // This happens outside the frame loop and never changes the rendered scene.
        var controlScene=new RtxptScene();controlScene.retain(RtxptAdapterSelfTest.scene(1));
        var controlFrame=RtxptAdapterSelfTest.frame(0);
        var controlFile=controlScene.export(output.resolve("native-control"),controlFrame,controlScene.items(controlFrame));
        System.out.println("RTXPT standalone control scene: "+controlFile);
        if(java.util.Arrays.asList(args).contains("--export-only"))return;
        try(Renderer r=Renderer.open("rtxpt",RendererConfig.pathTracedPresentation())) {
            var nativeHost=r.extension(RtxptAccess.class).orElseThrow();
            var control=r.extension(RendererFeatureController.class).orElseThrow();
            r.apply(RtxptAdapterSelfTest.scene(1));var initialFrame=RtxptAdapterSelfTest.frame(0);r.submit(initialFrame);
            RtxptAdapterSelfTest.require(nativeHost.windowHandle()!=0,"Project-controlled native window");
            for(int i=0;i<12;i++)RtxptAdapterSelfTest.require(nativeHost.renderFrame(),"Native render/present");
            var status=nativeHost.runtimeStatus();
            RtxptAdapterSelfTest.require(status.reconstruction()==RtxptAccess.Reconstruction.DLSS_RR,"DLSS-RR must actually be active: "+status);
            RtxptAdapterSelfTest.require(!status.frameGeneration(),"Frame generation disabled");
            System.out.println("RTXPT actual native settings: "+status);
            nativeHost.awaitIdle();
            int measuredFrames=120;
            long benchmarkStart=System.nanoTime();
            for(int i=0;i<measuredFrames;i++)RtxptAdapterSelfTest.require(nativeHost.renderFrame(),"Steady-state native benchmark frame");
            nativeHost.awaitIdle();
            double benchmarkSeconds=(System.nanoTime()-benchmarkStart)/1_000_000_000.0;
            System.out.printf(java.util.Locale.ROOT,
                "RTXPT steady-state: output=%dx%d, input=%dx%d, FG=off, frames=%d, wall=%.3fs, %.2f FPS (%.3f ms/frame)%n",
                initialFrame.width(),initialFrame.height(),status.renderWidth(),status.renderHeight(),measuredFrames,benchmarkSeconds,
                measuredFrames/benchmarkSeconds,benchmarkSeconds*1000/measuredFrames);
            var t=nativeHost.bridgeTimings();RtxptAdapterSelfTest.require(t.sceneExports()==1,"Static assets exported once");
            RtxptAdapterSelfTest.require(t.frames()>=133,"Presented native frames including the steady-state measurement");
            RtxptAdapterSelfTest.require(r.diagnostics().latestCompletedFrameSequence()==0,"Only a genuinely presented frame is marked complete");
            var frameGeneration=FrameGenerationOptions.builder().preference(RendererFeaturePreference.PREFERRED)
                .mode(FrameGenerationOptions.Mode.FRAME_GENERATION).multiplier(FrameGenerationOptions.Multiplier.TWO_X)
                .fallback(FrameGenerationOptions.Fallback.PRESENT_NATIVE_FRAMES).build();
            var fgPlan=control.plan(control.effectiveProfile().toBuilder().frameGeneration(frameGeneration).build());
            RtxptAdapterSelfTest.require(fgPlan.disposition()==RendererFeaturePlan.Disposition.APPLICABLE,"DLSS-FG changes at the Streamline frame boundary");
            RtxptAdapterSelfTest.require(control.apply(fgPlan).outcome()==RendererFeatureApplyResult.Outcome.APPLIED,"DLSS-FG policy accepted");
            RtxptAdapterSelfTest.require(nativeHost.renderFrame(),"Render after enabling DLSS-FG");
            status=nativeHost.runtimeStatus();
            if(status.frameGenerationSupported()) {
                RtxptAdapterSelfTest.require(status.frameGeneration(),"Supported DLSS-FG request must activate in RTXPT: "+status);
                RtxptAdapterSelfTest.require(status.generatedFramesPerNativeFrame()==1,"2x FG requests one generated frame: "+status);
                RtxptAdapterSelfTest.require(status.maxGeneratedFrames()>=1,"RTXPT reports the negotiated FG multiplier: "+status);
            } else RtxptAdapterSelfTest.require(!status.frameGeneration(),"Unsupported DLSS-FG must stay on native presentation");
            boolean frameGenerationActive=status.frameGeneration();
            var capabilities=r.extension(RenderingFeatureCapabilities.class).orElseThrow();
            RtxptAdapterSelfTest.require(capabilities.feature(RenderingFeatureCapabilities.Feature.FRAME_GENERATION).status()
                ==(frameGenerationActive?RenderingFeatureCapabilities.Status.ACTIVE:RenderingFeatureCapabilities.Status.FALLBACK),
                "Generic capability snapshot matches the native FG negotiation: "+capabilities);
            System.out.println("RTXPT DLSS-FG negotiation: "+status);
            var initial=output.resolve("absorbing.png");nativeHost.capture(initial);
            ((RtxptRenderer)r).snapshotMaterials(output.resolve("native-control/Materials"));
            var image=ImageIO.read(initial.toFile());
            RtxptAdapterSelfTest.require(image!=null && image.getWidth()==1920 && image.getHeight()==1080,"Full output screenshot");
            int min=765,max=0;
            for(int y=0;y<image.getHeight();y+=8)for(int x=0;x<image.getWidth();x+=8){int c=image.getRGB(x,y);int v=((c>>>16)&255)+((c>>>8)&255)+(c&255);min=Math.min(min,v);max=Math.max(max,v);}
            RtxptAdapterSelfTest.require(max-min>24,"Visible non-flat rendered scene");
            long transformBatchesBeforeEdit=nativeHost.bridgeTimings().transformBatches();
            r.apply(SceneTransaction.builder(2).upsert(SceneInstance.builder(1,1)
                .transform(new AffineTransform(new float[]{1,0,0,.75f,0,1,0,0,0,0,1,0}))
                .mediumBoundary(MediumBoundary.surface(new Medium(new Rgb(.2f,.6f,.8f),1.5f)).withPriority(4)).build()).build());
            r.submit(RtxptAdapterSelfTest.frame(1));
            RtxptAdapterSelfTest.require(nativeHost.runtimeStatus().frameGeneration()==frameGenerationActive,
                "Scene reload restores the requested DLSS-FG state");
            RtxptAdapterSelfTest.require(nativeHost.bridgeTimings().sceneExports()==1 && nativeHost.bridgeTimings().transformBatches()==transformBatchesBeforeEdit+1,"Transform-only edit uses one native batch without scene export");
            nativeHost.setExposureCompensation(-.75f);
            try {nativeHost.setExposureCompensation(Float.NaN);throw new AssertionError("Reject non-finite exposure");}
            catch(IllegalArgumentException expected) { }
            r.apply(RtxptAdapterSelfTest.scene(3,new Medium(new Rgb(.8f,.1f,.04f),1.33f)));
            r.submit(RtxptAdapterSelfTest.frame(2));
            for(int i=0;i<16;i++)RtxptAdapterSelfTest.require(nativeHost.renderFrame(),"Render after scene switch");
            RtxptAdapterSelfTest.require(nativeHost.bridgeTimings().sceneExports()==2,"Structural scene switch exports once");
            RtxptAdapterSelfTest.require(nativeHost.presentationStatus().exposureCompensation()==-.75f,"Exposure survives structural reload");
            nativeHost.capture(output.resolve("switched.png"));
            r.submit(RenderFrameRequest.builder(3,960,540,RtxptAdapterSelfTest.camera()).minimumSceneRevision(3)
                .environment(RtxptAdapterSelfTest.frame(0).environment()).build());
            RtxptAdapterSelfTest.require(nativeHost.renderFrame(),"Render after resize");
            RtxptAdapterSelfTest.require(nativeHost.presentationStatus().exposureCompensation()==-.75f,"Exposure survives resize");
            RtxptAdapterSelfTest.require(nativeHost.runtimeStatus().frameGeneration()==frameGenerationActive,
                "Swapchain resize disables and restores DLSS-FG around the resize frame");
            nativeHost.capture(output.resolve("resized.png"));
            var resized=ImageIO.read(output.resolve("resized.png").toFile());
            RtxptAdapterSelfTest.require(resized.getWidth()==960 && resized.getHeight()==540,"Native window/output resize");
            nativeHost.awaitIdle();
            if(frameGenerationActive) {
                var offPlan=control.plan(control.effectiveProfile().toBuilder().frameGeneration(FrameGenerationOptions.disabled()).build());
                RtxptAdapterSelfTest.require(offPlan.disposition()==RendererFeaturePlan.Disposition.APPLICABLE,"DLSS-FG can be turned off live");
                RtxptAdapterSelfTest.require(control.apply(offPlan).outcome()==RendererFeatureApplyResult.Outcome.APPLIED,"DLSS-FG disable accepted");
                RtxptAdapterSelfTest.require(nativeHost.renderFrame()&&!nativeHost.runtimeStatus().frameGeneration(),"DLSS-FG shuts down on the next native frame");
            }
            System.out.println("RTXPT in-process native smoke PASS: "+t);
            System.out.println("RTXPT scene switch, transform batch, capture and resize PASS; evidence="+output);
            // Verify the live PTMaterial, not only glTF text: Donut does not import
            // KHR_materials_ior into the upstream baker by itself.
            long materialRevision=4,materialSequence=4;
            for(float ior:new float[]{1.1f,1.5f,2.618034f}) {
                r.apply(SceneTransaction.builder(materialRevision)
                    .upsert(MaterialAsset.builder(1).transmission(0).indexOfRefraction(ior).build())
                    .upsert(SceneInstance.builder(1,1).build()).build());
                r.submit(RenderFrameRequest.builder(materialSequence++,960,540,RtxptAdapterSelfTest.camera())
                    .minimumSceneRevision(materialRevision++).build());
                var materialsDirectory=output.resolve("opaque-ior-"+ior);
                ((RtxptRenderer)r).snapshotMaterials(materialsDirectory);
                try(var files=Files.list(materialsDirectory)) {
                    var materialFile=files.filter(p->p.toString().endsWith(".material.json")).findFirst().orElseThrow();
                    var json=Files.readString(materialFile);
                    var match=java.util.regex.Pattern.compile("\"IoR\"\\s*:\\s*([0-9.eE+-]+)").matcher(json);
                    RtxptAdapterSelfTest.require(match.find()&&Math.abs(Float.parseFloat(match.group(1))-ior)<1e-5,
                        "Opaque IOR reaches the live native PTMaterial: "+ior+" in "+materialFile);
                }
            }
            System.out.println("RTXPT opaque material IOR native readback PASS: 1.1, 1.5, 2.618034");
        }
    }
}

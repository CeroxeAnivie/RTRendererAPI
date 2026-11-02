package demo;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenterConfig;
import top.ceroxe.rt.renderer.rtxpt.RtxptAccess;
import top.ceroxe.rt.renderer.rtxpt.RtxptComposedRenderer;

/** End-to-end GPU sharing, exact display colors, Vulkan UI, switching and completed throughput. */
public final class RtxptCompositionSelfTest {
    private static final int WIDTH=1920,HEIGHT=1080;
    private static long sequence;
    private RtxptCompositionSelfTest() { }
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]).toAbsolutePath();Files.createDirectories(output);
        String mode=args.length>1?args[1]:"acceptance";
        if(mode.equals("source-reuse")){nativeSourceReuse(output);return;}
        if(mode.equals("fusion-lifecycle")){fusionLifecycle(output);return;}
        if(mode.equals("native")||mode.startsWith("return-")||mode.equals("overlay")||mode.equals("no-overlay"))requireIdleGpu();
        if(mode.equals("native")){nativeBenchmark(output);return;}
        if(mode.equals("rtxpt-final")||mode.equals("rtxpt-hud")||mode.startsWith("return-")){nativeFinal(output,mode);return;}
        var config=configuration();
        try(var renderer=RtxptComposedRenderer.openVulkanPresentation(RendererBackendPolicy.RTXPT_ONLY,
                RendererConfig.builder().cpuFrameReadbackEnabled(false).build(),config,
                VulkanFramePresenterConfig.builder().title("RTRendererAPI - RTXPT + Vulkan composition")
                    .initialExtent(WIDTH,HEIGHT).presentMode(VulkanFramePresenterConfig.PresentMode.UNCAPPED).build())) {
            renderer.apply(RtxptMediumScene.create(1,"mixed"));
            for(int i=0;i<90;i++)submit(renderer,1,WIDTH,HEIGHT);
            require(renderer.rtxptStatus().reconstruction()==RtxptAccess.Reconstruction.DLSS_RR,"Native RR remains active");
            if(mode.equals("acceptance")) {
                renderer.presenter().captureNextPresentedFrame(output.resolve("base-final.png"));
                submit(renderer,1,WIDTH,HEIGHT);
                renderer.captureRtxptSource(output.resolve("base-source.png"));
                compare(output.resolve("base-source.png"),output.resolve("base-final.png"),false);
            }
            var layer=createOverlay(renderer);
            if(mode.equals("overlay")||mode.equals("acceptance"))renderer.overlays(List.of(layer));
            if(!mode.equals("acceptance")){benchmark(renderer,output,mode);return;}
            renderer.presenter().captureNextPresentedFrame(output.resolve("ui-final.png"));
            submit(renderer,1,WIDTH,HEIGHT);
            renderer.captureRtxptSource(output.resolve("ui-source.png"));
            compare(output.resolve("ui-source.png"),output.resolve("ui-final.png"),true);
            require(renderer.workSnapshot().vulkanFrames()==0,"Vulkan did not duplicate the RTXPT 3D scene");
            require(renderer.extension(RtxptAccess.class).isEmpty(),"Hidden producer cannot bypass its consumption lifecycle");

            renderer.backendPolicy(RendererBackendPolicy.VULKAN_ONLY);
            expectFailure(()->submit(renderer,1,WIDTH,HEIGHT),"Media cannot silently lose its optical contract");
            renderer.backendPolicy(RendererBackendPolicy.RTXPT_ONLY);
            renderer.overlays(List.of(new FrameCompositionPlan.Layer(
                new ResourceMutationKey(layer.source().generation(),9999),FrameCompositionPlan.Operation.ALPHA_OVER)));
            expectFailure(()->submit(renderer,1,WIDTH,HEIGHT),"Reject a nonexistent UI mutation after a source frame");
            renderer.overlays(List.of(layer));
            submit(renderer,1,WIDTH,HEIGHT);
            System.out.println("COMPOSITION_REJECT_AND_RECOVER PASS");

            renderer.apply(ordinary(2));
            for(int i=0;i<8;i++)submit(renderer,2,WIDTH,HEIGHT);
            var sameWindow=renderer.presenter();
            renderer.backendPolicy(RendererBackendPolicy.VULKAN_ONLY);
            for(int i=0;i<16;i++)submit(renderer,2,WIDTH,HEIGHT);
            renderer.presenter().captureNextPresentedFrame(output.resolve("vulkan-ui.png"));
            submit(renderer,2,WIDTH,HEIGHT);
            require(renderer.selection().orElseThrow().backend()==RendererBackendPolicy.Backend.VULKAN,"Actual Vulkan 3D selection");
            require(renderer.workSnapshot().vulkanFrames()>0,"Actual Vulkan 3D work occurred");
            renderer.backendPolicy(RendererBackendPolicy.RTXPT_ONLY);
            renderer.presenter().captureNextPresentedFrame(output.resolve("rtxpt-return-ui.png"));
            submit(renderer,2,WIDTH,HEIGHT);
            renderer.captureRtxptSource(output.resolve("rtxpt-return-source.png"));
            compare(output.resolve("rtxpt-return-source.png"),output.resolve("rtxpt-return-ui.png"),true);
            require(renderer.presenter()==sameWindow,"Switching preserved the presenter/window owner");
            renderer.overlays(List.of());
            for(int i=0;i<4;i++)submit(renderer,2,960,540);
            for(int i=0;i<4;i++)submit(renderer,2,WIDTH,HEIGHT);
            renderer.presenter().setOverlayText("RTXPT 3D + VULKAN UI | GPU SHARING VERIFIED\nSAME WINDOW / BACKEND SWITCH VERIFIED | FG OFF");
            renderer.presenter().captureNextPresentedFrame(output.resolve("final-hud.png"));
            submit(renderer,2,WIDTH,HEIGHT);
            System.out.println("COMPOSITION_SCENE_SWITCH_RESIZE_AND_HUD PASS "+renderer.workSnapshot());
        }
        System.out.println("RTXPT_VULKAN_COMPOSITION PASS");
    }
    private static RendererConfig configuration(){
        return RendererConfig.pathTracedPresentation().copyBuilder().lowLatency(LowLatencyOptions.disabled())
            .frameGeneration(FrameGenerationOptions.disabled()).build();
    }
    private static void submit(RtxptComposedRenderer renderer,long revision,int width,int height){
        renderer.presenter().pollEvents();
        require(!renderer.presenter().windowState().closeRequested(),"Visible viewport stays open");
        var frame=RtxptMediumDemo.frameRequest(sequence++,revision,width,height);
        long deadline=System.nanoTime()+10_000_000_000L;
        while(renderer.trySubmit(frame) instanceof Renderer.FrameSubmissionDeferred){
            require(System.nanoTime()<deadline,"Bounded Vulkan admission makes progress");
            renderer.presenter().pollEvents();Thread.onSpinWait();
        }
    }
    private static SceneTransaction ordinary(long revision){
        return SceneTransaction.builder(revision).resetScene()
            .upsert(MaterialAsset.builder(1).baseColorRgba8(0xff3060d0).roughness(.5f).build())
            .upsert(RtxptMediumScene.cube(1,1)).upsert(SceneInstance.builder(1,1).build()).build();
    }
    static FrameCompositionPlan.Layer createOverlay(RtxptComposedRenderer renderer) throws Exception {
        var texture=new TextureResource(new RenderResourceId(700),ResourceVersion.initial(),TextureDimension.TEXTURE_2D,
            WIDTH,HEIGHT,1,1,1,1,TextureFormat.RGBA8_UNORM,EnumSet.of(TextureUsage.COLOR_ATTACHMENT,TextureUsage.STORAGE_READ));
        var commands=renderer.vulkanCommands();
        require(commands.submitResources(new RenderResourceTransaction(0,List.of(),List.of(texture),List.of())).outcome()
            ==ResourceTransactionEvidence.Outcome.ACCEPTED,"Vulkan UI resource is accepted");
        var view=new TextureView(texture,TextureViewDimension.TEXTURE_2D,new TextureSubresourceRange(TextureAspect.COLOR,0,1,0,1));
        var pass=RenderPassDescriptor.color(WIDTH,HEIGHT,List.of(RenderAttachment.cleared(view,StoreOp.STORE,
            new ClearValue.Color(.25f,.0625f,0,.5f))));
        require(commands.submitCommands(new RenderCommandTransaction(0,List.of(new BeginRenderPassCommand(pass),new EndRenderPassCommand())))
            .outcome()==CommandExecutionEvidence.Outcome.RECORDED,"Vulkan UI draw recorded");
        long deadline=System.nanoTime()+10_000_000_000L;
        while(System.nanoTime()<deadline){
            var evidence=commands.commandExecutionEvidence(0).orElseThrow();
            if(evidence.outcome().gpuCompleted())return new FrameCompositionPlan.Layer(
                new ResourceMutationKey(ResourceGenerationKey.of(texture),0),FrameCompositionPlan.Operation.ALPHA_OVER);
            require(evidence.outcome()==CommandExecutionEvidence.Outcome.RECORDED,"UI draw failed: "+evidence);Thread.sleep(1);
        }
        throw new AssertionError("Vulkan UI draw timed out");
    }
    private static void compare(Path sourceFile,Path finalFile,boolean overlay) throws Exception {
        BufferedImage source=ImageIO.read(sourceFile.toFile()),target=ImageIO.read(finalFile.toFile());
        require(source!=null&&target!=null&&source.getWidth()==target.getWidth()&&source.getHeight()==target.getHeight(),"Matching final dimensions");
        long samples=0,total=0;int maximum=0;
        for(int y=8;y<source.getHeight()-8;y+=3)for(int x=8;x<source.getWidth()-8;x+=3){
            int a=source.getRGB(x,y),b=target.getRGB(x,y);
            for(int c=0;c<3;c++){
                int shift=16-c*8,value=(a>>>shift)&255,actual=(b>>>shift)&255;
                int expected=overlay?(int)Math.round(value*(1-128/255.0)+new int[]{64,16,0}[c]):value;
                int error=Math.abs(expected-actual);maximum=Math.max(maximum,error);total+=error;samples++;
            }
        }
        double mean=(double)total/samples;
        System.out.printf(Locale.ROOT,"COMPOSITION_PIXELS overlay=%s max=%d mean=%.6f%n",overlay,maximum,mean);
        require(maximum<=2,"GPU copy/alpha composition changed source SDR values; maximum="+maximum+", mean="+mean);
    }
    private static void benchmark(RtxptComposedRenderer renderer,Path output,String mode) throws Exception {
        renderer.presenter().retireD3D12SharedFrames();
        var before=renderer.workSnapshot();long start=System.nanoTime();int frames=360;
        for(int i=0;i<frames;i++)submit(renderer,1,WIDTH,HEIGHT);
        renderer.presenter().retireD3D12SharedFrames();
        double elapsed=(System.nanoTime()-start)/1e6;var after=renderer.workSnapshot();
        long completed=after.presentation().presentSamples()-before.presentation().presentSamples();
        require(completed==frames,"Only actual Vulkan final presents count");
        double copy=(after.presentation().managedCopyGpuNanos()-before.presentation().managedCopyGpuNanos())/1e6/
            Math.max(1,after.presentation().managedCopyGpuSamples()-before.presentation().managedCopyGpuSamples());
        String row=String.format(Locale.ROOT,"%s,%d,%.6f,%.6f,%.6f%n",mode,completed,elapsed/completed,completed*1000/elapsed,copy);
        Files.writeString(output.resolve(mode+".csv"),"mode,completed_frames,ms,fps,copy_gpu_ms\n"+row);System.out.print(row);
    }
    private static void nativeBenchmark(Path output) throws Exception {
        try(var renderer=Renderer.open("rtxpt",configuration())){
            renderer.apply(RtxptMediumScene.create(1,"mixed"));
            var host=renderer.extension(RtxptAccess.class).orElseThrow();
            for(int i=0;i<90;i++)renderer.submit(RtxptMediumDemo.frameRequest(sequence++,1,WIDTH,HEIGHT));
            host.awaitIdle();long start=System.nanoTime(),before=host.bridgeTimings().frames();int frames=360;
            for(int i=0;i<frames;i++)renderer.submit(RtxptMediumDemo.frameRequest(sequence++,1,WIDTH,HEIGHT));
            host.awaitIdle();double elapsed=(System.nanoTime()-start)/1e6;long completed=host.bridgeTimings().frames()-before;
            require(completed==frames,"Actual upstream rendered frames");
            String row=String.format(Locale.ROOT,"native,%d,%.6f,%.6f,0%n",completed,elapsed/completed,completed*1000/elapsed);
            Files.writeString(output.resolve("native.csv"),"mode,completed_frames,ms,fps,copy_gpu_ms\n"+row);System.out.print(row);
            System.out.println(host.runtimeStatus()+" "+host.presentationStatus());
        }
    }
    private static void nativeSourceReuse(Path output) throws Exception {
        try(var renderer=RtxptComposedRenderer.openRtxptPresentation(
                RendererConfig.builder().cpuFrameReadbackEnabled(false).build(),
                DemoRendererProfile.rtxptMediumInteractive(false),"RTRendererAPI - composition source reuse test")) {
            renderer.apply(RtxptMediumScene.create(1,"mixed"));
            var layer=createOverlay(renderer);
            renderer.overlays(List.of(layer));
            renderer.submit(RtxptMediumDemo.frameRequest(sequence++,1,WIDTH,HEIGHT));
            var commands=renderer.vulkanCommands();
            var texture=new TextureResource(new RenderResourceId(700),ResourceVersion.initial(),TextureDimension.TEXTURE_2D,
                WIDTH,HEIGHT,1,1,1,1,TextureFormat.RGBA8_UNORM,EnumSet.of(TextureUsage.COLOR_ATTACHMENT,TextureUsage.STORAGE_READ));
            var view=new TextureView(texture,TextureViewDimension.TEXTURE_2D,new TextureSubresourceRange(TextureAspect.COLOR,0,1,0,1));
            int blocked=0;
            for(long commandSequence=1;commandSequence<=12;commandSequence++) {
                var pass=RenderPassDescriptor.color(WIDTH,HEIGHT,List.of(RenderAttachment.cleared(view,StoreOp.STORE,
                    new ClearValue.Color(commandSequence%2==0?.25f:0,.0625f,0,.5f))));
                var transaction=new RenderCommandTransaction(commandSequence,List.of(new BeginRenderPassCommand(pass),new EndRenderPassCommand()));
                var result=commands.submitCommands(transaction);
                if(result.outcome()==CommandExecutionEvidence.Outcome.BLOCKED) {
                    require(result.reason()==CommandExecutionEvidence.Reason.BOUNDED_BACKPRESSURE,"Retryable source backpressure reason");
                    renderer.awaitCompositionConsumers();
                    result=commands.submitCommands(transaction);blocked++;
                }
                require(result.outcome()==CommandExecutionEvidence.Outcome.RECORDED,"Same sequence records after consumer retirement: "+result);
                long deadline=System.nanoTime()+10_000_000_000L;
                while(!commands.commandExecutionEvidence(commandSequence).orElseThrow().outcome().gpuCompleted()) {
                    require(System.nanoTime()<deadline,"Source rewrite completion deadline");Thread.sleep(1);
                }
                renderer.overlays(List.of(new FrameCompositionPlan.Layer(
                    new ResourceMutationKey(ResourceGenerationKey.of(texture),commandSequence),FrameCompositionPlan.Operation.ALPHA_OVER)));
                renderer.submit(RtxptMediumDemo.frameRequest(sequence++,1,WIDTH,HEIGHT));
            }
            require(blocked>0,"Test must exercise a genuinely consumer-owned source");
            var host=renderer.extension(RtxptAccess.class).orElseThrow();
            require(host.bridgeTimings().sceneExports()==1,"Consumer waits preserve the resident scene");
            host.capture(output.resolve("source-reuse-final.png"));
            Files.writeString(output.resolve("source-reuse-result.txt"),"PASS blocked="+blocked+", same-sequence retries=12, actual="+host.runtimeStatus());
            System.out.println("COMPOSITION_SOURCE_REUSE PASS: blocked="+blocked+"; all 12 rewrites presented");
        }
    }

    private static void fusionLifecycle(Path output) throws Exception {
        try(var renderer=RtxptComposedRenderer.openRtxptPresentation(
                RendererConfig.builder().cpuFrameReadbackEnabled(false).build(),
                DemoRendererProfile.rtxptMediumInteractive(false),"RTRendererAPI - Fusion lifecycle verification")) {
            renderer.apply(RtxptMediumScene.create(1,"mixed"));
            renderer.submit(RtxptMediumDemo.frameRequest(sequence++,1,960,540));
            var host=renderer.extension(RtxptAccess.class).orElseThrow();
            long window=host.windowHandle();
            var effects=new RtxptFusionDemo.VulkanEffects(renderer);
            for(int index=0;index<RtxptMediumScene.PRESETS.size();index++) {
                long revision=index+2;
                String scene=RtxptMediumScene.PRESETS.get(index);
                int width=index%2==0?960:1280,height=index%2==0?540:720;
                renderer.apply(RtxptMediumScene.create(revision,scene));
                host.setOverlayChoices(RtxptMediumScene.LABELS,index);
                var camera=RtxptMediumScene.camera(width,height);
                for(int mode:new int[]{0,1,2,0}) {
                    effects.draw(width,height,RtxptFusionDemo.project(camera,-1.2,.9,0),
                            RtxptFusionDemo.project(camera,1.3,.65,.3),index+.25f,mode);
                    renderer.submit(DemoRendererProfile.frame(sequence++,width,height,camera)
                            .minimumSceneRevision(revision).environment(RtxptMediumScene.environment())
                            .antiAliasing(DemoRendererProfile.antiAliasing(1)).build());
                    if(mode==2) {
                        var overlayPath=output.resolve("fusion-"+scene+"-isolated.png");
                        host.capture(overlayPath);
                        var overlayImage=ImageIO.read(overlayPath.toFile());
                        int cyan=0,dark=0;
                        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
                            int pixel=overlayImage.getRGB(x,y),r=(pixel>>>16)&255,g=(pixel>>>8)&255,b=pixel&255;
                            if(r<90&&g>120&&b>150)cyan++;
                            if(r<10&&g<10&&b<10)dark++;
                        }
                        require(cyan>20&&dark>width*height*.8,"Actual Vulkan overlay pixels must be visible in isolated final output: cyan="+cyan+", dark="+dark);
                    }
                }
                require(host.windowHandle()==window,"Scene/extent/layer changes preserve the native window");
                require(host.overlaySelection()==index,"Scene reload preserves its selector choice");
                var display=host.presentationStatus();
                require(display.outputWidth()==width&&display.outputHeight()==height,"Actual native resize completed");
                host.capture(output.resolve("fusion-"+scene+".png"));
                var image=ImageIO.read(output.resolve("fusion-"+scene+".png").toFile());
                require(image!=null&&image.getWidth()==width&&image.getHeight()==height,"Completed final image has resized extent");
                System.out.println("FUSION_LIFECYCLE_SCENE PASS "+scene+" "+width+"x"+height);
            }
            renderer.overlays(List.of());renderer.trimIdleCompositionResources();
            require(host.renderFrame(),"Final native frame after retiring all composition resources");
            require(renderer.workSnapshot().vulkanFrames()==0,"Fusion overlays never duplicate 3D rendering");
            require(effects.draws==18,"All three Vulkan-visible states were exercised for every scene");
            System.out.println("FUSION_LIFECYCLE PASS scenes=6 resize=6 overlayTransitions=24 nativeWindow="+window);
        }
    }

    private static void nativeFinal(Path output,String mode) throws Exception {
        boolean checkGeneration=mode.equals("rtxpt-final");
        boolean acceptance=checkGeneration||mode.equals("rtxpt-hud");
        var config=checkGeneration?DemoRendererProfile.rtxptMediumInteractive(false):configuration();
        try(var renderer=RtxptComposedRenderer.openRtxptPresentation(
                RendererConfig.builder().cpuFrameReadbackEnabled(false).build(),config,"RTRendererAPI - Medium Demo")) {
            renderer.apply(RtxptMediumScene.create(1,"mixed"));
            FrameCompositionPlan.Layer layer=null;
            if(!mode.equals("return-no-overlay")){layer=createOverlay(renderer);renderer.overlays(List.of(layer));}
            renderer.submit(RtxptMediumDemo.frameRequest(sequence++,1,WIDTH,HEIGHT));
            var host=renderer.extension(RtxptAccess.class).orElseThrow();
            for(int i=0;i<90;i++)require(host.renderFrame(),"Native final viewport stays open");
            require(renderer.workSnapshot().rtxptFrames()==host.bridgeTimings().frames(),
                "Work snapshot includes direct RtxptAccess.renderFrame calls");
            if(!acceptance) {
                int frames=Integer.getInteger("top.ceroxe.rt.rtxpt.compositionBenchmarkFrames",1800);
                if(frames<60)throw new IllegalArgumentException("At least 60 benchmark frames");
                host.awaitIdle();long before=host.bridgeTimings().frames(),start=System.nanoTime();
                for(int i=0;i<frames;i++) {
                    if(mode.equals("return-toggle")&&i%30==0)
                        renderer.overlays(i%60==0?List.of():List.of(layer));
                    require(host.renderFrame(),"Native final benchmark frame");
                }
                host.awaitIdle();double elapsed=(System.nanoTime()-start)/1e6;long completed=host.bridgeTimings().frames()-before;
                require(completed==frames,"Native benchmark must complete every requested frame; viewport may have been minimized or occluded: "+completed+"/"+frames);
                String row=String.format(Locale.ROOT,"%s,%d,%.6f,%.6f,0%n",mode,completed,elapsed/completed,completed*1000/elapsed);
                Files.writeString(output.resolve(mode+".csv"),"mode,completed_frames,ms,fps,copy_gpu_ms\n"+row);System.out.print(row);return;
            }
            var controller=renderer.extension(RendererFeatureController.class).orElseThrow();
            if(checkGeneration)require(new DemoWindowKeys(host.windowHandle()).focused(),
                "FG/MFG acceptance paused: test window must own the foreground; do not infer delivery from an enabled option");
            var csv=new StringBuilder("multiplier,native_frames,presented_frames,ratio,native_ms,output_fps,query_failures\n");
            for(var multiplier:checkGeneration?FrameGenerationOptions.Multiplier.values():new FrameGenerationOptions.Multiplier[0]) {
                var fg=FrameGenerationOptions.builder().preference(RendererFeaturePreference.REQUIRED)
                    .mode(multiplier==FrameGenerationOptions.Multiplier.TWO_X?FrameGenerationOptions.Mode.FRAME_GENERATION:FrameGenerationOptions.Mode.MULTI_FRAME_GENERATION)
                    .multiplier(multiplier).build();
                require(controller.apply(controller.plan(controller.effectiveProfile().toBuilder().frameGeneration(fg).build()))
                    .outcome()==RendererFeatureApplyResult.Outcome.APPLIED,"Upstream advanced profile applied");
                for(int i=0;i<24;i++)require(host.renderFrame(),"FG warmup");
                host.awaitIdle();var before=renderer.diagnostics().frameGenerationEvidence();long start=System.nanoTime();
                for(int i=0;i<180;i++)require(host.renderFrame(),"Native final FG/MFG frame");
                host.awaitIdle();double elapsed=(System.nanoTime()-start)/1e6;var after=renderer.diagnostics().frameGenerationEvidence();
                long nativeFrames=after.proxyPresentCalls()-before.proxyPresentCalls();
                long delivered=after.totalFramesActuallyPresented()-before.totalFramesActuallyPresented();
                double ratio=(double)delivered/nativeFrames;var status=host.runtimeStatus();
                require(status.reconstruction()==RtxptAccess.Reconstruction.DLSS_RR&&status.sharc()&&status.shaderExecutionReordering()
                    &&status.lowLatency()&&status.frameGeneration(),"RR + SHaRC + SER + Reflex + FG active: "+status);
                require(nativeFrames==180&&Math.abs(ratio-multiplier.presentedFramesPerNativeFrame())<.1,
                    "Actual final-window FG ratio: native="+nativeFrames+", delivered="+delivered+", ratio="+ratio+", status="+status);
                require(after.stateQueryFailures()==before.stateQueryFailures()&&after.latestNativeStatus().orElse(-1)==0,"SDK success");
                csv.append(String.format(Locale.ROOT,"%s,%d,%d,%.6f,%.6f,%.6f,%d%n",multiplier,nativeFrames,delivered,ratio,
                    elapsed/nativeFrames,delivered*1000/elapsed,after.stateQueryFailures()-before.stateQueryFailures()));
                Files.writeString(output.resolve("advanced.csv"),csv);System.out.println("NATIVE_FINAL_ADVANCED PASS "+multiplier+" "+status);
            }
            host.setOverlayText("");require(host.renderFrame(),"Capture frame");
            host.capture(output.resolve("native-ui-final.png"));renderer.captureRtxptSource(output.resolve("native-ui-source.png"));
            compare(output.resolve("native-ui-source.png"),output.resolve("native-ui-final.png"),true);
            renderer.overlays(List.of());
            for(int i=0;i<3;i++)require(host.renderFrame(),"Overlay disabled with no per-frame composition");
            host.capture(output.resolve("native-overlay-disabled.png"));
            renderer.captureRtxptSource(output.resolve("native-overlay-disabled-source.png"));
            compare(output.resolve("native-overlay-disabled-source.png"),output.resolve("native-overlay-disabled.png"),false);
            renderer.trimIdleCompositionResources();
            renderer.overlays(List.of(java.util.Objects.requireNonNull(layer)));
            for(int i=0;i<3;i++)require(host.renderFrame(),"Overlay recreated after freeing temporary images");
            host.capture(output.resolve("native-overlay-restored.png"));
            renderer.captureRtxptSource(output.resolve("native-overlay-restored-source.png"));
            compare(output.resolve("native-overlay-restored-source.png"),output.resolve("native-overlay-restored.png"),true);
            require(renderer.workSnapshot().presentation().presentSamples()==0,"Hidden Vulkan compositor never presented a frame");
            require(renderer.workSnapshot().vulkanFrames()==0,"Vulkan did not duplicate complete 3D tracing");
            renderer.overlays(List.of()); // Normal-color HUD evidence, without the orange test layer.
            host.setOverlayText("RTRendererAPI 7.0.1 - MEDIUM DEMO\nRTXPT: RR | VULKAN: GPU UI\nHUD / SCENE SELECTOR / OVERLAY LIFECYCLE VERIFIED");
            host.setOverlayChoices(RtxptMediumScene.LABELS,0);
            // ImGui measures a newly appearing auto-sized window before it draws its contents.
            for(int i=0;i<3;i++)require(host.renderFrame(),"Final HUD frame");
            host.capture(output.resolve("native-final-hud.png"));
            requireVisibleHud(output.resolve("native-final-hud.png"));
            require(renderer.workSnapshot().rtxptFrames()==host.bridgeTimings().frames(),"Final actual work count");
            System.out.println("RTXPT_NATIVE_FINAL_COMPOSITION PASS; generationChecked="+checkGeneration);
        }
    }
    static void requireVisibleHud(Path path) throws Exception {
        var image=ImageIO.read(path.toFile());int brightPixels=0;
        // White text in the dark HUD at (14,14), where this scene has only a flat background.
        for(int y=20;y<Math.min(105,image.getHeight());y++)for(int x=20;x<Math.min(650,image.getWidth());x++) {
            int c=image.getRGB(x,y);
            if(((c>>>16)&255)>170&&((c>>>8)&255)>170&&(c&255)>170)brightPixels++;
        }
        require(brightPixels>300,"HUD text is visible in final pixels; bright pixels="+brightPixels);
    }
    private static void requireIdleGpu() throws Exception {
        var process=new ProcessBuilder("nvidia-smi","--id=0","--query-gpu=utilization.gpu","--format=csv,noheader,nounits")
            .redirectErrorStream(true).start();
        String value=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();
        if(process.waitFor()!=0)throw new IllegalStateException("Cannot read initial GPU utilization: "+value);
        int utilization=Integer.parseInt(value);
        if(utilization<0||utilization>=10)throw new IllegalStateException(
            "Performance run paused: GPU 0 baseline must be below 10%, actual="+value);
        System.out.println("PERFORMANCE_BASELINE_GPU_PERCENT="+utilization);
    }
    private static void expectFailure(Runnable action,String reason){
        try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){System.out.println("EXPECTED_REJECTION "+expected.getMessage());return;}
        throw new AssertionError(reason);
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}

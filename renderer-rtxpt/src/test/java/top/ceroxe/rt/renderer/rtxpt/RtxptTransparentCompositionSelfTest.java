package top.ceroxe.rt.renderer.rtxpt;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenterConfig;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Short GPU background/external/overlay gate; CPU reference reads only explicit captures. */
public final class RtxptTransparentCompositionSelfTest {
    static final int W=640,H=360;
    static final Path OUT=RtxptTransparentNativeSelfTest.OUT;
    static long sequence;
    static final int[][] COLORS={{23,94,181},{205,163,43}};
    static void submit(RtxptComposedRenderer r){
        var f=RtxptContinuityNativeSelfTest.frame(sequence++,1,RtxptLightingNativeSelfTest.environment());
        long deadline=System.nanoTime()+10_000_000_000L;
        while(r.trySubmit(f) instanceof Renderer.FrameSubmissionDeferred){
            require(System.nanoTime()<deadline,"Composition submission makes bounded progress");
            r.awaitCompositionConsumers();Thread.onSpinWait();
        }
    }
    static FrameCompositionPlan.Layer texture(RtxptComposedRenderer r,boolean overlay)throws Exception{
        int id=overlay?702:701;long seq=overlay?2:0;
        var t=new TextureResource(new RenderResourceId(id),ResourceVersion.initial(),TextureDimension.TEXTURE_2D,
            W,H,1,1,1,1,TextureFormat.RGBA8_UNORM,EnumSet.of(TextureUsage.COPY_DESTINATION,TextureUsage.STORAGE_READ,TextureUsage.COLOR_ATTACHMENT));
        var commands=r.vulkanCommands();
        require(commands.submitResources(new RenderResourceTransaction(seq,List.of(),List.of(t),List.of())).outcome()==ResourceTransactionEvidence.Outcome.ACCEPTED,"Background GPU resource accepted");
        var bytes=ByteBuffer.allocate(W*H*4);
        for(int y=0;y<H;y++)for(int x=0;x<W;x++){
            var c=COLORS[((x/32)+(y/32))&1];
            if(overlay)bytes.put(new byte[]{64,32,16,(byte)128});
            else bytes.put(new byte[]{(byte)c[0],(byte)c[1],(byte)c[2],(byte)255});
        }
        bytes.flip();
        var range=new TextureSubresourceRange(TextureAspect.COLOR,0,1,0,1);
        var write=new WriteTextureCommand(new ResourceSlice.TextureSlice(t,range),new TextureOrigin(0,0,0),
            new TextureExtent(W,H,1),new TextureDataLayout(0,W*4,H),new ResourceData(bytes));
        var uploaded=commands.submitCommands(new RenderCommandTransaction(seq,List.of(write)));
        require(uploaded.outcome()==CommandExecutionEvidence.Outcome.RECORDED,"Host GPU upload recorded: "+uploaded);
        await(commands,seq);
        var view=new TextureView(t,TextureViewDimension.TEXTURE_2D,range);
        var pass=RenderPassDescriptor.color(W,H,List.of(RenderAttachment.of(view,LoadOp.LOAD,StoreOp.STORE)));
        var published=commands.submitCommands(new RenderCommandTransaction(seq+1,List.of(new BeginRenderPassCommand(pass),new EndRenderPassCommand())));
        require(published.outcome()==CommandExecutionEvidence.Outcome.RECORDED,"Host GPU output recorded: "+published);
        await(commands,seq+1);
        return new FrameCompositionPlan.Layer(new ResourceMutationKey(ResourceGenerationKey.of(t),seq+1),
            overlay?FrameCompositionPlan.Operation.ALPHA_OVER:FrameCompositionPlan.Operation.REPLACE);
    }
    static void await(RendererCommandAccess commands,long seq)throws Exception{
        long deadline=System.nanoTime()+10_000_000_000L;
        while(!commands.commandExecutionEvidence(seq).orElseThrow().outcome().gpuCompleted()){
            require(System.nanoTime()<deadline,"Host GPU layer upload completed");Thread.sleep(1);
        }
    }
    static double decode(double v){return v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4);}
    static double encode(double v){return v<=.0031308?v*12.92:1.055*Math.pow(v,1/2.4)-.055;}
    static void compare(BufferedImage source,BufferedImage result,String mode,boolean overlay){
        long total=0,n=0;int max=0,edges=0,sky=0,solid=0;
        for(int y=0;y<H;y++)for(int x=0;x<W;x++){
            int p=source.getRGB(x,y),a=p>>>24,q=result.getRGB(x,y);
            if(a==0)sky++;else if(a==255)solid++;else edges++;
            var bg=COLORS[((x/32)+(y/32))&1];int[] ui={64,32,16};
            for(int c=0;c<3;c++){
                int shift=16-c*8;
                double linear=decode(((p>>>shift)&255)/255d)+decode(bg[c]/255d)*(1-a/255d);
                if(overlay)linear=decode(ui[c]/255d)+linear*(1-128/255d);
                int expected=(int)Math.round(255*Math.min(1,encode(linear)));
                int error=Math.abs(expected-((q>>>shift)&255));max=Math.max(max,error);total+=error;n++;
            }
            require((q>>>24)==255,"Opaque host background produces opaque final output");
        }
        require(sky>W*H/8&&solid>W*H/8,"Both host sky and API surfaces are checked");
        require(max<=2,"Linear-light GPU composition matches CPU formula: max="+max);
        System.out.printf(Locale.ROOT,"TRANSPARENT_COMPOSITION %s overlay=%s max=%d mae=%.6f sky=%d solid=%d edges=%d%n",mode,overlay,max,total/(double)n,sky,solid,edges);
    }
    static void drain(RtxptComposedRenderer r){
        r.awaitCompositionConsumers();
        r.extension(RtxptAccess.class).ifPresent(RtxptAccess::awaitIdle);
    }
    public static void main(String[] args)throws Exception{
        String mode=args.length==0?"native":args[0],route=args.length<2?"rr":args[1];
        Files.createDirectories(OUT);var config=RtxptTransparentNativeSelfTest.config(route).copyBuilder().lowLatency(LowLatencyOptions.disabled()).build();
        var vk=RendererConfig.builder().cpuFrameReadbackEnabled(false).gpuTimingsEnabled(true).build();
        try(var r=mode.equals("native")?RtxptComposedRenderer.openRtxptPresentation(vk,config,"Transparent background acceptance"):
            RtxptComposedRenderer.openVulkanPresentation(RendererBackendPolicy.RTXPT_ONLY,vk,config,
                VulkanFramePresenterConfig.builder().title("Transparent background acceptance").initialExtent(W,H).visible(false).presentMode(VulkanFramePresenterConfig.PresentMode.UNCAPPED).build())){
            r.apply(RtxptAcceptanceFixture.scene());r.extension(SceneLightingControl.class).orElseThrow().transparentBackground();
            // No host scene or depth is supplied: only two independently completed GPU textures.
            var bg=texture(r,false);var ui=texture(r,true);
            for(int i=0;i<48;i++)submit(r);
            var source=OUT.resolve(mode+"-"+route+"-source.png");r.captureRtxptSource(source);
            r.backgroundLayer(bg);
            for(boolean overlay:new boolean[]{false,true}){
                r.overlays(overlay?List.of(ui):List.of());
                var path=OUT.resolve(mode+"-"+route+"-composed-"+overlay+".png");
                if(mode.equals("vulkan"))r.presenter().captureNextPresentedFrame(path);
                submit(r);
                r.captureRtxptSource(source); // Compare the exact same produced frame, including RR history.
                if(mode.equals("native"))r.extension(RtxptAccess.class).orElseThrow().capture(path);
                compare(ImageIO.read(source.toFile()),ImageIO.read(path.toFile()),mode+"-"+route,overlay);
            }
            require(r.workSnapshot().vulkanFrames()==0,"Host textures never trigger a duplicate Vulkan 3D scene");
            r.overlays(List.of());r.backgroundLayer(null);
            for(int i=0;i<8;i++)submit(r);
            drain(r);long start=System.nanoTime();int frames=48;
            for(int i=0;i<frames;i++)submit(r);drain(r);
            double sourceMs=(System.nanoTime()-start)/1e6/frames;
            r.backgroundLayer(bg);for(int i=0;i<8;i++)submit(r);
            drain(r);start=System.nanoTime();
            for(int i=0;i<frames;i++)submit(r);drain(r);
            double composedMs=(System.nanoTime()-start)/1e6/frames;
            System.out.printf(Locale.ROOT,"TRANSPARENT_COST %s-%s frames=%d sourceMs=%.6f composedMs=%.6f deltaMs=%.6f%n",mode,route,frames,sourceMs,composedMs,composedMs-sourceMs);
        }
    }
}

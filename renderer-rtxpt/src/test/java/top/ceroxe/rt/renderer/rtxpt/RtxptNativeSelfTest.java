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
            r.apply(RtxptAdapterSelfTest.scene(1));r.submit(RtxptAdapterSelfTest.frame(0));
            RtxptAdapterSelfTest.require(nativeHost.windowHandle()!=0,"Project-controlled native window");
            for(int i=0;i<12;i++)RtxptAdapterSelfTest.require(nativeHost.renderFrame(),"Native render/present");
            var status=nativeHost.runtimeStatus();
            RtxptAdapterSelfTest.require(status.reconstruction()==RtxptAccess.Reconstruction.DLSS_RR,"DLSS-RR must actually be active: "+status);
            RtxptAdapterSelfTest.require(!status.frameGeneration(),"Frame generation disabled");
            System.out.println("RTXPT actual native settings: "+status);
            var t=nativeHost.bridgeTimings();RtxptAdapterSelfTest.require(t.sceneExports()==1,"Static assets exported once");
            RtxptAdapterSelfTest.require(t.frames()>=13,"Presented native frames");
            var initial=output.resolve("absorbing.png");nativeHost.capture(initial);
            var image=ImageIO.read(initial.toFile());
            RtxptAdapterSelfTest.require(image!=null && image.getWidth()==1920 && image.getHeight()==1080,"Full output screenshot");
            int min=765,max=0;
            for(int y=0;y<image.getHeight();y+=8)for(int x=0;x<image.getWidth();x+=8){int c=image.getRGB(x,y);int v=((c>>>16)&255)+((c>>>8)&255)+(c&255);min=Math.min(min,v);max=Math.max(max,v);}
            RtxptAdapterSelfTest.require(max-min>24,"Visible non-flat rendered scene");
            r.apply(SceneTransaction.builder(2).upsert(SceneInstance.builder(1,1)
                .transform(new AffineTransform(new float[]{1,0,0,.75f,0,1,0,0,0,0,1,0}))
                .mediumBoundary(MediumBoundary.surface(new Medium(new Rgb(.2f,.6f,.8f),1.5f)).withPriority(4)).build()).build());
            r.submit(RtxptAdapterSelfTest.frame(1));
            RtxptAdapterSelfTest.require(nativeHost.bridgeTimings().sceneExports()==1 && nativeHost.bridgeTimings().transformBatches()==1,"Transform-only edit uses one native batch without scene export");
            r.apply(RtxptAdapterSelfTest.scene(3,new Medium(new Rgb(.8f,.1f,.04f),1.33f)));
            r.submit(RtxptAdapterSelfTest.frame(2));
            for(int i=0;i<16;i++)RtxptAdapterSelfTest.require(nativeHost.renderFrame(),"Render after scene switch");
            RtxptAdapterSelfTest.require(nativeHost.bridgeTimings().sceneExports()==2,"Structural scene switch exports once");
            nativeHost.capture(output.resolve("switched.png"));
            r.submit(RenderFrameRequest.builder(3,960,540,RtxptAdapterSelfTest.camera()).minimumSceneRevision(3)
                .environment(RtxptAdapterSelfTest.frame(0).environment()).build());
            RtxptAdapterSelfTest.require(nativeHost.renderFrame(),"Render after resize");
            nativeHost.capture(output.resolve("resized.png"));
            var resized=ImageIO.read(output.resolve("resized.png").toFile());
            RtxptAdapterSelfTest.require(resized.getWidth()==960 && resized.getHeight()==540,"Native window/output resize");
            nativeHost.awaitIdle();
            System.out.println("RTXPT in-process native smoke PASS: "+t);
            System.out.println("RTXPT scene switch, transform batch, capture and resize PASS; evidence="+output);
        }
    }
}

package top.ceroxe.rt.renderer.rtxpt;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Targeted real-output gate: geometric coverage, reconstructed edges and physical secondary paths. */
public final class RtxptTransparentNativeSelfTest {
    static final Path OUT=Path.of("build/reports/rtxpt-transparent-704");
    static RendererConfig config(String route){
        var c=RtxptLightingNativeSelfTest.configuration(route);
        var q=c.pathTracing().quality();
        q=q.withLighting(q.lighting().withInput(PathTracingQualityOptions.LightingInput.SCENE_ANALYTIC))
            .withPostProcessing(new PathTracingQualityOptions.PostProcessing(false,8,0,true));
        return c.copyBuilder().pathTracing(c.pathTracing().withQuality(q)).frameGeneration(FrameGenerationOptions.disabled()).build();
    }
    static BufferedImage shot(RtxptAccess h,String name,int settle)throws Exception{
        RtxptLightingNativeSelfTest.settle(h,settle);var p=OUT.resolve(name+".png");h.capture(p);return ImageIO.read(p.toFile());
    }
    static void coverage(BufferedImage a,String route,boolean reconstructed){
        int zero=0,full=0,edge=0,dirtyZero=0;
        for(int y=0;y<a.getHeight();y++)for(int x=0;x<a.getWidth();x++){
            int c=a.getRGB(x,y),alpha=c>>>24;
            if(alpha==0){zero++;if((c&0xffffff)!=0)dirtyZero++;}else if(alpha==255)full++;else edge++;
        }
        require(zero>a.getWidth()*a.getHeight()/8,"Primary misses have zero alpha "+route+" zero="+zero);
        require(full>a.getWidth()*a.getHeight()/8,"Physical surfaces keep coverage "+route+" full="+full);
        require(dirtyZero==0,"Zero-alpha output is exactly black, not hidden sky: "+dirtyZero);
        if(reconstructed)require(edge>20,"Reconstructed coverage has antialiased fractional edges: "+route+" edges="+edge);
        require((a.getRGB(5,5)>>>24)==0,"Unoccluded sky stays transparent");
        System.out.println("TRANSPARENT_COVERAGE "+route+" zero="+zero+" full="+full+" edge="+edge+" dirtyZero="+dirtyZero);
    }
    static SceneTransaction maskedScene(){
        byte[] pixels=new byte[16*16*4];for(int y=0;y<16;y++)for(int x=0;x<16;x++){
            int i=(y*16+x)*4;pixels[i]=(byte)220;pixels[i+1]=(byte)180;pixels[i+2]=100;pixels[i+3]=(byte)(x<8?0:255);
        }
        return SceneTransaction.builder(2).resetScene().upsert(TextureAsset.color(7,16,16,pixels))
            .upsert(MaterialAsset.builder(1).baseColorTextureId(7).blendMode(MaterialAsset.BlendMode.MASKED).alphaCutoff(.5f).roughness(.6f).build())
            .upsert(MeshAsset.builder(1,new float[]{-1,-1,0,1,-1,0,1,1,0,-1,1,0},new int[]{0,1,2,0,2,3},new long[]{1,1})
                .textureCoordinates(new float[]{0,1,1,1,1,0,0,0}).build())
            .upsert(SceneInstance.builder(1,1).build()).build();
    }
    public static void main(String[] args)throws Exception{
        Files.createDirectories(OUT);
        for(String route:args.length==0?List.of("analytic","rr","nrd"):List.of(args)){
            try(var r=Renderer.open("rtxpt",config(route))){
                var h=r.extension(RtxptAccess.class).orElseThrow();var control=r.extension(SceneLightingControl.class).orElseThrow();
                require(control.supportsTransparentBackground(),"Configuration explicitly supports coverage");
                var env=RtxptLightingNativeSelfTest.environment();long seq=0;r.apply(RtxptAcceptanceFixture.scene());
                control.visibleBackground(0,0,0);r.submit(RtxptContinuityNativeSelfTest.frame(seq++,1,env));
                var opaque=shot(h,route+"-opaque",route.equals("analytic")?2:48);
                control.transparentBackground();r.submit(RtxptContinuityNativeSelfTest.frame(seq++,1,env));
                var transparent=shot(h,route+"-transparent",route.equals("analytic")?2:48);
                System.out.println("TRANSPARENT_ROUTE "+route+" "+h.runtimeStatus());
                coverage(transparent,route,h.runtimeStatus().reconstruction()==RtxptAccess.Reconstruction.DLSS_RR);
                long count=0;double error=0;int max=0;
                for(int y=0;y<opaque.getHeight();y++)for(int x=0;x<opaque.getWidth();x++){
                    int c=transparent.getRGB(x,y);if((c>>>24)!=255)continue;
                    // Erode coverage so reconstruction edge changes cannot masquerade as surface error.
                    if(x<4||y<4||x>=opaque.getWidth()-4||y>=opaque.getHeight()-4)continue;
                    if((transparent.getRGB(x-4,y)>>>24)!=255||(transparent.getRGB(x+4,y)>>>24)!=255||(transparent.getRGB(x,y-4)>>>24)!=255||(transparent.getRGB(x,y+4)>>>24)!=255)continue;
                    int b=opaque.getRGB(x,y);for(int shift:new int[]{0,8,16}){int e=Math.abs(((c>>>shift)&255)-((b>>>shift)&255));error+=e;max=Math.max(max,e);count++;}
                }
                double mae=error/Math.max(1,count);require(count>10000,"Mixed opaque/metal/glass/absorption reference has interior samples");
                require(mae<(route.equals("analytic")?.001:4),"Secondary lighting survives transparency: "+route+" MAE="+mae);
                System.out.println("TRANSPARENT_SURFACES "+route+" samples="+count+" mae="+mae+" max="+max);
                r.apply(maskedScene());var camera=CameraState.lookAt(0,0,4,0,0,0).verticalFieldOfViewDegrees(52).aspectRatio(16d/9).build();
                r.submit(RenderFrameRequest.builder(seq++,640,360,camera).minimumSceneRevision(2).environment(env).build());
                var cutout=shot(h,route+"-masked",route.equals("analytic")?2:32);
                int left=cutout.getRGB(270,180)>>>24,right=cutout.getRGB(370,180)>>>24;
                require(Math.min(left,right)<=2&&Math.max(left,right)>=253,"Masked card preserves holes and solid texels independently of host geometry: "+left+","+right);
                r.submit(RenderFrameRequest.builder(seq++,800,450,camera).minimumSceneRevision(2).environment(env).build());
                var resized=shot(h,route+"-resized",4);require((resized.getRGB(5,5)>>>24)==0,"Resize keeps transparent misses");
                control.environmentBackground();r.submit(RenderFrameRequest.builder(seq++,800,450,camera).minimumSceneRevision(2).environment(env).build());
                var restored=shot(h,route+"-restored",4);require((restored.getRGB(5,5)>>>24)==255,"Opaque mode restores alpha one");
                System.out.println("TRANSPARENT_PASS "+route+" masked="+left+","+right+" resize/restore=PASS");
            }
        }
    }
}

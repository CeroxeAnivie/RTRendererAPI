package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.*;
import javax.imageio.ImageIO;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Bitwise secondary-path isolation with deterministic samples; not a temporal-noise test. */
public final class RtxptBackgroundNativeSelfTest {
    public static void main(String[] args)throws Exception {
        var out=Path.of("build/reports/rtxpt-background-703");Files.createDirectories(out);
        for(String route:new String[]{"analytic","raw"}){
            var base=RtxptLightingNativeSelfTest.configuration(route);var q=base.pathTracing().quality().withPostProcessing(new PathTracingQualityOptions.PostProcessing(false,8,0,false));
            try(var r=Renderer.open("rtxpt",base.copyBuilder().pathTracing(base.pathTracing().withQuality(q)).build())){
                var h=r.extension(RtxptAccess.class).orElseThrow();var control=r.extension(SceneLightingControl.class).orElseThrow();
                var e=RtxptLightingNativeSelfTest.environment();long seq=0;r.apply(RtxptAcceptanceFixture.scene());
                control.visibleBackground(.7f,.01f,.02f);r.submit(RtxptContinuityNativeSelfTest.frame(seq++,1,e));RtxptLightingNativeSelfTest.settle(h,8);var red=out.resolve(route+"-red.png");h.capture(red);
                control.visibleBackground(.01f,.02f,.7f);r.submit(RtxptContinuityNativeSelfTest.frame(seq++,1,e));RtxptLightingNativeSelfTest.settle(h,8);var blue=out.resolve(route+"-blue.png");h.capture(blue);
                var a=ImageIO.read(red.toFile());var b=ImageIO.read(blue.toFile());int changed=0,unchanged=0;double surfaceError=0;
                for(int y=0;y<a.getHeight();y++)for(int x=0;x<a.getWidth();x++){
                    int c=a.getRGB(x,y),d=b.getRGB(x,y);if(c==d)unchanged++;else changed++;
                    // The lower-center floor and visible metal/glass share this interior region.
                    if(x>=a.getWidth()/3&&x<a.getWidth()*2/3&&y>=a.getHeight()*2/3&&y<a.getHeight()*5/6)
                        for(int shift:new int[]{0,8,16})surfaceError+=Math.abs(((c>>>shift)&255)-((d>>>shift)&255));
                }
                require(changed>a.getWidth()*a.getHeight()/10,"Primary background changes visible pixels");
                require(unchanged>a.getWidth()*a.getHeight()/10,"Surface/reflection/transmission pixels remain unchanged");
                require(surfaceError==0,"Background-only edits preserve interior lighting exactly: "+surfaceError);
                var changedEnvironment=EnvironmentState.neutral();r.submit(RtxptContinuityNativeSelfTest.frame(seq++,1,changedEnvironment));RtxptLightingNativeSelfTest.settle(h,8);var dark=out.resolve(route+"-dark.png");h.capture(dark);
                var c=ImageIO.read(dark.toFile());require(c.getRGB(5,5)==b.getRGB(5,5),"Environment lighting does not change explicit primary background");
                require(RtxptLightingNativeSelfTest.difference(blue,dark)>.1,"Environment lighting still affects scene");
                r.submit(RenderFrameRequest.builder(seq++,800,450,RtxptAcceptanceFixture.camera()).minimumSceneRevision(1).environment(e).build());
                ((RtxptRenderer)r).windowState(1);h.renderFrame();((RtxptRenderer)r).windowState(0);h.renderFrame();
                System.out.println("BACKGROUND_PASS "+route+" changed="+changed+" identical="+unchanged+" surfaceAbsoluteError="+surfaceError);
            }
        }
    }
}

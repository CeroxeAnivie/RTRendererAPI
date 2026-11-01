package top.ceroxe.rt.renderer.luxcore;

import java.nio.*;
import java.time.Duration;
import top.ceroxe.rt.renderer.api.*;

/** Actual DLL/session/film/edit/resize/close regression. */
public final class LuxCoreNativeSelfTest {
    public static void main(String[] args) throws Exception {
        var config=RendererConfig.recommended();
        try(var renderer=Renderer.open("luxcore",config)) {
            var nativeAccess=renderer.extension(LuxCoreAccess.class).orElseThrow();
            renderer.apply(SceneTransaction.builder(1).resetScene().upsert(MaterialAsset.opaque(1,0xffa0b0c0))
                .upsert(MeshAsset.triangles(1,new float[]{-4,-3,0,4,-3,0,0,4,0},new int[]{0,1,2},1))
                .upsert(SceneInstance.builder(1,1).build()).build());
            var env=EnvironmentState.builder().skyRadiance(1,1,1).ambientIntensity(1).sunIntensity(0)
                .cameraMedium(new Medium(new Rgb(.01f,.02f,.03f),new Rgb(.02f,.02f,.02f),Rgb.ZERO,1,1,0)).build();
            var camera=CameraState.lookAt(0,0,5,0,0,0).build();
            renderer.submit(RenderFrameRequest.builder(0,96,64,camera).environment(env).build());
            var frame=renderer.awaitFrame(Duration.ofSeconds(45)).orElseThrow();
            LuxCoreAdapterSelfTest.require(frame.width()==96&&frame.height()==64,"film extent");
            var raw=ByteBuffer.allocateDirect(96*64*16).order(ByteOrder.nativeOrder());
            double samples=nativeAccess.readLinearRgba(raw);double sum=0;
            for(int p=0;p<96*64;p++)for(int c=0;c<3;c++){float v=raw.getFloat(p*16+c*4);LuxCoreAdapterSelfTest.require(Float.isFinite(v)&&v>=0,"finite nonnegative radiance");sum+=v;}
            LuxCoreAdapterSelfTest.require(samples>0&&sum>0,"upstream produced real samples and lit pixels");
            System.out.println("LuxCore first film validated: samples="+samples+" radianceSum="+sum);
            nativeAccess.sceneProperties("scene.lights.sky.gain = 0.5 0.5 0.5\n");
            renderer.submit(RenderFrameRequest.builder(1,80,60,camera).environment(env).build());
            frame=renderer.awaitFrame(Duration.ofSeconds(30)).orElseThrow();LuxCoreAdapterSelfTest.require(frame.width()==80,"native resize");
            System.out.println(nativeAccess.nativeStatistics());System.out.println("LuxCoreNativeSelfTest PASS samples="+samples+" radianceSum="+sum+" timings="+nativeAccess.bridgeTimings());
        }
    }
}

package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import top.ceroxe.rt.renderer.api.*;

/** Proves public surface depth and sampling reach the GPU by comparing completed images. */
public final class VulkanSurfaceBudgetNativeSelfTest {
    private static final int WIDTH=160,HEIGHT=120;
    private VulkanSurfaceBudgetNativeSelfTest() { }

    public static void main(String[] args) throws Exception {
        Path output=Path.of("build/reports/vulkan-surface-budgets");Files.createDirectories(output);
        byte[] primary=render(new SurfaceTransportOptions(1,1),output.resolve("depth-1.png"));
        byte[] continued=render(new SurfaceTransportOptions(8,1),output.resolve("depth-8.png"));
        byte[] sampled=render(new SurfaceTransportOptions(8,4),output.resolve("samples-4.png"));
        long primaryEnergy=energy(primary),continuedEnergy=energy(continued);
        if(continuedEnergy<=primaryEnergy+1000)throw new AssertionError("Surface depth did not change transmitted light: "+primaryEnergy+" -> "+continuedEnergy);
        if(Arrays.equals(continued,sampled))throw new AssertionError("Rough surface sampling did not change the completed GPU output");
        int changed=0;for(int i=0;i<continued.length;i++)if(continued[i]!=sampled[i])changed++;
        String result="PASS depth1_energy="+primaryEnergy+", depth8_energy="+continuedEnergy+", sample1_to4_changed_bytes="+changed;
        Files.writeString(output.resolve("result.txt"),result);System.out.println("VULKAN_SURFACE_BUDGET "+result);
    }

    private static byte[] render(SurfaceTransportOptions budget,Path png) throws Exception {
        var config=RendererConfig.builder().surfaceTransport(budget)
            .temporalRendering(TemporalRenderingOptions.disabled()).cpuFrameReadbackEnabled(true).build();
        try(var renderer=Renderer.open("vulkan-rt",config)) {
            var tx=SceneTransaction.builder(0).resetScene()
                .upsert(MaterialAsset.builder(1).baseColorRgba8(0xffffffff).transmission(1).indexOfRefraction(1.5f)
                    .roughness(.3f).doubleSided(true).blendMode(MaterialAsset.BlendMode.TRANSLUCENT).build())
                .upsert(MaterialAsset.builder(2).baseColorRgba8(0xff2020ff).emissive(0xff2020ff,4).build())
                .upsert(MaterialAsset.builder(3).baseColorRgba8(0xff20ff20).emissive(0xff20ff20,4).build())
                .upsert(quad(1,1,-4,4,0)).upsert(quad(2,2,-6,0,-2)).upsert(quad(3,3,0,6,-2))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).upsert(SceneInstance.builder(3,3).build()).build();
            renderer.apply(tx);
            var camera=CameraState.lookAt(0,0,4,0,0,0).verticalFieldOfViewDegrees(55).aspectRatio((double)WIDTH/HEIGHT).build();
            var request=RenderFrameRequest.builder(0,WIDTH,HEIGHT,camera).minimumSceneRevision(0)
                .environment(EnvironmentState.builder().skyRadiance(0,0,0).ambientIntensity(0).sunIntensity(0).build())
                .antiAliasing(AntiAliasingState.disabled()).build();
            long deadline=System.nanoTime()+Duration.ofMinutes(3).toNanos();
            while(renderer.trySubmit(request) instanceof Renderer.FrameSubmissionDeferred) {
                if(System.nanoTime()>deadline)throw new AssertionError("Scene convergence did not complete");
                Thread.sleep(1);
            }
            var frame=renderer.awaitFrame(Duration.ofSeconds(45)).orElseThrow();
            byte[] bytes=new byte[frame.byteCount()];frame.pixels().get(bytes);
            var image=new BufferedImage(WIDTH,HEIGHT,BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<HEIGHT;y++)for(int x=0;x<WIDTH;x++) {
                int i=(y*WIDTH+x)*4;
                image.setRGB(x,y,0xff000000|((bytes[i]&255)<<16)|((bytes[i+1]&255)<<8)|(bytes[i+2]&255));
            }
            ImageIO.write(image,"png",png.toFile());return bytes;
        }
    }

    private static MeshAsset quad(long id,long material,float left,float right,float z) {
        return MeshAsset.builder(id,new float[]{left,-4,z,right,-4,z,right,4,z,left,4,z},
            new int[]{0,1,2,0,2,3},new long[]{material,material})
            .normals(new float[]{0,0,1,0,0,1,0,0,1,0,0,1}).build();
    }
    private static long energy(byte[] pixels) {
        long total=0;for(int i=0;i<pixels.length;i++)if(i%4!=3)total+=pixels[i]&255;return total;
    }
}

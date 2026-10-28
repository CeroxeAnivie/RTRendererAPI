package demo;

import java.awt.image.BufferedImage;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import javax.imageio.ImageIO;
import top.ceroxe.rt.renderer.api.*;

/** Explicit GPU gate: feature ablations, finite HDR pixels, persistent/transient parity and images. */
public final class MediumGalleryNativeSelfTest {
    private static final int WIDTH=160,HEIGHT=90,FRAMES=32;
    private static final Path OUTPUT=Path.of("build/reports/medium-gallery").toAbsolutePath();
    public static void main(String[] arguments) throws Exception {
        Files.createDirectories(OUTPUT);
        var variants=new LinkedHashMap<String,String[]>();
        variants.put("all",new String[0]);
        variants.put("no-absorption",new String[]{"--absorption=false"});
        variants.put("no-scattering",new String[]{"--scattering=false"});
        variants.put("uniform-density",new String[]{"--heterogeneous=false"});
        variants.put("single-scattering",new String[]{"--bounces=0"});
        variants.put("no-emission",new String[]{"--emission=false"});
        variants.put("no-refraction",new String[]{"--refraction=false"});
        variants.put("no-caustics",new String[]{"--caustics=false"});
        variants.put("persistent",new String[]{"--transient=false"});
        variants.put("inward",new String[]{"--inward=true"});
        variants.put("inside",new String[]{"--inside=true"});
        var results=new LinkedHashMap<String,float[]>();
        for (var variant:variants.entrySet()) {
            var args=new ArrayList<>(List.of("--width="+WIDTH,"--height="+HEIGHT,"--fg=false","--animate=false",
                    "--validation=true","--photons=65536","--capture="+OUTPUT.resolve(variant.getKey()+".png")));
            args.addAll(Arrays.asList(variant.getValue()));
            var options=MediumGalleryDemo.Options.parse(args.toArray(String[]::new));
            var scene=new MediumGalleryScene(options);float[] average=new float[WIDTH*HEIGHT*3];
            var config=MediumGalleryDemo.configuration(options).copyBuilder()
                    .temporalRendering(TemporalRenderingOptions.disabled())
                    .rayTracingOptimizations(RayTracingOptimizationOptions.disabled()).build();
            long started=System.nanoTime();
            try (var renderer=Renderer.open(config)) {
                renderer.apply(scene.transaction());
                for (int frame=0;frame<FRAMES;frame++) {
                    var request=scene.frame(frame,WIDTH,HEIGHT,0,0);
                    long deadline=System.nanoTime()+Duration.ofSeconds(120).toNanos();
                    while (!(renderer.trySubmit(request) instanceof Renderer.FrameSubmitted)) {
                        if (System.nanoTime()>deadline) throw new AssertionError("admission timed out: "+variant.getKey());
                        LockSupport.parkNanos(1_000_000);
                    }
                    var output=renderer.awaitFrame(Duration.ofSeconds(120)).orElseThrow(()->new AssertionError("GPU frame timed out"));
                    var bytes=output.pixels().order(ByteOrder.LITTLE_ENDIAN);
                    require(output.format()==TextureFormat.RGBA16_FLOAT,"linear HDR output");
                    for (int y=0;y<HEIGHT;y++) for (int x=0;x<WIDTH;x++) for (int c=0;c<3;c++) {
                        float value=Float.float16ToFloat(bytes.getShort(y*output.rowBytes()+x*8+c*2));
                        require(Float.isFinite(value) && value>=0,"finite nonnegative HDR: "+variant.getKey());
                        average[(y*WIDTH+x)*3+c]+=value/FRAMES;
                    }
                }
                System.out.println("MediumGalleryNative variant="+variant.getKey()+" diagnostics="+renderer.diagnostics());
            }
            results.put(variant.getKey(),average);save(options.capture(),average);
            System.out.printf(Locale.ROOT,"MediumGalleryNative variant=%s frames=%d admissionReadbackWallMs=%.3f (not FPS)%n",
                    variant.getKey(),FRAMES,(System.nanoTime()-started)/1e6);
        }
        var reference=results.get("all");double energy=Arrays.stream(toDouble(reference)).sum()/reference.length;
        require(energy>1e-4,"gallery contains measurable lighting");
        for (var variant:results.entrySet()) {
            if (variant.getKey().equals("all")) continue;
            double difference=0;
            for (int i=0;i<reference.length;i++) difference+=Math.abs(reference[i]-variant.getValue()[i]);
            double relative=difference/(reference.length*energy);
            System.out.printf(Locale.ROOT,"MediumGalleryNative imageMeanAbsoluteRelativeDifference %s=%.8f%n",variant.getKey(),relative);
            if (variant.getKey().equals("persistent") || variant.getKey().equals("inward"))
                require(relative<.08,"equivalent boundary representations agree within the finite Monte Carlo budget: "+variant.getKey());
            else require(relative>1e-5,"feature ablation must alter the image: "+variant.getKey());
        }
        System.out.println("MediumGalleryNativeSelfTest passed; images="+OUTPUT
                +"; these small-scene checks do not certify convergence, 2K throughput or FG presentation");
    }
    private static double[] toDouble(float[] values) {double[] result=new double[values.length];for(int i=0;i<values.length;i++)result[i]=values[i];return result;}
    private static void save(Path path,float[] values) throws Exception {
        var image=new BufferedImage(WIDTH,HEIGHT,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<HEIGHT;y++)for(int x=0;x<WIDTH;x++) {
            int at=(y*WIDTH+x)*3;
            image.setRGB(x,y,display(values[at])<<16|display(values[at+1])<<8|display(values[at+2]));
        }
        ImageIO.write(image,"PNG",path.toFile());
    }
    private static int display(float value) {double x=Math.max(0,value),mapped=(x*(2.51*x+.03))/(x*(2.43*x+.59)+.14);return(int)Math.round(Math.pow(Math.clamp(mapped,0,1),1/2.2)*255);}
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}

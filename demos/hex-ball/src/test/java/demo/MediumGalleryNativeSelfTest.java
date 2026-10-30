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
    private static final int WIDTH=160,HEIGHT=90,FRAMES=8;
    private static final Path OUTPUT=Path.of("build/reports/medium-gallery").toAbsolutePath();
    public static void main(String[] arguments) throws Exception {
        Files.createDirectories(OUTPUT);
        String onlyPreset=System.getProperty("top.ceroxe.rt.gallery.onlyPreset");
        var variants=new LinkedHashMap<String,String[]>();
        for(var preset:MediumGalleryScene.Preset.values()) {
            if(onlyPreset!=null&&!preset.id.equals(onlyPreset))continue;
            variants.put(preset.id,new String[]{"--scene="+preset.id});
            if(preset.caustic())variants.put(preset.id+"-off",new String[]{"--scene="+preset.id,"--caustics=false"});
        }
        require(!variants.isEmpty(),"unknown gallery preset: "+onlyPreset);
        var results=new LinkedHashMap<String,float[]>();
        for (var variant:variants.entrySet()) {
            var args=new ArrayList<>(List.of("--width="+WIDTH,"--height="+HEIGHT,"--fg=false","--animate=false",
                    "--validation=true","--photons=65536","--capture="+OUTPUT.resolve(variant.getKey()+".png")));
            args.addAll(Arrays.asList(variant.getValue()));
            var options=MediumGalleryDemo.Options.parse(args.toArray(String[]::new));
            var scene=new MediumGalleryScene(options);float[] average=new float[WIDTH*HEIGHT*3];
            // Exercise the same feature policy as the actual FPS window. In
            // particular, do not mask a default SER/medium admission failure.
            var config=MediumGalleryDemo.configuration(options);
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
        for (var variant:results.entrySet()) {
            double energy=Arrays.stream(toDouble(variant.getValue())).sum()/variant.getValue().length;
            require(energy>1e-5,"preset contains measurable lighting: "+variant.getKey());
        }
        if(onlyPreset==null) {
            difference(results,"pbr-direct-uniform","pbr-direct-density");
            difference(results,"pbr-direct-uniform","pbr-multiple-uniform");
            difference(results,"pbr-direct-density","pbr-multiple-density");
        }
        for(var preset:MediumGalleryScene.Preset.values())if(preset.caustic()&&results.containsKey(preset.id))difference(results,preset.id,preset.id+"-off");
        Files.writeString(OUTPUT.resolve(onlyPreset==null?"result.json":"result-"+onlyPreset+".json"),"{\"passed\":true,\"presets\":"+variants.size()
                +",\"framesPerPreset\":"+FRAMES+",\"nativeFrames\":"+(variants.size()*FRAMES)+"}\n");
        System.out.println("介质展厅 GPU 验收通过；图像目录="+OUTPUT);
    }
    private static void difference(Map<String,float[]> results,String first,String second) {
        float[] a=results.get(first),b=results.get(second);double difference=0,energy=0;
        for(int i=0;i<a.length;i++){difference+=Math.abs(a[i]-b[i]);energy+=a[i];}
        double relative=difference/Math.max(energy,1e-12);
        require(relative>1e-5,"preset control must change the image: "+first+" / "+second);
        System.out.printf(Locale.ROOT,"展厅图像差异 %s / %s = %.8f%n",first,second,relative);
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

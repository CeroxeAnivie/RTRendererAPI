package demo;

import java.nio.*;
import java.nio.file.*;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.luxcore.LuxCoreAccess;

/** Every retained fixture uses the native physical migration, with real finite Film samples. */
public final class LuxCoreMediumMatrixSelfTest {
    public static void main(String[] args) throws Exception {
        int width=160,height=90,passed=0;long sequence=0;var data=ByteBuffer.allocateDirect(width*height*16).order(ByteOrder.nativeOrder());
        var cases=MediumPerformanceCases.all();
        try(var renderer=Renderer.open("luxcore",RendererConfig.recommended())) {
            var access=renderer.extension(LuxCoreAccess.class).orElseThrow();
            for(var scene:cases) {
                renderer.apply(NativeMediumScenes.scene(scene.transaction()));
                // All factories receive the global sequence to preserve renderer admission order.
                renderer.submit(NativeMediumScenes.frame(scene.frame(sequence++,width,height)));
                LuxCoreMediumBenchmark.waitSamples(access,0,30);double samples=access.readLinearRgba(data);double energy=0;
                for(int i=0;i<width*height;i++)for(int c=0;c<3;c++) {
                    float value=data.getFloat(i*16+c*4);if(!Float.isFinite(value)||value<0)throw new AssertionError(scene.id()+" invalid radiance "+value);energy+=value;
                }
                if(samples<=0||energy<=0)throw new AssertionError(scene.id()+" produced no real lit samples");
                System.out.println("LuxCoreMatrix PASS "+scene.id()+" samples="+samples);passed++;
            }
        }
        var output=Path.of("build/reports/luxcore-medium/matrix.json");Files.createDirectories(output.getParent());
        Files.writeString(output,"{\"passed\":"+passed+",\"total\":"+cases.size()+",\"scope\":\"native physical migrations, not legacy semantic parity\",\"nativeSemanticChanges\":"+NativeMediumScenes.JSON_CHANGES+"}\n");
    }
}

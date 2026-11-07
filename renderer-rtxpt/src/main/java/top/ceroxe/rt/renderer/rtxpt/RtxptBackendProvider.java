package top.ceroxe.rt.renderer.rtxpt;
import java.nio.file.Files;
import java.util.Objects;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.spi.RendererBackendProvider;

/** Provider for the original RTXPT execution chain and external API adapters. */
public final class RtxptBackendProvider implements RendererBackendProvider {
    @Override public Descriptor descriptor(){return Descriptor.builder("rtxpt").priority(-100).build();}
    @Override public ProbeResult probe(RendererConfig config){
        if(!System.getProperty("os.name","").startsWith("Windows"))return ProbeResult.unsupported("RTXPT managed D3D12 requires Windows");
        Objects.requireNonNull(config,"config");
        try {
            if(!Files.isRegularFile(RtxptNative.bridge()))return ProbeResult.unsupported("Build RTXPT JNI host first");
            return ProbeResult.compatible("NVIDIA RTXPT 1.9.0 in-process D3D12 managed rendering");
        }catch(IllegalStateException e){return ProbeResult.unsupported(e.getMessage());}
    }
    @Override public Renderer open(RendererConfig config){
        var p=probe(config); if(p.compatibility()!=Compatibility.COMPATIBLE)throw new IllegalStateException(p.reason());
        return new RtxptRenderer(config);
    }
}

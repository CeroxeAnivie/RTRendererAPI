package top.ceroxe.rt.renderer.rtxpt;
import java.nio.file.Files;
import java.util.List;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.spi.RendererBackendProvider;

/** Explicit opt-in: the ordinary Vulkan provider remains the default. */
public final class RtxptBackendProvider implements RendererBackendProvider {
    @Override public Descriptor descriptor(){return Descriptor.builder("rtxpt").priority(-100).build();}
    @Override public List<RendererGpuDevice> availableGpuDevices(){return List.of();}
    @Override public ProbeResult probe(RendererConfig config){
        if(!System.getProperty("os.name","").startsWith("Windows"))return ProbeResult.unsupported("RTXPT managed D3D12 requires Windows");
        try {
            if(!Files.isRegularFile(RtxptNative.bridge()))return ProbeResult.unsupported("Build RTXPT JNI host first");
            if(config.frameGeneration().preference()==RendererFeaturePreference.REQUIRED)
                return ProbeResult.unsupported("Frame generation is not exposed by the managed host");
            if(config.cpuFrameReadbackEnabled())return ProbeResult.unsupported("Managed presentation is GPU-native; CPU readback is not exposed");
            return ProbeResult.compatible("NVIDIA RTXPT 1.9.0 in-process D3D12 managed rendering");
        }catch(IllegalStateException e){return ProbeResult.unsupported(e.getMessage());}
    }
    @Override public Renderer open(RendererConfig config){
        var p=probe(config); if(p.compatibility()!=Compatibility.COMPATIBLE)throw new IllegalStateException(p.reason());
        return new RtxptRenderer(config);
    }
}

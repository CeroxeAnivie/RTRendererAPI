package top.ceroxe.rt.renderer.luxcore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.spi.RendererBackendProvider;

/** Explicit opt-in; probing never loads LuxCore or creates a GPU context. */
public final class LuxCoreBackendProvider implements RendererBackendProvider {
    @Override public Descriptor descriptor() { return Descriptor.builder("luxcore").priority(-100).build(); }
    @Override public List<RendererGpuDevice> availableGpuDevices() { return List.of(); }
    @Override public ProbeResult probe(RendererConfig config) {
        try {
            LuxCoreNative.sdk();
            if (!Files.isRegularFile(Path.of(System.getProperty("top.ceroxe.rt.luxcore.bridge",
                    "renderer-luxcore/build/native/rtrenderer_luxcore.dll"))))
                return ProbeResult.unsupported("Build renderer-luxcore JNI bridge first");
            if (config.frameGeneration().preference() == RendererFeaturePreference.REQUIRED
                    || config.frameReconstruction().preference() == RendererFeaturePreference.REQUIRED)
                return ProbeResult.unsupported("LuxCore owns rendering; Vulkan FG/reconstruction are unavailable");
            return ProbeResult.compatible("LuxCore 2.11.2 managed physical rendering");
        } catch (IllegalStateException e) { return ProbeResult.unsupported(e.getMessage()); }
    }
    @Override public Renderer open(RendererConfig config) { return new LuxCoreRenderer(config); }
}

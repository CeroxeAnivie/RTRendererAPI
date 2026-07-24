package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import top.ceroxe.mcvulkanrt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.mcvulkanrt.renderer.RendererRtDiagnostics;
import top.ceroxe.mcvulkanrt.renderer.api.RayTracingRenderer;
import top.ceroxe.mcvulkanrt.renderer.api.RayTracingRendererConfig;
import top.ceroxe.mcvulkanrt.renderer.spi.RayTracingBackendProvider;

import java.util.Objects;

/** Service-provider entry point for the standalone hardware Vulkan ray tracing backend. */
public final class VulkanRayTracingBackendProvider implements RayTracingBackendProvider {
    private static final Descriptor DESCRIPTOR = new Descriptor("vulkan-rt", 1_000, API_MAJOR, 0);

    @Override
    public Descriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ProbeResult probe(RayTracingRendererConfig configuration) {
        Objects.requireNonNull(configuration, "configuration");
        VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
        if (!capability.hardwareRayTracingReady()) {
            return ProbeResult.unsupported(capability.summary());
        }
        return ProbeResult.compatible(
                "hardware Vulkan RT ready on " + capability.preferredDevice().name()
        );
    }

    @Override
    public RayTracingRenderer open(RayTracingRendererConfig configuration) {
        RayTracingRendererConfig checked = Objects.requireNonNull(configuration, "configuration");
        VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
        if (!capability.hardwareRayTracingReady()) {
            throw new IllegalStateException("hardware Vulkan RT is unavailable: " + capability.summary());
        }
        VulkanGpuSceneRenderingSession session = VulkanGpuSceneRenderingSession.open(
                capability, checked, RendererRtDiagnostics.noop()
        );
        return new VulkanRendererHost(checked, session);
    }
}

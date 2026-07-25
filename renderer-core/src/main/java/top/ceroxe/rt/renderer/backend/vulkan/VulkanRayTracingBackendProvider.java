package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.RayTracingRenderer;
import top.ceroxe.rt.renderer.api.RayTracingRendererConfig;
import top.ceroxe.rt.renderer.api.RayTracingGpuDevice;
import top.ceroxe.rt.renderer.api.FrameOutputFormat;
import top.ceroxe.rt.renderer.spi.RayTracingBackendProvider;
import org.lwjgl.vulkan.VK10;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/** Service-provider entry point for the standalone hardware Vulkan ray tracing backend. */
public final class VulkanRayTracingBackendProvider implements RayTracingBackendProvider {
    private static final Descriptor DESCRIPTOR = Descriptor.builder("vulkan-rt")
            .priority(1_000)
            .apiMajor(API_MAJOR)
            .apiMinor(0)
            .build();

    /** Creates the stateless Vulkan RT service provider discovered by {@link java.util.ServiceLoader}. */
    public VulkanRayTracingBackendProvider() {
    }

    @Override
    public Descriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public List<RayTracingGpuDevice> availableGpuDevices() {
        return publicDevices(VulkanRtCapabilityProbe.capture());
    }

    @Override
    public ProbeResult probe(RayTracingRendererConfig configuration) {
        Objects.requireNonNull(configuration, "configuration");
        VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
        capability = selectConfiguredDevice(capability, configuration);
        if (!capability.hardwareRayTracingReady()) {
            return ProbeResult.unsupported(capability.summary());
        }
        VulkanRtCapabilityProbe.DeviceReport device = capability.preferredDevice();
        if (!publicFrameApiReady(device, configuration.frameOutputFormat())) {
            return ProbeResult.unsupported(
                    "hardware Vulkan RT is present but external frame memory is unavailable on "
                            + device.name()
            );
        }
        return ProbeResult.compatible(
                "hardware Vulkan RT and external frame memory ready on " + device.name()
        );
    }

    @Override
    public RayTracingRenderer open(RayTracingRendererConfig configuration) {
        RayTracingRendererConfig checked = Objects.requireNonNull(configuration, "configuration");
        return new VulkanRendererHost(checked, () -> openSession(checked));
    }

    private static VulkanRenderingSession openSession(RayTracingRendererConfig configuration) {
        VulkanRtCapabilityProbe.Result capability = selectConfiguredDevice(
                VulkanRtCapabilityProbe.capture(), configuration);
        if (!capability.hardwareRayTracingReady()) {
            throw new IllegalStateException("hardware Vulkan RT is unavailable: " + capability.summary());
        }
        if (!publicFrameApiReady(capability.preferredDevice(), configuration.frameOutputFormat())) {
            throw new IllegalStateException(
                    "selected Vulkan device cannot expose public GPU frames: " + capability.summary()
            );
        }
        return VulkanGpuSceneRenderingSession.open(capability, configuration, RendererRtDiagnostics.noop());
    }

    private static VulkanRtCapabilityProbe.Result selectConfiguredDevice(
            VulkanRtCapabilityProbe.Result capability,
            RayTracingRendererConfig configuration
    ) {
        RayTracingGpuDevice selected = configuration.gpuDevice().orElse(null);
        if (selected == null) return capability;
        if (!DESCRIPTOR.id().equals(selected.backendId())) {
            throw new IllegalArgumentException("selected GPU belongs to backend " + selected.backendId());
        }
        return capability.select(selected.stableId());
    }

    private static List<RayTracingGpuDevice> publicDevices(VulkanRtCapabilityProbe.Result capability) {
        if (capability.failed()) return List.of();
        return capability.devices().stream()
                .filter(VulkanRayTracingBackendProvider::publicFrameApiReady)
                .map(VulkanRayTracingBackendProvider::toPublicDevice)
                .toList();
    }

    static boolean publicFrameApiReady(VulkanRtCapabilityProbe.DeviceReport device) {
        return publicFrameApiReady(device, FrameOutputFormat.SDR_RGBA8);
    }

    static boolean publicFrameApiReady(
            VulkanRtCapabilityProbe.DeviceReport device,
            FrameOutputFormat outputFormat
    ) {
        VulkanRtCapabilityProbe.DeviceReport checked = Objects.requireNonNull(device, "device");
        return checked.hardwareRayTracingReady()
                && checked.externalMemory()
                && switch (Objects.requireNonNull(outputFormat, "outputFormat")) {
                    case SDR_RGBA8 -> checked.sdrRgba8Output();
                    case LINEAR_HDR_RGBA16F -> checked.linearHdrRgba16fOutput();
                };
    }

    static RayTracingGpuDevice toPublicDevice(VulkanRtCapabilityProbe.DeviceReport device) {
        EnumSet<RayTracingGpuDevice.Capability> capabilities = EnumSet.of(
                RayTracingGpuDevice.Capability.HARDWARE_RAY_TRACING,
                RayTracingGpuDevice.Capability.ACCELERATION_STRUCTURE,
                RayTracingGpuDevice.Capability.RAY_TRACING_PIPELINE,
                RayTracingGpuDevice.Capability.BUFFER_DEVICE_ADDRESS,
                RayTracingGpuDevice.Capability.SHADER_INT64
        );
        if (device.externalMemory()) capabilities.add(RayTracingGpuDevice.Capability.EXTERNAL_MEMORY);
        if (device.externalSemaphore()) capabilities.add(RayTracingGpuDevice.Capability.EXTERNAL_SEMAPHORE);
        if (device.sdrRgba8Output()) capabilities.add(RayTracingGpuDevice.Capability.NATIVE_SDR_RGBA8);
        if (device.linearHdrRgba16fOutput()) {
            capabilities.add(RayTracingGpuDevice.Capability.NATIVE_LINEAR_HDR_RGBA16F);
        }
        if (device.memoryBudget()) capabilities.add(RayTracingGpuDevice.Capability.MEMORY_BUDGET);
        if (device.gpuTimestamps()) capabilities.add(RayTracingGpuDevice.Capability.GPU_TIMESTAMPS);
        RayTracingGpuDevice.RayTracingLimits limits = RayTracingGpuDevice.RayTracingLimits.builder()
                .maxRayRecursionDepth(device.maxRayRecursionDepth())
                .shaderGroupHandleSize(device.shaderGroupHandleSize())
                .shaderGroupHandleAlignment(device.shaderGroupHandleAlignment())
                .shaderGroupBaseAlignment(device.shaderGroupBaseAlignment())
                .maxShaderGroupStride(device.maxShaderGroupStride())
                .maxRayDispatchInvocationCount(device.maxRayDispatchInvocationCount())
                .minAccelerationStructureScratchAlignment(
                        device.minAccelerationStructureScratchAlignment()
                )
                .build();
        return RayTracingGpuDevice.builder()
                .backendId(DESCRIPTOR.id())
                .stableId(device.stableId())
                .name(device.name())
                .vendorId(device.vendorId())
                .deviceId(device.deviceId())
                .type(switch (device.deviceType()) {
                    case VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> RayTracingGpuDevice.Type.DISCRETE;
                    case VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> RayTracingGpuDevice.Type.INTEGRATED;
                    case VK10.VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> RayTracingGpuDevice.Type.VIRTUAL;
                    default -> RayTracingGpuDevice.Type.OTHER;
                })
                .apiVersion(new RayTracingGpuDevice.ApiVersion(
                        VK10.VK_VERSION_MAJOR(device.apiVersion()),
                        VK10.VK_VERSION_MINOR(device.apiVersion()),
                        VK10.VK_VERSION_PATCH(device.apiVersion())
                ))
                .deviceLocalMemoryBytes(device.deviceLocalMemoryBytes())
                .capabilities(capabilities)
                .rayTracingLimits(limits)
                .build();
    }
}

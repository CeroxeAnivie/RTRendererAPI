package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.RayTracingRenderer;
import top.ceroxe.rt.renderer.api.RayTracingRendererConfig;
import top.ceroxe.rt.renderer.api.RayTracingGpuDevice;
import top.ceroxe.rt.renderer.api.HardwareCapabilities;
import top.ceroxe.rt.renderer.api.FrameOutputFormat;
import top.ceroxe.rt.renderer.spi.RayTracingBackendProvider;
import org.lwjgl.vulkan.VK10;

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
        VulkanRtCapabilityProbe.Result selectedCapability = capability.select(selected.stableId());
        RayTracingGpuDevice authoritative = toPublicDevice(selectedCapability.preferredDevice());
        validateSelectedSnapshot(authoritative, selected);
        return selectedCapability;
    }

    static void validateSelectedSnapshot(
            RayTracingGpuDevice authoritative,
            RayTracingGpuDevice selected
    ) {
        RayTracingGpuDevice fresh = Objects.requireNonNull(authoritative, "authoritative");
        RayTracingGpuDevice supplied = Objects.requireNonNull(selected, "selected");
        if (!fresh.equals(supplied)) {
            throw new IllegalArgumentException(
                    "selected GPU snapshot is stale or does not match the current hardware probe: "
                            + supplied.backendId() + '/' + supplied.stableId()
            );
        }
    }

    private static List<RayTracingGpuDevice> publicDevices(VulkanRtCapabilityProbe.Result capability) {
        if (capability.failed()) return List.of();
        return capability.devices().stream()
                .filter(VulkanRtCapabilityProbe.DeviceReport::hardwareRayTracingReady)
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
        HardwareCapabilities.RayTracingLimits limits = HardwareCapabilities.RayTracingLimits.builder()
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
        HardwareCapabilities hardware = HardwareCapabilities.builder()
                .probeState(HardwareCapabilities.ProbeState.COMPLETE)
                .feature(
                        HardwareCapabilities.Feature.HARDWARE_RAY_TRACING,
                        support(device.hardwareRayTracingReady(),
                                "Vulkan RT extensions, features, and limits were queried")
                )
                .feature(
                        HardwareCapabilities.Feature.ACCELERATION_STRUCTURE,
                        support(device.accelerationStructure() && device.accelerationStructureFeature(),
                                "VK_KHR_acceleration_structure extension and feature were queried")
                )
                .feature(
                        HardwareCapabilities.Feature.RAY_TRACING_PIPELINE,
                        support(device.rayTracingPipeline() && device.rayTracingPipelineFeature(),
                                "VK_KHR_ray_tracing_pipeline extension and feature were queried")
                )
                .feature(
                        HardwareCapabilities.Feature.BUFFER_DEVICE_ADDRESS,
                        support(device.bufferDeviceAddress() && device.bufferDeviceAddressFeature(),
                                "bufferDeviceAddress API support and feature were queried")
                )
                .feature(
                        HardwareCapabilities.Feature.SHADER_INT64,
                        support(device.shaderInt64Feature(), "shaderInt64 feature was queried")
                )
                .feature(
                        HardwareCapabilities.Feature.EXTERNAL_MEMORY,
                        support(device.externalMemory(),
                                "OPAQUE_WIN32 storage-image memory import and export were queried per output format")
                )
                .feature(
                        HardwareCapabilities.Feature.EXTERNAL_SEMAPHORE,
                        support(device.externalSemaphore(),
                                "OPAQUE_WIN32 binary semaphore export and import were queried")
                )
                .feature(
                        HardwareCapabilities.Feature.MEMORY_BUDGET,
                        support(device.memoryBudget(), "VK_EXT_memory_budget availability was queried")
                )
                .feature(
                        HardwareCapabilities.Feature.GPU_TIMESTAMPS,
                        support(device.gpuTimestamps(), "selected queue-family timestamp bits were queried")
                )
                .deviceLocalMemoryBytes(device.deviceLocalMemoryBytes())
                .maxImageDimension2D(device.maxImageDimension2D())
                .rayTracingLimits(limits)
                .frameInterop(
                        FrameOutputFormat.SDR_RGBA8,
                        HardwareCapabilities.ExternalHandleType.OPAQUE_WIN32,
                        frameInterop(
                                device.sdrRgba8Output(), device.sdrRgba8Import(),
                                device.sdrRgba8DedicatedOnly(), device.externalSemaphore()
                        )
                )
                .frameInterop(
                        FrameOutputFormat.LINEAR_HDR_RGBA16F,
                        HardwareCapabilities.ExternalHandleType.OPAQUE_WIN32,
                        frameInterop(
                                device.linearHdrRgba16fOutput(), device.linearHdrRgba16fImport(),
                                device.linearHdrRgba16fDedicatedOnly(), device.externalSemaphore()
                        )
                )
                .reason("complete Vulkan physical-device capability probe")
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
                .hardwareCapabilities(hardware)
                .build();
    }

    private static HardwareCapabilities.Support support(boolean supported, String evidence) {
        return supported
                ? HardwareCapabilities.Support.supported(evidence)
                : HardwareCapabilities.Support.unsupported(evidence + "; required support was absent");
    }

    private static HardwareCapabilities.FrameInteropSupport frameInterop(
            boolean memoryExport,
            boolean memoryImport,
            boolean dedicatedOnly,
            boolean semaphoreBidirectional
    ) {
        HardwareCapabilities.Support memoryExportSupport = support(
                memoryExport, "OPAQUE_WIN32 storage-image memory export was queried"
        );
        HardwareCapabilities.Support memoryImportSupport = support(
                memoryImport, "OPAQUE_WIN32 storage-image memory import was queried"
        );
        HardwareCapabilities.Support semaphoreSupport = support(
                semaphoreBidirectional, "OPAQUE_WIN32 binary semaphore export and import were queried"
        );
        return new HardwareCapabilities.FrameInteropSupport(
                memoryExportSupport,
                memoryImportSupport,
                semaphoreSupport,
                semaphoreSupport,
                memoryExport || memoryImport
                        ? dedicatedOnly
                                ? HardwareCapabilities.DedicatedAllocation.REQUIRED
                                : HardwareCapabilities.DedicatedAllocation.NOT_REQUIRED
                        : HardwareCapabilities.DedicatedAllocation.UNKNOWN
        );
    }
}

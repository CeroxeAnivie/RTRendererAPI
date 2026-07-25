package top.ceroxe.rt.renderer.rt.device;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.rt.acceleration.*;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.rt.renderer.rt.pipeline.RtRayTracingPipeline;
import top.ceroxe.rt.renderer.rt.pipeline.RtRayTracingPipelineProperties;

import java.util.List;
import java.util.Objects;

/**
 * Constructs the strongly ordered native resource graph consumed by the streaming RT
 * orchestrator. Resource ownership remains exclusively in the supplied scope; this immutable
 * bundle only removes device creation and teardown wiring from frame scheduling policy.
 */
record VulkanRtBackendResources(
        VkDevice device,
        RtDeviceQueueContexts queueContexts,
        long allocator,
        RtGpuBuffer bootstrapBuffer,
        RtAccelerationStructure bootstrapBlas,
        RtAccelerationStructure bootstrapTlas,
        RtSceneMaterialTable sceneMaterialTable,
        RtSectionBlasCache sectionBlasCache,
        RtDynamicBlasCache dynamicBlasCache,
        RtDynamicTlasCache dynamicTlasCache,
        RtWorldTlasCache worldTlasCache,
        RtRayTracingPipelineProperties rayTracingPipelineProperties,
        RtRayTracingPipeline rayTracingPipeline,
        String deviceName,
        int accelerationStructureScratchAlignment,
        List<String> enabledExtensions,
        VulkanRtExternalFrameInterop externalFrameInterop
) {
    VulkanRtBackendResources {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(queueContexts, "queueContexts");
        Objects.requireNonNull(bootstrapBuffer, "bootstrapBuffer");
        Objects.requireNonNull(bootstrapBlas, "bootstrapBlas");
        Objects.requireNonNull(bootstrapTlas, "bootstrapTlas");
        Objects.requireNonNull(sceneMaterialTable, "sceneMaterialTable");
        Objects.requireNonNull(sectionBlasCache, "sectionBlasCache");
        Objects.requireNonNull(dynamicBlasCache, "dynamicBlasCache");
        Objects.requireNonNull(dynamicTlasCache, "dynamicTlasCache");
        Objects.requireNonNull(worldTlasCache, "worldTlasCache");
        Objects.requireNonNull(rayTracingPipelineProperties, "rayTracingPipelineProperties");
        Objects.requireNonNull(rayTracingPipeline, "rayTracingPipeline");
        Objects.requireNonNull(deviceName, "deviceName");
        enabledExtensions = List.copyOf(enabledExtensions);
        Objects.requireNonNull(externalFrameInterop, "externalFrameInterop");
        if (allocator == 0L || accelerationStructureScratchAlignment <= 0) {
            throw new IllegalArgumentException("native RT resource bundle is incomplete");
        }
    }

    static VulkanRtBackendResources create(
            MemoryStack stack,
            RtResourceScope scope,
            VulkanRtCapabilityProbe.Result capability,
            RendererRtDiagnostics diagnostics
    ) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(diagnostics, "diagnostics");

        RtVulkanDeviceBootstrap bootstrap = scope.retain(
                "vulkan device bootstrap", RtVulkanDeviceBootstrap.open(capability));
        VkDevice device = bootstrap.device();
        VkPhysicalDevice physicalDevice = bootstrap.physicalDevice();
        long allocator = bootstrap.allocator();
        int scratchAlignment = bootstrap.accelerationStructureScratchAlignment();
        List<String> enabledExtensions = bootstrap.enabledExtensions();
        RtRayTracingPipelineProperties pipelineProperties =
                RtRayTracingPipelineProperties.query(stack, physicalDevice);
        RtExternalInteropCapabilities interop = RtExternalInteropCapabilities.create(
                stack, physicalDevice, device, bootstrap.properties().apiVersion(), enabledExtensions);
        VulkanRtExternalFrameInterop externalFrameInterop =
                new VulkanRtExternalFrameInterop(physicalDevice, device, interop);

        RtDeviceQueueContexts queues = scope.retain(
                "rt device queue contexts",
                RtDeviceQueueContexts.create(
                        stack, physicalDevice, device, bootstrap.queueFamilyIndex(),
                        bootstrap.requestedQueueCount(), diagnostics.stalls()));
        RtCommandContext frameCommands = queues.frameCommands();
        RtCommandContext buildCommands = queues.buildCommands();
        RtGpuBuffer bootstrapBuffer = scope.retain(
                "rt bootstrap device-address buffer",
                RtGpuBuffer.createDeviceAddressBuffer(
                        device, allocator, 4096L, bootstrapBufferUsageFlags(), diagnostics.stalls()));
        RtAccelerationStructure bootstrapBlas = scope.retain(
                "rt bootstrap blas",
                RtAccelerationStructure.buildBootstrapTriangleBlas(
                        device, allocator, buildCommands, scratchAlignment));
        RtAccelerationStructure bootstrapTlas = scope.retain(
                "rt bootstrap tlas",
                RtAccelerationStructure.buildBootstrapTlas(
                        device, allocator, buildCommands, scratchAlignment, bootstrapBlas));
        RtSceneMaterialTable materials = new RtSceneMaterialTable(
                device, allocator, diagnostics.materials(), diagnostics.stalls());
        materials.upload(buildCommands, RtSceneMaterialTable.bootstrapSnapshot());
        scope.retain("rt scene material table", materials);
        RtSectionBlasCache sections = scope.retain(
                "rt section blas cache",
                new RtSectionBlasCache(
                        device, allocator, queues.sectionBlasCommands(), scratchAlignment, diagnostics));
        RtDynamicBlasCache dynamicBlas = scope.retain(
                "rt dynamic blas cache",
                new RtDynamicBlasCache(
                        device, allocator, buildCommands, scratchAlignment,
                        bootstrapBlas.deviceAddress(), diagnostics));
        RtDynamicTlasCache dynamicTlas = scope.retain(
                "rt dynamic tlas cache",
                new RtDynamicTlasCache(
                        device, allocator, buildCommands, scratchAlignment, bootstrapBlas.deviceAddress()));
        RtWorldTlasCache worldTlas = scope.retain(
                "rt world tlas cache",
                new RtWorldTlasCache(
                        device, allocator, buildCommands, scratchAlignment,
                        bootstrapBlas.deviceAddress(), diagnostics));
        RtRayTracingPipeline pipeline = scope.retain(
                "rt ray tracing pipeline",
                RtRayTracingPipeline.create(
                        device, physicalDevice, allocator,
                        externalFrameInterop.sharedPresentationReady(),
                        externalFrameInterop.dedicatedAllocationRequired(),
                        frameCommands, bootstrapTlas, materials,
                        pipelineProperties, diagnostics));
        scope.retain("rt queues idle before native resource destroy", queues::waitForIdle);
        return new VulkanRtBackendResources(
                device, queues, allocator, bootstrapBuffer, bootstrapBlas,
                bootstrapTlas, materials, sections, dynamicBlas, dynamicTlas, worldTlas,
                pipelineProperties, pipeline, bootstrap.properties().deviceNameString(),
                scratchAlignment, enabledExtensions, externalFrameInterop);
    }

    private static int bootstrapBufferUsageFlags() {
        return VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                | VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
    }
}

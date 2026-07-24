package top.ceroxe.mcvulkanrt.renderer.rt.device;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import top.ceroxe.mcvulkanrt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.mcvulkanrt.renderer.RendererRtDiagnostics;

import java.util.List;
import java.util.Objects;

/**
 * Minimal independently owned Vulkan RT device used by the generic renderer pipeline.
 *
 * <p>This owner intentionally creates no scene cache, acceleration structure, descriptor set, or
 * shader pipeline. Higher layers construct those resources from explicit contracts while this
 * class enforces the parent lifetime: command lanes close before VMA, the logical device, and the
 * instance. It is the RHI-style root for the new renderer rather than an adapter to the legacy
 * section renderer.</p>
 */
public final class VulkanDeviceRuntime implements AutoCloseable {
    private final RtResourceScope resources;
    private final RtVulkanDeviceBootstrap bootstrap;
    private final RtDeviceQueueContexts queues;
    private boolean closed;

    private VulkanDeviceRuntime(
            RtResourceScope resources,
            RtVulkanDeviceBootstrap bootstrap,
            RtDeviceQueueContexts queues
    ) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
        this.queues = Objects.requireNonNull(queues, "queues");
    }

    public static VulkanDeviceRuntime open(VulkanRtCapabilityProbe.Result capability) {
        return open(capability, RendererRtDiagnostics.noop());
    }

    public static VulkanDeviceRuntime open(
            VulkanRtCapabilityProbe.Result capability,
            RendererRtDiagnostics diagnostics
    ) {
        return open(capability, diagnostics, false);
    }

    public static VulkanDeviceRuntime open(
            VulkanRtCapabilityProbe.Result capability,
            RendererRtDiagnostics diagnostics,
            boolean validationEnabled
    ) {
        return open(capability, diagnostics, validationEnabled, true);
    }

    public static VulkanDeviceRuntime open(
            VulkanRtCapabilityProbe.Result capability,
            RendererRtDiagnostics diagnostics,
            boolean validationEnabled,
            boolean gpuTimingsEnabled
    ) {
        VulkanRtCapabilityProbe.Result checkedCapability = Objects.requireNonNull(capability, "capability");
        RendererRtDiagnostics checkedDiagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        if (!checkedCapability.hardwareRayTracingReady()) {
            throw new IllegalArgumentException("Vulkan device runtime requires hardware ray tracing support");
        }

        RtResourceScope resources = new RtResourceScope();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            RtVulkanDeviceBootstrap bootstrap = resources.retain(
                    "Vulkan device bootstrap",
                    RtVulkanDeviceBootstrap.open(checkedCapability, validationEnabled)
            );
            RtDeviceQueueContexts queues = resources.retain(
                    "Vulkan command lanes",
                    RtDeviceQueueContexts.create(
                            stack,
                            bootstrap.physicalDevice(),
                            bootstrap.device(),
                            bootstrap.queueFamilyIndex(),
                            bootstrap.requestedQueueCount(),
                            checkedDiagnostics.stalls(),
                            gpuTimingsEnabled
                    )
            );
            return new VulkanDeviceRuntime(resources, bootstrap, queues);
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            try {
                resources.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public synchronized VkPhysicalDevice physicalDevice() {
        requireOpen();
        return bootstrap.physicalDevice();
    }

    public synchronized VkDevice device() {
        requireOpen();
        return bootstrap.device();
    }

    public synchronized long allocator() {
        requireOpen();
        return bootstrap.allocator();
    }

    /** Ordered lane for uploads and acceleration-structure builds. */
    public synchronized RtCommandContext buildCommands() {
        requireOpen();
        return queues.buildCommands();
    }

    /** Ordered lane reserved for ray dispatch and output publication. */
    public synchronized RtCommandContext frameCommands() {
        requireOpen();
        return queues.frameCommands();
    }

    public synchronized int queueFamilyIndex() {
        requireOpen();
        return bootstrap.queueFamilyIndex();
    }

    public synchronized int accelerationStructureScratchAlignment() {
        requireOpen();
        return bootstrap.accelerationStructureScratchAlignment();
    }

    public synchronized List<String> enabledExtensions() {
        requireOpen();
        return bootstrap.enabledExtensions();
    }

    /** Queries and probes the Win32 storage-image contract used by public GPU frame leases. */
    public synchronized ExternalFrameInterop externalFrameInterop() {
        requireOpen();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            RtExternalInteropCapabilities capabilities = RtExternalInteropCapabilities.create(
                    stack,
                    bootstrap.physicalDevice(),
                    bootstrap.device(),
                    bootstrap.properties().apiVersion(),
                    bootstrap.enabledExtensions()
            );
            return new ExternalFrameInterop(
                    capabilities.memoryProbe().successful(),
                    capabilities.semaphoreProbe().successful(),
                    capabilities.dedicatedOnly(),
                    capabilities.reason()
            );
        }
    }

    public synchronized boolean closed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        try {
            queues.waitForIdle();
        } catch (RuntimeException waitFailure) {
            failure = waitFailure;
        }
        try {
            resources.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        if (failure != null) throw failure;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Vulkan device runtime is closed");
    }

    public record ExternalFrameInterop(
            boolean memoryExportReady,
            boolean semaphoreExportReady,
            boolean dedicatedAllocationRequired,
            String reason
    ) {
        public ExternalFrameInterop {
            reason = Objects.requireNonNull(reason, "reason");
            if (!memoryExportReady && semaphoreExportReady) {
                throw new IllegalArgumentException("semaphore export cannot make frame memory exportable");
            }
        }
    }
}

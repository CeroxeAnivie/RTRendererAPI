package top.ceroxe.rt.renderer.rt.device;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.rt.pipeline.RtRayTracingPipeline;

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

    /**
     * Opens a runtime with no-op diagnostics and default feature policy.
     *
     * @param capability validated ray-tracing capability result used to select the device
     * @return independently owned runtime that the caller must close
     */
    public static VulkanDeviceRuntime open(VulkanRtCapabilityProbe.Result capability) {
        return open(capability, RendererRtDiagnostics.noop());
    }

    /**
     * Opens a runtime with caller diagnostics and validation disabled.
     *
     * @param capability  validated ray-tracing capability result used to select the device
     * @param diagnostics diagnostic sinks retained by created command lanes
     * @return independently owned runtime that the caller must close
     */
    public static VulkanDeviceRuntime open(
            VulkanRtCapabilityProbe.Result capability,
            RendererRtDiagnostics diagnostics
    ) {
        return open(capability, diagnostics, false);
    }

    /**
     * Opens a runtime with explicit validation and default GPU timing policy.
     *
     * @param capability        validated ray-tracing capability result used to select the device
     * @param diagnostics       diagnostic sinks retained by created command lanes
     * @param validationEnabled whether Vulkan validation should be enabled at bootstrap
     * @return independently owned runtime that the caller must close
     */
    public static VulkanDeviceRuntime open(
            VulkanRtCapabilityProbe.Result capability,
            RendererRtDiagnostics diagnostics,
            boolean validationEnabled
    ) {
        return open(capability, diagnostics, validationEnabled, true);
    }

    /**
     * Creates the device, allocator, and ordered command lanes as one closeable owner.
     * Partial creation is rolled back before an exception escapes.
     *
     * @param capability        validated ray-tracing capability result used to select the device
     * @param diagnostics       diagnostic sinks retained by created command lanes
     * @param validationEnabled whether Vulkan validation should be enabled at bootstrap
     * @param gpuTimingsEnabled whether command lanes should allocate GPU timestamp pools
     * @return independently owned runtime that the caller must close
     * @throws NullPointerException     if {@code capability} or {@code diagnostics} is {@code null}
     * @throws IllegalArgumentException if the capability does not provide hardware ray tracing
     */
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

    /**
     * Returns the selected physical device without transferring ownership.
     *
     * @return selected physical device while this runtime is open
     */
    public synchronized VkPhysicalDevice physicalDevice() {
        requireOpen();
        return bootstrap.physicalDevice();
    }

    /**
     * Returns the logical device without transferring ownership.
     *
     * @return runtime-owned logical device while this runtime is open
     */
    public synchronized VkDevice device() {
        requireOpen();
        return bootstrap.device();
    }

    /**
     * Returns the VMA allocator without transferring ownership.
     *
     * @return runtime-owned VMA allocator while this runtime is open
     */
    public synchronized long allocator() {
        requireOpen();
        return bootstrap.allocator();
    }

    /**
     * Returns the ordered lane for uploads and acceleration-structure builds.
     *
     * @return runtime-owned command lane, valid until {@link #close()}
     */
    public synchronized RtCommandContext buildCommands() {
        requireOpen();
        return queues.buildCommands();
    }

    /**
     * Returns the ordered lane reserved for ray dispatch and output publication.
     *
     * @return runtime-owned command lane, valid until {@link #close()}
     */
    public synchronized RtCommandContext frameCommands() {
        requireOpen();
        return queues.frameCommands();
    }

    /**
     * Returns the queue family shared by the runtime's command lanes.
     *
     * @return Vulkan queue-family index
     */
    public synchronized int queueFamilyIndex() {
        requireOpen();
        return bootstrap.queueFamilyIndex();
    }

    /**
     * Returns the required device-address alignment for acceleration-structure scratch storage.
     *
     * @return positive alignment in bytes
     */
    public synchronized int accelerationStructureScratchAlignment() {
        requireOpen();
        return bootstrap.accelerationStructureScratchAlignment();
    }

    /**
     * Returns the maximum legal width or height for a 2D image created by this runtime.
     *
     * @return dimension limit in pixels
     */
    public synchronized int maxImageDimension2D() {
        requireOpen();
        return bootstrap.properties().maxImageDimension2D();
    }

    /**
     * Returns the enabled logical-device extensions.
     *
     * @return immutable extension-name list
     */
    public synchronized List<String> enabledExtensions() {
        requireOpen();
        return bootstrap.enabledExtensions();
    }

    /**
     * Captures point-in-time device-local heap usage and budget information.
     *
     * @return immutable memory-budget sample
     */
    public synchronized VulkanMemoryBudgetSnapshot memoryBudget() {
        requireOpen();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return VulkanMemoryBudget.capture(
                    stack,
                    bootstrap.physicalDevice(),
                    bootstrap.allocator(),
                    bootstrap.memoryBudgetEnabled()
            );
        }
    }

    /**
     * Queries and probes the Win32 storage-image contract used by public GPU frame leases.
     *
     * @return immutable capability result that owns no native resources
     */
    public synchronized ExternalFrameInterop externalFrameInterop() {
        return externalFrameInterop(RtRayTracingPipeline.bootstrapOutputFormat());
    }

    /**
     * Queries and probes the Win32 storage-image contract for an explicit Vulkan format.
     *
     * @param outputFormat Vulkan format used by the exportable storage image
     * @return immutable capability result that owns no native resources
     */
    public synchronized ExternalFrameInterop externalFrameInterop(int outputFormat) {
        requireOpen();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            RtExternalInteropCapabilities capabilities = RtExternalInteropCapabilities.create(
                    stack,
                    bootstrap.physicalDevice(),
                    bootstrap.device(),
                    bootstrap.properties().apiVersion(),
                    bootstrap.enabledExtensions(),
                    outputFormat
            );
            return new ExternalFrameInterop(
                    capabilities.memoryProbe().successful(),
                    capabilities.semaphoreProbe().successful(),
                    capabilities.semaphoreProbe().successful()
                            && capabilities.semaphoreProbe().importable(),
                    capabilities.dedicatedOnly(),
                    capabilities.reason()
            );
        }
    }

    /**
     * Reports whether this owner has begun closing its queues and device resources.
     *
     * @return {@code true} after the first {@link #close()} call begins
     */
    public synchronized boolean closed() {
        return closed;
    }

    /**
     * Waits for owned queues to become idle and closes all device resources in dependency order.
     *
     * <p>This operation is idempotent. A resource-close failure is propagated after remaining
     * resources have been given their close opportunity.</p>
     */
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

    /**
     * Validated native frame-interoperability surface.
     *
     * @param memoryExportReady           whether frame memory export is usable
     * @param semaphoreExportReady        whether producer completion semaphore export is usable
     * @param semaphoreImportReady        whether consumer completion semaphore import is usable
     * @param dedicatedAllocationRequired whether exported images require dedicated allocation
     * @param reason                      diagnostic capability explanation
     */
    public record ExternalFrameInterop(
            boolean memoryExportReady,
            boolean semaphoreExportReady,
            boolean semaphoreImportReady,
            boolean dedicatedAllocationRequired,
            String reason
    ) {
        /**
         * Validates dependencies between memory export and semaphore capabilities.
         */
        public ExternalFrameInterop {
            reason = Objects.requireNonNull(reason, "reason");
            if (!memoryExportReady && semaphoreExportReady) {
                throw new IllegalArgumentException("semaphore export cannot make frame memory exportable");
            }
            if (semaphoreImportReady && !semaphoreExportReady) {
                throw new IllegalArgumentException("semaphore import readiness requires the validated interop path");
            }
        }
    }
}

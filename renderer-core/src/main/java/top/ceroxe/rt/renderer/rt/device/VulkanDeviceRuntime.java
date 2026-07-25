package top.ceroxe.rt.renderer.rt.device;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.KHRWin32Surface;
import org.lwjgl.vulkan.KHRGetSurfaceCapabilities2;
import org.lwjgl.vulkan.EXTFullScreenExclusive;
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
    private boolean managedPresentationActive;
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
     * Returns the runtime-owned instance for tightly integrated platform presentation.
     * The caller must not destroy it and must not retain it beyond this runtime.
     *
     * @return runtime-owned Vulkan instance while this runtime is open
     */
    public synchronized VkInstance instance() {
        requireOpen();
        return bootstrap.instance();
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
     * Returns the ordered frame queue used by ray dispatch and managed presentation.
     * Queue commands must use {@link VulkanQueueHostSync} for Vulkan host synchronization.
     *
     * @return borrowed frame queue valid until {@link #close()}
     */
    public synchronized VkQueue frameQueue() {
        requireOpen();
        return queues.frameQueue();
    }

    /**
     * Returns the queue reserved for potentially blocking platform presentation calls.
     * It is distinct from frame/build queues when the selected family exposes at least three
     * queues; otherwise it safely aliases the ordered frame queue.
     *
     * @return borrowed presentation queue valid until {@link #close()}
     */
    public synchronized VkQueue presentationQueue() {
        requireOpen();
        return queues.presentationQueue();
    }

    /**
     * Reports whether platform presentation can avoid blocking frame/build host submission.
     *
     * @return {@code true} when presentation owns a distinct native queue
     */
    public synchronized boolean dedicatedPresentationQueueEnabled() {
        requireOpen();
        return queues.presentationQueueDedicated();
    }

    /**
     * Reserves a monotonic GPU signal consumed by the dedicated managed-presentation queue.
     *
     * @return non-null signal, or a disabled signal when presentation aliases the frame queue
     */
    public synchronized ManagedPresentationSignal reserveManagedPresentationSignal() {
        requireOpen();
        if (!queues.presentationQueueDedicated()) return ManagedPresentationSignal.disabled();
        return new ManagedPresentationSignal(
                queues.presentationTimelineSemaphore(),
                queues.reservePresentationTimelineValue()
        );
    }

    /**
     * Reports whether this runtime was created with the instance and device extensions required
     * for a same-logical-device Win32 swapchain. Surface support for the selected queue family is
     * still checked against the actual GLFW window before the fast path is committed.
     *
     * @return {@code true} when the required managed Win32 presentation extensions are enabled
     */
    public synchronized boolean managedWin32PresentationEnabled() {
        requireOpen();
        List<String> instanceExtensions = bootstrap.enabledInstanceExtensions();
        return instanceExtensions.contains(KHRSurface.VK_KHR_SURFACE_EXTENSION_NAME)
                && instanceExtensions.contains(KHRWin32Surface.VK_KHR_WIN32_SURFACE_EXTENSION_NAME)
                && bootstrap.enabledExtensions().contains(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME);
    }

    /**
     * Reports whether the optional Windows full-screen swapchain hint is enabled.
     *
     * @return {@code true} when instance and device full-screen-exclusive extensions are enabled
     */
    public synchronized boolean fullScreenExclusivePresentationEnabled() {
        requireOpen();
        return bootstrap.enabledInstanceExtensions().contains(
                KHRGetSurfaceCapabilities2.VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME
        ) && bootstrap.enabledExtensions().contains(
                EXTFullScreenExclusive.VK_EXT_FULL_SCREEN_EXCLUSIVE_EXTENSION_NAME
        );
    }

    /**
     * Activates the same-device presentation ownership contract.
     *
     * <p>While active, frame producers keep output images owned by the renderer queue instead of
     * releasing every frame to {@code VK_QUEUE_FAMILY_EXTERNAL}. The official presenter is the
     * sole owner of this scope; expert consumers that request an external memory handle still
     * trigger a lazy, explicit ownership release in the frame slot.</p>
     */
    public synchronized void beginManagedPresentation() {
        requireOpen();
        if (!managedWin32PresentationEnabled()) {
            throw new UnsupportedOperationException(
                    "runtime does not expose the extensions required for managed presentation"
            );
        }
        if (managedPresentationActive) {
            throw new IllegalStateException("managed presentation is already active");
        }
        managedPresentationActive = true;
    }

    /**
     * Returns whether newly submitted frames should retain renderer-queue ownership.
     *
     * @return {@code true} while the official managed presenter owns the presentation scope
     */
    public synchronized boolean managedPresentationActive() {
        requireOpen();
        return managedPresentationActive;
    }

    /** Ends the ownership contract established by {@link #beginManagedPresentation()}. */
    public synchronized void endManagedPresentation() {
        requireOpen();
        if (!managedPresentationActive) {
            throw new IllegalStateException("managed presentation is not active");
        }
        managedPresentationActive = false;
    }

    /**
     * Same-device timeline identity connecting a completed producer frame to presentation.
     *
     * @param semaphore borrowed timeline semaphore, or zero when the fast path is disabled
     * @param value positive signal value, or zero when the fast path is disabled
     */
    public record ManagedPresentationSignal(long semaphore, long value) {
        /**
         * Validates that handle and value are either both zero or both positive.
         */
        public ManagedPresentationSignal {
            if ((semaphore == 0L) != (value == 0L) || value < 0L) {
                throw new IllegalArgumentException("managed presentation signal is inconsistent");
            }
        }

        /**
         * Creates the sentinel used when presentation aliases the frame queue.
         *
         * @return disabled zero-handle signal
         */
        public static ManagedPresentationSignal disabled() {
            return new ManagedPresentationSignal(0L, 0L);
        }

        /**
         * Reports whether a dedicated presentation queue must wait this signal.
         *
         * @return {@code true} when this signal carries a native timeline semaphore
         */
        public boolean enabled() {
            return semaphore != 0L;
        }
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

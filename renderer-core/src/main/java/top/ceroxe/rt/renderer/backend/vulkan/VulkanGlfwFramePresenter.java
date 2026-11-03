package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease;
import top.ceroxe.rt.renderer.api.interop.vulkan.D3D12SharedFrame;
import top.ceroxe.rt.renderer.api.FrameCompositionPlan;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenter;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenterConfig;
import top.ceroxe.rt.renderer.feature.VulkanSwapchainInterceptor;
import top.ceroxe.rt.renderer.rt.device.interop.VulkanWin32ExternalSemaphoreProbe;
import top.ceroxe.rt.renderer.rt.device.interop.Win32HandleSupport;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.device.VulkanQueueHostSync;
import top.ceroxe.rt.renderer.rt.device.RtCommandContext;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * Windows GLFW/Vulkan implementation of the official frame presenter.
 *
 * <p>The provider-owned fast path shares the renderer's instance, logical device, and ordered
 * frame queue, matching a conventional RHI viewport. External-memory import remains as the
 * compatibility path for leases that do not carry the internal managed-image capability.</p>
 */
final class VulkanGlfwFramePresenter implements VulkanFramePresenter {
    private static final String MANAGED_COPY_TIMING_LABEL = "managed-present-copy";
    private static final int EXTERNAL_MEMORY_HANDLE_TYPE =
            VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    private static final int EXTERNAL_SEMAPHORE_HANDLE_TYPE =
            VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    private static final int MAX_RETAINED_IMPORTED_IMAGES = 16;
    private static final int FRAME_CONTEXT_COUNT = 3;
    private static final int OVERLAY_MARGIN = 16;
    private static final int OVERLAY_COPY_BATCH_SIZE = 256;
    private static final long UINT64_MAX = -1L;

    private final Thread ownerThread;
    private final VulkanFramePresenterConfig configuration;
    private final VulkanDeviceRuntime sharedRuntime;
    private final VulkanSwapchainInterceptor swapchainInterceptor;
    private final String gpuStableId;
    private final Runnable closeCallback;
    private final LongConsumer frameRetiredCallback;
    private final Supplier<GpuFrameLease> managedFrameSupplier;
    private final Map<Long, ImportedFrame> importedFrames = new HashMap<>();
    private final Map<Long,VulkanD3D12ImportedFrame> d3d12Frames = new HashMap<>();
    private long lastD3D12Sequence = -1;
    private boolean timelineSemaphoreEnabled;
    private VulkanD3D12CompositionInputs.Resolver d3d12CompositionResolver;
    private VulkanD3D12Composition d3d12Composition;
    private Path pendingCapture;
    private boolean swapchainCaptureSupported;
    private boolean windowActivated;

    private long window;
    private VkInstance instance;
    private long surface;
    private boolean surfaceIntercepted;
    private VkPhysicalDevice physicalDevice;
    private VkDevice device;
    private int queueFamilyIndex = -1;
    private VkQueue queue;
    private long commandPool;
    private FrameContext[] frameContexts;
    private int nextFrameContext;
    private VulkanFrameDisplayTransform displayTransform;

    private long swapchain;
    private long[] swapchainImages = new long[0];
    private long[] presentationSemaphores = new long[0];
    private int swapchainFormat;
    private int swapchainWidth;
    private int swapchainHeight;
    private SwapchainPresentMode activePresentMode;
    private boolean closed;
    private boolean sharedDeviceFastPath;
    private boolean managedPresentationRegistered;
    private boolean fullScreenExclusiveAvailable;
    private long acquireSamples;
    private long acquireNanos;
    private long presentSamples;
    private long presentNanos;
    private long presentQueueLockNanos;
    private long presentNativeCallNanos;
    private long managedCopyGpuSamples;
    private long managedCopyGpuNanos;
    private String overlayText = "";
    private long overlayRevision;
    private GpuFrameLease pendingTagRetirementLease;
    private boolean closeStarted;
    private boolean resourcesClosed;

    private VulkanGlfwFramePresenter(
            VulkanDeviceRuntime sharedRuntime,
            String gpuStableId,
            VulkanFramePresenterConfig configuration,
            Supplier<GpuFrameLease> managedFrameSupplier,
            LongConsumer frameRetiredCallback,
            Runnable closeCallback
    ) {
        ownerThread = Thread.currentThread();
        this.sharedRuntime = sharedRuntime;
        this.swapchainInterceptor = sharedRuntime == null
                ? null
                : sharedRuntime.featureSession().swapchainInterceptor().orElse(null);
        this.gpuStableId = Objects.requireNonNull(gpuStableId, "gpuStableId");
        if (gpuStableId.isBlank()) throw new IllegalArgumentException("gpuStableId must not be blank");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.managedFrameSupplier = Objects.requireNonNull(managedFrameSupplier, "managedFrameSupplier");
        this.frameRetiredCallback = Objects.requireNonNull(frameRetiredCallback, "frameRetiredCallback");
        this.closeCallback = Objects.requireNonNull(closeCallback, "closeCallback");
    }

    static VulkanGlfwFramePresenter open(
            VulkanDeviceRuntime sharedRuntime,
            String gpuStableId,
            VulkanFramePresenterConfig configuration,
            Supplier<GpuFrameLease> managedFrameSupplier,
            LongConsumer frameRetiredCallback,
            Runnable closeCallback
    ) {
        VulkanGlfwFramePresenter presenter = new VulkanGlfwFramePresenter(
                sharedRuntime, gpuStableId, configuration, managedFrameSupplier,
                frameRetiredCallback, closeCallback
        );
        boolean initialized = false;
        try {
            GlfwRuntime.acquire();
            initialized = true;
            presenter.createWindow();
            presenter.createVulkanRuntime();
            presenter.createSwapchain();
            return presenter;
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            try {
                presenter.closePartial(initialized);
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private void createWindow() {
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        // Producer initialization can continue after this presenter opens. Keep the
        // window hidden until a real frame has been submitted for presentation.
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, configuration.visible() ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, configuration.visible() ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        boolean fullScreen = configuration.windowMode()
                == VulkanFramePresenterConfig.WindowMode.PRIMARY_MONITOR_FULLSCREEN;
        GLFW.glfwWindowHint(
                GLFW.GLFW_RESIZABLE,
                !fullScreen && configuration.resizable() ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE
        );
        long monitor = 0L;
        int width = configuration.initialWidth();
        int height = configuration.initialHeight();
        if (fullScreen) {
            monitor = GLFW.glfwGetPrimaryMonitor();
            if (monitor == 0L) {
                throw new IllegalStateException("GLFW reported no primary monitor for full-screen presentation");
            }
            GLFWVidMode videoMode = GLFW.glfwGetVideoMode(monitor);
            if (videoMode == null) {
                throw new IllegalStateException("GLFW reported no video mode for the primary monitor");
            }
            width = videoMode.width();
            height = videoMode.height();
            GLFW.glfwWindowHint(GLFW.GLFW_REFRESH_RATE, videoMode.refreshRate());
        } else {
            GlfwFramebufferExtent.Extent initialWindowExtent =
                    GlfwFramebufferExtent.initialWindowExtent(
                            GLFW.glfwGetPrimaryMonitor(), width, height
                    );
            width = initialWindowExtent.width();
            height = initialWindowExtent.height();
        }
        window = GLFW.glfwCreateWindow(
                width,
                height,
                configuration.title(),
                monitor,
                0L
        );
        if (window == 0L) throw new IllegalStateException("GLFW failed to create the Vulkan window");
        if (!fullScreen) {
            GlfwFramebufferExtent.normalizeWindowedFramebuffer(
                    window, configuration.initialWidth(), configuration.initialHeight()
            );
        }
    }

    private void activateOnFirstFrame() {
        if (windowActivated || !configuration.visible()) return;
        windowActivated = true;
        GLFW.glfwShowWindow(window);
        GLFW.glfwFocusWindow(window);
        ForegroundWindow.activate(org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(window));
        GLFW.glfwPollEvents();
    }

    private void createVulkanRuntime() {
        if (sharedRuntime != null && sharedRuntime.managedWin32PresentationEnabled()) {
            createSharedVulkanRuntime();
            return;
        }
        createStandaloneVulkanRuntime();
    }

    private void createSharedVulkanRuntime() {
        instance = sharedRuntime.instance();
        physicalDevice = sharedRuntime.physicalDevice();
        device = sharedRuntime.device();
        queueFamilyIndex = sharedRuntime.queueFamilyIndex();
        /*
         * DLSS-G's Vulkan eBlockNoClientQueues mode keeps the proxy pacer off the renderer queue.
         * The NVIDIA session enables that mode only with its input-completion timeline gate, so
         * tagged frame resources remain protected while the two queues make progress in parallel.
         */
        queue = sharedRuntime.presentationQueue();
        sharedDeviceFastPath = true;
        timelineSemaphoreEnabled = sharedRuntime.timelineSemaphoreEnabled();
        fullScreenExclusiveAvailable = sharedRuntime.fullScreenExclusivePresentationEnabled();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            createSurface(stack);
            IntBuffer supported = stack.ints(VK10.VK_FALSE);
            checkVk(
                    KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR(
                            physicalDevice, queueFamilyIndex, surface, supported
                    ),
                    "vkGetPhysicalDeviceSurfaceSupportKHR.rendererQueue"
            );
            if (supported.get(0) != VK10.VK_TRUE) {
                throw new UnsupportedOperationException(
                        "renderer frame queue family cannot present to the GLFW Win32 surface"
                );
            }
            createCommandResources(stack);
            sharedRuntime.beginManagedPresentation();
            managedPresentationRegistered = true;
        }
    }

    private void createStandaloneVulkanRuntime() {
        if (!GLFWVulkan.glfwVulkanSupported()) {
            throw new UnsupportedOperationException("GLFW reports no Vulkan loader support");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer requiredExtensions = GLFWVulkan.glfwGetRequiredInstanceExtensions();
            if (requiredExtensions == null || !requiredExtensions.hasRemaining()) {
                throw new IllegalStateException("GLFW returned no required Vulkan surface extensions");
            }
            VkApplicationInfo application = VkApplicationInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationName(stack.UTF8(configuration.title()))
                    .applicationVersion(VK10.VK_MAKE_VERSION(0, 1, 0))
                    .pEngineName(stack.UTF8("RTRendererAPI Vulkan Presenter"))
                    .engineVersion(VK10.VK_MAKE_VERSION(0, 1, 0))
                    .apiVersion(VK11.VK_API_VERSION_1_1);
            VkInstanceCreateInfo instanceInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationInfo(application)
                    .ppEnabledExtensionNames(requiredExtensions);
            PointerBuffer instanceHandle = stack.mallocPointer(1);
            checkVk(VK10.vkCreateInstance(instanceInfo, null, instanceHandle), "vkCreateInstance.presenter");
            instance = new VkInstance(instanceHandle.get(0), instanceInfo);

            createSurface(stack);

            physicalDevice = selectPhysicalDevice(stack);
            queueFamilyIndex = selectQueueFamily(stack, physicalDevice);
            createLogicalDevice(stack);
            createCommandResources(stack);
        }
    }

    private void createSurface(MemoryStack stack) {
        LongBuffer surfaceHandle = stack.longs(0L);
        if (swapchainInterceptor != null && swapchainInterceptor.proxyActive()) {
            VkWin32SurfaceCreateInfoKHR createInfo = VkWin32SurfaceCreateInfoKHR.calloc(stack)
                    .sType$Default()
                    .hinstance(org.lwjgl.system.windows.WindowsLibrary.HINSTANCE)
                    .hwnd(org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(window));
            checkVk(swapchainInterceptor.createWin32Surface(instance, createInfo, surfaceHandle),
                    "vkCreateWin32SurfaceKHR.vendor");
            surfaceIntercepted = true;
        } else {
            checkVk(GLFWVulkan.glfwCreateWindowSurface(instance, window, null, surfaceHandle),
                    "glfwCreateWindowSurface");
        }
        surface = surfaceHandle.get(0);
    }

    private VkPhysicalDevice selectPhysicalDevice(MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        checkVk(VK10.vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices.count");
        if (count.get(0) == 0) throw new UnsupportedOperationException("Vulkan reported no physical devices");
        PointerBuffer handles = stack.mallocPointer(count.get(0));
        checkVk(VK10.vkEnumeratePhysicalDevices(instance, count, handles), "vkEnumeratePhysicalDevices.list");
        for (int index = 0; index < handles.limit(); index++) {
            VkPhysicalDevice candidate = new VkPhysicalDevice(handles.get(index), instance);
            if (gpuStableId.equals(stableDeviceId(stack, candidate))) return candidate;
        }
        throw new UnsupportedOperationException(
                "the renderer GPU is unavailable to the presentation Vulkan instance: " + gpuStableId
        );
    }

    private int selectQueueFamily(MemoryStack stack, VkPhysicalDevice candidate) {
        IntBuffer count = stack.ints(0);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
        VkQueueFamilyProperties.Buffer properties = VkQueueFamilyProperties.malloc(count.get(0), stack);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, properties);
        for (int index = 0; index < properties.limit(); index++) {
            int required = VK10.VK_QUEUE_TRANSFER_BIT | VK10.VK_QUEUE_COMPUTE_BIT;
            boolean transferAndCompute = (properties.get(index).queueFlags() & required) == required;
            IntBuffer supported = stack.ints(VK10.VK_FALSE);
            checkVk(
                    KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR(candidate, index, surface, supported),
                    "vkGetPhysicalDeviceSurfaceSupportKHR"
            );
            if (transferAndCompute && supported.get(0) == VK10.VK_TRUE) return index;
        }
        throw new UnsupportedOperationException("renderer GPU has no transfer/compute/present queue family");
    }

    private void createLogicalDevice(MemoryStack stack) {
        var timeline = VkPhysicalDeviceTimelineSemaphoreFeatures.calloc(stack).sType$Default();
        VK11.vkGetPhysicalDeviceFeatures2(physicalDevice,VkPhysicalDeviceFeatures2.calloc(stack)
                .sType$Default().pNext(timeline.address()));
        IntBuffer extensionCount=stack.ints(0);
        checkVk(VK10.vkEnumerateDeviceExtensionProperties(physicalDevice,(String)null,extensionCount,null),"vkEnumerateDeviceExtensionProperties.presenter");
        var advertised=VkExtensionProperties.calloc(extensionCount.get(0),stack);
        checkVk(VK10.vkEnumerateDeviceExtensionProperties(physicalDevice,(String)null,extensionCount,advertised),"vkEnumerateDeviceExtensionProperties.presenter");
        for(var extension:advertised)if(extension.extensionNameString().equals(KHRTimelineSemaphore.VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME))
            timelineSemaphoreEnabled=timeline.timelineSemaphore();
        PointerBuffer extensions = stack.mallocPointer(timelineSemaphoreEnabled?4:3);
        extensions.put(stack.UTF8(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME));
        extensions.put(stack.UTF8(KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME));
        extensions.put(stack.UTF8(KHRExternalSemaphoreWin32.VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME));
        if(timelineSemaphoreEnabled)extensions.put(stack.UTF8(KHRTimelineSemaphore.VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME));
        extensions.flip();
        VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(1, stack)
                .sType$Default()
                .queueFamilyIndex(queueFamilyIndex)
                .pQueuePriorities(stack.floats(1.0F));
        VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack)
                .sType$Default()
                .pNext(timelineSemaphoreEnabled?timeline.address():0L)
                .pQueueCreateInfos(queues)
                .ppEnabledExtensionNames(extensions);
        PointerBuffer deviceHandle = stack.mallocPointer(1);
        checkVk(VK10.vkCreateDevice(physicalDevice, deviceInfo, null, deviceHandle), "vkCreateDevice.presenter");
        device = new VkDevice(deviceHandle.get(0), physicalDevice, deviceInfo);
        PointerBuffer queueHandle = stack.mallocPointer(1);
        VK10.vkGetDeviceQueue(device, queueFamilyIndex, 0, queueHandle);
        queue = new VkQueue(queueHandle.get(0), device);
    }

    private void createCommandResources(MemoryStack stack) {
        LongBuffer poolHandle = stack.longs(0L);
        checkVk(
                VK10.vkCreateCommandPool(
                        device,
                        VkCommandPoolCreateInfo.calloc(stack)
                                .sType$Default()
                                .flags(VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                                .queueFamilyIndex(queueFamilyIndex),
                        null,
                        poolHandle
                ),
                "vkCreateCommandPool.presenter"
        );
        commandPool = poolHandle.get(0);
        PointerBuffer commandBuffers = stack.mallocPointer(FRAME_CONTEXT_COUNT);
        checkVk(
                VK10.vkAllocateCommandBuffers(
                        device,
                        VkCommandBufferAllocateInfo.calloc(stack)
                                .sType$Default()
                                .commandPool(commandPool)
                                .level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                                .commandBufferCount(FRAME_CONTEXT_COUNT),
                        commandBuffers
                ),
                "vkAllocateCommandBuffers.presenter"
        );
        frameContexts = new FrameContext[FRAME_CONTEXT_COUNT];
        for (int index = 0; index < frameContexts.length; index++) {
            frameContexts[index] = createFrameContext(stack, commandBuffers.get(index), index);
        }
    }

    private FrameContext createFrameContext(MemoryStack stack, long commandBufferAddress, int slot) {
        long acquireSemaphore = 0L;
        long proxyPresentSemaphore = 0L;
        long fence = 0L;
        VulkanTextOverlayUpload overlayUpload = null;
        try {
            acquireSemaphore = createSemaphore(stack, false);
            if (swapchainInterceptor != null) {
                proxyPresentSemaphore = createSemaphore(stack, false);
            }
            fence = createFence(stack, true);
            overlayUpload = VulkanTextOverlayUpload.create(physicalDevice, device);
            return new FrameContext(
                    slot,
                    new VkCommandBuffer(commandBufferAddress, device),
                    acquireSemaphore,
                    proxyPresentSemaphore,
                    fence,
                    overlayUpload
            );
        } catch (RuntimeException | Error failure) {
            if (overlayUpload != null) overlayUpload.close();
            if (fence != 0L) VK10.vkDestroyFence(device, fence, null);
            if (proxyPresentSemaphore != 0L) {
                VK10.vkDestroySemaphore(device, proxyPresentSemaphore, null);
            }
            if (acquireSemaphore != 0L) VK10.vkDestroySemaphore(device, acquireSemaphore, null);
            throw failure;
        }
    }

    @Override
    public void pollEvents() {
        requireOwnerAndOpen();
        GLFW.glfwPollEvents();
    }

    @Override
    public WindowState windowState() {
        requireOwnerAndOpen();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.ints(0);
            IntBuffer height = stack.ints(0);
            GLFW.glfwGetFramebufferSize(window, width, height);
            int framebufferWidth = width.get(0);
            int framebufferHeight = height.get(0);
            if (framebufferWidth == 0 || framebufferHeight == 0) {
                framebufferWidth = 0;
                framebufferHeight = 0;
            }
            return new WindowState(
                    GLFW.glfwWindowShouldClose(window),
                    framebufferWidth,
                    framebufferHeight
            );
        }
    }

    @Override
    public SwapchainPresentMode activePresentMode() {
        requireOwnerAndOpen();
        return Objects.requireNonNull(activePresentMode, "swapchain present mode");
    }

    /** Internal acceptance diagnostics; querying focus never changes window activation. */
    boolean focused() {
        requireOwnerAndOpen();
        return GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
    }

    /** Windows activation is independent of GLFW's cached focus notifications. */
    boolean foreground() {
        requireOwnerAndOpen();
        try {
            java.lang.foreign.MemorySegment active = (java.lang.foreign.MemorySegment)
                    ForegroundWindow.GET.invokeExact();
            return active.address() == org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(window);
        } catch (Throwable failure) {
            throw new IllegalStateException("cannot query presenter foreground state", failure);
        }
    }

    private static final class ForegroundWindow {
        private static final java.lang.foreign.Linker LINKER = java.lang.foreign.Linker.nativeLinker();
        private static final java.lang.foreign.SymbolLookup USER32 = java.lang.foreign.SymbolLookup
                .libraryLookup("user32.dll", java.lang.foreign.Arena.global());
        private static final java.lang.invoke.MethodHandle GET = function("GetForegroundWindow",
                java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.ADDRESS));
        private static final java.lang.invoke.MethodHandle SET = function("SetForegroundWindow",
                java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT,
                        java.lang.foreign.ValueLayout.ADDRESS));
        private static final java.lang.invoke.MethodHandle RAISE = function("BringWindowToTop",
                java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT,
                        java.lang.foreign.ValueLayout.ADDRESS));
        private static final java.lang.invoke.MethodHandle THREAD = function("GetWindowThreadProcessId",
                java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT,
                        java.lang.foreign.ValueLayout.ADDRESS, java.lang.foreign.ValueLayout.ADDRESS));
        private static final java.lang.invoke.MethodHandle ATTACH = function("AttachThreadInput",
                java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT,
                        java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.JAVA_INT,
                        java.lang.foreign.ValueLayout.JAVA_INT));

        private static java.lang.invoke.MethodHandle function(String name,
                java.lang.foreign.FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(USER32.find(name).orElseThrow(), descriptor);
        }

        static void activate(long handle) {
            var target = java.lang.foreign.MemorySegment.ofAddress(handle);
            try {
                var active = (java.lang.foreign.MemorySegment) GET.invokeExact();
                if (active.address() == handle) return;
                int activated = (int) SET.invokeExact(target);
                if (activated != 0) return;
                int ownerThread = (int) THREAD.invokeExact(target, java.lang.foreign.MemorySegment.NULL);
                int activeThread = (int) THREAD.invokeExact(active, java.lang.foreign.MemorySegment.NULL);
                if (activeThread == 0 || activeThread == ownerThread) return;
                // Share activation state only for this startup request, never throughout rendering.
                int attached = (int) ATTACH.invokeExact(ownerThread, activeThread, 1);
                if (attached == 0) return;
                try {
                    int raised = (int) RAISE.invokeExact(target);
                    activated = (int) SET.invokeExact(target);
                } finally {
                    int detached = (int) ATTACH.invokeExact(ownerThread, activeThread, 0);
                }
            } catch (Throwable failure) {
                throw new IllegalStateException("cannot activate presenter window", failure);
            }
        }
    }

    @Override
    public PerformanceSnapshot performanceSnapshot() {
        requireOwnerAndOpen();
        return new PerformanceSnapshot(
                acquireSamples,
                acquireNanos,
                presentSamples,
                presentNanos,
                presentQueueLockNanos,
                presentNativeCallNanos,
                managedCopyGpuSamples,
                managedCopyGpuNanos
        );
    }

    @Override
    public void setTitle(String title) {
        requireOwnerAndOpen();
        String checked = Objects.requireNonNull(title, "title");
        if (checked.isBlank()) throw new IllegalArgumentException("title must not be blank");
        GLFW.glfwSetWindowTitle(window, checked);
    }

    @Override
    public void setOverlayText(String text) {
        requireOwnerAndOpen();
        String checked = Objects.requireNonNull(text, "text");
        if (overlayText.equals(checked)) return;
        overlayText = checked;
        overlayRevision = Math.incrementExact(overlayRevision);
    }

    @Override
    public void captureNextPresentedFrame(Path png) {
        requireOwnerAndOpen();
        if(sharedRuntime==null)throw new UnsupportedOperationException("Final-frame capture requires the renderer-bound Vulkan presenter");
        if(!swapchainCaptureSupported)throw new UnsupportedOperationException("This surface does not support diagnostic swapchain readback");
        if(pendingCapture!=null)throw new IllegalStateException("A final-frame capture request is already pending");
        pendingCapture=Objects.requireNonNull(png,"png").toAbsolutePath();
    }

    @Override
    public Optional<PresentationResult> presentLatestFrame() {
        return presentLatestFrame(List.of());
    }

    @Override
    public Optional<PresentationResult> presentLatestFrame(List<FrameCompositionPlan.Layer> overlays) {
        requireOwnerAndOpen();
        var layers=checkedCompositionLayers(overlays);
        pollCompletedManagedRetirements();
        GpuFrameLease lease = managedFrameSupplier.get();
        return lease == null
                ? Optional.empty()
                : Optional.of(presentAndRelease(lease, true, layers));
    }

    @Override
    public PresentationResult presentAndRelease(GpuFrameLease lease) {
        return presentAndRelease(lease, false, List.of());
    }

    @Override
    public PresentationResult presentD3D12SharedFrame(D3D12SharedFrame frame) {
        return presentD3D12SharedFrame(frame,List.of());
    }

    void attachD3D12CompositionResolver(VulkanD3D12CompositionInputs.Resolver resolver) {
        requireOwnerAndOpen();
        d3d12CompositionResolver=Objects.requireNonNull(resolver,"resolver");
    }

    @Override
    public PresentationResult presentD3D12SharedFrame(D3D12SharedFrame frame,List<FrameCompositionPlan.Layer> overlays) {
        return presentExternalFrame(frame,overlays,0);
    }
    @Override public PresentationResult presentD3D12SharedFrame(D3D12SharedFrame frame,FrameCompositionPlan.Layer background,List<FrameCompositionPlan.Layer> overlays) {
        return presentExternalFrame(frame,backgroundLayers(background,overlays),1);
    }
    private static List<FrameCompositionPlan.Layer> backgroundLayers(FrameCompositionPlan.Layer background,List<FrameCompositionPlan.Layer> overlays) {
        Objects.requireNonNull(background,"background");
        if(background.operation()!=FrameCompositionPlan.Operation.REPLACE)throw new IllegalArgumentException("Host background must use REPLACE");
        var layers=new java.util.ArrayList<FrameCompositionPlan.Layer>();layers.add(background);layers.addAll(Objects.requireNonNull(overlays,"overlays"));return List.copyOf(layers);
    }
    private PresentationResult presentExternalFrame(D3D12SharedFrame frame,List<FrameCompositionPlan.Layer> overlays,int backgroundLayers) {
        requireOwnerAndOpen();
        var layers=checkedCompositionLayers(overlays);
        if(proxySwapchainActive())throw new UnsupportedOperationException("D3D12 shared-frame composition requires Vulkan frame generation disabled");
        var imported=importD3D12Frame(frame);
        var state=windowState();
        if(state.drawable())ensureSwapchain(state.framebufferWidth(),state.framebufferHeight());
        FrameContext context=frameContexts[nextFrameContext];
        nextFrameContext=(nextFrameContext+1)%frameContexts.length;
        awaitFrameContext(context);
        boolean submitted=false;
        int imageIndex=-1;
        try(MemoryStack stack=MemoryStack.stackPush()) {
            if(!state.drawable()) {
                submitD3D12Frame(stack,context,imported,frame,-1,false);
                submitted=true;
                return d3d12Result(frame,Outcome.SKIPPED_MINIMIZED);
            }
            prepareComposition(context,layers,frame.width(),frame.height(),VK10.VK_FORMAT_R8G8B8A8_UNORM);
            if(context.d3d12Inputs!=null){context.d3d12Inputs.externalLayer=backgroundLayers;context.d3d12Inputs.linearSrgb=backgroundLayers>0||frame.alphaMode()==D3D12SharedFrame.AlphaMode.PREMULTIPLIED;}
            IntBuffer index=stack.ints(0);
            int acquire=acquireNextImage(device,swapchain,UINT64_MAX,context.acquireSemaphore,0L,index);
            if(acquire==KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
                submitD3D12Frame(stack,context,imported,frame,-1,false);
                submitted=true;
                recreateSwapchain();
                return d3d12Result(frame,Outcome.RETIRED_FOR_RECREATE);
            }
            if(acquire!=VK10.VK_SUCCESS&&acquire!=KHRSwapchain.VK_SUBOPTIMAL_KHR)checkVk(acquire,"vkAcquireNextImageKHR.d3d12");
            imageIndex=index.get(0);
            prepareOverlay(context);
            recordCopy(context.commandBuffer,imported.image(),VK10.VK_FORMAT_R8G8B8A8_UNORM,
                frame.width(),frame.height(),swapchainImages[imageIndex],true,context);
            submitD3D12Frame(stack,context,imported,frame,imageIndex,true);
            submitted=true;
            if(context.d3d12Inputs!=null)context.d3d12Inputs.submitted();
            var present=VkPresentInfoKHR.calloc(stack).sType$Default()
                .pWaitSemaphores(stack.longs(presentSemaphore(context,imageIndex))).swapchainCount(1)
                .pSwapchains(stack.longs(swapchain)).pImageIndices(stack.ints(imageIndex));
            var timing=VulkanQueueHostSync.presentTimed(queue,present,
                (target,info)->KHRSwapchain.vkQueuePresentKHR(target,info));
            presentSamples++;presentQueueLockNanos+=timing.queueLockWaitNanos();
            presentNativeCallNanos+=timing.nativeCallNanos();
            presentNanos+=timing.queueLockWaitNanos()+timing.nativeCallNanos();
            int result=timing.result();
            if(result==KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR||result==KHRSwapchain.VK_SUBOPTIMAL_KHR||acquire==KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                recreateSwapchain();
                return d3d12Result(frame,Outcome.RETIRED_FOR_RECREATE);
            }
            checkVk(result,"vkQueuePresentKHR.d3d12");
            if(context.captureReadback!=null)awaitFrameContext(context);
            activateOnFirstFrame();
            return d3d12Result(frame,Outcome.PRESENTED);
        } catch(RuntimeException|Error failure) {
            if(!submitted) {
                try(MemoryStack cleanup=MemoryStack.stackPush()) {
                    // Retire an accepted producer slot even if recording/acquisition failed.
                    submitD3D12Frame(cleanup,context,imported,frame,imageIndex,false);
                    // No present was queued for this acquired WSI image. Rebuild after retiring
                    // the empty submission so a recoverable recording error cannot leak images.
                    if(imageIndex>=0)recreateSwapchain();
                } catch(RuntimeException|Error releaseFailure) { failure.addSuppressed(releaseFailure); }
            }
            throw failure;
        }
    }

    @Override
    public void composeD3D12SharedFrame(D3D12SharedFrame frame,List<FrameCompositionPlan.Layer> overlays) {
        composeExternalFrame(frame,overlays,0);
    }
    @Override public void composeD3D12SharedFrame(D3D12SharedFrame frame,FrameCompositionPlan.Layer background,List<FrameCompositionPlan.Layer> overlays) {
        composeExternalFrame(frame,backgroundLayers(background,overlays),1);
    }
    private void composeExternalFrame(D3D12SharedFrame frame,List<FrameCompositionPlan.Layer> overlays,int backgroundLayers) {
        requireOwnerAndOpen();
        var layers=checkedCompositionLayers(overlays);
        var imported=importD3D12Frame(frame);
        FrameContext context=frameContexts[nextFrameContext];
        nextFrameContext=(nextFrameContext+1)%frameContexts.length;
        awaitFrameContext(context);
        boolean submitted=false;
        try(MemoryStack stack=MemoryStack.stackPush()) {
            if(!layers.isEmpty()) {
                prepareComposition(context,layers,frame.width(),frame.height(),VK10.VK_FORMAT_R8G8B8A8_UNORM);
                if(context.d3d12Inputs!=null){context.d3d12Inputs.externalLayer=backgroundLayers;context.d3d12Inputs.linearSrgb=backgroundLayers>0||frame.alphaMode()==D3D12SharedFrame.AlphaMode.PREMULTIPLIED;}
                recordD3D12ComposeBack(context,imported.image(),frame.width(),frame.height());
            }
            submitD3D12Frame(stack,context,imported,frame,-1,!layers.isEmpty());
            submitted=true;
            if(context.d3d12Inputs!=null)context.d3d12Inputs.submitted();
        } catch(RuntimeException|Error failure) {
            if(!submitted) {
                try(MemoryStack cleanup=MemoryStack.stackPush()) {
                    submitD3D12Frame(cleanup,context,imported,frame,-1,false);
                } catch(RuntimeException|Error releaseFailure) { failure.addSuppressed(releaseFailure); }
            }
            throw failure;
        }
    }

    private VulkanD3D12ImportedFrame importD3D12Frame(D3D12SharedFrame frame) {
        Objects.requireNonNull(frame,"frame");
        if(!timelineSemaphoreEnabled)throw new UnsupportedOperationException("D3D12 fence import requires Vulkan timeline semaphores");
        if(frame.frameSequence()<=lastD3D12Sequence)throw new IllegalArgumentException("External frame sequence must strictly advance");
        var imported=d3d12Frames.get(frame.resourceId());
        if(imported==null) {
            imported=VulkanD3D12ImportedFrame.open(physicalDevice,device,frame);
            d3d12Frames.put(frame.resourceId(),imported);
        }
        imported.requireNext(frame);
        return imported;
    }

    private void recordD3D12ComposeBack(FrameContext context,long image,int width,int height) {
        var commands=context.commandBuffer;
        checkVk(VK10.vkResetCommandBuffer(commands,0),"vkResetCommandBuffer.composeBack");
        try(MemoryStack stack=MemoryStack.stackPush()) {
            checkVk(VK10.vkBeginCommandBuffer(commands,VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)),"vkBeginCommandBuffer.composeBack");
            imageBarrier(stack,commands,image,VK10.VK_IMAGE_LAYOUT_GENERAL,VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                0,VK10.VK_ACCESS_TRANSFER_READ_BIT,VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK11.VK_QUEUE_FAMILY_EXTERNAL,queueFamilyIndex);
            long output=d3d12Composition.record(commands,stack,context.slot,image,width,height,context.d3d12Inputs);
            imageBarrier(stack,commands,image,VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK10.VK_ACCESS_TRANSFER_READ_BIT,VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK10.VK_QUEUE_FAMILY_IGNORED,VK10.VK_QUEUE_FAMILY_IGNORED);
            var copy=VkImageCopy.calloc(1,stack);
            copy.srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            copy.dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            copy.extent().set(width,height,1);
            VK10.vkCmdCopyImage(commands,output,VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,image,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,copy);
            imageBarrier(stack,commands,image,VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK10.VK_IMAGE_LAYOUT_GENERAL,
                VK10.VK_ACCESS_TRANSFER_WRITE_BIT,0,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                queueFamilyIndex,VK11.VK_QUEUE_FAMILY_EXTERNAL);
            checkVk(VK10.vkEndCommandBuffer(commands),"vkEndCommandBuffer.composeBack");
        }
    }

    private void submitD3D12Frame(MemoryStack stack,FrameContext context,VulkanD3D12ImportedFrame imported,
            D3D12SharedFrame frame,int imageIndex,boolean commands) {
        boolean acquired=imageIndex>=0;
        boolean presents=commands&&acquired;
        LongBuffer waits=acquired?stack.longs(imported.semaphore(),context.acquireSemaphore):stack.longs(imported.semaphore());
        LongBuffer signals=presents?stack.longs(imported.semaphore(),presentSemaphore(context,imageIndex)):stack.longs(imported.semaphore());
        var values=VkTimelineSemaphoreSubmitInfo.calloc(stack).sType$Default()
            .pWaitSemaphoreValues(acquired?stack.longs(frame.readyValue(),0L):stack.longs(frame.readyValue()))
            .pSignalSemaphoreValues(presents?stack.longs(frame.completionValue(),0L):stack.longs(frame.completionValue()));
        var submit=VkSubmitInfo.calloc(1,stack).sType$Default().pNext(values.address()).waitSemaphoreCount(waits.remaining())
            .pWaitSemaphores(waits).pWaitDstStageMask(acquired
                ?stack.ints(VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT)
                :stack.ints(VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT)).pSignalSemaphores(signals);
        if(commands)submit.pCommandBuffers(stack.pointers(context.commandBuffer.address()));
        checkVk(VK10.vkResetFences(device,context.fence),"vkResetFences.d3d12Consumer");
        checkVk(VulkanQueueHostSync.submit(queue,submit,context.fence),"vkQueueSubmit.d3d12Consumer");
        context.fencePending=true;context.d3d12Frame=imported;
        if(presents&&context.captureReadback!=null)context.captureReadback.submitted();
        imported.submitted(frame.readyValue());
        lastD3D12Sequence=frame.frameSequence();
    }

    private static PresentationResult d3d12Result(D3D12SharedFrame frame,Outcome outcome) {
        return new PresentationResult(frame.frameSequence(),frame.width(),frame.height(),outcome);
    }

    @Override
    public void awaitCompositionConsumers() {
        requireOwnerAndOpen();
        retireAllFrameContexts();
    }

    @Override
    public void retireD3D12SharedFrames() {
        requireOwnerAndOpen();
        retireAllFrameContexts();
        for(var frame:d3d12Frames.values())frame.close();
        d3d12Frames.clear();
        if(d3d12Composition!=null){d3d12Composition.close();d3d12Composition=null;}
        lastD3D12Sequence=-1;
    }

    private PresentationResult presentAndRelease(
            GpuFrameLease lease,
            boolean allowDeferredManagedRetirement,
            List<FrameCompositionPlan.Layer> overlays
    ) {
        requireOwnerAndOpen();
        retryPendingTagRetirement();
        GpuFrameLease checkedLease = Objects.requireNonNull(lease, "lease");
        if (checkedLease.state() != GpuFrameLease.LeaseState.ACTIVE) {
            throw new IllegalArgumentException("presenter requires an active frame lease");
        }
        GpuFrameLease.FrameDescriptor descriptor = checkedLease.descriptor();
        PresentationResult result = null;
        Throwable failure = null;
        try {
            WindowState state = windowState();
            if (!state.drawable()) {
                result = result(descriptor, Outcome.SKIPPED_MINIMIZED);
            } else {
                ensureSwapchain(state.framebufferWidth(), state.framebufferHeight());
                result = presentDrawable(checkedLease, descriptor, overlays);
                if (result.outcome() == Outcome.RETIRED_FOR_RECREATE) recreateSwapchain();
            }
        } catch (Throwable presentFailure) {
            failure = presentFailure;
        } finally {
            if (!managedLeaseRetained(checkedLease)) {
                try {
                    retireTagsAndLease(checkedLease);
                } catch (Throwable closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
        }
        boolean retirementOwnedByContext = managedLeaseRetained(checkedLease);
        if (retirementOwnedByContext && !allowDeferredManagedRetirement) {
            try {
                awaitRetainedManagedLease(checkedLease);
            } catch (Throwable retirementFailure) {
                if (failure == null) failure = retirementFailure;
                else failure.addSuppressed(retirementFailure);
            }
        }
        if (failure != null) rethrow(failure);
        return Objects.requireNonNull(result, "presentation result");
    }

    /**
     * Completes vendor tag retirement, lease release, and host backlog notification as one
     * retryable transaction. The field is installed before any fallible step so a failed null-tag
     * can never return the slot to the producer while Streamline still references its images.
     */
    private void retireTagsAndLease(GpuFrameLease lease) {
        if (pendingTagRetirementLease != null && pendingTagRetirementLease != lease) {
            throw new IllegalStateException("another frame is awaiting vendor tag retirement");
        }
        pendingTagRetirementLease = lease;
        retryPendingTagRetirement();
    }

    private void retryPendingTagRetirement() {
        GpuFrameLease pending = pendingTagRetirementLease;
        if (pending == null) return;
        long frameSequence = pending.descriptor().frameSequence();
        if (swapchainInterceptor != null) swapchainInterceptor.retireFrame(frameSequence);
        if (pending.state() == GpuFrameLease.LeaseState.ACTIVE
                && pending instanceof VulkanManagedFrameLease rendererLease) {
            // Every submitted non-retained path waits its frame context before reaching here;
            // pre-submit failures performed no consumer GPU access. Both cases are a stronger
            // completion proof than exporting a synthetic semaphore merely to close the lease.
            rendererLease.releaseAfterPresenterAccessComplete();
        }
        pending.close();
        if (pending.state() != GpuFrameLease.LeaseState.CLOSED) {
            throw new IllegalStateException("retired frame lease did not reach CLOSED state");
        }
        frameRetiredCallback.accept(frameSequence);
        pendingTagRetirementLease = null;
    }

    /**
     * Retires completed proxy-present contexts even when the producer has no free slot from which
     * to create another frame. Without this progress point, retained leases can fill the producer
     * admission bound and prevent the next context reuse that previously performed retirement.
     */
    private void pollCompletedManagedRetirements() {
        if (frameContexts == null) return;
        for (FrameContext context : frameContexts) {
            if (context.retainedManagedLease == null) continue;
            if (!context.fencePending) {
                throw new IllegalStateException("retained managed lease has no retirement fence");
            }
            int status = VK10.vkGetFenceStatus(device, context.fence);
            if (status == VK10.VK_NOT_READY) continue;
            checkVk(status, "vkGetFenceStatus.managedRetirement");
            awaitFrameContext(context);
        }
    }

    private void awaitRetainedManagedLease(GpuFrameLease lease) {
        if (frameContexts == null) {
            throw new IllegalStateException("managed lease retained without frame contexts");
        }
        for (FrameContext context : frameContexts) {
            if (!context.retains(lease)) continue;
            awaitFrameContext(context);
            return;
        }
        throw new IllegalStateException("managed lease retention context disappeared");
    }

    private PresentationResult presentDrawable(
            GpuFrameLease lease,
            GpuFrameLease.FrameDescriptor descriptor,
            List<FrameCompositionPlan.Layer> overlays
    ) {
        if (sharedDeviceFastPath && lease instanceof VulkanManagedFrameLease managedLease) {
            VulkanManagedFrameLease.NativeFrame nativeFrame = managedLease.managedNativeFrame();
            if (nativeFrame.device().address() == device.address()
                    && nativeFrame.queueFamilyIndex() == queueFamilyIndex) {
                return presentManagedDrawable(lease, managedLease, nativeFrame, descriptor, overlays);
            }
        }
        return presentExternalDrawable(lease, descriptor, overlays);
    }

    private PresentationResult presentManagedDrawable(
            GpuFrameLease lease,
            VulkanManagedFrameLease managedLease,
            VulkanManagedFrameLease.NativeFrame source,
            GpuFrameLease.FrameDescriptor descriptor,
            List<FrameCompositionPlan.Layer> overlays
    ) {
        requireImportable(descriptor);
        /*
         * Acquire only after a completed renderer frame reaches the consumer. Acquiring
         * immediately after the previous present blocks while the display engine still owns all
         * other images and serializes that wait onto every frame. The renderer's asynchronous
         * trace naturally gives the presentation engine time to release an image before this
         * demand-driven acquire, matching a conventional RHI frame boundary.
         */
        ManagedAcquisition acquisition = acquireManagedImage(overlays,descriptor);
        if (acquisition.result == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
            return result(descriptor, Outcome.RETIRED_FOR_RECREATE);
        }
        if (acquisition.result != VK10.VK_SUCCESS
                && acquisition.result != KHRSwapchain.VK_SUBOPTIMAL_KHR) {
            checkVk(acquisition.result, "vkAcquireNextImageKHR.managed");
        }
        FrameContext context = acquisition.context;
        int imageIndex = acquisition.imageIndex;
        prepareOverlay(context);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            boolean submitted = false;
            boolean retirementSubmitted = false;
            try {
                if (source.readyTimelineSemaphore() != 0L) {
                    recordCopy(
                            context.commandBuffer,
                            source.image(),
                            descriptor.format().value(),
                            descriptor.width(),
                            descriptor.height(),
                            swapchainImages[imageIndex],
                            source.externallyOwned(),
                            context
                    );
                    submitDedicatedManagedCopy(stack, context, source, imageIndex);
                    submitted = true;
                } else {
                    RtCommandContext.AsyncSubmission managedSubmission;
                    managedSubmission = sharedRuntime.frameCommands()
                            .submitTimedBinarySynchronizedOneTimeAsync(
                                    MANAGED_COPY_TIMING_LABEL,
                                    context.acquireSemaphore,
                                    presentSemaphore(context, imageIndex),
                                    (commandBuffer, commandStack) -> recordCopyCommands(
                                            commandBuffer,
                                            commandStack,
                                            source.image(),
                                            descriptor.format().value(),
                                            descriptor.width(),
                                            descriptor.height(),
                                            swapchainImages[imageIndex],
                                            source.externallyOwned(),
                                            context
                                    )
                            );
                    context.managedSubmission = managedSubmission;
                    if (context.captureReadback != null) context.captureReadback.submitted();
                    submitted = true;
                }
                if (context.d3d12Inputs != null) context.d3d12Inputs.submitted();

                /*
                 * VkSwapchainKHR is externally synchronized. In particular, vkQueuePresentKHR
                 * and vkAcquireNextImageKHR must never race on different host threads. Keep both
                 * calls on the presenter owner thread just as a conventional RHI viewport does;
                 * the renderer submission lane remains independently asynchronous.
                 */
                VulkanQueueHostSync.TimedPresent presentTiming = presentManagedImage(
                        context, imageIndex, descriptor.frameSequence()
                );
                int presented = presentTiming.result();
                if (source.readyTimelineSemaphore() != 0L) {
                    submitManagedRetirementFence(stack, context);
                    retirementSubmitted = true;
                    retainManagedLease(context, lease);
                } else {
                    managedLease.releaseAfterManagedQueueSubmission();
                    lease.close();
                }
                presentQueueLockNanos = Math.addExact(
                        presentQueueLockNanos, presentTiming.queueLockWaitNanos()
                );
                presentNativeCallNanos = Math.addExact(
                        presentNativeCallNanos, presentTiming.nativeCallNanos()
                );
                presentNanos = Math.addExact(
                        presentNanos,
                        Math.addExact(
                                presentTiming.queueLockWaitNanos(), presentTiming.nativeCallNanos()
                        )
                );
                presentSamples = Math.incrementExact(presentSamples);
                if (context.captureReadback != null) awaitFrameContext(context);
                if (presented == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR
                        || presented == KHRSwapchain.VK_SUBOPTIMAL_KHR
                        || acquisition.result == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                    return result(descriptor, Outcome.RETIRED_FOR_RECREATE);
                }
                checkVk(presented, "vkQueuePresentKHR.managed");
                activateOnFirstFrame();
                return result(descriptor, Outcome.PRESENTED);
            } finally {
                if (submitted && lease.state() == GpuFrameLease.LeaseState.ACTIVE
                        && !context.retains(lease)) {
                    if (source.readyTimelineSemaphore() != 0L && !retirementSubmitted) {
                        submitManagedRetirementFence(stack, context);
                    }
                    awaitFrameContext(context);
                }
            }
        }
    }

    private void submitDedicatedManagedCopy(
            MemoryStack stack,
            FrameContext context,
            VulkanManagedFrameLease.NativeFrame source,
            int imageIndex
    ) {
        if (source.readyTimelineSemaphore() == 0L || source.readyTimelineValue() <= 0L) {
            throw new IllegalArgumentException("dedicated managed copy requires a ready timeline signal");
        }
        LongBuffer waitSemaphores = stack.longs(
                context.acquireSemaphore,
                source.readyTimelineSemaphore()
        );
        IntBuffer waitStages = stack.ints(
                VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
        );
        LongBuffer signalSemaphores = stack.longs(presentSemaphore(context, imageIndex));
        VkTimelineSemaphoreSubmitInfo timeline = VkTimelineSemaphoreSubmitInfo.calloc(stack)
                .sType$Default()
                .pWaitSemaphoreValues(stack.longs(0L, source.readyTimelineValue()))
                .pSignalSemaphoreValues(stack.longs(0L));
        VkSubmitInfo.Buffer submit = VkSubmitInfo.calloc(1, stack)
                .sType$Default()
                .pNext(timeline.address())
                .waitSemaphoreCount(waitSemaphores.remaining())
                .pWaitSemaphores(waitSemaphores)
                .pWaitDstStageMask(waitStages)
                .pCommandBuffers(stack.pointers(context.commandBuffer.address()))
                .pSignalSemaphores(signalSemaphores);
        checkVk(
                VulkanQueueHostSync.submit(queue, submit, VK10.VK_NULL_HANDLE),
                "vkQueueSubmit.managedPresenterDedicated"
        );
        if (context.captureReadback != null) context.captureReadback.submitted();
    }

    /**
     * Appends the slot-lifetime boundary after proxy present on the same queue.
     *
     * <p>In Streamline's default presenting-client-queue mode, this marker cannot complete until
     * the copy and the SDK's input processing ahead of it have retired. Waiting only when this
     * frame context is reused preserves bounded ownership without serializing every present on
     * the CPU.</p>
     */
    private void submitManagedRetirementFence(MemoryStack stack, FrameContext context) {
        checkVk(VK10.vkResetFences(device, context.fence), "vkResetFences.managedRetirement");
        VkSubmitInfo.Buffer marker = VkSubmitInfo.calloc(1, stack).sType$Default();
        try {
            checkVk(
                    VulkanQueueHostSync.submit(queue, marker, context.fence),
                    "vkQueueSubmit.managedRetirement"
            );
            context.fencePending = true;
        } catch (RuntimeException submissionFailure) {
            /* vkResetFences succeeded but no successful submission owns the fence. Waiting for it
             * would deadlock cleanup forever. Queue idle is the only valid CPU-completion fallback
             * after the copy submission has already escaped this method. */
            try {
                waitPresenterIdle("managedRetirementSubmitFailure");
            } catch (RuntimeException idleFailure) {
                submissionFailure.addSuppressed(idleFailure);
            }
            throw submissionFailure;
        }
    }

    private void retainManagedLease(FrameContext context, GpuFrameLease lease) {
        if (context.retainedManagedLease != null) {
            throw new IllegalStateException("frame context already retains a managed lease");
        }
        if (lease.state() != GpuFrameLease.LeaseState.ACTIVE) {
            throw new IllegalStateException("only an active managed lease may be retained");
        }
        context.retainedManagedLease = lease;
    }

    private boolean managedLeaseRetained(GpuFrameLease lease) {
        if (frameContexts == null) return false;
        for (FrameContext context : frameContexts) {
            if (context.retains(lease)) return true;
        }
        return false;
    }

    private ManagedAcquisition acquireManagedImage(List<FrameCompositionPlan.Layer> overlays,
            GpuFrameLease.FrameDescriptor descriptor) {
        FrameContext context = frameContexts[nextFrameContext];
        nextFrameContext = (nextFrameContext + 1) % frameContexts.length;
        awaitFrameContext(context);
        prepareComposition(context,overlays,descriptor.width(),descriptor.height(),descriptor.format().value());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer imageIndexBuffer = stack.ints(0);
            long acquireStart = System.nanoTime();
            int acquire = acquireNextImage(
                    device, swapchain, UINT64_MAX, context.acquireSemaphore,
                    VK10.VK_NULL_HANDLE, imageIndexBuffer
            );
            acquireNanos = Math.addExact(acquireNanos, System.nanoTime() - acquireStart);
            acquireSamples = Math.incrementExact(acquireSamples);
            int imageIndex = acquire == VK10.VK_SUCCESS
                    || acquire == KHRSwapchain.VK_SUBOPTIMAL_KHR
                    ? imageIndexBuffer.get(0)
                    : -1;
            return new ManagedAcquisition(context, imageIndex, acquire);
        }
    }

    private VulkanQueueHostSync.TimedPresent presentManagedImage(
            FrameContext context,
            int imageIndex,
            long frameSequence
    ) {
        long waitSemaphore = presentSemaphore(context, imageIndex);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack)
                    .sType$Default()
                    .pWaitSemaphores(stack.longs(waitSemaphore))
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(swapchain))
                    .pImageIndices(stack.ints(imageIndex));
            VkPresentInfoKHR.nwaitSemaphoreCount(present.address(), 1);
            return VulkanQueueHostSync.presentTimed(
                    queue, present, (targetQueue, presentInfo) ->
                            queuePresent(targetQueue, presentInfo, frameSequence)
            );
        }
    }

    private int createSwapchain(VkSwapchainCreateInfoKHR createInfo, LongBuffer output) {
        return !proxySwapchainActive()
                ? KHRSwapchain.vkCreateSwapchainKHR(device, createInfo, null, output)
                : swapchainInterceptor.createSwapchain(device, createInfo, output);
    }

    private void destroySwapchain(long value) {
        if (!proxySwapchainActive()) {
            KHRSwapchain.vkDestroySwapchainKHR(device, value, null);
        } else {
            swapchainInterceptor.destroySwapchain(device, value);
        }
    }

    private int getSwapchainImages(IntBuffer count, LongBuffer images) {
        return !proxySwapchainActive()
                ? KHRSwapchain.vkGetSwapchainImagesKHR(device, swapchain, count, images)
                : swapchainInterceptor.getSwapchainImages(device, swapchain, count, images);
    }

    private int acquireNextImage(
            VkDevice targetDevice,
            long targetSwapchain,
            long timeout,
            long semaphore,
            long fence,
            IntBuffer imageIndex
    ) {
        return !proxySwapchainActive()
                ? KHRSwapchain.vkAcquireNextImageKHR(
                        targetDevice, targetSwapchain, timeout, semaphore, fence, imageIndex
                )
                : swapchainInterceptor.acquireNextImage(
                        targetDevice, targetSwapchain, timeout, semaphore, fence, imageIndex
                );
    }

    private int queuePresent(VkQueue targetQueue, VkPresentInfoKHR presentInfo, long frameSequence) {
        final int result;
        try {
            result = !proxySwapchainActive()
                    ? KHRSwapchain.vkQueuePresentKHR(targetQueue, presentInfo)
                    : swapchainInterceptor.queuePresent(targetQueue, presentInfo, frameSequence);
        } catch (RuntimeException | Error failure) {
            observePresentationSuppressing(frameSequence, false, failure);
            throw failure;
        }
        if (sharedRuntime != null) {
            sharedRuntime.featureSession().observePresentation(
                    frameSequence,
                    result == VK10.VK_SUCCESS || result == KHRSwapchain.VK_SUBOPTIMAL_KHR
            );
        }
        return result;
    }

    private void observePresentationSuppressing(
            long frameSequence,
            boolean succeeded,
            Throwable primaryFailure
    ) {
        if (sharedRuntime == null) return;
        try {
            sharedRuntime.featureSession().observePresentation(frameSequence, succeeded);
        } catch (RuntimeException | Error observationFailure) {
            primaryFailure.addSuppressed(observationFailure);
        }
    }

    private PresentationResult presentExternalDrawable(
            GpuFrameLease lease,
            GpuFrameLease.FrameDescriptor descriptor,
            List<FrameCompositionPlan.Layer> overlays
    ) {
        FrameContext context = frameContexts[nextFrameContext];
        nextFrameContext = (nextFrameContext + 1) % frameContexts.length;
        awaitFrameContext(context);
        prepareComposition(context,overlays,descriptor.width(),descriptor.height(),descriptor.format().value());

        ImportedFrame imported = importOrReuse(lease);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer imageIndexBuffer = stack.ints(0);
            int acquire = acquireNextImage(
                    device,
                    swapchain,
                    UINT64_MAX,
                    context.acquireSemaphore,
                    VK10.VK_NULL_HANDLE,
                    imageIndexBuffer
            );
            if (acquire == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
                return result(descriptor, Outcome.RETIRED_FOR_RECREATE);
            }
            if (acquire != VK10.VK_SUCCESS && acquire != KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                checkVk(acquire, "vkAcquireNextImageKHR");
            }
            int imageIndex = imageIndexBuffer.get(0);
            prepareOverlay(context);
            recordCopy(context, imported, swapchainImages[imageIndex]);

            VulkanWin32ExternalSemaphoreProbe.ExportedSemaphore completion =
                    VulkanWin32ExternalSemaphoreProbe.exportSemaphore(device);
            long completionHandle = 0L;
            boolean submitted = false;
            try {
                completionHandle = completion.detachWin32Handle();
                checkVk(VK10.vkResetFences(device, context.fence), "vkResetFences.presenter");
                VkSubmitInfo.Buffer submit = VkSubmitInfo.calloc(1, stack)
                        .sType$Default()
                        .waitSemaphoreCount(1)
                        .pWaitSemaphores(stack.longs(context.acquireSemaphore))
                        .pWaitDstStageMask(stack.ints(VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT))
                        .pCommandBuffers(stack.pointers(context.commandBuffer.address()))
                        .pSignalSemaphores(stack.longs(
                                presentSemaphore(context, imageIndex),
                                completion.semaphore()
                        ));
                checkVk(
                        VulkanQueueHostSync.submit(queue, submit, context.fence),
                        "vkQueueSubmit.presenter"
                );
                context.fencePending = true;
                submitted = true;
                if (context.captureReadback != null) context.captureReadback.submitted();
                imported.inFlightUses++;
                context.importedFrame = imported;
                context.completionSemaphore = completion;
                completion = null;
                if (context.d3d12Inputs != null) context.d3d12Inputs.submitted();

                try {
                    lease.release(new GpuFrameLease.ExternalSemaphoreSignal(
                            completionHandle,
                            new GpuFrameLease.VulkanSemaphoreHandleType(EXTERNAL_SEMAPHORE_HANDLE_TYPE),
                            GpuFrameLease.SemaphoreKind.BINARY,
                            0L,
                            GpuFrameLease.ImportDisposition.CALLER_RETAINS_HANDLE
                    ));
                } catch (RuntimeException releaseFailure) {
                    awaitFrameContext(context);
                    throw releaseFailure;
                } finally {
                    closeWin32Handle(completionHandle, "consumer completion semaphore");
                    completionHandle = 0L;
                }
                lease.close();

                VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack)
                        .sType$Default()
                        .pWaitSemaphores(stack.longs(presentSemaphore(context, imageIndex)))
                        .swapchainCount(1)
                        .pSwapchains(stack.longs(swapchain))
                        .pImageIndices(stack.ints(imageIndex));
                VkPresentInfoKHR.nwaitSemaphoreCount(present.address(), 1);
                int presented = VulkanQueueHostSync.present(
                        queue, present, (targetQueue, presentInfo) ->
                                queuePresent(targetQueue, presentInfo, descriptor.frameSequence())
                );
                if (context.captureReadback != null) awaitFrameContext(context);
                if (presented == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR
                        || presented == KHRSwapchain.VK_SUBOPTIMAL_KHR
                        || acquire == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                    return result(descriptor, Outcome.RETIRED_FOR_RECREATE);
                }
                checkVk(presented, "vkQueuePresentKHR");
                activateOnFirstFrame();
                evictUnusedImports();
                return result(descriptor, Outcome.PRESENTED);
            } finally {
                if (completionHandle != 0L) {
                    closeWin32Handle(completionHandle, "unpublished completion semaphore");
                }
                if (completion != null) completion.close();
                if (submitted && lease.state() == GpuFrameLease.LeaseState.ACTIVE) {
                    awaitFrameContext(context);
                }
            }
        }
    }

    private void recordCopy(FrameContext context, ImportedFrame source, long destinationImage) {
        recordCopy(
                context.commandBuffer,
                source.image,
                source.signature.format,
                source.signature.width,
                source.signature.height,
                destinationImage,
                true,
                context
        );
    }

    private void recordCopy(
            VkCommandBuffer commandBuffer,
            long sourceImage,
            int sourceFormat,
            int sourceWidth,
            int sourceHeight,
            long destinationImage,
            boolean sourceExternallyOwned,
            FrameContext context
    ) {
        checkVk(VK10.vkResetCommandBuffer(commandBuffer, 0), "vkResetCommandBuffer.presenter");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            checkVk(
                    VK10.vkBeginCommandBuffer(
                            commandBuffer,
                            VkCommandBufferBeginInfo.calloc(stack)
                                    .sType$Default()
                                    .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
                    ),
                    "vkBeginCommandBuffer.presenter"
            );
            recordCopyCommands(
                    commandBuffer, stack, sourceImage, sourceFormat,
                    sourceWidth, sourceHeight, destinationImage, sourceExternallyOwned, context
            );
            checkVk(VK10.vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer.presenter");
        }
    }

    private void recordCopyCommands(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long sourceImage,
            int sourceFormat,
            int sourceWidth,
            int sourceHeight,
            long destinationImage,
            boolean sourceExternallyOwned,
            FrameContext context
    ) {
            imageBarrier(
                    stack,
                    commandBuffer,
                    sourceImage,
                    VK10.VK_IMAGE_LAYOUT_GENERAL,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    sourceExternallyOwned ? 0 : VK10.VK_ACCESS_MEMORY_WRITE_BIT,
                    VK10.VK_ACCESS_TRANSFER_READ_BIT,
                    sourceExternallyOwned
                            ? VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
                            : VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    sourceExternallyOwned ? VK11.VK_QUEUE_FAMILY_EXTERNAL : VK10.VK_QUEUE_FAMILY_IGNORED,
                    sourceExternallyOwned ? queueFamilyIndex : VK10.VK_QUEUE_FAMILY_IGNORED
            );
            imageBarrier(
                    stack,
                    commandBuffer,
                    destinationImage,
                    VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    0,
                    VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_QUEUE_FAMILY_IGNORED,
                    VK10.VK_QUEUE_FAMILY_IGNORED
            );
            long displayImage = sourceImage;
            int displayFormat = sourceFormat;
            if (sourceFormat == VK10.VK_FORMAT_R16G16B16A16_SFLOAT) {
                if (displayTransform == null) {
                    displayTransform = VulkanFrameDisplayTransform.open(
                            instance, physicalDevice, device,
                            sharedDeviceFastPath ? sharedRuntime.allocator() : 0L,
                            FRAME_CONTEXT_COUNT
                    );
                }
                displayImage = displayTransform.record(
                        commandBuffer, stack, context.slot, sourceImage, sourceWidth, sourceHeight
                );
                displayFormat = VK10.VK_FORMAT_R8G8B8A8_UNORM;
            }
            if(context.d3d12Inputs!=null) {
                displayImage=d3d12Composition.record(commandBuffer,stack,context.slot,displayImage,sourceWidth,sourceHeight,context.d3d12Inputs);
                displayFormat=VK10.VK_FORMAT_R8G8B8A8_UNORM;
            }
            if (displayFormat == swapchainFormat
                    && sourceWidth == swapchainWidth
                    && sourceHeight == swapchainHeight) {
                VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
                region.srcSubresource()
                        .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0)
                        .baseArrayLayer(0)
                        .layerCount(1);
                region.dstSubresource()
                        .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0)
                        .baseArrayLayer(0)
                        .layerCount(1);
                region.extent().set(sourceWidth, sourceHeight, 1);
                VK10.vkCmdCopyImage(
                        commandBuffer,
                        displayImage,
                        VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        destinationImage,
                        VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        region
                );
            } else {
                VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
                region.srcSubresource()
                        .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0)
                        .baseArrayLayer(0)
                        .layerCount(1);
                region.srcOffsets(0).set(0, 0, 0);
                region.srcOffsets(1).set(sourceWidth, sourceHeight, 1);
                region.dstSubresource()
                        .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0)
                        .baseArrayLayer(0)
                        .layerCount(1);
                region.dstOffsets(0).set(0, 0, 0);
                region.dstOffsets(1).set(swapchainWidth, swapchainHeight, 1);
                VK10.vkCmdBlitImage(
                        commandBuffer,
                        displayImage,
                        VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        destinationImage,
                        VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        region,
                        sourceWidth == swapchainWidth && sourceHeight == swapchainHeight
                                ? VK10.VK_FILTER_NEAREST
                                : VK10.VK_FILTER_LINEAR
                );
            }
            /* The overlay may overlap the full-frame transfer write. Order the two writes even
             * though both keep the destination in TRANSFER_DST_OPTIMAL. */
            imageBarrier(
                    stack,
                    commandBuffer,
                    destinationImage,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_QUEUE_FAMILY_IGNORED,
                    VK10.VK_QUEUE_FAMILY_IGNORED
            );
            recordOverlayCommands(commandBuffer, stack, destinationImage, context);
            if(context.captureReadback!=null) {
                imageBarrier(stack,commandBuffer,destinationImage,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK10.VK_ACCESS_TRANSFER_WRITE_BIT,VK10.VK_ACCESS_TRANSFER_READ_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_QUEUE_FAMILY_IGNORED,VK10.VK_QUEUE_FAMILY_IGNORED);
                context.captureReadback.record(commandBuffer,stack,destinationImage);
            }
            /* Streamline intercepts the ordinary Vulkan WSI calls; it does not replace the host
             * image-layout contract. Every image named by VkPresentInfoKHR therefore enters
             * PRESENT_SRC_KHR before either the native or intercepted vkQueuePresentKHR call. */
            imageBarrier(
                    stack,
                    commandBuffer,
                    destinationImage,
                    context.captureReadback!=null?VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL:VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                    context.captureReadback!=null?VK10.VK_ACCESS_TRANSFER_READ_BIT:VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                    0,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                    VK10.VK_QUEUE_FAMILY_IGNORED,
                    VK10.VK_QUEUE_FAMILY_IGNORED
            );
            imageBarrier(
                    stack,
                    commandBuffer,
                    sourceImage,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK10.VK_IMAGE_LAYOUT_GENERAL,
                    VK10.VK_ACCESS_TRANSFER_READ_BIT,
                    sourceExternallyOwned
                            ? 0
                            : VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    sourceExternallyOwned
                            ? VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT
                            : VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    sourceExternallyOwned ? queueFamilyIndex : VK10.VK_QUEUE_FAMILY_IGNORED,
                    sourceExternallyOwned ? VK11.VK_QUEUE_FAMILY_EXTERNAL : VK10.VK_QUEUE_FAMILY_IGNORED
            );
    }

    private static List<FrameCompositionPlan.Layer> checkedCompositionLayers(List<FrameCompositionPlan.Layer> overlays) {
        var layers=List.copyOf(Objects.requireNonNull(overlays,"overlays"));
        if(layers.size()>7)throw new IllegalArgumentException("Scene presentation supports at most seven Vulkan overlay layers");
        return layers;
    }

    private void prepareComposition(FrameContext context,List<FrameCompositionPlan.Layer> layers,
            int width,int height,int sourceFormat) {
        if(layers.isEmpty())return;
        if(d3d12CompositionResolver==null||sharedRuntime==null)
            throw new UnsupportedOperationException("This presenter has no renderer-bound Vulkan composition sources");
        if(sourceFormat!=VK10.VK_FORMAT_R8G8B8A8_UNORM&&sourceFormat!=VK10.VK_FORMAT_R16G16B16A16_SFLOAT)
            throw new UnsupportedOperationException("Scene composition requires RGBA8 display or RGBA16F linear scene input");
        if(d3d12Composition==null)d3d12Composition=new VulkanD3D12Composition(sharedRuntime,FRAME_CONTEXT_COUNT);
        d3d12Composition.prepare(context.slot,width,height);
        context.d3d12Inputs=d3d12CompositionResolver.resolve(layers,width,height);
    }

    private void prepareOverlay(FrameContext context) {
        if(pendingCapture!=null) {
            if(!swapchainCaptureSupported)throw new UnsupportedOperationException("Resized surface cannot capture the final frame");
            context.captureReadback=new VulkanPresentedFrameCapture(sharedRuntime,swapchainWidth,swapchainHeight,swapchainFormat,pendingCapture);
            pendingCapture=null;
        }
        int availableWidth = Math.max(0, swapchainWidth - OVERLAY_MARGIN * 2);
        int availableHeight = Math.max(0, swapchainHeight - OVERLAY_MARGIN * 2);
        if (context.overlayRevision == overlayRevision
                && context.overlayFormat == swapchainFormat
                && context.overlayTargetWidth == availableWidth
                && context.overlayTargetHeight == availableHeight) {
            return;
        }
        boolean bgra = switch (swapchainFormat) {
            case VK10.VK_FORMAT_R8G8B8A8_UNORM -> false;
            case VK10.VK_FORMAT_B8G8R8A8_UNORM -> true;
            default -> throw new IllegalStateException(
                    "unsupported presenter HUD swapchain format: " + swapchainFormat
            );
        };
        VulkanTextOverlayRasterizer.Raster raster = VulkanTextOverlayRasterizer.rasterize(
                overlayText,
                availableWidth,
                availableHeight,
                bgra
        );
        if (raster.width() > 0) context.overlayUpload.write(raster);
        context.overlayWidth = raster.width();
        context.overlayHeight = raster.height();
        context.overlaySpans = raster.copySpans();
        context.overlayRevision = overlayRevision;
        context.overlayFormat = swapchainFormat;
        context.overlayTargetWidth = availableWidth;
        context.overlayTargetHeight = availableHeight;
    }

    private static void recordOverlayCommands(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long destinationImage,
            FrameContext context
    ) {
        if (context.overlayWidth == 0 || context.overlayHeight == 0 || context.overlaySpans.isEmpty()) return;
        VkBufferMemoryBarrier.Buffer uploadReady = VkBufferMemoryBarrier.calloc(1, stack)
                .sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_HOST_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .buffer(context.overlayUpload.buffer())
                .offset(0L)
                .size(VK10.VK_WHOLE_SIZE);
        VK10.vkCmdPipelineBarrier(
                commandBuffer,
                VK10.VK_PIPELINE_STAGE_HOST_BIT,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                0,
                null,
                uploadReady,
                null
        );
        for (int first = 0; first < context.overlaySpans.size(); first += OVERLAY_COPY_BATCH_SIZE) {
            int count = Math.min(OVERLAY_COPY_BATCH_SIZE, context.overlaySpans.size() - first);
            /*
             * A full transparent HUD can contain thousands of disjoint glyph runs. A bounded
             * nested stack frame keeps native recording memory constant without retaining native
             * structs after vkCmdCopyBufferToImage has copied them into the command buffer.
             */
            try (MemoryStack copyStack = MemoryStack.stackPush()) {
                VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(count, copyStack);
                for (int local = 0; local < count; local++) {
                    VulkanTextOverlayRasterizer.CopySpan span = context.overlaySpans.get(first + local);
                    VkBufferImageCopy region = copy.get(local)
                            .bufferOffset(((long) span.y() * context.overlayWidth + span.x()) * 4L)
                            .bufferRowLength(context.overlayWidth)
                            .bufferImageHeight(0);
                    region.imageSubresource()
                            .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                            .mipLevel(0)
                            .baseArrayLayer(0)
                            .layerCount(1);
                    region.imageOffset().set(OVERLAY_MARGIN + span.x(), OVERLAY_MARGIN + span.y(), 0);
                    region.imageExtent().set(span.width(), span.height(), 1);
                }
                VK10.vkCmdCopyBufferToImage(
                        commandBuffer,
                        context.overlayUpload.buffer(),
                        destinationImage,
                        VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        copy
                );
            }
        }
    }

    private static void imageBarrier(
            MemoryStack stack,
            VkCommandBuffer commandBuffer,
            long image,
            int oldLayout,
            int newLayout,
            int sourceAccess,
            int destinationAccess,
            int sourceStage,
            int destinationStage,
            int sourceQueueFamily,
            int destinationQueueFamily
    ) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType$Default()
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcAccessMask(sourceAccess)
                .dstAccessMask(destinationAccess)
                .srcQueueFamilyIndex(sourceQueueFamily)
                .dstQueueFamilyIndex(destinationQueueFamily)
                .image(image);
        barrier.subresourceRange()
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);
        VK10.vkCmdPipelineBarrier(
                commandBuffer,
                sourceStage,
                destinationStage,
                0,
                null,
                null,
                barrier
        );
    }

    private ImportedFrame importOrReuse(GpuFrameLease lease) {
        GpuFrameLease.FrameDescriptor descriptor = lease.descriptor();
        if (lease.memoryHandle().handleType().value() != EXTERNAL_MEMORY_HANDLE_TYPE) {
            throw new UnsupportedOperationException("presenter requires OPAQUE_WIN32 frame memory");
        }
        if (lease.memoryHandle().importDisposition()
                != GpuFrameLease.ImportDisposition.CALLER_RETAINS_HANDLE) {
            throw new UnsupportedOperationException("presenter requires caller-retained OPAQUE_WIN32 memory");
        }
        if (!lease.consumerCompletionCapabilities().supports(GpuFrameLease.SemaphoreKind.BINARY)) {
            throw new UnsupportedOperationException("presenter requires binary external semaphore completion");
        }
        FrameSignature signature = FrameSignature.from(descriptor);
        ImportedFrame existing = importedFrames.get(descriptor.resourceId());
        if (existing != null) {
            if (!existing.signature.equals(signature)) {
                throw new IllegalStateException("external resourceId was reused for incompatible image metadata");
            }
            return existing;
        }
        requireImportable(descriptor);
        ImportedFrame imported = importFrame(descriptor, lease.memoryHandle(), signature);
        ImportedFrame collision = importedFrames.putIfAbsent(descriptor.resourceId(), imported);
        if (collision != null) {
            imported.close();
            throw new IllegalStateException("external resourceId collision during frame import");
        }
        return imported;
    }

    private ImportedFrame importFrame(
            GpuFrameLease.FrameDescriptor descriptor,
            GpuFrameLease.ExportedNativeHandle<GpuFrameLease.VulkanMemoryHandleType> memoryHandle,
            FrameSignature signature
    ) {
        long win32Handle = memoryHandle.value();
        if (!Win32HandleSupport.valid(win32Handle)) {
            throw new IllegalArgumentException("frame lease contains an invalid Win32 memory handle");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkExternalMemoryImageCreateInfo external = VkExternalMemoryImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .handleTypes(memoryHandle.handleType().value());
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .pNext(external.address())
                    .flags(descriptor.imageCreateFlags().value())
                    .imageType(descriptor.imageType().value())
                    .format(descriptor.format().value())
                    .mipLevels(descriptor.mipLevels())
                    .arrayLayers(descriptor.arrayLayers())
                    .samples(descriptor.sampleCount().value())
                    .tiling(descriptor.imageTiling().value())
                    .usage(descriptor.imageUsage().value())
                    .sharingMode(descriptor.sharingMode().value())
                    .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().set(descriptor.width(), descriptor.height(), 1);
            LongBuffer imageHandle = stack.longs(0L);
            checkVk(VK10.vkCreateImage(device, imageInfo, null, imageHandle), "vkCreateImage.importedFrame");
            long image = imageHandle.get(0);
            long memory = 0L;
            try {
                VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
                VK10.vkGetImageMemoryRequirements(device, image, requirements);
                int memoryTypeIndex = descriptor.memoryTypeIndex();
                if (memoryTypeIndex >= Integer.SIZE
                        || (requirements.memoryTypeBits() & (1 << memoryTypeIndex)) == 0) {
                    throw new IllegalStateException(
                            "producer memoryTypeIndex is incompatible with the imported image requirements"
                    );
                }
                VkImportMemoryWin32HandleInfoKHR importInfo =
                        VkImportMemoryWin32HandleInfoKHR.calloc(stack)
                                .sType$Default()
                                .handleType(memoryHandle.handleType().value())
                                .handle(win32Handle);
                VkMemoryAllocateInfo allocation = VkMemoryAllocateInfo.calloc(stack)
                        .sType$Default()
                        .allocationSize(descriptor.allocationSize())
                        .memoryTypeIndex(memoryTypeIndex);
                if (descriptor.dedicatedAllocation()) {
                    VkMemoryDedicatedAllocateInfo dedicated = VkMemoryDedicatedAllocateInfo.calloc(stack)
                            .sType$Default()
                            .image(image)
                            .buffer(VK10.VK_NULL_HANDLE);
                    importInfo.pNext(dedicated.address());
                }
                allocation.pNext(importInfo.address());
                LongBuffer nativeMemoryHandle = stack.longs(0L);
                checkVk(VK10.vkAllocateMemory(device, allocation, null, nativeMemoryHandle), "vkAllocateMemory.importedFrame");
                memory = nativeMemoryHandle.get(0);
                checkVk(
                        VK10.vkBindImageMemory(device, image, memory, descriptor.allocationOffset()),
                        "vkBindImageMemory.importedFrame"
                );
                if (!memoryHandle.markImported()) {
                    throw new IllegalStateException("fresh frame memory handle was already imported");
                }
                ImportedFrame result = new ImportedFrame(
                        descriptor.resourceId(), signature, image, memory
                );
                image = 0L;
                memory = 0L;
                return result;
            } finally {
                if (memory != 0L) VK10.vkFreeMemory(device, memory, null);
                if (image != 0L) VK10.vkDestroyImage(device, image, null);
            }
        }
    }

    private static void requireImportable(GpuFrameLease.FrameDescriptor descriptor) {
        if (descriptor.imageType().value() != VK10.VK_IMAGE_TYPE_2D
                || descriptor.imageTiling().value() != VK10.VK_IMAGE_TILING_OPTIMAL
                || descriptor.sampleCount().value() != VK10.VK_SAMPLE_COUNT_1_BIT
                || descriptor.sharingMode().value() != VK10.VK_SHARING_MODE_EXCLUSIVE
                || descriptor.imageLayout().value() != VK10.VK_IMAGE_LAYOUT_GENERAL
                || descriptor.mipLevels() != 1
                || descriptor.arrayLayers() != 1) {
            throw new UnsupportedOperationException("presenter requires the canonical 2D external frame contract");
        }
        if ((descriptor.imageUsage().value() & VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT) == 0) {
            throw new UnsupportedOperationException("presenter requires TRANSFER_SRC frame usage");
        }
    }

    private void ensureSwapchain(int width, int height) {
        if (swapchain == 0L || swapchainWidth != width || swapchainHeight != height
                || proxySwapchainRebuildRequired()) {
            recreateSwapchain();
        }
    }

    private void recreateSwapchain() {
        boolean oneWayFallback = proxySwapchainRebuildRequired();
        waitPresenterIdle("recreateSwapchain");
        retireAllFrameContexts();
        destroySwapchain();
        if (oneWayFallback) swapchainInterceptor.prepareOneWayFallback();
        createSwapchain();
        if (swapchainInterceptor != null) swapchainInterceptor.acknowledgeSwapchainRebuild();
    }

    private boolean proxySwapchainRebuildRequired() {
        return swapchainInterceptor != null && swapchainInterceptor.swapchainRebuildRequired();
    }

    private boolean proxySwapchainActive() {
        return swapchainInterceptor != null && swapchainInterceptor.proxyActive();
    }

    private void createSwapchain() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSurfaceCapabilitiesKHR capabilities = VkSurfaceCapabilitiesKHR.calloc(stack);
            checkVk(
                    KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, capabilities),
                    "vkGetPhysicalDeviceSurfaceCapabilitiesKHR"
            );
            WindowState state = windowState();
            if (!state.drawable()) return;
            int width = capabilities.currentExtent().width();
            int height = capabilities.currentExtent().height();
            if (width == -1) {
                width = clamp(state.framebufferWidth(), capabilities.minImageExtent().width(), capabilities.maxImageExtent().width());
                height = clamp(state.framebufferHeight(), capabilities.minImageExtent().height(), capabilities.maxImageExtent().height());
            }
            SurfaceFormat selectedFormat = selectSurfaceFormat(stack);
            int presentMode = selectPresentMode(stack);
            activePresentMode = switch (presentMode) {
                case KHRSurface.VK_PRESENT_MODE_FIFO_KHR -> SwapchainPresentMode.FIFO;
                case KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR -> SwapchainPresentMode.MAILBOX;
                case KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR -> SwapchainPresentMode.IMMEDIATE;
                default -> throw new IllegalStateException("unsupported selected present mode: " + presentMode);
            };
            int imageCount = capabilities.minImageCount() + 1;
            swapchainCaptureSupported=(capabilities.supportedUsageFlags()&VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT)!=0;
            if (capabilities.maxImageCount() > 0) imageCount = Math.min(imageCount, capabilities.maxImageCount());
            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType$Default()
                    .surface(surface)
                    .minImageCount(imageCount)
                    .imageFormat(selectedFormat.format)
                    .imageColorSpace(selectedFormat.colorSpace)
                    .imageArrayLayers(1)
                    // Streamline's Vulkan swapchain proxy creates views for generated-frame
                    // composition. COLOR_ATTACHMENT makes those views legal while TRANSFER_DST
                    // remains the renderer's only direct use of the backbuffer.
                    .imageUsage(VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT
                            | (swapchainCaptureSupported?VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT:0)
                            | VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                    .imageSharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(capabilities.currentTransform())
                    .compositeAlpha(selectCompositeAlpha(capabilities.supportedCompositeAlpha()))
                    .presentMode(presentMode)
                    .clipped(true)
                    .oldSwapchain(VK10.VK_NULL_HANDLE);
            VkSurfaceFullScreenExclusiveInfoEXT fullScreenInfo = null;
            if (fullScreenExclusiveAvailable) {
                fullScreenInfo = VkSurfaceFullScreenExclusiveInfoEXT.calloc(stack)
                        .sType$Default()
                        .fullScreenExclusive(
                                configuration.windowMode()
                                        == VulkanFramePresenterConfig.WindowMode.PRIMARY_MONITOR_FULLSCREEN
                                        ? EXTFullScreenExclusive.VK_FULL_SCREEN_EXCLUSIVE_ALLOWED_EXT
                                        : EXTFullScreenExclusive.VK_FULL_SCREEN_EXCLUSIVE_DISALLOWED_EXT
                        );
                createInfo.pNext(fullScreenInfo.address());
            }
            createInfo.imageExtent().set(width, height);
            LongBuffer handle = stack.longs(0L);
            int createResult = createSwapchain(createInfo, handle);
            if (fullScreenInfo != null && createResult == VK10.VK_ERROR_INITIALIZATION_FAILED) {
                /* Optional RHI fullscreen metadata must never make presentation unusable. */
                createInfo.pNext(VK10.VK_NULL_HANDLE);
                handle.put(0, 0L);
                createResult = createSwapchain(createInfo, handle);
            }
            checkVk(createResult, "vkCreateSwapchainKHR");
            swapchain = handle.get(0);
            swapchainFormat = selectedFormat.format;
            swapchainWidth = width;
            swapchainHeight = height;

            IntBuffer count = stack.ints(0);
            checkVk(getSwapchainImages(count, null), "vkGetSwapchainImagesKHR.count");
            LongBuffer images = stack.mallocLong(count.get(0));
            checkVk(getSwapchainImages(count, images), "vkGetSwapchainImagesKHR.list");
            swapchainImages = new long[images.remaining()];
            presentationSemaphores = !proxySwapchainActive()
                    ? new long[images.remaining()] : new long[0];
            for (int index = 0; index < images.remaining(); index++) {
                swapchainImages[index] = images.get(index);
                if (!proxySwapchainActive()) {
                    presentationSemaphores[index] = createSemaphore(stack, false);
                }
            }
        }
    }

    private SurfaceFormat selectSurfaceFormat(MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        checkVk(KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, count, null), "vkGetPhysicalDeviceSurfaceFormatsKHR.count");
        VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
        checkVk(KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, count, formats), "vkGetPhysicalDeviceSurfaceFormatsKHR.list");
        // The renderer's default SDR storage image is RGBA8. Prefer an identical swapchain format
        // so an equal-sized frame uses a raw transfer copy instead of a full-screen BGRA swizzle.
        int[] preferred = {VK10.VK_FORMAT_R8G8B8A8_UNORM, VK10.VK_FORMAT_B8G8R8A8_UNORM};
        for (int wanted : preferred) {
            for (int index = 0; index < formats.limit(); index++) {
                VkSurfaceFormatKHR candidate = formats.get(index);
                if (candidate.format() == wanted
                        && candidate.colorSpace() == KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                    return new SurfaceFormat(candidate.format(), candidate.colorSpace());
                }
            }
        }
        throw new UnsupportedOperationException("swapchain exposes no RGBA8/BGRA8 UNORM surface format");
    }

    private int selectPresentMode(MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        checkVk(KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, count, null), "vkGetPhysicalDeviceSurfacePresentModesKHR.count");
        IntBuffer modes = stack.mallocInt(count.get(0));
        checkVk(KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, count, modes), "vkGetPhysicalDeviceSurfacePresentModesKHR.list");
        return switch (configuration.presentMode()) {
            case VSYNC -> KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
            case LOW_LATENCY -> contains(modes, KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR)
                    ? KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR
                    : KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
            case UNCAPPED -> contains(modes, KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR)
                    ? KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR
                    : contains(modes, KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR)
                    ? KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR
                    : KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
        };
    }

    private static boolean contains(IntBuffer values, int wanted) {
        for (int index = values.position(); index < values.limit(); index++) {
            if (values.get(index) == wanted) return true;
        }
        return false;
    }

    private static int selectCompositeAlpha(int supported) {
        int[] preferred = {
                KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
                KHRSurface.VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
                KHRSurface.VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,
                KHRSurface.VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR
        };
        for (int value : preferred) if ((supported & value) != 0) return value;
        throw new UnsupportedOperationException("surface exposes no supported composite alpha mode");
    }

    private void awaitFrameContext(FrameContext context) {
        if (context.managedSubmission != null) {
            while (!context.managedSubmission.pollComplete()) {
                GLFW.glfwPollEvents();
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            }
            context.managedSubmission.close();
            RtCommandContext.Timing timing = context.managedSubmission.timing();
            if (timing.gpuWorkNanos() >= 0L) {
                managedCopyGpuNanos = Math.addExact(
                        managedCopyGpuNanos, timing.gpuWorkNanos()
                );
                managedCopyGpuSamples = Math.incrementExact(managedCopyGpuSamples);
            }
            context.managedSubmission = null;
        }
        if (context.fencePending) {
            int result;
            do {
                result=VK10.vkWaitForFences(device, context.fence, true, 16_000_000L);
                if(result==VK10.VK_TIMEOUT)GLFW.glfwPollEvents();
            } while(result==VK10.VK_TIMEOUT);
            checkVk(result,"vkWaitForFences.presenter");
            // Publish completion before fallible lease and host callbacks so retries never wait
            // on a fence that has already proved the queue-lifetime boundary.
            context.fencePending = false;
        }
        if (context.retainedManagedLease != null) {
            GpuFrameLease retained = context.retainedManagedLease;
            // Keep ownership attached until tag retirement, lease release, and the host callback
            // all succeed. The completed same-queue fence is the proof that Streamline's Vulkan
            // pacer has consumed this frame's tagged inputs; retiring them at proxy-call return
            // races the asynchronous pacer and suppresses generated output.
            retireTagsAndLease(retained);
            context.retainedManagedLease = null;
        }
        if (context.completionSemaphore != null) {
            context.completionSemaphore.close();
            context.completionSemaphore = null;
        }
        if(context.d3d12Frame!=null) {
            context.d3d12Frame.retired();
            context.d3d12Frame=null;
        }
        if(context.d3d12Inputs!=null) {
            context.d3d12Inputs.close();
            context.d3d12Inputs=null;
        }
        if(context.captureReadback!=null) {
            var capture=context.captureReadback;
            context.captureReadback=null;
            try { capture.finish(); } finally { capture.close(); }
        }
        if (context.importedFrame != null) {
            context.importedFrame.inFlightUses--;
            if (context.importedFrame.inFlightUses < 0) {
                throw new IllegalStateException("imported frame use count underflow");
            }
            context.importedFrame = null;
        }
    }

    private void retireAllFrameContexts() {
        if (frameContexts == null) return;
        for (FrameContext context : frameContexts) awaitFrameContext(context);
    }

    private void evictUnusedImports() {
        if (importedFrames.size() <= MAX_RETAINED_IMPORTED_IMAGES) return;
        var iterator = importedFrames.entrySet().iterator();
        while (importedFrames.size() > MAX_RETAINED_IMPORTED_IMAGES && iterator.hasNext()) {
            ImportedFrame frame = iterator.next().getValue();
            if (frame.inFlightUses != 0) continue;
            iterator.remove();
            frame.close();
        }
    }

    @Override
    public void close() {
        requireOwner();
        if (closed) return;
        closeStarted = true;
        if (!resourcesClosed) {
            closePartial(true);
            resourcesClosed = true;
        }
        // The host callback is intentionally last and retryable. A failed native teardown keeps
        // the renderer/session alive; a failed callback keeps close() retryable without touching
        // already destroyed Vulkan handles a second time.
        closeCallback.run();
        closed = true;
    }

    private void closePartial(boolean glfwAcquired) {
        RuntimeException failure = null;
        if (device != null) {
            try {
                waitPresenterIdle("presenterClose");
            } catch (RuntimeException closeFailure) {
                failure = accumulate(failure, closeFailure);
            }
            try {
                retireAllFrameContexts();
            } catch (RuntimeException closeFailure) {
                failure = accumulate(failure, closeFailure);
            }
        }
        try {
            retryPendingTagRetirement();
        } catch (RuntimeException closeFailure) {
            failure = accumulate(failure, closeFailure);
        }
        var importedIterator = importedFrames.entrySet().iterator();
        while (importedIterator.hasNext()) {
            ImportedFrame imported = importedIterator.next().getValue();
            try {
                imported.close();
                importedIterator.remove();
            } catch (RuntimeException closeFailure) {
                failure = accumulate(failure, closeFailure);
            }
        }
        // Queue completion, tag release, and imported-memory retirement are ownership proofs.
        var d3d12Iterator=d3d12Frames.entrySet().iterator();
        while(d3d12Iterator.hasNext()) {
            try { d3d12Iterator.next().getValue().close();d3d12Iterator.remove(); }
            catch(RuntimeException closeFailure) { failure=accumulate(failure,closeFailure); }
        }
        // Never destroy their device/session below after one of those proofs failed.
        if (failure != null) throw failure;
        destroySwapchain();
        if (displayTransform != null) {
            displayTransform.close();
            displayTransform = null;
        }
        if(d3d12Composition!=null) {
            d3d12Composition.close();d3d12Composition=null;
        }
        if (managedPresentationRegistered) {
            sharedRuntime.endManagedPresentation();
            managedPresentationRegistered = false;
        }
        if (device != null && frameContexts != null) {
            for (FrameContext context : frameContexts) {
                if (context == null) continue;
                if (context.completionSemaphore != null) context.completionSemaphore.close();
                if (context.acquireSemaphore != 0L) VK10.vkDestroySemaphore(device, context.acquireSemaphore, null);
                if (context.proxyPresentSemaphore != 0L) {
                    VK10.vkDestroySemaphore(device, context.proxyPresentSemaphore, null);
                }
                if (context.fence != 0L) VK10.vkDestroyFence(device, context.fence, null);
                context.overlayUpload.close();
            }
        }
        frameContexts = null;
        if (device != null && commandPool != 0L) VK10.vkDestroyCommandPool(device, commandPool, null);
        commandPool = 0L;
        if (instance != null && surface != 0L) {
            if (surfaceIntercepted) swapchainInterceptor.destroySurface(instance, surface);
            else KHRSurface.vkDestroySurfaceKHR(instance, surface, null);
        }
        surface = 0L;
        surfaceIntercepted = false;
        if (!sharedDeviceFastPath && device != null) VK10.vkDestroyDevice(device, null);
        device = null;
        if (!sharedDeviceFastPath && instance != null) VK10.vkDestroyInstance(instance, null);
        instance = null;
        physicalDevice = null;
        queue = null;
        if (window != 0L) GLFW.glfwDestroyWindow(window);
        window = 0L;
        if (glfwAcquired) GlfwRuntime.release();
    }

    private void waitPresenterIdle(String operation) {
        // Vulkan's idle call has no timeout. The owner thread must continue dispatching
        // window messages while the driver drains the queue (notably during resize).
        var idle = new java.util.concurrent.FutureTask<Integer>(() -> sharedDeviceFastPath
                ? VulkanQueueHostSync.waitIdle(queue) : VK10.vkDeviceWaitIdle(device));
        Thread.ofPlatform().daemon().name("rtr-presenter-idle").start(idle);
        while (!idle.isDone()) {
            GLFW.glfwPollEvents();
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        }
        int result;
        try { result=idle.get(); }
        catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted after presenter idle",interrupted);
        } catch(java.util.concurrent.ExecutionException failed) {
            throw new IllegalStateException("Presenter idle failed",failed.getCause());
        }
        checkVk(result, sharedDeviceFastPath
                ? "vkQueueWaitIdle." + operation
                : "vkDeviceWaitIdle." + operation);
    }

    /**
     * Selects the binary semaphore whose reuse is proven by the matching WSI contract.
     *
     * <p>A native swapchain reacquires a concrete image, so its present semaphore remains tied to
     * that image. Streamline instead exposes an off-screen proxy index and asynchronously pairs
     * each present wait with the acquire signal for the next frame context. Binding both proxy
     * semaphores to the context preserves that pair without pretending the proxy index identifies
     * the real presentable image.</p>
     */
    private long presentSemaphore(FrameContext context, int imageIndex) {
        return proxySwapchainActive()
                ? context.proxyPresentSemaphore
                : presentationSemaphores[imageIndex];
    }

    private void destroySwapchain() {
        if (device == null) return;
        // The vendor proxy may reject eOff/null-tag cleanup and deliberately retain its
        // swapchain for retry. Keep every Java-side handle intact until that transaction succeeds.
        if (swapchain != 0L) {
            destroySwapchain(swapchain);
            swapchain = 0L;
        }
        for (long semaphore : presentationSemaphores) {
            if (semaphore != 0L) VK10.vkDestroySemaphore(device, semaphore, null);
        }
        presentationSemaphores = new long[0];
        swapchainImages = new long[0];
        swapchainFormat = 0;
        swapchainWidth = 0;
        swapchainHeight = 0;
    }

    private long createSemaphore(MemoryStack stack, boolean exportable) {
        VkSemaphoreCreateInfo createInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
        if (exportable) {
            VkExportSemaphoreCreateInfo export = VkExportSemaphoreCreateInfo.calloc(stack)
                    .sType$Default()
                    .handleTypes(EXTERNAL_SEMAPHORE_HANDLE_TYPE);
            createInfo.pNext(export.address());
        }
        LongBuffer handle = stack.longs(0L);
        checkVk(VK10.vkCreateSemaphore(device, createInfo, null, handle), "vkCreateSemaphore.presenter");
        return handle.get(0);
    }

    private long createFence(MemoryStack stack, boolean signaled) {
        LongBuffer handle = stack.longs(0L);
        checkVk(
                VK10.vkCreateFence(
                        device,
                        VkFenceCreateInfo.calloc(stack)
                                .sType$Default()
                                .flags(signaled ? VK10.VK_FENCE_CREATE_SIGNALED_BIT : 0),
                        null,
                        handle
                ),
                "vkCreateFence.presenter"
        );
        return handle.get(0);
    }

    private static String stableDeviceId(MemoryStack stack, VkPhysicalDevice device) {
        VkPhysicalDeviceIDProperties identity = VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();
        VkPhysicalDeviceProperties2 properties = VkPhysicalDeviceProperties2.calloc(stack)
                .sType$Default()
                .pNext(identity.address());
        VK11.vkGetPhysicalDeviceProperties2(device, properties);
        StringBuilder result = new StringBuilder(VK10.VK_UUID_SIZE * 2);
        for (int index = 0; index < VK10.VK_UUID_SIZE; index++) {
            int value = identity.deviceUUID(index) & 0xff;
            result.append(Character.forDigit(value >>> 4, 16));
            result.append(Character.forDigit(value & 0x0f, 16));
        }
        return result.toString();
    }

    private static int firstSetBit(int bits) {
        return bits == 0 ? -1 : Integer.numberOfTrailingZeros(bits);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private static PresentationResult result(
            GpuFrameLease.FrameDescriptor descriptor,
            Outcome outcome
    ) {
        return new PresentationResult(
                descriptor.frameSequence(), descriptor.width(), descriptor.height(), outcome
        );
    }

    private static void closeWin32Handle(long handle, String label) {
        if (handle == 0L) return;
        if (!Win32HandleSupport.close(handle)) {
            throw new IllegalStateException(
                    "failed to close " + label + ", Win32 error=" + Win32HandleSupport.lastError()
            );
        }
    }

    private static void checkVk(int result, String operation) {
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with VkResult " + result);
        }
    }

    private static RuntimeException accumulate(RuntimeException failure, RuntimeException next) {
        if (failure == null) return next;
        failure.addSuppressed(next);
        return failure;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new IllegalStateException("Vulkan presentation failed", failure);
    }

    private void requireOwnerAndOpen() {
        requireOwner();
        if (closed || closeStarted) throw new IllegalStateException("Vulkan frame presenter is closing or closed");
    }

    private void requireOwner() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Vulkan frame presenter is thread-affine to its opening thread");
        }
    }

    private final class ImportedFrame implements AutoCloseable {
        private final long resourceId;
        private final FrameSignature signature;
        private long image;
        private long memory;
        private int inFlightUses;

        private ImportedFrame(long resourceId, FrameSignature signature, long image, long memory) {
            this.resourceId = resourceId;
            this.signature = signature;
            this.image = image;
            this.memory = memory;
        }

        @Override
        public void close() {
            if (inFlightUses != 0) {
                throw new IllegalStateException("cannot close imported frame while GPU work is in flight: " + resourceId);
            }
            if (image != 0L) VK10.vkDestroyImage(device, image, null);
            image = 0L;
            if (memory != 0L) VK10.vkFreeMemory(device, memory, null);
            memory = 0L;
        }
    }

    private static final class FrameContext {
        private final int slot;
        private final VkCommandBuffer commandBuffer;
        private final long acquireSemaphore;
        private final long proxyPresentSemaphore;
        private final long fence;
        private final VulkanTextOverlayUpload overlayUpload;
        private VulkanWin32ExternalSemaphoreProbe.ExportedSemaphore completionSemaphore;
        private ImportedFrame importedFrame;
        private VulkanD3D12ImportedFrame d3d12Frame;
        private VulkanD3D12CompositionInputs d3d12Inputs;
        private VulkanPresentedFrameCapture captureReadback;
        private RtCommandContext.AsyncSubmission managedSubmission;
        private GpuFrameLease retainedManagedLease;
        private boolean fencePending;
        private long overlayRevision = Long.MIN_VALUE;
        private int overlayFormat;
        private int overlayTargetWidth;
        private int overlayTargetHeight;
        private int overlayWidth;
        private int overlayHeight;
        private List<VulkanTextOverlayRasterizer.CopySpan> overlaySpans = List.of();

        private FrameContext(
                int slot,
                VkCommandBuffer commandBuffer,
                long acquireSemaphore,
                long proxyPresentSemaphore,
                long fence,
                VulkanTextOverlayUpload overlayUpload
        ) {
            this.slot = slot;
            this.commandBuffer = commandBuffer;
            this.acquireSemaphore = acquireSemaphore;
            this.proxyPresentSemaphore = proxyPresentSemaphore;
            this.fence = fence;
            this.overlayUpload = Objects.requireNonNull(overlayUpload, "overlayUpload");
        }

        private boolean retains(GpuFrameLease lease) {
            return retainedManagedLease == lease;
        }
    }

    private record SurfaceFormat(int format, int colorSpace) {
    }

    private record ManagedAcquisition(FrameContext context, int imageIndex, int result) {
        private ManagedAcquisition {
            Objects.requireNonNull(context, "context");
            boolean acquired = result == VK10.VK_SUCCESS
                    || result == KHRSwapchain.VK_SUBOPTIMAL_KHR;
            if (acquired != (imageIndex >= 0)) {
                throw new IllegalArgumentException("managed acquisition result and image index diverged");
            }
        }
    }


    private record FrameSignature(
            int width,
            int height,
            int format,
            int imageType,
            int tiling,
            int usage,
            int flags,
            int mipLevels,
            int arrayLayers,
            int sampleCount,
            int sharingMode,
            int memoryTypeIndex,
            long allocationSize,
            long allocationOffset,
            boolean dedicated
    ) {
        private static FrameSignature from(GpuFrameLease.FrameDescriptor descriptor) {
            return new FrameSignature(
                    descriptor.width(),
                    descriptor.height(),
                    descriptor.format().value(),
                    descriptor.imageType().value(),
                    descriptor.imageTiling().value(),
                    descriptor.imageUsage().value(),
                    descriptor.imageCreateFlags().value(),
                    descriptor.mipLevels(),
                    descriptor.arrayLayers(),
                    descriptor.sampleCount().value(),
                    descriptor.sharingMode().value(),
                    descriptor.memoryTypeIndex(),
                    descriptor.allocationSize(),
                    descriptor.allocationOffset(),
                    descriptor.dedicatedAllocation()
            );
        }
    }

    /** GLFW is process-global, so presenters share one same-thread reference-counted lifetime. */
    private static final class GlfwRuntime {
        private static Thread owner;
        private static int references;

        private GlfwRuntime() {
        }

        private static synchronized void acquire() {
            Thread current = Thread.currentThread();
            if (references != 0 && owner != current) {
                throw new IllegalStateException("all managed Vulkan presenters must share one GLFW thread");
            }
            if (references == 0) {
                if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW initialization failed");
                owner = current;
            }
            references++;
        }

        private static synchronized void release() {
            if (references <= 0 || owner != Thread.currentThread()) {
                throw new IllegalStateException("GLFW presenter ownership is unbalanced");
            }
            references--;
            if (references == 0) {
                GLFW.glfwTerminate();
                owner = null;
            }
        }
    }
}

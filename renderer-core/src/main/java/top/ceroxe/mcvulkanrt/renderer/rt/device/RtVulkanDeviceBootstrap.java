package top.ceroxe.mcvulkanrt.renderer.rt.device;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceBufferDeviceAddressFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import top.ceroxe.mcvulkanrt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.mcvulkanrt.diagnostics.VulkanValidationFileLogger;
import top.ceroxe.mcvulkanrt.renderer.RendererLog;
import top.ceroxe.mcvulkanrt.renderer.rt.pipeline.RtRayTracingPipeline;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Owns Vulkan instance, selected physical device, logical device, and VMA allocator bootstrap. */
final class RtVulkanDeviceBootstrap implements AutoCloseable {
    private static final String APPLICATION_NAME = "MCVulkanRT";
    private static final String ENGINE_NAME = "MCVulkanRT-RTCore";

    private final RtResourceScope resources;
    private final VkPhysicalDevice physicalDevice;
    private final VkDevice device;
    private final long allocator;
    private final RtVulkanDeviceCapabilities.StablePhysicalDeviceProperties properties;
    private final List<String> enabledExtensions;
    private final int queueFamilyIndex;
    private final int requestedQueueCount;
    private final int accelerationStructureScratchAlignment;

    private RtVulkanDeviceBootstrap(
            RtResourceScope resources,
            SelectedPhysicalDevice physicalDevice,
            VkDevice device,
            long allocator,
            List<String> enabledExtensions,
            int queueFamilyIndex,
            int requestedQueueCount,
            int accelerationStructureScratchAlignment
    ) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.physicalDevice = Objects.requireNonNull(physicalDevice, "physicalDevice").device();
        this.properties = physicalDevice.properties();
        this.device = Objects.requireNonNull(device, "device");
        if (allocator == 0L || queueFamilyIndex < 0 || requestedQueueCount <= 0
                || accelerationStructureScratchAlignment <= 0) {
            throw new IllegalArgumentException("Vulkan bootstrap contains invalid native resources");
        }
        this.allocator = allocator;
        this.enabledExtensions = List.copyOf(enabledExtensions);
        this.queueFamilyIndex = queueFamilyIndex;
        this.requestedQueueCount = requestedQueueCount;
        this.accelerationStructureScratchAlignment = accelerationStructureScratchAlignment;
    }

    static RtVulkanDeviceBootstrap open(VulkanRtCapabilityProbe.Result capability) {
        return open(capability, false);
    }

    static RtVulkanDeviceBootstrap open(
            VulkanRtCapabilityProbe.Result capability,
            boolean validationEnabled
    ) {
        VulkanRtCapabilityProbe.Result requiredCapability = Objects.requireNonNull(capability, "capability");
        VulkanRtCapabilityProbe.DeviceReport preferred = requiredCapability.preferredDevice();
        if (preferred == null) {
            throw new IllegalStateException("capability result does not contain a hardware RT ready device");
        }
        RtResourceScope resources = new RtResourceScope();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanInstanceHandle instanceHandle = createInstance(stack, validationEnabled);
            resources.retain("vulkan instance", instanceHandle);
            SelectedPhysicalDevice physicalDevice = selectPhysicalDevice(stack, instanceHandle.instance(), preferred);
            SelectedQueueFamily queueFamily = selectQueueFamily(stack, physicalDevice.device());
            List<String> enabledExtensions = RtVulkanDeviceCapabilities.requiredDeviceExtensions(
                    physicalDevice.extensions(), physicalDevice.properties().apiVersion());
            int requestedQueueCount = VulkanRtDeviceContext.requestedQueueCount(queueFamily.availableQueues());
            VkDevice device = createDevice(
                    stack, physicalDevice.device(), queueFamily.index(), requestedQueueCount, enabledExtensions);
            resources.retain("vulkan logical device", () -> closeDevice(device));
            int scratchAlignment = queryAccelerationStructureScratchAlignment(stack, physicalDevice.device());
            requireBootstrapStorageImageFormat(stack, physicalDevice.device());
            long allocator = createAllocator(
                    stack, instanceHandle.instance(), physicalDevice.device(), device, physicalDevice.properties().apiVersion());
            resources.retain("vma allocator", () -> Vma.vmaDestroyAllocator(allocator));
            return new RtVulkanDeviceBootstrap(
                    resources, physicalDevice, device, allocator, enabledExtensions, queueFamily.index(),
                    requestedQueueCount, scratchAlignment);
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            try {
                resources.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    VkPhysicalDevice physicalDevice() { return physicalDevice; }
    VkDevice device() { return device; }
    long allocator() { return allocator; }
    RtVulkanDeviceCapabilities.StablePhysicalDeviceProperties properties() { return properties; }
    List<String> enabledExtensions() { return enabledExtensions; }
    int queueFamilyIndex() { return queueFamilyIndex; }
    int requestedQueueCount() { return requestedQueueCount; }
    int accelerationStructureScratchAlignment() { return accelerationStructureScratchAlignment; }

    @Override
    public void close() {
        resources.close();
    }

    private static VulkanInstanceHandle createInstance(MemoryStack stack, boolean validationEnabled) {
        VulkanValidationFileLogger validationLogger = VulkanValidationFileLogger.openIfEnabled(validationEnabled);
        if (validationLogger != null) {
            RendererLog.info("Vulkan validation enabled; JSONL output={}", validationLogger.logPath());
        }
        VkInstance createdInstance = null;
        try {
            VkApplicationInfo applicationInfo = VkApplicationInfo.calloc(stack).sType$Default()
                    .pApplicationName(stack.UTF8(APPLICATION_NAME)).applicationVersion(VK10.VK_MAKE_VERSION(0, 1, 0))
                    .pEngineName(stack.UTF8(ENGINE_NAME)).engineVersion(VK10.VK_MAKE_VERSION(0, 1, 0))
                    .apiVersion(selectInstanceApiVersion());
            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(applicationInfo);
            if (validationLogger != null) {
                validationLogger.configureInstanceCreateInfo(stack, createInfo);
            }
            PointerBuffer handle = stack.mallocPointer(1);
            checkVk(VK10.vkCreateInstance(createInfo, null, handle), "vkCreateInstance");
            createdInstance = new VkInstance(handle.get(0), createInfo);
            if (validationLogger != null) {
                validationLogger.createMessenger(stack, createdInstance);
            }
            return new VulkanInstanceHandle(createdInstance, validationLogger);
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            if (createdInstance != null) VK10.vkDestroyInstance(createdInstance, null);
            if (validationLogger != null) validationLogger.close();
            throw failure;
        }
    }

    private static int selectInstanceApiVersion() {
        try {
            if (VK.getInstanceVersionSupported() >= VK12.VK_API_VERSION_1_2) return VK12.VK_API_VERSION_1_2;
        } catch (RuntimeException | LinkageError ignored) {
            // Vulkan 1.0 loaders do not expose instance-version discovery.
        }
        return VK10.VK_API_VERSION_1_0;
    }

    private static SelectedPhysicalDevice selectPhysicalDevice(
            MemoryStack stack, VkInstance instance, VulkanRtCapabilityProbe.DeviceReport preferred) {
        IntBuffer count = stack.ints(0);
        checkVk(VK10.vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices.count");
        if (count.get(0) == 0) throw new IllegalStateException("Vulkan loader reported no physical devices");
        PointerBuffer devices = stack.mallocPointer(count.get(0));
        checkVk(VK10.vkEnumeratePhysicalDevices(instance, count, devices), "vkEnumeratePhysicalDevices.devices");
        SelectedPhysicalDevice fallbackDiscrete = null;
        for (int index = 0; index < count.get(0); index++) {
            VkPhysicalDevice device = new VkPhysicalDevice(devices.get(index), instance);
            try (MemoryStack deviceStack = MemoryStack.stackPush()) {
                VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(deviceStack);
                VK10.vkGetPhysicalDeviceProperties(device, properties);
                Set<String> extensions = RtVulkanDeviceCapabilities.enumerateDeviceExtensions(deviceStack, device);
                var features = RtVulkanDeviceCapabilities.queryRtFeatures(deviceStack, device, properties.apiVersion());
                if (!RtVulkanDeviceCapabilities.hardwareRtReady(properties.apiVersion(), extensions, features)) continue;
                SelectedPhysicalDevice candidate = new SelectedPhysicalDevice(
                        device, RtVulkanDeviceCapabilities.copyProperties(properties), extensions);
                if (properties.vendorID() == preferred.vendorId() && properties.deviceID() == preferred.deviceId()
                        && properties.deviceType() == preferred.deviceType()) return candidate;
                if (properties.deviceType() == VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU && fallbackDiscrete == null) {
                    fallbackDiscrete = candidate;
                }
            }
        }
        if (fallbackDiscrete != null) return fallbackDiscrete;
        throw new IllegalStateException("no discrete physical device satisfies Vulkan RT device creation requirements");
    }

    private static SelectedQueueFamily selectQueueFamily(MemoryStack stack, VkPhysicalDevice device) {
        IntBuffer count = stack.ints(0);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);
        if (count.get(0) == 0) throw new IllegalStateException("selected device reports no queue families");
        VkQueueFamilyProperties.Buffer properties = VkQueueFamilyProperties.malloc(count.get(0), stack);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(device, count, properties);
        SelectedQueueFamily compute = null;
        SelectedQueueFamily graphics = null;
        for (int index = 0; index < properties.limit(); index++) {
            VkQueueFamilyProperties property = properties.get(index);
            boolean hasGraphics = (property.queueFlags() & VK10.VK_QUEUE_GRAPHICS_BIT) != 0;
            boolean hasCompute = (property.queueFlags() & VK10.VK_QUEUE_COMPUTE_BIT) != 0;
            if (hasGraphics && hasCompute) return new SelectedQueueFamily(index, property.queueCount());
            if (hasCompute && compute == null) compute = new SelectedQueueFamily(index, property.queueCount());
            if (hasGraphics && graphics == null) graphics = new SelectedQueueFamily(index, property.queueCount());
        }
        if (compute != null) return compute;
        if (graphics != null) return graphics;
        throw new IllegalStateException("selected device has no graphics or compute queue family");
    }

    private static VkDevice createDevice(
            MemoryStack stack, VkPhysicalDevice physicalDevice, int queueFamilyIndex,
            int requestedQueueCount, List<String> enabledExtensions) {
        VkPhysicalDeviceAccelerationStructureFeaturesKHR acceleration =
                VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default().accelerationStructure(true);
        VkPhysicalDeviceRayTracingPipelineFeaturesKHR rayTracing =
                VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack).sType$Default().rayTracingPipeline(true);
        VkPhysicalDeviceBufferDeviceAddressFeatures address =
                VkPhysicalDeviceBufferDeviceAddressFeatures.calloc(stack).sType$Default().bufferDeviceAddress(true);
        VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
        features.features().shaderInt64(true);
        features.pNext(acceleration.address());
        acceleration.pNext(rayTracing.address());
        rayTracing.pNext(address.address());
        VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
                .sType(VK10.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO).queueFamilyIndex(queueFamilyIndex)
                .pQueuePriorities(queuePriorities(stack, requestedQueueCount));
        PointerBuffer extensionNames = stack.mallocPointer(enabledExtensions.size());
        for (String extension : enabledExtensions) extensionNames.put(stack.UTF8(extension));
        extensionNames.flip();
        VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack).sType$Default()
                .pNext(features.address()).pQueueCreateInfos(queueInfo).ppEnabledExtensionNames(extensionNames);
        PointerBuffer handle = stack.mallocPointer(1);
        checkVk(VK10.vkCreateDevice(physicalDevice, createInfo, null, handle), "vkCreateDevice");
        return new VkDevice(handle.get(0), physicalDevice, createInfo);
    }

    private static FloatBuffer queuePriorities(MemoryStack stack, int count) {
        FloatBuffer priorities = stack.mallocFloat(count);
        for (int index = 0; index < count; index++) priorities.put(1.0F);
        return priorities.flip();
    }

    private static long createAllocator(
            MemoryStack stack, VkInstance instance, VkPhysicalDevice physicalDevice, VkDevice device, int apiVersion) {
        VmaVulkanFunctions functions = VmaVulkanFunctions.calloc(stack).set(instance, device);
        VmaAllocatorCreateInfo info = VmaAllocatorCreateInfo.calloc(stack)
                .flags(Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT).physicalDevice(physicalDevice)
                .device(device).instance(instance).vulkanApiVersion(apiVersion).pVulkanFunctions(functions);
        PointerBuffer handle = stack.mallocPointer(1);
        checkVk(Vma.vmaCreateAllocator(info, handle), "vmaCreateAllocator");
        return handle.get(0);
    }

    private static int queryAccelerationStructureScratchAlignment(MemoryStack stack, VkPhysicalDevice physicalDevice) {
        VkPhysicalDeviceAccelerationStructurePropertiesKHR acceleration =
                VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
        VkPhysicalDeviceProperties2 properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(acceleration);
        VK11.vkGetPhysicalDeviceProperties2(physicalDevice, properties);
        int alignment = acceleration.minAccelerationStructureScratchOffsetAlignment();
        if (alignment <= 0) throw new IllegalStateException("invalid acceleration structure scratch alignment: " + alignment);
        return alignment;
    }

    private static void requireBootstrapStorageImageFormat(MemoryStack stack, VkPhysicalDevice physicalDevice) {
        VkFormatProperties properties = VkFormatProperties.calloc(stack);
        int format = RtRayTracingPipeline.bootstrapOutputFormat();
        VK10.vkGetPhysicalDeviceFormatProperties(physicalDevice, format, properties);
        if ((properties.optimalTilingFeatures() & VK10.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) == 0) {
            throw new IllegalStateException("selected device does not support storage image format: " + format);
        }
    }

    private static void closeDevice(VkDevice device) {
        int result = VK10.vkDeviceWaitIdle(device);
        VK10.vkDestroyDevice(device, null);
        checkVk(result, "vkDeviceWaitIdle");
    }

    private static void checkVk(int result, String stage) {
        if (result != VK10.VK_SUCCESS) throw new IllegalStateException(stage + " failed: " + result);
    }

    private record VulkanInstanceHandle(VkInstance instance, VulkanValidationFileLogger logger) implements AutoCloseable {
        @Override public void close() {
            try { if (logger != null) logger.destroyMessenger(instance); }
            finally {
                try { VK10.vkDestroyInstance(instance, null); }
                finally { if (logger != null) logger.close(); }
            }
        }
    }

    private record SelectedPhysicalDevice(
            VkPhysicalDevice device,
            RtVulkanDeviceCapabilities.StablePhysicalDeviceProperties properties,
            Set<String> extensions) {}

    private record SelectedQueueFamily(int index, int availableQueues) {
        private SelectedQueueFamily {
            if (index < 0 || availableQueues <= 0) throw new IllegalArgumentException("invalid queue family");
        }
    }
}

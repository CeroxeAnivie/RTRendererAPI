package top.ceroxe.rt.renderer.rt.device;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.diagnostics.VulkanValidationFileLogger;
import top.ceroxe.rt.renderer.RendererLog;
import top.ceroxe.rt.renderer.feature.Vulkan12Feature;
import top.ceroxe.rt.renderer.feature.Vulkan13Feature;
import top.ceroxe.rt.renderer.feature.VulkanFeaturePlan;
import top.ceroxe.rt.renderer.feature.VulkanQueueRequirements;
import top.ceroxe.rt.renderer.rt.pipeline.RtRayTracingPipeline;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Owns Vulkan instance, selected physical device, logical device, and VMA allocator bootstrap.
 */
final class RtVulkanDeviceBootstrap implements AutoCloseable {
    private static final String APPLICATION_NAME = "RTRenderer";
    private static final String ENGINE_NAME = "RTRenderer-RTCore";

    private final RtResourceScope resources;
    private final VkInstance instance;
    private final VkPhysicalDevice physicalDevice;
    private final VkDevice device;
    private final long allocator;
    private final RtVulkanDeviceCapabilities.StablePhysicalDeviceProperties properties;
    private final List<String> enabledExtensions;
    private final List<String> enabledInstanceExtensions;
    private final int queueFamilyIndex;
    private final int requestedQueueCount;
    private final boolean timelineSemaphoreEnabled;
    private final int accelerationStructureScratchAlignment;
    private final boolean memoryBudgetEnabled;
    private final boolean shaderExecutionReorderingEnabled;
    private final boolean samplerAnisotropyEnabled;
    private final boolean dynamicRenderingEnabled;
    private final Map<String, top.ceroxe.rt.renderer.feature.VulkanFeatureQueueAllocation> featureQueues;

    private RtVulkanDeviceBootstrap(
            RtResourceScope resources,
            VulkanInstanceHandle instance,
            SelectedPhysicalDevice physicalDevice,
            VkDevice device,
            long allocator,
            List<String> enabledExtensions,
            int queueFamilyIndex,
            int requestedQueueCount,
            boolean timelineSemaphoreEnabled,
            int accelerationStructureScratchAlignment,
            boolean memoryBudgetEnabled,
            boolean shaderExecutionReorderingEnabled,
            boolean samplerAnisotropyEnabled,
            boolean dynamicRenderingEnabled,
            Map<String, top.ceroxe.rt.renderer.feature.VulkanFeatureQueueAllocation> featureQueues
    ) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.instance = Objects.requireNonNull(instance, "instance").instance();
        this.physicalDevice = Objects.requireNonNull(physicalDevice, "physicalDevice").device();
        this.properties = physicalDevice.properties();
        this.device = Objects.requireNonNull(device, "device");
        if (allocator == 0L || queueFamilyIndex < 0 || requestedQueueCount <= 0
                || accelerationStructureScratchAlignment <= 0
                || (requestedQueueCount > 1 && !timelineSemaphoreEnabled)) {
            throw new IllegalArgumentException("Vulkan bootstrap contains invalid native resources");
        }
        this.allocator = allocator;
        this.enabledExtensions = List.copyOf(enabledExtensions);
        this.enabledInstanceExtensions = instance.enabledExtensions();
        this.queueFamilyIndex = queueFamilyIndex;
        this.requestedQueueCount = requestedQueueCount;
        this.timelineSemaphoreEnabled = timelineSemaphoreEnabled;
        this.accelerationStructureScratchAlignment = accelerationStructureScratchAlignment;
        this.memoryBudgetEnabled = memoryBudgetEnabled;
        this.shaderExecutionReorderingEnabled = shaderExecutionReorderingEnabled;
        this.samplerAnisotropyEnabled = samplerAnisotropyEnabled;
        this.dynamicRenderingEnabled = dynamicRenderingEnabled;
        this.featureQueues = Map.copyOf(featureQueues);
    }

    static RtVulkanDeviceBootstrap open(VulkanRtCapabilityProbe.Result capability) {
        return open(capability, false);
    }

    static RtVulkanDeviceBootstrap open(
            VulkanRtCapabilityProbe.Result capability,
            boolean validationEnabled
    ) {
        return open(
                capability, validationEnabled, Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), VulkanQueueRequirements.NONE, VulkanQueueRequirements.NONE,
                Map.of(), Map.of()
        );
    }

    static RtVulkanDeviceBootstrap open(
            VulkanRtCapabilityProbe.Result capability,
            boolean validationEnabled,
            VulkanFeaturePlan featurePlan
    ) {
        VulkanFeaturePlan checkedPlan = Objects.requireNonNull(featurePlan, "featurePlan");
        return open(
                capability,
                validationEnabled,
                checkedPlan.requiredInstanceExtensions(),
                checkedPlan.preferredInstanceExtensions(),
                checkedPlan.requiredDeviceExtensions(),
                checkedPlan.preferredDeviceExtensions(),
                checkedPlan.requiredVulkan12Features(),
                checkedPlan.preferredVulkan12Features(),
                checkedPlan.requiredVulkan13Features(),
                checkedPlan.preferredVulkan13Features(),
                checkedPlan.requiredQueues(),
                checkedPlan.preferredQueues(),
                checkedPlan.providerRequiredQueueRequirements(),
                checkedPlan.providerPreferredQueueRequirements()
        );
    }

    private static RtVulkanDeviceBootstrap open(
            VulkanRtCapabilityProbe.Result capability,
            boolean validationEnabled,
            Set<String> featureRequiredInstanceExtensions,
            Set<String> featurePreferredInstanceExtensions,
            Set<String> featureRequiredExtensions,
            Set<String> featurePreferredExtensions,
            Set<Vulkan12Feature> requiredVulkan12Features,
            Set<Vulkan12Feature> preferredVulkan12Features,
            Set<Vulkan13Feature> requiredVulkan13Features,
            Set<Vulkan13Feature> preferredVulkan13Features,
            VulkanQueueRequirements requiredQueues,
            VulkanQueueRequirements preferredQueues,
            Map<String, VulkanQueueRequirements> providerRequiredQueueRequirements,
            Map<String, VulkanQueueRequirements> providerPreferredQueueRequirements
    ) {
        VulkanRtCapabilityProbe.Result requiredCapability = Objects.requireNonNull(capability, "capability");
        VulkanRtCapabilityProbe.DeviceReport preferred = requiredCapability.preferredDevice();
        if (preferred == null) {
            throw new IllegalStateException("capability result does not contain a hardware RT ready device");
        }
        RtResourceScope resources = new RtResourceScope();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanInstanceHandle instanceHandle = createInstance(
                    stack,
                    validationEnabled,
                    featureRequiredInstanceExtensions,
                    featurePreferredInstanceExtensions
            );
            resources.retain("vulkan instance", instanceHandle);
            SelectedPhysicalDevice physicalDevice = selectPhysicalDevice(stack, instanceHandle.instance(), preferred);
            VulkanQueueFamilyCapabilities queueFamily = VulkanQueueFamilyCapabilities.select(
                    stack, physicalDevice.device()
            );
            DeviceFeatureSelection featureSelection = DeviceFeatureSelection.resolve(
                    stack,
                    physicalDevice.device(),
                    physicalDevice.properties().apiVersion(),
                    requiredVulkan12Features, preferredVulkan12Features,
                    requiredVulkan13Features, preferredVulkan13Features,
                    featureRequiredExtensions, featurePreferredExtensions,
                    physicalDevice.extensions()
            );
            boolean timelineSemaphoreEnabled = RtVulkanDeviceCapabilities.timelineSemaphoreSupported(
                    stack, physicalDevice.device(), physicalDevice.properties().apiVersion(), physicalDevice.extensions());
            List<String> enabledExtensions = RtVulkanDeviceCapabilities.requiredDeviceExtensions(
                    physicalDevice.extensions(),
                    physicalDevice.properties().apiVersion(),
                    featureRequiredExtensions,
                    featurePreferredExtensions
            );
            int coreQueueCount = RtDeviceQueueContexts.requestedQueueCount(
                    queueFamily.availableQueues(), timelineSemaphoreEnabled,
                    requiredQueues.additionalGraphicsComputeQueues(),
                    preferredQueues.additionalGraphicsComputeQueues()
            );
            QueueTopology queueTopology = QueueTopology.resolve(
                    stack, physicalDevice.device(), queueFamily, coreQueueCount, requiredQueues, preferredQueues,
                    providerRequiredQueueRequirements, providerPreferredQueueRequirements
            );
            int requestedQueueCount = queueTopology.primaryQueueCount();
            VkDevice device = createDevice(
                    stack, physicalDevice.device(), physicalDevice.properties().apiVersion(),
                    queueTopology, timelineSemaphoreEnabled,
                    featureSelection, enabledExtensions);
            resources.retain("vulkan logical device", () -> closeDevice(device));
            int scratchAlignment = queryAccelerationStructureScratchAlignment(stack, physicalDevice.device());
            requireBootstrapStorageImageFormat(stack, physicalDevice.device());
            boolean memoryBudgetEnabled = enabledExtensions.contains(
                    EXTMemoryBudget.VK_EXT_MEMORY_BUDGET_EXTENSION_NAME);
            long allocator = createAllocator(
                    stack, instanceHandle.instance(), physicalDevice.device(), device,
                    physicalDevice.properties().apiVersion(), memoryBudgetEnabled);
            resources.retain("vma allocator", () -> Vma.vmaDestroyAllocator(allocator));
            return new RtVulkanDeviceBootstrap(
                    resources, instanceHandle, physicalDevice, device, allocator, enabledExtensions, queueFamily.index(),
                    requestedQueueCount, timelineSemaphoreEnabled, scratchAlignment, memoryBudgetEnabled,
                    featureSelection.shaderExecutionReordering(),
                    featureSelection.samplerAnisotropy(),
                    featureSelection.vulkan13Features().contains(Vulkan13Feature.DYNAMIC_RENDERING),
                    queueTopology.featureQueues());
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            try {
                resources.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    boolean dynamicRenderingEnabled() {
        return dynamicRenderingEnabled;
    }

    private static VulkanInstanceHandle createInstance(
            MemoryStack stack,
            boolean validationEnabled,
            Set<String> featureRequiredExtensions,
            Set<String> featurePreferredExtensions
    ) {
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
            List<String> enabledInstanceExtensions = presentationInstanceExtensions(
                    stack, featureRequiredExtensions, featurePreferredExtensions
            );
            if (!enabledInstanceExtensions.isEmpty()) {
                PointerBuffer extensionNames = stack.mallocPointer(enabledInstanceExtensions.size());
                for (String extension : enabledInstanceExtensions) extensionNames.put(stack.UTF8(extension));
                createInfo.ppEnabledExtensionNames(extensionNames.flip());
            }
            if (validationLogger != null) {
                validationLogger.configureInstanceCreateInfo(stack, createInfo);
            }
            PointerBuffer handle = stack.mallocPointer(1);
            checkVk(VK10.vkCreateInstance(createInfo, null, handle), "vkCreateInstance");
            createdInstance = new VkInstance(handle.get(0), createInfo);
            if (validationLogger != null) {
                validationLogger.createMessenger(stack, createdInstance);
            }
            return new VulkanInstanceHandle(createdInstance, validationLogger, enabledInstanceExtensions);
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            if (createdInstance != null) VK10.vkDestroyInstance(createdInstance, null);
            if (validationLogger != null) validationLogger.close();
            throw failure;
        }
    }

    private static List<String> presentationInstanceExtensions(
            MemoryStack stack,
            Set<String> requiredFeatureExtensions,
            Set<String> preferredFeatureExtensions
    ) {
        IntBuffer count = stack.ints(0);
        checkVk(
                VK10.vkEnumerateInstanceExtensionProperties((String) null, count, null),
                "vkEnumerateInstanceExtensionProperties.count"
        );
        VkExtensionProperties.Buffer properties = VkExtensionProperties.malloc(count.get(0), stack);
        checkVk(
                VK10.vkEnumerateInstanceExtensionProperties((String) null, count, properties),
                "vkEnumerateInstanceExtensionProperties.values"
        );
        Set<String> available = new HashSet<>(properties.remaining());
        for (int index = 0; index < properties.limit(); index++) {
            available.add(properties.get(index).extensionNameString());
        }
        List<String> enabled = new ArrayList<>(3);
        if (available.contains(KHRSurface.VK_KHR_SURFACE_EXTENSION_NAME)
                && available.contains(KHRWin32Surface.VK_KHR_WIN32_SURFACE_EXTENSION_NAME)) {
            enabled.add(KHRSurface.VK_KHR_SURFACE_EXTENSION_NAME);
            enabled.add(KHRWin32Surface.VK_KHR_WIN32_SURFACE_EXTENSION_NAME);
            if (available.contains(
                    KHRGetSurfaceCapabilities2.VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME
            )) {
                enabled.add(
                        KHRGetSurfaceCapabilities2.VK_KHR_GET_SURFACE_CAPABILITIES_2_EXTENSION_NAME
                );
            }
        }
        for (String extension : requiredFeatureExtensions) {
            if (!available.contains(extension)) {
                throw new IllegalStateException("required Vulkan instance extension is unavailable: " + extension);
            }
            if (!enabled.contains(extension)) enabled.add(extension);
        }
        for (String extension : preferredFeatureExtensions) {
            if (available.contains(extension) && !enabled.contains(extension)) enabled.add(extension);
        }
        return List.copyOf(enabled);
    }

    private static int selectInstanceApiVersion() {
        try {
            if (VK.getInstanceVersionSupported() >= VK13.VK_API_VERSION_1_3) return VK13.VK_API_VERSION_1_3;
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
        for (int index = 0; index < count.get(0); index++) {
            VkPhysicalDevice device = new VkPhysicalDevice(devices.get(index), instance);
            try (MemoryStack deviceStack = MemoryStack.stackPush()) {
                VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(deviceStack);
                VK10.vkGetPhysicalDeviceProperties(device, properties);
                Set<String> extensions = RtVulkanDeviceCapabilities.enumerateDeviceExtensions(deviceStack, device);
                var features = RtVulkanDeviceCapabilities.queryRtFeatures(deviceStack, device, properties.apiVersion());
                if (!RtVulkanDeviceCapabilities.hardwareRtReady(properties.apiVersion(), extensions, features))
                    continue;
                String stableId = RtVulkanDeviceCapabilities.stableDeviceId(
                        deviceStack, device, properties.apiVersion());
                SelectedPhysicalDevice candidate = new SelectedPhysicalDevice(
                        device, RtVulkanDeviceCapabilities.copyProperties(properties), extensions);
                if (stableId.equals(preferred.stableId())) return candidate;
            }
        }
        throw new IllegalStateException(
                "selected physical device is no longer available or no longer satisfies Vulkan RT requirements: "
                        + preferred.stableId());
    }

    private static VkDevice createDevice(
            MemoryStack stack,
            VkPhysicalDevice physicalDevice,
            int apiVersion,
            QueueTopology queueTopology,
            boolean timelineSemaphoreEnabled,
            DeviceFeatureSelection featureSelection,
            List<String> enabledExtensions
    ) {
        VkPhysicalDeviceAccelerationStructureFeaturesKHR acceleration =
                VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default().accelerationStructure(true);
        VkPhysicalDeviceRayTracingPipelineFeaturesKHR rayTracing =
                VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack).sType$Default().rayTracingPipeline(true);
        VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
        features.features().shaderInt64(true);
        features.features().samplerAnisotropy(featureSelection.samplerAnisotropy());
        features.pNext(acceleration.address());
        acceleration.pNext(rayTracing.address());
        long featureChainTail = rayTracing.address();
        if (apiVersion >= VK12.VK_API_VERSION_1_2) {
            VkPhysicalDeviceVulkan12Features features12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
                    .sType$Default()
                    .bufferDeviceAddress(true)
                    .timelineSemaphore(timelineSemaphoreEnabled);
            featureSelection.vulkan12Features().forEach(feature -> feature.enable(features12));
            appendFeatureChain(featureChainTail, features12.address());
            featureChainTail = features12.address();
        } else {
            VkPhysicalDeviceBufferDeviceAddressFeatures address =
                    VkPhysicalDeviceBufferDeviceAddressFeatures.calloc(stack)
                            .sType$Default()
                            .bufferDeviceAddress(true);
            appendFeatureChain(featureChainTail, address.address());
            featureChainTail = address.address();
            if (timelineSemaphoreEnabled) {
                VkPhysicalDeviceTimelineSemaphoreFeatures timeline =
                        VkPhysicalDeviceTimelineSemaphoreFeatures.calloc(stack)
                                .sType$Default()
                                .timelineSemaphore(true);
                appendFeatureChain(featureChainTail, timeline.address());
                featureChainTail = timeline.address();
            }
        }
        if (!featureSelection.vulkan13Features().isEmpty()) {
            VkPhysicalDeviceVulkan13Features features13 = VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default();
            featureSelection.vulkan13Features().forEach(feature -> feature.enable(features13));
            appendFeatureChain(featureChainTail, features13.address());
            featureChainTail = features13.address();
        }
        if (featureSelection.computeDerivativeGroupQuads()) {
            VkPhysicalDeviceComputeShaderDerivativesFeaturesKHR computeDerivatives =
                    VkPhysicalDeviceComputeShaderDerivativesFeaturesKHR.calloc(stack)
                            .sType$Default()
                            .computeDerivativeGroupQuads(true);
            appendFeatureChain(featureChainTail, computeDerivatives.address());
            featureChainTail = computeDerivatives.address();
        }
        if (featureSelection.shaderExecutionReordering()) {
            VkPhysicalDeviceRayTracingInvocationReorderFeaturesNV invocationReorder =
                    VkPhysicalDeviceRayTracingInvocationReorderFeaturesNV.calloc(stack)
                            .sType$Default()
                            .rayTracingInvocationReorder(true);
            appendFeatureChain(featureChainTail, invocationReorder.address());
            featureChainTail = invocationReorder.address();
        }
        int queueInfoCount = queueTopology.opticalFlowFamilyIndex() < 0 ? 1 : 2;
        VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(queueInfoCount, stack);
        queueInfo.get(0).sType$Default().queueFamilyIndex(queueTopology.primaryFamilyIndex())
                .pQueuePriorities(queuePriorities(stack, queueTopology.primaryQueueCount()));
        if (queueInfoCount == 2) {
            queueInfo.get(1).sType$Default().queueFamilyIndex(queueTopology.opticalFlowFamilyIndex())
                    .pQueuePriorities(queuePriorities(stack, queueTopology.opticalFlowQueueCount()));
        }
        PointerBuffer extensionNames = stack.mallocPointer(enabledExtensions.size());
        for (String extension : enabledExtensions) extensionNames.put(stack.UTF8(extension));
        extensionNames.flip();
        VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack).sType$Default()
                .pNext(features.address()).pQueueCreateInfos(queueInfo).ppEnabledExtensionNames(extensionNames);
        PointerBuffer handle = stack.mallocPointer(1);
        checkVk(VK10.vkCreateDevice(physicalDevice, createInfo, null, handle), "vkCreateDevice");
        return new VkDevice(handle.get(0), physicalDevice, createInfo);
    }

    /** Appends a stack-owned feature structure without replacing a previous provider's request. */
    private static void appendFeatureChain(long tailAddress, long nextAddress) {
        VkBaseOutStructure.create(tailAddress).pNext(VkBaseOutStructure.create(nextAddress));
    }

    private record DeviceFeatureSelection(
            Set<Vulkan12Feature> vulkan12Features,
            Set<Vulkan13Feature> vulkan13Features,
            boolean computeDerivativeGroupQuads,
            boolean shaderExecutionReordering,
            boolean samplerAnisotropy
    ) {
        private DeviceFeatureSelection {
            vulkan12Features = Set.copyOf(vulkan12Features);
            vulkan13Features = Set.copyOf(vulkan13Features);
        }

        private static DeviceFeatureSelection resolve(
                MemoryStack stack,
                VkPhysicalDevice physicalDevice,
                int apiVersion,
                Set<Vulkan12Feature> required12,
                Set<Vulkan12Feature> preferred12,
                Set<Vulkan13Feature> required13,
                Set<Vulkan13Feature> preferred13,
                Set<String> requiredExtensions,
                Set<String> preferredExtensions,
                Set<String> availableExtensions
        ) {
            EnumSet<Vulkan12Feature> enabled12 = EnumSet.noneOf(Vulkan12Feature.class);
            EnumSet<Vulkan13Feature> enabled13 = EnumSet.noneOf(Vulkan13Feature.class);
            if (!required12.isEmpty() || !preferred12.isEmpty()) {
                if (apiVersion < VK12.VK_API_VERSION_1_2) {
                    if (!required12.isEmpty()) {
                        throw new IllegalStateException("required Vulkan 1.2 features need Vulkan 1.2: " + required12);
                    }
                } else {
                    VkPhysicalDeviceVulkan12Features supported =
                            VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
                    VkPhysicalDeviceFeatures2 query = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default()
                            .pNext(supported.address());
                    VK11.vkGetPhysicalDeviceFeatures2(physicalDevice, query);
                    enableSupported(required12, enabled12, supported, "required Vulkan 1.2 feature");
                    enableOptional(preferred12, enabled12, supported);
                }
            }
            if (!required13.isEmpty() || !preferred13.isEmpty()) {
                if (apiVersion < VK13.VK_API_VERSION_1_3) {
                    if (!required13.isEmpty()) {
                        throw new IllegalStateException("required Vulkan 1.3 features need Vulkan 1.3: " + required13);
                    }
                } else {
                    VkPhysicalDeviceVulkan13Features supported =
                            VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default();
                    VkPhysicalDeviceFeatures2 query = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default()
                            .pNext(supported.address());
                    VK11.vkGetPhysicalDeviceFeatures2(physicalDevice, query);
                    enableSupported(required13, enabled13, supported, "required Vulkan 1.3 feature");
                    enableOptional(preferred13, enabled13, supported);
                }
            }

            String serExtension = NVRayTracingInvocationReorder
                    .VK_NV_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME;
            boolean serRequired = requiredExtensions.contains(serExtension);
            boolean serRequested = serRequired || preferredExtensions.contains(serExtension);
            boolean serEnabled = false;
            if (serRequested && availableExtensions.contains(serExtension)) {
                VkPhysicalDeviceRayTracingInvocationReorderFeaturesNV supported =
                        VkPhysicalDeviceRayTracingInvocationReorderFeaturesNV.calloc(stack).sType$Default();
                VkPhysicalDeviceFeatures2 query = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default()
                        .pNext(supported.address());
                VK11.vkGetPhysicalDeviceFeatures2(physicalDevice, query);
                serEnabled = supported.rayTracingInvocationReorder();
            }
            if (serRequired && !serEnabled) {
                throw new IllegalStateException(
                        "required Vulkan feature is unavailable: rayTracingInvocationReorder"
                );
            }
            String computeDerivativesExtension = KHRComputeShaderDerivatives
                    .VK_KHR_COMPUTE_SHADER_DERIVATIVES_EXTENSION_NAME;
            boolean computeDerivativesRequired = requiredExtensions.contains(computeDerivativesExtension);
            boolean computeDerivativesRequested = computeDerivativesRequired
                    || preferredExtensions.contains(computeDerivativesExtension);
            boolean computeDerivativeGroupQuads = false;
            if (computeDerivativesRequested && availableExtensions.contains(computeDerivativesExtension)) {
                VkPhysicalDeviceComputeShaderDerivativesFeaturesKHR supported =
                        VkPhysicalDeviceComputeShaderDerivativesFeaturesKHR.calloc(stack).sType$Default();
                VkPhysicalDeviceFeatures2 query = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default()
                        .pNext(supported.address());
                VK11.vkGetPhysicalDeviceFeatures2(physicalDevice, query);
                computeDerivativeGroupQuads = supported.computeDerivativeGroupQuads();
            }
            if (computeDerivativesRequired && !computeDerivativeGroupQuads) {
                throw new IllegalStateException(
                        "required Vulkan feature is unavailable: computeDerivativeGroupQuads"
                );
            }
            VkPhysicalDeviceFeatures2 coreFeatures = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            VK11.vkGetPhysicalDeviceFeatures2(physicalDevice, coreFeatures);
            return new DeviceFeatureSelection(
                    enabled12, enabled13, computeDerivativeGroupQuads, serEnabled,
                    coreFeatures.features().samplerAnisotropy()
            );
        }

        private static void enableSupported(
                Set<Vulkan12Feature> required,
                EnumSet<Vulkan12Feature> enabled,
                VkPhysicalDeviceVulkan12Features supported,
                String label
        ) {
            for (Vulkan12Feature feature : required) {
                if (!feature.supportedBy(supported)) throw new IllegalStateException(label + " is unavailable: " + feature.streamlineName());
                enabled.add(feature);
            }
        }

        private static void enableOptional(
                Set<Vulkan12Feature> preferred,
                EnumSet<Vulkan12Feature> enabled,
                VkPhysicalDeviceVulkan12Features supported
        ) {
            for (Vulkan12Feature feature : preferred) if (feature.supportedBy(supported)) enabled.add(feature);
        }

        private static void enableSupported(
                Set<Vulkan13Feature> required,
                EnumSet<Vulkan13Feature> enabled,
                VkPhysicalDeviceVulkan13Features supported,
                String label
        ) {
            for (Vulkan13Feature feature : required) {
                if (!feature.supportedBy(supported)) throw new IllegalStateException(label + " is unavailable: " + feature.streamlineName());
                enabled.add(feature);
            }
        }

        private static void enableOptional(
                Set<Vulkan13Feature> preferred,
                EnumSet<Vulkan13Feature> enabled,
                VkPhysicalDeviceVulkan13Features supported
        ) {
            for (Vulkan13Feature feature : preferred) if (feature.supportedBy(supported)) enabled.add(feature);
        }
    }

    private record QueueTopology(
            int primaryFamilyIndex,
            int primaryQueueCount,
            int opticalFlowFamilyIndex,
            int opticalFlowQueueCount,
            Map<String, top.ceroxe.rt.renderer.feature.VulkanFeatureQueueAllocation> featureQueues
    ) {
        private static QueueTopology resolve(
                MemoryStack stack,
                VkPhysicalDevice physicalDevice,
                VulkanQueueFamilyCapabilities primaryFamily,
                int coreQueueCount,
                VulkanQueueRequirements required,
                VulkanQueueRequirements preferred,
                Map<String, VulkanQueueRequirements> providerRequiredRequirements,
                Map<String, VulkanQueueRequirements> providerPreferredRequirements
        ) {
            int requiredPrimaryCount = Math.addExact(coreQueueCount, required.additionalGraphicsComputeQueues());
            if (requiredPrimaryCount > primaryFamily.availableQueues()) {
                throw new IllegalStateException(
                        "selected Vulkan graphics/compute family cannot satisfy required provider queues: required="
                                + requiredPrimaryCount + ", available=" + primaryFamily.availableQueues());
            }
            int preferredPrimaryCount = Math.addExact(requiredPrimaryCount, preferred.additionalGraphicsComputeQueues());
            int primaryQueueCount = preferredPrimaryCount <= primaryFamily.availableQueues()
                    ? preferredPrimaryCount : requiredPrimaryCount;
            OpticalFlowQueueFamily optical = OpticalFlowQueueFamily.select(
                    stack,
                    physicalDevice,
                    primaryFamily.index(),
                    required.additionalOpticalFlowQueues(),
                    preferred.additionalOpticalFlowQueues()
            );
            boolean preferredPrimaryAllocated = primaryQueueCount == preferredPrimaryCount;
            int requestedOpticalCount = Math.addExact(
                    required.additionalOpticalFlowQueues(),
                    preferred.additionalOpticalFlowQueues()
            );
            boolean preferredOpticalAllocated = optical != null
                    && optical.queueCount() == requestedOpticalCount;
            int allocatedOpticalQueues = optical == null ? 0 : optical.queueCount();
            Map<String, top.ceroxe.rt.renderer.feature.VulkanFeatureQueueAllocation> featureQueues =
                    VulkanProviderQueueAllocator.allocate(
                            primaryFamily.index(), coreQueueCount, primaryQueueCount,
                            optical == null ? -1 : optical.index(), allocatedOpticalQueues,
                            preferredPrimaryAllocated, preferredOpticalAllocated,
                            providerRequiredRequirements, providerPreferredRequirements
                    );
            return new QueueTopology(
                    primaryFamily.index(), primaryQueueCount,
                    optical == null ? -1 : optical.index(), optical == null ? 0 : optical.queueCount(), featureQueues
            );
        }
    }

    top.ceroxe.rt.renderer.feature.VulkanFeatureQueueAllocation featureQueueAllocation(String providerId) {
        return featureQueues.getOrDefault(Objects.requireNonNull(providerId, "providerId"),
                top.ceroxe.rt.renderer.feature.VulkanFeatureQueueAllocation.NONE);
    }

    private record OpticalFlowQueueFamily(int index, int queueCount) {
        private static OpticalFlowQueueFamily select(
                MemoryStack stack,
                VkPhysicalDevice physicalDevice,
                int primaryFamilyIndex,
                int requiredCount,
                int preferredCount
        ) {
            int requestedCount = Math.addExact(requiredCount, preferredCount);
            if (requestedCount == 0) return null;
            IntBuffer count = stack.ints(0);
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, null);
            VkQueueFamilyProperties.Buffer properties = VkQueueFamilyProperties.malloc(count.get(0), stack);
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, properties);
            for (int index = 0; index < properties.limit(); index++) {
                VkQueueFamilyProperties candidate = properties.get(index);
                int flags = candidate.queueFlags();
                boolean dedicatedOpticalFlow = index != primaryFamilyIndex
                        && (flags & NVOpticalFlow.VK_QUEUE_OPTICAL_FLOW_BIT_NV) != 0
                        && (flags & (VK10.VK_QUEUE_GRAPHICS_BIT | VK10.VK_QUEUE_COMPUTE_BIT | VK10.VK_QUEUE_TRANSFER_BIT)) == 0;
                if (!dedicatedOpticalFlow) continue;
                if (candidate.queueCount() >= requestedCount) return new OpticalFlowQueueFamily(index, requestedCount);
                if (candidate.queueCount() >= requiredCount) return new OpticalFlowQueueFamily(index, requiredCount);
            }
            if (requiredCount > 0) {
                throw new IllegalStateException("no dedicated Vulkan optical-flow queue family satisfies required provider queues");
            }
            return null;
        }
    }

    private static FloatBuffer queuePriorities(MemoryStack stack, int count) {
        FloatBuffer priorities = stack.mallocFloat(count);
        for (int index = 0; index < count; index++) priorities.put(1.0F);
        return priorities.flip();
    }

    private static long createAllocator(
            MemoryStack stack,
            VkInstance instance,
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            int apiVersion,
            boolean memoryBudgetEnabled
    ) {
        VmaVulkanFunctions functions = VmaVulkanFunctions.calloc(stack).set(instance, device);
        int allocatorFlags = Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT;
        if (memoryBudgetEnabled) allocatorFlags |= Vma.VMA_ALLOCATOR_CREATE_EXT_MEMORY_BUDGET_BIT;
        VmaAllocatorCreateInfo info = VmaAllocatorCreateInfo.calloc(stack)
                .flags(allocatorFlags).physicalDevice(physicalDevice)
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
        if (alignment <= 0)
            throw new IllegalStateException("invalid acceleration structure scratch alignment: " + alignment);
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
        VulkanFailures.check(result, stage);
    }

    VkPhysicalDevice physicalDevice() {
        return physicalDevice;
    }

    VkInstance instance() {
        return instance;
    }

    VkDevice device() {
        return device;
    }

    long allocator() {
        return allocator;
    }

    RtVulkanDeviceCapabilities.StablePhysicalDeviceProperties properties() {
        return properties;
    }

    List<String> enabledExtensions() {
        return enabledExtensions;
    }

    List<String> enabledInstanceExtensions() {
        return enabledInstanceExtensions;
    }

    int queueFamilyIndex() {
        return queueFamilyIndex;
    }

    int requestedQueueCount() {
        return requestedQueueCount;
    }

    boolean timelineSemaphoreEnabled() {
        return timelineSemaphoreEnabled;
    }

    int accelerationStructureScratchAlignment() {
        return accelerationStructureScratchAlignment;
    }

    boolean memoryBudgetEnabled() {
        return memoryBudgetEnabled;
    }

    boolean shaderExecutionReorderingEnabled() {
        return shaderExecutionReorderingEnabled;
    }

    boolean samplerAnisotropyEnabled() {
        return samplerAnisotropyEnabled;
    }


    /**
     * Releases the complete Vulkan bootstrap resource scope in reverse ownership order.
     */
    @Override
    public void close() {
        resources.close();
    }

    private record VulkanInstanceHandle(
            VkInstance instance,
            VulkanValidationFileLogger logger,
            List<String> enabledExtensions
    ) implements AutoCloseable {
        private VulkanInstanceHandle {
            Objects.requireNonNull(instance, "instance");
            enabledExtensions = List.copyOf(enabledExtensions);
        }
        @Override
        public void close() {
            try {
                if (logger != null) logger.destroyMessenger(instance);
            } finally {
                try {
                    VK10.vkDestroyInstance(instance, null);
                } finally {
                    if (logger != null) logger.close();
                }
            }
        }
    }

    private record SelectedPhysicalDevice(
            VkPhysicalDevice device,
            RtVulkanDeviceCapabilities.StablePhysicalDeviceProperties properties,
            Set<String> extensions) {
    }

}

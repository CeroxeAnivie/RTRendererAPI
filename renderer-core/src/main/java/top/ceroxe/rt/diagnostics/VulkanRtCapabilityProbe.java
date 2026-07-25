package top.ceroxe.rt.diagnostics;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.rt.device.VulkanQueueFamilyCapabilities;

import java.nio.IntBuffer;
import java.util.*;

/**
 * 延迟执行的 Vulkan RT 硬件 capability probe。
 *
 * <p>这个 probe 只创建临时 {@code VkInstance}，枚举 physical device 与 device extension，
 * 然后立刻销毁 instance。它不创建 logical device、queue、surface、swapchain 或任何 GPU
 * allocation，因此不能被当作 renderer backend 初始化；它只是进入真正 RTCore 之前的硬闸门。</p>
 */
public final class VulkanRtCapabilityProbe {
    private static final String APPLICATION_NAME = "RTRenderer";
    private static final String ENGINE_NAME = "RTRenderer-Probe";

    private VulkanRtCapabilityProbe() {
    }

    /**
     * Captures a point-in-time inventory of Vulkan devices and the exact feature/property set
     * required by this renderer. The temporary Vulkan instance is destroyed before this method
     * returns; consequently, the result contains values only and owns no native resources.
     *
     * @return a successful device inventory, or a fail-closed result describing the probe stage
     */
    public static Result capture() {
        int requestedApiVersion = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            requestedApiVersion = selectInstanceApiVersion();
            VkApplicationInfo applicationInfo = VkApplicationInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8(APPLICATION_NAME))
                    .applicationVersion(VK10.VK_MAKE_VERSION(0, 1, 0))
                    .pEngineName(stack.UTF8(ENGINE_NAME))
                    .engineVersion(VK10.VK_MAKE_VERSION(0, 1, 0))
                    .apiVersion(requestedApiVersion);

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(applicationInfo);

            PointerBuffer instanceHandle = stack.mallocPointer(1);
            int createResult = VK10.vkCreateInstance(createInfo, null, instanceHandle);
            if (createResult != VK10.VK_SUCCESS) {
                return Result.failed(
                        requestedApiVersion,
                        "vkCreateInstance",
                        createResult,
                        "unable to create temporary Vulkan instance"
                );
            }

            VkInstance instance = new VkInstance(instanceHandle.get(0), createInfo);
            try {
                return enumeratePhysicalDevices(stack, instance, requestedApiVersion);
            } finally {
                VK10.vkDestroyInstance(instance, null);
            }
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            /*
             * LWJGL MemoryStack reports native stack exhaustion as OutOfMemoryError.
             * A capability probe must fail closed instead of leaving the renderer in
             * VULKAN_CAPABILITY_PROBE forever.
             */
            return Result.failed(requestedApiVersion, "probe", Integer.MIN_VALUE, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private static int selectInstanceApiVersion() {
        try {
            int supported = VK.getInstanceVersionSupported();
            if (supported >= VK12.VK_API_VERSION_1_2) {
                return VK12.VK_API_VERSION_1_2;
            }
        } catch (RuntimeException | LinkageError ignored) {
            /*
             * Vulkan 1.0 loaders may not expose vkEnumerateInstanceVersion. Requesting 1.0 keeps
             * the probe compatible while device-level extension checks still decide RT readiness.
             */
        }
        return VK10.VK_API_VERSION_1_0;
    }

    private static Result enumeratePhysicalDevices(MemoryStack stack, VkInstance instance, int requestedApiVersion) {
        IntBuffer count = stack.ints(0);
        int countResult = VK10.vkEnumeratePhysicalDevices(instance, count, null);
        if (countResult != VK10.VK_SUCCESS) {
            return Result.failed(requestedApiVersion, "vkEnumeratePhysicalDevices.count", countResult,
                    "unable to count physical devices");
        }
        int deviceCount = count.get(0);
        if (deviceCount == 0) {
            return new Result(requestedApiVersion, true, false, "none", VK10.VK_SUCCESS,
                    "no Vulkan physical device reported by loader", List.of());
        }

        PointerBuffer devices = stack.mallocPointer(deviceCount);
        int enumerateResult = VK10.vkEnumeratePhysicalDevices(instance, count, devices);
        if (enumerateResult != VK10.VK_SUCCESS) {
            return Result.failed(requestedApiVersion, "vkEnumeratePhysicalDevices.devices", enumerateResult,
                    "unable to enumerate physical devices");
        }

        List<DeviceReport> reports = new ArrayList<>(deviceCount);
        for (int index = 0; index < deviceCount; index++) {
            VkPhysicalDevice device = new VkPhysicalDevice(devices.get(index), instance);
            try (MemoryStack deviceStack = MemoryStack.stackPush()) {
                reports.add(captureDeviceReport(deviceStack, device));
            }
        }
        return new Result(requestedApiVersion, true, false, "ok", VK10.VK_SUCCESS, "", reports);
    }

    private static DeviceReport captureDeviceReport(MemoryStack stack, VkPhysicalDevice device) {
        VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
        VK10.vkGetPhysicalDeviceProperties(device, properties);
        Set<String> extensions = enumerateDeviceExtensions(stack, device);

        boolean accelerationStructure = extensions.contains(KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME);
        boolean rayTracingPipeline = extensions.contains(KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME);
        boolean deferredHostOperations = extensions.contains(KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME);
        boolean pipelineLibrary = extensions.contains(KHRPipelineLibrary.VK_KHR_PIPELINE_LIBRARY_EXTENSION_NAME);
        boolean api12OrNewer = properties.apiVersion() >= VK12.VK_API_VERSION_1_2;
        boolean bufferDeviceAddress = api12OrNewer || extensions.contains(KHRBufferDeviceAddress.VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME);
        boolean spirv14 = api12OrNewer || extensions.contains(KHRSpirv14.VK_KHR_SPIRV_1_4_EXTENSION_NAME);
        boolean shaderFloatControls = api12OrNewer || extensions.contains(KHRShaderFloatControls.VK_KHR_SHADER_FLOAT_CONTROLS_EXTENSION_NAME);
        FeatureReport features = queryRtFeatures(stack, device, properties.apiVersion());
        PropertyReport deviceProperties = queryRtProperties(stack, device, properties);
        VulkanQueueFamilyCapabilities queueFamily = VulkanQueueFamilyCapabilities.select(stack, device);
        long deviceLocalMemoryBytes = queryDeviceLocalMemoryBytes(stack, device);
        boolean sdrRgba8Output = supportsExternalFrameFormat(
                stack, device, extensions, VK10.VK_FORMAT_R8G8B8A8_UNORM
        );
        boolean linearHdrRgba16fOutput = supportsExternalFrameFormat(
                stack, device, extensions, VK10.VK_FORMAT_R16G16B16A16_SFLOAT
        );

        return new DeviceReport(
                deviceProperties.stableId(),
                properties.deviceNameString(),
                properties.vendorID(),
                properties.deviceID(),
                properties.deviceType(),
                properties.apiVersion(),
                accelerationStructure,
                rayTracingPipeline,
                deferredHostOperations,
                pipelineLibrary,
                bufferDeviceAddress,
                spirv14,
                shaderFloatControls,
                extensions.contains(KHRExternalMemory.VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME)
                        || properties.apiVersion() >= VK11.VK_API_VERSION_1_1,
                extensions.contains(KHRExternalSemaphore.VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME)
                        || properties.apiVersion() >= VK11.VK_API_VERSION_1_1,
                sdrRgba8Output,
                linearHdrRgba16fOutput,
                extensions.contains(EXTMemoryBudget.VK_EXT_MEMORY_BUDGET_EXTENSION_NAME),
                queueFamily.gpuTimestamps(),
                deviceLocalMemoryBytes,
                features.accelerationStructure(),
                features.rayTracingPipeline(),
                features.bufferDeviceAddress(),
                features.shaderInt64(),
                deviceProperties.maxRayRecursionDepth(),
                deviceProperties.shaderGroupHandleSize(),
                deviceProperties.shaderGroupHandleAlignment(),
                deviceProperties.shaderGroupBaseAlignment(),
                deviceProperties.maxShaderGroupStride(),
                deviceProperties.maxRayDispatchInvocationCount(),
                deviceProperties.minAccelerationStructureScratchAlignment()
        );
    }

    private static boolean supportsExternalFrameFormat(
            MemoryStack stack,
            VkPhysicalDevice device,
            Set<String> extensions,
            int format
    ) {
        if (!extensions.contains(KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME)) {
            return false;
        }
        VkFormatProperties formatProperties = VkFormatProperties.calloc(stack);
        VK10.vkGetPhysicalDeviceFormatProperties(device, format, formatProperties);
        if ((formatProperties.optimalTilingFeatures() & VK10.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) == 0) {
            return false;
        }
        int handleType = VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
        VkPhysicalDeviceExternalImageFormatInfo externalInfo =
                VkPhysicalDeviceExternalImageFormatInfo.calloc(stack)
                        .sType$Default()
                        .handleType(handleType);
        VkPhysicalDeviceImageFormatInfo2 formatInfo = VkPhysicalDeviceImageFormatInfo2.calloc(stack)
                .sType$Default()
                .pNext(externalInfo)
                .format(format)
                .type(VK10.VK_IMAGE_TYPE_2D)
                .tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                .usage(VK10.VK_IMAGE_USAGE_STORAGE_BIT
                        | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                        | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                .flags(0);
        VkExternalImageFormatProperties externalProperties =
                VkExternalImageFormatProperties.calloc(stack).sType$Default();
        VkImageFormatProperties2 imageProperties = VkImageFormatProperties2.calloc(stack)
                .sType$Default()
                .pNext(externalProperties);
        if (VK11.vkGetPhysicalDeviceImageFormatProperties2(device, formatInfo, imageProperties)
                != VK10.VK_SUCCESS) {
            return false;
        }
        VkExternalMemoryProperties memory = externalProperties.externalMemoryProperties();
        return (memory.compatibleHandleTypes() & handleType) != 0
                && (memory.externalMemoryFeatures() & VK11.VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT) != 0;
    }

    private static FeatureReport queryRtFeatures(MemoryStack stack, VkPhysicalDevice device, int deviceApiVersion) {
        if (deviceApiVersion < VK11.VK_API_VERSION_1_1) {
            return FeatureReport.UNAVAILABLE;
        }
        try {
            VkPhysicalDeviceAccelerationStructureFeaturesKHR accelerationStructure =
                    VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default();
            VkPhysicalDeviceRayTracingPipelineFeaturesKHR rayTracingPipeline =
                    VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack).sType$Default();
            VkPhysicalDeviceBufferDeviceAddressFeatures bufferDeviceAddress =
                    VkPhysicalDeviceBufferDeviceAddressFeatures.calloc(stack).sType$Default();
            VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();

            features2.pNext(accelerationStructure.address());
            accelerationStructure.pNext(rayTracingPipeline.address());
            rayTracingPipeline.pNext(bufferDeviceAddress.address());

            VK11.vkGetPhysicalDeviceFeatures2(device, features2);
            return new FeatureReport(
                    accelerationStructure.accelerationStructure(),
                    rayTracingPipeline.rayTracingPipeline(),
                    bufferDeviceAddress.bufferDeviceAddress(),
                    features2.features().shaderInt64()
            );
        } catch (RuntimeException | LinkageError ex) {
            return FeatureReport.UNAVAILABLE;
        }
    }

    private static PropertyReport queryRtProperties(
            MemoryStack stack,
            VkPhysicalDevice device,
            VkPhysicalDeviceProperties legacyProperties
    ) {
        if (legacyProperties.apiVersion() < VK11.VK_API_VERSION_1_1) {
            return PropertyReport.unavailable(hex(legacyProperties.pipelineCacheUUID()));
        }
        VkPhysicalDeviceIDProperties identity = VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();
        VkPhysicalDeviceRayTracingPipelinePropertiesKHR rayTracing =
                VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack).sType$Default();
        VkPhysicalDeviceAccelerationStructurePropertiesKHR acceleration =
                VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
        VkPhysicalDeviceProperties2 properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
        properties.pNext(identity.address());
        identity.pNext(rayTracing.address());
        rayTracing.pNext(acceleration.address());
        VK11.vkGetPhysicalDeviceProperties2(device, properties);
        return new PropertyReport(
                hex(identity.deviceUUID()),
                rayTracing.maxRayRecursionDepth(),
                rayTracing.shaderGroupHandleSize(),
                rayTracing.shaderGroupHandleAlignment(),
                rayTracing.shaderGroupBaseAlignment(),
                rayTracing.maxShaderGroupStride(),
                Integer.toUnsignedLong(rayTracing.maxRayDispatchInvocationCount()),
                acceleration.minAccelerationStructureScratchOffsetAlignment()
        );
    }

    private static long queryDeviceLocalMemoryBytes(MemoryStack stack, VkPhysicalDevice device) {
        VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.calloc(stack);
        VK10.vkGetPhysicalDeviceMemoryProperties(device, memory);
        long total = 0L;
        for (int index = 0; index < memory.memoryHeapCount(); index++) {
            if ((memory.memoryHeaps(index).flags() & VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) == 0) continue;
            long size = memory.memoryHeaps(index).size();
            total = Long.MAX_VALUE - total < size ? Long.MAX_VALUE : total + size;
        }
        return total;
    }

    private static String hex(java.nio.ByteBuffer bytes) {
        StringBuilder result = new StringBuilder(bytes.remaining() * 2);
        for (int index = bytes.position(); index < bytes.limit(); index++) {
            result.append(Character.forDigit((bytes.get(index) >>> 4) & 0x0f, 16));
            result.append(Character.forDigit(bytes.get(index) & 0x0f, 16));
        }
        return result.toString();
    }

    private static Set<String> enumerateDeviceExtensions(MemoryStack stack, VkPhysicalDevice device) {
        IntBuffer count = stack.ints(0);
        int countResult = VK10.vkEnumerateDeviceExtensionProperties(device, (String) null, count, null);
        if (countResult != VK10.VK_SUCCESS || count.get(0) == 0) {
            return Set.of();
        }

        /*
         * Real desktop drivers can expose enough extensions that VkExtensionProperties[]
         * no longer belongs on LWJGL's small per-thread MemoryStack. Use an explicitly
         * freed native buffer so the probe scales with driver extension count without
         * leaving the background thread stuck in an uncaught OutOfMemoryError.
         */
        VkExtensionProperties.Buffer properties = VkExtensionProperties.calloc(count.get(0));
        try {
            int enumerateResult = VK10.vkEnumerateDeviceExtensionProperties(device, (String) null, count, properties);
            if (enumerateResult != VK10.VK_SUCCESS) {
                return Set.of();
            }

            Set<String> extensions = new HashSet<>(properties.remaining());
            for (int index = 0; index < properties.limit(); index++) {
                extensions.add(properties.get(index).extensionNameString());
            }
            return extensions;
        } finally {
            properties.free();
        }
    }

    private static String deviceTypeName(int deviceType) {
        return switch (deviceType) {
            case VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> "discrete";
            case VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> "integrated";
            case VK10.VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> "virtual";
            case VK10.VK_PHYSICAL_DEVICE_TYPE_CPU -> "cpu";
            default -> "other";
        };
    }

    private static String apiVersionString(int version) {
        if (version == 0) {
            return "unknown";
        }
        return VK10.VK_VERSION_MAJOR(version) + "." + VK10.VK_VERSION_MINOR(version) + "." + VK10.VK_VERSION_PATCH(version);
    }

    private static String vkResultName(int result) {
        return switch (result) {
            case VK10.VK_SUCCESS -> "VK_SUCCESS";
            case VK10.VK_NOT_READY -> "VK_NOT_READY";
            case VK10.VK_TIMEOUT -> "VK_TIMEOUT";
            case VK10.VK_ERROR_OUT_OF_HOST_MEMORY -> "VK_ERROR_OUT_OF_HOST_MEMORY";
            case VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY -> "VK_ERROR_OUT_OF_DEVICE_MEMORY";
            case VK10.VK_ERROR_INITIALIZATION_FAILED -> "VK_ERROR_INITIALIZATION_FAILED";
            case VK10.VK_ERROR_LAYER_NOT_PRESENT -> "VK_ERROR_LAYER_NOT_PRESENT";
            case VK10.VK_ERROR_EXTENSION_NOT_PRESENT -> "VK_ERROR_EXTENSION_NOT_PRESENT";
            case VK10.VK_ERROR_INCOMPATIBLE_DRIVER -> "VK_ERROR_INCOMPATIBLE_DRIVER";
            default -> Integer.toString(result);
        };
    }

    /**
     * Immutable outcome of a Vulkan capability probe.
     *
     * @param requestedInstanceApiVersion Vulkan API version requested from the loader
     * @param instanceCreated             whether the temporary Vulkan instance was created
     * @param failed                      whether the probe terminated before producing a complete inventory
     * @param failureStage                Vulkan operation or probe stage that failed, or an empty string
     * @param vkResult                    Vulkan result for the failed operation, or {@code VK_SUCCESS}
     * @param message                     supplementary failure or inventory message, or an empty string
     * @param devices                     immutable reports for every enumerated physical device
     * @param selectedStableId            explicitly selected device identity, or an empty string for policy selection
     */
    public record Result(
            int requestedInstanceApiVersion,
            boolean instanceCreated,
            boolean failed,
            String failureStage,
            int vkResult,
            String message,
            List<DeviceReport> devices,
            String selectedStableId
    ) {
        /**
         * Normalizes optional diagnostic text and snapshots the device inventory.
         *
         * @throws NullPointerException if {@code devices} is {@code null}
         */
        public Result {
            failureStage = failureStage == null ? "" : failureStage;
            message = message == null ? "" : message;
            devices = List.copyOf(devices);
            selectedStableId = selectedStableId == null ? "" : selectedStableId;
        }

        /**
         * Creates an unselected result whose preferred device is chosen by renderer policy.
         *
         * @param requestedInstanceApiVersion Vulkan API version requested from the loader
         * @param instanceCreated             whether the temporary Vulkan instance was created
         * @param failed                      whether the probe failed
         * @param failureStage                failing probe stage, or an empty string
         * @param vkResult                    Vulkan result associated with the outcome
         * @param message                     supplementary diagnostic message
         * @param devices                     physical-device reports to snapshot
         */
        public Result(
                int requestedInstanceApiVersion,
                boolean instanceCreated,
                boolean failed,
                String failureStage,
                int vkResult,
                String message,
                List<DeviceReport> devices
        ) {
            this(requestedInstanceApiVersion, instanceCreated, failed, failureStage, vkResult, message, devices, "");
        }

        /**
         * Creates a fail-closed result when no trustworthy device inventory is available.
         *
         * @param requestedInstanceApiVersion Vulkan API version requested from the loader
         * @param failureStage                failing Vulkan operation or probe stage
         * @param vkResult                    Vulkan result, or {@link Integer#MIN_VALUE} for a Java-side failure
         * @param message                     human-readable diagnostic context
         * @return failed result with an empty device inventory
         */
        public static Result failed(int requestedInstanceApiVersion, String failureStage, int vkResult, String message) {
            return new Result(requestedInstanceApiVersion, false, true, failureStage, vkResult, message, List.of(), "");
        }

        /**
         * Returns a copy pinned to one hardware-RT-capable device.
         *
         * @param stableId stable device identity reported by {@link DeviceReport#stableId()}
         * @return a result whose preferred device is the requested device
         * @throws NullPointerException     if {@code stableId} is {@code null}
         * @throws IllegalArgumentException if the identity is blank, absent, or not RT-capable
         */
        public Result select(String stableId) {
            String checked = java.util.Objects.requireNonNull(stableId, "stableId");
            if (checked.isBlank()) throw new IllegalArgumentException("stableId must not be blank");
            DeviceReport selected = devices.stream()
                    .filter(DeviceReport::hardwareRayTracingReady)
                    .filter(device -> checked.equals(device.stableId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("selected RT GPU is not available: " + checked));
            return new Result(requestedInstanceApiVersion, instanceCreated, failed, failureStage,
                    vkResult, message, devices, selected.stableId());
        }

        /**
         * Reports whether at least one eligible device can be selected.
         *
         * @return whether at least one eligible device can be selected
         */
        public boolean hardwareRayTracingReady() {
            return preferredDevice() != null;
        }

        /**
         * Selects the explicitly requested device, or otherwise the best eligible discrete GPU
         * by local-memory capacity with a deterministic identity tie-breaker.
         *
         * @return preferred eligible device, or {@code null} when none is ready
         */
        public DeviceReport preferredDevice() {
            if (!selectedStableId.isBlank()) {
                return devices.stream()
                        .filter(DeviceReport::hardwareRayTracingReady)
                        .filter(device -> selectedStableId.equals(device.stableId()))
                        .findFirst()
                        .orElse(null);
            }
            return devices.stream()
                    .filter(DeviceReport::hardwareRayTracingReady)
                    .max(Comparator.comparing(DeviceReport::discreteGpu)
                            .thenComparingLong(DeviceReport::deviceLocalMemoryBytes)
                            .thenComparing(DeviceReport::stableId, Comparator.reverseOrder()))
                    .orElse(null);
        }

        /**
         * Formats the probe outcome for startup diagnostics.
         *
         * @return stable single-line diagnostic summary of the probe outcome
         */
        public String summary() {
            DeviceReport preferred = preferredDevice();
            if (failed) {
                return "vulkanRtCapability(instance=" + instanceCreated
                        + ", stage=" + failureStage
                        + ", result=" + vkResultName(vkResult)
                        + ", message=" + message + ")";
            }
            return "vulkanRtCapability(instance=" + instanceCreated
                    + ", requestedApi=" + apiVersionString(requestedInstanceApiVersion)
                    + ", devices=" + devices.size()
                    + ", hardwareRtReady=" + hardwareRayTracingReady()
                    + ", preferred=" + (preferred == null ? "none" : preferred.summary())
                    + (message.isBlank() ? "" : ", message=" + message)
                    + ")";
        }
    }

    /**
     * Immutable Vulkan physical-device capability and limit inventory.
     *
     * @param stableId                                 stable physical-device identity, normally derived from its UUID
     * @param name                                     Vulkan device display name
     * @param vendorId                                 PCI vendor identifier
     * @param deviceId                                 vendor device identifier
     * @param deviceType                               Vulkan physical-device type
     * @param apiVersion                               supported Vulkan API version
     * @param accelerationStructure                    acceleration-structure extension availability
     * @param rayTracingPipeline                       ray-tracing-pipeline extension availability
     * @param deferredHostOperations                   deferred-host-operations extension availability
     * @param pipelineLibrary                          pipeline-library extension availability
     * @param bufferDeviceAddress                      buffer-device-address extension/core availability
     * @param spirv14                                  SPIR-V 1.4 extension/core availability
     * @param shaderFloatControls                      shader-float-controls extension/core availability
     * @param externalMemory                           external-memory availability for public GPU frames
     * @param externalSemaphore                        external-semaphore availability
     * @param sdrRgba8Output                           exportable SDR RGBA8 storage-image availability
     * @param linearHdrRgba16fOutput                   exportable linear HDR RGBA16F storage-image availability
     * @param memoryBudget                             memory-budget telemetry availability
     * @param gpuTimestamps                            timestamp-query availability
     * @param deviceLocalMemoryBytes                   total device-local heap capacity in bytes
     * @param accelerationStructureFeature             acceleration-structure feature enablement
     * @param rayTracingPipelineFeature                ray-tracing-pipeline feature enablement
     * @param bufferDeviceAddressFeature               buffer-device-address feature enablement
     * @param shaderInt64Feature                       64-bit integer shader feature enablement
     * @param maxRayRecursionDepth                     maximum ray-pipeline recursion depth
     * @param shaderGroupHandleSize                    shader-group handle size in bytes
     * @param shaderGroupHandleAlignment               shader-group handle alignment in bytes
     * @param shaderGroupBaseAlignment                 shader-binding-table base alignment in bytes
     * @param maxShaderGroupStride                     maximum shader-group stride in bytes
     * @param maxRayDispatchInvocationCount            maximum invocations in one ray dispatch
     * @param minAccelerationStructureScratchAlignment required scratch-address alignment in bytes
     */
    public record DeviceReport(
            String stableId,
            String name,
            int vendorId,
            int deviceId,
            int deviceType,
            int apiVersion,
            boolean accelerationStructure,
            boolean rayTracingPipeline,
            boolean deferredHostOperations,
            boolean pipelineLibrary,
            boolean bufferDeviceAddress,
            boolean spirv14,
            boolean shaderFloatControls,
            boolean externalMemory,
            boolean externalSemaphore,
            boolean sdrRgba8Output,
            boolean linearHdrRgba16fOutput,
            boolean memoryBudget,
            boolean gpuTimestamps,
            long deviceLocalMemoryBytes,
            boolean accelerationStructureFeature,
            boolean rayTracingPipelineFeature,
            boolean bufferDeviceAddressFeature,
            boolean shaderInt64Feature,
            int maxRayRecursionDepth,
            int shaderGroupHandleSize,
            int shaderGroupHandleAlignment,
            int shaderGroupBaseAlignment,
            int maxShaderGroupStride,
            long maxRayDispatchInvocationCount,
            int minAccelerationStructureScratchAlignment
    ) {
        /**
         * Creates the reduced capability report used by deterministic unit fixtures.
         * Production probing uses the canonical constructor and supplies all native limits.
         *
         * @param name                         device display name
         * @param vendorId                     PCI vendor identifier
         * @param deviceId                     vendor device identifier
         * @param deviceType                   Vulkan physical-device type
         * @param apiVersion                   supported Vulkan API version
         * @param accelerationStructure        acceleration-structure extension availability
         * @param rayTracingPipeline           ray-tracing-pipeline extension availability
         * @param deferredHostOperations       deferred-host-operations extension availability
         * @param pipelineLibrary              pipeline-library extension availability
         * @param bufferDeviceAddress          buffer-device-address availability
         * @param spirv14                      SPIR-V 1.4 availability
         * @param shaderFloatControls          shader-float-controls availability
         * @param accelerationStructureFeature acceleration-structure feature enablement
         * @param rayTracingPipelineFeature    ray-tracing-pipeline feature enablement
         * @param bufferDeviceAddressFeature   buffer-device-address feature enablement
         */
        public DeviceReport(
                String name,
                int vendorId,
                int deviceId,
                int deviceType,
                int apiVersion,
                boolean accelerationStructure,
                boolean rayTracingPipeline,
                boolean deferredHostOperations,
                boolean pipelineLibrary,
                boolean bufferDeviceAddress,
                boolean spirv14,
                boolean shaderFloatControls,
                boolean accelerationStructureFeature,
                boolean rayTracingPipelineFeature,
                boolean bufferDeviceAddressFeature
        ) {
            this("test-" + Integer.toUnsignedString(vendorId, 16) + "-"
                            + Integer.toUnsignedString(deviceId, 16) + "-" + name,
                    name, vendorId, deviceId, deviceType, apiVersion,
                    accelerationStructure, rayTracingPipeline, deferredHostOperations, pipelineLibrary,
                    bufferDeviceAddress, spirv14, shaderFloatControls,
                    false, false, false, false, false, false, 0L,
                    accelerationStructureFeature, rayTracingPipelineFeature, bufferDeviceAddressFeature, true,
                    1, 1, 1, 1, 1, 1L, 1);
        }

        /**
         * Tests the complete backend hardware contract.
         *
         * @return whether every extension, feature, and non-zero limit required by the backend is present
         */
        public boolean hardwareRayTracingReady() {
            return accelerationStructure
                    && accelerationStructureFeature
                    && rayTracingPipeline
                    && rayTracingPipelineFeature
                    && deferredHostOperations
                    && pipelineLibrary
                    && bufferDeviceAddress
                    && bufferDeviceAddressFeature
                    && shaderInt64Feature
                    && spirv14
                    && shaderFloatControls
                    && maxRayRecursionDepth > 0
                    && shaderGroupHandleSize > 0
                    && shaderGroupHandleAlignment > 0
                    && shaderGroupBaseAlignment > 0
                    && maxShaderGroupStride > 0
                    && maxRayDispatchInvocationCount > 0L
                    && minAccelerationStructureScratchAlignment > 0;
        }

        /**
         * Tests the Vulkan physical-device classification.
         *
         * @return whether Vulkan classifies this physical device as a discrete GPU
         */
        public boolean discreteGpu() {
            return deviceType == VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;
        }

        /**
         * Formats this device's relevant capabilities and limits.
         *
         * @return stable single-line capability summary suitable for startup diagnostics
         */
        public String summary() {
            return name + "{uuid=" + stableId + ", type=" + deviceTypeName(deviceType)
                    + ", vendor=0x" + Integer.toHexString(vendorId)
                    + ", device=0x" + Integer.toHexString(deviceId)
                    + ", api=" + apiVersionString(apiVersion)
                    + ", as=" + accelerationStructure
                    + "/" + accelerationStructureFeature
                    + ", rtp=" + rayTracingPipeline
                    + "/" + rayTracingPipelineFeature
                    + ", bda=" + bufferDeviceAddress
                    + "/" + bufferDeviceAddressFeature
                    + ", shaderInt64=" + shaderInt64Feature
                    + ", sdrRgba8=" + sdrRgba8Output
                    + ", linearHdrRgba16f=" + linearHdrRgba16fOutput
                    + ", localMemory=" + deviceLocalMemoryBytes
                    + ", ready=" + hardwareRayTracingReady() + "}";
        }
    }

    private record FeatureReport(
            boolean accelerationStructure,
            boolean rayTracingPipeline,
            boolean bufferDeviceAddress,
            boolean shaderInt64
    ) {
        private static final FeatureReport UNAVAILABLE = new FeatureReport(false, false, false, false);
    }

    private record PropertyReport(
            String stableId,
            int maxRayRecursionDepth,
            int shaderGroupHandleSize,
            int shaderGroupHandleAlignment,
            int shaderGroupBaseAlignment,
            int maxShaderGroupStride,
            long maxRayDispatchInvocationCount,
            int minAccelerationStructureScratchAlignment
    ) {
        private static PropertyReport unavailable(String fallbackId) {
            return new PropertyReport(fallbackId, 0, 0, 0, 0, 0, 0L, 0);
        }
    }
}

package top.ceroxe.mcvulkanrt.diagnostics;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRBufferDeviceAddress;
import org.lwjgl.vulkan.KHRDeferredHostOperations;
import org.lwjgl.vulkan.KHRPipelineLibrary;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.KHRShaderFloatControls;
import org.lwjgl.vulkan.KHRSpirv14;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceBufferDeviceAddressFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 延迟执行的 Vulkan RT 硬件 capability probe。
 *
 * <p>这个 probe 只创建临时 {@code VkInstance}，枚举 physical device 与 device extension，
 * 然后立刻销毁 instance。它不创建 logical device、queue、surface、swapchain 或任何 GPU
 * allocation，因此不能被当作 renderer backend 初始化；它只是进入真正 RTCore 之前的硬闸门。</p>
 */
public final class VulkanRtCapabilityProbe {
    private static final String APPLICATION_NAME = "MCVulkanRT";
    private static final String ENGINE_NAME = "MCVulkanRT-Probe";
    private static final int NVIDIA_VENDOR_ID = 0x10DE;

    private VulkanRtCapabilityProbe() {
    }

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

        return new DeviceReport(
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
                features.accelerationStructure(),
                features.rayTracingPipeline(),
                features.bufferDeviceAddress()
        );
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
                    bufferDeviceAddress.bufferDeviceAddress()
            );
        } catch (RuntimeException | LinkageError ex) {
            return FeatureReport.UNAVAILABLE;
        }
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

    public record Result(
            int requestedInstanceApiVersion,
            boolean instanceCreated,
            boolean failed,
            String failureStage,
            int vkResult,
            String message,
            List<DeviceReport> devices
    ) {
        public Result {
            failureStage = failureStage == null ? "" : failureStage;
            message = message == null ? "" : message;
            devices = List.copyOf(devices);
        }

        public static Result failed(int requestedInstanceApiVersion, String failureStage, int vkResult, String message) {
            return new Result(requestedInstanceApiVersion, false, true, failureStage, vkResult, message, List.of());
        }

        public boolean hardwareRayTracingReady() {
            return preferredDevice() != null;
        }

        public DeviceReport preferredDevice() {
            DeviceReport nvidiaDiscrete = devices.stream()
                    .filter(DeviceReport::hardwareRayTracingReady)
                    .filter(DeviceReport::discreteGpu)
                    .filter(DeviceReport::nvidiaGpu)
                    .findFirst()
                    .orElse(null);
            if (nvidiaDiscrete != null) {
                return nvidiaDiscrete;
            }
            return devices.stream()
                    .filter(DeviceReport::hardwareRayTracingReady)
                    .filter(DeviceReport::discreteGpu)
                    .findFirst()
                    .orElse(null);
        }

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

    public record DeviceReport(
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
        public boolean hardwareRayTracingReady() {
            return accelerationStructure
                    && accelerationStructureFeature
                    && rayTracingPipeline
                    && rayTracingPipelineFeature
                    && deferredHostOperations
                    && pipelineLibrary
                    && bufferDeviceAddress
                    && bufferDeviceAddressFeature
                    && spirv14
                    && shaderFloatControls;
        }

        public boolean discreteGpu() {
            return deviceType == VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;
        }

        public boolean nvidiaGpu() {
            return vendorId == NVIDIA_VENDOR_ID;
        }

        public String summary() {
            return name + "{type=" + deviceTypeName(deviceType)
                    + ", vendor=0x" + Integer.toHexString(vendorId)
                    + ", device=0x" + Integer.toHexString(deviceId)
                    + ", api=" + apiVersionString(apiVersion)
                    + ", as=" + accelerationStructure
                    + "/" + accelerationStructureFeature
                    + ", rtp=" + rayTracingPipeline
                    + "/" + rayTracingPipelineFeature
                    + ", bda=" + bufferDeviceAddress
                    + "/" + bufferDeviceAddressFeature
                    + ", ready=" + hardwareRayTracingReady() + "}";
        }
    }

    private record FeatureReport(
            boolean accelerationStructure,
            boolean rayTracingPipeline,
            boolean bufferDeviceAddress
    ) {
        private static final FeatureReport UNAVAILABLE = new FeatureReport(false, false, false);
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
}

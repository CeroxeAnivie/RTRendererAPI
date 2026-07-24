package top.ceroxe.mcvulkanrt.renderer.rt.device;

import top.ceroxe.mcvulkanrt.diagnostics.VulkanRtCapabilityProbe;

import java.util.List;

/**
 * Keeps production-device admission rules separate from runtime state-machine
 * tests. The device subsystem is responsible for choosing a usable GPU before
 * any pipeline or scene resource can exist.
 */
public final class VulkanRtDeviceCapabilitySelfTest {
    private static final int VK_SUCCESS = 0;
    private static final int VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU = 1;
    private static final int VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU = 2;
    private static final int VK_API_VERSION_1_2 = makeApiVersion(1, 2, 0);
    private static final int NVIDIA_VENDOR_ID = 0x10DE;
    private static final int AMD_VENDOR_ID = 0x1002;
    private static final int INTEL_VENDOR_ID = 0x8086;

    private VulkanRtDeviceCapabilitySelfTest() {
    }

    public static void main(String[] args) {
        prefersNvidiaDiscreteRtDevice();
        rejectsIntegratedRtDeviceForProduction();
        System.out.println("VulkanRtDeviceCapabilitySelfTest passed");
    }

    private static void prefersNvidiaDiscreteRtDevice() {
        VulkanRtCapabilityProbe.Result result = capability(List.of(
                device("AMD RT Device", AMD_VENDOR_ID, 10, VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU, true),
                device("NVIDIA RT Device", NVIDIA_VENDOR_ID, 20, VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU, true)
        ));

        require(result.hardwareRayTracingReady(), "discrete RT capability should be production ready");
        require("NVIDIA RT Device".equals(result.preferredDevice().name()), "NVIDIA discrete RT device should be preferred");
    }

    private static void rejectsIntegratedRtDeviceForProduction() {
        VulkanRtCapabilityProbe.Result result = capability(List.of(
                device("Integrated RT Device", INTEL_VENDOR_ID, 30, VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU, true)
        ));

        require(!result.hardwareRayTracingReady(), "integrated GPU must not satisfy the production RT gate");
        require(result.preferredDevice() == null, "integrated GPU must not become the preferred production RT device");
    }

    private static VulkanRtCapabilityProbe.Result capability(List<VulkanRtCapabilityProbe.DeviceReport> devices) {
        return new VulkanRtCapabilityProbe.Result(
                VK_API_VERSION_1_2, true, false, "ok", VK_SUCCESS, "", devices
        );
    }

    private static VulkanRtCapabilityProbe.DeviceReport device(
            String name,
            int vendorId,
            int deviceId,
            int deviceType,
            boolean ready
    ) {
        return new VulkanRtCapabilityProbe.DeviceReport(
                name, vendorId, deviceId, deviceType, VK_API_VERSION_1_2,
                ready, ready, ready, ready, ready, ready, ready, ready, ready, ready
        );
    }

    private static int makeApiVersion(int major, int minor, int patch) {
        return (major << 22) | (minor << 12) | patch;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

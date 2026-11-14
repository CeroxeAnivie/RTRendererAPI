package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

/** Supplies the runtime borrowed by the isolated generic-command execution lane. */
interface VulkanGenericCommandRuntimeProvider {
    /** Returns the still-open runtime whose lifetime remains owned by the rendering session. */
    VulkanDeviceRuntime genericCommandRuntime();
}

package top.ceroxe.rt.renderer.feature;

import top.ceroxe.rt.renderer.api.RendererConfig;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

import java.util.Objects;

/**
 * Device-bound context supplied once when a feature session is opened.
 *
 * <p>The runtime is borrowed and remains owned by {@code renderer-core}; providers must never
 * destroy its Vulkan instance, device, allocator, or queues.</p>
 *
 * @param device borrowed open Vulkan device runtime
 * @param configuration immutable renderer policy used for feature negotiation
 */
public record VulkanFeatureOpenContext(
        VulkanDeviceRuntime device,
        RendererConfig configuration
) {
    /** Validates the borrowed runtime and immutable configuration. */
    public VulkanFeatureOpenContext {
        device = Objects.requireNonNull(device, "device");
        configuration = Objects.requireNonNull(configuration, "configuration");
    }
}

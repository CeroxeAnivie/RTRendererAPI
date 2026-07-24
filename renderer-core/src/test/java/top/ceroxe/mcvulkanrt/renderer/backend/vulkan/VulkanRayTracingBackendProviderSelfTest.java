package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import top.ceroxe.mcvulkanrt.renderer.spi.RayTracingBackendProvider;

import java.util.ServiceLoader;

/** Verifies that the professional public backend entry point is discoverable without internals. */
public final class VulkanRayTracingBackendProviderSelfTest {
    private VulkanRayTracingBackendProviderSelfTest() {
    }

    public static void main(String[] arguments) {
        RayTracingBackendProvider provider = ServiceLoader.load(RayTracingBackendProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(candidate -> candidate.descriptor().id().equals("vulkan-rt"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("vulkan-rt backend provider was not discovered"));
        RayTracingBackendProvider.Descriptor descriptor = provider.descriptor();
        if (descriptor.apiMajor() != RayTracingBackendProvider.API_MAJOR
                || descriptor.priority() <= 0) {
            throw new AssertionError("Vulkan backend descriptor is invalid: " + descriptor);
        }
        System.out.println("VulkanRayTracingBackendProviderSelfTest passed: " + descriptor);
    }
}

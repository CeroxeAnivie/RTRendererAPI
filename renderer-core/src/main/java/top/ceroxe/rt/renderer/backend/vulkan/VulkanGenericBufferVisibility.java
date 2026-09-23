package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.HashSet;
import java.util.Set;
import top.ceroxe.rt.renderer.api.ResourceGenerationKey;

final class VulkanGenericBufferVisibility {
    private final Set<ResourceGenerationKey> allConsumers = new HashSet<>();
    private final Set<ResourceGenerationKey> accelerationInputs = new HashSet<>();

    void written(ResourceGenerationKey resource) {
        allConsumers.remove(resource);
        accelerationInputs.remove(resource);
    }

    boolean readable(ResourceGenerationKey resource) {
        return allConsumers.contains(resource);
    }

    boolean accelerationInputReadable(ResourceGenerationKey resource) {
        return readable(resource) || accelerationInputs.contains(resource);
    }

    void allConsumersVisible(ResourceGenerationKey resource) {
        allConsumers.add(resource);
    }

    void accelerationInputVisible(ResourceGenerationKey resource) {
        accelerationInputs.add(resource);
    }
}

package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.RenderResourceId;
import top.ceroxe.rt.renderer.api.ResourceGenerationKey;
import top.ceroxe.rt.renderer.api.ResourceVersion;

public final class VulkanGenericBufferVisibilitySelfTest {
    public static void main(String[] args) {
        var state = new VulkanGenericBufferVisibility();
        var vertices = key(1, 0);
        var indices = key(2, 0);
        var replacement = key(1, 1);
        require(!state.readable(vertices) && !state.accelerationInputReadable(vertices), "new resource is visible");
        state.accelerationInputVisible(vertices);
        require(state.accelerationInputReadable(vertices), "AS input dependency lost");
        require(!state.readable(vertices), "AS input dependency incorrectly covers shader/copy consumers");
        require(!state.accelerationInputReadable(indices), "visibility leaked to another buffer");
        require(!state.accelerationInputReadable(replacement), "visibility leaked to another generation");
        state.allConsumersVisible(indices);
        require(state.readable(indices) && state.accelerationInputReadable(indices), "full dependency lost");
        state.written(vertices);
        require(!state.accelerationInputReadable(vertices), "repeated upload retained AS visibility");
        require(state.readable(indices), "write invalidated unrelated resource");
        state.allConsumersVisible(vertices);
        state.accelerationInputVisible(vertices);
        state.written(vertices);
        require(!state.readable(vertices) && !state.accelerationInputReadable(vertices), "write retained stale visibility");
        state.written(vertices);
        require(!state.readable(vertices), "repeated invalidation fabricated visibility");
        System.out.println("VulkanGenericBufferVisibilitySelfTest passed: consumer scopes, generations, repeated writes");
    }

    private static ResourceGenerationKey key(long id, long version) {
        return new ResourceGenerationKey(new RenderResourceId(id), new ResourceVersion(version));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

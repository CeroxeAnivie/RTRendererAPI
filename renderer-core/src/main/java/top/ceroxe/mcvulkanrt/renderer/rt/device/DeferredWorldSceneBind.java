package top.ceroxe.mcvulkanrt.renderer.rt.device;

import top.ceroxe.mcvulkanrt.renderer.RendererFrameUpdate;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtWorldTlasCache;

import java.util.Objects;

/** Immutable deferred-bind decision; it deliberately owns no Vulkan resource. */
record DeferredWorldSceneBind(
        RtWorldTlasCache.WorldTlasUpdate worldTlasUpdate,
        String bindReason,
        long deferrals
) {
    DeferredWorldSceneBind {
        worldTlasUpdate = Objects.requireNonNull(worldTlasUpdate, "worldTlasUpdate");
        bindReason = Objects.requireNonNull(bindReason, "bindReason");
        if (deferrals < 0L) {
            throw new IllegalArgumentException("deferrals must not be negative");
        }
    }

    String bindReason(RendererFrameUpdate update) {
        Objects.requireNonNull(update, "update");
        return bindReason;
    }

    DeferredWorldSceneBind withAdditionalDeferral() {
        return new DeferredWorldSceneBind(worldTlasUpdate, bindReason, Math.incrementExact(deferrals));
    }
}

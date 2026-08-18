package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.FrameCompositionEvidence;
import top.ceroxe.rt.renderer.api.FrameCompositionProvider;
import top.ceroxe.rt.renderer.api.FrameCompositionRequest;

import java.util.Objects;
import java.util.Optional;

/** Narrow public-extension adapter; lifecycle locking remains owned by {@link VulkanRendererHost}. */
final class VulkanFrameCompositionProvider implements FrameCompositionProvider {
    private final VulkanRendererHost host;

    VulkanFrameCompositionProvider(VulkanRendererHost host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    @Override
    public FrameCompositionEvidence compose(FrameCompositionRequest request) {
        return host.composeFrame(Objects.requireNonNull(request, "request"));
    }

    @Override
    public Optional<FrameCompositionEvidence> compositionEvidence(long frameSequence) {
        if (frameSequence < 0L) throw new IllegalArgumentException("frameSequence must not be negative");
        return host.compositionEvidence(frameSequence);
    }
}
